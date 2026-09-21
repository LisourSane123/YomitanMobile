package com.yomitanmobile.data.audio

import android.content.Context
import android.util.Log
import com.yomitanmobile.data.download.DictionaryDownloadManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native-speaker recordings on the phone: Kanji alive's 10 124 words
 * ([KanjiAlive]), downloaded on request and asked before any synthesised
 * voice.
 *
 * It is reached through [AudioArchive.find], right after the user's own
 * folder, so every place that wants a word said — the detail screen's play
 * button, a mined card, the JLPT and text-scan decks, a refresh of old cards —
 * gets a person before VOICEVOX and before the system TTS, without any of them
 * knowing the pack exists.
 *
 * The install runs on this singleton's scope, not the settings screen's, as
 * the VOICEVOX download does: leaving the screen must not cancel 66 MB.
 */
@Singleton
class NativeAudioPack @Inject constructor(
    @ApplicationContext context: Context
) {

    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    private val root = File(context.filesDir, "native_audio/kanjialive")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(
        if (KanjiAlive.isInstalled(root)) State.Installed else State.NotInstalled
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var installJob: Job? = null
    private val indexLock = Mutex()
    private var index: NativeAudioIndex? = null

    fun install() {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.value = State.Downloading(0, KanjiAlive.AUDIO_ZIP_BYTES)
            _state.value = try {
                KanjiAlive.install(
                    root,
                    open = { url -> DictionaryDownloadManager.openAllowed(url).inputStream },
                    onProgress = { done, total -> _state.value = State.Downloading(done, total) }
                )
                indexLock.withLock { index = null }
                State.Installed
            } catch (e: Exception) {
                Log.w(TAG, "Native recordings install failed", e)
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        installJob?.cancel()
        indexLock.withLock { index = null }
        KanjiAlive.uninstall(root)
        _state.value = State.NotInstalled
    }

    /** A native speaker saying this word, or null when the pack has none (or is not installed). */
    suspend fun find(expression: String, reading: String): File? {
        if (_state.value != State.Installed) return null
        return withContext(Dispatchers.IO) {
            val loaded = indexLock.withLock {
                index ?: runCatching { KanjiAlive.loadIndex(root) }
                    .onFailure { Log.w(TAG, "Reading the recordings index failed", it) }
                    .getOrNull()
                    ?.also { index = it }
            } ?: return@withContext null
            val name = loaded.find(expression, reading) ?: return@withContext null
            KanjiAlive.file(root, name).takeIf { it.isFile }
        }
    }

    private companion object {
        const val TAG = "NativeAudioPack"
    }
}
