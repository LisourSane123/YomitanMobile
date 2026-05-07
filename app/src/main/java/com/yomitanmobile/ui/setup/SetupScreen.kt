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
import androidx.compose.ui.platform.LocalConfiguration
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
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)

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
                    onContinue = onSetupComplete,
                    onDebug = { viewModel.debugLogSampleEntries() }
                )
                SetupState.ERROR -> ErrorContent(
                    message = errorMessage ?: if (isEnglish) "Unknown error" else "Nieznany błąd",
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
                "• Jitendex — JMdict ze wsparciem JLPT (N1-N5) ⭐\n" +
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
            Text(text = "Download recommended + JLPT (~20 MB)", fontSize = 16.sp)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onDownloadJmdict,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Download JMdict only (~15 MB)", fontSize = 14.sp)
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onSkip) {
            Text("Skip — I'll import manually")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Jitendex download: https://jitendex.org/pages/downloads.html",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DownloadingContent(
    progress: com.yomitanmobile.data.download.DownloadProgress?
) {
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
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
            DownloadPhase.DOWNLOADING -> if (isEnglish) "Downloading ${progress.dictionaryName}…" else "Pobieranie ${progress.dictionaryName}…"
            DownloadPhase.IMPORTING -> if (isEnglish) "Importing ${progress.dictionaryName}…" else "Importowanie ${progress.dictionaryName}…"
            DownloadPhase.COMPLETED -> if (isEnglish) "Done!" else "Gotowe!"
            DownloadPhase.ERROR -> progress?.errorMessage?.takeIf { it.isNotBlank() }
                ?: if (isEnglish) "Error!" else "Błąd!"
            null -> if (isEnglish) "Preparing…" else "Przygotowywanie…"
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
                text = if (isEnglish) "This may take a few minutes…" else "To może zająć kilka minut…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (progress?.phase == DownloadPhase.ERROR && !progress.errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = progress.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CompletedContent(onContinue: () -> Unit, onDebug: () -> Unit) {
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
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
            text = if (isEnglish) "Dictionary installed!" else "Słownik zainstalowany!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (isEnglish) {
                "JMdict (English) has been successfully downloaded and imported.\n" +
                    "You can now search words offline.\n\n" +
                    "Pronunciation via TTS is available automatically through Google TTS."
            } else {
                "JMdict (English) został pomyślnie pobrany i zaimportowany.\n" +
                    "Możesz teraz wyszukiwać słowa offline.\n\n" +
                    "Wymowa TTS jest dostępna automatycznie przez Google TTS."
            },
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
            Text(text = if (isEnglish) "Start searching" else "Rozpocznij wyszukiwanie", fontSize = 16.sp)
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onDebug) {
            Text(text = "Debug JLPT (logs)")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    val isEnglish = LocalConfiguration.current.locales.get(0).language.equals("en", ignoreCase = true)
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
            text = if (isEnglish) "Download error" else "Błąd pobierania",
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
            text = if (isEnglish) "Check your internet connection and try again." else "Sprawdź połączenie z internetem i spróbuj ponownie.",
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
            Text(text = if (isEnglish) "Try again" else "Spróbuj ponownie")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isEnglish) "Skip" else "Pomiń")
        }
    }
}
