package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.util.JapaneseTokenizer

/**
 * "Words" a scan finds that are not words: the sounds of dialogue and the
 * pieces longest-match segmentation chips off real words.
 *
 * Separate from the grammar stoplist on purpose. Grammar is scaffolding the
 * reader KNOWS (こと, ため); noise is something nobody should ever get a card
 * for (ああああ, はっはっ, a はす chipped out of 「はしていない」). Written for
 * the text scanner and meant for any other source of mined words — Kindle's
 * Vocabulary Builder records every stray selection just the same.
 *
 * Rules first, list second: each rule catches a whole family, and [BLACKLIST]
 * holds only what no rule can tell apart from a real word. Grown the way the
 * stoplist was: run `BookScanHarness` over a real book, read the first 100
 * (then 1000) cards, and fix the cause where there is one.
 */
object NoiseRules {

    /**
     * Emphasis and sound effects, judged from the spelling alone.
     *
     * - one kana held down: ああああ, くくくく, ーーー
     * - a laugh or gasp in beats: はっはっ, ふふっふふっ
     * - a word cut off by a glottal stop: ちょっ, にやっ, うわっ, はあっ
     * - a drawn-out particle or filler: よー, なー, はー, ねぇ — the form with
     *   the stretch taken out is a stoplisted word or a single kana
     */
    fun isEmphaticNoise(word: String, isStoplisted: (String) -> Boolean = { false }): Boolean {
        if (word.isEmpty() || !word.all { isKanaOrMark(it) }) return false
        val folded = foldKatakana(word)
        val core = folded.filterNot { it in STRETCH_MARKS || it in SMALL_VOWELS || it == 'っ' }
        if (core.isEmpty()) return true
        if (core.length >= 3 && core.all { it == core[0] }) return true
        if (BEATS.matches(folded)) return true
        if (folded.last() == 'っ' && folded.length <= 4) return true
        // Katakana spells loanwords with ー and small vowels (コート, ファン,
        // ホラー), so only a hiragana word is judged by its stretch.
        val hiragana = word.none { it in 'ァ'..'ヺ' }
        val stretched = folded.any { it in STRETCH_MARKS || it in SMALL_VOWELS }
        if (hiragana && stretched && (core.length <= 1 || isStoplisted(core))) return true
        return false
    }

    /**
     * A number written in kanji numerals and nothing else — 四十 out of
     * 「四十八時間」, 三百. A reader who has the numerals has every number;
     * a card per number teaches nothing. 一人, 二度, 三日 carry a counter and
     * are left alone.
     */
    fun isBareNumber(word: String): Boolean {
        if (word.length >= 2 && word.all { it in NUMERALS }) return true
        // A number with its counter — 十人, 三十分, 二本, 四人 — is the same
        // non-word once the numeral is more than 一: 一人, 一度, 一歩 and 一気
        // are vocabulary in their own right, 十人 and 二十四時 are arithmetic.
        val numerals = word.takeWhile { it in NUMERALS }
        val rest = word.drop(numerals.length)
        return numerals.isNotEmpty() && numerals != "一" && rest.length == 1 && rest[0] in COUNTERS
    }

    /**
     * A short hiragana match that is a piece of something else.
     *
     * The dictionary files rare nouns under their kana too — 蓮 as はす, 鷲 as
     * わし, 出汁 as だし, 檻 as おり — so longest match finds them inside
     * 「はしていない」, 「交わし」, 「孫だし」, 「なくなっており」. What gives
     * them away is that nobody WRITES those words in kana: the entry has a
     * kanji spelling and no "usually kana" tag, and the word is not common.
     * A word that really is written in kana (ずつ, よだれ) carries the tag.
     */
    fun isKanaFragment(word: String, entry: MergedWordEntry): Boolean {
        if (word.length > FRAGMENT_MAX_LENGTH || !word.all { it in 'ぁ'..'ゖ' }) return false
        if (entry.frequency in 1..FRAGMENT_COMMON_RANK) return false
        val spellings = listOf(entry.primaryExpression) + entry.alternativeExpressions
        if (spellings.none { s -> s.any { JapaneseTokenizer.isKanji(it) } }) return false
        return !WordFilterRules.isUsuallyKana(entry)
    }

