package com.arslan.textgrab

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.arslan.textgrab.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), SelectableOcrView.Listener {

    companion object {
        const val EXTRA_LATEST_SCREENSHOT = "com.arslan.textgrab.LATEST_SCREENSHOT"
        const val EXTRA_CAPTURED_SCREEN = "com.arslan.textgrab.CAPTURED_SCREEN"

        /** Short enough to feel instant, long enough not to read as a flash. */
        private const val SPLASH_FADE_MS = 150L

        /** Toolbar entry, as fractions of the capture transition. */
        private const val TOOLBAR_DELAY = 0.35f
        private const val TOOLBAR_DURATION = 0.5f
        private const val TOOLBAR_SLIDE = 0.4f
        /** Used before the toolbar has been measured, on the very first frame. */
        private const val TOOLBAR_SLIDE_FALLBACK_PX = 96
    }

    private lateinit var binding: ActivityMainBinding
    private var currentBitmap: Bitmap? = null
    private var currentResult: OcrEngine.Result? = null
    private var launchedWithImage = false
    private var translateJob: Job? = null
    private var ocrJob: Job? = null

    /** Holds the splash until there is a real frame behind it. */
    private var contentReady = false

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openImage(uri)
        }

    private val requestMediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Check again instead of trusting the flag: on Android 14+ the user
            // may have granted partial access, which reports "denied" here.
            if (hasMediaAccess()) loadLatestScreenshot()
            else Snackbar.make(
                binding.root, R.string.permission_needed, Snackbar.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val capturing = intent?.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false) == true
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until there is a real frame behind it; the window is
        // then never seen empty, whichever screen the intent asked for.
        splash.setKeepOnScreenCondition { !contentReady }
        splash.setOnExitAnimationListener { provider ->
            if (capturing) {
                // The frame behind is already a copy of the screen the user was
                // looking at, so the splash has nothing to hand over and simply
                // gets out of the way before the capture transition starts.
                provider.remove()
            } else {
                // Crossfade instead of the default slide-up: gentler than a cut,
                // and it keeps the surface colour continuous throughout.
                provider.view.animate()
                    .alpha(0f)
                    .setDuration(SPLASH_FADE_MS)
                    .withEndAction { provider.remove() }
            }
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: keep the bars usable, let the image draw behind them.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val base = (24 * resources.displayMetrics.density).toInt()
            binding.topBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.homeGroup.updatePadding(top = base + bars.top, bottom = base + bars.bottom)
            insets
        }

        binding.ocrView.listener = this
        // Capture previews sit below the toolbar instead of under it.
        binding.topBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            binding.ocrView.topInset = v.bottom.toFloat()
            // The toolbar's height depends on the status-bar inset, so the
            // progress hairline follows its bottom edge rather than a constant.
            binding.ocrProgress.translationY = v.bottom.toFloat()
        }

        binding.btnPick.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnScreenshot.setOnClickListener { requestScreenshotOcr() }
        binding.btnEnableCapture.setOnClickListener {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        binding.btnSetAssistant.setOnClickListener {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }.onFailure {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            }
        }

        val adbCommand = "adb shell am broadcast -a ${CaptureReceiver.ACTION_CAPTURE} -p $packageName"
        binding.adbCommand.text = adbCommand
        binding.adbRow.setOnClickListener { copyToClipboard(adbCommand) }

        binding.rowTargetLanguage.setOnClickListener { chooseTargetLanguage() }
        binding.rowLanguagePacks.setOnClickListener {
            LanguagePacksSheet(this) { refreshTranslationRows() }.show()
        }
        binding.rowEngine.setOnClickListener {
            TranslationEngineSheet(this) { refreshTranslationRows() }.show()
        }

        binding.btnBack.setOnClickListener { onBackFromViewer() }
        binding.btnSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.btnCopyAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { copyToClipboard(it) }
        }
        binding.btnShareAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { shareText(it) }
        }
        binding.btnTextMode.setOnClickListener { showTextSheet() }
        binding.btnTranslateAll.setOnClickListener {
            if (binding.ocrView.translations != null) resetTranslation() else translateScreen()
        }
        binding.btnTranslateAll.setOnLongClickListener {
            chooseTargetLanguage()
            true
        }

        binding.toolbarCopy.setOnClickListener {
            binding.ocrView.selectedText?.let {
                copyToClipboard(it)
                binding.ocrView.clearSelection()
            }
        }
        binding.toolbarSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.toolbarShare.setOnClickListener { binding.ocrView.selectedText?.let { shareText(it) } }
        binding.toolbarSearch.setOnClickListener { binding.ocrView.selectedText?.let { searchWeb(it) } }

        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.ocrView.hasSelection() -> binding.ocrView.clearSelection()
                binding.viewerGroup.isVisible -> onBackFromViewer()
                else -> finish()
            }
        }

        handleIntent(intent)

        // Whatever handleIntent chose is laid out by now; let the splash go as
        // soon as that state has actually been drawn.
        binding.root.post { contentReady = true }
    }

    override fun onResume() {
        super.onResume()
        // Offer the accessibility-based instant capture where supported.
        binding.btnEnableCapture.isVisible =
            Build.VERSION.SDK_INT >= 31 && !isCaptureServiceEnabled()
        binding.btnSetAssistant.isVisible = !isAssistantApp()
        val needsSetup = binding.btnEnableCapture.isVisible || binding.btnSetAssistant.isVisible
        binding.setupHeader.isVisible = needsSetup
        binding.setupCard.isVisible = needsSetup
        refreshTranslationRows()
    }

    private fun refreshTranslationRows() {
        binding.textTargetLanguage.text = Translator.displayName(Translator.defaultTarget(this))
        val engine = EngineSettings.selected(this)
        binding.textEngine.text = getString(engine.title).let {
            if (engine.isCloud) getString(R.string.engine_row_summary_cloud, it) else it
        }
        // Language packs only matter while the on-device engine is in use.
        binding.rowLanguagePacks.isVisible = !engine.isCloud
        lifecycleScope.launch {
            val count = runCatching { Translator.downloadedLanguages().size }.getOrNull() ?: return@launch
            binding.textLanguagePacks.text = getString(R.string.language_packs_summary, count)
        }
    }

    /** The assistant role cannot be requested directly, only checked (API 29+). */
    private fun isAssistantApp(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roles = getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    }

    /** Checks the system setting rather than the live service instance, which
     *  can be briefly null while the system (re)binds the service. */
    private fun isCaptureServiceEnabled(): Boolean {
        val component = "$packageName/${CaptureAccessibilityService::class.java.name}"
        val shortComponent = "$packageName/.${CaptureAccessibilityService::class.java.simpleName}"
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(component, ignoreCase = true) || it.equals(shortComponent, ignoreCase = true)
        }
    }

    override fun onDestroy() {
        // The pending capture is deliberately left alone: a launch that
        // recreates this activity would otherwise drop the very bitmap it
        // is being started for. CaptureHolder expires stale entries itself.
        clearViewerContent()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        // Consume the request: a relaunch of the same intent (recents, config
        // change, CLEAR_TOP) must not reopen what the user already dismissed.
        val captureRequested = intent.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false)
        val latestRequested = intent.getBooleanExtra(EXTRA_LATEST_SCREENSHOT, false)
        intent.removeExtra(EXTRA_CAPTURED_SCREEN)
        intent.removeExtra(EXTRA_LATEST_SCREENSHOT)
        intent.action = Intent.ACTION_MAIN
        intent.data = null

        when {
            uri != null -> {
                launchedWithImage = true
                openImage(uri)
            }
            captureRequested -> {
                val captured = CaptureHolder.take()
                if (captured != null) {
                    launchedWithImage = true
                    openBitmap(captured)
                } else showHome()
            }
            latestRequested -> {
                launchedWithImage = true
                requestScreenshotOcr()
            }
            else -> {
                // A capture that never reached the viewer must not resurface later.
                CaptureHolder.take()
                showHome()
            }
        }
    }

    // ------------------------------------------------------------- image flow

    /** Captures get the toolbar in its own strip above the preview; other images keep the overlay scrim. */
    private fun setCapturePreview(enabled: Boolean) {
        binding.ocrView.capturePreview = enabled
        if (enabled) binding.topBar.background = null
        else binding.topBar.setBackgroundResource(R.drawable.top_scrim)
    }

    private fun openImage(uri: Uri) {
        setCapturePreview(false)
        // Nothing to show until the file is decoded, so this is the one path
        // that gets a full-screen wait.
        showViewer(loading = true)
        startOcr {
            try {
                val bitmap = ImageLoader.load(this@MainActivity, uri)
                processBitmap(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Failed to open $uri", e)
                showHome()
                Snackbar.make(
                    binding.root,
                    getString(R.string.load_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openBitmap(bitmap: Bitmap) {
        // Only instant captures arrive here; shrink them so edge text is reachable.
        setCapturePreview(true)
        // The system's own open animation would slide or fade a window over the
        // screen that this very bitmap is a copy of. Drop it: the transition
        // below replaces it with one that keeps those pixels in place.
        clearOpenTransition()
        // Synchronously, still inside onCreate on a cold start: the capture is
        // in the very first frame, so there is nothing blank to hand over from.
        showImage(bitmap)
        binding.ocrView.playCaptureEntry()
        playToolbarEntry()
        startOcr { recognize(bitmap) }
    }

    /** Drops the system's open animation, so the capture's own one is all there is. */
    private fun clearOpenTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Leaves with no close animation: the exit transition already landed us there. */
    private fun finishWithoutTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            finish()
        } else {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * The toolbar arrives after the image has begun to settle, so the eye
     * follows the capture first and the controls appear around it.
     */
    private fun playToolbarEntry() {
        val bar = binding.topBar
        bar.alpha = 0f
        bar.translationY = -bar.height.coerceAtLeast(TOOLBAR_SLIDE_FALLBACK_PX) * TOOLBAR_SLIDE
        bar.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
        // The hairline belongs to the toolbar; it arrives with it rather than
        // blinking on alone the moment recognition starts.
        binding.ocrProgress.alpha = 0f
        binding.ocrProgress.animate()
            .alpha(1f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
    }

    /** Runs [block] as the single in-flight recognition, replacing any earlier one. */
    private fun startOcr(block: suspend () -> Unit) {
        ocrJob?.cancel()
        ocrJob = lifecycleScope.launch { block() }
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        showImage(bitmap)
        recognize(bitmap)
    }

    /**
     * Puts the image on screen before anything has been recognized. Panning and
     * zooming work right away; words become tappable when [recognize] lands.
     */
    private fun showImage(bitmap: Bitmap) {
        // A new image replaces the old one: drop what belonged to it, and undo
        // any alpha an exit transition left behind on the chrome.
        resetTranslation()
        binding.selectionToolbar.isVisible = false
        for (v in arrayOf(binding.topBar, binding.ocrProgress, binding.selectionToolbar)) {
            v.animate().cancel()
            v.alpha = 1f
        }
        // Not the hairline's: its translationY is where the toolbar ends, not animation state.
        binding.topBar.translationY = 0f
        currentResult = null
        currentBitmap = bitmap
        binding.ocrView.setImage(bitmap)
        showViewer(loading = false)
    }

    /** Recognition runs under the shown image, marked by the hairline progress bar. */
    private suspend fun recognize(bitmap: Bitmap) {
        binding.ocrProgress.show()
        try {
            val result = OcrEngine.recognize(bitmap)
            currentResult = result
            binding.ocrView.setContent(bitmap, result)
            if (result.isEmpty) {
                Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_LONG).show()
            }
        } finally {
            binding.ocrProgress.hide()
        }
    }

    private fun requestScreenshotOcr() {
        if (hasMediaAccess()) {
            loadLatestScreenshot()
        } else {
            requestMediaPermission.launch(
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    private fun hasMediaAccess(): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    // Android 14+ partial access: user picked specific photos.
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun loadLatestScreenshot() {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { queryLatestScreenshot() }
            if (uri != null) openImage(uri)
            else Snackbar.make(binding.root, R.string.no_screenshot_found, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun queryLatestScreenshot(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("%Screenshots%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            args = arrayOf("%/Screenshots/%")
        }
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        fun firstIdOf(sel: String?, selArgs: Array<String>?): Uri? =
            contentResolver.query(collection, projection, sel, selArgs, order)?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(collection, c.getLong(0))
                } else null
            }

        // Prefer the newest screenshot; fall back to the newest image of any kind.
        return firstIdOf(selection, args) ?: firstIdOf(null, null)
    }

    // ---------------------------------------------------------------- UI state

    private fun showHome() {
        clearViewerContent()
        binding.homeGroup.isVisible = true
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        launchedWithImage = false
    }

    private fun showViewer(loading: Boolean) {
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = true
        binding.progressGroup.isVisible = loading
        if (loading) {
            binding.selectionToolbar.isVisible = false
            // The progress scrim is translucent, so anything left in the view
            // would show through as the previous capture's preview.
            clearViewerContent()
        }
    }

    /** Drops the shown image and everything derived from it. */
    private fun clearViewerContent() {
        ocrJob?.cancel()
        ocrJob = null
        resetTranslation()
        binding.selectionToolbar.isVisible = false
        binding.ocrProgress.hide()
        binding.ocrView.clear()
        currentBitmap = null
        currentResult = null
    }

    private fun onBackFromViewer() {
        // A capture that played itself in plays itself back out: the image grows
        // to fill the screen again, so the window closes on the same pixels the
        // app is sitting on top of.
        if (launchedWithImage && binding.ocrView.canPlayCaptureExit) {
            val fadeOut = (SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong()
            for (v in arrayOf(binding.topBar, binding.ocrProgress, binding.selectionToolbar)) {
                v.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(fadeOut)
                    .setInterpolator(SelectableOcrView.EMPHASIZED_ACCELERATE)
            }
            ocrJob?.cancel()
            binding.ocrProgress.hide()
            binding.ocrView.playCaptureExit { finishWithoutTransition() }
            return
        }
        // Clear first either way: on finish() the instance can outlive this
        // frame and be reused for the next capture.
        clearViewerContent()
        if (launchedWithImage) finish() else showHome()
    }

    // ------------------------------------------------------------- selection

    override fun onSelectionChanged(text: String?, anchor: RectF?) {
        if (text == null || anchor == null) {
            binding.selectionToolbar.isVisible = false
            return
        }
        binding.selectionToolbar.isVisible = true
        binding.selectionToolbar.post { positionToolbar(anchor) }
    }

    private fun positionToolbar(anchor: RectF) {
        val toolbar = binding.selectionToolbar
        val container = binding.viewerGroup
        val margin = resources.displayMetrics.density * 12f

        var x = anchor.centerX() - toolbar.width / 2f
        x = max(margin, min(x, container.width - toolbar.width - margin))

        // Above the selection if there is room, otherwise below.
        var y = anchor.top - toolbar.height - margin * 1.5f
        if (y < container.height * 0.12f) y = anchor.bottom + margin * 1.5f
        y = max(margin, min(y, container.height - toolbar.height - margin))

        toolbar.translationX = x
        toolbar.translationY = y
    }

    // --------------------------------------------------------------- actions

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // Android 13+ shows its own clipboard confirmation overlay.
        if (Build.VERSION.SDK_INT < 33) {
            Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun searchWeb(text: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, text)
        }
        if (search.resolveActivity(packageManager) != null) {
            startActivity(search)
        } else {
            val browse = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://duckduckgo.com/?q=${Uri.encode(text)}")
            )
            runCatching { startActivity(browse) }
        }
    }

    private fun showTextSheet() {
        val text = currentResult?.fullText
        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_text, null)
        view.findViewById<TextView>(R.id.sheetText).text = text
        view.findViewById<View>(R.id.sheetCopy).setOnClickListener {
            copyToClipboard(text)
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.sheetShare).setOnClickListener { shareText(text) }
        sheet.setContentView(view)
        sheet.show()
    }

    // ----------------------------------------------------------- translation

    /** Replaces every recognized line on the image with its translation, in place. */
    private fun translateScreen() {
        val result = currentResult ?: return
        if (result.isEmpty) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_SHORT).show()
            return
        }
        val target = Translator.defaultTarget(this)
        translateJob?.cancel()
        translateJob = lifecycleScope.launch {
            binding.progressGroup.isVisible = true
            var downloadNote: Snackbar? = null
            try {
                val outcome = Translator.translate(this@MainActivity, result, target) {
                    downloadNote = Snackbar.make(
                        binding.root, R.string.downloading_models, Snackbar.LENGTH_INDEFINITE
                    ).also { it.show() }
                }
                if (currentResult !== result) return@launch
                if (outcome.sources.isEmpty()) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.already_in_language, Translator.displayName(target)),
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.change_language) { chooseTargetLanguage() }.show()
                    return@launch
                }
                binding.ocrView.translations = outcome.lines
                setTranslateActive(true)
                Snackbar.make(
                    binding.root,
                    getString(
                        R.string.translated_from,
                        outcome.sources.joinToString(", ") { Translator.displayName(it) },
                        Translator.displayName(target)
                    ),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.change_language) { chooseTargetLanguage() }.show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TextGrab", "Translation failed", e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.translation_failed, e.localizedMessage ?: ""),
                    Snackbar.LENGTH_LONG
                ).show()
            } finally {
                downloadNote?.dismiss()
                binding.progressGroup.isVisible = false
            }
        }
    }

    private fun resetTranslation() {
        translateJob?.cancel()
        translateJob = null
        binding.ocrView.translations = null
        setTranslateActive(false)
    }

    private fun setTranslateActive(active: Boolean) {
        binding.btnTranslateAll.imageTintList = ColorStateList.valueOf(
            if (active) 0xFF3B82F6.toInt() else android.graphics.Color.WHITE
        )
        binding.btnTranslateAll.contentDescription =
            getString(if (active) R.string.show_original else R.string.translate)
    }

    private fun chooseTargetLanguage() {
        val codes = Translator.languages.sortedBy { Translator.displayName(it) }
        val names = codes.map { Translator.displayName(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.translate_to)
            .setSingleChoiceItems(names, codes.indexOf(Translator.defaultTarget(this))) { dialog, which ->
                dialog.dismiss()
                Translator.saveTarget(this, codes[which])
                refreshTranslationRows()
                if (binding.viewerGroup.isVisible) translateScreen()
            }
            .show()
    }
}
