package kr.dongmyeon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 가상의 정사각형 영역으로 판정 규칙을 검증한다.
 *
 *   SQ: 경도 127.00~127.01, 위도 37.00~37.01 (약 900m x 1.1km)
 *   HOLE: SQ 가운데 구멍이 뚫린 영역
 */
class VisitJudgeTest {

    private fun square(minX: Double, minY: Double, maxX: Double, maxY: Double) =
        Ring(doubleArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY, minX, minY))

    private val sq = Region("SQ", "테스트시 테스트구 사각동", "테스트시", "테스트구",
        listOf(Polygon(square(127.00, 37.00, 127.01, 37.01))))
    private val right = Region("RT", "테스트시 테스트구 오른동", "테스트시", "테스트구",
        listOf(Polygon(square(127.01, 37.00, 127.02, 37.01))))
    private val ring = Region("RG", "테스트시 테스트구 고리동", "테스트시", "테스트구",
        listOf(Polygon(square(127.10, 37.00, 127.13, 37.03), listOf(square(127.11, 37.01, 127.12, 37.02)))))

    // 이 테스트들은 기하 판정 자체(영역 안 점 개수, 가로지르기)를 확인하는 게 목적이라
    // 공백-구간 확인 필요 로직(별도 테스트 클래스에서 검증)은 끄고 순수 지오메트리만 본다.
    private fun judge() = VisitJudge(
        RegionIndex(listOf(sq, right, ring)),
        maxGapMeters = Double.MAX_VALUE,
        maxGapMillis = Long.MAX_VALUE,
    )
    private fun fix(lat: Double, lng: Double, acc: Float? = 10f, t: Long = 0) = Fix(lat, lng, acc, t)

    // ---- 1. 영역 안 점 2개 ----

    @Test
    fun `영역 안 유효한 점 2개면 달성`() {
        val j = judge()
        assertTrue(j.process(fix(37.002, 127.002, t = 1000)).isEmpty(), "점 1개로는 미달성")
        val got = j.process(fix(37.003, 127.003, t = 2000))
        assertEquals(listOf("SQ"), got.map { it.code })
        assertEquals(Method.POINTS, got[0].method)
        assertEquals(1000, got[0].firstVisitedAt, "첫 방문 시각은 첫 점의 시각")
        assertEquals("테스트시 테스트구 사각동", got[0].name)
    }

    @Test
    fun `점 사이가 떨어져 있어도 누적 2개면 달성`() {
        val j = judge()
        j.process(fix(37.002, 127.002, t = 1))
        j.process(fix(37.5, 127.5, t = 2)) // 영역 밖(어느 영역도 아님)
        j.breakTrack()
        val got = j.process(fix(37.008, 127.008, t = 3))
        assertEquals(listOf("SQ"), got.map { it.code })
    }

    @Test
    fun `이미 달성한 영역은 다시 보고하지 않음`() {
        val j = judge()
        j.process(fix(37.002, 127.002))
        j.process(fix(37.003, 127.003))
        assertTrue(j.process(fix(37.004, 127.004)).isEmpty())
    }

    @Test
    fun `구멍 안의 점은 영역 밖`() {
        val j = judge()
        j.process(fix(37.015, 127.115))
        assertTrue(j.process(fix(37.016, 127.116)).isEmpty())
        assertTrue(j.state.hits.isEmpty())
    }

    // ---- 2. 가로지르기 ----

    @Test
    fun `두 점을 잇는 선이 영역을 가로지르면 달성`() {
        val j = judge()
        j.process(fix(37.005, 126.995, t = 0))        // 사각동 왼쪽 밖
        val got = j.process(fix(37.005, 127.025, t = 30_000)) // 오른동 너머 밖
        assertEquals(listOf("SQ", "RT"), got.map { it.code })
        assertTrue(got.all { it.method == Method.CROSSING })
        // 경계(127.00)는 선분의 1/6 지점 → 5초
        assertEquals(5_000, got[0].firstVisitedAt)
        assertEquals(15_000, got[1].firstVisitedAt)
    }

    @Test
    fun `영역 옆을 스쳐 지나가지 않으면 미달성`() {
        val j = judge()
        j.process(fix(36.99, 126.995))
        assertTrue(j.process(fix(36.99, 127.025)).isEmpty())
    }

    @Test
    fun `점 1개만 찍고 들어왔다 나가면 가로지른 것`() {
        val j = judge()
        j.process(fix(37.005, 126.990, t = 0))         // 밖
        assertTrue(j.process(fix(37.005, 127.005, t = 10)).isEmpty()) // 사각동 안 1개
        val got = j.process(fix(37.005, 127.030, t = 20)) // 오른동 건너뛰고 밖
        assertEquals(setOf("SQ", "RT"), got.map { it.code }.toSet())
        assertEquals(10, got.first { it.code == "SQ" }.firstVisitedAt)
    }

    @Test
    fun `기록 시작 직후 첫 점만 찍힌 영역은 가로지르기로 보지 않음`() {
        val j = judge()
        j.process(fix(37.005, 127.005)) // 사각동 안에서 시작(앞 점 없음)
        val got = j.process(fix(37.5, 127.5))
        assertTrue(got.isEmpty())
    }

    @Test
    fun `기록을 끊으면 이전 점과 잇지 않음`() {
        val j = judge()
        j.process(fix(37.005, 126.995))
        j.breakTrack()
        assertTrue(j.process(fix(37.005, 127.025)).isEmpty())
    }

    // ---- 3. 정확도 불량 점 무시 ----

    @Test
    fun `정확도 50m 초과 점은 영역 안 점으로 세지 않음`() {
        val j = judge()
        j.process(fix(37.002, 127.002, acc = 80f))
        assertTrue(j.process(fix(37.003, 127.003, acc = 51f)).isEmpty())
        assertTrue(j.state.hits.isEmpty())
        assertTrue(j.state.last == null)
    }

    @Test
    fun `정확도 정보가 없는 점도 무시`() {
        val j = judge()
        j.process(fix(37.002, 127.002, acc = null))
        assertTrue(j.process(fix(37.003, 127.003, acc = Float.NaN)).isEmpty())
    }

    @Test
    fun `정확도 정확히 50m 는 유효`() {
        val j = judge()
        j.process(fix(37.002, 127.002, acc = 50f))
        assertEquals(listOf("SQ"), j.process(fix(37.003, 127.003, acc = 50f)).map { it.code })
    }

    @Test
    fun `정확도 불량 점은 가로지르기 선분의 끝점으로도 쓰지 않음`() {
        val j = judge()
        j.process(fix(37.005, 126.995))                       // 유효, 왼쪽 밖
        assertTrue(j.process(fix(37.005, 127.025, acc = 200f)).isEmpty()) // 불량, 오른쪽 밖
        assertTrue(j.state.hits.isEmpty())
        // 다음 유효 점은 왼쪽 밖에 그대로 → 선분이 영역을 지나지 않음
        assertTrue(j.process(fix(37.006, 126.996)).isEmpty())
    }

    @Test
    fun `불량 점 하나 뒤의 유효 점은 직전 유효 점과 이어짐`() {
        val j = judge()
        j.process(fix(37.005, 126.995))
        j.process(fix(37.005, 127.005, acc = 999f)) // 무시
        val got = j.process(fix(37.005, 127.025))
        assertEquals(listOf("SQ", "RT"), got.map { it.code })
    }
}
