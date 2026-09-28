package kr.dongmyeon.app.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 재부팅 후 "기록 중" 상태였다면 서비스를 자동으로 다시 켠다.
 * 이게 없으면 폰을 껐다 켰을 때 사용자가 앱을 직접 열기 전까지 기록이 통째로 끊긴다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TrackingService.wasRecording(context)) return
        if (!Permissions.hasFineLocation(context)) return
        TrackingService.start(context, fresh = false)
    }
}
