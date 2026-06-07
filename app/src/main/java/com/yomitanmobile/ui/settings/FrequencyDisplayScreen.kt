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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.util.LocaleHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrequencyDisplayScreen(
    onNavigateBack: () -> Unit,
    viewModel: FrequencySettingsViewModel = hiltViewModel()
) {
    val isEnglish = LocaleHelper.isEnglish(LocalConfiguration.current)
    fun tr(pl: String, en: String) = if (isEnglish) en else pl

    val order by viewModel.order.collectAsState()
    val showAll by viewModel.showAll.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Wyświetlanie częstotliwości", "Frequency display")) },
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
                    "Listy wyżej mają pierwszeństwo. Górna lista jest pokazywana, gdy „Pokaż wszystkie” jest wyłączone.",
                    "Lists higher up take priority. The top list is the one shown when “Show all” is off."
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
                            Text(
                                "${index + 1}. $name",
                                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                                fontSize = 15.sp
                            )
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
