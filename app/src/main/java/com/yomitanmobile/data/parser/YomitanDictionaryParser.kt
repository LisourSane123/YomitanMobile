package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.local.dao.JlptUpdate
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.util.JlptLevelUtil
import com.yomitanmobile.domain.model.ExamplePair
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
import kotlinx.serialization.json.contentOrNull
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
        // 256 KB buffer halves the number of ZIP read syscalls per term bank
        // file and noticeably speeds up the read side of the import without
        // a meaningful memory cost (the buffer is reused across entries).
        const val BUFFER_SIZE = 256 * 1024
        const val MAX_INDEX_JSON_BYTES = 1 * 1024 * 1024
        const val MAX_TERM_BANK_BYTES = 25 * 1024 * 1024
        const val MAX_KANJI_BANK_BYTES = 20 * 1024 * 1024
        const val MAX_META_BANK_BYTES = 25 * 1024 * 1024
        // Jitendex's structured-content JSON expands ~6-8x from its ~38 MB
        // ZIP — about 250-300 MB uncompressed. 1 GB is a safe upper bound
        // that still rejects malicious zip-bombs without rejecting any
        // dictionary the app actually ships in its download list.
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1L * 1024L * 1024L * 1024L
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
        onKanjiBatch: suspend (List<KanjiEntry>, String) -> Unit = { _, _ -> },
        onJlptBatch: suspend (List<JlptUpdate>) -> Unit = { }
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
                                val KANJI_CHUNK_SIZE = 5000
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

                                val META_CHUNK_SIZE = 5000
                                var freqChunk = mutableListOf<FrequencyUpdate>()
                                var pitchChunk = mutableMapOf<String, String>()
                                var jlptChunk = mutableListOf<JlptUpdate>()
                                var processedInFile = 0

                                for (metaElement in metaArray) {
                                    try {
                                        val meta = metaElement.jsonArray
                                        if (meta.size < 3) continue
                                        val expr = meta[0].jsonPrimitive.content
                                        val type = meta[1].jsonPrimitive.content

                                        when (type) {
                                            "freq" -> {
                                                // The yomitan-jlpt-vocab dictionary
                                                // smuggles JLPT levels through the
                                                // "freq" channel: value=-1, displayValue="N1".
                                                // Detect that first; if the displayValue
                                                // doesn't look like a JLPT level, fall
                                                // through to plain frequency handling.
                                                val jlptLevel = parseJlptLevelFromMeta(meta[2])
                                                if (jlptLevel > 0) {
                                                    val reading = parseFrequencyReading(meta[2])
                                                    jlptChunk.add(JlptUpdate(expr, reading, jlptLevel))
                                                } else {
                                                    val freq = parseFrequencyValue(meta[2])
                                                    if (freq > 0) {
                                                        val reading = parseFrequencyReading(meta[2])
                                                        freqChunk.add(FrequencyUpdate(expr, reading, freq))
                                                    }
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
                                    if (freqChunk.size + pitchChunk.size + jlptChunk.size >= META_CHUNK_SIZE) {
                                        totalFreqUpdates += freqChunk.size
                                        totalPitchUpdates += pitchChunk.size
                                        if (jlptChunk.isNotEmpty()) {
                                            onJlptBatch(jlptChunk)
                                            jlptChunk = mutableListOf()
                                        }
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
                                if (jlptChunk.isNotEmpty()) {
                                    onJlptBatch(jlptChunk)
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

    /**
     * Detects JLPT levels piggybacked on Yomitan's `freq` meta entries.
     *
     * The community `yomitan-jlpt-vocab` dictionary stores its data as
     * `["相", "freq", {"reading": "あい", "frequency": {"value": -1, "displayValue": "N1"}}]`
     * — value -1 (so it doesn't pollute frequency tables) plus a displayValue
     * of "N1"-"N5". Returns 0 when no JLPT level is found.
     */
    private fun parseJlptLevelFromMeta(element: JsonElement): Int {
        return try {
            if (element !is JsonObject) return 0
            val freqField = element["frequency"] as? JsonObject ?: return 0
            val displayValue = freqField["displayValue"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return 0
            jlptLabelToLevel(displayValue)
        } catch (_: Exception) {
            0
        }
    }

    private fun jlptLabelToLevel(label: String): Int {
        // Accept "N1"…"N5" (any case) or just "1"…"5".
        val cleaned = label.trim().lowercase().removePrefix("n").trim()
        val n = cleaned.toIntOrNull() ?: return 0
        return if (n in 1..5) n else 0
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
        val sequenceNumber = if (term.size > 6) {
            try { term[6].jsonPrimitive.intOrNull ?: 0 } catch (_: Exception) { 0 }
        } else 0
        val termTags = if (term.size > 7) safeString(term[7]) else ""

        if (expression.isBlank() && reading.isBlank()) return null

        // term[3] = rules (conjugation paradigm tags: v1, v5u, adj-i…) — stored as part
        // of partsOfSpeech below so it is available for display but not used by the
        // built-in deconjugator (which applies its own rule tables).
        val rules = if (term.size > 3) safeString(term[3]) else ""

        // Try Jitendex's sense-groups layout first. When detected, each <li
        // data-content="sense"> becomes one definition; POS codes are pulled from
        // <span data-content="part-of-speech-info"> so they don't leak into the
        // gloss text; example sentences carry definitionIndex back to their sense.
        val jitendex = tryParseJitendexStructure(term[5])
        val definitions: List<String>
        val examples: List<ExamplePair>
        val posFromContent: List<String>
        if (jitendex != null) {
            definitions = jitendex.definitions
            examples = jitendex.examples
            posFromContent = jitendex.posCodes
        } else {
            // Plain JMDict / generic Yomitan dictionary — flat string definitions
            // plus a defensive walk for any "example"-marked containers.
            definitions = parseDefinitions(term[5])
            examples = extractExamples(term[5])
            posFromContent = emptyList()
        }

        // Tag-source priority:
        //   • Jitendex (posFromContent non-empty): rules + structured-content POS
        //     codes. definitionTags here is a visual badge ("★ priority form"),
        //     not real grammar info, so we drop it to keep the POS chip clean.
        //   • Plain JMDict / others: definitionTags is the canonical POS list.
        val combinedTags = if (posFromContent.isNotEmpty()) {
            listOfNotNull(
                rules.takeIf { it.isNotBlank() },
                posFromContent.joinToString(" ")
            ).joinToString(", ")
        } else {
            listOfNotNull(
                definitionTags.takeIf { it.isNotBlank() },
                rules.takeIf { it.isNotBlank() },
                termTags.takeIf { it.isNotBlank() }
            ).joinToString(", ")
        }

        // Extract JLPT once at import time so the UI can use it directly.
        val jlptLevel = JlptLevelUtil.extractAsInt("$definitionTags $termTags")

        val encodedDefinitions = json.encodeToString(
            ListSerializer(String.serializer()),
            definitions
        )

        // Encode the full example list as JSON, then mirror the first pair into
        // the legacy single-example columns for screens/Anki paths that haven't
        // been updated to consume the list yet.
        val encodedExamples = if (examples.isEmpty()) {
            ""
        } else {
            json.encodeToString(ListSerializer(ExamplePair.serializer()), examples)
        }
        val firstExample = examples.firstOrNull()

        return DictionaryEntry(
            expression = expression,
            reading = reading.ifBlank { expression },
            definition = encodedDefinitions,
            frequency = 0, // Actual frequency comes from frequency dictionaries via term_meta_bank
            pitchAccent = "",
            partsOfSpeech = combinedTags,
            dictionaryName = dictionaryName,
            sequenceNumber = sequenceNumber,
            exampleSentence = firstExample?.jp.orEmpty(),
            exampleSentenceTranslation = firstExample?.en.orEmpty(),
            jlptLevel = jlptLevel,
            examplesJson = encodedExamples
        )
    }

    // ---------- Jitendex sense-aware parser ----------
    //
    // Jitendex's structured-content layout:
    //
    //   structured-content
    //     └── ul data-content="sense-groups"
    //           └── li data-content="sense-group"
    //                 ├── span data-content="part-of-speech-info" (one or more)
    //                 │     data: { code: "v1" }, content: "1-dan"
    //                 └── ol
    //                       └── li data-content="sense"  ← each is a separate definition
    //                             ├── ul data-content="glossary"
    //                             │     └── li content: "to eat"
    //                             └── div data-content="extra-info"
    //                                   └── div data-content="example-sentence"
    //                                         ├── div data-content="example-sentence-a" (JP)
    //                                         └── div data-content="example-sentence-b" (EN)
    //
    // We walk this structure, emitting one definition per sense, and tagging
    // each example with its sense index. POS codes are pulled from the
    // "part-of-speech-info" span and skipped from the gloss flat-text.

    private data class JitendexParseResult(
        val definitions: List<String>,
        val examples: List<ExamplePair>,
        val posCodes: List<String>
    )

    private fun tryParseJitendexStructure(definitionsElement: JsonElement): JitendexParseResult? {
        val arr = definitionsElement as? JsonArray ?: return null

        val definitions = mutableListOf<String>()
        val examples = mutableListOf<ExamplePair>()
        val posCodes = LinkedHashSet<String>()
        var matched = false

        try {
            for (defElement in arr) {
                val sc = defElement as? JsonObject ?: continue
                if (sc["type"]?.jsonPrimitive?.contentOrNull != "structured-content") continue
                val topContent = sc["content"] ?: continue
                val topItems = elementAsList(topContent)

                for (item in topItems) {
                    val node = item as? JsonObject ?: continue
                    val dc = nodeDataContent(node)
                    if (dc != "sense-groups") continue

                    matched = true
                    val groups = elementAsList(node["content"]).filterIsInstance<JsonObject>()
                    for (group in groups) {
                        val groupContent = elementAsList(group["content"])
                        for (gItem in groupContent) {
                            val gObj = gItem as? JsonObject ?: continue
                            val gDc = nodeDataContent(gObj)
                            when {
                                gDc == "part-of-speech-info" -> {
                                    val code = gObj["data"]?.jsonObject?.get("code")
                                        ?.jsonPrimitive?.contentOrNull?.trim()
                                    if (!code.isNullOrBlank()) posCodes.add(code)
                                }
                                gObj["tag"]?.jsonPrimitive?.contentOrNull == "ol" -> {
                                    val senses = elementAsList(gObj["content"])
                                        .filterIsInstance<JsonObject>()
                                    for (sense in senses) {
                                        val idx = definitions.size
                                        val text = extractSenseGloss(sense)
                                        if (text.isNotBlank()) definitions.add(text)
                                        // Even if text is blank, examples still attach to
                                        // the most recent definition index (idx-1) — but
                                        // skip if no definitions exist yet.
                                        val attachIdx = if (text.isNotBlank()) idx else idx - 1
                                        if (attachIdx >= 0) {
                                            collectSenseExamples(sense, attachIdx, examples)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Defensive: any parse failure falls through to the legacy flat path.
            return null
        }

        return if (matched && definitions.isNotEmpty()) {
            JitendexParseResult(definitions, examples, posCodes.toList())
        } else null
    }

    private fun extractSenseGloss(sense: JsonObject): String {
        val items = elementAsList(sense["content"])
        val glosses = mutableListOf<String>()
        for (item in items) {
            val obj = item as? JsonObject ?: continue
            val dc = nodeDataContent(obj)
            // Only the <ul data-content="glossary"> children are real gloss text.
            // Everything else (extra-info wrappers, cross-references, attribution)
            // is rendered separately and must not pollute the meaning column.
            if (dc != "glossary") continue
            val children = elementAsList(obj["content"]).filterIsInstance<JsonObject>()
            for (li in children) {
                val text = extractTextFromContent(li["content"] ?: continue).trim()
                if (text.isNotBlank()) glosses.add(text)
            }
        }
        return glosses.joinToString("; ")
    }

    private fun collectSenseExamples(
        sense: JsonObject,
        senseIndex: Int,
        out: MutableList<ExamplePair>
    ) {
        walkForJitendexExamples(sense, senseIndex, out)
    }

    private fun walkForJitendexExamples(
        element: JsonElement,
        senseIndex: Int,
        out: MutableList<ExamplePair>
    ) {
        when (element) {
            is JsonObject -> {
                val dc = nodeDataContent(element)
                if (dc == "example-sentence") {
                    extractJitendexExamplePair(element)?.let {
                        out.add(it.copy(definitionIndex = senseIndex))
                    }
                    return
                }
                element["content"]?.let { walkForJitendexExamples(it, senseIndex, out) }
            }
            is JsonArray -> element.forEach {
                walkForJitendexExamples(it, senseIndex, out)
            }
            else -> Unit
        }
    }

    private fun extractJitendexExamplePair(container: JsonObject): ExamplePair? {
        var jp = ""
        var en = ""
        val items = elementAsList(container["content"])
        for (item in items) {
            val obj = item as? JsonObject ?: continue
            val dc = nodeDataContent(obj)?.lowercase().orEmpty()
            when {
                // Jitendex variants: "example-sentence-a" / "-japanese", "-b" / "-english"
                dc.contains("example-sentence-a") || dc.contains("japanese") -> {
                    jp = extractTextFromContent(obj["content"] ?: continue).trim()
                }
                dc.contains("example-sentence-b") || dc.contains("english") -> {
                    en = extractTextFromContent(obj["content"] ?: continue).trim()
                }
            }
        }
        // Fallback to the lang-attribute splitter if the data-content markers
        // didn't match (older Jitendex variants).
        if (jp.isBlank()) {
            val jpParts = mutableListOf<String>()
            val enParts = mutableListOf<String>()
            walkLangSplit(container, null, jpParts, enParts)
            jp = jpParts.joinToString(" ").trim()
            if (en.isBlank()) en = enParts.joinToString(" ").trim()
        }
        return if (jp.isBlank()) null else ExamplePair(jp, en)
    }

    private fun nodeDataContent(node: JsonObject): String? {
        val data = node["data"] as? JsonObject ?: return null
        return data["content"]?.jsonPrimitive?.contentOrNull
    }

    private fun elementAsList(element: JsonElement?): List<JsonElement> {
        return when (element) {
            null -> emptyList()
            is JsonArray -> element.toList()
            is JsonObject -> listOf(element)
            else -> listOf(element)
        }
    }

    // ---------- Example sentence extraction (Jitendex format) ----------
    //
    // Jitendex embeds Tatoeba example sentences inside a structured-content node
    // whose data.content marker contains "example" (the exact spelling has
    // varied across Jitendex versions: "example", "example-sentence",
    // "example-sentences"). Inside the container, JP and EN are split either by
    // a `lang` attribute on a child div / span, or implicitly by script —
    // kana/kanji = JP, ASCII Latin = EN.
    //
    // The walker is deliberately defensive: missing data, missing lang
    // attributes, plain string children, deeply nested ul/li layouts all need
    // to resolve to the same (jp, en) pair.

    private fun extractExamples(definitionsElement: JsonElement): List<ExamplePair> {
        val out = mutableListOf<ExamplePair>()
        try {
            walkForExamples(definitionsElement, out)
        } catch (_: Exception) {
            // Malformed structured-content shouldn't fail the whole import.
        }
        return out
    }

    private fun walkForExamples(element: JsonElement, out: MutableList<ExamplePair>) {
        when (element) {
            is JsonObject -> {
                if (isExampleContainer(element)) {
                    extractJpEnPair(element)?.let { out.add(it) }
                    // Don't descend further into a matched container — the
                    // pair is already extracted from this subtree.
                    return
                }
                element["content"]?.let { walkForExamples(it, out) }
            }
            is JsonArray -> element.forEach { walkForExamples(it, out) }
            else -> Unit
        }
    }

    private fun isExampleContainer(obj: JsonObject): Boolean {
        return try {
            val data = obj["data"] as? JsonObject ?: return false
            val content = data["content"]?.jsonPrimitive?.contentOrNull ?: return false
            // Match only the example-sentence wrapper (legacy "example",
            // current "example-sentence", plural "example-sentences"). The
            // looser substring check would also swallow "example-keyword" —
            // a span that highlights the target word INSIDE the JP sentence —
            // and dropping it would erase the head verb from the displayed
            // example.
            val lower = content.lowercase().trim()
            lower == "example" ||
                lower == "example-sentence" ||
                lower == "example-sentences" ||
                lower == "example-sentence-list"
        } catch (_: Exception) {
            false
        }
    }

    private fun extractJpEnPair(container: JsonObject): ExamplePair? {
        val jpParts = mutableListOf<String>()
        val enParts = mutableListOf<String>()
        try {
            walkLangSplit(container, inheritedLang = null, jpParts, enParts)
        } catch (_: Exception) {
            return null
        }
        // Japanese has no inter-word spacing — joining with " " would insert
        // visible gaps between every ruby/span fragment. EN keeps " " because
        // multi-span EN sentences (rare) still need word boundaries.
        val jp = jpParts.joinToString("").trim()
        val en = enParts.joinToString(" ").trim()
        return if (jp.isBlank()) null else ExamplePair(jp, en)
    }

    private fun walkLangSplit(
        element: JsonElement,
        inheritedLang: String?,
        jp: MutableList<String>,
        en: MutableList<String>
    ) {
        when (element) {
            is JsonObject -> {
                val tag = element["tag"]?.jsonPrimitive?.contentOrNull
                // <rt>/<rp> hold furigana readings. If we descend into them, the
                // kana reading gets concatenated with its kanji ("食た" instead of
                // "食"), making the displayed sentence unreadable.
                if (tag == "rt" || tag == "rp") return
                val dc = nodeDataContent(element)
                // Footnote markers like "[1]" attach below the EN line in the
                // source format but visually belong to the attribution section,
                // not the sentence text.
                if (dc == "attribution-footnote") return

                val lang = element["lang"]?.jsonPrimitive?.contentOrNull ?: inheritedLang
                val text = element["text"]?.jsonPrimitive?.contentOrNull
                if (text != null) appendTextByLang(text, lang, jp, en)
                element["content"]?.let { walkLangSplit(it, lang, jp, en) }
            }
            is JsonArray -> element.forEach { walkLangSplit(it, inheritedLang, jp, en) }
            is JsonPrimitive -> {
                val s = element.contentOrNull ?: return
                appendTextByLang(s, inheritedLang, jp, en)
            }
            else -> Unit
        }
    }

    private fun appendTextByLang(
        text: String,
        lang: String?,
        jp: MutableList<String>,
        en: MutableList<String>
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val langPrefix = lang?.lowercase()?.take(2)
        when (langPrefix) {
            "ja", "jp" -> jp.add(trimmed)
            "en" -> en.add(trimmed)
            // No lang attribute: split by script. This catches the case where
            // Jitendex stores example children as plain strings inside an
            // unmarked <div>.
            else -> if (containsJapaneseScript(trimmed)) jp.add(trimmed) else en.add(trimmed)
        }
    }

    private fun containsJapaneseScript(s: String): Boolean {
        return s.any { c ->
            c.code in 0x3040..0x309F ||  // hiragana
            c.code in 0x30A0..0x30FF ||  // katakana
            c.code in 0x4E00..0x9FFF     // CJK ideographs
        }
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
                        parts.joinToString("")
                    }
                }
                is JsonObject -> {
                    // Example containers are extracted separately via extractExamples();
                    // skip them here so the flat-text definition doesn't duplicate the
                    // sentence text inside the meaning column.
                    if (isExampleContainer(element)) return ""
                    val tag = element["tag"]?.jsonPrimitive?.contentOrNull
                    // Furigana readings live inside <ruby><rt>…</rt></ruby>. The
                    // base text appears as siblings of <rt> and is what the user
                    // actually reads; <rt> would otherwise concatenate the kana
                    // reading next to its kanji ("食た" instead of "食").
                    if (tag == "rt" || tag == "rp") return ""

                    val dc = nodeDataContent(element)
                    // Jitendex marks part-of-speech labels, attribution footnotes,
                    // and form-list headers with these data-content values. They
                    // belong to other display sections (POS chip / source link)
                    // and must not bleed into the gloss text.
                    if (dc == "part-of-speech-info" ||
                        dc == "attribution-footnote" ||
                        dc == "forms-label" ||
                        dc == "tag" ||
                        dc == "attribution"
                    ) return ""

                    val content = element["content"]
                    val text = element["text"]
                    when {
                        // For inline tags like span/ruby, extract content without extra separator
                        tag in setOf("span", "ruby", "a", "b", "i", "em", "strong") ->
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
