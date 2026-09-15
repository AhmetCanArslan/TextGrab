package com.arslan.textgrab

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.arslan.textgrab.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), SelectableOcrView.Listener {

    companion object {
        const val EXTRA_LATEST_SCREENSHOT = "com.arslan.textgrab.LATEST_SCREENSHOT"
        const val EXTRA_CAPTURED_SCREEN = "com.arslan.textgrab.CAPTURED_SCREEN"
    }

    private lateinit var binding: ActivityMainBinding
    private var currentBitmap: Bitmap? = null
    private var currentResult: OcrEngine.Result? = null
    private var launchedWithImage = false

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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: keep the bars usable, let the image draw behind them.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val base = (32 * resources.displayMetrics.density).toInt()
            binding.topBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.homeGroup.updatePadding(top = base + bars.top, bottom = base + bars.bottom)
            insets
        }

        binding.ocrView.listener = this
        // Capture previews sit below the toolbar instead of under it.
        binding.topBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            binding.ocrView.topInset = v.bottom.toFloat()
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
        binding.adbCommand.setOnClickListener { copyToClipboard(adbCommand) }

        binding.btnBack.setOnClickListener { onBackFromViewer() }
        binding.btnSelectAll.setOnClickListener { binding.ocrView.selectAll() }
        binding.btnCopyAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { copyToClipboard(it) }
        }
        binding.btnShareAll.setOnClickListener {
            currentResult?.fullText?.takeIf { it.isNotBlank() }?.let { shareText(it) }
        }
        binding.btnTextMode.setOnClickListener { showTextSheet() }

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
    }

    override fun onResume() {
        super.onResume()
        // Offer the accessibility-based instant capture where supported.
        binding.btnEnableCapture.isVisible =
            Build.VERSION.SDK_INT >= 31 && !isCaptureServiceEnabled()
        binding.btnSetAssistant.isVisible = !isAssistantApp()
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
        when {
            uri != null -> {
                launchedWithImage = true
                openImage(uri)
            }
            intent.getBooleanExtra(EXTRA_CAPTURED_SCREEN, false) -> {
                val captured = CaptureHolder.take()
                if (captured != null) {
                    launchedWithImage = true
                    openBitmap(captured)
                } else showHome()
            }
            intent.getBooleanExtra(EXTRA_LATEST_SCREENSHOT, false) -> {
                launchedWithImage = true
                requestScreenshotOcr()
            }
            else -> showHome()
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
        showViewer(loading = true)
        lifecycleScope.launch {
            try {
                val bitmap = ImageLoader.load(this@MainActivity, uri)
                processBitmap(bitmap)
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
        showViewer(loading = true)
        lifecycleScope.launch { processBitmap(bitmap) }
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        val result = OcrEngine.recognize(bitmap)
        currentBitmap = bitmap
        currentResult = result
        binding.ocrView.setContent(bitmap, result)
        showViewer(loading = false)
        if (result.isEmpty) {
            Snackbar.make(binding.root, R.string.no_text_found, Snackbar.LENGTH_LONG).show()
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
        binding.homeGroup.isVisible = true
        binding.viewerGroup.isVisible = false
        binding.progressGroup.isVisible = false
        binding.selectionToolbar.isVisible = false
        binding.ocrView.clear()
        currentBitmap = null
        currentResult = null
    }

    private fun showViewer(loading: Boolean) {
        binding.homeGroup.isVisible = false
        binding.viewerGroup.isVisible = true
        binding.progressGroup.isVisible = loading
        if (loading) binding.selectionToolbar.isVisible = false
    }

    private fun onBackFromViewer() {
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
}
