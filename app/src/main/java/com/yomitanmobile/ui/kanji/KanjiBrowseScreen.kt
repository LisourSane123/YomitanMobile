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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

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
                            tr(
                                "„Znane” znaczy: jakaś fiszka w kolekcji zawiera ten znak. " +
                                    "To nie jest pomiar pamięci.",
                                "“Known” means: some card in the collection carries this " +
                                    "character. It is not a measure of recall."
                            ),
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
