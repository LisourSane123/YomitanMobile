package com.yomitanmobile.data.audio.voicevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * The real engine, on a desktop JVM. Skipped unless pointed at an install:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*VoicevoxSpeakerTest" \
 *   -Dvoicevox.root=~/.local/share/kindle-sync/voicevox/core \
 *   -Dvoicevox.onnxruntime=~/.local/share/kindle-sync/voicevox/core/onnxruntime/lib/libvoicevox_onnxruntime.so.1.17.3
 * ```
 */
class VoicevoxSpeakerTest {

    @Test
    fun `a word comes out as normalised speech`() {
        val root = System.getProperty("voicevox.root").orEmpty()
        val runtime = System.getProperty("voicevox.onnxruntime").orEmpty()
        Assume.assumeTrue("pass -Dvoicevox.root and -Dvoicevox.onnxruntime", root.isNotEmpty() && runtime.isNotEmpty())
        Assume.assumeTrue(VoicevoxAssets.isInstalled(File(root)))

        VoicevoxSpeaker(runtime, File(root)).use { speaker ->
            val wav = speaker.wav("突きつける", "つきつける", "4")
            assertEquals("RIFF", String(wav, 0, 4))
            assertTrue("too short: ${wav.size}", wav.size > 20_000)
            assertEquals(WavLoudness.TARGET_RMS_DB, WavLoudness.rmsDb(wav), 1.5)
            // No accent known: the reading goes in as text.
            assertTrue(speaker.wav("ブサメン", "ブサメン", "").size > 10_000)
        }
    }
}
