package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.PitchAccentStyle
import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.SentenceContextHighlighter

/**
 * What a card looks like: the note type's templates and CSS, the pitch-accent
 * diagram, tap-to-reveal furigana, and the card-style screen's preview.
 *
 * Was the companion object of AnkiCardCreator, which tied every one of these
 * pure string builders to an Android class. The desktop Kindle tool writes the
 * same cards through AnkiConnect, so the design lives here, once.
 */
object CardTemplates {
    const val MAX_MEANINGS_ON_CARD = 6
    const val MAX_EXAMPLES_PER_MEANING = 1
    const val DEFAULT_PITCH_ACCENT_COLOR = "#ff8a65"
    const val DEFAULT_PITCH_LOW_COLOR = "#777777"
    const val DEFAULT_PITCH_KANA_COLOR = "#80cbc4"

    /**
     * Renders an example sentence as tap-to-reveal furigana HTML for the
     * exported card. Each kanji run with a Jitendex reading becomes a
     * `<ruby>` whose `<rt>` is hidden by default with `display:none` and
     * toggled by a self-contained inline `onclick`. Because the behaviour
     * lives entirely in the field HTML — no card-template, CSS, or model
     * change — it works on the existing note type and on any AnkiDroid card.
     *
     * `display:none` (not `visibility:hidden`) matters: a hidden-but-present
     * `<rt>` still reserves horizontal width in native ruby layout, so a
     * reading wider than its kanji (なに over 何) pushes the base characters
     * apart — the sentence looked oddly spaced even though the readings were
     * invisible. Removing the annotation box entirely makes the untapped
     * sentence render exactly like the plain front-side sentence; the only
     * trade-off is that revealing a reading grows the line height (a minor,
     * on-demand reflow) instead of being pre-reserved.
     *
     * Sentences with no ruby readings (plain imports) fall back to escaped
     * plain text, exactly as before.
     */
    fun buildFuriganaSentenceHtml(
        ex: com.yomitanmobile.domain.model.ExamplePair,
        furiganaColor: String = ""
    ): String {
        val segments = ex.segments
        if (segments.none { it.reading.isNotBlank() }) {
            return InputSanitizer.escapeHtml(ex.jp)
        }
        // Blank ⇒ inherit the sentence text color (same color as the text).
        val rtColor = furiganaColor.trim().ifBlank { "inherit" }
        // Jitendex splits a compound into one <ruby> PER kanji (果→くだ,
        // 物→もの), so a naive 1-ruby-per-segment rendering would force the
        // reader to tap each kanji separately. Group consecutive
        // reading-bearing segments into a single <ruby> whose one tap
        // reveals the whole word's readings at once; the toggle reads the
        // first <rt>'s state and applies it to all of them so they never
        // fall out of sync.
        val toggle = "var t=this.getElementsByTagName('rt');" +
            "if(t.length){var show=(t[0].style.display==='none');" +
            "for(var i=0;i<t.length;i++)t[i].style.display=show?'':'none';}"
        return buildString {
            var i = 0
            while (i < segments.size) {
                if (segments[i].reading.isBlank()) {
                    append(InputSanitizer.escapeHtml(segments[i].text))
                    i++
                } else {
                    append("<ruby style=\"cursor:pointer\" onclick=\"").append(toggle).append("\">")
                    while (i < segments.size && segments[i].reading.isNotBlank()) {
                        val base = InputSanitizer.escapeHtml(segments[i].text)
                        val reading = InputSanitizer.escapeHtml(segments[i].reading)
                        // No underline on the kanji base — the whole <ruby>
                        // is still tap-to-reveal via its onclick; we just
                        // don't mark the tappable kanji visually anymore.
                        append(base)
                        append("<rt style=\"display:none;font-size:0.6em;color:").append(rtColor).append("\">")
                        append(reading).append("</rt>")
                        i++
                    }
                    append("</ruby>")
                }
            }
        }
    }

    const val CARD_FRONT_TEMPLATE = """
        <div class="front">
            <span class="expression">{{Front}}</span>
            {{#FrontContext}}<div class="front-context">{{FrontContext}}</div>{{/FrontContext}}
        </div>
    """

