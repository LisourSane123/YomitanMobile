package com.yomitanmobile.ui.ankiscan

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.yomitanmobile.ui.common.rememberTr
import com.yomitanmobile.ui.common.LocalIsEnglish
import com.yomitanmobile.ui.common.rememberAnkiPermissionGate

/**
 * Scans the AnkiDroid collection and shows what it found.
 *
 * The word list is not decoration: the scan reads notes through a content
 * provider whose accepted search syntax has changed across AnkiDroid versions,
 * and a failed read looks exactly like an empty collection. Seeing the actual
 * words — and the note types they came from — is how the user verifies that
 * duplicate protection is really working before trusting it with a 2000-card
 * deck.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnkiScanScreen(
    onNavigateBack: () -> Unit,
    viewModel: AnkiScanViewModel = hiltViewModel()
) {
    val isEnglish = LocalIsEnglish.current
    val tr = rememberTr()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val withAnkiPermission = rememberAnkiPermissionGate()
    fun startScan() = withAnkiPermission { viewModel.scan() }

    val isScanning by viewModel.isScanning.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val storedWordCount by viewModel.storedWordCount.collectAsState()
    val refreshSurvey by viewModel.refreshSurvey.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val refreshProgress by viewModel.refreshProgress.collectAsState()
    val refreshReport by viewModel.refreshReport.collectAsState()
    var confirmRefresh by remember { mutableStateOf(false) }
    val sources by viewModel.sources.collectAsState()
    val words by viewModel.words.collectAsState()
    val query by viewModel.query.collectAsState()
    val error by viewModel.error.collectAsState()

    if (confirmRefresh) {
        AlertDialog(
            onDismissRequest = { confirmRefresh = false },
            title = { Text(tr("Przepisać pola fiszek?", "Rewrite the card fields?", "カードのフィールドを書き直しますか？")) },
            text = {
                Text(
                    tr(
                        "Każda notatka tej aplikacji zostanie zbudowana na nowo z dzisiejszych " +
                            "danych: częstotliwość z listy wiodącej, akcent, rozkład kanji, " +
                            "wymowa z archiwum. Historia powtórek, talia i harmonogram zostają " +
                            "nietknięte — zmienia się treść pól.\n\n" +
                            "Pole, którego nie da się odtworzyć (streszczenie AI, zdanie z " +
                            "książki), zachowuje dotychczasową wartość. Słowo, którego nie ma w " +
                            "żadnym zainstalowanym słowniku, jest pomijane w całości.\n\n" +
                            "Uwaga: jeśli poprawiałeś ręcznie treść pola (np. skróciłeś " +
                            "znaczenie), zostanie ono nadpisane wersją ze słownika — " +
                            "aplikacja nie rozpozna Twojej edycji.\n\n" +
                            "Zrób najpierw kopię zapasową kolekcji w AnkiDroidzie.",
                        "Every note of this app is rebuilt from today's data: the frequency from " +
                            "the leading list, pitch accent, kanji breakdown, a recording from " +
                            "the archive. Review history, deck and scheduling are untouched — " +
                            "the field contents change.\n\n" +
                            "A field that cannot be rebuilt (the AI summary, a sentence from a " +
                            "book) keeps what it had. A word no installed dictionary knows is " +
                            "skipped entirely.\n\n" +
                            "Careful: a field you edited by hand (a meaning you shortened, " +
                            "say) is overwritten with the dictionary's version — the app " +
                            "cannot recognise your edit.\n\n" +
                            "Back up your collection in AnkiDroid first.", "このアプリのすべてのノートを今日のデータで作り直します: メインのリストの頻度、ピッチアクセント、漢字の内訳、アーカイブの録音。学習履歴、デッキ、スケジュールはそのままで — フィールドの内容だけが変わります。\n\n作り直せないフィールド（AI要約、本の文）は元の内容を保ちます。インストール済みのどの辞書にもない単語はまるごとスキップします。\n\n注意: 手で編集したフィールド（短くした意味など）は辞書の内容で上書きされます — アプリは編集を見分けられません。\n\n先にAnkiDroidでコレクションをバックアップしてください。"
                    ),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRefresh = false
                    withAnkiPermission { viewModel.refreshNotes(audioWanted = true) }
                }) {
                    Text(tr("Przepisz", "Rewrite", "書き直す"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRefresh = false }) {
                    Text(tr("Anuluj", "Cancel", "キャンセル"))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Skan kolekcji Anki", "Anki collection scan", "Ankiコレクションのスキャン")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back", "戻る"))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(
                        "Czyta całą kolekcję AnkiDroida i zapisuje listę japońskich słów, które już masz. " +
                            "Dzięki temu aplikacja nie utworzy drugiej fiszki do słowa z Core, Kaishi ani z " +
                            "wcześniejszego kopania. Skan jest niezależny od typu notatki — bierze każde krótkie, " +
                            "czysto japońskie pole.",
                        "Reads the whole AnkiDroid collection and stores the Japanese words you already have, so " +
                            "the app never creates a second card for a word from Core, Kaishi or earlier mining. " +
                            "The scan is note-type agnostic — it takes every short, purely Japanese field.", "AnkiDroidのコレクション全体を読み取り、すでに持っている日本語の単語を保存します。これでCore、Kaishi、以前の採取にある単語のカードを二度作ることはありません。スキャンはノートタイプを問わず、短い日本語だけのフィールドをすべて読み取ります。"
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startScan() },
                        enabled = !isScanning,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text("  " + tr("Skanuję…", "Scanning…", "スキャン中…"))
                        } else {
                            Text(
                                if (storedWordCount > 0) tr("Skanuj ponownie", "Rescan", "再スキャン")
                                else tr("Skanuj kolekcję", "Scan collection", "コレクションをスキャン")
                            )
                        }
                    }
                    if (storedWordCount > 0) {
                        OutlinedButton(onClick = { viewModel.clear() }) {
                            Text(tr("Wyczyść", "Clear", "消去"))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Result card: note count is the signal that the provider answered.
            item {
                val result = summary
                if (result != null && !result.available) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            tr(
                                "Nie udało się odczytać kolekcji. Sprawdź, czy AnkiDroid jest zainstalowany, " +
                                    "czy przyznano uprawnienie i czy w Ustawieniach AnkiDroida włączone jest " +
                                    "„Enable AnkiDroid API”. Poprzedni wynik został zachowany.",
                                "The collection could not be read. Check that AnkiDroid is installed, the " +
                                    "permission was granted, and “Enable AnkiDroid API” is on in AnkiDroid's " +
                                    "settings. The previous result was kept.", "コレクションを読み取れませんでした。AnkiDroidがインストールされていること、権限が許可されていること、AnkiDroidの設定で「AnkiDroid APIを有効にする」がオンであることを確認してください。前回の結果は保持しました。"
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                } else if (result != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                tr(
                                    "Przeskanowano ${result.noteCount} notatek",
                                    "Scanned ${result.noteCount} notes", "${result.noteCount}件のノートをスキャンしました"),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                tr(
                                    "Znaleziono ${result.wordCount} japońskich słów",
                                    "Found ${result.wordCount} Japanese words", "日本語の単語を${result.wordCount}語見つけました"),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Two different claims, and only the second one is
                            // about knowing anything: a card added yesterday
                            // proves you own a card.
                            Text(
                                if (result.matureWordCount > 0) {
                                    tr(
                                        "W tym ${result.matureWordCount} na fiszkach dojrzałych " +
                                            "(interwał od 21 dni, nieuśpione)",
                                        "Of those, ${result.matureWordCount} sit on mature cards " +
                                            "(interval 21 days or more, not suspended)", "そのうち${result.matureWordCount}語は成熟したカード（間隔21日以上、保留を除く）にあります"
                                    )
                                } else {
                                    tr(
                                        "Twoja wersja AnkiDroida nie podała dojrzałości fiszek — " +
                                            "pozostaje liczba słów, jakie masz w kolekcji.",
                                        "Your AnkiDroid did not report card maturity — the word " +
                                            "count is all there is.", "AnkiDroidがカードの成熟度を報告しませんでした — 分かるのは単語数だけです。"
                                    )
                                },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tr(
                                    "Słów jest więcej niż notatek i to jest poprawne: z każdej notatki " +
                                        "indeksowany jest i zapis, i czytanie (食べる oraz たべる). " +
                                        "Liczba kart w AnkiDroidzie to jeszcze co innego — jedna notatka " +
                                        "z dwoma szablonami daje dwie karty.",
                                    "More words than notes is expected: each note is indexed under both " +
                                        "its written form and its reading (食べる and たべる). AnkiDroid's " +
                                        "card count is a third number again — one note with two templates " +
                                        "makes two cards.", "単語数がノート数より多いのは正常です: 各ノートは表記と読みの両方で登録されます（食べるとたべる）。AnkiDroidのカード数はさらに別の数です — テンプレートが2つあるノートは2枚のカードになります。"
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (result.truncated) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    tr(
                                        "Skan zatrzymał się na limicie bezpieczeństwa i nie objął całej " +
                                            "kolekcji. Wykrywanie duplikatów może przepuścić słowa, " +
                                            "które już masz.",
                                        "The scan stopped at its safety ceiling and did not cover the " +
                                            "whole collection. Duplicate detection may miss words you " +
                                            "already have.", "スキャンは安全上限で止まり、コレクション全体を読み取れませんでした。すでに持っている単語の重複を見逃すことがあります。"
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (result.noteCount == 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    tr(
                                        "Zero notatek przy niepustej kolekcji oznacza, że provider AnkiDroida " +
                                            "odrzucił zapytanie — to nie jest poprawny wynik.",
                                        "Zero notes on a non-empty collection means AnkiDroid's provider " +
                                            "rejected the query — that is not a valid result.", "空でないコレクションでノートが0件なのは、AnkiDroidのプロバイダーがクエリを拒否したということです — 有効な結果ではありません。"
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (result.scannedAt > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    tr("Ostatni skan: ", "Last scan: ", "前回のスキャン: ") +
                                        DateFormat.getDateTimeInstance(
                                            DateFormat.SHORT,
                                            DateFormat.SHORT
                                        ).format(Date(result.scannedAt)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (result.strayNoteTypes.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        StrayNoteTypeCard(result.strayNoteTypes)
                    }

                    Spacer(Modifier.height(12.dp))
                    RefreshSection(
                        survey = refreshSurvey,
                        refreshing = refreshing,
                        progress = refreshProgress,
                        report = refreshReport,
                        onRestyle = viewModel::restyleNoteTypes,
                        onRefresh = { confirmRefresh = true },
                        onCancel = viewModel::cancelRefresh,
                        onDismissReport = viewModel::clearRefreshReport
                    )
                } else if (storedWordCount > 0) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            tr(
                                "Zapisany skan: $storedWordCount słów",
                                "Stored scan: $storedWordCount words", "保存済みのスキャン: ${storedWordCount}語"),
                            modifier = Modifier.padding(16.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (sources.isNotEmpty()) {
                item {
                    Text(
                        tr("Typy notatek", "Note types", "ノートタイプ"),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sources.forEach { entry ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        (entry.source.ifBlank { tr("nieznany", "unknown", "不明") }) +
                                            " · ${entry.wordCount}"
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (storedWordCount > 0) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        label = { Text(tr("Szukaj w wykrytych słowach", "Search detected words", "見つかった単語を検索")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setText(AnnotatedString(viewModel.exportText()))
                                Toast.makeText(
                                    context,
                                    tr("Skopiowano listę do schowka", "Word list copied to clipboard", "単語リストをクリップボードにコピーしました"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(tr("Kopiuj listę słów", "Copy word list", "単語リストをコピー"))
                    }
                    Spacer(Modifier.height(8.dp))
                    Divider()
                }

                items(words, key = { it.word }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(row.word, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (row.source.isNotBlank()) {
                            Text(
                                row.source,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Divider()
                }

                item {
                    if (words.size >= 500) {
                        Text(
                            tr(
                                "Pokazano pierwsze 500 — użyj wyszukiwarki, żeby sprawdzić konkretne słowo.",
                                "Showing the first 500 — use the search box to check a specific word.", "最初の500語を表示しています — 特定の単語は検索欄で確認してください。"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            } else if (error == null) {
                item {
                    Text(
                        tr(
                            "Brak zapisanego skanu. Naciśnij „Skanuj kolekcję”.",
                            "No stored scan yet. Press “Scan collection”.", "保存済みのスキャンはまだありません。「コレクションをスキャン」を押してください。"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

/**
 * Near-duplicate note types this app left in the collection.
 *
 * A report and nothing more, and that is deliberate. AnkiDroid's provider
 * cannot move a note to a different note type: the only way to do it from here
 * would be to create a new note and delete the old one, which throws away its
 * review history — the single thing in a collection that cannot be rebuilt.
 * Anki on the desktop changes a note type in place, in one action, so the
 * honest help is to say exactly what to do there.
 *
 * The cause is fixed (see `getOrCreateModel`), so this list can only shrink.
 */
