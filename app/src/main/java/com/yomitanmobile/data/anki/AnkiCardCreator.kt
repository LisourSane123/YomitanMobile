package com.yomitanmobile.data.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.api.AddContentApi
import com.yomitanmobile.domain.model.AnkiCard
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.PitchAccentStyle
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.util.InputSanitizer
import com.yomitanmobile.util.SentenceContextHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AnkiCardCreator(
    private val context: Context
) {
    companion object {
        // Yomitan typically shows every sense; for cards a soft cap keeps the
        // back side scrollable. Bumped up from 3 because Jitendex entries with
        // many senses (聞く has 8) were getting truncated to almost nothing.
        private const val MAX_MEANINGS_ON_CARD = 6
        private const val MAX_EXAMPLES_PER_MEANING = 1
        private const val MODEL_NAME_PREFIX = "Yomitan-Mobile"
        private const val MAX_MODEL_CREATE_RETRIES = 8
        private const val DEFAULT_PITCH_ACCENT_COLOR = "#ff8a65"
        private const val DEFAULT_PITCH_LOW_COLOR = "#777777"
        private const val DEFAULT_PITCH_KANA_COLOR = "#80cbc4"

        const val DEFAULT_DECK_NAME = "Mining Deck"
        // Bumped from v7 to v8 because we added the Summary field — that's a
        // schema change Anki tracks per-model. Existing v7 cards stay where
        // they are with their old layout; new exports land on v8 with the
        // AI summary slot. Never bump unless FIELD_NAMES actually changes.
        const val MODEL_NAME = "Yomitan-Mobile-v8"
        private const val LEGACY_MODEL_NAME = "Yomitan-Mobile"
        private const val LEGACY_MODEL_NAME_V4 = "Yomitan-Mobile-v4"
        private const val LEGACY_MODEL_NAME_V7 = "Yomitan-Mobile-v7"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        val FIELD_NAMES = arrayOf("Front", "FrontContext", "Reading", "Meaning", "PitchAccent", "Frequency", "Audio", "Sentence", "KanjiBreakdown", "Summary")

        /**
         * Renders an example sentence as tap-to-reveal furigana HTML for the
         * exported card. Each kanji run with a Jitendex reading becomes a
         * `<ruby>` whose `<rt>` is hidden by default (space reserved via
         * `visibility:hidden`, so tapping doesn't shift the line) and toggled by
         * a self-contained inline `onclick`. Because the behaviour lives entirely
         * in the field HTML — no card-template, CSS, or model change — it works
         * on the existing note type and on any AnkiDroid card.
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
                "if(t.length){var v=(t[0].style.visibility==='visible')?'hidden':'visible';" +
                "for(var i=0;i<t.length;i++)t[i].style.visibility=v;}"
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
                            append("<span style=\"border-bottom:1px dotted #888\">").append(base).append("</span>")
                            append("<rt style=\"visibility:hidden;font-size:0.6em;color:").append(rtColor).append("\">")
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
        fun buildBackTemplate(sectionOrder: List<com.yomitanmobile.domain.model.CardSection>): String {
            val sb = StringBuilder()
            sb.append("<div class=\"back\">\n")
            sb.append("    <div class=\"section header-section\">\n")
            // Header order: expression → bold word-divider → reading.
            // The Frequency field is still on the model schema (so cards
            // keep working) but no longer rendered — users asked for a
            // cleaner header.
            sb.append("        <div class=\"expression\">{{Front}}</div>\n")
            sb.append("        <hr class=\"word-divider\">\n")
            sb.append("        <div class=\"reading\">{{Reading}}</div>\n")
            sb.append("    </div>\n")
            sb.append("    <hr>\n")
            for (section in sectionOrder) {
                sb.append("    ").append(blockHtmlFor(section)).append("\n")
            }
            sb.append("</div>")
            return sb.toString()
        }

        private fun blockHtmlFor(section: com.yomitanmobile.domain.model.CardSection): String =
            when (section) {
                com.yomitanmobile.domain.model.CardSection.PITCH ->
                    """{{#PitchAccent}}<div class="section"><div class="pitch">{{PitchAccent}}</div></div><hr>{{/PitchAccent}}"""
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
            .freq {
                font-size: 11px; color: #aaa;
                margin: 4px 0 0 0; opacity: 0.85;
                letter-spacing: 0.02em;
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
            .freq {
                font-size: 11px; color: #aaa;
                margin: 4px 0 0 0; opacity: 0.85;
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
        fun buildPreviewHtml(prefs: CardStylePreferences): String {
            val css = buildCssFromPreferences(prefs)
            val fontImportUrl = CardStylePreferences.googleFontsImportUrl(prefs.fontFamily)
            val fontImport = if (fontImportUrl != null) {
                """<link rel="stylesheet" href="$fontImportUrl">"""
            } else ""
            val frontContext = if (prefs.showFrontContextSentence) {
                """<div class="front-context">毎日野菜を<strong class="context-highlight">食べる</strong>。<br><small style="opacity:0.7;">(kontekst na froncie)</small></div>"""
            } else {
                ""
            }
            val previewPitch = buildPitchAccentHtml(
                reading = "たべる",
                pitchPositions = "2",
                prefs = prefs
            )
            val sectionsHtml = buildString {
                for (section in prefs.sectionOrder) {
                    append(previewBlockHtmlFor(section, previewPitch))
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
                    <div class="expression">食べる</div>
                    $frontContext
                </div>
                <hr>
                <div class="back">
                    <div class="section header-section">
                        <div class="expression">食べる</div>
                        <hr class="word-divider">
                        <div class="reading">たべる</div>
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
            previewPitch: String
        ): String = when (section) {
            com.yomitanmobile.domain.model.CardSection.PITCH ->
                """<div class="section"><div class="pitch">$previewPitch</div></div><hr>"""
            com.yomitanmobile.domain.model.CardSection.SUMMARY ->
                """<div class="section summary-section"><div class="summary">食べる (taberu) — ichidan verb meaning "to eat" or, idiomatically, "to live on" (e.g. a salary). Common JLPT N5 vocabulary.</div></div><hr>"""
            com.yomitanmobile.domain.model.CardSection.MEANING ->
                """
                <div class="section meaning-section"><div class="meaning">
                  <div class="pos-line">ichidan verb, transitive verb</div>
                  <ol class="meanings">
                    <li class="meaning-item">
                      <span class="gloss">to eat</span>
                      <div class="meaning-ex">
                        <div class="meaning-ex-jp">毎日野菜を食べます。</div>
                        <div class="meaning-ex-en">I eat vegetables every day.</div>
                      </div>
                    </li>
                    <li class="meaning-item">
                      <span class="gloss">to live on (e.g. a salary), to live off, to subsist on</span>
                    </li>
                  </ol>
                </div></div>
                <hr>
                """.trimIndent()
            com.yomitanmobile.domain.model.CardSection.SENTENCE ->
                """<div class="section"><div class="sentence"><div class="sentence-jp">朝ごはんに納豆を食べる。</div><div class="sentence-translation">I eat natto for breakfast.</div></div></div><hr>"""
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

        private fun buildPitchAccentHtml(
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

        private fun splitIntoMorae(reading: String): List<String> {
            val smallKana = setOf(
                'ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ',
                'ャ', 'ュ', 'ョ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ',
                'っ', 'ッ', 'ー'
            )
            val result = mutableListOf<String>()
            var i = 0
            while (i < reading.length) {
                val sb = StringBuilder()
                sb.append(reading[i])
                i++
                while (i < reading.length && reading[i] in smallKana) {
                    sb.append(reading[i])
                    i++
                }
                result.add(sb.toString())
            }
            return result
        }

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

    private val json = Json { ignoreUnknownKeys = true }

    private val ankiApi: AddContentApi by lazy { AddContentApi(context) }

    fun hasAnkiPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun isAnkiInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.ichi2.anki", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getOrCreateDeck(deckName: String): Long? {
        val deckList = ankiApi.deckList ?: run {
            return null
        }
        for ((id, name) in deckList) {
            if (name == deckName) return id
        }
        return ankiApi.addNewDeck(deckName)
    }

    fun getAvailableDecks(): List<String> {
        return try {
            ankiApi.deckList?.values?.toList()?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getOrCreateModel(
        css: String = CARD_CSS,
        backTemplate: String = CARD_BACK_TEMPLATE
    ): Long? {
        val modelList = ankiApi.modelList ?: run {
            return null
        }

        val compatibleModelId = modelList
            .entries
            .asSequence()
            .filter { (_, name) -> name.startsWith(MODEL_NAME_PREFIX) }
            .sortedBy { (_, name) -> modelNamePriority(name) }
            .map { it.key }
            .firstOrNull { modelId -> isModelCompatible(modelId) }

        if (compatibleModelId != null) {
            updateModelCss(compatibleModelId, css)
            // Push the current front/back templates so layout changes
            // (re-orderable sections, AI summary slot, etc.) propagate to
            // existing v8 cards without bumping the model name and
            // leaving an orphan model in the user's AnkiDroid deck.
            val pushed = updateModelTemplates(compatibleModelId, CARD_FRONT_TEMPLATE, backTemplate)
            if (pushed) {
                return compatibleModelId
            }
            // Older AnkiDroid silently dropped the template write — the
            // existing model still has its old back template, so a new
            // section order would never render. Spawn a numbered variant
            // (Yomitan-Mobile-v8-1, -2, …) so this and future exports
            // land on a model whose template matches the current order.
            // Pre-existing cards stay valid on the original model.
            return createCompatibleModel(css, backTemplate, skipPrimaryName = true)
        }

        return createCompatibleModel(css, backTemplate)
    }

    private fun modelNamePriority(name: String): Int {
        return when (name) {
            MODEL_NAME -> 0
            LEGACY_MODEL_NAME -> 1
            LEGACY_MODEL_NAME_V4 -> 2
            LEGACY_MODEL_NAME_V7 -> 3
            else -> 4
        }
    }

    private fun isModelCompatible(modelId: Long): Boolean {
        return try {
            val existingFields = ankiApi.getFieldList(modelId)
                .map { it.trim() }
            val expectedFields = FIELD_NAMES.map { it.trim() }

            existingFields == expectedFields
        } catch (_: Exception) {
            false
        }
    }

    /**
     * @param skipPrimaryName when true, never reuses or recreates
     * [MODEL_NAME] — used when the caller already determined that
     * model's template can't be updated and needs a fresh variant.
     * Without this guard, the existing-model fallback below would loop
     * back into the same broken model.
     */
    private fun createCompatibleModel(
        css: String,
        backTemplate: String = CARD_BACK_TEMPLATE,
        skipPrimaryName: Boolean = false
    ): Long? {
        val candidateNames = buildList {
            if (!skipPrimaryName) add(MODEL_NAME)
            for (index in 1..MAX_MODEL_CREATE_RETRIES) {
                add("$MODEL_NAME-$index")
            }
        }

        for (candidateName in candidateNames) {
            val createdModelId = runCatching {
                ankiApi.addNewCustomModel(
                    candidateName,
                    FIELD_NAMES,
                    arrayOf("Card 1"),
                    arrayOf(CARD_FRONT_TEMPLATE),
                    arrayOf(backTemplate),
                    css,
                    null,
                    null
                )
            }.getOrNull()

            if (createdModelId != null) {
                return createdModelId
            }

            // When falling through from a failed-template-update path we
            // must NOT reuse an existing variant either — that variant
            // either has the same stale template or comes from an older
            // export. Skip straight to the next numbered name so we end
            // up on a freshly created model with the current order.
            if (skipPrimaryName) continue

            val existingCandidateId = ankiApi.modelList
                ?.entries
                ?.firstOrNull { (_, name) -> name == candidateName }
                ?.key

            if (existingCandidateId != null && isModelCompatible(existingCandidateId)) {
                updateModelCss(existingCandidateId, css)
                updateModelTemplates(existingCandidateId, CARD_FRONT_TEMPLATE, backTemplate)
                return existingCandidateId
            }
        }

        return null
    }

    private fun addNoteWithRecovery(
        modelId: Long,
        deckId: Long,
        fields: Array<String>,
        css: String,
        backTemplate: String = CARD_BACK_TEMPLATE
    ): Result<Long> {
        val firstAttempt = runCatching {
            ankiApi.addNote(modelId, deckId, fields, null)
        }

        val firstNoteId = firstAttempt.getOrNull()
        if (firstNoteId != null) {
            return Result.success(firstNoteId)
        }

        val firstError = firstAttempt.exceptionOrNull()
        val shouldRetryWithFreshModel =
            firstError?.message?.contains("Incorrect flds argument", true) ?: false

        if (shouldRetryWithFreshModel) {
            val fallbackModelId = createCompatibleModel(css, backTemplate)
            if (fallbackModelId != null && fallbackModelId != modelId) {
                val retryAttempt = runCatching {
                    ankiApi.addNote(fallbackModelId, deckId, fields, null)
                }
                val retryNoteId = retryAttempt.getOrNull()
                if (retryNoteId != null) {
                    return Result.success(retryNoteId)
                }

                val retryError = retryAttempt.exceptionOrNull()
                if (retryError != null) {
                    return Result.failure(retryError)
                }
            }
        }

        if (firstError != null) {
            return Result.failure(firstError)
        }

        return Result.failure(IllegalStateException("Failed to add note - duplicate?"))
    }

    /**
     * Update the CSS of an existing AnkiDroid model via the content resolver.
     * This ensures card style preferences are applied even when the model already exists.
     */
    private fun updateModelCss(modelId: Long, css: String) {
        try {
            val modelUri = Uri.parse("content://com.ichi2.anki.flashcards/models/$modelId")
            val values = ContentValues().apply {
                put("css", css)
            }
            context.contentResolver.update(modelUri, values, null, null)
        } catch (_: Exception) {
            // Graceful degradation: if update fails, model still works with old CSS
        }
    }

    /**
     * Update the front/back templates of an existing AnkiDroid model.
     *
     * Anki templates aren't covered by AddContentApi, but the FlashCards
     * content provider does expose them at
     * `content://com.ichi2.anki.flashcards/models/<id>/templates/<ord>`
     * with `qfmt` (question/front) and `afmt` (answer/back) columns. We use
     * ord 0 because [createCompatibleModel] always creates a single
     * "Card 1" template per model.
     */
    /**
     * @return true if AnkiDroid accepted the template push (>= 1 row
     * updated), false if the write was silently dropped or threw. The
     * boolean lets [getOrCreateModel] decide whether to fall through to
     * a fresh numbered variant so section reorder still propagates on
     * older AnkiDroid builds that don't expose templates as writable
     * content-provider rows.
     */
    private fun updateModelTemplates(
        modelId: Long,
        frontTemplate: String,
        backTemplate: String
    ): Boolean {
        return try {
            val templateUri = Uri.parse("content://com.ichi2.anki.flashcards/models/$modelId/templates/0")
            val values = ContentValues().apply {
                put("qfmt", frontTemplate)
                put("afmt", backTemplate)
            }
            val rows = context.contentResolver.update(templateUri, values, null, null)
            if (rows == 0) {
                android.util.Log.w(
                    "AnkiCardCreator",
                    "updateModelTemplates: 0 rows updated for model=$modelId. " +
                        "Falling through to a versioned variant so reorder still applies."
                )
                false
            } else {
                true
            }
        } catch (e: Exception) {
            android.util.Log.w("AnkiCardCreator", "updateModelTemplates failed", e)
            false
        }
    }

    fun createAnkiCard(
        entry: WordEntry,
        audioFileName: String = "",
        randomFont: String? = null,
        stylePrefs: CardStylePreferences? = null,
        aiSummaryText: String = ""
    ): AnkiCard {
        val pitchHtml = buildPitchAccentHtml(
            entry.reading.ifBlank { entry.expression },
            entry.pitchAccent,
            stylePrefs
        )
        val freqText = entry.frequencyLabel()
        
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
                sentence = entry.exampleSentence,
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
        val english = com.yomitanmobile.util.LocaleHelper.isEnglish(context.resources.configuration)
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
            reading = InputSanitizer.escapeHtml(entry.reading),
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

    suspend fun addNote(card: AnkiCard, deckName: String = DEFAULT_DECK_NAME, stylePrefs: CardStylePreferences? = null): Result<Long> = withContext(Dispatchers.IO) {
        try {
            if (!hasAnkiPermission()) {
                return@withContext Result.failure(SecurityException("AnkiDroid permission not granted"))
            }
            if (!isAnkiInstalled()) {
                return@withContext Result.failure(IllegalStateException("AnkiDroid is not installed"))
            }
            val deckId = getOrCreateDeck(deckName)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create/find deck"))
            val css = if (stylePrefs != null) buildCssFromPreferences(stylePrefs) else CARD_CSS
            val backTemplate = buildBackTemplate(
                stylePrefs?.sectionOrder
                    ?: com.yomitanmobile.domain.model.CardSection.defaultOrder()
            )
            val modelId = getOrCreateModel(css, backTemplate)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create/find note type"))


            addNoteWithRecovery(
                modelId = modelId,
                deckId = deckId,
                fields = card.toFieldArray(),
                css = css,
                backTemplate = backTemplate
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateTtsAudio(text: String, tts: TextToSpeech): String =
        withContext(Dispatchers.IO) {
            try {
                val fileName =
                    "yomitan_${text.hashCode()}_${UUID.randomUUID().toString().take(8)}.wav"
                val audioDir = File(context.cacheDir, "anki_audio")
                if (!audioDir.exists()) audioDir.mkdirs()
                val tempFile = File(audioDir, fileName)

                val success = suspendCancellableCoroutine { continuation ->
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (!continuation.isCompleted) continuation.resume(true)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (!continuation.isCompleted) continuation.resume(false)
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (!continuation.isCompleted) continuation.resume(false)
                        }
                    })
                    val result = tts.synthesizeToFile(
                        text, null, tempFile,
                        "yomitan_tts_${UUID.randomUUID()}"
                    )
                    if (result != TextToSpeech.SUCCESS) {
                        if (!continuation.isCompleted) continuation.resume(false)
                    }
                }

                if (success && tempFile.exists()) {
                    val soundRef = addMediaToAnki(tempFile, fileName)
                    tempFile.delete()
                    soundRef
                } else ""
            } catch (_: Exception) {
                ""
            }
        }

    private fun addMediaToAnki(sourceFile: File, fileName: String): String {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                sourceFile
            )
            // Grant read permission to AnkiDroid so it can read the temp file
            context.grantUriPermission(
                "com.ichi2.anki",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // preferredName must NOT have file extension; mimeType must be "audio" or "image"
            val preferredName = fileName.substringBeforeLast(".")
            val result = ankiApi.addMediaFromUri(uri, preferredName, "audio")
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            result ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun exportToAnki(entry: WordEntry, tts: TextToSpeech?, deckName: String = DEFAULT_DECK_NAME, stylePrefs: CardStylePreferences? = null, kanjiData: List<com.yomitanmobile.data.local.entity.KanjiEntry> = emptyList(), aiSummaryText: String = ""): Result<Long> {
        val audioFileName = if (tts != null) {
            val textForTts = entry.reading.ifBlank { entry.expression }
            if (stylePrefs != null && stylePrefs.randomVoicesEnabled && stylePrefs.randomVoices.isNotEmpty()) {
                try {
                    val randomVoiceName = stylePrefs.randomVoices.random()
                    tts.voices?.find { it.name == randomVoiceName }?.let { tts.setVoice(it) }
                } catch (e: Exception) { }
            }
            generateTtsAudio(textForTts, tts)
        } else ""
        
        var randomFont: String? = null
        if (stylePrefs != null && stylePrefs.randomFontsEnabled && stylePrefs.randomFonts.isNotEmpty()) {
            randomFont = stylePrefs.randomFonts.random()
        }
        
        val kanjiHtml = if (kanjiData.isNotEmpty()) {
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

        val card = createAnkiCard(entry, audioFileName, randomFont, stylePrefs, aiSummaryText)
            .copy(kanjiBreakdown = kanjiHtml)
        return addNote(card, deckName, stylePrefs)
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
}
