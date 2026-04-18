package com.yomitanmobile.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryDownloadManagerSecurityTest {

    @Test
    fun isAllowedDownloadUrl_allowsExpectedGithubHostsOverHttps() {
        assertTrue(DictionaryDownloadManager.isAllowedDownloadUrl("https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip"))
        assertTrue(DictionaryDownloadManager.isAllowedDownloadUrl("https://raw.githubusercontent.com/org/repo/main/file.zip"))
        assertTrue(DictionaryDownloadManager.isAllowedDownloadUrl("https://objects.githubusercontent.com/some/path"))
    }

    @Test
    fun isAllowedDownloadUrl_rejectsNonHttpsAndUnknownHosts() {
        assertFalse(DictionaryDownloadManager.isAllowedDownloadUrl("http://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip"))
        assertFalse(DictionaryDownloadManager.isAllowedDownloadUrl("https://example.com/file.zip"))
        assertFalse(DictionaryDownloadManager.isAllowedDownloadUrl("not a url"))
    }
}
