package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.local.dao.JlptUpdate
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.util.JlptLevelUtil
import com.yomitanmobile.util.PartsOfSpeechFormatter
import com.yomitanmobile.domain.model.ExamplePair
import com.yomitanmobile.domain.model.FuriganaSegment
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
        const val MAX_TERM_BANK_BYTES = 48 * 1024 * 1024
        const val MAX_KANJI_BANK_BYTES = 20 * 1024 * 1024
        // BCCWJ (SUW+LUW combined, ~1M entries) ships term_meta_bank files
        // that decompress past 25 MB, so the old cap rejected the import with
        // "Plik … przekracza limit 25 MB". 64 MB clears the largest shipped
        // meta bank while staying well under MAX_TOTAL_UNCOMPRESSED_BYTES.
        const val MAX_META_BANK_BYTES = 64 * 1024 * 1024
        // Jitendex's structured-content JSON expands ~6-8x from its ~38 MB
        // ZIP — about 250-300 MB uncompressed. 1 GB is a safe upper bound
        // that still rejects malicious zip-bombs without rejecting any
        // dictionary the app actually ships in its download list.
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 1L * 1024L * 1024L * 1024L

        // HTML-ish tags that break a flow of inline text. When walking a
        // structured-content array, adjacent block children must be
        // separated with "; " so alternative headword forms and sibling
        // senses don't visually fuse onto the gloss text.
        val BLOCK_TAGS = setOf(
            "div", "p", "section", "article",
            "li", "ul", "ol", "dl", "dt", "dd",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "table", "tr", "td", "th",
            "header", "footer", "aside", "main", "nav"
        )

        // Data-content markers that label non-gloss subsections of a Yomitan
        // structured-content tree. The Jitendex sense-aware path already
        // handles "part-of-speech-info" etc., but plain JMdict and many
        // community dictionaries (NHK accent, JMnedict, frequency lists, …)
        // embed headword / form / reading / pitch widgets alongside the
        // gloss. Without filtering, those text fragments get concatenated
        // onto the meaning column.
        val NON_GLOSS_DATA_CONTENT = setOf(
            "part-of-speech-info",
            "attribution-footnote",
            "attribution",
            "forms-label",
            "tag",
            "headword",
            "headword-summary",
            "headword-list",
            "headword-info",
            "headword-section",
            "kanji-headword",
            "kana-headword",
            "form",
            "forms",
            "form-list",
            "form-info",
            "reading",
            "readings",
            "reading-list",
            "pronunciation",
            "pitch",
            "pitch-accent",
            "pitch-accent-list",
            "frequency",
            "frequency-list",
            "audio"
        )
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
                                                        val displayValue = parseFrequencyDisplayValue(meta[2])
                                                        freqChunk.add(FrequencyUpdate(expr, reading, freq, displayValue))
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

    /**
     * Extracts the human-facing label for a frequency entry. Yomitan lists may
     * ship an explicit `displayValue` (rank-based dicts, e.g. JPDB) either at
     * the top level or nested under `frequency`; plain numeric lists (BCCWJ)
     * have none, so we return "" and the storage layer falls back to the rank.
     *
     * Shapes handled:
     *   {"value": 1, "displayValue": "1"}                         -> "1"
     *   {"reading": "…", "frequency": {"value": 5002, "displayValue": "5002"}} -> "5002"
     *   {"reading": "…", "frequency": 1}                          -> ""
     *   1                                                         -> ""
     */
    private fun parseFrequencyDisplayValue(element: JsonElement): String {
        return try {
            if (element !is JsonObject) return ""
            val direct = element["displayValue"]?.jsonPrimitive?.contentOrNull?.trim()
            if (!direct.isNullOrBlank()) return direct
            val nested = (element["frequency"] as? JsonObject)
                ?.get("displayValue")?.jsonPrimitive?.contentOrNull?.trim()
            nested.orEmpty()
        } catch (_: Exception) {
            ""
        }
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

                // Real Jitendex emits TWO sense-group shapes, often side-by-side
                // with `forms` / `attribution` blocks at the top level. See
                // JITENDEX_STRUCTURE.md for the catalogue:
                //   - Shape A: a single div[data-content=sense-group] at top level
                //   - Shape B: a ul[data-content=sense-groups] wrapping
                //     li[data-content=sense-group] children
                // collectSenseGroups handles both — until this rewrite, the
                // parser only accepted Shape B (and even then required an <ol>
                // wrapper around senses that real Jitendex doesn't emit), so
                // most entries silently fell through to the legacy flat-text
                // path and leaked notes/xrefs/forms into the meaning column.
                val groups = mutableListOf<JsonObject>()
                collectSenseGroups(topContent, groups)
                if (groups.isEmpty()) continue
                matched = true

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
                            gDc == "sense" -> {
                                processSense(gObj, definitions, examples)
                            }
                            gObj["tag"]?.jsonPrimitive?.contentOrNull == "ol" -> {
                                // Legacy / fallback layout: senses wrapped in
                                // an <ol>. Kept so the older fixture-style
                                // payloads still parse cleanly even though
                                // real Jitendex doesn't emit the wrapper.
                                val senses = elementAsList(gObj["content"])
                                    .filterIsInstance<JsonObject>()
                                for (sense in senses) processSense(sense, definitions, examples)
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

    private data class SenseContent(val gloss: String, val notes: List<String>)

    /**
     * Recursively gather every `data-content="sense-group"` node in [node].
     * Real Jitendex emits two shapes (see JITENDEX_STRUCTURE.md):
     *   - A bare div[data-content=sense-group] at top level, or
     *   - A ul[data-content=sense-groups] wrapping li[data-content=sense-group].
     * Both end up in [out].
     */
    private fun collectSenseGroups(node: JsonElement, out: MutableList<JsonObject>) {
        when (node) {
            is JsonObject -> {
                when (nodeDataContent(node)) {
                    "sense-group" -> out.add(node)
                    "sense-groups" -> {
                        // Descend one level — the <li sense-group> children sit directly
                        // inside the <ul sense-groups> wrapper.
                        val children = elementAsList(node["content"]).filterIsInstance<JsonObject>()
                        for (child in children) {
                            if (nodeDataContent(child) == "sense-group") out.add(child)
                        }
                    }
                    else -> node["content"]?.let { collectSenseGroups(it, out) }
                }
            }
            is JsonArray -> node.forEach { collectSenseGroups(it, out) }
            else -> Unit
        }
    }

    /** Emit one sense's gloss + notes + examples into the running parse buffers. */
    private fun processSense(
        sense: JsonObject,
        definitions: MutableList<String>,
        examples: MutableList<ExamplePair>
    ) {
        val idx = definitions.size
        val sc = extractSenseGloss(sense)
        if (sc.gloss.isNotBlank()) definitions.add(sc.gloss)
        // Notes are pushed as marker-prefixed entries so the mapper-side
        // NotesExtractor can route them to the Notes card without a DB
        // schema change.
        for (note in sc.notes) {
            definitions.add("${com.yomitanmobile.util.NotesExtractor.NOTE_MARKER}$note")
        }
        // Examples attach to the sense even if its gloss is blank.
        val attachIdx = if (sc.gloss.isNotBlank()) idx else idx - 1
        if (attachIdx >= 0) collectSenseExamples(sense, attachIdx, examples)
    }

    private fun extractSenseGloss(sense: JsonObject): SenseContent {
        val items = elementAsList(sense["content"])
        val glosses = mutableListOf<String>()
        // Jitendex stores per-sense usage hints ("usually written in kana",
        // "archaic", "honorific", domain tags like "music", …) as <span
        // data-content="tag"> nodes with a human-readable `title` attribute.
        // They can live inside the glossary <li> alongside the gloss, or in a
        // sibling <ul data-content="miscellany"> wrapper. extractTextFromContent
        // drops `tag` nodes, so without this harvest the hint is lost — e.g.
        // 但し would show "but, however" with no clue that it's typically
        // written 但し. We collect tag labels once per sense and prepend them
        // in parens so each gloss reads "(usually written in kana) but, however".
        val tagLabels = LinkedHashSet<String>()
        collectUsageTags(sense, tagLabels)

        val notes = mutableListOf<String>()

        for (item in items) {
            val obj = item as? JsonObject ?: continue
            val dc = nodeDataContent(obj)
            when (dc) {
                "glossary" -> {
                    val children = elementAsList(obj["content"]).filterIsInstance<JsonObject>()
                    for (li in children) {
                        val text = extractTextFromContent(li["content"] ?: continue).trim()
                        if (text.isNotBlank()) glosses.add(text)
                    }
                }
                "extra-info" -> {
                    // Typed extra-info boxes (sense-note, xref, antonym,
                    // lang-source, info-gloss) carry the data the user wants
                    // routed to the bottom Notes card. Anything else inside
                    // extra-info (e.g. an inline div of prose for a non-
                    // Jitendex dictionary) falls back to a raw-text capture.
                    notes.addAll(extractExtraInfoNotes(obj))
                }
                // Other markers (POS, tag, miscellany, headword/form wrappers)
                // belong to other display sections and must not bleed into
                // either the meaning column or the notes card.
                else -> Unit
            }
        }

        val glossText = glosses.joinToString("; ")
        val composed = when {
            tagLabels.isEmpty() -> glossText
            glossText.isBlank() -> "(${tagLabels.joinToString(", ")})"
            else -> "(${tagLabels.joinToString(", ")}) $glossText"
        }
        return SenseContent(composed, notes)
    }

    /**
     * Walk an `extra-info` subtree and surface every typed Jitendex box as a
     * labelled note string. Falls back to a raw-text extraction when nothing
     * typed is found, which keeps non-Jitendex dictionaries that just dump
     * prose inside `extra-info` from being silently dropped.
     */
    private fun extractExtraInfoNotes(extraInfo: JsonObject): List<String> {
        val typed = mutableListOf<String>()
        collectTypedExtraBoxes(extraInfo, typed)
        if (typed.isNotEmpty()) return typed
        val raw = extractTextFromContent(extraInfo).trim()
        return if (raw.isBlank()) emptyList() else listOf(raw)
    }

    private fun collectTypedExtraBoxes(node: JsonElement, out: MutableList<String>) {
        when (node) {
            is JsonObject -> {
                when (nodeDataContent(node)) {
                    "sense-note" -> out.add(formatLabeledBox(node, "Note"))
                    "xref" -> out.add(formatReferenceBox(node, "See also"))
                    "antonym" -> out.add(formatReferenceBox(node, "Antonym"))
                    "lang-source" -> out.add(formatLabeledBox(node, "Language of Origin"))
                    "info-gloss" -> out.add(formatLabeledBox(node, "Explanation"))
                    // Examples are extracted via the dedicated walker; don't
                    // re-capture them as notes.
                    "example-sentence",
                    "example",
                    "example-sentences",
                    "example-sentence-list" -> Unit
                    else -> node["content"]?.let { collectTypedExtraBoxes(it, out) }
                }
            }
            is JsonArray -> node.forEach { collectTypedExtraBoxes(it, out) }
            else -> Unit
        }
    }

    /**
     * Format a `<label-content>` extra-info box like:
     *   sense-note  ⇒ "Note: <body>"
     *   lang-source ⇒ "Language of Origin: <body>"
     *   info-gloss  ⇒ "Explanation: <body>"
     *
     * Uses the box's own `*-label` text when present; falls back to
     * [defaultLabel] otherwise so the user-facing prefix is never blank.
     */
    private fun formatLabeledBox(box: JsonObject, defaultLabel: String): String {
        val children = elementAsList(box["content"])
        var label: String? = null
        var content: String? = null
        for (child in children) {
            val obj = child as? JsonObject ?: continue
            val dc = nodeDataContent(obj) ?: continue
            when {
                dc.endsWith("-label") -> label = extractTextFromContent(obj).trim()
                dc.endsWith("-content") -> content = extractTextFromContent(obj).trim()
            }
        }
        val finalLabel = label?.takeIf { it.isNotBlank() } ?: defaultLabel
        return if (content.isNullOrBlank()) finalLabel else "$finalLabel: $content"
    }

    /**
     * Format an `xref` / `antonym` box. These boxes nest a `*-content` div
     * which holds a `reference-label` span followed by one or more `<a>`
     * links to the referenced entries; the sibling `*-glossary` div carries
     * the linked entry's gloss text. We prefer the link text(s) (joined by
     * comma) for the chip; the glossary text is a fallback when the box has
     * no links for some reason.
     */
    private fun formatReferenceBox(box: JsonObject, defaultLabel: String): String {
        val children = elementAsList(box["content"])
        var label = defaultLabel
        val targets = mutableListOf<String>()
        var fallbackGloss: String? = null
        for (child in children) {
            val obj = child as? JsonObject ?: continue
            val dc = nodeDataContent(obj) ?: continue
            when {
                dc.endsWith("-content") -> {
                    val inner = elementAsList(obj["content"])
                    for (n in inner) {
                        val o = n as? JsonObject ?: continue
                        val odc = nodeDataContent(o)
                        val tag = o["tag"]?.jsonPrimitive?.contentOrNull
                        when {
                            odc == "reference-label" -> {
                                val txt = extractTextFromContent(o).trim()
                                if (txt.isNotBlank()) label = txt
                            }
                            tag == "a" -> {
                                val txt = extractTextFromContent(o).trim()
                                if (txt.isNotBlank()) targets.add(txt)
                            }
                        }
                    }
                }
                dc.endsWith("-glossary") -> {
                    fallbackGloss = extractTextFromContent(obj).trim()
                }
            }
        }
        return when {
            targets.isNotEmpty() -> "$label: ${targets.joinToString(", ")}"
            !fallbackGloss.isNullOrBlank() -> "$label: $fallbackGloss"
            else -> label
        }
    }

    private fun collectUsageTags(element: JsonElement, out: MutableSet<String>) {
        when (element) {
            is JsonObject -> {
                val dc = nodeDataContent(element)
                // POS codes are surfaced separately on the POS chip; skip them
                // so we don't duplicate "transitive verb" inside every gloss.
                if (dc == "part-of-speech-info") return
                // Don't dive into example sentences — the inner highlight spans
                // would otherwise be mistaken for tags.
                if (dc == "example-sentence" ||
                    dc == "example" ||
                    dc == "example-sentences" ||
                    dc == "example-sentence-list") return
                if (dc == "tag") {
                    val title = element["title"]?.jsonPrimitive?.contentOrNull?.trim()
                    val code = (element["data"] as? JsonObject)
                        ?.get("code")?.jsonPrimitive?.contentOrNull?.trim()
                    // Prefer the compact form ("usually kana") over Jitendex's
                    // verbose `title` ("word usually written using kana alone")
                    // so the hint doesn't eat the whole gloss line on a card.
                    val label = code?.let { PartsOfSpeechFormatter.shortUsageLabelForCode(it) }
                        ?: title?.takeIf { it.isNotBlank() }
                        ?: code
                    if (!label.isNullOrBlank()) out.add(label)
                    return
                }
                element["content"]?.let { collectUsageTags(it, out) }
            }
            is JsonArray -> element.forEach { collectUsageTags(it, out) }
            else -> Unit
        }
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
        // The JP-side content element, kept so we can build furigana segments
        // (ruby readings) from the exact same subtree the plain jp came from.
        var jpContent: JsonElement? = null
        val items = elementAsList(container["content"])
        for (item in items) {
            val obj = item as? JsonObject ?: continue
            val dc = nodeDataContent(obj)?.lowercase().orEmpty()
            when {
                // Jitendex variants: "example-sentence-a" / "-japanese", "-b" / "-english"
                dc.contains("example-sentence-a") || dc.contains("japanese") -> {
                    val content = obj["content"] ?: continue
                    jpContent = content
                    jp = extractTextFromContent(content).trim()
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
        if (jp.isBlank()) return null
        val segments = jpContent?.let { buildFuriganaSegments(it) } ?: emptyList()
        return ExamplePair(jp, en, segments = segments)
    }

    /**
     * Turn a JP example-sentence content subtree into furigana segments,
     * preserving the `<ruby>…<rt>reading</rt></ruby>` annotations the plain-text
     * extractor drops. A `<ruby>` node becomes one (kanji, reading) segment;
     * everything else is coalesced into blank-reading text segments. Adjacent
     * text is merged so the output stays compact.
     */
    private fun buildFuriganaSegments(element: JsonElement): List<FuriganaSegment> {
        val out = mutableListOf<FuriganaSegment>()
        collectFuriganaSegments(element, out)
        return out
    }

    private fun collectFuriganaSegments(element: JsonElement, out: MutableList<FuriganaSegment>) {
        when (element) {
            is JsonObject -> {
                val tag = element["tag"]?.jsonPrimitive?.contentOrNull
                // Stray rt/rp outside a ruby wrapper is a bare reading — drop it
                // so it doesn't surface as body text.
                if (tag == "rt" || tag == "rp") return
                if (nodeDataContent(element) == "attribution-footnote") return
                if (tag == "ruby") {
                    val base = StringBuilder()
                    val reading = StringBuilder()
                    collectRuby(element["content"], base, reading)
                    if (base.isNotEmpty()) {
                        appendFuriganaText(out, base.toString(), reading.toString())
                    }
                    return
                }
                element["text"]?.jsonPrimitive?.contentOrNull?.let {
                    appendFuriganaText(out, it, "")
                }
                element["content"]?.let { collectFuriganaSegments(it, out) }
            }
            is JsonArray -> element.forEach { collectFuriganaSegments(it, out) }
            is JsonPrimitive -> element.contentOrNull?.let { appendFuriganaText(out, it, "") }
            else -> Unit
        }
    }

    /** Gather the base text and rt/rp reading inside a single `<ruby>` node. */
    private fun collectRuby(element: JsonElement?, base: StringBuilder, reading: StringBuilder) {
        when (element) {
            is JsonObject -> {
                val tag = element["tag"]?.jsonPrimitive?.contentOrNull
                if (tag == "rt" || tag == "rp") {
                    // Read the rt's own children — extractTextFromContent would
                    // return "" for an rt/rp node itself (it filters them out).
                    element["content"]?.let { reading.append(extractTextFromContent(it)) }
                    element["text"]?.jsonPrimitive?.contentOrNull?.let { reading.append(it) }
                    return
                }
                element["text"]?.jsonPrimitive?.contentOrNull?.let { base.append(it) }
                element["content"]?.let { collectRuby(it, base, reading) }
            }
            is JsonArray -> element.forEach { collectRuby(it, base, reading) }
            is JsonPrimitive -> element.contentOrNull?.let { base.append(it) }
            else -> Unit
        }
    }

    private fun appendFuriganaText(out: MutableList<FuriganaSegment>, text: String, reading: String) {
        if (text.isEmpty()) return
        if (reading.isBlank()) {
            // Coalesce consecutive plain runs into the previous plain segment.
            val last = out.lastOrNull()
            if (last != null && last.reading.isEmpty()) {
                out[out.lastIndex] = last.copy(text = last.text + text)
                return
            }
            out.add(FuriganaSegment(text, ""))
        } else {
            out.add(FuriganaSegment(text, reading))
        }
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
                element.flatMap { item ->
                    try {
                        when (item) {
                            is JsonPrimitive -> listOf(item.content)
                            is JsonObject -> parseStructuredContentAsList(item)
                            is JsonArray -> listOf(
                                item.mapNotNull { subItem ->
                                    when (subItem) {
                                        is JsonPrimitive -> subItem.content
                                        is JsonObject -> parseStructuredContent(subItem)
                                        else -> null
                                    }
                                }.joinToString("; ")
                            )
                            else -> emptyList()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }.filter { it.isNotBlank() }
            }
            is JsonObject -> parseStructuredContentAsList(element).filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    /**
     * Like [parseStructuredContent], but if the node's top-level content
     * carries an obvious sense list — an `<ol>` or `<ul>` whose `<li>`
     * children each describe a separate meaning — emit one definition per
     * `<li>` so the back-side card renders them as `1.`, `2.`, `3.` instead
     * of a single fused entry. Falls through to the original flat extraction
     * when no list structure is detected.
     *
     * This is the non-Jitendex fallback path. Jitendex's stricter
     * sense-groups layout is still handled separately by
     * [tryParseJitendexStructure], which runs before this.
     */
    private fun parseStructuredContentAsList(obj: JsonObject): List<String> {
        val split = trySplitSenseList(obj)
        if (split != null) return split
        return listOfNotNull(parseStructuredContent(obj))
    }

    private fun trySplitSenseList(obj: JsonObject): List<String>? {
        return try {
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type != "structured-content") return null
            val topContent = obj["content"] ?: return null
            val items = elementAsList(topContent)
            // Look for the first <ol>/<ul> at the top level whose children are
            // mostly <li> elements. Treat each such <li> as a separate sense.
            for (item in items) {
                val node = item as? JsonObject ?: continue
                val tag = node["tag"]?.jsonPrimitive?.contentOrNull ?: continue
                if (tag != "ol" && tag != "ul") continue
                val children = elementAsList(node["content"]).filterIsInstance<JsonObject>()
                val liChildren = children.filter {
                    it["tag"]?.jsonPrimitive?.contentOrNull == "li"
                }
                if (liChildren.size < 2) continue
                val perSense = liChildren.mapNotNull { li ->
                    val text = extractTextFromContent(li["content"] ?: return@mapNotNull null).trim()
                    text.ifBlank { null }
                }
                if (perSense.isNotEmpty()) return perSense
            }
            null
        } catch (_: Exception) {
            null
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
                    // Render each child once, then join inline children with no
                    // separator (so ruby + text fragments concatenate naturally
                    // inside a sentence) and block-level children with "; " (so
                    // adjacent senses / alternative forms don't visually fuse
                    // into "wise person, sage賢者けんじゃ").
                    val pieces = element.mapNotNull { child ->
                        val text = extractTextFromContent(child)
                        if (text.isBlank()) null else child to text
                    }
                    if (pieces.isEmpty()) return ""
                    buildString {
                        for ((i, p) in pieces.withIndex()) {
                            val (child, text) = p
                            if (i > 0) {
                                val prev = pieces[i - 1].first
                                if (isBlockNode(prev) || isBlockNode(child)) append("; ")
                            }
                            append(text)
                        }
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
                    // Jitendex / Yomitan mark part-of-speech labels, attribution
                    // footnotes, headword forms, and pitch / frequency widgets
                    // with these data-content values. They belong to other display
                    // sections (POS chip / header / pitch widget / source link)
                    // and must not bleed into the gloss text — without filtering,
                    // alternate kanji and kana readings ("賢者", "けんじゃ") fuse
                    // onto the end of the meaning column.
                    if (dc in NON_GLOSS_DATA_CONTENT) return ""

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

    private fun isBlockNode(element: JsonElement): Boolean {
        if (element !is JsonObject) return false
        val tag = element["tag"]?.jsonPrimitive?.contentOrNull ?: return false
        return tag in BLOCK_TAGS
    }

}
