package com.arslan.textgrab

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Warm up the recognizer so the first shared image is instant.
        OcrEngine.warmUp()
    }
}