    // Header block (expression + reading + small frequency line) is
    // visually peeled off from the rest of the card by an unconditional
    // <hr> that always sits right after it. Each subsequent section
    // ends with its own <hr>; the bottom-most visible <hr> is hidden
    // via the `hr:last-of-type { display: none }` CSS rule so the card
    // doesn't show a dangling line under the final section. This
    // makes the section order trivially reorderable — every section
    // is a self-contained block with a trailing separator.
    //
    // The kept-around CARD_BACK_TEMPLATE constant is the fallback for
    // tests / previews that don't have a CardStylePreferences in
    // hand. Real exports go through buildBackTemplate(sectionOrder).
    const val CARD_BACK_TEMPLATE = """
        <div class="back">
            {{#Frequency}}<div class="freq">{{Frequency}}</div>{{/Frequency}}
            <div class="section header-section">
                <div class="expression">{{Front}}</div>
                <hr class="word-divider">
                <div class="reading">{{Reading}}</div>
            </div>
            <hr>
            {{#PitchAccent}}<div class="section"><div class="pitch">{{PitchAccent}}</div></div><hr>{{/PitchAccent}}
            {{#Summary}}<div class="section summary-section"><div class="summary">{{Summary}}</div></div><hr>{{/Summary}}
            <div class="section meaning-section">
                <div class="meaning">{{Meaning}}</div>
            </div>
            <hr>
            {{#Sentence}}<div class="section"><div class="sentence">{{Sentence}}</div></div><hr>{{/Sentence}}
            {{#Audio}}<div class="section audio-section"><div class="audio">{{Audio}}</div></div><hr>{{/Audio}}
            {{#KanjiBreakdown}}<div class="section kanji-section"><div class="kanji-breakdown">{{KanjiBreakdown}}</div></div><hr>{{/KanjiBreakdown}}
        </div>
    """

    /**
     * Builds the back-side template HTML using the user's chosen
     * [sectionOrder]. The header block is fixed; only the back-half
     * sections are reorderable. Meaning is always rendered (no mustache
     * wrapper) — every other section is wrapped in its own
     * `{{#Field}}…{{/Field}}` so empty data collapses the entire block,
     * trailing `<hr>` included.
     */
    fun buildBackTemplate(
        sectionOrder: List<com.yomitanmobile.domain.model.CardSection>,
        profile: com.yomitanmobile.domain.model.CardProfile =
            com.yomitanmobile.domain.model.CardProfile.JAPANESE,
        /**
         * Fields the note type being written actually has. Null means "the
         * current profile's", which is the case for every new export.
         *
         * It is not null when restyling a note type left over from an
         * older version of this app: those have fewer fields (no Summary,
         * no FrontContext, no KanjiBreakdown), and a template naming a
         * field the type lacks renders as the literal text
         * "{{KanjiBreakdown}}" on the card. So the template is cut down to
         * what is there instead of the note type being refused.
         */
        available: Set<String>? = null
    ): String {
        val has = { field: String -> available == null || field in available }
        val wordField = when {
            available == null || "Front" in available -> "Front"
            // An ancient note type of ours may name it something else;
            // whatever its first field is, that is the word.
            else -> available.firstOrNull() ?: "Front"
        }
        val sb = StringBuilder()
        sb.append("<div class=\"back\">\n")
        // Frequency rank, pinned to the top-right corner of the card. The
        // element is always emitted (empty Frequency renders nothing at
        // all thanks to the section tag); the "show frequency" preference
        // decides whether CSS reveals it. It used to be the other way
        // round — the CSS rule existed but nothing ever produced the
        // element, so the toggle changed nothing.
        if (has("Frequency")) {
            sb.append("    {{#Frequency}}<div class=\"freq\">{{Frequency}}</div>{{/Frequency}}\n")
        }
        sb.append("    <div class=\"section header-section\">\n")
        // Header order: expression → bold word-divider → reading.
        // The Frequency field is still on the model schema (so cards
        // keep working) but no longer rendered — users asked for a
        // cleaner header.
        sb.append("        <div class=\"expression\">{{" + wordField + "}}</div>\n")
        // Japanese only: see CardProfile.readingInHeader. On a Latin-script
        // card the Reading field is IPA and lives in its own section below.
        if (profile.readingInHeader && has("Reading")) {
            sb.append("        <hr class=\"word-divider\">\n")
            sb.append("        <div class=\"reading\">{{Reading}}</div>\n")
        }
        sb.append("    </div>\n")
        sb.append("    <hr>\n")
        // A section whose field the note type doesn't have would render
        // as the literal text "{{KanjiBreakdown}}" on the card, so it is
        // dropped here rather than left to collapse at display time.
        for (section in profile.orderSections(sectionOrder)) {
            if (!sectionFieldsPresent(section, profile, available)) continue
            sb.append("    ").append(blockHtmlFor(section, profile)).append("\n")
        }
        sb.append("</div>")
        return sb.toString()
    }

