package com.arslan.textgrab

import android.app.Activity
import android.os.Build
import android.os.Bundle

class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capture = if (Build.VERSION.SDK_INT >= 31) CaptureAccessibilityService.instance else null
        if (capture != null) {
            capture.captureScreenAndOpen(dismissShade = false, delayMs = ASSIST_GESTURE_DELAY_MS)
        } else {
            startActivity(MainActivity.openIntent(this, MainActivity.EXTRA_LATEST_SCREENSHOT))
        }
        finish()
    }

    private companion object {

        const val ASSIST_GESTURE_DELAY_MS = 300L
    }
}
