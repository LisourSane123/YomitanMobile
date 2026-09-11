package com.yomitanmobile.data.audio

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AudioPlayer(
    private val context: Context,
    private val languageSettings: com.yomitanmobile.data.settings.LanguageSettings
) {

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null

    /** Language the live engine was configured for, so a re-init is only
     *  paid for when the studied language actually changed. */
    private var ttsLanguageTag: String? = null

    /** Serialises [ensureTts] so two bulk jobs can't tear down each other's engine. */
    private val ttsInitMutex = Mutex()

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun initTts(onReady: (TextToSpeech?) -> Unit = {}) {
        val wantedLanguage = languageSettings.current.ttsLanguageTag
        // A ready engine for the right language is reused. Re-creating it on
        // every detail screen used to shut down the instance a running bulk
        // export was synthesising with.
        val live = tts
        if (live != null && _ttsReady.value && ttsLanguageTag == wantedLanguage) {
            onReady(live)
            return
        }
        tts?.shutdown() // Release previous TTS instance to prevent resource leak
        _ttsReady.value = false
        ttsLanguageTag = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // The voice has to match what is being studied: a Japanese
                // engine reading "dog" pronounces it as romaji.
                val result = tts?.setLanguage(Locale.forLanguageTag(wantedLanguage))
                _ttsReady.value = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                ttsLanguageTag = if (_ttsReady.value) wantedLanguage else null
                if (_ttsReady.value) {
                    onTtsReady()
                    onReady(tts)
                } else {
                    onReady(null)
                }
            } else {
                _ttsReady.value = false
                onReady(null)
            }
        }
    }

    fun getTts(): TextToSpeech? = if (_ttsReady.value) tts else null

    /**
     * The engine, initialising it first if nobody has yet — the form every
     * background caller needs.
     *
     * [getTts] returns null until some screen has called [initTts] and the
     * engine has finished starting up, which is asynchronous. The bulk card
     * writers (JLPT deck, text scan) run without ever having touched the
     * detail screen, so asking for [getTts] there returned null and every card
     * came out silent. Returns null when the device has no usable Japanese
     * voice or start-up times out, so callers can say so instead of writing
     * silent cards.
     */
    suspend fun ensureTts(timeoutMs: Long = TTS_INIT_TIMEOUT_MS): TextToSpeech? =
        ttsInitMutex.withLock {
            getTts()?.let { engine ->
                if (ttsLanguageTag == languageSettings.current.ttsLanguageTag) {
                    return@withLock engine
                }
            }
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    initTts { engine ->
                        if (!continuation.isCompleted) continuation.resume(engine)
                    }
                }
            }
        }

    fun speakWithTts(text: String) {
        if (!_ttsReady.value) {
            return
        }
        stopPlayback()
        _isPlaying.value = true

        val utteranceId = "yomitan_speak_${UUID.randomUUID()}"
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {
                _isPlaying.value = true
            }
            override fun onDone(id: String?) {
                _isPlaying.value = false
            }
            @Deprecated("Deprecated")
            override fun onError(id: String?) {
                _isPlaying.value = false
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Automatically pronounce a word using TTS.
     * Called when navigating to a word detail screen.
     */
    fun autoPronounceTts(text: String) {
        if (!_ttsReady.value) {
            pendingAutoSpeak = text
            return
        }
        speakWithTts(text)
    }

    private var pendingAutoSpeak: String? = null

    /**
     * Called when TTS becomes ready – speaks any pending auto-pronounce text.
     */
    private fun onTtsReady() {
        pendingAutoSpeak?.let { text ->
            pendingAutoSpeak = null
            speakWithTts(text)
        }
    }

    fun playAudioFile(filePath: String) {
        stopPlayback()
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return
            }
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setOnPreparedListener {
                    _isPlaying.value = true
                    start()
                }
                setOnCompletionListener {
                    _isPlaying.value = false
                    release()
                    mediaPlayer = null
                }
                setOnErrorListener { mp, _, _ ->
                    _isPlaying.value = false
                    try { mp.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            _isPlaying.value = false
        }
    }

    fun playWord(text: String, audioFilePath: String? = null) {
        if (!audioFilePath.isNullOrBlank()) {
            playAudioFile(audioFilePath)
        } else {
            speakWithTts(text)
        }
    }

    fun stopPlayback() {
        tts?.stop()
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
            mediaPlayer = null
        }
        _isPlaying.value = false
    }

    fun release() {
        stopPlayback()
        tts?.shutdown()
        tts = null
        ttsLanguageTag = null
        _ttsReady.value = false
    }

    private companion object {
        /** Engine start-up is a service bind; a stuck one must not hang a deck. */
        const val TTS_INIT_TIMEOUT_MS = 10_000L
    }
}
