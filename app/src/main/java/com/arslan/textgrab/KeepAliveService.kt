package com.arslan.textgrab

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing. A bound accessibility service keeps the process from being killed in the
 * background, so the tile, the assist gesture and the adb trigger open without a cold start.
 */
class KeepAliveService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
