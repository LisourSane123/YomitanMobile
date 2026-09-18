package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.usecase.WordFilterRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The generator made cards for words I already have."
 *
 * Every case here is a note shape a real shared deck uses. Two of them used to
 * answer "not in the collection" for a word the collection plainly held:
 *
 *  - a field that wraps the reading in brackets (食べる【たべる】, 食べる（たべる）)
 *    contributed NOTHING to the index, because a bracket is not a Japanese
 *    character and the whole field was discarded;
 *  - a word normally written in kana (ください) stored by the deck in kana, while
 *    the dictionary candidate carries the kanji spelling 下さい.
 *
 * The homophone guard those two fixes must not break is the last test: 帰る is
 * still missing from a collection that only holds 変える, even though both read
 * かえる.
 */
class AnkiCollectionDedupTest {

    private val sep = ''

    private fun index(vararg notes: String): AnkiCollectionIndex.Index {
        val keys = HashSet<String>()
        notes.forEach { AnkiNoteFieldIndexer.collectKeysFromNote(it, keys) }
        return AnkiCollectionIndex.Index(keys, notes.size, available = true)
    }

    private fun word(
        expression: String,
        reading: String,
        usageTags: List<String> = emptyList(),
        partsOfSpeech: List<String> = listOf("n")
    ) = MergedWordEntry(
        primaryId = 1,
        primaryExpression = expression,
        reading = reading,
        definitions = listOf("gloss"),
        alternativeExpressions = emptyList(),
        usageTags = usageTags,
        partsOfSpeech = partsOfSpeech
    )

    private fun AnkiCollectionIndex.Index.holds(entry: MergedWordEntry) = contains(
        entry.primaryExpression,
        entry.reading,
        readingCountsAlone = WordFilterRules.isUsuallyKana(entry)
    )

    @Test
    fun `separate kanji and kana fields, as Core 2k has them`() {
        val collection = index("食べる${sep}たべる${sep}to eat${sep}私はパンを食べる。")
        assertTrue(collection.holds(word("食べる", "たべる")))
    }

    @Test
    fun `ruby in the reading field, as Kaishi has it`() {
        val collection = index("出来る${sep}出[で]来[き]る${sep}to be able to")
        assertTrue(collection.holds(word("出来る", "できる")))
        assertTrue(collection.holds(word("できる", "できる")))
    }

    @Test
    fun `a reading wrapped in brackets is still indexed`() {
        assertTrue(index("食べる【たべる】${sep}to eat").holds(word("食べる", "たべる")))
        assertTrue(index("食べる（たべる）${sep}to eat").holds(word("食べる", "たべる")))
        assertTrue(index("食べる (v1)${sep}to eat").holds(word("食べる", "たべる")))
    }

    @Test
    fun `a kana-only deck holds the word the dictionary spells with kanji`() {
        val collection = index("ください${sep}please${sep}水をください。")
        assertTrue(
            collection.holds(word("下さい", "ください", usageTags = listOf("usually kana")))
        )
        // The raw JMdict tag on the part-of-speech side counts the same.
        assertTrue(
            collection.holds(word("下さい", "ください", partsOfSpeech = listOf("exp", "uk")))
        )
    }

    @Test
    fun `a kanji deck holds the word the dictionary spells with kana`() {
        val collection = index("有難う${sep}ありがとう${sep}thank you")
        assertTrue(collection.holds(word("ありがとう", "ありがとう")))
    }

    @Test
    fun `homophones do not swallow each other`() {
        val collection = index("変える${sep}かえる${sep}to change")
        // Both read かえる, and 帰る is normally written in kanji — so it is
        // genuinely missing and must still get a card.
        assertFalse(collection.holds(word("帰る", "かえる")))
    }

    /**
     * The reported case: "I made a deck from a book and it gave me cards for
     * 綺麗 and 傷つく, which are already in my deck."
     *
     * JMdict files every spelling of a word under one sequence, and the deck
     * holds whichever one its author typed. `MergedWordEntry` cannot supply
     * the siblings — `mergeEntries` groups by (expression, reading), so
     * `alternativeExpressions` is structurally always empty — so they are read
     * from the database by sequence and passed in here. See
     * `WrittenFormsBySequenceDbTest` for that half.
     */
    private fun AnkiCollectionIndex.Index.holdsAnyOf(
        entry: MergedWordEntry,
        writtenForms: List<String>
    ) = containsAny(
        listOf(entry.primaryExpression) + writtenForms,
        entry.reading,
        readingCountsAlone = WordFilterRules.isUsuallyKana(entry)
    )

    @Test
    fun `a deck writing the word with the other kanji is not a second word`() {
        val collection = index("傷付く${sep}きずつく${sep}to be hurt")
        val entry = word("傷つく", "きずつく", partsOfSpeech = listOf("v5k", "vi"))

        // The headword alone is what the generator used to compare, and it is
        // why the card came back.
        assertFalse(collection.holds(entry))
        assertTrue(collection.holdsAnyOf(entry, listOf("傷つく", "傷付く", "疵つく")))
    }

    /**
     * 綺麗 reached the collection by a second route that happened to work: it
     * is tagged `uk`, so a deck holding きれい in a reading field matched on
     * the reading alone. Take that route away — a deck whose note is just the
     * spelling — and the word is missing again, because 奇麗 is a different
     * string. The spelling path has to carry it on its own.
     */
    @Test
    fun `綺麗 and 奇麗 are one word`() {
        val spellingOnly = index("奇麗")
        val entry = word("綺麗", "きれい", usageTags = listOf("usually kana"))

        assertFalse(spellingOnly.holds(entry))
        assertTrue(spellingOnly.holdsAnyOf(entry, listOf("綺麗", "奇麗")))

        // And with no `uk` tag the reading route is closed even when the deck
        // does store one — an adjective the dictionary calls kanji-written.
        val withReading = index("奇麗${sep}きれい${sep}pretty")
        val notKana = word("綺麗", "きれい", partsOfSpeech = listOf("adj-na"))
        assertFalse(withReading.holds(notKana))
        assertTrue(withReading.holdsAnyOf(notKana, listOf("綺麗", "奇麗")))
    }

    /**
     * The guard the fix must not cost: written forms come from ONE sequence,
     * so a homophone never joins the list. Passing them in cannot make 帰る
     * match a collection holding only 変える.
     */
    @Test
    fun `supplying written forms does not open the homophone door`() {
        val collection = index("変える${sep}かえる${sep}to change")
        val entry = word("帰る", "かえる", partsOfSpeech = listOf("v5r", "vi"))

        assertFalse(collection.holdsAnyOf(entry, listOf("帰る", "還る", "歸る")))
    }

    @Test
    fun `a sentence field does not poison the index`() {
        val collection = index("毎日${sep}まいにち${sep}私は毎日野菜を食べる。")
        assertFalse(collection.holds(word("野菜", "やさい")))
        assertTrue(collection.holds(word("毎日", "まいにち")))
    }
}
