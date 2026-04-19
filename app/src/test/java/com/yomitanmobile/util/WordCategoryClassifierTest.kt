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
}
