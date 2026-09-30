package kr.dongmyeon.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object Permissions {
    private fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun hasFineLocation(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)

    /** 위치 "항상 허용" */
    fun hasBackgroundLocation(c: Context) = granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasNotifications(c: Context) =
        Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS)

    fun ignoresBatteryOptimization(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /** 갤러리 사진 목록을 읽을 권한(사진 자동 인식 기능용) */
    fun hasPhotoLibrary(c: Context) =
        if (Build.VERSION.SDK_INT >= 33) granted(c, Manifest.permission.READ_MEDIA_IMAGES)
        else granted(c, Manifest.permission.READ_EXTERNAL_STORAGE)

    /** 사진의 정확한 GPS EXIF 값을 읽을 권한(안드로이드 10 미만은 이 권한 자체가 없음) */
    fun hasMediaLocation(c: Context) =
        Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_MEDIA_LOCATION)
}
