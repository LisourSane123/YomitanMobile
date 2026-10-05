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
        val direct = HashMap<String, String>()
        val bases = dictionaryForms.toHashSet()
        for (base in dictionaryForms) {
            val stems = stemsOf(base)
            if (stems.isEmpty()) continue
            for (form in JapaneseConjugator.derivedForms(base)) {
                if (form == base) continue
                if (stems.none { form.startsWith(it) }) continue
                // A form the text ALSO uses as a headword of its own is only a
                // form when its ending says so. 食べたい and 気にしない are
                // adj-i entries and still inflections; 走り抜ける, 憶える and
                // 乗せる end where a potential or a causative would, and are
                // verbs in their own right — Japanese pairs them with 走り抜く,
                // 憶う and 乗す, and a deck wants both.
                if (form in bases && SAFE_ENDINGS.none { form.endsWith(it) }) continue
                // First base wins: two paradigms reaching the same surface is
                // rare, and picking either is better than dropping the card.
                direct.putIfAbsent(form, base)
            }
        }
        // 食べたかった → 食べたい → 食べる: follow the chain, so one word ends
        // up with one card however many steps the text took to get there.
        val out = HashMap<String, String>(direct.size)
        for ((form, base) in direct) {
            var target = base
            val seen = hashSetOf(form, base)
            while (true) {
                val next = direct[target] ?: break
                if (!seen.add(next)) break
                target = next
            }
            if (target != form) out[form] = target
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
        // 気にする inflects as 気にし〜, which "drop the last kana" (気にす)
        // rejected — so 気にしない and お願いします kept their own cards next to
        // the word they are a form of. Same for the 〜てくる compounds.
        base.endsWith("する") -> base.dropLast(2).let { listOf(it + "し", it + "さ", it + "せ", it + "す") }
        base.endsWith("くる") -> base.dropLast(2).let { listOf(it + "き", it + "こ", it + "く") }
        else -> listOf(base.dropLast(1))
    }

    /**
     * Endings that are never a word of their own, so a headword carrying one
     * is safe to merge into the word it inflects. Deliberately without 〜える /
     * 〜ける / 〜せる: those are where the potential and the causative collide
     * with the transitive-intransitive pairs Japanese is built on.
     */
    private val SAFE_ENDINGS = listOf(
        "たい", "たくない", "たかった", "たくて", "たければ", "たがる",
        "ない", "なかった", "ます", "ました", "ません", "ませんでした",
        "させる", "させられる", "られる", "すぎる", "すぎた", "すぎて",
        "やすい", "にくい", "ながら", "なさい", "そう",
        "さ", "み", "げ", "くて", "かった", "くない", "ければ", "くなる", "くなった"
    )

    private const val SPOKEN_NEGATIVE = "negative (ん)"

    private val NOMINALISING_SUFFIXES = listOf("さ", "み", "げ", "がる", "がって", "がった")

    private val STEM_SUFFIXES = listOf(
        "たい", "たくない", "たかった", "たくて", "たがる", "すぎる", "すぎた", "すぎて",
        "やすい", "にくい", "ながら", "なさい"
    )
}
