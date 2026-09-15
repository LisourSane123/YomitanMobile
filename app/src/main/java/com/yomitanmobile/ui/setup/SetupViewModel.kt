package com.yomitanmobile.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.download.AvailableDictionaries
import com.yomitanmobile.data.download.DictionaryDownloadManager
import com.yomitanmobile.data.download.DownloadProgress
import com.yomitanmobile.data.download.DictionaryDownloadInfo
import com.yomitanmobile.data.download.QueueState
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.domain.repository.DictionaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val repository: DictionaryRepository,
    languageSettings: LanguageSettings
) : ViewModel() {

    /**
     * Set on the language screen immediately before this one, so it is
     * already current here. Everything setup offers is derived from it —
     * there is no separate "which dictionaries" question to ask.
     */
    val language: AppLanguage = languageSettings.current

    /** The single most useful dictionary for this language. */
    private val primaryDictionary = when (language) {
        AppLanguage.JAPANESE -> AvailableDictionaries.jmdict
        AppLanguage.ENGLISH -> AvailableDictionaries.wiktionaryEnPl
        AppLanguage.SPANISH -> AvailableDictionaries.wiktionaryEsEn
    }

    val recommendedDictionaries = AvailableDictionaries.recommendedFor(language)

    private val _setupState = MutableStateFlow(SetupState.WELCOME)
    val setupState: StateFlow<SetupState> = _setupState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val downloadProgress: StateFlow<DownloadProgress?> = downloadManager.currentDownload

    val hasDictionaries: StateFlow<Boolean> = repository.getImportedDictionaries()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun startPrimaryDownload() = startInstall(listOf(primaryDictionary))

    fun startRecommendedDownload() = startInstall(recommendedDictionaries)

    private var installWatcher: Job? = null

    /**
     * Hands the dictionaries to the shared install queue and follows them.
     *
     * Setup used to install them itself, one `downloadAndImport` after another
     * inside this ViewModel's scope — outside the queue, so the background
     * service never knew about them and minimising the app during the first
     * install (the long one, minutes of it) let Android freeze or kill the
     * work. The queue runs on the application scope under that service; this
     * screen only watches it.
     */
    private fun startInstall(dictionaries: List<DictionaryDownloadInfo>) {
        _setupState.value = SetupState.DOWNLOADING
        // Finished entries already in the queue (an earlier attempt) must not
        // be mistaken for the outcome of this one. A dictionary already
        // waiting or installing is not queued twice, and IS this attempt.
        val earlier = downloadManager.queue.value
            .filter { it.state != QueueState.WAITING && it.state != QueueState.RUNNING }
        val ids = dictionaries.map { it.id }.toSet()
        downloadManager.enqueue(dictionaries)
        installWatcher?.cancel()
        installWatcher = viewModelScope.launch {
            val outcome = downloadManager.queue
                .map { queue ->
                    ids.map { id -> queue.lastOrNull { item -> item.info.id == id && earlier.none { it === item } } }
                }
                .first { items ->
                    items.all { it != null && it.state != QueueState.WAITING && it.state != QueueState.RUNNING }
                }
                .filterNotNull()
            val failed = outcome.firstOrNull { it.state == QueueState.FAILED }
            if (failed != null) {
                _errorMessage.value = "${failed.info.name}: ${failed.error.orEmpty()}"
                _setupState.value = SetupState.ERROR
            } else {
                _setupState.value = SetupState.COMPLETED
            }
        }
    }

    fun skip() {
        _setupState.value = SetupState.SKIPPED
    }

    fun retry() {
        _errorMessage.value = null
        startPrimaryDownload()
    }
}
