package com.yomitanmobile.ui.kanji

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.yomitanmobile.data.anki.AnkiCollectionStore
import com.yomitanmobile.data.anki.KanjiStudyExport
import com.yomitanmobile.data.anki.KanjiTally
import com.yomitanmobile.ui.common.rememberTr
import com.yomitanmobile.ui.common.tr

/**
 * The kanji browser: a bucket (jōyō grade or JLPT level), how much of it the
 * collection covers, and the characters themselves.
 *
 * The coverage figure is the point of the screen. The app already knew every
 * kanji its dictionaries describe and every word in the user's AnkiDroid
 * collection, and could not answer the one question a learner asks of those
 * two facts together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanjiBrowseScreen(
    onNavigateBack: () -> Unit,
    onKanjiClick: (String) -> Unit,
    viewModel: KanjiViewModel = hiltViewModel()
) {
    val buckets by viewModel.buckets.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val kanji by viewModel.kanji.collectAsState()
    val known by viewModel.knownKanji.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val empty by viewModel.empty.collectAsState()
    val needsReimport by viewModel.needsReimport.collectAsState()
    val tally by viewModel.tally.collectAsState()
    val matureTally by viewModel.matureTally.collectAsState()

    // Two questions about the same data, and only one of them needs a kanji
    // dictionary: "how much of jōyō grade 3 do I have" is a join with KANJIDIC,
    // while "which characters are my own cards made of" is the scan alone.
    var showMine by remember { mutableStateOf(false) }
    val scope by viewModel.scope.collectAsState()
    val studiedTally by viewModel.studiedTally.collectAsState()
    val exportPlan by viewModel.exportPlan.collectAsState()
    val shareFile by viewModel.shareFile.collectAsState()
    val context = LocalContext.current

    // Kanji Study reads a plain list from a file it is given; both ways of
    // handing it over are offered, because only the file one is documented.
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(KanjiStudyExport.MIME_TYPE)
    ) { uri -> uri?.let(viewModel::exportToFile) }

    LaunchedEffect(shareFile) {
        val uri = shareFile ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).apply {
            type = KanjiStudyExport.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, KanjiStudyExport.FILE_NAME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
        viewModel.shareHandled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Kanji", "Kanji")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (!loading) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showMine,
                        onClick = { showMine = false },
                        label = { Text(tr("Pokrycie", "Coverage")) }
                    )
                    FilterChip(
                        selected = showMine,
                        onClick = { showMine = true },
                        label = { Text(tr("Moje kanji", "My kanji")) }
                    )
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                showMine -> MyKanjiSection(
                    tally = when (scope) {
                        AnkiCollectionStore.Scope.ANY -> tally
                        AnkiCollectionStore.Scope.STUDIED -> studiedTally
                        AnkiCollectionStore.Scope.MATURE -> matureTally
                    },
                    scope = scope,
                    onScopeChange = viewModel::setScope,
                    exportPlan = exportPlan,
                    onSave = { saveLauncher.launch(KanjiStudyExport.FILE_NAME) },
                    onShare = viewModel::prepareShare,
                    onKanjiClick = onKanjiClick
                )

                empty -> Notice(
                    tr(
                        "Żaden zainstalowany słownik nie opisuje kanji. Pobierz KANJIDIC " +
                            "z ekranu słowników — bez niego ten ekran nie ma czego pokazać.",
                        "No installed dictionary describes kanji. Install KANJIDIC from the " +
                            "dictionary screen — without it this screen has nothing to show."
                    )
                )

                else -> {
                    if (needsReimport) {
                        Spacer(Modifier.height(12.dp))
                        Notice(
                            tr(
                                "Twój słownik kanji nie ma poziomów ani klas — został " +
                                    "zaimportowany, zanim aplikacja je czytała. Zaimportuj go " +
                                    "ponownie, aby podzielić znaki na poziomy.",
                                "Your kanji dictionary carries no grades or JLPT levels — it " +
                                    "was imported before the app read them. Re-import it to get " +
                                    "the buckets."
                            )
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        buckets.forEach { bucket ->
                            FilterChip(
                                selected = bucket.label == selected?.label,
                                onClick = { viewModel.select(bucket) },
                                label = { Text(bucket.label) }
                            )
                        }
                    }

                    selected?.let { bucket ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tr(
                                "Masz fiszki z ${bucket.known} z ${bucket.total} znaków",
                                "You have cards using ${bucket.known} of ${bucket.total} characters"
                            ),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = bucket.coverage,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (bucket.mature > 0) {
                                tr(
                                    "Z tego ${bucket.mature} na fiszkach dojrzałych " +
                                        "(interwał 21 dni lub więcej) — to jest ta liczba, " +
                                        "która mówi coś o pamięci.",
                                    "Of those, ${bucket.mature} sit on mature cards (interval " +
                                        "21 days or more) — that is the number that says " +
                                        "something about memory."
                                )
                            } else {
                                tr(
                                    "„Znane” znaczy: jakaś fiszka w kolekcji zawiera ten znak. " +
                                        "To nie jest pomiar pamięci — przeskanuj kolekcję, " +
                                        "żeby poznać też dojrzałość kart.",
                                    "“Known” means: some card in the collection carries this " +
                                        "character. It is not a measure of recall — rescan the " +
                                        "collection to learn card maturity too."
                                )
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 56.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(kanji, key = { it.kanji }) { row ->
                            val isKnown = row.kanji in known
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isKnown) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { onKanjiClick(row.kanji) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    row.kanji,
                                    fontSize = 26.sp,
                                    color = if (isKnown) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The collection cut into characters: every kanji the user has a card for, and
 * how many of their words carry it, commonest first.
 *
 * Needs no kanji dictionary — this is the scan and nothing else, which is why
 * it sits before the "install KANJIDIC" notice rather than behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyKanjiSection(
    tally: KanjiTally.KanjiTallyResult,
    scope: AnkiCollectionStore.Scope,
    onScopeChange: (AnkiCollectionStore.Scope) -> Unit,
    exportPlan: KanjiStudyExport.Plan,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onKanjiClick: (String) -> Unit
) {
    val tr = rememberTr()
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Which cards the characters may come from. The two narrower scopes
        // leave suspended cards out by construction — a card taken out of
        // rotation is not something to build a study list on — so there is no
        // separate switch for them.
        listOf(
            AnkiCollectionStore.Scope.STUDIED to tr("Zaczęte", "Started"),
            AnkiCollectionStore.Scope.MATURE to tr("Dojrzałe", "Mature"),
            AnkiCollectionStore.Scope.ANY to tr("Wszystkie karty", "All cards")
        ).forEach { (value, label) ->
            FilterChip(
                selected = scope == value,
                onClick = { onScopeChange(value) },
                label = { Text(label) }
            )
        }
    }
    Text(
        when (scope) {
            AnkiCollectionStore.Scope.STUDIED -> tr(
                "Słowa z kart, które już się pojawiły — bez nowych i bez zawieszonych.",
                "Words from cards that have come up at least once — no new ones, none suspended."
            )
            AnkiCollectionStore.Scope.MATURE -> tr(
                "Tylko karty z interwałem 21 dni lub więcej. To ta liczba, która mówi coś o pamięci.",
                "Only cards with an interval of 21 days or more — the number that says something " +
                    "about memory."
            )
            AnkiCollectionStore.Scope.ANY -> tr(
                "Każde słowo ze skanu, także z kart nowych i zawieszonych.",
                "Every word in the scan, including new and suspended cards."
            )
        },
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (tally.counts.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Notice(
            if (scope != AnkiCollectionStore.Scope.ANY) {
                tr(
                    "Żadna karta w tym zakresie nie zawiera kanji. Stan kart zna tylko skan " +
                        "zrobiony przez nowszą wersję aplikacji — przeskanuj kolekcję ponownie " +
                        "(Narzędzia → Skan kolekcji Anki).",
                    "No card in this scope carries a kanji. Card state is only known to a scan " +
                        "taken by a newer version of the app — rescan the collection " +
                        "(Tools → Anki collection scan)."
                )
            } else {
                tr(
                    "Nie ma zapisanego skanu kolekcji. Zrób go w Narzędzia → Skan kolekcji " +
                        "Anki; ten ekran liczy kanji w słowach, które skan znalazł.",
                    "There is no stored collection scan. Take one in Tools → Anki collection " +
                        "scan; this screen counts the kanji in the words it found."
                )
            }
        )
        return
    }

    Spacer(Modifier.height(12.dp))
    Text(
        tr(
            "${tally.distinctKanji} różnych kanji, ${tally.totalOccurrences} wystąpień " +
                "w ${tally.wordsWithKanji} słowach",
            "${tally.distinctKanji} different kanji, ${tally.totalOccurrences} occurrences " +
                "across ${tally.wordsWithKanji} words"
        ),
        fontWeight = FontWeight.Medium
    )
    Text(
        tr(
            "Liczone po słowach ze skanu kolekcji. 日曜日 to jedno słowo z 日 i dwa " +
                "wystąpienia.",
            "Counted over the words in the collection scan. 日曜日 is one word with 日 in it " +
                "and two occurrences."
        ),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!exportPlan.isEmpty) {
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Do Kanji Study: ${exportPlan.kanji.size} znaków w ${exportPlan.sets.size} " +
                    "zestawach po ${exportPlan.setSize}. Bez ${exportPlan.dropped} znaków " +
                    "z jednego słowa — te nie mają się gdzie utrwalić.",
                "For Kanji Study: ${exportPlan.kanji.size} characters in ${exportPlan.sets.size} " +
                    "sets of ${exportPlan.setSize}. Without the ${exportPlan.dropped} characters " +
                    "that sit in a single word — those have nowhere to stick."
            ),
            fontSize = 12.sp
        )
        Text(
            if (exportPlan.unranked == exportPlan.kanji.size) {
                tr(
                    "Kolejność: najwięcej moich słów najpierw. Twój słownik kanji nie podaje " +
                        "częstości w mediach, więc remisy rozstrzyga sam znak — zaimportuj " +
                        "KANJIDIC ponownie, żeby je ułożyć sensownie.",
                    "Order: most of my words first. Your kanji dictionary gives no media " +
                        "frequency, so ties fall back to the character itself — re-import " +
                        "KANJIDIC to order them properly."
                )
            } else {
                tr(
                    "Kolejność: najwięcej moich słów najpierw, remisy po częstości w mediach " +
                        "(KANJIDIC). Każda linia pliku to jeden zestaw w aplikacji.",
                    "Order: most of my words first, ties by media frequency (KANJIDIC). Each " +
                        "line of the file is one set in the app."
                )
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave) {
                Text(tr("Zapisz plik", "Save file"))
            }
            TextButton(onClick = onShare) {
                Text(tr("Udostępnij", "Share"))
            }
        }
    }

    val most = tally.counts.first().words.coerceAtLeast(1)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(tally.counts, key = { it.kanji }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onKanjiClick(row.kanji) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(row.kanji, fontSize = 28.sp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        tr(
                            "w ${row.words} ${wordsPl(row.words)}",
                            "in ${row.words} ${if (row.words == 1) "word" else "words"}"
                        ),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    // A bar against the commonest character, so the shape of
                    // the list is readable without reading every number.
                    LinearProgressIndicator(
                        progress = row.words.toFloat() / most,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (row.occurrences != row.words) {
                    Text(
                        tr("${row.occurrences}×", "${row.occurrences}×"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

/** Locative: "w 1 słowie", "w 12 słowach". */
private fun wordsPl(count: Int): String = if (count == 1) "słowie" else "słowach"

@Composable
private fun Notice(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}
