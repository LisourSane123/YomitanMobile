package com.yomitanmobile.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.dataStore
import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.WordCategoryClassifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val dictionaries by viewModel.dictionaries.collectAsState()
    val minedCategoryStats by viewModel.minedCategoryStats.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showDeckEditDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var currentDeckName by remember { mutableStateOf("") }
    var currentThemeMode by remember { mutableStateOf("system") }
    var currentLanguage by remember { mutableStateOf("system") }
    var dailyGoalCount by remember { mutableStateOf(0f) }
    var sentenceApiConsentGranted by remember { mutableStateOf(false) }
    var showSentenceApiConsentDialog by remember { mutableStateOf(false) }
    var showCardQualityInDetails by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    // Load current deck name, theme mode and daily goal
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        currentDeckName = prefs[MainActivity.ANKI_DECK_NAME] ?: ""
        currentThemeMode = prefs[MainActivity.THEME_MODE] ?: "system"
        dailyGoalCount = (prefs[MainActivity.DAILY_GOAL_COUNT] ?: 0).toFloat()
        sentenceApiConsentGranted = prefs[MainActivity.SENTENCE_API_CONSENT_GRANTED] ?: false
        showCardQualityInDetails = prefs[MainActivity.DETAIL_SHOW_CARD_QUALITY] ?: true
        // Language is stored in SharedPreferences (needed for synchronous read at startup)
        val langPrefs = context.getSharedPreferences(MainActivity.LANG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        currentLanguage = langPrefs.getString(MainActivity.LANG_PREFS_KEY, "system") ?: "system"
    }

    if (showSentenceApiConsentDialog) {
        AlertDialog(
            onDismissRequest = { showSentenceApiConsentDialog = false },
            title = { Text(tr("Zgoda na API zdań", "Sentence API consent")) },
            text = {
                Text(
                    tr(
                        "Aplikacja będzie wysyłać wyszukiwane słowo do zewnętrznego API tylko w celu pobrania przykładowego zdania. Zgodę możesz odwołać w dowolnym momencie.",
                        "The app will send the searched word to an external API only to fetch an example sentence. You can revoke consent at any time."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sentenceApiConsentGranted = true
                    coroutineScope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[MainActivity.SENTENCE_API_CONSENT_GRANTED] = true
                        }
                    }
                    showSentenceApiConsentDialog = false
                }) {
                    Text(tr("Wyrażam zgodę", "I agree"))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSentenceApiConsentDialog = false
                }) {
                    Text(tr("Anuluj", "Cancel"))
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
            }
        }
    }

    showDeleteDialog?.let { dictionaryName ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(tr("Usuń słownik", "Delete dictionary")) },
            text = {
                Text(
                    tr(
                        "Czy na pewno chcesz usunąć słownik \"$dictionaryName\" i wszystkie jego wpisy?",
                        "Are you sure you want to delete dictionary \"$dictionaryName\" and all its entries?"
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteDictionary(dictionaryName); showDeleteDialog = null }) {
                    Text(tr("Usuń", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text(tr("Anuluj", "Cancel")) }
            }
        )
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
                    val name = InputSanitizer.sanitizeDeckName(editedDeckName)
                    currentDeckName = name
                    coroutineScope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[MainActivity.ANKI_DECK_NAME] = name
                        }
                    }
                    showDeckEditDialog = false
                }) {
                    Text(tr("Zapisz", "Save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeckEditDialog = false }) { Text(tr("Anuluj", "Cancel")) }
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
                            text = tr(
                                "Aplikacja działa w pełni offline (lokalnie). Nie zbieramy, nie przechowujemy, ani nie wysyłamy żadnych danych osobistych na zewnętrzne serwery. Wymaga połączenia z internetem jedynie w celu pobrania słowników od dostawców zewnętrznych.",
                                "The app works fully offline (locally). We do not collect, store, or send any personal data to external servers. Internet access is required only to download dictionaries from third-party providers."
                            ),
                            fontSize = 14.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("OK") }
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

            item {
                DictionaryCategoryDistributionCard(
                    categoryStats = minedCategoryStats,
                    title = tr("Kategorie kopanych słów", "Mined word categories"),
                    subtitle = tr(
                        "Rozkład wszystkich skopanych słów według kategorii.",
                        "Distribution of all mined words by category."
                    ),
                    top3Title = tr("Top 3 kategorie", "Top 3 categories"),
                    allCategoriesTitle = tr("Wszystkie kategorie", "All categories"),
                    emptyText = tr(
                        "Brak danych kategorii. Skop pierwsze słowa, aby zobaczyć wykres.",
                        "No category data yet. Mine your first words to see the chart."
                    )
                )
            }

            // Installed dictionaries
            if (dictionaries.isNotEmpty()) {
                item {
                    Text(
                        tr("Zainstalowane słowniki (${dictionaries.size})", "Installed dictionaries (${dictionaries.size})"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(dictionaries, key = { it.id }) { dict ->
                    DictionaryCard(dictionary = dict, onDelete = { showDeleteDialog = dict.name })
                }
            } else if (!isImporting) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text(tr("Brak zainstalowanych słowników", "No installed dictionaries"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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

            // TTS info
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
                            Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                tr("Wymowa TTS", "TTS pronunciation"),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                tr(
                                    "Wymowa japońska przez Google TTS. Po otwarciu słowa wymowa odtwarza się automatycznie.",
                                    "Japanese pronunciation via Google TTS. After opening a word, pronunciation plays automatically."
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Card quality section toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                tr("Pokaż jakość fiszki", "Show card quality"),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                tr(
                                    "Wyświetla sekcję oceny jakości na ekranie szczegółów słowa.",
                                    "Shows quality scoring section on the word detail screen."
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = showCardQualityInDetails,
                            onCheckedChange = { enabled ->
                                showCardQualityInDetails = enabled
                                coroutineScope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[MainActivity.DETAIL_SHOW_CARD_QUALITY] = enabled
                                    }
                                }
                            }
                        )
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

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                tr("Zgoda na API zdań", "Sentence API consent"),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                tr(
                                    "Wymagane do pobierania przykładowych zdań z internetu. Użycie API włączasz osobno w stylu fiszki.",
                                    "Required to fetch example sentences from the internet. API usage is enabled separately in card style."
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = sentenceApiConsentGranted,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    sentenceApiConsentGranted = false
                                    coroutineScope.launch {
                                        context.dataStore.edit { prefs ->
                                            prefs[MainActivity.SENTENCE_API_CONSENT_GRANTED] = false
                                            prefs[MainActivity.CARD_USE_ONLINE_SENTENCE_API] = false
                                        }
                                    }
                                } else if (!sentenceApiConsentGranted) {
                                    showSentenceApiConsentDialog = true
                                }
                            }
                        )
                    }
                }
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

private val CategoryChartColors = listOf(
    Color(0xFFEF5350),
    Color(0xFF42A5F5),
    Color(0xFF66BB6A),
    Color(0xFFFFCA28),
    Color(0xFFAB47BC),
    Color(0xFF26A69A),
    Color(0xFFFF7043),
    Color(0xFF7E57C2),
    Color(0xFF8D6E63),
    Color(0xFFEC407A),
    Color(0xFF29B6F6),
    Color(0xFFD4E157)
)

@Composable
private fun DictionaryCategoryDistributionCard(
    categoryStats: List<MinedCategoryStat>,
    title: String,
    subtitle: String,
    top3Title: String,
    allCategoriesTitle: String,
    emptyText: String
) {
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
    val colorByCode = remember(categoryStats) {
        categoryStats.mapIndexed { index, stat ->
            stat.code to CategoryChartColors[index % CategoryChartColors.size]
        }.toMap()
    }
    val nonZeroStats = categoryStats.filter { it.count > 0 }
    val totalCount = nonZeroStats.sumOf { it.count }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )

            if (totalCount <= 0) {
                Text(
                    emptyText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            } else {
                val donutCenterColor = MaterialTheme.colorScheme.surface
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(220.dp)) {
                        var startAngle = -90f
                        nonZeroStats.forEach { stat ->
                            val sweep = 360f * stat.count.toFloat() / totalCount.toFloat()
                            drawArc(
                                color = colorByCode[stat.code] ?: Color.Gray,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = true
                            )
                            startAngle += sweep
                        }

                        drawCircle(
                            color = donutCenterColor,
                            radius = size.minDimension * 0.28f
                        )
                    }

                    Text(
                        text = totalCount.toString(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    top3Title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                nonZeroStats
                    .sortedByDescending { it.count }
                    .take(3)
                    .forEachIndexed { index, stat ->
                        val localizedLabel = WordCategoryClassifier.displayName(stat.code, isEnglish)
                        val percent = if (totalCount == 0) 0f else (stat.count.toFloat() * 100f / totalCount.toFloat())
                        val color = colorByCode[stat.code] ?: Color.Gray
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(color = color, shape = CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${index + 1}. $localizedLabel: ${String.format(Locale.getDefault(), "%.1f", percent)}% (${stat.count})",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                Text(
                    allCategoriesTitle,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                categoryStats.forEach { stat ->
                    val localizedLabel = WordCategoryClassifier.displayName(stat.code, isEnglish)
                    val color = colorByCode[stat.code] ?: Color.Gray
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color = color, shape = CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$localizedLabel: ${stat.count}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                }
            }
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

@Composable
private fun DictionaryCard(dictionary: DictionaryInfo, onDelete: () -> Unit) {
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(dictionary.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${dictionary.entryCount} ${tr("wpisów", "entries")}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dictionary.revision.isNotBlank()) {
                    Text("${tr("Wersja", "Version")}: ${dictionary.revision}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
                Text("${tr("Dodano", "Added")}: ${dateFormat.format(Date(dictionary.importDate))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = tr("Usuń słownik", "Delete dictionary"), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
