package com.yomitanmobile.data.audio

import android.content.Context
import android.util.Log
import com.yomitanmobile.data.download.DictionaryDownloadManager
import com.yomitanmobile.data.settings.LanguageSettings
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
 * Native-speaker recordings for English and Spanish on the phone, from
 * Lingua Libre ([LinguaLibre]).
 *
 * Switching it on downloads the INDEX only — one query, a few seconds; a
 * recording is fetched the first time its word is played or put on a card,
 * and kept, so every word needs the network once. Reached through
 * [AudioArchive.find] after the user's own folder, like the Japanese pack, so
 * playback and every card get a person before the system TTS.
 *
 * Per study language: the index is for the language being studied, which can
 * only change with a restart (LanguageSettings), and a Japanese word is never
 * looked up in it.
 */
@Singleton
class LinguaLibreAudio @Inject constructor(
    @ApplicationContext context: Context,
    languageSettings: LanguageSettings
) {

    sealed interface State {
        /** Lingua Libre has too little for this language (Japanese: Kanji alive serves it). */
        data object NotOffered : State
        data object NotInstalled : State
        data object Installing : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    private val language = languageSettings.current
    private val item = LinguaLibre.languageItem(language)
    private val root = File(context.filesDir, "lingualibre/${language.entryTag}")
    private val indexFile = File(root, "index.tsv")
    private val audioDir = File(root, "audio")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(
        when {
            item == null -> State.NotOffered
            indexFile.isFile -> State.Installed
            else -> State.NotInstalled
        }
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var installJob: Job? = null
    private val indexLock = Mutex()
    private var index: LinguaLibre.Index? = null

    fun install() {
        val languageItem = item ?: return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            _state.value = State.Installing
            _state.value = try {
                val connection = DictionaryDownloadManager.openAllowed(
                    LinguaLibre.queryUrl(languageItem),
                    accept = "text/tab-separated-values"
                )
                val text = try {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } finally {
                    connection.disconnect()
                }
                val records = LinguaLibre.parseTsv(text)
                // An empty or truncated answer must not look like an index.
                check(records.size >= MIN_RECORDS) { "Lingua Libre answered with ${records.size} recordings" }
                val built = LinguaLibre.buildIndex(records)
                root.mkdirs()
                val partial = File(root, "index.tsv.partial")
                partial.writeText(built.serialize())
                check(partial.renameTo(indexFile)) { "could not write the index" }
                indexLock.withLock { index = built }
                State.Installed
            } catch (e: Exception) {
                Log.w(TAG, "Lingua Libre index download failed", e)
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        installJob?.cancel()
        indexLock.withLock { index = null }
        root.deleteRecursively()
        _state.value = if (item == null) State.NotOffered else State.NotInstalled
    }

    /**
     * A native speaker saying [word], downloading it on first use; null when
     * the index has no such word, the pack is off, or the network is not
     * there — the caller then synthesises one, as before.
     */
    suspend fun find(word: String): File? {
        if (_state.value != State.Installed) return null
        return withContext(Dispatchers.IO) {
            val loaded = indexLock.withLock {
                index ?: runCatching { LinguaLibre.Index.parse(indexFile.readText()) }
                    .onFailure { Log.w(TAG, "Reading the index failed", it) }
                    .getOrNull()
                    ?.also { index = it }
            } ?: return@withContext null
            val name = loaded.find(word) ?: return@withContext null
            val target = File(audioDir, LinguaLibre.cacheName(name))
            if (target.isFile && target.length() > 0) return@withContext target
            download(name, target)
        }
    }

    private fun download(fileName: String, target: File): File? = try {
        audioDir.mkdirs()
        val connection = DictionaryDownloadManager.openAllowed(LinguaLibre.downloadUrl(fileName), accept = "audio/*")
        val bytes = try {
            // Bounded by hand: InputStream.readNBytes is API 33, the app runs on 26.
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1 shl 14)
                while (out.size() <= MAX_RECORDING_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
        check(bytes.size in 1..MAX_RECORDING_BYTES) { "recording of ${bytes.size} bytes" }
        check(LinguaLibre.looksLikeAudio(bytes.copyOf(minOf(bytes.size, 16)))) { "not audio" }
        val partial = File(target.path + ".partial")
        partial.writeBytes(bytes)
        if (partial.renameTo(target)) target else null
    } catch (e: Exception) {
        Log.w(TAG, "Could not fetch $fileName", e)
        null
    }

    private companion object {
        const val TAG = "LinguaLibreAudio"
        /** Spanish has 17 348 recordings; anything far below means a broken answer. */
        const val MIN_RECORDS = 1_000
        /** One spoken word; a WAV of that is ~100 KB. */
        const val MAX_RECORDING_BYTES = 5 * 1024 * 1024
    }
}
