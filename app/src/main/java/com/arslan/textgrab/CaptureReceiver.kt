package com.arslan.textgrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Lets adb trigger a capture, e.g. for automation or key bindings:
 *
 *   adb shell am broadcast -a com.arslan.textgrab.action.CAPTURE -p com.arslan.textgrab
 *
 * Guarded by the DUMP permission in the manifest, which the adb shell holds
 * but regular apps cannot obtain, so other apps cannot trigger captures.
 */
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
                        // No shade to dismiss; capture immediately.
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
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(MainActivity.EXTRA_LATEST_SCREENSHOT, true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    // SINGLE_TOP: reuse a running MainActivity via onNewIntent
                    // instead of letting CLEAR_TOP recreate it.
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        })
    }
}
