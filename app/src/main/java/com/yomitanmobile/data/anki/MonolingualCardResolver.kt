package com.yomitanmobile.data.anki

import android.content.Context
import android.util.Log
import com.yomitanmobile.MainActivity
import com.yomitanmobile.dataStore
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.repository.DictionaryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Which language the Meaning field of a generated card is written in. */
enum class CardMeaningLanguage(val storageValue: String) {
    /** Definitions exactly as the search dictionaries provide them. */
    ENGLISH("EN"),

    /** Definitions taken from a monolingual (JP-JP) dictionary. */
    JAPANESE("JA");

    companion object {
        fun fromStorage(value: String?): CardMeaningLanguage =
            entries.firstOrNull { it.storageValue == value } ?: ENGLISH
    }
}

/**
 * Rewrites card content for the monolingual (JP-JP) card engine.
 *
 * Sits between "what the search found" and "what gets written to Anki", so
 * both export paths — the detail screen's single card and the bulk JLPT deck
 * generator — produce the same thing, and neither has to know the setting
 * exists.
 *
 * Three rules, in this order:
 *  1. The Meaning field comes from the dictionary the user picked in settings.
 *  2. If that dictionary has no entry for the word, the English definition is
 *     kept rather than shipping a blank card. A monolingual dictionary covers
 *     far fewer headwords than JMdict, and an empty back is worse than an
 *     English one.
 *  3. Example sentences stay as they are (they are already Japanese) but lose
 *     their English translation — a Japanese card with an English sentence
 *     gloss under it defeats the point.
 *
 * Everything else on the card (kanji meanings from KANJIDIC, part-of-speech
 * labels) has no Japanese source installed, so it is left untouched.
 */
@Singleton
class MonolingualCardResolver @Inject constructor(
    private val repository: DictionaryRepository,
    @ApplicationContext private val context: Context,
    private val languageSettings: LanguageSettings
) {

    data class Settings(
        val language: CardMeaningLanguage,
        val dictionaryName: String
    ) {
        val isMonolingual: Boolean
            get() = language == CardMeaningLanguage.JAPANESE && dictionaryName.isNotBlank()
    }

    suspend fun readSettings(): Settings {
        val prefs = context.dataStore.data.first()
        return Settings(
            language = CardMeaningLanguage.fromStorage(prefs[MainActivity.CARD_MEANING_LANGUAGE]),
            dictionaryName = prefs[MainActivity.CARD_MONOLINGUAL_DICTIONARY].orEmpty()
        )
    }

    /** Convenience for the single-card export path. */
    suspend fun apply(entry: WordEntry): WordEntry = apply(listOf(entry)).first()

    /**
     * Applies the engine to a whole batch with ONE database round trip, which
     * is what makes this usable for a 2000-card JLPT deck.
     */
    suspend fun apply(entries: List<WordEntry>): List<WordEntry> {
        if (entries.isEmpty()) return entries
        val language = languageSettings.current
        if (!language.hasJapaneseFeatures) {
            return applyPreferredGlossFirst(entries, language)
        }
        val settings = runCatching { readSettings() }.getOrElse {
            Log.w(TAG, "Reading card language settings failed; keeping English", it)
            return entries
        }
        if (!settings.isMonolingual) return entries

        val monolingual = runCatching {
            repository.getEntriesForExpressionsFromDictionary(
                entries.map { it.expression.ifBlank { it.reading } },
                settings.dictionaryName
            )
        }.getOrElse {
            Log.w(TAG, "Monolingual lookup failed; keeping English definitions", it)
            return entries
        }

        // Index by both the exact (expression, reading) pair and the bare
        // expression: a monolingual dictionary often stores kana-only readings
        // differently from JMdict, and an expression-level hit is still the
        // right word as long as the pair didn't match.
        val byPair = HashMap<String, List<String>>(monolingual.size)
        val byExpression = HashMap<String, List<String>>(monolingual.size)
        for (row in monolingual) {
            val definitions = row.definitions.filter { it.isNotBlank() }
            if (definitions.isEmpty()) continue
            byPair.putIfAbsent(pairKey(row.expression, row.reading), definitions)
            byExpression.putIfAbsent(row.expression.trim(), definitions)
        }

        return entries.map { entry ->
            val expression = entry.expression.ifBlank { entry.reading }
            val japanese = byPair[pairKey(expression, entry.reading)]
                ?: byExpression[expression.trim()]
            entry.copy(
                // Rule 2: no monolingual entry → keep what we had.
                definitions = japanese ?: entry.definitions,
                // Rule 3: Japanese sentences, no English gloss.
                exampleSentenceTranslation = "",
                examples = entry.examples.map { it.copy(en = "") }
            )
        }
    }

    /**
     * Non-Japanese cards: the bilingual gloss if there is one, whatever
     * search found if there isn't.
     *
     * "Preferred" is the dictionary that is the point of the language —
     * kty-en-pl for English, kty-es-en for Spanish. Its monolingual
     * companion (kty-en-en, kty-es-es) is installed alongside it and search
     * returns whichever matched, so without this a card could come out with
     * both languages' definitions interleaved, or with the monolingual one
     * on top purely because of insertion order. Naming the preferred source
     * makes it deterministic: a word it covers gets a purely bilingual back,
     * and only a word it never reached falls through.
     *
     * The fallback is not an error path — for English it is the expected
     * outcome for rarer words, since kty-en-pl has ~79 000 headwords against
     * a much larger vocabulary, and a monolingual definition beats a blank
     * card. With no companion installed either, what the preferred
     * dictionary had stays untouched.
     */
    private suspend fun applyPreferredGlossFirst(
        entries: List<WordEntry>,
        language: AppLanguage
    ): List<WordEntry> {
        val preferredPrefix = language.preferredGlossDictionary ?: return entries
        val glossDictionary = runCatching { findGlossDictionary(preferredPrefix) }.getOrElse {
            Log.w(TAG, "Listing dictionaries failed; leaving definitions as found", it)
            return entries
        } ?: return entries

        val preferred = runCatching {
            repository.getEntriesForExpressionsFromDictionary(
                entries.map { it.expression.ifBlank { it.reading } },
                glossDictionary
            )
        }.getOrElse {
            Log.w(TAG, "Polish gloss lookup failed; keeping what search found", it)
            return entries
        }

        val byExpression = HashMap<String, List<String>>(preferred.size)
        for (row in preferred) {
            val definitions = row.definitions.filter { it.isNotBlank() }
            if (definitions.isEmpty()) continue
            byExpression.putIfAbsent(row.expression.trim(), definitions)
        }
        if (byExpression.isEmpty()) return entries

        return entries.map { entry ->
            val expression = entry.expression.ifBlank { entry.reading }.trim()
            val preferredDefinitions = byExpression[expression]
            if (preferredDefinitions == null) entry else entry.copy(definitions = preferredDefinitions)
        }
    }

    /**
     * The installed English-Polish dictionary, by index.json title.
     *
     * Matched on a prefix rather than an exact string so a re-release
     * ("kty-en-pl-2026") still resolves, and so a user who imported the zip
     * by hand instead of downloading it in-app gets the same behaviour.
     */
    private suspend fun findGlossDictionary(prefix: String): String? =
        repository.getImportedDictionaries().first()
            .map { it.name }
            .firstOrNull { it.trim().lowercase().startsWith(prefix) }

    private fun pairKey(expression: String, reading: String): String {
        val expr = expression.trim()
        val read = reading.trim().ifEmpty { expr }
        return expr + " " + read
    }

    private companion object {
        const val TAG = "MonolingualCard"

    }
}
