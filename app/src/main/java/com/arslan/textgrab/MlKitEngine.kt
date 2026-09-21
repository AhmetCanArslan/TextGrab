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

object MlKitEngine : TranslationEngine {

    private const val MAX_CHARS = 300

    private const val MAX_PARALLEL = 4

    private val PLACEHOLDER = Regex("""\[\s*(\d{1,2})\s*]""")

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

            if (text.getOrNull(at - 1)?.isDigit() == true && next?.isDigit() == true) return false

            var j = at - 1
            while (j >= 0 && text[j].isLetter()) j--
            val stub = at - j - 1
            if (stub in 1..2 && (text[j + 1].isUpperCase() || text.getOrNull(j) == '.')) return false
        }
        if (next == null) return true

        if (c in "。！？" || isCjk(c) || text.getOrNull(at - 1)?.let(::isCjk) == true) return true
        if (!next.isWhitespace()) return false
        val after = text.drop(at + 1).firstOrNull { !it.isWhitespace() } ?: return true
        return !after.isLowerCase()
    }

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
