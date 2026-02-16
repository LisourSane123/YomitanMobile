package com.yomitanmobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.domain.model.ImportProgress
import com.yomitanmobile.domain.model.ImportResult
import com.yomitanmobile.domain.usecase.DeleteDictionaryUseCase
import com.yomitanmobile.domain.usecase.GetDictionariesUseCase
import com.yomitanmobile.domain.usecase.ImportDictionaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

sealed class SettingsEvent {
    data class ImportSuccess(val result: ImportResult) : SettingsEvent()
    data class ImportError(val message: String) : SettingsEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val importDictionaryUseCase: ImportDictionaryUseCase,
    private val deleteDictionaryUseCase: DeleteDictionaryUseCase,
    getDictionariesUseCase: GetDictionariesUseCase
) : ViewModel() {

    val dictionaries: StateFlow<List<DictionaryInfo>> = getDictionariesUseCase.invoke()
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
