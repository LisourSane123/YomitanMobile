package com.yomitanmobile.kindle

import com.yomitanmobile.data.anki.CardBuilder
import com.yomitanmobile.data.anki.ContextCard
import com.yomitanmobile.data.anki.MonolingualChain
import com.yomitanmobile.data.anki.RefreshMerge
import com.yomitanmobile.data.audio.KanjiAlive
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.domain.model.CardProfile
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.util.InputSanitizer
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
        check(anki.isUp()) { tr("Anki nie odpowiada — uruchom Anki z AnkiConnect.", "Anki is not answering — start Anki with AnkiConnect.", "Ankiが応答しません — AnkiConnect付きでAnkiを起動してください。") }
    }

    private fun sync(): Boolean = runCatching { anki.sync() }.isSuccess
    private val syncWarning get() = tr(" UWAGA: synchronizacja nie przeszła — kliknij Sync w Anki.", " NOTE: the sync failed — press Sync in Anki.", " 注意: 同期に失敗しました — AnkiでSyncを押してください。")

    /**
     * Re-records the Audio field of every card this tool made (`tag:from_kindle`)
     * with the current engine, in place: `updateNoteFields` keeps the note, its
     * cards and their review history.
     */
    suspend fun refreshAudio(): Outcome {
        start()
        if (config.syncAnkiWeb) anki.sync()
        val infos = anki.call("notesInfo", JSONObject().put("query", "tag:from_kindle")) as JSONArray
        val notes = (0 until infos.length()).map { infos.getJSONObject(it) }.map { info ->
            val fields = info.getJSONObject("fields")
            fun field(name: String) = fields.optJSONObject(name)?.optString("value").orEmpty()
            // Front carries the random-font span; the word is its text.
            // An onomatopoeia card has no Front; its word is in Word.
            Triple(info.getLong("noteId"), plain(field("Front")).ifEmpty { plain(field("Word")) }, plain(field("Reading")))
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
        return Outcome(false, tr("Wykonano: nowe audio w $refreshed z ${notes.size} fiszek from_kindle.", "Done: new audio on $refreshed of ${notes.size} from_kindle cards.", "完了: from_kindleのカード${notes.size}枚のうち${refreshed}枚の音声を更新しました。") +
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
        if (config.syncAnkiWeb && !dryRun) anki.sync()

        val query = "\"note:${profile.modelName}\" OR \"note:${ContextCard.MODEL_NAME}\""
        val ids = anki.call("findNotes", JSONObject().put("query", query)) as JSONArray
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
        log("notes: ${notes.size} of ${profile.modelName} and ${ContextCard.MODEL_NAME}")
        // The two note types are told apart by their fields: only the onomatopoeia card has Word.
        fun isOnomatopoeia(fields: Map<String, String>) = "Word" in fields && "Front" !in fields
        fun wordOf(fields: Map<String, String>) = plain(fields[if (isOnomatopoeia(fields)) "Word" else "Front"].orEmpty())

        val parser = YomitanDictionaryParser()
        val dictionary = Dictionary.load(parser, config)
        val words = notes.map { (_, f) -> wordOf(f) }.filter { it.isNotEmpty() }.toSet()
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
            val word = wordOf(current)
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
            val after = if (isOnomatopoeia(current)) {
                RefreshMerge.merge(current, ContextCard.fields(rebuilt, word, "", ""), ContextCard.OWNED_BY_NOTE)
            } else {
                RefreshMerge.merge(current, rebuilt)
            }
            Plan(id, word, reading, "FOUND", current, after, merged.pitchAccent)
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
                "Preview: ${toWrite.size} of ${final.size} cards to refresh ($notFound skipped). Report: ${File(outDir, "refresh.tsv").path}", "プレビュー: ${final.size}枚のうち${toWrite.size}枚を更新予定（スキップ${notFound}件）。レポート: ${File(outDir, "refresh.tsv").path}"
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
            "Done: refreshed $updated of ${toWrite.size} cards (verified: $verified).", "完了: ${toWrite.size}枚のうち${updated}枚を更新しました（確認済み: ${verified}）。") + if (syncedAfter) "" else syncWarning)
    }

    /**
     * Rewrites the meaning of UNLEARNED cards — never reviewed and not
     * suspended — from the JP-JP chain, exactly as a new card would get it,
     * and tags them `J-J`. A vocabulary card's Meaning is asked of the chain;
     * a context card's Definition of surasura and then the chain without the
     * encyclopedia, as a new onomatopoeia's is. A card that has been studied
     * keeps the meaning it was learnt with; so does one whose meaning the user
     * wrote in by hand, one already Japanese, and one no monolingual
     * dictionary has. Only that one field changes: same note, same card,
     * same position in the queue.
     *
     * [scope] narrows the notes with an Anki search ("tag:from_kindle
     * added:4"); empty means every unlearned note of ours. A dry run writes
     * convert-jj.tsv and touches nothing.
     */
    suspend fun convertToJapanese(dryRun: Boolean, excluded: Set<String>, scope: String): Outcome {
        start()
        config.problems().firstOrNull()?.let { return Outcome(true, it) }
        val parser = YomitanDictionaryParser()
        val chain = monolingualChain(parser, config, ::log)
        if (chain.isEmpty()) {
            return Outcome(true, tr(
                "Nie wykonano: nie znaleziono słowników JP-JP (Ustawienia → Słownik JP-JP).",
                "Not done: no JP-JP dictionaries were found (Settings → JP-JP dictionary).",
                "未完了: 国語辞典が見つかりません（設定 → 国語辞典）。"
            ))
        }
        val surasura = config.onomatopoeia?.takeIf { it.isFile }?.let { MonolingualDictionary(parser, it) }
        val contextChain = listOfNotNull(surasura) + chain.filterNot { it.isEncyclopedia }
        if (config.syncAnkiWeb && !dryRun) anki.sync()

        // One template per note of these types, so "the card is new and not
        // suspended" is a statement about the note.
        val types = (listOf(profile.modelName) + ContextCard.MODEL_NAMES).joinToString(" OR ") { "\"note:$it\"" }
        val query = "($types) is:new -is:suspended -tag:${SyncJob.J_J_TAG}" + if (scope.isBlank()) "" else " ($scope)"
        log("convert-jj: $query")
        val ids = anki.call("findNotes", JSONObject().put("query", query)) as JSONArray
        val notes = (0 until ids.length()).map { ids.getLong(it) }.chunked(500).flatMap { chunk ->
            val infos = anki.call("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
            (0 until infos.length()).map { i ->
                val info = infos.getJSONObject(i)
                val fields = info.getJSONObject("fields")
                info.getLong("noteId") to fields.keys().asSequence().associateWith { fields.getJSONObject(it).optString("value") }
            }
        }

        // A context card is the one with Word and Definition instead of Front and Meaning.
        fun isContext(fields: Map<String, String>) = "Word" in fields && "Definition" in fields
        fun wordOf(fields: Map<String, String>) = plain(fields[if (isContext(fields)) "Word" else "Front"].orEmpty())
        fun meaningField(fields: Map<String, String>) = if (isContext(fields)) "Definition" else "Meaning"

        val dictionary = Dictionary.load(parser, config)
        val words = notes.map { (_, f) -> wordOf(f) }.filter { it.isNotEmpty() }.toSet()
        val entries = dictionary.collect(words, emptySet()).groupBy { it.expression to it.reading }

        data class Candidate(
            val id: Long, val word: String, val reading: String, val field: String,
            val before: String, val status: String, val context: Boolean, val entry: MergedWordEntry? = null
        )
        val candidates = notes.map { (id, fields) ->
            val word = wordOf(fields)
            val reading = plain(fields["Reading"].orEmpty())
            val field = meaningField(fields)
            val meaning = fields[field].orEmpty()
            val found = entries[word to reading] ?: entries.filterKeys { it.first == word }.values.flatten().takeIf { it.isNotEmpty() }
            val status = when {
                word in excluded -> "EXCLUDED"
                field !in fields -> "NO_MEANING_FIELD"
                RefreshMerge.hasUserWriting(meaning) -> "HAND_EDITED"
                RefreshMerge.isJapaneseMeaning(meaning) -> "ALREADY_JAPANESE"
                word.isEmpty() || found == null -> "NOT_IN_DICTIONARY"
                else -> "ASK"
            }
            Candidate(id, word, reading, field, meaning, status, isContext(fields),
                found?.takeIf { status == "ASK" }?.let { MergedWordEntry.mergeEntries(it).first() })
        }
        val asked = candidates.filter { it.entry != null }
        out.progress(tr(
            "Szukam definicji JP-JP dla ${asked.size} nienauczonych fiszek…",
            "Looking up JP-JP definitions for ${asked.size} unlearned cards…",
            "未学習のカード${asked.size}枚の国語辞典の定義を検索中…"
        ))
        suspend fun japanese(list: List<Candidate>, dictionaries: List<MonolingualDictionary>, fold: Boolean) =
            list.zip(MonolingualChain.apply(
                list.map { it.entry!!.toWordEntry() },
                dictionaries.map { it.title },
                lookup = { title, expressions -> dictionaries.first { it.title == title }.lookup(expressions) },
                onFailure = { title, error -> log("monolingual: $title failed (${error.message}), next one asked") },
                foldKatakana = fold
            ))
        val answered = japanese(asked.filter { !it.context }, chain, fold = false) +
            japanese(asked.filter { it.context }, contextChain, fold = true)
        val style = phoneStyle(config, anki, ::log).copy(showFrontContextSentence = false)
        val builder = CardBuilder(profile, config.englishLabels)
        val converted = answered.associate { (c, word) ->
            c.id to if (word.meaningSource.isEmpty()) null else {
                word.meaningSource to profile.fieldNames.zip(builder.buildFields(word, style)).toMap()["Meaning"].orEmpty()
            }
        }

        data class Plan(val c: Candidate, val status: String, val source: String, val after: String)
        val plans = candidates.map { c ->
            val result = converted[c.id]
            when {
                c.status != "ASK" -> Plan(c, c.status, "", c.before)
                result == null || result.second.isBlank() -> Plan(c, "NO_JAPANESE_DEFINITION", "", c.before)
                else -> Plan(c, "CONVERT", result.first, result.second)
            }
        }
        val toWrite = plans.filter { it.status == "CONVERT" }
        val report = File(outDir, "convert-jj.tsv")
        report.writeText(buildString {
            appendLine("note\tstatus\tkind\tword\treading\tdictionary\tbefore\tafter")
            for (p in plans) appendLine(
                "${p.c.id}\t${p.status}\t${if (p.c.context) "context" else "vocabulary"}\t${p.c.word}\t${p.c.reading}\t${p.source}\t" +
                    "${plain(p.c.before).replace('\t', ' ').replace('\n', ' ').take(120)}\t${plain(p.after).replace('\t', ' ').replace('\n', ' ').take(160)}"
            )
        })
        log("convert-jj: ${plans.size} notes ${plans.groupingBy { it.status }.eachCount()}; by dictionary ${toWrite.groupingBy { it.source }.eachCount()}")
        if (dryRun) {
            return Outcome(false, tr(
                "Podgląd: ${toWrite.size} z ${plans.size} nienauczonych fiszek dostanie definicję JP-JP. Raport: ${report.path}",
                "Preview: ${toWrite.size} of ${plans.size} unlearned cards would get a JP-JP definition. Report: ${report.path}",
                "プレビュー: 未学習のカード${plans.size}枚のうち${toWrite.size}枚が国語辞典の定義になります。レポート: ${report.path}"
            ))
        }

        var updated = 0
        var verified = 0
        for (chunk in toWrite.chunked(100)) {
            val actions = JSONArray()
            for (p in chunk) {
                actions.put(JSONObject().put("action", "updateNoteFields").put("params", JSONObject().put("note",
                    JSONObject().put("id", p.c.id).put("fields", JSONObject().put(p.c.field, p.after)))))
            }
            val results = anki.call("multi", JSONObject().put("actions", actions)) as JSONArray
            val done = ArrayList<Long>()
            for (i in 0 until results.length()) {
                val error = (results.opt(i) as? JSONObject)?.opt("error")
                if (error == null || error == JSONObject.NULL) done += chunk[i].c.id else log("not converted: ${chunk[i].c.word} — $error")
            }
            // The tag only where the meaning went in: J-J must never sit on an English card.
            if (done.isNotEmpty()) anki.call("addTags", JSONObject().put("notes", JSONArray(done)).put("tags", SyncJob.J_J_TAG))
            updated += done.size
        }
        for (chunk in toWrite.chunked(500)) {
            val infos = anki.call("notesInfo", JSONObject().put("notes", JSONArray(chunk.map { it.c.id }))) as JSONArray
            for (i in 0 until infos.length()) {
                val info = infos.getJSONObject(i)
                val p = chunk[i]
                val value = info.getJSONObject("fields").optJSONObject(p.c.field)?.optString("value")
                val tags = info.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                if (value == p.after && SyncJob.J_J_TAG in tags) verified++ else log("verify failed: ${p.c.word}")
            }
        }
        log("converted $updated of ${toWrite.size}, verified $verified")
        val syncedAfter = !config.syncAnkiWeb || updated == 0 || sync()
        return Outcome(updated < toWrite.size || verified < updated, tr(
            "Wykonano: $updated z ${toWrite.size} nienauczonych fiszek ma definicję JP-JP (sprawdzone: $verified).",
            "Done: $updated of ${toWrite.size} unlearned cards now have a JP-JP definition (verified: $verified).",
            "完了: 未学習のカード${toWrite.size}枚のうち${updated}枚を国語辞典の定義にしました（確認済み: $verified）。"
        ) + if (syncedAfter) "" else syncWarning)
    }

    /**
     * Fills the EMPTY Summary of vocabulary cards with the encyclopedia's
     * (Pixiv's) summary of the word — what a new card gets since the
     * encyclopedia moved from the end of the meaning chain to its own slot.
     * Only that field: the Meaning, the tags, the scheduling stay. Studied
     * cards too, since a summary changes nothing about what was learnt. Skipped
     * where the Meaning already IS the encyclopedia's text (Pixiv was the only
     * dictionary that had the word) and where the encyclopedia lacks the word.
     * [scope] narrows the notes with an Anki search; a dry run writes
     * summary.tsv and touches nothing.
     */
    suspend fun fillSummaries(dryRun: Boolean, excluded: Set<String>, scope: String): Outcome {
        start()
        config.problems().firstOrNull()?.let { return Outcome(true, it) }
        val encyclopedias = monolingualChain(YomitanDictionaryParser(), config, ::log).filter { it.isEncyclopedia }
        if (encyclopedias.isEmpty()) {
            return Outcome(true, tr(
                "Nie wykonano: nie znaleziono encyklopedii Pixiv wśród słowników JP-JP.",
                "Not done: no Pixiv encyclopedia among the JP-JP dictionaries.",
                "未完了: 国語辞典の中にピクシブ百科事典が見つかりません。"
            ))
        }
        if (config.syncAnkiWeb && !dryRun) anki.sync()
        val query = "\"note:${profile.modelName}\" Summary:" + if (scope.isBlank()) "" else " ($scope)"
        log("summary: $query")
        val ids = anki.call("findNotes", JSONObject().put("query", query)) as JSONArray
        val notes = (0 until ids.length()).map { ids.getLong(it) }.chunked(500).flatMap { chunk ->
            val infos = anki.call("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
            (0 until infos.length()).map { i ->
                val info = infos.getJSONObject(i)
                val fields = info.getJSONObject("fields")
                info.getLong("noteId") to fields.keys().asSequence().associateWith { fields.getJSONObject(it).optString("value") }
            }
        }.filter { (_, f) -> plain(f["Front"].orEmpty()).let { it.isNotEmpty() && it !in excluded } }
        out.progress(tr(
            "Szukam streszczeń Pixiv dla ${notes.size} fiszek…",
            "Looking up Pixiv summaries for ${notes.size} cards…",
            "${notes.size}枚のカードのピクシブ要約を検索中…"
        ))
        val found = MonolingualChain.summaries(
            notes.map { (_, f) -> WordEntry(expression = plain(f["Front"].orEmpty()), reading = plain(f["Reading"].orEmpty()), definitions = emptyList()) },
            encyclopedias.map { it.title },
            lookup = { title, expressions -> encyclopedias.first { it.title == title }.lookup(expressions) },
            onFailure = { title, error -> log("summary: $title failed (${error.message})") }
        )
        data class Plan(val id: Long, val word: String, val status: String, val summary: String)
        val plans = notes.zip(found).map { (note, summary) ->
            val (id, fields) = note
            val word = plain(fields["Front"].orEmpty())
            // The Meaning is Pixiv's own text when Pixiv was the only one to have the word.
            val meaning = plain(fields["Meaning"].orEmpty()).filterNot(Char::isWhitespace)
            when {
                summary.isEmpty() -> Plan(id, word, "NOT_IN_ENCYCLOPEDIA", "")
                summary.lines().first().filterNot(Char::isWhitespace).let { it.isNotEmpty() && it in meaning } -> Plan(id, word, "MEANING_IS_ENCYCLOPEDIA", summary)
                else -> Plan(id, word, "FILL", summary)
            }
        }
        val toWrite = plans.filter { it.status == "FILL" }
        val report = File(outDir, "summary.tsv")
        report.writeText(buildString {
            appendLine("note\tstatus\tword\tsummary")
            for (p in plans) appendLine("${p.id}\t${p.status}\t${p.word}\t${p.summary.replace('\t', ' ').replace('\n', ' ').take(160)}")
        })
        log("summary: ${plans.size} notes ${plans.groupingBy { it.status }.eachCount()}")
        if (dryRun) {
            return Outcome(false, tr(
                "Podgląd: ${toWrite.size} z ${plans.size} fiszek dostanie streszczenie Pixiv. Raport: ${report.path}",
                "Preview: ${toWrite.size} of ${plans.size} cards would get a Pixiv summary. Report: ${report.path}",
                "プレビュー: ${plans.size}枚のうち${toWrite.size}枚にピクシブ要約が入ります。レポート: ${report.path}"
            ))
        }
        var updated = 0
        var verified = 0
        for (chunk in toWrite.chunked(100)) {
            val actions = JSONArray()
            for (p in chunk) {
                actions.put(JSONObject().put("action", "updateNoteFields").put("params", JSONObject().put("note",
                    JSONObject().put("id", p.id).put("fields", JSONObject().put("Summary", InputSanitizer.escapeHtml(p.summary))))))
            }
            val results = anki.call("multi", JSONObject().put("actions", actions)) as JSONArray
            for (i in 0 until results.length()) {
                val error = (results.opt(i) as? JSONObject)?.opt("error")
                if (error == null || error == JSONObject.NULL) updated++ else log("summary not written: ${chunk[i].word} — $error")
            }
        }
        for (chunk in toWrite.chunked(500)) {
            val infos = anki.call("notesInfo", JSONObject().put("notes", JSONArray(chunk.map { it.id }))) as JSONArray
            for (i in 0 until infos.length()) {
                val value = infos.getJSONObject(i).getJSONObject("fields").optJSONObject("Summary")?.optString("value")
                if (value == InputSanitizer.escapeHtml(chunk[i].summary)) verified++ else log("verify failed: ${chunk[i].word}")
            }
        }
        log("summary written on $updated of ${toWrite.size}, verified $verified")
        val syncedAfter = !config.syncAnkiWeb || updated == 0 || sync()
        return Outcome(updated < toWrite.size || verified < updated, tr(
            "Wykonano: $updated z ${toWrite.size} fiszek ma streszczenie Pixiv (sprawdzone: $verified).",
            "Done: $updated of ${toWrite.size} cards now have a Pixiv summary (verified: $verified).",
            "完了: ${toWrite.size}枚のうち${updated}枚にピクシブ要約を入れました（確認済み: $verified）。"
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
        val (css, front, back) = CardBuilder(profile, config.englishLabels).packageStyling(phoneStyle(config, anki, ::log))
        val models = (anki.call("modelNames") as JSONArray).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        // The onomatopoeia card wears the vocabulary card's look plus its own rules.
        val targets = buildList {
            add(Triple(profile.modelName, "", Triple(css, front, back)))
            if (ContextCard.MODEL_NAME in models) add(Triple(ContextCard.MODEL_NAME, "context-", ContextCard.styling(css)))
        }
        for ((model, prefix, styling) in targets) {
            if (!restyleModel(model, prefix, styling, dryRun)) {
                return Outcome(true, tr("Nie wykonano: zapis wyglądu $model nie zgadza się z odczytem.", "Not done: the written styling of $model reads back different.", "未完了: ${model}のスタイルの書き込みが読み出しと一致しません。"))
            }
        }
        if (dryRun) return Outcome(false, tr("Podgląd wyglądu gotowy: ${outDir.path}/restyle-*", "Restyle preview written: ${outDir.path}/restyle-*", "スタイルのプレビューを書き出しました: ${outDir.path}/restyle-*"))
        val reordered = reorderConfig(Platform.autoReorderAddon())?.let { anki.reorder(it) } ?: -1
        val syncedAfter = !config.syncAnkiWeb || sync()
        return Outcome(false, tr(
            "Wykonano: nowy wygląd fiszek; przestawiono ${maxOf(reordered, 0)} nowych kart (AutoReorder).",
            "Done: new card design; ${maxOf(reordered, 0)} new cards reordered (AutoReorder).", "完了: カードの新デザイン。新規カード${maxOf(reordered, 0)}枚を並べ替えました（AutoReorder）。") + if (syncedAfter) "" else syncWarning)
    }

    /** One note type's CSS and single template; false when the write reads back different. */
    private fun restyleModel(model: String, prefix: String, styling: Triple<String, String, String>, dryRun: Boolean): Boolean {
        val (css, front, back) = styling
        val currentCss = (anki.call("modelStyling", JSONObject().put("modelName", model)) as JSONObject).optString("css")
        val templates = anki.call("modelTemplates", JSONObject().put("modelName", model)) as JSONObject
        check(templates.length() == 1) { "$model has ${templates.length()} templates, expected one" }
        val cardName = templates.keys().next()
        val current = templates.getJSONObject(cardName)
        File(outDir, "restyle-${prefix}current.css").writeText(currentCss)
        File(outDir, "restyle-${prefix}current-front.html").writeText(current.optString("Front"))
        File(outDir, "restyle-${prefix}current-back.html").writeText(current.optString("Back"))
        File(outDir, "restyle-${prefix}new.css").writeText(css)
        File(outDir, "restyle-${prefix}new-front.html").writeText(front)
        File(outDir, "restyle-${prefix}new-back.html").writeText(back)

        // A template naming a field the type lacks prints "{{Field}}" on the card.
        val fields = (anki.call("modelFieldNames", JSONObject().put("modelName", model)) as JSONArray)
            .let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val referenced = FIELD_REF.findAll(front + back).map { it.groupValues[1].substringAfterLast(':').trim() }
            .filter { it.isNotEmpty() && it !in SPECIAL_FIELDS }.toSet()
        val unknown = referenced - fields
        check(unknown.isEmpty()) { "templates name fields $model does not have: $unknown" }
        if (dryRun) return true

        anki.call("updateModelStyling", JSONObject().put("model", JSONObject().put("name", model).put("css", css)))
        anki.call("updateModelTemplates", JSONObject().put("model", JSONObject().put("name", model)
            .put("templates", JSONObject().put(cardName, JSONObject().put("Front", front).put("Back", back)))))
        val nowCss = (anki.call("modelStyling", JSONObject().put("modelName", model)) as JSONObject).optString("css")
        val now = (anki.call("modelTemplates", JSONObject().put("modelName", model)) as JSONObject).getJSONObject(cardName)
        val verified = nowCss == css && now.optString("Front") == front && now.optString("Back") == back
        log("$model written, read back ${if (verified) "equal" else "DIFFERENT"}")
        return verified
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
        out.progress(tr("Pobieram nagrania native speakerów (Kanji alive, ok. 74 MB)…", "Downloading native-speaker recordings (Kanji alive, ~74 MB)…", "ネイティブの録音をダウンロード中（Kanji alive、約74 MB）…"))
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
                    out.progress(tr("Pobieram nagrania: ${done / 1_000_000} / ${total / 1_000_000} MB…", "Downloading recordings: ${done / 1_000_000} / ${total / 1_000_000} MB…", "録音をダウンロード中: ${done / 1_000_000} / ${total / 1_000_000} MB…"))
                }
            }
        )
        val index = KanjiAlive.loadIndex(config.nativeAudio)
        log("native recordings installed in ${config.nativeAudio}: ${index?.size ?: 0} keys")
        return if (index != null) Outcome(false, tr(
            "Wykonano: nagrania native speakerów zainstalowane. Nowe fiszki dostaną je przed VOICEVOX.",
            "Done: native-speaker recordings installed. New cards get them before VOICEVOX.", "完了: ネイティブの録音をインストールしました。新しいカードではVOICEVOXより先に使われます。")) else Outcome(true, tr("Nie wykonano: instalacja nagrań nie powiodła się.", "Not done: the recordings did not install.", "未完了: 録音をインストールできませんでした。"))
    }

    private companion object {
        /** `{{Field}}`, `{{#Field}}`, `{{/Field}}`, `{{^Field}}`, `{{furigana:Field}}`. */
        val FIELD_REF = Regex("""\{\{[#/^]?([^}]+)}}""")
        val SPECIAL_FIELDS = setOf("FrontSide", "Tags", "Type", "Deck", "Subdeck", "Card", "CardFlag", "CardID")
    }
}
