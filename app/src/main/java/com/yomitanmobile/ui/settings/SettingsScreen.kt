package com.yomitanmobile.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.anki.CardMeaningLanguage
import com.yomitanmobile.dataStore
import com.yomitanmobile.util.InputSanitizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDownload: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToCardStyle: () -> Unit = {},
    onNavigateToDictionaries: () -> Unit = {},
    onNavigateToFrequencyDisplay: () -> Unit = {},
    onNavigateToJlptDeck: () -> Unit = {},
    onNavigateToAnkiScan: () -> Unit = {},
    onNavigateToTextScan: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isEnglish = com.yomitanmobile.util.LocaleHelper.isEnglish(LocalConfiguration.current)
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    val isImporting by viewModel.isImporting.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()
    val isRestoring by viewModel.isRestoring.collectAsState()
    var showDeckEditDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    // After a successful restore the in-memory Hilt-singleton database
    // handle is closed and every DAO reference is stale. Continuing to use
    // the app in that state throws on the next query, so we lock the UI
    // behind a mandatory dialog whose only action is to kill the process.
    // The user relaunches and Hilt rebuilds the graph against the
    // newly-restored DB file.
    var showRestartRequiredDialog by remember { mutableStateOf(false) }
    var selectedBackupForRestore by remember { mutableStateOf<File?>(null) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var currentDeckName by remember { mutableStateOf("") }
    // Whether "Create backup" also exports the whitelisted settings
    // (everything except the AI API key). On by default.
    var includeSettingsInBackup by remember { mutableStateOf(true) }
    var currentThemeMode by remember { mutableStateOf("system") }
    var currentLanguage by remember { mutableStateOf("system") }
    var dailyGoalCount by remember { mutableStateOf(0f) }
    // Card meaning engine (JP-EN vs JP-JP) and the dictionary the JP-JP mode
    // reads definitions from.
    var cardMeaningLanguage by remember { mutableStateOf(CardMeaningLanguage.ENGLISH) }
    var monolingualDictionary by remember { mutableStateOf("") }
    var showMonolingualPicker by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Load current deck name, theme mode and daily goal
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        currentDeckName = prefs[MainActivity.ANKI_DECK_NAME] ?: ""
        currentThemeMode = prefs[MainActivity.THEME_MODE] ?: "system"
        dailyGoalCount = (prefs[MainActivity.DAILY_GOAL_COUNT] ?: 0).toFloat()
        cardMeaningLanguage = CardMeaningLanguage.fromStorage(prefs[MainActivity.CARD_MEANING_LANGUAGE])
        monolingualDictionary = prefs[MainActivity.CARD_MONOLINGUAL_DICTIONARY] ?: ""
        // Language is stored in SharedPreferences (needed for synchronous read at startup)
        val langPrefs = context.getSharedPreferences(MainActivity.LANG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        currentLanguage = langPrefs.getString(MainActivity.LANG_PREFS_KEY, "system") ?: "system"
    }

    // Picks which installed dictionary supplies JP-JP definitions. Listing the
    // installed dictionaries (instead of hardcoding a name) is what makes the
    // engine work with any monolingual zip the user imported themselves — the
    // commercial 国語辞典 can't be shipped as downloads.
    if (showMonolingualPicker) {
        val installed by viewModel.dictionaries.collectAsState()
        AlertDialog(
            onDismissRequest = { showMonolingualPicker = false },
            title = { Text(tr("Słownik JP-JP", "JP-JP dictionary")) },
            text = {
                if (installed.isEmpty()) {
                    Text(
                        tr(
                            "Brak zainstalowanych słowników.",
                            "No dictionaries installed."
                        )
                    )
                } else {
                    LazyColumn {
                        items(installed) { info ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        monolingualDictionary = info.name
                                        showMonolingualPicker = false
                                        coroutineScope.launch {
                                            context.dataStore.edit {
                                                it[MainActivity.CARD_MONOLINGUAL_DICTIONARY] = info.name
                                            }
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(info.name, modifier = Modifier.weight(1f))
                                if (info.name == monolingualDictionary) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMonolingualPicker = false }) {
                    Text(tr("Zamknij", "Close"))
                }
            }
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) viewModel.importDictionary(inputStream)
                else Toast.makeText(context, tr("Nie można otworzyć pliku", "Cannot open file"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, tr("Błąd: ${e.message}", "Error: ${e.message}"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Standalone settings.json import — counterpart of the "include settings"
    // export toggle on backup creation. JSON mime types vary by file manager
    // (some report octet-stream or text/plain for .json), so accept all three.
    val settingsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) viewModel.importSettings(inputStream)
                else Toast.makeText(context, tr("Nie można otworzyć pliku", "Cannot open file"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, tr("Błąd: ${e.message}", "Error: ${e.message}"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ImportSuccess ->
                    Toast.makeText(
                        context,
                        tr(
                            "Zaimportowano ${event.result.dictionaryName}: ${event.result.entriesImported} wpisów",
                            "Imported ${event.result.dictionaryName}: ${event.result.entriesImported} entries"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                is SettingsEvent.ImportError ->
                    Toast.makeText(context, tr("Błąd importu: ${event.message}", "Import error: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.BackupSuccess -> {
                    Toast.makeText(context, tr("Kopia zapasowa utworzona", "Backup created"), Toast.LENGTH_LONG).show()
                    showBackupDialog = false
                }
                is SettingsEvent.BackupError ->
                    Toast.makeText(context, tr("Błąd: ${event.message}", "Error: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.RestoreSuccess -> {
                    showRestoreDialog = false
                    showRestartRequiredDialog = true
                }
                is SettingsEvent.RestoreError ->
                    Toast.makeText(context, tr("Błąd przywracania: ${event.message}", "Restore error: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.SettingsImported -> {
                    val msg = if (event.applied > 0) {
                        tr(
                            "Zaimportowano ustawienia (${event.applied})",
                            "Settings imported (${event.applied})"
                        )
                    } else {
                        tr(
                            "Plik nie zawiera żadnych rozpoznanych ustawień",
                            "The file contains no recognised settings"
                        )
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                is SettingsEvent.SettingsImportError ->
                    Toast.makeText(context, tr("Błąd importu ustawień: ${event.message}", "Settings import error: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.ReclassifyDone -> {
                    val msg = tr(
                        "Przeliczono ${event.updated}, zachowano ręcznych ${event.skippedManual}, pominięto ${event.skippedMissing}",
                        "Updated ${event.updated}, kept ${event.skippedManual} manual, skipped ${event.skippedMissing}"
                    )
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                is SettingsEvent.ReclassifyError ->
                    Toast.makeText(
                        context,
                        tr("Błąd: ${event.message}", "Error: ${event.message}"),
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    if (showDeckEditDialog) {
        var editedDeckName by remember { mutableStateOf(currentDeckName.ifBlank { "Mining Deck" }) }
        // Existing AnkiDroid decks, fetched once when the dialog opens so the
        // user can pick one instead of retyping. Empty when AnkiDroid is
        // absent / unauthorized — then only the manual field shows.
        var availableSettingsDecks by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) {
            availableSettingsDecks = viewModel.getAvailableDecks()
        }

        fun saveDeck(name: String) {
            val sanitized = InputSanitizer.sanitizeDeckName(name)
            currentDeckName = sanitized
            coroutineScope.launch {
                context.dataStore.edit { prefs ->
                    prefs[MainActivity.ANKI_DECK_NAME] = sanitized
                }
            }
            showDeckEditDialog = false
        }

        AlertDialog(
            onDismissRequest = { showDeckEditDialog = false },
            title = { Text(tr("Zmień talię Anki", "Change Anki deck")) },
            text = {
                Column {
                    Text(
                        tr("Nowe fiszki będą dodawane do wybranej talii.", "New cards will be added to the selected deck."),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    if (availableSettingsDecks.isNotEmpty()) {
                        Text(
                            tr("Istniejące talie:", "Existing decks:"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        availableSettingsDecks.forEach { deck ->
                            OutlinedButton(
                                onClick = { saveDeck(deck) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    deck,
                                    fontSize = 14.sp,
                                    fontWeight = if (deck == currentDeckName) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tr("Lub utwórz nową talię:", "Or create a new deck:"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = editedDeckName,
                        onValueChange = { editedDeckName = it },
                        label = { Text(tr("Nazwa talii", "Deck name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveDeck(editedDeckName) }) {
                    Text(tr("Zapisz", "Save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeckEditDialog = false }) {
                    Text(tr("Anuluj", "Cancel"))
                }
            }
        )
    }

    if (showRestartRequiredDialog) {
        // Non-dismissable: the database singleton is closed and any DAO
        // call from here on throws IllegalStateException. The only path
        // forward is killing the process so Hilt rebuilds the graph.
        AlertDialog(
            onDismissRequest = { /* no-op: must restart */ },
            title = { Text(tr("Wymagany restart", "Restart required")) },
            text = {
                Text(
                    tr(
                        "Kopia została przywrócona. Aplikacja musi zostać uruchomiona ponownie, aby załadować przywrócone dane.",
                        "Backup restored. The app must restart to load the restored data."
                    ),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // finishAffinity drops the back stack; exitProcess
                    // tears down the JVM so the next launch starts a clean
                    // Hilt graph rather than reusing the closed DB handle.
                    (context as? Activity)?.finishAffinity()
                    kotlin.system.exitProcess(0)
                }) {
                    Text(tr("Uruchom ponownie", "Restart now"))
                }
            }
        )
    }

    if (showRestoreDialog && selectedBackupForRestore != null) {
        val backup = selectedBackupForRestore
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(tr("Przywróć kopię zapasową", "Restore backup")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Czy na pewno chcesz przywrócić tę kopię zapasową?\n${backup?.name ?: ""}\n\nAktualne dane zostaną zastąpione.",
                            "Are you sure you want to restore this backup?\n${backup?.name ?: ""}\n\nCurrent data will be replaced."
                        ),
                        fontSize = 14.sp
                    )
                    Text(
                        tr("Aplikacja musi być zrestartowana po przywróceniu.", "App must be restarted after restore."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedBackupForRestore != null) {
                        viewModel.restoreBackup(selectedBackupForRestore!!)
                    }
                }, enabled = !isRestoring) {
                    Text(tr("Przywróć", "Restore"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }, enabled = !isRestoring) {
                    Text(tr("Anuluj", "Cancel"))
                }
            }
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text(tr("O aplikacji i licencje", "About app and licenses")) },
            text = {
                LazyColumn {
                    item {
                        Text(
                            tr(
                                "Wersja aplikacji: ${com.yomitanmobile.BuildConfig.VERSION_NAME}",
                                "App version: ${com.yomitanmobile.BuildConfig.VERSION_NAME}"
                            ),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Aplikacja korzysta z otwartych słowników do działania. Dostępne słowniki m.in. JMdict oraz KANJIDIC są udostępniane na licencjach Creative Commons Attribution-ShareAlike 4.0 International lub podobnych.\n\nWłasność i prawa autorskie:",
                                "The app uses open dictionaries. Available dictionaries including JMdict and KANJIDIC are shared under Creative Commons Attribution-ShareAlike 4.0 International licenses or similar.\n\nOwnership and copyrights:"
                            ),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("JMdict/Kanjidic (EDRDG - Electronic Dictionary Research and Development Group)")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Tatoeba Project (CC-BY 2.0 FR) dla przykładowych zdań (jeśli zaimportowane).",
                                "Tatoeba Project (CC-BY 2.0 FR) for example sentences (if imported)."
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text(tr("Zamknij", "Close")) }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text(tr("Polityka Prywatności", "Privacy Policy")) },
            text = {
                LazyColumn {
                    item {
                        Text(
                            tr(
                                "1. Zakres danych\nYomitan Mobile nie wymaga konta i nie zbiera danych osobowych w centralnym backendzie.\nDomyślnie dane użytkownika (np. historia wyszukiwań, ustawienia, lista eksportów) są przechowywane lokalnie na urządzeniu.\n\n2. Kiedy aplikacja łączy się z internetem\nAplikacja może używać sieci w dwóch scenariuszach:\n1) Pobieranie słowników na życzenie użytkownika.\n2) Opcjonalne pobieranie zdań przykładowych z zewnętrznego API (tylko po wyrażeniu zgody).\n\n3. Opcjonalne API zdań\nFunkcja zdań online jest dobrowolna i domyślnie wyłączona.\nPo włączeniu aplikacja wysyła zapytanie zawierające szukane słowo do zewnętrznego API wyłącznie w celu pobrania przykładowego zdania.\nZgodę można w każdej chwili cofnąć w ustawieniach aplikacji.\n\n4. Przechowywanie lokalne\nDane tworzone przez aplikację (m.in. historia wyszukiwań, preferencje, metadane eksportu do Anki) są przechowywane lokalnie.\nUżytkownik może usunąć je przez wyczyszczenie danych aplikacji w ustawieniach systemu Android.\n\n5. Integracja z AnkiDroid\nEksport do AnkiDroid wykorzystuje oficjalne API AnkiDroid i lokalny mechanizm Content Provider.\n\n6. Kontakt\nW razie pytań lub wątpliwości dotyczących prywatności prosimy o otwarcie zgłoszenia (Issue) w repozytorium projektu.\n\nStan na dzień: 18 kwietnia 2026.",
                                "1. Data scope\nYomitan Mobile does not require an account and does not collect personal data in a central backend.\nBy default, user data (search history, settings, export list) is stored locally on the device.\n\n2. When the app connects to the internet\nThe app may use the network in two cases:\n1) Downloading dictionaries on user request.\n2) Optional fetching of example sentences from an external API (only with consent).\n\n3. Optional sentence API\nThe online sentence feature is optional and disabled by default.\nWhen enabled, the app sends the searched word to an external sentence API solely to fetch an example sentence.\nConsent can be revoked at any time in the app settings.\n\n4. Local storage\nData created by the app (search history, preferences, Anki export metadata) is stored locally.\nYou can delete it by clearing app data in Android system settings.\n\n5. AnkiDroid integration\nExport to AnkiDroid uses the official AnkiDroid API and a local Content Provider mechanism.\n\n6. Contact\nIf you have questions or privacy concerns, please open an Issue in the project repository.\n\nStatus as of: April 18, 2026."
                            ),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text(tr("Zamknij", "Close")) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Ustawienia", "Settings")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ═══════════════════════════════════════
            // SECTION: Słowniki (Dictionaries)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.MenuBook,
                    title = tr("Słowniki", "Dictionaries")
                )
            }

            item {
                Button(
                    onClick = onNavigateToDownload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Pobierz słowniki z internetu", "Download dictionaries from the internet"))
                }
            }

            item {
                OutlinedButton(
                    onClick = { filePickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Importuj słownik Yomitan (.zip)", "Import Yomitan dictionary (.zip)"))
                }
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.MenuBook,
                    title = tr("Zainstalowane słowniki", "Installed dictionaries"),
                    subtitle = tr("Przeglądaj i zarządzaj słownikami", "Browse and manage dictionaries"),
                    onClick = onNavigateToDictionaries
                )
            }

            if (isImporting) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(tr("Importowanie słownika...", "Importing dictionary..."))
                            }
                            importProgress?.let { progress ->
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(progress = progress.progressPercent, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    tr(
                                        "Plik ${progress.filesProcessed}/${progress.totalFiles} • ${progress.entriesProcessed} wpisów",
                                        "File ${progress.filesProcessed}/${progress.totalFiles} • ${progress.entriesProcessed} entries"
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════
            // SECTION: Wygląd (Appearance)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Palette,
                    title = tr("Wygląd", "Appearance")
                )
            }

            // Theme mode toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(tr("Motyw", "Theme"), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = currentThemeMode == "system",
                                onClick = {
                                    currentThemeMode = "system"
                                    coroutineScope.launch {
                                        context.dataStore.edit { it[MainActivity.THEME_MODE] = "system" }
                                    }
                                },
                                label = { Text(tr("Systemowy", "System")) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = currentThemeMode == "light",
                                onClick = {
                                    currentThemeMode = "light"
                                    coroutineScope.launch {
                                        context.dataStore.edit { it[MainActivity.THEME_MODE] = "light" }
                                    }
                                },
                                label = { Text(tr("Jasny", "Light")) },
                                leadingIcon = if (currentThemeMode == "light") null else {
                                    { Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = currentThemeMode == "dark",
                                onClick = {
                                    currentThemeMode = "dark"
                                    coroutineScope.launch {
                                        context.dataStore.edit { it[MainActivity.THEME_MODE] = "dark" }
                                    }
                                },
                                label = { Text(tr("Ciemny", "Dark")) },
                                leadingIcon = if (currentThemeMode == "dark") null else {
                                    { Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Card style button
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Style,
                    title = tr("Wygląd fiszki Anki", "Anki card style"),
                    subtitle = tr("Czcionka, rozmiar, kolory, podgląd", "Font, size, colors, preview"),
                    onClick = onNavigateToCardStyle
                )
            }

            // Frequency display button
            item {
                SettingsClickableItem(
                    icon = Icons.Default.BarChart,
                    title = tr("Wyświetlanie częstotliwości", "Frequency display"),
                    subtitle = tr("Kolejność list, pokaż wszystkie", "List order, show all"),
                    onClick = onNavigateToFrequencyDisplay
                )
            }

            // ═══════════════════════════════════════
            // SECTION: Anki
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Style,
                    title = "Anki"
                )
            }

            // Anki deck setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Style,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                tr("Talia Anki", "Anki deck"),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (currentDeckName.isNotBlank()) currentDeckName
                                else tr("Nie wybrano (zostaniesz zapytany przy eksporcie)", "Not selected (you will be asked during export)"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        IconButton(onClick = { showDeckEditDialog = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = tr("Zmień talię", "Change deck"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Card engine: which language the Meaning field is written in.
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            tr("Silnik fiszek", "Card engine"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            tr(
                                "Język pola „Znaczenie”. W trybie JP-JP definicja pochodzi z wybranego " +
                                    "słownika japońsko-japońskiego, a zdania z Jitendex zostają bez " +
                                    "angielskiego tłumaczenia. Gdy słowa nie ma w słowniku JP-JP, " +
                                    "zostaje definicja angielska.",
                                "Language of the Meaning field. In JP-JP mode the definition comes from the " +
                                    "chosen Japanese-Japanese dictionary and Jitendex sentences keep their " +
                                    "Japanese only, without the English translation. Words missing from the " +
                                    "JP-JP dictionary keep their English definition."
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = cardMeaningLanguage == CardMeaningLanguage.ENGLISH,
                                onClick = {
                                    cardMeaningLanguage = CardMeaningLanguage.ENGLISH
                                    coroutineScope.launch {
                                        context.dataStore.edit {
                                            it[MainActivity.CARD_MEANING_LANGUAGE] =
                                                CardMeaningLanguage.ENGLISH.storageValue
                                        }
                                    }
                                },
                                label = { Text("JP → EN") }
                            )
                            FilterChip(
                                selected = cardMeaningLanguage == CardMeaningLanguage.JAPANESE,
                                onClick = {
                                    cardMeaningLanguage = CardMeaningLanguage.JAPANESE
                                    coroutineScope.launch {
                                        context.dataStore.edit {
                                            it[MainActivity.CARD_MEANING_LANGUAGE] =
                                                CardMeaningLanguage.JAPANESE.storageValue
                                        }
                                    }
                                },
                                label = { Text("JP → JP") }
                            )
                        }
                        if (cardMeaningLanguage == CardMeaningLanguage.JAPANESE) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                tr("Słownik japońsko-japoński", "Japanese-Japanese dictionary"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { showMonolingualPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    monolingualDictionary.ifBlank {
                                        tr("Wybierz słownik…", "Pick a dictionary…")
                                    }
                                )
                            }
                            if (monolingualDictionary.isBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    tr(
                                        "Bez wybranego słownika tryb JP-JP nic nie zmienia. Zainstaluj np. " +
                                            "„日本語 Wiktionary” z ekranu pobierania albo zaimportuj własny " +
                                            "słownik (三省堂, 明鏡…) z pliku.",
                                        "With no dictionary chosen JP-JP mode changes nothing. Install e.g. " +
                                            "“日本語 Wiktionary” from the download screen, or import your own " +
                                            "dictionary (三省堂, 明鏡…) from a file."
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // JLPT deck generator
            item {
                SettingsClickableItem(
                    icon = Icons.Default.School,
                    title = tr("Generator talii JLPT", "JLPT deck generator"),
                    subtitle = tr(
                        "Cała lista słów z poziomu jako gotowe fiszki, bez kopania",
                        "A whole JLPT level as ready-made cards, no mining"
                    ),
                    onClick = onNavigateToJlptDeck
                )
            }

            // Text scan: subtitles / EPUB in, cards for the unknown words out.
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Subtitles,
                    title = tr("Fiszki z napisów lub książki", "Cards from subtitles or a book"),
                    subtitle = tr(
                        "Wczytaj .srt/.ass/.epub — aplikacja zrobi fiszki z nieznanych słów",
                        "Load .srt/.ass/.epub — the app makes cards from the unknown words"
                    ),
                    onClick = onNavigateToTextScan
                )
            }

            // Anki collection scan — the duplicate guard both mining and the
            // JLPT generator read from.
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Search,
                    title = tr("Skan kolekcji Anki", "Anki collection scan"),
                    subtitle = tr(
                        "Wykrywa słowa, które już masz (Core, Kaishi, własne) i blokuje duplikaty",
                        "Detects words you already have (Core, Kaishi, your own) and blocks duplicates"
                    ),
                    onClick = onNavigateToAnkiScan
                )
            }

            // ═══════════════════════════════════════
            // SECTION: Statystyki i cele (Stats & goals)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.BarChart,
                    title = tr("Statystyki i cele", "Stats & goals")
                )
            }

            // Daily goal setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.EmojiEvents,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    tr("Cel dzienny fiszek", "Daily card goal"),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (dailyGoalCount.toInt() == 0) tr("Wyłączony", "Disabled")
                                    else tr("${dailyGoalCount.toInt()} fiszek dziennie", "${dailyGoalCount.toInt()} cards/day"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (dailyGoalCount.toInt() == 0) tr("Wyłączony", "Disabled") else "${dailyGoalCount.toInt()}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("50", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                        Slider(
                            value = dailyGoalCount,
                            onValueChange = { dailyGoalCount = it },
                            onValueChangeFinished = {
                                coroutineScope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[MainActivity.DAILY_GOAL_COUNT] = dailyGoalCount.toInt()
                                    }
                                }
                            },
                            valueRange = 0f..50f,
                            steps = 49
                        )
                        Text(
                            tr("Ustaw na 0 aby wyłączyć cel dzienny", "Set to 0 to disable daily goal"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.BarChart,
                    title = tr("Statystyki", "Statistics"),
                    subtitle = tr("Przegląd aktywności, streak, wykres fiszek", "Activity overview, streak, card chart"),
                    onClick = onNavigateToStatistics
                )
            }

            // Reclassify pass. Walks every ExportedWord row and re-runs
            // WordCategoryClassifier with the current rules. Manual user
            // overrides are preserved; rows whose source dictionary is
            // gone are silently skipped. Run lock = isReclassifying.
            item {
                val isReclassifying by viewModel.isReclassifying.collectAsState()
                SettingsClickableItem(
                    icon = Icons.Default.List,
                    title = tr("Przelicz kategorie", "Recompute categories"),
                    subtitle = if (isReclassifying) {
                        tr("Przeliczanie…", "Recomputing…")
                    } else {
                        tr(
                            "Przepisz kategorie wyeksportowanych słów po zmianie reguł",
                            "Rewrite categories of exported words after rule changes"
                        )
                    },
                    onClick = {
                        if (!isReclassifying) viewModel.reclassifyCategories()
                    }
                )
            }

            // ═══════════════════════════════════════
            // SECTION: Aplikacja (App)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Language,
                    title = tr("Aplikacja", "App")
                )
            }

            // Language selector
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    tr("Język aplikacji", "App language"),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    when (currentLanguage) {
                                        "pl" -> tr("Polski", "Polish")
                                        "en" -> "English"
                                        else -> tr("Systemowy", "System")
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "system" to tr("Systemowy", "System"),
                                "pl" to tr("Polski", "Polish"),
                                "en" to "English"
                            ).forEach { (code, label) ->
                                FilterChip(
                                    selected = currentLanguage == code,
                                    onClick = {
                                        if (currentLanguage != code) {
                                            currentLanguage = code
                                            val langPrefs = context.getSharedPreferences(
                                                MainActivity.LANG_PREFS_NAME,
                                                android.content.Context.MODE_PRIVATE
                                            )
                                            langPrefs.edit()
                                                .putString(MainActivity.LANG_PREFS_KEY, code)
                                                .apply()
                                            (context as? Activity)?.recreate()
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════
            // SECTION: Kopia zapasowa (Backup)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.CloudDownload,
                    title = tr("Kopia zapasowa", "Backup & Restore")
                )
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isBackingUp && !isRestoring) {
                            includeSettingsInBackup = !includeSettingsInBackup
                        }
                ) {
                    Checkbox(
                        checked = includeSettingsInBackup,
                        onCheckedChange = { includeSettingsInBackup = it },
                        enabled = !isBackingUp && !isRestoring
                    )
                    Text(
                        tr(
                            "Dołącz ustawienia (bez klucza AI)",
                            "Include settings (without AI key)"
                        ),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        if (!isBackingUp) {
                            viewModel.createBackup(includeSettingsInBackup)
                        }
                    },
                    enabled = !isBackingUp && !isRestoring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isBackingUp) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(tr("Utwórz kopię zapasową", "Create backup"))
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        settingsPickerLauncher.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain")
                        )
                    },
                    enabled = !isBackingUp && !isRestoring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Importuj ustawienia z pliku", "Import settings from file"))
                }
                Text(
                    tr(
                        "Wybierz settings.json z folderu kopii zapasowej. Baza danych nie jest zmieniana; klucz AI nigdy nie jest przenoszony.",
                        "Pick a settings.json from a backup folder. The database is untouched; the AI key is never carried over."
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (backups.isNotEmpty()) {
                item {
                    Text(
                        tr("Dostępne kopie (${backups.size}):", "Available backups (${backups.size}):"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(backups.size) { index ->
                    val backup = backups[index]
                    val timestamp = backup.name.replace("backup_", "")
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    timestamp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    backup.absolutePath,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        selectedBackupForRestore = backup
                                        showRestoreDialog = true
                                    },
                                    enabled = !isRestoring,
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    if (isRestoring && selectedBackupForRestore == backup) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.dp)
                                    } else {
                                        Text(tr("Przywróć", "Restore"), fontSize = 11.sp)
                                    }
                                }
                                
                                OutlinedButton(
                                    onClick = { viewModel.deleteBackup(backup) },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(tr("Usuń", "Delete"), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════
            // SECTION: Prywatność i integracje (Privacy & integrations)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Shield,
                    title = tr("Prywatność i integracje", "Privacy & integrations")
                )
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.Policy,
                    title = tr("Polityka Prywatności", "Privacy Policy"),
                    subtitle = tr("Zasady prywatności i lokalne przetwarzanie danych", "Privacy rules and local data processing"),
                    onClick = { showPrivacyDialog = true }
                )
            }

            // ═══════════════════════════════════════
            // SECTION: Informacje (About)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Info,
                    title = tr("Informacje i licencje", "Information & licenses")
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.MenuBook,
                    title = tr("Licencje słowników", "Dictionary licenses"),
                    subtitle = tr("Informacje o otwartych danych i prawach autorskich", "Open data and copyright information"),
                    onClick = { showLicensesDialog = true }
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SettingsClickableItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
