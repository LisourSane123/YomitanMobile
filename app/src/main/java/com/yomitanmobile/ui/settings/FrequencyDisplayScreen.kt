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
                                "Włączone: słowo, którego główna lista nie zna, zostaje bez rankingu — " +
                                    "każda liczba w aplikacji i na fiszce pochodzi z jednej skali, więc dodatki " +
                                    "sortujące w Anki (np. AutoReorder) układają nowe karty poprawnie. " +
                                    "Wyłączone: takie słowo dostaje ranking z innej listy.",
                                "On: a word the leading list does not know is left unranked — every number in the app " +
                                    "and on a card comes from one scale, so an Anki sorting addon (AutoReorder and " +
                                    "friends) orders new cards the way that list meant. Off: such a word borrows " +
                                    "another list's rank."
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

            Spacer(Modifier.height(16.dp))

            Text(
                tr("Kolejność priorytetów", "Priority order"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr(
                    "Pierwsza lista jest główna: to jej ranking trafia na fiszkę, ustawia kolejność " +
                        "wyników wyszukiwania i decyduje, co generatory uznają za zbyt rzadkie. " +
                        "Pozostałe listy są używane dla słów, których główna nie zna.",
                    "The first list leads: its rank is what goes on a card, orders search results and " +
                        "decides what the deck generators call too rare. The rest fill in the words the " +
                        "leading list does not know."
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
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text("${index + 1}. $name", fontSize = 15.sp)
                                // What the list actually counted. The installed
                                // name ("JPDBv2", "CEJC-LUW") says who made it
                                // and nothing about what is in it.
                                FrequencyCorpus.labelFor(name)?.let { label ->
                                    Text(
                                        if (isEnglish) label.en else label.pl,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (index == 0) {
                                    Text(
                                        tr("główna — jej ranking trafia na fiszkę", "leading — its rank goes on the card"),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                // What the numbers in this list MEAN. A list
                                // of occurrence counts runs the other way, and
                                // read as ranks it calls the commonest words
                                // the rarest — so it is converted on import
                                // and the conversion is said out loud here,
                                // with a way to correct it.
                                val isCounted = name in countBased
                                Text(
                                    if (isCounted) {
                                        tr(
                                            "liczby wystąpień (większa = częstsze) — przeliczone na ranking",
                                            "occurrence counts (higher = commoner) — converted to ranks"
                                        )
                                    } else {
                                        tr(
                                            "ranking (mniejszy numer = częstsze)",
                                            "ranks (lower number = commoner)"
                                        )
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = { viewModel.setCountBased(name, !isCounted) },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        if (isCounted) {
                                            tr("To jednak ranking", "These are ranks after all")
                                        } else {
                                            tr("To liczby wystąpień", "These are occurrence counts")
                                        },
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (index > 0) {
                                TextButton(onClick = { viewModel.makeLeading(name) }) {
                                    Text(tr("Ustaw główną", "Make leading"), fontSize = 12.sp)
                                }
                            }
                            IconButton(
                                onClick = { viewModel.moveUp(name) },
                                enabled = index > 0
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = tr("W górę", "Move up")
                                )
                            }
                            IconButton(
                                onClick = { viewModel.moveDown(name) },
                                enabled = index < order.lastIndex
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = tr("W dół", "Move down")
                                )
                            }
                        }
                    }
                }
            }
        }
        if (isReapplying) RecomputingOverlay()
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