    /** Fields one back-side section names, so a lean note type can skip it. */
    private fun sectionFieldsPresent(
        section: com.yomitanmobile.domain.model.CardSection,
        profile: com.yomitanmobile.domain.model.CardProfile,
        available: Set<String>?
    ): Boolean {
        if (available == null) return true
        val needed = when (section) {
            com.yomitanmobile.domain.model.CardSection.PITCH ->
                if (profile.readingInHeader) "PitchAccent" else "Reading"
            com.yomitanmobile.domain.model.CardSection.SUMMARY -> "Summary"
            com.yomitanmobile.domain.model.CardSection.MEANING -> "Meaning"
            com.yomitanmobile.domain.model.CardSection.SENTENCE -> "Sentence"
            com.yomitanmobile.domain.model.CardSection.AUDIO -> "Audio"
            com.yomitanmobile.domain.model.CardSection.KANJI -> "KanjiBreakdown"
        }
        return needed in available
    }

    /**
     * The front template for a note type that may lack our fields.
     *
     * Same reason as [buildBackTemplate]'s `available`: a `{{FrontContext}}`
     * on a note type without that field is printed, not rendered.
     */
    fun buildFrontTemplate(available: Set<String>? = null): String {
        if (available == null) return CARD_FRONT_TEMPLATE
        val wordField = if ("Front" in available) "Front" else available.firstOrNull() ?: "Front"
        val context = if ("FrontContext" in available) {
            "\n                {{#FrontContext}}<div class=\"front-context\">" +
                "{{FrontContext}}</div>{{/FrontContext}}"
        } else {
            ""
        }
        return """
        <div class="front">
            <span class="expression">{{$wordField}}</span>$context
        </div>
    """
    }

    private fun blockHtmlFor(
        section: com.yomitanmobile.domain.model.CardSection,
        profile: com.yomitanmobile.domain.model.CardProfile =
            com.yomitanmobile.domain.model.CardProfile.JAPANESE
    ): String =
        when (section) {
            // One "how is this said" slot, two sources: the pitch diagram
            // built into PitchAccent, or the IPA that sits in Reading on a
            // note type which has no PitchAccent field at all.
            com.yomitanmobile.domain.model.CardSection.PITCH ->
                if (profile.readingInHeader) {
                    """{{#PitchAccent}}<div class="section"><div class="pitch">{{PitchAccent}}</div></div><hr>{{/PitchAccent}}"""
                } else {
                    """{{#Reading}}<div class="section"><div class="pitch">{{Reading}}</div></div><hr>{{/Reading}}"""
                }
            com.yomitanmobile.domain.model.CardSection.SUMMARY ->
                """{{#Summary}}<div class="section summary-section"><div class="summary">{{Summary}}</div></div><hr>{{/Summary}}"""
            com.yomitanmobile.domain.model.CardSection.MEANING ->
                """<div class="section meaning-section"><div class="meaning">{{Meaning}}</div></div><hr>"""
            com.yomitanmobile.domain.model.CardSection.SENTENCE ->
                """{{#Sentence}}<div class="section"><div class="sentence">{{Sentence}}</div></div><hr>{{/Sentence}}"""
            com.yomitanmobile.domain.model.CardSection.AUDIO ->
                """{{#Audio}}<div class="section audio-section"><div class="audio">{{Audio}}</div></div><hr>{{/Audio}}"""
            com.yomitanmobile.domain.model.CardSection.KANJI ->
                """{{#KanjiBreakdown}}<div class="section kanji-section"><div class="kanji-breakdown">{{KanjiBreakdown}}</div></div><hr>{{/KanjiBreakdown}}"""
        }

