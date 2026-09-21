package com.arslan.textgrab

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi

object CaptureHolder {

    private const val MAX_AGE_MS = 30_000L

    private var bitmap: Bitmap? = null
    private var putAt = 0L

    @Synchronized
    fun put(b: Bitmap) {
        bitmap = b
        putAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun take(): Bitmap? {
        val b = bitmap
        bitmap = null
        return b?.takeIf { SystemClock.elapsedRealtime() - putAt <= MAX_AGE_MS }
    }
}

class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

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

    @RequiresApi(31)
    fun captureScreenAndOpen(dismissShade: Boolean = true, delayMs: Long = 0L) {
        if (!dismissShade) {
            if (delayMs > 0) handler.postDelayed({ doCapture() }, delayMs) else doCapture()
            return
        }
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        handler.postDelayed({ doCapture() }, maxOf(delayMs, SHADE_DISMISS_DELAY_MS))
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
        startActivity(
            MainActivity.openIntent(
                this,
                if (captured) MainActivity.EXTRA_CAPTURED_SCREEN
                else MainActivity.EXTRA_LATEST_SCREENSHOT
            )
        )
    }
}
