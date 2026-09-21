package com.arslan.textgrab

import android.content.Context
import androidx.annotation.StringRes
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One batch of work for a backend: whole paragraphs in reading order, taken
 * from a single screen. Engines that can see all of them at once (the cloud
 * ones) should translate them together — that shared context is the whole
 * point of handing them over as a batch.
 */
class TranslationRequest(
    val texts: List<String>,
    /** Locally detected language of each text, same size as [texts]. */
    val sources: List<String>,
    val target: String,
    /** Fires once before a slow first-time step, e.g. a model download. */
    val onPrepare: () -> Unit = {},
)

/** A translation backend. Returns exactly one result per input text. */
interface TranslationEngine {
    suspend fun translate(request: TranslationRequest): List<String>
}

/** Failure with a message that is safe and useful to show to the user. */
class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Which backend translates, and the credentials the cloud ones need.
 *
 * Keys live in their own preferences file so it can be kept out of backups
 * (see `res/xml/backup_rules.xml`); nothing is ever sent anywhere except to
 * the endpoint of the engine the user picked.
 */
object EngineSettings {

    const val ON_DEVICE = "on_device"
    const val DEEPL = "deepl"
    const val GOOGLE = "google"
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"

    /** A ready-made endpoint/model pair offered in the configuration dialog. */
    class Preset(val label: String, val endpoint: String, val model: String)

    /** Everything the UI needs to show and configure one engine. */
    class Info(
        val id: String,
        @get:StringRes val title: Int,
        @get:StringRes val summary: Int,
        val needsKey: Boolean,
        val hasEndpoint: Boolean,
        val hasModel: Boolean,
        val defaultEndpoint: String = "",
        val defaultModel: String = "",
        @get:StringRes val keyHelp: Int = 0,
        val presets: List<Preset> = emptyList(),
    ) {
        val isCloud get() = needsKey
    }

