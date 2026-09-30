package kr.dongmyeon.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kr.dongmyeon.core.Achievement
import kr.dongmyeon.core.Fix
import kr.dongmyeon.core.Geo
import kr.dongmyeon.core.Hit
import kr.dongmyeon.core.JudgeState
import kr.dongmyeon.core.LastPoint
import kr.dongmyeon.core.Method
import kr.dongmyeon.core.RegionIndex
import kr.dongmyeon.core.VisitJudge
import java.io.File
import java.util.Calendar
import java.util.UUID

/** [VisitRepository.importBackup] 결과 요약 */
data class BackupImportResult(
    val achievedCount: Int,
    val tripsCount: Int,
    val photosCount: Int,
    val skippedPhotos: Int,
)

/** 최근 수신 위치 정보(화면 표시용) */
data class LiveStatus(
    val lastFix: Fix? = null,
    val lastFixValid: Boolean = false,
    val currentRegionName: String? = null,
    val receivedCount: Int = 0,
    val validCount: Int = 0,
)

class VisitRepository(private val context: Context, private val db: AppDatabase) {
    private val dao = db.visitDao()
    private val mutex = Mutex()
    private var index: RegionIndex? = null
    private var judge: VisitJudge? = null

    // 현재 진행 중인 "여행" 구간의 거리·신규지역 누적치.
    // 버튼으로 시작/정지하는 게 아니라, 위치가 들어오는데 진행 중인 구간이 없으면 자동으로 열리고
    // 20분 이상 새 위치가 안 들어오면(=정지 중, 100m 거리 필터라 안 움직이면 위치 자체가 안 찍힘)
    // 자동으로 닫힌다. 앱 프로세스가 완전히 재시작되면 이 값은 초기화된다(진행 중이던 구간은 유실될 수 있음).
    private var sessionStart: Long? = null
    private var sessionDistance = 0.0
    private var sessionNewRegions = 0
    private var sessionLastFix: Fix? = null
    private var lastFixWallClock: Long? = null

    private val _live = MutableStateFlow(LiveStatus())
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    val achieved: Flow<List<AchievedEntity>> = dao.observeAchieved()
    val needsReview: Flow<List<NeedsReviewEntity>> = dao.observeNeedsReview()
    val trips: Flow<List<TripEntity>> = dao.observeTrips()
    val routePoints: Flow<List<RoutePointEntity>> = dao.observeRoutePoints()

    /** 내장 경계 파일 로드(최초 1회) */
    suspend fun regions(): RegionIndex = mutex.withLock { loadIndexLocked() }

    private suspend fun loadIndexLocked(): RegionIndex = index ?: withContext(Dispatchers.Default) {
        val text = context.assets.open(REGIONS_ASSET).bufferedReader().use { it.readText() }
        RegionIndex.fromGeoJson(text)
    }.also { index = it }

    private suspend fun judgeLocked(): VisitJudge = judge ?: run {
        val idx = loadIndexLocked()
        val state = JudgeState(
            achieved = dao.allAchieved().mapTo(HashSet()) { it.code },
            hits = dao.allHits().associateTo(HashMap()) { it.code to Hit(it.count, it.firstTime) },
            last = dao.lastPoint()?.let {
                LastPoint(Fix(it.lat, it.lng, it.accuracyM, it.timeMillis), it.regionCode, it.enteredFromOutside)
            },
            needsReview = dao.allNeedsReview().mapTo(HashSet()) { it.code },
        )
        VisitJudge(idx, state)
    }.also { judge = it }

