package com.arslan.textgrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class CaptureReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CAPTURE = "com.arslan.textgrab.action.CAPTURE"
        const val ACTION_LATEST_SCREENSHOT = "com.arslan.textgrab.action.LATEST_SCREENSHOT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CAPTURE -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    val capture = CaptureAccessibilityService.instance
                    if (capture != null) {

                        capture.captureScreenAndOpen(dismissShade = false)
                        return
                    }
                }
                openLatestScreenshot(context)
            }
            ACTION_LATEST_SCREENSHOT -> openLatestScreenshot(context)
        }
    }

    private fun openLatestScreenshot(context: Context) {
        context.startActivity(
            MainActivity.openIntent(context, MainActivity.EXTRA_LATEST_SCREENSHOT)
        )
    }
}
