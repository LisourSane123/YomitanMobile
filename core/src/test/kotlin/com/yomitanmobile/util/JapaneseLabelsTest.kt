package com.yomitanmobile.util

import com.yomitanmobile.data.anki.CardBuilder
import com.yomitanmobile.domain.model.CardProfile
import com.yomitanmobile.domain.model.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class JapaneseLabelsTest {

    @Test
    fun `jitendex codes read as a kokugo dictionary would name them`() {
        assertEquals("五段動詞（る）、他動詞", PartsOfSpeechFormatter.format("v5r, vt", LabelLanguage.JAPANESE))
        assertEquals("副詞、擬音語・擬態語", PartsOfSpeechFormatter.format("adv on-mim ⭐ ichi", LabelLanguage.JAPANESE))
        assertEquals("通常かな表記", PartsOfSpeechFormatter.localizeUsageTag("usually kana", LabelLanguage.JAPANESE))
        assertEquals("関西方言", PartsOfSpeechFormatter.localizeUsageTag("Kansai dial.", LabelLanguage.JAPANESE))
        // Unknown codes still pass through.
        assertEquals("名詞、xyz", PartsOfSpeechFormatter.format("n xyz", LabelLanguage.JAPANESE))
    }

    @Test
    fun `the old boolean API is unchanged`() {
        assertEquals("godan verb (る), transitive verb", PartsOfSpeechFormatter.format("v5r vt", english = true))
        assertEquals("zwykle kaną", PartsOfSpeechFormatter.localizeUsageTag("usually kana", english = false))
    }

    @Test
    fun `labels follow the meaning's language`() {
        val builder = CardBuilder(CardProfile.JAPANESE, english = false)
        val gloss = WordEntry(expression = "猫", reading = "ねこ", definitions = listOf("cat"))
        assertEquals(LabelLanguage.POLISH, builder.labelLanguageFor(gloss))
        assertEquals(LabelLanguage.JAPANESE, builder.labelLanguageFor(gloss.copy(meaningSource = "旺文社")))
    }
}
