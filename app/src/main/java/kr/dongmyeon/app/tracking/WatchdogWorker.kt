package kr.dongmyeon.app.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * "기록 중"이어야 하는데 서비스가 죽어 있으면 다시 켠다.
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
        return Result.success()
    }

    companion object {
        private const val NAME = "tracking-watchdog"

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
