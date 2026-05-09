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
        const val MODEL_NAME = "Yomitan-Mobile-v7"
        private const val LEGACY_MODEL_NAME = "Yomitan-Mobile"
        private const val LEGACY_MODEL_NAME_V4 = "Yomitan-Mobile-v4"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        val FIELD_NAMES = arrayOf("Front", "FrontContext", "Reading", "Meaning", "PitchAccent", "Frequency", "Audio", "Sentence", "KanjiBreakdown")

        const val CARD_FRONT_TEMPLATE = """
            <div class="front">
                <span class="expression">{{Front}}</span>
                {{#FrontContext}}<div class="front-context">{{FrontContext}}</div>{{/FrontContext}}
            </div>
        """

        // Flat layout — sections separated by horizontal rules instead of
        // background boxes. Each section is wrapped in its own .section
        // block with consistent vertical padding so neighbouring parts
        // (reading + pitch, meaning + examples, kanji breakdown) don't
        // visually merge into one another. {{Sentence}} only fires for
        // unattached example data (online Tatoeba / pre-seeded
        // SentenceDao). Order: header (expression + reading) → pitch →
        // frequency → meanings → unattached sentences → audio → kanji
        // breakdown.
        const val CARD_BACK_TEMPLATE = """
            <div class="back">
                <div class="section header-section">
                    <div class="expression">{{Front}}</div>
                    <div class="reading">{{Reading}}</div>
                </div>
                {{#PitchAccent}}<hr><div class="section"><div class="pitch">{{PitchAccent}}</div></div>{{/PitchAccent}}
                {{#Frequency}}<hr><div class="section"><div class="freq">{{Frequency}}</div></div>{{/Frequency}}
                <hr>
                <div class="section meaning-section">
                    <div class="meaning">{{Meaning}}</div>
                </div>
                {{#Sentence}}<hr><div class="section"><div class="sentence">{{Sentence}}</div></div>{{/Sentence}}
                {{#Audio}}<hr><div class="section audio-section"><div class="audio">{{Audio}}</div></div>{{/Audio}}
                {{#KanjiBreakdown}}<hr><div class="section kanji-section"><div class="kanji-breakdown">{{KanjiBreakdown}}</div></div>{{/KanjiBreakdown}}
            </div>
        """

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
            .pos-line {
                font-size: 13px; font-style: italic; color: #80cbc4;
                margin: 0 0 10px 0; text-align: left;
                letter-spacing: 0.02em;
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
                font-size: 13px; color: #aaa; margin: 4px 0;
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
                font-size: 13px; color: #aaa; margin: 4px 0;
                ${if (!prefs.showFrequency) "display: none;" else ""}
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
                    <div class="expression">食べる</div>
                    <div class="reading">たべる</div>
                    <hr>
                    <div class="pitch">$previewPitch</div>
                    <hr>
                    <div class="freq">★★★ Top 1K</div>
                    <hr>
                    <div class="meaning">
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
                    </div>
                    <hr>
                    <div class="kanji-breakdown">
                      <div class="kanji-breakdown-title">Kanji</div>
                      <div class="kanji-item">
                        <span class="kanji-char">食</span>
                        <span class="kanji-readings">On: ショク &nbsp; Kun: た.べる, く.う</span>
                        <div class="kanji-meanings">eat, food</div>
                      </div>
                    </div>
                </div>
            </body>
            </html>
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

    private fun getOrCreateModel(css: String = CARD_CSS): Long? {
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
            // Also push the latest front/back templates so users who already
            // have the v7 model installed get the redesigned layout (numbered
            // glosses with examples nested) without us bumping to v8 and
            // leaving an orphaned model in their AnkiDroid.
            updateModelTemplates(compatibleModelId, CARD_FRONT_TEMPLATE, CARD_BACK_TEMPLATE)
            return compatibleModelId
        }

        return createCompatibleModel(css)
    }

    private fun modelNamePriority(name: String): Int {
        return when (name) {
            MODEL_NAME -> 0
            LEGACY_MODEL_NAME -> 1
            LEGACY_MODEL_NAME_V4 -> 2
            else -> 3
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

    private fun createCompatibleModel(css: String): Long? {
        val candidateNames = buildList {
            add(MODEL_NAME)
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
                    arrayOf(CARD_BACK_TEMPLATE),
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

            if (existingCandidateId != null && isModelCompatible(existingCandidateId)) {
                updateModelCss(existingCandidateId, css)
                return existingCandidateId
            }
        }

        return null
    }

    private fun addNoteWithRecovery(
        modelId: Long,
        deckId: Long,
        fields: Array<String>,
        css: String
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
            val fallbackModelId = createCompatibleModel(css)
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
    private fun updateModelTemplates(modelId: Long, frontTemplate: String, backTemplate: String) {
        try {
            val templateUri = Uri.parse("content://com.ichi2.anki.flashcards/models/$modelId/templates/0")
            val values = ContentValues().apply {
                put("qfmt", frontTemplate)
                put("afmt", backTemplate)
            }
            context.contentResolver.update(templateUri, values, null, null)
        } catch (_: Exception) {
            // Templates couldn't be updated (older AnkiDroid, permissions, etc.).
            // The card still renders with whatever template was last installed.
        }
    }

    fun createAnkiCard(
        entry: WordEntry,
        audioFileName: String = "",
        randomFont: String? = null,
        stylePrefs: CardStylePreferences? = null
    ): AnkiCard {
        val pitchHtml = buildPitchAccentHtml(
            entry.reading.ifBlank { entry.expression },
            entry.pitchAccent,
            stylePrefs
        )
        val freqText = entry.frequencyLabel()
        
        val frontExpression = InputSanitizer.escapeHtml(entry.expression.ifBlank { entry.reading })
        val frontContent = if (randomFont != null) "<span style=\"font-family: '$randomFont', sans-serif;\">$frontExpression</span>" else frontExpression
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

        val posLabel = com.yomitanmobile.util.PartsOfSpeechFormatter.format(entry.partsOfSpeech)

        return AnkiCard(
            front = frontContent,
            frontContext = frontContext,
            reading = InputSanitizer.escapeHtml(entry.reading),
            meaning = formatMeaningForCard(entry.definitions, attachedExamples, posLabel),
            pitchAccent = pitchHtml,
            frequency = InputSanitizer.escapeHtml(freqText),
            audioFileName = audioFileName,
            sentence = buildString {
                unattachedExamples.take(3).forEachIndexed { idx, ex ->
                    if (idx > 0) append("<div class=\"sentence-divider\"></div>")
                    if (ex.jp.isNotBlank()) {
                        append("<div class=\"sentence-jp\">")
                        append(InputSanitizer.escapeHtml(ex.jp))
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
        posLabel: String
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
                        append(InputSanitizer.escapeHtml(ex.jp))
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
            val modelId = getOrCreateModel(css)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create/find note type"))


            addNoteWithRecovery(
                modelId = modelId,
                deckId = deckId,
                fields = card.toFieldArray(),
                css = css
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

    suspend fun exportToAnki(entry: WordEntry, tts: TextToSpeech?, deckName: String = DEFAULT_DECK_NAME, stylePrefs: CardStylePreferences? = null, kanjiData: List<com.yomitanmobile.data.local.entity.KanjiEntry> = emptyList()): Result<Long> {
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
                    val safeOnyomi = InputSanitizer.escapeHtml(kanji.onyomi)
                    val safeKunyomi = InputSanitizer.escapeHtml(kanji.kunyomi)

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

        val card = createAnkiCard(entry, audioFileName, randomFont, stylePrefs).copy(kanjiBreakdown = kanjiHtml)
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
