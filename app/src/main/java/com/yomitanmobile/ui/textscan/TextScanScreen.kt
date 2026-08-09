package com.yomitanmobile.ui.textscan

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.domain.model.FrequencyTier
import com.yomitanmobile.domain.model.TextScanSkipReason
import com.yomitanmobile.util.LocaleHelper
import kotlin.math.roundToInt

/**
 * "Make cards from what I actually watched or read": the user picks a subtitle
 * file or an EPUB, the app segments it against the installed dictionaries and
 * offers cards for the words that are not already known.
 *
 * Same shape as the JLPT deck screen — dry run first, then write — because it
 * is the same promise: nothing reaches AnkiDroid before the user has seen what
 * would be created and what was filtered out.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TextScanScreen(
    onNavigateBack: () -> Unit,
    viewModel: TextScanViewModel = hiltViewModel()
) {
    val isEnglish = LocaleHelper.isEnglish(LocalConfiguration.current)
    fun tr(pl: String, en: String) = if (isEnglish) en else pl
    val context = LocalContext.current

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
                    "Odrzucono uprawnienie do AnkiDroida — bez niego nie da się utworzyć fiszek.",
                    "AnkiDroid permission denied — no cards can be created without it."
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    fun withAnkiPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, ANKI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) action() else {
            pendingAnkiAction = action
            ankiPermissionLauncher.launch(ANKI_PERMISSION)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.analyze(uri) }

    val filters by viewModel.filters.collectAsState()
    val deckName by viewModel.deckName.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val analysisStage by viewModel.analysisStage.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val progress by viewModel.progress.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is TextScanEvent.Finished -> tr(
                    "Utworzono ${event.result.added} kart w talii „${event.result.deckName}”" +
                        if (event.result.failed > 0) " (${event.result.failed} odrzucone)" else "",
                    "Created ${event.result.added} cards in deck “${event.result.deckName}”" +
                        if (event.result.failed > 0) " (${event.result.failed} rejected)" else ""
                )
                is TextScanEvent.Error -> tr("Błąd: ${event.message}", "Error: ${event.message}")
                is TextScanEvent.FileTooLarge -> tr(
                    "Plik jest za duży (${event.megabytes} MB).",
                    "File is too large (${event.megabytes} MB)."
                )
                TextScanEvent.UnsupportedFormat -> tr(
                    "Nieobsługiwany format pliku. Obsługiwane: .srt, .ass, .ssa, .vtt, .txt, .epub.",
                    "Unsupported file format. Supported: .srt, .ass, .ssa, .vtt, .txt, .epub."
                )
                TextScanEvent.NoDictionary -> tr(
                    "Brak zainstalowanego słownika — bez niego nie da się podzielić tekstu na słowa.",
                    "No dictionary installed — without one the text cannot be split into words."
                )
                TextScanEvent.PermissionRequired -> {
                    pendingAnkiAction = { viewModel.generate() }
                    ankiPermissionLauncher.launch(ANKI_PERMISSION)
                    tr("Potrzebne uprawnienie do AnkiDroida", "AnkiDroid permission needed")
                }
                TextScanEvent.AnkiNotInstalled ->
                    tr("AnkiDroid nie jest zainstalowany", "AnkiDroid is not installed")
                TextScanEvent.Cancelled -> tr("Przerwano tworzenie fiszek", "Card creation cancelled")
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Skan tekstu", "Text scan")) },
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
                    "Wczytaj napisy (.srt, .ass, .ssa, .vtt), książkę (.epub) lub zwykły tekst (.txt). " +
                        "Aplikacja podzieli tekst na słowa, odrzuci te, które już znasz (kolekcja Anki " +
                        "i wcześniejsze eksporty) i zrobi fiszki z reszty.",
                    "Load subtitles (.srt, .ass, .ssa, .vtt), a book (.epub) or plain text (.txt). " +
                        "The app splits the text into words, drops the ones you already know (Anki " +
                        "collection and earlier exports) and makes cards from the rest."
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = !isAnalyzing && progress == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tr("Wybierz plik", "Pick a file"))
            }

            if (isAnalyzing) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        "  " + stageLabel(analysisStage, isEnglish),
                        fontSize = 14.sp
                    )
                }
            }

            val currentPlan = plan
            if (currentPlan != null) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            currentPlan.source.fileName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            tr(
                                "${currentPlan.source.formatLabel} · ${currentPlan.source.charsetName} · " +
                                    "${currentPlan.source.characterCount} znaków japońskich",
                                "${currentPlan.source.formatLabel} · ${currentPlan.source.charsetName} · " +
                                    "${currentPlan.source.characterCount} Japanese characters"
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            tr(
                                "Słów w tekście: ${currentPlan.totalTokenCount}, różnych: ${currentPlan.distinctWordCount}",
                                "Words in the text: ${currentPlan.totalTokenCount}, distinct: ${currentPlan.distinctWordCount}"
                            ),
                            fontSize = 13.sp
                        )
                        if (currentPlan.totalTokenCount > 0) {
                            Text(
                                tr(
                                    "Rozumiesz już ok. ${(currentPlan.knownCoverage * 100).roundToInt()}% tekstu",
                                    "You already know about ${(currentPlan.knownCoverage * 100).roundToInt()}% of the text"
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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

            SectionTitle(tr("Zakres częstotliwości", "Frequency range"))
            Text(
                tr(
                    "Fiszki tylko ze słów mieszczących się w wybranym progu list częstotliwości.",
                    "Cards only for words within the chosen frequency-list cut-off."
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrequencyTier.SELECTABLE.forEach { tier ->
                    FilterChip(
                        selected = filters.tier == tier,
                        onClick = { viewModel.updateFilters { it.copy(tier = tier) } },
                        label = { Text(tier.label(isEnglish)) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionTitle(tr("Filtry", "Filters"))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (filters.minOccurrences <= 1) {
                            tr("Każde wystąpienie się liczy", "Every occurrence counts")
                        } else {
                            tr(
                                "Tylko słowa występujące co najmniej ${filters.minOccurrences} razy",
                                "Only words occurring at least ${filters.minOccurrences} times"
                            )
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        tr(
                            "W książce słowo widziane raz rzadko jest warte fiszki.",
                            "In a book, a word seen once is rarely worth a card."
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = filters.minOccurrences.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateFilters { it.copy(minOccurrences = value.roundToInt()) }
                        },
                        valueRange = 1f..10f,
                        steps = 8
                    )

                    ToggleRow(
                        title = tr("Uwzględnij słowa bez rangi", "Include unranked words"),
                        subtitle = tr(
                            "Bez listy częstotliwości wszystkie słowa są „bez rangi” — wyłączenie tego da pustą talię.",
                            "With no frequency list installed every word is unranked — turning this off yields an empty deck."
                        ),
                        checked = filters.includeUnranked,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(includeUnranked = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń słowa gramatyczne", "Skip function words"),
                        subtitle = tr(
                            "Partykuły, です/ます, する/いる — inaczej pierwsze sto fiszek to sama gramatyka.",
                            "Particles, です/ます, する/いる — otherwise the first hundred cards are pure grammar."
                        ),
                        checked = filters.skipFunctionWords,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipFunctionWords = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń słowa już w Anki", "Skip words already in Anki"),
                        subtitle = tr(
                            "Na podstawie zapisanego skanu kolekcji (ekran „Skan kolekcji Anki”).",
                            "Based on the stored collection scan (the “Anki collection scan” screen)."
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
                        title = tr("Pomiń archaizmy i słowa rzadkie", "Skip archaic and rare words"),
                        subtitle = tr("Tagi: archaic, obsolete, rare, dated.", "Tags: archaic, obsolete, rare, dated."),
                        checked = filters.skipArchaic,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipArchaic = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń nazwy własne", "Skip proper names"),
                        subtitle = tr(
                            "Nazwiska i nazwy miejsc z napisów potrafią zdominować listę.",
                            "Surnames and place names from subtitles can dominate the list."
                        ),
                        checked = filters.skipProperNames,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipProperNames = value) }
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
                            "Przy limicie zostają słowa najczęstsze w tym tekście.",
                            "When capped, the words most frequent in this text are kept."
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = filters.maxWords.toFloat(),
                        onValueChange = { value ->
                            val rounded = (value / 25f).roundToInt() * 25
                            viewModel.updateFilters { it.copy(maxWords = rounded) }
                        },
                        valueRange = 0f..1000f,
                        steps = 39
                    )
                }
            }

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
                        if (currentPlan.ankiScanUnavailable) {
                            Text(
                                tr(
                                    "Kolekcja Anki nie była jeszcze skanowana — nie wiadomo, które słowa już masz. " +
                                        "Uruchom „Skan kolekcji Anki” w ustawieniach.",
                                    "The Anki collection has never been scanned — it is unknown which words you " +
                                        "already have. Run “Anki collection scan” in the settings."
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
                                tr("Najczęstsze nieznane słowa:", "Most frequent unknown words:"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                currentPlan.selected.take(20).joinToString("、") {
                                    "${it.entry.displayText()}(${it.occurrences})"
                                },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { withAnkiPermission { viewModel.generate() } },
                    enabled = currentPlan.selectedCount > 0 && progress == null,
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
                        "Karty dostają tagi yomitan-mobile i text-scan oraz nazwę pliku, więc łatwo je w Anki " +
                            "odnaleźć lub usunąć. Nie są liczone w statystykach kopania.",
                        "Cards are tagged yomitan-mobile, text-scan and the file name, so they are easy to find " +
                            "or delete in Anki. They are not counted in the mining statistics."
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
    Spacer(Modifier.height(4.dp))
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
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** AnkiDroid's read/write permission, mirrored from AnkiCardCreator. */
private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

