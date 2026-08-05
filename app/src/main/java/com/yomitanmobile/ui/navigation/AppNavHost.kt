package com.yomitanmobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.yomitanmobile.ui.cardstyle.CardStyleScreen
import com.yomitanmobile.ui.detail.DetailScreen
import com.yomitanmobile.ui.dictionaries.DictionariesScreen
import com.yomitanmobile.ui.download.DictionaryDownloadScreen
import com.yomitanmobile.ui.favorites.FavoritesScreen
import com.yomitanmobile.ui.search.SearchScreen
import com.yomitanmobile.ui.settings.FrequencyDisplayScreen
import com.yomitanmobile.ui.settings.SettingsScreen
import com.yomitanmobile.ui.setup.SetupScreen
import com.yomitanmobile.ui.statistics.StatisticsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Search.route,
    focusSearch: Boolean = false,
    sharedSearchQuery: String? = null,
    sharedSearchNonce: Int = 0
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Setup.route) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Search.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onWordClick = { entryId ->
                    navController.navigate(Screen.Detail.createRoute(entryId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onFavoritesClick = {
                    navController.navigate(Screen.Favorites.route)
                },
                focusSearch = focusSearch,
                initialQuery = sharedSearchQuery,
                initialQueryNonce = sharedSearchNonce,
                onNavigateToStatistics = { navController.navigate(Screen.Statistics.route) }
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(
                navArgument("entryId") { type = NavType.LongType }
            )
        ) {
            DetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDownload = {
                    navController.navigate(Screen.DictionaryDownload.route)
                },
                onNavigateToStatistics = {
                    navController.navigate(Screen.Statistics.route)
                },
                onNavigateToCardStyle = {
                    navController.navigate(Screen.CardStyle.route)
                },
                onNavigateToDictionaries = {
                    navController.navigate(Screen.Dictionaries.route)
                },
                onNavigateToFrequencyDisplay = {
                    navController.navigate(Screen.FrequencyDisplay.route)
                },
                onNavigateToJlptDeck = {
                    navController.navigate(Screen.JlptDeck.route)
                },
                onNavigateToAnkiScan = {
                    navController.navigate(Screen.AnkiScan.route)
                }
            )
        }

        composable(Screen.AnkiScan.route) {
            com.yomitanmobile.ui.ankiscan.AnkiScanScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.JlptDeck.route) {
            com.yomitanmobile.ui.jlptdeck.JlptDeckScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.FrequencyDisplay.route) {
            FrequencyDisplayScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CardStyle.route) {
            CardStyleScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.DictionaryDownload.route) {
            DictionaryDownloadScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Statistics.route) {
            StatisticsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Dictionaries.route) {
            DictionariesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Categories.route) {
            com.yomitanmobile.ui.statistics.CategoriesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Favorites.route) {
            FavoritesScreen(
                onNavigateBack = { navController.popBackStack() },
                onWordClick = { entryId ->
                    navController.navigate(Screen.Detail.createRoute(entryId))
                }
            )
        }
    }
}
