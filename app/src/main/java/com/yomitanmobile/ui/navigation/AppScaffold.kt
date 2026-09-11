package com.yomitanmobile.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * The app frame: the navigation graph plus the bottom bar, which shows only on
 * the four top-level destinations. A detail view, a deck generator or the
 * first-run setup gets the whole screen — they are places you finish and leave,
 * not places you switch between.
 */
@Composable
fun AppScaffold(
    navController: NavHostController,
    startDestination: String,
    focusSearch: Boolean = false,
    sharedSearchQuery: String? = null,
    sharedSearchNonce: Int = 0
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val showBottomBar = backStackEntry?.destination?.route in BOTTOM_ROUTES

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = { if (showBottomBar) AppBottomBar(navController) }
    ) { padding ->
        // Only the bottom inset is ours to apply; every screen draws its own
        // top bar and would otherwise be pushed down twice.
        AppNavHost(
            navController = navController,
            startDestination = startDestination,
            focusSearch = focusSearch,
            sharedSearchQuery = sharedSearchQuery,
            sharedSearchNonce = sharedSearchNonce,
            modifier = Modifier.padding(PaddingValues(bottom = padding.calculateBottomPadding()))
        )
    }
}
