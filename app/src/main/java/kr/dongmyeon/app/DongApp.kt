package kr.dongmyeon.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kr.dongmyeon.app.data.AppDatabase
import kr.dongmyeon.app.data.AppPrefs
import kr.dongmyeon.app.data.VisitRepository
import org.maplibre.android.MapLibre

class DongApp : Application() {
    lateinit var repo: VisitRepository
        private set
    lateinit var prefs: AppPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        repo = VisitRepository(this, AppDatabase.create(this))
        prefs = AppPrefs(this)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACKING, "위치 기록 중", NotificationManager.IMPORTANCE_LOW).apply {
                description = "기록 중일 때 상단에 표시되는 알림"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ACHIEVED, "새 지역 달성", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "실행 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "설정한 요일·시간에 가까운 미달성 지역을 추천"
            }
        )
    }

    companion object {
        const val CH_TRACKING = "tracking"
        const val CH_ACHIEVED = "achieved"
        const val CH_REMINDER = "reminder"

        fun repo(context: Context) = (context.applicationContext as DongApp).repo
        fun prefs(context: Context) = (context.applicationContext as DongApp).prefs
    }
}
