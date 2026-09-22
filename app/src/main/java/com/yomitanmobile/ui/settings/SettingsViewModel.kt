package com.yomitanmobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.backup.BackupManager
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.domain.model.ImportProgress
import com.yomitanmobile.domain.model.ImportResult
import com.yomitanmobile.domain.usecase.DeleteDictionaryUseCase
import com.yomitanmobile.domain.usecase.GetDictionariesUseCase
import com.yomitanmobile.domain.usecase.ImportDictionaryUseCase
import com.yomitanmobile.util.WordCategoryClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import javax.inject.Inject

sealed class SettingsEvent {
    data class BackupSuccess(val backupPath: String) : SettingsEvent()
    data class BackupError(val message: String) : SettingsEvent()
    object RestoreSuccess : SettingsEvent()
    data class RestoreError(val message: String) : SettingsEvent()
    /** Standalone settings.json import — [applied] = number of entries written. */
    data class SettingsImported(val applied: Int) : SettingsEvent()
    data class SettingsImportError(val message: String) : SettingsEvent()
    data class ImportSuccess(val result: ImportResult) : SettingsEvent()
    data class ImportError(val message: String) : SettingsEvent()
    /**
     * Result of "Recompute categories" — surfaced as a snackbar with
     * per-bucket counts (updated / preserved-by-manual-override / skipped
     * because source dictionary is gone).
     */
    data class ReclassifyDone(
        val updated: Int,
        val skippedManual: Int,
        val skippedMissing: Int
    ) : SettingsEvent()
    data class ReclassifyError(val message: String) : SettingsEvent()
    /** The pronunciation archive was walked: [files] distinct recordings. */
    data class AudioArchiveIndexed(val files: Int) : SettingsEvent()
    /** The picked folder held no readable audio; the old index was kept. */
    object AudioArchiveEmpty : SettingsEvent()
}

