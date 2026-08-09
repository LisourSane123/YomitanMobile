package com.yomitanmobile.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TextExtractionTest {

    @Test
    fun `srt keeps dialogue and drops indices and timecodes`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            今日はいい天気ですね。

            2
            00:00:04,000 --> 00:00:06,000
            <i>そうだね。</i>
        """.trimIndent()

        val (text, cues) = TextExtraction.stripSrt(srt)

        assertEquals(2, cues)
        assertTrue(text.contains("今日はいい天気ですね。"))
        assertTrue(text.contains("そうだね。"))
        assertFalse(text.contains("-->"))
        assertFalse(text.contains("<i>"))
        assertFalse(text.contains("00:00"))
    }

    @Test
    fun `srt keeps a numeric line that is part of the dialogue`() {
        // A cue whose text is just a number must not be mistaken for the next
        // cue's index — the line right after a timecode is always dialogue.
        val srt = "1\n00:00:01,000 --> 00:00:03,000\n2000\n"
        val (text, _) = TextExtraction.stripSrt(srt)
        assertEquals("2000", text.trim())
    }

    @Test
    fun `ass takes only the dialogue field and strips override blocks`() {
        val ass = """
            [Script Info]
            Title: test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\pos(960,1050)}走れ！
            Dialogue: 0,0:00:04.00,0:00:05.00,Sign,,0,0,0,,{\p1}m 0 0 l 10 10{\p0}
            Comment: 0,0:00:06.00,0:00:07.00,Default,,0,0,0,,無視される
        """.trimIndent()

        val (text, cues) = TextExtraction.stripAss(ass)

        assertEquals(1, cues)
        assertEquals("走れ！", text.trim())
        assertFalse(text.contains("無視される"))
    }

    @Test
    fun `ass keeps text containing commas`() {
        // The dialogue field is the 10th; commas inside it must survive the split.
        val ass = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,はい, そうです\n"
        val (text, _) = TextExtraction.stripAss(ass)
        assertEquals("はい, そうです", text.trim())
    }

    @Test
    fun `vtt drops the header, notes and cue timings`() {
        val vtt = """
            WEBVTT

            NOTE this is a comment
            still the comment

            00:01.000 --> 00:04.000
            始めましょう。
        """.trimIndent()

        val (text, cues) = TextExtraction.stripVtt(vtt)

        assertEquals(1, cues)
        assertEquals("始めましょう。", text.trim())
    }

    @Test
    fun `epub reading drops furigana rt content`() {
        val chapter = """
            <html><head><style>p { color: red }</style></head><body>
            <p><ruby>漢字<rt>かんじ</rt></ruby>を読む。</p>
            </body></html>
        """.trimIndent()
        val bytes = zipOf("OEBPS/chapter1.xhtml" to chapter, "mimetype" to "application/epub+zip")

        val result = TextExtraction.extractEpub(bytes)

        assertEquals(1, result.partCount)
        assertTrue(result.text.contains("漢字を読む。"))
        // The reading must not survive: it would be tokenised as its own word.
        assertFalse(result.text.contains("かんじ"))
        assertFalse(result.text.contains("color: red"))
    }

    @Test
    fun `shift-jis subtitles are decoded, not mojibaked`() {
        val japanese = "静かな夜だ。"
        val bytes = japanese.toByteArray(charset("Shift_JIS"))

        val (text, charsetName) = TextExtraction.decode(bytes)

        assertEquals(japanese, text)
        assertEquals("Shift_JIS", charsetName)
    }

    @Test
    fun `utf-8 with a BOM decodes without the marker`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "テスト".toByteArray(Charsets.UTF_8)

        val (text, charsetName) = TextExtraction.decode(bytes)

        assertEquals("テスト", text)
        assertEquals("UTF-8", charsetName)
    }

    @Test
    fun `sniffing recognises formats the file name got wrong`() {
        assertEquals(
            TextFileFormat.ASS,
            TextExtraction.sniff("[Script Info]\nTitle: x".toByteArray())
        )
        assertEquals(
            TextFileFormat.SRT,
            TextExtraction.sniff("1\n00:00:01,000 --> 00:00:02,000\nはい".toByteArray())
        )
        assertEquals(TextFileFormat.EPUB, TextExtraction.sniff(zipOf("a.txt" to "x")))
        assertEquals(TextFileFormat.PDF, TextExtraction.sniff("%PDF-1.7\n...".toByteArray()))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("a<b & c", TextExtraction.htmlToText("<p>a&lt;b &amp; c</p>"))
        assertEquals("あ", TextExtraction.htmlToText("<p>&#12354;</p>"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
