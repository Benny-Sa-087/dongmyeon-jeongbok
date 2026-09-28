package kr.dongmyeon.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 앱에 내장한 실제 인천 경계 데이터로 판정 확인 */
class IncheonDataTest {
    private val index: RegionIndex by lazy {
        RegionIndex.fromGeoJson(File(System.getProperty("regionsFile")).readText())
    }

    @Test
    fun `인천 읍면동 158개 로드`() {
        assertEquals(158, index.regions.size)
        assertTrue(index.regions.all { it.sido == "인천광역시" && it.code.length == 10 })
    }

    @Test
    fun `알려진 좌표의 소속 읍면동`() {
        assertEquals("인천광역시 남동구 구월1동", index.find(37.4563, 126.7052)?.name) // 인천시청
        assertEquals("인천광역시 영종구 운서1동", index.find(37.4492, 126.4506)?.name) // 인천공항 T1
        assertEquals("인천광역시 연수구 송도2동", index.find(37.3925, 126.6390)?.name) // 송도 센트럴파크
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
}
