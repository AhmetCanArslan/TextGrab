package com.arslan.textgrab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.Toast
import com.arslan.textgrab.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object CaptureHolder {

    private const val MAX_AGE_MS = 30_000L

    /** A screenshot and its text, read while the viewer is still starting. */
    class Capture(val bitmap: Bitmap, val reading: Deferred<OcrEngine.Reading>)

    private var capture: Capture? = null
    private var putAt = 0L

    @Synchronized
    fun put(c: Capture) {
        capture?.reading?.cancel()
        capture = c
        putAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun take(): Capture? {
        val c = capture ?: return null
        capture = null
        if (SystemClock.elapsedRealtime() - putAt <= MAX_AGE_MS) return c
        c.reading.cancel()
        return null
    }
}

object ScreenCapture {

    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val CAPTURE_RETRY_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** Accessibility services can take screenshots from Android 11 on. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 30

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(context, CaptureService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun captureAndOpen(context: Context, delayMs: Long = 0L): Job {
        val app = context.applicationContext
        job?.cancel()
        return scope.launch {
            if (Build.VERSION.SDK_INT < 30) {
                Toast.makeText(app, R.string.capture_unsupported, Toast.LENGTH_LONG).show()
                return@launch
            }
            val service = connect(app)
            if (service == null) {
                Toast.makeText(app, R.string.a11y_not_ready, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (delayMs > 0) {
                // The models load while the shade or the assist gesture gets out of the way.
                OcrEngine.warmUp()
                delay(delayMs)
            }
            // The first screenshot right after the service binds can fail; one more try covers it.
            val bitmap = service.capture() ?: run {
                delay(CAPTURE_RETRY_MS)
                service.capture()
            }
            if (bitmap == null) {
                Toast.makeText(app, R.string.capture_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Dispatched rather than immediate, so the viewer is asked for before the models load.
            val reading = scope.async(Dispatchers.Main) { OcrEngine.read(bitmap) }
            CaptureHolder.put(CaptureHolder.Capture(bitmap, reading))
            runCatching { service.startActivity(viewerIntent(app)) }.onFailure {
                CaptureHolder.take()?.reading?.cancel()
                OcrEngine.release()
            }
        }.also { job = it }
    }

    /**
     * Loads the viewer's classes and the OCR libraries once the service binds, which after a
     * reboot or a background kill happens well before the first capture. Without it that capture
     * pays for them while the user waits, and after a reboot the app's code is not compiled yet.
     */
    fun prewarm(context: Context) {
        if (job?.isActive == true) return
        val themed = ContextThemeWrapper(context.applicationContext, R.style.Theme_TextGrab)
        Looper.myQueue().addIdleHandler {
            if (job?.isActive != true) {
                runCatching { ActivityMainBinding.inflate(LayoutInflater.from(themed)) }
                scope.launch {
                    runCatching { OcrEngine.read(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)) }
                    OcrEngine.release()
                }
            }
            false
        }
    }

    /** After a cold start the system needs a moment to rebind the enabled service. */
    private suspend fun connect(context: Context): CaptureService? {
        if (!isEnabled(context)) return null
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { CaptureService.instance.filterNotNull().first() }
    }

    private fun viewerIntent(context: Context) = Intent(context, CaptureViewerActivity::class.java)
        .putExtra(MainActivity.EXTRA_CAPTURED_SCREEN, true)
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
}
