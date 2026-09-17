package com.yomitanmobile.ui.navigation

sealed class Screen(val route: String) {
    data object LanguageSelect : Screen("language_select")
    data object Setup : Screen("setup")
    data object Search : Screen("search")
    data object Detail : Screen("detail/{entryId}") {
        fun createRoute(entryId: Long): String = "detail/$entryId"
    }
    data object Settings : Screen("settings")
    data object DictionaryDownload : Screen("dictionary_download")
    data object Statistics : Screen("statistics")
    data object Categories : Screen("categories")
    data object CardStyle : Screen("card_style")
    data object FrequencyDisplay : Screen("frequency_display")
    data object Favorites : Screen("favorites")
    data object Dictionaries : Screen("dictionaries")
    data object JlptDeck : Screen("jlpt_deck")
    /** The kanji browser: buckets, coverage, the characters themselves. */
    data object Kanji : Screen("kanji")
    data object KanjiDetail : Screen("kanji/{kanji}") {
        fun createRoute(kanji: String): String = "kanji/$kanji"
    }
    data object AnkiScan : Screen("anki_scan")
    data object TextScan : Screen("text_scan")
    /** The hub the bottom bar opens: everything that builds cards in bulk. */
    data object Tools : Screen("tools")
    data object Backup : Screen("backup")
}
