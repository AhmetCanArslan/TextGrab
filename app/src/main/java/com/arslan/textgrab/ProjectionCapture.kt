package com.arslan.textgrab

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screen capture before Android 11, where accessibility services cannot take screenshots.
 * The user grants a media projection once; [ProjectionService] keeps it, and the process, alive
 * until they stop it, and every capture mirrors the screen into a short-lived virtual display.
 */
object ProjectionCapture {

    private const val CONSENT_TIMEOUT_MS = 120_000L
    private const val FRAME_TIMEOUT_MS = 2_000L

    private var projection: MediaProjection? = null
    private var consent: CompletableDeferred<Boolean>? = null

    private val frames: Handler by lazy {
        Handler(HandlerThread("projection").apply { start() }.looper)
    }

    val isReady: Boolean get() = projection != null

    /** Shows the system's capture prompt; true once the projection is held. */
    suspend fun request(context: Context): Boolean {
        if (isReady) return true
        val pending = consent?.takeIf { it.isActive } ?: CompletableDeferred<Boolean>().also {
            consent = it
            val intent = Intent(context, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { context.startActivity(intent) }.onFailure { _ -> it.complete(false) }
        }
        return withTimeoutOrNull(CONSENT_TIMEOUT_MS) { pending.await() } == true
    }

    /** Called by [ProjectionService] with the granted projection, or null when it is refused. */
    fun attach(context: Context, granted: MediaProjection?) {
        if (granted != null) {
            projection?.stop()
            projection = granted
            granted.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection !== granted) return
                    projection = null
                    ProjectionService.stop(context)
                }
            }, Handler(Looper.getMainLooper()))
        }
        consent?.complete(granted != null)
    }

    fun release() {
        val held = projection ?: return
        projection = null
        held.stop()
    }

    suspend fun capture(context: Context): Bitmap? {
        val projection = projection ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val frame = CompletableDeferred<Bitmap?>()
        reader.setOnImageAvailableListener({ r ->
            if (frame.isCompleted) return@setOnImageAvailableListener
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            frame.complete(runCatching { image.use { it.toBitmap(width, height) } }.getOrNull())
        }, frames)
        val display: VirtualDisplay? = runCatching {
            projection.createVirtualDisplay(
                "TextGrab", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, frames
            )
        }.getOrNull()
        if (display == null) frame.complete(null)

        val bitmap = withTimeoutOrNull(FRAME_TIMEOUT_MS) { frame.await() }
        // On the frame thread, so it cannot close the reader while a frame is being copied.
        frames.post {
            display?.release()
            reader.close()
        }
        return bitmap
    }

    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes[0]
        val stride = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (stride == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }
}