    const val CARD_CSS = """
        .card {
            font-family: "Hiragino Sans", "Yu Gothic", "Meiryo", sans-serif;
            font-size: 18px;
            text-align: center;
            color: #e0e0e0;
            background-color: #1a1a1a;
            padding: 20px;
        }
        .section { padding: 4px 0; }
        .header-section { padding-top: 0; }
        .expression { font-size: 48px; font-weight: bold; color: #ffffff; }
        .reading { font-size: 26px; color: #80cbc4; margin: 6px 0 0 0; }
        .meaning-section { padding: 6px 0; }
        .meaning {
            font-size: 18px; color: #e0e0e0;
            text-align: left;
        }
        .summary-section { padding: 4px 0; }
        .summary {
            font-size: 15px; color: #d7d7d7; text-align: left;
            line-height: 1.5; white-space: pre-wrap;
        }
        .pos-line {
            font-size: 13px; font-style: italic; color: #80cbc4;
            margin: 0 0 10px 0; text-align: left;
            letter-spacing: 0.02em;
        }
        .usage-tags {
            display: inline-block; font-size: 11px; font-weight: bold;
            color: #1a1a1a; background-color: #ffcc80;
            padding: 2px 6px; border-radius: 4px;
            margin: 0 0 8px 0; letter-spacing: 0.02em;
        }
        .meanings { margin: 0; padding: 0 0 0 1.6em; }
        .meaning-item { margin-bottom: 10px; line-height: 1.45; }
        .meaning-item:last-child { margin-bottom: 0; }
        .meaning-item .gloss { color: #e0e0e0; }
        .meaning-ex {
            margin: 4px 0 2px 0;
            padding-left: 10px;
            border-left: 2px solid #80cbc4;
        }
        .meaning-ex-jp {
            font-size: 14px; color: #cfd8dc; line-height: 1.4;
        }
        .meaning-ex-en {
            font-size: 12px; color: #90a4ae; margin-top: 2px;
            font-style: italic; line-height: 1.3;
        }
        .pitch {
            font-size: 16px; color: #ff8a65; margin: 4px 0;
        }
        .back { position: relative; }
        .freq {
            position: absolute; top: 0; right: 0;
            font-size: 11px; color: #aaa;
            margin: 0; opacity: 0.85;
            letter-spacing: 0.02em;
            display: none;
        }
        .word-divider {
            border: none; border-top: 1px solid #fff;
            margin: 12px 0; width: 100%;
        }
        .front-context {
            font-size: 14px; color: #cfd8dc; margin-top: 8px;
            text-align: center; line-height: 1.35;
        }
        .context-highlight {
            font-weight: 700;
            color: #ffffff;
            background: rgba(128, 203, 196, 0.28);
            border-radius: 4px;
            padding: 0 2px;
        }
        .audio { margin: 8px 0; }
        .sentence {
            font-size: 14px; color: #b0bec5; margin: 4px 0;
            text-align: left; line-height: 1.4;
        }
        .sentence-jp { font-style: italic; }
        .sentence-translation {
            font-size: 12px; color: #90a4ae; margin-top: 4px;
            font-style: normal; opacity: 0.95;
        }
        .sentence-divider {
            height: 1px; background: #3a3a3a; margin: 8px auto;
            width: 60%; opacity: 0.5;
        }
        hr {
            border: none; border-top: 1px solid #555;
            margin: 16px 0; opacity: 0.7;
        }
        .back > hr:last-of-type { display: none; }
        .kanji-breakdown {
            font-size: 16px; color: #ccc;
            text-align: left;
        }
        .kanji-breakdown-title {
            font-size: 12px; color: #80cbc4; text-transform: uppercase;
            letter-spacing: 0.08em; margin-bottom: 8px;
        }
        .kanji-item { margin-bottom: 8px; line-height: 1.4; }
        .kanji-item:last-child { margin-bottom: 0; }
        .kanji-char { font-size: 22px; color: #fff; margin-right: 8px; font-weight: bold; }
        .kanji-readings { font-size: 13px; color: #b0bec5; }
        .kanji-meanings { font-size: 13px; color: #cfd8dc; margin-top: 2px; }
    """

