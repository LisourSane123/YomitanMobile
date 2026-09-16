package com.yomitanmobile.domain.usecase

import com.yomitanmobile.util.JapaneseConjugator
import com.yomitanmobile.util.JapaneseDeconjugator

/**
 * One word, one card — however the text happened to inflect it.
 *
 * JMdict files plenty of inflections under headwords of their own: 近く is a
 * noun ("vicinity") ranked 348, 高さ a noun, 食べたい an entry, and the scanner
 * counted each of them as a word in its own right. A reader who has 近い was
 * getting 近く as well, and a reader who has 食べる was getting 食べたい,
 * 食べすぎる and 食べやすい.
 *
 * Two jobs, both of them working off the same generated paradigm
 * ([JapaneseConjugator.derivedForms]):
 *
 *  • [index] collapses the deck onto its own dictionary forms;
 *  • [possibleBases] answers "is this an inflection of something the reader
 *    already has?", which is how 近く finds 近い in the Anki collection even
 *    though the deck never saw 近い at all.
 *
 * 方 is deliberately not an inflection: 読み方 is a word people look up.
 */
object ParadigmMerge {

    /**
     * form → dictionary form, built from the words that look like dictionary
     * forms themselves.
     *
     * A generated form only counts when it starts with the base's stem. The
     * generator emits both conjugation classes for every る-final word (kana
     * alone cannot tell them apart) and among する's forms is できる, a word of
     * its own — the stem test is what keeps those out.
     */
    fun index(dictionaryForms: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val bases = dictionaryForms.toHashSet()
        for (base in dictionaryForms) {
            val stems = stemsOf(base)
            if (stems.isEmpty()) continue
            for (form in JapaneseConjugator.derivedForms(base)) {
                if (form == base || form in bases) continue
                if (stems.none { form.startsWith(it) }) continue
                // First base wins: two paradigms reaching the same surface is
                // rare, and picking either is better than dropping the card.
                out.putIfAbsent(form, base)
            }
        }
        return out
    }

    /**
     * The dictionary forms [word] could be an inflection of, most likely
     * first. Used to ask the Anki collection about a word the deck itself does
     * not contain.
     */
    fun possibleBases(word: String): List<String> {
        if (word.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        // The plain paradigm, through the deconjugator: 食べたい → 食べる,
        // 近く → 近い, 食べすぎる → 食べる.
        JapaneseDeconjugator.analyze(word)
            // 〜ん is the spoken negative (思わん → 思う) AND the tail of
            // ordinary words: 盛ん read that way became "an inflection of 盛る"
            // and lost its card to a 盛る already in the collection.
            .filterNot { SPOKEN_NEGATIVE in it.reason }
            .forEach { out += it.baseForm }
        // The nominalisations, which no conjugation rule reaches: 高さ → 高い,
        // 深み → 深い, 嬉しげ → 嬉しい, 悲しがる → 悲しい.
        for (suffix in NOMINALISING_SUFFIXES) {
            if (word.length > suffix.length && word.endsWith(suffix)) {
                out += word.dropLast(suffix.length) + "い"
            }
        }
        // …and the ます-stem derivations the deconjugator does not chain
        // through (食べたがる → 食べ → 食べる).
        for (suffix in STEM_SUFFIXES) {
            if (word.length <= suffix.length || !word.endsWith(suffix)) continue
            val stem = word.dropLast(suffix.length)
            out += JapaneseDeconjugator.bareStemBases(stem)
            out += stem + "る"
        }
        out.remove(word)
        return out.filter { it.length >= 2 }
    }

    /**
     * What every form of the word starts with: 食べる → 食べ, 近い → 近.
     *
     * The irregulars need their own answer — する inflects as し / さ / せ and
     * 来る as 来 / き / こ, so the plain "drop the last kana" stem would throw
     * away した and 来た. 勉強する and その他の〜する compounds keep the noun,
     * which is stem enough.
     */
    private fun stemsOf(base: String): List<String> = when {
        base.length < 2 -> emptyList()
        base == "する" -> listOf("し", "さ", "せ")
        base == "くる" -> listOf("き", "こ", "く")
        base == "来る" -> listOf("来")
        else -> listOf(base.dropLast(1))
    }

    private const val SPOKEN_NEGATIVE = "negative (ん)"

    private val NOMINALISING_SUFFIXES = listOf("さ", "み", "げ", "がる", "がって", "がった")

    private val STEM_SUFFIXES = listOf(
        "たい", "たくない", "たかった", "たくて", "たがる", "すぎる", "すぎた", "すぎて",
        "やすい", "にくい", "ながら", "なさい"
    )
}
