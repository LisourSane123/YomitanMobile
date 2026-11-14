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
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.ui.common.rememberTr
import com.yomitanmobile.ui.common.rememberNotificationPermissionAsk
import com.yomitanmobile.ui.common.LocalIsEnglish

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val setupState by viewModel.setupState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isEnglish = LocalIsEnglish.current
    val withNotifications = rememberNotificationPermissionAsk()

    Surface(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = setupState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "setup_state"
        ) { state ->
            when (state) {
                SetupState.WELCOME -> WelcomeContent(
                    language = viewModel.language,
                    isEnglish = isEnglish,
                    onDownloadRecommended = { withNotifications { viewModel.startRecommendedDownload() } },
                    onDownloadPrimary = { withNotifications { viewModel.startPrimaryDownload() } },
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
                    message = errorMessage ?: com.yomitanmobile.util.trI(isEnglish, "Nieznany błąd", "Unknown error", "不明なエラー"),
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
    language: AppLanguage,
    isEnglish: Boolean,
    onDownloadRecommended: () -> Unit,
    onDownloadPrimary: () -> Unit,
    onSkip: () -> Unit
) {
    val tr = rememberTr()
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
            when (language) {
                AppLanguage.JAPANESE -> tr("Słownik japońsko-angielski", "Japanese-English dictionary", "和英辞書")
                AppLanguage.ENGLISH -> tr("Słownik angielsko-polski", "English-Polish dictionary", "英ポ辞書")
                AppLanguage.SPANISH -> tr("Słownik hiszpańsko-angielski", "Spanish-English dictionary", "西英辞書")
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Text(
            when (language) {
                AppLanguage.JAPANESE -> tr(
                    "Pobierz rekomendowane słowniki, aby w pełni korzystać z aplikacji:\n" +
                        "• Jitendex — główny słownik (~200K wpisów)\n" +
                        "• Tagi JLPT — poziomy N5–N1\n" +
                        "• JPDB Frequency — ranking częstotliwości\n" +
                        "• Kanjium — akcent tonalny (pitch accent)\n" +
                        "• KANJIDIC — rozbiór kanji na fiszkach",
                    "Download the recommended dictionaries to get the most out of the app:\n" +
                        "• Jitendex - main dictionary (~200K entries)\n" +
                        "• JLPT tags - N5-N1 levels\n" +
                        "• JPDB Frequency - frequency ranking\n" +
                        "• Kanjium - pitch accent\n" +
                        "• KANJIDIC - kanji breakdown on cards", "アプリを十分に使うために、おすすめの辞書をダウンロードしてください:\n• Jitendex — メインの辞書（約20万項目）\n• JLPTタグ — N5〜N1のレベル\n• JPDB Frequency — 頻度順位\n• Kanjium — ピッチアクセント\n• KANJIDIC — カードの漢字の内訳"
                )
                AppLanguage.ENGLISH -> tr(
                    "Pobierz rekomendowane słowniki, aby w pełni korzystać z aplikacji:\n" +
                        "• Wiktionary EN→PL — ~79 000 haseł z polskimi znaczeniami\n" +
                        "• Wymowa IPA — transkrypcja fonetyczna\n\n" +
                        "Słownik angielsko-angielski (rezerwa dla słów bez polskiego " +
                        "tłumaczenia) pobierzesz później w ustawieniach — waży 120 MB.",
                    "Download the recommended dictionaries to get the most out of the app:\n" +
                        "• Wiktionary EN→PL - ~79,000 headwords with Polish meanings\n" +
                        "• IPA pronunciation - phonetic transcription\n\n" +
                        "The English-English dictionary (the fallback for words with no " +
                        "Polish gloss) is a 120 MB download you can add later in settings.", "アプリを十分に使うために、おすすめの辞書をダウンロードしてください:\n• Wiktionary EN→PL — ポーランド語の意味付きの約79,000語\n• IPA発音 — 発音記号\n\n英英辞書（ポーランド語の訳がない単語の予備）は120 MBで、あとから設定で追加できます。"
                )
                AppLanguage.SPANISH -> tr(
                    "Pobierz rekomendowane słowniki, aby w pełni korzystać z aplikacji:\n" +
                        "• Wiktionary ES→EN — 1,16 mln haseł z angielskimi znaczeniami, " +
                        "razem z formami odmienionymi (hablando → hablar)\n" +
                        "• Wymowa IPA — transkrypcja fonetyczna\n\n" +
                        "Import słownika trwa kilkanaście minut i zajmuje ~600 MB " +
                        "w pamięci urządzenia.",
                    "Download the recommended dictionaries to get the most out of the app:\n" +
                        "• Wiktionary ES→EN - 1.16M headwords with English meanings, " +
                        "inflected forms included (hablando → hablar)\n" +
                        "• IPA pronunciation - phonetic transcription\n\n" +
                        "The import takes several minutes and needs about 600 MB of " +
                        "device storage.", "アプリを十分に使うために、おすすめの辞書をダウンロードしてください:\n• Wiktionary ES→EN — 英語の意味付きの116万語、活用形も含む（hablando → hablar）\n• IPA発音 — 発音記号\n\nインポートには数分かかり、端末の空き容量が約600 MB必要です。"
                )
            },
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
            Text(
                text = when (language) {
                    AppLanguage.JAPANESE -> tr("Pobierz rekomendowane (~19 MB)", "Download recommended (~19 MB)", "おすすめをダウンロード（約19 MB）")
                    AppLanguage.ENGLISH -> tr("Pobierz rekomendowane (~6 MB)", "Download recommended (~6 MB)", "おすすめをダウンロード（約6 MB）")
                    AppLanguage.SPANISH -> tr("Pobierz rekomendowane (~32 MB)", "Download recommended (~32 MB)", "おすすめをダウンロード（約32 MB）")
                },
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onDownloadPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                when (language) {
                    AppLanguage.JAPANESE -> tr("Tylko JMdict (~15 MB)", "JMdict only (~15 MB)", "JMdictのみ（約15 MB）")
                    AppLanguage.ENGLISH -> tr("Tylko słownik EN→PL (~4 MB)", "EN→PL dictionary only (~4 MB)", "英→ポ辞書のみ（約4 MB）")
                    AppLanguage.SPANISH -> tr("Tylko słownik ES→EN (~21 MB)", "ES→EN dictionary only (~21 MB)", "西→英辞書のみ（約21 MB）")
                },
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onSkip) {
            Text(tr("Pomiń — zaimportuję ręcznie", "Skip - I'll import manually", "スキップ — 自分でインポートする"))
        }
    }
}

@Composable
private fun DownloadingContent(
    progress: com.yomitanmobile.data.download.DownloadProgress?
) {
    val isEnglish = LocalIsEnglish.current
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
            DownloadPhase.DOWNLOADING -> com.yomitanmobile.util.trI(isEnglish, "Pobieranie ${progress.dictionaryName}…", "Downloading ${progress.dictionaryName}…", "${progress.dictionaryName}をダウンロード中…")
            DownloadPhase.IMPORTING -> com.yomitanmobile.util.trI(isEnglish, "Importowanie ${progress.dictionaryName}…", "Importing ${progress.dictionaryName}…", "${progress.dictionaryName}をインポート中…")
            DownloadPhase.COMPLETED -> com.yomitanmobile.util.trI(isEnglish, "Gotowe!", "Done!", "完了！")
            DownloadPhase.ERROR -> com.yomitanmobile.util.trI(isEnglish, "Błąd!", "Error!", "エラー！")
            null -> com.yomitanmobile.util.trI(isEnglish, "Przygotowywanie…", "Preparing…", "準備中…")
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
                text = com.yomitanmobile.util.trI(isEnglish, "To może zająć kilka minut…", "This may take a few minutes…", "数分かかることがあります…"),
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
    val isEnglish = LocalIsEnglish.current
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
            text = com.yomitanmobile.util.trI(isEnglish, "Słownik zainstalowany!", "Dictionary installed!", "辞書をインストールしました！"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = com.yomitanmobile.util.trI(
                isEnglish,
                "JMdict (English) został pomyślnie pobrany i zaimportowany.\n" +
                    "Możesz teraz wyszukiwać słowa offline.\n\n" +
                    "Wymowa TTS jest dostępna automatycznie przez Google TTS.",
                "JMdict (English) has been successfully downloaded and imported.\n" +
                    "You can now search words offline.\n\n" +
                    "Pronunciation via TTS is available automatically through Google TTS.",
                "JMdict（英語）のダウンロードとインポートが完了しました。\n" +
                    "オフラインで単語を検索できます。\n\n" +
                    "読み上げはGoogleの読み上げで自動的に使えます。"
            ),
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
            Text(text = com.yomitanmobile.util.trI(isEnglish, "Rozpocznij wyszukiwanie", "Start searching", "検索を始める"), fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    val isEnglish = LocalIsEnglish.current
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
            text = com.yomitanmobile.util.trI(isEnglish, "Błąd pobierania", "Download error", "ダウンロードエラー"),
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
            text = com.yomitanmobile.util.trI(isEnglish, "Sprawdź połączenie z internetem i spróbuj ponownie.", "Check your internet connection and try again.", "インターネット接続を確認して、もう一度お試しください。"),
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
            Text(text = com.yomitanmobile.util.trI(isEnglish, "Spróbuj ponownie", "Try again", "再試行"))
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(com.yomitanmobile.util.trI(isEnglish, "Pomiń", "Skip", "スキップ"))
        }
    }
}
