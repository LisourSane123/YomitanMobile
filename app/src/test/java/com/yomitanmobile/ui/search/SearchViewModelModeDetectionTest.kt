package com.yomitanmobile.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchViewModelModeDetectionTest {

    @Test
    fun detectSearchMode_returnsJapanese_forJapaneseText() {
        val mode = SearchViewModel.detectSearchMode("食べる")

        assertEquals(SearchMode.JAPANESE, mode)
    }

    @Test
    fun detectSearchMode_returnsEnglish_forLatinText() {
        val mode = SearchViewModel.detectSearchMode("eat")

        assertEquals(SearchMode.ENGLISH, mode)
    }

    @Test
    fun detectSearchMode_returnsJapanese_forBlankText() {
        val mode = SearchViewModel.detectSearchMode("   ")

        assertEquals(SearchMode.JAPANESE, mode)
    }

    @Test
    fun shouldUseRomajiFallback_true_forConvertedRomaji() {
        val shouldFallback = SearchViewModel.shouldUseRomajiFallback(
            query = "taberu",
            romajiConverted = "たべる"
        )

        assertTrue(shouldFallback)
    }

    @Test
    fun shouldUseRomajiFallback_false_forUnchangedText() {
        val shouldFallback = SearchViewModel.shouldUseRomajiFallback(
            query = "teacher",
            romajiConverted = "teacher"
        )

        assertFalse(shouldFallback)
    }

    @Test
    fun modeForQueryEdit_autoDetects_whenNotManuallyOverridden() {
        assertEquals(
            SearchMode.JAPANESE,
            SearchViewModel.modeForQueryEdit(SearchMode.ENGLISH, "食べる", manualOverride = false)
        )
        assertEquals(
            SearchMode.ENGLISH,
            SearchViewModel.modeForQueryEdit(SearchMode.JAPANESE, "eat", manualOverride = false)
        )
    }

    @Test
    fun modeForQueryEdit_keepsManualMode_whileTyping() {
        // The core of the fix: a manually chosen mode (here ROMAJI, which
        // auto-detect can never produce) survives subsequent keystrokes.
        assertEquals(
            SearchMode.ROMAJI,
            SearchViewModel.modeForQueryEdit(SearchMode.ROMAJI, "neko", manualOverride = true)
        )
        assertEquals(
            SearchMode.ENGLISH,
            SearchViewModel.modeForQueryEdit(SearchMode.ENGLISH, "食べる", manualOverride = true)
        )
    }
}
