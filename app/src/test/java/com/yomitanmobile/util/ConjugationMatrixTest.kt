package com.yomitanmobile.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every inflection of every verb class, put through the two things that have
 * to understand it: [JapaneseDeconjugator] (does the base form come back at
 * all?) and [JapaneseTokenizer] (does a SCAN pick that base form, or does an
 * earlier candidate that happens to be a real word win instead?).
 *
 * The forms are built here by an independent forward conjugator — a plain kana
 * table — rather than taken from the deconjugator's own rule list, so the test
 * cannot agree with the implementation by construction. Irregulars are written
 * out by hand.
 *
 * The run writes `docs/CONJUGATION_MATRIX.md`: a form-by-form report of what
 * resolves and what does not. That document is the point of this test; the
 * assertions only pin the core so a regression fails the build.
 */
class ConjugationMatrixTest {

    // ---------------------------------------------------------------- model

    private data class Word(val base: String, val kind: String, val forms: List<Pair<String, String>>)

    private data class Row(
        val word: String,
        val kind: String,
        val form: String,
        val surface: String,
        val reachedByDeconjugator: Boolean,
        val resolvedByScan: String?
    ) {
        val scanOk: Boolean get() = resolvedByScan == word || isGrammarChain || isSuruNoun

        /**
         * 勉強します resolved to 勉強, not 勉強する. Both are dictionary entries
         * and the noun is the better card, so the scan offering it is correct
         * — see JapaneseDeconjugator.addSuruForms.
         */
        val isSuruNoun: Boolean
            get() = word.endsWith("する") && resolvedByScan == word.removeSuffix("する")

        /**
         * Resolved to one of [JapaneseTokenizer.GRAMMAR_FORMS] instead of the
         * verb: される, させて and the copula chains are consumed whole and
         * counted as grammar on purpose, so this is a pass, not a miss.
         */
        val isGrammarChain: Boolean
            get() = resolvedByScan != null && resolvedByScan in JapaneseTokenizer.GRAMMAR_FORMS

        val status: String get() = when {
            resolvedByScan == word -> "ok"
            isSuruNoun -> "suru-noun (${resolvedByScan})"
            isGrammarChain -> "grammar chain"
            resolvedByScan == null -> "**none**"
            else -> "**${resolvedByScan}**"
        }
    }

    /**
     * Words the scan could confuse an inflection with. They are real dictionary
     * entries whose spelling collides with an intermediate deconjugation
     * candidate — the reason 〜されています used to produce cards for 去る.
     */
    private val distractors = listOf(
        "去る", "敷く", "鋸", "出鼻", "担う", "猿", "咲く", "着る", "斬る", "煮る", "似る"
    )

    // ------------------------------------------------------ forward conjugator

    /** ending → (a-row, i-row, e-row, o-row, te-form, ta-form). */
    private val godanTable = mapOf(
        'う' to listOf("わ", "い", "え", "お", "って", "った"),
        'く' to listOf("か", "き", "け", "こ", "いて", "いた"),
        'ぐ' to listOf("が", "ぎ", "げ", "ご", "いで", "いだ"),
        'す' to listOf("さ", "し", "せ", "そ", "して", "した"),
        'つ' to listOf("た", "ち", "て", "と", "って", "った"),
        'ぬ' to listOf("な", "に", "ね", "の", "んで", "んだ"),
        'ぶ' to listOf("ば", "び", "べ", "ぼ", "んで", "んだ"),
        'む' to listOf("ま", "み", "め", "も", "んで", "んだ"),
        'る' to listOf("ら", "り", "れ", "ろ", "って", "った")
    )