    /**
     * Plain hiragana, with nothing about the spelling that says "vocabulary".
     *
     * What survives the other rules is nearly all hiragana, because the pieces
     * longest match breaks off a sentence are hiragana by nature (それだけ, かと,
     * けし, たん). Three spellings are kept:
     *  • anything carrying a kanji,
     *  • katakana, which is how Japanese writes its loanwords (クラス, コンビニ,
     *    イヤホン) and plenty of mimetics,
     *  • a two-mora reduplication (わざわざ, そろそろ, ぼちぼち) — the shape of
     *    hiragana onomatopoeia and mimetic adverbs. The two morae have to
     *    differ, or ああああ would come back in through it.
     */
    fun isPlainKana(word: String): Boolean {
        if (word.any { JapaneseTokenizer.isKanji(it) }) return false
        if (word.any { it in 'ァ'..'ヺ' }) return false
        if (word.length == 4 && word.take(2) == word.drop(2) && word[0] != word[1]) return false
        return true
    }

    /**
     * What no rule can tell from a real word, in the spelling the text used.
     * Checked against the word and every spelling of the entry it resolved to.
     */
    fun isBlacklisted(word: String, entry: MergedWordEntry?): Boolean {
        // Exact spellings only: いか is noise in hiragana, イカ is a squid.
        if (word in BLACKLIST) return true
        if (entry == null) return false
        return entry.primaryExpression in BLACKLIST || entry.alternativeExpressions.any { it in BLACKLIST }
    }

    /**
     * Grown from real scans, one line per finding. Every entry says where it
     * came from, so a later reader can tell a mistake from a decision.
     */
    val BLACKLIST: Set<String> = setOf(
        // クラスの大嫌いな女子と結婚することになった。1 — first 1000 cards
        "らか",     // なんらか read as なん + らか; the suffix of 朗らか on its own
        "にし",     // 「そのくらいにしときなよ」: に + しとき, read as 西 in kana
        "いか",     // 「いかにも」: 烏賊 / 以下 in kana, never meant in a novel
        "あったら", // 「暇があったら」: が + あったら, read as 可惜 "alas"
        "だいじょ", // 「だいじょぶ」 cut short
        "はす",     // 「ケンカはしていない」: は + して; 蓮 is tagged usually-kana
        "くい",     // 「くく……くくくく」, a laugh read as 杭
        "ぶつ",     // 「ぶちぶち」 read as 打つ
        "だし",     // 「孫だし」: だ + し, read as 出汁
        "間分",     // 「一時間分」 cut between 時 and 間
        "ぐぬぬ",   // a growl
        // ようこそ実力至上主義の教室へ 1 — first 400 cards
        "はないか",  // 「つもりはないから」: は + ない + か, read as 墨魚
        "何やつ",    // 「何やってるの」 cut between や and つ
        "うだる",    // 「ようだねえ」 read as 茹だる
        "しな",      // 「困るだろうしな」: し + な
    )

    fun foldKatakana(value: String): String = buildString(value.length) {
        for (ch in value) append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
    }

    private fun isKanaOrMark(c: Char): Boolean =
        c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c in STRETCH_MARKS

    private const val NUMERALS = "〇零一二三四五六七八九十百千万億兆"
    // No 日 or 月: 二十日 and 十一月 are words with readings of their own.
    private const val COUNTERS = "人年本枚冊回分時歳位個匹台点件度杯頭羽話巻週秒階番倍"

    private const val STRETCH_MARKS = "ー〜～"
    private const val SMALL_VOWELS = "ぁぃぅぇぉ"

    /** はっはっ, ふふっふふっ: the same one or two kana, each beat closed by っ. */
    private val BEATS = Regex("""^(.{1,2}っ)\1+$""")

    private const val FRAGMENT_MAX_LENGTH = 3

    /**
     * Past this a short kana word is not one a reader meets in kana anyway.
     * The same everyday band the grammar filter uses.
     */
    private const val FRAGMENT_COMMON_RANK = 3_000
}
