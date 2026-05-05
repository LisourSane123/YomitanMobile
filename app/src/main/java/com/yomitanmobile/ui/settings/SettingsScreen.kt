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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Style
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
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    val isImporting by viewModel.isImporting.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()
    val isRestoring by viewModel.isRestoring.collectAsState()
    var showDeckEditDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var selectedBackupForRestore by remember { mutableStateOf<File?>(null) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var currentDeckName by remember { mutableStateOf("") }
    var currentThemeMode by remember { mutableStateOf("system") }
    var currentLanguage by remember { mutableStateOf("system") }
    var dailyGoalCount by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    // Load current deck name, theme mode and daily goal
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        currentDeckName = prefs[MainActivity.ANKI_DECK_NAME] ?: ""
        currentThemeMode = prefs[MainActivity.THEME_MODE] ?: "system"
        dailyGoalCount = (prefs[MainActivity.DAILY_GOAL_COUNT] ?: 0).toFloat()
        // Language is stored in SharedPreferences (needed for synchronous read at startup)
        val langPrefs = context.getSharedPreferences(MainActivity.LANG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        currentLanguage = langPrefs.getString(MainActivity.LANG_PREFS_KEY, "system") ?: "system"
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
                    Toast.makeText(context, tr("Przywrócono z kopii. Proszę zrestartować aplikację.", "Restored. Please restart the app."), Toast.LENGTH_LONG).show()
                    showRestoreDialog = false
                }
                is SettingsEvent.RestoreError ->
                    Toast.makeText(context, tr("Błąd przywracania: ${event.message}", "Restore error: ${event.message}"), Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showDeckEditDialog) {
        var editedDeckName by remember { mutableStateOf(currentDeckName.ifBlank { "Mining Deck" }) }
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
                TextButton(onClick = {
                    val sanitized = InputSanitizer.sanitizeDeckName(editedDeckName)
                    currentDeckName = sanitized
                    coroutineScope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[MainActivity.ANKI_DECK_NAME] = sanitized
                        }
                    }
                    showDeckEditDialog = false
                }) {
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
                        Text(tr("Wersja aplikacji: 1.0.0", "App version: 1.0.0"), fontWeight = FontWeight.Bold)
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
                Button(
                    onClick = { 
                        if (!isBackingUp) {
                            viewModel.createBackup()
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
