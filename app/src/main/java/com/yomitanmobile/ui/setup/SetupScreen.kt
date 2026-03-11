package com.yomitanmobile.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.data.download.DownloadPhase

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val setupState by viewModel.setupState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = setupState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "setup_state"
        ) { state ->
            when (state) {
                SetupState.WELCOME -> WelcomeContent(
                    onDownloadRecommended = { viewModel.startRecommendedDownload() },
                    onDownloadJmdict = { viewModel.startJmDictDownload() },
                    onSkip = {
                        viewModel.skip()
                        onSetupComplete()
                    }
                )
                SetupState.DOWNLOADING -> DownloadingContent(
                    progress = downloadProgress
                )
                SetupState.COMPLETED -> CompletedContent(
                    onContinue = onSetupComplete
                )
                SetupState.ERROR -> ErrorContent(
                    message = errorMessage ?: "Nieznany błąd",
                    onRetry = { viewModel.retry() },
                    onSkip = {
                        viewModel.skip()
                        onSetupComplete()
                    }
                )
                SetupState.SKIPPED -> {
                    onSetupComplete()
                }
            }
        }
    }
}

@Composable
private fun WelcomeContent(
    onDownloadRecommended: () -> Unit,
    onDownloadJmdict: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Yomitan Mobile",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Słownik japońsko-angielski",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Text(
            "Pobierz rekomendowane słowniki, aby w pełni korzystać z aplikacji:\n" +
                "• JMdict — główny słownik (~200K wpisów)\n" +
                "• JPDB Frequency — ranking częstotliwości\n" +
                "• Kanjium — akcent tonalny (pitch accent)",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(36.dp))

        Button(
            onClick = onDownloadRecommended,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.size(12.dp))
            Text("Pobierz rekomendowane (~19 MB)", fontSize = 16.sp)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onDownloadJmdict,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Pobierz tylko JMdict (~15 MB)", fontSize = 14.sp)
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onSkip) {
            Text("Pomiń — zaimportuję ręcznie")
        }
    }
}

@Composable
private fun DownloadingContent(
    progress: com.yomitanmobile.data.download.DownloadProgress?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            strokeWidth = 4.dp
        )

        Spacer(Modifier.height(32.dp))

        val phaseText = when (progress?.phase) {
            DownloadPhase.DOWNLOADING -> "Pobieranie ${progress.dictionaryName}…"
            DownloadPhase.IMPORTING -> "Importowanie ${progress.dictionaryName}…"
            DownloadPhase.COMPLETED -> "Gotowe!"
            DownloadPhase.ERROR -> "Błąd!"
            null -> "Przygotowywanie…"
        }

        Text(
            phaseText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(16.dp))

        if (progress?.phase == DownloadPhase.DOWNLOADING && progress.totalBytes > 0) {
            LinearProgressIndicator(
                progress = progress.progressPercent,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "%.1f MB / %.1f MB".format(
                    progress.bytesDownloaded / 1_048_576.0,
                    progress.totalBytes / 1_048_576.0
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (progress?.phase == DownloadPhase.IMPORTING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "To może zająć kilka minut…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CompletedContent(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Słownik zainstalowany!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "JMdict (English) został pomyślnie pobrany i zaimportowany.\n" +
                "Możesz teraz wyszukiwać słowa offline.\n\n" +
                "Wymowa TTS jest dostępna automatycznie przez Google TTS.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Rozpocznij wyszukiwanie", fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Błąd pobierania",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(16.dp))

        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "Sprawdź połączenie z internetem i spróbuj ponownie.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Spróbuj ponownie")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Pomiń")
        }
    }
}