    /**
     * Generate CSS dynamically from [CardStylePreferences].
     */
    fun buildCssFromPreferences(prefs: CardStylePreferences): String {
        val fontWeight = if (prefs.expressionBold) "bold" else "normal"
        val baseFontImportUrl = CardStylePreferences.googleFontsImportUrl(prefs.fontFamily)
        val baseFontImport = if (baseFontImportUrl != null) "@import url('$baseFontImportUrl');\n" else ""

        val randomFontsImports = if (prefs.randomFontsEnabled && prefs.randomFonts.isNotEmpty()) {
            prefs.randomFonts.mapNotNull { CardStylePreferences.googleFontsImportUrl(it) }
                .joinToString("\n") { "@import url('$it');" } + "\n"
        } else ""

        return """
        $baseFontImport$randomFontsImports.card {
            font-family: "${prefs.fontFamily}", "Yu Gothic", "Meiryo", sans-serif;
            font-size: ${prefs.meaningFontSize}px;
            text-align: center;
            color: ${prefs.meaningColor};
            background-color: ${prefs.cardBackgroundColor};
            padding: 20px;
        }
        .section { padding: 4px 0; }
        .header-section { padding-top: 0; }
        .meaning-section { padding: 6px 0; }
        .summary-section { padding: 4px 0; }
        .summary {
            font-size: ${(prefs.meaningFontSize - 3).coerceAtLeast(12)}px;
            color: #d7d7d7; text-align: left;
            line-height: 1.5; white-space: pre-wrap;
        }
        .expression { font-size: ${prefs.expressionFontSize}px; font-weight: $fontWeight; color: ${prefs.expressionColor}; }
        .reading { font-size: ${prefs.readingFontSize}px; color: ${prefs.readingColor}; margin: 6px 0 0 0; }
        .meaning {
            font-size: ${prefs.meaningFontSize}px; color: ${prefs.meaningColor};
            text-align: left;
        }
        .pos-line {
            font-size: 13px; font-style: italic; color: ${prefs.accentColor};
            margin: 0 0 10px 0; text-align: left; letter-spacing: 0.02em;
        }
        .usage-tags {
            display: inline-block; font-size: 11px; font-weight: bold;
            color: #1a1a1a; background-color: #ffcc80;
            padding: 2px 6px; border-radius: 4px;
            margin: 0 0 8px 0; letter-spacing: 0.02em;
        }
        .meanings { margin: 0; padding: 0 0 0 1.6em; }
        .meaning-item { margin-bottom: 10px; line-height: 1.45; }
        .meaning-item:last-child { margin-bottom: 0; }
        .meaning-item .gloss { color: ${prefs.meaningColor}; }
        .meaning-ex {
            margin: 4px 0 2px 0; padding-left: 10px;
            border-left: 2px solid ${prefs.accentColor};
            ${if (!prefs.showSentence) "display: none;" else ""}
        }
        .meaning-ex-jp {
            font-size: ${prefs.backSentenceFontSize}px; color: #cfd8dc; line-height: 1.4;
        }
        .meaning-ex-en {
            font-size: ${(prefs.backSentenceFontSize - 2).coerceAtLeast(10)}px;
            color: #90a4ae; margin-top: 2px; font-style: italic; line-height: 1.3;
        }
        .pitch {
            font-size: 16px; color: #ff8a65; margin: 4px 0;
            ${if (!prefs.showPitchAccent) "display: none;" else ""}
        }
        .back { position: relative; }
        .freq {
            position: absolute; top: 0; right: 0;
            font-size: 11px; color: #aaa;
            margin: 0; opacity: 0.85;
            letter-spacing: 0.02em;
            ${if (!prefs.showFrequency) "display: none;" else ""}
        }
        .word-divider {
            border: none; border-top: 1px solid #fff;
            margin: 12px 0; width: 100%;
            display: ${if (!prefs.showWordDivider) "none" else "block"};
        }
        .front-context {
            font-size: ${prefs.frontContextSentenceFontSize}px; color: #d7d7d7; margin-top: 8px;
            text-align: center; line-height: 1.35;
            ${if (!prefs.showFrontContextSentence) "display: none;" else ""}
        }
        .context-highlight {
            font-weight: 700;
            color: ${prefs.expressionColor};
            background: ${prefs.accentColor}44;
            border-radius: 4px;
            padding: 0 2px;
        }
        .audio { margin: 8px 0; }
        .sentence {
            font-size: ${prefs.backSentenceFontSize}px; color: #bbb; margin: 4px 0;
            text-align: left; line-height: 1.4;
            ${if (!prefs.showSentence) "display: none;" else ""}
        }
        .sentence-jp { font-style: italic; }
        .sentence-translation {
            font-size: ${(prefs.backSentenceFontSize - 2).coerceAtLeast(10)}px;
            color: #90a4ae; margin-top: 4px; font-style: normal; opacity: 0.95;
        }
        .sentence-divider {
            height: 1px; background: #3a3a3a; margin: 8px auto;
            width: 60%; opacity: 0.5;
        }
        hr {
            border: none; border-top: 1px solid #555;
            margin: 16px 0; opacity: 0.7;
            ${if (!prefs.showSectionDividers) "display: none;" else ""}
        }
        /*
         * Each section ends with its own <hr> for predictable
         * spacing under user-controlled reordering, but the
         * very last <hr> is just a dangling line under the
         * final visible section — drop it.
         */
        .back > hr:last-of-type { display: none; }
        .kanji-breakdown {
            font-size: 16px; color: #ccc; text-align: left;
        }
        .kanji-breakdown-title {
            font-size: 12px; color: ${prefs.accentColor}; text-transform: uppercase;
            letter-spacing: 0.08em; margin-bottom: 8px;
        }
        .kanji-item { margin-bottom: 8px; line-height: 1.4; }
        .kanji-item:last-child { margin-bottom: 0; }
        .kanji-char { font-size: 22px; color: #fff; margin-right: 8px; font-weight: bold; }
        .kanji-readings { font-size: 13px; color: #b0bec5; }
        .kanji-meanings { font-size: 13px; color: #cfd8dc; margin-top: 2px; }
        """.trimIndent()
    }

