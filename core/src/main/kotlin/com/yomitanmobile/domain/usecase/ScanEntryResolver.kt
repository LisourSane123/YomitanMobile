package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.util.JapaneseTokenizer

/**
 * Picks the dictionary entry a scanned word stands for.
 *
 * Shared by the text scanner and the offline book-scan harness, which used to
 * restate the same rule by hand: written form first, reading only for words no
 * written form matched, commonest homophone wins.
 *
 * On top of that it follows REDIRECTS. Jitendex carries entries whose only
 * content is an arrow to the real word — `ああっ ⟶ああ`, `じーちゃん ⟶爺ちゃん`,
 * `ホント ⟶本当`, `フツー ⟶普通`. They exist so a lookup of a non-standard
 * spelling lands somewhere, and a scan meets exactly those spellings in
 * dialogue: emphasis (`バカああああっ`), drawn-out vowels, katakana for tone.
 * Each became a card whose back said "⟶ああ". Following the arrow makes the
 * occurrence count for the word itself, which the stoplist, the Anki check and
 * the frequency cut then judge like any other.
 *
 * Spanish arrives at the same place by a different road: there the DICTIONARY
 * files every conjugation as an entry pointing at its lemma, so the redirect
 * step is what conjugation rules would have been ([FormOf]).
 */
object ScanEntryResolver {

    suspend fun resolve(
        words: Collection<String>,
        byExpressions: suspend (List<String>) -> List<WordEntry>,
        byReadings: suspend (List<String>) -> List<WordEntry>
    ): Map<String, MergedWordEntry> {
        if (words.isEmpty()) return emptyMap()
        val wordList = words.distinct()

        val expressionHits = byExpressions(wordList).groupBy { it.expression }
        val unresolved = wordList.filter { it !in expressionHits }
        val readingHits = if (unresolved.isEmpty()) emptyMap() else byReadings(unresolved).groupBy { it.reading }

        val direct = HashMap<String, MergedWordEntry>(wordList.size)
        for (word in wordList) {
            val entries = expressionHits[word] ?: readingHits[word] ?: continue
            direct[word] = best(entries) ?: continue
        }

        val redirects = direct.mapNotNull { (word, entry) ->
            redirectTarget(entry)?.let { word to it }
        }
        if (redirects.isEmpty()) return direct

        val targetHits = byExpressions(redirects.map { it.second.expression }.distinct())
            .groupBy { it.expression }
        for ((word, target) in redirects) {
            direct.remove(word)
            if (isFragment(word)) continue
            val candidates = targetHits[target.expression].orEmpty()
                .filter { target.reading.isEmpty() || it.reading == target.reading }
                .ifEmpty { targetHits[target.expression].orEmpty() }
            val resolved = best(candidates)?.takeIf { redirectTarget(it) == null } ?: continue
            direct[word] = resolved
        }
        return direct
    }

    /** Where a redirect entry points, or null for an ordinary entry. */
    fun redirectTarget(entry: MergedWordEntry): Target? {
        // Spanish says it in a machine-readable way: an inflected form is an
        // entry pointing at its lemma (see [FormOf]). Following it is what
        // makes a scan count 「hablando」, 「hablé」 and 「hablaron」 as uses of
        // hablar, and put one card in the deck instead of three.
        FormOf.baseOf(entry)?.let { return Target(it, "") }
        // Jitendex ships the arrow plus a second gloss naming it:
        // ["⟶爺ちゃん", "爺ちゃん (redirected from じーちゃん)"].
        val glosses = entry.definitions.filter { it.isNotBlank() }
        if (glosses.isEmpty() || !glosses.first().trimStart().startsWith(ARROW)) return null
        if (!glosses.all { it.trimStart().startsWith(ARROW) || REDIRECT_NOTE in it }) return null
        val text = glosses.first().trim().removePrefix(ARROW).trim()
        val expression = text.substringBefore('（').substringBefore('(').trim()
        if (expression.isEmpty()) return null
        val reading = if ('（' in text) text.substringAfter('（').substringBefore('）').trim() else ""
        return Target(expression, reading)
    }

    data class Target(val expression: String, val reading: String)

    /**
     * A redirect reached from a piece of a word rather than a spelling of one.
     * A lone kanji pointing at its okurigana form is a verb stem chopped off
     * the rest of the verb (`引` ⟶ 引き out of 手を引かれ, `込` ⟶ 込み out of
     * 呑み込まれ); a word opening on っ, ー or ん starts mid-word (`ったい`
     * ⟶ たい out of ぜ────ったい). Following those produced a card for a word
     * the text never used.
     */
    private fun isFragment(word: String): Boolean {
        if (word.length == 1 && JapaneseTokenizer.isKanji(word[0])) return true
        return word.first() in FRAGMENT_OPENERS
    }

    private fun best(entries: List<WordEntry>): MergedWordEntry? =
        MergedWordEntry.mergeEntries(entries)
            .minByOrNull { if (it.frequency > 0) it.frequency else Int.MAX_VALUE }

    private const val ARROW = "⟶"
    private const val REDIRECT_NOTE = "(redirected from"
    private const val FRAGMENT_OPENERS = "っッーんンゃゅょャュョぁぃぅぇぉァィゥェォ"
}
