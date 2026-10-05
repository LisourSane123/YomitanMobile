package com.yomitanmobile.util

/**
 * Forward counterpart to [JapaneseDeconjugator].
 *
 * Given a dictionary form (verb / i-adjective) it generates the plausible
 * inflected SURFACE forms, e.g. 食べる -> [食べた, 食べて, 食べない, 食べます, …].
 *
 * Used by [SentenceContextHighlighter] so a context sentence that contains a
 * conjugated occurrence of the word (食べる in the dictionary, 食べた in the
 * sentence) still gets highlighted. Where the deconjugator collapses many
 * inflections back to one base form, this expands one base form out to the
 * inflections we might encounter in running text.
 *
 * る-final words are inherently ambiguous (ichidan vs. godan-る) from kana
 * alone, so we emit BOTH conjugation sets. Only the form that actually occurs
 * in a sentence will be matched, so the extra candidates are harmless.
 */
object JapaneseConjugator {

    // う-row dictionary ending -> the same column in the あ / い / え / お rows.
    private val uRowToA = mapOf(
        'う' to 'わ', 'く' to 'か', 'ぐ' to 'が', 'す' to 'さ', 'つ' to 'た',
        'ぬ' to 'な', 'ぶ' to 'ば', 'む' to 'ま', 'る' to 'ら'
    )
    private val uRowToI = mapOf(
        'う' to 'い', 'く' to 'き', 'ぐ' to 'ぎ', 'す' to 'し', 'つ' to 'ち',
        'ぬ' to 'に', 'ぶ' to 'び', 'む' to 'み', 'る' to 'り'
    )
    private val uRowToE = mapOf(
        'う' to 'え', 'く' to 'け', 'ぐ' to 'げ', 'す' to 'せ', 'つ' to 'て',
        'ぬ' to 'ね', 'ぶ' to 'べ', 'む' to 'め', 'る' to 'れ'
    )
    private val uRowToO = mapOf(
        'う' to 'お', 'く' to 'こ', 'ぐ' to 'ご', 'す' to 'そ', 'つ' to 'と',
        'ぬ' to 'の', 'ぶ' to 'ぼ', 'む' to 'も', 'る' to 'ろ'
    )

    /**
     * Returns the dictionary form together with its plausible inflected surface
     * forms. Non-inflectable input (nouns, particles, too-short strings) comes
     * back as just the input itself, so callers can use the result uniformly.
     */
    fun inflectedForms(dictionaryForm: String): List<String> {
        val word = dictionaryForm.trim()
        if (word.length < 2) return if (word.isBlank()) emptyList() else listOf(word)

        val forms = LinkedHashSet<String>()
        forms += word

        when {
            word == "くる" -> forms += kanaKuruForms
            word == "来る" -> forms += kanjiKuruForms
            word.endsWith("する") -> forms += suruForms(word.removeSuffix("する"))
            word.endsWith("い") -> forms += iAdjectiveForms(word.dropLast(1))
        }

        val last = word.last()
        // る-final: could be ichidan; emit the ichidan set (stem = drop る).
        if (last == 'る') forms += ichidanForms(word.dropLast(1))
        // う-row final (incl. る): could be godan; emit the godan set.
        if (uRowToI.containsKey(last)) forms += godanForms(word)

        return forms.toList()
    }

    /**
     * Everything that is still the same WORD: the paradigm plus the
     * derivations a dictionary files under their own headword — 食べたい,
     * 高さ, 深み, 悲しがる, 食べすぎる.
     *
     * Wider than [inflectedForms], which only has to find the word inside a
     * sentence. This set is what the text scanner merges cards by, so a reader
     * who has 食べる does not also get 食べたい, 食べすぎる and 食べ方 as three
     * more cards. Callers must still check that the form starts with the
     * base's stem: the る-final ambiguity makes this list emit both classes'
     * endings, and among them する's potential できる, which is a word of its
     * own.
     */
    fun derivedForms(dictionaryForm: String): List<String> {
        val word = dictionaryForm.trim()
        if (word.length < 2) return emptyList()
        val out = LinkedHashSet(inflectedForms(word))
        if (word.endsWith("い")) {
            val stem = word.dropLast(1)
            out += ADJECTIVE_DERIVATIONS.map { stem + it }
        }
        masuStemOf(word)?.let { stem ->
            out += VERB_DERIVATIONS.map { stem + it }
        }
        return out.toList()
    }

