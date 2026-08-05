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
     * Fields longer than this are sentences, meanings or notes — never a
     * headword — and indexing them would produce false duplicates.
     */
    private const val MAX_FIELD_LENGTH = 24

    private val HTML_TAG = Regex("<[^>]*>")
    private val SOUND_OR_IMAGE = Regex("\\[(sound|anki):[^]]*]")

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
        text = HTML_TAG.replace(text, " ")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
        if (text.isEmpty() || text.length > MAX_FIELD_LENGTH) return

        if (FURIGANA.containsMatchIn(text)) {
            // 食[た]べる -> expression 食べる AND reading たべる, so a deck that
            // only stores the ruby form still matches on either.
            addKey(FURIGANA.replace(text) { it.groupValues[1] }, out)
            addKey(FURIGANA.replace(text) { it.groupValues[2] }, out)
            return
        }
        addKey(text, out)
    }

    fun normalizeKey(value: String): String =
        value.filterNot { it.isWhitespace() }
            .trim('～', '〜', '~', '・', '.', '·')

    fun isKanaOnly(value: String): Boolean = value.isNotEmpty() && value.all { ch ->
        ch in 'ぁ'..'ゟ' || ch in 'ァ'..'ヿ' || ch == 'ー' || ch == '・'
    }

    private fun isJapanese(value: String): Boolean = value.isNotEmpty() && value.all { ch ->
        ch in 'ぁ'..'ゟ' || ch in 'ァ'..'ヿ' || ch == 'ー' || ch == '・' ||
            ch in '一'..'鿿' || ch == '々' || ch == 'ヶ' || ch == 'ヵ'
    }

    private fun addKey(value: String, out: MutableSet<String>) {
        val key = normalizeKey(value)
        if (key.isNotEmpty() && key.length <= MAX_FIELD_LENGTH && isJapanese(key)) {
            out.add(key)
        }
    }
}
