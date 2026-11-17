package com.yomitanmobile.data.anki

import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.SentenceContextHighlighter

/**
 * The context card: what an onomatopoeia or a grammar point gets instead of
 * a vocabulary card. Both are learnt in the sentence they were met in, so the
 * front is that sentence with the word underlined and nothing else, and the
 * back is a space for the user's own translation of the sentence followed by
 * an explanation. One note type for both; a tag says which kind a note is.
 *
 * Its own note type rather than more fields on `Yomitan-Mobile-v8`: new fields
 * there would change FIELD_NAMES and force a v9 on every card. The name does
 * NOT start with `Yomitan-Mobile-v8`, because the refresher treats that name
 * and anything after a hyphen as the vocabulary type and would restyle this
 * one with the vocabulary templates (docs/adr/0001-context-note-type.md).
 *
 * The look is the vocabulary card's — the same CSS from the same settings —
 * plus the few rules below for what only this card has.
 */
object ContextCard {

    const val MODEL_NAME = "Yomitan-Mobile-Context"

    /**
     * Its name for a few hours on 2026-11-14 — one real sync created it under
     * this name before the rename. Searched together with [MODEL_NAME] until
     * that note type is renamed in Anki.
     */
    const val LEGACY_MODEL_NAME = "Yomitan-Mobile-Onomatopoeia"

    val MODEL_NAMES = listOf(MODEL_NAME, LEGACY_MODEL_NAME)

    /** The tag on every onomatopoeia note, so `tag:onomatopoeia` finds them. */
    const val ONOMATOPOEIA_TAG = "onomatopoeia"

    /**
     * `Word` is short on purpose: the duplicate scan indexes short fields, and
     * it is what makes this note count as "the word is in Anki". `Frequency`
     * carries the same plain rank as a vocabulary card, so AutoReorder orders
     * both kinds together.
     */
    val FIELD_NAMES = listOf(
        "Word", "Reading", "ContextSentence", "ContextTranslation", "Definition",
        "PitchAccent", "Audio", "Frequency", "Book"
    )

    /**
     * Fields that belong to the note, never rebuilt by a refresh: the word as
     * the book wrote it, the sentence, the user's translation, the book.
     */
    val OWNED_BY_NOTE = setOf("Word", "ContextSentence", "ContextTranslation", "Book")

    const val FRONT_TEMPLATE = """
        <div class="front context-front">
            <div class="context-sentence">{{ContextSentence}}</div>
        </div>
    """

    const val BACK_TEMPLATE = """
        <div class="back context-back">
            {{#Frequency}}<div class="freq">{{Frequency}}</div>{{/Frequency}}
            <div class="section"><div class="context-sentence">{{ContextSentence}}</div></div>
            <hr>
            <div class="section translation-section">
                <div class="translation-label">訳</div>
                {{#ContextTranslation}}<div class="translation">{{ContextTranslation}}</div>{{/ContextTranslation}}
                {{^ContextTranslation}}<div class="translation translation-empty">&nbsp;</div>{{/ContextTranslation}}
            </div>
            <hr>
            <div class="section header-section">
                <div class="expression">{{Word}}</div>
                <hr class="word-divider">
                <div class="reading">{{Reading}}</div>
            </div>
            <hr>
            {{#PitchAccent}}<div class="section"><div class="pitch">{{PitchAccent}}</div></div><hr>{{/PitchAccent}}
            <div class="section meaning-section"><div class="meaning">{{Definition}}</div></div>
            <hr>
            {{#Audio}}<div class="section audio-section"><div class="audio">{{Audio}}</div></div><hr>{{/Audio}}
            {{#Book}}<div class="section book-section"><div class="book">{{Book}}</div></div>{{/Book}}
        </div>
    """

    /** Appended to the vocabulary card's CSS. */
    const val EXTRA_CSS = """
        .context-sentence { font-size: 28px; line-height: 1.6; text-align: center; }
        .context-word { text-decoration: underline; text-decoration-thickness: 2px; text-underline-offset: 5px; }
        .translation-section { text-align: left; }
        .translation-label { font-size: 13px; opacity: 0.6; margin-bottom: 4px; }
        .translation { font-size: 18px; min-height: 1.6em; }
        .translation-empty { border: 1px dashed rgba(128, 128, 128, 0.6); border-radius: 6px; min-height: 2.4em; }
        .book-section .book { font-size: 12px; opacity: 0.55; }
    """

    /** Templates and CSS for the note type, from the vocabulary card's CSS. */
    fun styling(vocabularyCss: String): Triple<String, String, String> =
        Triple(vocabularyCss + "\n" + EXTRA_CSS.trimIndent(), FRONT_TEMPLATE.trimIndent(), BACK_TEMPLATE.trimIndent())

    /**
     * The note's fields, built from the vocabulary card's fields for the same
     * word (reading, pitch, meaning, frequency, audio are made exactly as
     * there), plus the sentence and the book.
     */
    fun fields(vocabulary: Map<String, String>, word: String, sentenceHtml: String, book: String): LinkedHashMap<String, String> =
        linkedMapOf(
            "Word" to InputSanitizer.escapeHtml(word),
            "Reading" to vocabulary["Reading"].orEmpty(),
            "ContextSentence" to sentenceHtml,
            "ContextTranslation" to "",
            "Definition" to vocabulary["Meaning"].orEmpty(),
            "PitchAccent" to vocabulary["PitchAccent"].orEmpty(),
            "Audio" to vocabulary["Audio"].orEmpty(),
            "Frequency" to vocabulary["Frequency"].orEmpty(),
            "Book" to InputSanitizer.escapeHtml(book)
        )

    /**
     * The sentence as HTML with the onomatopoeia underlined, or null when the
     * word cannot be found in it — then the word gets a vocabulary card.
     *
     * What is underlined is the form the text uses (ドキドキ for a dictionary
     * どきどき), not the particle or verb after it: 「ドキドキと」「ドキドキして」
     * underline ドキドキ, so the card shows how the word joins the sentence.
     */
    fun underline(sentence: String, tokens: List<String>): String? {
        val text = sentence.trim()
        if (text.isEmpty()) return null
        val range = literalRange(text, tokens) ?: SentenceContextHighlighter.findTargetRange(text, tokens)?.let { trimEnding(text, it) }
            ?: return null
        return buildString {
            append(InputSanitizer.escapeHtml(text.substring(0, range.first)))
            append("<u class=\"context-word\">")
            append(InputSanitizer.escapeHtml(text.substring(range)))
            append("</u>")
            append(InputSanitizer.escapeHtml(text.substring(range.last + 1)))
        }
    }

    /** The longest token found as written, katakana and hiragana folded together. */
    private fun literalRange(text: String, tokens: List<String>): IntRange? {
        val haystack = fold(text)
        return tokens.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
            .firstNotNullOfOrNull { token ->
                haystack.indexOf(fold(token)).takeIf { it >= 0 }?.let { it until it + token.length }
            }
    }

    /** The highlighter's range minus a trailing と/する-form, which belongs to the sentence. */
    private fun trimEnding(text: String, range: IntRange): IntRange {
        val found = text.substring(range)
        val ending = ENDINGS.firstOrNull { found.length > it.length + 1 && found.endsWith(it) } ?: return range
        return range.first until range.last + 1 - ending.length
    }

    private val ENDINGS = listOf("させられる", "している", "していた", "しない", "します", "しました", "して", "した", "する", "と")

    private fun fold(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }
}
