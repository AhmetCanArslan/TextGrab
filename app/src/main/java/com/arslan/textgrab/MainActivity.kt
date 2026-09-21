package com.arslan.textgrab

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import androidx.core.view.WindowInsetsControllerCompat
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

        private const val SPLASH_FADE_MS = 150L

        private const val TOOLBAR_DELAY = 0.35f
        private const val TOOLBAR_DURATION = 0.5f
        private const val TOOLBAR_SLIDE = 0.4f

        private const val TOOLBAR_SLIDE_FALLBACK_PX = 96
    }

    private lateinit var binding: ActivityMainBinding
    private var currentBitmap: Bitmap? = null
    private var currentResult: OcrEngine.Result? = null
    private var launchedWithImage = false
    private var translateJob: Job? = null
    private var ocrJob: Job? = null

    private var contentReady = false

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openImage(uri)
        }

    private val requestMediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->

            if (hasMediaAccess()) loadLatestScreenshot()
            else Snackbar.make(
                binding.root, R.string.permission_needed, Snackbar.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val capturing = intent?.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false) == true
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        splash.setKeepOnScreenCondition { !contentReady }
        splash.setOnExitAnimationListener { provider ->
            if (capturing) {

                provider.remove()
            } else {

                provider.view.animate()
                    .alpha(0f)
                    .setDuration(SPLASH_FADE_MS)
                    .withEndAction { provider.remove() }
            }
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        binding.topBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            binding.ocrView.topInset = v.bottom.toFloat()

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

        binding.root.post { contentReady = true }
    }

    override fun onResume() {
        super.onResume()

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

        binding.rowLanguagePacks.isVisible = !engine.isCloud
        lifecycleScope.launch {
            val count = runCatching { Translator.downloadedLanguages().size }.getOrNull() ?: return@launch
            binding.textLanguagePacks.text = getString(R.string.language_packs_summary, count)
        }
    }

    private fun isAssistantApp(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roles = getSystemService(android.app.role.RoleManager::class.java) ?: return false
        return roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    }

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

                CaptureHolder.take()
                showHome()
            }
        }
    }

    private fun setCapturePreview(enabled: Boolean) {
        binding.ocrView.capturePreview = enabled
        if (enabled) binding.topBar.background = null
        else binding.topBar.setBackgroundResource(R.drawable.top_scrim)
    }

    private fun openImage(uri: Uri) {
        setCapturePreview(false)

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

        setCapturePreview(true)

        clearOpenTransition()

        showImage(bitmap)
        binding.ocrView.playCaptureEntry()
        playToolbarEntry()
        startOcr { recognize(bitmap) }
    }

    private fun clearOpenTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

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

        binding.ocrProgress.alpha = 0f
        binding.ocrProgress.animate()
            .alpha(1f)
            .setStartDelay((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DELAY).toLong())
            .setDuration((SelectableOcrView.CAPTURE_TRANSITION_MS * TOOLBAR_DURATION).toLong())
            .setInterpolator(SelectableOcrView.EMPHASIZED_DECELERATE)
    }

    private fun startOcr(block: suspend () -> Unit) {
        ocrJob?.cancel()
        ocrJob = lifecycleScope.launch { block() }
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        showImage(bitmap)
        recognize(bitmap)
    }

    private fun showImage(bitmap: Bitmap) {

        resetTranslation()
        binding.selectionToolbar.isVisible = false
        for (v in arrayOf(binding.topBar, binding.ocrProgress, binding.selectionToolbar)) {
            v.animate().cancel()
            v.alpha = 1f
        }

        binding.topBar.translationY = 0f
        currentResult = null
        currentBitmap = bitmap
        binding.ocrView.setImage(bitmap)
        showViewer(loading = false)
    }

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

        return firstIdOf(selection, args) ?: firstIdOf(null, null)
    }

    private fun showHome() {
        clearViewerContent()
        binding.homeGroup.isVisible = true
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        launchedWithImage = false
        updateSystemBarAppearance()
    }

    private fun showViewer(loading: Boolean) {
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = true
        binding.progressGroup.isVisible = loading
        updateSystemBarAppearance()
        if (loading) {
            binding.selectionToolbar.isVisible = false

            clearViewerContent()
        }
    }

    private fun updateSystemBarAppearance() {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val light = !binding.viewerGroup.isVisible && !night
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

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

        clearViewerContent()
        if (launchedWithImage) finish() else showHome()
    }

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

        var y = anchor.top - toolbar.height - margin * 1.5f
        if (y < container.height * 0.12f) y = anchor.bottom + margin * 1.5f
        y = max(margin, min(y, container.height - toolbar.height - margin))

        toolbar.translationX = x
        toolbar.translationY = y
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))

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
