package com.yomitanmobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.compose.rememberNavController
import com.yomitanmobile.ui.navigation.AppNavHost
import com.yomitanmobile.ui.navigation.Screen
import com.yomitanmobile.ui.theme.YomitanMobileTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "yomitan_prefs")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val ANKI_DECK_NAME = stringPreferencesKey("anki_deck_name")
        val THEME_MODE = stringPreferencesKey("theme_mode") // "system", "light", "dark"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var startRoute by remember { mutableStateOf<String?>(null) }
            var themeMode by remember { mutableStateOf("system") }

            LaunchedEffect(Unit) {
                val prefs = dataStore.data.first()
                val setupDone = prefs[SETUP_COMPLETED] ?: false
                themeMode = prefs[THEME_MODE] ?: "system"
                startRoute = if (setupDone) {
                    Screen.Search.route
                } else {
                    Screen.Setup.route
                }
            }

            // Listen for theme changes
            LaunchedEffect(Unit) {
                dataStore.data.collect { prefs ->
                    themeMode = prefs[THEME_MODE] ?: "system"
                }
            }

            val isDarkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            YomitanMobileTheme(darkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    startRoute?.let { route ->
                        val navController = rememberNavController()
                        AppNavHost(
                            navController = navController,
                            startDestination = route
                        )

                        // Mark setup as completed when navigating away from setup
                        LaunchedEffect(navController) {
                            navController.currentBackStackEntryFlow.collect { entry ->
                                if (entry.destination.route == Screen.Search.route) {
                                    dataStore.edit { prefs ->
                                        prefs[SETUP_COMPLETED] = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
