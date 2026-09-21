package com.arslan.textgrab

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capture = if (Build.VERSION.SDK_INT >= 31) CaptureAccessibilityService.instance else null
        if (capture != null) {
            capture.captureScreenAndOpen(dismissShade = false, delayMs = ASSIST_GESTURE_DELAY_MS)
        } else {

            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(MainActivity.EXTRA_LATEST_SCREENSHOT, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or

                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            })
        }
        finish()
    }

    private companion object {

        const val ASSIST_GESTURE_DELAY_MS = 300L
    }
}
