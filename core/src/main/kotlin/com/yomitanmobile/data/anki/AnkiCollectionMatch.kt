package com.yomitanmobile.data.anki

import com.yomitanmobile.util.EnglishLemmatizer

/**
 * The rule that decides "is this word already in the collection", apart from
 * how the collection is read. The phone reads notes through AnkiDroid's
 * provider ([AnkiCollectionIndex]), the desktop Kindle tool through
 * AnkiConnect; both hand the fields to [AnkiNoteFieldIndexer] and ask the same
 * [Index], so a word is a duplicate on the laptop exactly when it would be on
 * the phone.
 */
object AnkiCollectionMatch {

    /**
     * A word is "already in the collection" when its written form matches an
     * indexed field. Kana-only words also match on the reading, since for them
     * there is no kanji form to disambiguate homophones with — the same rule
     * [com.yomitanmobile.util.JlptVocabulary] uses.
     */
    data class Index(
        private val keys: Set<String>,
        val noteCount: Int,
        val available: Boolean,
        /**
         * "spelling\treading" for every kanji spelling and kana key that ONE
         * note holds together (see [NoteFacts]). What lets another spelling
         * of the entry count without letting a different word in: JMdict
         * files 風 as a spelling of 振り (ふり), and a deck's 風 is かぜ.
         * Empty for an index built from the stored word list, which then
         * answers by the older rule alone.
         */
        private val spellingReadings: Set<String> = emptySet(),
        /**
         * The kana keys of notes that hold no kanji at all — a deck that
         * files 膝 as ひざ. Only these, not the reading field of a kanji
         * note, can stand for a word through its reading.
         */
        private val kanaHeadwords: Set<String> = emptySet()
    ) {
        /**
         * @param readingCountsAlone the word is normally written in kana (see
         * [com.yomitanmobile.domain.usecase.WordFilterRules.isUsuallyKana]), so
         * its reading identifies it even though the candidate carries a kanji
         * spelling. Without this, 下さい looked missing to a collection holding
         * ください and the generator made a card the user already had.
         */
        fun contains(
            expression: String,
            reading: String,
            readingCountsAlone: Boolean = false
        ): Boolean = containsAny(listOf(expression), reading, readingCountsAlone)

        /**
         * Same question for a word that has several written forms.
         *
         * One dictionary entry carries every spelling of the word (JMdict
         * lists 持って来る, 持ってくる and もって来る together) while the deck
         * holds whichever one its author happened to type. Comparing only the
         * primary headword therefore reported "not in your collection" for
         * compound verbs the user had been studying for months — the mixed
         * kanji/kana spellings are exactly where decks disagree.
         *
         * @param expressions the word's written forms, the primary one FIRST:
         * it is the one that decides whether a bare reading match is allowed.
         * @param forms what the dictionary says about the entry beyond its
         * headword ([WordForms]): its other spellings, and whether its
         * reading belongs to it alone.
         */
        fun containsAny(
            expressions: List<String>,
            reading: String,
            readingCountsAlone: Boolean = false,
            forms: WordForms = WordForms.NONE
        ): Boolean {
            if (containsByHeadword(expressions, reading, readingCountsAlone)) return true
            val read = AnkiNoteFieldIndexer.normalizeKey(reading)
            if (!available || read.isEmpty()) return false
            // Another spelling of the same entry (身体 → 体, 眼 → 目), but
            // only from a note that holds this reading beside it: JMdict
            // lists rare spellings that are common words of their own
            // (振り・風, 下る・降る, 屋・家), and a spelling alone let 風
            // "wind" answer for 振り.
            for (other in forms.otherSpellings) {
                val key = AnkiNoteFieldIndexer.normalizeKey(other)
                if ("$key\t$read" in spellingReadings) return true
            }
            // A deck that files the word in kana (Core's ひざ, あきれる,
            // にらむ): the reading stands for the word when no other word
            // anyone would mean is read that way.
            return forms.readingIsUnambiguous && read in kanaHeadwords
        }

        private fun containsByHeadword(
            expressions: List<String>,
            reading: String,
            readingCountsAlone: Boolean
        ): Boolean {
            if (!available) return false
            // An English or Spanish word: its note's first field is the only
            // place it is indexed (see AnkiNoteFieldIndexer), under its
            // lowercased spelling. No reading rule applies to it.
            val latin = expressions.filter { AnkiNoteFieldIndexer.isLatinWord(it) }
            if (latin.isNotEmpty() && latin.any { latinMatch(it) }) return true
            val read = AnkiNoteFieldIndexer.normalizeKey(reading)
            val spellings = expressions
                .map { AnkiNoteFieldIndexer.normalizeKey(it) }
                .filter { it.isNotEmpty() }

            for (expr in spellings) {
                if (expr in keys) return true
                if (read.isEmpty()) continue
                // Mixed spellings of the SAME word, derived from the reading:
                // 持って来る + もってくる also means 持ってくる and もって来る.
                // They still carry a kanji block, so they identify the word as
                // precisely as the headword does — unlike the bare reading,
                // which stays subject to the homophone rule below.
                for (variant in KanaSpellingVariants.of(expr, read)) {
                    if (variant != read && variant in keys) return true
                }
            }

            if (read.isEmpty() || read !in keys) return false
            // Otherwise the reading only counts when no kanji form could point
            // at a different word: a kana-only headword, or a word the
            // dictionary says is normally written in kana anyway.
            val primary = spellings.firstOrNull().orEmpty()
            val kanjiFormIsDecisive = primary.isNotEmpty() &&
                !AnkiNoteFieldIndexer.isKanaOnly(primary) &&
                !readingCountsAlone
            return !kanjiFormIsDecisive
        }

        /**
         * The word itself, or the base form it is an inflection of.
         *
         * A collection holds "make", and the user mines "made" off the page
         * they read it on: without this the card is created, and the pair sits
         * in the deck for good. English is where the app can answer this
         * itself ([EnglishLemmatizer]); a Spanish form is resolved to its
         * lemma earlier, by the dictionary, because kty-es-en files every
         * conjugation as an entry pointing at the base (see `FormOf`).
         *
         * Only the direction that can be trusted: "made" asks about "make",
         * never the other way round. Generating every form of a word would
         * mean a collection holding "saw" answers for "see".
         */
        private fun latinMatch(word: String): Boolean {
            if (AnkiNoteFieldIndexer.latinKey(word) in keys) return true
            return EnglishLemmatizer.inflectionBases(word)
                .any { AnkiNoteFieldIndexer.latinKey(it) in keys }
        }

        /**
         * This index with [words] added — words written to the collection
         * since it was built. They carry no per-note facts; their headwords
         * are enough to block writing them twice.
         */
        fun plusWords(words: Set<String>): Index =
            copy(keys = keys + words, available = available || words.isNotEmpty())

        companion object {
            val EMPTY = Index(emptySet(), 0, available = false)
        }
    }

