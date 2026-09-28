package kr.dongmyeon.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.dongmyeon.app.DongApp
import kr.dongmyeon.app.tracking.Permissions
import kr.dongmyeon.app.tracking.TrackingService

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("지도", Icons.Filled.Map),
    Tab("기록", Icons.AutoMirrored.Filled.List),
    Tab("설정", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 기록 중이었는데 앱이 종료되어 서비스가 멈춘 경우 이어서 기록
        if (TrackingService.wasRecording(this) && !TrackingService.isRunning.value &&
            Permissions.hasFineLocation(this)
        ) {
            TrackingService.start(this, fresh = false)
        }

        val repo = DongApp.repo(this)
        val prefs = DongApp.prefs(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32))) {
                val achieved by repo.achieved.collectAsStateWithLifecycle(initialValue = emptyList())
                val live by repo.live.collectAsStateWithLifecycle()
                val running by TrackingService.isRunning.collectAsStateWithLifecycle()
                val offlineMode by prefs.offlineMode.collectAsStateWithLifecycle()
                val total by produceState<Int?>(null) { value = repo.regions().regions.size }
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
                            0 -> MapScreen(achieved, total, offlineMode)
                            1 -> RecordsScreen(achieved)
                            else -> SettingsScreen(repo, running, live, prefs, offlineMode)
                        }
                    }
                }
            }
        }
    }
}
