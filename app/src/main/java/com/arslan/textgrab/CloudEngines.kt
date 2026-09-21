package com.arslan.textgrab

import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class DeepLEngine(private val config: EngineSettings.Config) : TranslationEngine {

    private val batchSize = 40

    override suspend fun translate(request: TranslationRequest): List<String> {
        val target = targetCode(request.target)
        val out = ArrayList<String>(request.texts.size)
        for (batch in request.texts.chunked(batchSize)) {
            val body = JSONObject().apply {
                put("text", JSONArray(batch))
                put("target_lang", target)
                put("preserve_formatting", true)

                sourceCode(request.sources)?.let { put("source_lang", it) }
            }
            val response = Http.postJson(
                endpoint(),
                mapOf("Authorization" to "DeepL-Auth-Key ${config.key}"),
                body.toString(),
            )
            val translations = JSONObject(response).optJSONArray("translations")
                ?: throw TranslationException("DeepL returned no translations")
            for (i in batch.indices) {
                out.add(translations.optJSONObject(i)?.optString("text").orEmpty().ifEmpty { batch[i] })
            }
        }
        return out
    }

    private fun endpoint(): String = config.endpoint.ifBlank {
        if (config.key.trim().endsWith(":fx")) "https://api-free.deepl.com/v2/translate"
        else "https://api.deepl.com/v2/translate"
    }

    private fun sourceCode(sources: List<String>): String? {
        val single = sources.distinct().singleOrNull() ?: return null
        return SOURCE[single] ?: single.uppercase(Locale.ROOT).takeIf { it in SUPPORTED_SOURCES }
    }

    private fun targetCode(code: String): String =
        TARGET[code] ?: code.uppercase(Locale.ROOT).takeIf { it in SUPPORTED_TARGETS }
            ?: throw TranslationException(
                "DeepL cannot translate into ${Translator.displayName(code)}"
            )

    private companion object {

        val TARGET = mapOf("en" to "EN-US", "pt" to "PT-BR", "no" to "NB", "zh" to "ZH")
        val SOURCE = mapOf("no" to "NB")
        val SUPPORTED_TARGETS = setOf(
            "AR", "BG", "CS", "DA", "DE", "EL", "EN-GB", "EN-US", "ES", "ET", "FI", "FR", "HU",
            "ID", "IT", "JA", "KO", "LT", "LV", "NB", "NL", "PL", "PT-BR", "PT-PT", "RO", "RU",
            "SK", "SL", "SV", "TR", "UK", "ZH",
        )
        val SUPPORTED_SOURCES = setOf(
            "AR", "BG", "CS", "DA", "DE", "EL", "EN", "ES", "ET", "FI", "FR", "HU", "ID", "IT",
            "JA", "KO", "LT", "LV", "NB", "NL", "PL", "PT", "RO", "RU", "SK", "SL", "SV", "TR",
            "UK", "ZH",
        )
    }
}

class GoogleTranslateEngine(private val config: EngineSettings.Config) : TranslationEngine {

    private val batchSize = 50

    override suspend fun translate(request: TranslationRequest): List<String> {
        val url = config.endpoint.ifBlank {
            "https://translation.googleapis.com/language/translate/v2"
        } + "?key=" + URLEncoder.encode(config.key, "UTF-8")
        val out = ArrayList<String>(request.texts.size)
        for (batch in request.texts.chunked(batchSize)) {
            val body = JSONObject().apply {
                put("q", JSONArray(batch))
                put("target", request.target)
                put("format", "text")
                request.sources.distinct().singleOrNull()?.let { put("source", it) }
            }
            val response = Http.postJson(url, emptyMap(), body.toString())
            val translations = JSONObject(response).optJSONObject("data")?.optJSONArray("translations")
                ?: throw TranslationException("Google returned no translations")
            for (i in batch.indices) {
                val text = translations.optJSONObject(i)?.optString("translatedText").orEmpty()
                out.add(if (text.isEmpty()) batch[i] else unescape(text))
            }
        }
        return out
    }

    private fun unescape(text: String): String = text
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&amp;", "&")
}

abstract class LlmEngine(protected val config: EngineSettings.Config) : TranslationEngine {

    private val batchSize = 30
    private val batchChars = 4000

    protected abstract suspend fun complete(system: String, user: String, outputBudget: Int): String

