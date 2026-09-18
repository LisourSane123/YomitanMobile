package com.yomitanmobile.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.anki.AnkiCollectionIndex
import com.yomitanmobile.data.anki.AnkiNoteFieldIndexer
import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.mapper.toDomain
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.CardProfile
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.usecase.ScanEntryResolver
import com.yomitanmobile.domain.usecase.TextScanPlanner
import com.yomitanmobile.domain.usecase.WordFilterRules
import com.yomitanmobile.util.JapaneseDeconjugator
import com.yomitanmobile.util.JapaneseTokenizer
import com.yomitanmobile.util.SentenceContextHighlighter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kindle Vocabulary Builder → Anki desktop, on the app's own code.
 *
 * `tools/kindle-sync/kindle-sync.sh` finds the Kindle, copies `vocab.db` off
 * it and dumps the lookups to a TSV; this reads that TSV and does everything
 * the phone would do with a mined word: dictionary form through
 * [JapaneseDeconjugator] and [ScanEntryResolver], "already in Anki" through
 * [AnkiNoteFieldIndexer] over the whole desktop collection, and the card
 * through [AnkiCardCreator.createAnkiCard] — so a card from the laptop is the
 * card the phone would write. The Kindle's sentence goes to FrontContext, the
 * slot the text scanner puts its source sentence in.
 *
 * Runs under Robolectric only because [AnkiCardCreator] wants a Context for
 * its locale; nothing here touches a device. Anki is reached through
 * AnkiConnect (localhost:8765), never through the collection file.
 *
 * Skipped unless the lookups are supplied:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*KindleSync" \
 *   -Dkindle.lookups=/path/lookups.tsv \
 *   -Ddict.zip=/path/jitendex-yomitan.zip \
 *   -Dfreq.zip=/path/JPDB_frequency.zip \
 *   -Dkindle.deck=test_kindle [-Dkindle.dryRun=true] [-Dout.dir=/path/report]
 * ```
 *
 * TSV columns (tab-separated, no header): word, stem, usage, timestamp (ms),
 * book title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KindleSync {

    private data class Lookup(
        val word: String,
        val stem: String,
        val usage: String,
        val timestamp: Long,
        val book: String
    )

    @Test
    fun sync() = runBlocking {
        val lookupsPath = System.getProperty("kindle.lookups").orEmpty()
        val dictZip = System.getProperty("dict.zip").orEmpty()
        Assume.assumeTrue(
            "kindle-sync: pass -Dkindle.lookups and -Ddict.zip",
            lookupsPath.isNotEmpty() && dictZip.isNotEmpty()
        )
        val freqZip = System.getProperty("freq.zip").orEmpty()
        val deck = System.getProperty("kindle.deck") ?: "test_kindle"
        val dryRun = System.getProperty("kindle.dryRun")?.toBooleanStrictOrNull() ?: false
        val outDir = File(System.getProperty("out.dir") ?: "build/kindle-sync").apply { mkdirs() }
        val anki = AnkiConnect(System.getProperty("anki.connect") ?: "http://127.0.0.1:8765")

        // ---- 1. lookups, in the order they were made -------------------------
        val lookups = File(lookupsPath).readLines()
            .mapNotNull { line ->
                val c = line.split('\t')
                if (c.size < 5) return@mapNotNull null
                Lookup(c[0].trim(), c[1].trim(), c[2], c[3].toLongOrNull() ?: 0, c[4].trim())
            }
            .filter { it.word.isNotEmpty() && it.word.any(JapaneseTokenizer::isJapanese) }
            .sortedBy { it.timestamp }
        log("lookups: ${lookups.size}")
        if (lookups.isEmpty()) return@runBlocking

        // ---- 2. what each lookup could be the dictionary form of ------------
        // Kindle's `word` is its own guess at the headword, which for Japanese
        // is sometimes right (突きつける) and sometimes the selection itself;
        // the deconjugator covers the second case, `stem` is the last resort.
        val candidatesOf = lookups.associateWith { lookup ->
            buildList {
                add(lookup.word)
                JapaneseDeconjugator.analyze(lookup.word)
                    .sortedBy { it.depth }
                    .forEach { add(it.baseForm) }
                if (lookup.stem.length > 1) add(lookup.stem)
            }.distinct()
        }
        val needed = candidatesOf.values.flatten().toHashSet()

        // ---- 3. dictionary + frequency, streamed from the zips --------------
        val parser = YomitanDictionaryParser()
        val ranks = HashMap<String, MutableList<Pair<String, Int>>>()
        if (freqZip.isNotEmpty()) {
            parser.parseFromZipStreaming(
                inputStream = File(freqZip).inputStream().buffered(),
                onBatch = { _, _ -> },
                onMetaBatch = { updates: List<FrequencyUpdate>, _ ->
                    for (u in updates) {
                        if (u.frequency <= 0) continue
                        ranks.getOrPut(u.expression) { ArrayList(2) }.add(u.reading.orEmpty() to u.frequency)
                    }
                }
            )
        }
        fun rankOf(expression: String, reading: String): Int =
            ranks[expression].orEmpty()
                .filter { it.first.isEmpty() || it.first == reading }
                .minOfOrNull { it.second } ?: 0

        suspend fun collect(expressions: Set<String>, readings: Set<String>): List<WordEntry> {
            val out = ArrayList<WordEntry>()
            parser.parseFromZipStreaming(
                inputStream = File(dictZip).inputStream().buffered(),
                onBatch = { entries: List<DictionaryEntry>, _ ->
                    for (entry in entries) {
                        if (entry.expression !in expressions && entry.reading !in readings) continue
                        // One list installed here, so it is the leading one:
                        // its number is what the card's Frequency field carries.
                        val rank = rankOf(entry.expression, entry.reading)
                        out += entry.copy(frequency = rank).toDomain()
                            .copy(frequencyValue = if (rank > 0) rank.toString() else "")
                    }
                }
            )
            return out
        }
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

        // ---- 4. the desktop collection, indexed like the phone's scan -------
        val ankiIndex = anki.collectionIndex()
        log("anki: ${ankiIndex.noteCount} notes indexed")

        // ---- 5. decide, one word per lookup ----------------------------------
        val report = StringBuilder()
        report.appendLine("#\tstatus\tkindle word\tcard\treading\trank\tsentence")
        val toAdd = LinkedHashMap<String, Pair<MergedWordEntry, Lookup>>()
        for (lookup in lookups) {
            val entry = candidatesOf.getValue(lookup).firstNotNullOfOrNull { candidate ->
                val fromStemOnly = candidate == lookup.stem && candidate != lookup.word &&
                    JapaneseDeconjugator.analyze(lookup.word).none { it.baseForm == candidate }
                resolved[candidate]?.takeIf { !fromStemOnly || isPrefixOf(it, lookup.word) }
            }
            val status = when {
                entry == null -> "NOT_IN_DICTIONARY"
                entry.definitions.none { it.isNotBlank() } -> "NO_DEFINITION"
                // A single kana is a mis-tap on the Kindle (と out of とぼとぼ,
                // オ out of a censored word), never the word the reader meant.
                entry.primaryExpression.length == 1 &&
                    JapaneseTokenizer.isKana(entry.primaryExpression[0]) -> "SINGLE_KANA"
                entry.primaryExpression in TextScanPlanner.FUNCTION_WORDS -> "FUNCTION_WORD"
                ankiIndex.containsAny(
                    listOf(entry.primaryExpression) + entry.alternativeExpressions,
                    entry.reading,
                    readingCountsAlone = WordFilterRules.isUsuallyKana(entry)
                ) -> "IN_ANKI"
                "${entry.primaryExpression}|${entry.reading}" in toAdd -> "REPEAT"
                else -> {
                    toAdd["${entry.primaryExpression}|${entry.reading}"] = entry to lookup
                    "NEW"
                }
            }
            report.appendLine(
                "${lookup.timestamp}\t$status\t${lookup.word}\t${entry?.primaryExpression.orEmpty()}\t" +
                    "${entry?.reading.orEmpty()}\t${entry?.frequency ?: ""}\t${sentenceFor(lookup, entry)}"
            )
        }
        val summary = report.lineSequence().drop(1).filter { it.isNotBlank() }
            .groupingBy { it.split('\t')[1] }.eachCount()
        log("plan: $summary")

        // ---- 6. cards ---------------------------------------------------------
        val context: Context = ApplicationProvider.getApplicationContext()
        val creator = AnkiCardCreator(context, LanguageSettings(context))
        val profile = CardProfile.JAPANESE
        // Kindle lookups are all about the sentence they were made in, so the
        // front-context slot is on regardless of the card-style default —
        // the same override the text scanner makes.
        val style = CardStylePreferences(showFrontContextSentence = true)
        val notes = toAdd.values.map { (entry, lookup) ->
            val word = entry.toWordEntry().copy(
                exampleSentence = sentenceFor(lookup, entry),
                exampleSentenceTranslation = ""
            )
            val fields = creator.createAnkiCard(word, stylePrefs = style).toFieldArray(profile)
            val tags = listOf("yomitan-mobile", "kindle", slug(lookup.book)).filter { it.isNotEmpty() }
            Triple(entry.primaryExpression, profile.fieldNames.zip(fields).toMap(), tags)
        }

        if (dryRun || notes.isEmpty()) {
            log(if (dryRun) "dry run: ${notes.size} cards would be added to $deck" else "nothing new")
        } else {
            val (css, front, back) = creator.packageStyling(style)
            val model = anki.ensureModel(profile, css, front, back)
            anki.ensureDeck(deck)
            var added = 0
            for ((word, fields, tags) in notes) {
                val error = anki.addNote(deck, model, fields, tags)
                if (error == null) added++ else log("not added: $word — $error")
            }
            log("added $added of ${notes.size} cards to $deck (note type $model)")
        }

        val file = File(outDir, "kindle-sync.tsv")
        file.writeText(report.toString())
        // A single line the shell wrapper turns into the desktop notification.
        File(outDir, "summary.txt").writeText(
            "lookups=${lookups.size} new=${notes.size} in_anki=${summary["IN_ANKI"] ?: 0} " +
                "not_found=${summary["NOT_IN_DICTIONARY"] ?: 0} dry_run=$dryRun\n"
        )
        log("report written to ${file.absolutePath}")
        println(report)
    }

    /**
     * The sentence the word was looked up in. Kindle's `usage` is a window of
     * text, not a sentence — the first lookup of a book drags the title page
     * and the chapter heading along — so it is cut on sentence ends and the
     * piece holding the word wins.
     */
    private fun sentenceFor(lookup: Lookup, entry: MergedWordEntry?): String {
        val usage = lookup.usage.trim { it.isWhitespace() || it == '　' }
        val pieces = SENTENCE_END.findAll(usage).map { it.value.trim { c -> c.isWhitespace() || c == '　' } }
            .filter { it.isNotEmpty() }.toList()
        val tokens = listOfNotNull(entry?.primaryExpression, entry?.reading, lookup.word).filter { it.isNotEmpty() }
        return pieces.firstOrNull { SentenceContextHighlighter.containsTarget(it, tokens) }
            ?: pieces.firstOrNull { lookup.word in it }
            ?: usage
    }

    /**
     * Kindle's `stem` for a word its dictionary does not know is the reading
     * of whatever headword it matched at the start — しんぞく for 親族会議,
     * but also ぶり for ブリッヂオ. Taken as a reading it can land anywhere
     * (振り), so an entry reached through it only counts when it is written
     * at the start of what the reader selected, and is more than one character.
     */
    private fun isPrefixOf(entry: MergedWordEntry, word: String): Boolean =
        (listOf(entry.primaryExpression) + entry.alternativeExpressions)
            .any { it.length >= 2 && word.startsWith(it) }

    private fun slug(title: String): String =
        title.trim().replace(Regex("[\\s　]+"), "_").replace(Regex("[\"'`]"), "").take(60)

    private fun log(message: String) = println("[kindle-sync] $message")

    /** The few AnkiConnect actions this needs. */
    private class AnkiConnect(private val endpoint: String) {

        fun call(action: String, params: JSONObject = JSONObject()): Any? {
            val body = JSONObject().put("action", action).put("version", 6).put("params", params)
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 120_000
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val response = JSONObject(connection.inputStream.bufferedReader().readText())
            val error = response.opt("error")
            if (error != null && error != JSONObject.NULL) throw IllegalStateException("$action: $error")
            return response.opt("result")
        }

        /**
         * Every note of the collection through the same indexer the phone's
         * scan uses. Fields are rejoined with Anki's own separator, which is
         * the string [AnkiNoteFieldIndexer.collectKeysFromNote] expects.
         */
        fun collectionIndex(): AnkiCollectionIndex.Index {
            val ids = call("findNotes", JSONObject().put("query", "deck:*")) as JSONArray
            val keys = HashSet<String>(1 shl 14)
            val all = (0 until ids.length()).map { ids.getLong(it) }
            for (chunk in all.chunked(500)) {
                val infos = call("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
                for (i in 0 until infos.length()) {
                    val fields = infos.getJSONObject(i).optJSONObject("fields") ?: continue
                    val ordered = fields.keys().asSequence()
                        .map { fields.getJSONObject(it) }
                        .sortedBy { it.optInt("order") }
                        .joinToString("") { it.optString("value") }
                    AnkiNoteFieldIndexer.collectKeysFromNote(ordered, keys)
                }
            }
            return AnkiCollectionIndex.Index(keys, all.size, available = true)
        }

        /**
         * The profile's note type. An existing one with the right fields is
         * used as it is — its styling may be what the phone synced — and a
         * new one is only created when the collection has none.
         */
        fun ensureModel(profile: CardProfile, css: String, front: String, back: String): String {
            val name = profile.modelName
            val names = call("modelNames") as JSONArray
            if ((0 until names.length()).any { names.getString(it) == name }) {
                val fields = call("modelFieldNames", JSONObject().put("modelName", name)) as JSONArray
                val have = (0 until fields.length()).map { fields.getString(it) }
                check(have == profile.fieldNames.toList()) {
                    "note type $name has fields $have, expected ${profile.fieldNames.toList()}"
                }
                return name
            }
            call(
                "createModel",
                JSONObject()
                    .put("modelName", name)
                    .put("inOrderFields", JSONArray(profile.fieldNames.toList()))
                    .put("css", css)
                    .put(
                        "cardTemplates",
                        JSONArray().put(JSONObject().put("Name", "Card 1").put("Front", front).put("Back", back))
                    )
            )
            return name
        }

        fun ensureDeck(deck: String) {
            call("createDeck", JSONObject().put("deck", deck))
        }

        /** Null when added, otherwise the reason AnkiConnect gave. */
        fun addNote(deck: String, model: String, fields: Map<String, String>, tags: List<String>): String? =
            try {
                call(
                    "addNote",
                    JSONObject().put(
                        "note",
                        JSONObject()
                            .put("deckName", deck)
                            .put("modelName", model)
                            .put("fields", JSONObject(fields))
                            .put("tags", JSONArray(tags))
                    )
                )
                null
            } catch (e: Exception) {
                e.message
            }
    }

    private companion object {
        /**
         * A run of text up to and including its sentence end. Whitespace ends
         * one too: Kindle joins paragraphs with " 　", and the title page and
         * chapter heading that ride along with a book's first lookup are
         * separated by nothing else.
         */
        val SENTENCE_END = Regex("[^。！？!?\\s　]+[。！？!?」』]*")
    }
}
