package com.yomitanmobile.data.anki

import com.yomitanmobile.data.settings.PreferenceKeys
import android.content.Context
import android.util.Log
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
 *  1. The Meaning field comes from the dictionaries the user picked in
 *     settings, in the order they were put: the first one that has the word
 *     supplies it, the next is asked only for the words the first one lacks.
 *     A definition that is only a pointer at another spelling (Pixiv's
 *     「ドキドキ」の表記揺れ) is not a meaning and counts as "lacks".
 *  2. If no dictionary in that list has the word, the English definition is
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
        /** Asked in this order; the first that has the word supplies it. */
        val dictionaryNames: List<String>
    ) {
        val isMonolingual: Boolean
            get() = language == CardMeaningLanguage.JAPANESE && dictionaryNames.isNotEmpty()
    }

    suspend fun readSettings(): Settings {
        val prefs = context.dataStore.data.first()
        return Settings(
            language = CardMeaningLanguage.fromStorage(prefs[PreferenceKeys.CARD_MEANING_LANGUAGE]),
            dictionaryNames = parseDictionaryChain(prefs[PreferenceKeys.CARD_MONOLINGUAL_DICTIONARY])
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

        // Rule 1: one query per dictionary, each asked only for the words
        // the ones before it did not have.
        val found = arrayOfNulls<List<String>>(entries.size)
        val source = arrayOfNulls<String>(entries.size)
        for (dictionaryName in settings.dictionaryNames) {
            val missing = entries.indices.filter { found[it] == null }
            if (missing.isEmpty()) break
            val monolingual = runCatching {
                repository.getEntriesForExpressionsFromDictionary(
                    missing.map { entries[it].expression.ifBlank { entries[it].reading } },
                    dictionaryName
                )
            }.onFailure {
                Log.w(TAG, "Monolingual lookup in $dictionaryName failed; trying the next one", it)
            }.getOrNull() ?: continue

            // Index by both the exact (expression, reading) pair and the bare
            // expression: a monolingual dictionary often stores kana-only readings
            // differently from JMdict, and an expression-level hit is still the
            // right word as long as the pair didn't match.
            val byPair = HashMap<String, List<String>>(monolingual.size)
            val byExpression = HashMap<String, List<String>>(monolingual.size)
            for (row in monolingual) {
                val definitions = row.definitions.filter { it.isNotBlank() }
                if (definitions.isEmpty() || definitions.all(::isSpellingPointer)) continue
                byPair.putIfAbsent(pairKey(row.expression, row.reading), definitions)
                byExpression.putIfAbsent(row.expression.trim(), definitions)
            }
            for (i in missing) {
                val entry = entries[i]
                val expression = entry.expression.ifBlank { entry.reading }
                val japanese = byPair[pairKey(expression, entry.reading)]
                    ?: byExpression[expression.trim()]
                    ?: continue
                found[i] = japanese
                source[i] = dictionaryName
            }
        }

        return entries.mapIndexed { i, entry ->
            entry.copy(
                // Rule 2: no monolingual entry → keep what we had.
                definitions = found[i] ?: entry.definitions,
                meaningSource = source[i].orEmpty(),
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

    companion object {
        private const val TAG = "MonolingualCard"

        /**
         * The setting holds the dictionaries one per line, in the order they
         * are asked. A value saved before there was a list is one name, which
         * reads as a list of one — so an existing choice carries over.
         */
        fun parseDictionaryChain(raw: String?): List<String> =
            raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun serializeDictionaryChain(names: List<String>): String =
            names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")

        /**
         * "This is another spelling of X" and nothing more. Pixiv files 18 555
         * of its 724 000 articles that way (どきどき → 「ドキドキ」の表記揺れ);
         * on a card it says nothing about the word, so the next dictionary in
         * the list is asked instead.
         */
        private val SPELLING_POINTER = Regex("""^「[^」]+」の(表記揺れ|表記ゆれ|別表記)。?$""")

        fun isSpellingPointer(definition: String): Boolean =
            SPELLING_POINTER.matches(definition.trim())
    }
}
