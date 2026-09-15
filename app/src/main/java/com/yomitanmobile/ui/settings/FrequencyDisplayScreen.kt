package com.yomitanmobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.ui.common.rememberTr
import com.yomitanmobile.ui.common.LocalIsEnglish
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import com.yomitanmobile.domain.model.FrequencyCorpus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrequencyDisplayScreen(
    onNavigateBack: () -> Unit,
    viewModel: FrequencySettingsViewModel = hiltViewModel()
) {
    val isEnglish = LocalIsEnglish.current
    val tr = rememberTr()

    val order by viewModel.order.collectAsState()
    val showAll by viewModel.showAll.collectAsState()
    val strictLeading by viewModel.strictLeading.collectAsState()
    val countBased by viewModel.countBased.collectAsState()
    val isReapplying by viewModel.isReapplying.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Wyświetlanie częstotliwości", "Frequency display")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back"))
                    }
                }
            )
        }
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                tr("Główna lista", "Leading list"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr(
                    "Dotknij listy, żeby ustawić ją jako główną. Tylko z niej pochodzą oznaczenia „Top 3K” itd. " +
                        "i liczba na fiszce, i to ona ustawia kolejność wyników. Pozostałe listy są widoczne " +
                        "na ekranie słowa, a strzałki ustalają ich kolejność.",
                    "Tap a list to make it leading. \"Top 3K\" badges and the number on the card come from it " +
                        "alone, and it orders search results. The other lists show on the word screen; the " +
                        "arrows set their order."
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (order.isEmpty()) {
                Text(
                    tr(
                        "Brak zainstalowanych list częstotliwości. Pobierz je z ekranu słowników.",
                        "No frequency lists installed. Download some from the dictionaries screen."
                    ),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                order.forEachIndexed { index, name ->
                    FrequencyListCard(
                        position = index + 1,
                        name = name,
                        isLeading = index == 0,
                        isCounted = name in countBased,
                        canMoveUp = index > 1,
                        canMoveDown = index in 1 until order.lastIndex,
                        onMakeLeading = { viewModel.makeLeading(name) },
                        onMoveUp = { viewModel.moveUp(name) },
                        onMoveDown = { viewModel.moveDown(name) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            tr("Pokaż wszystkie listy", "Show all lists"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            tr(
                                "Dotyczy ekranu słowa. Na liście wyników zawsze widać tylko listę główną; " +
                                    "po wejściu w słowo — wszystkie zainstalowane listy. Wyłączone: także tam tylko główna.",
                                "Applies to the word screen. The result list always shows the leading list alone; " +
                                    "opening a word shows every installed list. Off: that screen shows only the leading one too."
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = showAll, onCheckedChange = { viewModel.setShowAll(it) })
                }
            }

            Spacer(Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            tr("Trzymaj się tylko głównej listy", "Stick to the leading list only"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            tr(
                                "Dotyczy kolejności wyników i filtrów „za rzadkie” w generatorach talii. " +
                                    "Włączone: słowo, którego główna lista nie zna, traktowane jest jak nieznane. " +
                                    "Wyłączone: jego pozycję bierzemy z innej listy. Oznaczenia Top 3K i liczba " +
                                    "na fiszce zawsze pochodzą tylko z głównej listy.",
                                "Affects search order and the deck generators' \"too rare\" filters. On: a word the " +
                                    "leading list does not know counts as unranked. Off: its standing is taken from " +
                                    "another list. Top 3K badges and the card number always come from the leading list alone."
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = strictLeading,
                        onCheckedChange = { viewModel.setStrictLeading(it) }
                    )
                }
            }

        }
        if (isReapplying) RecomputingOverlay()
      }
    }
}

/**
 * One installed list: whether it leads, and — stated plainly, because the
 * format has no field for it and the two kinds run opposite ways — whether its
 * numbers are ranks or occurrence counts. The numbers are shown as the list
 * shipped them; nothing here converts one kind into the other.
 */
@Composable
private fun FrequencyListCard(
    position: Int,
    name: String,
    isLeading: Boolean,
    isCounted: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMakeLeading: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val tr = rememberTr()
    val isEnglish = LocalIsEnglish.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = !isLeading, onClick = onMakeLeading),
        colors = if (isLeading) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isLeading, onClick = if (isLeading) null else onMakeLeading)
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    if (isLeading) name else "$position. $name",
                    fontSize = 15.sp,
                    fontWeight = if (isLeading) FontWeight.SemiBold else FontWeight.Normal
                )
                if (isLeading) {
                    Text(
                        tr("GŁÓWNA — Top 3K itd. i liczba na fiszce", "LEADING — Top 3K etc. and the card number"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // What the list actually counted. The installed name
                // ("JPDBv2", "CEJC-LUW") says who made it, not what is in it.
                FrequencyCorpus.labelFor(name)?.let { label ->
                    Text(
                        if (isEnglish) label.en else label.pl,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                FormatBadge(isCounted = isCounted, isLeading = isLeading)
            }
            if (!isLeading) {
                Column {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = tr("W górę", "Move up"))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = tr("W dół", "Move down"))
                    }
                }
            }
        }
    }
}

/**
 * The list's format in words and with an example, so nobody has to know what
 * "higher is better" means to read it right.
 */
@Composable
private fun FormatBadge(isCounted: Boolean, isLeading: Boolean) {
    val tr = rememberTr()
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isCounted) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                if (isCounted) {
                    tr("Format: LICZBA WYSTĄPIEŃ  ↓ malejąco", "Format: OCCURRENCE COUNTS  ↓ descending")
                } else {
                    tr("Format: RANKING  ↑ rosnąco", "Format: RANKS  ↑ ascending")
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (isCounted) {
                    tr(
                        "Większa liczba = częstsze słowo (np. 120000× częściej niż 50×).",
                        "Higher number = commoner word (e.g. 120000× is commoner than 50×)."
                    )
                } else {
                    tr(
                        "Mniejsza liczba = częstsze słowo (#1 to najczęstsze).",
                        "Lower number = commoner word (#1 is the commonest)."
                    )
                },
                fontSize = 12.sp
            )
            if (isLeading) {
                Text(
                    if (isCounted) {
                        tr(
                            "Na fiszkę trafia ta liczba bez zmian — w Anki sortuj pole Frequency malejąco.",
                            "The card gets this number unchanged — sort the Frequency field descending in Anki."
                        )
                    } else {
                        tr(
                            "Na fiszkę trafia ta liczba bez zmian — w Anki sortuj pole Frequency rosnąco.",
                            "The card gets this number unchanged — sort the Frequency field ascending in Anki."
                        )
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Covers the screen while ranks are recomputed. Each change here is a pass
 * over the whole dictionary that takes seconds, and the only sign of it used
 * to be one grey line at the top — invisible from a list scrolled down to the
 * button that was tapped, so the tap looked like it did nothing and got
 * tapped again. The overlay also swallows touches: a second flip queued behind
 * the first is exactly the confusion it is there to prevent. Leaving the
 * screen is still allowed — the pass runs on the application scope.
 */
@Composable
private fun RecomputingOverlay() {
    val tr = rememberTr()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.padding(32.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    tr("Przeliczanie rankingów…", "Recomputing ranks…"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(
                        "Może to potrwać kilka–kilkanaście sekund. Możesz wyjść z ekranu lub zminimalizować aplikację — przeliczanie dokończy się w tle.",
                        "This can take several seconds. You can leave the screen or minimise the app — it will finish in the background."
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
