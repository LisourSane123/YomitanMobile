package com.yomitanmobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import java.io.File
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
        val CARD_FRONT_CONTEXT_SENTENCE_FONT_SIZE = intPreferencesKey("card_front_context_sentence_font_size")
        val CARD_BACK_SENTENCE_FONT_SIZE = intPreferencesKey("card_back_sentence_font_size")
        val CARD_FONT_FAMILY = stringPreferencesKey("card_font_family")
        val CARD_BACKGROUND_COLOR = stringPreferencesKey("card_background_color")
        val CARD_EXPRESSION_COLOR = stringPreferencesKey("card_expression_color")
        val CARD_READING_COLOR = stringPreferencesKey("card_reading_color")
        val CARD_MEANING_COLOR = stringPreferencesKey("card_meaning_color")
        val CARD_ACCENT_COLOR = stringPreferencesKey("card_accent_color")
        val CARD_SHOW_PITCH = booleanPreferencesKey("card_show_pitch")
        val CARD_PITCH_ACCENT_STYLE = stringPreferencesKey("card_pitch_accent_style")
        val CARD_SHOW_FREQUENCY = booleanPreferencesKey("card_show_frequency")
        val CARD_SHOW_SENTENCE = booleanPreferencesKey("card_show_sentence")
        val CARD_SHOW_FRONT_CONTEXT_SENTENCE = booleanPreferencesKey("card_show_front_context_sentence")
        val CARD_RANDOM_FONTS_ENABLED = booleanPreferencesKey("card_random_fonts_enabled")
        val CARD_RANDOM_FONTS = stringSetPreferencesKey("card_random_fonts")
        val TTS_RANDOM_VOICES_ENABLED = booleanPreferencesKey("tts_random_voices_enabled")
        val TTS_RANDOM_VOICES = stringSetPreferencesKey("tts_random_voices")
        val CARD_SHOW_SECTION_DIVIDERS = booleanPreferencesKey("card_show_section_dividers")
        val CARD_SHOW_WORD_DIVIDER = booleanPreferencesKey("card_show_word_divider")

        // AI summary integration. Gated behind CARD_AI_SUMMARY_ENABLED so the
        // network call only happens when the user explicitly opts in. The
        // prompt is a template — placeholders like {expression}, {reading},
        // {meaning}, {language} get substituted before the call.
        val CARD_AI_SUMMARY_ENABLED = booleanPreferencesKey("card_ai_summary_enabled")
        val CARD_AI_PROVIDER = stringPreferencesKey("card_ai_provider")
        val CARD_AI_API_KEY = stringPreferencesKey("card_ai_api_key")
        val CARD_AI_PROMPT = stringPreferencesKey("card_ai_prompt")
        // Optional per-provider model override. When blank, AiSummaryService
        // falls back to AiProvider.defaultModel (Gemini → gemini-3.1-flash-lite,
        // DeepSeek → deepseek-chat, OpenAI → gpt-4o-mini).
        val CARD_AI_MODEL = stringPreferencesKey("card_ai_model")

        // Comma-separated list of CardSection.storageValue, e.g.
        // "pitch,summary,meaning,sentence,audio,kanji". CardSection.decode
        // tolerates missing/extra tokens so an old saved order doesn't
        // hide new sections after an upgrade.
        val CARD_SECTION_ORDER = stringPreferencesKey("card_section_order")

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

        // If the previous run crashed, our uncaught-exception handler
        // wrote the stack trace to filesDir/last_crash.txt. Pull it in
        // here and clear the file so the banner shows exactly once. The
        // banner content is surfaced in the Compose layer below via the
        // crashReport state.
        val crashReport = readAndClearLastCrash()

        setContent {
            var startRoute by remember { mutableStateOf<String?>(null) }
            var themeMode by remember { mutableStateOf("system") }
            var shouldFocusSearch by remember {
                mutableStateOf(isQuickSearch || !sharedSearchQuery.isNullOrBlank())
            }
            var lastCrash by remember { mutableStateOf(crashReport) }

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
                    lastCrash?.let { trace ->
                        CrashReportDialog(trace = trace, onDismiss = { lastCrash = null })
                    }
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
        val raw = when (action) {
            // Share-sheet path. Limited to text/* MIME so we don't try
            // to interpret images or files as a search query.
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (!type.startsWith("text/")) return null
                intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            }
            // Selection-toolbar path. EXTRA_PROCESS_TEXT is a CharSequence
            // (the system passes a span-rich one in some apps) — collapse
            // to plain String before trimming.
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    ?.toString()
                    .orEmpty()
            }
            else -> return null
        }.trim()
        if (raw.isBlank()) return null

        val firstLine = raw.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

        return firstLine.take(80).ifBlank { null }
    }

    /**
     * Pulls the contents of the crash file written by
     * [YomitanMobileApp]'s uncaught-exception handler, then deletes it
     * so the banner shows exactly once. Returns null if no crash file
     * exists or reading failed — in both cases we silently skip showing
     * the dialog.
     */
    private fun readAndClearLastCrash(): String? {
        val file = File(filesDir, YomitanMobileApp.LAST_CRASH_FILE)
        if (!file.exists()) return null
        return try {
            val text = file.readText().take(8000)
            file.delete()
            text.ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }
}

@Composable
private fun CrashReportDialog(trace: String, onDismiss: () -> Unit) {
    // Surfaces the prior-run crash as a modal dialog. Monospace font
    // makes stack traces readable; verticalScroll handles long traces
    // without truncation. The user can long-press / select-all in the
    // dialog to copy the text out for sharing.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Previous crash") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = trace,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    )
}
