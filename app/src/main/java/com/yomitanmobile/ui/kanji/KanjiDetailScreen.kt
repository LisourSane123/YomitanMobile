package com.yomitanmobile.ui.kanji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.yomitanmobile.data.mapper.toKanjiInfo
import com.yomitanmobile.ui.common.tr

/**
 * One character: what it reads as, what it means, and — the part that makes it
 * worth a screen — the words it is actually written in, commonest first.
 *
 * That list is what turns a kanji from a symbol into vocabulary, and it is one
 * query away from what search already does: the substring pass added for 欲 →
 * 食欲 is the same lookup with a single character in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanjiDetailScreen(
    onNavigateBack: () -> Unit,
    onWordClick: (Long) -> Unit,
    viewModel: KanjiDetailViewModel = hiltViewModel()
) {
    val entry by viewModel.entry.collectAsState()
    val words by viewModel.words.collectAsState()
    val known by viewModel.known.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val info = entry?.toKanjiInfo()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.kanji) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back"))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(viewModel.kanji, fontSize = 64.sp)
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    Column {
                        if (info != null && info.onyomi.isNotBlank()) {
                            Text(tr("On: ${info.onyomi}", "On: ${info.onyomi}"), fontSize = 14.sp)
                        }
                        if (info != null && info.kunyomi.isNotBlank()) {
                            Text(tr("Kun: ${info.kunyomi}", "Kun: ${info.kunyomi}"), fontSize = 14.sp)
                        }
                        if (known) {
                            Text(
                                tr("Masz fiszkę z tym znakiem", "A card in your collection uses it"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            entry?.let { row ->
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (row.strokes > 0) {
                            AssistChip(
                                onClick = {},
                                label = { Text(tr("${row.strokes} kresek", "${row.strokes} strokes")) }
                            )
                        }
                        if (row.grade > 0) {
                            AssistChip(
                                onClick = {},
                                label = { Text(KanjiViewModel.gradeLabel(row.grade)) }
                            )
                        }
                        if (row.jlpt > 0) {
                            AssistChip(onClick = {}, label = { Text("N${row.jlpt}") })
                        }
                    }
                }
            }

            if (info != null && info.meanings.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tr("Znaczenie", "Meaning"),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(info.meanings.joinToString(", "), fontSize = 14.sp)
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    tr("Słowa z tym znakiem", "Words written with it"),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!loading && words.isEmpty()) {
                    Text(
                        tr(
                            "Żaden zainstalowany słownik nie ma słowa z tym znakiem.",
                            "No installed dictionary has a word written with it."
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            items(words, key = { it.id }) { word ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { onWordClick(word.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(word.expression, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            if (word.reading.isNotBlank() && word.reading != word.expression) {
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text(
                                    word.reading,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val gloss = word.definitions.firstOrNull().orEmpty()
                        if (gloss.isNotBlank()) {
                            Text(
                                gloss,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
