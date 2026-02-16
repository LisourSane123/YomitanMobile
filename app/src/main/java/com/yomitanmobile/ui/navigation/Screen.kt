package com.yomitanmobile.ui.navigation

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Search : Screen("search")
    data object Detail : Screen("detail/{entryId}") {
        fun createRoute(entryId: Long): String = "detail/$entryId"
    }
    data object Settings : Screen("settings")
    data object DictionaryDownload : Screen("dictionary_download")
}
