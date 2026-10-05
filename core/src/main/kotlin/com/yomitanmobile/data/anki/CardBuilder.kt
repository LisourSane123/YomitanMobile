package com.yomitanmobile.data.anki

import com.yomitanmobile.data.anki.CardTemplates.CARD_CSS
import com.yomitanmobile.data.anki.CardTemplates.CARD_FRONT_TEMPLATE
import com.yomitanmobile.data.anki.CardTemplates.MAX_EXAMPLES_PER_MEANING
import com.yomitanmobile.data.anki.CardTemplates.MAX_MEANINGS_ON_CARD
import com.yomitanmobile.data.anki.CardTemplates.buildBackTemplate
import com.yomitanmobile.data.anki.CardTemplates.buildCssFromPreferences
import com.yomitanmobile.data.anki.CardTemplates.buildFuriganaSentenceHtml
import com.yomitanmobile.data.anki.CardTemplates.buildPitchAccentHtml
import com.yomitanmobile.domain.model.AnkiCard
import com.yomitanmobile.domain.model.CardProfile
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.SentenceContextHighlighter
import kotlinx.serialization.json.Json

/**
 * Turns a dictionary entry into a card's fields — the part of an export that
 * is the same wherever the card goes: AnkiDroid's provider, an .apkg file, or
 * desktop Anki through AnkiConnect.
 *
 * [english] is the interface language, which decides the language of the
 * grammar and usage labels on the back. The phone reads it from its locale;
 * the desktop tool from the phone's settings.
 */
