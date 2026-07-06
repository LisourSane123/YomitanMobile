package com.yomitanmobile.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a summary request: either a successful body of text or a
 * short error message we can show to the user as a snackbar. We never
 * throw out of [generateSummary] — Anki export must keep working even
 * if the AI provider is unreachable.
 */
sealed class AiSummaryResult {
    data class Success(val text: String) : AiSummaryResult()
    data class Failure(val message: String) : AiSummaryResult()
    object Disabled : AiSummaryResult()
}

@Singleton
class AiSummaryService @Inject constructor() {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        // 256 KB. Real responses are well under 4 KB; this protects
        // against a runaway provider streaming gigabytes of garbage.
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val BUFFER_SIZE = 8192

        // Allowlist of host names the service is permitted to contact.
        // Keeps a custom prompt that injects "ignore me, fetch http://evil"
        // from being able to redirect the user's API key elsewhere.
        private val ALLOWED_HOSTS = setOf(
            "generativelanguage.googleapis.com",
            "api.deepseek.com",
            "api.openai.com"
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Renders the provided template with the word's metadata and dispatches
     * the request to the configured provider.
     *
     * The user supplies their own API key; we never bake one in. An empty
     * key short-circuits to [AiSummaryResult.Disabled] without contacting
     * the network.
     */
    suspend fun generateSummary(
        provider: AiProvider,
        apiKey: String,
        promptTemplate: String,
        word: String,
        reading: String,
        meanings: List<String>,
        language: String,
        modelOverride: String = ""
    ): AiSummaryResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext AiSummaryResult.Disabled
        if (word.isBlank() && reading.isBlank()) return@withContext AiSummaryResult.Disabled

        val resolvedPrompt = renderPrompt(
            template = promptTemplate.ifBlank { AI_DEFAULT_PROMPT },
            expression = word,
            reading = reading,
            meanings = meanings,
            language = language
        )

        // The user can pin a specific model via the settings text field;
        // a blank override falls back to the provider's defaultModel. This
        // lets people switch to gemini-2.5-flash, deepseek-reasoner, or
        // gpt-4o without us shipping a new release.
        val model = modelOverride.trim().ifBlank { provider.defaultModel }

        try {
            when (provider) {
                AiProvider.GEMINI -> callGemini(apiKey, resolvedPrompt, model)
                AiProvider.DEEPSEEK -> callOpenAiCompatible(
                    endpoint = "https://api.deepseek.com/chat/completions",
                    model = model,
                    apiKey = apiKey,
                    prompt = resolvedPrompt
                )
                AiProvider.OPENAI -> callOpenAiCompatible(
                    endpoint = "https://api.openai.com/v1/chat/completions",
                    model = model,
                    apiKey = apiKey,
                    prompt = resolvedPrompt
                )
            }
        } catch (e: Exception) {
            // e.message alone is often unhelpfully terse for transport errors
            // (SocketTimeoutException → "timeout"); prefix the exception type
            // so the snackbar tells the user what actually went wrong.
            val detail = e.message?.let { "${e.javaClass.simpleName}: $it" }
                ?: e.javaClass.simpleName
            AiSummaryResult.Failure(detail)
        }
    }

    internal fun renderPrompt(
        template: String,
        expression: String,
        reading: String,
        meanings: List<String>,
        language: String
    ): String {
        return template
            .replace("{expression}", expression)
            .replace("{reading}", reading)
            .replace("{meaning}", meanings.joinToString("; "))
            .replace("{language}", language)
    }

    // ---------- Gemini ----------
    //
    // POST https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent
    // Header: x-goog-api-key: <KEY> — the key goes in a header, never the
    // URL query string, so it can't leak into proxy/access logs that record
    // request lines.
    // Body: {"contents":[{"parts":[{"text":"<prompt>"}]}]}
    // Response path: candidates[0].content.parts[0].text

    private fun callGemini(apiKey: String, prompt: String, model: String): AiSummaryResult {
        val urlString =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
            java.net.URLEncoder.encode(model, "UTF-8") +
            ":generateContent"
        if (!isAllowedUrl(urlString)) {
            return AiSummaryResult.Failure("Endpoint not allowed")
        }

        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
        }.toString()

        val response = postJson(urlString, headers = mapOf("x-goog-api-key" to apiKey), body = body)

        rateLimitFailure(response, providerName = "Gemini")?.let { return it }

