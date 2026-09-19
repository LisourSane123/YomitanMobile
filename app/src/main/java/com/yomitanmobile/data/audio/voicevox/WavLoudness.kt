package com.yomitanmobile.data.audio.voicevox

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Brings a 16-bit PCM WAV to a fixed loudness.
 *
 * Voices differ by several dB, and a deck that jumps in volume from one card
 * to the next is the thing a listener notices first. The target sits where
 * the phone's own TTS recordings already are (about −18 dBFS RMS), and the
 * gain is capped so no sample goes past −1 dBFS.
 *
 * Anything that is not a plain PCM16 WAV is returned untouched.
 */
object WavLoudness {

    const val TARGET_RMS_DB = -18.0
    const val PEAK_CEILING_DB = -1.0

    fun normalize(wav: ByteArray): ByteArray {
        val data = dataChunk(wav) ?: return wav
        val samples = ShortArray(data.second / 2)
        ByteBuffer.wrap(wav, data.first, data.second).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        if (samples.isEmpty()) return wav

        var sumSquares = 0.0
        var peak = 0
        for (s in samples) {
            sumSquares += s.toDouble() * s
            peak = maxOf(peak, kotlin.math.abs(s.toInt()))
        }
        val rms = sqrt(sumSquares / samples.size)
        if (rms < 1.0 || peak == 0) return wav

        val wanted = 32767.0 * 10.0.pow(TARGET_RMS_DB / 20.0) / rms
        val ceiling = 32767.0 * 10.0.pow(PEAK_CEILING_DB / 20.0) / peak
        val gain = minOf(wanted, ceiling)

        val out = wav.copyOf()
        val buffer = ByteBuffer.wrap(out, data.first, data.second).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            buffer.putShort((s * gain).toInt().coerceIn(-32768, 32767).toShort())
        }
        return out
    }

    /** RMS of a PCM16 WAV in dBFS, for tests and logs. */
    fun rmsDb(wav: ByteArray): Double {
        val data = dataChunk(wav) ?: return Double.NaN
        val samples = ShortArray(data.second / 2)
        ByteBuffer.wrap(wav, data.first, data.second).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
        return 20 * log10(rms / 32767.0)
    }

    /** Offset and length of the `data` chunk of a PCM16 RIFF/WAVE file. */
    private fun dataChunk(wav: ByteArray): Pair<Int, Int>? {
        if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null
        val le = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var pcm16 = false
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = le.getInt(offset + 4)
            val body = offset + 8
            if (size < 0 || body + size > wav.size) {
                // A streamed WAV can leave the data size unset; take the rest.
                return if (id == "data" && pcm16) body to ((wav.size - body) and 1.inv()) else null
            }
            when (id) {
                "fmt " -> pcm16 = le.getShort(body).toInt() == 1 && le.getShort(body + 14).toInt() == 16
                "data" -> return if (pcm16) body to (size and 1.inv()) else null
            }
            offset = body + size + (size and 1)
        }
        return null
    }
}
