package com.yomitanmobile.data.audio

import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.AppLanguage
import kotlinx.coroutines.flow.first as firstMatching
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's Lingua Libre path against the real services: one SPARQL query
 * for the index, one recording, through the downloader's host allowlist and
 * the Commons → upload.wikimedia.org redirect. Network, so only on request:
 * `-Dll.live=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LinguaLibreAudioLiveTest {

    @Test
    fun `the index installs and a word is fetched once, then served from disk`() = runBlocking {
        Assume.assumeTrue(System.getProperty("ll.live") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = LanguageSettings(context).apply { setLanguage(AppLanguage.ENGLISH) }
        val audio = LinguaLibreAudio(context, settings)

        audio.install()
        val state = withTimeout(120_000) {
            audio.state.firstMatching { it is LinguaLibreAudio.State.Installed || it is LinguaLibreAudio.State.Failed }
        }
        assertEquals(LinguaLibreAudio.State.Installed, state)

        val first = audio.find("house")!!
        assertTrue("${first.length()} bytes", first.length() > 10_000)
        assertEquals("RIFF", String(first.readBytes().copyOf(4)))
        // Second time from disk: same file, not rewritten.
        val stamp = first.lastModified()
        val again = audio.find("house")!!
        assertEquals(first.path, again.path)
        assertEquals(stamp, again.lastModified())
        // A casing nobody recorded falls back to the one somebody did.
        assertTrue(audio.find("THROUGH") != null)
        assertNull(audio.find("qwxzv"))
        println("Lingua Libre live: ${first.name}, ${first.length()} bytes")
        audio.uninstall()
    }
}
