package com.yomitanmobile.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomitanmobile.ui.common.tr

/**
 * Everything that builds cards in bulk, in one place.
 *
 * These three used to be rows in the settings screen, which is where a feature
 * goes to be forgotten: the JLPT generator, the text scanner and the
 * collection scan are the reason the app exists beyond looking words up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onNavigateToJlptDeck: () -> Unit,
    onNavigateToTextScan: () -> Unit,
    onNavigateToAnkiScan: () -> Unit,
    onNavigateToDictionaries: () -> Unit,
    onNavigateToDownload: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToKanji: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("Narzędzia", "Tools", "ツール")) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // What exists for the language being studied: the level decks are
            // JLPT or CEFR, and the kanji browser is Japanese machinery —
            // shown for English it led to a screen that could only fail.
            val studyLanguage = com.yomitanmobile.ui.common.LocalStudyLanguage.current
            val scale = com.yomitanmobile.domain.model.LevelScale.forLanguage(studyLanguage)
            SectionLabel(tr("Tworzenie fiszek", "Building cards", "カードの作成"))
            if (scale != null) ToolCard(
                icon = Icons.Default.School,
                title = tr("Generator talii ${scale.displayName}", "${scale.displayName} deck generator", "${scale.displayName}デッキの生成"),
                subtitle = tr(
                    "Cały poziom naraz, bez kopania słowo po słowie.",
                    "A whole level at once, without mining word by word.", "単語を一つずつ採取せずに、レベル全体を一度に。"),
                onClick = onNavigateToJlptDeck
            )
            // The scanner is no longer Japanese-only: English and Spanish
            // read the same files through LatinTokenizer.
            ToolCard(
                icon = Icons.Default.MenuBook,
                title = tr("Fiszki z napisów lub książki", "Cards from subtitles or a book", "字幕や本からカードを作る"),
                subtitle = tr(
                    "Skanuje plik i robi fiszki ze słów, których jeszcze nie znasz.",
                    "Scans a file and makes cards for the words you do not know yet.", "ファイルをスキャンして、まだ知らない単語のカードを作ります。"),
                onClick = onNavigateToTextScan
            )
            ToolCard(
                icon = Icons.Default.Search,
                title = tr("Skan kolekcji Anki", "Anki collection scan", "Ankiコレクションのスキャン"),
                subtitle = tr(
                    "Czyta twoją kolekcję, żeby nie tworzyć fiszek, które już masz.",
                    "Reads your collection so no card is made twice.", "コレクションを読み取り、同じカードを二度作らないようにします。"),
                onClick = onNavigateToAnkiScan
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel(tr("Słowniki i słowa", "Dictionaries and words", "辞書と単語"))
            ToolCard(
                icon = Icons.Default.LibraryBooks,
                title = tr("Pobierz słowniki", "Download dictionaries", "辞書をダウンロード"),
                subtitle = tr(
                    "Kolejka działa w tle — możesz wrócić do nauki.",
                    "The queue runs in the background — go back to studying.", "キューはバックグラウンドで進みます — 勉強に戻ってください。"),
                onClick = onNavigateToDownload
            )
            ToolCard(
                icon = Icons.Default.Category,
                title = tr("Zainstalowane słowniki", "Installed dictionaries", "インストール済みの辞書"),
                subtitle = tr("Przeglądaj i zarządzaj słownikami", "Browse and manage dictionaries", "辞書の一覧と管理"),
                onClick = onNavigateToDictionaries
            )
            if (studyLanguage.hasJapaneseFeatures) ToolCard(
                icon = Icons.Default.Translate,
                title = tr("Kanji", "Kanji", "漢字"),
                subtitle = tr(
                    "Znaki według poziomu i ile z nich masz już w kolekcji",
                    "Characters by level, and how many your collection covers", "レベル別の漢字と、コレクションのカバー率"),
                onClick = onNavigateToKanji
            )

            ToolCard(
                icon = Icons.Default.FavoriteBorder,
                title = tr("Ulubione", "Favourites", "お気に入り"),
                subtitle = tr("Słowa odłożone na później", "Words saved for later", "あとで見るために保存した単語"),
                onClick = onNavigateToFavorites
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
