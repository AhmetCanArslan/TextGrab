package com.arslan.textgrab

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Kept verbatim inside sentences: links, emails, handles, hashtags. */
internal val PROTECTED = Regex("""https?://\S+|www\.\S+|[\w.+-]+@[\w-]+\.[\w.]+|[@#]\w+""")

internal const val SENTENCE_END = ".!?…。！？؟"

internal fun isCjk(c: Char): Boolean = c.code.let {
    it in 0x3040..0x30FF || it in 0x3400..0x4DBF || it in 0x4E00..0x9FFF ||
        it in 0xAC00..0xD7AF || it in 0xFF00..0xFF60
}

/**
 * Turns an OCR result into a translated one, whichever backend does the actual
 * translating (see [TranslationEngine] and [EngineSettings]).
 *
 * The part that decides the quality lives here, not in the backend: OCR lines
 * are stitched back into paragraphs by layout — ML Kit's own block ids shatter
 * on screenshots, which is how context gets lost — the language of the screen
 * and of each paragraph is identified on-device, and the translation is then
 * reflowed over the lines it came from. Backends only ever see whole
 * paragraphs, in reading order, as one batch per screen.
 */
object Translator {

    /**
     * [lines] is indexed like [OcrEngine.Result.lineBoxes]; null keeps the
     * original line (numbers, links, text already in the target language).
     */
    class Outcome(val lines: List<String?>, val sources: Set<String>)

    private const val PREFS = "translate"
    private const val KEY_TARGET = "target"

    /** Shorter runs are too ambiguous to identify; they use the screen's language. */
    private const val MIN_IDENTIFY_LENGTH = 20

    /** Confidence needed before a paragraph may disagree with the screen's language. */
    private const val MIN_IDENTIFY_CONFIDENCE = 0.6f

    /** Vertical gap between two lines of one paragraph, relative to line height. */
    private const val MAX_LINE_GAP = 0.9f

    /** Lines of very different heights are a heading and its body, not a paragraph. */
    private const val MIN_HEIGHT_RATIO = 0.7f

    /** Whole lines that are only numbers, prices, times, dates or sizes. */
    private val NUMERIC = Regex(
        """[\d\s.,:;/%$€£¥₺+\-–()×x*]*\d[\d\s.,:;/%$€£¥₺+\-–()]*""" +
            """(am|pm|kb|mb|gb|tb|km|kg|cm|mm|m|g|h|min|s|ms)?\.?""",
        RegexOption.IGNORE_CASE
    )

    /** List markers, kept out of the translation and put back in front of it. */
    private val BULLET = Regex("""^\s*([•·‣▪◦●○*+]|[-–—]|\(?\d{1,2}[.)]|[a-zA-Z][.)])\s+""")

