package com.yomitanmobile.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.anki.CardMeaningLanguage
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.util.InputSanitizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import java.io.File
import com.yomitanmobile.ui.common.rememberTr
import com.yomitanmobile.ui.common.LocalIsEnglish
import com.yomitanmobile.ui.common.SettingsDivider
import com.yomitanmobile.ui.common.SettingsRow
import com.yomitanmobile.ui.common.SettingsSectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDownload: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToCardStyle: () -> Unit = {},
    onNavigateToDictionaries: () -> Unit = {},
    onNavigateToFrequencyDisplay: () -> Unit = {},
    onNavigateToJlptDeck: () -> Unit = {},
    onNavigateToAnkiScan: () -> Unit = {},
    onNavigateToTextScan: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isEnglish = LocalIsEnglish.current
    val tr = rememberTr()
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
    // Study-language switch. Holds the language the user tapped, which is
    // also what makes the dialog visible; null = no switch pending.
    var pendingStudyLanguage by remember { mutableStateOf<AppLanguage?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }
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
                        items(installed, key = { it.id }) { info ->
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

    // The pronunciation archive: one folder, walked once. OpenDocumentTree
    // rather than a file picker because the archives are directory trees of
    // hundreds of thousands of files, and SAF cannot be asked for one of them
    // by name — see AudioArchive.
    val audioArchiveFiles by viewModel.audioArchiveFiles.collectAsState()
    val audioArchiveLabel by viewModel.audioArchiveLabel.collectAsState()
    val audioArchiveIndexing by viewModel.audioArchiveIndexing.collectAsState()
    val voicevoxState by viewModel.voicevoxState.collectAsState()
    val voicevoxEnabled by viewModel.voicevoxEnabled.collectAsState()
    var showVoicevoxTerms by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val audioArchivePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::indexAudioArchive) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ImportSuccess ->
                    Toast.makeText(
                        context,
                        buildString {
                            append(
                                tr(
                                    "Zaimportowano ${event.result.dictionaryName}: ${event.result.entriesImported} wpisów",
                                    "Imported ${event.result.dictionaryName}: ${event.result.entriesImported} entries"
                                )
                            )
                            // The index that meaning search reads did not
                            // rebuild — say so, instead of leaving the user
                            // with a success message and a search that can't
                            // see the new dictionary.
                            if (event.result.warning != null) {
                                append(" — ")
                                append(
                                    tr(
                                        "uwaga: indeks wyszukiwania po znaczeniu nie odświeżył się, powtórz import",
                                        "warning: the meaning-search index did not refresh, re-run the import"
                                    )
                                )
                            }
                        },
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
                is SettingsEvent.AudioArchiveIndexed ->
                    Toast.makeText(
                        context,
                        tr(
                            "Zindeksowano ${event.files} nagrań",
                            "Indexed ${event.files} recordings"
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                is SettingsEvent.AudioArchiveEmpty ->
                    Toast.makeText(
                        context,
                        tr(
                            "W tym folderze nie ma plików audio z japońskimi nazwami — poprzednie archiwum zostało bez zmian.",
                            "That folder holds no audio files with Japanese names — the previous archive was left alone."
                        ),
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

    if (showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text(tr("Język nauki", "Study language")) },
            text = {
                Column {
                    AppLanguage.entries.forEach { language ->
                        val label = when (language) {
                            AppLanguage.JAPANESE -> tr("🇯🇵  Japoński", "🇯🇵  Japanese")
                            AppLanguage.ENGLISH -> tr("🇬🇧  Angielski → polski", "🇬🇧  English → Polish")
                            AppLanguage.SPANISH -> tr("🇪🇸  Hiszpański → angielski", "🇪🇸  Spanish → English")
                        }
                        TextButton(
                            onClick = {
                                showLanguagePicker = false
                                // Picking the language already in use is a
                                // no-op, not a pointless restart.
                                if (language != viewModel.studyLanguage) {
                                    pendingStudyLanguage = language
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                label,
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = if (language == viewModel.studyLanguage) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguagePicker = false }) {
                    Text(tr("Anuluj", "Cancel"))
                }
            }
        )
    }

    val pendingLanguageTarget = pendingStudyLanguage
    if (pendingLanguageTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingStudyLanguage = null },
            title = { Text(tr("Zmienić język nauki?", "Change study language?")) },
            text = {
                Text(
                    tr(
                        "Aplikacja zostanie CAŁKOWICIE zamknięta i musisz uruchomić ją " +
                            "ponownie ręcznie.\n\n" +
                            "Twoje słowniki, fiszki i ustawienia pozostaną nienaruszone — " +
                            "słowniki drugiego języka po prostu przestaną pojawiać się " +
                            "w wynikach, dopóki nie wrócisz. Jeśli nie masz jeszcze " +
                            "słowników dla nowego języka, po restarcie pobierzesz je " +
                            "w sekcji Słowniki.",
                        "The app will be shut down COMPLETELY and you will have to " +
                            "launch it again yourself.\n\n" +
                            "Your dictionaries, cards and settings are left untouched - " +
                            "the other language's dictionaries simply stop appearing in " +
                            "results until you switch back. If you have no dictionaries " +
                            "for the new language yet, download them under Dictionaries " +
                            "after the restart."
                    ),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        viewModel.setStudyLanguage(pendingLanguageTarget)
                        // Same teardown as the post-restore restart: drop the
                        // back stack, then end the process so nothing that
                        // captured the previous language survives.
                        (context as? Activity)?.finishAffinity()
                        kotlin.system.exitProcess(0)
                    }
                }) {
                    Text(tr("Zmień i zamknij", "Change and close"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStudyLanguage = null }) {
                    Text(tr("Anuluj", "Cancel"))
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                "Głos wymowy (jeśli pobrany): ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.CREDIT}, silnik VOICEVOX CORE (MIT).",
                                "Pronunciation voice (if downloaded): ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.CREDIT}, VOICEVOX CORE engine (MIT)."
                            )
                        )
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

    if (showVoicevoxTerms) {
        AlertDialog(
            onDismissRequest = { showVoicevoxTerms = false },
            title = { Text(tr("Głos VOICEVOX — regulamin", "VOICEVOX voice — terms")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Pobierane są modele głosów VOICEVOX i słownik wymowy Open JTalk, razem około " +
                                "${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MB, z GitHuba.\n\n" +
                                "Regulamin głosów pozwala używać nagrań do nauki, pod warunkiem podpisu: " +
                                "„VOICEVOX Nemo” (dowolny użytek) i „VOICEVOX:No.7” (tylko niekomercyjnie). " +
                                "Nagrania z fiszek zostają u ciebie — przy publikowaniu ich trzeba podać ten podpis.",
                            "This downloads the VOICEVOX voice models and the Open JTalk pronunciation dictionary, about " +
                                "${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MB, from GitHub.\n\n" +
                                "The voice terms allow the recordings for study, with credit: " +
                                "\"VOICEVOX Nemo\" (any use) and \"VOICEVOX:No.7\" (non-commercial only). " +
                                "Card recordings stay with you — publishing them requires that credit."
                        ),
                        fontSize = 14.sp
                    )
                    TextButton(onClick = { uriHandler.openUri(com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TERMS_URL) }) {
                        Text(tr("Pełny regulamin (TERMS.txt)", "Full terms (TERMS.txt)"))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showVoicevoxTerms = false
                    viewModel.installVoicevox()
                }) { Text(tr("Akceptuję i pobieram", "Accept and download")) }
            },
            dismissButton = {
                TextButton(onClick = { showVoicevoxTerms = false }) { Text(tr("Anuluj", "Cancel")) }
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
            // No gap and no outer padding: rows are flush and a hairline
            // separates them, so a gap here would break every rule in two.
            // Horizontal padding lives inside the rows instead.
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ═══════════════════════════════════════
            // SECTION: Język nauki (Study language)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Translate,
                    title = tr("Język nauki", "Study language"),
                    first = true
                )
            }

            item {
                val current = viewModel.studyLanguage
                SettingsClickableItem(
                    icon = Icons.Default.Translate,
                    title = when (current) {
                        AppLanguage.JAPANESE -> tr("Japoński", "Japanese")
                        AppLanguage.ENGLISH -> tr("Angielski → polski", "English → Polish")
                        AppLanguage.SPANISH -> tr("Hiszpański → angielski", "Spanish → English")
                    },
                    subtitle = tr(
                        "Dotknij, aby zmienić. Wymaga restartu aplikacji.",
                        "Tap to change. Requires an app restart."
                    ),
                    onClick = { showLanguagePicker = true }
                )
            }

            if (!viewModel.studyLanguage.hasJapaneseFeatures) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                when (viewModel.studyLanguage) {
                                    AppLanguage.SPANISH -> tr(
                                        "Czego nie ma w trybie hiszpańskim",
                                        "Not available in Spanish mode"
                                    )
                                    else -> tr(
                                        "Czego nie ma w trybie angielskim",
                                        "Not available in English mode"
                                    )
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr(
                                    "• Ranking częstotliwości — dla angielskiego nie ma listy " +
                                        "w formacie Yomitan, więc słowa nie są sortowane od " +
                                        "najczęstszych słów.\n" +
                                        "• Generator talii JLPT, skaner napisów/EPUB i skan kolekcji Anki " +
                                        "— wyłączone; każde opiera się na czymś, czego angielski " +
                                        "nie ma (tagi JLPT, kolejność wg częstotliwości, " +
                                        "rozpoznawanie japońskich pól).\n" +
                                        "• Akcent tonalny, furigana i rozbiór kanji — zastąpione wymową IPA." +
                                        if (viewModel.studyLanguage == AppLanguage.ENGLISH) {
                                            "\n• Formy nieregularne (went, better) trzeba wpisać " +
                                                "w bezokoliczniku — słownik ich nie odmienia."
                                        } else {
                                            ""
                                        },
                                    "• Frequency ranking - no Yomitan-format list exists for English, " +
                                        "so results are not ordered by how common a word is.\n" +
                                        "• The JLPT deck generator, the subtitle/EPUB scanner and the Anki " +
                                        "collection scan are disabled - each rests on something " +
                                        "English has no source for (JLPT tags, frequency-based " +
                                        "study order, recognising Japanese fields).\n" +
                                        "• Pitch accent, furigana and kanji breakdown - replaced by IPA." +
                                        if (viewModel.studyLanguage == AppLanguage.ENGLISH) {
                                            "\n• Irregular forms (went, better) have to be typed as the " +
                                                "base word - the dictionary does not list them."
                                        } else {
                                            ""
                                        }
                                ),
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            }

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
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
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
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
                SettingsBlock {
                    Column {
                        Text(
                            tr("Motyw", "Theme"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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

            // Pronunciation archive
            item {
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("Archiwum wymowy", "Pronunciation archive"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when {
                                        audioArchiveIndexing -> tr(
                                            "Indeksowanie folderu…",
                                            "Indexing the folder…"
                                        )
                                        audioArchiveFiles > 0 -> tr(
                                            "$audioArchiveFiles nagrań" +
                                                (audioArchiveLabel?.let { " — $it" } ?: ""),
                                            "$audioArchiveFiles recordings" +
                                                (audioArchiveLabel?.let { " — $it" } ?: "")
                                        )
                                        else -> tr(
                                            "Brak. Fiszki mówią głosem syntezatora, który czyta hasło bez kontekstu i gubi akcent.",
                                            "None. Cards speak with the synthesiser, which reads a headword without context and drops the accent."
                                        )
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { audioArchivePicker.launch(null) },
                                enabled = !audioArchiveIndexing
                            ) {
                                Text(
                                    if (audioArchiveFiles > 0) tr("Zmień folder", "Change folder")
                                    else tr("Wskaż folder", "Pick a folder")
                                )
                            }
                            if (audioArchiveFiles > 0) {
                                OutlinedButton(
                                    onClick = viewModel::forgetAudioArchive,
                                    enabled = !audioArchiveIndexing
                                ) {
                                    Text(tr("Usuń", "Remove"))
                                }
                            }
                        }
                    }
                }
            }

            // VOICEVOX voice
            item {
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("Głos VOICEVOX", "VOICEVOX voice"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when (val state = voicevoxState) {
                                        is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Installed -> tr(
                                            "Neuronowy głos japoński, offline. Mówi czytanie z akcentem z karty. " +
                                                "Używany po archiwum wymowy, przed systemowym TTS.",
                                            "Neural Japanese voice, offline. Says the reading with the card's accent. " +
                                                "Used after the pronunciation archive, before the system TTS."
                                        )
                                        is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Downloading -> tr(
                                            "Pobieranie… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB",
                                            "Downloading… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB"
                                        )
                                        is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Failed -> tr(
                                            "Pobieranie nie powiodło się: ${state.message}",
                                            "Download failed: ${state.message}"
                                        )
                                        else -> tr(
                                            "Dużo lepszy od systemowego TTS: neuronowy, offline, z akcentem z karty. " +
                                                "Wymaga pobrania ok. ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MB.",
                                            "Far better than the system TTS: neural, offline, with the card's accent. " +
                                                "Needs a download of about ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MB."
                                        )
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (voicevoxState is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Installed) {
                                androidx.compose.material3.Switch(
                                    checked = voicevoxEnabled,
                                    onCheckedChange = viewModel::setVoicevoxEnabled
                                )
                            }
                        }
                        val downloading = voicevoxState as? com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Downloading
                        if (downloading != null) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = (downloading.done.toFloat() / downloading.total.coerceAtLeast(1)).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (voicevoxState) {
                                is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Installed -> {
                                    Button(onClick = viewModel::previewVoicevox, enabled = voicevoxEnabled) {
                                        Text(tr("Odsłuchaj", "Listen"))
                                    }
                                    OutlinedButton(onClick = viewModel::uninstallVoicevox) {
                                        Text(tr("Usuń", "Remove"))
                                    }
                                }
                                is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Downloading -> Unit
                                else -> Button(onClick = { showVoicevoxTerms = true }) {
                                    Text(
                                        if (voicevoxState is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Failed)
                                            tr("Spróbuj ponownie", "Try again")
                                        else tr("Pobierz głos", "Download the voice")
                                    )
                                }
                            }
                        }
                        Text(
                            com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.CREDIT,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                SettingsBlock {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Style,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                tr("Talia Anki", "Anki deck"),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (currentDeckName.isNotBlank()) currentDeckName
                                else tr("Nie wybrano (zostaniesz zapytany przy eksporcie)", "Not selected (you will be asked during export)"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            //
            // Japanese only. Outside it the Meaning field already follows a
            // fixed rule — the bilingual dictionary if it has the word, its
            // monolingual companion if not — so the JP-EN / JP-JP choice has
            // nothing to switch and setting it would appear to do something.
            if (viewModel.studyLanguage.hasJapaneseFeatures) {
            item {
                SettingsBlock {
                    Column {
                        Text(
                            tr("Silnik fiszek", "Card engine"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
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
            }

            // The JLPT generator, the text scanner and the collection scan
            // used to be three rows here. They are the work, not settings, and
            // now live in the Tools tab of the bottom bar.

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
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.EmojiEvents,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    tr("Cel dzienny fiszek", "Daily card goal"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (dailyGoalCount.toInt() == 0) tr("Wyłączony", "Disabled")
                                    else tr("${dailyGoalCount.toInt()} fiszek dziennie", "${dailyGoalCount.toInt()} cards/day"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            Text("50", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    tr("Język aplikacji", "App language"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when (currentLanguage) {
                                        "pl" -> tr("Polski", "Polish")
                                        "en" -> "English"
                                        else -> tr("Systemowy", "System")
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                SettingsClickableItem(
                    icon = Icons.Default.CloudDownload,
                    title = tr("Kopie zapasowe", "Backups"),
                    subtitle = tr(
                        "Utwórz, przywróć lub zaimportuj ustawienia z pliku",
                        "Create, restore, or import settings from a file"
                    ),
                    onClick = onNavigateToBackup
                )
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

/**
 * The section break and the tappable row both live in `ui/common` now — the
 * card style screen and the deck generators draw the same list.
 */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    first: Boolean = false
) = SettingsSectionHeader(icon = icon, title = title, first = first)

/**
 * A setting that needs room — chips, a slider, a pair of buttons.
 *
 * Same gutter and same hairline as [SettingsRow], so a compound setting reads
 * as one more entry in the list instead of a panel dropped into it.
 */
@Composable
private fun SettingsBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            content = content
        )
        SettingsDivider(inset = false)
    }
}

@Composable
private fun SettingsClickableItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) = SettingsRow(icon = icon, title = title, subtitle = subtitle, onClick = onClick)
