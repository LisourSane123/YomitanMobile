package com.yomitanmobile.ui.dictionaries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.repository.DictionaryRemover
import com.yomitanmobile.domain.usecase.GetDictionariesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DictionariesViewModel @Inject constructor(
    getDictionaries: GetDictionariesUseCase,
    private val remover: DictionaryRemover
) : ViewModel() {

    val dictionaries: StateFlow<List<DictionaryInfo>> = getDictionaries.invoke()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Name → share deleted. Outlives this screen: the delete is the application's. */
    val removing: StateFlow<Map<String, Float>> = remover.removing

    val failures: SharedFlow<DictionaryRemover.Failure> = remover.failures

    fun delete(name: String) = remover.remove(name)
}
