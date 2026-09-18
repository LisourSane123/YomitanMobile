package com.yomitanmobile.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yomitanmobile.domain.model.MergedWordEntry

/** One row of the pre-generation review. */
data class DeckPreviewItem(
    val key: String,
    val expression: String,
    val reading: String,
    val gloss: String,
    /** Rank, occurrence count — whatever the generator wants to show. */
    val note: String = ""
)

/**
 * The key a word is remembered under while the user marks it.
 *
 * Expression plus reading, the same pair `mergeEntries` groups on, so the two
 * homographs of a spelling stay two rows and a re-analysis that produces the
 * same word finds the user's mark still on it.
 */
fun previewKeyOf(entry: MergedWordEntry): String =
    "${entry.primaryExpression}\t${entry.reading}"

/** Which rows the review is showing. */
private enum class ReviewFilter { ALL, STUDY, SUSPENDED }

/** How many rows "mark the head of the deck" covers. */
private const val FIRST_BATCH = 100

/**
 * The entry point to the review: what the deck will look like, and a way in.
 *
 * Deliberately NOT a list. This used to open a 400dp-tall `LazyColumn` inline,
 * nested inside the screen's own `verticalScroll` — two scrollers fighting over
 * the same drag, and a porthole onto three and a half thousand rows. Marking
 * words one by one through it was the thing that could not be done. The list
 * lives in [DeckReviewDialog] now and owns a whole screen.
 */
