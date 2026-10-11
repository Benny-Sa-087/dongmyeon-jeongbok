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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.dongmyeon.app.DongApp
import kr.dongmyeon.app.R
import kr.dongmyeon.app.ui.MainActivity
import kr.dongmyeon.core.Fix
import kr.dongmyeon.core.Method

/**
 * 위치 기록 포그라운드 서비스.
 * 배터리 절약: 직전 위치에서 100m 이상 움직였을 때만 위치를 받는다.
 */
class TrackingService : LifecycleService() {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val fixes = Channel<Fix>(Channel.UNLIMITED)
    private var updatesRequested = false
    private var achievedCount = 0

    /**
     * 거리 필터(minUpdateDistanceMeters)는 "앱에 콜백을 전달할지"만 걸러줄 뿐, GPS 칩 자체는
     * 그 간격(FAST_INTERVAL_MS)마다 계속 깨어나 위치를 확인한다. 즉 차가 완전히 서 있어도 10초마다
     * GPS를 계속 쓰는 셈이라 배터리 소모가 크다. 그래서 한동안 "믿을 만한" 콜백이 전혀 없으면
     * (=안 움직이는 중) 주기를 늘린 저전력 모드로 바꾸고, 다시 오면(=움직이기 시작) 빠른 모드로 되돌린다.
     *
     * 정확도가 나쁜 콜백은 움직임 신호로 치지 않는다. 실내(사무실 등)에서는 GPS 신호가 벽에 반사되며
     * 튀어서(멀티패스), 실제로는 가만히 있어도 수백m 이동한 것처럼 잘못 잡히는 경우가 흔하다. 그런 값까지
     * "움직였다"로 인정하면 하루 종일 책상에 앉아 있어도 저전력 모드로 안 내려가 버린다. 판정 로직
     * (VisitJudge)이 쓰는 것과 같은 정확도 기준(50m)을 그대로 써서, 신뢰할 수 있는 움직임만 반영한다.
     */
    private enum class PollMode { FAST, SLOW }
    private var pollMode = PollMode.FAST
    private var lastCallbackAt = System.currentTimeMillis()

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
            if (result.locations.any { it.hasAccuracy() && it.accuracy <= RELIABLE_ACCURACY_M }) {
                lastCallbackAt = System.currentTimeMillis()
                if (pollMode == PollMode.SLOW) switchPollMode(PollMode.FAST)
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
                found.forEach {
                    if (it.method == Method.NEEDS_REVIEW) notifyNeedsReview(it.name) else notifyAchieved(it.name)
                }
                updateNotification()
            }
        }
        // "여행"은 버튼이 아니라 움직임 공백으로 자동 마감된다. 1분마다 확인한다.
        // 겸사겸사 같은 주기로 "한동안 안 움직였는지"도 봐서 저전력 모드로 전환한다.
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000)
                repo.checkTripTimeout()
                if (pollMode == PollMode.FAST && System.currentTimeMillis() - lastCallbackAt >= STATIONARY_SWITCH_MS) {
                    switchPollMode(PollMode.SLOW)
                }
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
            // (시스템이 서비스를 재시작한 경우엔 이어서 기록) 여행 구간 자체는 버튼과 무관하게
            // 다음 위치가 들어올 때 자동으로 열린다(VisitRepository.onFix 참고).
            if (fresh && !updatesRequested) {
                DongApp.repo(this@TrackingService).breakTrack()
            }
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
        pollMode = PollMode.FAST
        lastCallbackAt = System.currentTimeMillis()
        fused.requestLocationUpdates(buildRequest(PollMode.FAST), callback, Looper.getMainLooper())
        updatesRequested = true
    }

    @SuppressLint("MissingPermission")
    private fun switchPollMode(mode: PollMode) {
        if (pollMode == mode || !updatesRequested) return
        pollMode = mode
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(buildRequest(mode), callback, Looper.getMainLooper())
    }

    private fun buildRequest(mode: PollMode): LocationRequest {
        val interval = if (mode == PollMode.FAST) INTERVAL_MS else SLOW_INTERVAL_MS
        val minInterval = if (mode == PollMode.FAST) MIN_INTERVAL_MS else SLOW_MIN_INTERVAL_MS
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun stopTracking() {
        if (updatesRequested) fused.removeLocationUpdates(callback)
        updatesRequested = false
        setRecordingPref(this, false)
        _running.value = false
        // 사용자가 직접 정지한 경우에만 감시를 끈다. 시스템이 서비스를 강제 종료한 경우엔
        // stopTracking() 을 안 거치므로(onDestroy 만 호출됨) 감시가 계속 남아 복구해준다.
        WatchdogWorker.cancel(this)
        val repo = DongApp.repo(this)
        lifecycleScope.launch { repo.endSession() }
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
            .setContentTitle("Benny Outbound · 위치 기록 중")
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

    private fun notifyNeedsReview(name: String) {
        val n = NotificationCompat.Builder(this, DongApp.CH_ACHIEVED)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("확인이 필요해요")
            .setContentText("$name · GPS가 잠깐 끊긴 구간이라 기록 탭에서 확인해주세요")
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
        /** VisitJudge 기본 정확도 기준과 동일. 이보다 부정확한 콜백은 움직임 신호로 안 친다. */
        private const val RELIABLE_ACCURACY_M = 50f
        /** 저전력 모드(정차 중으로 판단될 때)의 GPS 확인 주기 */
        private const val SLOW_INTERVAL_MS = 5 * 60_000L
        private const val SLOW_MIN_INTERVAL_MS = 4 * 60_000L
        /** 이만큼 위치 콜백이 전혀 없으면(=안 움직이는 중) 저전력 모드로 전환 */
        private const val STATIONARY_SWITCH_MS = 10 * 60_000L
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
