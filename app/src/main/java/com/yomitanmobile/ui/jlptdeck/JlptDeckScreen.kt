package com.yomitanmobile.ui.jlptdeck

import com.yomitanmobile.domain.model.LevelScale

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.yomitanmobile.ui.common.DeckPreviewItem
import com.yomitanmobile.ui.common.DeckPreviewSection
import com.yomitanmobile.ui.common.previewKeyOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.domain.model.JlptSkipReason
import kotlin.math.roundToInt
import com.yomitanmobile.ui.common.rememberTr
import com.yomitanmobile.ui.common.LocalIsEnglish
import com.yomitanmobile.ui.common.rememberAnkiPermissionGate
import com.yomitanmobile.ui.common.SectionTitle
import com.yomitanmobile.ui.common.ToggleRow

/**
 * Bulk deck generator: turns a whole JLPT (Japanese) or CEFR (English) level into cards styled exactly like
 * the ones the detail screen exports, with a dry run first so the user sees
 * what would be created (and what was filtered out) before anything is
 * written to AnkiDroid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JlptDeckScreen(
    onNavigateBack: () -> Unit,
    viewModel: JlptDeckViewModel = hiltViewModel()
) {
    val isEnglish = LocalIsEnglish.current
    val tr = rememberTr()
    val context = LocalContext.current

    // Both the dry run (it scans the collection for duplicates) and the write
    // need AnkiDroid's read/write permission. Asking here, at the moment the
    // user presses the button, beats the old advice of "export one card
    // manually first to grant it".
    val withAnkiPermission = rememberAnkiPermissionGate()

    val level by viewModel.level.collectAsState()
    // JLPT for Japanese, CEFR for English — every level name below reads it.
    val scale = viewModel.scale
    val filters by viewModel.filters.collectAsState()
    val deckName by viewModel.deckName.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val taggedWordCount by viewModel.taggedWordCount.collectAsState()
    val suspendedKeys by viewModel.suspendedKeys.collectAsState()
    val recordAsKnown by viewModel.recordAsKnown.collectAsState()

    // CreateDocument rather than a folder pick: the user names the file and
    // puts it where their Anki is, and the app never needs storage permission.
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            com.yomitanmobile.data.anki.ApkgWriter.MIME_TYPE
        )
    ) { uri -> uri?.let(viewModel::exportToFile) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is JlptDeckEvent.Finished -> tr(
                    "Utworzono ${event.result.added} kart w talii „${event.result.deckName}”" +
                        // Not necessarily errors: AnkiDroid returns fewer ids
                        // when it skips notes it considers duplicates, and the
                        // batch cannot tell the two apart.
                        (if (event.result.failed > 0) " (${event.result.failed} pominięte: duplikaty lub błędy)" else "") +
                        (if (!event.result.recordedAsKnown) " — skan kolekcji nietknięty" else ""),
                    "Created ${event.result.added} cards in deck “${event.result.deckName}”" +
                        (if (event.result.failed > 0) " (${event.result.failed} skipped: duplicates or errors)" else "") +
                        (if (!event.result.recordedAsKnown) " — the collection scan was left untouched" else "")
                )
                is JlptDeckEvent.Error -> tr("Błąd: ${event.message}", "Error: ${event.message}", "エラー: ${event.message}")
                JlptDeckEvent.PermissionRequired -> {
                    withAnkiPermission { viewModel.generate() }
                    tr("Potrzebne uprawnienie do AnkiDroida", "AnkiDroid permission needed", "AnkiDroidの権限が必要です")
                }
                JlptDeckEvent.AnkiNotInstalled -> tr("AnkiDroid nie jest zainstalowany", "AnkiDroid is not installed", "AnkiDroidがインストールされていません")
                JlptDeckEvent.Cancelled -> tr("Przerwano generowanie", "Generation cancelled", "生成をキャンセルしました")
                is JlptDeckEvent.SuspendNeedsAnki -> tr(
                    "AnkiDroid nie pozwala usypiać fiszek z zewnątrz. ${event.count} kart " +
                        "dostało tag „${event.tag}” — wyszukaj go w Anki i uśpij jednym " +
                        "ruchem. Zapis do pliku .apkg usypia je od razu.",
                    "AnkiDroid does not let another app suspend cards. ${event.count} of them " +
                        "carry the tag “${event.tag}” — search for it in Anki and suspend them " +
                        "in one go. Exporting to an .apkg file suspends them outright.", "AnkiDroidでは他のアプリがカードを保留にできません。${event.count}枚にタグ「${event.tag}」が付いています — Ankiでこのタグを検索して、まとめて保留にしてください。.apkgファイルへの書き出しなら最初から保留になります。"
                )
                is JlptDeckEvent.FileWritten -> tr(
                    "Zapisano plik: ${event.notes} fiszek, w tym ${event.suspended} uśpionych. " +
                        "Zaimportuj go w Anki.",
                    "File written: ${event.notes} cards, ${event.suspended} of them suspended. " +
                        "Import it in Anki.", "ファイルを書き出しました: カード${event.notes}枚、うち保留${event.suspended}枚。Ankiでインポートしてください。"
                )
                JlptDeckEvent.AudioUnavailable -> tr(
                    "Brak działającego syntezatora mowy — karty powstaną bez audio.",
                    "No working text-to-speech voice — cards will be created without audio.", "使える読み上げ音声がありません — 音声なしでカードを作成します。")
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Talia ${scale.displayName}", "${scale.displayName} deck", "${scale.displayName}デッキ")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back", "戻る"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                tr(
                    "Tworzy gotową talię ze wszystkich słów wybranego poziomu ${scale.displayName} — bez kopania słowo po słowie. " +
                        "Fiszki mają dokładnie ten sam wygląd, co eksport ze szczegółów słowa.",
                    "Builds a ready deck from every word of the chosen ${scale.displayName} level — no word-by-word mining. " +
                        "The cards use exactly the same styling as a single export from the detail screen.", "選んだ${scale.displayName}レベルのすべての単語から、すぐ使えるデッキを作ります — 単語を一つずつ採取する必要はありません。カードの見た目は、詳細画面から1枚ずつエクスポートしたものとまったく同じです。"
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            SectionTitle(tr("Poziom", "Level", "レベル"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scale.levels.forEach { candidate ->
                    FilterChip(
                        selected = level == candidate,
                        onClick = { viewModel.setLevel(candidate) },
                        label = { Text(scale.label(candidate)) }
                    )
                }
            }

            // Coverage warning. Without a JLPT-tagged dictionary the generator
            // falls back to the small curated built-in list, which produces a
            // deck a fraction of the real level's size — say so before the
            // user generates it, not after.
            if (taggedWordCount == 0) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        tr(
                            "Żaden zainstalowany słownik nie oznacza słów poziomem ${scale.label(level)}. " +
                                (if (scale == LevelScale.JLPT) "Talia powstanie tylko z wbudowanej, krótkiej listy — zainstaluj "
                                else "Talia będzie pusta — zainstaluj ") +
                                "słownik „${scale.tagDictionaryName}” z ekranu pobierania słowników, aby " +
                                "dostać pełny poziom.",
                            "No installed dictionary tags words with level ${scale.label(level)}. " +
                                (if (scale == LevelScale.JLPT) "The deck would be built from the short built-in list only — install the "
                                else "The deck would be empty — install the ") +
                                "“${scale.tagDictionaryName}” dictionary from the dictionary download screen " +
                                "to get the full level.", "レベル${scale.label(level)}の単語にタグを付けている辞書がインストールされていません。${if (scale == LevelScale.JLPT) "デッキは内蔵の短いリストだけから作られます — " else "デッキは空になります — "}レベル全体を得るには、辞書のダウンロード画面から「${scale.tagDictionaryName}」をインストールしてください。"
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = deckName,
                onValueChange = viewModel::setDeckName,
                label = { Text(tr("Nazwa talii w Anki", "Anki deck name", "Ankiのデッキ名")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            SectionTitle(tr("Filtry", "Filters", "フィルター"))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (filters.maxFrequencyRank > 0) {
                            tr(
                                "Pomiń słowa rzadsze niż #${filters.maxFrequencyRank}",
                                "Skip words rarer than #${filters.maxFrequencyRank}", "${filters.maxFrequencyRank}位よりまれな単語を除く")
                        } else {
                            tr("Bez limitu rzadkości", "No rarity limit", "まれさの上限なし")
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        tr(
                            "Ranga z zainstalowanych list częstotliwości. 0 = filtr wyłączony.",
                            "Rank from the installed frequency lists. 0 = filter off.", "インストール済みの頻度リストの順位。0 = フィルターなし。"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = filters.maxFrequencyRank.toFloat(),
                        onValueChange = { value ->
                            val rounded = (value / 1000f).roundToInt() * 1000
                            viewModel.updateFilters { it.copy(maxFrequencyRank = rounded) }
                        },
                        valueRange = 0f..100_000f,
                        steps = 99
                    )

                    ToggleRow(
                        title = tr("Uwzględnij słowa bez rangi", "Include unranked words", "順位のない単語も含める"),
                        subtitle = tr(
                            "Bez zainstalowanej listy częstotliwości wszystkie słowa są „bez rangi” — wyłączenie tego da pustą talię.",
                            "With no frequency list installed every word is unranked — turning this off yields an empty deck.", "頻度リストがないとすべての単語が順位なしになります — これをオフにすると空のデッキになります。"),
                        checked = filters.includeUnranked,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(includeUnranked = value) }
                        }
                    )

                    ToggleRow(
                        title = tr(
                            "Zapamiętaj utworzone słowa jako posiadane",
                            "Remember the created words as owned", "作成した単語を所有済みとして記録する"),
                        subtitle = tr(
                            "Zapisany skan kolekcji to jedyny ślad po tej talii — fiszki " +
                                "generowane nie trafiają do statystyk kopania. Wyłącz przy " +
                                "talii próbnej: skan zostanie nietknięty, aż zrobisz go sam.",
                            "The stored collection scan is the only record this deck exists — " +
                                "generated cards stay out of the mining statistics. Turn it off " +
                                "for a trial deck: the scan stays untouched until you rescan.", "保存済みのコレクションスキャンが、このデッキの唯一の記録です — 生成したカードは採取の統計に入りません。試しのデッキではオフにしてください: 再スキャンするまでスキャンはそのままです。"
                        ),
                        checked = recordAsKnown,
                        onCheckedChange = viewModel::setRecordAsKnown,
                        showDivider = true
                    )

                    ToggleRow(
                        title = tr("Pomiń archaizmy i słowa rzadkie", "Skip archaic and rare words", "古語・まれな語を除く"),
                        subtitle = tr(
                            "Tagi: archaic, obsolete, rare, dated.",
                            "Tags: archaic, obsolete, rare, dated.", "タグ: 古語、廃語、まれ、古風。"),
                        checked = filters.skipArchaic,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipArchaic = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń nazwy własne", "Skip proper names", "固有名詞を除く"),
                        subtitle = tr(
                            "Wpisy z JMnedict: nazwiska, miejsca, firmy.",
                            "JMnedict entries: surnames, places, companies.", "JMnedictの項目: 姓、地名、会社名。"),
                        checked = filters.skipProperNames,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipProperNames = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń słowa już w Anki", "Skip words already in Anki", "Ankiにある単語を除く"),
                        subtitle = tr(
                            "Skanuje całą kolekcję, także talie Core 2k/6k/10k i Kaishi 1.5k oraz wcześniej wygenerowane poziomy.",
                            "Scans the whole collection, including Core 2k/6k/10k and Kaishi 1.5k decks and previously generated levels.", "コレクション全体をスキャンします。Core 2k/6k/10k、Kaishi 1.5kのデッキや以前生成したレベルも含みます。"),
                        checked = filters.skipAlreadyInAnki,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipAlreadyInAnki = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń słowa już wykopane", "Skip words already mined", "採取済みの単語を除く"),
                        subtitle = tr(
                            "Słowa wyeksportowane wcześniej z tej aplikacji.",
                            "Words this app exported before.", "このアプリが以前エクスポートした単語。"),
                        checked = filters.skipAlreadyMined,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipAlreadyMined = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Generuj audio (wolne)", "Generate audio (slow)", "音声を生成する（遅い）"),
                        subtitle = tr(
                            "Syntezator mowy dla każdego słowa — około sekundy na kartę.",
                            "Text-to-speech per word — roughly a second per card.", "単語ごとの読み上げ — 1枚あたり約1秒。"),
                        checked = filters.generateAudio,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(generateAudio = value) }
                        }
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (filters.maxWords > 0) {
                            tr("Maksymalnie ${filters.maxWords} kart", "At most ${filters.maxWords} cards", "最大${filters.maxWords}枚")
                        } else {
                            tr("Bez limitu liczby kart", "No card-count limit", "枚数の上限なし")
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        tr(
                            "Przy limicie zostają słowa najczęstsze.",
                            "When capped, the most frequent words are kept.", "上限があるときは、よく使う単語が残ります。"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = filters.maxWords.toFloat(),
                        onValueChange = { value ->
                            val rounded = (value / 50f).roundToInt() * 50
                            viewModel.updateFilters { it.copy(maxWords = rounded) }
                        },
                        valueRange = 0f..2000f,
                        steps = 39
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            val busy = progress != null
            Button(
                onClick = { withAnkiPermission { viewModel.analyze() } },
                enabled = !isAnalyzing && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("  " + tr("Analizuję…", "Analysing…", "分析中…"))
                } else {
                    Text(tr("Sprawdź, co powstanie", "Check what would be created", "作成される内容を確認"))
                }
            }

            val currentPlan = plan
            if (currentPlan != null) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            tr(
                                "Do utworzenia: ${currentPlan.selectedCount} kart",
                                "To create: ${currentPlan.selectedCount} cards", "作成予定: ${currentPlan.selectedCount}枚"),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            tr(
                                "Kandydatów na poziomie ${scale.label(currentPlan.level)}: ${currentPlan.candidateCount}",
                                "Candidates at level ${scale.label(currentPlan.level)}: ${currentPlan.candidateCount}", "レベル${scale.label(currentPlan.level)}の候補: ${currentPlan.candidateCount}"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Say what the duplicate check actually had to work
                        // with, and how old it is. Silence here left the user
                        // unable to tell "nothing of yours is in this level"
                        // apart from "the check never ran".
                        if (currentPlan.scannedWordCount > 0) {
                            val days = ((System.currentTimeMillis() - currentPlan.scannedAt) /
                                (24L * 60L * 60L * 1000L)).toInt()
                            val age = when {
                                currentPlan.scannedAt <= 0L -> ""
                                days <= 0 -> tr(", skan z dzisiaj", ", scanned today", "、今日スキャン")
                                days == 1 -> tr(", skan sprzed 1 dnia", ", scanned 1 day ago", "、1日前にスキャン")
                                else -> tr(", skan sprzed $days dni", ", scanned $days days ago", "、${days}日前にスキャン")
                            }
                            Text(
                                tr(
                                    "Twoja kolekcja Anki: ${currentPlan.scannedWordCount} słów$age",
                                    "Your Anki collection: ${currentPlan.scannedWordCount} words$age", "Ankiコレクション: ${currentPlan.scannedWordCount}語$age"),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (days >= 7) {
                                Text(
                                    tr(
                                        "Karty dodane po skanie nie są brane pod uwagę — " +
                                            "przeskanuj kolekcję ponownie, jeśli od tego czasu " +
                                            "coś doszło.",
                                        "Cards added after that scan are not taken into account — " +
                                            "rescan the collection if anything has been added since.", "そのスキャン後に追加したカードは考慮されません — その後に何か追加したなら、コレクションを再スキャンしてください。"
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (currentPlan.ankiScanUnavailable) {
                            Text(
                                tr(
                                    "Nie udało się odczytać kolekcji Anki — sprawdzanie duplikatów wyłączone.",
                                    "Could not read the Anki collection — duplicate check disabled.", "Ankiコレクションを読み取れませんでした — 重複チェックは無効です。"),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (currentPlan.candidateCount == 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tr(
                                    "Żadne zainstalowane słowniki nie mają słów z tego poziomu. Pobierz " +
                                        "słownik „${scale.tagDictionaryName}” z ekranu pobierania słowników.",
                                    "None of the installed dictionaries cover this level. Download the " +
                                        "“${scale.tagDictionaryName}” dictionary from the dictionary download screen.", "インストール済みの辞書にこのレベルの単語はありません。辞書のダウンロード画面から「${scale.tagDictionaryName}」をダウンロードしてください。"
                                ),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (currentPlan.skipped.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                            currentPlan.skipped.forEach { (reason, count) ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        skipReasonLabel(reason, isEnglish),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text("$count", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        if (currentPlan.selected.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                            // AnkiDroid introduces new cards in the order they
                            // were written, so the order below IS the order the
                            // deck will be studied in. Saying so makes the
                            // guarantee checkable instead of implicit.
                            Text(
                                tr(
                                    "Pierwsze słowa (talia powstaje od najczęstszych):",
                                    "First words (the deck is created most-frequent-first):", "最初の単語（デッキはよく使う順に作成されます）:"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                currentPlan.selected.take(20).joinToString("、") { it.displayText() },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Words no installed frequency dictionary ranks
                            // cannot take part in that ordering — they land at
                            // the end, shuffled. Without this line a deck that
                            // is mostly unranked looks like the sorting simply
                            // did not happen.
                            val unranked = currentPlan.selected.count { it.frequency <= 0 }
                            if (unranked > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    tr(
                                        "$unranked z ${currentPlan.selectedCount} słów nie ma rangi " +
                                            "częstotliwości — trafią na koniec talii, w losowej " +
                                            "kolejności. Zainstaluj słownik częstotliwości, żeby " +
                                            "uporządkować całość.",
                                        "$unranked of ${currentPlan.selectedCount} words carry no " +
                                            "frequency rank — they go to the end of the deck, in " +
                                            "random order. Install a frequency dictionary to order " +
                                            "the whole deck.", "${currentPlan.selectedCount}語のうち${unranked}語には頻度順位がありません — デッキの最後にランダムな順で入ります。デッキ全体を並べるには頻度辞書をインストールしてください。"
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                DeckPreviewSection(
                    items = currentPlan.selected.map { entry ->
                        DeckPreviewItem(
                            key = previewKeyOf(entry),
                            expression = entry.primaryExpression,
                            reading = entry.reading,
                            gloss = entry.definitionTextShort(),
                            note = if (entry.frequency > 0) "#${entry.frequency}" else ""
                        )
                    },
                    suspended = suspendedKeys,
                    onToggle = viewModel::toggleSuspended,
                    onSuspendAll = viewModel::suspendAll,
                    onClearSuspended = viewModel::clearSuspended,
                    onSuspendFirst = viewModel::suspendFirst,
                    onSetSuspended = viewModel::setSuspended
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { withAnkiPermission { viewModel.generate() } },
                    enabled = currentPlan.selectedCount > 0 && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        tr(
                            "Utwórz ${currentPlan.selectedCount} fiszek",
                            "Create ${currentPlan.selectedCount} cards", "${currentPlan.selectedCount}枚のカードを作成")
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        fileLauncher.launch(
                            "${deckName.trim().ifBlank { scale.deckName(currentPlan.level) }}.apkg"
                        )
                    },
                    enabled = currentPlan.selectedCount > 0 && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tr("Zapisz jako plik .apkg", "Save as an .apkg file", ".apkgファイルとして保存"))
                }
                Text(
                    tr(
                        "Plik nie potrzebuje AnkiDroida, ma dokładnie jeden typ notatki i " +
                            "naprawdę usypia zaznaczone fiszki — czego API AnkiDroida nie potrafi.",
                        "A file needs no AnkiDroid, carries exactly one note type, and really " +
                            "suspends the cards you marked — which AnkiDroid's API cannot do.", "ファイルならAnkiDroidは不要で、ノートタイプはちょうど1つ、選んだカードを本当に保留にできます — AnkiDroidのAPIにはできないことです。"
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Text(
                    tr(
                        "Karty dostają tagi yomitan-mobile i ${scale.tag(currentPlan.level)}, więc łatwo je w Anki odnaleźć lub usunąć. " +
                            "Nie są liczone w statystykach kopania.",
                        "Cards are tagged yomitan-mobile and ${scale.tag(currentPlan.level)}, so they are easy to find or delete in Anki. " +
                            "They are not counted in the mining statistics.", "カードにはyomitan-mobileと${scale.tag(currentPlan.level)}のタグが付くので、Ankiで探したり削除したりしやすくなります。採取の統計には数えません。"
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            val currentProgress = progress
            if (currentProgress != null) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            tr(
                                "Tworzę fiszki: ${currentProgress.done} / ${currentProgress.total}",
                                "Creating cards: ${currentProgress.done} / ${currentProgress.total}", "カードを作成中: ${currentProgress.done} / ${currentProgress.total}"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (currentProgress.currentWord.isNotBlank()) {
                            Text(
                                currentProgress.currentWord,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = if (currentProgress.total > 0) {
                                currentProgress.done.toFloat() / currentProgress.total
                            } else 0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.cancelGeneration() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(tr("Przerwij", "Cancel", "キャンセル"))
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}


/** AnkiDroid's read/write permission, mirrored from AnkiCardCreator. */
private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

