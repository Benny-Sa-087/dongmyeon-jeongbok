package kr.dongmyeon.app.tracking

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kr.dongmyeon.app.data.ReminderSchedule
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 설정한 요일·시간마다 "실행 알림"을 보내도록 예약한다.
 * WorkManager 주기 작업은 최초 지연 시간(initialDelay)만 목표 요일·시간에 맞추고,
 * 그 뒤로는 7일 간격으로 반복되므로 매주 같은 요일·시간에 울린다(오차는 수 분 이내).
 */
object ReminderScheduler {
    private const val NAME = "weekly-reminder"

    fun apply(context: Context, schedule: ReminderSchedule) {
        val wm = WorkManager.getInstance(context)
        if (!schedule.enabled) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNext(schedule), TimeUnit.MILLISECONDS)
            .build()
        // REPLACE: 사용자가 요일/시간을 바꾸면 새 시각 기준으로 다시 계산해야 하므로 기존 예약을 갈아 끼운다.
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
    }

    /** 앱 시작 시 호출: 예약이 이미 있으면 그대로 두고(REPLACE 하면 다음 알림 시각이 매번 밀림), 없으면 새로 건다. */
    fun ensureScheduled(context: Context, schedule: ReminderSchedule) {
        if (!schedule.enabled) return
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNext(schedule), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun delayUntilNext(schedule: ReminderSchedule): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.DAY_OF_WEEK, schedule.dayOfWeek)
            set(Calendar.HOUR_OF_DAY, schedule.hour)
            set(Calendar.MINUTE, schedule.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 7)
        return target.timeInMillis - now.timeInMillis
    }
}
