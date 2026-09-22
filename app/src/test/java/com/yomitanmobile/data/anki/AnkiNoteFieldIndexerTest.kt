package com.yomitanmobile.data.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun ignoresLatinAndEmptyFieldsBesideTheWord() {
        // A Japanese note's English gloss and tags are not words it holds.
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("時間", "time", "", "  ", "N5"))

        assertEquals(setOf("時間"), keys)
    }

    @Test
    fun aLatinFirstFieldIsTheEnglishWordAndNothingElse() {
        // An English deck: the first field is its word (see LatinDuplicateTest).
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("time", "", "  ", "N5"))

        assertEquals(setOf("time"), keys)
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
    fun indexesRubyCompoundsSpacedOverThreeKanjiBlocks() {
        // The old "at most two runs" rule threw these away with the sentences.
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("落[お]ち 着[つ]き 払[はら]う"))

        assertTrue(keys.toString(), "落ち着き払う" in keys)
        assertTrue(keys.toString(), "おちつきはらう" in keys)
    }

    @Test
    fun indexesMixedSpellingsOfARubyCompound() {
        // The deck writes 来る with kanji, the dictionary headword the user
        // mines may not — and the other way round.
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("持[も]って 来[く]る"))

        assertTrue(keys.toString(), "持って来る" in keys)
        assertTrue(keys.toString(), "持ってくる" in keys)
        assertTrue(keys.toString(), "もってくる" in keys)
    }

    @Test
    fun indexesEverySpellingListedInOneField() {
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("持って来る / 持ってくる"))

        assertTrue("持って来る" in keys)
        assertTrue("持ってくる" in keys)
    }

    @Test
    fun blockMarkupSeparatesTwoFieldsWorthOfWords() {
        val keys = AnkiNoteFieldIndexer.keysFromNote(note("<div>時間</div><div>じかん</div>"))

        assertTrue(keys.toString(), "時間" in keys)
        assertTrue(keys.toString(), "じかん" in keys)
        assertFalse("時間じかん" in keys)
    }

    @Test
    fun compoundVerbMatchesTheOtherSpelling() {
        val index = AnkiCollectionIndex.Index(
            keys = setOf("持ってくる"),
            noteCount = 1,
            available = true
        )

        assertTrue(index.contains("持って来る", "もってくる"))
        // The reverse (deck keeps the kanji, dictionary headword is kana) has
        // no reading to fold on the deck's side, so it is the entry's
        // alternative spellings that close it — see [alternativeSpellingsCount].
    }

    @Test
    fun spellingVariantsNeverDegradeIntoAReadingMatch() {
        val index = AnkiCollectionIndex.Index(setOf("きく"), 1, available = true)

        // 聞く's only variant besides itself IS the reading, which stays
        // governed by the homophone rule.
        assertFalse(index.contains("聞く", "きく"))
    }

    @Test
    fun alternativeSpellingsCount() {
        val index = AnkiCollectionIndex.Index(setOf("弁える"), 1, available = true)

        assertFalse(index.contains("辨える", "わきまえる"))
        assertTrue(index.containsAny(listOf("辨える", "弁える"), "わきまえる"))
    }

    @Test
    fun unavailableIndexNeverClaimsAMatch() {
        assertFalse(AnkiCollectionIndex.Index.EMPTY.contains("食べる", "たべる"))
    }
}
