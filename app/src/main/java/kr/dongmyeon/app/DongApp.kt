package kr.dongmyeon.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kr.dongmyeon.app.data.AppDatabase
import kr.dongmyeon.app.data.AppPrefs
import kr.dongmyeon.app.data.VisitRepository
import org.maplibre.android.MapLibre
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class DongApp : Application() {
    lateinit var repo: VisitRepository
        private set
    lateinit var prefs: AppPrefs
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // PC/adb 없이도 원인을 알 수 있도록, 처리 안 된 예외를 파일로 남기고
        // 다음 실행 때 MainActivity 에서 화면에 그대로 보여준다.
        // attachBaseContext 는 앱의 ContentProvider 들이 생성되기도 전에 실행되므로
        // 어떤 단계에서 죽더라도 이 핸들러가 먼저 잡는다.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(base.filesDir, CRASH_LOG_FILE).writeText(sw.toString())
            } catch (_: Throwable) {
                // 로그 남기다 또 죽으면 곤란하니 무시
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

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
        const val CRASH_LOG_FILE = "last_crash.txt"

        fun repo(context: Context) = (context.applicationContext as DongApp).repo
        fun prefs(context: Context) = (context.applicationContext as DongApp).prefs
    }
}
