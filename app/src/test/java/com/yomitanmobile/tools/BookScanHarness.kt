package com.yomitanmobile.tools

import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.mapper.toDomain
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.text.TextExtraction
import com.yomitanmobile.data.text.TextFileFormat
import com.yomitanmobile.domain.model.FrequencyTier
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScanToken
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanSource
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.usecase.TextScanPlanner
import com.yomitanmobile.util.JapaneseTokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File
import com.yomitanmobile.data.anki.AnkiCollectionIndex
import com.yomitanmobile.data.anki.AnkiNoteFieldIndexer
import com.yomitanmobile.domain.usecase.WordFilterRules
import com.yomitanmobile.domain.usecase.ScanEntryResolver
import java.text.Normalizer

/**
 * Runs the REAL text-scan pipeline on a desktop JVM, over files on this
 * machine.
 *
 * The app reads its lexicon and its entries out of Room, which only exists on
 * a device; here the same Yomitan zips the app downloads are parsed by the
 * app's own [YomitanDictionaryParser] and the dictionary is held in memory
 * instead. Everything downstream — [TextExtraction], [JapaneseTokenizer],
 * [MergedWordEntry.mergeEntries], [TextScanPlanner] — is the production code
 * the phone runs, so the plan this prints is the plan the app would produce
 * for the same file, minus the duplicate check (the AnkiDroid collection is
 * not reachable from here).
 *
 * Skipped unless the paths are supplied:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*BookScanHarness" \
 *   -Dbook.paths=/path/vol1.epub[:/path/vol2.epub] \
 *   -Ddict.zip=/path/JMdict_english.zip \
 *   -Dfreq.zip=/path/JPDB_frequency.zip \
 *   -Dout.dir=/path/report \
 *   [-Danki.fields=/path/fields.txt]
 * ```
 *
 * `anki.fields` turns on the "already in Anki" filter. Dump it from a COPY of
 * the desktop collection (never the live file):
 * `sqlite3 collection.anki2 "select replace(replace(replace(flds, char(10), '<br>'), char(13), ''), char(31), '␟') from notes" > fields.txt`
 */
class BookScanHarness {