private fun skipReasonLabel(reason: JlptSkipReason, isEnglish: Boolean): String = when (reason) {
    JlptSkipReason.NO_DEFINITION ->
        com.yomitanmobile.util.trI(isEnglish, "Brak definicji w słowniku", "No definition in the dictionary", "辞書に定義がない")
    JlptSkipReason.TOO_RARE ->
        com.yomitanmobile.util.trI(isEnglish, "Zbyt rzadkie", "Too rare", "まれすぎる")
    JlptSkipReason.UNRANKED ->
        com.yomitanmobile.util.trI(isEnglish, "Bez danych o częstości", "No frequency data", "頻度のデータなし")
    JlptSkipReason.ARCHAIC ->
        com.yomitanmobile.util.trI(isEnglish, "Archaizmy i przestarzałe", "Archaic / obsolete / rare", "古語・廃語・まれ")
    JlptSkipReason.PROPER_NAME ->
        com.yomitanmobile.util.trI(isEnglish, "Nazwy własne", "Proper names", "固有名詞")
    JlptSkipReason.ALREADY_IN_ANKI ->
        com.yomitanmobile.util.trI(isEnglish, "Już w Anki", "Already in Anki", "すでにAnkiにある")
    JlptSkipReason.ALREADY_MINED ->
        com.yomitanmobile.util.trI(isEnglish, "Już wykopane w aplikacji", "Already mined in the app", "アプリで採取済み")
    JlptSkipReason.OVER_LIMIT ->
        com.yomitanmobile.util.trI(isEnglish, "Ponad limit kart", "Over the card limit", "カードの上限超過")
}