    /** 위치 한 점 처리 → 새로 확정/확인 필요된 지역 */
    suspend fun onFix(fix: Fix): List<Achievement> = mutex.withLock {
        val j = judgeLocked()
        val valid = j.isValid(fix)
        val found = j.process(fix)
        val region = if (valid) index?.find(fix.lat, fix.lng) else null
        _live.value = _live.value.let {
            it.copy(
                lastFix = fix,
                lastFixValid = valid,
                currentRegionName = if (valid) region?.name ?: "(읍면동 영역 밖)" else it.currentRegionName,
                receivedCount = it.receivedCount + 1,
                validCount = it.validCount + if (valid) 1 else 0,
            )
        }
        if (!valid) return@withLock found

        val confirmed = found.filter { it.method != Method.NEEDS_REVIEW }
        val flagged = found.filter { it.method == Method.NEEDS_REVIEW }

        // 진행 중인 여행이 없으면 이 점이 새 여행의 시작이다(버튼 없이 자동으로 연다).
        if (sessionStart == null) {
            sessionStart = fix.timeMillis
            sessionDistance = 0.0
            sessionNewRegions = 0
            sessionLastFix = null
        }
        lastFixWallClock = System.currentTimeMillis()
        sessionLastFix?.let { prev -> sessionDistance += Geo.distanceMeters(prev.lat, prev.lng, fix.lat, fix.lng) }
        sessionLastFix = fix
        sessionNewRegions += confirmed.size
        dao.insertRoutePoint(RoutePointEntity(lat = fix.lat, lng = fix.lng, timeMillis = fix.timeMillis))

        val st = j.state
        dao.saveStep(
            newAchieved = confirmed.map {
                AchievedEntity(it.code, it.name, it.sido, it.sgg, it.firstVisitedAt, it.method.name)
            },
            hits = listOfNotNull(region?.code?.let { c -> st.hits[c]?.let { HitEntity(c, it.count, it.firstTime) } }),
            removedHits = confirmed.map { it.code },
            needsReview = flagged.map { NeedsReviewEntity(it.code, it.name, it.sido, it.sgg, it.firstVisitedAt) },
            resolvedReview = confirmed.map { it.code },
            last = st.last?.let {
                LastPointEntity(
                    lat = it.fix.lat, lng = it.fix.lng, accuracyM = it.fix.accuracyM ?: 0f,
                    timeMillis = it.fix.timeMillis, regionCode = it.regionCode,
                    enteredFromOutside = it.enteredFromOutside,
                )
            },
        )
        found
    }

    /** 기록 시작 시: 이전 기록의 마지막 점과 선으로 잇지 않음 */
    suspend fun breakTrack() = mutex.withLock {
        judge?.breakTrack()
        dao.clearLastPoint()
    }

    /** "확인 필요" 지역을 실제 방문으로 확정 */
    suspend fun confirmReview(code: String): Achievement? = mutex.withLock {
        val a = judgeLocked().confirmReview(code, System.currentTimeMillis()) ?: return@withLock null
        dao.confirmReview(AchievedEntity(a.code, a.name, a.sido, a.sgg, a.firstVisitedAt, a.method.name))
        a
    }

    /** "확인 필요" 지역을 무시 */
    suspend fun dismissReview(code: String) = mutex.withLock {
        judgeLocked().dismissReview(code)
        dao.deleteNeedsReview(listOf(code))
    }

    /**
     * 현장에서 "직접 방문" 버튼으로 확정.
     * 아직 미달성이면 새로 달성(ON_SITE)시키고, 이미 머무름/통과로 달성된 곳이면
     * 최초 방문일은 그대로 두고 방문 방식만 "직접 방문"으로 올려 붙인다.
     */
    suspend fun markOnSite(code: String): Achievement? = mutex.withLock {
        val existing = dao.getAchieved(code)
        if (existing != null) {
            if (existing.method == Method.ON_SITE.name) return@withLock null // 이미 직접 방문으로 확인됨
            dao.updateMethod(code, Method.ON_SITE.name)
            return@withLock Achievement(existing.code, existing.name, existing.sido, existing.sgg, existing.firstVisitedAt, Method.ON_SITE)
        }
        val a = judgeLocked().markOnSite(code, System.currentTimeMillis()) ?: return@withLock null
        dao.insertAchievedOne(AchievedEntity(a.code, a.name, a.sido, a.sgg, a.firstVisitedAt, a.method.name))
        dao.deleteNeedsReview(listOf(code))
        a
    }

    val photoCodes: Flow<List<String>> = dao.observePhotoCodes()

    fun photosOf(code: String): Flow<List<PhotoEntity>> = dao.observePhotos(code)

