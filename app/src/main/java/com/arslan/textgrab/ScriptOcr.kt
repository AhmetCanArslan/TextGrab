package com.arslan.textgrab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Recognition for a script ML Kit lacks, through Tesseract and a bundled model. ML Kit drops
 * Arabic without a trace and reads Cyrillic as Latin look-alikes, so neither can be fixed there.
 */
class ScriptOcr private constructor(
    private val language: String,
    private val prefKey: String,
    /** Languages written in this script, for which reading it on every capture is on by default. */
    private val scriptLanguages: Set<String>,
    private val isScript: (Char) -> Boolean,
) {

    class Line(val box: RectF, val words: List<Pair<String, RectF>>, val blockId: Int, val confidence: Float)

    companion object {
        /** A line Tesseract is less sure of than this is almost always another script misread. */
        private const val MIN_LINE_CONFIDENCE = 0.6f

        /** Below this a line must be almost purely letters of the script to count. */
        private const val SURE_LINE_CONFIDENCE = 0.8f

        private const val MIN_WORD_CONFIDENCE = 0.3f

        private const val PREFS = "recognition"

        val ARABIC = ScriptOcr("ara", "arabic", setOf("ar", "fa", "ur", "ps", "ckb", "sd", "ug")) {
            it.code.let { c ->
                c in 0x0600..0x06FF || c in 0x0750..0x077F || c in 0x08A0..0x08FF ||
                    c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF
            }
        }

        val CYRILLIC = ScriptOcr(
            "rus", "cyrillic",
            setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tg"),
        ) { it.code in 0x0400..0x052F }

        val all = listOf(ARABIC, CYRILLIC)
    }

    private val lock = Mutex()
    private var tess: TessBaseAPI? = null

    /** Whether every capture is also read in this script, at the cost of a slower read. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(prefKey, systemUsesScript())

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(prefKey, enabled).apply()
    }

    private fun systemUsesScript(): Boolean {
        val locales = android.os.LocaleList.getDefault()
        return (0 until locales.size()).any { locales[it].language in scriptLanguages }
    }

    suspend fun read(context: Context, bitmap: Bitmap): List<Line> = withContext(Dispatchers.Default) {
        lock.withLock {
            val api = tess ?: open(context) ?: return@withLock emptyList()
            val pixels = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                api.setImage(pixels)
                collect(api)
            } finally {
                api.clear()
                if (pixels !== bitmap) pixels.recycle()
            }
        }
    }

    fun release() {
        // A read in flight holds the lock; it is cheap enough to keep the engine until next time.
        if (!lock.tryLock()) return
        try {
            tess?.recycle()
            tess = null
        } finally {
            lock.unlock()
        }
    }

    private fun open(context: Context): TessBaseAPI? {
        val root = File(context.noBackupFilesDir, "tesseract")
        val model = File(root, "tessdata/$language.traineddata")
        if (!model.exists()) {
            model.parentFile?.mkdirs()
            val partial = File(model.path + ".part")
            context.assets.open("tessdata/$language.traineddata").use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            partial.renameTo(model)
        }
        val api = TessBaseAPI()
        if (!api.init(root.absolutePath, language, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            android.util.Log.w("TextGrab", "Tesseract failed to load $language")
            return null
        }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        tess = api
        return api
    }

    private fun collect(api: TessBaseAPI): List<Line> {
        api.getUTF8Text() // runs recognition; the iterator below reads its result
        val it = api.resultIterator ?: return emptyList()
        val lines = ArrayList<Line>()
        var words = ArrayList<Pair<String, RectF>>()
        var confSum = 0f
        var block = 0
        try {
            it.begin()
            do {
                if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK) && lines.isNotEmpty()) block++
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) / 100f
                val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (text.isNotEmpty() && conf >= MIN_WORD_CONFIDENCE && rect != null && !rect.isEmpty) {
                    words.add(text to RectF(rect))
                    confSum += conf
                }
                if (it.isAtFinalElement(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE, TessBaseAPI.PageIteratorLevel.RIL_WORD)) {
                    line(words, confSum, block)?.let(lines::add)
                    words = ArrayList()
                    confSum = 0f
                }
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            line(words, confSum, block)?.let(lines::add)
        } finally {
            it.delete()
        }
        return lines
    }

    private fun line(words: List<Pair<String, RectF>>, confSum: Float, block: Int): Line? {
        if (words.isEmpty()) return null
        val confidence = confSum / words.size
        if (confidence < MIN_LINE_CONFIDENCE) return null
        val text = words.joinToString("") { it.first }
        val letters = text.count { it.isLetter() }
        // Mostly letters of the script, or Tesseract is reading another script with the wrong model.
        val native = text.count(isScript)
        if (letters == 0 || native * 2 < letters) return null
        // Icons read as a few glyphs of the script mixed with digits and symbols.
        val purity = if (confidence < SURE_LINE_CONFIDENCE) 0.9f else 0.7f
        if (native < 2 || native < purity * text.length) return null
        if (words.none { w -> w.first.count(isScript) >= 3 }) return null
        val box = RectF(words[0].second).apply { words.forEach { union(it.second) } }
        return Line(box, words, block, confidence)
    }
}