    private fun godan(base: String): Word {
        val stem = base.dropLast(1)
        val table = godanTable.getValue(base.last())
        val a = table[0]
        val i = table[1]
        val e = table[2]
        val o = table[3]
        val te = table[4]
        val ta = table[5]
        return Word(
            base, "godan (-${base.last()})",
            listOf(
                "polite" to "$stem${i}ます",
                "polite past" to "$stem${i}ました",
                "polite negative" to "$stem${i}ません",
                "polite negative past" to "$stem${i}ませんでした",
                "negative" to "$stem${a}ない",
                "negative past" to "$stem${a}なかった",
                "past" to "$stem$ta",
                "te-form" to "$stem$te",
                "progressive" to "$stem${te}いる",
                "progressive (dropped い)" to "$stem${te}る",
                "progressive past" to "$stem${te}いた",
                "resultative" to "$stem${te}しまう",
                "potential" to "$stem${e}る",
                "passive" to "$stem${a}れる",
                "causative" to "$stem${a}せる",
                "causative-passive" to "$stem${a}せられる",
                "imperative" to "$stem$e",
                "volitional" to "$stem${o}う",
                "conditional ba" to "$stem${e}ば",
                "conditional tara" to "$stem${ta}ら",
                "desiderative" to "$stem${i}たい",
                "desiderative negative" to "$stem${i}たくない",
                "desiderative past" to "$stem${i}たかった",
                "simultaneous" to "$stem${i}ながら",
                "excessive" to "$stem${i}すぎる",
                "appearance" to "$stem${i}そう",
                "polite imperative" to "$stem${i}なさい"
            )
        )
    }

    private fun ichidan(base: String): Word {
        val stem = base.dropLast(1)
        return Word(
            base, "ichidan",
            listOf(
                "polite" to "${stem}ます",
                "polite past" to "${stem}ました",
                "polite negative" to "${stem}ません",
                "polite negative past" to "${stem}ませんでした",
                "negative" to "${stem}ない",
                "negative past" to "${stem}なかった",
                "past" to "${stem}た",
                "te-form" to "${stem}て",
                "progressive" to "${stem}ている",
                "progressive (dropped い)" to "${stem}てる",
                "progressive past" to "${stem}ていた",
                "resultative" to "${stem}てしまう",
                "potential" to "${stem}られる",
                "passive" to "${stem}られる",
                "causative" to "${stem}させる",
                "causative-passive" to "${stem}させられる",
                "imperative" to "${stem}ろ",
                "volitional" to "${stem}よう",
                "conditional ba" to "${stem}れば",
                "conditional tara" to "${stem}たら",
                "desiderative" to "${stem}たい",
                "desiderative negative" to "${stem}たくない",
                "desiderative past" to "${stem}たかった",
                "simultaneous" to "${stem}ながら",
                "excessive" to "${stem}すぎる",
                "appearance" to "${stem}そう",
                "polite imperative" to "${stem}なさい"
            )
        )
    }

    private fun iAdjective(base: String): Word {
        val stem = base.dropLast(1)
        return Word(
            base, "i-adjective",
            listOf(
                "negative" to "${stem}くない",
                "negative past" to "${stem}くなかった",
                "past" to "${stem}かった",
                "te-form" to "${stem}くて",
                "adverbial" to "${stem}く",
                "conditional ba" to "${stem}ければ",
                "conditional tara" to "${stem}かったら",
                "excessive" to "${stem}すぎる",
                "appearance" to "${stem}そう",
                "polite" to "${base}です",
                "polite past" to "${stem}かったです"
            )
        )
    }

    // ------------------------------------------------------------- the words

