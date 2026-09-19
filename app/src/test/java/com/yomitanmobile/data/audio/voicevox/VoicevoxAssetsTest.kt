package com.yomitanmobile.data.audio.voicevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class VoicevoxAssetsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun tar(vararg entries: Pair<String, String?>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val header = ByteArray(512)
            name.toByteArray().copyInto(header, 0)
            val bytes = content?.toByteArray() ?: ByteArray(0)
            "%011o".format(bytes.size).toByteArray().copyInto(header, 124)
            header[156] = (if (content == null) '5' else '0').code.toByte()
            out.write(header)
            out.write(bytes)
            out.write(ByteArray(((512 - bytes.size % 512) % 512)))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    @Test
    fun `files and folders are unpacked where the archive says`() {
        val into = temp.newFolder()
        VoicevoxAssets.untar(
            ByteArrayInputStream(tar("dic/" to null, "dic/sys.dic" to "abc", "dic/a/b.def" to "x".repeat(700))),
            into
        )
        assertEquals("abc", File(into, "dic/sys.dic").readText())
        assertEquals(700, File(into, "dic/a/b.def").length())
    }

    @Test
    fun `an entry reaching outside the folder is refused`() {
        val into = temp.newFolder("install")
        try {
            VoicevoxAssets.untar(ByteArrayInputStream(tar("../escaped.txt" to "no")), into)
            fail("expected the entry to be refused")
        } catch (expected: IllegalStateException) {
        }
        assertFalse(File(into.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `a sibling folder sharing the prefix is outside too`() {
        val into = temp.newFolder("dict")
        try {
            VoicevoxAssets.untar(ByteArrayInputStream(tar("../dict2/x" to "no")), into)
            fail("expected the entry to be refused")
        } catch (expected: IllegalStateException) {
        }
    }

    @Test
    fun `a file that fails its checksum never lands in place`() {
        val root = temp.newFolder()
        try {
            VoicevoxAssets.install(root, open = { ByteArrayInputStream("not the model".toByteArray()) })
            fail("expected a checksum failure")
        } catch (expected: IllegalStateException) {
        }
        assertFalse(File(VoicevoxAssets.modelsDir(root), "n0.vvm").exists())
        assertFalse(VoicevoxAssets.isInstalled(root))
    }

    @Test
    fun `every voice the speaker picks comes from a model that is downloaded`() {
        assertEquals(VoicevoxAssets.MODELS.flatMap { it.styles }, VoicevoxSpeaker.VOICES)
    }
}
