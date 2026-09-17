package com.yomitanmobile.data.anki

import android.content.Context
import android.util.Log
import com.ichi2.anki.FlashCardsContract
import com.yomitanmobile.data.settings.readCardStylePreferences
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings cards this app wrote earlier up to what it writes today.
 *
 * Two different repairs, because the provider allows exactly one of the three
 * things a user would want:
 *
 *  • **Restyle** — rewrite the templates and CSS of every note type of ours.
 *    A collection that accumulated hundreds of stray `Yomitan-Mobile-v8-N`
 *    types then renders every one of them as the current design. Notes are not
 *    touched at all, so no review history is at risk.
 *  • **Refresh** — rewrite the FIELDS of our notes from today's data:
 *    the frequency number from the list that leads now, a pitch diagram from a
 *    dictionary installed since, a kanji breakdown, a recording from the
 *    pronunciation archive. `AddContentApi.updateNoteFields` updates a note in
 *    place: same note, same id, same scheduling.
 *  • **Merging the strays into one note type is NOT possible from here.** The
 *    provider cannot change a note's `mid`, and the workaround — create a new
 *    note, delete the old — destroys its review history, the one thing in a
 *    collection that cannot be rebuilt. Desktop Anki does it in place
 *    (Browse → Notes → Change Note Type) and that is the honest advice.
 *
 * Nothing here runs without being asked. A refresh writes into the user's
 * collection, so it is surveyed first and reported afterwards, per field.
 */
