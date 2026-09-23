package com.yomitanmobile.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reader sees on the page is what the scanner should read: heading
 * text but not its hashes, a link's words but not its URL, and no code at all.
 */
class MarkdownExtractionTest {

    private fun strip(raw: String) = TextExtraction.stripMarkdown(raw)

    @Test
    fun `headings, emphasis and bullets lose their markers and keep their words`() {
        val text = strip(
            """
            # Chapter One

            The **quick** brown _fox_ and a ~~slow~~ dog.

            - first item
            - second item
            1. numbered item
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "Chapter One",
                "The quick brown fox and a slow dog.",
                "first item",
                "second item",
                "numbered item"
            ),
            text.lines()
        )
    }

    @Test
    fun `a link keeps its text and loses its target`() {
        val text = strip("See [the whole story](https://example.com/a_b) and ![cover](img/cover.png) here.")
        assertEquals("See the whole story and here.", text.replace(Regex(" +"), " "))
    }

    @Test
    fun `code is not the language being learned`() {
        val text = strip(
            """
            Prose before.

            ```kotlin
            val someIdentifier = compileThis()
            ```

            Prose after `inlineCode` ends.
            """.trimIndent()
        )
        assertFalse("someIdentifier" in text)
        assertFalse("compileThis" in text)
        assertFalse("inlineCode" in text)
        assertTrue("Prose before." in text)
        assertTrue("Prose after" in text)
    }

    @Test
    fun `front matter, rules, tables and footnotes are not prose`() {
        val text = strip(
            """
            ---
            title: My Notes
            tags: [vocab]
            ---

            Real text here.[^1]

            ---

            | word | gloss |
            |------|-------|
            | dog  | pies  |

            [^1]: a footnote
            [ref]: https://example.com
            """.trimIndent()
        )
        assertFalse("title" in text)
        assertFalse("vocab" in text)
        assertFalse("example.com" in text)
        assertTrue("Real text here." in text)
        assertTrue("dog" in text && "pies" in text)
    }

    @Test
    fun `an underscore inside a word is not emphasis`() {
        assertEquals("snake_case stays and *so does a lone star.", strip("snake_case stays and *so does a lone star."))
    }

    @Test
    fun `raw html and blockquotes are unwrapped`() {
        val text = strip("> quoted line\n\n<p>Some <b>bold</b> html.</p>")
        assertTrue("quoted line" in text)
        assertEquals("quoted line\nSome bold html.", text.replace(Regex(" +"), " ").trim())
    }

    @Test
    fun `a markdown file is recognised by its extension and stripped`() {
        assertEquals(TextFileFormat.MARKDOWN, TextFileFormat.fromFileName("notes.md"))
        val result = TextExtraction.extract(
            "# Title\n\nA **word** here.".toByteArray(),
            TextFileFormat.MARKDOWN,
            TextExtraction.Encoding.LATIN
        )
        assertEquals("Title\nA word here.", result.text)
    }

    @Test
    fun `a latin file that is not utf-8 is read as windows-1252, not shift-jis`() {
        // "don't" with a CP1252 curly apostrophe (0x92) — not valid UTF-8.
        val bytes = byteArrayOf(100, 111, 110, 0x92.toByte(), 116)
        val (text, charset) = TextExtraction.decode(bytes, TextExtraction.Encoding.LATIN)
        assertEquals("windows-1252", charset)
        assertEquals("don’t", text)
        // The Japanese profile still guesses a Japanese encoding for the same bytes.
        assertEquals("Shift_JIS", TextExtraction.decode(bytes, TextExtraction.Encoding.JAPANESE).second)
    }
}
