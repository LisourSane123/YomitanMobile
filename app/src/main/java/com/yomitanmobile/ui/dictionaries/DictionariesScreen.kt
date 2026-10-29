package com.yomitanmobile.ui.dictionaries

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.ui.common.SettingsRow
import com.yomitanmobile.ui.common.SettingsSectionHeader
import com.yomitanmobile.ui.common.rememberTr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installed dictionaries, drawn as the settings list is: rows and hairlines
 * under a header per study language, not a stack of grey cards.
 *
 * A dictionary being deleted stays on the list with its progress until its
 * data is really gone — the delete takes minutes for a big one, and a row
 * that vanished at once would come back on the next visit if it failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionariesScreen(
    onNavigateBack: () -> Unit,
    viewModel: DictionariesViewModel = hiltViewModel()
) {
    val tr = rememberTr()

    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()
    val removing by viewModel.removing.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.failures.collect { failure ->
            snackbar.showSnackbar(
                tr(
                    "Nie udało się usunąć „${failure.dictionary}”: ${failure.message}",
                    "Could not delete \"${failure.dictionary}\": ${failure.message}"
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Słowniki", "Dictionaries")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { paddingValues ->
        if (dictionaries.isEmpty()) {
            Text(
                text = tr("Brak zainstalowanych słowników", "No dictionaries installed"),
                modifier = Modifier.padding(paddingValues).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Scaffold
        }
        val byLanguage = remember(dictionaries) {
            dictionaries.groupBy { it.language }.toSortedMap(compareBy { LANGUAGE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } })
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            byLanguage.entries.forEachIndexed { index, (language, group) ->
                item(key = "header-$language") {
                    SettingsSectionHeader(
                        icon = Icons.Default.Language,
                        title = "${languageName(language, tr)} · ${group.size}",
                        first = index == 0
                    )
                }
                items(group, key = { it.id }) { dictionary ->
                    DictionaryRow(
                        dictionary = dictionary,
                        progress = removing[dictionary.name],
                        showDivider = dictionary != group.last(),
                        onDelete = { confirmDelete = dictionary.name }
                    )
                }
            }
        }
    }

    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Usunąć słownik?", "Delete dictionary?")) },
            text = {
                Text(
                    tr(
                        "„$name” zniknie z wyszukiwania i kart. Duży słownik usuwa się kilka minut — możesz w tym czasie wyjść z tego ekranu.",
                        "\"$name\" will be gone from search and cards. A big dictionary takes a few minutes to delete — you can leave this screen meanwhile."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(name)
                    confirmDelete = null
                }) {
                    Text(tr("Usuń", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(tr("Anuluj", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun DictionaryRow(
    dictionary: DictionaryInfo,
    progress: Float?,
    showDivider: Boolean,
    onDelete: () -> Unit
) {
    val tr = rememberTr()
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val details = buildList {
        add("${"%,d".format(dictionary.entryCount).replace(',', ' ')} ${tr("wpisów", "entries")}")
        if (dictionary.revision.isNotBlank()) add(dictionary.revision)
        add(tr("dodano", "added") + " " + dateFormat.format(Date(dictionary.importDate)))
    }.joinToString(" · ")

    SettingsRow(
        icon = Icons.Default.MenuBook,
        title = dictionary.name,
        subtitle = if (progress == null) {
            details
        } else {
            tr("Usuwanie… ${(progress * 100).toInt()}%", "Deleting… ${(progress * 100).toInt()}%")
        },
        showDivider = showDivider,
        trailing = {
            if (progress == null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = tr("Usuń ${dictionary.name}", "Delete ${dictionary.name}"),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.5.dp)
            }
        },
        content = if (progress == null) null else {
            { LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth()) }
        }
    )
}

private val LANGUAGE_ORDER = listOf("ja", "en", "es")

private fun languageName(code: String, tr: (String, String) -> String) = when (code) {
    "ja" -> tr("Japoński", "Japanese")
    "en" -> tr("Angielski", "English")
    "es" -> tr("Hiszpański", "Spanish")
    else -> code
}
