package com.yomitanmobile.kindle

import com.yomitanmobile.data.audio.AudioKeys
import com.yomitanmobile.data.audio.KanjiAlive
import com.yomitanmobile.data.audio.NativeAudioIndex
import com.yomitanmobile.data.audio.voicevox.VoicevoxAssets
import com.yomitanmobile.data.audio.voicevox.VoicevoxSpeaker
import java.io.File
import java.security.MessageDigest

/** What the voice is asked to say: the reading, with the accent to say it in. */
data class Spoken(val expression: String, val reading: String, val pitch: String)

/**
 * One recording per word, in the phone's order (AudioArchive.find): the user's
 * own folder, then Kanji alive's native speakers, and only then VOICEVOX — a
 * person beats any synthesiser.
 *
 * ffmpeg is used when it is there, to bring every recording to MP3 at one
 * loudness so the kinds sit side by side. It is not required: Linux has it,
 * Windows and macOS usually do not, and Anki plays WAV and AAC as they are.
 */
class AudioMaker(private val config: Config, private val log: (String) -> Unit) : AutoCloseable {

    private val archive: Archive? by lazy {
        config.audioArchive.takeIf { it.isDirectory }?.let(::Archive)?.also { log("audio archive: ${it.fileCount} files") }
    }
    private val nativeRoot: File? by lazy { config.nativeAudio.takeIf { KanjiAlive.isInstalled(it) } }
    private val nativeIndex: NativeAudioIndex? by lazy { nativeRoot?.let { KanjiAlive.loadIndex(it) } }
    private val ffmpeg: String? by lazy { Platform.which("ffmpeg") }
    private var voicevox: VoicevoxSpeaker? = null
    private var voicevoxTried = false

