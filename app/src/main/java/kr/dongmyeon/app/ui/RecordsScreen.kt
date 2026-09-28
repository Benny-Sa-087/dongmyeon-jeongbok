package kr.dongmyeon.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.dongmyeon.app.data.AchievedEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

fun formatDateTime(millis: Long): String =
    FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun methodLabel(method: String) = when (method) {
    "POINTS" -> "머무름"
    "CROSSING" -> "통과"
    else -> method
}

/** 최근 달성 지역 목록(최신순) */
@Composable
fun RecordsScreen(achieved: List<AchievedEntity>) {
    if (achieved.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("아직 달성한 지역이 없습니다.\n설정 탭에서 기록을 시작하세요.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
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
