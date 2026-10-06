package com.arslan.textgrab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
            delay(delayMs)
            // The first screenshot right after the service binds can fail; one more try covers it.
            val bitmap = service.capture() ?: run {
                delay(CAPTURE_RETRY_MS)
                service.capture()
            }
            if (bitmap == null) {
                Toast.makeText(app, R.string.capture_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            CaptureHolder.put(bitmap)
            runCatching { service.startActivity(viewerIntent(app)) }
        }.also { job = it }
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
