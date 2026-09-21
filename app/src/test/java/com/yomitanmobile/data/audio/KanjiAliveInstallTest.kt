package com.yomitanmobile.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The real install, against the real release files, without a network:
 * checksums, the zip, the word list and the index all go through the code the
 * phone runs. Skipped unless pointed at a folder holding `ka_data.csv` and
 * `audio-aac.zip` as downloaded from [KanjiAlive]'s URLs:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*KanjiAliveInstallTest" -Dka.dir=/path/to/downloads
 * ```
 */
class KanjiAliveInstallTest {

    @Test
    fun `the release installs, and every indexed file is there`() {
        val downloads = File(System.getProperty("ka.dir").orEmpty())
        Assume.assumeTrue("pass -Dka.dir", File(downloads, "audio-aac.zip").isFile && File(downloads, "ka_data.csv").isFile)
        val root = Files.createTempDirectory("kanjialive").toFile()
        try {
            val local = mapOf(
                KanjiAlive.WORD_LIST_URL to File(downloads, "ka_data.csv"),
                KanjiAlive.AUDIO_ZIP_URL to File(downloads, "audio-aac.zip")
            )
            KanjiAlive.install(root, open = { url -> local.getValue(url).inputStream() })

            assertTrue(KanjiAlive.isInstalled(root))
            // The zip is gone once unpacked: 66 MB the phone does not keep twice.
            assertFalse(File(root, "audio-aac.zip").exists())
            val index = KanjiAlive.loadIndex(root)!!
            val file = index.find("述語", "じゅつご")!!
            assertTrue(KanjiAlive.file(root, file).length() > 1000)
            assertNull(index.find("足跡", "あしあと"))
            val recordings = KanjiAlive.recordings(File(root, "ka_data.csv").readText())
            assertEquals(recordings.size, File(root, "files").list()!!.size)
            println("installed ${recordings.size} recordings, ${index.size} keys")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a wrong checksum installs nothing`() {
        val root = Files.createTempDirectory("kanjialive").toFile()
        try {
            val result = runCatching {
                KanjiAlive.install(root, open = { "not the release".byteInputStream() })
            }
            assertTrue(result.isFailure)
            assertFalse(KanjiAlive.isInstalled(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
