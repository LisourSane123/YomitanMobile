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

    /**
     * Which single-byte encoding a file that is not valid UTF-8 is guessed to
     * be. The guess cannot be shared: every one of these decodes any byte
     * sequence without complaining, so the WRONG fallback never fails — it
     * silently returns mojibake. Shift_JIS applied to an English EPUB exported
     * from Word (CP1252 curly quotes) turns the whole book into line noise,
     * and Windows-1252 applied to Japanese subtitles does the same the other
     * way; the study language is what says which risk to take.
     */
    enum class Encoding(val fallbacks: List<String>) {
        JAPANESE(listOf("Shift_JIS", "EUC-JP")),
        LATIN(listOf("windows-1252", "ISO-8859-1"))
    }

    fun extract(
        bytes: ByteArray,
        format: TextFileFormat,
        encoding: Encoding = Encoding.JAPANESE
    ): Result = when (format) {
        TextFileFormat.EPUB -> extractEpub(bytes, encoding)
        TextFileFormat.PDF -> throw UnsupportedOperationException("PDF handled by PdfTextExtractor")
        else -> {
            val decoded = decode(bytes, encoding)
            val (text, parts) = when (format) {
                TextFileFormat.SRT -> stripSrt(decoded.first)
                TextFileFormat.VTT -> stripVtt(decoded.first)
                TextFileFormat.ASS -> stripAss(decoded.first)
                TextFileFormat.MARKDOWN -> stripMarkdown(decoded.first) to 0
                else -> decoded.first to 0
            }
            Result(text, format, decoded.second, parts)
        }
    }

    /**
     * Sniffs the format when the file name gave nothing away (or lied).
     * Cheap prefix checks only — the caller has already read the bytes.
     */
    fun sniff(
        bytes: ByteArray,
        fallback: TextFileFormat = TextFileFormat.PLAIN,
        encoding: Encoding = Encoding.JAPANESE
    ): TextFileFormat {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return TextFileFormat.EPUB
        }
        if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-") {
            return TextFileFormat.PDF
        }
        val head = decode(bytes.copyOfRange(0, minOf(bytes.size, 4096)), encoding).first
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
    fun decode(bytes: ByteArray, encoding: Encoding = Encoding.JAPANESE): Pair<String, String> {
        val stripped = stripBom(bytes)
        strictDecode(stripped.first, Charsets.UTF_8)?.let {
            return it to (stripped.second ?: "UTF-8")
        }
        stripped.second?.let { forced ->
            // A BOM declared UTF-16; honour it even though the strict pass failed.
            return String(stripped.first, charsetOrUtf8(forced)) to forced
        }
        for (name in encoding.fallbacks) {
            strictDecode(stripped.first, charsetOrUtf8(name))?.let { return it to name }
        }
        val last = encoding.fallbacks.last()
        return String(stripped.first, charsetOrUtf8(last)) to last
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
    fun extractEpub(bytes: ByteArray, encoding: Encoding = Encoding.JAPANESE): Result {
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
                val raw = decode(zip.readBytes(), encoding).first
                if (isNavigationOrColophon(name, raw)) continue
                val text = htmlToText(raw)
                if (isPublisherPage(text) || isLicencePage(text)) continue
                chapters++
                out.append(text).append('\n')
            }
        }
        return Result(out.toString(), TextFileFormat.EPUB, "UTF-8", chapters)
    }

    /**
     * `<head>` goes with scripts and styles. Its `<title>` repeats the book's
     * full title in EVERY chapter file — one volume counted 電子, 特典 and 付き
     * (from "【電子特典付き】") 26 times each and made cards of them, and
     * inflated クラス, 大嫌い and 結婚 by the same count.
     */
    private val SCRIPT_OR_STYLE = Regex("""<(head|script|style)\b[^>]*>.*?</\1>""", RE_OPTIONS)

    /**
     * `class="p-colophon"` (and `p-colophon2`, the second page of it),
     * `p-caution` (the e-book terms: 再ダウンロード, 複製, 譲渡),
     * `epub:type="toc"`, `<nav …>`: publisher matter, not text.
     */
    private val NON_TEXT_BODY = Regex(
        """<body\b[^>]*\b(class|epub:type)\s*=\s*"[^"]*\b(colophon\d*|toc\d*|nav|caution\d*)\b|<nav\b""",
        RE_OPTIONS
    )

    /**
     * The table of contents and the colophon. The first repeats every chapter
     * title once more; the second is the same publisher boilerplate in every
     * book of an imprint (発行者, ご覧になるリーディングシステムにより…), which
     * reached the deck as ことがある and 発行.
     */
    private fun isNavigationOrColophon(name: String, html: String): Boolean {
        val file = name.substringAfterLast('/')
        if (file == "nav.xhtml" || file == "toc.xhtml") return true
        if (NON_TEXT_BODY.containsMatchIn(html)) return true
        // Some publishers obfuscate the class names (class_s5gw), so the page
        // is recognised by what it says: three of these phrases together are
        // the terms of an e-book, never a scene.
        return COLOPHON_PHRASES.count { it in html } >= COLOPHON_PHRASE_HITS
    }

    /**
     * The other end of the book: the colophon, the staff credits, the author's
     * profile and the e-book notice. One volume of 無職転生 had them on four
     * pages of 120 to 285 characters each, none with three of the phrases
     * above, and they put 発行, 株式会社, 在住, 岐阜県, 小説家 and the editors'
     * surnames into the deck.
     *
     * Length is what makes a single phrase enough: a chapter runs to
     * thousands of characters, so a page this short that also talks like a
     * publisher is one.
     */
    private fun isPublisherPage(text: String): Boolean {
        val length = text.count { !it.isWhitespace() }
        if (length == 0 || length > MAX_PUBLISHER_PAGE_LENGTH) return false
        return PUBLISHER_PHRASES.any { it in text }
    }

    /**
     * The licence and the distributor's notice at the end of an English
     * ebook — Project Gutenberg's runs to several pages and is longer than
     * [MAX_PUBLISHER_PAGE_LENGTH], so length cannot be what finds it. Three
     * of these phrases together is what a licence says and a chapter does
     * not; Pride and Prejudice ended a deck with "infringement",
     * "transcription", "deductible" and "punitive", none of which Jane Austen
     * wrote.
     */
    private fun isLicencePage(text: String): Boolean =
        LICENCE_PHRASES.count { it in text } >= LICENCE_PHRASE_HITS

    private val LICENCE_PHRASES = listOf(
        "Project Gutenberg", "PROJECT GUTENBERG", "public domain", "copyright",
        "Copyright", "trademark", "redistribut", "royalt", "License", "licence",
        "All rights reserved", "no warrant", "Foundation", "donations",
        "electronic work"
    )

    /**
     * Higher than the Japanese colophon's three, because an English novel can
     * legitimately say "copyright" or "foundation" once in its own text.
     */
    private const val LICENCE_PHRASE_HITS = 4

    private val COLOPHON_PHRASES = listOf(
        "本電子書籍", "無断", "複製", "転載", "発行者", "発行所", "著作権", "禁じ", "落丁", "乱丁"
    )

    private const val COLOPHON_PHRASE_HITS = 3

    private val PUBLISHER_PHRASES = COLOPHON_PHRASES + listOf(
        "電子書籍", "発行", "印刷", "製本", "定価", "ISBN", "初出", "小説家になろう",
        "著者プロフィール", "担当編集", "ブックデザイン", "企画", "株式会社", "在住",
        "お問い合わせ", "リーディングシステム", "縦書き", "書籍化"
    )

    /** Longer than this and it is a chapter, whatever words it uses. */
    private const val MAX_PUBLISHER_PAGE_LENGTH = 800
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

    // ---------------------------------------------------------------- markdown

    /**
     * Markdown: prose with syntax in it, and every marker left in becomes a
     * word of its own.
     *
     * Notes and exported articles are where a reader's own material actually
     * lives, and a `.md` file used to be read as plain text — so a link's URL
     * was tokenised (`https`, `github`, `com`), a fenced block put its code
     * identifiers in the deck, and `**słowo**` reached the dictionary with the
     * asterisks attached. What a person reads on the page is what this keeps:
     * heading text yes, the `##` no; link text yes, its target no; code no,
     * because code is not the language being learned.
     *
     * Deliberately not a Markdown parser. The syntax is only ever removed,
     * never interpreted, so a file with half-broken markup still scans — the
     * worst case is a stray marker in a sentence, not a lost chapter.
     */
    fun stripMarkdown(raw: String): String {
        var text = raw.replace("\r\n", "\n")
        // Front matter is metadata (title:, tags:, date:) — a page's worth of
        // key names that are not words of the text.
        text = FRONT_MATTER.replace(text, "")
        text = FENCED_CODE.replace(text, "\n")
        text = INLINE_CODE.replace(text, " ")
        // An image's alt text is usually a file name; a link's text is prose.
        text = MD_IMAGE.replace(text, " ")
        text = MD_INLINE_LINK.replace(text, "$1")
        text = MD_REFERENCE_LINK.replace(text, "$1")
        text = MD_FOOTNOTE_REF.replace(text, " ")
        text = URL.replace(text, " ")

        text = text.lineSequence()
            .filterNot { line ->
                MD_LINK_DEFINITION.matches(line) || MD_RULE.matches(line) || MD_TABLE_DIVIDER.matches(line)
            }
            .map { line ->
                var out = MD_BLOCKQUOTE.replace(line, "")
                out = MD_HEADING.replace(out, "")
                out = MD_TRAILING_HASHES.replace(out, "")
                out = MD_LIST_BULLET.replace(out, "")
                // A table's cells are separate phrases, not one run-on line.
                out.replace('|', ' ')
            }
            .joinToString("\n")

        text = MD_STRONG.replace(text, "$2")
        text = MD_EMPHASIS.replace(text, "$2")
        text = MD_STRIKE.replace(text, "$1")
        // Markdown allows raw HTML, and a `.md` exported from a web page is
        // half of it.
        text = HTML_TAG.replace(text, " ")
        text = MD_ESCAPED.replace(text, "$1")
        return decodeEntities(text)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private val MULTILINE_DOTALL = setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)

    private val FRONT_MATTER = Regex("""\A---[ \t]*\n.*?\n---[ \t]*(\n|$)""", RegexOption.DOT_MATCHES_ALL)

    /** An unterminated block runs to the end of the file, which is what an editor shows too. */
    private val FENCED_CODE =
        Regex("""^[ \t]{0,3}(`{3,}|~{3,})[^\n]*\n.*?(^[ \t]{0,3}\1[^\n]*$|\z)""", MULTILINE_DOTALL)
    private val INLINE_CODE = Regex("""`+[^`\n]*`+""")
    private val MD_IMAGE = Regex("""!\[[^\]]*\]\([^)\n]*\)""")
    private val MD_INLINE_LINK = Regex("""\[([^\]\n]*)\]\([^)\n]*\)""")
    private val MD_REFERENCE_LINK = Regex("""\[([^\]\n]*)\]\[[^\]\n]*\]""")
    private val MD_FOOTNOTE_REF = Regex("""\[\^[^\]\n]*\]""")
    private val MD_LINK_DEFINITION = Regex("""^[ \t]*\[[^\]\n]+\]:[ \t]*\S+.*$""")
    private val URL = Regex("""<?\b(https?|ftp|mailto):\S+""")

    /** `---`, `***`, `___`, `===` — a rule, or the underline of a setext heading. */
    private val MD_RULE = Regex("""^[ \t]*([-=*_])[ \t]*(\1[ \t]*){1,}$""")
    private val MD_TABLE_DIVIDER = Regex("""^[ \t]*\|?[ \t:|-]*-[ \t:|-]*\|?[ \t]*$""")
    private val MD_BLOCKQUOTE = Regex("""^[ \t]*>+[ \t]?""")
    private val MD_HEADING = Regex("""^[ \t]{0,3}#{1,6}[ \t]*""")
    private val MD_TRAILING_HASHES = Regex("""[ \t]+#+[ \t]*$""")
    private val MD_LIST_BULLET = Regex("""^[ \t]*([-*+]|\d+[.)])[ \t]+""")

    // The markers only come off in pairs, so `snake_case` and a lone asterisk
    // in dialogue survive as themselves.
    private val MD_STRONG = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""", RegexOption.DOT_MATCHES_ALL)
    private val MD_EMPHASIS = Regex("""(?<![\w*_])([*_])(?=\S)([^*_\n]+?)(?<=\S)\1(?![\w*_])""")
    private val MD_STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
    private val MD_ESCAPED = Regex("""\\([\\`*_{}\[\]()#+\-.!>~|])""")

    private val NAMED_ENTITIES = listOf(
        "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&apos;" to "'", "&amp;" to "&"
    )
}