    /**
     * Build a full HTML page for preview purposes.
     */
    /**
     * Sample data for the live preview, per language.
     *
     * The preview used to be Japanese regardless of what was being
     * studied, so someone setting up English cards was adjusting fonts
     * and colours against 食べる / たべる — and could not see what their
     * own cards would look like, which is the entire purpose of the
     * screen.
     */
    private data class PreviewSample(
        val word: String,
        val reading: String,
        val pitchPositions: String,
        val posLine: String,
        val glossOne: String,
        val glossTwo: String,
        val exampleSource: String,
        val exampleTranslation: String,
        val contextSentence: String,
        val contextWord: String,
        val summary: String
    )

    private fun previewSampleFor(
        language: com.yomitanmobile.domain.model.AppLanguage
    ): PreviewSample = when (language) {
        com.yomitanmobile.domain.model.AppLanguage.JAPANESE -> PreviewSample(
            word = "食べる",
            reading = "たべる",
            pitchPositions = "2",
            posLine = "ichidan verb, transitive verb",
            glossOne = "to eat",
            glossTwo = "to live on (e.g. a salary), to live off, to subsist on",
            exampleSource = "朝ごはんに納豆を食べる。",
            exampleTranslation = "I eat natto for breakfast.",
            contextSentence = "毎日野菜を",
            contextWord = "食べる",
            summary = "食べる (taberu) — ichidan verb meaning \"to eat\" or, idiomatically, " +
                "\"to live on\" (e.g. a salary). Common JLPT N5 vocabulary."
        )
        com.yomitanmobile.domain.model.AppLanguage.ENGLISH -> PreviewSample(
            word = "dog",
            reading = "/dɒɡ/",
            pitchPositions = "",
            posLine = "rzeczownik",
            glossOne = "pies",
            glossTwo = "pies, samiec psa (t. wilka, lisa)",
            exampleSource = "It is said that a dog is a human's best friend.",
            exampleTranslation = "Mówi się, że pies jest najlepszym przyjacielem człowieka.",
            contextSentence = "She walked her ",
            contextWord = "dog",
            summary = "dog — rzeczownik, jedno z najczęstszych słów w języku angielskim."
        )
        com.yomitanmobile.domain.model.AppLanguage.SPANISH -> PreviewSample(
            word = "hablar",
            reading = "/aˈβlaɾ/",
            pitchPositions = "",
            posLine = "verb",
            glossOne = "to speak, to talk",
            glossTwo = "to address, to speak to someone",
            exampleSource = "¿Podemos hablar un momento?",
            exampleTranslation = "Can we talk for a moment?",
            contextSentence = "Quiero ",
            contextWord = "hablar"
                ,
            summary = "hablar — regular -ar verb; hablando is its gerund."
        )
    }

