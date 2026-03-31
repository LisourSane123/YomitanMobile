package com.yomitanmobile

import android.content.Context
import android.content.Intent
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
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.compose.rememberNavController
import com.yomitanmobile.ui.navigation.AppNavHost
import com.yomitanmobile.ui.navigation.Screen
import com.yomitanmobile.ui.theme.YomitanMobileTheme
import com.yomitanmobile.widget.QuickSearchWidgetProvider
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

        // Card style preferences
        val CARD_EXPRESSION_BOLD = booleanPreferencesKey("card_expression_bold")
        val CARD_EXPRESSION_FONT_SIZE = intPreferencesKey("card_expression_font_size")
        val CARD_READING_FONT_SIZE = intPreferencesKey("card_reading_font_size")
        val CARD_MEANING_FONT_SIZE = intPreferencesKey("card_meaning_font_size")
        val CARD_FONT_FAMILY = stringPreferencesKey("card_font_family")
        val CARD_BACKGROUND_COLOR = stringPreferencesKey("card_background_color")
        val CARD_EXPRESSION_COLOR = stringPreferencesKey("card_expression_color")
        val CARD_READING_COLOR = stringPreferencesKey("card_reading_color")
        val CARD_MEANING_COLOR = stringPreferencesKey("card_meaning_color")
        val CARD_ACCENT_COLOR = stringPreferencesKey("card_accent_color")
        val CARD_SHOW_PITCH = booleanPreferencesKey("card_show_pitch")
        val CARD_SHOW_FREQUENCY = booleanPreferencesKey("card_show_frequency")
        val CARD_SHOW_SENTENCE = booleanPreferencesKey("card_show_sentence")

        // Daily goal
        val DAILY_GOAL_COUNT = intPreferencesKey("daily_goal_count") // 0 = disabled

        // Language (stored in SharedPreferences for sync read in attachBaseContext)
        const val LANG_PREFS_NAME = "lang_prefs"
        const val LANG_PREFS_KEY = "app_language" // "system" | "pl" | "en"
    }

    override fun attachBaseContext(newBase: Context) {
        val langPrefs = newBase.getSharedPreferences(LANG_PREFS_NAME, Context.MODE_PRIVATE)
        val language = langPrefs.getString(LANG_PREFS_KEY, "system") ?: "system"
        val locale = when (language) {
            "pl" -> java.util.Locale("pl")
            "en" -> java.util.Locale("en")
            else -> java.util.Locale.getDefault()
        }
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val localizedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isQuickSearch = intent?.action == QuickSearchWidgetProvider.ACTION_QUICK_SEARCH

        setContent {
            var startRoute by remember { mutableStateOf<String?>(null) }
            var themeMode by remember { mutableStateOf("system") }
            var shouldFocusSearch by remember { mutableStateOf(isQuickSearch) }

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
                            startDestination = route,
                            focusSearch = shouldFocusSearch
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
