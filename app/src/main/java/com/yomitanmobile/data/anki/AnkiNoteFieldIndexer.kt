package com.yomitanmobile.data.anki

/**
 * Turns the raw fields of an AnkiDroid note into the set of Japanese words it
 * represents. Pulled out of [AnkiCollectionIndex] so the note-type
 * compatibility rules can be unit-tested without a content provider.
 *
 * The strategy is note-type agnostic on purpose. Rather than mapping fields
 * per deck ("Core 2k uses Vocabulary-Kanji, Kaishi uses Word…"), every field
 * is considered and anything that isn't a short, purely Japanese string is
 * thrown away. Sentences, English meanings, sound tags and HTML never survive
 * that filter, so what is left is the headword — whatever the deck calls it.
 */
internal object AnkiNoteFieldIndexer {

    /** Anki stores a note's fields joined by the 0x1f unit separator. */
    private const val FIELD_SEPARATOR = '\u001f'

    /**
     * Ceiling on the RAW field, before ruby brackets are resolved. Generous on
     * purpose: 取[と]り返[かえ]しのつかない is a legitimate headword and carries
     * its readings inline. Its only job is to bail out of obvious prose early.
     */
    private const val MAX_RAW_FIELD_LENGTH = 40

    /**
     * Ceiling on the finished key. Japanese headwords essentially never run
     * past this — the longest entries in JMdict that anyone mines sit around
     * ten characters — so anything longer is a sentence, a meaning or a note.
     * Indexing those inflates the "words found" figure and, worse, a field
     * that happens to hold exactly one common word would mark that word as
     * already known and silently drop it from every later scan.
     */
    private const val MAX_KEY_LENGTH = 16

    /**
     * Whitespace-separated runs a field WITHOUT ruby may have. Ruby fields are
     * judged by [rubyRunsLookLikeHeadword] instead, because Anki's furigana
     * notation legitimately spaces a single word into one run per kanji block.
     */
    private const val MAX_RUNS = 2

    /**
     * `\s` alone misses the ideographic space, which is the one a Japanese
     * keyboard produces — a sentence spaced with 　 then looked like a single
     * run and slipped past every rule below.
     */
    private val WHITESPACE = Regex("[\\s\u3000]+")

    /**
     * Bracket characters that wrap a reading or an annotation next to the
     * headword. `[` and `]` are missing on purpose: those are ruby.
     */
    private val BRACKETS = Regex("[【】（）〔〕｛｝「」『』()]+")

    /**
     * Block-level markup ends a line. Stripping it to a space instead glued
     * `<div>時間</div><div>じかん</div>` into one run pair that then failed
     * every later test — two perfectly good keys lost to formatting.
     */
    private val BLOCK_TAG = Regex("(?i)<\\s*/?\\s*(br|div|p|li|ul|ol|tr|td|th|h[1-6])[^>]*>")
    private val HTML_TAG = Regex("<[^>]*>")
    private val SOUND_OR_IMAGE = Regex("\\[(sound|anki):[^]]*]")

    /**
     * Separators a hand-rolled deck uses to put several spellings in ONE
     * field (`持って来る / 持ってくる`, `行く;いく`). Each piece is indexed on
     * its own. The Japanese comma is deliberately absent: 、 is prose
     * punctuation, and splitting on it would mine words out of sentences.
     */
    private val LIST_SEPARATOR = Regex("[\\n\\r;；/／|｜]+")

    /**
     * `漢字[かんじ]` ruby notation, the format Core 2k/6k/10k and Kaishi 1.5k
     * use in their reading fields.
     */
    private val FURIGANA = Regex("([\\p{IsHan}々ヶ]+)\\[([\\p{IsHiragana}\\p{IsKatakana}ー]+)]")

    /** Adds every indexable word of one note's raw `flds` blob to [out]. */
    fun collectKeysFromNote(flds: String, out: MutableSet<String>) {
        for (field in flds.split(FIELD_SEPARATOR)) {
            collectKeys(field, out)
        }
    }

    /** Convenience for tests and one-off callers. */
    fun keysFromNote(flds: String): Set<String> =
        HashSet<String>().also { collectKeysFromNote(flds, it) }

    fun collectKeys(rawField: String, out: MutableSet<String>) {
        var text = SOUND_OR_IMAGE.replace(rawField, " ")
        text = BLOCK_TAG.replace(text, "\n")
        text = HTML_TAG.replace(text, " ")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        for (piece in text.split(LIST_SEPARATOR)) {
            collectFromPiece(piece.trim(), out)
        }
    }