    fun buildPreviewHtml(
        prefs: CardStylePreferences,
        language: com.yomitanmobile.domain.model.AppLanguage =
            com.yomitanmobile.domain.model.AppLanguage.DEFAULT
    ): String {
        val sample = previewSampleFor(language)
        val profile = com.yomitanmobile.domain.model.CardProfile.forLanguage(language)
        val css = buildCssFromPreferences(prefs)
        val fontImportUrl = CardStylePreferences.googleFontsImportUrl(prefs.fontFamily)
        val fontImport = if (fontImportUrl != null) {
            """<link rel="stylesheet" href="$fontImportUrl">"""
        } else ""
        val frontContext = if (prefs.showFrontContextSentence) {
            """<div class="front-context">${sample.contextSentence}<strong class="context-highlight">${sample.contextWord}</strong><br><small style="opacity:0.7;">(kontekst na froncie)</small></div>"""
        } else {
            ""
        }
        // Latin-script profiles have no pitch diagram; their pronunciation
        // block shows the IPA that the Reading field carries.
        val previewPitch = if (profile.readingInHeader) {
            buildPitchAccentHtml(
                reading = sample.reading,
                pitchPositions = sample.pitchPositions,
                prefs = prefs
            )
        } else {
            InputSanitizer.escapeHtml(sample.reading)
        }
        val sectionsHtml = buildString {
            for (section in profile.orderSections(prefs.sectionOrder)) {
                append(previewBlockHtmlFor(section, previewPitch, sample))
                append("\n")
            }
        }
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            $fontImport
            <style>$css</style>
        </head>
        <body class="card">
            <div class="front" style="margin-bottom: 20px;">
                <div class="expression">${sample.word}</div>
                $frontContext
            </div>
            <hr>
            <div class="back">
                <div class="freq">4821</div>
                <div class="section header-section">
                    <div class="expression">${sample.word}</div>
                    ${if (profile.readingInHeader) {
                        """<hr class="word-divider">
                    <div class="reading">${sample.reading}</div>"""
                    } else ""}
                </div>
                <hr>
                $sectionsHtml
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    /**
     * Sample HTML for a single back-side section, used by the live
     * preview in CardStyleScreen. Mirrors the structure produced by
     * [blockHtmlFor] but with concrete sample data instead of mustache
     * placeholders.
     */
    private fun previewBlockHtmlFor(
        section: com.yomitanmobile.domain.model.CardSection,
        previewPitch: String,
        sample: PreviewSample
    ): String = when (section) {
        com.yomitanmobile.domain.model.CardSection.PITCH ->
            """<div class="section"><div class="pitch">$previewPitch</div></div><hr>"""
        com.yomitanmobile.domain.model.CardSection.SUMMARY ->
            """<div class="section summary-section"><div class="summary">${sample.summary}</div></div><hr>"""
        com.yomitanmobile.domain.model.CardSection.MEANING ->
            """
            <div class="section meaning-section"><div class="meaning">
              <div class="pos-line">${sample.posLine}</div>
              <ol class="meanings">
                <li class="meaning-item">
                  <span class="gloss">${sample.glossOne}</span>
                  <div class="meaning-ex">
                    <div class="meaning-ex-jp">${sample.exampleSource}</div>
                    <div class="meaning-ex-en">${sample.exampleTranslation}</div>
                  </div>
                </li>
                <li class="meaning-item">
                  <span class="gloss">${sample.glossTwo}</span>
                </li>
              </ol>
            </div></div>
            <hr>
            """.trimIndent()
        com.yomitanmobile.domain.model.CardSection.SENTENCE ->
            """<div class="section"><div class="sentence"><div class="sentence-jp">${sample.exampleSource}</div><div class="sentence-translation">${sample.exampleTranslation}</div></div></div><hr>"""
        com.yomitanmobile.domain.model.CardSection.AUDIO ->
            """<div class="section audio-section"><div class="audio">🔊 [audio]</div></div><hr>"""
        com.yomitanmobile.domain.model.CardSection.KANJI ->
            """
            <div class="section kanji-section"><div class="kanji-breakdown">
              <div class="kanji-breakdown-title">Kanji</div>
              <div class="kanji-item">
                <span class="kanji-char">食</span>
                <span class="kanji-readings">On: ショク &nbsp; Kun: た.べる, く.う</span>
                <div class="kanji-meanings">eat, food</div>
              </div>
            </div></div>
            <hr>
            """.trimIndent()
    }

    fun buildPitchAccentHtml(
        reading: String,
        pitchPositions: String,
        prefs: CardStylePreferences?
    ): String {
        if (pitchPositions.isBlank()) return ""
        val positions = pitchPositions.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (positions.isEmpty()) return ""

        val morae = splitIntoMorae(reading)
        if (morae.isEmpty()) return ""

        val accentColor = normalizedCssColor(prefs?.accentColor, DEFAULT_PITCH_ACCENT_COLOR)
        val kanaColor = normalizedCssColor(prefs?.readingColor, DEFAULT_PITCH_KANA_COLOR)
        val style = prefs?.pitchAccentStyle ?: PitchAccentStyle.LEGACY

        return buildString {
            positions.forEachIndexed { idx, dropPos ->
                if (idx > 0) append("&nbsp;&nbsp;")
                val pattern = computePitchPattern(morae.size, dropPos)
                val label = pitchLabel(dropPos, morae.size)
                if (style == PitchAccentStyle.DOT_LINE) {
                    append(
                        buildDotLinePitchAccentPattern(
                            morae = morae,
                            pattern = pattern,
                            dropPos = dropPos,
                            label = label,
                            accentColor = accentColor,
                            kanaColor = kanaColor
                        )
                    )
                } else {
                    append(
                        buildLegacyPitchAccentPattern(
                            morae = morae,
                            pattern = pattern,
                            dropPos = dropPos,
                            label = label,
                            accentColor = accentColor
                        )
                    )
                }
            }
        }
    }

    private fun buildLegacyPitchAccentPattern(
        morae: List<String>,
        pattern: List<Boolean>,
        dropPos: Int,
        label: String,
        accentColor: String
    ): String {
        return buildString {
            append("<span style=\"font-size:12px;color:#999;\">[$dropPos] $label</span> ")
            for (i in morae.indices) {
                val high = pattern[i]
                val style = if (high) {
                    "border-top:2px solid $accentColor;padding-top:2px;"
                } else {
                    "padding-top:4px;"
                }
                val rightBorder = if (i < morae.size - 1 && pattern[i] != pattern[i + 1]) {
                    if (pattern[i]) "border-right:2px solid $accentColor;" else "border-right:2px solid #666;"
                } else {
                    ""
                }
                append(
                    "<span style=\"$style$rightBorder display:inline-block;\">${InputSanitizer.escapeHtml(morae[i])}</span>"
                )
            }
        }
    }

    private fun buildDotLinePitchAccentPattern(
        morae: List<String>,
        pattern: List<Boolean>,
        dropPos: Int,
        label: String,
        accentColor: String,
        kanaColor: String
    ): String {
        return buildString {
            append("<span style=\"display:inline-flex;flex-direction:column;align-items:flex-start;\">")
            append("<span style=\"font-size:12px;color:#999;\">[$dropPos] $label</span>")
            append("<span style=\"white-space:nowrap;line-height:1.0;\">")
            for (i in morae.indices) {
                val isHigh = pattern[i]
                val nodeChar = if (isHigh) "●" else "○"
                val nodeColor = if (isHigh) accentColor else DEFAULT_PITCH_LOW_COLOR
                append(
                    "<span style=\"display:inline-block;min-width:0.95em;text-align:center;color:$nodeColor;font-weight:700;\">$nodeChar</span>"
                )
                if (i < morae.size - 1) {
                    val nextHigh = pattern[i + 1]
                    val connectorChar = when {
                        isHigh && nextHigh -> "━"
                        !isHigh && !nextHigh -> "─"
                        isHigh && !nextHigh -> "╲"
                        else -> "╱"
                    }
                    val connectorColor = when {
                        isHigh && nextHigh -> accentColor
                        !isHigh && !nextHigh -> "#666"
                        isHigh && !nextHigh -> accentColor
                        else -> "#888"
                    }
                    append(
                        "<span style=\"display:inline-block;min-width:0.95em;text-align:center;color:$connectorColor;font-weight:700;\">$connectorChar</span>"
                    )
                }
            }
            append("</span>")

            append("<span style=\"white-space:nowrap;line-height:1.0;margin-top:2px;\">")
            for (i in morae.indices) {
                append(
                    "<span style=\"display:inline-block;min-width:0.95em;text-align:center;color:$kanaColor;\">${InputSanitizer.escapeHtml(morae[i])}</span>"
                )
                if (i < morae.size - 1) {
                    append(
                        "<span style=\"display:inline-block;min-width:0.95em;text-align:center;color:transparent;\">・</span>"
                    )
                }
            }
            append("</span>")
            append("</span>")
        }
    }

    /** See [com.yomitanmobile.util.JapaneseMora] — one rule, two screens. */
    private fun splitIntoMorae(reading: String): List<String> =
        com.yomitanmobile.util.JapaneseMora.split(reading)

    private fun computePitchPattern(moraCount: Int, dropPos: Int): List<Boolean> {
        if (moraCount == 0) return emptyList()
        if (moraCount == 1) return listOf(dropPos != 0)
        return List(moraCount) { i ->
            when {
                dropPos == 0 -> i > 0
                dropPos == 1 -> i == 0
                else -> i > 0 && i < dropPos
            }
        }
    }

    private fun pitchLabel(dropPos: Int, moraCount: Int): String {
        return when (dropPos) {
            0 -> "平板"
            1 -> "頭高"
            moraCount -> "尾高"
            else -> "中高"
        }
    }

    private fun normalizedCssColor(value: String?, fallback: String): String {
        val candidate = value?.trim().orEmpty()
        val hexColorRegex = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
        return if (candidate.matches(hexColorRegex)) candidate else fallback
    }
}
