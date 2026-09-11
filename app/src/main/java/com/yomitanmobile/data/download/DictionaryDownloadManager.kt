package com.yomitanmobile.data.download

import android.content.Context
import android.util.Log
import com.yomitanmobile.domain.repository.DictionaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Singleton

data class DownloadProgress(
    val dictionaryId: String,
    val dictionaryName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val phase: DownloadPhase
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}

enum class DownloadPhase {
    DOWNLOADING,
    IMPORTING,
    COMPLETED,
    ERROR
}

sealed class DownloadResult {
    data class Success(val dictionaryName: String, val entriesImported: Int) : DownloadResult()
    data class Error(val dictionaryName: String, val message: String) : DownloadResult()
}

/** Where a queued dictionary is in its life. */
enum class QueueState { WAITING, RUNNING, DONE, FAILED, CANCELLED }

/**
 * One dictionary in the install queue.
 *
 * The queue outlives the screen that filled it: installing a dictionary takes
 * minutes, and nobody wants to sit on the download screen watching a bar when
 * they could be mining cards.
 */
data class QueuedDownload(
    val info: DictionaryDownloadInfo,
    val state: QueueState = QueueState.WAITING,
    /** Entries imported, once it is [QueueState.DONE]. */
    val entriesImported: Int = 0,
    val error: String? = null
)

