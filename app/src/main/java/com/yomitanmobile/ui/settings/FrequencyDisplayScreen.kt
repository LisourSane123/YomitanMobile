package com.yomitanmobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                                "Włączone: każda zainstalowana lista pokazuje swój ranking. Wyłączone: tylko lista o najwyższym priorytecie.",
                                "On: every installed list shows its rank. Off: only the top-priority list."
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = showAll, onCheckedChange = { viewModel.setShowAll(it) })
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
            if (isReapplying) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tr("Przeliczanie rankingów…", "Recomputing ranks…"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    }
}
