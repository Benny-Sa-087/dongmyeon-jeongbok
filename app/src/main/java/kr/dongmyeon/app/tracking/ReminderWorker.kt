package kr.dongmyeon.app.tracking

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.dongmyeon.app.DongApp
import kr.dongmyeon.app.R
import kr.dongmyeon.app.ui.MainActivity
import kr.dongmyeon.core.Geo
import kr.dongmyeon.core.Region

/**
 * 설정한 요일·시간에 울리는 "실행 알림". 계획을 미리 세워야 움직이는 사람을 위해,
 * "어디로 갈지"를 앱이 대신 골라 알림에 바로 담아준다 — 열어서 생각할 필요 없이 그대로 나가면 됨.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val repo = DongApp.repo(ctx)

        val fix = repo.lastKnownFix() ?: freshLocationOrNull(ctx)
        val text = if (fix == null) {
            "위치 정보가 없어요. 기록을 한 번 켜거나 지도를 열어보면 다음부터 근처 미달성 지역을 추천해줍니다."
        } else {
            val achieved = repo.achievedCodes()
            val nearest = repo.regions().regions
                .filter { it.code !in achieved }
                .sortedBy { Geo.distanceMeters(fix.lat, fix.lng, it.centroidLat, it.centroidLng) }
                .take(3)
            if (nearest.isEmpty()) {
                "축하합니다! 등록된 지역을 모두 달성했습니다 🎉"
            } else {
                nearest.joinToString(" · ") { region -> "${region.shortName}(${distanceLabel(fix, region)})" }
            }
        }
        notify(ctx, text)
        return Result.success()
    }

    private fun distanceLabel(fix: kr.dongmyeon.core.Fix, region: Region): String {
        val km = Geo.distanceMeters(fix.lat, fix.lng, region.centroidLat, region.centroidLng) / 1000.0
        return "%.1fkm".format(km)
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshLocationOrNull(ctx: Context): kr.dongmyeon.core.Fix? {
        if (!Permissions.hasFineLocation(ctx)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val loc = Tasks.await(LocationServices.getFusedLocationProviderClient(ctx).lastLocation)
                loc?.let { kr.dongmyeon.core.Fix(it.latitude, it.longitude, it.accuracy, it.time) }
            }.getOrNull()
        }
    }

    private fun notify(ctx: Context, body: String) {
        val intent = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, DongApp.CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("오늘 가볼만한 미달성 동네")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(REMINDER_NOTIF_ID, n)
    }

    companion object {
        private const val REMINDER_NOTIF_ID = 100
    }
}
