package kr.dongmyeon.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 앱에 내장한 전국 읍면동 경계 데이터(2단계)로 판정 확인 */
class NationalDataTest {
    private val index: RegionIndex by lazy {
        RegionIndex.fromGeoJson(File(System.getProperty("regionsFile")).readText())
    }

    @Test
    fun `전국 읍면동 3558개 로드`() {
        assertEquals(3558, index.regions.size)
        assertTrue(index.regions.all { it.code.length == 10 })
        // 인천은 1단계에서 검증했던 158개가 전국 데이터 안에도 그대로 있어야 한다
        assertEquals(158, index.regions.count { it.sido == "인천광역시" })
    }

    @Test
    fun `전국 16개 시도가 모두 있음`() {
        val sidos = index.regions.map { it.sido }.toSet()
        assertEquals(16, sidos.size, "시도 목록: $sidos")
    }

    @Test
    fun `알려진 좌표의 소속 읍면동`() {
        assertEquals("인천광역시 남동구 구월1동", index.find(37.4563, 126.7052)?.name) // 인천시청
        assertEquals("인천광역시 영종구 운서1동", index.find(37.4492, 126.4506)?.name) // 인천공항 T1
        assertEquals("인천광역시 연수구 송도2동", index.find(37.3925, 126.6390)?.name) // 송도 센트럴파크
        assertEquals("서울특별시 중구 명동", index.find(37.5665, 126.9780)?.name) // 서울시청
        assertEquals("부산광역시 연제구 연산5동", index.find(35.1796, 129.0756)?.name) // 부산시청
        assertEquals("제주특별자치도 제주시 이도2동", index.find(33.4996, 126.5312)?.name) // 제주시청
        assertEquals("경상북도 울릉군 울릉읍", index.find(37.2401, 131.8697)?.name) // 독도(동도)
        assertNull(index.find(37.30, 126.40)) // 바다
    }

    @Test
    fun `시청에서 송도까지 한 번에 이으면 실거리가 커서 확인 필요로만 표시`() {
        // 약 13km를 10분 만에 잇는 단일 구간이라 정상 주행 간격(기본 2km 3분 기준)을 넘어서고,
        // 이건 정확히 터널·신호끊김 오탐을 막기 위한 공백-구간 안전장치가 걸려야 하는 상황이다.
        val j = VisitJudge(index)
        j.process(Fix(37.4563, 126.7052, 5f, 0))
        val got = j.process(Fix(37.3925, 126.6390, 5f, 600_000))
        assertTrue(got.size >= 3, "중간 동네 여러 개: ${got.map { it.name }}")
        assertTrue(got.all { it.method == Method.NEEDS_REVIEW })
        assertTrue(got.none { it.name.endsWith("구월1동") || it.name.endsWith("송도2동") })
    }

    @Test
    fun `서울에서 인천으로 정상 간격으로 이동하면 사이 지역들이 바로 달성`() {
        // 실제 서비스처럼 100m 간격, 5초 주기로 촘촘히 찍히는 상황을 흉내낸다.
        val j = VisitJudge(index)
        val startLat = 37.5665; val startLng = 126.9780 // 서울시청
        val endLat = 37.4563; val endLng = 126.7052     // 인천시청
        val steps = 300
        var achievedSomewhere = false
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val lat = startLat + (endLat - startLat) * t
            val lng = startLng + (endLng - startLng) * t
            val got = j.process(Fix(lat, lng, 5f, (i * 5_000).toLong()))
            if (got.any { it.method == Method.CROSSING || it.method == Method.POINTS }) achievedSomewhere = true
            assertTrue(got.none { it.method == Method.NEEDS_REVIEW }, "정상 간격인데 확인 필요로 빠짐: ${got}")
        }
        assertTrue(achievedSomewhere)
    }
}
