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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yomitanmobile.ui.common.LocalIsEnglish
import com.yomitanmobile.ui.common.isEnglishUi
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
import com.yomitanmobile.data.settings.PreferenceKeys
import com.yomitanmobile.ui.navigation.AppScaffold
import com.yomitanmobile.ui.navigation.Screen
import com.yomitanmobile.ui.theme.YomitanMobileTheme
import com.yomitanmobile.widget.QuickSearchWidgetProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "yomitan_prefs")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var languageSettings: com.yomitanmobile.data.settings.LanguageSettings

    private var sharedSearchQuery: String? by mutableStateOf(null)

    // Bumped on every incoming share/PROCESS_TEXT intent. A shared query is
    // an EVENT, not a value: sharing the same word twice must re-trigger
    // navigation + re-apply, but state holders (and LaunchedEffect keys)
    // don't fire when the new value equals the old one. The nonce makes
    // every share distinct without touching the query text itself.
    private var sharedSearchNonce: Int by mutableStateOf(0)

    companion object {
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
                val setupDone = prefs[PreferenceKeys.SETUP_COMPLETED] ?: false
                val languageChosen = prefs[PreferenceKeys.APP_LANGUAGE] != null
                themeMode = prefs[PreferenceKeys.THEME_MODE] ?: "system"
                // The language question comes before the dictionary question,
                // because the answer decides which dictionaries setup offers.
                // An install that predates this setting has no stored language
                // and passes through here once; picking Japanese leaves it
                // exactly as it was, since that is what its existing rows are.
                startRoute = when {
                    !languageChosen -> Screen.LanguageSelect.route
                    setupDone -> Screen.Search.route
                    else -> Screen.Setup.route
                }
            }

            // Listen for theme changes
            LaunchedEffect(Unit) {
                dataStore.data.collect { prefs ->
                    themeMode = prefs[PreferenceKeys.THEME_MODE] ?: "system"
                }
            }

            val isDarkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            YomitanMobileTheme(darkTheme = isDarkTheme) {
                // Every `tr(pl, en)` literal in the app reads this. Nothing
                // provided it, so the default (`false`) stood everywhere and an
                // English device saw a Polish interface.
                CompositionLocalProvider(
                    LocalIsEnglish provides isEnglishUi(),
                    com.yomitanmobile.ui.common.LocalStudyLanguage provides languageSettings.current
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        lastCrash?.let { trace ->
                            CrashReportDialog(trace = trace, onDismiss = { lastCrash = null })
                        }
                        startRoute?.let { route ->
                            val navController = rememberNavController()
                            AppScaffold(
                                navController = navController,
                                startDestination = route,
                                focusSearch = shouldFocusSearch,
                                sharedSearchQuery = sharedSearchQuery,
                                sharedSearchNonce = sharedSearchNonce
                            )

                            // Keyed on the nonce (not just the text) so sharing
                            // the SAME word a second time still navigates back
                            // to Search from wherever the user currently is.
                            LaunchedEffect(sharedSearchQuery, sharedSearchNonce) {
                                if (!sharedSearchQuery.isNullOrBlank()) {
                                    shouldFocusSearch = false
                                    navController.navigate(Screen.Search.route) {
                                        launchSingleTop = true
                                    }
                                }
                            }

                            // Reaching the search screen means setup is behind
                            // us — recorded ONCE per launch. This used to
                            // collect for the whole session and rewrite the
                            // preferences file on every arrival, so switching
                            // to the Search tab wrote to DataStore and woke
                            // every reader of it each time.
                            LaunchedEffect(navController) {
                                navController.currentBackStackEntryFlow
                                    .first { it.destination.route == Screen.Search.route }
                                dataStore.edit { prefs -> prefs[PreferenceKeys.SETUP_COMPLETED] = true }
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
        val extracted = extractSearchQueryFromIntent(intent)
        if (extracted != null) {
            sharedSearchQuery = extracted
            sharedSearchNonce++
        }
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
    // without truncation. SelectionContainer makes the trace long-press
    // selectable so the user can copy it out for a bug report — plain
    // Compose Text is NOT selectable by default.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Previous crash") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        text = trace,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    )
}
