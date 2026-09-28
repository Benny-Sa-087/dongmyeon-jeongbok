package kr.dongmyeon.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    companion object {
        private const val FILE = "app_prefs"
        private const val KEY_OFFLINE = "offline_map_mode"
    }
}
