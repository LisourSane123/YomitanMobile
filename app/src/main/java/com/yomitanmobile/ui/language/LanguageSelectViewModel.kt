package com.yomitanmobile.ui.language

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LanguageSelectViewModel @Inject constructor(
    private val languageSettings: LanguageSettings
) : ViewModel() {

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    val current: AppLanguage get() = languageSettings.current

    /**
     * Persists the choice and signals the caller to move on. The in-memory
     * cache is updated by [LanguageSettings.setLanguage] before [saved]
     * flips, so the setup screen that follows already sees the new language
     * when it asks which dictionaries to offer.
     */
    fun choose(language: AppLanguage) {
        viewModelScope.launch {
            languageSettings.setLanguage(language)
            _saved.value = true
        }
    }
}
