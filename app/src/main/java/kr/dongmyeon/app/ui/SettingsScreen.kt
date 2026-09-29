package kr.dongmyeon.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import kr.dongmyeon.app.data.AppPrefs
import kr.dongmyeon.app.data.LiveStatus
import kr.dongmyeon.app.data.ReminderSchedule
import kr.dongmyeon.app.data.VisitRepository
import kr.dongmyeon.app.tracking.Permissions
import kr.dongmyeon.app.tracking.ReminderScheduler
import kr.dongmyeon.app.tracking.ReminderWorker
import kr.dongmyeon.app.tracking.TrackingService
import java.util.Calendar

private val DAY_LABELS = listOf(
    Calendar.MONDAY to "월", Calendar.TUESDAY to "화", Calendar.WEDNESDAY to "수",
    Calendar.THURSDAY to "목", Calendar.FRIDAY to "금", Calendar.SATURDAY to "토", Calendar.SUNDAY to "일",
)

@Composable
fun SettingsScreen(
    repo: VisitRepository,
    running: Boolean,
    live: LiveStatus,
    prefs: AppPrefs,
    offlineMode: Boolean,
    reminder: ReminderSchedule,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 설정 화면에서 돌아올 때마다 권한 상태 새로 읽기
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    var confirmClear by remember { mutableStateOf(false) }

    val fine = remember(refresh) { Permissions.hasFineLocation(context) }
    val background = remember(refresh) { Permissions.hasBackgroundLocation(context) }
    val notif = remember(refresh) { Permissions.hasNotifications(context) }
    val battery = remember(refresh) { Permissions.ignoresBatteryOptimization(context) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refresh++
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            TrackingService.start(context)
            if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(context)) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("위치 기록") {
            Text(if (running) "● 기록 중" else "○ 정지됨", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            if (running) {
                Button(
                    onClick = { TrackingService.stop(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("기록 정지") }
            } else {
                Button(onClick = {
                    if (fine) {
                        TrackingService.start(context)
                        if (Build.VERSION.SDK_INT >= 33 && !notif) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        locationLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("기록 시작") }
            }
            Text(
                "한 번 켜두면 계속 그대로 두시면 됩니다. 여행 시작·종료를 직접 누를 필요 없이, " +
                    "움직이면 자동으로 여행이 시작되고 20분 이상 멈춰 있으면 자동으로 마감됩니다.\n" +
                    "100m 이상 이동할 때만 위치를 받아 배터리를 아낍니다. 기록 중에는 상단에 알림이 표시됩니다.\n" +
                    "재부팅되거나 시스템이 기록을 강제 종료해도 최대 15분 안에 자동으로 다시 켜집니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("권한") {
            PermissionRow("정확한 위치", fine) {
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
            PermissionRow("위치 '항상 허용' (화면 꺼져도 기록)", background) {
                if (!fine) {
                    locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                } else {
                    // Android 11 이상에서는 앱 위치 권한 설정 화면으로 이동함
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionRow("알림", notif) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            PermissionRow("배터리 최적화 제외", battery) { requestIgnoreBattery(context) }
            TextButton(onClick = { openAppSettings(context) }) { Text("앱 설정 화면 열기") }
        }

        Section("판정 확인용 정보") {
            val f = live.lastFix
            Text("받은 위치 ${live.receivedCount}개 · 유효 ${live.validCount}개")
            if (f != null) {
                Text(
                    "마지막: %.5f, %.5f · 정확도 %s · %s".format(
                        f.lat, f.lng,
                        f.accuracyM?.let { "%.0fm".format(it) } ?: "없음",
                        if (live.lastFixValid) "유효" else "무시(50m 초과)",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("현재 지역: ${live.currentRegionName ?: "-"}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("아직 받은 위치가 없습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("지도") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("오프라인 모드")
                    Text(
                        "켜면 배경지도를 아예 요청하지 않습니다(회색 배경 + 경계·색칠만 표시). " +
                            "지도를 볼 때 대략적 위치가 지도 서버로 나가는 것을 막고 싶을 때 켜세요. " +
                            "달성 판정은 이 설정과 무관하게 항상 기기 안에서만 이루어집니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = offlineMode, onCheckedChange = { prefs.setOfflineMode(it) })
            }
        }

        Section("실행 알림") {
            Text(
                "설정한 요일·시간이 되면 지금 위치에서 가까운 미달성 지역 3곳을 알림으로 바로 추천합니다. " +
                    "계획을 따로 세울 필요 없이 알림만 보고 나가면 됩니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("알림 켜기", modifier = Modifier.weight(1f))
                Switch(
                    checked = reminder.enabled,
                    onCheckedChange = {
                        val next = reminder.copy(enabled = it)
                        prefs.setReminder(next)
                        ReminderScheduler.apply(context, next)
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DAY_LABELS.forEach { (day, label) ->
                    FilterChip(
                        selected = reminder.dayOfWeek == day,
                        onClick = {
                            val next = reminder.copy(dayOfWeek = day)
                            prefs.setReminder(next)
                            ReminderScheduler.apply(context, next)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "시간: %02d:%02d".format(reminder.hour, reminder.minute),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = {
                    TimePickerDialog(
                        context,
                        { _, h, m ->
                            val next = reminder.copy(hour = h, minute = m)
                            prefs.setReminder(next)
                            ReminderScheduler.apply(context, next)
                        },
                        reminder.hour, reminder.minute, true,
                    ).show()
                }) { Text("시간 변경") }
            }
            TextButton(onClick = {
                WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReminderWorker>().build())
            }) { Text("지금 테스트 알림 보내기") }
        }

        Section("데이터") {
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                Text("달성 기록 모두 지우기")
            }
            Text("백업(JSON 내보내기/불러오기)은 3단계에서 추가됩니다.", style = MaterialTheme.typography.bodySmall)
        }

        Section("경계 데이터 출처") {
            Text(
                "읍면동 경계: 통계청 SGIS 행정동 경계(공공누리 제1유형)를 가공한 " +
                    "vuski/admdongkor (CC BY 4.0), 2026-07-01 기준. 앱 용량을 위해 단순화함.\n" +
                    "배경지도: OpenFreeMap, © OpenStreetMap contributors",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("기록 지우기") },
            text = { Text("달성한 지역과 방문 기록이 모두 삭제됩니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { repo.clearAll() }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (granted) "✅ " else "⚠️ ") + label, modifier = Modifier.weight(1f))
        if (!granted) TextButton(onClick = onRequest) { Text("허용") }
    }
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBattery(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${context.packageName}"))
    )
}
