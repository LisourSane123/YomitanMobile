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
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.dataStore
import com.yomitanmobile.util.InputSanitizer
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
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val dictionaries by viewModel.dictionaries.collectAsState()
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
                else Toast.makeText(context, "Nie można otworzyć pliku", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Błąd: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ImportSuccess ->
                    Toast.makeText(context, "Zaimportowano ${event.result.dictionaryName}: ${event.result.entriesImported} wpisów", Toast.LENGTH_LONG).show()
                is SettingsEvent.ImportError ->
                    Toast.makeText(context, "Błąd importu: ${event.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    showDeleteDialog?.let { dictionaryName ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Usuń słownik") },
            text = { Text("Czy na pewno chcesz usunąć słownik \"$dictionaryName\" i wszystkie jego wpisy?") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteDictionary(dictionaryName); showDeleteDialog = null }) {
                    Text("Usuń", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Anuluj") }
            }
        )
    }

    if (showDeckEditDialog) {
        var editedDeckName by remember { mutableStateOf(currentDeckName.ifBlank { "Mining Deck" }) }
        AlertDialog(
            onDismissRequest = { showDeckEditDialog = false },
            title = { Text("Zmień talię Anki") },
            text = {
                Column {
                    Text(
                        "Nowe fiszki będą dodawane do wybranej talii.",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = editedDeckName,
                        onValueChange = { editedDeckName = it },
                        label = { Text("Nazwa talii") },
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
                    Text("Zapisz")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeckEditDialog = false }) { Text("Anuluj") }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Polityka Prywatności") },
            text = {
                LazyColumn {
                    item {
                        Text(
                            text = "Aplikacja działa w pełni offline (lokalnie). Nie zbieramy, " +
                                    "nie przechowujemy, ani nie wysyłamy żadnych danych osobistych " +
                                    "na zewnętrzne serwery. Wymaga połączenia z internetem " +
                                    "jedynie w celu pobrania słowników od dostawców zewnętrznych.",
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
            title = { Text("O aplikacji i licencje") },
            text = {
                LazyColumn {
                    item {
                        Text("Wersja aplikacji: 1.0.0", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Aplikacja korzysta z otwartych słowników do działania. " +
                                "Dostępne słowniki m.in. JMdict oraz KANJIDIC są udostępniane " +
                                "na licencjach Creative Commons Attribution-ShareAlike 4.0 International " +
                                "lub podobnych.\n\n" +
                                "Własność i prawa autorskie:", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("JMdict/Kanjidic (EDRDG - Electronic Dictionary Research and Development Group)")
                        Spacer(Modifier.height(8.dp))
                        Text("Tatoeba Project (CC-BY 2.0 FR) dla przykładowych zdań (jeśli zaimportowane).")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("Zamknij") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
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
                    title = "Słowniki"
                )
            }

            item {
                Button(
                    onClick = onNavigateToDownload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pobierz słowniki z internetu")
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
                    Text("Importuj słownik Yomitan (.zip)")
                }
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
                                Text("Importowanie słownika...")
                            }
                            importProgress?.let { progress ->
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(progress = progress.progressPercent, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Plik ${progress.filesProcessed}/${progress.totalFiles} • ${progress.entriesProcessed} wpisów",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // Installed dictionaries
            if (dictionaries.isNotEmpty()) {
                item {
                    Text(
                        "Zainstalowane słowniki (${dictionaries.size})",
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
                            Text("Brak zainstalowanych słowników", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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
                    title = "Wygląd"
                )
            }

            // Theme mode toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Motyw", fontWeight = FontWeight.Bold)
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
                                label = { Text("Systemowy") },
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
                                label = { Text("Jasny") },
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
                                label = { Text("Ciemny") },
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
                    title = "Wygląd fiszki Anki",
                    subtitle = "Czcionka, rozmiar, kolory, podgląd",
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
                                "Talia Anki",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (currentDeckName.isNotBlank()) currentDeckName
                                else "Nie wybrano (zostaniesz zapytany przy eksporcie)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        IconButton(onClick = { showDeckEditDialog = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Zmień talię",
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
                                "Wymowa TTS",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Wymowa japońska przez Google TTS. " +
                                    "Po otwarciu słowa wymowa odtwarza się automatycznie.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // ═══════════════════════════════════════
            // SECTION: Inne (Other)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.EmojiEvents,
                    title = "Inne"
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
                                    "Cel dzienny fiszek",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (dailyGoalCount.toInt() == 0) "Wyłączony"
                                    else "${dailyGoalCount.toInt()} fiszek dziennie",
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
                                if (dailyGoalCount.toInt() == 0) "Wyłączony" else "${dailyGoalCount.toInt()}",
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
                            "Ustaw na 0 aby wyłączyć cel dzienny",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
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
                                    "Język aplikacji",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    when (currentLanguage) {
                                        "pl" -> "Polski"
                                        "en" -> "English"
                                        else -> "Systemowy"
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "system" to "Systemowy",
                                "pl" to "Polski",
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

            // Statistics button
            item {
                SettingsClickableItem(
                    icon = Icons.Default.BarChart,
                    title = "Statystyki",
                    subtitle = "Przegląd aktywności, streak, wykres fiszek",
                    onClick = onNavigateToStatistics
                )
            }

            // ═══════════════════════════════════════
            // SECTION: Informacje (About)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Info,
                    title = "Informacje"
                )
            }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Policy,
                    title = "Polityka Prywatności",
                    subtitle = "Zasady prywatności i lokalne przetwarzanie danych",
                    onClick = { showPrivacyDialog = true }
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.MenuBook,
                    title = "Licencje słowników",
                    subtitle = "Informacje o otwartych danych i prawach autorskich",
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

@Composable
private fun DictionaryCard(dictionary: DictionaryInfo, onDelete: () -> Unit) {
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
                Text("${dictionary.entryCount} wpisów", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (dictionary.revision.isNotBlank()) {
                    Text("Wersja: ${dictionary.revision}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
                Text("Dodano: ${dateFormat.format(Date(dictionary.importDate))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Usuń słownik", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
