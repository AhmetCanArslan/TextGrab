package com.arslan.textgrab

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Invisible ACTION_ASSIST handler: when TextGrab is chosen as the default
 * digital assistant, long-pressing home / the navigation handle lands here.
 * Nothing is drawn (Theme.NoDisplay), so the capture shows the app below.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capture = if (Build.VERSION.SDK_INT >= 31) CaptureAccessibilityService.instance else null
        if (capture != null) {
            capture.captureScreenAndOpen(dismissShade = false, delayMs = ASSIST_GESTURE_DELAY_MS)
        } else {
            // Launching from a foreground activity is allowed, unlike from the service fallback.
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(MainActivity.EXTRA_LATEST_SCREENSHOT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        }
        finish()
    }

    private companion object {
        /** Lets the assist gesture's feedback animation settle before capturing. */
        const val ASSIST_GESTURE_DELAY_MS = 300L
    }
}
