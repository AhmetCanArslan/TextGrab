package com.arslan.textgrab

import android.util.LruCache
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translator as MlTranslator
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The bundled offline backend. Models (~30 MB per language) are downloaded once
 * on first use, after which it works with no network at all.
 *
 * ML Kit translates one sentence at a time and has no memory between calls, so
 * everything here is about handing it the largest honest unit of text: links
 * and handles become placeholders instead of splitting the sentence around
 * them, shouted labels are lowercased so the model sees real words, and the
 * paragraph goes in as whole sentences rather than arbitrary fragments.
 */
object MlKitEngine : TranslationEngine {

    /** Longest text handed to the model at once; beyond this quality drops off. */
    private const val MAX_CHARS = 300

    /** Paragraphs translated at the same time. */
    private const val MAX_PARALLEL = 4

    /** Stand-in for a protected token while the sentence is translated. */
    private val PLACEHOLDER = Regex("""\[\s*(\d{1,2})\s*]""")

    /** Repeated UI strings (tabs, buttons, labels) are translated once per session. */
    private val cache = LruCache<String, String>(512)

    override suspend fun translate(request: TranslationRequest): List<String> {
        val target = request.target
        val clients = HashMap<String, MlTranslator>()
        try {
            var downloadAnnounced = false
            for (source in request.sources.toSet()) {
                if (source == target) continue
                if (!downloadAnnounced && (!Translator.isDownloaded(source) || !Translator.isDownloaded(target))) {
                    downloadAnnounced = true
                    request.onPrepare()
                }
                clients[source] = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source).setTargetLanguage(target).build()
                ).also { it.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
            }
            val gate = Semaphore(MAX_PARALLEL)
            return coroutineScope {
                request.texts.mapIndexed { i, text ->
                    async {
                        val source = request.sources[i]
                        val client = clients[source] ?: return@async text
                        gate.withPermit { translateText(client, source, target, text) }
                    }
                }.awaitAll()
            }
        } finally {
            clients.values.forEach { it.close() }
        }
    }

    /**
     * Translates one paragraph: protected tokens are masked so the sentence
     * stays whole, shouted text is lowercased so the model sees real words, and
     * the text goes in as complete sentences rather than arbitrary fragments.
     */
    private suspend fun translateText(
        client: MlTranslator,
        source: String,
        target: String,
        text: String,
    ): String {
        val key = "$source>$target\u0000$text"
        cache.get(key)?.let { return it }

        val tokens = ArrayList<String>()
        var masked = PROTECTED.replace(text) { m ->
            tokens.add(m.value)
            "[${tokens.size}]"
        }
        // ALL-CAPS labels are out-of-vocabulary for the model; translate them as
        // ordinary words and shout the result back.
        val shouted = isShouted(masked)
        if (shouted) masked = masked.lowercase(Locale.forLanguageTag(source))

        val sb = StringBuilder()
        for (chunk in chunks(masked)) {
            if (chunk.none { it.isLetter() }) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(chunk)
                continue
            }
            val piece = client.translate(chunk).await().trim()
            if (piece.isEmpty()) continue
            if (sb.isNotEmpty() && !isCjk(sb.last())) sb.append(' ')
            sb.append(piece)
        }

        var result = sb.toString()
        if (shouted) result = result.uppercase(Locale.forLanguageTag(target))
        result = restore(result, tokens)
        cache.put(key, result)
        return result
    }

    private fun isShouted(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 2 && letters.all { it.isUpperCase() }
    }

    /** Puts the protected tokens back; any the model dropped are appended. */
    private fun restore(text: String, tokens: List<String>): String {
        if (tokens.isEmpty()) return text
        val used = BooleanArray(tokens.size)
        val out = PLACEHOLDER.replace(text) { m ->
            val i = m.groupValues[1].toInt() - 1
            if (i in tokens.indices) {
                used[i] = true
                tokens[i]
            } else {
                m.value
            }
        }
        val missing = tokens.filterIndexed { i, _ -> !used[i] }
        return if (missing.isEmpty()) out else (out.trimEnd() + " " + missing.joinToString(" ")).trim()
    }

    /** Whole sentences, packed up to [MAX_CHARS] so the model keeps the context. */
    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in sentences(text)) {
            for (part in splitLong(sentence)) {
                if (sb.isNotEmpty() && sb.length + 1 + part.length > MAX_CHARS) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(part)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Splits on sentence ends, leaving decimals and short abbreviations alone. */
    private fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in SENTENCE_END && breaksHere(text, i)) {
                var end = i + 1
                while (end < text.length && (text[end] in SENTENCE_END || text[end] in "\"')]»”’")) end++
                text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                start = end
                i = end
                continue
            }
            i++
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun breaksHere(text: String, at: Int): Boolean {
        val c = text[at]
        val next = text.getOrNull(at + 1)
        if (c == '.') {
            // "3.5", "v1.2"
            if (text.getOrNull(at - 1)?.isDigit() == true && next?.isDigit() == true) return false
            // "Dr.", "e.g.", "No." — an abbreviation, not the end of a sentence.
            // Sentences really can end in a short word ("it.", "up."), so only a
            // capitalised or already dotted stub counts.
            var j = at - 1
            while (j >= 0 && text[j].isLetter()) j--
            val stub = at - j - 1
            if (stub in 1..2 && (text[j + 1].isUpperCase() || text.getOrNull(j) == '.')) return false
        }
        if (next == null) return true
        // CJK text has no space after its full stop.
        if (c in "。！？" || isCjk(c) || text.getOrNull(at - 1)?.let(::isCjk) == true) return true
        if (!next.isWhitespace()) return false
        val after = text.drop(at + 1).firstOrNull { !it.isWhitespace() } ?: return true
        return !after.isLowerCase()
    }

    /** Last resort for a sentence that alone exceeds the model's comfort zone. */
    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARS) return listOf(sentence)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        val spaced = sentence.contains(' ')
        val tokens = if (spaced) sentence.split(' ') else sentence.map { it.toString() }
        for (token in tokens) {
            if (sb.isNotEmpty() && sb.length + token.length + 1 > MAX_CHARS) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty() && spaced) sb.append(' ')
            sb.append(token)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
