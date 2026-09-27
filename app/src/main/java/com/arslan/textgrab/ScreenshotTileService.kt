package com.arslan.textgrab

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class ScreenshotTileService : TileService() {

    override fun onClick() {
        val intent = Intent(this, CaptureActivity::class.java)
            .putExtra(CaptureActivity.EXTRA_DELAY_MS, SHADE_COLLAPSE_MS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private companion object {

        const val SHADE_COLLAPSE_MS = 500L
    }
}
