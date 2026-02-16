package com.yomitanmobile.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.download.AvailableDictionaries
import com.yomitanmobile.data.download.DictionaryDownloadManager
import com.yomitanmobile.data.download.DownloadProgress
import com.yomitanmobile.data.download.DownloadResult
import com.yomitanmobile.domain.repository.DictionaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupState {
    WELCOME,
    DOWNLOADING,
    COMPLETED,
    ERROR,
    SKIPPED
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val downloadManager: DictionaryDownloadManager,
    private val repository: DictionaryRepository
) : ViewModel() {

    private val _setupState = MutableStateFlow(SetupState.WELCOME)
    val setupState: StateFlow<SetupState> = _setupState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val downloadProgress: StateFlow<DownloadProgress?> = downloadManager.currentDownload

    val hasDictionaries: StateFlow<Boolean> = repository.getImportedDictionaries()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun startJmDictDownload() {
        _setupState.value = SetupState.DOWNLOADING
        viewModelScope.launch {
            val result = downloadManager.downloadAndImport(AvailableDictionaries.jmdict)
            when (result) {
                is DownloadResult.Success -> {
                    _setupState.value = SetupState.COMPLETED
                }
                is DownloadResult.Error -> {
                    _errorMessage.value = result.message
                    _setupState.value = SetupState.ERROR
                }
            }
        }
    }

    fun skip() {
        _setupState.value = SetupState.SKIPPED
    }

    fun retry() {
        _errorMessage.value = null
        startJmDictDownload()
    }
}
