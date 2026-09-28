package kr.dongmyeon.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * 실행 알림 일정. dayOfWeek 는 [Calendar.SUNDAY]([Calendar.SUNDAY]=1) ~ [Calendar.SATURDAY](=7).
 */
data class ReminderSchedule(
    val enabled: Boolean,
    val dayOfWeek: Int,
    val hour: Int,
    val minute: Int,
)

/** 간단한 설정값(SharedPreferences) */
class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 켜면 지도 배경 타일을 아예 요청하지 않는다(읍면동 경계·색칠만 표시).
     * 판정은 이 설정과 무관하게 항상 내장 데이터로 오프라인 동작한다.
     */
    private val _offlineMode = MutableStateFlow(prefs.getBoolean(KEY_OFFLINE, false))
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    fun setOfflineMode(on: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE, on).apply()
        _offlineMode.value = on
    }

    private val _reminder = MutableStateFlow(
        ReminderSchedule(
            enabled = prefs.getBoolean(KEY_REMINDER_ON, false),
            dayOfWeek = prefs.getInt(KEY_REMINDER_DAY, Calendar.SATURDAY),
            hour = prefs.getInt(KEY_REMINDER_HOUR, 10),
            minute = prefs.getInt(KEY_REMINDER_MIN, 0),
        )
    )
    val reminder: StateFlow<ReminderSchedule> = _reminder.asStateFlow()

    fun setReminder(schedule: ReminderSchedule) {
        prefs.edit()
            .putBoolean(KEY_REMINDER_ON, schedule.enabled)
            .putInt(KEY_REMINDER_DAY, schedule.dayOfWeek)
            .putInt(KEY_REMINDER_HOUR, schedule.hour)
            .putInt(KEY_REMINDER_MIN, schedule.minute)
            .apply()
        _reminder.value = schedule
    }

    companion object {
        private const val FILE = "app_prefs"
        private const val KEY_OFFLINE = "offline_map_mode"
        private const val KEY_REMINDER_ON = "reminder_on"
        private const val KEY_REMINDER_DAY = "reminder_day"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_REMINDER_MIN = "reminder_min"
    }
}
