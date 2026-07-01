package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KanjiReadingFormatterTest {

    @Test
    fun replacesOkuriganaDotWithNakaguro() {
        assertEquals("た・べる", KanjiReadingFormatter.format("た.べる"))
    }

    @Test
    fun handlesMultipleReadingsKeepingCommaSeparator() {
        assertEquals("た・べる, く・う", KanjiReadingFormatter.format("た.べる, く.う"))
    }

    @Test
    fun leavesPlainReadingUntouched() {
        assertEquals("ショク", KanjiReadingFormatter.format("ショク"))
    }

    @Test
    fun emptyStringStaysEmpty() {
        assertEquals("", KanjiReadingFormatter.format(""))
    }
}
