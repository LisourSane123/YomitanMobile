package com.yomitanmobile.kindle

import com.yomitanmobile.data.anki.CardBuilder
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.domain.model.CardProfile
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.usecase.ScanEntryResolver
import com.yomitanmobile.domain.usecase.TextScanPlanner
import com.yomitanmobile.domain.usecase.WordFilterRules
import com.yomitanmobile.util.JapaneseDeconjugator
import com.yomitanmobile.util.JapaneseTokenizer
import com.yomitanmobile.util.SentenceContextHighlighter
import java.io.File
import java.sql.DriverManager

/** Where a run reports to: the window's log and progress line, or the terminal plus notifications. */
interface Reporter {
    /** A step the user should see. */
    fun progress(message: String)
    /** A detail for the log. */
    fun log(message: String)
}

/** How a run ended, in a sentence for the user. */
data class Outcome(val failed: Boolean, val message: String)

/**
 * Kindle Vocabulary Builder → Anki desktop, on the app's own code: dictionary
 * form through [JapaneseDeconjugator] and [ScanEntryResolver], "already in
 * Anki" through the phone's duplicate rule over the whole desktop collection,
 * and the card through [CardBuilder] — so a card from the laptop is the card the
 * phone would write. The Kindle's sentence goes to FrontContext, the slot the
 * text scanner puts its source sentence in.
 *
 * Anki is reached through AnkiConnect (localhost:8765), never through the
 * collection file.
 */
class SyncJob(private val config: Config, private val out: Reporter) {

    private val outDir = File(Platform.stateDir, "last-run").apply { mkdirs() }
    private val logFile = File(outDir, "run.log")
    private val anki = AnkiConnect(config.ankiConnect)
    private val profile = CardProfile.JAPANESE

    data class Options(
        val dryRun: Boolean = false,
        /** Ignore the last-run marker and take every lookup. */
        val all: Boolean = false,
        /** Read this vocab.db instead of the Kindle's (a file the user picked). */
        val vocab: File? = null,
        /** How long to wait for the Kindle to appear. */
        val waitSeconds: Int = 0,
        val deck: String? = null,
        val sync: Boolean? = null
    )

    private fun log(message: String) {
        out.log(message)
        runCatching { logFile.appendText("[kindle-sync] $message\n") }
    }

    private fun progress(message: String) {
        runCatching { logFile.appendText("[kindle-sync] $message\n") }
        out.progress(message)
    }

    private fun fail(message: String) = Outcome(true, tr("Nie wykonano: ", "Not done: ") + message)

    // ---- the sync ---------------------------------------------------------

