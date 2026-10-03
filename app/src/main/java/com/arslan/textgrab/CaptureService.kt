package com.arslan.textgrab

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Takes the screenshots. Being a bound accessibility service also keeps the process from being
 * killed in the background, so the tile, the assist gesture and the adb trigger open without a
 * cold start. It listens to no accessibility events and cannot read window content.
 */
class CaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        instance.value = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance.value = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    @RequiresApi(30)
    suspend fun capture(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = runCatching {
                    screenshot.hardwareBuffer.use { buffer ->
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        hardware?.copy(Bitmap.Config.ARGB_8888, false).also { hardware?.recycle() }
                    }
                }.getOrNull()
                continuation.resume(bitmap)
            }

            override fun onFailure(errorCode: Int) = continuation.resume(null)
        }
        runCatching { takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.IO.asExecutor(), callback) }
            .onFailure { continuation.resume(null) }
    }

    companion object {

        val instance = MutableStateFlow<CaptureService?>(null)
    }
}