@Composable
private fun StrayNoteTypeCard(
    strays: List<com.yomitanmobile.data.anki.AnkiCollectionStore.StrayNoteType>
) {
    val tr = rememberTr()
    val singles = strays.count { it.notes <= 1 }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                tr(
                    "${strays.size} zbędnych typów notatek",
                    "${strays.size} redundant note types", "不要なノートタイプ ${strays.size}件"),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                tr(
                    "Starsze wersje tej aplikacji tworzyły nowy typ notatki, gdy AnkiDroid " +
                        "odmówił zapisu szablonu — $singles z nich trzyma jedną notatkę. " +
                        "Nowe eksporty już tego nie robią.",
                    "Older versions of this app minted a new note type whenever AnkiDroid " +
                        "refused a template write — $singles of these hold a single note. " +
                        "New exports no longer do that.", "以前のバージョンのアプリは、AnkiDroidがテンプレートの書き込みを拒否するたびに新しいノートタイプを作っていました — そのうち${singles}件はノートが1件だけです。新しいエクスポートではもう起きません。"
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "Naprawa musi się odbyć w Anki na komputerze: Przeglądaj → zaznacz " +
                        "notatki → Notatki → Zmień typ notatki. Aplikacja tego nie zrobi — " +
                        "AnkiDroid nie pozwala zmienić typu notatki, a obejście (nowa " +
                        "notatka i skasowanie starej) kasuje historię powtórek.",
                    "The fix has to happen in Anki on the desktop: Browse → select the " +
                        "notes → Notes → Change Note Type. The app cannot do it — AnkiDroid " +
                        "has no way to change a note's type, and the workaround (new note, " +
                        "delete the old) throws away its review history.", "修正はパソコンのAnkiで行う必要があります: ブラウズ → ノートを選択 → ノート → ノートタイプを変更。アプリにはできません — AnkiDroidにはノートタイプを変える方法がなく、代わりの方法（新しいノートを作って古いものを削除）では学習履歴が失われます。"
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))
            for (stray in strays.take(STRAY_PREVIEW_LIMIT)) {
                Text(
                    tr(
                        "${stray.name} — ${stray.notes} notatek",
                        "${stray.name} — ${stray.notes} notes", "${stray.name} — ${stray.notes}件のノート"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (strays.size > STRAY_PREVIEW_LIMIT) {
                Text(
                    tr(
                        "...oraz ${strays.size - STRAY_PREVIEW_LIMIT} kolejnych",
                        "...and ${strays.size - STRAY_PREVIEW_LIMIT} more", "…ほか${strays.size - STRAY_PREVIEW_LIMIT}件"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/** A collection can hold a thousand of these; the card shows the worst few. */
private const val STRAY_PREVIEW_LIMIT = 8


/**
 * Bringing cards this app wrote earlier up to what it writes today.
 *
 * Two buttons for two different operations, and the difference matters:
 * restyling touches note TYPES only (no note, no scheduling, nothing to lose),
 * while refreshing rewrites the contents of real notes and is therefore
 * confirmed, backed by the rule that an unbuildable field keeps its old value.
 *
 * What is missing from the list is merging the stray note types, and it is
 * missing because the provider cannot do it — see AnkiNoteRefresher.
 */
@Composable
private fun RefreshSection(
    survey: com.yomitanmobile.data.anki.AnkiNoteRefresher.Survey?,
    refreshing: Boolean,
    progress: Triple<Int, Int, String>?,
    report: RefreshReport?,
    onRestyle: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onDismissReport: () -> Unit
) {
    val tr = rememberTr()
    if (survey == null || !survey.available || survey.noteCount == 0) return

    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                tr("Odśwież fiszki tej aplikacji", "Refresh this app's cards", "このアプリのカードを更新"),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                tr(
                    "${survey.noteCount} notatek w ${survey.modelCount} typach notatek.",
                    "${survey.noteCount} notes across ${survey.modelCount} note types.", "${survey.modelCount}種類のノートタイプに${survey.noteCount}件のノート。"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "Wygląd: nadpisuje szablony i CSS we wszystkich typach notatek — także " +
                        "w tych zduplikowanych i tych starszych, z mniejszą liczbą pól: " +
                        "szablon powstaje z pól, które dany typ naprawdę ma. Nie rusza " +
                        "żadnej notatki.",
                    "Look: rewrites the templates and CSS on every note type of ours — the " +
                        "duplicates and the older, leaner ones included: the template is built " +
                        "from the fields each type actually has. No note is touched.", "見た目: このアプリのすべてのノートタイプのテンプレートとCSSを書き直します — 重複したものや、フィールドの少ない古いものも含みます。テンプレートは各タイプが実際に持つフィールドから作られます。ノートには触れません。"
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                tr(
                    "Treść: przepisuje pola z dzisiejszych danych — częstotliwość, akcent, " +
                        "kanji, wymowa z archiwum. Historia powtórek zostaje.",
                    "Contents: rewrites the fields from today's data — frequency, pitch, kanji, " +
                        "a recording from the archive. Review history is kept.", "内容: 今日のデータでフィールドを書き直します — 頻度、ピッチ、漢字、アーカイブの録音。学習履歴は保持されます。"
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                val (done, total, word) = progress
                Text(
                    tr("$done / $total — $word", "$done / $total — $word", "$done / $total — $word"),
                    fontSize = 12.sp
                )
                LinearProgressIndicator(
                    progress = if (total > 0) done.toFloat() / total else 0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            report?.let { outcome ->
                Spacer(Modifier.height(8.dp))
                Text(
                    when (outcome) {
                        is RefreshReport.Restyled -> if (outcome.refused > 0) {
                            tr(
                                "Odświeżono wygląd ${outcome.restyled} typów; " +
                                    "${outcome.refused} odrzucił AnkiDroid.",
                                "Restyled ${outcome.restyled} note types; AnkiDroid refused " +
                                    "${outcome.refused}.", "${outcome.restyled}件のノートタイプのスタイルを更新しました。AnkiDroidが拒否: ${outcome.refused}件。"
                            )
                        } else {
                            tr(
                                "Odświeżono wygląd ${outcome.restyled} typów notatek.",
                                "Restyled ${outcome.restyled} note types.", "${outcome.restyled}件のノートタイプのスタイルを更新しました。")
                        }
                        is RefreshReport.Refreshed -> tr(
                            "Przepisano ${outcome.updated} notatek; ${outcome.missing} słów " +
                                "nie ma w żadnym słowniku; ${outcome.refused} odrzucił AnkiDroid.",
                            "Rewrote ${outcome.updated} notes; ${outcome.missing} words are in " +
                                "no dictionary; AnkiDroid refused ${outcome.refused}.", "${outcome.updated}件のノートを書き直しました。どの辞書にもない単語: ${outcome.missing}件。AnkiDroidが拒否: ${outcome.refused}件。"
                        )
                        RefreshReport.Failed -> tr(
                            "Nie udało się — sprawdź uprawnienie do AnkiDroida.",
                            "It failed — check the AnkiDroid permission.", "失敗しました — AnkiDroidの権限を確認してください。")
                    },
                    fontSize = 13.sp
                )
                TextButton(onClick = onDismissReport) { Text(tr("OK", "OK", "OK")) }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRestyle, enabled = !refreshing) {
                    Text(tr("Odśwież wygląd", "Refresh the look", "見た目を更新"))
                }
                OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(tr("Przepisz pola", "Rewrite the fields", "フィールドを書き直す"))
                }
                if (refreshing) {
                    TextButton(onClick = onCancel) { Text(tr("Przerwij", "Stop", "停止")) }
                }
            }
        }
    }
}
