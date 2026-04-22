package com.yomitanmobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PitchAccentStyleTest {

    @Test
    fun fromStorage_returnsDotLine_forStoredDotLineValue() {
        val style = PitchAccentStyle.fromStorage("dot_line")

        assertEquals(PitchAccentStyle.DOT_LINE, style)
    }

    @Test
    fun fromStorage_returnsLegacy_forUnknownValue() {
        val style = PitchAccentStyle.fromStorage("unknown")

        assertEquals(PitchAccentStyle.LEGACY, style)
    }
}