    /** 食べる → 食べ, 話す → 話し: the stem everything polite hangs off. */
    private fun masuStemOf(word: String): String? =
        inflectedForms(word).firstOrNull { it.endsWith("ます") }?.removeSuffix("ます")?.takeIf { it.isNotEmpty() }

    /**
     * On the ます-stem. たい is an inflection of the verb however JMdict files
     * it, and so are すぎる and そう; 方 is deliberately absent — 読み方 is a
     * word people look up.
     */
    private val VERB_DERIVATIONS = listOf(
        "たい", "たくない", "たかった", "たくて", "たければ", "たがる", "たがって",
        "すぎる", "すぎた", "すぎて", "そう", "そうだ", "ながら", "なさい", "やすい", "にくい"
    )

    /** On the adjective stem: 高さ, 深み, 嬉しげ, 悲しがる, 高すぎる. */
    private val ADJECTIVE_DERIVATIONS = listOf(
        "さ", "み", "げ", "がる", "がって", "がった", "そう", "すぎる", "すぎた", "すぎて",
        "く", "くて", "かった", "くない", "くなかった", "ければ", "くなる", "くなった", "くなり"
    )

    private fun iAdjectiveForms(stem: String): List<String> {
        if (stem.isBlank()) return emptyList()
        return listOf(
            stem + "い",
            stem + "く",
            stem + "くて",
            stem + "かった",
            stem + "くない",
            stem + "くなかった",
            stem + "ければ"
        )
    }

    private fun ichidanForms(stem: String): List<String> {
        if (stem.isBlank()) return emptyList()
        return listOf(
            stem + "る",
            stem + "ます", stem + "ました", stem + "ません", stem + "ませんでした",
            stem + "た", stem + "て", stem + "ている", stem + "てる",
            stem + "ない", stem + "なかった",
            stem + "られる", stem + "られた", stem + "られない",
            stem + "させる", stem + "させた",
            stem + "させられる", stem + "させられた",
            stem + "れる", // colloquial potential (食べれる)
            stem + "よう", stem + "ろ", stem + "れば"
        )
    }

    private fun godanForms(word: String): List<String> {
        val last = word.last()
        val aRow = uRowToA[last] ?: return emptyList()
        val iRow = uRowToI.getValue(last)
        val eRow = uRowToE.getValue(last)
        val oRow = uRowToO.getValue(last)
        val base = word.dropLast(1)
        val masuStem = base + iRow

        val (te, ta) = when (last) {
            'う', 'つ', 'る' -> "って" to "った"
            'く' -> "いて" to "いた"
            'ぐ' -> "いで" to "いだ"
            'す' -> "して" to "した"
            'ぬ', 'ぶ', 'む' -> "んで" to "んだ"
            else -> return emptyList()
        }

        return listOf(
            word,
            masuStem + "ます", masuStem + "ました", masuStem + "ません", masuStem + "ませんでした",
            base + ta, base + te, base + te + "いる", base + te + "る",
            base + aRow + "ない", base + aRow + "なかった",
            base + aRow + "れる", // passive
            base + aRow + "せる", // causative
            base + eRow + "る",   // potential
            base + eRow,          // imperative
            base + eRow + "ば",   // conditional
            base + oRow + "う"    // volitional
        )
    }

    private fun suruForms(prefix: String): List<String> = listOf(
        prefix + "する", prefix + "します", prefix + "しました",
        prefix + "しません", prefix + "しませんでした",
        prefix + "した", prefix + "して", prefix + "している", prefix + "してる",
        prefix + "しない", prefix + "しなかった",
        prefix + "される", prefix + "させる", prefix + "させられる",
        prefix + "できる", prefix + "しよう", prefix + "しろ", prefix + "すれば"
    )

    // 来る (kuru) is irregular: kana surface changes く/き/こ across the
    // paradigm, so there is no constant prefix to append to — list the forms.
    private val kanaKuruForms = listOf(
        "くる", "きます", "きました", "きません", "きませんでした",
        "きた", "きて", "きている", "きてる",
        "こない", "こなかった", "こられる", "こさせる",
        "こよう", "こい", "くれば"
    )

    // Same paradigm written with the 来 kanji: the kanji is constant and only
    // the okurigana changes (来る / 来た / 来て / 来ない …).
    private val kanjiKuruForms = listOf(
        "来る", "来ます", "来ました", "来ません", "来ませんでした",
        "来た", "来て", "来ている", "来てる",
        "来ない", "来なかった", "来られる", "来させる",
        "来よう", "来い", "来れば"
    )
}
