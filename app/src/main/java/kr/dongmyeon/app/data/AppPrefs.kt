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

    /**
     * 정비 항목별 "마지막 점검 시점" 기준(그때까지의 누적 주행거리, 그때 시각).
     * 처음 조회하는 항목이면 지금을 기준으로 새로 잡는다(과거 주행을 갑자기 "밀린 정비"로 잡지 않기 위해).
     */
    fun maintenanceBaseline(key: String, currentTotalDistanceMeters: Double): Pair<Double, Long> {
        val distKey = "maint_${key}_dist"
        val timeKey = "maint_${key}_time"
        if (!prefs.contains(distKey)) {
            val now = System.currentTimeMillis()
            prefs.edit().putLong(distKey, currentTotalDistanceMeters.toRawBits()).putLong(timeKey, now).apply()
            return currentTotalDistanceMeters to now
        }
        return Double.fromBits(prefs.getLong(distKey, 0L)) to prefs.getLong(timeKey, System.currentTimeMillis())
    }

    /** 정비 완료 체크: 이 시점(지금까지 주행거리·현재 시각)을 새 기준으로 삼는다. */
    fun setMaintenanceBaseline(key: String, totalDistanceMeters: Double, timeMillis: Long) {
        prefs.edit()
            .putLong("maint_${key}_dist", totalDistanceMeters.toRawBits())
            .putLong("maint_${key}_time", timeMillis)
            .apply()
    }

    /**
     * "사진 자동 인식" 기능(갤러리 사진의 EXIF GPS·촬영시각으로 방문 자동 기록). 기본은 꺼짐(opt-in) —
     * 갤러리 전체를 읽는 권한이 필요한 기능이라 사용자가 직접 켜야 동작한다.
     */
    private val _photoAutoImport = MutableStateFlow(prefs.getBoolean(KEY_PHOTO_AUTO, false))
    val photoAutoImport: StateFlow<Boolean> = _photoAutoImport.asStateFlow()

    fun setPhotoAutoImport(on: Boolean) {
        prefs.edit().putBoolean(KEY_PHOTO_AUTO, on).apply()
        _photoAutoImport.value = on
    }

    /** 마지막으로 스캔한 갤러리 사진의 MediaStore _ID(다음 스캔은 이보다 큰 것만 확인) */
    var lastPhotoImportId: Long
        get() = prefs.getLong(KEY_PHOTO_LAST_ID, 0L)
        set(v) { prefs.edit().putLong(KEY_PHOTO_LAST_ID, v).apply() }

    fun setLastPhotoImportSummary(text: String) {
        prefs.edit().putString(KEY_PHOTO_SUMMARY, text).apply()
    }

    fun lastPhotoImportSummary(): String? = prefs.getString(KEY_PHOTO_SUMMARY, null)

    companion object {
        private const val FILE = "app_prefs"
        private const val KEY_OFFLINE = "offline_map_mode"
        private const val KEY_REMINDER_ON = "reminder_on"
        private const val KEY_REMINDER_DAY = "reminder_day"
        private const val KEY_REMINDER_HOUR = "reminder_hour"
        private const val KEY_REMINDER_MIN = "reminder_min"
        private const val KEY_PHOTO_AUTO = "photo_auto_import"
        private const val KEY_PHOTO_LAST_ID = "photo_auto_last_id"
        private const val KEY_PHOTO_SUMMARY = "photo_auto_summary"
    }
}
