package com.arslan.textgrab

import android.content.Context
import android.graphics.RectF
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device translation via ML Kit. Language identification is bundled in the
 * APK; translation models (~30 MB per language) are downloaded once on first
 * use, after which translating works offline.
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

    /** Kept verbatim inside sentences: links, emails, handles, hashtags. */
    private val PROTECTED = Regex("""https?://\S+|www\.\S+|[\w.+-]+@[\w-]+\.[\w.]+|[@#]\w+""")

    /** Whole lines that are only numbers, prices, times, dates or sizes. */
    private val NUMERIC = Regex(
        """[\d\s.,:;/%$€£¥₺+\-–()×x*]*\d[\d\s.,:;/%$€£¥₺+\-–()]*""" +
            """(am|pm|kb|mb|gb|tb|km|kg|cm|mm|m|g|h|min|s|ms)?\.?""",
        RegexOption.IGNORE_CASE
    )

    private val languageId by lazy { LanguageIdentification.getClient() }
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
     * Translates a whole OCR result. Lines are grouped into paragraphs (runs of
     * lines in the same ML Kit block), each translated as one sentence with its
     * own detected language, then the result is wrapped back over the lines.
     * [onDownload] fires once if a language model has to be downloaded first.
     */
    suspend fun translate(result: OcrEngine.Result, target: String, onDownload: () -> Unit): Outcome {
        val texts = result.lineTexts
        val out = arrayOfNulls<String>(texts.size)
        val sources = LinkedHashSet<String>()
        val screenLanguage = identify(result.fullText) ?: TranslateLanguage.ENGLISH
        val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
        var downloadAnnounced = false
        try {
            for (run in paragraphs(result, texts)) {
                val text = joinLines(run.map { texts[it] })
                val source = (if (text.length >= MIN_IDENTIFY_LENGTH) identify(text) else null)
                    ?: screenLanguage
                if (source == target) continue
                val client = clients.getOrPut(source) {
                    if (!downloadAnnounced && (!isDownloaded(source) || !isDownloaded(target))) {
                        downloadAnnounced = true
                        onDownload()
                    }
                    Translation.getClient(
                        TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
                    ).also { it.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                }
                val translated = translateProtected(client, text)
                val widths = run.map { result.lineBoxes[it].width() }
                distribute(translated, widths).forEachIndexed { i, line -> out[run[i]] = line }
                sources.add(source)
            }
        } finally {
            clients.values.forEach { it.close() }
        }
        return Outcome(out.toList(), sources)
    }

    /** Consecutive lines of one block, split around lines that shouldn't be translated. */
    private fun paragraphs(result: OcrEngine.Result, texts: List<String>): List<List<Int>> {
        val runs = ArrayList<MutableList<Int>>()
        var current: MutableList<Int>? = null
        for (id in texts.indices) {
            if (isUntranslatable(texts[id])) {
                current = null
                continue
            }
            val prev = current?.last()
            if (prev == null || result.lineBlocks[prev] != result.lineBlocks[id]) {
                current = mutableListOf<Int>().also { runs.add(it) }
            }
            current!!.add(id)
        }
        return runs
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
            if (sb.isEmpty()) {
                sb.append(t)
            } else if (sb.length > 1 && sb.last() == '-' && sb[sb.length - 2].isLetter() &&
                t.firstOrNull()?.isLowerCase() == true
            ) {
                sb.setLength(sb.length - 1)
                sb.append(t)
            } else {
                sb.append(' ').append(t)
            }
        }
        return sb.toString()
    }

    /** Translates the text between protected tokens, keeping the tokens themselves verbatim. */
    private suspend fun translateProtected(
        client: com.google.mlkit.nl.translate.Translator, text: String,
    ): String {
        val sb = StringBuilder()
        var last = 0
        suspend fun chunk(s: String) {
            if (s.none { it.isLetter() }) {
                sb.append(s)
                return
            }
            val lead = s.takeWhile { it.isWhitespace() }
            val trail = s.takeLastWhile { it.isWhitespace() }
            sb.append(lead).append(client.translate(s.trim()).await()).append(trail)
        }
        for (m in PROTECTED.findAll(text)) {
            chunk(text.substring(last, m.range.first))
            sb.append(m.value)
            last = m.range.last + 1
        }
        chunk(text.substring(last))
        return sb.toString()
    }

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

    private suspend fun identify(text: String): String? {
        val tag = languageId.identifyLanguage(text).await()
        return if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
    }

    private suspend fun isDownloaded(code: String): Boolean =
        models.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()

}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
