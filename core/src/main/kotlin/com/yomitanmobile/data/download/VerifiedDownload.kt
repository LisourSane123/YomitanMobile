package com.yomitanmobile.data.download

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * A download that lands only if its SHA-256 is the one pinned in the code.
 *
 * Shared by the add-ons that are fetched rather than shipped — the VOICEVOX
 * voice, the native-speaker recordings — so each is bound by the same rule:
 * the bytes go to a `.partial` file, and only a matching checksum moves them
 * into place. No Android here, so the desktop tools use it as well.
 */
object VerifiedDownload {

    fun download(
        url: String,
        sha256: String,
        target: File,
        open: (String) -> InputStream,
        onBytes: (Long) -> Unit = {}
    ) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".partial")
        val digest = MessageDigest.getInstance("SHA-256")
        open(url).use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                    if (total - lastReport > (1 shl 20)) {
                        onBytes(total)
                        lastReport = total
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) {
            partial.delete()
            error("$url: checksum $actual, expected $sha256")
        }
        target.delete()
        if (!partial.renameTo(target)) error("could not move $partial into place")
    }
}
