package com.yomitanmobile.ui.jlptdeck

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.domain.model.JlptSkipReason
import com.yomitanmobile.util.LocaleHelper
import kotlin.math.roundToInt

/**
 * Bulk deck generator: turns a whole JLPT level into cards styled exactly like
 * the ones the detail screen exports, with a dry run first so the user sees
 * what would be created (and what was filtered out) before anything is
 * written to AnkiDroid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JlptDeckScreen(
    onNavigateBack: () -> Unit,
    viewModel: JlptDeckViewModel = hiltViewModel()
) {
    val isEnglish = LocaleHelper.isEnglish(LocalConfiguration.current)
    fun tr(pl: String, en: String) = if (isEnglish) en else pl
    val context = LocalContext.current

    // Both the dry run (it scans the collection for duplicates) and the write
    // need AnkiDroid's read/write permission. Asking here, at the moment the
    // user presses the button, beats the old advice of "export one card
    // manually first to grant it".
    var pendingAnkiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ankiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val action = pendingAnkiAction
        pendingAnkiAction = null
        if (isGranted) {
            action?.invoke()
        } else {
            Toast.makeText(
                context,
                tr(
                    "Odrzucono uprawnienie do AnkiDroida — bez niego nie da się sprawdzić duplikatów ani utworzyć talii.",
                    "AnkiDroid permission denied — without it duplicates can't be checked and no deck can be created."
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    fun withAnkiPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, ANKI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingAnkiAction = action
            ankiPermissionLauncher.launch(ANKI_PERMISSION)
        }
    }

    val level by viewModel.level.collectAsState()
    val filters by viewModel.filters.collectAsState()
    val deckName by viewModel.deckName.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val taggedWordCount by viewModel.taggedWordCount.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is JlptDeckEvent.Finished -> tr(
                    "Utworzono ${event.result.added} kart w talii „${event.result.deckName}”" +
                        if (event.result.failed > 0) " (${event.result.failed} odrzucone)" else "",
                    "Created ${event.result.added} cards in deck “${event.result.deckName}”" +
                        if (event.result.failed > 0) " (${event.result.failed} rejected)" else ""
                )
                is JlptDeckEvent.Error -> tr("Błąd: ${event.message}", "Error: ${event.message}")
                JlptDeckEvent.PermissionRequired -> {
                    pendingAnkiAction = { viewModel.generate() }
                    ankiPermissionLauncher.launch(ANKI_PERMISSION)
                    tr("Potrzebne uprawnienie do AnkiDroida", "AnkiDroid permission needed")
                }
                JlptDeckEvent.AnkiNotInstalled -> tr("AnkiDroid nie jest zainstalowany", "AnkiDroid is not installed")
                JlptDeckEvent.Cancelled -> tr("Przerwano generowanie", "Generation cancelled")
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Talia JLPT", "JLPT deck")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wstecz", "Back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                tr(
                    "Tworzy gotową talię ze wszystkich słów wybranego poziomu JLPT — bez kopania słowo po słowie. " +
                        "Fiszki mają dokładnie ten sam wygląd, co eksport ze szczegółów słowa.",
                    "Builds a ready deck from every word of the chosen JLPT level — no word-by-word mining. " +
                        "The cards use exactly the same styling as a single export from the detail screen."
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            SectionTitle(tr("Poziom", "Level"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JlptDeckViewModel.LEVELS.forEach { candidate ->
                    FilterChip(
                        selected = level == candidate,
                        onClick = { viewModel.setLevel(candidate) },
                        label = { Text("N$candidate") }
                    )
                }
            }

            // Coverage warning. Without a JLPT-tagged dictionary the generator
            // falls back to the small curated built-in list, which produces a
            // deck a fraction of the real level's size — say so before the
            // user generates it, not after.
            if (taggedWordCount == 0) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        tr(
                            "Żaden zainstalowany słownik nie oznacza słów poziomem N$level. " +
                                "Talia powstanie tylko z wbudowanej, krótkiej listy — zainstaluj " +
                                "słownik „JLPT Vocab Tags” z ekranu pobierania słowników, aby " +
                                "dostać pełny poziom.",
                            "No installed dictionary tags words with level N$level. The deck " +
                                "would be built from the short built-in list only — install the " +
                                "“JLPT Vocab Tags” dictionary from the dictionary download screen " +
                                "to get the full level."
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = deckName,
                onValueChange = viewModel::setDeckName,
                label = { Text(tr("Nazwa talii w Anki", "Anki deck name")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            SectionTitle(tr("Filtry", "Filters"))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (filters.maxFrequencyRank > 0) {
                            tr(
                                "Pomiń słowa rzadsze niż #${filters.maxFrequencyRank}",
                                "Skip words rarer than #${filters.maxFrequencyRank}"
                            )
                        } else {
                            tr("Bez limitu rzadkości", "No rarity limit")
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        tr(
                            "Ranga z zainstalowanych list częstotliwości. 0 = filtr wyłączony.",
                            "Rank from the installed frequency lists. 0 = filter off."
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = filters.maxFrequencyRank.toFloat(),
                        onValueChange = { value ->
                            val rounded = (value / 1000f).roundToInt() * 1000
                            viewModel.updateFilters { it.copy(maxFrequencyRank = rounded) }
                        },
                        valueRange = 0f..100_000f,
                        steps = 99
                    )

                    ToggleRow(
                        title = tr("Uwzględnij słowa bez rangi", "Include unranked words"),
                        subtitle = tr(
                            "Bez zainstalowanej listy częstotliwości wszystkie słowa są „bez rangi” — wyłączenie tego da pustą talię.",
                            "With no frequency list installed every word is unranked — turning this off yields an empty deck."
                        ),
                        checked = filters.includeUnranked,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(includeUnranked = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń archaizmy i słowa rzadkie", "Skip archaic and rare words"),
                        subtitle = tr(
                            "Tagi: archaic, obsolete, rare, dated.",
                            "Tags: archaic, obsolete, rare, dated."
                        ),
                        checked = filters.skipArchaic,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipArchaic = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń nazwy własne", "Skip proper names"),
                        subtitle = tr(
                            "Wpisy z JMnedict: nazwiska, miejsca, firmy.",
                            "JMnedict entries: surnames, places, companies."
                        ),
                        checked = filters.skipProperNames,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipProperNames = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń słowa już w Anki", "Skip words already in Anki"),
                        subtitle = tr(
                            "Skanuje całą kolekcję, także talie Core 2k/6k/10k i Kaishi 1.5k oraz wcześniej wygenerowane poziomy.",
                            "Scans the whole collection, including Core 2k/6k/10k and Kaishi 1.5k decks and previously generated levels."
                        ),
                        checked = filters.skipAlreadyInAnki,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipAlreadyInAnki = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń słowa już wykopane", "Skip words already mined"),
                        subtitle = tr(
                            "Słowa wyeksportowane wcześniej z tej aplikacji.",
                            "Words this app exported before."
                        ),
                        checked = filters.skipAlreadyMined,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipAlreadyMined = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Generuj audio (wolne)", "Generate audio (slow)"),
                        subtitle = tr(
                            "Syntezator mowy dla każdego słowa — około sekundy na kartę.",
                            "Text-to-speech per word — roughly a second per card."
                        ),
                        checked = filters.generateAudio,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(generateAudio = value) }
                        }
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (filters.maxWords > 0) {
                            tr("Maksymalnie ${filters.maxWords} kart", "At most ${filters.maxWords} cards")
                        } else {
                            tr("Bez limitu liczby kart", "No card-count limit")
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        tr(
                            "Przy limicie zostają słowa najczęstsze.",
                            "When capped, the most frequent words are kept."
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = filters.maxWords.toFloat(),
                        onValueChange = { value ->
                            val rounded = (value / 50f).roundToInt() * 50
                            viewModel.updateFilters { it.copy(maxWords = rounded) }
                        },
                        valueRange = 0f..2000f,
                        steps = 39
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            val busy = progress != null
            Button(
                onClick = { withAnkiPermission { viewModel.analyze() } },
                enabled = !isAnalyzing && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("  " + tr("Analizuję…", "Analysing…"))
                } else {
                    Text(tr("Sprawdź, co powstanie", "Check what would be created"))
                }
            }

            val currentPlan = plan
            if (currentPlan != null) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            tr(
                                "Do utworzenia: ${currentPlan.selectedCount} kart",
                                "To create: ${currentPlan.selectedCount} cards"
                            ),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            tr(
                                "Kandydatów na poziomie N${currentPlan.level}: ${currentPlan.candidateCount}",
                                "Candidates at level N${currentPlan.level}: ${currentPlan.candidateCount}"
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (currentPlan.scannedNoteCount > 0) {
                            Text(
                                tr(
                                    "Przeskanowano ${currentPlan.scannedNoteCount} notatek w Anki",
                                    "Scanned ${currentPlan.scannedNoteCount} notes in Anki"
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (currentPlan.ankiScanUnavailable) {
                            Text(
                                tr(
                                    "Nie udało się odczytać kolekcji Anki — sprawdzanie duplikatów wyłączone.",
                                    "Could not read the Anki collection — duplicate check disabled."
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (currentPlan.candidateCount == 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tr(
                                    "Żadne zainstalowane słowniki nie mają słów z tego poziomu. Pobierz Jitendex " +
                                        "albo słownik „JLPT Vocab Tags” z ekranu pobierania słowników.",
                                    "None of the installed dictionaries cover this level. Download Jitendex or the " +
                                        "“JLPT Vocab Tags” dictionary from the dictionary download screen."
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (currentPlan.skipped.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                            currentPlan.skipped.forEach { (reason, count) ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        skipReasonLabel(reason, isEnglish),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("$count", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        if (currentPlan.selected.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tr("Przykładowe słowa:", "Sample words:"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                currentPlan.selected.take(20).joinToString("、") { it.displayText() },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { withAnkiPermission { viewModel.generate() } },
                    enabled = currentPlan.selectedCount > 0 && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        tr(
                            "Utwórz ${currentPlan.selectedCount} fiszek",
                            "Create ${currentPlan.selectedCount} cards"
                        )
                    )
                }
                Text(
                    tr(
                        "Karty dostają tagi yomitan-mobile i jlpt-n${currentPlan.level}, więc łatwo je w Anki odnaleźć lub usunąć. " +
                            "Nie są liczone w statystykach kopania.",
                        "Cards are tagged yomitan-mobile and jlpt-n${currentPlan.level}, so they are easy to find or delete in Anki. " +
                            "They are not counted in the mining statistics."
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            val currentProgress = progress
            if (currentProgress != null) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            tr(
                                "Tworzę fiszki: ${currentProgress.done} / ${currentProgress.total}",
                                "Creating cards: ${currentProgress.done} / ${currentProgress.total}"
                            ),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (currentProgress.currentWord.isNotBlank()) {
                            Text(
                                currentProgress.currentWord,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = if (currentProgress.total > 0) {
                                currentProgress.done.toFloat() / currentProgress.total
                            } else 0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.cancelGeneration() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(tr("Przerwij", "Cancel"))
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** AnkiDroid's read/write permission, mirrored from AnkiCardCreator. */
private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

private fun skipReasonLabel(reason: JlptSkipReason, isEnglish: Boolean): String = when (reason) {
    JlptSkipReason.NO_DEFINITION ->
        if (isEnglish) "No definition in the dictionary" else "Brak definicji w słowniku"
    JlptSkipReason.TOO_RARE ->
        if (isEnglish) "Too rare" else "Zbyt rzadkie"
    JlptSkipReason.UNRANKED ->
        if (isEnglish) "No frequency data" else "Bez danych o częstości"
    JlptSkipReason.ARCHAIC ->
        if (isEnglish) "Archaic / obsolete / rare" else "Archaizmy i przestarzałe"
    JlptSkipReason.PROPER_NAME ->
        if (isEnglish) "Proper names" else "Nazwy własne"
    JlptSkipReason.ALREADY_IN_ANKI ->
        if (isEnglish) "Already in Anki" else "Już w Anki"
    JlptSkipReason.ALREADY_MINED ->
        if (isEnglish) "Already mined in the app" else "Już wykopane w aplikacji"
    JlptSkipReason.OVER_LIMIT ->
        if (isEnglish) "Over the card limit" else "Ponad limit kart"
}
