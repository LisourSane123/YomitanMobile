package com.yomitanmobile.data.audio.voicevox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class WavLoudnessTest {

    private fun wav(amplitude: Double, samples: Int = 24_000): ByteArray {
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(24_000).putInt(48_000)
            .putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        for (i in 0 until samples) buffer.putShort((amplitude * 32767 * sin(2 * PI * 440 * i / 24_000)).toInt().toShort())
        return buffer.array()
    }

    @Test
    fun `a quiet recording is brought up to the target`() {
        val out = WavLoudness.normalize(wav(0.02))
        assertEquals(WavLoudness.TARGET_RMS_DB, WavLoudness.rmsDb(out), 0.2)
    }

    @Test
    fun `a loud recording is brought down to the target`() {
        val out = WavLoudness.normalize(wav(0.9))
        assertEquals(WavLoudness.TARGET_RMS_DB, WavLoudness.rmsDb(out), 0.2)
    }

    @Test
    fun `the gain never pushes a peak past the ceiling`() {
        // A near-silent file with one spike: RMS alone would ask for a huge gain.
        val input = wav(0.001)
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).putShort(44 + 200, 16_000)
        val out = WavLoudness.normalize(input)
        val samples = ShortArray((out.size - 44) / 2)
        ByteBuffer.wrap(out, 44, out.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        assertTrue(samples.maxOf { kotlin.math.abs(it.toInt()) } <= 29_204)
    }

    @Test
    fun `the header is left as it was`() {
        val input = wav(0.1)
        assertArrayEquals(input.copyOfRange(0, 44), WavLoudness.normalize(input).copyOfRange(0, 44))
    }

    @Test
    fun `anything that is not PCM16 WAV is returned untouched`() {
        val junk = "ID3 not a wav at all, just some bytes that are long enough".toByteArray()
        assertArrayEquals(junk, WavLoudness.normalize(junk))
    }
}
