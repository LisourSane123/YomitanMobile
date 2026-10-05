package com.yomitanmobile.kindle

import com.yomitanmobile.data.anki.CardBuilder
import com.yomitanmobile.data.anki.RefreshMerge
import com.yomitanmobile.data.audio.KanjiAlive
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.domain.model.CardProfile
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.util.JapaneseTokenizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Work on cards already in Anki — no Kindle involved. Command line only: these
 * are rare, deliberate operations, and each has a dry run.
 */
class Maintenance(private val config: Config, private val out: Reporter) {

    private val outDir = File(Platform.stateDir, "last-run").apply { mkdirs() }
    private val logFile = File(outDir, "run.log")
    private val anki = AnkiConnect(config.ankiConnect)
    private val profile = CardProfile.JAPANESE
    private fun plain(html: String) = SyncJob.plain(html)

    private fun log(message: String) {
        out.log(message)
        runCatching { logFile.appendText("[kindle-sync] $message\n") }
    }

    private fun start() {
        logFile.writeText("")
        check(anki.isUp()) { tr("Anki nie odpowiada — uruchom Anki z AnkiConnect.", "Anki is not answering — start Anki with AnkiConnect.") }
    }

    private fun sync(): Boolean = runCatching { anki.call("sync") }.isSuccess
    private val syncWarning get() = tr(" UWAGA: synchronizacja nie przeszła — kliknij Sync w Anki.", " NOTE: the sync failed — press Sync in Anki.")

    /**
     * Re-records the Audio field of every card this tool made (`tag:from_kindle`)
     * with the current engine, in place: `updateNoteFields` keeps the note, its
     * cards and their review history.
     */
    suspend fun refreshAudio(): Outcome {
        start()
        if (config.syncAnkiWeb) anki.call("sync")
        val infos = anki.call("notesInfo", JSONObject().put("query", "tag:from_kindle")) as JSONArray
        val notes = (0 until infos.length()).map { infos.getJSONObject(it) }.map { info ->
            val fields = info.getJSONObject("fields")
            fun field(name: String) = fields.optJSONObject(name)?.optString("value").orEmpty()
            // Front carries the random-font span; the word is its text.
            Triple(info.getLong("noteId"), plain(field("Front")), plain(field("Reading")))
        }
        val pitch = Dictionary.loadPitch(YomitanDictionaryParser(), config.pitch)
        val audio = AudioMaker(config, ::log).use { maker ->
            maker.speak(notes.map { (_, word, reading) -> Spoken(word, reading, pitch[word].orEmpty()) }, outDir)
        }
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
        val syncedAfter = !config.syncAnkiWeb || sync()
        log("audio refreshed on $refreshed of ${notes.size} notes")
        return Outcome(false, tr("Wykonano: nowe audio w $refreshed z ${notes.size} fiszek from_kindle.", "Done: new audio on $refreshed of ${notes.size} from_kindle cards.") +
            if (syncedAfter) "" else syncWarning)
    }

