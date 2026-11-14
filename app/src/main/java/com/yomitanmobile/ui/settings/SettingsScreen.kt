package com.yomitanmobile.ui.settings

import com.yomitanmobile.data.settings.PreferenceKeys
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.anki.CardMeaningLanguage
import com.yomitanmobile.data.anki.MonolingualCardResolver
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.util.InputSanitizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import java.io.File
import com.yomitanmobile.ui.common.rememberTr
import com.yomitanmobile.ui.common.LocalIsEnglish
import com.yomitanmobile.ui.common.SettingsDivider
import com.yomitanmobile.ui.common.SettingsRow
import com.yomitanmobile.ui.common.SettingsSectionHeader

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDownload: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToCardStyle: () -> Unit = {},
    onNavigateToDictionaries: () -> Unit = {},
    onNavigateToFrequencyDisplay: () -> Unit = {},
    onNavigateToJlptDeck: () -> Unit = {},
    onNavigateToAnkiScan: () -> Unit = {},
    onNavigateToTextScan: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isEnglish = LocalIsEnglish.current
    val tr = rememberTr()
    val isImporting by viewModel.isImporting.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val isBackingUp by viewModel.isBackingUp.collectAsState()
    val isRestoring by viewModel.isRestoring.collectAsState()
    var showDeckEditDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    // After a successful restore the in-memory Hilt-singleton database
    // handle is closed and every DAO reference is stale. Continuing to use
    // the app in that state throws on the next query, so we lock the UI
    // behind a mandatory dialog whose only action is to kill the process.
    // The user relaunches and Hilt rebuilds the graph against the
    // newly-restored DB file.
    var showRestartRequiredDialog by remember { mutableStateOf(false) }
    // Study-language switch. Holds the language the user tapped, which is
    // also what makes the dialog visible; null = no switch pending.
    var pendingStudyLanguage by remember { mutableStateOf<AppLanguage?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var selectedBackupForRestore by remember { mutableStateOf<File?>(null) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var currentDeckName by remember { mutableStateOf("") }
    // Whether "Create backup" also exports the whitelisted settings
    // (everything except the AI API key). On by default.
    var includeSettingsInBackup by remember { mutableStateOf(true) }
    var currentThemeMode by remember { mutableStateOf("system") }
    var currentLanguage by remember { mutableStateOf("system") }
    var dailyGoalCount by remember { mutableStateOf(0f) }
    // Card meaning engine (JP-EN vs JP-JP) and the dictionaries the JP-JP mode
    // reads definitions from, in the order they are asked.
    var cardMeaningLanguage by remember { mutableStateOf(CardMeaningLanguage.ENGLISH) }
    var monolingualDictionaries by remember { mutableStateOf(emptyList<String>()) }
    var showMonolingualPicker by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    fun saveMonolingualDictionaries(names: List<String>) {
        monolingualDictionaries = names
        coroutineScope.launch {
            context.dataStore.edit {
                it[PreferenceKeys.CARD_MONOLINGUAL_DICTIONARY] =
                    MonolingualCardResolver.serializeDictionaryChain(names)
            }
        }
    }

    // Load current deck name, theme mode and daily goal
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        currentDeckName = prefs[PreferenceKeys.ANKI_DECK_NAME] ?: ""
        currentThemeMode = prefs[PreferenceKeys.THEME_MODE] ?: "system"
        dailyGoalCount = (prefs[PreferenceKeys.DAILY_GOAL_COUNT] ?: 0).toFloat()
        cardMeaningLanguage = CardMeaningLanguage.fromStorage(prefs[PreferenceKeys.CARD_MEANING_LANGUAGE])
        monolingualDictionaries = MonolingualCardResolver.parseDictionaryChain(
            prefs[PreferenceKeys.CARD_MONOLINGUAL_DICTIONARY]
        )
        // Language is stored in SharedPreferences (needed for synchronous read at startup)
        val langPrefs = context.getSharedPreferences(MainActivity.LANG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        currentLanguage = langPrefs.getString(MainActivity.LANG_PREFS_KEY, "system") ?: "system"
    }

    // Adds an installed dictionary to the end of the JP-JP list, or takes it
    // off. Listing the
    // installed dictionaries (instead of hardcoding a name) is what makes the
    // engine work with any monolingual zip the user imported themselves — the
    // commercial 国語辞典 can't be shipped as downloads.
    if (showMonolingualPicker) {
        val installed by viewModel.dictionaries.collectAsState()
        AlertDialog(
            onDismissRequest = { showMonolingualPicker = false },
            title = { Text(tr("Słownik JP-JP", "JP-JP dictionary", "JP-JP辞書")) },
            text = {
                if (installed.isEmpty()) {
                    Text(
                        tr(
                            "Brak zainstalowanych słowników.",
                            "No dictionaries installed.", "辞書がインストールされていません。")
                    )
                } else {
                    LazyColumn {
                        items(installed, key = { it.id }) { info ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        saveMonolingualDictionaries(
                                            if (info.name in monolingualDictionaries) {
                                                monolingualDictionaries - info.name
                                            } else {
                                                monolingualDictionaries + info.name
                                            }
                                        )
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(info.name, modifier = Modifier.weight(1f))
                                val position = monolingualDictionaries.indexOf(info.name)
                                if (position >= 0) {
                                    Text("${position + 1}", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMonolingualPicker = false }) {
                    Text(tr("Zamknij", "Close", "閉じる"))
                }
            }
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) viewModel.importDictionary(inputStream)
                else Toast.makeText(context, tr("Nie można otworzyć pliku", "Cannot open file", "ファイルを開けません"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, tr("Błąd: ${e.message}", "Error: ${e.message}", "エラー: ${e.message}"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Standalone settings.json import — counterpart of the "include settings"
    // export toggle on backup creation. JSON mime types vary by file manager
    // (some report octet-stream or text/plain for .json), so accept all three.
    val settingsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) viewModel.importSettings(inputStream)
                else Toast.makeText(context, tr("Nie można otworzyć pliku", "Cannot open file", "ファイルを開けません"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, tr("Błąd: ${e.message}", "Error: ${e.message}", "エラー: ${e.message}"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // The pronunciation archive: one folder, walked once. OpenDocumentTree
    // rather than a file picker because the archives are directory trees of
    // hundreds of thousands of files, and SAF cannot be asked for one of them
    // by name — see AudioArchive.
    val audioArchiveFiles by viewModel.audioArchiveFiles.collectAsState()
    val audioArchiveLabel by viewModel.audioArchiveLabel.collectAsState()
    val audioArchiveIndexing by viewModel.audioArchiveIndexing.collectAsState()
    val nativeAudioState by viewModel.nativeAudioState.collectAsState()
    val linguaLibreState by viewModel.linguaLibreState.collectAsState()
    val voicevoxState by viewModel.voicevoxState.collectAsState()
    val voicevoxEnabled by viewModel.voicevoxEnabled.collectAsState()
    var showVoicevoxTerms by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val audioArchivePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::indexAudioArchive) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ImportSuccess ->
                    Toast.makeText(
                        context,
                        buildString {
                            append(
                                tr(
                                    "Zaimportowano ${event.result.dictionaryName}: ${event.result.entriesImported} wpisów",
                                    "Imported ${event.result.dictionaryName}: ${event.result.entriesImported} entries", "${event.result.dictionaryName}をインポートしました: ${event.result.entriesImported}件")
                            )
                            // The index that meaning search reads did not
                            // rebuild — say so, instead of leaving the user
                            // with a success message and a search that can't
                            // see the new dictionary.
                            if (event.result.warning != null) {
                                append(" — ")
                                append(
                                    tr(
                                        "uwaga: indeks wyszukiwania po znaczeniu nie odświeżył się, powtórz import",
                                        "warning: the meaning-search index did not refresh, re-run the import", "警告: 意味検索の索引が更新されませんでした。インポートをやり直してください")
                                )
                            }
                        },
                        Toast.LENGTH_LONG
                    ).show()
                is SettingsEvent.ImportError ->
                    Toast.makeText(context, tr("Błąd importu: ${event.message}", "Import error: ${event.message}", "インポートエラー: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.BackupSuccess -> {
                    Toast.makeText(context, tr("Kopia zapasowa utworzona", "Backup created", "バックアップを作成しました"), Toast.LENGTH_LONG).show()
                    showBackupDialog = false
                }
                is SettingsEvent.BackupError ->
                    Toast.makeText(context, tr("Błąd: ${event.message}", "Error: ${event.message}", "エラー: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.RestoreSuccess -> {
                    showRestoreDialog = false
                    showRestartRequiredDialog = true
                }
                is SettingsEvent.RestoreError ->
                    Toast.makeText(context, tr("Błąd przywracania: ${event.message}", "Restore error: ${event.message}", "復元エラー: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.SettingsImported -> {
                    val msg = if (event.applied > 0) {
                        tr(
                            "Zaimportowano ustawienia (${event.applied})",
                            "Settings imported (${event.applied})", "設定をインポートしました（${event.applied}）")
                    } else {
                        tr(
                            "Plik nie zawiera żadnych rozpoznanych ustawień",
                            "The file contains no recognised settings", "ファイルに認識できる設定がありません")
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                is SettingsEvent.SettingsImportError ->
                    Toast.makeText(context, tr("Błąd importu ustawień: ${event.message}", "Settings import error: ${event.message}", "設定のインポートエラー: ${event.message}"), Toast.LENGTH_LONG).show()
                is SettingsEvent.ReclassifyDone -> {
                    val msg = tr(
                        "Przeliczono ${event.updated}, zachowano ręcznych ${event.skippedManual}, pominięto ${event.skippedMissing}",
                        "Updated ${event.updated}, kept ${event.skippedManual} manual, skipped ${event.skippedMissing}", "更新 ${event.updated}件、手動の保持 ${event.skippedManual}件、スキップ ${event.skippedMissing}件")
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                is SettingsEvent.ReclassifyError ->
                    Toast.makeText(
                        context,
                        tr("Błąd: ${event.message}", "Error: ${event.message}", "エラー: ${event.message}"),
                        Toast.LENGTH_LONG
                    ).show()
                is SettingsEvent.AudioArchiveIndexed ->
                    Toast.makeText(
                        context,
                        tr(
                            "Zindeksowano ${event.files} nagrań",
                            "Indexed ${event.files} recordings", "${event.files}件の録音を索引に登録しました"),
                        Toast.LENGTH_LONG
                    ).show()
                is SettingsEvent.AudioArchiveEmpty ->
                    Toast.makeText(
                        context,
                        tr(
                            "W tym folderze nie ma plików audio z japońskimi nazwami — poprzednie archiwum zostało bez zmian.",
                            "That folder holds no audio files with Japanese names — the previous archive was left alone.", "そのフォルダーには日本語名の音声ファイルがありません — 以前のアーカイブはそのままです。"),
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    if (showDeckEditDialog) {
        var editedDeckName by remember { mutableStateOf(currentDeckName.ifBlank { "Mining Deck" }) }
        // Existing AnkiDroid decks, fetched once when the dialog opens so the
        // user can pick one instead of retyping. Empty when AnkiDroid is
        // absent / unauthorized — then only the manual field shows.
        var availableSettingsDecks by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) {
            availableSettingsDecks = viewModel.getAvailableDecks()
        }

        fun saveDeck(name: String) {
            val sanitized = InputSanitizer.sanitizeDeckName(name)
            currentDeckName = sanitized
            coroutineScope.launch {
                context.dataStore.edit { prefs ->
                    prefs[PreferenceKeys.ANKI_DECK_NAME] = sanitized
                }
            }
            showDeckEditDialog = false
        }

        AlertDialog(
            onDismissRequest = { showDeckEditDialog = false },
            title = { Text(tr("Zmień talię Anki", "Change Anki deck", "Ankiのデッキを変更")) },
            text = {
                Column {
                    Text(
                        tr("Nowe fiszki będą dodawane do wybranej talii.", "New cards will be added to the selected deck.", "新しいカードは選んだデッキに追加されます。"),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    if (availableSettingsDecks.isNotEmpty()) {
                        Text(
                            tr("Istniejące talie:", "Existing decks:", "既存のデッキ:"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        availableSettingsDecks.forEach { deck ->
                            OutlinedButton(
                                onClick = { saveDeck(deck) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    deck,
                                    fontSize = 14.sp,
                                    fontWeight = if (deck == currentDeckName) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            tr("Lub utwórz nową talię:", "Or create a new deck:", "または新しいデッキを作成:"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = editedDeckName,
                        onValueChange = { editedDeckName = it },
                        label = { Text(tr("Nazwa talii", "Deck name", "デッキ名")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveDeck(editedDeckName) }) {
                    Text(tr("Zapisz", "Save", "保存"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeckEditDialog = false }) {
                    Text(tr("Anuluj", "Cancel", "キャンセル"))
                }
            }
        )
    }

    if (showRestartRequiredDialog) {
        // Non-dismissable: the database singleton is closed and any DAO
        // call from here on throws IllegalStateException. The only path
        // forward is killing the process so Hilt rebuilds the graph.
        AlertDialog(
            onDismissRequest = { /* no-op: must restart */ },
            title = { Text(tr("Wymagany restart", "Restart required", "再起動が必要です")) },
            text = {
                Text(
                    tr(
                        "Kopia została przywrócona. Aplikacja musi zostać uruchomiona ponownie, aby załadować przywrócone dane.",
                        "Backup restored. The app must restart to load the restored data.", "バックアップを復元しました。復元したデータを読み込むにはアプリの再起動が必要です。"),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // finishAffinity drops the back stack; exitProcess
                    // tears down the JVM so the next launch starts a clean
                    // Hilt graph rather than reusing the closed DB handle.
                    (context as? Activity)?.finishAffinity()
                    kotlin.system.exitProcess(0)
                }) {
                    Text(tr("Uruchom ponownie", "Restart now", "今すぐ再起動"))
                }
            }
        )
    }

    if (showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text(tr("Język nauki", "Study language", "学習する言語")) },
            text = {
                Column {
                    AppLanguage.entries.forEach { language ->
                        val label = when (language) {
                            AppLanguage.JAPANESE -> tr("🇯🇵  Japoński", "🇯🇵  Japanese", "🇯🇵  日本語")
                            AppLanguage.ENGLISH -> tr("🇬🇧  Angielski → polski", "🇬🇧  English → Polish", "🇬🇧  英語 → ポーランド語")
                            AppLanguage.SPANISH -> tr("🇪🇸  Hiszpański → angielski", "🇪🇸  Spanish → English", "🇪🇸  スペイン語 → 英語")
                        }
                        TextButton(
                            onClick = {
                                showLanguagePicker = false
                                // Picking the language already in use is a
                                // no-op, not a pointless restart.
                                if (language != viewModel.studyLanguage) {
                                    pendingStudyLanguage = language
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                label,
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = if (language == viewModel.studyLanguage) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguagePicker = false }) {
                    Text(tr("Anuluj", "Cancel", "キャンセル"))
                }
            }
        )
    }

    val pendingLanguageTarget = pendingStudyLanguage
    if (pendingLanguageTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingStudyLanguage = null },
            title = { Text(tr("Zmienić język nauki?", "Change study language?", "学習する言語を変更しますか？")) },
            text = {
                Text(
                    tr(
                        "Aplikacja zostanie CAŁKOWICIE zamknięta i musisz uruchomić ją " +
                            "ponownie ręcznie.\n\n" +
                            "Twoje słowniki, fiszki i ustawienia pozostaną nienaruszone — " +
                            "słowniki drugiego języka po prostu przestaną pojawiać się " +
                            "w wynikach, dopóki nie wrócisz. Jeśli nie masz jeszcze " +
                            "słowników dla nowego języka, po restarcie pobierzesz je " +
                            "w sekcji Słowniki.",
                        "The app will be shut down COMPLETELY and you will have to " +
                            "launch it again yourself.\n\n" +
                            "Your dictionaries, cards and settings are left untouched - " +
                            "the other language's dictionaries simply stop appearing in " +
                            "results until you switch back. If you have no dictionaries " +
                            "for the new language yet, download them under Dictionaries " +
                            "after the restart.", "アプリは完全に終了し、ご自分でもう一度起動する必要があります。\n\n辞書、カード、設定はそのままです — もう一方の言語の辞書が、戻すまで検索結果に出なくなるだけです。新しい言語の辞書をまだ持っていない場合は、再起動後に「辞書」からダウンロードしてください。"
                    ),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        viewModel.setStudyLanguage(pendingLanguageTarget)
                        // Same teardown as the post-restore restart: drop the
                        // back stack, then end the process so nothing that
                        // captured the previous language survives.
                        (context as? Activity)?.finishAffinity()
                        kotlin.system.exitProcess(0)
                    }
                }) {
                    Text(tr("Zmień i zamknij", "Change and close", "変更して閉じる"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStudyLanguage = null }) {
                    Text(tr("Anuluj", "Cancel", "キャンセル"))
                }
            }
        )
    }

    if (showRestoreDialog && selectedBackupForRestore != null) {
        val backup = selectedBackupForRestore
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(tr("Przywróć kopię zapasową", "Restore backup", "バックアップを復元")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Czy na pewno chcesz przywrócić tę kopię zapasową?\n${backup?.name ?: ""}\n\nAktualne dane zostaną zastąpione.",
                            "Are you sure you want to restore this backup?\n${backup?.name ?: ""}\n\nCurrent data will be replaced.", "このバックアップを本当に復元しますか？\n${backup?.name ?: ""}\n\n現在のデータは置き換えられます。"
                        ),
                        fontSize = 14.sp
                    )
                    Text(
                        tr("Aplikacja musi być zrestartowana po przywróceniu.", "App must be restarted after restore.", "復元後はアプリの再起動が必要です。"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedBackupForRestore != null) {
                        viewModel.restoreBackup(selectedBackupForRestore!!)
                    }
                }, enabled = !isRestoring) {
                    Text(tr("Przywróć", "Restore", "復元"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }, enabled = !isRestoring) {
                    Text(tr("Anuluj", "Cancel", "キャンセル"))
                }
            }
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text(tr("O aplikacji i licencje", "About app and licenses", "アプリについて・ライセンス")) },
            text = {
                LazyColumn {
                    item {
                        Text(
                            tr(
                                "Wersja aplikacji: ${com.yomitanmobile.BuildConfig.VERSION_NAME}",
                                "App version: ${com.yomitanmobile.BuildConfig.VERSION_NAME}", "アプリのバージョン: ${com.yomitanmobile.BuildConfig.VERSION_NAME}"),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Aplikacja korzysta z otwartych słowników do działania. Dostępne słowniki m.in. JMdict oraz KANJIDIC są udostępniane na licencjach Creative Commons Attribution-ShareAlike 4.0 International lub podobnych.\n\nWłasność i prawa autorskie:",
                                "The app uses open dictionaries. Available dictionaries including JMdict and KANJIDIC are shared under Creative Commons Attribution-ShareAlike 4.0 International licenses or similar.\n\nOwnership and copyrights:", "このアプリはオープンな辞書を使用しています。JMdictやKANJIDICをはじめとする利用可能な辞書は、クリエイティブ・コモンズ 表示-継承 4.0 国際ライセンスまたは同等のライセンスで公開されています。\n\n権利と著作権:"),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("JMdict/Kanjidic (EDRDG - Electronic Dictionary Research and Development Group)")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Poziomy CEFR (jeśli pobrane): The CEFR-J Wordlist Version 1.5, Yukio Tono, Tokyo University of Foreign Studies; Octanove Vocabulary Profile C1/C2, CC BY-SA 4.0.",
                                "CEFR levels (if downloaded): The CEFR-J Wordlist Version 1.5, Yukio Tono, Tokyo University of Foreign Studies; Octanove Vocabulary Profile C1/C2, CC BY-SA 4.0.", "CEFRレベル（ダウンロードした場合）: The CEFR-J Wordlist Version 1.5、投野由紀夫、東京外国語大学; Octanove Vocabulary Profile C1/C2、CC BY-SA 4.0。")
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Częstość słów angielskich i hiszpańskich (jeśli pobrana): wordfreq (Robyn Speer) oraz FrequencyWords (Hermit Dave, z OpenSubtitles 2018), CC BY-SA 4.0.",
                                "English and Spanish word frequency (if downloaded): wordfreq (Robyn Speer) and FrequencyWords (Hermit Dave, from OpenSubtitles 2018), CC BY-SA 4.0.", "英語・スペイン語の単語頻度（ダウンロードした場合）: wordfreq（Robyn Speer）およびFrequencyWords（Hermit Dave、OpenSubtitles 2018より）、CC BY-SA 4.0。")
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Nagrania native speakerów dla angielskiego i hiszpańskiego (jeśli włączone): ${com.yomitanmobile.data.audio.LinguaLibre.CREDIT}.",
                                "Native-speaker recordings for English and Spanish (if switched on): ${com.yomitanmobile.data.audio.LinguaLibre.CREDIT}.", "英語・スペイン語のネイティブ録音（オンにした場合）: ${com.yomitanmobile.data.audio.LinguaLibre.CREDIT}。")
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Nagrania native speakerów (jeśli pobrane): ${com.yomitanmobile.data.audio.KanjiAlive.CREDIT}.",
                                "Native-speaker recordings (if downloaded): ${com.yomitanmobile.data.audio.KanjiAlive.CREDIT}.", "ネイティブ録音（ダウンロードした場合）: ${com.yomitanmobile.data.audio.KanjiAlive.CREDIT}。")
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Głos wymowy (jeśli pobrany): ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.CREDIT}, silnik VOICEVOX CORE (MIT).",
                                "Pronunciation voice (if downloaded): ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.CREDIT}, VOICEVOX CORE engine (MIT).", "発音の音声（ダウンロードした場合）: ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.CREDIT}、VOICEVOX COREエンジン（MIT）。")
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "Tatoeba Project (CC-BY 2.0 FR) dla przykładowych zdań (jeśli zaimportowane).",
                                "Tatoeba Project (CC-BY 2.0 FR) for example sentences (if imported).", "例文はTatoeba Project（CC-BY 2.0 FR）による（インポートした場合）。")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text(tr("Zamknij", "Close", "閉じる")) }
            }
        )
    }

    if (showVoicevoxTerms) {
        AlertDialog(
            onDismissRequest = { showVoicevoxTerms = false },
            title = { Text(tr("Głos VOICEVOX — regulamin", "VOICEVOX voice — terms", "VOICEVOXの音声 — 利用規約")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Pobierane są modele głosów VOICEVOX i słownik wymowy Open JTalk, razem około " +
                                "${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MB, z GitHuba.\n\n" +
                                "Regulamin głosów pozwala używać nagrań do nauki, pod warunkiem podpisu: " +
                                "„VOICEVOX Nemo” (dowolny użytek) i „VOICEVOX:No.7” (tylko niekomercyjnie). " +
                                "Nagrania z fiszek zostają u ciebie — przy publikowaniu ich trzeba podać ten podpis.",
                            "This downloads the VOICEVOX voice models and the Open JTalk pronunciation dictionary, about " +
                                "${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MB, from GitHub.\n\n" +
                                "The voice terms allow the recordings for study, with credit: " +
                                "\"VOICEVOX Nemo\" (any use) and \"VOICEVOX:No.7\" (non-commercial only). " +
                                "Card recordings stay with you — publishing them requires that credit.", "VOICEVOXの音声モデルとOpen JTalkの発音辞書、合計約${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MBをGitHubからダウンロードします。\n\n音声の利用規約では、クレジット表記のうえで学習用に録音を使えます: 「VOICEVOX Nemo」（用途を問わず）と「VOICEVOX:No.7」（非商用のみ）。カードの録音は手元に残ります — 公開する場合はクレジット表記が必要です。"
                        ),
                        fontSize = 14.sp
                    )
                    TextButton(onClick = { uriHandler.openUri(com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TERMS_URL) }) {
                        Text(tr("Pełny regulamin (TERMS.txt)", "Full terms (TERMS.txt)", "規約の全文（TERMS.txt）"))
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showVoicevoxTerms = false
                    viewModel.installVoicevox()
                }) { Text(tr("Akceptuję i pobieram", "Accept and download", "同意してダウンロード")) }
            },
            dismissButton = {
                TextButton(onClick = { showVoicevoxTerms = false }) { Text(tr("Anuluj", "Cancel", "キャンセル")) }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text(tr("Polityka Prywatności", "Privacy Policy", "プライバシーポリシー")) },
            text = {
                LazyColumn {
                    item {
                        Text(
                            tr(
                                "1. Zakres danych\nYomitan Mobile nie wymaga konta i nie zbiera danych osobowych w centralnym backendzie.\nDomyślnie dane użytkownika (np. historia wyszukiwań, ustawienia, lista eksportów) są przechowywane lokalnie na urządzeniu.\n\n2. Kiedy aplikacja łączy się z internetem\nAplikacja może używać sieci w dwóch scenariuszach:\n1) Pobieranie słowników na życzenie użytkownika.\n2) Opcjonalne pobieranie zdań przykładowych z zewnętrznego API (tylko po wyrażeniu zgody).\n\n3. Opcjonalne API zdań\nFunkcja zdań online jest dobrowolna i domyślnie wyłączona.\nPo włączeniu aplikacja wysyła zapytanie zawierające szukane słowo do zewnętrznego API wyłącznie w celu pobrania przykładowego zdania.\nZgodę można w każdej chwili cofnąć w ustawieniach aplikacji.\n\n4. Przechowywanie lokalne\nDane tworzone przez aplikację (m.in. historia wyszukiwań, preferencje, metadane eksportu do Anki) są przechowywane lokalnie.\nUżytkownik może usunąć je przez wyczyszczenie danych aplikacji w ustawieniach systemu Android.\n\n5. Integracja z AnkiDroid\nEksport do AnkiDroid wykorzystuje oficjalne API AnkiDroid i lokalny mechanizm Content Provider.\n\n6. Kontakt\nW razie pytań lub wątpliwości dotyczących prywatności prosimy o otwarcie zgłoszenia (Issue) w repozytorium projektu.\n\nStan na dzień: 18 kwietnia 2026.",
                                "1. Data scope\nYomitan Mobile does not require an account and does not collect personal data in a central backend.\nBy default, user data (search history, settings, export list) is stored locally on the device.\n\n2. When the app connects to the internet\nThe app may use the network in two cases:\n1) Downloading dictionaries on user request.\n2) Optional fetching of example sentences from an external API (only with consent).\n\n3. Optional sentence API\nThe online sentence feature is optional and disabled by default.\nWhen enabled, the app sends the searched word to an external sentence API solely to fetch an example sentence.\nConsent can be revoked at any time in the app settings.\n\n4. Local storage\nData created by the app (search history, preferences, Anki export metadata) is stored locally.\nYou can delete it by clearing app data in Android system settings.\n\n5. AnkiDroid integration\nExport to AnkiDroid uses the official AnkiDroid API and a local Content Provider mechanism.\n\n6. Contact\nIf you have questions or privacy concerns, please open an Issue in the project repository.\n\nStatus as of: April 18, 2026.", "1. データの範囲\nYomitan Mobileはアカウントを必要とせず、中央のサーバーで個人データを収集しません。\n初期設定では、ユーザーのデータ（検索履歴、設定、エクスポートの一覧）は端末内に保存されます。\n\n2. アプリがインターネットに接続するとき\nアプリがネットワークを使うのは次の2つの場合です:\n1) ユーザーの要求による辞書のダウンロード。\n2) 外部APIからの例文の取得（同意した場合のみ、任意）。\n\n3. 任意の例文API\nオンライン例文機能は任意で、初期設定では無効です。\n有効にすると、例文を取得する目的に限り、検索した単語を外部の例文APIに送信します。\n同意はアプリの設定でいつでも取り消せます。\n\n4. 端末内の保存\nアプリが作成するデータ（検索履歴、設定、Ankiエクスポートの情報）は端末内に保存されます。\nAndroidの設定でアプリのデータを消去すると削除できます。\n\n5. AnkiDroidとの連携\nAnkiDroidへのエクスポートには、公式のAnkiDroid APIと端末内のContent Providerの仕組みを使います。\n\n6. お問い合わせ\nご質問やプライバシーに関する懸念がある場合は、プロジェクトのリポジトリでIssueを作成してください。\n\n2026年4月18日現在。"),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text(tr("Zamknij", "Close", "閉じる")) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Ustawienia", "Settings", "設定")) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = tr("Wróć", "Back", "戻る"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            // No gap and no outer padding: rows are flush and a hairline
            // separates them, so a gap here would break every rule in two.
            // Horizontal padding lives inside the rows instead.
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // ═══════════════════════════════════════
            // SECTION: Język nauki (Study language)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Translate,
                    title = tr("Język nauki", "Study language", "学習する言語"),
                    first = true
                )
            }

            item {
                val current = viewModel.studyLanguage
                SettingsClickableItem(
                    icon = Icons.Default.Translate,
                    title = when (current) {
                        AppLanguage.JAPANESE -> tr("Japoński", "Japanese", "日本語")
                        AppLanguage.ENGLISH -> tr("Angielski → polski", "English → Polish", "英語 → ポーランド語")
                        AppLanguage.SPANISH -> tr("Hiszpański → angielski", "Spanish → English", "スペイン語 → 英語")
                    },
                    subtitle = tr(
                        "Dotknij, aby zmienić. Wymaga restartu aplikacji.",
                        "Tap to change. Requires an app restart.", "タップして変更。アプリの再起動が必要です。"),
                    onClick = { showLanguagePicker = true }
                )
            }

            if (!viewModel.studyLanguage.hasJapaneseFeatures) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                when (viewModel.studyLanguage) {
                                    AppLanguage.SPANISH -> tr(
                                        "Czego nie ma w trybie hiszpańskim",
                                        "Not available in Spanish mode", "スペイン語モードでは利用できません")
                                    else -> tr(
                                        "Czego nie ma w trybie angielskim",
                                        "Not available in English mode", "英語モードでは利用できません")
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr(
                                    "• Ranking częstotliwości — dla angielskiego nie ma listy " +
                                        "w formacie Yomitan, więc słowa nie są sortowane od " +
                                        "najczęstszych słów.\n" +
                                        "• Generator talii JLPT, skaner napisów/EPUB i skan kolekcji Anki " +
                                        "— wyłączone; każde opiera się na czymś, czego angielski " +
                                        "nie ma (tagi JLPT, kolejność wg częstotliwości, " +
                                        "rozpoznawanie japońskich pól).\n" +
                                        "• Akcent tonalny, furigana i rozbiór kanji — zastąpione wymową IPA." +
                                        if (viewModel.studyLanguage == AppLanguage.ENGLISH) {
                                            "\n• Formy nieregularne (went, better) trzeba wpisać " +
                                                "w bezokoliczniku — słownik ich nie odmienia."
                                        } else {
                                            ""
                                        },
                                    "• Frequency ranking - no Yomitan-format list exists for English, " +
                                        "so results are not ordered by how common a word is.\n" +
                                        "• The JLPT deck generator, the subtitle/EPUB scanner and the Anki " +
                                        "collection scan are disabled - each rests on something " +
                                        "English has no source for (JLPT tags, frequency-based " +
                                        "study order, recognising Japanese fields).\n" +
                                        "• Pitch accent, furigana and kanji breakdown - replaced by IPA." +
                                        if (viewModel.studyLanguage == AppLanguage.ENGLISH) {
                                            "\n• Irregular forms (went, better) have to be typed as the " +
                                                "base word - the dictionary does not list them."
                                        } else {
                                            ""
                                        }, "• 頻度順位 — 英語にはYomitan形式のリストがないため、結果は単語のよく使われる順に並びません。\n• JLPTデッキの生成、字幕/EPUBスキャン、Ankiコレクションのスキャンは無効です — どれも英語には情報源のないもの（JLPTタグ、頻度に基づく学習順、日本語フィールドの判別）に依存しています。\n• ピッチアクセント、ふりがな、漢字の内訳 — IPAに置き換わります。${if (viewModel.studyLanguage == AppLanguage.ENGLISH) "\n• 不規則な形（went, better）は基本形で入力する必要があります — 辞書に載っていないためです。" else ""}"
                                ),
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            }

            // SECTION: Słowniki (Dictionaries)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.MenuBook,
                    title = tr("Słowniki", "Dictionaries", "辞書")
                )
            }

            item {
                Button(
                    onClick = onNavigateToDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Pobierz słowniki z internetu", "Download dictionaries from the internet", "インターネットから辞書をダウンロード"))
                }
            }

            item {
                OutlinedButton(
                    onClick = { filePickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    enabled = !isImporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Importuj słownik Yomitan (.zip)", "Import Yomitan dictionary (.zip)", "Yomitan辞書（.zip）をインポート"))
                }
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.MenuBook,
                    title = tr("Zainstalowane słowniki", "Installed dictionaries", "インストール済みの辞書"),
                    subtitle = tr("Przeglądaj i zarządzaj słownikami", "Browse and manage dictionaries", "辞書の一覧と管理"),
                    onClick = onNavigateToDictionaries
                )
            }

            if (isImporting) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(tr("Importowanie słownika...", "Importing dictionary...", "辞書をインポート中…"))
                            }
                            importProgress?.let { progress ->
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(progress = progress.progressPercent, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    tr(
                                        "Plik ${progress.filesProcessed}/${progress.totalFiles} • ${progress.entriesProcessed} wpisów",
                                        "File ${progress.filesProcessed}/${progress.totalFiles} • ${progress.entriesProcessed} entries", "ファイル ${progress.filesProcessed}/${progress.totalFiles} • ${progress.entriesProcessed}件"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════
            // SECTION: Wygląd (Appearance)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Palette,
                    title = tr("Wygląd", "Appearance", "外観")
                )
            }

            // Theme mode toggle
            item {
                SettingsBlock {
                    Column {
                        Text(
                            tr("Motyw", "Theme", "テーマ"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = currentThemeMode == "system",
                                onClick = {
                                    currentThemeMode = "system"
                                    coroutineScope.launch {
                                        context.dataStore.edit { it[PreferenceKeys.THEME_MODE] = "system" }
                                    }
                                },
                                label = { Text(tr("Systemowy", "System", "システム")) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = currentThemeMode == "light",
                                onClick = {
                                    currentThemeMode = "light"
                                    coroutineScope.launch {
                                        context.dataStore.edit { it[PreferenceKeys.THEME_MODE] = "light" }
                                    }
                                },
                                label = { Text(tr("Jasny", "Light", "ライト")) },
                                leadingIcon = if (currentThemeMode == "light") null else {
                                    { Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = currentThemeMode == "dark",
                                onClick = {
                                    currentThemeMode = "dark"
                                    coroutineScope.launch {
                                        context.dataStore.edit { it[PreferenceKeys.THEME_MODE] = "dark" }
                                    }
                                },
                                label = { Text(tr("Ciemny", "Dark", "ダーク")) },
                                leadingIcon = if (currentThemeMode == "dark") null else {
                                    { Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Card style button
            item {
                SettingsClickableItem(
                    icon = Icons.Default.Style,
                    title = tr("Wygląd fiszki Anki", "Anki card style", "Ankiカードのスタイル"),
                    subtitle = tr("Czcionka, rozmiar, kolory, podgląd", "Font, size, colors, preview", "フォント、サイズ、色、プレビュー"),
                    onClick = onNavigateToCardStyle
                )
            }

            // Frequency display button
            item {
                SettingsClickableItem(
                    icon = Icons.Default.BarChart,
                    title = tr("Wyświetlanie częstotliwości", "Frequency display", "頻度の表示"),
                    subtitle = tr("Kolejność list, pokaż wszystkie", "List order, show all", "リストの順番、すべて表示"),
                    onClick = onNavigateToFrequencyDisplay
                )
            }

            // Pronunciation archive
            item {
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("Archiwum wymowy", "Pronunciation archive", "発音アーカイブ"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when {
                                        audioArchiveIndexing -> tr(
                                            "Indeksowanie folderu…",
                                            "Indexing the folder…", "フォルダーを索引に登録中…")
                                        audioArchiveFiles > 0 -> tr(
                                            "$audioArchiveFiles nagrań" +
                                                (audioArchiveLabel?.let { " — $it" } ?: ""),
                                            "$audioArchiveFiles recordings" +
                                                (audioArchiveLabel?.let { " — $it" } ?: ""), "録音${audioArchiveFiles}件${audioArchiveLabel?.let { " — $it" } ?: ""}"
                                        )
                                        else -> tr(
                                            "Brak. Fiszki mówią głosem syntezatora, który czyta hasło bez kontekstu i gubi akcent.",
                                            "None. Cards speak with the synthesiser, which reads a headword without context and drops the accent.", "なし。カードは合成音声で読み上げます。合成音声は見出し語を文脈なしで読み、アクセントも失われます。")
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { audioArchivePicker.launch(null) },
                                enabled = !audioArchiveIndexing
                            ) {
                                Text(
                                    if (audioArchiveFiles > 0) tr("Zmień folder", "Change folder", "フォルダーを変更")
                                    else tr("Wskaż folder", "Pick a folder", "フォルダーを選ぶ")
                                )
                            }
                            if (audioArchiveFiles > 0) {
                                OutlinedButton(
                                    onClick = viewModel::forgetAudioArchive,
                                    enabled = !audioArchiveIndexing
                                ) {
                                    Text(tr("Usuń", "Remove", "削除"))
                                }
                            }
                        }
                    }
                }
            }

            // Native-speaker recordings — before VOICEVOX on screen because
            // they come before it in AudioArchive.find. Kanji alive for
            // Japanese; Lingua Libre for English and Spanish, below.
            if (viewModel.studyLanguage == AppLanguage.JAPANESE) item {
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("Nagrania native speakerów", "Native-speaker recordings", "ネイティブの録音"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when (val state = nativeAudioState) {
                                        is com.yomitanmobile.data.audio.NativeAudioPack.State.Installed -> tr(
                                            "Zainstalowane. Ponad 10 000 słów czytanych przez Japończyków — " +
                                                "używane wszędzie przed głosem syntetycznym: przy odsłuchu, " +
                                                "na nowych fiszkach i w generatorach talii.",
                                            "Installed. Over 10,000 words read by native Japanese speakers — " +
                                                "used everywhere before a synthesised voice: playback, new cards " +
                                                "and the deck generators.", "インストール済み。ネイティブの日本人が読んだ1万語以上 — 合成音声より先に、再生、新しいカード、デッキ生成のすべてで使われます。"
                                        )
                                        is com.yomitanmobile.data.audio.NativeAudioPack.State.Downloading -> tr(
                                            "Pobieranie… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB",
                                            "Downloading… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB", "ダウンロード中… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB")
                                        is com.yomitanmobile.data.audio.NativeAudioPack.State.Failed -> tr(
                                            "Pobieranie nie powiodło się: ${state.message}",
                                            "Download failed: ${state.message}", "ダウンロードに失敗しました: ${state.message}")
                                        else -> tr(
                                            "Ponad 10 000 słów czytanych przez Japończyków (kobiety i mężczyźni), " +
                                                "na wolnej licencji. Słowo, którego nie ma w nagraniach, dalej " +
                                                "mówi VOICEVOX lub systemowy TTS. Pobranie ok. " +
                                                "${com.yomitanmobile.data.audio.KanjiAlive.AUDIO_ZIP_BYTES / 1_000_000} MB.",
                                            "Over 10,000 words read by native Japanese speakers (women and men), " +
                                                "freely licensed. A word they do not cover is still said by " +
                                                "VOICEVOX or the system TTS. A download of about " +
                                                "${com.yomitanmobile.data.audio.KanjiAlive.AUDIO_ZIP_BYTES / 1_000_000} MB.", "ネイティブの日本人（女性と男性）が読んだ1万語以上、自由なライセンス。録音のない単語は引き続きVOICEVOXかシステムの読み上げで発音します。ダウンロードは約${com.yomitanmobile.data.audio.KanjiAlive.AUDIO_ZIP_BYTES / 1_000_000} MB。"
                                        )
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val nativeDownloading = nativeAudioState as? com.yomitanmobile.data.audio.NativeAudioPack.State.Downloading
                        if (nativeDownloading != null) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = (nativeDownloading.done.toFloat() / nativeDownloading.total.coerceAtLeast(1)).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (nativeAudioState) {
                                is com.yomitanmobile.data.audio.NativeAudioPack.State.Installed -> {
                                    Button(onClick = viewModel::previewNativeAudio) {
                                        Text(tr("Odsłuchaj", "Listen", "聞く"))
                                    }
                                    OutlinedButton(onClick = viewModel::uninstallNativeAudio) {
                                        Text(tr("Usuń", "Remove", "削除"))
                                    }
                                }
                                is com.yomitanmobile.data.audio.NativeAudioPack.State.Downloading -> Unit
                                else -> Button(onClick = viewModel::installNativeAudio) {
                                    Text(
                                        if (nativeAudioState is com.yomitanmobile.data.audio.NativeAudioPack.State.Failed)
                                            tr("Spróbuj ponownie", "Try again", "再試行")
                                        else tr("Pobierz nagrania", "Download the recordings", "録音をダウンロード")
                                    )
                                }
                            }
                        }
                        Text(
                            com.yomitanmobile.data.audio.KanjiAlive.CREDIT,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (linguaLibreState !is com.yomitanmobile.data.audio.LinguaLibreAudio.State.NotOffered) item {
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("Nagrania native speakerów", "Native-speaker recordings", "ネイティブの録音"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when (val state = linguaLibreState) {
                                        is com.yomitanmobile.data.audio.LinguaLibreAudio.State.Installed -> tr(
                                            "Włączone. Słowo nagrane przez native speakera odtwarza się i trafia na fiszkę " +
                                                "przed głosem syntetycznym. Każde nagranie pobiera się raz, przy pierwszym " +
                                                "użyciu słowa — potrzebny jest wtedy internet.",
                                            "On. A word a native speaker recorded is played and put on the card before " +
                                                "any synthesised voice. Each recording is fetched once, the first time the " +
                                                "word is used — that needs the internet.", "オン。ネイティブが録音した単語は、合成音声より先に再生され、カードに入ります。録音は単語を初めて使うときに一度だけ取得します — そのときインターネットが必要です。"
                                        )
                                        is com.yomitanmobile.data.audio.LinguaLibreAudio.State.Installing -> tr(
                                            "Pobieranie spisu nagrań…",
                                            "Downloading the list of recordings…", "録音の一覧をダウンロード中…")
                                        is com.yomitanmobile.data.audio.LinguaLibreAudio.State.Failed -> tr(
                                            "Nie udało się pobrać spisu: ${state.message}",
                                            "Could not download the list: ${state.message}", "一覧をダウンロードできませんでした: ${state.message}")
                                        else -> tr(
                                            "Słowa czytane przez native speakerów z projektu Lingua Libre: dla angielskiego " +
                                                "98% z tysiąca najczęstszych słów. Włączenie pobiera sam spis (kilka MB); " +
                                                "nagranie ściąga się przy pierwszym użyciu słowa i zostaje w telefonie.",
                                            "Words read by native speakers, from Lingua Libre: for English, 98% of the " +
                                                "thousand commonest words. Switching on downloads the list only (a few MB); " +
                                                "a recording is fetched the first time its word is used and kept.", "Lingua Libreのネイティブによる単語の録音: 英語ではよく使う1000語の98%。オンにすると一覧（数MB）だけをダウンロードし、録音は単語を初めて使うときに取得して保存します。"
                                        )
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (linguaLibreState is com.yomitanmobile.data.audio.LinguaLibreAudio.State.Installing) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (linguaLibreState) {
                                is com.yomitanmobile.data.audio.LinguaLibreAudio.State.Installed -> {
                                    Button(onClick = viewModel::previewLinguaLibre) {
                                        Text(tr("Odsłuchaj", "Listen", "聞く"))
                                    }
                                    OutlinedButton(onClick = viewModel::uninstallLinguaLibre) {
                                        Text(tr("Wyłącz", "Switch off", "オフにする"))
                                    }
                                }
                                is com.yomitanmobile.data.audio.LinguaLibreAudio.State.Installing -> Unit
                                else -> Button(onClick = viewModel::installLinguaLibre) {
                                    Text(
                                        if (linguaLibreState is com.yomitanmobile.data.audio.LinguaLibreAudio.State.Failed)
                                            tr("Spróbuj ponownie", "Try again", "再試行")
                                        else tr("Włącz nagrania", "Switch on", "オンにする")
                                    )
                                }
                            }
                        }
                        Text(
                            com.yomitanmobile.data.audio.LinguaLibre.CREDIT,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // VOICEVOX voice
            item {
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("Głos VOICEVOX", "VOICEVOX voice", "VOICEVOXの音声"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when (val state = voicevoxState) {
                                        is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Installed -> tr(
                                            "Neuronowy głos japoński, offline. Mówi czytanie z akcentem z karty. " +
                                                "Używany dla słów bez nagrania native speakera, przed systemowym TTS.",
                                            "Neural Japanese voice, offline. Says the reading with the card's accent. " +
                                                "Used for words no native speaker recorded, before the system TTS.", "ニューラルの日本語音声、オフライン。カードのアクセントで読みを発音します。ネイティブの録音がない単語に、システムの読み上げより先に使います。"
                                        )
                                        is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Downloading -> tr(
                                            "Pobieranie… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB",
                                            "Downloading… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB", "ダウンロード中… ${state.done / 1_000_000} / ${state.total / 1_000_000} MB")
                                        is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Failed -> tr(
                                            "Pobieranie nie powiodło się: ${state.message}",
                                            "Download failed: ${state.message}", "ダウンロードに失敗しました: ${state.message}")
                                        else -> tr(
                                            "Dużo lepszy od systemowego TTS: neuronowy, offline, z akcentem z karty. " +
                                                "Wymaga pobrania ok. ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MB.",
                                            "Far better than the system TTS: neural, offline, with the card's accent. " +
                                                "Needs a download of about ${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MB.", "システムの読み上げよりはるかに良い: ニューラル、オフライン、カードのアクセント付き。約${com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.TOTAL_BYTES / 1_000_000} MBのダウンロードが必要です。"
                                        )
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (voicevoxState is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Installed) {
                                androidx.compose.material3.Switch(
                                    checked = voicevoxEnabled,
                                    onCheckedChange = viewModel::setVoicevoxEnabled
                                )
                            }
                        }
                        val downloading = voicevoxState as? com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Downloading
                        if (downloading != null) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = (downloading.done.toFloat() / downloading.total.coerceAtLeast(1)).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (voicevoxState) {
                                is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Installed -> {
                                    Button(onClick = viewModel::previewVoicevox, enabled = voicevoxEnabled) {
                                        Text(tr("Odsłuchaj", "Listen", "聞く"))
                                    }
                                    OutlinedButton(onClick = viewModel::uninstallVoicevox) {
                                        Text(tr("Usuń", "Remove", "削除"))
                                    }
                                }
                                is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Downloading -> Unit
                                else -> Button(onClick = { showVoicevoxTerms = true }) {
                                    Text(
                                        if (voicevoxState is com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State.Failed)
                                            tr("Spróbuj ponownie", "Try again", "再試行")
                                        else tr("Pobierz głos", "Download the voice", "音声をダウンロード")
                                    )
                                }
                            }
                        }
                        Text(
                            com.yomitanmobile.data.audio.voicevox.VoicevoxAssets.CREDIT,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ═══════════════════════════════════════
            // SECTION: Anki
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Style,
                    title = "Anki"
                )
            }

            // Anki deck setting
            item {
                SettingsBlock {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Style,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                tr("Talia Anki", "Anki deck", "Ankiのデッキ"),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (currentDeckName.isNotBlank()) currentDeckName
                                else tr("Nie wybrano (zostaniesz zapytany przy eksporcie)", "Not selected (you will be asked during export)", "未選択（エクスポート時に確認します）"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showDeckEditDialog = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = tr("Zmień talię", "Change deck", "デッキを変更"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Card engine: which language the Meaning field is written in.
            //
            // Japanese only. Outside it the Meaning field already follows a
            // fixed rule — the bilingual dictionary if it has the word, its
            // monolingual companion if not — so the JP-EN / JP-JP choice has
            // nothing to switch and setting it would appear to do something.
            if (viewModel.studyLanguage.hasJapaneseFeatures) {
            item {
                SettingsBlock {
                    Column {
                        Text(
                            tr("Silnik fiszek", "Card engine", "カードの作り方"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            tr(
                                "Język pola „Znaczenie”. W trybie JP-JP definicja pochodzi z pierwszego " +
                                    "słownika japońsko-japońskiego z listy, który ma dane słowo, a zdania " +
                                    "z Jitendex zostają bez angielskiego tłumaczenia. Gdy słowa nie ma w " +
                                    "żadnym, zostaje definicja angielska. Karty z definicją JP-JP dostają " +
                                    "w Anki tag J-J.",
                                "Language of the Meaning field. In JP-JP mode the definition comes from the " +
                                    "first Japanese-Japanese dictionary on the list that has the word, and " +
                                    "Jitendex sentences keep their Japanese only, without the English " +
                                    "translation. Words none of them has keep their English definition. " +
                                    "Cards with a JP-JP definition get the J-J tag in Anki.", "「意味」フィールドの言語。JP-JPモードでは、その単語がある国語辞典のうち、リストの最初のものから定義を取り、Jitendexの例文は英訳なしの日本語だけになります。どの辞書にもない単語は英語の定義のままです。JP-JPの定義を持つカードにはAnkiでJ-Jタグが付きます。"
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = cardMeaningLanguage == CardMeaningLanguage.ENGLISH,
                                onClick = {
                                    cardMeaningLanguage = CardMeaningLanguage.ENGLISH
                                    coroutineScope.launch {
                                        context.dataStore.edit {
                                            it[PreferenceKeys.CARD_MEANING_LANGUAGE] =
                                                CardMeaningLanguage.ENGLISH.storageValue
                                        }
                                    }
                                },
                                label = { Text("JP → EN") }
                            )
                            FilterChip(
                                selected = cardMeaningLanguage == CardMeaningLanguage.JAPANESE,
                                onClick = {
                                    cardMeaningLanguage = CardMeaningLanguage.JAPANESE
                                    coroutineScope.launch {
                                        context.dataStore.edit {
                                            it[PreferenceKeys.CARD_MEANING_LANGUAGE] =
                                                CardMeaningLanguage.JAPANESE.storageValue
                                        }
                                    }
                                },
                                label = { Text("JP → JP") }
                            )
                        }
                        if (cardMeaningLanguage == CardMeaningLanguage.JAPANESE) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                tr(
                                    "Słowniki japońsko-japońskie, w kolejności",
                                    "Japanese-Japanese dictionaries, in order", "国語辞典（順番どおりに使用）"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            monolingualDictionaries.forEachIndexed { index, name ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${index + 1}. $name",
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            saveMonolingualDictionaries(
                                                monolingualDictionaries.toMutableList().apply {
                                                    add(index - 1, removeAt(index))
                                                }
                                            )
                                        },
                                        enabled = index > 0
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            contentDescription = tr("Wyżej", "Move up", "上へ")
                                        )
                                    }
                                    IconButton(
                                        onClick = { saveMonolingualDictionaries(monolingualDictionaries - name) }
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = tr("Usuń z listy", "Remove from the list", "リストから外す")
                                        )
                                    }
                                }
                            }
                            if (monolingualDictionaries.isNotEmpty()) {
                                Text(
                                    tr(
                                        "${monolingualDictionaries.size + 1}. angielski (Jitendex)",
                                        "${monolingualDictionaries.size + 1}. English (Jitendex)", "${monolingualDictionaries.size + 1}. 英語（Jitendex）"),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            OutlinedButton(
                                onClick = { showMonolingualPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Dodaj lub usuń słownik…", "Add or remove a dictionary…", "辞書を追加・削除…"))
                            }
                            if (monolingualDictionaries.isEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    tr(
                                        "Bez wybranego słownika tryb JP-JP nic nie zmienia. Zainstaluj np. " +
                                            "„日本語 Wiktionary” z ekranu pobierania albo zaimportuj własny " +
                                            "słownik (三省堂, 明鏡…) z pliku.",
                                        "With no dictionary chosen JP-JP mode changes nothing. Install e.g. " +
                                            "“日本語 Wiktionary” from the download screen, or import your own " +
                                            "dictionary (三省堂, 明鏡…) from a file.", "辞書を選ばないとJP-JPモードは何も変えません。ダウンロード画面から「日本語 Wiktionary」などをインストールするか、自分の辞書（三省堂、明鏡…）をファイルからインポートしてください。"
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            }

            // The JLPT generator, the text scanner and the collection scan
            // used to be three rows here. They are the work, not settings, and
            // now live in the Tools tab of the bottom bar.

            // ═══════════════════════════════════════
            // SECTION: Statystyki i cele (Stats & goals)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.BarChart,
                    title = tr("Statystyki i cele", "Stats & goals", "統計と目標")
                )
            }

            // Daily goal setting
            item {
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.EmojiEvents,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    tr("Cel dzienny fiszek", "Daily card goal", "1日のカード目標"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (dailyGoalCount.toInt() == 0) tr("Wyłączony", "Disabled", "無効")
                                    else tr("${dailyGoalCount.toInt()} fiszek dziennie", "${dailyGoalCount.toInt()} cards/day", "1日${dailyGoalCount.toInt()}枚"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (dailyGoalCount.toInt() == 0) tr("Wyłączony", "Disabled", "無効") else "${dailyGoalCount.toInt()}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("50", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = dailyGoalCount,
                            onValueChange = { dailyGoalCount = it },
                            onValueChangeFinished = {
                                coroutineScope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[PreferenceKeys.DAILY_GOAL_COUNT] = dailyGoalCount.toInt()
                                    }
                                }
                            },
                            valueRange = 0f..50f,
                            steps = 49
                        )
                        Text(
                            tr("Ustaw na 0 aby wyłączyć cel dzienny", "Set to 0 to disable daily goal", "0にすると1日の目標が無効になります"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Reclassify pass. Walks every ExportedWord row and re-runs
            // WordCategoryClassifier with the current rules. Manual user
            // overrides are preserved; rows whose source dictionary is
            // gone are silently skipped. Run lock = isReclassifying.
            item {
                val isReclassifying by viewModel.isReclassifying.collectAsState()
                SettingsClickableItem(
                    icon = Icons.Default.List,
                    title = tr("Przelicz kategorie", "Recompute categories", "カテゴリーを再計算"),
                    subtitle = if (isReclassifying) {
                        tr("Przeliczanie…", "Recomputing…", "再計算中…")
                    } else {
                        tr(
                            "Przepisz kategorie wyeksportowanych słów po zmianie reguł",
                            "Rewrite categories of exported words after rule changes", "ルールの変更後、エクスポートした単語のカテゴリーを書き直します")
                    },
                    onClick = {
                        if (!isReclassifying) viewModel.reclassifyCategories()
                    }
                )
            }

            // ═══════════════════════════════════════
            // SECTION: Aplikacja (App)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Language,
                    title = tr("Aplikacja", "App", "アプリ")
                )
            }

            // Language selector
            item {
                SettingsBlock {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    tr("Język aplikacji", "App language", "アプリの言語"),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when (currentLanguage) {
                                        "pl" -> "Polski"
                                        "en" -> "English"
                                        "ja" -> "日本語"
                                        else -> tr("Systemowy", "System", "システム")
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Each language named in itself, so whoever cannot read
                            // the current one can still find their own.
                            listOf(
                                "system" to tr("Systemowy", "System", "システム"),
                                "pl" to "Polski",
                                "en" to "English",
                                "ja" to "日本語"
                            ).forEach { (code, label) ->
                                FilterChip(
                                    selected = currentLanguage == code,
                                    onClick = {
                                        if (currentLanguage != code) {
                                            currentLanguage = code
                                            val langPrefs = context.getSharedPreferences(
                                                MainActivity.LANG_PREFS_NAME,
                                                android.content.Context.MODE_PRIVATE
                                            )
                                            langPrefs.edit()
                                                .putString(MainActivity.LANG_PREFS_KEY, code)
                                                .apply()
                                            com.yomitanmobile.util.LocaleHelper.chosen =
                                                com.yomitanmobile.util.UiLanguage.fromStorage(code)
                                            (context as? Activity)?.recreate()
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════
            // SECTION: Kopia zapasowa (Backup)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.CloudDownload,
                    title = tr("Kopia zapasowa", "Backup & Restore", "バックアップと復元")
                )
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.CloudDownload,
                    title = tr("Kopie zapasowe", "Backups", "バックアップ"),
                    subtitle = tr(
                        "Utwórz, przywróć lub zaimportuj ustawienia z pliku",
                        "Create, restore, or import settings from a file", "バックアップの作成・復元、ファイルからの設定のインポート"),
                    onClick = onNavigateToBackup
                )
            }

            // ═══════════════════════════════════════
            // SECTION: Prywatność i integracje (Privacy & integrations)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Shield,
                    title = tr("Prywatność i integracje", "Privacy & integrations", "プライバシーと連携")
                )
            }

            item {
                SettingsClickableItem(
                    icon = Icons.Default.Policy,
                    title = tr("Polityka Prywatności", "Privacy Policy", "プライバシーポリシー"),
                    subtitle = tr("Zasady prywatności i lokalne przetwarzanie danych", "Privacy rules and local data processing", "プライバシーの規則と端末内でのデータ処理"),
                    onClick = { showPrivacyDialog = true }
                )
            }

            // ═══════════════════════════════════════
            // SECTION: Informacje (About)
            // ═══════════════════════════════════════
            item {
                SectionHeader(
                    icon = Icons.Default.Info,
                    title = tr("Informacje i licencje", "Information & licenses", "情報とライセンス")
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SettingsClickableItem(
                    icon = Icons.Default.MenuBook,
                    title = tr("Licencje słowników", "Dictionary licenses", "辞書のライセンス"),
                    subtitle = tr("Informacje o otwartych danych i prawach autorskich", "Open data and copyright information", "オープンデータと著作権の情報"),
                    onClick = { showLicensesDialog = true }
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/**
 * The section break and the tappable row both live in `ui/common` now — the
 * card style screen and the deck generators draw the same list.
 */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    first: Boolean = false
) = SettingsSectionHeader(icon = icon, title = title, first = first)

/**
 * A setting that needs room — chips, a slider, a pair of buttons.
 *
 * Same gutter and same hairline as [SettingsRow], so a compound setting reads
 * as one more entry in the list instead of a panel dropped into it.
 */
@Composable
private fun SettingsBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            content = content
        )
        SettingsDivider(inset = false)
    }
}

@Composable
private fun SettingsClickableItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) = SettingsRow(icon = icon, title = title, subtitle = subtitle, onClick = onClick)
