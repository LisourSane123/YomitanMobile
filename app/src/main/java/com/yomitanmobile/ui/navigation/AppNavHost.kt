package com.yomitanmobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.yomitanmobile.ui.detail.DetailScreen
import com.yomitanmobile.ui.download.DictionaryDownloadScreen
import com.yomitanmobile.ui.search.SearchScreen
import com.yomitanmobile.ui.settings.SettingsScreen
import com.yomitanmobile.ui.setup.SetupScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Search.route
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
                }
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
                }
            )
        }

        composable(Screen.DictionaryDownload.route) {
            DictionaryDownloadScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
