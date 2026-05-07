package com.yomitanmobile.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.util.WordCategoryClassifier
import com.yomitanmobile.util.JlptLevelUtil
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onWordClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onFavoritesClick: () -> Unit = {},
    onNavigateToStatistics: () -> Unit,
    focusSearch: Boolean = false,
    initialQuery: String? = null,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val deconjugationCandidates by viewModel.deconjugationCandidates.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val dailyGoal by viewModel.dailyGoal.collectAsState()
    val importedWordsCount by viewModel.importedWordsCount.collectAsState()
    val categoryStats by viewModel.categoryStats.collectAsState()
    val searchMode by viewModel.searchMode.collectAsState()
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl

    // Quick stats icon navigates to Statistics screen
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-focus search bar when launched from quick search widget
    if (focusSearch) {
        LaunchedEffect(Unit) {
            delay(300)
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) { }
        }
    }

    // Refresh daily goal every time screen is shown (e.g. returning from detail after export)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshDailyGoal()
    }

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            viewModel.applyExternalQuery(initialQuery)
        }
    }

    // Quick stats dialog removed; navigation goes to Statistics screen instead

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Yomitan Mobile") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = onFavoritesClick) {
                        Icon(Icons.Default.Favorite, contentDescription = tr("Ulubione", "Favorites"))
                    }
                    IconButton(onClick = onNavigateToStatistics) {
                        Icon(Icons.Default.BarChart, contentDescription = tr("Statystyki", "Statistics"))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = tr("Ustawienia", "Settings"))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SearchBar(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = { },
                active = false,
                onActiveChange = { },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        when (searchMode) {
                            SearchMode.JAPANESE -> tr("Wpisz słowo po japońsku...", "Type a Japanese word...")
                            SearchMode.ENGLISH -> tr("Type an English word...", "Type an English word...")
                            SearchMode.ROMAJI -> tr("taberu, nomu, miru...", "taberu, nomu, miru...")
                        }
                    )
                },
                trailingIcon = {
                    AnimatedVisibility(visible = query.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(Icons.Default.Clear, contentDescription = tr("Wyczyść", "Clear"))
                        }
                    }
                }
            ) { }

            // Daily goal progress bar
            if (dailyGoal.isEnabled) {
                DailyGoalBanner(dailyGoal, isEnglish)
            }

            if (searchMode == SearchMode.JAPANESE && query.isNotBlank() && deconjugationCandidates.isNotEmpty()) {
                DeconjugationHintsCard(
                    candidates = deconjugationCandidates,
                    onCandidateClick = viewModel::onQueryChange
                )
            }

            when {
                query.isBlank() -> {
                    if (searchHistory.isNotEmpty()) {
                        SearchHistorySection(
                            history = searchHistory,
                            onHistoryClick = { query -> viewModel.onQueryChange(query) },
                            onClearHistory = viewModel::clearHistory,
                            isEnglish = isEnglish
                        )
                    } else {
                        EmptySearchState(searchMode, isEnglish)
                    }
                }
                results.isEmpty() && isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                results.isEmpty() -> NoResultsState(query, searchMode, isEnglish)
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = results, key = { it.primaryId }) { entry ->
                            MergedWordEntryCard(entry = entry, onClick = {
                                viewModel.onWordClicked(entry)
                                onWordClick(entry.primaryId)
                            })
                        }
                    }
                }
            }
        }
    }
}

// QuickStatsDialog removed — stats icon now navigates to dedicated Categories screen

