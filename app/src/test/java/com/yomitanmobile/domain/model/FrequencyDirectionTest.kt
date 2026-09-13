package com.yomitanmobile.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a rank list from a list of raw occurrence counts, which the Yomitan
 * meta format does not say and which the whole app depends on: read backwards,
 * a count list claims the commonest word in Japanese is the rarest one.
 */
class FrequencyDirectionTest {

    private fun isCountBased(
        name: String = "list",
        rowCount: Int,
        distinctValues: Int = rowCount,
        maxValue: Int
    ) = FrequencyDirection.isCountBased(name, rowCount, distinctValues, maxValue)

    @Test
    fun `a rank list is a permutation of its own length`() {
        // JPDB: 180 000 words ranked 1..180 000, each rank used once.
        assertFalse(isCountBased(name = "JPDBv2", rowCount = 180_000, maxValue = 180_000))
    }

    @Test
    fun `a rank list with a few ties is still a rank list`() {
        assertFalse(
            isCountBased(
                name = "BCCWJ_SUW_LUW_combined",
                rowCount = 80_000,
                distinctValues = 79_000,
                maxValue = 79_000
            )
        )
    }

    @Test
    fun `counts run far past the number of words they cover`() {
        // Innocent Corpus: 300 000 words, the commonest seen 5 million times.
        assertTrue(
            isCountBased(
                name = "Anime word counts",
                rowCount = 300_000,
                distinctValues = 40_000,
                maxValue = 5_000_000
            )
        )
    }

    @Test
    fun `counts that stay small are caught by how heavily they repeat`() {
        // A small corpus: values only reach 4 000, but two thirds of the words
        // were seen once or twice, which no rank list ever looks like.
        assertTrue(
            isCountBased(
                rowCount = 50_000,
                distinctValues = 900,
                maxValue = 4_000
            )
        )
    }

    @Test
    fun `a list too small to judge is assumed to be ranked`() {
        // The safe direction: a wrongly converted list is visible in the UI,
        // a wrongly ranked tiny list changes almost nothing.
        assertFalse(isCountBased(rowCount = 20, distinctValues = 2, maxValue = 900_000))
    }

    @Test
    fun `a name that says what the numbers are settles it`() {
        assertTrue(isCountBased(name = "Innocent Corpus", rowCount = 10, maxValue = 10))
    }
}
