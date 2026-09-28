package kr.dongmyeon.app.tracking

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kr.dongmyeon.app.DongApp
import kr.dongmyeon.app.R
import kr.dongmyeon.app.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * "기록 중"이어야 하는데 서비스가 죽어 있으면 다시 켠다.
 * 겸사겸사 15분마다 "출퇴근 외 진짜 여행이 며칠째 없는지"도 확인해서, 기준을 넘으면 하루 한 번만 알려준다.
 *
 * 안드로이드의 START_STICKY 는 시스템에 여유가 있을 때만 재시작해주는 힌트일 뿐 강제가 아니고,
 * 제조사(삼성/샤오미 등)의 배터리 관리 기능이 서비스를 죽이면 그 뒤로 아무도 다시 켜주지 않는다.
 * WorkManager 는 OS 의 JobScheduler/AlarmManager 를 직접 써서 앱이 죽어 있어도 시스템이 깨워 실행하므로,
 * 이 워커가 기록이 조용히 멈춰 있는 상황을 실제로 복구하는 역할을 한다.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (TrackingService.wasRecording(ctx) && !TrackingService.isRunning.value &&
            Permissions.hasFineLocation(ctx)
        ) {
            TrackingService.start(ctx, fresh = false)
        }
        checkTripStale(ctx)
        return Result.success()
    }

    private suspend fun checkTripStale(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / 86_400_000L
        if (prefs.getLong(KEY_LAST_ALERT_DAY, -1) == today) return // 하루 한 번만

        val lastTrip = DongApp.repo(ctx).lastTripAt() ?: return
        val daysSince = (System.currentTimeMillis() - lastTrip) / 86_400_000L
        if (daysSince < STALE_DAYS) return

        prefs.edit().putLong(KEY_LAST_ALERT_DAY, today).apply()
        val intent = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, DongApp.CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("출퇴근 말고 진짜 여행, ${daysSince}일째 없어요")
            .setContentText("오버랜딩 세팅도 오래 쉬면 배터리가 빠집니다. 이번 주말 코스를 잡아볼까요?")
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(STALE_NOTIF_ID, n)
    }

    companion object {
        private const val NAME = "tracking-watchdog"
        private const val PREFS = "watchdog"
        private const val KEY_LAST_ALERT_DAY = "last_trip_alert_day"
        private const val STALE_DAYS = 10
        private const val STALE_NOTIF_ID = 101

        /** 기록을 시작할 때 호출. 15분(WorkManager 최소 주기)마다 감시한다. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** 기록을 정지할 때 호출. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
