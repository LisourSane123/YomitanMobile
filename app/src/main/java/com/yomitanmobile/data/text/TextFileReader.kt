package com.yomitanmobile.data.text

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a user-picked document into plain text.
 *
 * The whole file is read into memory on purpose: a novel-length EPUB is a few
 * megabytes, and the tokeniser needs the text as one string anyway. [MAX_BYTES]
 * keeps a mistakenly picked video file from taking the process down.
 */
@Singleton
class TextFileReader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    class TooLargeException(val bytes: Long) : Exception("File too large: $bytes bytes")

    class UnsupportedFormatException(val format: TextFileFormat?) :
        Exception("Unsupported format: ${format?.label ?: "unknown"}")

    data class Document(
        val fileName: String,
        val text: String,
        val format: TextFileFormat,
        val charsetName: String,
        val partCount: Int
    )

    suspend fun read(uri: Uri): Document = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val bytes = context.contentResolver.openInputStream(uri)
            ?.use { stream -> stream.readAtMost(MAX_BYTES) }
            ?: throw IllegalStateException("Cannot open $uri")

        // Extension first, content sniff second: a Storage Access Framework Uri
        // often reports application/octet-stream, and users rename files.
        val declared = TextFileFormat.fromFileName(name)
        val format = when (declared) {
            null -> TextExtraction.sniff(bytes)
            TextFileFormat.PLAIN -> TextExtraction.sniff(bytes, fallback = TextFileFormat.PLAIN)
            else -> declared
        }
        if (format == TextFileFormat.PDF) throw UnsupportedFormatException(format)

        val result = TextExtraction.extract(bytes, format)
        Document(
            fileName = name,
            text = result.text,
            format = result.format,
            charsetName = result.charsetName,
            partCount = result.partCount
        )
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Reads up to [limit] bytes, failing loudly rather than truncating silently. */
    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) throw TooLargeException(total)
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    companion object {
        /** 64 MB — far above any subtitle or novel, far below "picked a movie". */
        const val MAX_BYTES = 64 * 1024 * 1024
    }
}
