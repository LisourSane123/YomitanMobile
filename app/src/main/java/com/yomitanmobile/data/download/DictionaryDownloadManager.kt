package com.yomitanmobile.data.download

import android.content.Context
import com.yomitanmobile.domain.repository.DictionaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

@Singleton
class DictionaryDownloadManager(
    private val context: Context,
    private val repository: DictionaryRepository
) {
    companion object {
        private const val BUFFER_SIZE = 8192
    }

    private val _currentDownload = MutableStateFlow<DownloadProgress?>(null)
    val currentDownload: StateFlow<DownloadProgress?> = _currentDownload.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    suspend fun downloadAndImport(info: DictionaryDownloadInfo): DownloadResult =
        withContext(Dispatchers.IO) {
            if (_isDownloading.value) {
                return@withContext DownloadResult.Error(
                    info.name,
                    "Inne pobieranie jest w toku"
                )
            }

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

                // Phase 2: Import
                _currentDownload.value = _currentDownload.value?.copy(
                    phase = DownloadPhase.IMPORTING
                )

                val result = FileInputStream(tempFile).use { fis ->
                    repository.importDictionary(
                        inputStream = fis,
                        onProgress = { _ ->
                            _currentDownload.value = _currentDownload.value?.copy(
                                phase = DownloadPhase.IMPORTING
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

    private suspend fun downloadFile(
        urlString: String,
        outputFile: File,
        info: DictionaryDownloadInfo
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = urlString
            var redirectCount = 0
            val maxRedirects = 5

            // Follow redirects manually (GitHub releases use redirects)
            while (redirectCount < maxRedirects) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "YomitanMobile/1.0")
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (newUrl.isNullOrBlank()) throw Exception("Redirect without Location header")
                    currentUrl = if (newUrl.startsWith("http")) newUrl else {
                        val base = URL(currentUrl)
                        URL(base, newUrl).toString()
                    }
                    redirectCount++
                    continue
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP $responseCode: ${connection.responseMessage}")
                }
                break
            }

            if (redirectCount >= maxRedirects) {
                throw Exception("Too many redirects ($maxRedirects) for URL: $urlString")
            }

            val totalBytes = connection?.contentLengthLong ?: -1L
            var bytesDownloaded = 0L

            BufferedInputStream(connection!!.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

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
}
