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

/** Arabic-script recognition, which ML Kit lacks, through Tesseract and its bundled model. */
object ArabicOcr {

    class Line(val box: RectF, val words: List<Pair<String, RectF>>, val blockId: Int, val confidence: Float)

    private const val LANGUAGE = "ara"

    /** A line Tesseract is less sure of than this is almost always Latin or CJK read as Arabic. */
    private const val MIN_LINE_CONFIDENCE = 0.6f

    /** Below this a line must be almost purely Arabic letters to count. */
    private const val SURE_LINE_CONFIDENCE = 0.8f

    private const val MIN_WORD_CONFIDENCE = 0.3f

    private const val PREFS = "recognition"
    private const val KEY_ALWAYS = "arabic"

    /** Languages written in Arabic script, for which reading it on every capture is on by default. */
    private val SCRIPT_LANGUAGES = setOf("ar", "fa", "ur", "ps", "ckb", "sd", "ug")

    private val lock = Mutex()
    private var tess: TessBaseAPI? = null

    /** Whether every capture is also read as Arabic, at the cost of a slower read. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS, systemUsesScript())

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ALWAYS, enabled).apply()
    }

    private fun systemUsesScript(): Boolean {
        val locales = android.os.LocaleList.getDefault()
        return (0 until locales.size()).any { locales[it].language in SCRIPT_LANGUAGES }
    }

    private fun isArabic(c: Char): Boolean = c.code.let {
        it in 0x0600..0x06FF || it in 0x0750..0x077F || it in 0x08A0..0x08FF ||
            it in 0xFB50..0xFDFF || it in 0xFE70..0xFEFF
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
        val model = File(root, "tessdata/$LANGUAGE.traineddata")
        if (!model.exists()) {
            model.parentFile?.mkdirs()
            val partial = File(model.path + ".part")
            context.assets.open("tessdata/$LANGUAGE.traineddata").use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            partial.renameTo(model)
        }
        val api = TessBaseAPI()
        if (!api.init(root.absolutePath, LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            android.util.Log.w("TextGrab", "Tesseract failed to load $LANGUAGE")
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
        // Mostly Arabic letters, or Tesseract is reading another script with the wrong model.
        if (letters == 0 || text.count(::isArabic) * 2 < letters) return null
        // Icons read as a few Arabic glyphs mixed with digits and symbols.
        val arabic = text.count(::isArabic)
        val purity = if (confidence < SURE_LINE_CONFIDENCE) 0.9f else 0.7f
        if (arabic < 2 || arabic < purity * text.length) return null
        if (words.none { w -> w.first.count(::isArabic) >= 3 }) return null
        val box = RectF(words[0].second).apply { words.forEach { union(it.second) } }
        return Line(box, words, block, confidence)
    }
}
