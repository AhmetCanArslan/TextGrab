package com.arslan.textgrab

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService

class ScreenshotTileService : TileService() {

    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 31) {
            val capture = CaptureAccessibilityService.instance
            if (capture != null) {

                capture.captureScreenAndOpen()
                return
            }
        }
        val intent = MainActivity.openIntent(this, MainActivity.EXTRA_LATEST_SCREENSHOT)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