    private val languageId by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.2f).build()
        )
    }
    private val models by lazy { RemoteModelManager.getInstance() }

    val languages: List<String> get() = TranslateLanguage.getAllLanguages()

    fun displayName(code: String): String =
        Locale.forLanguageTag(code).let { it.getDisplayName(it) }
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** Last chosen target, falling back to the device language, then English. */
    fun defaultTarget(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TARGET, null)
            ?: TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
            ?: TranslateLanguage.ENGLISH

    fun saveTarget(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TARGET, code).apply()
    }

    /**
     * Translates a whole OCR result with the engine the user selected. Lines
     * are stitched into paragraphs by layout, translated as running text, then
     * reflowed back over the lines they came from. [onPrepare] fires once if a
     * slow first-time step is needed, e.g. downloading a language model.
     */
    suspend fun translate(
        context: Context,
        result: OcrEngine.Result,
        target: String,
        onPrepare: () -> Unit,
    ): Outcome {
        val texts = result.lineTexts
        val out = arrayOfNulls<String>(texts.size)
        val paragraphs = paragraphs(result, texts)
        if (paragraphs.isEmpty()) return Outcome(out.toList(), emptySet())

        val screenLanguage = dominantLanguage(result.fullText) ?: TranslateLanguage.ENGLISH
        for (p in paragraphs) p.source = identifySource(p.text, screenLanguage)

        // Text already in the target language keeps its original line.
        val pending = paragraphs.filter { it.source != target }
        if (pending.isEmpty()) return Outcome(out.toList(), emptySet())

        val engine = EngineSettings.engine(context)
        val translated = engine.translate(
            TranslationRequest(
                texts = pending.map { it.text },
                sources = pending.map { it.source },
                target = target,
                onPrepare = onPrepare,
            )
        )
        val sources = LinkedHashSet<String>()
        pending.forEachIndexed { i, p ->
            val text = translated.getOrNull(i)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val widths = p.lines.map { result.lineBoxes[it].width() }
            distribute(p.prefix + text, widths)
                .forEachIndexed { line, lineText -> out[p.lines[line]] = lineText }
            sources.add(p.source)
        }
        return Outcome(out.toList(), sources)
    }

    // ---------------------------------------------------------- paragraphing

    /** Lines that read as one paragraph, plus the text handed to the model. */
    private class Paragraph(val lines: List<Int>, val prefix: String, val text: String) {
        var source: String = TranslateLanguage.ENGLISH
    }

    /**
     * Groups lines into paragraphs by how they sit on screen. ML Kit's own
     * blocks are far too fine-grained on screenshots — a paragraph regularly
     * arrives as one block per line, which is exactly how context gets lost.
     */
    private fun paragraphs(result: OcrEngine.Result, texts: List<String>): List<Paragraph> {
        val rows = rowOfLine(result)
        val runs = ArrayList<MutableList<Int>>()
        var current: MutableList<Int>? = null
        for (id in texts.indices) {
            if (isUntranslatable(texts[id])) {
                current = null
                continue
            }
            val prev = current?.last()
            if (prev == null || !continuesParagraph(result, rows, texts, prev, id)) {
                current = mutableListOf<Int>().also { runs.add(it) }
            }
            current!!.add(id)
        }
        return runs.map { run ->
            val marker = BULLET.find(texts[run.first()].trimStart())?.value?.trim().orEmpty()
            val body = joinLines(run.map { texts[it] }).let {
                if (marker.isEmpty()) it else it.removePrefix(marker).trimStart()
            }
            Paragraph(run, if (marker.isEmpty()) "" else "$marker ", body)
        }.filter { it.text.isNotBlank() }
    }

    /** Visual row of each line, so side-by-side columns never merge. */
    private fun rowOfLine(result: OcrEngine.Result): IntArray {
        val rows = IntArray(result.lineBoxes.size) { -1 }
        for (w in result.words) if (rows[w.lineId] == -1) rows[w.lineId] = w.rowId
        return rows
    }

    private fun continuesParagraph(
        result: OcrEngine.Result,
        rows: IntArray,
        texts: List<String>,
        prev: Int,
        id: Int,
    ): Boolean {
        // Two lines on one visual row are separate columns, not a paragraph.
        if (rows[prev] == rows[id]) return false
        // A list marker always opens a new item.
        if (BULLET.containsMatchIn(texts[id].trimStart())) return false

        val a = result.lineBoxes[prev]
        val b = result.lineBoxes[id]
        if (b.centerY() < a.centerY()) return false

        val height = max(a.height(), b.height())
        if (height <= 0f) return false
        val gap = b.top - a.bottom
        if (gap > MAX_LINE_GAP * height || gap < -0.6f * height) return false
        // Different type sizes mean a heading meeting its body text.
        if (min(a.height(), b.height()) / height < MIN_HEIGHT_RATIO) return false
        // Same column: the lines have to sit over each other.
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap < 0.4f * min(a.width(), b.width())) return false

        // Inside one ML Kit block the lines are known to belong together; across
        // blocks, only tight line spacing is trusted.
        return result.lineBlocks[prev] == result.lineBlocks[id] || gap <= 0.5f * height
    }

    private fun isUntranslatable(line: String): Boolean {
        val t = line.trim()
        return t.none { it.isLetter() } || NUMERIC.matches(t) || PROTECTED.matches(t)
    }

    /** Joins OCR lines into running text, undoing words hyphenated across lines. */
    private fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(t)
            } else if (sb.length > 1 && sb.last() == '-' && sb[sb.length - 2].isLetter() &&
                t.firstOrNull()?.isLowerCase() == true
            ) {
                sb.setLength(sb.length - 1)
                sb.append(t)
            } else if (isCjk(sb.last()) && t.first().let { isCjk(it) || it in SENTENCE_END }) {
                // Chinese, Japanese and Korean wrap without spaces.
                sb.append(t)
            } else {
                sb.append(' ').append(t)
            }
        }
        return sb.toString()
    }

    // -------------------------------------------------------- layout & language

    /** Wraps [text] over lines in proportion to their widths, like reflowing a paragraph. */
    private fun distribute(text: String, widths: List<Float>): List<String> {
        if (widths.size == 1) return listOf(text)
        // Scripts without spaces (Chinese, Japanese, Thai) wrap per character.
        val spaced = text.contains(' ')
        val tokens = if (spaced) text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        else text.map { it.toString() }
        val total = tokens.sumOf { it.length }.toFloat()
        val sumWidth = widths.sum().coerceAtLeast(1f)
        val out = List(widths.size) { StringBuilder() }
        var line = 0
        var used = 0f
        var boundary = total * widths[0] / sumWidth
        for (token in tokens) {
            // Move on once the token's middle would cross this line's share.
            while (line < widths.size - 1 && used + token.length / 2f > boundary) {
                line++
                boundary += total * widths[line] / sumWidth
            }
            if (out[line].isNotEmpty() && spaced) out[line].append(' ')
            out[line].append(token)
            used += token.length
        }
        return out.map { it.toString() }
    }

    /** The language of the screen as a whole; the fallback for short paragraphs. */
    private suspend fun dominantLanguage(text: String): String? =
        candidates(text).firstNotNullOfOrNull { TranslateLanguage.fromLanguageTag(it.first) }

    /**
     * A paragraph only gets its own language when the guess is confident —
     * otherwise a stray button or a proper noun would be translated as if it
     * were another language, which is where the worst output comes from.
     */
    private suspend fun identifySource(text: String, screenLanguage: String): String {
        if (text.count { it.isLetter() } < MIN_IDENTIFY_LENGTH) return screenLanguage
        for ((tag, confidence) in candidates(text)) {
            val code = TranslateLanguage.fromLanguageTag(tag) ?: continue
            if (code == screenLanguage) return screenLanguage
            return if (confidence >= MIN_IDENTIFY_CONFIDENCE) code else screenLanguage
        }
        return screenLanguage
    }

    /** Identified languages, most likely first. */
    private suspend fun candidates(text: String): List<Pair<String, Float>> {
        if (text.isBlank()) return emptyList()
        return languageId.identifyPossibleLanguages(text).await()
            .filter { it.languageTag != "und" }
            .sortedByDescending { it.confidence }
            .map { it.languageTag to it.confidence }
    }

    internal suspend fun isDownloaded(code: String): Boolean =
        models.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()

    // ------------------------------------------------------ model management

    /** Languages whose model is currently being downloaded from the language manager. */
    val busyLanguages: MutableSet<String> = HashSet()

    /** English ships with ML Kit and cannot be removed. */
    fun isBuiltIn(code: String) = code == TranslateLanguage.ENGLISH

    suspend fun downloadedLanguages(): Set<String> =
        models.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .mapTo(HashSet()) { it.language } + TranslateLanguage.ENGLISH

    suspend fun downloadLanguage(code: String) {
        models.download(TranslateRemoteModel.Builder(code).build(), DownloadConditions.Builder().build()).await()
    }

    suspend fun deleteLanguage(code: String) {
        if (isBuiltIn(code)) return
        models.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
    }
}
