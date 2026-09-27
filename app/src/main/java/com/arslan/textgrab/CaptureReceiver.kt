package com.arslan.textgrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CaptureReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CAPTURE = "com.arslan.textgrab.action.CAPTURE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CAPTURE) return
        val pending = goAsync()
        ScreenCapture.captureAndOpen(context).invokeOnCompletion { pending.finish() }
    }
}
