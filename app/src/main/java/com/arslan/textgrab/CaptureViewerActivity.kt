package com.arslan.textgrab

/**
 * [MainActivity] as the viewer for a captured screen. Its own task affinity keeps a capture out of
 * the app's main task, so the launcher icon still opens home instead of resuming the screenshot.
 */
class CaptureViewerActivity : MainActivity()
