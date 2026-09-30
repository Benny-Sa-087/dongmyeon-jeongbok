package kr.dongmyeon.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.dongmyeon.app.data.AchievedEntity
import kr.dongmyeon.app.data.NeedsReviewEntity
import kr.dongmyeon.app.data.VisitRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

fun formatDateTime(millis: Long): String =
    FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun methodLabel(method: String) = when (method) {
    "POINTS" -> "머무름"
    "CROSSING" -> "통과"
    "ON_SITE" -> "직접 방문"
    else -> method
}

/** 최근 달성 지역 목록(최신순) + 확인 필요 지역 + 전체/시도별 통계 */
@Composable
fun RecordsScreen(
    repo: VisitRepository,
    achieved: List<AchievedEntity>,
    needsReview: List<NeedsReviewEntity>,
    totalRegions: Int?,
    sidoTotals: Map<String, Int>?,
) {
    val scope = rememberCoroutineScope()

    if (achieved.isEmpty() && needsReview.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("아직 달성한 지역이 없습니다.\n설정 탭에서 기록을 시작하세요.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { StatsSection(achieved, totalRegions, sidoTotals) }

        if (needsReview.isNotEmpty()) {
            item {
                Text(
                    "확인 필요 ${needsReview.size}곳",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                )
                Text(
                    "GPS가 잠깐 끊긴 구간이라 자동으로 인정하지 않았습니다. 실제로 지나갔다면 확정해주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(needsReview, key = { "review-" + it.code }) { r ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(r.name.substringAfter(' '), style = MaterialTheme.typography.titleSmall)
                        Text(formatDateTime(r.flaggedAt), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { scope.launch { repo.dismissReview(r.code) } }) { Text("무시") }
                            TextButton(onClick = { scope.launch { repo.confirmReview(r.code) } }) { Text("실제 방문 맞음") }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "달성 ${achieved.size}곳",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(achieved, key = { it.code }) { a ->
            ListItem(
                headlineContent = { Text(a.name.substringAfter(' ')) },
                supportingContent = {
                    Column {
                        Text("${formatDateTime(a.firstVisitedAt)} · ${methodLabel(a.method)}")
                    }
                },
                overlineContent = { Text(a.sido) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun StatsSection(achieved: List<AchievedEntity>, totalRegions: Int?, sidoTotals: Map<String, Int>?) {
    Column(Modifier.padding(16.dp)) {
        Text("통계", style = MaterialTheme.typography.titleLarge)
        if (totalRegions != null && totalRegions > 0) {
            val pct = achieved.size * 100.0 / totalRegions
            Text(
                "전체 ${achieved.size} / ${totalRegions}곳 (${"%.1f".format(pct)}%)",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            LinearProgressIndicator(
                progress = { (pct / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
            )
        }
        if (sidoTotals != null) {
            val achievedBySido = achieved.groupingBy { it.sido }.eachCount()
            sidoTotals.entries.sortedByDescending { it.value }.forEach { (sido, total) ->
                val done = achievedBySido[sido] ?: 0
                val pct = if (total > 0) done * 100.0 / total else 0.0
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(sido, style = MaterialTheme.typography.bodyMedium)
                    Text("$done/$total (${"%.0f".format(pct)}%)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    HorizontalDivider()
}