@Composable
private fun DeconjugationHintsCard(
    candidates: List<com.yomitanmobile.util.DeconjugationCandidate>,
    onCandidateClick: (String) -> Unit
) {
    val shown = candidates.take(4)
    if (shown.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Rozpoznane formy podstawowe",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.height(6.dp))
            shown.forEachIndexed { idx, candidate ->
                if (idx > 0) {
                    Spacer(Modifier.height(6.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCandidateClick(candidate.baseForm) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = candidate.baseForm,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = candidate.reason,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyGoalBanner(dailyGoal: DailyGoalState, isEnglish: Boolean) {
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    val containerColor = if (dailyGoal.isCompleted) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    }
    val contentColor = if (dailyGoal.isCompleted) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = if (dailyGoal.isCompleted) MaterialTheme.colorScheme.tertiary else contentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (dailyGoal.isCompleted) tr("Cel dzienny osiągnięty!", "Daily goal achieved!") else tr("Cel dzienny", "Daily goal"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = contentColor
                    )
                    Text(
                        text = "${dailyGoal.todayCount}/${dailyGoal.goalCount}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = dailyGoal.progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    trackColor = contentColor.copy(alpha = 0.15f)
                )
            }
        }
    }
}

@Composable
private fun SearchHistorySection(
    history: List<com.yomitanmobile.data.local.entity.SearchHistory>,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    isEnglish: Boolean
) {
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    tr("Historia wyszukiwań", "Search history"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClearHistory) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = tr("Wyczyść historię", "Clear search history"),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(tr("Wyczyść", "Clear"), fontSize = 12.sp)
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(items = history, key = { it.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHistoryClick(item.query) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = item.query,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MergedWordEntryCard(entry: MergedWordEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayText(),
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (entry.reading.isNotBlank() && entry.reading != entry.primaryExpression) {
                    Text(text = entry.reading, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.alternativeExpressions.isNotEmpty()) {
                    Text(
                        text = "Formy: ${entry.alternativeExpressions.joinToString(", ")}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Show numbered definitions
                entry.definitions.take(3).forEachIndexed { index, definition ->
                    Text(
                        text = "${index + 1}. $definition",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.definitions.size > 3) {
                    Text(
                        text = "…i ${entry.definitions.size - 3} więcej",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (entry.exampleSentence.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.exampleSentence,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.exampleSentenceTranslation.isNotBlank()) {
                        Text(
                            text = entry.exampleSentenceTranslation,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (entry.partsOfSpeech.isNotEmpty()) {
                    Text(
                        text = entry.partsOfSpeech.joinToString(", "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            val freqLabel = entry.frequencyLabel()
            if (freqLabel.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = freqLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            val tagsString = entry.partsOfSpeech.joinToString(", ")
            android.util.Log.d("JlptDebug", "SearchScreen - Word: ${entry.primaryExpression}, partsOfSpeech=[${entry.partsOfSpeech}], tagsString='$tagsString'")
            val jlptLevel = JlptLevelUtil.getLevel(
                tagsString = tagsString
            )
            android.util.Log.d("JlptDebug", "SearchScreen - Extracted JLPT level: $jlptLevel")
            if (jlptLevel != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = jlptLevel.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .background(
                            color = androidx.compose.ui.graphics.Color(jlptLevel.color),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptySearchState(searchMode: SearchMode, isEnglish: Boolean) {
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Search, contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                when (searchMode) {
                    SearchMode.JAPANESE -> tr("Wpisz słowo po japońsku", "Type a Japanese word")
                    SearchMode.ENGLISH -> tr("Type an English word", "Type an English word")
                    SearchMode.ROMAJI -> tr("Wpisz słowo w romaji", "Type a word in romaji")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (searchMode) {
                    SearchMode.JAPANESE -> tr("漢字、ひらがな、カタカナ", "Kanji, hiragana, katakana")
                    SearchMode.ENGLISH -> tr("e.g. eat → 食べる, drink → 飲む", "e.g. eat → 食べる, drink → 飲む")
                    SearchMode.ROMAJI -> tr("np. taberu → 食べる, nomu → 飲む", "e.g. taberu → 食べる, nomu → 飲む")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun NoResultsState(query: String, searchMode: SearchMode, isEnglish: Boolean) {
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Brak wyników dla:", "No results for:"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("「$query」", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            Text(
                when (searchMode) {
                    SearchMode.JAPANESE -> tr("Sprawdź pisownię lub zaimportuj słownik", "Check the spelling or import a dictionary")
                    SearchMode.ENGLISH -> tr("Spróbuj innego słowa angielskiego", "Try a different English word")
                    SearchMode.ROMAJI -> tr("Sprawdź pisownię romaji", "Check the romaji spelling")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
