package com.yomitanmobile.data.bunpro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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

data class BunproProgress(
    val learnedVocabulary: List<String>,
    val learnedKanji: List<String>
)

@Singleton
class BunproProgressService @Inject constructor() {

    companion object {
        const val DEFAULT_ENDPOINT_TEMPLATE = "https://bunpro.jp/api/user/{token}"

        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private const val BUFFER_SIZE = 8192

        private val ALLOWED_HOSTS = setOf(
            "bunpro.jp",
            "www.bunpro.jp"
        )

        private val POSITIVE_PATH_MARKERS = listOf(
            "vocab", "vocabulary", "word", "words", "term", "terms",
            "expression", "japanese", "kanji", "character", "characters", "question"
        )

        private val NEGATIVE_PATH_MARKERS = listOf(
            "url", "image", "avatar", "email", "token", "slug", "audio",
            "translation", "english", "meaning", "timestamp"
        )

        private val japaneseSegmentRegex = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}々〆ヶー]{1,24}")

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

        private fun containsJapanese(text: String): Boolean {
            return text.any {
                when (Character.UnicodeScript.of(it.code)) {
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA -> true
                    else -> false
                }
            }
        }

        private fun isLikelyLearningPath(path: String): Boolean {
            val lowered = path.lowercase(Locale.ROOT)
            if (NEGATIVE_PATH_MARKERS.any { lowered.contains(it) }) return false
            return POSITIVE_PATH_MARKERS.any { lowered.contains(it) }
        }

        private fun extractJapaneseTerms(text: String): List<String> {
            return japaneseSegmentRegex.findAll(text)
                .map { it.value.trim() }
                .filter { it.isNotBlank() }
                .toList()
        }

        private fun collectCandidateTerms(
            element: JsonElement,
            path: String,
            destination: MutableList<String>,
            focusedOnly: Boolean
        ) {
            when (element) {
                is JsonObject -> {
                    element.forEach { (key, value) ->
                        val nextPath = if (path.isBlank()) key else "$path.$key"
                        collectCandidateTerms(value, nextPath, destination, focusedOnly)
                    }
                }
                is JsonArray -> {
                    element.forEach { item ->
                        collectCandidateTerms(item, path, destination, focusedOnly)
                    }
                }
                else -> {
                    val raw = element.jsonPrimitive.contentOrNull?.trim().orEmpty()
                    if (raw.isBlank()) return
                    if (!containsJapanese(raw)) return
                    if (focusedOnly && !isLikelyLearningPath(path)) return
                    destination += extractJapaneseTerms(raw)
                }
            }
        }

        private fun normalizeVocabulary(terms: List<String>): List<String> {
            return terms.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filter { it.length <= 24 }
                .filter { containsJapanese(it) }
                .distinct()
                .sorted()
                .toList()
        }

        private fun extractKanji(terms: List<String>): List<String> {
            val chars = linkedSetOf<Char>()
            for (term in terms) {
                for (char in term) {
                    if (Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN) {
                        chars += char
                    }
                }
            }
            return chars.map { it.toString() }.sorted()
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetchProgress(endpointTemplate: String, apiToken: String): BunproProgress? = withContext(Dispatchers.IO) {
        val token = apiToken.trim()
        if (token.isBlank()) return@withContext null

        val requestUrl = buildRequestUrl(endpointTemplate, token) ?: return@withContext null
        if (!isAllowedUrl(requestUrl)) return@withContext null

        var connection: HttpURLConnection? = null
        try {
            var currentUrl: String = requestUrl
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
                    val location: String = connection.getHeaderField("Location") ?: return@withContext null
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

            val payload = connection?.inputStream?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IllegalStateException("Bunpro API response too large")
                    }
                    out.write(buffer, 0, read)
                }
                out.toString(StandardCharsets.UTF_8.name())
            } ?: return@withContext null

            parseProgressFromResponse(payload)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun buildRequestUrl(endpointTemplate: String, token: String): String? {
        val template = endpointTemplate.trim().ifBlank { DEFAULT_ENDPOINT_TEMPLATE }
        if (template.isBlank()) return null
        val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
        return when {
            template.contains("{token}") -> template.replace("{token}", encodedToken)
            template.endsWith("/") -> template + encodedToken
            else -> "$template/$encodedToken"
        }
    }

    internal fun parseProgressFromResponse(payload: String): BunproProgress? {
        return try {
            val root = json.parseToJsonElement(payload)

            val focusedTerms = mutableListOf<String>()
            collectCandidateTerms(root, path = "", destination = focusedTerms, focusedOnly = true)

            val vocabulary = if (focusedTerms.isNotEmpty()) {
                normalizeVocabulary(focusedTerms)
            } else {
                val fallbackTerms = mutableListOf<String>()
                collectCandidateTerms(root, path = "", destination = fallbackTerms, focusedOnly = false)
                normalizeVocabulary(fallbackTerms)
            }

            val kanji = extractKanji(vocabulary)
            BunproProgress(
                learnedVocabulary = vocabulary,
                learnedKanji = kanji
            )
        } catch (_: Exception) {
            null
        }
    }
}
