package kr.dongmyeon.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.dongmyeon.app.data.AppPrefs
import kr.dongmyeon.app.data.MAINTENANCE_ITEMS
import kr.dongmyeon.app.data.TripEntity
import kr.dongmyeon.app.data.isDue
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("M월 d일")

/**
 * "차량 가동률"이 아니라 "평소 출퇴근 패턴에서 벗어난 진짜 여행이 있었는가"를 본다.
 * 매일 차를 몰아 출퇴근하는 차라 단순 운행 여부로는 오버랜딩 활용도를 알 수 없기 때문.
 */
@Composable
fun VehicleScreen(trips: List<TripEntity>, needsReviewCount: Int, prefs: AppPrefs) {
    val now = System.currentTimeMillis()
    val totalDistance = trips.sumOf { it.distanceMeters }
    val lastTrip = trips.firstOrNull { it.isRoutine == false }
    val daysSince = lastTrip?.let { (now - it.startTime) / 86_400_000L }
    val thisMonth = YearMonth.now()
    val tripsThisMonth = trips.count {
        it.isRoutine == false &&
            YearMonth.from(Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault())) == thisMonth
    }
    val routineStreak = trips.takeWhile { it.isRoutine == true }.size
    val baselineTrips = trips.count { it.isRoutine != null }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("차량 활용", style = MaterialTheme.typography.headlineSmall)
        Text(
            "매일 타는 출퇴근 주행 말고, 평소와 다른 '진짜 여행'이 있었는지를 봅니다.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("마지막 여행", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (lastTrip == null) {
                    Text(
                        if (baselineTrips < MIN_BASELINE) "아직 출퇴근 패턴을 배우는 중입니다(구간 $baselineTrips/$MIN_BASELINE)."
                        else "출퇴근 외 주행 기록이 아직 없습니다.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        "${DATE_FORMAT.format(Instant.ofEpochMilli(lastTrip.startTime).atZone(ZoneId.systemDefault()))} · ${daysSince}일 전",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    if ((daysSince ?: 0) >= 10) {
                        Text(
                            "오버랜딩 세팅이 오래 쉬고 있어요. 보조배터리·타이어 상태도 같이 점검해보세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("이번 달 여행 횟수", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text("${tripsThisMonth}회", style = MaterialTheme.typography.headlineMedium)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("연속 출퇴근만", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (routineStreak == 0) "지난 주행이 여행이었습니다" else "${routineStreak}회 연속 출퇴근성 주행만 있었습니다",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        if (needsReviewCount > 0) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "기록 탭에 확인이 필요한 지역 ${needsReviewCount}곳이 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Text("정비 체크리스트", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Text(
            "요일별 고정 문구가 아니라 실제 누적 주행거리·경과일 기준으로 계산합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        MAINTENANCE_ITEMS.forEach { item ->
            var refreshTick by remember { mutableIntStateOf(0) }
            val (baseDistance, baseTime) = remember(refreshTick, totalDistance) {
                prefs.maintenanceBaseline(item.key, totalDistance)
            }
            val sinceKm = (totalDistance - baseDistance) / 1000.0
            val sinceDays = (now - baseTime) / 86_400_000L
            val due = item.isDue(sinceKm, sinceDays)

            Card(
                Modifier.fillMaxWidth(),
                colors = if (due) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.titleSmall)
                        val detail = buildList {
                            if (item.intervalKm != null) add("%.0fkm 주행 / 기준 %.0fkm".format(sinceKm, item.intervalKm))
                            if (item.intervalDays != null) add("${sinceDays}일 경과 / 기준 ${item.intervalDays}일")
                        }.joinToString(" · ")
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                        if (due) {
                            Text("점검 필요", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Button(onClick = {
                        prefs.setMaintenanceBaseline(item.key, totalDistance, now)
                        refreshTick++
                    }) { Text("점검 완료") }
                }
            }
        }
    }
}

private const val MIN_BASELINE = 5
