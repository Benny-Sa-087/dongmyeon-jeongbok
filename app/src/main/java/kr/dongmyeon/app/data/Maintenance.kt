package kr.dongmyeon.app.data

/**
 * 오버랜딩 세팅 점검 항목. 요일별 고정 문구가 아니라 실제 누적 주행거리·경과 일수 기준으로 판단한다.
 * [intervalKm]/[intervalDays] 중 있는 쪽만 보고, 둘 다 있으면 먼저 도달하는 쪽 기준으로 "점검 필요"가 된다.
 */
data class MaintenanceItem(
    val key: String,
    val label: String,
    val intervalKm: Double?,
    val intervalDays: Int?,
)

val MAINTENANCE_ITEMS = listOf(
    MaintenanceItem("tire", "타이어 공기압·마모", intervalKm = 3_000.0, intervalDays = 60),
    MaintenanceItem("battery", "보조(서브)배터리 전압", intervalKm = null, intervalDays = 30),
    MaintenanceItem("engine", "엔진오일·소모품", intervalKm = 8_000.0, intervalDays = null),
    MaintenanceItem("fridge", "냉장고·인버터 작동", intervalKm = null, intervalDays = 30),
)

/** [item]이 지금 점검이 필요한지: 기준 거리/일수 중 있는 쪽이 하나라도 넘으면 필요 */
fun MaintenanceItem.isDue(sinceKm: Double, sinceDays: Long): Boolean {
    val kmDue = intervalKm?.let { sinceKm >= it } ?: false
    val daysDue = intervalDays?.let { sinceDays >= it } ?: false
    return kmDue || daysDue
}
