package com.yomitanmobile.data.anki

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.api.AddContentApi
import com.yomitanmobile.domain.model.AnkiCard
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.util.InputSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AnkiCardCreator(
    private val context: Context
) {
    companion object {
        const val DEFAULT_DECK_NAME = "Mining Deck"
        const val MODEL_NAME = "Yomitan-Mobile-v2"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        val FIELD_NAMES = arrayOf("Front", "Reading", "Meaning", "PitchAccent", "Frequency", "Audio", "Sentence")

        const val CARD_FRONT_TEMPLATE = """
            <div class="front">
                <span class="expression">{{Front}}</span>
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
            .audio { margin: 8px 0; }
            .sentence {
                font-size: 18px; color: #bbb; margin-top: 15px; font-style: italic;
                text-align: left; padding: 12px; background: #252525; border-radius: 8px;
                border-left: 3px solid #4dd0e1;
            }
            hr { border: none; border-top: 1px solid #444; margin: 15px 0; }
        """
    }

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

    private fun getOrCreateModel(): Long? {
        val modelList = ankiApi.modelList ?: run {
            return null
        }
        for ((id, name) in modelList) {
            if (name == MODEL_NAME) return id
        }
        return ankiApi.addNewCustomModel(
            MODEL_NAME, FIELD_NAMES,
            arrayOf("Card 1"),
            arrayOf(CARD_FRONT_TEMPLATE),
            arrayOf(CARD_BACK_TEMPLATE),
            CARD_CSS, null, null
        )
    }

    fun createAnkiCard(entry: WordEntry, audioFileName: String = ""): AnkiCard {
        val pitchHtml = buildPitchAccentHtml(
            entry.reading.ifBlank { entry.expression },
            entry.pitchAccent
        )
        val freqText = entry.frequencyLabel()
        return AnkiCard(
            front = InputSanitizer.escapeHtml(entry.expression.ifBlank { entry.reading }),
            reading = InputSanitizer.escapeHtml(entry.reading),
            meaning = entry.definitions.joinToString("<br>") { InputSanitizer.escapeHtml(it) },
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

    suspend fun addNote(card: AnkiCard, deckName: String = DEFAULT_DECK_NAME): Result<Long> = withContext(Dispatchers.IO) {
        try {
            if (!hasAnkiPermission()) {
                return@withContext Result.failure(SecurityException("AnkiDroid permission not granted"))
            }
            if (!isAnkiInstalled()) {
                return@withContext Result.failure(IllegalStateException("AnkiDroid is not installed"))
            }
            val deckId = getOrCreateDeck(deckName)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create/find deck"))
            val modelId = getOrCreateModel()
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
                val tempFile = File(context.cacheDir, fileName)

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

    suspend fun exportToAnki(entry: WordEntry, tts: TextToSpeech?, deckName: String = DEFAULT_DECK_NAME): Result<Long> {
        val audioFileName = if (tts != null) {
            val textForTts = entry.reading.ifBlank { entry.expression }
            generateTtsAudio(textForTts, tts)
        } else ""
        val card = createAnkiCard(entry, audioFileName)
        return addNote(card, deckName)
    }
}
