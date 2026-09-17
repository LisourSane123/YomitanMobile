package com.yomitanmobile.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Review the deck before it is written, and mark cards to arrive suspended.
 *
 * The problem this solves is specific: frequency-first ordering means the head
 * of every generated deck is, by definition, what the reader already knows.
 * `assumeKnownTopRank` cuts that off by a number; this is the same decision
 * made word by word, for the words a number cannot separate.
 *
 * Marked cards are still created — the point is that the deck stays complete
 * and searchable in Anki, and that unsuspending later is one action. Dropping
 * them instead would mean re-running the generator to change your mind.
 */
@Composable
fun DeckPreviewSection(
    items: List<DeckPreviewItem>,
    suspended: Set<String>,
    onToggle: (String) -> Unit,
    onSuspendAll: () -> Unit,
    onClearSuspended: () -> Unit,
    /** Null hides the shortcut; otherwise it marks the first N rows. */
    onSuspendFirst: ((Int) -> Unit)? = null
) {
    val tr = rememberTr()
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tr("Przejrzyj fiszki", "Review the cards"),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    tr(
                        "${items.size} do utworzenia, ${suspended.size} oznaczonych jako uśpione",
                        "${items.size} to create, ${suspended.size} marked suspended"
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
        }

        if (!expanded) return@Column

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

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onSuspendFirst != null) {
                TextButton(onClick = { onSuspendFirst(FIRST_BATCH) }) {
                    Text(tr("Pierwsze $FIRST_BATCH", "First $FIRST_BATCH"))
                }
            }
            TextButton(onClick = onSuspendAll) { Text(tr("Wszystkie", "All")) }
            TextButton(onClick = onClearSuspended) { Text(tr("Wyczyść", "Clear")) }
        }

        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
            items(items, key = { it.key }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(item.key) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.key in suspended,
                        // The whole row is the target; the box would otherwise
                        // be a 20dp hit area repeated a thousand times.
                        onCheckedChange = { onToggle(item.key) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.expression, fontWeight = FontWeight.Medium)
                            if (item.reading.isNotBlank() && item.reading != item.expression) {
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text(
                                    item.reading,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.note.isNotBlank()) {
                                Spacer(Modifier.weight(1f))
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Divider()
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** How many rows "mark the head of the deck" covers. */
private const val FIRST_BATCH = 100
