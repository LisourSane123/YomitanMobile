package com.yomitanmobile.ui.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.data.download.DictionaryCategory
import com.yomitanmobile.data.download.DictionaryDownloadInfo
import com.yomitanmobile.data.download.DownloadPhase
import com.yomitanmobile.data.download.localizedDescription

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DictionaryDownloadScreen(
    onNavigateBack: () -> Unit,
    viewModel: DictionaryDownloadViewModel = hiltViewModel()
) {
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val installedDictionaries by viewModel.installedDictionaries.collectAsState()
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DownloadEvent.Success -> {
                    snackbarHostState.showSnackbar(
                        tr("✓ ${event.name}: ${event.entries} wpisów zaimportowano", "✓ ${event.name}: ${event.entries} entries imported")
                    )
                }
                is DownloadEvent.Error -> {
                    snackbarHostState.showSnackbar(
                        tr("✗ ${event.name}: ${event.message}", "✗ ${event.name}: ${event.message}")
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Pobierz słowniki", "Download dictionaries")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wstecz", "Back"))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Download progress banner
            AnimatedVisibility(
                visible = downloadProgress != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                downloadProgress?.let { progress ->
                    DownloadProgressBanner(progress, isEnglish)
                }
            }

            // Category filter chips
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { viewModel.selectCategory(null) },
                    label = { Text(tr("Wszystkie", "All")) }
                )
                DictionaryCategory.entries.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { viewModel.selectCategory(category) },
                        label = { Text(categoryLabel(category, isEnglish)) },
                        leadingIcon = {
                            Icon(
                                categoryIcon(category),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            // Info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            tr("Pobieranie słowników", "Dictionary downloads"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            tr(
                                "Słowniki zostaną pobrane z internetu i zaimportowane offline. Po pobraniu nie potrzebujesz internetu do wyszukiwania.",
                                "The dictionaries will be downloaded from the internet and imported offline. After that, you do not need the internet to search."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Download all recommended button
            Button(
                onClick = { viewModel.downloadAllRecommended() },
                enabled = !isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Pobierz wszystkie rekomendowane", "Download all recommended"))
            }

            Spacer(Modifier.height(8.dp))

            // Dictionary list
            val filteredDicts = if (selectedCategory != null) {
                viewModel.availableDictionaries.filter { it.category == selectedCategory }
            } else {
                viewModel.availableDictionaries
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            ) {
                items(filteredDicts, key = { it.id }) { dictInfo ->
                    val isInstalled = isDictionaryInstalled(dictInfo, installedDictionaries)
                    val isMetaDict = dictInfo.category == DictionaryCategory.FREQUENCY ||
                            dictInfo.category == DictionaryCategory.PITCH_ACCENT
                    val isCurrentlyDownloading = downloadProgress?.dictionaryId == dictInfo.id

                    DictionaryDownloadCard(
                        info = dictInfo,
                        isInstalled = isInstalled,
                        isDownloading = isCurrentlyDownloading,
                        onDownload = { viewModel.downloadDictionary(dictInfo) },
                        enabled = !isDownloading,
                        allowReimport = isMetaDict,
                        isEnglish = isEnglish
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                tr("💡 Wskazówki", "💡 Tips"),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tr(
                                    "• JMdict (English) – podstawowy słownik, zainstaluj go jako pierwszy\n" +
                                        "• Frequency – pozwala sortować słowa wg częstości użycia\n" +
                                        "• Pitch Accent – pokazuje akcent tonalny słów\n" +
                                        "• Wymowa TTS – automatycznie dostępna przez Google TTS (nie wymaga pobierania)\n" +
                                        "• Możesz też importować własne słowniki (ZIP) w Ustawieniach",
                                    "• JMdict (English) - the main dictionary, install it first\n" +
                                        "• Frequency - lets you sort words by usage frequency\n" +
                                        "• Pitch Accent - shows word pitch accent patterns\n" +
                                        "• TTS pronunciation - automatically available via Google TTS (no download needed)\n" +
                                        "• You can also import your own dictionaries (ZIP) in Settings"
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressBanner(progress: com.yomitanmobile.data.download.DownloadProgress, isEnglish: Boolean) {
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (progress.phase) {
                DownloadPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
                DownloadPhase.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (progress.phase) {
                    DownloadPhase.DOWNLOADING -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            tr("Pobieranie: ${progress.dictionaryName}", "Downloading: ${progress.dictionaryName}"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    DownloadPhase.IMPORTING -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                tr("Importowanie: ${progress.dictionaryName}", "Importing: ${progress.dictionaryName}"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            if (progress.totalBytes > 0) {
                                Text(
                                    tr("${progress.bytesDownloaded}/${progress.totalBytes} wpisów", "${progress.bytesDownloaded}/${progress.totalBytes} entries"),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    DownloadPhase.COMPLETED -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            tr("Gotowe: ${progress.dictionaryName}", "Done: ${progress.dictionaryName}"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    DownloadPhase.ERROR -> {
                        Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            tr("Błąd: ${progress.dictionaryName}", "Error: ${progress.dictionaryName}"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            when {
                progress.phase == DownloadPhase.DOWNLOADING && progress.totalBytes > 0 -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress.progressPercent,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                progress.phase == DownloadPhase.DOWNLOADING -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                progress.phase == DownloadPhase.IMPORTING && progress.totalBytes > 0 -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress.progressPercent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
/**
 * Checks if a dictionary is installed by comparing normalized names.
 * Handles cases where index.json title differs from the expected display name
 * (e.g. JPDB imports as "JPDBv2" but is displayed as "JPDB Frequency v2.2").
 */
private fun isDictionaryInstalled(
    dictInfo: DictionaryDownloadInfo,
    installedDictionaries: List<com.yomitanmobile.data.local.entity.DictionaryInfo>
): Boolean {
    return installedDictionaries.any { installed ->
        val iName = installed.name.lowercase().replace(" ", "").replace("-", "").replace("_", "")
        val dName = dictInfo.name.lowercase().replace(" ", "").replace("-", "").replace("_", "")
        val dId = dictInfo.id.lowercase().replace("_", "")

        iName == dName ||
        iName.contains(dName) || dName.contains(iName) ||
        iName.contains(dId) || dId.contains(iName)
    }
}

@Composable
private fun DictionaryDownloadCard(
    info: DictionaryDownloadInfo,
    isInstalled: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    enabled: Boolean,
    allowReimport: Boolean = false,
    isEnglish: Boolean
) {
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstalled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = categoryIcon(info.category),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .padding(top = 4.dp),
                tint = if (isInstalled) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        info.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isInstalled) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = tr("Zainstalowany", "Installed"),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    info.localizedDescription(isEnglish),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${categoryLabel(info.category, isEnglish)} • ${info.fileSize}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(
                            onClick = onDownload,
                            enabled = enabled && (!isInstalled || allowReimport)
                        ) {
                            Icon(
                                if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    isInstalled && allowReimport -> tr("Zaktualizuj", "Update")
                                    isInstalled -> tr("Zainstalowany", "Installed")
                                    else -> tr("Pobierz", "Download")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun categoryLabel(category: DictionaryCategory, isEnglish: Boolean): String = when (category) {
    DictionaryCategory.DICTIONARY -> if (isEnglish) "Dictionary" else "Słownik"
    DictionaryCategory.FREQUENCY -> if (isEnglish) "Frequency" else "Częstotliwość"
    DictionaryCategory.PITCH_ACCENT -> if (isEnglish) "Pitch Accent" else "Akcent tonalny"
    DictionaryCategory.KANJI -> if (isEnglish) "Kanji" else "Kanji"
}

private fun categoryIcon(category: DictionaryCategory): ImageVector = when (category) {
    DictionaryCategory.DICTIONARY -> Icons.Default.Translate
    DictionaryCategory.FREQUENCY -> Icons.Default.Speed
    DictionaryCategory.PITCH_ACCENT -> Icons.Default.MusicNote
    DictionaryCategory.KANJI -> Icons.Default.LibraryBooks
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}
