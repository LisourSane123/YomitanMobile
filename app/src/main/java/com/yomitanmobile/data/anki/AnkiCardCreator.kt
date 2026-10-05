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
import com.yomitanmobile.data.anki.CardTemplates.CARD_CSS
import com.yomitanmobile.data.anki.CardTemplates.CARD_FRONT_TEMPLATE
import com.yomitanmobile.data.anki.CardTemplates.CARD_BACK_TEMPLATE
import com.yomitanmobile.data.anki.CardTemplates.buildBackTemplate
import com.yomitanmobile.data.anki.CardTemplates.buildFrontTemplate
import com.yomitanmobile.data.anki.CardTemplates.buildCssFromPreferences
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
    private val audioArchive: com.yomitanmobile.data.audio.AudioArchive? = null,
    /**
     * The VOICEVOX voice, asked after the archive and before the system TTS.
     * Null in the tests that only build HTML.
     */
    private val voicevox: com.yomitanmobile.data.audio.voicevox.VoicevoxVoice? = null
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
         * Note types this app will never touch, whatever else changes.
         *
         * Anki's own, the shared decks people actually have, and the note
         * types desktop Yomitan/Yomichan writes to. A card here was not made
         * by this app and its owner did not ask us to redesign it.
         */
        private val STANDARD_MODEL_NAMES = listOf(
            "Basic",
            "Basic (and reversed card)",
            "Basic (optional reversed card)",
            "Basic (type in the answer)",
            "Cloze",
            "Image Occlusion",
            "Image Occlusion Enhanced",
            "Yomitan",
            "Yomichan",
            "Yomitan Japanese",
            "Yomichan Japanese",
            "Japanese",
            "Japanese (recognition)",
            "Japanese (recognition&recall)",
            "Mining",
            "JP Mining Note",
            "Lapis",
            "Core 2000",
            "Core 2k",
            "Core 2k/6k",
            "Core 2k/6k Optimized",
            "Core 6000",
            "Core 10k",
            "Kaishi 1.5k",
            "Tango N5",
            "Tango N4",
            "Tango N3",
            "Tango N2",
            "Tango N1"
        )

        /**
         * Notes per `addNotes` call. Big enough that the per-transaction
         * overhead disappears, small enough that a cancelled generation loses
         * at most this many cards' worth of work and progress still moves.
         */
        private const val BATCH_CHUNK_SIZE = 50

        // Yomitan typically shows every sense; for cards a soft cap keeps the
        // back side scrollable. Bumped up from 3 because Jitendex entries with
        // many senses (聞く has 8) were getting truncated to almost nothing.
        private const val MODEL_NAME_PREFIX = "Yomitan-Mobile"
        private const val MAX_MODEL_CREATE_RETRIES = 8

        /**
         * How many compatible note types to look at before picking one. A
         * collection can hold hundreds of them (a refused template write used
         * to mint one per export), and each check is a provider round trip.
         */
        private const val MAX_MODEL_CANDIDATES = 8

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
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The card itself — fields, HTML, styling — is [CardBuilder]'s, shared with
     * the desktop Kindle tool; this class is the AnkiDroid and TTS side. Built
     * per call for the same reason [profile] is read per call.
     */
    private val cardBuilder: CardBuilder
        get() = CardBuilder(profile, com.yomitanmobile.util.LocaleHelper.isEnglish(context.resources.configuration))

    fun pickFrontContextSentence(entry: WordEntry): String = cardBuilder.pickFrontContextSentence(entry)

    fun createAnkiCard(
        entry: WordEntry,
        audioFileName: String = "",
        randomFont: String? = null,
        stylePrefs: CardStylePreferences? = null,
        aiSummaryText: String = ""
    ): AnkiCard = cardBuilder.createAnkiCard(entry, audioFileName, randomFont, stylePrefs, aiSummaryText)

    /** Styling for a package, resolved exactly as the provider path resolves it. */
    fun packageStyling(stylePrefs: CardStylePreferences?): Triple<String, String, String> =
        cardBuilder.packageStyling(stylePrefs)

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
        val synthesised = voicevoxAudio(entry, media)
        if (synthesised.isNotEmpty()) return synthesised
        if (tts == null) return ""
        applyRandomVoice(tts, stylePrefs)
        return generateTtsAudio(entry.reading.ifBlank { entry.expression }, tts, media)
    }

    /**
     * VOICEVOX's recording of [entry], already handed to [media]. Said from
     * the reading with the entry's own pitch accent, so the recording matches
     * the pitch diagram on the same card. The file is the voice's cache entry
     * and is never deleted here: the package writer reads it later, and the
     * detail screen plays the same file.
     */
    private suspend fun voicevoxAudio(entry: WordEntry, media: MediaSink): String {
        val voice = voicevox ?: return ""
        return try {
            val file = voice.wav(entry.expression, entry.reading, entry.pitchAccent) ?: return ""
            media.add(file, file.name)
        } catch (e: Exception) {
            android.util.Log.w("AnkiCardCreator", "VOICEVOX audio for ${entry.expression} failed", e)
            ""
        }
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
        
        val kanjiHtml = cardBuilder.buildKanjiBreakdownHtml(entry, kanjiData)

        val card = createAnkiCard(entry, audioFileName, randomFont, stylePrefs, aiSummaryText)
            .copy(kanjiBreakdown = kanjiHtml)
        return addNote(card, deckName, stylePrefs)
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
                    .copy(kanjiBreakdown = cardBuilder.buildKanjiBreakdownHtml(entry, kanjiData))
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

    /**
     * Every field of a card for [entry], built exactly as an export would.
     *
     * Exists for refreshing notes that are ALREADY in the collection: their
     * fields were written by an older version of the app, with older data
     * (an empty Frequency because no list was installed yet, no audio, a kanji
     * breakdown from a dictionary since replaced). The provider cannot change
     * a note's type, but `updateNoteFields` can rewrite its contents.
     */
    suspend fun rebuildFields(
        entry: WordEntry,
        stylePrefs: CardStylePreferences?,
        kanjiData: List<com.yomitanmobile.data.local.entity.KanjiEntry> = emptyList(),
        tts: TextToSpeech? = null,
        audioWanted: Boolean = false
    ): Array<String> {
        val audio = if (audioWanted) resolveAudio(entry, tts, stylePrefs) else ""
        val randomFont = if (
            stylePrefs != null && stylePrefs.randomFontsEnabled && stylePrefs.randomFonts.isNotEmpty()
        ) {
            stylePrefs.randomFonts.random()
        } else null
        return createAnkiCard(entry, audio, randomFont, stylePrefs)
            .copy(kanjiBreakdown = cardBuilder.buildKanjiBreakdownHtml(entry, kanjiData))
            .toFieldArray(profile)
    }

    /**
     * Rewrites the templates and CSS of one note type to the current design.
     *
     * Public because of the mess this app made: a collection can hold hundreds
     * of near-identical note types minted by the old refusal path, and while
     * the provider will not merge them, it will restyle them — so every card
     * renders as the current design even though its note type is a stray.
     *
     * False when the provider refused the write, which is the same refusal
     * that created the strays in the first place.
     */
    fun restyleModel(modelId: Long, stylePrefs: CardStylePreferences?): Boolean {
        // Built against THIS note type's fields, not the current profile's.
        // The types worth restyling are the ones older versions of this app
        // left behind, and they have fewer fields — a template naming a field
        // they lack prints "{{KanjiBreakdown}}" on the card instead of
        // rendering it, which is a worse card than the one we started with.
        val available = fieldNamesOf(modelId).toSet().ifEmpty { null }
        val css = if (stylePrefs != null) buildCssFromPreferences(stylePrefs) else CARD_CSS
        val back = buildBackTemplate(
            stylePrefs?.sectionOrder
                ?: com.yomitanmobile.domain.model.CardSection.defaultOrder(),
            profile,
            available
        )
        updateModelCss(modelId, css)
        return updateModelTemplates(modelId, buildFrontTemplate(available), back)
    }

    /**
     * Note types this app may rewrite. **The name decides.**
     *
     * Everything that edits an existing collection goes through here, so the
     * test is exact: a profile's model name, or that name followed by a
     * hyphen — which is precisely how AnkiDroid names a model it was asked to
     * create a second time (`Yomitan-Mobile-v8-1`). Nothing from desktop
     * Yomitan ("Yomitan", "Lapis", "JP Mining Note"), Core 2k/6k, Kaishi or a
     * hand-written note type can match it, and [isOurModelName] refuses those
     * names outright as well, so a future loosening of the prefix rule cannot
     * quietly swallow them.
     *
     * Field count is deliberately NOT part of the test. Note types left over
     * from this app's own development have fewer fields — no Summary, no
     * FrontContext, no KanjiBreakdown — and those are exactly the cards worth
     * refreshing. A lean note type is adapted to instead of refused: the
     * templates are built from the fields it actually has (see
     * [buildBackTemplate]'s `available`), and a field it lacks is simply not
     * written.
     */
    fun ourModels(): Map<Long, String> = try {
        // The CURRENT profile only. A Japanese session must not rebuild an
        // English note with Japanese logic, and each profile's note type is
        // named after it anyway.
        val base = profile.modelName
        (ankiApi.modelList ?: emptyMap())
            .filterValues { name -> isOurModelName(name, base) }
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * Is this note-type name one of ours?
     *
     * Two gates. The name has to be the profile's own or a numbered variant of
     * it, and it must not be one of the well-known note types a collection is
     * full of. The second is redundant against the first today — none of those
     * names begins with "Yomitan-Mobile-" — and it stays because the first one
     * is the kind of rule that gets loosened later, and these are the names it
     * must never reach.
     */
    internal fun isOurModelName(name: String, base: String = profile.modelName): Boolean {
        val trimmed = name.trim()
        if (STANDARD_MODEL_NAMES.any { it.equals(trimmed, ignoreCase = true) }) return false
        return trimmed == base || trimmed.startsWith("$base-")
    }

    /** How many notes one note type holds. */
    fun noteCountOf(modelId: Long): Int = try {
        ankiApi.getNoteCount(modelId)
    } catch (_: Exception) {
        0
    }

    /** Field names of one note type, so a stray can be written by name. */
    fun fieldNamesOf(modelId: Long): List<String> = try {
        ankiApi.getFieldList(modelId)?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** Rewrites one existing note's fields. False when the provider refused. */
    fun updateNoteFields(noteId: Long, fields: Array<String>): Boolean = try {
        ankiApi.updateNoteFields(noteId, fields)
    } catch (e: Exception) {
        android.util.Log.w("AnkiCardCreator", "updateNoteFields failed for $noteId", e)
        false
    }

    /** The note type a package written now would carry. */
    fun packageProfile(): com.yomitanmobile.domain.model.CardProfile = profile

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
                    .copy(kanjiBreakdown = cardBuilder.buildKanjiBreakdownHtml(entry, kanjiData))
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

}
