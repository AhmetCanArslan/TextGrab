package com.arslan.textgrab

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.Toast
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

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

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    const val PERMISSION_REQUEST_CODE = 7301

    private const val MANAGER_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    private const val SERVICE_VERSION = 1
    private const val BINDER_TIMEOUT_MS = 3_000L
    private const val BIND_TIMEOUT_MS = 5_000L

    private const val HEADER_BYTES = 12
    private const val MAX_EXTRA_HEADER_BYTES = 64
    private const val FORMAT_RGBA_8888 = 1
    private const val FORMAT_RGBX_8888 = 2
    private const val DATASPACE_DISPLAY_P3 = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bindLock = Mutex()
    private val service = MutableStateFlow<IScreenService?>(null)
    private var job: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.value = binder?.takeIf { it.pingBinder() }?.let { IScreenService.Stub.asInterface(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
        }
    }

    fun status(context: Context): Status = when {
        alive() && granted() -> Status.READY
        alive() -> Status.NO_PERMISSION
        managerInstalled(context) -> Status.NOT_RUNNING
        else -> Status.NOT_INSTALLED
    }

    fun managerPackage(context: Context): String? = runCatching {
        context.packageManager.getPermissionInfo(MANAGER_PERMISSION, 0).packageName
    }.getOrNull()

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    fun captureAndOpen(context: Context, delayMs: Long = 0L): Job {
        val app = context.applicationContext
        job?.cancel()
        return scope.launch {
            OcrEngine.warmUp()
            val screen = connect(app)
            if (screen == null) {
                Toast.makeText(app, R.string.shizuku_not_ready, Toast.LENGTH_LONG).show()
                return@launch
            }
            delay(delayMs)
            val bitmap = withContext(Dispatchers.IO) { runCatching { read(screen) }.getOrNull() }
            if (bitmap == null) {
                Toast.makeText(app, R.string.capture_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            CaptureHolder.put(bitmap)
            withContext(Dispatchers.IO) { runCatching { screen.exec(viewerCommand(app)) } }
        }.also { job = it }
    }

    private suspend fun connect(context: Context): IScreenService? = bindLock.withLock {
        service.value?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        service.value = null
        if (!awaitBinder() || !granted()) return null
        runCatching { Shizuku.bindUserService(args(context), connection) }.onFailure { return null }
        withTimeoutOrNull(BIND_TIMEOUT_MS) { service.filterNotNull().first() }
    }

    private suspend fun awaitBinder(): Boolean {
        if (alive()) return true
        val received = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { received.complete(Unit) }
        Shizuku.addBinderReceivedListenerSticky(listener)
        return try {
            withTimeoutOrNull(BINDER_TIMEOUT_MS) { received.await() } != null
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }

    private fun read(screen: IScreenService): Bitmap? = readRaw(screen) ?: readPng(screen)

    private fun readRaw(screen: IScreenService): Bitmap? =
        DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(screen.capture(false))).use { input ->
            val head = ByteArray(HEADER_BYTES).also { input.readFully(it) }
            val header = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val width = header.int
            val height = header.int
            val format = header.int
            if (format != FORMAT_RGBA_8888 && format != FORMAT_RGBX_8888) return null
            val pixels = width * height * 4
            val body = ByteArray(pixels + MAX_EXTRA_HEADER_BYTES)
            var size = 0
            while (size < body.size) {
                val read = input.read(body, size, body.size - size)
                if (read < 0) break
                size += read
            }
            val offset = size - pixels
            if (offset !in 0 until MAX_EXTRA_HEADER_BYTES) return null
            val p3 = offset >= 4 &&
                ByteBuffer.wrap(body, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int == DATASPACE_DISPLAY_P3
            val space = ColorSpace.get(if (p3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false, space).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(body, offset, pixels))
            }
        }

    private fun readPng(screen: IScreenService): Bitmap? =
        ParcelFileDescriptor.AutoCloseInputStream(screen.capture(true)).use { BitmapFactory.decodeStream(it) }

    private fun viewerCommand(context: Context): String =
        "am start -n ${context.packageName}/${MainActivity::class.java.name} " +
            "--ez ${MainActivity.EXTRA_CAPTURED_SCREEN} true --activity-clear-top --activity-single-top"

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ScreenService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("capture")
        .debuggable(false)
        .version(SERVICE_VERSION)

    private fun alive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun granted(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun managerInstalled(context: Context): Boolean = managerPackage(context) != null
}
