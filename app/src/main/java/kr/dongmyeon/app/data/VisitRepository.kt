package kr.dongmyeon.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kr.dongmyeon.core.Achievement
import kr.dongmyeon.core.Fix
import kr.dongmyeon.core.Geo
import kr.dongmyeon.core.Hit
import kr.dongmyeon.core.JudgeState
import kr.dongmyeon.core.LastPoint
import kr.dongmyeon.core.Method
import kr.dongmyeon.core.RegionIndex
import kr.dongmyeon.core.VisitJudge
import java.util.Calendar

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

    // 현재 진행 중인 "구간"(기록 시작~정지)의 거리·신규지역 누적치.
    // 앱 프로세스가 완전히 재시작되면 이 값은 초기화된다(진행 중이던 구간은 짧게 끊겨 기록될 수 있음).
    private var sessionStart: Long? = null
    private var sessionDistance = 0.0
    private var sessionNewRegions = 0
    private var sessionLastFix: Fix? = null

    private val _live = MutableStateFlow(LiveStatus())
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    val achieved: Flow<List<AchievedEntity>> = dao.observeAchieved()
    val needsReview: Flow<List<NeedsReviewEntity>> = dao.observeNeedsReview()
    val trips: Flow<List<TripEntity>> = dao.observeTrips()

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

        if (sessionStart != null) {
            sessionLastFix?.let { prev -> sessionDistance += Geo.distanceMeters(prev.lat, prev.lng, fix.lat, fix.lng) }
            sessionLastFix = fix
            sessionNewRegions += confirmed.size
        }

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

    /** 현장에서 "직접 방문" 버튼으로 확정 */
    suspend fun markOnSite(code: String): Achievement? = mutex.withLock {
        val a = judgeLocked().markOnSite(code, System.currentTimeMillis()) ?: return@withLock null
        dao.insertAchievedOne(AchievedEntity(a.code, a.name, a.sido, a.sgg, a.firstVisitedAt, a.method.name))
        dao.deleteNeedsReview(listOf(code))
        a
    }

    /** 기록을 새로 시작할 때 호출: 이번 구간의 거리·신규지역 누적을 시작한다. */
    suspend fun startSession() = mutex.withLock {
        sessionStart = System.currentTimeMillis()
        sessionDistance = 0.0
        sessionNewRegions = 0
        sessionLastFix = null
    }

    /**
     * 기록을 정지할 때 호출: 이번 구간을 "여행"으로 저장하고,
     * 최근 출퇴근성 구간들과 비교해 이번에도 평소 패턴인지 판정한다.
     *
     * 판정 기준(출퇴근용으로도 쓰는 차라 단순 "가동 여부"로는 못 가림):
     * - 새로 달성한 지역이 하나라도 있으면 무조건 "여행"(newRegionCount>0 → isRoutine=false)
     * - 주말/공휴일 감안 없이 우선 요일만 봐서, 평일이 아니면 "여행" 쪽으로 기운다(출근이 없으니까)
     * - 최근 평일 출퇴근성 구간이 5개 미만이면 기준을 아직 못 만든 것이므로 판정 보류(null)
     * - 기준이 있으면, 이번 거리가 "평소 평일 출퇴근 거리" 중앙값의 1.6배를 넘으면 "여행"
     */
    suspend fun endSession() = mutex.withLock {
        val start = sessionStart ?: return@withLock
        sessionStart = null
        val end = System.currentTimeMillis()
        if (end - start < MIN_SESSION_MS) return@withLock // 너무 짧은 구간은 통계에 안 넣음

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

    /** 모든 달성 기록 삭제 */
    suspend fun clearAll() = mutex.withLock {
        dao.clearAll()
        judge = null
        _live.value = LiveStatus()
    }

    /** 달성한 지역 코드 집합(실행 알림에서 미달성 지역을 고를 때 씀) */
    suspend fun achievedCodes(): Set<String> = dao.allAchieved().mapTo(HashSet()) { it.code }

    /** DB에 저장된 마지막 유효 위치(기록이 꺼져 있어도 남아 있을 수 있음) */
    suspend fun lastKnownFix(): Fix? = dao.lastPoint()?.let { Fix(it.lat, it.lng, it.accuracyM, it.timeMillis) }

    /** 가장 최근 "여행"(출퇴근이 아닌 구간)의 시작 시각. 대시보드용. */
    suspend fun lastTripAt(): Long? = dao.recentTrips(200).firstOrNull { it.isRoutine == false }?.startTime

    companion object {
        const val REGIONS_ASSET = "regions.geojson"
        private const val MIN_SESSION_MS = 60_000L
        private const val MIN_BASELINE_TRIPS = 5
        private const val ROUTINE_SLACK = 1.6
    }
}