@Composable
fun DeckPreviewSection(
    items: List<DeckPreviewItem>,
    suspended: Set<String>,
    onToggle: (String) -> Unit,
    onSuspendAll: () -> Unit,
    onClearSuspended: () -> Unit,
    /** Null hides the shortcut; otherwise it marks the first N rows. */
    onSuspendFirst: ((Int) -> Unit)? = null,
    /**
     * Marks or unmarks a batch at once. This is what the review's bulk buttons
     * act through, so they can operate on what the search has narrowed to
     * rather than on the whole deck.
     */
    onSetSuspended: (Collection<String>, Boolean) -> Unit
) {
    val tr = rememberTr()
    var reviewing by remember { mutableStateOf(false) }
    // One row per key, always.
    //
    // The mark is keyed on expression + reading, so two rows sharing a key
    // cannot be marked independently — one tap would tick both. They are the
    // same decision, and showing it twice is at best confusing. It is also
    // what `LazyColumn(key = …)` refuses outright: a duplicate key throws, and
    // this list is now the screen, not a strip inside it. The JLPT generator
    // cannot produce one (its candidates come out of `mergeEntries`, which
    // groups on exactly this pair), the scanner resolves each token on its own
    // and has no such guarantee.
    val rows = remember(items) { items.distinctBy { it.key } }
    val markedHere = rows.count { it.key in suspended }
    val toStudy = rows.size - markedHere

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tr("Przejrzyj fiszki", "Review the cards"),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    tr(
                        "$toStudy do nauki · $markedHere uśpionych",
                        "$toStudy to study · $markedHere suspended"
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = { reviewing = true }, enabled = rows.isNotEmpty()) {
                Text(tr("Zaznacz ręcznie", "Mark by hand"))
            }
        }
        Text(
            tr(
                "Zaznacz słowa, które już znasz. Powstaną jako fiszki, ale uśpione — " +
                    "talia jest kompletna, a nauka nie zaczyna się od tego, co umiesz.",
                "Tick the words you already know. They are still created, but suspended — " +
                    "the deck stays complete and studying does not start with what you know."
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (reviewing) {
        DeckReviewDialog(
            items = rows,
            suspended = suspended,
            onToggle = onToggle,
            onSuspendAll = onSuspendAll,
            onClearSuspended = onClearSuspended,
            onSuspendFirst = onSuspendFirst,
            onSetSuspended = onSetSuspended,
            onDismiss = { reviewing = false }
        )
    }
}

/**
 * The whole deck, on a whole screen, one row per card.
 *
 * Three thousand words cannot be gone through in a list you have to find
 * first. What makes it workable is not the size of the list but the two things
 * beside it: a search box, and bulk buttons that act on **what the search has
 * narrowed to**. "Suspend everything matching 食" is one decision instead of
 * forty taps; the same goes for a level, a reading, a gloss word.
 *
 * The filter chips exist for the other half of the job — checking what you
 * marked. Marks made across a long session are invisible in a list of three
 * thousand until you can ask to see only them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DeckReviewDialog(
    items: List<DeckPreviewItem>,
    suspended: Set<String>,
    onToggle: (String) -> Unit,
    onSuspendAll: () -> Unit,
    onClearSuspended: () -> Unit,
    onSuspendFirst: ((Int) -> Unit)?,
    onSetSuspended: (Collection<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val tr = rememberTr()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReviewFilter.ALL) }

    // The text pass is the expensive one and does not depend on the marks, so
    // it is remembered across every tick; the mark pass runs over what is left.
    val matchingQuery = remember(items, query) {
        val q = query.trim()
        if (q.isBlank()) {
            items
        } else {
            items.filter {
                it.expression.contains(q, ignoreCase = true) ||
                    it.reading.contains(q, ignoreCase = true) ||
                    it.gloss.contains(q, ignoreCase = true)
            }
        }
    }
    val visible = when (filter) {
        ReviewFilter.ALL -> matchingQuery
        ReviewFilter.STUDY -> matchingQuery.filter { it.key !in suspended }
        ReviewFilter.SUSPENDED -> matchingQuery.filter { it.key in suspended }
    }
    val markedHere = items.count { it.key in suspended }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(tr("Przejrzyj fiszki", "Review the cards"))
                                Text(
                                    tr(
                                        "${items.size - markedHere} do nauki · $markedHere uśpionych",
                                        "${items.size - markedHere} to study · $markedHere suspended"
                                    ),
                                    fontSize = 12.sp
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = tr("Zamknij", "Close")
                                )
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Text(tr("Gotowe", "Done"))
                        }
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(tr("Szukaj w talii", "Search the deck")) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = tr("Wyczyść", "Clear")
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filter == ReviewFilter.ALL,
                            onClick = { filter = ReviewFilter.ALL },
                            label = { Text(tr("Wszystkie", "All")) }
                        )
                        FilterChip(
                            selected = filter == ReviewFilter.STUDY,
                            onClick = { filter = ReviewFilter.STUDY },
                            label = { Text(tr("Do nauki", "To study")) }
                        )
                        FilterChip(
                            selected = filter == ReviewFilter.SUSPENDED,
                            onClick = { filter = ReviewFilter.SUSPENDED },
                            label = { Text(tr("Uśpione", "Suspended")) }
                        )
                    }

                    // Bulk actions act on [visible] — the point of the search
                    // box. "Everything matching 見" is one decision, not forty
                    // taps, and it is the only way a deck of thousands gets
                    // reviewed in one sitting.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = { onSetSuspended(visible.map { it.key }, true) },
                            enabled = visible.isNotEmpty()
                        ) {
                            Text(tr("Uśpij widoczne (${visible.size})", "Suspend shown (${visible.size})"))
                        }
                        TextButton(
                            onClick = { onSetSuspended(visible.map { it.key }, false) },
                            enabled = visible.isNotEmpty()
                        ) {
                            Text(tr("Odznacz widoczne", "Unmark shown"))
                        }
                        if (onSuspendFirst != null) {
                            TextButton(onClick = { onSuspendFirst(FIRST_BATCH) }) {
                                Text(tr("Pierwsze $FIRST_BATCH", "First $FIRST_BATCH"))
                            }
                        }
                        TextButton(onClick = onSuspendAll) { Text(tr("Wszystkie", "All")) }
                        TextButton(onClick = onClearSuspended) { Text(tr("Wyczyść", "Clear")) }
                    }

                    // Said here because this is where the decision is made,
                    // and the user need never scroll back to the buttons that
                    // say it. AnkiDroid's API cannot suspend anything: through
                    // the provider a marked card is created with a tag and the
                    // user suspends the tag in Anki. Only the .apkg path
                    // writes `cards.queue = -1` outright.
                    Text(
                        tr(
                            "Przez AnkiDroida fiszka dostanie tag „yomitan-suspend” — uśpisz " +
                                "je w Anki jednym zaznaczeniem. Plik .apkg usypia je od razu.",
                            "Through AnkiDroid a marked card gets the tag “yomitan-suspend” — " +
                                "you suspend them in Anki in one go. An .apkg file suspends " +
                                "them outright."
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    Divider()

                    if (visible.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tr("Nic nie pasuje", "Nothing matches"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visible, key = { it.key }) { item ->
                                DeckReviewRow(
                                    item = item,
                                    checked = item.key in suspended,
                                    onToggle = { onToggle(item.key) }
                                )
                                Divider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckReviewRow(
    item: DeckPreviewItem,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target; the box alone would be a 20dp hit
            // area repeated a thousand times.
            .clickable(onClick = onToggle)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.expression,
                    fontWeight = FontWeight.Medium,
                    // A suspended row is not disabled — it is still tappable —
                    // but it should read as the half of the deck being set aside.
                    color = if (checked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (item.reading.isNotBlank() && item.reading != item.expression) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.reading,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.note.isNotBlank()) {
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.note,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.gloss.isNotBlank()) {
                Text(
                    item.gloss,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
