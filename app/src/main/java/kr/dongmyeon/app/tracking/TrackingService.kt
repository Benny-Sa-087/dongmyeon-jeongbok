package kr.dongmyeon.app.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.dongmyeon.app.DongApp
import kr.dongmyeon.app.R
import kr.dongmyeon.app.ui.MainActivity
import kr.dongmyeon.core.Fix

/**
 * 위치 기록 포그라운드 서비스.
 * 배터리 절약: 직전 위치에서 100m 이상 움직였을 때만 위치를 받는다.
 */
class TrackingService : LifecycleService() {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val fixes = Channel<Fix>(Channel.UNLIMITED)
    private var updatesRequested = false
    private var achievedCount = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                fixes.trySend(
                    Fix(
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                        timeMillis = loc.time,
                    )
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val repo = DongApp.repo(this)
        lifecycleScope.launch {
            repo.achieved.collect {
                achievedCount = it.size
                updateNotification()
            }
        }
        // 점은 받은 순서대로 하나씩 처리
        lifecycleScope.launch {
            for (fix in fixes) {
                val found = repo.onFix(fix)
                found.forEach { notifyAchieved(it.name) }
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        if (!Permissions.hasFineLocation(this)) {
            // 위치 권한 없이 위치 포그라운드 서비스를 띄우면 Android 14+ 에서 예외 발생
            setRecordingPref(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        setRecordingPref(this, true)
        _running.value = true
        WatchdogWorker.schedule(this)

        val fresh = intent?.action == ACTION_START
        lifecycleScope.launch {
            // 사용자가 새로 시작한 경우에만 이전 기록의 마지막 점과 끊는다.
            // (시스템이 서비스를 재시작한 경우엔 이어서 기록)
            if (fresh && !updatesRequested) DongApp.repo(this@TrackingService).breakTrack()
            requestUpdates()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (updatesRequested) return
        if (!Permissions.hasFineLocation(this)) {
            stopTracking()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        updatesRequested = true
    }

    private fun stopTracking() {
        if (updatesRequested) fused.removeLocationUpdates(callback)
        updatesRequested = false
        setRecordingPref(this, false)
        _running.value = false
        // 사용자가 직접 정지한 경우에만 감시를 끈다. 시스템이 서비스를 강제 종료한 경우엔
        // stopTracking() 을 안 거치므로(onDestroy 만 호출됨) 감시가 계속 남아 복구해준다.
        WatchdogWorker.cancel(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (updatesRequested) fused.removeLocationUpdates(callback)
        _running.value = false
        super.onDestroy()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val live = DongApp.repo(this).live.value
        val where = live.currentRegionName?.substringAfterLast(' ')
        val text = buildString {
            append("달성 ${achievedCount}곳")
            if (where != null) append(" · 현재 $where")
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DongApp.CH_TRACKING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("동면 정복 · 위치 기록 중")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(0, "기록 정지", stop)
            .build()
    }

    private fun updateNotification() {
        if (!_running.value) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun notifyAchieved(name: String) {
        val n = NotificationCompat.Builder(this, DongApp.CH_ACHIEVED)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("새 지역 달성!")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(name.hashCode(), n)
    }

    companion object {
        const val ACTION_START = "kr.dongmyeon.app.START"
        const val ACTION_STOP = "kr.dongmyeon.app.STOP"
        const val ACTION_RESUME = "kr.dongmyeon.app.RESUME"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 10_000L
        private const val MIN_INTERVAL_MS = 5_000L
        private const val MIN_DISTANCE_M = 100f
        private const val PREFS = "tracking"
        private const val KEY_RECORDING = "recording"

        private val _running = MutableStateFlow(false)
        /** 서비스 실행 여부(화면 표시용) */
        val isRunning: StateFlow<Boolean> = _running.asStateFlow()

        /** fresh=true: 사용자가 새로 시작(이전 경로와 잇지 않음), false: 중단된 기록 이어가기 */
        fun start(context: Context, fresh: Boolean = true) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(if (fresh) ACTION_START else ACTION_RESUME),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        fun wasRecording(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECORDING, false)

        private fun setRecordingPref(context: Context, on: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECORDING, on).apply()
    }
}
