package com.yomitanmobile.data.text

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

private val RE_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

/**
 * Turns a subtitle / ebook / plain-text file into the running text a reader
 * actually sees: no timecodes, no styling overrides, no markup, and — for
 * EPUB — no furigana `<rt>` readings, which would otherwise be tokenised as
 * separate words and double-count every kanji compound in the book.
 *
 * Pure Kotlin (no Android types) so the format handling is unit-testable;
 * [TextFileReader] owns the Uri/ContentResolver side.
 */
object TextExtraction {

    /** Extracted plain text plus how it was read, for the UI's "what did I get" line. */
    data class Result(
        val text: String,
        val format: TextFileFormat,
        val charsetName: String,
        /** Sub-documents read (EPUB chapters, subtitle cues). 0 when not applicable. */
        val partCount: Int = 0
    )

    fun extract(bytes: ByteArray, format: TextFileFormat): Result = when (format) {
        TextFileFormat.EPUB -> extractEpub(bytes)
        TextFileFormat.PDF -> throw UnsupportedOperationException("PDF handled by PdfTextExtractor")
        else -> {
            val decoded = decode(bytes)
            val (text, parts) = when (format) {
                TextFileFormat.SRT -> stripSrt(decoded.first)
                TextFileFormat.VTT -> stripVtt(decoded.first)
                TextFileFormat.ASS -> stripAss(decoded.first)
                else -> decoded.first to 0
            }
            Result(text, format, decoded.second, parts)
        }
    }

