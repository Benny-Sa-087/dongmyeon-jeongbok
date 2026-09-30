package kr.dongmyeon.app.tracking

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** "사진 자동 인식" 설정 스위치에 맞춰 [PhotoImportWorker] 주기 작업을 걸거나 내린다. */
object PhotoImportScheduler {
    private const val PERIODIC_NAME = "photo-auto-import"
    private const val ONE_TIME_NAME = "photo-auto-import-now"

    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PhotoImportWorker>(15, TimeUnit.MINUTES).build(),
        )
        scanNow(context)
    }

    /** 앱을 열 때나 "지금 스캔하기" 버튼에서: 15분을 기다리지 않고 바로 한 번 스캔한다. */
    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhotoImportWorker>().build(),
        )
    }
}
