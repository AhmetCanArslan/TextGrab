package com.arslan.textgrab

import android.app.Activity
import android.os.Bundle

class CaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenCapture.captureAndOpen(this, intent.getLongExtra(EXTRA_DELAY_MS, ASSIST_GESTURE_DELAY_MS))
        finish()
    }

    companion object {

        const val EXTRA_DELAY_MS = "com.arslan.textgrab.DELAY_MS"

        private const val ASSIST_GESTURE_DELAY_MS = 300L
    }
}
