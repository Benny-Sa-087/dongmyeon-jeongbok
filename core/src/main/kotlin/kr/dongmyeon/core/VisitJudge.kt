package kr.dongmyeon.core

/** GPS 한 점. accuracyM 은 수평 정확도(68% 반경, m). 정확도가 없으면 null. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val timeMillis: Long,
)

enum class Method {
    /** 영역 안에 유효한 점 2개 이상 */
    POINTS,

    /** 이동선이 영역을 가로지름 */
    CROSSING,
}

data class Achievement(
    val code: String,
    val name: String,
    val sido: String,
    val sgg: String,
    val firstVisitedAt: Long,
    val method: Method,
)

/** 아직 달성 못 한 영역에 찍힌 유효 점 기록 */
data class Hit(val count: Int, val firstTime: Long)

/** 직전 유효 점과 그 점의 판정 정보 */
data class LastPoint(
    val fix: Fix,
    /** 이 점이 속한 영역 코드(바다 등은 null) */
    val regionCode: String?,
    /** 이 점 바로 앞의 유효 점이 다른 영역(또는 영역 밖)에 있었는지 */
    val enteredFromOutside: Boolean,
)

/**
 * 판정 상태. 앱에서는 DB 에 저장했다가 다시 불러온다.
 */
class JudgeState(
    val achieved: MutableSet<String> = HashSet(),
    val hits: MutableMap<String, Hit> = HashMap(),
    var last: LastPoint? = null,
)

/**
 * 읍면동 달성 판정기.
 *
 * 규칙
 * 1. 정확도가 [maxAccuracyM] 보다 나쁘거나 정확도 정보가 없는 점은 버린다(경로 연결에도 쓰지 않는다).
 * 2. 한 영역 안에 유효한 점이 2개 이상 찍히면 달성(POINTS). 첫 방문 시각 = 첫 점의 시각.
 * 3. 연속된 두 유효 점을 잇는 선분이 영역을 가로지르면 달성(CROSSING).
 *    - 두 끝점이 모두 영역 밖이고 선분이 영역 경계와 만나는 경우.
 *      첫 방문 시각 = 선분이 경계에 처음 닿는 지점의 시각(선형 보간).
 *    - 영역 안에 점이 1개만 찍혔더라도 그 앞 점과 뒤 점이 모두 영역 밖이면
 *      선이 영역을 들어왔다 나간 것이므로 가로지른 것으로 본다(고속 주행 대비).
 * 속도 제한은 없다.
 */
class VisitJudge(
    private val index: RegionIndex,
    val state: JudgeState = JudgeState(),
    private val maxAccuracyM: Float = 50f,
) {
    fun isValid(fix: Fix): Boolean {
        val acc = fix.accuracyM ?: return false
        return !acc.isNaN() && acc <= maxAccuracyM
    }

    /** 점 하나를 처리하고 새로 달성된 영역을 시각 순으로 돌려준다. */
    fun process(fix: Fix): List<Achievement> {
        if (!isValid(fix)) return emptyList()

        val found = ArrayList<Achievement>()
        val region = index.find(fix.lat, fix.lng)
        val last = state.last

        if (last != null) {
            val a = last.fix
            // 3-a. 두 끝점 모두 밖인 영역을 선분이 지나감
            for (r in index.candidatesForSegment(a.lat, a.lng, fix.lat, fix.lng)) {
                if (r.code in state.achieved) continue
                if (r.code == last.regionCode || r.code == region?.code) continue
                val t = r.firstBoundaryHit(a.lat, a.lng, fix.lat, fix.lng) ?: continue
                val time = a.timeMillis + Math.round((fix.timeMillis - a.timeMillis) * t)
                found += achieve(r, time, Method.CROSSING)
            }
            // 3-b. 점 1개만 찍고 지나간 영역
            val prevCode = last.regionCode
            if (prevCode != null && prevCode != region?.code && last.enteredFromOutside &&
                prevCode !in state.achieved
            ) {
                index.byCode[prevCode]?.let { found += achieve(it, last.fix.timeMillis, Method.CROSSING) }
            }
        }

        // 2. 영역 안 점 개수
        if (region != null && region.code !in state.achieved) {
            val prev = state.hits[region.code]
            val hit = if (prev == null) Hit(1, fix.timeMillis) else prev.copy(count = prev.count + 1)
            state.hits[region.code] = hit
            if (hit.count >= 2) found += achieve(region, hit.firstTime, Method.POINTS)
        }

        state.last = LastPoint(
            fix = fix,
            regionCode = region?.code,
            enteredFromOutside = last != null && last.regionCode != region?.code,
        )
        return found.sortedBy { it.firstVisitedAt }
    }

    /** 기록을 새로 시작할 때: 이전 점과 선으로 잇지 않도록 끊는다. */
    fun breakTrack() {
        state.last = null
    }

    private fun achieve(r: Region, time: Long, method: Method): Achievement {
        // 앞서 점 1개가 찍힌 적이 있으면 그 시각이 더 이른 첫 방문
        val first = state.hits.remove(r.code)?.firstTime?.let { minOf(it, time) } ?: time
        state.achieved += r.code
        return Achievement(r.code, r.name, r.sido, r.sgg, first, method)
    }
}
