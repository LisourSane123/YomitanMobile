package com.yomitanmobile.data.audio.voicevox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoicevoxVoiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `without the download the voice stands aside for the system TTS`() = runBlocking {
        val voice = VoicevoxVoice(context)
        assertEquals(VoicevoxVoice.State.NotInstalled, voice.state.value)
        assertFalse(voice.isActive())
        assertNull(voice.wav("食べる", "たべる", "2"))
    }

    @Test
    fun `switching it off is remembered`() = runBlocking {
        val voice = VoicevoxVoice(context)
        voice.setEnabled(false)
        assertFalse(VoicevoxVoice(context).isActive())
    }
}
