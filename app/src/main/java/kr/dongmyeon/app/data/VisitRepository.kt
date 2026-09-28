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
import kr.dongmyeon.core.Hit
import kr.dongmyeon.core.JudgeState
import kr.dongmyeon.core.LastPoint
import kr.dongmyeon.core.RegionIndex
import kr.dongmyeon.core.VisitJudge

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

    private val _live = MutableStateFlow(LiveStatus())
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    val achieved: Flow<List<AchievedEntity>> = dao.observeAchieved()

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
        )
        VisitJudge(idx, state)
    }.also { judge = it }

    /** 위치 한 점 처리 → 새로 달성한 지역 */
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

        val st = j.state
        dao.saveStep(
            newAchieved = found.map {
                AchievedEntity(it.code, it.name, it.sido, it.sgg, it.firstVisitedAt, it.method.name)
            },
            hits = listOfNotNull(region?.code?.let { c -> st.hits[c]?.let { HitEntity(c, it.count, it.firstTime) } }),
            removedHits = found.map { it.code },
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

    companion object {
        const val REGIONS_ASSET = "regions.geojson"
    }
}