    val engines: List<Info> = listOf(
        Info(
            id = ON_DEVICE,
            title = R.string.engine_on_device,
            summary = R.string.engine_on_device_summary,
            needsKey = false, hasEndpoint = false, hasModel = false,
        ),
        Info(
            id = DEEPL,
            title = R.string.engine_deepl,
            summary = R.string.engine_deepl_summary,
            needsKey = true, hasEndpoint = true, hasModel = false,
            defaultEndpoint = "",
            keyHelp = R.string.engine_key_help_deepl,
        ),
        Info(
            id = GOOGLE,
            title = R.string.engine_google,
            summary = R.string.engine_google_summary,
            needsKey = true, hasEndpoint = true, hasModel = false,
            defaultEndpoint = "https://translation.googleapis.com/language/translate/v2",
            keyHelp = R.string.engine_key_help_google,
        ),
        Info(
            id = OPENAI,
            title = R.string.engine_openai,
            summary = R.string.engine_openai_summary,
            needsKey = true, hasEndpoint = true, hasModel = true,
            defaultEndpoint = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            keyHelp = R.string.engine_key_help_openai,
            // Anything that speaks /chat/completions fits here; the presets
            // only save typing, every field stays editable.
            presets = listOf(
                Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
                Preset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
                Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
                Preset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
                Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
                Preset("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"),
                Preset("Together", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
                Preset("Ollama", "http://localhost:11434/v1", "llama3.1"),
                Preset("LM Studio", "http://localhost:1234/v1", "local-model"),
            ),
        ),
        Info(
            id = ANTHROPIC,
            title = R.string.engine_anthropic,
            summary = R.string.engine_anthropic_summary,
            needsKey = true, hasEndpoint = true, hasModel = true,
            defaultEndpoint = "https://api.anthropic.com/v1/messages",
            defaultModel = "claude-haiku-4-5-20251001",
            keyHelp = R.string.engine_key_help_anthropic,
            presets = listOf(
                Preset("Haiku 4.5", "https://api.anthropic.com/v1/messages", "claude-haiku-4-5-20251001"),
                Preset("Sonnet 5", "https://api.anthropic.com/v1/messages", "claude-sonnet-5"),
            ),
        ),
    )

    fun info(id: String): Info = engines.firstOrNull { it.id == id } ?: engines.first()

    /** Endpoint, model and key of one engine, with the defaults filled in. */
    class Config(val endpoint: String, val model: String, val key: String) {
        val hasKey get() = key.isNotBlank()

        /** A locally hosted endpoint, which is trusted without a key. */
        val isLocal get() = LOCAL.containsMatchIn(endpoint)
    }

    private const val PREFS = "translate_engines"
    private const val KEY_SELECTED = "selected"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedId(context: Context): String =
        prefs(context).getString(KEY_SELECTED, ON_DEVICE)
            ?.takeIf { id -> engines.any { it.id == id } } ?: ON_DEVICE

    fun selected(context: Context): Info = info(selectedId(context))

    fun select(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    fun config(context: Context, id: String): Config {
        val info = info(id)
        val p = prefs(context)
        return Config(
            endpoint = p.getString("$id.endpoint", null)?.takeIf { it.isNotBlank() } ?: info.defaultEndpoint,
            model = p.getString("$id.model", null)?.takeIf { it.isNotBlank() } ?: info.defaultModel,
            key = p.getString("$id.key", null).orEmpty(),
        )
    }

    fun saveConfig(context: Context, id: String, endpoint: String, model: String, key: String) {
        prefs(context).edit()
            .putString("$id.endpoint", endpoint.trim())
            .putString("$id.model", model.trim())
            .putString("$id.key", key.trim())
            .apply()
    }

    fun clear(context: Context, id: String) {
        prefs(context).edit()
            .remove("$id.endpoint").remove("$id.model").remove("$id.key").apply()
    }

    /** True once the engine can actually run. */
    fun isReady(context: Context, id: String): Boolean =
        isUsable(info(id), config(context, id))

    /** Same check against values that may not be saved yet. */
    fun isUsable(info: Info, config: Config): Boolean =
        !info.needsKey || config.hasKey || config.isLocal

    /** A model server on this device or on the emulator host needs no key. */
    private val LOCAL = Regex(
        """^https?://(localhost|127\.0\.0\.1|10\.0\.2\.2|\[::1])([:/]|$)""",
        RegexOption.IGNORE_CASE
    )

    /** Builds the engine for [id], or throws if the user still has to configure it. */
    fun engine(context: Context, id: String = selectedId(context)): TranslationEngine {
        val info = info(id)
        val config = config(context, id)
        if (!isUsable(info, config)) {
            throw TranslationException(
                context.getString(R.string.engine_needs_key, context.getString(info.title))
            )
        }
        return when (id) {
            DEEPL -> DeepLEngine(config)
            GOOGLE -> GoogleTranslateEngine(config)
            OPENAI -> OpenAiEngine(config)
            ANTHROPIC -> AnthropicEngine(config)
            else -> MlKitEngine
        }
    }
}

/** Minimal JSON-over-HTTPS client; the app carries no networking library. */
internal object Http {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): String =
        withContext(Dispatchers.IO) {
            val connection = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw TranslationException(badUrl(url), e)
            }
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                for ((name, value) in headers) connection.setRequestProperty(name, value)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw TranslationException(describe(status, text))
                text
            } catch (e: TranslationException) {
                throw e
            } catch (e: IOException) {
                throw TranslationException("Network error: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                connection.disconnect()
            }
        }

    private fun badUrl(url: String) = "Invalid endpoint: $url"

    /** Turns an HTTP failure into something the user can act on. */
    private fun describe(status: Int, body: String): String {
        val detail = detail(body)
        val reason = when (status) {
            400 -> "Request rejected"
            401, 403 -> "API key rejected"
            404 -> "Endpoint not found"
            413 -> "Too much text for one request"
            429 -> "Rate limit or quota reached"
            456 -> "Translation quota exhausted"
            in 500..599 -> "The service is having trouble"
            else -> "HTTP $status"
        }
        return if (detail.isEmpty()) "$reason ($status)" else "$reason ($status): $detail"
    }

    /** Pulls the human-readable part out of an error body, whatever its shape. */
    private fun detail(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val message = runCatching {
            val json = org.json.JSONObject(trimmed)
            val error = json.opt("error")
            when (error) {
                is org.json.JSONObject -> error.optString("message").ifEmpty { error.toString() }
                is String -> error
                else -> json.optString("message")
            }
        }.getOrNull().orEmpty().ifEmpty { trimmed }
        return message.take(200)
    }
}
