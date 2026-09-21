package com.yomitanmobile.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.anki.RefreshMerge
import com.yomitanmobile.data.audio.AudioKeys
import com.yomitanmobile.data.audio.KanjiAlive
import com.yomitanmobile.data.audio.NativeAudioIndex
import com.yomitanmobile.data.audio.voicevox.VoicevoxAssets
import com.yomitanmobile.data.audio.voicevox.VoicevoxSpeaker
import com.yomitanmobile.data.anki.AnkiCollectionIndex
import com.yomitanmobile.data.anki.AnkiNoteFieldIndexer
import com.yomitanmobile.data.local.dao.FrequencyUpdate
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.data.settings.readCardStylePreferences
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
 *   -Dpitch.zip=/path/kanjium_pitch_accents.zip \
 *   -Dkanji.zip=/path/KANJIDIC_english.zip \
 *   -Dkindle.settings=/path/settings.json \
 *   -Dvoicevox.root=/path/voicevox/core -Dvoicevox.onnxruntime=/path/libvoicevox_onnxruntime.so \
 *   -Dkindle.deck=test_kindle [-Dkindle.dryRun=true] [-Dout.dir=/path/report]
 * ```
 *
 * Everything past the dictionary is optional and exists to make the card the
 * phone's card: the same pitch and kanji dictionaries the app's catalogue
 * installs, the phone's own card-style settings (the `settings.json` of an app
 * backup), and a recording for the Audio field from [VoicevoxSpeaker] — the
 * class the phone uses for the same field, run here through VOICEVOX's
 * desktop bindings.
 *
 * TSV columns (tab-separated, no header): word, stem, usage, timestamp (ms),
 * book title.
 */
@RunWith(RobolectricTestRunner::class)
// Polish, like the phone: the part-of-speech line and the usage tags on the
// card are localised from the device locale.
@Config(sdk = [33], qualifiers = "pl")
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
        logFile = File(outDir, "run.log").apply { writeText("") }
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
        val dictionary = Dictionary.load(parser, dictZip, freqZip, loadPitch(parser))
        suspend fun collect(expressions: Set<String>, readings: Set<String>): List<WordEntry> =
            dictionary.collect(expressions, readings)
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
        // Sync first: the duplicate check is only as good as the collection it
        // reads, and a card added on the phone since the last sync is exactly
        // the duplicate it would miss. A failure here stops the run — adding
        // blind is the surprise this is meant to prevent.
        val sync = System.getProperty("kindle.sync")?.toBooleanStrictOrNull() ?: true
        if (sync && !dryRun) {
            progress("Synchronizuję z AnkiWeb, żeby sprawdzić, co już masz…")
            anki.call("sync")
            log("synced before adding")
        }
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
        progress(
            "Z ${lookups.size} wyszukań: ${toAdd.size} nowych słów, ${summary["IN_ANKI"] ?: 0} już w Anki" +
                if (toAdd.isEmpty()) "." else " — tworzę fiszki…"
        )

        // ---- 6. cards ---------------------------------------------------------
        val context: Context = ApplicationProvider.getApplicationContext()
        val creator = AnkiCardCreator(context, LanguageSettings(context))
        val profile = CardProfile.JAPANESE
        // Kindle lookups are all about the sentence they were made in, so the
        // front-context slot is on regardless of the card-style setting —
        // the same override the text scanner makes.
        val style = phoneStyle(anki).copy(showFrontContextSentence = true)
        log("style: random fonts ${if (style.randomFontsEnabled) style.randomFonts else "off"}")

        // Kanji breakdown, from the same KANJIDIC the phone installs.
        val kanjiWanted = toAdd.values.flatMapTo(HashSet()) { (entry, _) ->
            entry.primaryExpression.filter { JapaneseTokenizer.isKanji(it) }.map(Char::toString)
        }
        val kanji = HashMap<String, KanjiEntry>()
        System.getProperty("kanji.zip").orEmpty().takeIf { it.isNotEmpty() && kanjiWanted.isNotEmpty() }?.let { zip ->
            parser.parseFromZipStreaming(
                inputStream = File(zip).inputStream().buffered(),
                onBatch = { _, _ -> },
                onKanjiBatch = { batch, _ -> batch.filter { it.kanji in kanjiWanted }.forEach { kanji[it.kanji] = it } }
            )
        }

        if (!dryRun && toAdd.isNotEmpty()) progress("Nagrywam wymowę dla ${toAdd.size} słów…")
        val audio = if (dryRun) emptyMap() else speak(
            toAdd.values.map { (entry, _) -> Spoken(entry.primaryExpression, entry.reading, entry.pitchAccent) },
            outDir
        )
        val notes = toAdd.values.map { (entry, lookup) ->
            val word = entry.toWordEntry().copy(
                exampleSentence = sentenceFor(lookup, entry),
                exampleSentenceTranslation = ""
            )
            val kanjiData = entry.primaryExpression.filter { JapaneseTokenizer.isKanji(it) }
                .map(Char::toString).distinct().mapNotNull { kanji[it] }
            // rebuildFields is the phone's own "every field of a card" path:
            // random font, pitch diagram, kanji breakdown included.
            val fields = profile.fieldNames.zip(creator.rebuildFields(word, style, kanjiData)).toMap().toMutableMap()
            val recording = audio[entry.primaryExpression to entry.reading]
            if (recording != null) fields["Audio"] = "[sound:${recording.name}]"
            val tags = listOf("yomitan-mobile", "from_kindle", slug(lookup.book)).filter { it.isNotEmpty() }
            Note(entry.primaryExpression, fields, tags, recording)
        }

        var addedCount = 0
        val addedNotes = ArrayList<Note>()
        if (dryRun || notes.isEmpty()) {
            log(if (dryRun) "dry run: ${notes.size} cards would be added to $deck" else "nothing new")
        } else {
            progress("Dodaję ${notes.size} fiszek do talii $deck…")
            val (css, front, back) = creator.packageStyling(style)
            val model = anki.ensureModel(profile, css, front, back)
            anki.ensureDeck(deck)
            var added = 0
            for (note in notes) {
                note.recording?.let { anki.storeMedia(it) }
                val error = anki.addNote(deck, model, note.fields, note.tags)
                if (error == null) {
                    added++
                    addedNotes += note
                } else {
                    log("not added: ${note.word} — $error")
                }
            }
            log("added $added of ${notes.size} cards to $deck (note type $model)")
            addedCount = added
        }

        // AutoReorder's own pass, then the second sync so the new cards reach
        // the phone already in study order.
        var reordered = -1
        // Whether AutoReorder's search covers the deck just written to: "na"
        // when the add-on is absent. A deck outside its `search_to_sort` is
        // the silent half of this tool — the cards are added, the order is
        // never applied to them, and nothing says so.
        var reorderCovers = "na"
        // Only a sync that was needed and failed is worth a warning.
        var syncedAfter = true
        if (!dryRun && addedCount > 0) {
            reorderConfig()?.let { config ->
                val mine = anki.countCards("\"deck:$deck\" is:new")
                val covered = anki.countCards("\"deck:$deck\" is:new (${config.search})")
                reorderCovers = (mine > 0 && covered == mine).toString()
                if (reorderCovers == "true") {
                    progress("Układam kolejność nowych kart (AutoReorder)…")
                    reordered = anki.reorder(config)
                    log("reorder (${config.search}, by ${config.field}): $reordered cards moved")
                } else {
                    // Reordering anyway would be worse than doing nothing:
                    // shift_existing moves every OTHER new card — these ones —
                    // behind the whole sorted queue.
                    log(
                        "not reordering: AutoReorder sorts \"${config.search}\", which covers " +
                            "$covered of $mine new cards in $deck. Put the deck inside that search."
                    )
                }
            }
            if (sync) {
                progress("Synchronizuję z AnkiWeb…")
                syncedAfter = runCatching { anki.call("sync") }
                    .onFailure { log("sync after adding failed: ${it.message}") }
                    .isSuccess
            }
        }

        // The duplicate check above ran against the collection as it was
        // after the FIRST sync. A word the phone mined and sent in the
        // meantime arrives with the second one, and only now can the two be
        // seen side by side. Reported, never deleted: both cards may already
        // be on the phone, and which one to keep is the user's call.
        val duplicatesAfter = if (!dryRun && sync && syncedAfter && addedNotes.isNotEmpty()) {
            anki.duplicatesOf(addedNotes.map { it.word to plain(it.fields["Reading"].orEmpty()) })
                .onEach { log("duplicate after sync: $it — the phone added it during this run") }
        } else {
            emptyList()
        }

        val file = File(outDir, "kindle-sync.tsv")
        file.writeText(report.toString())
        // A single line the shell wrapper turns into the desktop notification.
        File(outDir, "summary.txt").writeText(
            "lookups=${lookups.size} new=${notes.size} added=$addedCount in_anki=${summary["IN_ANKI"] ?: 0} " +
                "not_found=${summary["NOT_IN_DICTIONARY"] ?: 0} reordered=$reordered " +
                "reorder_covers=$reorderCovers synced_after=$syncedAfter " +
                "duplicates_after=${duplicatesAfter.size} dry_run=$dryRun\n"
        )
        log("report written to ${file.absolutePath}")
        println(report)
    }

    /** What AutoReorder is configured to do, read from the add-on itself. */
    private data class ReorderConfig(
        val search: String,
        val field: String,
        val reverse: Boolean,
        val shiftExisting: Boolean
    )

    /**
     * AutoReorder's settings, from the add-on's `meta.json` (where Anki keeps
     * the user's edits) over its `config.json` (the shipped defaults). Null
     * when the add-on is absent or disabled — then nothing is reordered,
     * exactly as Anki itself would not.
     */
    private fun reorderConfig(): ReorderConfig? {
        val dir = System.getProperty("anki.reorderAddon").orEmpty().takeIf { it.isNotEmpty() }?.let(::File)
            ?: return null
        val meta = File(dir, "meta.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
        if (meta.optBoolean("disabled", false)) return null
        val defaults = File(dir, "config.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
        val user = meta.optJSONObject("config") ?: JSONObject()
        fun value(key: String): Any? = if (user.has(key)) user.get(key) else defaults.opt(key)
        val search = value("search_to_sort") as? String ?: return null
        return ReorderConfig(
            search = search,
            field = value("sort_field") as? String ?: "Frequency",
            reverse = value("sort_reverse") as? Boolean ?: false,
            shiftExisting = value("shift_existing") as? Boolean ?: true
        )
    }

    private data class Note(
        val word: String,
        val fields: Map<String, String>,
        val tags: List<String>,
        val recording: File?
    )

    /**
     * The phone's card style. An app backup's `settings.json` is the real
     * thing — it is read through [readCardStylePreferences], the one path the
     * phone itself uses, so every validation on it applies here too. Without
     * one, the fonts are read off the cards the phone wrote most recently:
     * random fonts are the only style setting that lands in a note's FIELDS
     * (everything else is the note type's CSS, which the phone keeps in sync
     * itself and this tool does not touch).
     */
    private fun phoneStyle(anki: AnkiConnect): CardStylePreferences {
        val settings = System.getProperty("kindle.settings").orEmpty().let(::File)
        if (settings.isFile) {
            log("style: from ${settings.path}")
            return readCardStylePreferences(preferencesFromBackup(settings.readText()))
        }
        val fonts = anki.recentFrontFonts()
        return if (fonts.isEmpty()) CardStylePreferences()
        else CardStylePreferences(randomFontsEnabled = true, randomFonts = fonts)
    }

    /** BackupManager's `{ key: { "t": type, "v": value } }` back into DataStore preferences. */
    private fun preferencesFromBackup(text: String): Preferences {
        val root = JSONObject(text)
        val prefs = mutablePreferencesOf()
        for (name in root.keys()) {
            val entry = root.optJSONObject(name) ?: continue
            runCatching {
                when (entry.optString("t")) {
                    "bool" -> prefs[booleanPreferencesKey(name)] = entry.getBoolean("v")
                    "int" -> prefs[intPreferencesKey(name)] = entry.getInt("v")
                    "long" -> prefs[longPreferencesKey(name)] = entry.getLong("v")
                    "float" -> prefs[floatPreferencesKey(name)] = entry.getDouble("v").toFloat()
                    "double" -> prefs[doublePreferencesKey(name)] = entry.getDouble("v")
                    "string" -> prefs[stringPreferencesKey(name)] = entry.getString("v")
                    "stringset" -> prefs[stringSetPreferencesKey(name)] =
                        entry.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                }
            }
        }
        return prefs
    }

    /**
     * Re-records the Audio field of every card this tool made (`tag:from_kindle`)
     * with the current engine, in place: `updateNoteFields` keeps the note,
     * its cards and their review history. For when the voice gets better —
     * Open JTalk → VOICEVOX, or a pronunciation archive later.
     *
     * ```
     * tools/kindle-sync/kindle-sync.sh --refresh-audio
     * ```
     */
    @Test
    fun refreshAudio() = runBlocking {
        Assume.assumeTrue(
            "kindle-sync: pass -Dkindle.refreshAudio=true",
            System.getProperty("kindle.refreshAudio") == "true"
        )
        val anki = AnkiConnect(System.getProperty("anki.connect") ?: "http://127.0.0.1:8765")
        val outDir = File(System.getProperty("out.dir") ?: "build/kindle-sync").apply { mkdirs() }
        logFile = File(outDir, "run.log").apply { writeText("") }
        val sync = System.getProperty("kindle.sync")?.toBooleanStrictOrNull() ?: true
        if (sync) anki.call("sync")

        val infos = anki.call("notesInfo", JSONObject().put("query", "tag:from_kindle")) as JSONArray
        val notes = (0 until infos.length()).map { infos.getJSONObject(it) }.map { info ->
            val fields = info.getJSONObject("fields")
            fun field(name: String) = fields.optJSONObject(name)?.optString("value").orEmpty()
            // Front carries the random-font span; the word is its text.
            Triple(info.getLong("noteId"), TAGS.replace(field("Front"), "").trim(), TAGS.replace(field("Reading"), "").trim())
        }
        val pitch = loadPitch(YomitanDictionaryParser())
        val audio = speak(notes.map { (_, word, reading) -> Spoken(word, reading, pitch[word].orEmpty()) }, outDir)
        var refreshed = 0
        for ((id, word, reading) in notes) {
            val recording = audio[word to reading] ?: continue
            anki.storeMedia(recording)
            anki.call(
                "updateNoteFields",
                JSONObject().put("note", JSONObject().put("id", id).put("fields", JSONObject().put("Audio", "[sound:${recording.name}]")))
            )
            refreshed++
        }
        val syncedAfter = !sync || runCatching { anki.call("sync") }.isSuccess
        log("audio refreshed on $refreshed of ${notes.size} notes")
        File(outDir, "summary.txt").writeText("refreshed=$refreshed notes=${notes.size} synced_after=$syncedAfter\n")
    }

    /**
     * Brings every note of the current note type up to what the app writes
     * today, in place: `updateNoteFields` keeps the note, its cards and their
     * review history. What it is for is the collection the app's older
     * versions left behind — a tier label ("★★★ Top 1K") where the Frequency
     * field should hold a rank, no pitch diagram, no kanji breakdown.
     *
     * The field rule is [RefreshMerge]'s, the same one the phone's refresh
     * uses: the front, the mined sentence and the AI summary are the note's and
     * are kept; a label in Frequency is replaced by the rank, or cleared.
     * A note whose word the dictionary does not list WITH ITS READING is left
     * alone entirely — a homophone's definition is worse than an old card.
     *
     * The front-context slot is not filled on cards that never had one: a
     * sentence appearing on the front of a card already in review changes the
     * question it asks, which is not what a refresh is for.
     *
     * ```
     * tools/kindle-sync/kindle-sync.sh --refresh-cards [--dry-run]
     * ```
     * A dry run writes `refresh.tsv` (every note, what would change) and
     * `refresh-sample.tsv` (full before/after of a few notes) and touches nothing.
     */
    @Test
    fun refreshCards() = runBlocking {
        Assume.assumeTrue(
            "kindle-sync: pass -Dkindle.refreshCards=true",
            System.getProperty("kindle.refreshCards") == "true"
        )
        val dictZip = System.getProperty("dict.zip").orEmpty()
        check(dictZip.isNotEmpty()) { "pass -Ddict.zip" }
        val dryRun = System.getProperty("kindle.dryRun")?.toBooleanStrictOrNull() ?: false
        val sync = System.getProperty("kindle.sync")?.toBooleanStrictOrNull() ?: true
        val outDir = File(System.getProperty("out.dir") ?: "build/kindle-sync").apply { mkdirs() }
        logFile = File(outDir, "run.log").apply { writeText("") }
        val anki = AnkiConnect(System.getProperty("anki.connect") ?: "http://127.0.0.1:8765")
        val profile = CardProfile.JAPANESE
        if (sync && !dryRun) anki.call("sync")

        // ---- the notes --------------------------------------------------------
        val ids = anki.call("findNotes", JSONObject().put("query", "\"note:${profile.modelName}\"")) as JSONArray
        val notes = (0 until ids.length()).map { ids.getLong(it) }.chunked(500).flatMap { chunk ->
            val infos = anki.call("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
            (0 until infos.length()).map { i ->
                val info = infos.getJSONObject(i)
                val fields = info.getJSONObject("fields")
                val ordered = fields.keys().asSequence()
                    .map { it to fields.getJSONObject(it) }
                    .sortedBy { it.second.optInt("order") }
                    .associateTo(LinkedHashMap()) { (name, f) -> name to f.optString("value") }
                info.getLong("noteId") to ordered
            }
        }
        log("notes: ${notes.size} of ${profile.modelName}")

        // ---- their dictionary entries -----------------------------------------
        val parser = YomitanDictionaryParser()
        val dictionary = Dictionary.load(parser, dictZip, System.getProperty("freq.zip").orEmpty(), loadPitch(parser))
        val words = notes.map { (_, f) -> plain(f["Front"].orEmpty()) }.filter { it.isNotEmpty() }.toSet()
        val entries = dictionary.collect(words, emptySet()).groupBy { it.expression to it.reading }

        val style = phoneStyle(anki).copy(showFrontContextSentence = false)
        val context: Context = ApplicationProvider.getApplicationContext()
        val creator = AnkiCardCreator(context, LanguageSettings(context))

        val kanjiWanted = words.flatMapTo(HashSet()) { w -> w.filter { JapaneseTokenizer.isKanji(it) }.map(Char::toString) }
        val kanji = HashMap<String, KanjiEntry>()
        System.getProperty("kanji.zip").orEmpty().takeIf { it.isNotEmpty() }?.let { zip ->
            parser.parseFromZipStreaming(
                inputStream = File(zip).inputStream().buffered(),
                onBatch = { _, _ -> },
                onKanjiBatch = { batch, _ -> batch.filter { it.kanji in kanjiWanted }.forEach { kanji[it.kanji] = it } }
            )
        }

        // ---- decide ------------------------------------------------------------
        data class Plan(val id: Long, val word: String, val reading: String, val status: String,
                        val before: Map<String, String>, val after: Map<String, String>,
                        /** The accent positions, for the voice — not the diagram in the field. */
                        val pitch: String = "") {
            val changed get() = after.filter { (k, v) -> before[k] != v }.keys
        }
        // Words the user asked to keep exactly as they are — a meaning written
        // in English by hand is invisible to RefreshMerge's own test.
        val excluded = System.getProperty("kindle.refreshExclude").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val plans = notes.map { (id, current) ->
            val word = plain(current["Front"].orEmpty())
            val reading = plain(current["Reading"].orEmpty())
            if (word in excluded) return@map Plan(id, word, reading, "EXCLUDED", current, current)
            val found = entries[word to reading] ?: if (reading.isEmpty()) {
                entries.filterKeys { it.first == word }.values.flatten().takeIf { it.isNotEmpty() }
            } else null
            if (word.isEmpty() || found == null) {
                return@map Plan(id, word, reading, "NOT_IN_DICTIONARY", current, current)
            }
            val merged = MergedWordEntry.mergeEntries(found).first()
            val kanjiData = merged.primaryExpression.filter { JapaneseTokenizer.isKanji(it) }
                .map(Char::toString).distinct().mapNotNull { kanji[it] }
            val rebuilt = profile.fieldNames.zip(creator.rebuildFields(merged.toWordEntry(), style, kanjiData)).toMap()
            Plan(id, word, reading, "FOUND", current, RefreshMerge.merge(current, rebuilt), merged.pitchAccent)
        }

        // Audio only where a card has none: a recording already on a card is
        // the user's (an archive file, an earlier voice) and --refresh-audio
        // exists for replacing those on purpose.
        val needAudio = plans.filter { it.status == "FOUND" && it.after["Audio"].isNullOrBlank() }
        val audio = if (dryRun || needAudio.isEmpty()) emptyMap() else speak(
            needAudio.map { Spoken(it.word, it.reading, it.pitch) },
            outDir
        )
        val final = plans.map { plan ->
            val rec = audio[plan.word to plan.reading] ?: return@map plan
            plan.copy(after = LinkedHashMap(plan.after).apply { put("Audio", "[sound:${rec.name}]") })
        }

        // ---- report ------------------------------------------------------------
        val fieldCounts = final.flatMap { it.changed }.groupingBy { it }.eachCount()
        val labelsBefore = final.count { !RefreshMerge.isPlainRank(it.before["Frequency"].orEmpty()) && it.before["Frequency"].orEmpty().isNotBlank() }
        val labelsAfter = final.count { !RefreshMerge.isPlainRank(it.after["Frequency"].orEmpty()) && it.after["Frequency"].orEmpty().isNotBlank() }
        val toWrite = final.filter { it.changed.isNotEmpty() }
        File(outDir, "refresh.tsv").writeText(buildString {
            appendLine("note\tstatus\tword\treading\tfrequency before\tfrequency after\tchanged fields")
            for (p in final) appendLine("${p.id}\t${p.status}\t${p.word}\t${p.reading}\t${p.before["Frequency"]}\t${p.after["Frequency"]}\t${p.changed.joinToString(",")}")
        })
        File(outDir, "refresh-sample.tsv").writeText(buildString {
            appendLine("note\tword\tfield\tbefore\tafter")
            for (p in toWrite.shuffled(java.util.Random(7)).take(12)) for (f in p.changed) {
                appendLine("${p.id}\t${p.word}\t$f\t${p.before[f].orEmpty().replace('\t', ' ').replace('\n', ' ')}\t${p.after[f].orEmpty().replace('\t', ' ').replace('\n', ' ')}")
            }
        })
        // Everything a reviewer needs to judge a change, not only the dozen above.
        File(outDir, "refresh-full.json").writeText(JSONArray().apply {
            for (p in toWrite) put(JSONObject().put("id", p.id).put("word", p.word).put("reading", p.reading)
                .put("before", JSONObject(p.changed.associateWith { p.before[it].orEmpty() }))
                .put("after", JSONObject(p.changed.associateWith { p.after[it].orEmpty() })))
        }.toString())
        final.filter { it.status == "NOT_IN_DICTIONARY" }.forEach { log("skipped, not in the dictionary with its reading: ${it.word} (${it.reading})") }
        final.filter { it.status == "EXCLUDED" }.forEach { log("excluded on request: ${it.word}") }
        log("found ${final.count { it.status == "FOUND" }}, not in dictionary ${final.count { it.status != "FOUND" }}")
        log("would change ${toWrite.size} notes; fields: $fieldCounts")
        log("frequency labels: $labelsBefore before, $labelsAfter after")

        // ---- write -------------------------------------------------------------
        var updated = 0
        var verified = 0
        if (!dryRun) {
            audio.values.forEach { anki.storeMedia(it) }
            for (chunk in toWrite.chunked(100)) {
                val actions = JSONArray()
                for (p in chunk) {
                    val fields = JSONObject()
                    for (f in p.changed) fields.put(f, p.after[f].orEmpty())
                    actions.put(JSONObject().put("action", "updateNoteFields")
                        .put("params", JSONObject().put("note", JSONObject().put("id", p.id).put("fields", fields))))
                }
                val results = anki.call("multi", JSONObject().put("actions", actions)) as JSONArray
                for (i in 0 until results.length()) {
                    val r = results.opt(i)
                    val error = (r as? JSONObject)?.opt("error")
                    if (error == null || error == JSONObject.NULL) updated++ else log("not updated: ${chunk[i].word} — $error")
                }
            }
            // Read back what was written: the result of a multi says the call
            // ran, not that the note now holds what was meant.
            for (chunk in toWrite.chunked(500)) {
                val infos = anki.call("notesInfo", JSONObject().put("notes", JSONArray(chunk.map { it.id }))) as JSONArray
                for (i in 0 until infos.length()) {
                    val fields = infos.getJSONObject(i).getJSONObject("fields")
                    val p = chunk[i]
                    if (p.changed.all { f -> fields.optJSONObject(f)?.optString("value") == p.after[f] }) verified++
                    else log("verify failed: ${p.word}")
                }
            }
            log("updated $updated of ${toWrite.size}, verified $verified")
        }
        val syncedAfter = dryRun || !sync || updated == 0 || runCatching { anki.call("sync") }.isSuccess
        File(outDir, "summary.txt").writeText(
            "notes=${final.size} found=${final.count { it.status == "FOUND" }} " +
                "not_found=${final.count { it.status != "FOUND" }} to_change=${toWrite.size} " +
                "updated=$updated verified=$verified labels_before=$labelsBefore labels_after=$labelsAfter " +
                "audio_added=${audio.size} synced_after=$syncedAfter dry_run=$dryRun\n"
        )
    }

    /**
     * The note type's look brought up to what the app writes today: CSS and
     * templates from [AnkiCardCreator.packageStyling] with the phone's style,
     * the same call a new note type is created with. Notes are not touched;
     * changing a template's text is not a schema change, so a normal sync
     * carries it. Then AutoReorder's pass, because a refresh that turned tier
     * labels into ranks leaves the new-card queue in the old order.
     *
     * ```
     * tools/kindle-sync/kindle-sync.sh --restyle [--dry-run]
     * ```
     * The dry run writes the current and the new CSS/templates to the out dir.
     */
    @Test
    fun restyle() = runBlocking {
        Assume.assumeTrue("kindle-sync: pass -Dkindle.restyle=true", System.getProperty("kindle.restyle") == "true")
        val dryRun = System.getProperty("kindle.dryRun")?.toBooleanStrictOrNull() ?: false
        val sync = System.getProperty("kindle.sync")?.toBooleanStrictOrNull() ?: true
        val outDir = File(System.getProperty("out.dir") ?: "build/kindle-sync").apply { mkdirs() }
        logFile = File(outDir, "run.log").apply { writeText("") }
        val anki = AnkiConnect(System.getProperty("anki.connect") ?: "http://127.0.0.1:8765")
        val profile = CardProfile.JAPANESE
        val model = profile.modelName

        val context: Context = ApplicationProvider.getApplicationContext()
        val creator = AnkiCardCreator(context, LanguageSettings(context))
        val (css, front, back) = creator.packageStyling(phoneStyle(anki))

        val currentCss = (anki.call("modelStyling", JSONObject().put("modelName", model)) as JSONObject).optString("css")
        val templates = anki.call("modelTemplates", JSONObject().put("modelName", model)) as JSONObject
        check(templates.length() == 1) { "$model has ${templates.length()} templates, expected one" }
        val cardName = templates.keys().next()
        val current = templates.getJSONObject(cardName)
        File(outDir, "restyle-current.css").writeText(currentCss)
        File(outDir, "restyle-current-front.html").writeText(current.optString("Front"))
        File(outDir, "restyle-current-back.html").writeText(current.optString("Back"))
        File(outDir, "restyle-new.css").writeText(css)
        File(outDir, "restyle-new-front.html").writeText(front)
        File(outDir, "restyle-new-back.html").writeText(back)

        // A template naming a field the type lacks prints "{{Field}}" on the card.
        val fields = (anki.call("modelFieldNames", JSONObject().put("modelName", model)) as JSONArray)
            .let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val referenced = FIELD_REF.findAll(front + back).map { it.groupValues[1].substringAfterLast(':').trim() }
            .filter { it.isNotEmpty() && it !in SPECIAL_FIELDS }.toSet()
        val unknown = referenced - fields
        check(unknown.isEmpty()) { "templates name fields $model does not have: $unknown" }

        val cssChanged = css != currentCss
        val frontChanged = front != current.optString("Front")
        val backChanged = back != current.optString("Back")
        log("css ${currentCss.length} → ${css.length} chars (${if (cssChanged) "changed" else "same"}), " +
            "front ${if (frontChanged) "changed" else "same"}, back ${if (backChanged) "changed" else "same"}; " +
            "fields referenced: ${referenced.sorted()}")

        var verified = false
        var reordered = -1
        if (!dryRun) {
            anki.call("updateModelStyling", JSONObject().put("model", JSONObject().put("name", model).put("css", css)))
            anki.call("updateModelTemplates", JSONObject().put("model", JSONObject().put("name", model)
                .put("templates", JSONObject().put(cardName, JSONObject().put("Front", front).put("Back", back)))))
            val nowCss = (anki.call("modelStyling", JSONObject().put("modelName", model)) as JSONObject).optString("css")
            val now = (anki.call("modelTemplates", JSONObject().put("modelName", model)) as JSONObject).getJSONObject(cardName)
            verified = nowCss == css && now.optString("Front") == front && now.optString("Back") == back
            log("written, read back ${if (verified) "equal" else "DIFFERENT"}")
            reorderConfig()?.let { config ->
                reordered = anki.reorder(config)
                log("reorder (${config.search}, by ${config.field}): $reordered cards moved")
            }
        }
        val syncedAfter = dryRun || !sync || runCatching { anki.call("sync") }.isSuccess
        File(outDir, "summary.txt").writeText(
            "css_changed=$cssChanged front_changed=$frontChanged back_changed=$backChanged " +
                "verified=$verified reordered=$reordered synced_after=$syncedAfter dry_run=$dryRun\n"
        )
    }

    private fun plain(html: String): String = TAGS.replace(html.replace(Regex("\\[sound:[^]]*]"), ""), "")
        .replace("&nbsp;", " ").trim()

    /**
     * Read-only: which notes matching an Anki search have a word some OTHER
     * note holds too — the same rule the post-sync check uses.
     *
     * ```
     * ./gradlew :app:testDebugUnitTest --tests "*KindleSync.checkDuplicates" \
     *   -Dkindle.checkDuplicates='tag:from_kindle'
     * ```
     */
    @Test
    fun checkDuplicates() {
        val query = System.getProperty("kindle.checkDuplicates").orEmpty()
        Assume.assumeTrue("kindle-sync: pass -Dkindle.checkDuplicates=<anki search>", query.isNotEmpty())
        val outDir = File(System.getProperty("out.dir") ?: "build/kindle-sync").apply { mkdirs() }
        logFile = File(outDir, "run.log").apply { writeText("") }
        val anki = AnkiConnect(System.getProperty("anki.connect") ?: "http://127.0.0.1:8765")
        val ids = anki.call("findNotes", JSONObject().put("query", query)) as JSONArray
        val words = (0 until ids.length()).map { ids.getLong(it) }.chunked(500).flatMap { chunk ->
            val infos = anki.call("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
            (0 until infos.length()).map { i ->
                val fields = infos.getJSONObject(i).getJSONObject("fields")
                plain(fields.optJSONObject("Front")?.optString("value").orEmpty()) to
                    plain(fields.optJSONObject("Reading")?.optString("value").orEmpty())
            }
        }.distinct()
        val duplicates = anki.duplicatesOf(words)
        log("checked ${words.size} words matching '$query': ${duplicates.size} held by more than one note ${duplicates}")
        File(outDir, "summary.txt").writeText("checked=${words.size} duplicates=${duplicates.size}\n")
    }

    /**
     * Pitch positions by written form, as DictionaryDao's updatePitchAccent
     * stores them on the phone.
     */
    private suspend fun loadPitch(parser: YomitanDictionaryParser): Map<String, String> {
        val pitch = HashMap<String, String>()
        System.getProperty("pitch.zip").orEmpty().takeIf { it.isNotEmpty() }?.let { zip ->
            parser.parseFromZipStreaming(
                inputStream = File(zip).inputStream().buffered(),
                onBatch = { _, _ -> },
                onMetaBatch = { _, pitches -> pitch.putAll(pitches) }
            )
        }
        return pitch
    }

    /**
     * The term dictionary, the frequency list and the pitch dictionary, read
     * the way the phone stores them — shared by the sync and the refresh so a
     * refreshed card is built from exactly the data a new one would be.
     */
    private class Dictionary private constructor(
        private val parser: YomitanDictionaryParser,
        private val dictZip: String,
        private val ranks: Map<String, List<Pair<String, Int>>>,
        /** Pitch by written form, as DictionaryDao's updatePitchAccent keys it. */
        private val pitch: Map<String, String>
    ) {
        fun rankOf(expression: String, reading: String): Int =
            ranks[expression].orEmpty()
                .filter { it.first.isEmpty() || it.first == reading }
                .minOfOrNull { it.second } ?: 0

        /** Every entry whose written form is in [expressions] or reading in [readings]. */
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
                        val domain = entry.copy(frequency = rank).toDomain()
                        out += domain.copy(
                            frequencyValue = if (rank > 0) rank.toString() else "",
                            pitchAccent = domain.pitchAccent.ifBlank { pitch[entry.expression].orEmpty() }
                        )
                    }
                }
            )
            return out
        }

        companion object {
            suspend fun load(
                parser: YomitanDictionaryParser,
                dictZip: String,
                freqZip: String,
                pitch: Map<String, String>
            ): Dictionary {
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
                return Dictionary(parser, dictZip, ranks, pitch)
            }
        }
    }

    /** What the TTS is asked to say: the reading, with the accent to say it in. */
    private data class Spoken(val expression: String, val reading: String, val pitch: String)

    /**
     * The user's pronunciation archive, indexed the way the phone indexes it:
     * [AudioKeys] reads each path, and a lookup takes the best priority (a
     * spelling+reading pair, then a spelling, then a bare reading), ties to
     * the first file walked — `ORDER BY priority, id` in AudioFileDao.
     */
    private class Archive(root: File) {
        private val best = HashMap<String, Pair<Int, File>>()
        val fileCount: Int

        init {
            var files = 0
            root.walkTopDown()
                .filter { it.isFile && AUDIO_EXTENSIONS.any { ext -> it.name.endsWith(ext, ignoreCase = true) } }
                .forEach { file ->
                    files++
                    for ((key, priority) in AudioKeys.keysFor(file.name, file.parentFile?.name.orEmpty())) {
                        val existing = best[key]
                        if (existing == null || priority < existing.first) best[key] = priority to file
                    }
                }
            fileCount = files
        }

        fun find(expression: String, reading: String): File? =
            AudioKeys.lookupKeys(expression, reading).mapNotNull { best[it] }.minByOrNull { it.first }?.second
    }

    private val archive: Archive? by lazy {
        System.getProperty("audio.archive").orEmpty().let(::File).takeIf { it.isDirectory }
            ?.let(::Archive)?.also { log("audio archive: ${it.fileCount} files") }
    }

    /**
     * One recording per word, keyed by (expression, reading): the user's
     * archive when it has the word — a native speaker beats any synthesiser —
     * and TTS otherwise. The file name is derived from the word and the
     * source, so a rerun reuses what Anki already holds, while a better source
     * gets a new name — AnkiDroid caches media by name and would keep playing
     * the old recording.
     */
    private fun speak(entries: List<Spoken>, outDir: File): Map<Pair<String, String>, File> {
        if (entries.isEmpty()) return emptyMap()
        val dir = File(outDir, "audio").apply { mkdirs() }
        val byKey = entries.associateBy { it.expression to it.reading }
        fun target(key: Pair<String, String>, source: String): File {
            val hash = java.security.MessageDigest.getInstance("SHA-1")
                .digest("${key.first}|${key.second}".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
            return File(dir, "yomitan_kindle_${source}_$hash.mp3")
        }

        val result = HashMap<Pair<String, String>, File>()
        var fromArchive = 0
        var fromNative = 0
        for (key in byKey.keys) {
            // The phone's order (AudioArchive.find): the user's own folder,
            // then the native-speaker pack, and only then a synthesised voice.
            val (recording, source) = archive?.find(key.first, key.second)?.let { it to ARCHIVE_SOURCE }
                ?: nativeRecording(key.first, key.second)?.let { it to NATIVE_SOURCE }
                ?: continue
            val out = target(key, source)
            // Same loudness as the TTS files, so the two kinds sit side by side.
            if (out.isFile || ffmpeg(recording, out)) {
                result[key] = out
                if (source == ARCHIVE_SOURCE) fromArchive++ else fromNative++
            }
        }
        if (archive != null) log("audio: $fromArchive of ${byKey.size} words from the archive")
        if (nativeIndex != null) log("audio: $fromNative of ${byKey.size} words from native speakers (${KanjiAlive.CREDIT})")

        val wanted = (byKey.keys - result.keys).associateWith { target(it, AUDIO_ENGINE) }
        val missing = wanted.filterValues { !it.isFile }
        if (missing.isNotEmpty()) {
            val speaker = speaker()
            if (speaker == null) {
                log("audio: VOICEVOX not installed, ${missing.size} words go without a recording")
            } else {
                for ((key, file) in missing) {
                    val spoken = byKey.getValue(key)
                    runCatching {
                        val wav = File(dir, file.name + ".wav")
                        wav.writeBytes(speaker.wav(spoken.expression, spoken.reading, spoken.pitch))
                        // Already normalised by the speaker; only encoded here.
                        encodeMp3(wav, file)
                        wav.delete()
                    }.onFailure { log("tts failed for ${key.first}: ${it.message}") }
                }
            }
        }
        result += wanted.filterValues { it.isFile }
        log("audio: ${result.size} of ${byKey.size} words")
        (byKey.keys - result.keys).forEach { log("audio: no recording for ${it.first} (${it.second})") }
        return result
    }

    /** The native-speaker pack, when installed (kindle-sync.sh --install-native-audio). */
    private val nativeRoot: File? by lazy {
        System.getProperty("native.audio").orEmpty().let(::File).takeIf { KanjiAlive.isInstalled(it) }
    }
    private val nativeIndex: NativeAudioIndex? by lazy { nativeRoot?.let { KanjiAlive.loadIndex(it) } }

    private fun nativeRecording(expression: String, reading: String): File? {
        val root = nativeRoot ?: return null
        val name = nativeIndex?.find(expression, reading) ?: return null
        return KanjiAlive.file(root, name).takeIf { it.isFile }
    }

    /**
     * Installs the native-speaker pack on the desktop — the same install the
     * phone runs, SHA-256 pins included — so Kindle cards get the recordings
     * phone cards get.
     *
     * ```
     * tools/kindle-sync/kindle-sync.sh --install-native-audio
     * ```
     */
    @Test
    fun installNativeAudio() {
        val root = System.getProperty("native.audio").orEmpty()
        Assume.assumeTrue(
            "kindle-sync: pass -Dkindle.installNativeAudio=true -Dnative.audio=<dir>",
            System.getProperty("kindle.installNativeAudio") == "true" && root.isNotEmpty()
        )
        val outDir = File(System.getProperty("out.dir") ?: "build/kindle-sync").apply { mkdirs() }
        logFile = File(outDir, "run.log").apply { writeText("") }
        var lastMb = -1L
        KanjiAlive.install(
            File(root),
            open = { url ->
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "YomitanMobile-KindleSync/1.0")
                }.inputStream
            },
            onProgress = { done, total ->
                val mb = done / 10_000_000
                if (mb != lastMb) {
                    lastMb = mb
                    progress("Pobieram nagrania native speakerów: ${done / 1_000_000} / ${total / 1_000_000} MB…")
                }
            }
        )
        val index = KanjiAlive.loadIndex(File(root))
        log("native recordings installed in $root: ${index?.size ?: 0} keys")
        File(outDir, "summary.txt").writeText("installed=${index != null} keys=${index?.size ?: 0}\n")
    }

    private var voicevox: VoicevoxSpeaker? = null

    /** Built once per run: loading the models is the slow part. */
    private fun speaker(): VoicevoxSpeaker? {
        voicevox?.let { return it }
        val root = System.getProperty("voicevox.root").orEmpty().let(::File)
        val runtime = System.getProperty("voicevox.onnxruntime").orEmpty()
        if (runtime.isEmpty() || !VoicevoxAssets.isInstalled(root)) return null
        return runCatching { VoicevoxSpeaker(runtime, root) }
            .onFailure { log("VOICEVOX failed to load: ${it.message}") }
            .getOrNull()
            .also { voicevox = it }
    }

    private fun encodeMp3(input: File, output: File): Boolean = runCatching {
        ProcessBuilder(
            "ffmpeg", "-v", "error", "-y", "-i", input.absolutePath,
            "-ar", "44100", "-codec:a", "libmp3lame", "-q:a", "3", output.absolutePath
        ).redirectErrorStream(true).start().let { process ->
            process.inputStream.readBytes()
            process.waitFor() == 0 && output.isFile
        }
    }.getOrDefault(false)

    private fun ffmpeg(input: File, output: File): Boolean = runCatching {
        ProcessBuilder(
            "ffmpeg", "-v", "error", "-y", "-i", input.absolutePath,
            "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", "44100", "-ac", "1",
            "-codec:a", "libmp3lame", "-q:a", "3", output.absolutePath
        ).redirectErrorStream(true).start().let { process ->
            process.inputStream.readBytes()
            process.waitFor() == 0 && output.isFile
        }
    }.getOrDefault(false)

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

    /**
     * Gradle does not forward a test's stdout, so every line this class
     * printed died inside the build — including "tts failed for X", which is
     * why a card with no recording had to be diagnosed from the absence of a
     * file. The run's log is written next to its report instead.
     */
    private var logFile: File? = null

    /**
     * A step of the run, in the log and in the desktop notification
     * kindle-sync.sh opened for it (`-Dkindle.notifyFile` holds its id), so
     * the one bubble on screen keeps saying what is happening.
     */
    private fun progress(message: String) {
        log(message)
        val file = System.getProperty("kindle.notifyFile").orEmpty().takeIf { it.isNotEmpty() }?.let(::File) ?: return
        runCatching {
            val id = file.takeIf { it.isFile }?.readText()?.trim().orEmpty()
            val command = mutableListOf("notify-send", "-a", "Kindle → Anki", "-p")
            if (id.isNotEmpty()) command += listOf("-r", id)
            command += listOf("Kindle → Anki", message)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val printed = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && printed.all { it.isDigit() } && printed.isNotEmpty()) file.writeText(printed)
        }
    }

    private fun log(message: String) {
        val line = "[kindle-sync] $message"
        println(line)
        logFile?.runCatching { appendText(line + "\n") }
    }

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

        /**
         * The fonts the phone's random-font setting put on its most recent
         * cards (`<span style="font-family: 'X'">` in Front).
         */
        fun recentFrontFonts(): Set<String> {
            val ids = call("findNotes", JSONObject().put("query", "\"note:${CardProfile.JAPANESE.modelName}*\" -tag:from_kindle -tag:kindle")) as JSONArray
            val recent = (0 until ids.length()).map { ids.getLong(it) }.sorted().takeLast(300)
            if (recent.isEmpty()) return emptySet()
            val infos = call("notesInfo", JSONObject().put("notes", JSONArray(recent))) as JSONArray
            return (0 until infos.length()).flatMapTo(LinkedHashSet()) { i ->
                val front = infos.getJSONObject(i).optJSONObject("fields")?.optJSONObject("Front")?.optString("value").orEmpty()
                FONT.findAll(front).map { it.groupValues[1] }.toList()
            }
        }

        fun storeMedia(file: File) {
            call("storeMediaFile", JSONObject().put("filename", file.name).put("path", file.absolutePath))
        }

        /**
         * AutoReorder's `reorder_cards`, over AnkiConnect. The add-on only runs
         * when Anki starts or from its Tools menu, and AnkiConnect cannot call
         * into another add-on, so the ten lines are restated here — same
         * search, same field, same stable sort (cards in their current order,
         * an empty or non-numeric field sorts last), and the same
         * `reposition_new_cards(start 0, step 1, shift_existing)`. Positions
         * are written with `setSpecificValueOfCard`, which goes through
         * `update_card`, so the change syncs like any other.
         *
         * Returns how many cards changed position; 0 when the order already
         * holds, which is also when the add-on leaves the collection alone.
         */
        fun reorder(config: ReorderConfig): Int {
            val ids = call("findCards", JSONObject().put("query", config.search)) as JSONArray
            val cards = cardsInfo((0 until ids.length()).map { ids.getLong(it) })
                .sortedWith(compareBy<CardPosition> { it.due }.thenBy { it.id })
            val byFrequency = compareBy<CardPosition> { it.frequency(config.field) }
            val sorted = cards.sortedWith(if (config.reverse) byFrequency.reversed() else byFrequency)
            if (sorted.map { it.id } == cards.map { it.id }) return 0

            val updates = ArrayList<Pair<Long, Long>>()
            if (config.shiftExisting) {
                // Anki moves every OTHER new card at or past the start out of the way.
                val others = call("findCards", JSONObject().put("query", "is:new -(${config.search})")) as JSONArray
                cardsInfo((0 until others.length()).map { others.getLong(it) })
                    .filter { it.due >= 0 }
                    .forEach { updates += it.id to it.due + sorted.size }
            }
            sorted.forEachIndexed { position, card ->
                if (card.due != position.toLong()) updates += card.id to position.toLong()
            }
            for (chunk in updates.chunked(200)) {
                val actions = JSONArray()
                for ((card, due) in chunk) {
                    actions.put(
                        JSONObject().put("action", "setSpecificValueOfCard").put(
                            "params",
                            JSONObject().put("card", card).put("keys", JSONArray(listOf("due")))
                                .put("newValues", JSONArray(listOf(due)))
                        )
                    )
                }
                call("multi", JSONObject().put("actions", actions))
            }
            return sorted.withIndex().count { (position, card) -> card.due != position.toLong() }
        }

        /**
         * The words among [words] that more than one note now holds, by the
         * rule every duplicate check here uses: a whole field, indexed by
         * [AnkiNoteFieldIndexer], found through the same search the phone's
         * live check runs.
         */
        fun duplicatesOf(words: List<Pair<String, String>>): List<String> = words.mapNotNull { (word, reading) ->
            val search = AnkiCollectionIndex.liveSearch(listOf(word), reading) ?: return@mapNotNull null
            val ids = call("findNotes", JSONObject().put("query", search)) as JSONArray
            val key = AnkiNoteFieldIndexer.normalizeKey(word)
            val holding = (0 until ids.length()).map { ids.getLong(it) }.chunked(500).sumOf { chunk ->
                val infos = call("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
                (0 until infos.length()).count { i ->
                    val fields = infos.getJSONObject(i).optJSONObject("fields") ?: return@count false
                    val ordered = fields.keys().asSequence().map { fields.getJSONObject(it) }
                        .sortedBy { it.optInt("order") }.joinToString("\u001f") { it.optString("value") }
                    val keys = HashSet<String>()
                    AnkiNoteFieldIndexer.collectKeysFromNote(ordered, keys)
                    key in keys
                }
            }
            word.takeIf { holding > 1 }
        }

        /** How many cards a search matches. */
        fun countCards(query: String): Int =
            (call("findCards", JSONObject().put("query", query)) as JSONArray).length()

        private fun cardsInfo(ids: List<Long>): List<CardPosition> = ids.chunked(500).flatMap { chunk ->
            val infos = call("cardsInfo", JSONObject().put("cards", JSONArray(chunk))) as JSONArray
            (0 until infos.length()).map { i ->
                val info = infos.getJSONObject(i)
                val fields = info.optJSONObject("fields") ?: JSONObject()
                CardPosition(
                    id = info.getLong("cardId"),
                    due = info.getLong("due"),
                    fields = fields.keys().asSequence().associateWith { fields.getJSONObject(it).optString("value") }
                )
            }
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

    private data class CardPosition(val id: Long, val due: Long, val fields: Map<String, String>) {
        /** AutoReorder's get_frequency: the field as an int, anything else last. */
        fun frequency(field: String): Long = fields[field]?.trim()?.toLongOrNull() ?: Long.MAX_VALUE
    }

    private companion object {
        /** Bumped whenever the recordings change voice; part of every file name. */
        const val AUDIO_ENGINE = "vv"
        const val ARCHIVE_SOURCE = "ar"
        const val NATIVE_SOURCE = "na"
        val AUDIO_EXTENSIONS = listOf(".mp3", ".ogg", ".opus", ".m4a", ".aac", ".wav", ".flac")
        val TAGS = Regex("<[^>]*>")

        val FONT = Regex("font-family: '([^']+)'")

        /** `{{Field}}`, `{{#Field}}`, `{{/Field}}`, `{{^Field}}`, `{{furigana:Field}}`. */
        val FIELD_REF = Regex("""\{\{[#/^]?([^}]+)}}""")
        val SPECIAL_FIELDS = setOf("FrontSide", "Tags", "Type", "Deck", "Subdeck", "Card", "CardFlag", "CardID")

        /**
         * A run of text up to and including its sentence end. Whitespace ends
         * one too: Kindle joins paragraphs with " 　", and the title page and
         * chapter heading that ride along with a book's first lookup are
         * separated by nothing else.
         */
        val SENTENCE_END = Regex("[^。！？!?\\s　]+[。！？!?」』]*")
    }
}
