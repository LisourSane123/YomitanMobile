package com.yomitanmobile.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.local.dao.FrequencyDao
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the frequency-display settings screen: the priority order of installed
 * frequency lists and the "show all vs. top only" toggle. The canonical order
 * is the user's saved priority reconciled with the lists actually installed —
 * newly installed lists are appended, uninstalled ones drop off.
 */
@HiltViewModel
class FrequencySettingsViewModel @Inject constructor(
    frequencyDao: FrequencyDao,
    private val frequencySettings: FrequencySettings,
    private val repository: DictionaryRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /**
     * True while the frequency stamped on every entry is being recomputed.
     *
     * Changing which list leads changes the number that goes on cards, orders
     * search results and decides what counts as rare — so it is not a display
     * preference, it is a pass over the whole dictionary table.
     */
    private val _isReapplying = MutableStateFlow(false)
    val isReapplying: StateFlow<Boolean> = _isReapplying.asStateFlow()

    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()

    private val _showAll = MutableStateFlow(true)
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = appContext.dataStore.data.first()
            _showAll.value = prefs[MainActivity.FREQUENCY_SHOW_ALL] ?: true
            val saved = (prefs[MainActivity.FREQUENCY_DISPLAY_ORDER] ?: "")
                .split(',').map { it.trim() }.filter { it.isNotBlank() }
            _order.value = saved
            frequencyDao.observeDictionaries().collect { installed ->
                // Keep saved priority for still-installed lists; append any new
                // list at the end; drop lists that are no longer installed.
                val current = _order.value.ifEmpty { saved }
                _order.value = current.filter { it in installed } +
                    installed.filter { it !in current }
            }
        }
    }

    fun moveUp(name: String) = move(name, -1)
    fun moveDown(name: String) = move(name, +1)

    /**
     * Promotes a list to the top: its rank is what a card gets when several
     * lists know the word.
     */
    fun makeLeading(name: String) {
        val list = _order.value.toMutableList()
        if (!list.remove(name)) return
        list.add(0, name)
        _order.value = list
        persistOrder()
    }

    private fun move(name: String, delta: Int) {
        val list = _order.value.toMutableList()
        val index = list.indexOf(name)
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        list[index] = list[target].also { list[target] = list[index] }
        _order.value = list
        persistOrder()
    }

    fun setShowAll(value: Boolean) {
        _showAll.value = value
        viewModelScope.launch {
            appContext.dataStore.edit { it[MainActivity.FREQUENCY_SHOW_ALL] = value }
        }
    }

    private fun persistOrder() {
        val order = _order.value
        viewModelScope.launch {
            frequencySettings.setOrder(order)
            // The order decides which rank wins, so the rollup has to run
            // again; otherwise the change would show on the detail screen and
            // nowhere else — least of all on the next card.
            _isReapplying.value = true
            try {
                repository.reapplyFrequencies()
            } finally {
                _isReapplying.value = false
            }
        }
    }
}
