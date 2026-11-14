package com.yomitanmobile.ui.textscan

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.yomitanmobile.ui.common.DeckPreviewItem
import com.yomitanmobile.ui.common.DeckPreviewSection
import com.yomitanmobile.ui.common.previewKeyOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.domain.model.FrequencyTier
import com.yomitanmobile.domain.model.GrammarSource
import com.yomitanmobile.domain.model.GrammarUse
import com.yomitanmobile.domain.model.TextScanSkipReason
import kotlin.math.roundToInt
import com.yomitanmobile.ui.common.rememberTr
import com.yomitanmobile.ui.common.LocalIsEnglish
import com.yomitanmobile.ui.common.rememberAnkiPermissionGate
import com.yomitanmobile.ui.common.SectionTitle
import com.yomitanmobile.ui.common.ToggleRow

/**
 * "Make cards from what I actually watched or read": the user picks a subtitle
 * file or an EPUB, the app segments it against the installed dictionaries and
 * offers cards for the words that are not already known.
 *
 * Same shape as the JLPT deck screen — dry run first, then write — because it
 * is the same promise: nothing reaches AnkiDroid before the user has seen what
 * would be created and what was filtered out.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TextScanScreen(
    onNavigateBack: () -> Unit,
    viewModel: TextScanViewModel = hiltViewModel()
) {
    val isEnglish = LocalIsEnglish.current
    val tr = rememberTr()
    val context = LocalContext.current

    val withAnkiPermission = rememberAnkiPermissionGate()
    // See the JLPT generator: the user names the file and places it themselves,
    // so no storage permission is ever needed.
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            com.yomitanmobile.data.anki.ApkgWriter.MIME_TYPE
        )
    ) { uri -> uri?.let(viewModel::exportToFile) }

    // Multiple documents on purpose: a season of subtitles or a series of
    // volumes is one body of text, and scanning it in one go is what makes the
    // word counts and the "appears early" ordering meaningful.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.analyze(uris) }

    // The kana switches, and the wording of the grammar one, only mean
    // something for Japanese; see TextScanViewModel.isJapanese.
    val isJapanese = viewModel.isJapanese
    val filters by viewModel.filters.collectAsState()
    val deckName by viewModel.deckName.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val analysisStage by viewModel.analysisStage.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val suspendedKeys by viewModel.suspendedKeys.collectAsState()
    val recordAsKnown by viewModel.recordAsKnown.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is TextScanEvent.Finished -> tr(
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
                is TextScanEvent.Error -> tr("Błąd: ${event.message}", "Error: ${event.message}", "エラー: ${event.message}")
                is TextScanEvent.FileTooLarge -> tr(
                    "Plik jest za duży (${event.megabytes} MB).",
                    "File is too large (${event.megabytes} MB).", "ファイルが大きすぎます（${event.megabytes} MB）。")
                TextScanEvent.UnsupportedFormat -> tr(
                    "Nieobsługiwany format pliku. Obsługiwane: .srt, .ass, .ssa, .vtt, .txt, .md, .epub.",
                    "Unsupported file format. Supported: .srt, .ass, .ssa, .vtt, .txt, .md, .epub.", "対応していない形式です。対応形式: .srt, .ass, .ssa, .vtt, .txt, .md, .epub。")
                TextScanEvent.NoDictionary -> tr(
                    "Brak zainstalowanego słownika — bez niego nie da się podzielić tekstu na słowa.",
                    "No dictionary installed — without one the text cannot be split into words.", "辞書がインストールされていません — 辞書がないと文章を単語に分けられません。")
                TextScanEvent.PermissionRequired -> {
                    withAnkiPermission { viewModel.generate() }
                    tr("Potrzebne uprawnienie do AnkiDroida", "AnkiDroid permission needed", "AnkiDroidの権限が必要です")
                }
                TextScanEvent.AnkiNotInstalled ->
                    tr("AnkiDroid nie jest zainstalowany", "AnkiDroid is not installed", "AnkiDroidがインストールされていません")
                TextScanEvent.Cancelled -> tr("Przerwano tworzenie fiszek", "Card creation cancelled", "カードの作成をキャンセルしました")
                TextScanEvent.AudioUnavailable -> tr(
                    "Brak działającego syntezatora mowy — karty powstaną bez audio.",
                    "No working text-to-speech voice — cards will be created without audio.", "使える読み上げ音声がありません — 音声なしでカードを作成します。")
                is TextScanEvent.SuspendNeedsAnki -> tr(
                    "AnkiDroid nie pozwala usypiać fiszek z zewnątrz. ${event.count} kart " +
                        "dostało tag „${event.tag}” — wyszukaj go w Anki i uśpij jednym " +
                        "ruchem. Zapis do pliku .apkg usypia je od razu.",
                    "AnkiDroid does not let another app suspend cards. ${event.count} of them " +
                        "carry the tag “${event.tag}” — search for it in Anki and suspend them " +
                        "in one go. Exporting to an .apkg file suspends them outright.", "AnkiDroidでは他のアプリがカードを保留にできません。${event.count}枚にタグ「${event.tag}」が付いています — Ankiでこのタグを検索して、まとめて保留にしてください。.apkgファイルへの書き出しなら最初から保留になります。"
                )
                is TextScanEvent.FileWritten -> tr(
                    "Zapisano plik: ${event.notes} fiszek, w tym ${event.suspended} uśpionych. " +
                        "Zaimportuj go w Anki.",
                    "File written: ${event.notes} cards, ${event.suspended} of them suspended. " +
                        "Import it in Anki.", "ファイルを書き出しました: カード${event.notes}枚、うち保留${event.suspended}枚。Ankiでインポートしてください。"
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Skan tekstu", "Text scan", "テキストスキャン")) },
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
                    "Wczytaj napisy (.srt, .ass, .ssa, .vtt), książkę (.epub), notatki (.md) " +
                        "lub zwykły tekst (.txt) — " +
                        "możesz zaznaczyć wiele plików naraz, np. cały sezon albo całą serię. " +
                        "Aplikacja podzieli tekst na słowa, odrzuci te, które już znasz (kolekcja Anki " +
                        "i wcześniejsze eksporty) i zrobi fiszki z reszty.",
                    "Load subtitles (.srt, .ass, .ssa, .vtt), a book (.epub), notes (.md) " +
                        "or plain text (.txt) — " +
                        "you can pick several files at once, e.g. a whole season or a whole series. " +
                        "The app splits the text into words, drops the ones you already know (Anki " +
                        "collection and earlier exports) and makes cards from the rest.", "字幕（.srt, .ass, .ssa, .vtt）、本（.epub）、メモ（.md）、テキスト（.txt）を読み込みます — 1シーズン分やシリーズ全巻など、複数のファイルを一度に選べます。アプリは文章を単語に分け、すでに知っている単語（Ankiコレクションと以前のエクスポート）を除き、残りからカードを作ります。"
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = !isAnalyzing && progress == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tr("Wybierz pliki", "Pick files", "ファイルを選ぶ"))
            }

            if (isAnalyzing) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        "  " + stageLabel(analysisStage, isEnglish),
                        fontSize = 14.sp
                    )
                }
            }

            val currentPlan = plan
            if (currentPlan != null) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (currentPlan.fileCount == 1) {
                                currentPlan.sources.first().fileName
                            } else {
                                tr(
                                    "${currentPlan.fileCount} plików",
                                    "${currentPlan.fileCount} files", "${currentPlan.fileCount}ファイル")
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val characters = currentPlan.sources.sumOf { it.characterCount }
                        val formats = currentPlan.sources.map { it.formatLabel }.distinct()
                            .joinToString(", ")
                        val charsets = currentPlan.sources.map { it.charsetName }.distinct()
                            .joinToString(", ")
                        Text(
                            if (isJapanese) tr(
                                "$formats · $charsets · $characters znaków japońskich",
                                "$formats · $charsets · $characters Japanese characters", "$formats · $charsets · 日本語の文字 ${characters}字") else tr(
                                "$formats · $charsets · $characters liter",
                                "$formats · $charsets · $characters letters", "$formats · $charsets · ${characters}文字"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (currentPlan.fileCount > 1) {
                            // The order matters for the card order, so show it.
                            Text(
                                currentPlan.sources.joinToString(" → ") { it.fileName },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            tr(
                                "Słów w tekście: ${currentPlan.totalTokenCount}, różnych: ${currentPlan.distinctWordCount}",
                                "Words in the text: ${currentPlan.totalTokenCount}, distinct: ${currentPlan.distinctWordCount}", "本文の単語: ${currentPlan.totalTokenCount}、異なり語: ${currentPlan.distinctWordCount}"),
                            fontSize = 13.sp
                        )
                        if (currentPlan.totalTokenCount > 0) {
                            Text(
                                tr(
                                    "Rozumiesz już ok. ${(currentPlan.knownCoverage * 100).roundToInt()}% tekstu",
                                    "You already know about ${(currentPlan.knownCoverage * 100).roundToInt()}% of the text", "本文の約${(currentPlan.knownCoverage * 100).roundToInt()}%はすでに知っている単語です"),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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

            SectionTitle(tr("Zakres częstotliwości", "Frequency range", "頻度の範囲"))
            Text(
                tr(
                    "Fiszki tylko ze słów mieszczących się w wybranym progu list częstotliwości.",
                    "Cards only for words within the chosen frequency-list cut-off.", "選んだ頻度リストの範囲内の単語だけをカードにします。"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrequencyTier.SELECTABLE.forEach { tier ->
                    FilterChip(
                        selected = filters.tier == tier,
                        onClick = { viewModel.updateFilters { it.copy(tier = tier) } },
                        label = { Text(tier.label(isEnglish, japanese = com.yomitanmobile.util.LocaleHelper.active == com.yomitanmobile.util.UiLanguage.JAPANESE)) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionTitle(tr("Znam już najczęstsze", "Already know the commonest", "よく使う語はもう知っている"))
            Text(
                tr(
                    "Talia jest ułożona od najczęstszych słów, więc jej początek to z definicji " +
                        "słowa, które znasz. Powiedz, ile najczęstszych słów pominąć.",
                    "The deck is ordered commonest-first, so its opening is by definition made of " +
                        "words you already know. Say how many of the commonest to skip.", "デッキはよく使う順に並ぶので、最初の部分は当然すでに知っている単語です。よく使う語を何語飛ばすか指定してください。"
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ASSUME_KNOWN_RANKS.forEach { rank ->
                    FilterChip(
                        selected = filters.assumeKnownTopRank == rank,
                        onClick = { viewModel.updateFilters { it.copy(assumeKnownTopRank = rank) } },
                        label = {
                            Text(
                                if (rank == 0) tr("Żadnych", "None", "なし")
                                else "Top ${rank / 1000}K"
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionTitle(tr("Filtry", "Filters", "フィルター"))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (filters.minOccurrences <= 1) {
                            tr("Każde wystąpienie się liczy", "Every occurrence counts", "すべての出現を数える")
                        } else {
                            tr(
                                "Tylko słowa występujące co najmniej ${filters.minOccurrences} razy",
                                "Only words occurring at least ${filters.minOccurrences} times", "${filters.minOccurrences}回以上出てくる単語だけ")
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        tr(
                            "W książce słowo widziane raz rzadko jest warte fiszki.",
                            "In a book, a word seen once is rarely worth a card.", "本の中で一度しか見ない単語は、たいていカードにする価値がありません。"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = filters.minOccurrences.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateFilters { it.copy(minOccurrences = value.roundToInt()) }
                        },
                        valueRange = 1f..10f,
                        steps = 8
                    )

                    ToggleRow(
                        title = tr("Uwzględnij słowa bez rangi", "Include unranked words", "順位のない単語も含める"),
                        subtitle = tr(
                            "Bez listy częstotliwości wszystkie słowa są „bez rangi” — wyłączenie tego da pustą talię.",
                            "With no frequency list installed every word is unranked — turning this off yields an empty deck.", "頻度リストがないとすべての単語が順位なしになります — これをオフにすると空のデッキになります。"),
                        checked = filters.includeUnranked,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(includeUnranked = value) }
                        }
                    )

                    if (isJapanese) ToggleRow(
                        title = tr("Pomiń słowa pisane samą hiraganą", "Skip plain hiragana words", "ひらがなだけの単語を除く"),
                        subtitle = tr(
                            "Zostają słowa z kanji, katakaną (クラス, コンビニ) oraz powtórzenia " +
                                "dwóch sylab (わざわざ, そろそろ). Resztki błędów segmentacji są " +
                                "w hiraganie (それだけ, かと, けし) — razem z nimi znikają przysłówki " +
                                "typu そもそも czy とはいえ.",
                            "Keeps kanji, katakana (クラス, コンビニ) and two-mora reduplications " +
                                "(わざわざ, そろそろ). What is left of the segmentation errors is plain " +
                                "hiragana (それだけ, かと, けし) — and so are adverbs like そもそも and とはいえ.", "漢字、カタカナ（クラス、コンビニ）、2拍の繰り返し（わざわざ、そろそろ）は残ります。分割の誤りの残りはひらがなだけの語です（それだけ、かと、けし） — そもそも、とはいえのような副詞も一緒に消えます。"
                        ),
                        checked = filters.skipPlainKana,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipPlainKana = value) }
                        }
                    )

                    if (isJapanese) ToggleRow(
                        title = tr("Pomiń słowa w katakanie", "Skip katakana words", "カタカナ語を除く"),
                        subtitle = tr(
                            "Zapożyczenia (クラス, コンビニ, イヤホン) i imiona pisane katakaną. " +
                                "Powtórzenia dwóch sylab zostają (ドキドキ, ニヤニヤ).",
                            "Loanwords (クラス, コンビニ, イヤホン) and the names a story spells that " +
                                "way. Two-mora reduplications stay (ドキドキ, ニヤニヤ).", "外来語（クラス、コンビニ、イヤホン）や、物語がカタカナで書く名前。2拍の繰り返しは残ります（ドキドキ、ニヤニヤ）。"
                        ),
                        checked = filters.skipKatakana,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipKatakana = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń słowa gramatyczne", "Skip function words", "機能語を除く"),
                        subtitle = if (isJapanese) tr(
                            "Partykuły, です/ます, する/いる oraz wszystko, co słownik oznacza jako " +
                                "spójnik, partykułę, kopulę czy końcówkę posiłkową (それでも, ということ).",
                            "Particles, です/ます, する/いる, plus anything the dictionary tags as a " +
                                "conjunction, particle, copula or auxiliary (それでも, ということ).", "助詞、です/ます、する/いる、そして辞書が接続詞・助詞・断定・助動詞とする語（それでも、ということ）。"
                        ) else tr(
                            "Rodzajniki, zaimki, przyimki i czasowniki posiłkowe (the, of, would, " +
                                "el, por, estar) — klasy zamknięte, które stoją na szczycie każdej " +
                                "listy częstości.",
                            "Articles, pronouns, prepositions and auxiliaries (the, of, would, " +
                                "el, por, estar) — the closed classes that top every frequency list.", "冠詞、代名詞、前置詞、助動詞（the, of, would, el, por, estar） — どの頻度リストでも上位に来る閉じた品詞です。"
                        ),
                        checked = filters.skipFunctionWords,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipFunctionWords = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Zdanie ze źródła na froncie", "Source sentence on the front", "表面に出典の文"),
                        subtitle = tr(
                            "Fiszka pokazuje słowo w zdaniu, w którym padło — zaznaczone w tekście. " +
                                "Włącza kontekst na froncie dla tej partii kart, niezależnie od stylu kart.",
                            "The card shows the word in the sentence it appeared in, highlighted. " +
                                "Forces the front-context slot for this batch, whatever the card style says.", "カードに、単語が出てきた文を強調付きで表示します。カードのスタイル設定にかかわらず、このまとまりでは表面の文脈欄を使います。"
                        ),
                        checked = filters.useSourceSentences,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(useSourceSentences = value) }
                        }
                    )

                    ToggleRow(
                        title = tr(
                            "Zapamiętaj utworzone słowa jako posiadane",
                            "Remember the created words as owned", "作成した単語を所有済みとして記録する"),
                        subtitle = tr(
                            "Zapisany skan kolekcji to jedyny ślad po tej talii — fiszki " +
                                "generowane nie trafiają do statystyk kopania. Wyłącz przy " +
                                "talii próbnej: kolejny tom nie zostanie zatruty, a skan " +
                                "zrobisz sam, kiedy talia będzie dobra.",
                            "The stored collection scan is the only record this deck exists — " +
                                "generated cards stay out of the mining statistics. Turn it off " +
                                "for a trial deck: the next volume's scan stays clean, and you " +
                                "rescan yourself once the deck is good.", "保存済みのコレクションスキャンが、このデッキの唯一の記録です — 生成したカードは採取の統計に入りません。試しのデッキではオフにしてください: 次の巻のスキャンが汚れず、デッキが良ければ自分で再スキャンできます。"
                        ),
                        checked = recordAsKnown,
                        onCheckedChange = viewModel::setRecordAsKnown
                    )

                    ToggleRow(
                        title = tr("Pomiń słowa już w Anki", "Skip words already in Anki", "Ankiにある単語を除く"),
                        subtitle = tr(
                            "Na podstawie zapisanego skanu kolekcji (ekran „Skan kolekcji Anki”).",
                            "Based on the stored collection scan (the “Anki collection scan” screen).", "保存済みのコレクションスキャン（「Ankiコレクションのスキャン」画面）に基づきます。"),
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
                        title = tr("Pomiń archaizmy i słowa rzadkie", "Skip archaic and rare words", "古語・まれな語を除く"),
                        subtitle = tr("Tagi: archaic, obsolete, rare, dated.", "Tags: archaic, obsolete, rare, dated.", "タグ: 古語、廃語、まれ、古風。"),
                        checked = filters.skipArchaic,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipArchaic = value) }
                        }
                    )

                    ToggleRow(
                        title = tr("Pomiń nazwy własne", "Skip proper names", "固有名詞を除く"),
                        subtitle = tr(
                            "Nazwiska i nazwy miejsc z napisów potrafią zdominować listę.",
                            "Surnames and place names from subtitles can dominate the list.", "字幕の人名や地名がリストを占めてしまうことがあります。"),
                        checked = filters.skipProperNames,
                        onCheckedChange = { value ->
                            viewModel.updateFilters { it.copy(skipProperNames = value) }
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
                            "Przy limicie zostają słowa najczęstsze w tym tekście.",
                            "When capped, the words most frequent in this text are kept.", "上限があるときは、本文によく出る単語が残ります。"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = filters.maxWords.toFloat(),
                        onValueChange = { value ->
                            val rounded = (value / 25f).roundToInt() * 25
                            viewModel.updateFilters { it.copy(maxWords = rounded) }
                        },
                        valueRange = 0f..1000f,
                        steps = 39
                    )
                }
            }

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
                        if (currentPlan.ankiScanUnavailable) {
                            Text(
                                tr(
                                    "Kolekcja Anki nie była jeszcze skanowana — nie wiadomo, które słowa już masz. " +
                                        "Uruchom „Skan kolekcji Anki” w ustawieniach.",
                                    "The Anki collection has never been scanned — it is unknown which words you " +
                                        "already have. Run “Anki collection scan” in the settings.", "Ankiコレクションがまだスキャンされていません — どの単語をすでに持っているか分かりません。設定の「Ankiコレクションのスキャン」を実行してください。"
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
                            Text(
                                tr("Najczęstsze nieznane słowa:", "Most frequent unknown words:", "よく出る未知の単語:"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                currentPlan.selected.take(20).joinToString("、") {
                                    "${it.entry.displayText()}(${it.occurrences})"
                                },
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (currentPlan.grammarUses.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Divider()
                            GrammarHighlights(currentPlan.grammarUses)
                            GrammarCounter(currentPlan.grammarUses, isEnglish)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                DeckPreviewSection(
                    items = currentPlan.selected.map { word ->
                        DeckPreviewItem(
                            key = previewKeyOf(word.entry),
                            expression = word.entry.primaryExpression,
                            reading = word.entry.reading,
                            gloss = word.entry.definitionTextShort(),
                            note = tr("×${word.occurrences}", "×${word.occurrences}", "×${word.occurrences}")
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
                    enabled = currentPlan.selectedCount > 0 && progress == null,
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
                            "${deckName.trim().ifBlank { "Yomitan Mobile" }}.apkg"
                        )
                    },
                    enabled = currentPlan.selectedCount > 0 && progress == null,
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
                        "Kolejność kart: najpierw słowa najczęstsze w japońszczyźnie w ogóle, potem te " +
                            "najczęstsze w tych plikach, a przy remisie te, które pojawiają się wcześniej " +
                            "(przy serii — w pierwszych tomach). Anki wprowadza nowe karty w kolejności " +
                            "dodania, więc zostaw w talii domyślne „nowe karty: kolejność dodania”.",
                        "Card order: words most common in Japanese overall first, then the ones most " +
                            "frequent in these files, ties going to whatever appears earliest (in a " +
                            "series, the first volumes). AnkiDroid introduces new cards in the order " +
                            "they were added, so keep the deck's default “new cards: order added”.", "カードの順番: まず日本語全体でよく使う単語、次にこれらのファイルでよく出る単語、同じなら早く出てくるもの（シリーズなら最初の巻）。AnkiDroidは新しいカードを追加順に出すので、デッキの初期設定「新規カード: 追加順」のままにしてください。"
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    tr(
                        "Karty dostają tagi yomitan-mobile i text-scan oraz nazwę pliku, więc łatwo je w Anki " +
                            "odnaleźć lub usunąć. Nie są liczone w statystykach kopania.",
                        "Cards are tagged yomitan-mobile, text-scan and the file name, so they are easy to find " +
                            "or delete in Anki. They are not counted in the mining statistics.", "カードにはyomitan-mobile、text-scan、ファイル名のタグが付くので、Ankiで探したり削除したりしやすくなります。採取の統計には数えません。"
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

private fun stageLabel(stage: String, isEnglish: Boolean): String = when (stage) {
    TextScanViewModel.STAGE_READING ->
        com.yomitanmobile.util.trI(isEnglish, "Czytam plik…", "Reading the file…", "ファイルを読み込み中…")
    TextScanViewModel.STAGE_LEXICON ->
        com.yomitanmobile.util.trI(isEnglish, "Ładuję słownik…", "Loading the dictionary…", "辞書を読み込み中…")
    TextScanViewModel.STAGE_TOKENIZING ->
        com.yomitanmobile.util.trI(isEnglish, "Dzielę tekst na słowa…", "Splitting the text into words…", "文章を単語に分割中…")
    TextScanViewModel.STAGE_RESOLVING ->
        com.yomitanmobile.util.trI(isEnglish, "Szukam słów w słowniku…", "Looking words up…", "単語を辞書で検索中…")
    TextScanViewModel.STAGE_COMPARING ->
        com.yomitanmobile.util.trI(isEnglish, "Porównuję ze znanymi słowami…", "Comparing with what you know…", "既知の単語と照合中…")
    else -> com.yomitanmobile.util.trI(isEnglish, "Analizuję…", "Analysing…", "分析中…")
}

/**
 * Offered "I already know the top N words" cut-offs. Beginners take none;
 * 1K–5K is the useful range for someone who has finished a core deck and wants
 * the scan to hand them only what that deck did not cover.
 */