    /**
     * The file name is derived from the word and the source, so a rerun reuses
     * what Anki already holds, while a better source gets a new name — AnkiDroid
     * caches media by name and would keep playing the old recording.
     */
    fun speak(entries: List<Spoken>, outDir: File): Map<Pair<String, String>, File> {
        if (entries.isEmpty()) return emptyMap()
        val dir = File(outDir, "audio").apply { mkdirs() }
        val byKey = entries.associateBy { it.expression to it.reading }
        fun target(key: Pair<String, String>, source: String, ext: String): File {
            val hash = MessageDigest.getInstance("SHA-1")
                .digest("${key.first}|${key.second}".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
            return File(dir, "yomitan_kindle_${source}_$hash.$ext")
        }

        val result = HashMap<Pair<String, String>, File>()
        var fromArchive = 0
        var fromNative = 0
        for (key in byKey.keys) {
            val (recording, source) = archive?.find(key.first, key.second)?.let { it to ARCHIVE_SOURCE }
                ?: nativeRecording(key.first, key.second)?.let { it to NATIVE_SOURCE }
                ?: continue
            val out = if (ffmpeg != null) target(key, source, "mp3") else target(key, source, recording.extension.lowercase())
            val made = out.isFile || if (ffmpeg != null) normalise(recording, out) else runCatching {
                recording.copyTo(out, overwrite = true); true
            }.getOrDefault(false)
            if (made) {
                result[key] = out
                if (source == ARCHIVE_SOURCE) fromArchive++ else fromNative++
            }
        }
        if (archive != null) log("audio: $fromArchive of ${byKey.size} words from the archive")
        if (nativeIndex != null) log("audio: $fromNative of ${byKey.size} words from native speakers (${KanjiAlive.CREDIT})")

        val ext = if (ffmpeg != null) "mp3" else "wav"
        val wanted = (byKey.keys - result.keys).associateWith { target(it, AUDIO_ENGINE, ext) }
        val missing = wanted.filterValues { !it.isFile }
        if (missing.isNotEmpty()) {
            val speaker = speaker()
            if (speaker == null) {
                log("audio: VOICEVOX not installed, ${missing.size} words go without a recording")
            } else {
                for ((key, file) in missing) {
                    val spoken = byKey.getValue(key)
                    runCatching {
                        val wav = speaker.wav(spoken.expression, spoken.reading, spoken.pitch)
                        if (ffmpeg == null) {
                            file.writeBytes(wav)
                        } else {
                            val tmp = File(dir, file.name + ".wav")
                            tmp.writeBytes(wav)
                            // Already normalised by the speaker; only encoded here.
                            encodeMp3(tmp, file)
                            tmp.delete()
                        }
                    }.onFailure { log("tts failed for ${key.first}: ${it.message}") }
                }
            }
        }
        result += wanted.filterValues { it.isFile }
        log("audio: ${result.size} of ${byKey.size} words")
        (byKey.keys - result.keys).forEach { log("audio: no recording for ${it.first} (${it.second})") }
        return result
    }

    private fun nativeRecording(expression: String, reading: String): File? {
        val root = nativeRoot ?: return null
        val name = nativeIndex?.find(expression, reading) ?: return null
        return KanjiAlive.file(root, name).takeIf { it.isFile }
    }

    /** Built once per run: loading the models is the slow part. */
    private fun speaker(): VoicevoxSpeaker? {
        if (voicevoxTried) return voicevox
        voicevoxTried = true
        val root = config.voicevox
        val runtime = File(root, "onnxruntime/lib").listFiles().orEmpty()
            .firstOrNull { it.name.contains("voicevox_onnxruntime") }?.path
        if (runtime == null || !VoicevoxAssets.isInstalled(root)) return null
        voicevox = runCatching { VoicevoxSpeaker(runtime, root) }
            .onFailure { log("VOICEVOX failed to load: ${it.message}") }
            .getOrNull()
        return voicevox
    }

    override fun close() {
        voicevox?.close()
    }

    private fun encodeMp3(input: File, output: File): Boolean = ffmpegRun(
        "-i", input.absolutePath, "-ar", "44100", "-codec:a", "libmp3lame", "-q:a", "3", output.absolutePath
    ) && output.isFile

    private fun normalise(input: File, output: File): Boolean = ffmpegRun(
        "-i", input.absolutePath, "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", "44100", "-ac", "1",
        "-codec:a", "libmp3lame", "-q:a", "3", output.absolutePath
    ) && output.isFile

    private fun ffmpegRun(vararg args: String): Boolean {
        val binary = ffmpeg ?: return false
        return Platform.run(binary, "-v", "error", "-y", *args, timeoutSeconds = 60) != null
    }

    /**
     * The user's pronunciation archive, indexed the way the phone indexes it:
     * [AudioKeys] reads each path, and a lookup takes the best priority (a
     * spelling+reading pair, then a spelling, then a bare reading), ties to
     * the first file walked — `ORDER BY priority, id` in AudioFileDao.
     */
    private class Archive(root: File) {
        private val best = HashMap<String, Pair<Int, File>>()
        val fileCount: Int

        init {
            var files = 0
            root.walkTopDown()
                .filter { it.isFile && AUDIO_EXTENSIONS.any { ext -> it.name.endsWith(ext, ignoreCase = true) } }
                .forEach { file ->
                    files++
                    for ((key, priority) in AudioKeys.keysFor(file.name, file.parentFile?.name.orEmpty())) {
                        val existing = best[key]
                        if (existing == null || priority < existing.first) best[key] = priority to file
                    }
                }
            fileCount = files
        }

        fun find(expression: String, reading: String): File? =
            AudioKeys.lookupKeys(expression, reading).mapNotNull { best[it] }.minByOrNull { it.first }?.second
    }

    companion object {
        /** Bumped whenever the recordings change voice; part of every file name. */
        const val AUDIO_ENGINE = "vv"
        const val ARCHIVE_SOURCE = "ar"
        const val NATIVE_SOURCE = "na"
        val AUDIO_EXTENSIONS = listOf(".mp3", ".ogg", ".opus", ".m4a", ".aac", ".wav", ".flac")
    }
}
