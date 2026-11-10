package com.arslan.textgrab

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Invisible host for the system's screen capture prompt. */
class ProjectionRequestActivity : Activity() {

    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }.onFailure { close() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        answered = true
        if (resultCode == RESULT_OK && data != null) {
            ProjectionService.start(this, resultCode, data)
        } else {
            ProjectionCapture.attach(this, null)
        }
        close()
    }

    override fun onDestroy() {
        if (!answered && isFinishing) ProjectionCapture.attach(this, null)
        super.onDestroy()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {

        const val REQUEST_CAPTURE = 1
    }
}
