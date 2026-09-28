package kr.dongmyeon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 터널·오지 등으로 GPS 가 오래 끊겼다가 잡혔을 때(공백 구간)를 다루는 규칙 검증.
 *
 * SQ: 경도 127.00~127.01, 위도 37.00~37.01 짜리 정사각형 영역 하나.
 */
class GapReviewTest {

    private fun square(minX: Double, minY: Double, maxX: Double, maxY: Double) =
        Ring(doubleArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY, minX, minY))

    private val sq = Region("SQ", "테스트시 테스트구 사각동", "테스트시", "테스트구",
        listOf(Polygon(square(127.00, 37.00, 127.01, 37.01))))

    // 기본값(2km / 3분)으로 판정: 실제 서비스가 쓰는 것과 동일한 기준
    private fun judge() = VisitJudge(RegionIndex(listOf(sq)))
    private fun fix(lat: Double, lng: Double, t: Long) = Fix(lat, lng, 10f, t)

    @Test
    fun `거리 공백이 크면 가로지르기를 확인 필요로만 표시`() {
        val j = judge()
        j.process(fix(37.005, 126.90, 0))          // 왼쪽 밖, 사각동과 10km 이상 떨어짐
        val got = j.process(fix(37.005, 127.10, 30_000)) // 오른쪽 밖, 30초 만에 도착(비현실적 이동)
        assertEquals(listOf("SQ"), got.map { it.code })
        assertEquals(Method.NEEDS_REVIEW, got[0].method)
        assertTrue("SQ" !in j.state.achieved)
        assertTrue("SQ" in j.state.needsReview)
    }

    @Test
    fun `시간 공백이 크면 거리가 가까워도 확인 필요`() {
        val j = judge()
        j.process(fix(37.005, 126.995, 0))
        val got = j.process(fix(37.005, 127.025, 10 * 60_000)) // 10분 공백(3분 기준 초과), 거리는 약 2.7km
        assertEquals(Method.NEEDS_REVIEW, got.single().method)
    }

    @Test
    fun `공백이 정상 범위면 그대로 달성`() {
        val j = judge()
        j.process(fix(37.005, 126.999, 0))
        val got = j.process(fix(37.005, 127.011, 5_000)) // 약 1km, 5초
        assertEquals(Method.CROSSING, got.single().method)
        assertTrue("SQ" in j.state.achieved)
    }

    @Test
    fun `확인 필요 항목을 확정하면 달성으로 바뀜`() {
        val j = judge()
        j.process(fix(37.005, 126.90, 0))
        j.process(fix(37.005, 127.10, 30_000))
        assertTrue("SQ" in j.state.needsReview)

        val confirmed = j.confirmReview("SQ", 99L)
        assertEquals("SQ", confirmed?.code)
        assertEquals(Method.CROSSING, confirmed?.method)
        assertTrue("SQ" in j.state.achieved)
        assertTrue("SQ" !in j.state.needsReview)
    }

    @Test
    fun `확인 필요 항목을 무시하면 사라짐`() {
        val j = judge()
        j.process(fix(37.005, 126.90, 0))
        j.process(fix(37.005, 127.10, 30_000))
        j.dismissReview("SQ")
        assertTrue("SQ" !in j.state.needsReview)
        assertTrue("SQ" !in j.state.achieved)
    }

    @Test
    fun `확인 필요 상태에서도 나중에 정상적으로 지나가면 바로 달성`() {
        val j = judge()
        j.process(fix(37.005, 126.90, 0))
        j.process(fix(37.005, 127.10, 30_000)) // 공백 큼 -> 확인 필요
        j.breakTrack()
        j.process(fix(37.005, 126.999, 0))
        val got = j.process(fix(37.005, 127.011, 5_000)) // 이번엔 공백 정상 -> 바로 달성
        assertEquals(Method.CROSSING, got.single().method)
        assertTrue("SQ" in j.state.achieved)
        assertTrue("SQ" !in j.state.needsReview)
    }

    @Test
    fun `이미 달성한 영역은 다시 확인 필요로 보고하지 않음`() {
        val j = judge()
        j.process(fix(37.002, 127.002, 0))
        j.process(fix(37.003, 127.003, 1_000)) // POINTS 로 달성
        j.breakTrack()
        j.process(fix(37.005, 126.90, 0))
        val got = j.process(fix(37.005, 127.10, 30_000))
        assertTrue(got.isEmpty())
    }

    @Test
    fun `직접 방문으로 표시하면 즉시 달성`() {
        val j = judge()
        val a = j.markOnSite("SQ", 42L)
        assertEquals(Method.ON_SITE, a?.method)
        assertEquals(42L, a?.firstVisitedAt)
        assertTrue("SQ" in j.state.achieved)
    }

    @Test
    fun `이미 달성한 곳을 직접 방문으로 눌러도 중복되지 않음`() {
        val j = judge()
        j.markOnSite("SQ", 1L)
        assertNull(j.markOnSite("SQ", 2L))
    }
}
