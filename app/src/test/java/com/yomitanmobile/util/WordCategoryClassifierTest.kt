package com.yomitanmobile.util

import com.yomitanmobile.domain.model.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class WordCategoryClassifierTest {

    @Test
    fun classify_detectsFoodCategory() {
        val entry = WordEntry(
            expression = "食べる",
            reading = "たべる",
            definitions = listOf("to eat", "to consume food")
        )

        val category = WordCategoryClassifier.classify(entry)

        assertEquals(WordCategoryClassifier.CATEGORY_FOOD, category)
    }

    @Test
    fun classify_detectsTravelCategory() {
        val entry = WordEntry(
            expression = "空港",
            reading = "くうこう",
            definitions = listOf("airport", "air terminal")
        )

        val category = WordCategoryClassifier.classify(entry)

        assertEquals(WordCategoryClassifier.CATEGORY_TRAVEL, category)
    }

    @Test
    fun classify_detectsEconomyCategory() {
        val entry = WordEntry(
            expression = "インフレ",
            reading = "いんふれ",
            definitions = listOf("inflation", "economic growth in prices")
        )

        val category = WordCategoryClassifier.classify(entry)

        assertEquals(WordCategoryClassifier.CATEGORY_ECONOMY, category)
    }

    @Test
    fun classify_returnsOtherForUnknownWords() {
        val entry = WordEntry(
            expression = "曖昧",
            reading = "あいまい",
            definitions = listOf("ambiguous", "vague")
        )

        val category = WordCategoryClassifier.classify(entry)

        assertEquals(WordCategoryClassifier.CATEGORY_OTHER, category)
    }

    // ----- Regression tests for the score-based classifier ------------------
    //
    // Pre-fix behaviour was "first rule with any matching keyword wins". That
    // gave wrong answers whenever a word lived in two categories' keyword
    // lists — FOOD was first so anything with "meat", "fish", or 肉 / 魚 in
    // it got classified as FOOD regardless of actual sense. Score-based
    // matching counts hits per rule and picks the highest scorer.

    @Test
    fun classify_bloodRelative_picksRelationshipsNotFood() {
        // 肉親 = blood relative; one's own flesh and blood. The kanji 肉
        // literally means "meat" and used to trigger FOOD; the English
        // definition has *no* food keywords but multiple RELATIONSHIPS
        // keywords ("blood", "relative", "kin", "family").
        val entry = WordEntry(
            expression = "肉親",
            reading = "にくしん",
            definitions = listOf("blood relative", "one's kin", "family member")
        )

        val category = WordCategoryClassifier.classify(entry)

        assertEquals(WordCategoryClassifier.CATEGORY_RELATIONSHIPS, category)
    }

    @Test
    fun classify_teacher_picksEducationNotWork() {
        // 先生 is the canonical "lives in multiple buckets" word — teacher
        // (EDUCATION), doctor (HEALTH), boss-ish honorific (WORK). The
        // primary definition is overwhelmingly EDUCATION-coded.
        val entry = WordEntry(
            expression = "先生",
            reading = "せんせい",
            definitions = listOf("teacher", "instructor", "professor")
        )

        val category = WordCategoryClassifier.classify(entry)

        assertEquals(WordCategoryClassifier.CATEGORY_EDUCATION, category)
    }

    @Test
    fun classify_stockMarket_picksEconomyNotShopping() {
        // "market" appears in both ECONOMY and SHOPPING. ECONOMY wins by
        // accumulated score (stock, investment, share, trade).
        val entry = WordEntry(
            expression = "株式市場",
            reading = "かぶしきしじょう",
            definitions = listOf("stock market", "share trading", "investment exchange")
        )

        val category = WordCategoryClassifier.classify(entry)

        assertEquals(WordCategoryClassifier.CATEGORY_ECONOMY, category)
    }

    @Test
    fun classify_blankEntry_returnsOther() {
        val entry = WordEntry(
            expression = "",
            reading = "",
            definitions = emptyList()
        )

        assertEquals(
            WordCategoryClassifier.CATEGORY_OTHER,
            WordCategoryClassifier.classify(entry)
        )
    }

    @Test
    fun classify_isDeterministicAcrossRuns() {
        // Same input must always classify the same way — guards against
        // accidental Set/Map iteration-order dependence in the rule list.
        val entry = WordEntry(
            expression = "病院",
            reading = "びょういん",
            definitions = listOf("hospital", "clinic")
        )

        val first = WordCategoryClassifier.classify(entry)
        val second = WordCategoryClassifier.classify(entry)
        val third = WordCategoryClassifier.classify(entry)

        assertEquals(WordCategoryClassifier.CATEGORY_HEALTH, first)
        assertEquals(first, second)
        assertEquals(second, third)
    }

    @Test
    fun classify_multipleCategoryKeywords_picksStrongestBucket() {
        // Definition string deliberately seeds three FOOD keywords and one
        // ANIMALS keyword — FOOD should win by score, not by being earlier
        // in the rule list (which it already is — this test would pass
        // even with the broken first-wins implementation, so we pair it
        // with the inverse case below).
        val foodHeavy = WordEntry(
            expression = "夕食",
            reading = "ゆうしょく",
            definitions = listOf("dinner; evening meal; a hearty supper of fish and rice")
        )
        assertEquals(
            WordCategoryClassifier.CATEGORY_FOOD,
            WordCategoryClassifier.classify(foodHeavy)
        )

        // ANIMALS-heavy definition with a single food-coded word ("fish").
        // Pre-fix this returned FOOD because FOOD is earlier in the rule
        // list; post-fix ANIMALS wins on score.
        val animalsHeavy = WordEntry(
            expression = "水族館",
            reading = "すいぞくかん",
            definitions = listOf("aquarium with tropical fish, sharks, dolphins, and sea turtles")
        )
        assertEquals(
            WordCategoryClassifier.CATEGORY_ANIMALS,
            WordCategoryClassifier.classify(animalsHeavy)
        )
    }

    @Test
    fun classify_posTagsAndReadingDoNotForceMatches() {
        // POS tags ("v1", "vt", "n") and the kana reading are part of the
        // current haystack. Make sure a word whose definition has no
        // keyword overlap with any rule still resolves to OTHER even when
        // its POS or reading happens to share substrings with rule terms.
        val entry = WordEntry(
            expression = "曖昧",
            reading = "あいまい",
            definitions = listOf("ambiguous", "vague"),
            partsOfSpeech = "adj-na, n"
        )

        assertEquals(
            WordCategoryClassifier.CATEGORY_OTHER,
            WordCategoryClassifier.classify(entry)
        )
    }
}