    /** 카메라로 찍을 사진을 저장할 빈 파일을 미리 만들고, 그 FileProvider Uri 를 돌려준다. */
    fun preparePhotoFile(code: String): Pair<File, Uri> {
        val dir = File(context.filesDir, "photos/$code").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    /** 카메라 촬영 뒤 [preparePhotoFile]로 만든 파일을 실제 등록에 반영한다. */
    suspend fun commitPhoto(code: String, file: File) = mutex.withLock {
        insertPhotoLocked(code, file.absolutePath)
    }

    /** 갤러리에서 고른 이미지를 앱 내부 저장소로 복사해 등록한다. */
    suspend fun addPhotoFromUri(code: String, source: Uri) = withContext(Dispatchers.IO) {
        val (file, _) = preparePhotoFile(code)
        context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        mutex.withLock { insertPhotoLocked(code, file.absolutePath) }
    }

    /**
     * "사진 자동 인식" 기능: 갤러리 사진의 EXIF GPS로 찾은 지역에, EXIF 촬영시각을 그대로 실제 방문시각으로
     * 써서 등록한다(스캔한 지금 시각이 아니라 사진이 찍힌 그때가 방문 시각이 되도록).
     * 새로 달성된 경우에만 그 [Achievement]를 돌려준다(알림용).
     *
     * 원본 사진을 내부 저장소로 복사하지 않고 갤러리의 content:// Uri 를 그대로 기록한다 — 수동으로
     * 카메라/갤러리에서 붙이는 사진과 달리 자동 스캔은 대상이 훨씬 많을 수 있어서, 복사하면 저장공간을
     * 그대로 두 배로 먹게 된다. 이 기능이 갤러리 읽기 권한을 이미 갖고 있어야만 동작하므로 나중에 다시
     * 읽는 데도 문제가 없다(단, 사용자가 원본 사진을 갤러리에서 지우면 썸네일도 함께 사라진다).
     */
    suspend fun importPhotoFromGallery(code: String, source: Uri, takenAtMillis: Long): Achievement? =
        mutex.withLock { insertPhotoLocked(code, source.toString(), takenAtMillis) }

    /** 사진이 생겼다는 건 실제로 다녀왔다는 뜻이므로, 아직 달성 전이면 직접 방문으로 같이 달성시킨다. */
    private suspend fun insertPhotoLocked(code: String, filePath: String, takenAt: Long = System.currentTimeMillis()): Achievement? {
        val existing = dao.getAchieved(code)
        var newAchievement: Achievement? = null
        if (existing == null) {
            judgeLocked().markOnSite(code, takenAt)?.let {
                dao.insertAchievedOne(AchievedEntity(it.code, it.name, it.sido, it.sgg, it.firstVisitedAt, it.method.name))
                dao.deleteNeedsReview(listOf(code))
                newAchievement = it
            }
        } else if (existing.method != Method.ON_SITE.name) {
            dao.updateMethod(code, Method.ON_SITE.name)
        }
        dao.insertPhoto(PhotoEntity(code = code, filePath = filePath, takenAt = takenAt))
        return newAchievement
    }

    suspend fun deletePhoto(photo: PhotoEntity) {
        // 자동 인식으로 등록된 사진은 content:// Uri(갤러리 원본)를 그대로 가리키므로 지우면 안 된다.
        // 앱이 직접 복사해서 갖고 있는 내부 저장소 파일일 때만 삭제한다.
        if (!photo.filePath.startsWith("content://")) runCatching { File(photo.filePath).delete() }
        dao.deletePhoto(photo.id)
    }

    /**
     * 서비스가 살아있는 동안 주기적으로(예: 1분마다) 호출.
     * 마지막 위치가 들어온 뒤로 [TRIP_GAP_MS] 이상 조용하면 "정차·도착"으로 보고 여행을 자동 마감한다.
     */
    suspend fun checkTripTimeout() = mutex.withLock {
        val start = sessionStart ?: return@withLock
        val lastWall = lastFixWallClock ?: return@withLock
        if (System.currentTimeMillis() - lastWall < TRIP_GAP_MS) return@withLock
        finalizeSession(start, sessionLastFix?.timeMillis ?: lastWall)
        sessionStart = null
    }

    /** 기록 자체를 끌 때 호출: 지금까지 쌓인 구간이 있으면 그 시점에서 마감한다. */
    suspend fun endSession() = mutex.withLock {
        val start = sessionStart ?: return@withLock
        sessionStart = null
        finalizeSession(start, sessionLastFix?.timeMillis ?: System.currentTimeMillis())
    }

    /**
     * 이번 구간을 "여행"으로 저장하고, 최근 출퇴근성 구간들과 비교해 평소 패턴인지 판정한다.
     *
     * 판정 기준(출퇴근용으로도 쓰는 차라 단순 "가동 여부"로는 못 가림):
     * - 새로 달성한 지역이 하나라도 있으면 무조건 "여행"(newRegionCount>0 → isRoutine=false)
     * - 주말/공휴일 감안 없이 우선 요일만 봐서, 평일이 아니면 "여행" 쪽으로 기운다(출근이 없으니까)
     * - 최근 평일 출퇴근성 구간이 5개 미만이면 기준을 아직 못 만든 것이므로 판정 보류(null)
     * - 기준이 있으면, 이번 거리가 "평소 평일 출퇴근 거리" 중앙값의 1.6배를 넘으면 "여행"
     */
    private suspend fun finalizeSession(start: Long, end: Long) {
        if (end - start < MIN_SESSION_MS) return // 너무 짧은 구간은 통계에 안 넣음

        val dow = Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.DAY_OF_WEEK)
        val isWeekday = dow != Calendar.SUNDAY && dow != Calendar.SATURDAY

        val isRoutine: Boolean? = when {
            sessionNewRegions > 0 -> false
            !isWeekday -> false
            else -> {
                val baseline = dao.recentTrips(60)
                    .filter { it.isRoutine == true && it.dayOfWeek != Calendar.SUNDAY && it.dayOfWeek != Calendar.SATURDAY }
                    .map { it.distanceMeters }
                if (baseline.size < MIN_BASELINE_TRIPS) null else sessionDistance <= median(baseline) * ROUTINE_SLACK
            }
        }

        dao.insertTrip(
            TripEntity(
                startTime = start, endTime = end, distanceMeters = sessionDistance,
                newRegionCount = sessionNewRegions, dayOfWeek = dow, isRoutine = isRoutine,
            )
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    /** 모든 달성 기록 삭제(사진 파일 포함) */
    suspend fun clearAll() = mutex.withLock {
        dao.clearAll()
        withContext(Dispatchers.IO) { File(context.filesDir, "photos").deleteRecursively() }
        judge = null
        _live.value = LiveStatus()
    }

    /** 달성한 지역 코드 집합(실행 알림에서 미달성 지역을 고를 때 씀) */
    suspend fun achievedCodes(): Set<String> = dao.allAchieved().mapTo(HashSet()) { it.code }

    /** DB에 저장된 마지막 유효 위치(기록이 꺼져 있어도 남아 있을 수 있음) */
    suspend fun lastKnownFix(): Fix? = dao.lastPoint()?.let { Fix(it.lat, it.lng, it.accuracyM, it.timeMillis) }

    /** 가장 최근 "여행"(출퇴근이 아닌 구간)의 시작 시각. 대시보드용. */
    suspend fun lastTripAt(): Long? = dao.recentTrips(200).firstOrNull { it.isRoutine == false }?.startTime

    /** 지금까지 기록된 모든 구간의 누적 주행거리(정비 체크리스트의 주행거리 기준용) */
    suspend fun totalDistanceMeters(): Double = dao.recentTrips(Int.MAX_VALUE).sumOf { it.distanceMeters }

    /**
     * 달성 지역·여행 기록·사진 메타데이터를 JSON 문자열로 내보낸다.
     * 사진 원본 파일은 용량 문제로 JSON 안에 포함하지 않는다(경로만 기록). 그래서 기기를 바꾸거나
     * 재설치하면 사진은 복원되지 않고, 같은 기기에서 앱만 업데이트한 경우에만 사진도 그대로 남는다.
     */
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        val achievedList = dao.allAchieved()
        val tripsList = dao.recentTrips(Int.MAX_VALUE)
        val photosList = dao.allPhotos()
        val obj = buildJsonObject {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            putJsonArray("achieved") {
                achievedList.forEach { a ->
                    add(buildJsonObject {
                        put("code", a.code); put("name", a.name); put("sido", a.sido); put("sgg", a.sgg)
                        put("firstVisitedAt", a.firstVisitedAt); put("method", a.method)
                    })
                }
            }
            putJsonArray("trips") {
                tripsList.forEach { t ->
                    add(buildJsonObject {
                        put("startTime", t.startTime); put("endTime", t.endTime)
                        put("distanceMeters", t.distanceMeters); put("newRegionCount", t.newRegionCount)
                        put("dayOfWeek", t.dayOfWeek)
                        put("isRoutine", t.isRoutine)
                    })
                }
            }
            putJsonArray("photos") {
                photosList.forEach { p ->
                    add(buildJsonObject {
                        put("code", p.code); put("filePath", p.filePath); put("takenAt", p.takenAt)
                    })
                }
            }
        }
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * [exportBackup]이 만든 JSON을 다시 불러온다.
     * 달성 지역은 이미 있는 코드는 건드리지 않고 없는 것만 채워 넣는다(로컬 데이터 우선, 덮어쓰지 않음).
     * 여행 기록은 그냥 추가되므로, 같은 백업을 두 번 불러오면 중복될 수 있다.
     * 사진은 파일이 실제로 남아있는 경우에만(같은 기기, 삭제 전) 복원한다.
     */
    suspend fun importBackup(text: String): BackupImportResult = withContext(Dispatchers.IO) {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(text).jsonObject

        val achievedList = root["achieved"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            AchievedEntity(
                code = o["code"]!!.jsonPrimitive.content, name = o["name"]!!.jsonPrimitive.content,
                sido = o["sido"]!!.jsonPrimitive.content, sgg = o["sgg"]!!.jsonPrimitive.content,
                firstVisitedAt = o["firstVisitedAt"]!!.jsonPrimitive.long, method = o["method"]!!.jsonPrimitive.content,
            )
        }
        val tripsList = root["trips"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            TripEntity(
                startTime = o["startTime"]!!.jsonPrimitive.long, endTime = o["endTime"]!!.jsonPrimitive.long,
                distanceMeters = o["distanceMeters"]!!.jsonPrimitive.double,
                newRegionCount = o["newRegionCount"]!!.jsonPrimitive.int, dayOfWeek = o["dayOfWeek"]!!.jsonPrimitive.int,
                isRoutine = (o["isRoutine"] as? JsonPrimitive)?.takeIf { p -> p != JsonNull }?.boolean,
            )
        }
        val photosList = root["photos"]?.jsonArray.orEmpty().mapNotNull {
            val o = it.jsonObject
            val path = o["filePath"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // content:// Uri(자동 인식된 사진)는 실존 여부를 파일시스템으로 확인할 수 없으니 그대로 두고,
            // 내부 저장소 경로만 실제로 파일이 남아 있는지 확인한다(다른 기기·재설치 등으로 없어졌을 수 있음).
            if (!path.startsWith("content://") && !File(path).exists()) return@mapNotNull null
            PhotoEntity(code = o["code"]!!.jsonPrimitive.content, filePath = path, takenAt = o["takenAt"]!!.jsonPrimitive.long)
        }
        val skippedPhotos = (root["photos"]?.jsonArray?.size ?: 0) - photosList.size

        mutex.withLock {
            dao.insertAchieved(achievedList) // IGNORE: 이미 있는 코드는 로컬 값을 유지
            tripsList.forEach { dao.insertTrip(it) }
            photosList.forEach { dao.insertPhoto(it) }
            judge = null
        }
        BackupImportResult(achievedList.size, tripsList.size, photosList.size, skippedPhotos)
    }

    companion object {
        const val REGIONS_ASSET = "regions.geojson"
        private const val MIN_SESSION_MS = 60_000L
        private const val MIN_BASELINE_TRIPS = 5
        private const val ROUTINE_SLACK = 1.6
        /**
         * 이만큼 새 위치가 안 들어오면 여행이 끝난 것으로 본다(주차 후 정지 상태로 간주).
         * 지도의 이동 경로 선도 이 간격보다 크게 벌어진 두 점은 잇지 않는다(따로 저장된 구간).
         */
        const val TRIP_GAP_MS = 20 * 60_000L
    }
}
