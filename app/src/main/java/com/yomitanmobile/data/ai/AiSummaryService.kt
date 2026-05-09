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
        language: String
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

        try {
            when (provider) {
                AiProvider.GEMINI -> callGemini(apiKey, resolvedPrompt)
                AiProvider.DEEPSEEK -> callOpenAiCompatible(
                    endpoint = "https://api.deepseek.com/chat/completions",
                    model = AiProvider.DEEPSEEK.defaultModel,
                    apiKey = apiKey,
                    prompt = resolvedPrompt
                )
                AiProvider.OPENAI -> callOpenAiCompatible(
                    endpoint = "https://api.openai.com/v1/chat/completions",
                    model = AiProvider.OPENAI.defaultModel,
                    apiKey = apiKey,
                    prompt = resolvedPrompt
                )
            }
        } catch (e: Exception) {
            AiSummaryResult.Failure(e.message ?: "AI request failed")
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
    // POST https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent?key=<KEY>
    // Body: {"contents":[{"parts":[{"text":"<prompt>"}]}]}
    // Response path: candidates[0].content.parts[0].text

    private fun callGemini(apiKey: String, prompt: String): AiSummaryResult {
        val urlString =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
            AiProvider.GEMINI.defaultModel +
            ":generateContent?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8")
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

        val raw = postJson(urlString, headers = emptyMap(), body = body)
            ?: return AiSummaryResult.Failure("No response from Gemini")

        return parseFirstString(raw, listOf("candidates", "0", "content", "parts", "0", "text"))
            ?.let { AiSummaryResult.Success(it.trim()) }
            ?: AiSummaryResult.Failure(extractErrorMessage(raw) ?: "Empty Gemini response")
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

        val raw = postJson(
            url = endpoint,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = body
        ) ?: return AiSummaryResult.Failure("No response from $endpoint")

        return parseFirstString(raw, listOf("choices", "0", "message", "content"))
            ?.let { AiSummaryResult.Success(it.trim()) }
            ?: AiSummaryResult.Failure(extractErrorMessage(raw) ?: "Empty AI response")
    }

    // ---------- HTTP helpers ----------

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String
    ): String? {
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

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            stream.use { input ->
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
        } catch (_: Exception) {
            null
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