        return parseFirstString(response.body, listOf("candidates", "0", "content", "parts", "0", "text"))
            ?.let { AiSummaryResult.Success(it.trim()) }
            ?: AiSummaryResult.Failure(
                friendlyError(response, providerName = "Gemini") ?: "Empty Gemini response"
            )
    }

    // ---------- OpenAI / DeepSeek (compatible) ----------
    //
    // POST <endpoint>
    // Authorization: Bearer <KEY>
    // Body: {"model":"...","messages":[{"role":"user","content":"<prompt>"}],"temperature":0.4}
    // Response path: choices[0].message.content

    private fun callOpenAiCompatible(
        endpoint: String,
        model: String,
        apiKey: String,
        prompt: String
    ): AiSummaryResult {
        if (!isAllowedUrl(endpoint)) {
            return AiSummaryResult.Failure("Endpoint not allowed")
        }
        val body = buildJsonObject {
            put("model", model)
            put("temperature", JsonPrimitive(0.4))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }.toString()

        val response = postJson(
            url = endpoint,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = body
        )

        val providerName = if (endpoint.contains("deepseek")) "DeepSeek" else "OpenAI"
        rateLimitFailure(response, providerName = providerName)?.let { return it }

        return parseFirstString(response.body, listOf("choices", "0", "message", "content"))
            ?.let { AiSummaryResult.Success(it.trim()) }
            ?: AiSummaryResult.Failure(
                friendlyError(response, providerName = providerName) ?: "Empty AI response"
            )
    }

    /**
     * Returns a Failure with a user-friendly message when the response is
     * a rate-limit (HTTP 429) or quota-exhaustion. Lets callers short-circuit
     * before attempting to parse a success-shaped body that won't be there.
     */
    private fun rateLimitFailure(response: HttpResponse, providerName: String): AiSummaryResult? {
        // Text heuristics only apply to ERROR responses — a perfectly valid
        // 200 whose generated summary happens to mention "rate limit" must
        // not be reported as a failure.
        val isError = response.statusCode !in 200..299
        val isRateLimited = response.statusCode == 429 ||
            (isError && (
                response.body.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                response.body.contains("rate limit", ignoreCase = true)
            ))
        if (!isRateLimited) return null
        val providerHint = extractErrorMessage(response.body)?.let { " ($it)" } ?: ""
        return AiSummaryResult.Failure(
            "$providerName rate limit reached$providerHint. Wait a moment or check your quota."
        )
    }

    private fun friendlyError(response: HttpResponse, providerName: String): String? {
        val embedded = extractErrorMessage(response.body)
        return when {
            embedded != null -> "$providerName: $embedded"
            response.statusCode in 400..499 ->
                "$providerName rejected the request (HTTP ${response.statusCode}). Check the API key."
            response.statusCode in 500..599 ->
                "$providerName is unavailable (HTTP ${response.statusCode}). Try again later."
            else -> null
        }
    }

    // ---------- HTTP helpers ----------

    /**
     * Status code + raw body. Callers need both: a 429 body still contains
     * a meaningful `error.message` we want to surface, but the status code
     * is what tells us "this is a rate limit, don't bother parsing for the
     * success shape".
     */
    internal data class HttpResponse(val statusCode: Int, val body: String)

    /**
     * Throws on transport-level failures (timeout, DNS, oversized response)
     * instead of swallowing them — [generateSummary]'s catch turns the
     * exception into a Failure whose message names the actual cause, rather
     * than the old blanket "No response from <provider>".
     */
    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String
    ): HttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "YomitanMobile/1.0")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val bodyText = stream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IllegalStateException("AI response exceeded ${MAX_RESPONSE_BYTES / 1024} KB")
                    }
                    out.write(buf, 0, read)
                }
                out.toString(StandardCharsets.UTF_8.name())
            }
            HttpResponse(statusCode, bodyText)
        } finally {
            connection?.disconnect()
        }
    }

    private fun isAllowedUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            val host = parsed.host.lowercase(Locale.ROOT)
            parsed.protocol.equals("https", ignoreCase = true) && host in ALLOWED_HOSTS
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Walks a JSON document along [path], where each segment is either a
     * field name on a JsonObject or a numeric index on a JsonArray, and
     * returns the leaf as a string if it lands on a JsonPrimitive.
     */
    internal fun parseFirstString(payload: String, path: List<String>): String? {
        return try {
            var node: kotlinx.serialization.json.JsonElement = json.parseToJsonElement(payload)
            for (step in path) {
                node = when (node) {
                    is JsonObject -> node[step] ?: return null
                    is JsonArray -> {
                        val idx = step.toIntOrNull() ?: return null
                        node.getOrNull(idx) ?: return null
                    }
                    else -> return null
                }
            }
            (node as? JsonPrimitive)?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Most providers wrap errors as `{"error": {"message": "..."}}`. Pull
     * that out so the user gets a useful snackbar instead of "Empty
     * response".
     */
    internal fun extractErrorMessage(payload: String): String? {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val error = root["error"]?.jsonObject ?: return null
            error["message"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }
}
