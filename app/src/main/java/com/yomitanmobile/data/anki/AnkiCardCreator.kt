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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Outcome of a bulk write; [failed] counts notes AnkiDroid rejected. */
data class BatchExportResult(
    val added: Int,
    val failed: Int
)

@Singleton
class AnkiCardCreator(
    private val context: Context,
    private val languageSettings: com.yomitanmobile.data.settings.LanguageSettings,
    /**
     * The user's pronunciation archive, asked before the synthesiser. Null in
     * the tests that only build HTML.
     */
    private val audioArchive: com.yomitanmobile.data.audio.AudioArchive? = null
) {

    /**
     * Which note type this export writes to. Read per call rather than
     * captured once: the setting only changes across a process restart
     * today, but a stale profile would write English content into Japanese
     * field slots, which is silent corruption rather than a visible failure.
     */
    private val profile: com.yomitanmobile.domain.model.CardProfile
        get() = com.yomitanmobile.domain.model.CardProfile.forLanguage(languageSettings.current)
    companion object {
        /**
         * Notes per `addNotes` call. Big enough that the per-transaction
         * overhead disappears, small enough that a cancelled generation loses
         * at most this many cards' worth of work and progress still moves.
         */
        private const val BATCH_CHUNK_SIZE = 50

        // Yomitan typically shows every sense; for cards a soft cap keeps the
        // back side scrollable. Bumped up from 3 because Jitendex entries with
        // many senses (聞く has 8) were getting truncated to almost nothing.
        private const val MAX_MEANINGS_ON_CARD = 6
        private const val MAX_EXAMPLES_PER_MEANING = 1
        private const val MODEL_NAME_PREFIX = "Yomitan-Mobile"
        private const val MAX_MODEL_CREATE_RETRIES = 8

        /**
         * How many compatible note types to look at before picking one. A
         * collection can hold hundreds of them (a refused template write used
         * to mint one per export), and each check is a provider round trip.
         */
        private const val MAX_MODEL_CANDIDATES = 8
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

        // Kept as the Japanese profile's field list for tests and for the
        // legacy-model comparison above. The authoritative per-language
        // lists live on CardProfile.
        val FIELD_NAMES = com.yomitanmobile.domain.model.CardProfile.JAPANESE.fieldNames

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
                com.yomitanmobile.domain.model.CardProfile.JAPANESE
        ): String {
            val sb = StringBuilder()
            sb.append("<div class=\"back\">\n")
            // Frequency rank, pinned to the top-right corner of the card. The
            // element is always emitted (empty Frequency renders nothing at
            // all thanks to the section tag); the "show frequency" preference
            // decides whether CSS reveals it. It used to be the other way
            // round — the CSS rule existed but nothing ever produced the
            // element, so the toggle changed nothing.
            sb.append("    {{#Frequency}}<div class=\"freq\">{{Frequency}}</div>{{/Frequency}}\n")
            sb.append("    <div class=\"section header-section\">\n")
            // Header order: expression → bold word-divider → reading.
            // The Frequency field is still on the model schema (so cards
            // keep working) but no longer rendered — users asked for a
            // cleaner header.
            sb.append("        <div class=\"expression\">{{Front}}</div>\n")
            // Japanese only: see CardProfile.readingInHeader. On a Latin-script
            // card the Reading field is IPA and lives in its own section below.
            if (profile.readingInHeader) {
                sb.append("        <hr class=\"word-divider\">\n")
                sb.append("        <div class=\"reading\">{{Reading}}</div>\n")
            }
            sb.append("    </div>\n")
            sb.append("    <hr>\n")
            // A section whose field the note type doesn't have would render
            // as the literal text "{{KanjiBreakdown}}" on the card, so it is
            // dropped here rather than left to collapse at display time.
            for (section in profile.orderSections(sectionOrder)) {
                sb.append("    ").append(blockHtmlFor(section, profile)).append("\n")
            }
            sb.append("</div>")
            return sb.toString()
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

    /**
     * The collection's deck names.
     *
     * Suspending and on [Dispatchers.IO] because `deckList` is a binder round
     * trip into AnkiDroid, which opens the collection to answer it — seconds
     * on a large one, and longer when AnkiDroid is cold. It used to be a plain
     * blocking call, and the deck generator's `init` ran it on the main
     * thread: the screen came up frozen and the back button did nothing until
     * the provider answered. The dispatcher lives here rather than at the call
     * sites so the next caller cannot repeat that (only one of the three
     * wrapped it).
     */
    suspend fun getAvailableDecks(): List<String> = withContext(Dispatchers.IO) {
        try {
            ankiApi.deckList?.values?.toList()?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getOrCreateModel(
        css: String = CARD_CSS,
        backTemplate: String = CARD_BACK_TEMPLATE
    ): Long? {
        val profile = this.profile
        val modelList = ankiApi.modelList ?: run {
            return null
        }

        val compatibleCandidates = modelList
            .entries
            .asSequence()
            // Only models belonging to THIS profile are candidates. Both
            // names start with "Yomitan-Mobile", so matching on the prefix
            // alone would offer a Japanese note type to an English export;
            // the field-list check below would reject it, but only after
            // the CSS and templates had been pushed to it.
            .filter { (_, name) -> name.startsWith(profile.modelName) }
            .sortedBy { (_, name) -> modelNamePriority(name, profile) }
            .map { it.key }
            .filter { modelId -> isModelCompatible(modelId, profile) }
            .take(MAX_MODEL_CANDIDATES)
            .toList()

        if (compatibleCandidates.isNotEmpty()) {
            // A model whose templates already say what this export would say
            // needs no write at all — and on an AnkiDroid that refuses
            // template writes, it is the only model that renders correctly.
            // Looking for it first is what stops a refused write from minting
            // another note type: one user's collection had 1 113 of them,
            // 1 029 holding a single note.
            val ready = compatibleCandidates.firstOrNull { modelId ->
                templatesMatch(modelId, CARD_FRONT_TEMPLATE, backTemplate)
            }
            if (ready != null) {
                updateModelCss(ready, css)
                return ready
            }
            val modelId = compatibleCandidates.first()
            updateModelCss(modelId, css)
            // Push the current front/back templates so layout changes
            // (re-orderable sections, AI summary slot, etc.) propagate to
            // existing v8 cards.
            if (!updateModelTemplates(modelId, CARD_FRONT_TEMPLATE, backTemplate)) {
                // The write was refused. Keep exporting to this model: its
                // cards render with the previous layout, which is a far
                // smaller problem than a new note type per export.
                android.util.Log.w(
                    "AnkiCardCreator",
                    "Template update refused for model=$modelId; exporting to it anyway"
                )
            }
            return modelId
        }

        return createCompatibleModel(css, backTemplate)
    }

    private fun modelNamePriority(
        name: String,
        profile: com.yomitanmobile.domain.model.CardProfile
    ): Int {
        if (profile != com.yomitanmobile.domain.model.CardProfile.JAPANESE) {
            // English has no legacy note types to migrate from — it is new.
            return if (name == profile.modelName) 0 else 1
        }
        return when (name) {
            MODEL_NAME -> 0
            LEGACY_MODEL_NAME -> 1
            LEGACY_MODEL_NAME_V4 -> 2
            LEGACY_MODEL_NAME_V7 -> 3
            else -> 4
        }
    }

    private fun isModelCompatible(
        modelId: Long,
        profile: com.yomitanmobile.domain.model.CardProfile
    ): Boolean {
        return try {
            val existingFields = ankiApi.getFieldList(modelId)
                .map { it.trim() }
            val expectedFields = profile.fieldNames.map { it.trim() }

            existingFields == expectedFields
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates the note type, or lands on one that already carries this
     * profile's fields. Only reached when the collection has no compatible
     * model at all: a refused template write is no longer a reason to make
     * one (see [getOrCreateModel]).
     */
    private fun createCompatibleModel(
        css: String,
        backTemplate: String = CARD_BACK_TEMPLATE
    ): Long? {
        val profile = this.profile
        val candidateNames = buildList {
            add(profile.modelName)
            for (index in 1..MAX_MODEL_CREATE_RETRIES) {
                add("${profile.modelName}-$index")
            }
        }

        for (candidateName in candidateNames) {
            val createdModelId = runCatching {
                ankiApi.addNewCustomModel(
                    candidateName,
                    profile.fieldNames,
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

            val existingCandidateId = ankiApi.modelList
                ?.entries
                ?.firstOrNull { (_, name) -> name == candidateName }
                ?.key

            if (existingCandidateId != null && isModelCompatible(existingCandidateId, profile)) {
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
    /**
     * True when the model's templates are already the ones this export would
     * write. Unknown (unreadable provider, older AnkiDroid) counts as false,
     * which only means the caller tries the write it would have tried anyway.
     */
    private fun templatesMatch(modelId: Long, frontTemplate: String, backTemplate: String): Boolean {
        return try {
            val uri = Uri.parse("content://com.ichi2.anki.flashcards/models/$modelId/templates/0")
            context.contentResolver.query(uri, arrayOf("qfmt", "afmt"), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                val front = cursor.getString(cursor.getColumnIndexOrThrow("qfmt"))
                val back = cursor.getString(cursor.getColumnIndexOrThrow("afmt"))
                front == frontTemplate && back == backTemplate
            } ?: false
        } catch (e: Exception) {
            android.util.Log.w("AnkiCardCreator", "Reading model templates failed", e)
            false
        }
    }

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
     * @return true if AnkiDroid accepted the push (>= 1 row updated), false
     * if the write was silently dropped or threw. A false used to mean "make
     * another note type"; it now only means the cards on this model keep the
     * layout they have.
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
                    "updateModelTemplates: 0 rows updated for model=$modelId"
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
    internal fun pickFrontContextSentence(entry: WordEntry): String {
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
                    ?: com.yomitanmobile.domain.model.CardSection.defaultOrder(),
                profile
            )
            val modelId = getOrCreateModel(css, backTemplate)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create/find note type"))


            addNoteWithRecovery(
                modelId = modelId,
                deckId = deckId,
                fields = card.toFieldArray(profile),
                css = css,
                backTemplate = backTemplate
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The `Audio` field for one entry: a real recording when the user's
     * archive has one, the synthesiser otherwise.
     *
     * The order is the whole point. A TTS engine reads a headword with no
     * context, so it picks a plausible reading rather than the right one
     * (行った, 一日, 開く) and flattens the pitch accent the card prints right
     * above it. A recording of the word is simply what the word sounds like.
     *
     * Falling back rather than failing is the same rule the monolingual card
     * engine follows: an archive covers a fraction of the dictionary, and a
     * synthesised reading beats a silent card.
     */
    private suspend fun resolveAudio(
        entry: WordEntry,
        tts: TextToSpeech?,
        stylePrefs: CardStylePreferences?,
        media: MediaSink = providerMedia
    ): String {
        val archived = archiveAudio(entry, media)
        if (archived.isNotEmpty()) return archived
        if (tts == null) return ""
        applyRandomVoice(tts, stylePrefs)
        return generateTtsAudio(entry.reading.ifBlank { entry.expression }, tts, media)
    }

    /** The archive's recording for [entry], already handed to [media]. */
    private suspend fun archiveAudio(entry: WordEntry, media: MediaSink): String {
        val archive = audioArchive ?: return ""
        return try {
            val match = archive.find(entry.expression, entry.reading) ?: return ""
            val file = archive.copyToCache(match) ?: return ""
            val reference = media.add(file, file.name)
            // The package writer reads the file when it zips, long after this
            // returns, so only the provider path may delete it here.
            if (media.consumesImmediately) file.delete()
            reference
        } catch (e: Exception) {
            android.util.Log.w("AnkiCardCreator", "Archive audio for ${entry.expression} failed", e)
            ""
        }
    }

    /**
     * Where a card's audio file ends up.
     *
     * Two destinations, and they behave differently enough to be worth naming:
     * AnkiDroid's provider copies the file into its own media folder during
     * the call, while the `.apkg` writer only notes it down and reads it when
     * the zip is built. Deleting a temp file after the call is right for the
     * first and destroys the second.
     */
    interface MediaSink {
        suspend fun add(file: File, fileName: String): String

        /** True when [add] has finished with the file by the time it returns. */
        val consumesImmediately: Boolean get() = true
    }

    private val providerMedia = object : MediaSink {
        override suspend fun add(file: File, fileName: String) = addMediaToAnki(file, fileName)
    }

    /**
     * Collects media for a package instead of handing it to AnkiDroid.
     *
     * The returned reference is the same `[sound:…]` an Anki note carries; the
     * file behind it is copied into the zip at the end.
     */
    class PackageMedia : MediaSink {
        private val collected = LinkedHashMap<String, File>()

        val files: Map<String, File> get() = collected

        override val consumesImmediately: Boolean get() = false

        override suspend fun add(file: File, fileName: String): String {
            val name = fileName.ifBlank { file.name }
            collected[name] = file
            return "[sound:$name]"
        }
    }

    suspend fun generateTtsAudio(
        text: String,
        tts: TextToSpeech,
        media: MediaSink = providerMedia
    ): String =
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
                    val soundRef = media.add(tempFile, fileName)
                    if (media.consumesImmediately) tempFile.delete()
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
            try {
                ankiApi.addMediaFromUri(uri, preferredName, "audio") ?: ""
            } finally {
                // In a finally block: an exception on the way out used to leave
                // AnkiDroid holding read access to the app's cache file for the
                // rest of the process.
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * @param audioWanted whether this card should carry audio at all. It
     * defaults to "there is a synthesiser", which is what the callers used to
     * mean by passing one — but a user with a pronunciation archive and no
     * working TTS voice still wants the recording, so the two questions are
     * now separate.
     */
    suspend fun exportToAnki(entry: WordEntry, tts: TextToSpeech?, deckName: String = DEFAULT_DECK_NAME, stylePrefs: CardStylePreferences? = null, kanjiData: List<com.yomitanmobile.data.local.entity.KanjiEntry> = emptyList(), aiSummaryText: String = "", audioWanted: Boolean = tts != null): Result<Long> {
        val audioFileName = if (audioWanted) resolveAudio(entry, tts, stylePrefs) else ""
        
        var randomFont: String? = null
        if (stylePrefs != null && stylePrefs.randomFontsEnabled && stylePrefs.randomFonts.isNotEmpty()) {
            randomFont = stylePrefs.randomFonts.random()
        }
        
        val kanjiHtml = buildKanjiBreakdownHtml(entry, kanjiData)

        val card = createAnkiCard(entry, audioFileName, randomFont, stylePrefs, aiSummaryText)
            .copy(kanjiBreakdown = kanjiHtml)
        return addNote(card, deckName, stylePrefs)
    }

    /** Kanji column HTML for one entry, ordered as the kanji appear in it. */
    private fun buildKanjiBreakdownHtml(
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

    /**
     * Writes many cards in one go, for the bulk JLPT deck generator.
     *
     * Differences from [exportToAnki], all of them deliberate:
     *  - the note type, deck and CSS are resolved ONCE instead of per word;
     *  - notes go in through `addNotes` in chunks, so AnkiDroid commits one
     *    transaction per chunk rather than one per card (a 1 000-word deck is
     *    seconds instead of minutes);
     *  - kanji rows for a whole chunk are fetched with a single query through
     *    [kanjiProvider];
     *  - TTS audio is opt-in ([tts] non-null), because synthesising a file per
     *    word dominates the runtime;
     *  - no AI summaries: one API call per word would be slow and expensive.
     *
     * The coroutine is cancellable between chunks — a cancelled job keeps the
     * cards written so far, which is why [onProgress] reports committed counts.
     */
    /**
     * The same cards [exportBatchToAnki] would write, prepared for a file.
     *
     * Everything about a card is identical — templates, CSS, audio resolution,
     * kanji breakdown — so a deck imported from a package is the same deck. It
     * is the destination that differs, and with it two things the provider
     * cannot do: the note type is exactly ours, and a card can be created
     * suspended.
     *
     * @param suspendWord decides per word whether its card arrives suspended.
     */
    suspend fun buildPackageNotes(
        entries: List<WordEntry>,
        stylePrefs: CardStylePreferences?,
        kanjiProvider: suspend (List<String>) -> List<com.yomitanmobile.data.local.entity.KanjiEntry>,
        tts: TextToSpeech? = null,
        tags: Set<String> = emptySet(),
        audioWanted: Boolean = tts != null,
        suspendWord: (WordEntry) -> Boolean = { false },
        onProgress: suspend (done: Int, total: Int, currentWord: String) -> Unit = { _, _, _ -> }
    ): Pair<List<ApkgWriter.Note>, Map<String, File>> = withContext(Dispatchers.IO) {
        val media = PackageMedia()
        val notes = ArrayList<ApkgWriter.Note>(entries.size)

        for (chunk in entries.chunked(BATCH_CHUNK_SIZE)) {
            currentCoroutineContext().ensureActive()
            val kanjiChars = chunk
                .flatMap { entry ->
                    entry.expression.filter {
                        com.yomitanmobile.domain.model.MergedWordEntry.isKanji(it)
                    }.map(Char::toString)
                }
                .distinct()
            val kanjiByChar = runCatching { kanjiProvider(kanjiChars) }
                .getOrElse { emptyList() }
                .associateBy { it.kanji }

            for (entry in chunk) {
                currentCoroutineContext().ensureActive()
                onProgress(notes.size, entries.size, entry.expression.ifBlank { entry.reading })

                val audioFileName =
                    if (audioWanted) resolveAudio(entry, tts, stylePrefs, media) else ""
                val randomFont = if (
                    stylePrefs != null && stylePrefs.randomFontsEnabled &&
                    stylePrefs.randomFonts.isNotEmpty()
                ) {
                    stylePrefs.randomFonts.random()
                } else null

                val kanjiData = entry.expression
                    .filter { com.yomitanmobile.domain.model.MergedWordEntry.isKanji(it) }
                    .map(Char::toString)
                    .distinct()
                    .mapNotNull { kanjiByChar[it] }

                val card = createAnkiCard(entry, audioFileName, randomFont, stylePrefs)
                    .copy(kanjiBreakdown = buildKanjiBreakdownHtml(entry, kanjiData))
                notes += ApkgWriter.Note(
                    fields = card.toFieldArray(profile),
                    tags = tags,
                    suspended = suspendWord(entry)
                )
            }
        }
        onProgress(notes.size, entries.size, "")
        notes to media.files
    }

    /** The note type a package written now would carry. */
    fun packageProfile(): com.yomitanmobile.domain.model.CardProfile = profile

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

    suspend fun exportBatchToAnki(
        entries: List<WordEntry>,
        deckName: String,
        stylePrefs: CardStylePreferences?,
        kanjiProvider: suspend (List<String>) -> List<com.yomitanmobile.data.local.entity.KanjiEntry>,
        tts: TextToSpeech? = null,
        tags: Set<String> = emptySet(),
        /** See [exportToAnki]: audio can come from the archive without a TTS voice. */
        audioWanted: Boolean = tts != null,
        onProgress: suspend (done: Int, total: Int, currentWord: String) -> Unit = { _, _, _ -> }
    ): Result<BatchExportResult> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext Result.success(BatchExportResult(0, 0))
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
                ?: com.yomitanmobile.domain.model.CardSection.defaultOrder(),
            profile
        )
        val modelId = getOrCreateModel(css, backTemplate)
            ?: return@withContext Result.failure(IllegalStateException("Failed to create/find note type"))

        var added = 0
        var failed = 0

        for (chunk in entries.chunked(BATCH_CHUNK_SIZE)) {
            currentCoroutineContext().ensureActive()

            // One kanji query per chunk instead of one per word.
            val kanjiChars = chunk
                .flatMap { entry ->
                    entry.expression.filter {
                        com.yomitanmobile.domain.model.MergedWordEntry.isKanji(it)
                    }.map(Char::toString)
                }
                .distinct()
            val kanjiByChar = runCatching { kanjiProvider(kanjiChars) }
                .getOrElse {
                    android.util.Log.w("AnkiCardCreator", "Batch kanji lookup failed", it)
                    emptyList()
                }
                .associateBy { it.kanji }

            val fieldsList = ArrayList<Array<String>>(chunk.size)
            for (entry in chunk) {
                currentCoroutineContext().ensureActive()
                // Count the words prepared so far in this chunk too: with TTS
                // on, building a chunk takes ~50 s and a bar frozen on the
                // last committed count reads as a hang. The committed number
                // is restored at the end of every chunk.
                onProgress(added + fieldsList.size, entries.size, entry.expression.ifBlank { entry.reading })

                val audioFileName = if (audioWanted) resolveAudio(entry, tts, stylePrefs) else ""
                val randomFont = if (
                    stylePrefs != null && stylePrefs.randomFontsEnabled &&
                    stylePrefs.randomFonts.isNotEmpty()
                ) {
                    stylePrefs.randomFonts.random()
                } else null

                val kanjiData = entry.expression
                    .filter { com.yomitanmobile.domain.model.MergedWordEntry.isKanji(it) }
                    .map(Char::toString)
                    .distinct()
                    .mapNotNull { kanjiByChar[it] }

                val card = createAnkiCard(entry, audioFileName, randomFont, stylePrefs)
                    .copy(kanjiBreakdown = buildKanjiBreakdownHtml(entry, kanjiData))
                fieldsList.add(card.toFieldArray(profile))
            }

            val tagsList = List(fieldsList.size) { tags }
            val inserted = runCatching { ankiApi.addNotes(modelId, deckId, fieldsList, tagsList) }
                .getOrElse { error ->
                    android.util.Log.w("AnkiCardCreator", "addNotes failed for a chunk", error)
                    -1
                }

            if (inserted >= 0) {
                added += inserted
                failed += fieldsList.size - inserted
            } else {
                failed += fieldsList.size
            }
            onProgress(added, entries.size, "")
        }

        Result.success(BatchExportResult(added = added, failed = failed))
    }

    /** Picks one of the user's random TTS voices, if that option is on. */
    private fun applyRandomVoice(tts: TextToSpeech, stylePrefs: CardStylePreferences?) {
        if (stylePrefs == null || !stylePrefs.randomVoicesEnabled) return
        if (stylePrefs.randomVoices.isEmpty()) return
        try {
            val randomVoiceName = stylePrefs.randomVoices.random()
            tts.voices?.find { it.name == randomVoiceName }?.let { tts.setVoice(it) }
        } catch (e: Exception) {
            android.util.Log.w("AnkiCardCreator", "Random voice selection failed", e)
        }
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
