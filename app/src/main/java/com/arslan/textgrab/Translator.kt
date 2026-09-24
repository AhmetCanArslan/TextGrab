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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal val PROTECTED = Regex("""https?://\S+|www\.\S+|[\w.+-]+@[\w-]+\.[\w.]+|[@#]\w+""")

internal const val SENTENCE_END = ".!?…。！？؟"

internal fun isCjk(c: Char): Boolean = c.code.let {
    it in 0x3040..0x30FF || it in 0x3400..0x4DBF || it in 0x4E00..0x9FFF ||
        it in 0xAC00..0xD7AF || it in 0xFF00..0xFF60
}

object Translator {

    class Outcome(val lines: List<String?>, val sources: Set<String>)

    private const val PREFS = "translate"
    private const val KEY_TARGET = "target"

    private const val MIN_IDENTIFY_LENGTH = 12

    private const val MIN_IDENTIFY_CONFIDENCE = 0.5f

    /** Short snippets identify poorly, so only a strong guess is allowed to override the screen. */
    private const val SHORT_IDENTIFY_CONFIDENCE = 0.7f

    /** How much of the screen a second language must account for to be usable as a fallback. */
    private const val MIN_SECONDARY_CONFIDENCE = 0.2f

    private const val MAX_LINE_GAP = 0.9f

    private const val MIN_HEIGHT_RATIO = 0.7f

    private val NUMERIC = Regex(
        """[\d\s.,:;/%$€£¥₺+\-–()×x*]*\d[\d\s.,:;/%$€£¥₺+\-–()]*""" +
            """(am|pm|kb|mb|gb|tb|km|kg|cm|mm|m|g|h|min|s|ms)?\.?""",
        RegexOption.IGNORE_CASE
    )

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

    fun defaultTarget(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TARGET, null)
            ?: TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
            ?: TranslateLanguage.ENGLISH

    fun saveTarget(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TARGET, code).apply()
    }

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

        val screen = screenLanguages(result.fullText)
        val screenLanguage = screen.firstOrNull()?.first ?: TranslateLanguage.ENGLISH

        // When the screen as a whole reads as the target language, anything the identifier is
        // unsure about would inherit the target and be dropped from [pending] - which is how
        // short labels in another language end up never being translated. Fall back to the
        // strongest other language on screen instead.
        val fallback = if (screenLanguage != target) {
            screenLanguage
        } else {
            screen.firstOrNull { it.first != target && it.second >= MIN_SECONDARY_CONFIDENCE }
                ?.first ?: target
        }
        coroutineScope {
            paragraphs.map { p ->
                async { p.source = identifySource(p.text, screenLanguage, fallback, target) }
            }.awaitAll()
        }

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

    private class Paragraph(val lines: List<Int>, val prefix: String, val text: String) {
        var source: String = TranslateLanguage.ENGLISH
    }

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

        if (rows[prev] == rows[id]) return false

        if (BULLET.containsMatchIn(texts[id].trimStart())) return false

        val a = result.lineBoxes[prev]
        val b = result.lineBoxes[id]
        if (b.centerY() < a.centerY()) return false

        val height = max(a.height(), b.height())
        if (height <= 0f) return false
        val gap = b.top - a.bottom
        if (gap > MAX_LINE_GAP * height || gap < -0.6f * height) return false

        if (min(a.height(), b.height()) / height < MIN_HEIGHT_RATIO) return false

        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap < 0.4f * min(a.width(), b.width())) return false

        return result.lineBlocks[prev] == result.lineBlocks[id] || gap <= 0.5f * height
    }

    private fun isUntranslatable(line: String): Boolean {
        val t = line.trim()
        return t.none { it.isLetter() } || NUMERIC.matches(t) || PROTECTED.matches(t)
    }

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

                sb.append(t)
            } else {
                sb.append(' ').append(t)
            }
        }
        return sb.toString()
    }

    private fun distribute(text: String, widths: List<Float>): List<String> {
        if (widths.size == 1) return listOf(text)

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

    /** Every language on screen that ML Kit can translate, strongest first. */
    private suspend fun screenLanguages(text: String): List<Pair<String, Float>> =
        candidates(text).mapNotNull { (tag, confidence) ->
            TranslateLanguage.fromLanguageTag(tag)?.let { it to confidence }
        }

    private suspend fun identifySource(
        text: String,
        screenLanguage: String,
        fallback: String,
        target: String,
    ): String {
        val best = candidates(text).firstNotNullOfOrNull { (tag, confidence) ->
            TranslateLanguage.fromLanguageTag(tag)?.let { it to confidence }
        } ?: return fallback

        val (code, confidence) = best
        val floor = if (text.count { it.isLetter() } >= MIN_IDENTIFY_LENGTH) {
            MIN_IDENTIFY_CONFIDENCE
        } else {
            SHORT_IDENTIFY_CONFIDENCE
        }
        if (confidence >= floor) return code

        // Weak guess: trust the screen, but never turn a snippet the identifier already reads
        // as the target language into something to translate.
        return if (code == target) target else if (code == screenLanguage) screenLanguage else fallback
    }

    private suspend fun candidates(text: String): List<Pair<String, Float>> {
        if (text.isBlank()) return emptyList()
        return languageId.identifyPossibleLanguages(text).await()
            .filter { it.languageTag != "und" }
            .sortedByDescending { it.confidence }
            .map { it.languageTag to it.confidence }
    }

    internal suspend fun isDownloaded(code: String): Boolean =
        models.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()

    val busyLanguages: MutableSet<String> = HashSet()

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
