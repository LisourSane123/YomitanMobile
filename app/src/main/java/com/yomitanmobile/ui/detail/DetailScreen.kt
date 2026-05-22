package com.yomitanmobile.ui.detail

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.util.JlptLevelUtil
import com.yomitanmobile.util.PartsOfSpeechFormatter

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
    val isFavorite by viewModel.isFavorite.collectAsState()
    val lookupCount by viewModel.lookupCount.collectAsState()
    val isEnglish = com.yomitanmobile.util.LocaleHelper.isEnglish(LocalConfiguration.current)
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeckDialog by remember { mutableStateOf(false) }
    var availableDecks by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicateInfo by remember { mutableStateOf("" to "") }
    // Tracks whether the user clicked the plain or the AI-flavored
    // export button. Carried across deck-pick / duplicate / permission
    // round-trips so dialog confirmations don't lose the choice.
    var pendingIncludeAi by remember { mutableStateOf(false) }
    // Non-null while the export coroutine is parked waiting for the user
    // to decide what to do after a failed AI summary call. The string is
    // the provider's error message shown verbatim in the dialog.
    var aiFailureMessage by remember { mutableStateOf<String?>(null) }

    val ankiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.exportToAnki(pendingIncludeAi)
        } else {
            Toast.makeText(context, tr("Uprawnienia do AnkiDroid zostały odrzucone.", "AnkiDroid permissions were denied."), Toast.LENGTH_LONG).show()
        }
    }

    // Deck selection dialog
    if (showDeckDialog) {
        DeckSelectionDialog(
            existingDecks = availableDecks,
            onDeckSelected = { deckName ->
                showDeckDialog = false
                viewModel.exportToAnkiWithDeck(deckName, pendingIncludeAi)
            },
            onDismiss = { showDeckDialog = false }
        )
    }

    // Duplicate export warning dialog
    if (showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = false },
            title = { Text(tr("Fiszka już wyeksportowana", "Card already exported")) },
            text = {
                Text(tr(
                    "Słowo \"${duplicateInfo.first}\" zostało już wyeksportowane do talii \"${duplicateInfo.second}\". Czy chcesz wyeksportować ponownie?",
                    "The word \"${duplicateInfo.first}\" has already been exported to the deck \"${duplicateInfo.second}\". Do you want to export it again?"
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDuplicateDialog = false
                    viewModel.forceExport(pendingIncludeAi)
                }) {
                    Text(tr("Eksportuj ponownie", "Export again"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicateDialog = false }) {
                    Text(tr("Anuluj", "Cancel"))
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.AnkiExportSuccess ->
                    snackbarHostState.showSnackbar(tr("Fiszka dodana do Anki!", "Card added to Anki!"))
                is DetailEvent.AnkiExportError ->
                    snackbarHostState.showSnackbar(tr("Błąd: ${event.message}", "Error: ${event.message}"))
                is DetailEvent.AnkiPermissionRequired ->
                    ankiPermissionLauncher.launch("com.ichi2.anki.permission.READ_WRITE_DATABASE")
                is DetailEvent.AnkiNotInstalled ->
                    Toast.makeText(context, tr("AnkiDroid nie jest zainstalowany!", "AnkiDroid is not installed!"), Toast.LENGTH_LONG).show()
                is DetailEvent.AnkiDeckSelectionRequired -> {
                    availableDecks = event.decks
                    showDeckDialog = true
                }
                is DetailEvent.AlreadyExported -> {
                    duplicateInfo = event.expression to event.deckName
                    showDuplicateDialog = true
                }
                is DetailEvent.AiSummaryFailedNeedsChoice ->
                    aiFailureMessage = event.message
                is DetailEvent.AnkiExportCancelled ->
                    snackbarHostState.showSnackbar(
                        tr("Eksport anulowany.", "Export cancelled.")
                    )
            }
        }
    }

    aiFailureMessage?.let { message ->
        // Mandatory choice — neither dismissing the dialog nor clicking
        // outside the scrim closes it without a decision. We treat
        // "outside click" as "cancel" because aborting is the safer
        // default when the user hasn't made an explicit call. Either
        // branch resumes the parked export coroutine via the VM.
        AlertDialog(
            onDismissRequest = {
                aiFailureMessage = null
                viewModel.resolveAiFailure(AiFailureChoice.CANCEL_EXPORT)
            },
            title = { Text(tr("Streszczenie AI nie powiodło się", "AI summary failed")) },
            text = {
                Text(
                    tr(
                        "Powód: $message\n\nMożesz utworzyć fiszkę bez streszczenia lub przerwać eksport.",
                        "Reason: $message\n\nYou can still create the card without the summary, or abort the export."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    aiFailureMessage = null
                    viewModel.resolveAiFailure(AiFailureChoice.CONTINUE_WITHOUT_AI)
                }) {
                    Text(tr("Utwórz bez AI", "Create without AI"))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    aiFailureMessage = null
                    viewModel.resolveAiFailure(AiFailureChoice.CANCEL_EXPORT)
                }) {
                    Text(tr("Anuluj eksport", "Cancel export"))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.displayText() ?: tr("Szczegóły", "Details")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back"))
                    }
                },
                actions = {
                    if (entry != null) {
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (isFavorite) tr("Usuń z ulubionych", "Remove from favorites") else tr("Dodaj do ulubionych", "Add to favorites"),
                                tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        // Plain export — no AI summary, fast.
                        IconButton(
                            onClick = {
                                pendingIncludeAi = false
                                viewModel.exportToAnki(includeAiSummary = false)
                            },
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
                                    contentDescription = tr("Eksportuj do Anki", "Export to Anki"),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        // AI-flavored export — calls the LLM for a summary.
                        // Visually distinct: gradient pill + sparkle icon
                        // so it's obvious which one will hit the API.
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF7E57C2),
                                            Color(0xFFEC407A)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = {
                                    pendingIncludeAi = true
                                    viewModel.exportToAnki(includeAiSummary = true)
                                },
                                enabled = !isExporting,
                                modifier = Modifier.size(40.dp)
                            ) {
                                if (isExporting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = tr(
                                            "Eksportuj do Anki ze streszczeniem AI",
                                            "Export to Anki with AI summary"
                                        ),
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
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
                    Text(tr("Nie znaleziono wpisu", "Entry not found"))
                }
            }
            else -> {
                WordDetailContent(
                    entry = entry!!,
                    isPlaying = isPlaying,
                    ttsReady = ttsReady,
                    onPlayAudio = viewModel::playAudio,
                    onStopAudio = viewModel::stopAudio,
                    isEnglish = isEnglish,
                    lookupCount = lookupCount,
                    isFavorite = isFavorite,
                    onToggleFavorite = { viewModel.toggleFavorite() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordDetailContent(
    entry: MergedWordEntry,
    isPlaying: Boolean,
    ttsReady: Boolean,
    onPlayAudio: () -> Unit,
    onStopAudio: () -> Unit,
    isEnglish: Boolean,
    lookupCount: Int,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
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
                if (entry.reading.isNotBlank() && entry.reading != entry.primaryExpression) {
                    Text(entry.reading, fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                }

                // Examples no longer render in the header card — they appear
                // directly under their corresponding meaning in the "Znaczenie"
                // section below, so the user sees which sense each sentence
                // illustrates.

                // Alternative expressions/forms
                if (entry.alternativeExpressions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tr(
                            "Alternatywne formy: ${entry.alternativeExpressions.joinToString(", ")}",
                            "Alternative forms: ${entry.alternativeExpressions.joinToString(", ")}"
                        ),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontStyle = FontStyle.Italic
                    )
                }
                Spacer(Modifier.height(8.dp))
                val freqLabel = entry.frequencyLabel()
                if (freqLabel.isNotBlank()) {
                    Text(freqLabel, fontSize = 14.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Medium)
                }
                val jlptLevel = JlptLevelUtil.fromDbValue(entry.jlptLevel)
                // The classifier-derived category chip was removed at
                // the user's request — the JLPT and lookup-count badges
                // stay because they directly help the learner judge a
                // word at a glance. Category is still tracked in the DB
                // for stats; the manual_category column is now write-only
                // from the UI side.
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (jlptLevel != null) {
                        Text(
                            text = "JLPT ${jlptLevel.label}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier
                                .background(
                                    color = androidx.compose.ui.graphics.Color(jlptLevel.color),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                    // Lookup count badge. The displayed value is
                    // `lookupCount - 1` so a first-ever lookup shows
                    // nothing, the second shows "1×", third shows "2×",
                    // and so on — i.e. the chip means "you've seen this
                    // before, this many times." Hidden until the user
                    // returns at least once.
                    if (lookupCount >= 2) {
                        val previousLookups = lookupCount - 1
                        if (jlptLevel != null) Spacer(Modifier.width(8.dp))
                        Text(
                            text = tr(
                                "Sprawdzone ${previousLookups}×",
                                "Looked up ${previousLookups}×"
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
                // Repeated-lookup prompt. The threshold (3) is empirical
                // — fewer than that and the user is probably just
                // browsing; more and the "I keep coming back to this
                // word" signal is real. We only nudge if the word isn't
                // already favorited, to avoid annoying users who already
                // committed to learning it.
                if (lookupCount >= 3 && !isFavorite) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tr(
                                "Sprawdzasz to słowo regularnie — może warto je dodać do ulubionych?",
                                "You keep coming back to this word — maybe favorite it?"
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onToggleFavorite) {
                            Text(tr("Dodaj", "Favorite"))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { if (isPlaying) onStopAudio() else onPlayAudio() },
                    enabled = ttsReady || entry.audioFile.isNotBlank()
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) tr("Zatrzymaj", "Stop") else tr("Odtwórz wymowę", "Play pronunciation"),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isPlaying) tr("Zatrzymaj", "Stop") else tr("Odtwórz wymowę", "Play pronunciation"))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Pitch Accent
        if (entry.pitchAccent.isNotBlank()) {
            SectionCard(title = "Pitch Accent") {
                PitchAccentDiagram(
                    reading = entry.reading.ifBlank { entry.primaryExpression },
                    pitchPositions = entry.pitchAccent
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Usage notes (e.g. "usually kana", "formal", "mimetic"). Promoted to
        // their own card directly above the meaning so the reader sees the
        // register/usage caveat before reading the gloss — easy to miss when
        // it's just a small chip next to the JLPT badge.
        if (entry.usageTags.isNotEmpty()) {
            SectionCard(title = tr("Notatki", "Notes")) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    entry.usageTags.forEach { tag ->
                        Text(
                            text = tag,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Definitions, with the example sentence(s) for each meaning rendered
        // directly underneath. Examples whose definitionIndex is -1 are
        // unattached (legacy data, online Tatoeba results, plain JMDict
        // imports) — those are shown after the last meaning.
        SectionCard(title = tr("Znaczenie", "Meaning")) {
            val examplesByDef = entry.examples.groupBy { it.definitionIndex }
            entry.definitions.forEachIndexed { index, definition ->
                if (index > 0) Divider(modifier = Modifier.padding(vertical = 8.dp))
                Row {
                    Text("${index + 1}. ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(definition, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                examplesByDef[index]?.take(3)?.forEachIndexed { exIdx, ex ->
                    Spacer(Modifier.height(if (exIdx == 0) 6.dp else 4.dp))
                    Text(
                        text = ex.jp,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(start = 20.dp)
                    )
                    if (ex.en.isNotBlank()) {
                        Text(
                            text = ex.en,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(start = 20.dp)
                        )
                    }
                }
            }

            // Unattached examples (definitionIndex == -1) — older data path or
            // online Tatoeba responses where we can't tie the sentence to a
            // specific gloss.
            val unattached = examplesByDef[-1].orEmpty()
                .ifEmpty {
                    if (entry.examples.isEmpty() && entry.exampleSentence.isNotBlank()) {
                        listOf(
                            com.yomitanmobile.domain.model.ExamplePair(
                                jp = entry.exampleSentence,
                                en = entry.exampleSentenceTranslation
                            )
                        )
                    } else emptyList()
                }
            if (unattached.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(6.dp))
                unattached.take(3).forEachIndexed { idx, ex ->
                    if (idx > 0) Spacer(Modifier.height(4.dp))
                    Text(
                        text = ex.jp,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        lineHeight = 20.sp
                    )
                    if (ex.en.isNotBlank()) {
                        Text(
                            text = ex.en,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Parts of speech
        val posLabel = PartsOfSpeechFormatter.format(entry.partsOfSpeech.joinToString(" "))
        if (posLabel.isNotEmpty()) {
            SectionCard(title = tr("Część mowy", "Part of speech")) {
                Text(posLabel, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }

        // Dictionary source
        if (entry.dictionaryName.isNotBlank()) {
            Text(tr("Źródło: ${entry.dictionaryName}", "Source: ${entry.dictionaryName}"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 4.dp))
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
    val isEnglish = com.yomitanmobile.util.LocaleHelper.isEnglish(LocalConfiguration.current)
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Wybierz talię Anki", "Choose Anki deck")) },
        text = {
            Column {
                if (existingDecks.isNotEmpty()) {
                    Text(
                        tr("Istniejące talie:", "Existing decks:"),
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
                    tr("Lub utwórz nową talię:", "Or create a new deck:"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = newDeckName,
                    onValueChange = { newDeckName = it },
                    label = { Text(tr("Nazwa talii", "Deck name")) },
                    placeholder = { Text(tr("np. Mining Deck", "e.g. Mining Deck")) },
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
                Text(tr("Utwórz", "Create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Anuluj", "Cancel")) }
        }
    )
}
