package com.yomitanmobile.data.audio.voicevox

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.download.DictionaryDownloadManager
import com.yomitanmobile.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VOICEVOX on the phone: the download, the switch, and the speaker.
 *
 * The voice is an opt-in download (~150 MB, [VoicevoxAssets]) because it is
 * far better than the system TTS — neural, and told the accent the card
 * prints instead of guessing it — but far too big to ship in the APK. Once it
 * is installed and switched on, card audio and the detail screen's play button
 * use it; the system TTS stays the fallback for everything else.
 *
 * The install runs on this singleton's own scope, not the settings screen's:
 * leaving the screen must not cancel a download of that size.
 */
@Singleton
class VoicevoxVoice @Inject constructor(
    @ApplicationContext private val context: Context
) {

    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    private val root = File(context.filesDir, "voicevox")
    private val cache = File(context.cacheDir, "voicevox_audio")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(
        if (VoicevoxAssets.isInstalled(root)) State.Installed else State.NotInstalled
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Switched on by default the moment the voice is installed — installing it is the opt-in. */
    val enabled: Flow<Boolean> = context.dataStore.data.map { it[MainActivity.TTS_VOICEVOX_ENABLED] ?: true }

    private var installJob: Job? = null

    fun install() {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.value = State.Downloading(0, VoicevoxAssets.TOTAL_BYTES)
            _state.value = try {
                VoicevoxAssets.install(
                    root,
                    open = { url -> DictionaryDownloadManager.openAllowed(url).inputStream },
                    onProgress = { done, total -> _state.value = State.Downloading(done, total) }
                )
                State.Installed
            } catch (e: Exception) {
                Log.w(TAG, "VOICEVOX install failed", e)
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun setEnabled(value: Boolean) {
        context.dataStore.edit { it[MainActivity.TTS_VOICEVOX_ENABLED] = value }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        installJob?.cancel()
        speakerLock.withLock { closeSpeaker() }
        VoicevoxAssets.uninstall(root)
        cache.deleteRecursively()
        _state.value = State.NotInstalled
    }

    /** Installed and switched on: the callers' one question. */
    suspend fun isActive(): Boolean =
        _state.value == State.Installed && enabled.first()

    /**
     * A WAV of the word, or null when VOICEVOX is off, not installed or
     * failed — the caller then falls back to the system TTS. Recordings are
     * cached by word, so pressing play twice synthesises once.
     */
    suspend fun wav(expression: String, reading: String, pitch: String): File? {
        if (!isActive()) return null
        return withContext(Dispatchers.IO) {
            val file = File(cache, "yomitan_vv_${key(expression, reading, pitch)}.wav")
            if (file.isFile && file.length() > 0) return@withContext file
            try {
                val bytes = speakerLock.withLock {
                    val speaker = speaker ?: VoicevoxSpeaker(Onnxruntime.LIB_RECOMMENDED_UNVERSIONED_FILENAME, root)
                        .also { speaker = it }
                    scheduleRelease()
                    speaker.wav(expression, reading, pitch)
                }
                cache.mkdirs()
                file.writeBytes(bytes)
                file
            } catch (e: Throwable) {
                // UnsatisfiedLinkError included: a device whose ABI the APK
                // has no ONNX Runtime for must fall back, not crash.
                Log.w(TAG, "VOICEVOX could not say $expression", e)
                null
            }
        }
    }

    private val speakerLock = Mutex()
    private var speaker: VoicevoxSpeaker? = null
    private var releaseJob: Job? = null

    /**
     * The loaded models hold a few hundred MB, so the speaker is let go after
     * a quiet minute. A batch export keeps it alive; a single word pays the
     * load (a couple of seconds) again next time.
     */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            speakerLock.withLock { closeSpeaker() }
        }
    }

    private fun closeSpeaker() {
        speaker?.let { runCatching { it.close() } }
        speaker = null
    }

    private fun key(expression: String, reading: String, pitch: String): String =
        MessageDigest.getInstance("SHA-1").digest("$expression|$reading|$pitch".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        private const val TAG = "VoicevoxVoice"
        private const val IDLE_RELEASE_MS = 60_000L
    }
}
