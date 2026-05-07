package com.yomitanmobile.data.parser

import android.util.Log
import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.domain.model.MeaningBlock
import com.yomitanmobile.domain.model.MeaningExample

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
import com.yomitanmobile.util.InputSanitizer

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
        // Increase total uncompressed bytes limit to 1 GB to accommodate
        // larger community dictionaries like Jitendex.
        // Adjust this if you expect even larger imports on low-memory devices.
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L // 1 GB
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
        val meaningBlocks = parseMeaningBlocks(term[5])
        val definitions = if (meaningBlocks.isNotEmpty()) {
            meaningBlocks.map { it.meaning }
        } else {
            parseDefinitions(term[5])
        }
        val sequenceNumber = if (term.size > 6) {
            try { term[6].jsonPrimitive.intOrNull ?: 0 } catch (_: Exception) { 0 }
        } else 0
        val termTags = if (term.size > 7) safeString(term[7]) else ""

        if (expression.isBlank() && reading.isBlank()) return null

        val encodedDefinitions = if (meaningBlocks.isNotEmpty()) {
            json.encodeToString(ListSerializer(MeaningBlock.serializer()), meaningBlocks)
        } else {
            json.encodeToString(ListSerializer(String.serializer()), definitions)
        }

        val structuredPartsOfSpeech = extractStructuredPartOfSpeechTags(term[5])
        val partsOfSpeech = listOfNotNull(
            definitionTags.takeIf { it.isNotBlank() },
            termTags.takeIf { it.isNotBlank() },
            structuredPartsOfSpeech.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ).joinToString(", ")
        
        // Debug logging (safe for tests and release)
        if (expression.isNotBlank() && (definitionTags.isNotBlank() || termTags.isNotBlank())) {
            try {
                Log.d("YomitanParser", "Parsed: expr='$expression', definitionTags='$definitionTags', termTags='$termTags', partsOfSpeech='$partsOfSpeech'")
            } catch (_: Throwable) {
                // Logging might fail in tests - that's ok
                println("YomitanParser: expr='$expression', partsOfSpeech='$partsOfSpeech'")
            }
        }

        return DictionaryEntry(
            expression = expression,
            reading = reading.ifBlank { expression },
            definition = encodedDefinitions,
            frequency = 0, // Actual frequency comes from frequency dictionaries via term_meta_bank
            pitchAccent = "",
            partsOfSpeech = partsOfSpeech,
            dictionaryName = dictionaryName,
            sequenceNumber = sequenceNumber
        )
    }

    private fun parseMeaningBlocks(element: JsonElement): List<MeaningBlock> {
        val blocks = mutableListOf<MeaningBlock>()

        fun walk(node: JsonElement) {
            when (node) {
                is JsonArray -> node.forEach { walk(it) }
                is JsonObject -> {
                    val type = node["type"]?.jsonPrimitive?.content
                    if (type == "structured-content") {
                        val content = node["content"]
                        if (content != null) {
                            extractMeaningBlocksFromStructuredContent(content, blocks)
                        }
                    }
                }
                else -> Unit
            }
        }

        walk(element)
        return blocks
    }

    private fun extractStructuredPartOfSpeechTags(element: JsonElement): List<String> {
        val tags = mutableListOf<String>()

        fun walk(node: JsonElement) {
            when (node) {
                is JsonArray -> node.forEach { walk(it) }
                is JsonObject -> {
                    val content = node["data"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                    when {
                        content == "part-of-speech-info" -> {
                            val code = node["data"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                                ?: node["content"]?.let { extractPlainText(it) }
                            if (!code.isNullOrBlank() && code !in tags) {
                                tags.add(code)
                            }
                        }
                        node["content"] != null -> walk(node["content"]!!)
                    }
                }
                else -> Unit
            }
        }

        walk(element)
        return tags
    }

    private fun extractMeaningBlocksFromStructuredContent(element: JsonElement, output: MutableList<MeaningBlock>) {
        when (element) {
            is JsonArray -> element.forEach { child ->
                if (child is JsonObject) {
                    val content = child["data"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                    when (content) {
                        "sense-group" -> parseSenseGroup(child)?.let(output::add)
                        "redirect-glossary" -> Unit
                        else -> {
                            val nested = child["content"]
                            if (nested != null) extractMeaningBlocksFromStructuredContent(nested, output)
                        }
                    }
                }
            }
            is JsonObject -> {
                val content = element["data"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                when (content) {
                    "sense-group" -> parseSenseGroup(element)?.let(output::add)
                    else -> element["content"]?.let { extractMeaningBlocksFromStructuredContent(it, output) }
                }
            }
            else -> Unit
        }
    }

    private data class SenseParseResult(
        val meanings: List<String>,
        val examples: List<MeaningExample>
    )

    private fun parseSenseGroup(node: JsonObject): MeaningBlock? {
        val children = node["content"]
        if (children !is JsonArray) return null

        val meanings = mutableListOf<String>()
        val examples = mutableListOf<MeaningExample>()

        children.forEach { child ->
            if (child !is JsonObject) return@forEach

            val childContent = child["data"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            when {
                childContent == "sense" -> {
                    val sense = parseSense(child)
                    meanings.addAll(sense.meanings)
                    examples.addAll(sense.examples)
                }
            }
        }

        val meaningText = meanings.joinToString("; ").trim()
        if (meaningText.isBlank()) return null

        return MeaningBlock(
            meaning = meaningText,
            examples = examples
        )
    }

    private fun parseSense(node: JsonObject): SenseParseResult {
        val children = node["content"]
        val meanings = mutableListOf<String>()
        val examples = mutableListOf<MeaningExample>()

        fun visit(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach { visit(it) }
                is JsonObject -> {
                    val childContent = element["data"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                    when {
                        childContent == "glossary" -> extractGlossaryItems(element).let(meanings::addAll)
                        childContent == "extra-info" -> extractExampleSentences(element).let(examples::addAll)
                        else -> element["content"]?.let { visit(it) }
                    }
                }
                else -> Unit
            }
        }

        if (children != null) visit(children)
        return SenseParseResult(meanings, examples)
    }

    private fun extractGlossaryItems(node: JsonObject): List<String> {
        val content = node["content"]
        return when (content) {
            is JsonArray -> content.mapNotNull { item -> extractPlainText(item).takeIf { it.isNotBlank() } }
            is JsonObject -> listOfNotNull(extractPlainText(content).takeIf { it.isNotBlank() })
            else -> emptyList()
        }
    }

    private fun extractExampleSentences(node: JsonObject): List<MeaningExample> {
        val content = node["content"]
        if (content !is JsonObject) return emptyList()

        val exampleNode = content["content"] as? JsonArray ?: return emptyList()
        var sentenceHtml = ""
        var translation = ""

        exampleNode.forEach { child ->
            if (child !is JsonObject) return@forEach
            when (child["data"]?.jsonObject?.get("content")?.jsonPrimitive?.content) {
                "example-sentence-a" -> sentenceHtml = renderHtml(child["content"])
                "example-sentence-b" -> translation = extractPlainText(child["content"]) 
            }
        }

        val sentenceText = extractPlainText(childContentForPlainText(exampleNode))

        return if (sentenceHtml.isNotBlank() || translation.isNotBlank()) {
            listOf(MeaningExample(sentenceHtml = sentenceHtml, sentenceText = sentenceText, translation = translation))
        } else {
            emptyList()
        }
    }

    private fun childContentForPlainText(exampleNode: JsonArray): JsonElement? {
        exampleNode.forEach { child ->
            if (child is JsonObject && child["data"]?.jsonObject?.get("content")?.jsonPrimitive?.content == "example-sentence-a") {
                return child["content"]
            }
        }
        return null
    }

    private fun renderHtml(element: JsonElement?): String {
        return when (element) {
            null -> ""
            is JsonPrimitive -> InputSanitizer.escapeHtml(element.content)
            is JsonArray -> element.joinToString(separator = "") { renderHtml(it) }
            is JsonObject -> {
                val tag = element["tag"]?.jsonPrimitive?.content
                val content = element["content"]
                when (tag) {
                    "ruby" -> {
                        if (content is JsonArray && content.size >= 2) {
                            val base = renderHtml(content[0])
                            val ruby = renderHtml(content[1])
                            "<ruby>$base<rt>$ruby</rt></ruby>"
                        } else {
                            renderHtml(content)
                        }
                    }
                    "rt" -> renderHtml(content)
                    "span", "div", "a", "b", "i", "em", "strong" -> renderHtml(content)
                    else -> when {
                        content != null -> renderHtml(content)
                        element["text"] != null -> InputSanitizer.escapeHtml(element["text"]!!.jsonPrimitive.content)
                        else -> ""
                    }
                }
            }
            else -> ""
        }
    }

    private fun extractPlainText(element: JsonElement?): String {
        return when (element) {
            null -> ""
            is JsonPrimitive -> element.content
            is JsonArray -> element.joinToString(separator = "") { extractPlainText(it) }
            is JsonObject -> {
                val content = element["content"]
                when {
                    element["text"] != null -> element["text"]!!.jsonPrimitive.content
                    content != null -> extractPlainText(content)
                    else -> ""
                }
            }
            else -> ""
        }
    }

    private fun normalizePartOfSpeech(code: String): String? {
        return when (code.lowercase()) {
            "n" -> "noun"
            "vt" -> "transitive"
            "vi" -> "intransitive"
            "v1" -> "1-dan verb"
            "vs" -> "suru verb"
            "adj-i" -> "i-adjective"
            "adj-na" -> "na-adjective"
            "*" -> null
            else -> code
        }
    }

    private fun parseDefinitions(element: JsonElement): List<String> {
        return when (element) {
            is JsonPrimitive -> splitMeaningLines(element.content)
            is JsonArray -> {
                element.flatMap { item -> parseDefinitionItem(item) }
            }
            is JsonObject -> parseStructuredContent(element)
                ?.let { splitMeaningLines(it) }
                ?: emptyList()
            else -> emptyList()
        }
    }

    private fun parseDefinitionItem(item: JsonElement): List<String> {
        return try {
            when (item) {
                is JsonPrimitive -> splitMeaningLines(item.content)
                is JsonObject -> {
                    parseStructuredContent(item)
                        ?.let { splitMeaningLines(it) }
                        ?: emptyList()
                }
                is JsonArray -> {
                    val flattened = item.mapNotNull { subItem ->
                        when (subItem) {
                            is JsonPrimitive -> subItem.content
                            is JsonObject -> parseStructuredContent(subItem)
                            else -> null
                        }
                    }.filter { it.isNotBlank() }

                    if (flattened.isEmpty()) emptyList() else listOf(flattened.joinToString(", "))
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun splitMeaningLines(text: String): List<String> {
        return text
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
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
                    val inlineTags = setOf("span", "ruby", "rt", "rp", "a", "b", "i", "em", "strong")

                    // List items represent separate meanings.
                    val hasListItems = element.any { it is JsonObject && it.jsonObject["tag"]?.jsonPrimitive?.content == "li" }
                    if (hasListItems) {
                        parts.joinToString("\n")
                    } else {
                        // Inline ruby/span fragments should stay as one sentence (no commas between tokens).
                        val hasInlineNodes = element.any {
                            it is JsonObject && it.jsonObject["tag"]?.jsonPrimitive?.content in inlineTags
                        }
                        val hasOnlyInlineOrPrimitive = element.all {
                            it is JsonPrimitive ||
                                (it is JsonObject && (
                                    it.jsonObject["tag"]?.jsonPrimitive?.content in inlineTags ||
                                        it.jsonObject["type"]?.jsonPrimitive?.content == "text" ||
                                        it.jsonObject["text"] != null
                                    ))
                        }

                        if (hasInlineNodes && hasOnlyInlineOrPrimitive) {
                            parts.joinToString(separator = "")
                        } else {
                            // Non-inline arrays are treated as synonym-like lists.
                            parts.joinToString(", ")
                        }
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