private fun stageLabel(stage: String, isEnglish: Boolean): String = when (stage) {
    TextScanViewModel.STAGE_READING ->
        if (isEnglish) "Reading the file…" else "Czytam plik…"
    TextScanViewModel.STAGE_LEXICON ->
        if (isEnglish) "Loading the dictionary…" else "Ładuję słownik…"
    TextScanViewModel.STAGE_TOKENIZING ->
        if (isEnglish) "Splitting the text into words…" else "Dzielę tekst na słowa…"
    TextScanViewModel.STAGE_RESOLVING ->
        if (isEnglish) "Looking words up…" else "Szukam słów w słowniku…"
    TextScanViewModel.STAGE_COMPARING ->
        if (isEnglish) "Comparing with what you know…" else "Porównuję ze znanymi słowami…"
    else -> if (isEnglish) "Analysing…" else "Analizuję…"
}

private fun skipReasonLabel(reason: TextScanSkipReason, isEnglish: Boolean): String = when (reason) {
    TextScanSkipReason.NOT_IN_DICTIONARY ->
        if (isEnglish) "Not in any dictionary" else "Brak w słownikach"
    TextScanSkipReason.TOO_FEW_OCCURRENCES ->
        if (isEnglish) "Too few occurrences" else "Za mało wystąpień"
    TextScanSkipReason.FUNCTION_WORD ->
        if (isEnglish) "Grammar / function words" else "Słowa gramatyczne"
    TextScanSkipReason.NO_DEFINITION ->
        if (isEnglish) "No definition in the dictionary" else "Brak definicji w słowniku"
    TextScanSkipReason.TOO_RARE ->
        if (isEnglish) "Outside the frequency range" else "Poza zakresem częstotliwości"
    TextScanSkipReason.UNRANKED ->
        if (isEnglish) "No frequency data" else "Bez danych o częstości"
    TextScanSkipReason.ARCHAIC ->
        if (isEnglish) "Archaic / obsolete / rare" else "Archaizmy i przestarzałe"
    TextScanSkipReason.PROPER_NAME ->
        if (isEnglish) "Proper names" else "Nazwy własne"
    TextScanSkipReason.ALREADY_IN_ANKI ->
        if (isEnglish) "Already in Anki" else "Już w Anki"
    TextScanSkipReason.ALREADY_MINED ->
        if (isEnglish) "Already mined in the app" else "Już wykopane w aplikacji"
    TextScanSkipReason.OVER_LIMIT ->
        if (isEnglish) "Over the card limit" else "Ponad limit kart"
}
