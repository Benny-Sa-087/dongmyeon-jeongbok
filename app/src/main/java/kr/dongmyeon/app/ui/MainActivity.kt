package kr.dongmyeon.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.dongmyeon.app.DongApp
import kr.dongmyeon.app.tracking.Permissions
import kr.dongmyeon.app.tracking.ReminderScheduler
import kr.dongmyeon.app.tracking.TrackingService
import java.io.File

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("지도", Icons.Filled.Map),
    Tab("기록", Icons.AutoMirrored.Filled.List),
    Tab("차량", Icons.Filled.DirectionsCar),
    Tab("설정", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 직전 실행에서 처리 안 된 예외로 죽었다면, 그 내용을 화면에 그대로 보여주고
        // 정상 화면 진입(=repo 접근 등)은 건너뛴다. PC/adb 없이도 원인을 확인하기 위함.
        val crashFile = File(filesDir, DongApp.CRASH_LOG_FILE)
        if (crashFile.exists()) {
            val crashText = crashFile.readText()
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) {
                    CrashScreen(crashText) {
                        crashFile.delete()
                        recreate()
                    }
                }
            }
            return
        }

        // 기록 중이었는데 앱이 종료되어 서비스가 멈춘 경우 이어서 기록
        if (TrackingService.wasRecording(this) && !TrackingService.isRunning.value &&
            Permissions.hasFineLocation(this)
        ) {
            TrackingService.start(this, fresh = false)
        }

        val repo = DongApp.repo(this)
        val prefs = DongApp.prefs(this)
        ReminderScheduler.ensureScheduled(this, prefs.reminder.value)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) {
                val achieved by repo.achieved.collectAsStateWithLifecycle(initialValue = emptyList())
                val needsReview by repo.needsReview.collectAsStateWithLifecycle(initialValue = emptyList())
                val trips by repo.trips.collectAsStateWithLifecycle(initialValue = emptyList())
                val photoCodes by repo.photoCodes.collectAsStateWithLifecycle(initialValue = emptyList())
                val live by repo.live.collectAsStateWithLifecycle()
                val running by TrackingService.isRunning.collectAsStateWithLifecycle()
                val offlineMode by prefs.offlineMode.collectAsStateWithLifecycle()
                val reminder by prefs.reminder.collectAsStateWithLifecycle()
                val total by produceState<Int?>(null) { value = repo.regions().regions.size }
                val sidoTotals by produceState<Map<String, Int>?>(null) {
                    value = repo.regions().regions.groupingBy { it.sido }.eachCount()
                }
                val routePoints by repo.routePoints.collectAsStateWithLifecycle(initialValue = emptyList())
                var tab by rememberSaveable { mutableIntStateOf(0) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (tab) {
                            0 -> MapScreen(repo, achieved, total, offlineMode, photoCodes, routePoints)
                            1 -> RecordsScreen(repo, achieved, needsReview, total, sidoTotals)
                            2 -> VehicleScreen(trips, needsReview.size, prefs)
                            else -> SettingsScreen(repo, running, live, prefs, offlineMode, reminder)
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CrashScreen(crashText: String, onClear: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("베니앱이 마지막 실행에서 오류로 종료됐어요", style = MaterialTheme.typography.titleMedium)
        Text(
            "아래 내용을 캡처해서 보내주세요.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Text(crashText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onClear, modifier = Modifier.padding(top = 16.dp)) {
            Text("확인, 앱 계속 사용하기")
        }
    }
}
