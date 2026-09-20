package com.yomitanmobile.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.audio.AudioKeys
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
        // Pitch is keyed by the written form alone, as DictionaryDao's
        // updatePitchAccent stores it on the phone.
        val pitch = loadPitch(parser)
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
        if (dryRun || notes.isEmpty()) {
            log(if (dryRun) "dry run: ${notes.size} cards would be added to $deck" else "nothing new")
        } else {
            val (css, front, back) = creator.packageStyling(style)
            val model = anki.ensureModel(profile, css, front, back)
            anki.ensureDeck(deck)
            var added = 0
            for (note in notes) {
                note.recording?.let { anki.storeMedia(it) }
                val error = anki.addNote(deck, model, note.fields, note.tags)
                if (error == null) added++ else log("not added: ${note.word} — $error")
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
                syncedAfter = runCatching { anki.call("sync") }
                    .onFailure { log("sync after adding failed: ${it.message}") }
                    .isSuccess
            }
        }

        val file = File(outDir, "kindle-sync.tsv")
        file.writeText(report.toString())
        // A single line the shell wrapper turns into the desktop notification.
        File(outDir, "summary.txt").writeText(
            "lookups=${lookups.size} new=${notes.size} added=$addedCount in_anki=${summary["IN_ANKI"] ?: 0} " +
                "not_found=${summary["NOT_IN_DICTIONARY"] ?: 0} reordered=$reordered " +
                "reorder_covers=$reorderCovers synced_after=$syncedAfter dry_run=$dryRun\n"
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
        for (key in byKey.keys) {
            val recording = archive?.find(key.first, key.second) ?: continue
            val out = target(key, ARCHIVE_SOURCE)
            // Same loudness as the TTS files, so the two kinds sit side by side.
            if (out.isFile || ffmpeg(recording, out)) {
                result[key] = out
                fromArchive++
            }
        }
        if (archive != null) log("audio: $fromArchive of ${byKey.size} words from the archive")

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
        val AUDIO_EXTENSIONS = listOf(".mp3", ".ogg", ".opus", ".m4a", ".aac", ".wav", ".flac")
        val TAGS = Regex("<[^>]*>")

        val FONT = Regex("font-family: '([^']+)'")

        /**
         * A run of text up to and including its sentence end. Whitespace ends
         * one too: Kindle joins paragraphs with " 　", and the title page and
         * chapter heading that ride along with a book's first lookup are
         * separated by nothing else.
         */
        val SENTENCE_END = Regex("[^。！？!?\\s　]+[。！？!?」』]*")
    }
}