private val ASSUME_KNOWN_RANKS = listOf(0, 1_000, 2_000, 3_000, 5_000)

/**
 * What the text leans on grammatically, as something to read before reading.
 *
 * The counter below it is an instrument for tuning the filters; this is the
 * same data answering a learner's question instead — a construction met forty
 * times in one volume is worth half an hour with a grammar guide, and no
 * vocabulary deck will teach it.
 *
 * Its limits are stated rather than hidden: what the scanner can see are
 * dictionary ENTRIES tagged as grammar, so 〜ざるを得ない shows up and 〜ている
 * never will — JMdict has no entry for it.
 */
@Composable
private fun GrammarHighlights(uses: List<GrammarUse>) {
    val tr = rememberTr()
    val top = uses.sortedByDescending { it.occurrences }.take(GRAMMAR_HIGHLIGHT_LIMIT)
    if (top.isEmpty()) return

    Spacer(Modifier.height(8.dp))
    Text(
        tr("Na czym stoi ten tekst:", "What this text leans on:", "この文章がよく使う文法:"),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )
    Text(
        top.joinToString("、") { "${it.form}(${it.occurrences})" },
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        tr(
            "Konstrukcje, które słownik zna jako osobne hasła — powtórz je przed lekturą. " +
                "Formy bez własnego hasła (〜ている, 〜てしまう) się tu nie pojawią.",
            "Constructions the dictionary lists as entries of their own — worth revising " +
                "before you read. Forms with no entry (〜ている, 〜てしまう) cannot appear here.", "辞書に独立した項目のある構文 — 読む前に復習する価値があります。項目のない形（〜ている、〜てしまう）はここに出ません。"
        ),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
}

/**
 * How often the text used each grammatical structure.
 *
 * A diagnostic, collapsed by default: it is the only way to see where the
 * grammar filter drew its line on THIS text — what it dropped as scaffolding,
 * what it kept because the construction is rare, and how often the book
 * actually uses each one. When a deck comes out full of grammar, or missing a
 * construction the reader wanted, this list says which rule did it.
 */
@Composable
private fun GrammarCounter(uses: List<GrammarUse>, isEnglish: Boolean) {
    val tr = rememberTr()
    var expanded by remember { mutableStateOf(false) }
    val totalOccurrences = uses.sumOf { it.occurrences }

    TextButton(
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 0.dp)
    ) {
        Text(
            tr(
                "Gramatyka w tekście: ${uses.size} struktur, $totalOccurrences użyć" +
                    (if (expanded) " ▲" else " ▼"),
                "Grammar in this text: ${uses.size} structures, $totalOccurrences uses" +
                    (if (expanded) " ▲" else " ▼"), "この文章の文法: ${uses.size}構文、${totalOccurrences}回${if (expanded) " ▲" else " ▼"}"
            ),
            fontSize = 13.sp
        )
    }

    if (!expanded) return

    Text(
        tr(
            "lista = pominięte jako podstawy · tagi = pominięte na podstawie tagów słownika · " +
                "przeszło = reguły gramatyczne przepuściły; ✓ oznacza, że powstała z tego fiszka",
            "list = dropped as basics · tags = dropped by the dictionary's tags · " +
                "passed = the grammar rules let it through; ✓ marks the ones that became a card", "リスト = 基本として除外 · タグ = 辞書のタグで除外 · 通過 = 文法の規則が通した; ✓はカードになったもの"
        ),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    for (use in uses.take(GRAMMAR_COUNTER_LIMIT)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(use.form, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                grammarSourceLabel(use.source, isEnglish),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                if (use.becameCard) "✓" else " ",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                if (use.rank > 0) "#${use.rank}" else "—",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text("${use.occurrences}×", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (uses.size > GRAMMAR_COUNTER_LIMIT) {
        Text(
            tr(
                "…i ${uses.size - GRAMMAR_COUNTER_LIMIT} rzadszych",
                "…and ${uses.size - GRAMMAR_COUNTER_LIMIT} rarer ones", "…ほか、よりまれなもの${uses.size - GRAMMAR_COUNTER_LIMIT}件"),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Long enough to see the shape of the text, short enough to scroll past. */
/** How many structures the pre-reading summary names. */
private const val GRAMMAR_HIGHLIGHT_LIMIT = 10

private const val GRAMMAR_COUNTER_LIMIT = 60

private fun grammarSourceLabel(source: GrammarSource, isEnglish: Boolean): String = when (source) {
    GrammarSource.STOPLIST -> com.yomitanmobile.util.trI(isEnglish, "lista", "list", "リスト")
    GrammarSource.TAG_RULE -> com.yomitanmobile.util.trI(isEnglish, "tagi", "tags", "タグ")
    GrammarSource.KEPT -> com.yomitanmobile.util.trI(isEnglish, "przeszło", "passed", "通過")
}

private fun skipReasonLabel(reason: TextScanSkipReason, isEnglish: Boolean): String = when (reason) {
    TextScanSkipReason.NOT_IN_DICTIONARY ->
        com.yomitanmobile.util.trI(isEnglish, "Brak w słownikach", "Not in any dictionary", "どの辞書にもない")
    TextScanSkipReason.TOO_FEW_OCCURRENCES ->
        com.yomitanmobile.util.trI(isEnglish, "Za mało wystąpień", "Too few occurrences", "出現回数が少なすぎる")
    TextScanSkipReason.FUNCTION_WORD ->
        com.yomitanmobile.util.trI(isEnglish, "Słowa gramatyczne", "Grammar / function words", "文法・機能語")
    TextScanSkipReason.KANA_ONLY ->
        com.yomitanmobile.util.trI(isEnglish, "Sama hiragana", "Plain hiragana", "ひらがなだけ")
    TextScanSkipReason.KATAKANA_ONLY ->
        com.yomitanmobile.util.trI(isEnglish, "Katakana", "Katakana", "カタカナ")
    TextScanSkipReason.NOISE ->
        com.yomitanmobile.util.trI(isEnglish, "Szum (odgłosy, fragmenty słów)", "Noise (sounds, word fragments)", "ノイズ（擬音、単語の断片）")
    TextScanSkipReason.NO_DEFINITION ->
        com.yomitanmobile.util.trI(isEnglish, "Brak definicji w słowniku", "No definition in the dictionary", "辞書に定義がない")
    TextScanSkipReason.TOO_RARE ->
        com.yomitanmobile.util.trI(isEnglish, "Poza zakresem częstotliwości", "Outside the frequency range", "頻度の範囲外")
    TextScanSkipReason.UNRANKED ->
        com.yomitanmobile.util.trI(isEnglish, "Bez danych o częstości", "No frequency data", "頻度のデータなし")
    TextScanSkipReason.ASSUMED_KNOWN ->
        com.yomitanmobile.util.trI(isEnglish, "Zbyt częste (uznane za znane)", "Too common (assumed known)", "よく使いすぎる（既知とみなす）")
    TextScanSkipReason.ARCHAIC ->
        com.yomitanmobile.util.trI(isEnglish, "Archaizmy i przestarzałe", "Archaic / obsolete / rare", "古語・廃語・まれ")
    TextScanSkipReason.PROPER_NAME ->
        com.yomitanmobile.util.trI(isEnglish, "Nazwy własne", "Proper names", "固有名詞")
    TextScanSkipReason.ALREADY_IN_ANKI ->
        com.yomitanmobile.util.trI(isEnglish, "Już w Anki", "Already in Anki", "すでにAnkiにある")
    TextScanSkipReason.ALREADY_MINED ->
        com.yomitanmobile.util.trI(isEnglish, "Już wykopane w aplikacji", "Already mined in the app", "アプリで採取済み")
    TextScanSkipReason.OVER_LIMIT ->
        com.yomitanmobile.util.trI(isEnglish, "Ponad limit kart", "Over the card limit", "カードの上限超過")
}