    override suspend fun translate(request: TranslationRequest): List<String> {
        val target = Translator.displayName(request.target)
        val system = system(target, request.target)
        val out = ArrayList<String>(request.texts.size)
        for (batch in batches(request.texts)) {
            val texts = batch.map { request.texts[it] }
            val sources = batch.map { request.sources[it] }.distinct()
                .joinToString(", ") { Translator.displayName(it) }
            val user = buildString {
                append("Target language: ").append(target).append(" (").append(request.target).append(")\n")
                if (sources.isNotEmpty()) append("Detected source language(s): ").append(sources).append('\n')
                append('\n')
                texts.forEachIndexed { i, text -> append(i + 1).append(". ").append(text).append('\n') }
            }
            val budget = texts.sumOf { it.length } / 2 + 512
            val answer = complete(system, user, budget.coerceIn(512, 8192))
            val parsed = parse(answer, texts.size)
            texts.forEachIndexed { i, text -> out.add(parsed[i] ?: text) }
        }
        return out
    }

    private fun batches(texts: List<String>): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var chars = 0
        for (i in texts.indices) {
            if (current.isNotEmpty() && (current.size >= batchSize || chars + texts[i].length > batchChars)) {
                out.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(i)
            chars += texts[i].length
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun system(targetName: String, targetCode: String): String = """
        You are a translation engine for text read off a screenshot by OCR. Translate each numbered segment into $targetName ($targetCode).

        The segments all come from one screen, in reading order: read them together and use them as context for each other, so that titles, buttons and body text end up consistent with one another.

        Rules:
        - Return every segment, in the same order, numbered the same way: "<number>. <translation>". Nothing else — no notes, no explanations, no extra blank lines.
        - Keep the register and tone of the original. Interface text should read like interface text in $targetName: short, idiomatic, the wording that software actually uses.
        - Never add, drop or explain content.
        - Leave verbatim: URLs, email addresses, @handles, #hashtags, file names, code, version numbers, numbers with their units, and brand or product names.
        - A segment already in $targetName is repeated unchanged.
        - OCR makes mistakes; translate what the text clearly meant to say, and never comment on it.
        - If a segment is a single word with no context, translate it the way that word is normally used in a user interface.
    """.trimIndent()

    private fun parse(answer: String, size: Int): Array<String?> {
        val out = arrayOfNulls<String>(size)
        val head = Regex("""^\s*(\d{1,3})\s*[.)\]]\s*(.*)$""")
        var current = -1
        val sb = StringBuilder()
        fun flush() {
            if (current in 0 until size) out[current] = sb.toString().trim()
            sb.setLength(0)
        }
        for (line in answer.lines()) {
            val match = head.find(line)
            if (match != null) {
                flush()
                current = match.groupValues[1].toInt() - 1
                sb.append(match.groupValues[2])
            } else if (current >= 0) {
                if (line.isBlank()) continue
                sb.append(' ').append(line.trim())
            }
        }
        flush()

        if (size == 1 && out[0].isNullOrBlank()) {
            answer.trim().takeIf { it.isNotEmpty() }?.let { out[0] = it }
        }
        return out
    }

    protected fun contentOf(json: JSONObject, path: () -> String?): String =
        path()?.takeIf { it.isNotBlank() }
            ?: throw TranslationException("The model returned an empty answer: ${json.toString().take(200)}")
}

class OpenAiEngine(config: EngineSettings.Config) : LlmEngine(config) {

    override suspend fun complete(system: String, user: String, outputBudget: Int): String {
        val url = config.endpoint.trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }
        val body = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                }
            )
        }

        val headers =
            if (config.hasKey) mapOf("Authorization" to "Bearer ${config.key}") else emptyMap()
        val response = try {
            Http.postJson(url, headers, body.toString())
        } catch (e: TranslationException) {

            if (e.message?.contains("temperature", ignoreCase = true) != true) throw e
            Http.postJson(url, headers, body.apply { remove("temperature") }.toString())
        }
        val json = JSONObject(response)
        return contentOf(json) {
            json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
        }
    }
}

class AnthropicEngine(config: EngineSettings.Config) : LlmEngine(config) {

    override suspend fun complete(system: String, user: String, outputBudget: Int): String {
        val url = config.endpoint.trimEnd('/').let {
            if (it.endsWith("/messages")) it else "$it/messages"
        }
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", outputBudget)
            put("temperature", 0)
            put("system", system)
            put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", user))
            )
        }
        val response = Http.postJson(
            url,
            mapOf("x-api-key" to config.key, "anthropic-version" to "2023-06-01"),
            body.toString(),
        )
        val json = JSONObject(response)
        return contentOf(json) {
            val content = json.optJSONArray("content") ?: return@contentOf null
            (0 until content.length())
                .mapNotNull { content.optJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .joinToString("") { it.optString("text") }
        }
    }
}
