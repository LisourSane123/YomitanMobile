package com.yomitanmobile.data.audio

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import javax.inject.Singleton

@Singleton
class AudioPlayer(
    private val context: Context
) {
    companion object {
        const val TAG = "AudioPlayer"
    }

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null

    private val _ttsReady = MutableStateFlow(false)
    val ttsReady: StateFlow<Boolean> = _ttsReady.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun initTts(onReady: (TextToSpeech?) -> Unit = {}) {
        tts?.shutdown() // Release previous TTS instance to prevent resource leak
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.JAPANESE)
                _ttsReady.value = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (_ttsReady.value) {
                    Log.d(TAG, "TTS initialized successfully for Japanese")
                    onTtsReady()
                    onReady(tts)
                } else {
                    Log.w(TAG, "Japanese TTS not available")
                    onReady(null)
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                _ttsReady.value = false
                onReady(null)
            }
        }
    }

    fun getTts(): TextToSpeech? = if (_ttsReady.value) tts else null

    fun speakWithTts(text: String) {
        if (!_ttsReady.value) {
            Log.w(TAG, "TTS not ready, cannot speak")
            return
        }
        stopPlayback()
        _isPlaying.value = true

        val utteranceId = "yomitan_speak_${System.currentTimeMillis()}"
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
            Log.d(TAG, "TTS not ready for auto-pronounce, queuing")
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
                Log.w(TAG, "Audio file not found: $filePath")
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
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    _isPlaying.value = false
                    try { mp.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file", e)
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
        _ttsReady.value = false
    }
}
