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
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

    private var sharedSearchQuery: String? by mutableStateOf(null)

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
        val CARD_SHOW_FRONT_CONTEXT_SENTENCE = booleanPreferencesKey("card_show_front_context_sentence")
        val CARD_RANDOM_FONTS_ENABLED = booleanPreferencesKey("card_random_fonts_enabled")
        val CARD_RANDOM_FONTS = stringSetPreferencesKey("card_random_fonts")
        val TTS_RANDOM_VOICES_ENABLED = booleanPreferencesKey("tts_random_voices_enabled")
        val TTS_RANDOM_VOICES = stringSetPreferencesKey("tts_random_voices")
        val CARD_USE_ONLINE_SENTENCE_API = booleanPreferencesKey("card_use_online_sentence_api")
        val SENTENCE_API_CONSENT_GRANTED = booleanPreferencesKey("sentence_api_consent_granted")

        // Bunpro integration
        val BUNPRO_API_ENABLED = booleanPreferencesKey("bunpro_api_enabled")
        val BUNPRO_API_TOKEN = stringPreferencesKey("bunpro_api_token")
        val BUNPRO_API_ENDPOINT = stringPreferencesKey("bunpro_api_endpoint")

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
        sharedSearchQuery = extractSearchQueryFromIntent(intent)

        setContent {
            var startRoute by remember { mutableStateOf<String?>(null) }
            var themeMode by remember { mutableStateOf("system") }
            var shouldFocusSearch by remember {
                mutableStateOf(isQuickSearch || !sharedSearchQuery.isNullOrBlank())
            }

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
                            focusSearch = shouldFocusSearch,
                            sharedSearchQuery = sharedSearchQuery
                        )

                        LaunchedEffect(sharedSearchQuery) {
                            if (!sharedSearchQuery.isNullOrBlank()) {
                                shouldFocusSearch = false
                                navController.navigate(Screen.Search.route) {
                                    launchSingleTop = true
                                }
                            }
                        }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedSearchQuery = extractSearchQueryFromIntent(intent)
    }

    private fun extractSearchQueryFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        val action = intent.action ?: return null
        val type = intent.type.orEmpty()
        if (action != Intent.ACTION_SEND) return null
        if (!type.startsWith("text/")) return null

        val raw = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        if (raw.isBlank()) return null

        val firstLine = raw.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

        return firstLine.take(80).ifBlank { null }
    }
}