    /**
     * Builds an [Index] note by note, keeping the two facts that only make
     * sense per note — which spelling sits beside which reading, and which
     * notes are written in kana alone. Fed each note's keys as
     * [AnkiNoteFieldIndexer] produced them.
     */
    class NoteFacts {
        private val keys = HashSet<String>(1 shl 14)
        private val spellingReadings = HashSet<String>(1 shl 14)
        private val kanaHeadwords = HashSet<String>(1 shl 10)

        fun add(noteKeys: Collection<String>) {
            keys += noteKeys
            val kana = noteKeys.filter { AnkiNoteFieldIndexer.isKanaOnly(it) }
            if (kana.isEmpty()) return
            val kanji = noteKeys.filter { key -> key.any { AnkiNoteFieldIndexer.isKanji(it) } }
            if (kanji.isEmpty()) {
                kanaHeadwords += kana
            } else {
                for (spelling in kanji) for (r in kana) spellingReadings += "$spelling\t$r"
            }
        }

        val keyCount: Int get() = keys.size

        fun build(noteCount: Int): Index =
            Index(keys, noteCount, available = true, spellingReadings, kanaHeadwords)
    }

    /**
     * How many base forms of one word the live search asks Anki about.
     * Each is an OR clause; the lemmatiser's first few are the plausible
     * ones and the rest only widen the sweep.
     */
    private const val LIVE_MAX_BASES = 3


    /**
     * The Anki search [liveContainsAny] runs, or null when there is
     * nothing to search for.
     *
     * Anki's search matches raw field text, and a deck may hold the word
     * as ruby — 持[も]って 来[く]る — where "持って来る" is not a
     * substring. So a spelling with kanji is searched as its kanji, each
     * required (`("持" "来")`), which every ruby and plain form of it
     * contains; a kana spelling, and the reading, as themselves. The
     * indexer then decides on whole fields, so the breadth only costs a
     * few more notes read.
     */
    fun liveSearch(expressions: List<String>, reading: String): String? {
        val clauses = LinkedHashSet<String>()
        // The base forms too, or the note holding "make" is never even
        // fetched when the question is about "made" — the live path has to
        // read the notes that `Index.containsAny` will then judge.
        val bases = expressions
            .filter { AnkiNoteFieldIndexer.isLatinWord(it) }
            .flatMap { EnglishLemmatizer.inflectionBases(it, limit = LIVE_MAX_BASES) }
        for (raw in expressions + reading + bases) {
            val word = AnkiNoteFieldIndexer.normalizeKey(raw)
            if (word.isEmpty()) continue
            val kanji = word.filter { AnkiNoteFieldIndexer.isKanji(it) }.toSet()
            clauses += if (kanji.isEmpty()) {
                quote(word)
            } else {
                kanji.joinToString(" ", prefix = "(", postfix = ")") { quote(it.toString()) }
            }
        }
        return clauses.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
    }

    /** An Anki search term matching [text] literally anywhere in a field. */
    private fun quote(text: String): String {
        // Inside quotes Anki still reads * and _ as wildcards and \ as an
        // escape; a dictionary word never contains them, but a stray one
        // must not widen the search.
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("*", "\\*").replace("_", "\\_")
        return "\"$escaped\""
    }
}