    suspend fun sync(options: Options): Outcome {
        logFile.writeText("")
        config.problems().firstOrNull()?.let { return fail(it) }
        val deck = options.deck ?: config.deck
        val syncWeb = options.sync ?: config.syncAnkiWeb
        val dryRun = options.dryRun

        // ---- 1. vocab.db ---------------------------------------------------
        val vocab = File(outDir, "vocab.work.db")
        vocab.delete()
        if (options.vocab != null) {
            progress(tr("Czytam słówka z pliku ${options.vocab.name}…", "Reading words from ${options.vocab.name}…"))
            options.vocab.copyTo(vocab, overwrite = true)
        } else {
            progress(tr("Szukam Kindle i odczytuję słówka z Vocabulary Buildera…", "Looking for the Kindle and reading Vocabulary Builder…"))
            val device = KindleDevice(::log)
            val deadline = System.currentTimeMillis() + options.waitSeconds * 1000L
            while (!device.copyVocab(vocab)) {
                if (System.currentTimeMillis() >= deadline) {
                    return when (device.onUsb()) {
                        true -> fail(tr(
                            "Kindle jest podłączony, ale nie udało się odczytać vocab.db (odblokuj ekran Kindle, sprawdź tryb USB).",
                            "the Kindle is plugged in but vocab.db could not be read (unlock the Kindle's screen, check its USB mode)."
                        ))
                        else -> fail(tr(
                            "nie znaleziono Kindle. Podłącz go kablem USB i odblokuj ekran — albo wskaż plik vocab.db ręcznie.",
                            "no Kindle found. Plug it in over USB and unlock its screen — or pick vocab.db by hand."
                        ))
                    }
                }
                Thread.sleep(3_000)
            }
        }
        // Kept: the evidence when a card looks wrong.
        vocab.copyTo(File(Platform.stateDir, "vocab.last.db"), overwrite = true)

        // ---- 2. the lookups since the last run ----------------------------
        val marker = File(Platform.stateDir, "last_timestamp")
        val since = if (options.all) 0L else marker.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
        val lookups = runCatching { readLookups(vocab, since) }.getOrElse {
            return fail(tr("plik vocab.db jest nieczytelny (${it.message}).", "vocab.db cannot be read (${it.message})."))
        }
        log("${lookups.size} new lookups since $since")
        if (lookups.isEmpty()) {
            return Outcome(false, tr("Wykonano: brak nowych słów w Vocabulary Builderze.", "Done: no new words in Vocabulary Builder."))
        }
        progress(tr("Odczytano słówka: ${lookups.size} nowych wyszukań od ostatniego razu.", "Read ${lookups.size} new lookups since last time."))
        pullPhoneSettings(config, ::log)

        // ---- 3. Anki must be answering ------------------------------------
        if (!anki.isUp()) {
            if (Platform.startAnki()) progress(tr("Uruchamiam Anki…", "Starting Anki…"))
            // Anki takes a while to load a big collection; two minutes is generous.
            for (attempt in 1..40) {
                if (anki.isUp()) break
                Thread.sleep(3_000)
            }
        }
        if (!anki.isUp()) {
            return fail(tr(
                "Anki nie odpowiada — uruchom Anki z dodatkiem AnkiConnect. Nic nie dodano.",
                "Anki is not answering — start Anki with the AnkiConnect add-on. Nothing was added."
            ))
        }

        // ---- 4. the pipeline ------------------------------------------------
        val result = runCatching { cards(lookups, deck, dryRun, syncWeb) }.getOrElse {
            log("failed: $it")
            return fail(tr("błąd przetwarzania albo synchronizacji (nic nie dodano): ${it.message}", "processing or sync failed (nothing added): ${it.message}"))
        }

        // The marker moves only when the lookups were really dealt with: cards
        // were written, or there was nothing to write. A run that planned
        // cards and added none must be repeatable.
        if (!dryRun && (result.added > 0 || result.new == 0)) {
            marker.parentFile.mkdirs()
            marker.writeText(lookups.last().timestamp.toString())
        } else if (!dryRun) {
            log("marker not moved: ${result.new} cards planned, ${result.added} added — the run repeats next time")
        }
        return Outcome(dryRun.not() && result.added == 0 && result.new > 0, result.message(deck, dryRun))
    }

    data class Lookup(val word: String, val stem: String, val usage: String, val timestamp: Long, val book: String)

