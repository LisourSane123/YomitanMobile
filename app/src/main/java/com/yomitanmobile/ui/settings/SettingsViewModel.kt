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
    data class ImportSuccess(val result: ImportResult) : SettingsEvent()
    data class ImportError(val message: String) : SettingsEvent()
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
    getDictionariesUseCase: GetDictionariesUseCase,
    exportedWordDao: ExportedWordDao
) : ViewModel() {

    val dictionaries: StateFlow<List<DictionaryInfo>> = getDictionariesUseCase.invoke()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val minedCategoryStats: StateFlow<List<MinedCategoryStat>> = exportedWordDao.getCategoryActivityAll()
        .map { rows ->
            val countsByCode = rows
                .associate { row -> row.category.trim().ifBlank { WordCategoryClassifier.CATEGORY_OTHER } to row.count }

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

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events = _events.asSharedFlow()

    fun importDictionary(inputStream: InputStream) {
        viewModelScope.launch {
            _isImporting.value = true
            _importProgress.value = null
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
            } catch (e: Exception) {
                _events.emit(SettingsEvent.ImportError(e.message ?: "Unknown error"))
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

    init {
        refreshBackupList()
    }

    fun refreshBackupList() {
        viewModelScope.launch {
            val result = backupManager.listBackups()
            _backups.value = result.getOrDefault(emptyList())
        }
    }

    fun createBackup() {
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                val result = backupManager.createBackup()
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