    /** One field, or one spelling out of a field that listed several. */
    private fun collectFromPiece(text: String, out: MutableSet<String>) {
        if (text.isEmpty() || text.length > MAX_RAW_FIELD_LENGTH) return
        val runs = text.split(WHITESPACE).filter { it.isNotBlank() }
        if (runs.isEmpty()) return

        if (FURIGANA.containsMatchIn(text)) {
            if (!rubyRunsLookLikeHeadword(runs)) return
            // 食[た]べる -> expression 食べる AND reading たべる, so a deck that
            // only stores the ruby form still matches on either — plus the
            // mixed spellings in between (持[も]って 来[く]る also yields
            // 持ってくる), which is how the compound verbs a deck writes in
            // kana meet the dictionary's kanji headword.
            for (variant in rubyVariants(text)) addKey(variant, out)
            return
        }

        if (runs.size > MAX_RUNS) return

        // Brackets around a reading or a marker are a widespread hand-rolled
        // format: 食べる【たべる】, 食べる（たべる）, 食べる (v1). The whole field
        // used to be thrown away, because a bracket is not a Japanese
        // character — so every note in such a deck contributed NOTHING to the
        // index and its words all looked missing. Index each piece instead;
        // anything that is not a short Japanese run still falls out below.
        // Square brackets are deliberately not delimiters here — they are ruby
        // notation, handled above.
        if (BRACKETS.containsMatchIn(text)) {
            for (piece in text.split(BRACKETS)) addKey(piece, out)
            return
        }
        addKey(text, out)
    }

    /**
     * Tells a ruby headword apart from a ruby SENTENCE.
     *
     * AnkiDroid's furigana notation only ever puts a space in front of a
     * kanji-reading group, so every run of a single word starts with a kanji —
     * bar a leading kana prefix (お 願[ねが]い). A kana-only run in the middle
     * can only come from words having been spaced apart by hand
     * (`私[わたし] は 毎日[まいにち] …`), which strips down to a flawless
     * Japanese key nothing else would reject.
     *
     * The old rule — at most two runs — answered the same question by counting,
     * and threw away every compound written with three kanji blocks
     * (`落[お]ち 着[つ]き 払[はら]う`, `持[も]って 来[く]る` in decks that space
     * the て-form) along with the sentences.
     */
    private fun rubyRunsLookLikeHeadword(runs: List<String>): Boolean =
        runs.drop(1).all { isKanji(it.first()) }

    /**
     * Every spelling of a ruby field: each kanji block either kept or written
     * out as its reading. `持[も]って 来[く]る` → 持って来る, 持ってくる,
     * もって来る, もってくる.
     */
    private fun rubyVariants(text: String): List<String> {
        val matches = FURIGANA.findAll(text).toList()
        if (matches.isEmpty()) return listOf(text)
        if (matches.size > MAX_VARIANT_GROUPS) {
            // Too many blocks to enumerate; the two forms that matter most are
            // the all-kanji and the all-kana one.
            return listOf(
                FURIGANA.replace(text) { it.groupValues[1] },
                FURIGANA.replace(text) { it.groupValues[2] }
            )
        }
        val out = ArrayList<String>(1 shl matches.size)
        for (mask in 0 until (1 shl matches.size)) {
            val builder = StringBuilder(text.length)
            var cursor = 0
            matches.forEachIndexed { i, match ->
                builder.append(text, cursor, match.range.first)
                val useReading = (mask shr i) and 1 == 1
                builder.append(match.groupValues[if (useReading) 2 else 1])
                cursor = match.range.last + 1
            }
            builder.append(text, cursor, text.length)
            out.add(builder.toString())
        }
        return out
    }

    fun normalizeKey(value: String): String =
        value.filterNot { it.isWhitespace() }
            .trim('～', '〜', '~', '・', '.', '·')

    fun isKanaOnly(value: String): Boolean = value.isNotEmpty() && value.all { ch ->
        ch in 'ぁ'..'ゟ' || ch in 'ァ'..'ヿ' || ch == 'ー' || ch == '・'
    }

    fun isKanji(ch: Char): Boolean =
        ch in '一'..'鿿' || ch == '々' || ch == 'ヶ' || ch == 'ヵ'

    private fun isJapanese(value: String): Boolean = value.isNotEmpty() && value.all { ch ->
        ch in 'ぁ'..'ゟ' || ch in 'ァ'..'ヿ' || ch == 'ー' || ch == '・' || isKanji(ch)
    }

    private fun addKey(value: String, out: MutableSet<String>) {
        val key = normalizeKey(value)
        if (key.isNotEmpty() && key.length <= MAX_KEY_LENGTH && isJapanese(key)) {
            out.add(key)
        }
    }

    /** 2^4 spellings is already more than any real headword needs. */
    private const val MAX_VARIANT_GROUPS = 4
}