data class MinedCategoryStat(
    val code: String,
    val label: String,
    val count: Int
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val importDictionaryUseCase: ImportDictionaryUseCase,
    private val deleteDictionaryUseCase: DeleteDictionaryUseCase,
    private val backupManager: BackupManager,
    private val reclassifyCategoriesUseCase: com.yomitanmobile.domain.usecase.ReclassifyCategoriesUseCase,
    private val ankiCardCreator: com.yomitanmobile.data.anki.AnkiCardCreator,
    getDictionariesUseCase: GetDictionariesUseCase,
    exportedWordDao: ExportedWordDao,
    private val languageSettings: com.yomitanmobile.data.settings.LanguageSettings,
    private val audioArchive: com.yomitanmobile.data.audio.AudioArchive,
    private val voicevox: com.yomitanmobile.data.audio.voicevox.VoicevoxVoice,
    private val nativeAudio: com.yomitanmobile.data.audio.NativeAudioPack,
    private val linguaLibre: com.yomitanmobile.data.audio.LinguaLibreAudio,
    private val audioPlayer: com.yomitanmobile.data.audio.AudioPlayer
) : ViewModel() {

    // ── Lingua Libre (English, Spanish) ──────────────────────────────────

    val linguaLibreState: StateFlow<com.yomitanmobile.data.audio.LinguaLibreAudio.State> = linguaLibre.state

    /** Downloads the index on the pack's own scope; recordings come later, one per word. */
    fun installLinguaLibre() = linguaLibre.install()

    fun uninstallLinguaLibre() {
        viewModelScope.launch { linguaLibre.uninstall() }
    }

    /** A common word of the language being studied — it fetches one recording to play. */
    fun previewLinguaLibre() {
        viewModelScope.launch {
            val word = if (studyLanguage == com.yomitanmobile.domain.model.AppLanguage.SPANISH) "hola" else "hello"
            linguaLibre.find(word)?.let { audioPlayer.playAudioFile(it.path) }
        }
    }

    // ── Native-speaker recordings ────────────────────────────────────────

    val nativeAudioState: StateFlow<com.yomitanmobile.data.audio.NativeAudioPack.State> = nativeAudio.state

    /** Starts the download; it runs on the pack's own scope, so leaving the screen does not stop it. */
    fun installNativeAudio() = nativeAudio.install()

    fun uninstallNativeAudio() {
        viewModelScope.launch { nativeAudio.uninstall() }
    }

    /** One of the recordings, so the user hears what was installed. */
    fun previewNativeAudio() {
        viewModelScope.launch {
            nativeAudio.find("一番", "いちばん")?.let { audioPlayer.playAudioFile(it.path) }
        }
    }

    // ── VOICEVOX voice ───────────────────────────────────────────────────

    val voicevoxState: StateFlow<com.yomitanmobile.data.audio.voicevox.VoicevoxVoice.State> = voicevox.state

    val voicevoxEnabled: StateFlow<Boolean> = voicevox.enabled
        .catch { emit(true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Starts the download; it runs on the voice's own scope, so leaving the screen does not stop it. */
    fun installVoicevox() = voicevox.install()

    fun setVoicevoxEnabled(value: Boolean) {
        viewModelScope.launch { voicevox.setEnabled(value) }
    }

    fun uninstallVoicevox() {
        viewModelScope.launch { voicevox.uninstall() }
    }

    /** A word with a known accent, so the sample shows what the voice is for. */
    fun previewVoicevox() {
        viewModelScope.launch {
            voicevox.wav("日本語", "にほんご", "0")?.let { audioPlayer.playAudioFile(it.path) }
        }
    }

    // ── Pronunciation archive ────────────────────────────────────────────

    /** Files indexed in the user's archive; 0 means there is none. */
    val audioArchiveFiles: StateFlow<Int> = audioArchive.observeFileCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _audioArchiveLabel = MutableStateFlow<String?>(null)
    val audioArchiveLabel: StateFlow<String?> = _audioArchiveLabel.asStateFlow()

    private val _audioArchiveIndexing = MutableStateFlow(false)
    val audioArchiveIndexing: StateFlow<Boolean> = _audioArchiveIndexing.asStateFlow()

    init {
        viewModelScope.launch { _audioArchiveLabel.value = audioArchive.folderLabel() }
    }

    /**
     * Walks a picked folder and replaces the index.
     *
     * The walk is the only slow part of this feature and it happens once per
     * archive, so it runs here rather than behind the download queue — but
     * [audioArchiveIndexing] covers the row while it does, since a large
     * archive is hundreds of thousands of files.
     */
    fun indexAudioArchive(treeUri: android.net.Uri) {
        if (_audioArchiveIndexing.value) return
        _audioArchiveIndexing.value = true
        viewModelScope.launch {
            val result = audioArchive.index(treeUri)
            _audioArchiveLabel.value = audioArchive.folderLabel()
            _audioArchiveIndexing.value = false
            _events.emit(
                if (result.failed) SettingsEvent.AudioArchiveEmpty
                else SettingsEvent.AudioArchiveIndexed(result.files)
            )
        }
    }

    fun forgetAudioArchive() {
        viewModelScope.launch {
            audioArchive.forget()
            _audioArchiveLabel.value = null
        }
    }

    /** The language currently being studied, for the settings row. */
    val studyLanguage: com.yomitanmobile.domain.model.AppLanguage
        get() = languageSettings.current

    /**
     * Persists a new study language.
     *
     * The caller restarts the process immediately afterwards, which is not
     * cosmetic: the search lexicon, the note-type profile and every
     * ViewModel that captured the old language live for as long as the
     * process does. Restarting is what makes the switch total instead of
     * leaving half the app answering for the previous language.
     */
    suspend fun setStudyLanguage(language: com.yomitanmobile.domain.model.AppLanguage) {
        languageSettings.setLanguage(language)
    }

    /**
     * Existing AnkiDroid deck names, so the "Change deck" dialog can offer a
     * pick-list instead of forcing the user to retype a name. Returns an empty
     * list when AnkiDroid isn't installed / permission isn't granted, in which
     * case the dialog falls back to the manual text field.
     */
    suspend fun getAvailableDecks(): List<String> = ankiCardCreator.getAvailableDecks()

    val dictionaries: StateFlow<List<DictionaryInfo>> = getDictionariesUseCase.invoke()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Multi-label rollup (fix E): one ExportedWord row can count toward
    // several categories. The classifier helper handles the manual-override
    // → CSV → legacy fallback chain so this view doesn't need to know the
    // schema details.
    val minedCategoryStats: StateFlow<List<MinedCategoryStat>> = exportedWordDao.getCategoryRowsAll()
        .map { rows ->
            val countsByCode = WordCategoryClassifier.tallyCategories(
                rows.map { Triple(it.manualCategory, it.exportCategories, it.exportCategory) }
            )

            WordCategoryClassifier.mostImportantCategories().map { (code, label) ->
                MinedCategoryStat(
                    code = code,
                    label = label,
                    count = countsByCode[code] ?: 0
                )
            }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    // Buffered, never suspending. A default MutableSharedFlow is a rendezvous
    // channel: emit() waits for a collector, and the screen's collector only
    // exists while the screen is composed. Navigating away from a retained
    // ViewModel mid-operation parked the emitting coroutine forever, so the
    // finally block that clears the progress / "is exporting" flag never ran
    // and the screen came back stuck.
    private val _events = MutableSharedFlow<SettingsEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun importDictionary(inputStream: InputStream) {
        viewModelScope.launch {
            _isImporting.value = true
            _importProgress.value = null
            // Catch Throwable (not just Exception). The parser can OOM on
            // pathological inputs (Errors don't extend Exception), and
            // letting that escape would crash the app instead of giving
            // the user a useful error toast.
            try {
                val result = importDictionaryUseCase.invoke(
                    inputStream = inputStream,
                    onProgress = { progress -> _importProgress.value = progress }
                )
                if (result.success) {
                    _events.emit(SettingsEvent.ImportSuccess(result))
                } else {
                    _events.emit(SettingsEvent.ImportError(result.errorMessage ?: "Import failed"))
                }
            } catch (t: Throwable) {
                _events.emit(
                    SettingsEvent.ImportError(
                        "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                    )
                )
            } finally {
                _isImporting.value = false
                _importProgress.value = null
            }
        }
    }

    private val _backups = MutableStateFlow<List<File>>(emptyList())
    val backups: StateFlow<List<File>> = _backups.asStateFlow()

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _isReclassifying = MutableStateFlow(false)
    val isReclassifying: StateFlow<Boolean> = _isReclassifying.asStateFlow()

    /**
     * Re-runs WordCategoryClassifier on every exported word, rewriting
     * the multi-label `export_categories` column. Manual overrides
     * survive untouched. Surface result via SettingsEvent so the UI
     * can show a snackbar with the counts.
     */
    fun reclassifyCategories() {
        viewModelScope.launch {
            _isReclassifying.value = true
            try {
                val result = reclassifyCategoriesUseCase()
                _events.emit(
                    SettingsEvent.ReclassifyDone(
                        updated = result.updated,
                        skippedManual = result.skippedManual,
                        skippedMissing = result.skippedMissing
                    )
                )
            } catch (e: Exception) {
                _events.emit(
                    SettingsEvent.ReclassifyError(e.message ?: "Unknown error")
                )
            } finally {
                _isReclassifying.value = false
            }
        }
    }

    init {
        refreshBackupList()
    }

    fun refreshBackupList() {
        viewModelScope.launch {
            val result = backupManager.listBackups()
            _backups.value = result.getOrDefault(emptyList())
        }
    }

    fun createBackup(includeSettings: Boolean = true) {
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                val result = backupManager.createBackup(includeSettings)
                result.fold(
                    onSuccess = { backupFolder ->
                        _events.emit(SettingsEvent.BackupSuccess(backupFolder.absolutePath))
                        refreshBackupList()
                    },
                    onFailure = { error ->
                        _events.emit(SettingsEvent.BackupError(error.message ?: "Backup failed"))
                    }
                )
            } catch (e: Exception) {
                _events.emit(SettingsEvent.BackupError(e.message ?: "Unknown error"))
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun restoreBackup(backupFolder: File) {
        viewModelScope.launch {
            _isRestoring.value = true
            try {
                val result = backupManager.restoreBackup(backupFolder)
                result.fold(
                    onSuccess = {
                        _events.emit(SettingsEvent.RestoreSuccess)
                    },
                    onFailure = { error ->
                        _events.emit(SettingsEvent.RestoreError(error.message ?: "Restore failed"))
                    }
                )
            } catch (e: Exception) {
                _events.emit(SettingsEvent.RestoreError(e.message ?: "Unknown error"))
            } finally {
                _isRestoring.value = false
            }
        }
    }

    /**
     * Apply a user-picked settings.json (the file "Create backup" writes when
     * "include settings" is on). Settings-only — the database is untouched,
     * so unlike a full restore no app restart is required.
     */
    fun importSettings(inputStream: InputStream) {
        viewModelScope.launch {
            backupManager.importSettings(inputStream).fold(
                onSuccess = { applied ->
                    _events.emit(SettingsEvent.SettingsImported(applied))
                },
                onFailure = { error ->
                    _events.emit(
                        SettingsEvent.SettingsImportError(error.message ?: "Import failed")
                    )
                }
            )
        }
    }

    fun deleteBackup(backupFolder: File) {
        viewModelScope.launch {
            try {
                backupManager.deleteBackup(backupFolder).fold(
                    onSuccess = {
                        refreshBackupList()
                    },
                    onFailure = { error ->
                        _events.emit(SettingsEvent.BackupError("Failed to delete: ${error.message}"))
                    }
                )
            } catch (e: Exception) {
                _events.emit(SettingsEvent.BackupError(e.message ?: "Unknown error"))
            }
        }
    }

    fun deleteDictionary(name: String) {
        viewModelScope.launch {
            try {
                deleteDictionaryUseCase.invoke(name)
            } catch (e: Exception) {
                _events.emit(SettingsEvent.ImportError("Failed to delete: ${e.message}"))
            }
        }
    }
}
