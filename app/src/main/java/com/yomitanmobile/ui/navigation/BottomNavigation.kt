package com.yomitanmobile.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.yomitanmobile.ui.common.tr
import com.yomitanmobile.ui.download.DownloadQueueStrip

/**
 * The four places worth one tap.
 *
 * Everything the app does besides searching used to live inside the settings
 * screen: the JLPT generator, the text scanner and the collection scan were
 * rows between "AI engine" and "daily goal", four scrolls deep. They are not
 * settings, they are the work.
 */
enum class BottomDestination(
    val screen: Screen,
    val icon: ImageVector,
    val pl: String,
    val en: String
) {
    SEARCH(Screen.Search, Icons.Default.Search, "Szukaj", "Search"),
    TOOLS(Screen.Tools, Icons.Default.Build, "Narzędzia", "Tools"),
    STATISTICS(Screen.Statistics, Icons.Default.BarChart, "Statystyki", "Stats"),
    SETTINGS(Screen.Settings, Icons.Default.Settings, "Ustawienia", "Settings")
}

/** Routes that show the bar. Anything deeper is a detail view and hides it. */
val BOTTOM_ROUTES: Set<String> = BottomDestination.entries.mapTo(HashSet()) { it.screen.route }

@Composable
fun AppBottomBar(navController: NavHostController, modifier: Modifier = Modifier) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Column(modifier = modifier.fillMaxWidth()) {
        // Whatever is installing right now, visible from every tab — the point
        // of a background queue is that you can walk away from it.
        DownloadQueueStrip()
        NavigationBar {
            for (destination in BottomDestination.entries) {
                val label = tr(destination.pl, destination.en)
                NavigationBarItem(
                    selected = currentRoute == destination.screen.route,
                    onClick = {
                        if (currentRoute != destination.screen.route) {
                            navController.navigate(destination.screen.route) {
                                // One entry per tab, and the back button always
                                // leads home rather than through a history of
                                // taps.
                                popUpTo(Screen.Search.route) {
                                    inclusive = destination == BottomDestination.SEARCH
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(destination.icon, contentDescription = label) },
                    label = { Text(label) }
                )
            }
        }
    }
}
