package kr.dongmyeon.app.tracking

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.dongmyeon.app.DongApp
import kr.dongmyeon.app.R
import kr.dongmyeon.app.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * "사진 자동 인식"(설정에서 켰을 때만 동작). 휴대폰으로 찍은 사진에는 구글맵·갤러리 앱이 위치를 정확히
 * 표시할 때 쓰는 것과 같은 GPS·촬영시각이 EXIF 로 그대로 들어있다. 이를 읽어 해당 읍면동을 찾고,
 * 사진 찍은 그 시각을 실제 방문시각으로 삼아 "직접 방문"으로 자동 기록한다.
 *
 * 실시간 감지(사진 찍는 즉시)는 앱이 항상 떠 있어야만 가능해 배터리 부담이 크므로, 대신 WatchdogWorker와
 * 같은 15분 주기 WorkManager 작업으로 마지막 스캔 이후 새로 생긴 사진만 훑는다(MediaStore _ID 기준).
 */
class PhotoImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = DongApp.prefs(ctx)
        if (!prefs.photoAutoImport.value || !Permissions.hasPhotoLibrary(ctx)) return Result.success()

        val repo = DongApp.repo(ctx)
        val index = repo.regions()
        val lastId = prefs.lastPhotoImportId
        var maxId = lastId
        var scanned = 0
        val newlyAchievedNames = mutableListOf<String>()

        withContext(Dispatchers.IO) {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
            val cursor = ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Images.Media._ID} > ?", arrayOf(lastId.toString()),
                "${MediaStore.Images.Media._ID} ASC",
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    if (id > maxId) maxId = id
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val latLng = readExifLatLng(ctx, uri) ?: continue
                    val region = index.find(latLng.first, latLng.second) ?: continue
                    val takenAt = c.getLong(dateCol).takeIf { it > 0 } ?: System.currentTimeMillis()
                    val achievement = repo.importPhotoFromGallery(region.code, uri, takenAt)
                    scanned++
                    achievement?.let { newlyAchievedNames += it.name }
                }
            }
        }

        if (maxId > lastId) prefs.lastPhotoImportId = maxId
        if (scanned > 0) {
            prefs.setLastPhotoImportSummary("사진 ${scanned}장에서 위치 인식 (${TIME_FORMAT.format(java.util.Date())})")
        }
        if (newlyAchievedNames.isNotEmpty()) notifyAchieved(ctx, newlyAchievedNames)
        return Result.success()
    }

    /** EXIF 의 정확한 GPS 는 ACCESS_MEDIA_LOCATION 권한과 원본 요청(setRequireOriginal)이 있어야 읽힌다. */
    private fun readExifLatLng(ctx: Context, uri: Uri): Pair<Double, Double>? = runCatching {
        val target = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
        ctx.contentResolver.openInputStream(target)?.use { stream ->
            val exif = ExifInterface(stream)
            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) latLong[0].toDouble() to latLong[1].toDouble() else null
        }
    }.getOrNull()

    private fun notifyAchieved(ctx: Context, names: List<String>) {
        val body = names.joinToString(" · ")
        val intent = PendingIntent.getActivity(
            ctx, 4, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, DongApp.CH_ACHIEVED)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("사진에서 새 지역 자동 인식!")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    companion object {
        private const val NOTIF_ID = 102
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.KOREA)
    }
}
