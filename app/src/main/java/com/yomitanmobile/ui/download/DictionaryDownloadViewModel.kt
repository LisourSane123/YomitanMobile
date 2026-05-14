package com.yomitanmobile.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.download.AvailableDictionaries
import com.yomitanmobile.data.download.DictionaryCategory
import com.yomitanmobile.data.download.DictionaryDownloadInfo
import com.yomitanmobile.data.download.DictionaryDownloadManager
import com.yomitanmobile.data.download.DownloadPhase
import com.yomitanmobile.data.download.DownloadProgress
import com.yomitanmobile.data.download.DownloadResult
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.domain.usecase.GetDictionariesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DownloadEvent {
    data class Success(val name: String, val entries: Int) : DownloadEvent()
    data class Error(val name: String, val message: String) : DownloadEvent()
}

@HiltViewModel
class DictionaryDownloadViewModel @Inject constructor(
    private val downloadManager: DictionaryDownloadManager,
    getDictionariesUseCase: GetDictionariesUseCase
) : ViewModel() {

    val availableDictionaries: List<DictionaryDownloadInfo> = AvailableDictionaries.all

    val installedDictionaries: StateFlow<List<DictionaryInfo>> = getDictionariesUseCase.invoke()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadProgress: StateFlow<DownloadProgress?> = downloadManager.currentDownload

    val isDownloading: StateFlow<Boolean> = downloadManager.isDownloading

    private val _selectedCategory = MutableStateFlow<DictionaryCategory?>(null)
    val selectedCategory: StateFlow<DictionaryCategory?> = _selectedCategory.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>()
    val events = _events.asSharedFlow()

    fun selectCategory(category: DictionaryCategory?) {
        _selectedCategory.value = category
    }

    fun getFilteredDictionaries(): List<DictionaryDownloadInfo> {
        val cat = _selectedCategory.value
        return if (cat != null) {
            availableDictionaries.filter { it.category == cat }
        } else {
            availableDictionaries
        }
    }

    fun isDictionaryInstalled(dictInfo: DictionaryDownloadInfo): Boolean {
        val installed = installedDictionaries.value.map { it.name.lowercase() }
        return installed.any { name ->
            name.contains(dictInfo.id.replace("_", " ")) ||
            dictInfo.name.lowercase().let { dName ->
                name.contains(dName) || dName.contains(name)
            }
        }
    }

    fun downloadDictionary(info: DictionaryDownloadInfo) {
        viewModelScope.launch {
            // Catch Throwable (not just Exception) — kotlinx.coroutines
            // turns OutOfMemoryError into a propagating throw, and the
            // dictionary import path allocates several MB at a time. We
            // surface those as DownloadEvent.Error rather than letting
            // them reach the uncaught-exception handler, which would
            // crash the app on the user with no diagnostic.
            try {
                val result = downloadManager.downloadAndImport(info)
                when (result) {
                    is DownloadResult.Success -> {
                        _events.emit(DownloadEvent.Success(result.dictionaryName, result.entriesImported))
                    }
                    is DownloadResult.Error -> {
                        _events.emit(DownloadEvent.Error(result.dictionaryName, result.message))
                    }
                }
            } catch (t: Throwable) {
                _events.emit(
                    DownloadEvent.Error(
                        info.name,
                        "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                    )
                )
            }
        }
    }

    fun downloadJmdict() {
        downloadDictionary(AvailableDictionaries.jmdict)
    }

    fun downloadAllRecommended() {
        viewModelScope.launch {
            for (dict in AvailableDictionaries.recommended) {
                if (!isDictionaryInstalled(dict)) {
                    try {
                        val result = downloadManager.downloadAndImport(dict)
                        when (result) {
                            is DownloadResult.Success -> {
                                _events.emit(DownloadEvent.Success(result.dictionaryName, result.entriesImported))
                            }
                            is DownloadResult.Error -> {
                                _events.emit(DownloadEvent.Error(result.dictionaryName, result.message))
                            }
                        }
                    } catch (t: Throwable) {
                        _events.emit(
                            DownloadEvent.Error(
                                dict.name,
                                "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                            )
                        )
                    }
                }
            }
        }
    }
}
