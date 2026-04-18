package com.yomitanmobile.data.sentence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class OnlineSentence(
    val japanese: String,
    val translation: String = ""
)

@Singleton
class OnlineSentenceService @Inject constructor() {

    private companion object {
        const val ENDPOINT = "https://tatoeba.org/en/api_v0/search"
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024
        const val MAX_RESULTS = 5
        const val BUFFER_SIZE = 8192
        const val MAX_REDIRECTS = 3

        private val ALLOWED_HOSTS = setOf(
            "tatoeba.org",
            "www.tatoeba.org"
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchSentenceForWord(word: String): OnlineSentence? = withContext(Dispatchers.IO) {
        val query = word.trim()
        if (query.isBlank()) return@withContext null

        var connection: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            var currentUrl = "$ENDPOINT?from=jpn&to=eng&query=$encoded&per_page=$MAX_RESULTS&page=1"
            if (!isAllowedUrl(currentUrl)) return@withContext null

            var redirects = 0
            while (true) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "YomitanMobile/1.0")
                    setRequestProperty("Accept", "application/json")
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    if (redirects >= MAX_REDIRECTS) return@withContext null
                    val location = connection.getHeaderField("Location") ?: return@withContext null
                    connection.disconnect()
                    currentUrl = resolveRedirectUrl(currentUrl, location)
                    if (!isAllowedUrl(currentUrl)) return@withContext null
                    redirects++
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext null
                }
                break
            }

            val activeConnection = connection ?: return@withContext null
            val payload = activeConnection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IllegalStateException("Sentence API response too large")
                    }
                    out.write(buffer, 0, read)
                }
                out.toString(StandardCharsets.UTF_8.name())
            }

            parseSentenceFromResponse(payload, query)
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

    private fun resolveRedirectUrl(baseUrl: String, location: String): String {
        return if (location.startsWith("http", ignoreCase = true)) {
            location
        } else {
            URL(URL(baseUrl), location).toString()
        }
    }

    internal fun parseSentenceFromResponse(payload: String, query: String): OnlineSentence? {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val results = root["results"]?.jsonArray ?: return null
            if (results.isEmpty()) return null

            var fallback: OnlineSentence? = null

            for (result in results) {
                val obj = result.jsonObject
                val japanese = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (japanese.isBlank()) continue

                val translation = extractTranslation(obj)
                val candidate = OnlineSentence(
                    japanese = japanese,
                    translation = translation
                )

                if (fallback == null) {
                    fallback = candidate
                }

                if (japanese.contains(query)) {
                    return candidate
                }
            }

            fallback
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTranslation(result: JsonObject): String {
        val translations = result["translations"] as? JsonArray ?: return ""
        var fallback = ""

        for (group in translations) {
            val groupArray = group as? JsonArray ?: continue
            for (item in groupArray) {
                val t = item as? JsonObject ?: continue
                val text = t["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isBlank()) continue

                val lang = t["lang"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (lang == "eng" || lang == "en") {
                    return text
                }
                if (fallback.isBlank()) {
                    fallback = text
                }
            }
        }

        return fallback
    }
}
