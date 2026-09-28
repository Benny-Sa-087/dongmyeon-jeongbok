package kr.dongmyeon.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.dongmyeon.app.data.TripEntity
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
fun VehicleScreen(trips: List<TripEntity>, needsReviewCount: Int) {
    val now = System.currentTimeMillis()
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
    }
}

private const val MIN_BASELINE = 5
