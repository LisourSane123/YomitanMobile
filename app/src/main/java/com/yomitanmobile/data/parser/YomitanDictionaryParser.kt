package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.KanjiEntry

import com.yomitanmobile.domain.model.ImportProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ParseResult(
    val dictionaryName: String,
    val version: String,
    val revision: String,
    val entriesCount: Int,
    val isMetaDictionary: Boolean = false,
    val metaFrequencyCount: Int = 0,
    val metaPitchCount: Int = 0
)


/**
 * Parses Yomitan/Yomichan dictionary ZIP files in a streaming fashion.
 *
 * Format: ZIP containing index.json + term_bank_X.json files.
 * Each term entry: [expression, reading, definitionTags, rules, score, definitions, sequenceNumber, termTags]
 */
@Singleton
class YomitanDictionaryParser @Inject constructor() {

    private companion object {
        const val TEMP_DICTIONARY_NAME = "temp"
        const val BUFFER_SIZE = 8192
        const val MAX_INDEX_JSON_BYTES = 1 * 1024 * 1024
        const val MAX_TERM_BANK_BYTES = 25 * 1024 * 1024
        const val MAX_KANJI_BANK_BYTES = 20 * 1024 * 1024
        const val MAX_META_BANK_BYTES = 25 * 1024 * 1024
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 200L * 1024L * 1024L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Streaming parser — processes one term_bank file at a time and emits batches
     * via [onBatch] callback so they can be inserted into DB immediately.
     * Also processes term_meta_bank files for frequency and pitch accent data
     * via [onMetaBatch] callback.
     * This avoids holding all entries in memory at once (prevents OOM).
     */
    suspend fun parseFromZipStreaming(
        inputStream: InputStream,
        onProgress: (ImportProgress) -> Unit = {},
        onBatch: suspend (List<DictionaryEntry>, String) -> Unit,
        onMetaBatch: suspend (List<FrequencyUpdate>, Map<String, String>) -> Unit = { _, _ -> },
        onKanjiBatch: suspend (List<KanjiEntry>, String) -> Unit = { _, _ -> }
    ): ParseResult = withContext(Dispatchers.IO) {

        var indexJson: String? = null
        var totalEntries = 0
        var totalFreqUpdates = 0
        var totalPitchUpdates = 0
        var filesProcessed = 0
        var hasTermBanks = false
        var hasMetaBanks = false
        var hasKanjiBanks = false
        var totalUncompressedBytes = 0L

        ZipInputStream(inputStream).use { zip ->
            fun readEntryTextLimited(entryName: String, maxBytes: Int): String {
                val out = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesReadTotal = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read == -1) break
                    bytesReadTotal += read
                    totalUncompressedBytes += read

                    if (bytesReadTotal > maxBytes) {
                        throw Exception("Plik $entryName przekracza limit ${maxBytes / (1024 * 1024)} MB")
                    }
                    if (totalUncompressedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw Exception("Archiwum przekracza limit ${MAX_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)} MB")
                    }
                    out.write(buffer, 0, read)
                }
                return out.toString(Charsets.UTF_8.name())
            }

            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                // Skip directories
                if (!entry.isDirectory) {
                    when {
                        name.endsWith("index.json") -> {
                            indexJson = readEntryTextLimited(name, MAX_INDEX_JSON_BYTES)
                        }
                        name.contains("term_bank_") && name.endsWith(".json") -> {
                            hasTermBanks = true
                            // Parse this term bank file immediately and emit batch
                            try {
                                val content = readEntryTextLimited(name, MAX_TERM_BANK_BYTES)
                                val termArray = json.decodeFromString<JsonArray>(content)

                                val dictionaryName = TEMP_DICTIONARY_NAME
                                val batch = mutableListOf<DictionaryEntry>()
                                for (termElement in termArray) {
                                    try {
                                        val term = termElement.jsonArray
                                        val parsed = parseTermEntry(term, dictionaryName)
                                        if (parsed != null) {
                                            batch.add(parsed)
                                        }
                                    } catch (_: Exception) {
                                        // Skip malformed term entry
                                    }
                                }

                                totalEntries += batch.size
                                filesProcessed++

                                onProgress(
                                    ImportProgress(
                                        currentFile = name,
                                        filesProcessed = filesProcessed,
                                        totalFiles = filesProcessed,
                                        entriesProcessed = totalEntries,
                                        totalEntries = totalEntries
                                    )
                                )

                                if (batch.isNotEmpty()) {
                                    onBatch(batch, name)
                                }
                            } catch (e: Exception) {
                                throw Exception("Błąd przetwarzania $name: ${e.message}", e)
                            }
                        }
                        name.contains("kanji_bank_") && name.endsWith(".json") -> {
                            hasKanjiBanks = true
                            try {
                                val content = readEntryTextLimited(name, MAX_KANJI_BANK_BYTES)
                                val jsonArray = json.decodeFromString<JsonArray>(content)
                                val KANJI_CHUNK_SIZE = 2000
                                val batch = mutableListOf<KanjiEntry>()

                                for (item in jsonArray) {
                                    try {
                                        val kanjiArr = item.jsonArray
                                        if (kanjiArr.size < 5) continue
                                        
                                        val character = safeString(kanjiArr[0])
                                        val onyomi = safeString(kanjiArr[1])
                                        val kunyomi = safeString(kanjiArr[2])
                                        val meaningsArr = parseDefinitions(kanjiArr[4])
                                        
                                        val encodedMeanings = json.encodeToString(
                                            ListSerializer(String.serializer()),
                                            meaningsArr
                                        )

                                        if (character.isNotBlank()) {
                                            batch.add(
                                                KanjiEntry(
                                                    kanji = character,
                                                    onyomi = onyomi,
                                                    kunyomi = kunyomi,
                                                    meanings = encodedMeanings,
                                                    dictionaryName = TEMP_DICTIONARY_NAME
                                                )
                                            )
                                        }

                                        if (batch.size >= KANJI_CHUNK_SIZE) {
                                            onKanjiBatch(batch.toList(), name)
                                            batch.clear()
                                        }
                                    } catch (_: Exception) {}
                                }
                                if (batch.isNotEmpty()) {
                                    onKanjiBatch(batch, name)
                                }
                                filesProcessed++
                            } catch (e: Exception) {
                                throw Exception("Błąd przetwarzania $name: ${e.message}", e)
                            }
                        }
                        name.contains("term_meta_bank_") && name.endsWith(".json") -> {
                            hasMetaBanks = true
                            try {
                                val content = readEntryTextLimited(name, MAX_META_BANK_BYTES)
                                val metaArray = json.decodeFromString<JsonArray>(content)
                                val totalMetaEntries = metaArray.size

                                val META_CHUNK_SIZE = 2000
                                var freqChunk = mutableListOf<FrequencyUpdate>()
                                var pitchChunk = mutableMapOf<String, String>()
                                var processedInFile = 0

                                for (metaElement in metaArray) {
                                    try {
                                        val meta = metaElement.jsonArray
                                        if (meta.size < 3) continue
                                        val expr = meta[0].jsonPrimitive.content
                                        val type = meta[1].jsonPrimitive.content

                                        when (type) {
                                            "freq" -> {
                                                val freq = parseFrequencyValue(meta[2])
                                                if (freq > 0) {
                                                    val reading = parseFrequencyReading(meta[2])
                                                    freqChunk.add(FrequencyUpdate(expr, reading, freq))
                                                }
                                            }
                                            "pitch" -> {
                                                val pitchStr = parsePitchValue(meta[2])
                                                if (pitchStr.isNotBlank()) pitchChunk[expr] = pitchStr
                                            }
                                        }
                                    } catch (_: Exception) {
                                        // Skip malformed meta entry
                                    }

                                    processedInFile++

                                    // Emit in chunks to avoid accumulating huge maps
                                    if (freqChunk.size + pitchChunk.size >= META_CHUNK_SIZE) {
                                        totalFreqUpdates += freqChunk.size
                                        totalPitchUpdates += pitchChunk.size
                                        onMetaBatch(freqChunk, pitchChunk)
                                        freqChunk = mutableListOf()
                                        pitchChunk = mutableMapOf()

                                        // Report progress during meta processing
                                        onProgress(
                                            ImportProgress(
                                                currentFile = name,
                                                filesProcessed = filesProcessed,
                                                totalFiles = filesProcessed + 1,
                                                entriesProcessed = processedInFile,
                                                totalEntries = totalMetaEntries
                                            )
                                        )
                                    }
                                }

                                // Emit remaining entries
                                if (freqChunk.isNotEmpty() || pitchChunk.isNotEmpty()) {
                                    totalFreqUpdates += freqChunk.size
                                    totalPitchUpdates += pitchChunk.size
                                    onMetaBatch(freqChunk, pitchChunk)
                                }
                                filesProcessed++

                                onProgress(
                                    ImportProgress(
                                        currentFile = name,
                                        filesProcessed = filesProcessed,
                                        totalFiles = filesProcessed,
                                        entriesProcessed = totalFreqUpdates + totalPitchUpdates,
                                        totalEntries = totalFreqUpdates + totalPitchUpdates
                                    )
                                )
                            } catch (e: OutOfMemoryError) {
                                // Large meta files can cause OOM — report gracefully
                                System.gc()
                                throw Exception("Za mało pamięci do przetworzenia pliku $name (${e.message})")
                            } catch (e: Exception) {
                                throw Exception("Błąd przetwarzania $name: ${e.message}")
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (!hasTermBanks && !hasMetaBanks && !hasKanjiBanks) {
            throw Exception("Archiwum nie zawiera obsługiwanych plików słownika")
        }

        val indexData = indexJson?.let {
            try {
                json.decodeFromString<JsonObject>(it)
            } catch (_: Exception) {
                null
            }
        }
        val dictionaryName = indexData?.get("title")?.jsonPrimitive?.content ?: "Unknown Dictionary"
        val version = indexData?.get("format")?.jsonPrimitive?.content
            ?: indexData?.get("version")?.jsonPrimitive?.content ?: "3"
        val revision = indexData?.get("revision")?.jsonPrimitive?.content ?: ""

        ParseResult(
            dictionaryName = dictionaryName,
            version = version,
            revision = revision,
            entriesCount = totalEntries,
            isMetaDictionary = hasMetaBanks && !hasTermBanks,
            metaFrequencyCount = totalFreqUpdates,
            metaPitchCount = totalPitchUpdates
        )
    }

    private fun parseFrequencyValue(element: JsonElement): Int {
        return try {
            when (element) {
                is JsonPrimitive -> element.intOrNull ?: element.content.filter { it.isDigit() }.toIntOrNull() ?: 0
                is JsonObject -> {
                    // Handle various Yomitan frequency formats:
                    // {"value": N} or {"frequency": N} or {"frequency": {"value": N}}
                    // {"reading": "...", "frequency": N} or {"reading": "...", "frequency": {"value": N}}
                    element["value"]?.jsonPrimitive?.intOrNull
                        ?: element["frequency"]?.let { freqObj ->
                            when (freqObj) {
                                is JsonPrimitive -> freqObj.intOrNull ?: freqObj.content.filter { it.isDigit() }.toIntOrNull()
                                is JsonObject -> freqObj["value"]?.jsonPrimitive?.intOrNull
                                    ?: freqObj["displayValue"]?.jsonPrimitive?.content?.filter { it.isDigit() }?.toIntOrNull()
                                else -> null
                            }
                        }
                        ?: element["displayValue"]?.jsonPrimitive?.content?.filter { it.isDigit() }?.toIntOrNull()
                        ?: 0
                }
                else -> 0
            }
        } catch (e: Exception) { 0 }
    }

    private fun parseFrequencyReading(element: JsonElement): String? {
        return try {
            if (element !is JsonObject) return null
            val reading = element["reading"]?.jsonPrimitive?.content?.trim()
            reading?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePitchValue(element: JsonElement): String {
        return try {
            if (element !is JsonObject) return ""
            val pitches = element["pitches"]?.jsonArray ?: return ""
            val positions = pitches.mapNotNull { pitchObj ->
                pitchObj.jsonObject["position"]?.jsonPrimitive?.intOrNull
            }
            if (positions.isNotEmpty()) positions.joinToString(",") else ""
        } catch (e: Exception) { "" }
    }

    private fun safeString(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.joinToString(", ") { safeString(it) }
        is JsonObject -> element.toString()
        else -> ""
    }

    private fun parseTermEntry(term: JsonArray, dictionaryName: String): DictionaryEntry? {
        if (term.size < 6) return null

        val expression = safeString(term[0])
        val reading = safeString(term[1])
        val definitionTags = safeString(term[2])
        // term[4] is the dictionary sort score, NOT frequency rank
        val definitions = parseDefinitions(term[5])
        val sequenceNumber = if (term.size > 6) {
            try { term[6].jsonPrimitive.intOrNull ?: 0 } catch (_: Exception) { 0 }
        } else 0
        val termTags = if (term.size > 7) safeString(term[7]) else ""

        if (expression.isBlank() && reading.isBlank()) return null

        val encodedDefinitions = json.encodeToString(
            ListSerializer(String.serializer()),
            definitions
        )

        return DictionaryEntry(
            expression = expression,
            reading = reading.ifBlank { expression },
            definition = encodedDefinitions,
            frequency = 0, // Actual frequency comes from frequency dictionaries via term_meta_bank
            pitchAccent = "",
            partsOfSpeech = listOfNotNull(
                definitionTags.takeIf { it.isNotBlank() },
                termTags.takeIf { it.isNotBlank() }
            ).joinToString(", "),
            dictionaryName = dictionaryName,
            sequenceNumber = sequenceNumber
        )
    }

    private fun parseDefinitions(element: JsonElement): List<String> {
        return when (element) {
            is JsonPrimitive -> listOf(element.content)
            is JsonArray -> {
                element.mapNotNull { item ->
                    try {
                        when (item) {
                            is JsonPrimitive -> item.content
                            is JsonObject -> parseStructuredContent(item)
                            is JsonArray -> {
                                item.mapNotNull { subItem ->
                                    when (subItem) {
                                        is JsonPrimitive -> subItem.content
                                        is JsonObject -> parseStructuredContent(subItem)
                                        else -> null
                                    }
                                }.joinToString("; ")
                            }
                            else -> null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }.filter { it.isNotBlank() }
            }
            is JsonObject -> listOfNotNull(parseStructuredContent(element))
            else -> emptyList()
        }
    }

    private fun parseStructuredContent(obj: JsonObject): String? {
        return try {
            val type = obj["type"]?.jsonPrimitive?.content
            if (type == "structured-content") {
                val content = obj["content"]
                return content?.let { extractTextFromContent(it) }
            }
            if (type == "text") {
                return obj["text"]?.jsonPrimitive?.content
            }
            val text = obj["text"]?.jsonPrimitive?.content
            if (text != null) return text
            val content = obj["content"]
            if (content != null) return extractTextFromContent(content)
            val glossary = obj["glossary"]
            if (glossary != null) return extractTextFromContent(glossary)
            obj.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTextFromContent(element: JsonElement): String {
        return try {
            when (element) {
                is JsonPrimitive -> element.content
                is JsonArray -> {
                    val parts = element.map { extractTextFromContent(it) }.filter { it.isNotBlank() }
                    // Check if children are list items (li tags) — join with "; "
                    val hasListItems = element.any { it is JsonObject && it.jsonObject["tag"]?.jsonPrimitive?.content == "li" }
                    if (hasListItems) {
                        parts.joinToString("; ")
                    } else {
                        parts.joinToString(", ")
                    }
                }
                is JsonObject -> {
                    val tag = element["tag"]?.jsonPrimitive?.content
                    val content = element["content"]
                    val text = element["text"]
                    when {
                        // For inline tags like span/ruby, extract content without extra separator
                        tag in setOf("span", "ruby", "rt", "rp", "a", "b", "i", "em", "strong") ->
                            content?.let { extractTextFromContent(it) } ?: text?.let { safeString(it) } ?: ""
                        content != null -> extractTextFromContent(content)
                        text != null -> safeString(text)
                        else -> ""
                    }
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
