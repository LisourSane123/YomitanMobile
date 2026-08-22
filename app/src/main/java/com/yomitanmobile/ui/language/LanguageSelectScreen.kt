package com.yomitanmobile.ui.language

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.util.LocaleHelper

/**
 * First-run language picker.
 *
 * Shown once, before the dictionary setup, because the answer decides what
 * setup then offers to download. It is not a preference among preferences:
 * the whole app — search, cards, the text scanner — is built around one
 * language at a time, so this is the question that has to be answered before
 * anything else can be.
 */
@Composable
fun LanguageSelectScreen(
    onLanguageChosen: () -> Unit,
    viewModel: LanguageSelectViewModel = hiltViewModel()
) {
    val saved by viewModel.saved.collectAsState()
    val isEnglish = LocaleHelper.isEnglish(LocalConfiguration.current)
    fun tr(pl: String, en: String): String = if (isEnglish) en else pl

    LaunchedEffect(saved) {
        if (saved) onLanguageChosen()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = tr("Czego się uczysz?", "What are you learning?"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = tr(
                    "Wybór decyduje o słownikach, wyszukiwaniu i formacie fiszek. " +
                        "Możesz go później zmienić w ustawieniach.",
                    "This decides the dictionaries, the search, and the card format. " +
                        "You can change it later in settings."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))

            LanguageCard(
                flag = "🇯🇵",
                title = tr("Japoński", "Japanese"),
                subtitle = tr(
                    "Kanji, furigana, akcent tonalny, poziomy JLPT. Znaczenia po angielsku.",
                    "Kanji, furigana, pitch accent, JLPT levels. English meanings."
                ),
                onClick = { viewModel.choose(AppLanguage.JAPANESE) }
            )
            Spacer(modifier = Modifier.height(16.dp))
            LanguageCard(
                flag = "🇬🇧",
                title = tr("Angielski", "English"),
                subtitle = tr(
                    "Znaczenia po polsku, wymowa IPA, przykłady zdań z tłumaczeniem.",
                    "Polish meanings, IPA pronunciation, example sentences with translations."
                ),
                onClick = { viewModel.choose(AppLanguage.ENGLISH) }
            )
        }
    }
}

@Composable
private fun LanguageCard(
    flag: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = flag, fontSize = 40.sp)
            Spacer(modifier = Modifier.width(20.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
