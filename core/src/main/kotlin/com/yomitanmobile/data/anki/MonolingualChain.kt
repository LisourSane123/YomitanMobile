package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.WordEntry

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
 * The JP-JP meaning rule, shared by the phone (`MonolingualCardResolver`,
 * which asks Room) and the desktop Kindle program (which streams zips): only
 * where the rows come from differs, so that is the one thing passed in.
 *
 * Three rules, in this order:
 *  1. The Meaning comes from the dictionaries in [chain], in that order: the
 *     first one that has the word supplies it, the next is asked only for the
 *     words the ones before it lacked. A definition that is only a pointer at
 *     another spelling (Pixiv's 「ドキドキ」の表記揺れ) counts as "lacks", and
 *     so does an article about a work ([isWorkEntry]).
 *  2. A word none of them has keeps its English definition — a monolingual
 *     dictionary covers far fewer headwords than JMdict, and an empty back is
 *     worse than an English one.
 *  3. Example sentences stay (they are Japanese) but lose their English
 *     translation.
 *
 * [WordEntry.meaningSource] names the dictionary that answered, "" when none
 * did; it is what puts the `J-J` tag on the note.
 */
object MonolingualChain {

    /**
     * [lookup] returns the rows of one dictionary whose written form is one
     * of the given expressions. Throwing skips that dictionary.
     */
    suspend fun apply(
        entries: List<WordEntry>,
        chain: List<String>,
        lookup: suspend (dictionaryName: String, expressions: List<String>) -> List<WordEntry>,
        onFailure: (dictionaryName: String, error: Throwable) -> Unit = { _, _ -> },
        /**
         * Also ask for a katakana word in hiragana. For onomatopoeia: surasura
         * and the 国語辞典 file ドキドキ under どきどき, and a book writing it
         * in katakana is how five of thirteen sound words missed surasura.
         * Off for vocabulary, where katakana is a loanword's own spelling.
         */
        foldKatakana: Boolean = false
    ): List<WordEntry> {
        if (entries.isEmpty() || chain.isEmpty()) return entries
        val found = arrayOfNulls<List<String>>(entries.size)
        val source = arrayOfNulls<String>(entries.size)
        for (dictionaryName in chain) {
            val missing = entries.indices.filter { found[it] == null }
            if (missing.isEmpty()) break
            val rows = runCatching {
                lookup(dictionaryName, missing.flatMap { i ->
                    val expression = entries[i].expression.ifBlank { entries[i].reading }
                    if (foldKatakana) listOf(expression, toHiragana(expression)).distinct() else listOf(expression)
                })
            }.onFailure { onFailure(dictionaryName, it) }.getOrNull() ?: continue

            // Both the exact (expression, reading) pair and the bare expression:
            // a monolingual dictionary often writes readings differently from
            // JMdict, and an expression-level hit is still the right word as
            // long as no pair matched.
            val byPair = HashMap<String, List<String>>(rows.size)
            val byExpression = HashMap<String, List<String>>(rows.size)
            for (row in rows) {
                val definitions = row.definitions.filter { it.isNotBlank() }
                if (definitions.isEmpty() || definitions.all(::isSpellingPointer) || isWorkEntry(definitions)) continue
                byPair.putIfAbsent(pairKey(row.expression, row.reading), definitions)
                byExpression.putIfAbsent(row.expression.trim(), definitions)
            }
            for (i in missing) {
                val entry = entries[i]
                val expression = entry.expression.ifBlank { entry.reading }
                found[i] = byPair[pairKey(expression, entry.reading)]
                    ?: byExpression[expression.trim()]
                    ?: (if (foldKatakana) byExpression[toHiragana(expression).trim()] else null)
                    ?: continue
                source[i] = dictionaryName
            }
        }
        return entries.mapIndexed { i, entry ->
            entry.copy(
                definitions = found[i] ?: entry.definitions,
                meaningSource = source[i].orEmpty(),
                exampleSentenceTranslation = "",
                examples = entry.examples.map { it.copy(en = "") }
            )
        }
    }

    /** Katakana folded onto hiragana, ー and anything else left as it is. */
    fun toHiragana(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }

    private fun pairKey(expression: String, reading: String): String {
        val expr = expression.trim()
        return expr + " " + reading.trim().ifEmpty { expr }
    }

    /**
     * The setting holds the dictionaries one per line, in the order they are
     * asked. A value saved before there was a list is one name, which reads
     * as a list of one — so an existing choice carries over.
     */
    fun parse(raw: String?): List<String> =
        raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun serialize(names: List<String>): String =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")

    /**
     * A definition that only points somewhere else, and so says nothing on a
     * card: the next dictionary is asked instead. Three shapes, all seen on
     * real cards:
     *  - Pixiv's "another spelling of X" (18 555 articles; どきどき →
     *    「ドキドキ」の表記揺れ, ツル → 「つる」のカタカナ表記, カゴ →
     *    ｢籠｣の平仮名読みである表記揺れ, 世間話 → 噂の別表記である), judged on
     *    its first sentence, since some add a second about a character (兎);
     *  - Pixiv's disambiguation and name pages (奴 → 曖昧さ回避…, 綾 →
     *    人名、あるいはキャラクター名);
     *  - 大辞泉's cross-reference: its headword line, then nothing but
     *    ⇒ lines (部活動 → ⇒部活, 東京都 → ⇒東京).
     */
    fun isSpellingPointer(definition: String): Boolean {
        val text = definition.trim()
        if (text.isEmpty()) return false
        val firstSentence = text.substringBefore('。').substringBefore('\n').trim()
        if (SPELLING_POINTER.matches(firstSentence)) return true
        if (NAVIGATION_PAGE.containsMatchIn(firstSentence)) return true
        if (SAME_AS.matches(firstSentence) && text.substringAfter('。', "").isBlank()) return true
        // One line: headword, its notes in brackets, then ⇒ (インター‐ハイ《…の略》⇒全国高等学校総合体育大会).
        if ('\n' !in text && ONE_LINE_POINTER.matches(BRACKETED.replace(text, "").trim())) return true
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val body = if (lines.firstOrNull()?.let { HEADWORD_LINE.containsMatchIn(it) && !it.contains('⇒') } == true) lines.drop(1) else lines
        // A note in brackets before the arrow (《…の略》⇒…) does not make it a definition.
        return body.isNotEmpty() && body.all { BRACKETED.replace(it, "").trim().let { rest -> rest.startsWith("⇒") || rest.isEmpty() } } &&
            body.any { '⇒' in it }
    }

    private val SPELLING_POINTER = Regex(
        """^(?:[「｢][^」｣]+[」｣]|\S{1,20}?)の(?:\S{0,8}?)(?:表記揺れ|表記ゆれ|別表記|カタカナ表記|ひらがな表記|平仮名表記|平仮名読み|片仮名表記)(?:である|です)?$"""
    )
    /** 「X」に同じ — the whole definition is "same as X". */
    private val SAME_AS = Regex("""^[「｢][^」｣]+[」｣]に同じ$""")
    private val BRACKETED = Regex("""〔[^〕]*〕|［[^］]*］|《[^》]*》|【[^】]*】""")
    private val ONE_LINE_POINTER = Regex("""^[^\s⇒]*⇒\S+$""")
    private val NAVIGATION_PAGE = Regex("""^(?:曖昧さ回避|人名、あるいはキャラクター名|人名またはキャラクター名)""")

    /** 大辞泉's headword line: reading with ‐ and 【spelling】, or a loanword's 【source】. */
    private val HEADWORD_LINE = Regex("""【[^】]+】|‐|◦|〔[^〕]+〕""")

    /**
     * An article about a work or something in one — a song, a film, a
     * character — rather than about the word. Pixiv marks every such article
     * with its work first (`«曲»`, `«ポケモン»`, `«艦これ»`: 116 714 of
     * PixivLight's 709 197), and the same word nearly always has an ordinary
     * article beside it: フラストレーション is 欲求不満 in one and 彩音's
     * song in the other, and which came first in the zip decided the card.
     * Skipping the marked ones lets the ordinary article answer, or the next
     * dictionary when there is none (チョップ is only a Precure character).
     */
    fun isWorkEntry(definitions: List<String>): Boolean =
        definitions.firstOrNull { it.isNotBlank() }?.trim()?.let { WORK_MARKER.containsMatchIn(it) } == true

    private val WORK_MARKER = Regex("""^«[^»]+»""")
}
