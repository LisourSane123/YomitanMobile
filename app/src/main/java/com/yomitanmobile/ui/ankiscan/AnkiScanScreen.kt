package com.yomitanmobile.ui.ankiscan

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.util.LocaleHelper
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Scans the AnkiDroid collection and shows what it found.
 *
 * The word list is not decoration: the scan reads notes through a content
 * provider whose accepted search syntax has changed across AnkiDroid versions,
 * and a failed read looks exactly like an empty collection. Seeing the actual
 * words — and the note types they came from — is how the user verifies that
 * duplicate protection is really working before trusting it with a 2000-card
 * deck.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnkiScanScreen(
    onNavigateBack: () -> Unit,
    viewModel: AnkiScanViewModel = hiltViewModel()
) {
    val isEnglish = LocaleHelper.isEnglish(LocalConfiguration.current)
    fun tr(pl: String, en: String) = if (isEnglish) en else pl
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var pendingScan by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val wanted = pendingScan
        pendingScan = false
        if (granted && wanted) {
            viewModel.scan()
        } else if (!granted) {
            Toast.makeText(
                context,
                tr(
                    "Bez uprawnienia do AnkiDroida nie da się przeczytać kolekcji.",
                    "Without AnkiDroid permission the collection can't be read."
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    fun startScan() {
        val granted = ContextCompat.checkSelfPermission(context, ANKI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.scan()
        } else {
            pendingScan = true
            permissionLauncher.launch(ANKI_PERMISSION)
        }
    }

    val isScanning by viewModel.isScanning.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val storedWordCount by viewModel.storedWordCount.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val words by viewModel.words.collectAsState()
    val query by viewModel.query.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Skan kolekcji Anki", "Anki collection scan")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wstecz", "Back"))
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
                Text(
                    tr(
                        "Czyta całą kolekcję AnkiDroida i zapisuje listę japońskich słów, które już masz. " +
                            "Dzięki temu aplikacja nie utworzy drugiej fiszki do słowa z Core, Kaishi ani z " +
                            "wcześniejszego kopania. Skan jest niezależny od typu notatki — bierze każde krótkie, " +
                            "czysto japońskie pole.",
                        "Reads the whole AnkiDroid collection and stores the Japanese words you already have, so " +
                            "the app never creates a second card for a word from Core, Kaishi or earlier mining. " +
                            "The scan is note-type agnostic — it takes every short, purely Japanese field."
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startScan() },
                        enabled = !isScanning,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text("  " + tr("Skanuję…", "Scanning…"))
                        } else {
                            Text(
                                if (storedWordCount > 0) tr("Skanuj ponownie", "Rescan")
                                else tr("Skanuj kolekcję", "Scan collection")
                            )
                        }
                    }
                    if (storedWordCount > 0) {
                        OutlinedButton(onClick = { viewModel.clear() }) {
                            Text(tr("Wyczyść", "Clear"))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Result card: note count is the signal that the provider answered.
            item {
                val result = summary
                if (result != null && !result.available) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            tr(
                                "Nie udało się odczytać kolekcji. Sprawdź, czy AnkiDroid jest zainstalowany, " +
                                    "czy przyznano uprawnienie i czy w Ustawieniach AnkiDroida włączone jest " +
                                    "„Enable AnkiDroid API”. Poprzedni wynik został zachowany.",
                                "The collection could not be read. Check that AnkiDroid is installed, the " +
                                    "permission was granted, and “Enable AnkiDroid API” is on in AnkiDroid's " +
                                    "settings. The previous result was kept."
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                } else if (result != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                tr(
                                    "Przeskanowano ${result.noteCount} notatek",
                                    "Scanned ${result.noteCount} notes"
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                tr(
                                    "Znaleziono ${result.wordCount} japońskich słów",
                                    "Found ${result.wordCount} Japanese words"
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (result.noteCount == 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    tr(
                                        "Zero notatek przy niepustej kolekcji oznacza, że provider AnkiDroida " +
                                            "odrzucił zapytanie — to nie jest poprawny wynik.",
                                        "Zero notes on a non-empty collection means AnkiDroid's provider " +
                                            "rejected the query — that is not a valid result."
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (result.scannedAt > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    tr("Ostatni skan: ", "Last scan: ") +
                                        DateFormat.getDateTimeInstance(
                                            DateFormat.SHORT,
                                            DateFormat.SHORT
                                        ).format(Date(result.scannedAt)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                } else if (storedWordCount > 0) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            tr(
                                "Zapisany skan: $storedWordCount słów",
                                "Stored scan: $storedWordCount words"
                            ),
                            modifier = Modifier.padding(16.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (sources.isNotEmpty()) {
                item {
                    Text(
                        tr("Typy notatek", "Note types"),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sources.forEach { entry ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        (entry.source.ifBlank { tr("nieznany", "unknown") }) +
                                            " · ${entry.wordCount}"
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (storedWordCount > 0) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        label = { Text(tr("Szukaj w wykrytych słowach", "Search detected words")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setText(AnnotatedString(viewModel.exportText()))
                                Toast.makeText(
                                    context,
                                    tr("Skopiowano listę do schowka", "Word list copied to clipboard"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(tr("Kopiuj listę słów", "Copy word list"))
                    }
                    Spacer(Modifier.height(8.dp))
                    Divider()
                }

                items(words) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(row.word, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (row.source.isNotBlank()) {
                            Text(
                                row.source,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Divider()
                }

                item {
                    if (words.size >= 500) {
                        Text(
                            tr(
                                "Pokazano pierwsze 500 — użyj wyszukiwarki, żeby sprawdzić konkretne słowo.",
                                "Showing the first 500 — use the search box to check a specific word."
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            } else if (error == null) {
                item {
                    Text(
                        tr(
                            "Brak zapisanego skanu. Naciśnij „Skanuj kolekcję”.",
                            "No stored scan yet. Press “Scan collection”."
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
