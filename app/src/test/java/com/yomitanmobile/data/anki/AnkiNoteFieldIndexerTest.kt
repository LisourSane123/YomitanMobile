package com.yomitanmobile.data.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The duplicate check has to work against decks this app never created, so
 * these cases are modelled on the real field layouts of the two decks people
 * actually own: Core 2k/6k/10k and Kaishi 1.5k.
 */
class AnkiNoteFieldIndexerTest {

    private val sep = '\u001f'

    private fun note(vararg fields: String) = fields.joinToString(sep.toString())

    @Test
    fun indexesCore2kVocabularyFields() {
        // Core 2k/6k: Vocabulary-Kanji, Vocabulary-Furigana, Vocabulary-Kana,
        // Vocabulary-English, Expression, Sentence…
        val keys = AnkiNoteFieldIndexer.keysFromNote(
            note(
                "食べる",
                "食[た]べる",
                "たべる",
                "to eat",
                "私は毎日野菜を食べる。",
                "[sound:core_1234.mp3]"
            )
        )

        assertTrue("食べる" in keys)
        assertTrue("たべる" in keys)
        // The sentence must never be indexed — it would match half the level.
        assertFalse("私は毎日野菜を食べる。" in keys)
        assertFalse("toeat" in keys)
    }

    @Test
    fun indexesKaishiWordAndRubyReading() {
        // Kaishi 1.5k: Word, Word Reading, Word Meaning, Sentence…
        val keys = AnkiNoteFieldIndexer.keysFromNote(
            note("新しい", "新[あたら]しい", "new", "新しい車を買った。")
        )

        assertTrue("新しい" in keys)
        assertTrue("あたらしい" in keys)
    }

    @Test
    fun stripsHtmlAndSoundTags() {
        val keys = AnkiNoteFieldIndexer.keysFromNote(
            note("<div>時間</div>", "[sound:jp_1.mp3]", "<b>じかん</b>")
        )

        assertTrue("時間" in keys)
        assertTrue("じかん" in keys)
    }

    @Test
    fun ignoresLatinAndEmptyFields() {
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("time", "", "  ", "N5"))

        assertTrue(keys.isEmpty())
    }

    @Test
    fun ignoresSpacedFuriganaSentences() {
        // Core's Expression field: a whole sentence in Anki ruby notation.
        // Nothing else rejects it — strip the brackets and it is a flawless
        // Japanese string of ten characters.
        val keys = AnkiNoteFieldIndexer.keysFromNote(
            note("私[わたし] は 毎日[まいにち] 野菜[やさい] を 食[た]べる")
        )

        assertTrue(keys.toString(), keys.isEmpty())
    }

    @Test
    fun ignoresUnpunctuatedRunningText() {
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("今日はとてもいい天気だから散歩に行こう"))

        assertTrue(keys.toString(), keys.isEmpty())
    }

    @Test
    fun indexesALongRubyHeadword() {
        // 18 raw characters, 10 once the readings are resolved.
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("取[と]り返[かえ]しのつかない"))

        assertTrue("取り返しのつかない" in keys)
        assertTrue("とりかえしのつかない" in keys)
    }

    @Test
    fun kanaWordMatchesOnReadingButKanjiWordDoesNot() {
        val index = AnkiCollectionIndex.Index(
            keys = setOf("きく", "食べる"),
            noteCount = 2,
            available = true
        )

        // Kana-only entry: reading match is the only signal there is.
        assertTrue(index.contains("きく", "きく"))
        // 聞く / 効く / 菊 all read きく — a reading hit must NOT mark the
        // kanji word as already present.
        assertFalse(index.contains("聞く", "きく"))
        assertTrue(index.contains("食べる", "たべる"))
    }

    @Test
    fun unavailableIndexNeverClaimsAMatch() {
        assertFalse(AnkiCollectionIndex.Index.EMPTY.contains("食べる", "たべる"))
    }
}