    /**
     * Brings every note of the current note type up to what the app writes
     * today, in place. The field rule is [RefreshMerge]'s, the same one the
     * phone's refresh uses: the front, the mined sentence and the AI summary
     * are the note's and are kept; a label in Frequency is replaced by the
     * rank, or cleared. A note whose word the dictionary does not list WITH ITS
     * READING is left alone — a homophone's definition is worse than an old card.
     *
     * A dry run writes refresh.tsv, refresh-sample.tsv and refresh-full.json
     * and touches nothing.
     */
    suspend fun refreshCards(dryRun: Boolean, excluded: Set<String>): Outcome {
        start()
        config.problems().firstOrNull()?.let { return Outcome(true, it) }
        if (config.syncAnkiWeb && !dryRun) anki.call("sync")

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

        val parser = YomitanDictionaryParser()
        val dictionary = Dictionary.load(parser, config)
        val words = notes.map { (_, f) -> plain(f["Front"].orEmpty()) }.filter { it.isNotEmpty() }.toSet()
        val entries = dictionary.collect(words, emptySet()).groupBy { it.expression to it.reading }
        val style = phoneStyle(config, anki, ::log).copy(showFrontContextSentence = false)
        val builder = CardBuilder(profile, config.englishLabels)
        val kanji = Dictionary.loadKanji(parser, config.kanji, words.flatMapTo(HashSet()) { w -> w.filter(JapaneseTokenizer::isKanji).map(Char::toString) })

        data class Plan(
            val id: Long, val word: String, val reading: String, val status: String,
            val before: Map<String, String>, val after: Map<String, String>, val pitch: String = ""
        ) {
            val changed get() = after.filter { (k, v) -> before[k] != v }.keys
        }
        val plans = notes.map { (id, current) ->
            val word = plain(current["Front"].orEmpty())
            val reading = plain(current["Reading"].orEmpty())
            if (word in excluded) return@map Plan(id, word, reading, "EXCLUDED", current, current)
            val found = entries[word to reading] ?: if (reading.isEmpty()) {
                entries.filterKeys { it.first == word }.values.flatten().takeIf { it.isNotEmpty() }
            } else null
            if (word.isEmpty() || found == null) return@map Plan(id, word, reading, "NOT_IN_DICTIONARY", current, current)
            val merged = MergedWordEntry.mergeEntries(found).first()
            val kanjiData = merged.primaryExpression.filter(JapaneseTokenizer::isKanji).map(Char::toString).distinct().mapNotNull { kanji[it] }
            val font = style.randomFonts.takeIf { style.randomFontsEnabled && it.isNotEmpty() }?.random()
            val rebuilt = profile.fieldNames.zip(builder.buildFields(merged.toWordEntry(), style, kanjiData, randomFont = font)).toMap()
            Plan(id, word, reading, "FOUND", current, RefreshMerge.merge(current, rebuilt), merged.pitchAccent)
        }

        // Audio only where a card has none: a recording already on a card is
        // the user's, and refresh-audio exists for replacing those on purpose.
        val needAudio = plans.filter { it.status == "FOUND" && it.after["Audio"].isNullOrBlank() }
        val audio = if (dryRun || needAudio.isEmpty()) emptyMap() else AudioMaker(config, ::log).use { maker ->
            maker.speak(needAudio.map { Spoken(it.word, it.reading, it.pitch) }, outDir)
        }
        val final = plans.map { plan ->
            val rec = audio[plan.word to plan.reading] ?: return@map plan
            plan.copy(after = LinkedHashMap(plan.after).apply { put("Audio", "[sound:${rec.name}]") })
        }

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
        File(outDir, "refresh-full.json").writeText(JSONArray().apply {
            for (p in toWrite) put(JSONObject().put("id", p.id).put("word", p.word).put("reading", p.reading)
                .put("before", JSONObject(p.changed.associateWith { p.before[it].orEmpty() }))
                .put("after", JSONObject(p.changed.associateWith { p.after[it].orEmpty() })))
        }.toString())
        final.filter { it.status == "NOT_IN_DICTIONARY" }.forEach { log("skipped, not in the dictionary with its reading: ${it.word} (${it.reading})") }
        final.filter { it.status == "EXCLUDED" }.forEach { log("excluded on request: ${it.word}") }
        log("would change ${toWrite.size} notes; fields: ${final.flatMap { it.changed }.groupingBy { it }.eachCount()}")
        val notFound = final.count { it.status != "FOUND" }
        if (dryRun) {
            return Outcome(false, tr(
                "Podgląd: ${toWrite.size} z ${final.size} fiszek do odświeżenia ($notFound pominiętych). Raport: ${File(outDir, "refresh.tsv").path}",
                "Preview: ${toWrite.size} of ${final.size} cards to refresh ($notFound skipped). Report: ${File(outDir, "refresh.tsv").path}"
            ))
        }

        var updated = 0
        var verified = 0
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
                val error = (results.opt(i) as? JSONObject)?.opt("error")
                if (error == null || error == JSONObject.NULL) updated++ else log("not updated: ${chunk[i].word} — $error")
            }
        }
        // Read back what was written: a multi's result says the call ran, not
        // that the note now holds what was meant.
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
        val syncedAfter = !config.syncAnkiWeb || updated == 0 || sync()
        return Outcome(updated < toWrite.size, tr(
            "Wykonano: odświeżono $updated z ${toWrite.size} fiszek (sprawdzone: $verified).",
            "Done: refreshed $updated of ${toWrite.size} cards (verified: $verified)."
        ) + if (syncedAfter) "" else syncWarning)
    }

    /**
     * The note type's look brought up to what the app writes today, with the
     * phone's style. Notes are not touched; a template change is not a schema
     * change, so a normal sync carries it. Then AutoReorder's pass.
     */
    fun restyle(dryRun: Boolean): Outcome {
        start()
        pullPhoneSettings(config, ::log)
        val model = profile.modelName
        val (css, front, back) = CardBuilder(profile, config.englishLabels).packageStyling(phoneStyle(config, anki, ::log))
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
        if (dryRun) return Outcome(false, tr("Podgląd wyglądu gotowy: ${outDir.path}/restyle-*", "Restyle preview written: ${outDir.path}/restyle-*"))

        anki.call("updateModelStyling", JSONObject().put("model", JSONObject().put("name", model).put("css", css)))
        anki.call("updateModelTemplates", JSONObject().put("model", JSONObject().put("name", model)
            .put("templates", JSONObject().put(cardName, JSONObject().put("Front", front).put("Back", back)))))
        val nowCss = (anki.call("modelStyling", JSONObject().put("modelName", model)) as JSONObject).optString("css")
        val now = (anki.call("modelTemplates", JSONObject().put("modelName", model)) as JSONObject).getJSONObject(cardName)
        val verified = nowCss == css && now.optString("Front") == front && now.optString("Back") == back
        log("written, read back ${if (verified) "equal" else "DIFFERENT"}")
        if (!verified) return Outcome(true, tr("Nie wykonano: zapis wyglądu nie zgadza się z odczytem.", "Not done: the written styling reads back different."))
        val reordered = reorderConfig(Platform.autoReorderAddon())?.let { anki.reorder(it) } ?: -1
        val syncedAfter = !config.syncAnkiWeb || sync()
        return Outcome(false, tr(
            "Wykonano: nowy wygląd fiszek; przestawiono ${maxOf(reordered, 0)} nowych kart (AutoReorder).",
            "Done: new card design; ${maxOf(reordered, 0)} new cards reordered (AutoReorder)."
        ) + if (syncedAfter) "" else syncWarning)
    }

    /** Read-only: which notes matching an Anki search hold a word some OTHER note holds too. */
    fun checkDuplicates(query: String): Outcome {
        start()
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
        log("checked ${words.size} words matching '$query': ${duplicates.size} held by more than one note $duplicates")
        return Outcome(false, "${words.size} checked, ${duplicates.size} duplicates: ${duplicates.joinToString(", ")}")
    }

    /**
     * Installs Kanji alive's native-speaker recordings — the same install the
     * phone runs, SHA-256 pins included — so Kindle cards get the recordings
     * phone cards get.
     */
    fun installNativeAudio(): Outcome {
        logFile.writeText("")
        var lastStep = -1L
        out.progress(tr("Pobieram nagrania native speakerów (Kanji alive, ok. 74 MB)…", "Downloading native-speaker recordings (Kanji alive, ~74 MB)…"))
        KanjiAlive.install(
            config.nativeAudio,
            open = { url ->
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "YomitanMobile-KindleSync/1.0")
                }.inputStream
            },
            onProgress = { done, total ->
                val step = done / 10_000_000
                if (step != lastStep) {
                    lastStep = step
                    out.progress(tr("Pobieram nagrania: ${done / 1_000_000} / ${total / 1_000_000} MB…", "Downloading recordings: ${done / 1_000_000} / ${total / 1_000_000} MB…"))
                }
            }
        )
        val index = KanjiAlive.loadIndex(config.nativeAudio)
        log("native recordings installed in ${config.nativeAudio}: ${index?.size ?: 0} keys")
        return if (index != null) Outcome(false, tr(
            "Wykonano: nagrania native speakerów zainstalowane. Nowe fiszki dostaną je przed VOICEVOX.",
            "Done: native-speaker recordings installed. New cards get them before VOICEVOX."
        )) else Outcome(true, tr("Nie wykonano: instalacja nagrań nie powiodła się.", "Not done: the recordings did not install."))
    }

    private companion object {
        /** `{{Field}}`, `{{#Field}}`, `{{/Field}}`, `{{^Field}}`, `{{furigana:Field}}`. */
        val FIELD_REF = Regex("""\{\{[#/^]?([^}]+)}}""")
        val SPECIAL_FIELDS = setOf("FrontSide", "Tags", "Type", "Deck", "Subdeck", "Card", "CardFlag", "CardID")
    }
}
