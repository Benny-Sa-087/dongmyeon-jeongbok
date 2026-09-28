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
}
