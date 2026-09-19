package com.yomitanmobile.data.audio.voicevox

import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/**
 * One word, pronounced by VOICEVOX. No Android in here: the phone and the
 * laptop's Kindle tool run this same class, so a card sounds the same
 * wherever it was made.
 *
 * Loading the models is seconds of work and a few hundred MB of memory, so a
 * speaker is built once and kept; [wav] is then a fraction of a second.
 *
 * @param onnxruntime what VOICEVOX loads its ONNX Runtime from — a bare
 *   library name on Android (the APK's lib/ folder), a path on a desktop.
 * @param root the downloaded files, laid out as [VoicevoxAssets] installs them.
 */
class VoicevoxSpeaker(onnxruntime: String, root: File) : Closeable {

    private val synthesizer: Synthesizer
    private val models = ArrayList<VoiceModelFile>()

    init {
        val runtime = Onnxruntime.loadOnce().filename(onnxruntime).perform()
        synthesizer = Synthesizer.builder(runtime, OpenJtalk(VoicevoxAssets.dictionaryDir(root).absolutePath))
            .build()
        for (model in VoicevoxAssets.MODELS) {
            val file = VoiceModelFile(File(VoicevoxAssets.modelsDir(root), model.fileName).absolutePath)
            synthesizer.loadVoiceModel(file).perform()
            models += file
        }
    }

    /**
     * A normalised 16-bit WAV of [reading] said with [pitch] (the pitch
     * column as stored: drop positions, first one wins). Without a usable
     * accent the reading — or the expression, when there is no reading — goes
     * in as text and VOICEVOX's own analysis decides.
     */
    @Synchronized
    fun wav(expression: String, reading: String, pitch: String): ByteArray {
        val style = voiceFor(expression, reading)
        val kana = AccentKana.accented(reading, pitch)
        val query = if (kana != null) {
            synthesizer.createAudioQueryFromKana(kana, style)
        } else {
            synthesizer.createAudioQuery(reading.ifBlank { expression }, style)
        }
        // A single word needs no lead-in or tail of silence.
        query.prePhonemeLength = 0.05
        query.postPhonemeLength = 0.1
        return WavLoudness.normalize(synthesizer.synthesis(query, style).perform())
    }

    override fun close() {
        models.forEach { runCatching { it.close() } }
        models.clear()
    }

    companion object {
        /**
         * Neutral narrator voices, not the character voices: those are
         * anime-styled and read a single word with a lot of attitude. The
         * voice is picked per word and keyed by it, so a word always gets the
         * same voice — the phone's random-voice setting, made reproducible.
         */
        val VOICES: List<Int> = VoicevoxAssets.MODELS.flatMap { it.styles }

        fun voiceFor(expression: String, reading: String): Int {
            val digest = MessageDigest.getInstance("SHA-1").digest("$expression|$reading".toByteArray())
            val index = java.math.BigInteger(1, digest).mod(java.math.BigInteger.valueOf(VOICES.size.toLong()))
            return VOICES[index.toInt()]
        }
    }
}
