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
        private const val MAX_MEANINGS_ON_CARD = 3

        const val DEFAULT_DECK_NAME = "Mining Deck"
        const val MODEL_NAME = "Yomitan-Mobile"
        private const val LEGACY_MODEL_NAME_V4 = "Yomitan-Mobile-v4"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        val FIELD_NAMES = arrayOf("Front", "FrontContext", "Reading", "Meaning", "PitchAccent", "Frequency", "Audio", "Sentence", "KanjiBreakdown")

        const val CARD_FRONT_TEMPLATE = """
            <div class="front">
                <span class="expression">{{Front}}</span>
                {{#FrontContext}}<div class="front-context">{{FrontContext}}</div>{{/FrontContext}}
            </div>
        """

        const val CARD_BACK_TEMPLATE = """
            <div class="back">
                <div class="expression">{{Front}}</div>
                {{#Frequency}}<div class="freq">{{Frequency}}</div>{{/Frequency}}
                <hr>
                <div class="reading">{{Reading}}</div>
                {{#PitchAccent}}<div class="pitch">{{PitchAccent}}</div>{{/PitchAccent}}
                <div class="meaning">{{Meaning}}</div>
                <div class="audio">{{Audio}}</div>
                {{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}
                {{#KanjiBreakdown}}<div class="kanji-breakdown">{{KanjiBreakdown}}</div>{{/KanjiBreakdown}}
            </div>
        """

        const val CARD_CSS = """
            .card {
                font-family: "Hiragino Sans", "Yu Gothic", "Meiryo", sans-serif;
                font-size: 20px;
                text-align: center;
                color: #e0e0e0;
                background-color: #1a1a1a;
                padding: 20px;
            }
            .expression { font-size: 48px; font-weight: bold; color: #ffffff; }
            .reading { font-size: 28px; color: #80cbc4; margin: 10px 0; }
            .meaning {
                font-size: 20px; color: #e0e0e0; margin: 12px 0;
                text-align: left; padding: 12px; background: #2a2a2a; border-radius: 8px;
                border-left: 3px solid #80cbc4;
            }
            .pitch {
                font-size: 16px; color: #ff8a65; margin: 8px 0;
                padding: 6px 12px; background: #2a2a2a; border-radius: 6px;
                display: inline-block;
            }
            .freq {
                font-size: 13px; color: #aaa; margin: 4px 0;
                padding: 2px 10px; background: #333; border-radius: 12px;
                display: inline-block;
            }
            .front-context {
                font-size: 18px; color: #d7d7d7; margin-top: 14px;
                text-align: left; padding: 10px 12px; background: #242424; border-radius: 8px;
                border-left: 3px solid #80cbc4;
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
                font-size: 18px; color: #bbb; margin-top: 15px; font-style: italic;
                text-align: left; padding: 12px; background: #252525; border-radius: 8px;
                border-left: 3px solid #4dd0e1;
            }
            hr { border: none; border-top: 1px solid #444; margin: 15px 0; }
            .kanji-breakdown { font-size: 16px; color: #ccc; margin-top: 15px; padding: 12px; background: #252525; border-radius: 8px; text-align: left; } .kanji-item { margin-bottom: 8px; } .kanji-char { font-size: 24px; color: #fff; margin-right: 8px; font-weight: bold; }
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
            .expression { font-size: ${prefs.expressionFontSize}px; font-weight: $fontWeight; color: ${prefs.expressionColor}; }
            .reading { font-size: ${prefs.readingFontSize}px; color: ${prefs.readingColor}; margin: 10px 0; }
            .meaning {
                font-size: ${prefs.meaningFontSize}px; color: ${prefs.meaningColor}; margin: 12px 0;
                text-align: left; padding: 12px; background: #2a2a2a; border-radius: 8px;
                border-left: 3px solid ${prefs.accentColor};
            }
            .pitch {
                font-size: 16px; color: #ff8a65; margin: 8px 0;
                padding: 6px 12px; background: #2a2a2a; border-radius: 6px;
                display: inline-block;
                ${if (!prefs.showPitchAccent) "display: none;" else ""}
            }
            .freq {
                font-size: 13px; color: #aaa; margin: 4px 0;
                padding: 2px 10px; background: #333; border-radius: 12px;
                display: inline-block;
                ${if (!prefs.showFrequency) "display: none;" else ""}
            }
            .front-context {
                font-size: 18px; color: #d7d7d7; margin-top: 14px;
                text-align: left; padding: 10px 12px; background: #242424; border-radius: 8px;
                border-left: 3px solid ${prefs.accentColor};
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
                font-size: 18px; color: #bbb; margin-top: 15px; font-style: italic;
                text-align: left; padding: 12px; background: #252525; border-radius: 8px;
                border-left: 3px solid #4dd0e1;
                ${if (!prefs.showSentence) "display: none;" else ""}
            }
            hr { border: none; border-top: 1px solid #444; margin: 15px 0; }
            .kanji-breakdown { font-size: 16px; color: #ccc; margin-top: 15px; padding: 12px; background: #252525; border-radius: 8px; text-align: left; } .kanji-item { margin-bottom: 8px; } .kanji-char { font-size: 24px; color: #fff; margin-right: 8px; font-weight: bold; }
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
                    <div class="freq">★★★ Top 1K</div>
                    <hr>
                    <div class="reading">たべる</div>
                    <div class="pitch"><span style="font-size:12px;color:#999;">[2] 中高</span> <span style="padding-top:4px; display:inline-block;">た</span><span style="border-top:2px solid #ff8a65;padding-top:2px;border-right:2px solid #ff8a65; display:inline-block;">べ</span><span style="padding-top:4px; display:inline-block;">る</span></div>
                    <div class="meaning">1. to eat<br>2. to live on (e.g. a salary); to live off; to subsist on</div>
                    <div class="sentence">毎日野菜を食べます。<br><small>I eat vegetables every day.</small></div>
                </div>
            </body>
            </html>
            """.trimIndent()
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
        val compatibleNames = setOf(MODEL_NAME, LEGACY_MODEL_NAME_V4)
        for ((id, name) in modelList) {
            if (name in compatibleNames) {
                // Compatible model exists — update CSS to reflect current style preferences
                updateModelCss(id, css)
                return id
            }
        }
        return ankiApi.addNewCustomModel(
            MODEL_NAME, FIELD_NAMES,
            arrayOf("Card 1"),
            arrayOf(CARD_FRONT_TEMPLATE),
            arrayOf(CARD_BACK_TEMPLATE),
            css, null, null
        )
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

    fun createAnkiCard(
        entry: WordEntry,
        audioFileName: String = "",
        randomFont: String? = null,
        stylePrefs: CardStylePreferences? = null
    ): AnkiCard {
        val pitchHtml = buildPitchAccentHtml(
            entry.reading.ifBlank { entry.expression },
            entry.pitchAccent
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
        
        return AnkiCard(
            front = frontContent,
            frontContext = frontContext,
            reading = InputSanitizer.escapeHtml(entry.reading),
            meaning = formatMeaningForCard(entry.definitions),
            pitchAccent = pitchHtml,
            frequency = InputSanitizer.escapeHtml(freqText),
            audioFileName = audioFileName,
            sentence = buildString {
                append(InputSanitizer.escapeHtml(entry.exampleSentence))
                if (entry.exampleSentenceTranslation.isNotBlank()) {
                    append("<br><small>")
                    append(InputSanitizer.escapeHtml(entry.exampleSentenceTranslation))
                    append("</small>")
                }
            }
        )
    }

    private fun formatMeaningForCard(definitions: List<String>): String {
        val meaningLines = definitions.asSequence()
            .map { it.trim().replace(";", ", ") }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_MEANINGS_ON_CARD)
            .toList()

        return meaningLines.joinToString("<br><br>") { InputSanitizer.escapeHtml(it) }
    }

    /**
     * Build an HTML representation of the pitch accent pattern.
     * Shows morae with overline styling for high pitch.
     */
    private fun buildPitchAccentHtml(reading: String, pitchPositions: String): String {
        if (pitchPositions.isBlank()) return ""
        val positions = pitchPositions.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (positions.isEmpty()) return ""

        val morae = splitIntoMorae(reading)
        if (morae.isEmpty()) return ""

        return buildString {
            positions.forEachIndexed { idx, dropPos ->
                if (idx > 0) append("&nbsp;&nbsp;")
                val pattern = computePitchPattern(morae.size, dropPos)
                val label = when (dropPos) {
                    0 -> "平板"
                    1 -> "頭高"
                    morae.size -> "尾高"
                    else -> "中高"
                }
                append("<span style=\"font-size:12px;color:#999;\">[$dropPos] $label</span> ")
                for (i in morae.indices) {
                    val high = pattern[i]
                    val style = if (high) {
                        "border-top:2px solid #ff8a65;padding-top:2px;"
                    } else {
                        "padding-top:4px;"
                    }
                    // Add drop marker
                    val rightBorder = if (i < morae.size - 1 && pattern[i] != pattern[i + 1]) {
                        if (pattern[i]) "border-right:2px solid #ff8a65;" else "border-right:2px solid #666;"
                    } else ""
                    append("<span style=\"$style$rightBorder display:inline-block;\">${morae[i]}</span>")
                }
            }
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

            val noteId = ankiApi.addNote(modelId, deckId, card.toFieldArray(), null)
            if (noteId != null) {
                Result.success(noteId)
            } else {
                Result.failure(IllegalStateException("Failed to add note - duplicate?"))
            }
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
                        "yomitan_tts_${System.currentTimeMillis()}"
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
            kanjiData.sortedBy { entry.expression.indexOf(it.kanji).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
                .joinToString("") { kanji ->
                    val cleanMeanings = parseKanjiMeanings(kanji.meanings)
                        .map { InputSanitizer.escapeHtml(it) }
                        .joinToString(", ")
                    val safeKanji = InputSanitizer.escapeHtml(kanji.kanji)
                    val safeOnyomi = InputSanitizer.escapeHtml(kanji.onyomi)
                    val safeKunyomi = InputSanitizer.escapeHtml(kanji.kunyomi)

                    "<div class='kanji-item'><span class='kanji-char'>$safeKanji</span>" +
                    (if (safeOnyomi.isNotEmpty()) " On: $safeOnyomi" else "") +
                    (if (safeKunyomi.isNotEmpty()) " Kun: $safeKunyomi" else "") +
                    (if (cleanMeanings.isNotEmpty()) "<br>Znaczenie: $cleanMeanings" else "") +
                    "</div>"
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
