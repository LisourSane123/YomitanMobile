package com.yomitanmobile.ui.detail

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.domain.model.WordEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val entry by viewModel.entry.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val ttsReady by viewModel.ttsReady.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeckDialog by remember { mutableStateOf(false) }
    var availableDecks by remember { mutableStateOf<List<String>>(emptyList()) }

    val ankiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.exportToAnki()
        } else {
            Toast.makeText(context, "Uprawnienia do AnkiDroid zostały odrzucone.", Toast.LENGTH_LONG).show()
        }
    }

    // Deck selection dialog
    if (showDeckDialog) {
        DeckSelectionDialog(
            existingDecks = availableDecks,
            onDeckSelected = { deckName ->
                showDeckDialog = false
                viewModel.exportToAnkiWithDeck(deckName)
            },
            onDismiss = { showDeckDialog = false }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.AnkiExportSuccess ->
                    snackbarHostState.showSnackbar("Fiszka dodana do Anki!")
                is DetailEvent.AnkiExportError ->
                    snackbarHostState.showSnackbar("Błąd: ${event.message}")
                is DetailEvent.AnkiPermissionRequired ->
                    ankiPermissionLauncher.launch("com.ichi2.anki.permission.READ_WRITE_DATABASE")
                is DetailEvent.AnkiNotInstalled ->
                    Toast.makeText(context, "AnkiDroid nie jest zainstalowany!", Toast.LENGTH_LONG).show()
                is DetailEvent.AnkiDeckSelectionRequired -> {
                    availableDecks = event.decks
                    showDeckDialog = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.displayText() ?: "Szczegóły") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Wróć")
                    }
                },
                actions = {
                    if (entry != null) {
                        IconButton(
                            onClick = { viewModel.exportToAnki() },
                            enabled = !isExporting
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Eksportuj do Anki",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            entry == null -> {
                Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    Text("Nie znaleziono wpisu")
                }
            }
            else -> {
                WordDetailContent(
                    entry = entry!!,
                    isPlaying = isPlaying,
                    ttsReady = ttsReady,
                    onPlayAudio = viewModel::playAudio,
                    onStopAudio = viewModel::stopAudio,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@Composable
private fun WordDetailContent(
    entry: WordEntry,
    isPlaying: Boolean,
    ttsReady: Boolean,
    onPlayAudio: () -> Unit,
    onStopAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Main word card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(entry.displayText(), fontSize = 52.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                if (entry.reading.isNotBlank() && entry.reading != entry.expression) {
                    Text(entry.reading, fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                val freqLabel = entry.frequencyLabel()
                if (freqLabel.isNotBlank()) {
                    Text(freqLabel, fontSize = 14.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { if (isPlaying) onStopAudio() else onPlayAudio() },
                    enabled = ttsReady || entry.audioFile.isNotBlank()
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Zatrzymaj" else "Odtwórz wymowę",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isPlaying) "Zatrzymaj" else "Odtwórz wymowę")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Pitch Accent
        if (entry.pitchAccent.isNotBlank()) {
            SectionCard(title = "Pitch Accent") {
                PitchAccentDiagram(
                    reading = entry.reading.ifBlank { entry.expression },
                    pitchPositions = entry.pitchAccent
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Definitions
        SectionCard(title = "Znaczenie") {
            entry.definitions.forEachIndexed { index, definition ->
                if (index > 0) Divider(modifier = Modifier.padding(vertical = 8.dp))
                Row {
                    Text("${index + 1}. ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(definition, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Parts of speech
        if (entry.partsOfSpeech.isNotBlank()) {
            SectionCard(title = "Część mowy") {
                Text(entry.partsOfSpeech, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }

        // Example sentence
        if (entry.exampleSentence.isNotBlank()) {
            SectionCard(title = "Przykładowe zdanie") {
                Text(entry.exampleSentence, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                if (entry.exampleSentenceTranslation.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(entry.exampleSentenceTranslation, fontSize = 14.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Dictionary source
        if (entry.dictionaryName.isNotBlank()) {
            Text("Źródło: ${entry.dictionaryName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

/**
 * Visual pitch accent diagram.
 * [pitchPositions] is a comma-separated string of pitch drop positions (e.g. "0", "1", "3").
 * Position 0 = heiban (flat), 1 = atamadaka, N = nakadaka/odaka.
 *
 * The diagram shows mora characters with high/low lines above them indicating pitch.
 */
@Composable
private fun PitchAccentDiagram(
    reading: String,
    pitchPositions: String,
    modifier: Modifier = Modifier
) {
    val positions = pitchPositions.split(",").mapNotNull { it.trim().toIntOrNull() }
    if (positions.isEmpty()) return

    val morae = splitIntoMorae(reading)
    if (morae.isEmpty()) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    Column(modifier = modifier) {
        positions.forEach { dropPos ->
            val pitchPattern = computePitchPattern(morae.size, dropPos)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Text label for the pattern type
                val patternName = when (dropPos) {
                    0 -> "平板 (heiban)"
                    1 -> "頭高 (atamadaka)"
                    morae.size -> "尾高 (odaka)"
                    else -> "中高 (nakadaka)"
                }
                Text(
                    text = "[$dropPos] $patternName",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

            // Draw the pitch diagram
            val highY = 8f
            val lowY = 40f
            val moraWidth = 48f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp)
            ) {
                val canvasWidth = size.width
                val actualMoraWidth = minOf(moraWidth, canvasWidth / morae.size)

                for (i in pitchPattern.indices) {
                    val x = i * actualMoraWidth + actualMoraWidth / 2
                    val y = if (pitchPattern[i]) highY else lowY
                    val circleColor = if (pitchPattern[i]) primaryColor else errorColor

                    drawCircle(
                        color = circleColor,
                        radius = 6f,
                        center = Offset(x, y)
                    )

                    // Draw connecting line to next mora
                    if (i < pitchPattern.size - 1) {
                        val nextX = (i + 1) * actualMoraWidth + actualMoraWidth / 2
                        val nextY = if (pitchPattern[i + 1]) highY else lowY
                        drawLine(
                            color = circleColor,
                            start = Offset(x, y),
                            end = Offset(nextX, nextY),
                            strokeWidth = 3f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // Mora labels
            Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                morae.forEach { mora ->
                    Text(
                        text = mora,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(48.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Splits a Japanese reading into morae (syllable units).
 * Small kana (ゃ, ゅ, ょ, ぁ, ぃ, ぅ, ぇ, ぉ, ァ, ィ, ゥ, ェ, ォ, ャ, ュ, ョ) 
 * are attached to the preceding mora.
 */
private fun splitIntoMorae(reading: String): List<String> {
    val smallKana = setOf(
        'ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ',
        'ャ', 'ュ', 'ョ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ',
        'っ', 'ッ', 'ー'
    )
    val result = mutableListOf<String>()
    var i = 0
    while (i < reading.length) {
        val sb = StringBuilder()
        sb.append(reading[i])
        i++
        while (i < reading.length && reading[i] in smallKana) {
            sb.append(reading[i])
            i++
        }
        result.add(sb.toString())
    }
    return result
}

/**
 * Computes the high(true)/low(false) pattern for each mora.
 * Japanese pitch accent rules:
 * - dropPos 0 (heiban): LHHH...H (low first, then all high)
 * - dropPos 1 (atamadaka): HLLL...L (high first, then all low)
 * - dropPos N (nakadaka): LHHH...HLL (low first, high until position N, then low)
 * - dropPos = moraCount (odaka): LHHH...H (like heiban but drops after last mora)
 */
private fun computePitchPattern(moraCount: Int, dropPos: Int): List<Boolean> {
    if (moraCount == 0) return emptyList()
    if (moraCount == 1) return listOf(dropPos != 0)

    return List(moraCount) { i ->
        when {
            dropPos == 0 -> i > 0  // heiban: low-high-high...
            dropPos == 1 -> i == 0  // atamadaka: high-low-low...
            else -> i > 0 && i < dropPos  // nakadaka/odaka: low-high...-low
        }
    }
}

@Composable
private fun DeckSelectionDialog(
    existingDecks: List<String>,
    onDeckSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newDeckName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wybierz talię Anki") },
        text = {
            Column {
                if (existingDecks.isNotEmpty()) {
                    Text(
                        "Istniejące talie:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    existingDecks.forEach { deck ->
                        OutlinedButton(
                            onClick = { onDeckSelected(deck) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(deck, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "Lub utwórz nową talię:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = newDeckName,
                    onValueChange = { newDeckName = it },
                    label = { Text("Nazwa talii") },
                    placeholder = { Text("np. Mining Deck") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDeckSelected(newDeckName.ifBlank { "Mining Deck" }) },
                enabled = true
            ) {
                Text("Utwórz")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anuluj") }
        }
    )
}