@Singleton
class DictionaryDownloadManager(
    private val context: Context,
    private val repository: DictionaryRepository,
    /**
     * Application-scoped: an install must survive the screen that started it.
     * Downloads took minutes and died the moment the user navigated away,
     * because the work ran in the download screen's ViewModel scope.
     */
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DictionaryDownload"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private const val MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L

        private val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )

        internal fun isAllowedDownloadUrl(url: String): Boolean {
            return try {
                val parsed = URL(url)
                val host = parsed.host.lowercase(Locale.ROOT)
                parsed.protocol.equals("https", ignoreCase = true) &&
                    host in ALLOWED_DOWNLOAD_HOSTS
            } catch (_: Exception) {
                false
            }
        }
    }

    private val _currentDownload = MutableStateFlow<DownloadProgress?>(null)
    val currentDownload: StateFlow<DownloadProgress?> = _currentDownload.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    /**
     * Everything asked for, in the order it was asked for: what is installing
     * now, what is waiting, and what finished.
     */
    private val _queue = MutableStateFlow<List<QueuedDownload>>(emptyList())
    val queue: StateFlow<List<QueuedDownload>> = _queue.asStateFlow()

    /**
     * Outcomes, for whatever screen happens to be open. Buffered and
     * drop-oldest: a rendezvous flow would park the worker forever whenever
     * nobody is looking at the download screen, which is the normal case now
     * that the queue runs in the background.
     */
    private val _results = MutableSharedFlow<DownloadResult>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val results: SharedFlow<DownloadResult> = _results.asSharedFlow()

    private val downloadMutex = Mutex()
    private val queueMutex = Mutex()
    private var worker: Job? = null

    /**
     * How one queued dictionary is installed. A property rather than a direct
     * call so the queue's own behaviour — order, de-duplication, cancelling,
     * what happens after a failure — can be tested without a network.
     */
    internal var installer: suspend (DictionaryDownloadInfo) -> DownloadResult =
        { downloadAndImport(it) }

    /**
     * Adds dictionaries to the install queue and starts working through it if
     * nothing is running.
     *
     * Returns immediately: the work happens on [scope], which belongs to the
     * application rather than to any screen, so leaving the download screen —
     * or the whole settings tree — no longer cancels an install halfway
     * through. Anything already queued or installing is not queued twice.
     */
    fun enqueue(dictionaries: List<DictionaryDownloadInfo>) {
        if (dictionaries.isEmpty()) return
        scope.launch {
            queueMutex.withLock {
                val pending = _queue.value.filter { it.state == QueueState.WAITING || it.state == QueueState.RUNNING }
                    .mapTo(HashSet()) { it.info.id }
                val added = dictionaries.filterNot { it.id in pending }.map { QueuedDownload(it) }
                if (added.isEmpty()) return@withLock
                _queue.value = _queue.value + added
                if (worker?.isActive != true) worker = scope.launch { drainQueue() }
            }
        }
    }

    fun enqueue(info: DictionaryDownloadInfo) = enqueue(listOf(info))

    /**
     * Drops a dictionary from the queue. The one currently installing keeps
     * going — a half-imported dictionary is worse than a finished one, and the
     * import is the long part anyway.
     */
    fun cancelQueued(id: String) {
        _queue.value = _queue.value.map {
            if (it.info.id == id && it.state == QueueState.WAITING) it.copy(state = QueueState.CANCELLED) else it
        }
    }

    /** Clears everything that is no longer going to change. */
    fun clearFinished() {
        _queue.value = _queue.value.filter {
            it.state == QueueState.WAITING || it.state == QueueState.RUNNING
        }
    }

    /** Installs queued dictionaries one at a time until none are waiting. */
    private suspend fun drainQueue() {
        while (true) {
            val next = _queue.value.firstOrNull { it.state == QueueState.WAITING } ?: return
            setState(next.info.id) { it.copy(state = QueueState.RUNNING) }
            val result = try {
                installer(next.info)
            } catch (t: Throwable) {
                // Throwable, not Exception: the import path allocates in
                // megabytes and an OutOfMemoryError here would otherwise reach
                // the uncaught handler and take the app down mid-queue.
                Log.w(TAG, "Install of ${next.info.name} failed", t)
                DownloadResult.Error(next.info.name, "${t.javaClass.simpleName}: ${t.message ?: "no message"}")
            }
            when (result) {
                is DownloadResult.Success -> setState(next.info.id) {
                    it.copy(state = QueueState.DONE, entriesImported = result.entriesImported)
                }
                is DownloadResult.Error -> setState(next.info.id) {
                    it.copy(state = QueueState.FAILED, error = result.message)
                }
            }
            _results.emit(result)
        }
    }

    private fun setState(id: String, transform: (QueuedDownload) -> QueuedDownload) {
        _queue.value = _queue.value.map { if (it.info.id == id) transform(it) else it }
    }

    suspend fun downloadAndImport(info: DictionaryDownloadInfo): DownloadResult {
        if (!downloadMutex.tryLock()) {
            return DownloadResult.Error(info.name, "Inne pobieranie jest w toku")
        }
        try {
            return withContext(Dispatchers.IO) {
                _isDownloading.value = true
                val tempFile = File(context.cacheDir, "dict_download_${info.id}.zip")

                try {
                    // Phase 1: Download
                    _currentDownload.value = DownloadProgress(
                        dictionaryId = info.id,
                        dictionaryName = info.name,
                        bytesDownloaded = 0,
                        totalBytes = -1,
                        phase = DownloadPhase.DOWNLOADING
                    )

                    downloadFile(info.url, tempFile, info)
                    verifyZipSignature(tempFile)
                    verifySha256(tempFile, info.sha256)

                    // Phase 2: Import
                    _currentDownload.value = _currentDownload.value?.copy(
                        phase = DownloadPhase.IMPORTING
                    )

                    val result = FileInputStream(tempFile).use { fis ->
                        repository.importDictionary(
                            inputStream = fis,
                            onProgress = { progress ->
                                _currentDownload.value = _currentDownload.value?.copy(
                                    phase = DownloadPhase.IMPORTING,
                                    bytesDownloaded = progress.entriesProcessed.toLong(),
                                    totalBytes = progress.totalEntries.toLong()
                                )
                            }
                        )
                    }

                    // Phase 3: Complete
                    _currentDownload.value = _currentDownload.value?.copy(
                        phase = DownloadPhase.COMPLETED
                    )

                    if (result.success) {
                        DownloadResult.Success(info.name, result.entriesImported)
                    } else {
                        DownloadResult.Error(info.name, result.errorMessage ?: "Import failed")
                    }
                } catch (e: Exception) {
                    _currentDownload.value = _currentDownload.value?.copy(
                        phase = DownloadPhase.ERROR
                    )
                    DownloadResult.Error(info.name, e.message ?: "Unknown error")
                } finally {
                    tempFile.delete()
                    _isDownloading.value = false
                    // Clear progress after a delay so UI can show final state
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            kotlinx.coroutines.delay(2000)
                            _currentDownload.value = null
                        }
                    } catch (_: Exception) {
                        _currentDownload.value = null
                    }
                }
            }
        } finally {
            downloadMutex.unlock()
        }
    }

    private suspend fun downloadFile(
        urlString: String,
        outputFile: File,
        info: DictionaryDownloadInfo
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            if (!isAllowedDownloadUrl(urlString)) {
                throw Exception("Niedozwolony adres pobierania")
            }

            var currentUrl = urlString
            var redirectCount = 0

            // Follow redirects manually (GitHub releases use redirects)
            while (redirectCount < MAX_REDIRECTS) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "YomitanMobile/1.0")
                    setRequestProperty("Accept", "application/octet-stream")
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (newUrl.isNullOrBlank()) throw Exception("Redirect without Location header")
                    currentUrl = resolveRedirectUrl(currentUrl, newUrl)
                    if (!isAllowedDownloadUrl(currentUrl)) {
                        throw Exception("Redirect do niedozwolonego hosta")
                    }
                    redirectCount++
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP $responseCode: ${connection.responseMessage}")
                }
                break
            }

            if (redirectCount >= MAX_REDIRECTS) {
                throw Exception("Too many redirects ($MAX_REDIRECTS) for URL: $urlString")
            }

            val totalBytes = connection?.contentLengthLong ?: -1L
            if (totalBytes > MAX_DOWNLOAD_BYTES) {
                throw Exception("Plik jest zbyt duży (${totalBytes / (1024 * 1024)} MB)")
            }
            var bytesDownloaded = 0L

            BufferedInputStream(connection!!.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        if (bytesDownloaded > MAX_DOWNLOAD_BYTES) {
                            throw Exception("Pobrany plik przekracza limit ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB")
                        }

                        _currentDownload.value = DownloadProgress(
                            dictionaryId = info.id,
                            dictionaryName = info.name,
                            bytesDownloaded = bytesDownloaded,
                            totalBytes = totalBytes,
                            phase = DownloadPhase.DOWNLOADING
                        )
                    }
                }
            }

        } finally {
            connection?.disconnect()
        }
    }

    private fun resolveRedirectUrl(baseUrl: String, locationHeader: String): String {
        return if (locationHeader.startsWith("http", ignoreCase = true)) {
            locationHeader
        } else {
            URL(URL(baseUrl), locationHeader).toString()
        }
    }

    private fun verifyZipSignature(file: File) {
        FileInputStream(file).use { input ->
            val signature = ByteArray(4)
            val read = input.read(signature)
            if (read < 4 || signature[0] != 0x50.toByte() || signature[1] != 0x4B.toByte()) {
                throw Exception("Pobrany plik nie jest poprawnym archiwum ZIP")
            }
        }
    }

    private fun verifySha256(file: File, expectedSha256: String?) {
        val expected = expectedSha256?.trim()?.lowercase(Locale.ROOT) ?: return
        if (expected.isBlank()) return

        val actual = sha256(file)
        if (actual != expected) {
            throw Exception("Niepoprawna suma kontrolna pobranego pliku")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