@Singleton
class AnkiNoteRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ankiCardCreator: AnkiCardCreator,
    private val repository: DictionaryRepository
) {

    /** What is out there, before anything is written. */
    data class Survey(
        val modelCount: Int,
        val noteCount: Int,
        val available: Boolean = true
    ) {
        companion object {
            val UNAVAILABLE = Survey(0, 0, available = false)
        }
    }

    data class RestyleResult(val restyled: Int, val refused: Int)

    data class RefreshResult(
        val updated: Int,
        /** The word could not be found in any installed dictionary. */
        val notInDictionary: Int,
        /** The provider refused the write — the same refusal that made strays. */
        val refused: Int
    )

    /** How many of our note types exist and how many notes they hold. */
    suspend fun survey(): Survey = withContext(Dispatchers.IO) {
        if (!ankiCardCreator.hasAnkiPermission() || !ankiCardCreator.isAnkiInstalled()) {
            return@withContext Survey.UNAVAILABLE
        }
        val models = ankiCardCreator.ourModels()
        if (models.isEmpty()) return@withContext Survey(0, 0)
        val notes = models.keys.sumOf { modelId ->
            runCatching { countNotes(modelId) }.getOrDefault(0)
        }
        Survey(models.size, notes)
    }

    /** Rewrites templates and CSS on every note type of ours. */
    suspend fun restyleAll(): RestyleResult = withContext(Dispatchers.IO) {
        val prefs = stylePreferences()
        var restyled = 0
        var refused = 0
        for (modelId in ankiCardCreator.ourModels().keys) {
            currentCoroutineContext().ensureActive()
            if (ankiCardCreator.restyleModel(modelId, prefs)) restyled++ else refused++
        }
        RestyleResult(restyled, refused)
    }

    /**
     * Rebuilds the fields of every note of ours from today's data.
     *
     * Two rules keep it from destroying work the app cannot regenerate:
     *
     *  • A field the rebuild leaves EMPTY keeps whatever the note already had.
     *    The AI summary costs an API call per card and the front-context
     *    sentence came out of a book the app no longer has; both would
     *    otherwise be silently erased.
     *  • A note whose word is in no installed dictionary is left alone
     *    entirely, rather than rewritten from a blank entry.
     *
     * Fields are matched BY NAME, through the note type's own field list, so a
     * stray note type is updated correctly even if its order ever differed.
     */
    suspend fun refreshAll(
        audioWanted: Boolean = false,
        onProgress: suspend (done: Int, total: Int, word: String) -> Unit = { _, _, _ -> }
    ): RefreshResult = withContext(Dispatchers.IO) {
        val prefs = stylePreferences()
        val profileFields = ankiCardCreator.packageProfile().fieldNames
        var updated = 0
        var missing = 0
        var refused = 0
        var done = 0

        val models = ankiCardCreator.ourModels()
        val total = models.keys.sumOf { runCatching { countNotes(it) }.getOrDefault(0) }

        for ((modelId, modelName) in models) {
            currentCoroutineContext().ensureActive()
            val noteTypeFields = ankiCardCreator.fieldNamesOf(modelId)
            if (noteTypeFields.isEmpty()) {
                Log.w(TAG, "Skipping '$modelName': the provider lists no fields for it")
                continue
            }
            // Deliberately no "must have all our fields" test. The note types
            // most in need of a refresh are the ones this app left behind
            // while it was being written, and they have fewer fields. Each
            // field is written by NAME, so a type without Summary simply never
            // receives one.
            val wordIndex = noteTypeFields.indexOf(FRONT_FIELD).takeIf { it >= 0 } ?: 0

            for ((noteId, current) in readNotes(modelId)) {
                currentCoroutineContext().ensureActive()
                done++
                // "Front" when the type has it; otherwise its first field,
                // which in every version of our note type is the word.
                val front = current.getOrNull(wordIndex).orEmpty()
                val word = plainText(front)
                onProgress(done, total, word)
                if (word.isBlank()) continue

                val entry = lookup(word)
                if (entry == null) {
                    missing++
                    continue
                }

                val kanji = runCatching {
                    repository.getKanjis(
                        entry.expression
                            .filter { com.yomitanmobile.domain.model.MergedWordEntry.isKanji(it) }
                            .map(Char::toString)
                            .distinct()
                    )
                }.getOrDefault(emptyList())

                val rebuilt = ankiCardCreator.rebuildFields(
                    entry = entry,
                    stylePrefs = prefs,
                    kanjiData = kanji,
                    audioWanted = audioWanted
                )

                val merged = merge(current, rebuilt, noteTypeFields, profileFields)
                if (merged.contentEquals(current)) continue
                if (ankiCardCreator.updateNoteFields(noteId, merged)) updated++ else refused++
            }
        }
        RefreshResult(updated, missing, refused)
    }

    /**
     * The new value per field, falling back to what the note already held.
     *
     * Indexed by the NOTE TYPE's field list, not ours: the array handed to the
     * provider has to be in that type's own order and length.
     */
    private fun merge(
        current: Array<String>,
        rebuilt: Array<String>,
        noteTypeFields: List<String>,
        profileFields: Array<String>
    ): Array<String> = Array(noteTypeFields.size) { index ->
        val name = noteTypeFields[index]
        val ours = profileFields.indexOf(name)
        val fresh = if (ours >= 0) rebuilt.getOrNull(ours).orEmpty() else ""
        if (fresh.isNotBlank()) fresh else current.getOrNull(index).orEmpty()
    }

    /** The dictionary entry behind a card's front, or null. */
    private suspend fun lookup(word: String): WordEntry? {
        val direct = runCatching { repository.getEntriesForExpressions(listOf(word)) }
            .getOrDefault(emptyList())
        if (direct.isNotEmpty()) return best(direct)
        val byReading = runCatching { repository.getEntriesByReading(word) }.getOrDefault(emptyList())
        return best(byReading)
    }

    /** Commonest sense wins, the same rule the scanner's resolver uses. */
    private fun best(entries: List<WordEntry>): WordEntry? = entries
        .minByOrNull { if (it.frequency > 0) it.frequency else Int.MAX_VALUE }

    private suspend fun stylePreferences(): CardStylePreferences =
        readCardStylePreferences(context.dataStore.data.first())

    private fun countNotes(modelId: Long): Int =
        runCatching { ankiCardCreator.noteCountOf(modelId) }.getOrDefault(0)

    /** Note id → its field values, for one note type. */
    private fun readNotes(modelId: Long): List<Pair<Long, Array<String>>> = try {
        val out = ArrayList<Pair<Long, Array<String>>>()
        context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI,
            arrayOf(
                FlashCardsContract.Note._ID,
                FlashCardsContract.Note.FLDS,
                FlashCardsContract.Note.MID
            ),
            "mid:$modelId",
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(FlashCardsContract.Note._ID)
            val fldsIndex = cursor.getColumnIndex(FlashCardsContract.Note.FLDS)
            val midIndex = cursor.getColumnIndex(FlashCardsContract.Note.MID)
            while (cursor.moveToNext()) {
                // The provider's search syntax has varied; filtering here too
                // means a version that ignores `mid:` cannot make us rewrite
                // somebody else's notes.
                if (midIndex >= 0 && cursor.getLong(midIndex) != modelId) continue
                val id = if (idIndex >= 0) cursor.getLong(idIndex) else continue
                val flds = (if (fldsIndex >= 0) cursor.getString(fldsIndex) else null) ?: continue
                out += id to flds.split(FIELD_SEPARATOR).toTypedArray()
            }
        }
        out
    } catch (e: Exception) {
        Log.w(TAG, "Reading notes of model $modelId failed", e)
        emptyList()
    }

    private fun plainText(value: String): String = value
        .replace(Regex("\\[sound:[^]]*]"), "")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .trim()

    companion object {
        private const val TAG = "AnkiNoteRefresher"
        private const val FIELD_SEPARATOR = ''
        private const val FRONT_FIELD = "Front"
    }
}
