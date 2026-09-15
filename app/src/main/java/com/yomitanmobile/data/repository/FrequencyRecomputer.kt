package com.yomitanmobile.data.repository

import android.util.Log
import com.yomitanmobile.data.local.dao.FrequencyDao
import com.yomitanmobile.domain.repository.DictionaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the frequency passes the settings screen asks for — flipping a list
 * between ranks and counts, re-rolling the stored rank after the leading list
 * or strictness changed.
 *
 * Each of them is a pass over the whole dictionary table and takes seconds on
 * a phone. They used to run in the screen's ViewModel scope, which went wrong
 * three ways: leaving the screen cancelled a pass half-way (the direction was
 * already saved, the ranks were not yet converted), two quick taps ran two
 * passes over the same rows at once, and nothing outside one small line at the
 * top of the screen said that anything was happening. Here the work belongs to
 * the application, runs one pass at a time in the order asked for, and
 * [isRunning] is what the screen blocks on and what keeps
 * [com.yomitanmobile.service.BackgroundWorkService] alive while minimised.
 */
@Singleton
class FrequencyRecomputer @Inject constructor(
    private val repository: DictionaryRepository,
    private val frequencyDao: FrequencyDao,
    private val scope: CoroutineScope,
    private val backgroundWork: BackgroundWorkStarter
) {
    private val mutex = Mutex()
    private val lock = Any()
    private var pending = 0 // guarded by [lock], together with [_isRunning]

    private val _isRunning = MutableStateFlow(false)

    /** Passes queued or running. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun setDirection(dictionary: String, higherIsBetter: Boolean) =
        submit { repository.setFrequencyListDirection(dictionary, higherIsBetter) }

    fun reapply() = submit { repository.reapplyFrequencies() }

    /**
     * Gives installed lists with no stored direction one. Only blocks the
     * screen when there is such a list — the check itself is one small query,
     * and a spinner flashing on every visit would teach the user to ignore it.
     */
    suspend fun classifyUnknownLists() {
        val unknown = try {
            val known = frequencyDao.getListSettings().mapTo(HashSet()) { it.dictionary }
            frequencyDao.observeDictionaries().first().any { it !in known }
        } catch (e: Exception) {
            Log.w(TAG, "Checking for unclassified frequency lists failed", e)
            false
        }
        if (unknown) submit { repository.classifyUnknownFrequencyLists() }
    }

    private fun submit(block: suspend () -> Unit) {
        // Raised before the coroutine starts, so the screen blocks on the same
        // frame as the tap and the service sees work the moment it is started.
        synchronized(lock) {
            pending++
            _isRunning.value = true
        }
        backgroundWork.start()
        scope.launch {
            try {
                mutex.withLock { block() }
            } catch (e: Exception) {
                Log.w(TAG, "Frequency recompute failed", e)
            } finally {
                synchronized(lock) {
                    if (--pending == 0) _isRunning.value = false
                }
            }
        }
    }

    private companion object {
        const val TAG = "FrequencyRecomputer"
    }
}

/** Starts the foreground service that keeps long work alive in the background. */
fun interface BackgroundWorkStarter {
    fun start()
}