    /**
     * Sniffs the format when the file name gave nothing away (or lied).
     * Cheap prefix checks only — the caller has already read the bytes.
     */
    fun sniff(bytes: ByteArray, fallback: TextFileFormat = TextFileFormat.PLAIN): TextFileFormat {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return TextFileFormat.EPUB
        }
        if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-") {
            return TextFileFormat.PDF
        }
        val head = decode(bytes.copyOfRange(0, minOf(bytes.size, 4096))).first
        return when {
            head.startsWith("WEBVTT") -> TextFileFormat.VTT
            head.contains("[Script Info]") || head.contains("Dialogue:") -> TextFileFormat.ASS
            SRT_CUE.containsMatchIn(head) -> TextFileFormat.SRT
            else -> fallback
        }
    }

    // ---------------------------------------------------------------- charset

    /**
     * Japanese subtitles come in UTF-8, Shift_JIS and (rarely) EUC-JP, and the
     * file itself rarely says which. Strict UTF-8 first: it is the only one of
     * the three whose multi-byte sequences are self-validating, so a clean
     * decode is proof. Anything that fails falls back to Shift_JIS, which
     * never fails but would turn a UTF-8 file into mojibake — hence the order.
     */
    fun decode(bytes: ByteArray): Pair<String, String> {
        val stripped = stripBom(bytes)
        strictDecode(stripped.first, Charsets.UTF_8)?.let {
            return it to (stripped.second ?: "UTF-8")
        }
        stripped.second?.let { forced ->
            // A BOM declared UTF-16; honour it even though the strict pass failed.
            return String(stripped.first, charsetOrUtf8(forced)) to forced
        }
        for (name in listOf("Shift_JIS", "EUC-JP")) {
            strictDecode(stripped.first, charsetOrUtf8(name))?.let { return it to name }
        }
        return String(stripped.first, charsetOrUtf8("Shift_JIS")) to "Shift_JIS"
    }

    private fun charsetOrUtf8(name: String) =
        runCatching { charset(name) }.getOrDefault(Charsets.UTF_8)

    private fun charset(name: String) = java.nio.charset.Charset.forName(name)

    private fun strictDecode(bytes: ByteArray, cs: java.nio.charset.Charset): String? {
        val decoder: CharsetDecoder = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }

    /** Returns the byte array without its BOM, plus the charset the BOM declared. */
    private fun stripBom(bytes: ByteArray): Pair<ByteArray, String?> = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> bytes.copyOfRange(3, bytes.size) to "UTF-8"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            bytes.copyOfRange(2, bytes.size) to "UTF-16LE"
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            bytes.copyOfRange(2, bytes.size) to "UTF-16BE"
        else -> bytes to null
    }

    // --------------------------------------------------------------- subtitles

    private val SRT_CUE =
        Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}""")
    private val HTML_TAG = Regex("""<[^>]*>""")
    private val ASS_OVERRIDE = Regex("""\{[^}]*\}""")
    private val INDEX_LINE = Regex("""^\d+$""")

    /** SubRip: drop the sequence numbers and the timecode lines, keep the cues. */
    fun stripSrt(raw: String): Pair<String, Int> {
        var cues = 0
        val out = StringBuilder()
        var previousWasCue = false
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> previousWasCue = false
                SRT_CUE.containsMatchIn(trimmed) -> {
                    cues++
                    previousWasCue = true
                }
                INDEX_LINE.matches(trimmed) && !previousWasCue -> Unit
                else -> {
                    out.append(cleanInline(trimmed)).append('\n')
                    previousWasCue = false
                }
            }
        }
        return out.toString() to cues
    }

    /** WebVTT: same shape as SRT plus a header, NOTE blocks and cue settings. */
    fun stripVtt(raw: String): Pair<String, Int> {
        var cues = 0
        val out = StringBuilder()
        var inNote = false
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> inNote = false
                trimmed.startsWith("WEBVTT") -> Unit
                trimmed.startsWith("NOTE") || trimmed.startsWith("STYLE") ||
                    trimmed.startsWith("REGION") -> inNote = true
                inNote -> Unit
                trimmed.contains("-->") -> cues++
                INDEX_LINE.matches(trimmed) -> Unit
                else -> out.append(cleanInline(trimmed)).append('\n')
            }
        }
        return out.toString() to cues
    }

    /**
     * ASS/SSA: only `Dialogue:` lines carry text, and only after the 9 header
     * fields. Karaoke/drawing lines (`\p1`) are vector shapes, not words.
     */
    fun stripAss(raw: String): Pair<String, Int> {
        var cues = 0
        val out = StringBuilder()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            val body = trimmed.substringAfter(':').split(',', limit = 10)
            if (body.size < 10) continue
            val text = body[9]
            if (text.contains("\\p1")) continue
            cues++
            val cleaned = ASS_OVERRIDE.replace(text, "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
            out.append(cleanInline(cleaned)).append('\n')
        }
        return out.toString() to cues
    }

    private fun cleanInline(text: String): String =
        decodeEntities(HTML_TAG.replace(text, "")).trim()

    // -------------------------------------------------------------------- epub

    /**
     * EPUB is a ZIP of XHTML documents. Chapters are read in the archive's own
     * order rather than through the spine in `content.opf`: word extraction
     * does not care about reading order, and skipping the OPF parse means a
     * mildly malformed book still scans.
     *
     * `<rt>` (furigana) and `<rp>` content is dropped before the tags are
     * stripped — keeping it would feed every kanji compound's reading into the
     * tokeniser as if it were a separate word.
     */
    fun extractEpub(bytes: ByteArray): Result {
        val out = StringBuilder()
        var chapters = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.lowercase()
                val isDocument = name.endsWith(".xhtml") || name.endsWith(".html") ||
                    name.endsWith(".htm")
                if (!isDocument) continue
                val raw = decode(zip.readBytes()).first
                chapters++
                out.append(htmlToText(raw)).append('\n')
            }
        }
        return Result(out.toString(), TextFileFormat.EPUB, "UTF-8", chapters)
    }

    private val SCRIPT_OR_STYLE = Regex("""<(script|style)\b[^>]*>.*?</\1>""", RE_OPTIONS)
    private val RUBY_READING = Regex("""<(rt|rp)\b[^>]*>.*?</\1>""", RE_OPTIONS)
    private val BLOCK_BREAK = Regex("""</(p|div|h[1-6]|li|br|tr)\s*>|<br\s*/?>""", RE_OPTIONS)

    fun htmlToText(html: String): String {
        var text = SCRIPT_OR_STYLE.replace(html, " ")
        text = RUBY_READING.replace(text, "")
        text = BLOCK_BREAK.replace(text, "\n")
        text = HTML_TAG.replace(text, "")
        return decodeEntities(text)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private val NUMERIC_ENTITY = Regex("""&#(x?)([0-9a-fA-F]+);""")

    private fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        var out = NUMERIC_ENTITY.replace(text) { match ->
            val radix = if (match.groupValues[1].isEmpty()) 10 else 16
            val code = match.groupValues[2].toIntOrNull(radix)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        for ((entity, replacement) in NAMED_ENTITIES) out = out.replace(entity, replacement)
        return out
    }

    private val NAMED_ENTITIES = listOf(
        "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&apos;" to "'", "&amp;" to "&"
    )
}