    @Test
    fun scanBooks() = runBlocking {
        // Separated by the platform path separator, not a comma: book file
        // names routinely contain commas ("-- 1, 2020 -- KADOKAWA").
        val bookPaths = System.getProperty("book.paths").orEmpty()
            .split(File.pathSeparator).map { it.trim() }.filter { it.isNotEmpty() }
        val dictZip = System.getProperty("dict.zip").orEmpty()
        Assume.assumeTrue("harness: pass -Dbook.paths and -Ddict.zip", bookPaths.isNotEmpty() && dictZip.isNotEmpty())

        val freqZip = System.getProperty("freq.zip").orEmpty()
        val outDir = File(System.getProperty("out.dir") ?: "build/book-scan").apply { mkdirs() }
        val parser = YomitanDictionaryParser()

        // ---- 1. read the books exactly as TextFileReader would -------------
        // Files are sorted by name so a series is read in publication order,
        // which is what makes the "earliness" term mean anything.
        val documents = bookPaths.map { resolveBook(it) }.sortedBy { it.name.lowercase() }.map { file ->
            val bytes = file.readBytes()
            val format = TextFileFormat.fromFileName(file.name) ?: TextExtraction.sniff(bytes)
            val extracted = TextExtraction.extract(bytes, format)
            log("read ${file.name}: ${extracted.text.length} chars, ${extracted.partCount} parts")
            file.name to extracted
        }

        // ---- 2. lexicon, as getSurfaceLexicon() builds it ------------------
        // Expressions, plus only those readings that are also a spelling —
        // the same rule as DictionaryDao.getKanaWrittenReadings, restated here
        // because that one is SQL.
        val lexicon = HashSet<String>(1 shl 19)
        parser.parseFromZipStreaming(
            inputStream = File(dictZip).inputStream().buffered(),
            onBatch = { entries, _ ->
                for (entry in entries) {
                    lexicon.add(entry.expression)
                    val kanaWritten = entry.reading == entry.expression ||
                        entry.partsOfSpeech.split(',', ';', ' ')
                            .any { it.trim() == "uk" } ||
                        entry.definition.contains("usually kana")
                    if (entry.reading.isNotBlank() && kanaWritten) lexicon.add(entry.reading)
                }
            }
        )
        log("lexicon: ${lexicon.size} surfaces")

        // ---- 3. frequency ranks (needed before tokenising: the segmenter
        // prefers a common reading of an ambiguous stretch) -------------------------------------------
        // Mirrors applyFrequenciesFromTable(): best (lowest) rank wins, and a
        // rank stored without a reading matches the expression alone.
        val ranks = HashMap<String, MutableList<Pair<String, Int>>>(1 shl 18)
        if (freqZip.isNotEmpty()) {
            parser.parseFromZipStreaming(
                inputStream = File(freqZip).inputStream().buffered(),
                onBatch = { _, _ -> },
                onMetaBatch = { updates: List<FrequencyUpdate>, _ ->
                    for (u in updates) {
                        if (u.frequency <= 0) continue
                        ranks.getOrPut(u.expression) { ArrayList(2) }
                            .add(u.reading.orEmpty() to u.frequency)
                    }
                }
            )
        }
        log("frequency: ${ranks.size} expressions ranked")
        // Same as getSurfaceLexicon: kana spellings a list ranks as written.
        for ((surface, entries) in ranks) {
            if (!surface.all { JapaneseTokenizer.isKana(it) || it == 'ー' }) continue
            if (entries.any { it.first.isEmpty() && it.second in 1..JapaneseTokenizer.KANA_WRITTEN_RANK }) {
                lexicon.add(surface)
            }
        }

        fun rankOf(expression: String, reading: String): Int =
            ranks[expression].orEmpty()
                .filter { it.first.isEmpty() || it.first == reading }
                .minOfOrNull { it.second } ?: 0

        // ---- 4. tokenise all books as ONE body of text ---------------------
        // The same two structures the app builds: everything written, plus
        // where the frequency lists put each surface (see
        // DictionaryRepository.getCommonSurfaces). BOTH columns, exactly like
        // FrequencyDao.getCommonSurfaceRanks — the lists rank 会う and 在る,
        // not あう and ある, so without the readings every kana spelling looks
        // unranked.
        val common = HashMap<String, Int>(1 shl 16)
        fun offer(surface: String, rank: Int) {
            if (surface.isEmpty() || rank !in 1..JapaneseTokenizer.COMMON_RANK) return
            val existing = common[surface]
            if (existing == null || rank < existing) common[surface] = rank
        }
        for ((surface, entries) in ranks) {
            for ((reading, rank) in entries) {
                offer(surface, rank)
                offer(reading, rank)
            }
        }
        val words = object : JapaneseTokenizer.Lexicon {
            override fun contains(surface: String) = surface in lexicon
            override fun rank(surface: String) = common[surface] ?: 0
            override val ranksAvailable: Boolean get() = common.isNotEmpty()
        }
        val accumulator = JapaneseTokenizer.Accumulator()
        for ((_, document) in documents) accumulator.add(document.text, words)
        val totalLength = accumulator.totalLength.coerceAtLeast(1)
        val tokens = accumulator.tokens().map { token ->
            ScanToken(
                baseForm = token.baseForm,
                occurrences = token.count,
                sentence = token.sentence,
                earliness = 1f - token.firstOffset.toFloat() / totalLength,
                honorificHits = token.honorificHits
            )
        }
        val totalTokenCount = tokens.sumOf { it.occurrences }
        log("tokens: ${tokens.size} distinct, $totalTokenCount running")

        // ---- 5. entries for the words the text actually used ---------------
        // The dictionary is streamed, so lookups are answered from the set of
        // entries a pass collected. Redirect targets are only known once the
        // first answers are in, which is what the second pass is for.
        suspend fun collect(expressions: Set<String>, readings: Set<String>): List<WordEntry> {
            val out = ArrayList<WordEntry>()
            parser.parseFromZipStreaming(
                inputStream = File(dictZip).inputStream().buffered(),
                onBatch = { entries: List<DictionaryEntry>, _ ->
                    for (entry in entries) {
                        if (entry.expression !in expressions && entry.reading !in readings) continue
                        out += entry.copy(frequency = rankOf(entry.expression, entry.reading)).toDomain()
                    }
                }
            )
            return out
        }
        val needed = tokens.mapTo(HashSet()) { it.baseForm }
        val firstPass = collect(needed, needed)
        val secondPass = HashMap<String, List<WordEntry>>()
        val resolved = ScanEntryResolver.resolve(
            words = needed,
            byExpressions = { list ->
                val wanted = list.toHashSet()
                val known = firstPass.filter { it.expression in wanted }
                val missing = wanted - known.mapTo(HashSet()) { it.expression } - secondPass.keys
                if (missing.isNotEmpty()) {
                    val fetched = collect(missing, emptySet()).groupBy { it.expression }
                    for (m in missing) secondPass[m] = fetched[m].orEmpty()
                }
                known + wanted.flatMap { secondPass[it].orEmpty() }
            },
            byReadings = { list ->
                val wanted = list.toHashSet()
                firstPass.filter { it.reading in wanted }
            }
        )
        log("resolved: ${resolved.size} of ${needed.size} words found in the dictionary")

        // ---- 6. the plan ---------------------------------------------------
        val sources = documents.map { (name, document) ->
            TextScanSource(
                fileName = name,
                formatLabel = document.format.label,
                charsetName = document.charsetName,
                characterCount = document.text.count { JapaneseTokenizer.isJapanese(it) },
                partCount = document.partCount
            )
        }
        // ---- 5b. the Anki collection, when a field dump is given ------------
        // One note per line, fields separated by ␟ — Anki's own `flds` column,
        // dumped with newlines turned into <br> (see the doc comment at the top). Indexed by the same code AnkiCollectionIndex runs on
        // the phone, so "already in Anki" means exactly what it means there.
        val ankiFields = System.getProperty("anki.fields").orEmpty()
        val ankiIndex = if (ankiFields.isNotEmpty()) {
            val keys = HashSet<String>(1 shl 14)
            var notes = 0
            File(ankiFields).forEachLine { line ->
                // ␟ stands in for U+001F: the sqlite3 shell prints the real
                // separator as the two characters "^_".
                AnkiNoteFieldIndexer.collectKeysFromNote(line.replace('␟', '\u001f'), keys)
                notes++
            }
            log("anki: $notes notes, ${keys.size} keys")
            AnkiCollectionIndex.Index(keys, notes, available = true)
        } else {
            null
        }

        val filters = TextScanFilters(
            tier = FrequencyTier.entries.first { it.name == (System.getProperty("tier") ?: "TOP_20K") },
            minOccurrences = System.getProperty("minOccurrences")?.toIntOrNull() ?: 1,
            skipPlainKana = System.getProperty("skipPlainKana")?.toBooleanStrictOrNull() ?: true,
            skipKatakana = System.getProperty("skipKatakana")?.toBooleanStrictOrNull() ?: true,
            assumeKnownTopRank = System.getProperty("assumeKnownTopRank")?.toIntOrNull() ?: 0,
            maxWords = System.getProperty("maxWords")?.toIntOrNull() ?: 0,
            // exported_words lives on the phone; the collection only when dumped.
            skipAlreadyInAnki = ankiIndex != null,
            skipAlreadyMined = false
        )
        val plan = TextScanPlanner.plan(
            sources = sources,
            words = tokens,
            entries = resolved,
            filters = filters,
            totalTokenCount = totalTokenCount,
            isInAnki = { entry ->
                ankiIndex?.containsAny(
                    listOf(entry.primaryExpression) + entry.alternativeExpressions,
                    entry.reading,
                    readingCountsAlone = WordFilterRules.isUsuallyKana(entry)
                ) ?: false
            },
            ankiScanUnavailable = ankiIndex == null
        )

        // ---- 7. report ------------------------------------------------------
        val report = buildString {
            appendLine("# Text scan")
            for (source in sources) {
                appendLine(
                    "- ${source.fileName}: ${source.formatLabel}, ${source.charsetName}, " +
                        "${source.characterCount} Japanese chars, ${source.partCount} parts"
                )
            }
            appendLine()
            appendLine("filters: tier=${filters.tier} minOccurrences=${filters.minOccurrences} " +
                "assumeKnownTopRank=${filters.assumeKnownTopRank} maxWords=${filters.maxWords}")
            appendLine("running words: $totalTokenCount")
            appendLine("distinct words: ${plan.distinctWordCount}")
            appendLine("selected: ${plan.selectedCount}")
            appendLine("skipped: ${plan.skippedCount}")
            appendLine("known coverage: ${"%.1f".format(plan.knownCoverage * 100)}%")
            appendLine()
            appendLine("## skipped by reason")
            for ((reason, count) in plan.skipped.entries.sortedByDescending { it.value }) {
                appendLine("- $reason: $count")
            }
            appendLine()
            appendLine("## grammar counter")
            appendLine("(every structure the text used: form, bucket, global rank, occurrences)")
            appendLine("form\tbucket\tcard\trank\toccurrences")
            for (use in plan.grammarUses) {
                appendLine(
                    "${use.form}\t${use.source}\t${if (use.becameCard) "CARD" else ""}\t" +
                        "${if (use.rank > 0) use.rank else ""}\t${use.occurrences}"
                )
            }
            appendLine()
            appendLine(
                "grammar totals: ${plan.grammarUses.size} structures, " +
                    "${plan.grammarUses.sumOf { it.occurrences }} uses, " +
                    plan.grammarUses.groupBy { it.source }
                        .entries.sortedBy { it.key.name }
                        .joinToString(", ") { (source, list) -> "$source=${list.size}" }
            )
            appendLine()
            // Problem 4 lives here: what the grammar filter did NOT catch.
            // Nothing is dropped on this evidence — it is a list to read before
            // deciding what to add to the stoplist or the tag rules.
            appendLine("## grammar suspects still in the deck")
            appendLine("(kana-only, or carrying a grammatical tag, sorted by occurrences)")
            appendLine("word\treading\tocc\trank\ttags\tmeaning")
            plan.selected
                .filter { word ->
                    val entry = word.entry
                    val kanaOnly = entry.primaryExpression.none { JapaneseTokenizer.isKanji(it) }
                    val tags = entry.partsOfSpeech.flatMap { it.split(',', ';', ' ') }
                        .map { it.trim() }
                    kanaOnly || tags.any { it in GRAMMAR_TAGS }
                }
                .sortedByDescending { it.occurrences }
                .take(120)
                .forEach { word ->
                    val entry = word.entry
                    appendLine(
                        "${entry.primaryExpression}\t${entry.reading}\t${word.occurrences}\t" +
                            "${entry.frequency}\t${entry.partsOfSpeech.joinToString(" | ")}\t" +
                            entry.definitions.firstOrNull().orEmpty().replace('\t', ' ').take(70)
                    )
                }
            appendLine()
            appendLine("## cards, in the order Anki would introduce them")
            appendLine("#\tword\treading\tocc\trank\tscore\ttags\tmeaning\tsentence")
            plan.selected.forEachIndexed { i, word ->
                val entry = word.entry
                appendLine(
                    "${i + 1}\t${entry.primaryExpression}\t${entry.reading}\t${word.occurrences}\t" +
                        "${entry.frequency}\t${"%.3f".format(word.score)}\t" +
                        entry.partsOfSpeech.joinToString(" | ") + "\t" +
                        entry.definitions.firstOrNull().orEmpty().replace('\t', ' ').take(90) + "\t" +
                        word.sentence.replace('\t', ' ')
                )
            }
        }
        val file = File(outDir, "text-scan.tsv")
        file.writeText(report)
        log("report written to ${file.absolutePath}")
        log(report.lineSequence().take(40).joinToString("\n"))
    }

    /**
     * A path pasted from a file manager can differ from the one on disk by
     * Unicode normalisation alone — "Tōkyō" with a precomposed ō against o
     * plus a combining macron. Same name, different bytes, and File.exists()
     * says no.
     */
    private fun resolveBook(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val wanted = Normalizer.normalize(direct.name, Normalizer.Form.NFC)
        val match = direct.parentFile?.listFiles()?.firstOrNull {
            Normalizer.normalize(it.name, Normalizer.Form.NFC) == wanted
        }
        return match ?: direct
    }

    private fun log(message: String) = println("[book-scan] $message")

    private companion object {
        /**
         * Tags that make a word a candidate for the grammar filter. Wider than
         * [com.yomitanmobile.domain.usecase.WordFilterRules]'s own set on
         * purpose — this is a list to look at, not a list to act on.
         */
        val GRAMMAR_TAGS = setOf(
            "prt", "conj", "cop", "cop-da", "aux", "aux-v", "aux-adj",
            "int", "pn", "adj-pn", "cnj", "exp", "suf", "pref"
        )
    }
}
