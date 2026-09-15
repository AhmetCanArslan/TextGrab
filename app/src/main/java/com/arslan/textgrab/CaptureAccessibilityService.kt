package com.arslan.textgrab

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi

/**
 * Hands a freshly captured screen bitmap from the accessibility service
 * to MainActivity without going through disk.
 */
object CaptureHolder {
    private var bitmap: Bitmap? = null

    @Synchronized
    fun put(b: Bitmap) {
        bitmap = b
    }

    @Synchronized
    fun take(): Bitmap? {
        val b = bitmap
        bitmap = null
        return b
    }
}

/**
 * Screenshot-only accessibility service: lets the quick-settings tile capture
 * the current screen by itself, so "tile tap -> selectable text" needs no
 * manual screenshot first. The service reads no window content and reacts to
 * no events; it only exposes AccessibilityService.takeScreenshot().
 */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        /** Delay between dismissing the shade and capturing, so the closing
         *  animation is never part of the screenshot. */
        private const val SHADE_DISMISS_DELAY_MS = 800L
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * Dismisses the notification shade (the tile was just tapped, so it is
     * open), waits for the animation, captures the screen and opens the
     * viewer. Falls back to the latest-screenshot flow on failure.
     */
    @RequiresApi(31)
    fun captureScreenAndOpen(dismissShade: Boolean = true) {
        if (!dismissShade) {
            doCapture()
            return
        }
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        handler.postDelayed({ doCapture() }, SHADE_DISMISS_DELAY_MS)
    }

    @RequiresApi(31)
    private fun doCapture() {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer, result.colorSpace
                    )
                    // ML Kit and our overlay need CPU-accessible pixels.
                    val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (software != null) {
                        CaptureHolder.put(software)
                        openMain(captured = true)
                    } else {
                        fallback()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    fallback()
                }
            }
        )
    }

    private fun fallback() {
        Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show()
        openMain(captured = false)
    }

    private fun openMain(captured: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(
                if (captured) MainActivity.EXTRA_CAPTURED_SCREEN
                else MainActivity.EXTRA_LATEST_SCREENSHOT,
                true
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }
}