    private val words: List<Word> = listOf(
        godan("買う"), godan("書く"), godan("泳ぐ"), godan("話す"), godan("待つ"),
        godan("死ぬ"), godan("遊ぶ"), godan("読む"), godan("取る"),
        ichidan("食べる"), ichidan("見る"), ichidan("起きる"), ichidan("寝る"),
        iAdjective("高い"), iAdjective("優しい"), iAdjective("楽しい"),

        Word(
            "する", "irregular (suru)",
            listOf(
                "polite" to "します", "polite past" to "しました",
                "polite negative" to "しません", "polite negative past" to "しませんでした",
                "negative" to "しない", "negative past" to "しなかった",
                "past" to "した", "te-form" to "して",
                "progressive" to "している", "progressive (dropped い)" to "してる",
                "passive" to "される",
                "passive te-form" to "されて", "passive past" to "された",
                "causative" to "させる", "causative-passive" to "させられる",
                "imperative" to "しろ", "volitional" to "しよう",
                "conditional ba" to "すれば", "conditional tara" to "したら",
                "desiderative" to "したい", "simultaneous" to "しながら"
            )
        ),
        Word(
            "勉強する", "irregular (noun + suru)",
            listOf(
                "polite" to "勉強します", "past" to "勉強した", "te-form" to "勉強して",
                "negative" to "勉強しない", "passive" to "勉強される",
                "passive past" to "勉強された", "causative" to "勉強させる",
                "volitional" to "勉強しよう",
                "conditional ba" to "勉強すれば", "desiderative" to "勉強したい"
            )
        ),
        Word(
            "来る", "irregular (kuru, kanji)",
            listOf(
                "polite" to "来ます", "polite past" to "来ました",
                "polite negative" to "来ません", "negative" to "来ない",
                "negative past" to "来なかった", "past" to "来た", "te-form" to "来て",
                "progressive" to "来ている", "potential" to "来られる",
                "passive" to "来られる", "causative" to "来させる",
                "imperative" to "来い", "volitional" to "来よう",
                "conditional ba" to "来れば", "conditional tara" to "来たら",
                "desiderative" to "来たい"
            )
        ),
        Word(
            "くる", "irregular (kuru, kana)",
            listOf(
                "polite" to "きます", "polite past" to "きました",
                "polite negative" to "きません", "negative" to "こない",
                "negative past" to "こなかった", "past" to "きた", "te-form" to "きて",
                "potential" to "こられる", "causative" to "こさせる",
                "imperative" to "こい", "volitional" to "こよう",
                "conditional ba" to "くれば", "conditional tara" to "きたら"
            )
        ),
        Word(
            "行く", "irregular (te / ta form)",
            listOf(
                "polite" to "行きます", "negative" to "行かない",
                "past" to "行った", "te-form" to "行って",
                "progressive" to "行っている", "potential" to "行ける",
                "passive" to "行かれる", "causative" to "行かせる",
                "imperative" to "行け", "volitional" to "行こう",
                "conditional ba" to "行けば", "conditional tara" to "行ったら"
            )
        ),
        Word(
            "ある", "irregular (suppletive negative)",
            listOf(
                "polite" to "あります", "polite past" to "ありました",
                "polite negative" to "ありません", "past" to "あった",
                "te-form" to "あって", "conditional ba" to "あれば",
                "conditional tara" to "あったら", "volitional" to "あろう"
            )
        ),
        Word(
            "くれる", "irregular (imperative)",
            listOf(
                "polite" to "くれます", "negative" to "くれない",
                "past" to "くれた", "te-form" to "くれて",
                "imperative" to "くれ", "conditional ba" to "くれれば"
            )
        ),
        Word(
            "下さる", "honorific (r-irregular)",
            listOf(
                "polite" to "下さいます", "imperative" to "下さい",
                "past" to "下さった", "te-form" to "下さって", "negative" to "下さらない"
            )
        ),
        Word(
            "なさる", "honorific (r-irregular)",
            listOf(
                "polite" to "なさいます", "imperative" to "なさい",
                "past" to "なさった", "te-form" to "なさって"
            )
        ),
        Word(
            "いらっしゃる", "honorific (r-irregular)",
            listOf(
                "polite" to "いらっしゃいます", "past" to "いらっしゃった",
                "te-form" to "いらっしゃって", "imperative" to "いらっしゃい"
            )
        ),
        Word(
            "おっしゃる", "honorific (r-irregular)",
            listOf("polite" to "おっしゃいます", "past" to "おっしゃった", "te-form" to "おっしゃって")
        ),
        Word(
            "問う", "irregular (te / ta form)",
            listOf("polite" to "問います", "past" to "問うた", "te-form" to "問うて", "negative" to "問わない")
        ),
        Word(
            "いい", "irregular (suppletive adjective)",
            listOf(
                "negative" to "よくない", "past" to "よかった",
                "negative past" to "よくなかった", "te-form" to "よくて",
                "adverbial" to "よく", "conditional ba" to "よければ"
            )
        ),
        Word(
            "静か", "na-adjective / copula",
            listOf(
                "plain" to "静かだ", "past" to "静かだった",
                "negative" to "静かじゃない", "negative past" to "静かじゃなかった",
                "te-form" to "静かで", "conditional" to "静かなら",
                "polite" to "静かです", "polite past" to "静かでした"
            )
        ),
        // Colloquial contractions a novel is full of.
        Word(
            "食べる", "contraction",
            listOf(
                "te+shimau" to "食べちゃう", "te+shimatta" to "食べちゃった",
                "te+oku" to "食べとく", "negative (colloquial)" to "食べねえ"
            )
        ),
        Word(
            "読む", "contraction",
            listOf(
                "te+shimau" to "読んじゃう", "te+shimatta" to "読んじゃった",
                "progressive (dropped い)" to "読んでる", "te+oku" to "読んどく"
            )
        )
    )

