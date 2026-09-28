package kr.dongmyeon.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kr.dongmyeon.app.data.AppDatabase
import kr.dongmyeon.app.data.VisitRepository
import org.maplibre.android.MapLibre

class DongApp : Application() {
    lateinit var repo: VisitRepository
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        repo = VisitRepository(this, AppDatabase.create(this))
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
    }

    companion object {
        const val CH_TRACKING = "tracking"
        const val CH_ACHIEVED = "achieved"

        fun repo(context: Context) = (context.applicationContext as DongApp).repo
    }
}
