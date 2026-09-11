package com.yomitanmobile.ui.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.data.download.DownloadPhase
import com.yomitanmobile.data.download.QueueState
import com.yomitanmobile.ui.common.tr

/**
 * A one-line "still installing" strip above the bottom bar.
 *
 * The queue keeps running wherever the user goes, which is the whole point —
 * but a background job nobody can see is a job nobody trusts, so it says what
 * it is doing and how much is left from every tab.
 */
@Composable
fun DownloadQueueStrip(
    modifier: Modifier = Modifier,
    viewModel: DictionaryDownloadViewModel = hiltViewModel()
) {
    val queue by viewModel.queue.collectAsState()
    val progress by viewModel.downloadProgress.collectAsState()

    val running = queue.firstOrNull { it.state == QueueState.RUNNING } ?: return
    val waiting = queue.count { it.state == QueueState.WAITING }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val phase = when (progress?.phase) {
                    DownloadPhase.IMPORTING -> tr("Importowanie", "Importing")
                    else -> tr("Pobieranie", "Downloading")
                }
                Text(
                    "$phase: ${running.info.name}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                if (waiting > 0) {
                    Text(
                        tr("w kolejce: $waiting", "queued: $waiting"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            val fraction = progress?.progressPercent ?: 0f
            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}
