package com.yomitanmobile.data.audio.voicevox

import com.yomitanmobile.data.download.VerifiedDownload
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The VOICEVOX files that are downloaded rather than shipped: two voice models
 * and the Open JTalk dictionary, about 150 MB together, which is why they are
 * an opt-in download and not part of the APK.
 *
 * All of it comes from GitHub releases and is checked against a pinned
 * SHA-256. The layout under the install root is the one VOICEVOX's own
 * downloader produces (`models/vvms/`, `dict/`), so a folder made by that
 * downloader on a laptop works as it is.
 */
object VoicevoxAssets {

    data class Model(
        val fileName: String,
        val url: String,
        val sha256: String,
        val bytes: Long,
        /** The talk styles used from this model, see [VoicevoxSpeaker.VOICES]. */
        val styles: List<Int>
    )

    /**
     * n0: VOICEVOX Nemo 女声1-3 / 男声1-2, generic narrator voices, free for any
     * use with the credit "VOICEVOX Nemo". 6: No.7 アナウンス, non-commercial,
     * credit "VOICEVOX:No.7". Model release 0.16.4 is the one VOICEVOX's
     * 0.17 downloader installs.
     */
    val MODELS = listOf(
        Model(
            fileName = "n0.vvm",
            url = "https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.16.4/n0.vvm",
            sha256 = "e91e2e6ed5cfa6940ff55b61234ba89c631fce3d0146c23685552e7f5d2fe437",
            bytes = 73_074_437,
            styles = listOf(10005, 10007, 10004, 10001, 10000)
        ),
        Model(
            fileName = "6.vvm",
            url = "https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.16.4/6.vvm",
            sha256 = "94f37ddfe76b8bc203642b6a38e071bee236a9409344a6392155b684e02611c3",
            bytes = 58_211_181,
            styles = listOf(30)
        )
    )

    const val DICTIONARY_NAME = "open_jtalk_dic_utf_8-1.11"
    const val DICTIONARY_URL =
        "https://github.com/r9y9/open_jtalk/releases/download/v1.11.1/open_jtalk_dic_utf_8-1.11.tar.gz"
    const val DICTIONARY_SHA256 = "fe6ba0e43542cef98339abdffd903e062008ea170b04e7e2a35da805902f382a"
    const val DICTIONARY_BYTES = 23_646_843L

    /** Where the terms the user accepts before downloading are published. */
    const val TERMS_URL = "https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.16.4/TERMS.txt"

    /** The credit the voice licences ask for, shown wherever the voice is offered. */
    const val CREDIT = "VOICEVOX Nemo, VOICEVOX:No.7"

    val TOTAL_BYTES: Long = MODELS.sumOf { it.bytes } + DICTIONARY_BYTES

    fun modelsDir(root: File) = File(root, "models/vvms")
    fun dictionaryDir(root: File) = File(root, "dict/$DICTIONARY_NAME")

    /** Everything a [VoicevoxSpeaker] needs is on disk. */
    fun isInstalled(root: File): Boolean =
        MODELS.all { File(modelsDir(root), it.fileName).length() == it.bytes } &&
            File(dictionaryDir(root), "sys.dic").isFile

    /**
     * Downloads what is missing into [root]. [open] makes the connection, so
     * the caller decides which hosts are acceptable; every file is verified
     * before it is moved into place, and a failure leaves nothing half-written
     * where [isInstalled] would find it.
     */
    fun install(
        root: File,
        open: (String) -> InputStream,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        var done = 0L
        for (model in MODELS) {
            val target = File(modelsDir(root), model.fileName)
            if (target.length() != model.bytes) {
                VerifiedDownload.download(model.url, model.sha256, target, open) { onProgress(done + it, TOTAL_BYTES) }
            }
            done += model.bytes
            onProgress(done, TOTAL_BYTES)
        }
        if (!File(dictionaryDir(root), "sys.dic").isFile) {
            val archive = File(root, "$DICTIONARY_NAME.tar.gz")
            VerifiedDownload.download(DICTIONARY_URL, DICTIONARY_SHA256, archive, open) { onProgress(done + it, TOTAL_BYTES) }
            val staging = File(root, "dict.partial").apply { deleteRecursively(); mkdirs() }
            GZIPInputStream(archive.inputStream().buffered()).use { untar(it, staging) }
            val dictParent = File(root, "dict").apply { mkdirs() }
            dictionaryDir(root).deleteRecursively()
            if (!File(staging, DICTIONARY_NAME).renameTo(dictionaryDir(root))) {
                error("could not move the dictionary into $dictParent")
            }
            staging.deleteRecursively()
            archive.delete()
        }
        onProgress(TOTAL_BYTES, TOTAL_BYTES)
    }

    /** Removes everything [install] put under [root]. */
    fun uninstall(root: File) {
        root.deleteRecursively()
    }

    /**
     * Just enough of ustar to unpack one tarball of regular files and
     * directories. Names are checked to stay under [into] — a tar entry named
     * `../../x` must not write outside the install folder.
     */
    internal fun untar(input: InputStream, into: File) {
        val header = ByteArray(512)
        val root = into.canonicalFile
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it.toInt() == 0 }) return
            val name = field(header, 0, 100).let { base ->
                val prefix = field(header, 345, 155)
                if (prefix.isNotEmpty()) "$prefix/$base" else base
            }
            val size = field(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val target = File(root, name).canonicalFile
            if (target != root && !target.path.startsWith(root.path + File.separator)) error("tar entry escapes the folder: $name")
            when (type) {
                '5' -> target.mkdirs()
                '0', '\u0000' -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> copyExactly(input, out, size) }
                }
                else -> skip(input, size)
            }
            val padding = (512 - size % 512) % 512
            skip(input, padding)
        }
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return if (read == 0) false else error("truncated tar header")
            read += n
        }
        return true
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) error("truncated tar entry")
            output.write(buffer, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size
        val buffer = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }
}