    /** Dictionary words some forms legitimately resolve to. */
    private val extraEntries = listOf("できる", "勉強", "しまう", "おく", "いる", "ない", "そう")

    // ------------------------------------------------------------------ run

    @Test
    fun `every inflection of every verb class resolves to its dictionary form`() {
        val lexiconWords = (words.map { it.base } + distractors + extraEntries).toSet()
        val lexicon = JapaneseTokenizer.Lexicon { it in lexiconWords }

        val rows = words.flatMap { word ->
            word.forms.map { (formName, surface) ->
                val candidates = JapaneseDeconjugator.candidateForms(surface)
                val scanned = JapaneseTokenizer.tokenize(surface, lexicon)
                Row(
                    word = word.base,
                    kind = word.kind,
                    form = formName,
                    surface = surface,
                    reachedByDeconjugator = surface == word.base || word.base in candidates,
                    resolvedByScan = scanned.firstOrNull()?.baseForm
                )
            }
        }

        writeReport(rows)

        // The core: if any of these break, the scanner is mis-reading ordinary
        // prose and the document below is not worth reading.
        val core = rows.filter {
            it.word in setOf("食べる", "書く", "読む", "する", "高い") &&
                it.form in setOf(
                    "polite", "past", "te-form", "negative", "potential",
                    "passive", "causative", "volitional", "conditional ba"
                )
        }
        val brokenCore = core.filterNot { it.scanOk }
        assertTrue(
            "core inflections no longer resolve: " +
                brokenCore.joinToString { "${it.surface} -> ${it.resolvedByScan} (want ${it.word})" },
            brokenCore.isEmpty()
        )
    }

    private fun writeReport(rows: List<Row>) {
        val out = File("../docs/CONJUGATION_MATRIX.md").takeIf { it.parentFile.exists() }
            ?: File("docs/CONJUGATION_MATRIX.md").also { it.parentFile.mkdirs() }

        val failures = rows.filterNot { it.scanOk }
        val text = buildString {
            appendLine("# Conjugation coverage")
            appendLine()
            appendLine(
                "Generated by `ConjugationMatrixTest`. Every form is built by an independent " +
                    "forward conjugator, then put through both halves of the scanner."
            )
            appendLine()
            appendLine("- **deconj**: `JapaneseDeconjugator` offers the dictionary form among its candidates.")
            appendLine(
                "- **scan**: `JapaneseTokenizer` actually resolves the form to that dictionary form, " +
                    "against a lexicon that also contains the words an intermediate candidate could " +
                    "collide with (去る, 敷く, 鋸, 出鼻, 担う, 猿, 咲く, 着る, 斬る). This is the column " +
                    "that matters: the deconjugator returns its candidates sorted, so a wrong word " +
                    "that sorts earlier wins."
            )
            appendLine()
            appendLine("**${rows.count { it.scanOk }} of ${rows.size} forms resolve correctly.**")
            appendLine()
            if (failures.isEmpty()) {
                appendLine("No failures.")
            } else {
                appendLine("## Forms that do NOT resolve")
                appendLine()
                appendLine("| word | class | form | surface | resolved to | deconj |")
                appendLine("|---|---|---|---|---|---|")
                for (row in failures) {
                    appendLine(
                        "| ${row.word} | ${row.kind} | ${row.form} | ${row.surface} | " +
                            "${row.resolvedByScan ?: "— (nothing)"} | ${if (row.reachedByDeconjugator) "yes" else "no"} |"
                    )
                }
                appendLine()
            }
            appendLine("## Full matrix")
            appendLine()
            for ((word, group) in rows.groupBy { it.word to it.kind }.entries.groupBy { it.key.first }) {
                appendLine("### $word")
                appendLine()
                appendLine("| form | surface | deconj | scan |")
                appendLine("|---|---|---|---|")
                for ((_, groupRows) in group) {
                    for (row in groupRows) {
                            appendLine(
                            "| ${row.form} | ${row.surface} | " +
                                "${if (row.reachedByDeconjugator) "ok" else "**no**"} | ${row.status} |"
                        )
                    }
                }
                appendLine()
            }
        }
        out.writeText(text)
        println("[conjugation] report written to ${out.absolutePath}")
    }
}
