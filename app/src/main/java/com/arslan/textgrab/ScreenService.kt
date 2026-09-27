package com.arslan.textgrab

import android.os.ParcelFileDescriptor
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ScreenService : IScreenService.Stub() {

    override fun destroy() = exitProcess(0)

    override fun capture(png: Boolean): ParcelFileDescriptor {
        val (source, sink) = ParcelFileDescriptor.createPipe()
        val command = if (png) listOf("screencap", "-p") else listOf("screencap")
        val process = ProcessBuilder(command).start()
        thread(name = "screencap") {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                    process.inputStream.use { it.copyTo(out) }
                }
            }
            process.destroy()
        }
        return source
    }

    override fun exec(command: String): Int = runCatching {
        ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start().run {
            inputStream.use { it.readBytes() }
            waitFor()
        }
    }.getOrDefault(-1)
}
