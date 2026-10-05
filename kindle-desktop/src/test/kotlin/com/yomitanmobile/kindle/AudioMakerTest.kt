package com.yomitanmobile.kindle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The audio path on this machine's real voice and recordings — what a dry run
 * skips. Skipped where neither VOICEVOX nor the native pack is installed.
 */
class AudioMakerTest {

    @Test
    fun recordsEveryWordFromTheInstalledSources() {
        val config = Config.load()
        Assume.assumeTrue(
            "no VOICEVOX or native pack installed",
            File(config.voicevox, "models/vvms").isDirectory || File(config.nativeAudio, "index.tsv").exists() ||
                config.nativeAudio.isDirectory
        )
        val out = Files.createTempDirectory("audio").toFile()
        val logs = ArrayList<String>()
        // 学校 is in Kanji alive's examples; 蹂躙 is not, so it needs the voice.
        val words = listOf(Spoken("学校", "がっこう", "0"), Spoken("蹂躙", "じゅうりん", "0"))
        val made = AudioMaker(config, logs::add).use { it.speak(words, out) }
        logs.forEach(::println)
        assertEquals(logs.joinToString("\n"), 2, made.size)
        made.values.forEach { assertTrue("${it.name} is empty", it.length() > 1_000) }
        out.deleteRecursively()
    }

    @Test
    fun polishPlural() {
        assertEquals("1 nowa fiszka", SyncJob.fiszki(1))
        assertEquals("3 nowe fiszki", SyncJob.fiszki(3))
        assertEquals("83 nowe fiszki", SyncJob.fiszki(83))
        assertEquals("12 nowych fiszek", SyncJob.fiszki(12))
        assertEquals("5 nowych fiszek", SyncJob.fiszki(5))
        assertEquals("0 nowych fiszek", SyncJob.fiszki(0))
    }
}
