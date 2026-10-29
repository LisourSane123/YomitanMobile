package com.yomitanmobile.data.repository

import android.util.Log
import com.yomitanmobile.domain.repository.DictionaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes dictionaries on the application scope.
 *
 * Deleting a big dictionary takes minutes on a phone (every term row takes
 * its FTS row with it). It used to run in the dictionary screen's ViewModel,
 * which made deleting look broken: the dictionary stayed on the list with
 * nothing saying work was under way, leaving the screen cancelled the delete
 * half-way, and a failure went to an event nothing on that screen listened to.
 * Here the work belongs to the application, one dictionary at a time,
 * [removing] is what the screen draws progress from, and
 * [com.yomitanmobile.service.BackgroundWorkService] keeps the process alive
 * while it is non-empty.
 */
@Singleton
class DictionaryRemover @Inject constructor(
    private val repository: DictionaryRepository,
    private val scope: CoroutineScope,
    private val backgroundWork: BackgroundWorkStarter
) {
    private val mutex = Mutex()

    private val _removing = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Dictionaries queued or being deleted → share of their rows already gone. */
    val removing: StateFlow<Map<String, Float>> = _removing.asStateFlow()

    private val _failures = MutableSharedFlow<Failure>(extraBufferCapacity = 8)

    /** Deletes that failed; the dictionary is still installed. */
    val failures: SharedFlow<Failure> = _failures.asSharedFlow()

    data class Failure(val dictionary: String, val message: String)

    fun remove(dictionary: String) {
        var added = false
        _removing.update { current ->
            added = dictionary !in current
            if (added) current + (dictionary to 0f) else current
        }
        if (!added) return // a second tap on a delete already under way
        backgroundWork.start()
        scope.launch {
            try {
                mutex.withLock {
                    repository.deleteDictionary(dictionary) { progress ->
                        _removing.update { it + (dictionary to progress) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Deleting $dictionary failed", e)
                _failures.tryEmit(Failure(dictionary, e.message ?: e.javaClass.simpleName))
            } finally {
                _removing.update { it - dictionary }
            }
        }
    }

    private companion object {
        const val TAG = "DictionaryRemover"
    }
}