class CardBuilder(
    private val profile: CardProfile,
    private val english: Boolean
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Picks the sentence that goes under the word on the FRONT of the card.
     *
     * The legacy `exampleSentence` column is only a mirror of the first parsed
     * example, and it is empty for every entry whose examples arrived through
     * the [WordEntry.examples] list (Jitendex) or through a dictionary that
     * ships no examples at all — which is why the front-context option looked
     * dead for most words. Candidates are therefore collected from both
     * sources, and a sentence that actually CONTAINS the target word wins, so
     * the highlight has something to mark.
     */
    fun pickFrontContextSentence(entry: WordEntry): String {
        val tokens = listOf(entry.expression, entry.reading)
        val candidates = buildList {
            if (entry.exampleSentence.isNotBlank()) add(entry.exampleSentence)
            entry.examples.forEach { if (it.jp.isNotBlank()) add(it.jp) }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        return candidates.firstOrNull { SentenceContextHighlighter.containsTarget(it, tokens) }
            ?: candidates.firstOrNull()
            ?: ""
    }

    fun createAnkiCard(
        entry: WordEntry,
        audioFileName: String = "",
        randomFont: String? = null,
        stylePrefs: CardStylePreferences? = null,
        aiSummaryText: String = ""
    ): AnkiCard {
        val isEnglishProfile = profile == com.yomitanmobile.domain.model.CardProfile.ENGLISH
        // On an English card the pitch_accent column holds an IPA string
        // (see the parser's "ipa" meta handling), not a Japanese accent
        // pattern — running it through the mora splitter would produce a
        // diagram of nonsense. It goes to the Reading slot instead, which is
        // otherwise a copy of the front word and would render the headword
        // twice.
        val pitchHtml = if (isEnglishProfile) {
            ""
        } else {
            buildPitchAccentHtml(
                entry.reading.ifBlank { entry.expression },
                entry.pitchAccent,
                stylePrefs
            )
        }
        val readingText = if (isEnglishProfile) entry.pitchAccent else entry.reading
        // The Frequency field carries the LEADING list's own number exactly
        // as the list shipped it (e.g. "4821", or "120000" for a list of
        // occurrence counts), not the starred tier label and not a position
        // derived from it. Anki reorder addons sort new cards by it — ascending
        // for a rank list, descending for a count list, which the frequency
        // screen says. Empty when the leading list does not know the word.
        val freqText = entry.frequencyValue.trim().takeWhile { it.isDigit() }
        
        val frontWord = entry.expression.ifBlank { entry.reading }
        val frontExpression = InputSanitizer.escapeHtml(frontWord)
        val frontContent = if (randomFont != null) "<span style=\"font-family: '$randomFont', sans-serif;\">$frontExpression</span>" else frontExpression
        // Front-context sentence is attached for EVERY word (not just
        // hiragana-only) when the preference is on. The highlighter expands the
        // expression/reading into their inflected forms and underlines the
        // occurrence in the sentence, so the target word is marked whether it's
        // written in kana or kanji.
        val frontContext = if (stylePrefs?.showFrontContextSentence == true) {
            SentenceContextHighlighter.buildHighlightedSentenceHtml(
                sentence = pickFrontContextSentence(entry),
                preferredTokens = listOf(entry.expression, entry.reading)
            )
        } else {
            ""
        }
        
        // Examples whose definitionIndex is set come from Jitendex and belong
        // INSIDE the meaning column under their gloss. The unattached fallback
        // (online Tatoeba responses, pre-seeded SentenceDao, plain JMDict
        // single-pair fields) drops into {{Sentence}} only when there are NO
        // attached examples — otherwise the parser-mirrored exampleSentence
        // would duplicate the first attached gloss example at the bottom.
        val attachedExamples = entry.examples.filter { it.definitionIndex >= 0 }
        val unattachedExamples: List<com.yomitanmobile.domain.model.ExamplePair> = when {
            attachedExamples.isNotEmpty() ->
                entry.examples.filter { it.definitionIndex < 0 }
            // The parsed list is always the richer source (ruby segments,
            // several pairs); the mirrored single columns are only the
            // fallback for entries that never got a parsed list — and they may
            // now hold a seeded sentence picked purely for the front side.
            entry.examples.isNotEmpty() -> entry.examples
            entry.exampleSentence.isNotBlank() -> listOf(
                com.yomitanmobile.domain.model.ExamplePair(
                    jp = entry.exampleSentence,
                    en = entry.exampleSentenceTranslation
                )
            )
            else -> emptyList()
        }

        // Localize the grammar / usage labels to match the app language so the
        // exported card reads the same as the detail screen.
        val posLabel = com.yomitanmobile.util.PartsOfSpeechFormatter.format(entry.partsOfSpeech, english = english)
        val localizedUsageTags = entry.usageTags.map {
            com.yomitanmobile.util.PartsOfSpeechFormatter.localizeUsageTag(it, english)
        }

        // Sanitize the AI summary: it comes from a third-party LLM and may
        // include HTML or scripts. We escape it to text-only and rely on
        // CSS `white-space: pre-wrap` for line breaks.
        val summaryHtml = if (aiSummaryText.isNotBlank()) {
            InputSanitizer.escapeHtml(aiSummaryText.trim())
        } else ""

        return AnkiCard(
            front = frontContent,
            frontContext = frontContext,
            reading = InputSanitizer.escapeHtml(readingText),
            meaning = formatMeaningForCard(entry.definitions, attachedExamples, posLabel, localizedUsageTags, stylePrefs?.furiganaColor.orEmpty()),
            pitchAccent = pitchHtml,
            frequency = InputSanitizer.escapeHtml(freqText),
            audioFileName = audioFileName,
            summary = summaryHtml,
            sentence = buildString {
                unattachedExamples.take(3).forEachIndexed { idx, ex ->
                    if (idx > 0) append("<div class=\"sentence-divider\"></div>")
                    if (ex.jp.isNotBlank()) {
                        append("<div class=\"sentence-jp\">")
                        append(buildFuriganaSentenceHtml(ex, stylePrefs?.furiganaColor.orEmpty()))
                        append("</div>")
                    }
                    if (ex.en.isNotBlank()) {
                        append("<div class=\"sentence-translation\">")
                        append(InputSanitizer.escapeHtml(ex.en))
                        append("</div>")
                    }
                }
            }
        )
    }

    /**
     * Yomitan-style meaning column.
     *   • [posLabel] (e.g. "ichidan verb, transitive verb") sits at the top
     *     in italic accent color so the user sees the grammar tag once,
     *     before the gloss list.
     *   • Each gloss is a numbered list item with up to
     *     [MAX_EXAMPLES_PER_MEANING] example sentence(s) tucked directly
     *     beneath it.
     */
    private fun formatMeaningForCard(
        definitions: List<String>,
        attachedExamples: List<com.yomitanmobile.domain.model.ExamplePair>,
        posLabel: String,
        usageTags: List<String> = emptyList(),
        furiganaColor: String = ""
    ): String {
        val meaningLines = definitions.asSequence()
            .mapIndexed { idx, def -> idx to def.trim().replace(";", ", ") }
            .filter { it.second.isNotBlank() }
            .take(MAX_MEANINGS_ON_CARD)
            .toList()
        if (meaningLines.isEmpty()) return ""

        val examplesByDef = attachedExamples.groupBy { it.definitionIndex }

        return buildString {
            if (posLabel.isNotBlank()) {
                append("<div class=\"pos-line\">")
                append(InputSanitizer.escapeHtml(posLabel))
                append("</div>")
            }
            if (usageTags.isNotEmpty()) {
                append("<div class=\"usage-tags\">")
                append(InputSanitizer.escapeHtml(usageTags.joinToString(" · ")))
                append("</div>")
            }
            append("<ol class=\"meanings\">")
            for ((origIdx, gloss) in meaningLines) {
                append("<li class=\"meaning-item\">")
                append("<span class=\"gloss\">")
                append(InputSanitizer.escapeHtml(gloss))
                append("</span>")
                examplesByDef[origIdx]?.take(MAX_EXAMPLES_PER_MEANING)?.forEach { ex ->
                    append("<div class=\"meaning-ex\">")
                    if (ex.jp.isNotBlank()) {
                        append("<div class=\"meaning-ex-jp\">")
                        append(buildFuriganaSentenceHtml(ex, furiganaColor))
                        append("</div>")
                    }
                    if (ex.en.isNotBlank()) {
                        append("<div class=\"meaning-ex-en\">")
                        append(InputSanitizer.escapeHtml(ex.en))
                        append("</div>")
                    }
                    append("</div>")
                }
                append("</li>")
            }
            append("</ol>")
        }
    }

    /** Kanji column HTML for one entry, ordered as the kanji appear in it. */
    fun buildKanjiBreakdownHtml(
        entry: WordEntry,
        kanjiData: List<com.yomitanmobile.data.local.entity.KanjiEntry>
    ): String {
        return if (kanjiData.isNotEmpty()) {
            val ordered = kanjiData.sortedBy {
                entry.expression.indexOf(it.kanji).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE
            }
            buildString {
                append("<div class=\"kanji-breakdown-title\">Kanji</div>")
                for (kanji in ordered) {
                    val cleanMeanings = parseKanjiMeanings(kanji.meanings)
                        .map { InputSanitizer.escapeHtml(it) }
                        .joinToString(", ")
                    val safeKanji = InputSanitizer.escapeHtml(kanji.kanji)
                    // Swap KANJIDIC's ASCII "." okurigana separator for the round
                    // nakaguro "・" before escaping (た.べる → た・べる).
                    val safeOnyomi = InputSanitizer.escapeHtml(
                        com.yomitanmobile.util.KanjiReadingFormatter.format(kanji.onyomi)
                    )
                    val safeKunyomi = InputSanitizer.escapeHtml(
                        com.yomitanmobile.util.KanjiReadingFormatter.format(kanji.kunyomi)
                    )

                    append("<div class=\"kanji-item\">")
                    append("<span class=\"kanji-char\">").append(safeKanji).append("</span>")
                    val readings = buildString {
                        if (safeOnyomi.isNotEmpty()) append("On: ").append(safeOnyomi)
                        if (safeKunyomi.isNotEmpty()) {
                            if (isNotEmpty()) append(" &nbsp; ")
                            append("Kun: ").append(safeKunyomi)
                        }
                    }
                    if (readings.isNotEmpty()) {
                        append("<span class=\"kanji-readings\">").append(readings).append("</span>")
                    }
                    if (cleanMeanings.isNotEmpty()) {
                        append("<div class=\"kanji-meanings\">")
                            .append(cleanMeanings)
                            .append("</div>")
                    }
                    append("</div>")
                }
            }
        } else ""
    }

    private fun parseKanjiMeanings(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            raw.removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                .filter { it.isNotBlank() }
        }
    }

    /** Styling for a package, resolved exactly as the provider path resolves it. */
    fun packageStyling(stylePrefs: CardStylePreferences?): Triple<String, String, String> {
        val css = if (stylePrefs != null) buildCssFromPreferences(stylePrefs) else CARD_CSS
        val back = buildBackTemplate(
            stylePrefs?.sectionOrder
                ?: com.yomitanmobile.domain.model.CardSection.defaultOrder(),
            profile
        )
        return Triple(css, CARD_FRONT_TEMPLATE, back)
    }

    /**
     * Every field of a card, in [profile]'s field order: [createAnkiCard] plus
     * the kanji breakdown. [audioFileName] is whatever the caller already put
     * in the media folder ("" for none) and [randomFont] the front's font.
     */
    fun buildFields(
        entry: WordEntry,
        stylePrefs: CardStylePreferences?,
        kanjiData: List<com.yomitanmobile.data.local.entity.KanjiEntry> = emptyList(),
        audioFileName: String = "",
        randomFont: String? = null
    ): Array<String> =
        createAnkiCard(entry, audioFileName, randomFont, stylePrefs)
            .copy(kanjiBreakdown = buildKanjiBreakdownHtml(entry, kanjiData))
            .toFieldArray(profile)
}