    /** The Japanese lookups made after [since], oldest first. */
    private fun readLookups(vocab: File, since: Long): List<Lookup> {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${vocab.absolutePath}?open_mode=1").use { db ->
            db.prepareStatement(
                """
                SELECT w.word, w.stem, l.usage, l.timestamp, coalesce(b.title, '')
                FROM LOOKUPS l
                JOIN WORDS w ON w.id = l.word_key
                LEFT JOIN BOOK_INFO b ON b.id = l.book_key
                WHERE w.lang = 'ja' AND l.timestamp > ?
                ORDER BY l.timestamp
                """.trimIndent()
            ).use { st ->
                st.setLong(1, since)
                val rows = st.executeQuery()
                val list = ArrayList<Lookup>()
                while (rows.next()) {
                    list += Lookup(
                        rows.getString(1).orEmpty().trim(),
                        rows.getString(2).orEmpty().trim(),
                        rows.getString(3).orEmpty().replace(Regex("[\\t\\r\\n]"), " "),
                        rows.getLong(4),
                        rows.getString(5).orEmpty().trim()
                    )
                }
                return list.filter { it.word.isNotEmpty() && it.word.any(JapaneseTokenizer::isJapanese) }
            }
        }
    }

    private data class Note(val word: String, val fields: Map<String, String>, val tags: List<String>, val recording: File?)

    private class RunResult(
        val lookups: Int, val new: Int, val added: Int, val inAnki: Int, val notFound: Int,
        val reordered: Int, val reorderCovers: String, val syncedAfter: Boolean, val duplicatesAfter: List<String>
    ) {
        fun message(deck: String, dryRun: Boolean): String {
            var m = when {
                dryRun -> tr(
                    "Podgląd (nic nie dodano): ${fiszki(new)} do dodania ($lookups wyszukań, $inAnki już w Anki).",
                    "Preview (nothing added): $new new cards to add ($lookups lookups, $inAnki already in Anki)."
                )
                added == 0 && new > 0 -> tr(
                    "Nie wykonano: $new fiszek do dodania, żadna nie weszła — spróbuję ponownie następnym razem.",
                    "Not done: $new cards to add, none went in — the next run tries again."
                )
                else -> tr(
                    "Wykonano: ${fiszki(added)} w talii $deck ($lookups wyszukań, $inAnki już było w Anki).",
                    "Done: $added new cards in deck $deck ($lookups lookups, $inAnki already in Anki)."
                )
            }
            if (reordered >= 0) m += tr(" Kolejność ustawiona (AutoReorder).", " Order set (AutoReorder).")
            if (duplicatesAfter.isNotEmpty()) m += tr(
                " UWAGA: ${duplicatesAfter.size} słów jest teraz w Anki dwa razy — telefon dodał je w trakcie: ${duplicatesAfter.joinToString(", ")}.",
                " NOTE: ${duplicatesAfter.size} words are now in Anki twice — the phone added them meanwhile: ${duplicatesAfter.joinToString(", ")}."
            )
            if (reorderCovers == "false") m += tr(
                " UWAGA: AutoReorder nie obejmuje talii $deck — nowe fiszki nie są ułożone wg częstości.",
                " NOTE: AutoReorder does not cover deck $deck — the new cards are not in frequency order."
            )
            if (!syncedAfter) m += tr(" UWAGA: synchronizacja po dodaniu nie przeszła — kliknij Sync w Anki.", " NOTE: the sync after adding failed — press Sync in Anki.")
            return m
        }
    }

    private suspend fun cards(lookups: List<Lookup>, deck: String, dryRun: Boolean, sync: Boolean): RunResult {
        // ---- what each lookup could be the dictionary form of ------------
        // Kindle's `word` is its own guess at the headword, which for Japanese
        // is sometimes right (突きつける) and sometimes the selection itself;
        // the deconjugator covers the second case, `stem` is the last resort.
        val candidatesOf = lookups.associateWith { lookup ->
            buildList {
                add(lookup.word)
                JapaneseDeconjugator.analyze(lookup.word).sortedBy { it.depth }.forEach { add(it.baseForm) }
                if (lookup.stem.length > 1) add(lookup.stem)
            }.distinct()
        }
        val needed = candidatesOf.values.flatten().toHashSet()

        // ---- dictionary + frequency, streamed from the zips ---------------
        progress(tr("Szukam słów w słowniku…", "Looking the words up…"))
        val parser = YomitanDictionaryParser()
        val dictionary = Dictionary.load(parser, config)
        val firstPass = dictionary.collect(needed, needed)
        val secondPass = HashMap<String, List<WordEntry>>()
        val resolved = ScanEntryResolver.resolve(
            words = needed,
            byExpressions = { list ->
                val wanted = list.toHashSet()
                val known = firstPass.filter { it.expression in wanted }
                val missing = wanted - known.mapTo(HashSet()) { it.expression } - secondPass.keys
                if (missing.isNotEmpty()) {
                    val fetched = dictionary.collect(missing, emptySet()).groupBy { it.expression }
                    for (m in missing) secondPass[m] = fetched[m].orEmpty()
                }
                known + wanted.flatMap { secondPass[it].orEmpty() }
            },
            byReadings = { list ->
                val wanted = list.toHashSet()
                firstPass.filter { it.reading in wanted }
            }
        )

        // ---- the desktop collection, indexed like the phone's scan -------
        // Sync first: the duplicate check is only as good as the collection it
        // reads, and a card added on the phone since the last sync is exactly
        // the duplicate it would miss. A failure here stops the run.
        if (sync && !dryRun) {
            progress(tr("Synchronizuję z AnkiWeb, żeby sprawdzić, co już masz…", "Syncing with AnkiWeb to see what you already have…"))
            anki.call("sync")
        }
        val ankiIndex = anki.collectionIndex()
        log("anki: ${ankiIndex.noteCount} notes indexed")

        // ---- decide, one word per lookup -----------------------------------
        val report = StringBuilder("#\tstatus\tkindle word\tcard\treading\trank\tsentence\n")
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
                // A single kana is a mis-tap on the Kindle (と out of とぼとぼ).
                entry.primaryExpression.length == 1 && JapaneseTokenizer.isKana(entry.primaryExpression[0]) -> "SINGLE_KANA"
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
            report.append("${lookup.timestamp}\t$status\t${lookup.word}\t${entry?.primaryExpression.orEmpty()}\t")
                .append("${entry?.reading.orEmpty()}\t${entry?.frequency ?: ""}\t${sentenceFor(lookup, entry)}\n")
        }
        val summary = report.lineSequence().drop(1).filter { it.isNotBlank() }.groupingBy { it.split('\t')[1] }.eachCount()
        log("plan: $summary")
        progress(tr(
            "Z ${lookups.size} wyszukań: ${toAdd.size} nowych słów, ${summary["IN_ANKI"] ?: 0} już w Anki" + if (toAdd.isEmpty()) "." else " — tworzę fiszki…",
            "Of ${lookups.size} lookups: ${toAdd.size} new words, ${summary["IN_ANKI"] ?: 0} already in Anki" + if (toAdd.isEmpty()) "." else " — making cards…"
        ))

        // ---- cards -----------------------------------------------------------
        val builder = CardBuilder(profile, config.englishLabels)
        // Kindle lookups are all about the sentence they were made in, so the
        // front-context slot is on regardless of the card-style setting.
        val style = phoneStyle(config, anki, ::log).copy(showFrontContextSentence = true)
        val kanji = Dictionary.loadKanji(
            parser, config.kanji,
            toAdd.values.flatMapTo(HashSet()) { (e, _) -> e.primaryExpression.filter(JapaneseTokenizer::isKanji).map(Char::toString) }
        )
        if (!dryRun && toAdd.isNotEmpty()) progress(tr("Nagrywam wymowę dla ${toAdd.size} słów…", "Recording pronunciation for ${toAdd.size} words…"))
        val audio = if (dryRun) emptyMap() else AudioMaker(config, ::log).use { maker ->
            maker.speak(toAdd.values.map { (e, _) -> Spoken(e.primaryExpression, e.reading, e.pitchAccent) }, outDir)
        }
        val notes = toAdd.values.map { (entry, lookup) ->
            val word = entry.toWordEntry().copy(exampleSentence = sentenceFor(lookup, entry), exampleSentenceTranslation = "")
            val kanjiData = entry.primaryExpression.filter(JapaneseTokenizer::isKanji).map(Char::toString).distinct().mapNotNull { kanji[it] }
            val font = style.randomFonts.takeIf { style.randomFontsEnabled && it.isNotEmpty() }?.random()
            val fields = profile.fieldNames.zip(builder.buildFields(word, style, kanjiData, randomFont = font)).toMap().toMutableMap()
            val recording = audio[entry.primaryExpression to entry.reading]
            if (recording != null) fields["Audio"] = "[sound:${recording.name}]"
            val tags = listOf("yomitan-mobile", "from_kindle", slug(lookup.book)).filter { it.isNotEmpty() }
            Note(entry.primaryExpression, fields, tags, recording)
        }

        var added = 0
        val addedNotes = ArrayList<Note>()
        // The cards a dry run would have written, field by field: what "preview"
        // means to someone deciding whether to let the real run go ahead.
        File(outDir, "cards-preview.json").writeText(org.json.JSONArray().apply {
            for (note in notes) put(org.json.JSONObject().put("word", note.word).put("tags", org.json.JSONArray(note.tags))
                .put("fields", org.json.JSONObject(note.fields)))
        }.toString(2))
        if (dryRun || notes.isEmpty()) {
            log(if (dryRun) "dry run: ${notes.size} cards would be added to $deck" else "nothing new")
        } else {
            progress(tr("Dodaję ${fiszki(notes.size)} do talii $deck…", "Adding ${notes.size} cards to deck $deck…"))
            val (css, front, back) = builder.packageStyling(style)
            val model = anki.ensureModel(profile, css, front, back)
            anki.ensureDeck(deck)
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
        }

        // ---- AutoReorder's own pass, then the second sync ----------------
        var reordered = -1
        var reorderCovers = "na"
        var syncedAfter = true
        if (!dryRun && added > 0) {
            reorderConfig(Platform.autoReorderAddon())?.let { cfg ->
                val mine = anki.countCards("\"deck:$deck\" is:new")
                val covered = anki.countCards("\"deck:$deck\" is:new (${cfg.search})")
                reorderCovers = (mine > 0 && covered == mine).toString()
                if (reorderCovers == "true") {
                    progress(tr("Układam kolejność nowych kart (AutoReorder)…", "Ordering new cards (AutoReorder)…"))
                    reordered = anki.reorder(cfg)
                    log("reorder (${cfg.search}, by ${cfg.field}): $reordered cards moved")
                } else {
                    // Reordering anyway would push these cards behind the queue.
                    log("not reordering: AutoReorder sorts \"${cfg.search}\", which covers $covered of $mine new cards in $deck")
                }
            }
            if (sync) {
                progress(tr("Synchronizuję z AnkiWeb…", "Syncing with AnkiWeb…"))
                syncedAfter = runCatching { anki.call("sync") }.onFailure { log("sync after adding failed: ${it.message}") }.isSuccess
            }
        }

        // A word the phone mined during this run arrives with the second sync;
        // only now can the two be seen side by side. Reported, never deleted.
        val duplicatesAfter = if (!dryRun && sync && syncedAfter && addedNotes.isNotEmpty()) {
            anki.duplicatesOf(addedNotes.map { it.word to plain(it.fields["Reading"].orEmpty()) })
                .onEach { log("duplicate after sync: $it — the phone added it during this run") }
        } else emptyList()

        File(outDir, "kindle-sync.tsv").writeText(report.toString())
        log("report: ${File(outDir, "kindle-sync.tsv").path}")
        return RunResult(
            lookups.size, notes.size, added, summary["IN_ANKI"] ?: 0, summary["NOT_IN_DICTIONARY"] ?: 0,
            reordered, reorderCovers, syncedAfter, duplicatesAfter
        )
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
     * of whatever headword it matched at the start — しんぞく for 親族会議, but
     * also ぶり for ブリッヂオ. An entry reached through it only counts when it
     * is written at the start of what the reader selected.
     */
    private fun isPrefixOf(entry: MergedWordEntry, word: String): Boolean =
        (listOf(entry.primaryExpression) + entry.alternativeExpressions).any { it.length >= 2 && word.startsWith(it) }

    private fun slug(title: String): String =
        title.trim().replace(Regex("[\\s　]+"), "_").replace(Regex("[\"'`]"), "").take(60)

    companion object {
        private val TAGS = Regex("<[^>]*>")

        fun plain(html: String): String = TAGS.replace(html.replace(Regex("\\[sound:[^]]*]"), ""), "")
            .replace("&nbsp;", " ").trim()

        /**
         * A run of text up to and including its sentence end. Whitespace ends
         * one too: Kindle joins paragraphs with " 　", and the title page and
         * chapter heading that ride along with a book's first lookup are
         * separated by nothing else.
         */
        val SENTENCE_END = Regex("[^。！？!?\\s　]+[。！？!?」』]*")

        /** "1 nowa fiszka", "3 nowe fiszki", "83 nowe fiszki", "12 nowych fiszek". */
        fun fiszki(n: Int): String {
            val form = when {
                n == 1 -> "nowa fiszka"
                n % 10 in 2..4 && n % 100 !in 12..14 -> "nowe fiszki"
                else -> "nowych fiszek"
            }
            return "$n $form"
        }
    }
}
