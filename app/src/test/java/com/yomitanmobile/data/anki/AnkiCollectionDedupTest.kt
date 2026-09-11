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

    @Test
    fun `a sentence field does not poison the index`() {
        val collection = index("毎日${sep}まいにち${sep}私は毎日野菜を食べる。")
        assertFalse(collection.holds(word("野菜", "やさい")))
        assertTrue(collection.holds(word("毎日", "まいにち")))
    }
}
