package com.yomitanmobile.util

/**
 * Simple rule-based Japanese deconjugator for search assistance.
 *
 * It returns plausible base forms for inflected Japanese forms (verbs/adjectives),
 * e.g. 食べさせられた -> 食べる, 飲んで -> 飲む, 高かった -> 高い.
 */
data class DeconjugationCandidate(
    val baseForm: String,
    val reason: String
)

object JapaneseDeconjugator {
    private const val MAX_DEPTH = 3
    private const val MAX_CANDIDATES = 24

    /**
     * Auxiliaries that attach to a て / で form. Longest first so ています is
     * matched before ます.
     */
    private val TE_AUXILIARIES = listOf(
        "いました", "いません", "しまいました", "しまった", "しまう", "います",
        "おいた", "おきます", "あった", "あります", "いった", "いきます",
        "きました", "きます", "みました", "みます", "ください", "くれる",
        "もらう", "いない", "いた", "いて", "いる", "おく", "ある", "いく",
        "くる", "みる", "ます", "ました", "てる", "たら", "た", "る"
    )

    /** し-inflections that mark a する verb. Longest first. */
    private val SURU_SUFFIXES = listOf(
        "しなかった", "しませんでした", "しなければ", "しません", "しました",
        "しよう", "しない", "します", "しろ", "した", "して", "する"
    )

    /** Same for 来る, whose stem changes vowel (き / く / こ). */
    private val KURU_SUFFIXES = listOf(
        "きませんでした", "こなかった", "きました", "きません", "きます",
        "こない", "こよう", "きた", "きて"
    )

    /** Irregular て / た forms of 行く, in kanji and in kana. */
    private val IKU_FORMS = listOf(
        "行った" to "行く", "行って" to "行く",
        "いった" to "いく", "いって" to "いく"
    )

    /** Copula tails that say nothing about the word in front of them. */
    private val COPULA_SUFFIXES = listOf(
        "ではありません", "じゃありません", "ではなかった", "じゃなかった",
        "ではない", "じゃない", "でした", "だった", "です", "だ"
    )

    private val iRowToU = mapOf(
        'い' to 'う',
        'き' to 'く',
        'ぎ' to 'ぐ',
        'し' to 'す',
        'ち' to 'つ',
        'に' to 'ぬ',
        'び' to 'ぶ',
        'み' to 'む',
        'り' to 'る'
    )

    private val aRowToU = mapOf(
        'わ' to 'う',
        'か' to 'く',
        'が' to 'ぐ',
        'さ' to 'す',
        'た' to 'つ',
        'な' to 'ぬ',
        'ば' to 'ぶ',
        'ま' to 'む',
        'ら' to 'る'
    )

    /** え-row → う-row: potential (読める→読む) and conditional (行けば→行く). */
    private val eRowToU = mapOf(
        'え' to 'う',
        'け' to 'く',
        'げ' to 'ぐ',
        'せ' to 'す',
        'て' to 'つ',
        'ね' to 'ぬ',
        'べ' to 'ぶ',
        'め' to 'む',
        'れ' to 'る'
    )

    /** お-row → う-row: volitional (行こう→行く). */
    private val oRowToU = mapOf(
        'お' to 'う',
        'こ' to 'く',
        'ご' to 'ぐ',
        'そ' to 'す',
        'と' to 'つ',
        'の' to 'ぬ',
        'ぼ' to 'ぶ',
        'も' to 'む',
        'ろ' to 'る'
    )

    private data class Step(
        val form: String,
        val reason: String
    )

    private data class Node(
        val form: String,
        val reasons: List<String>,
        val depth: Int
    )

    /**
     * Returns inferred dictionary forms (without the original input).
     */
    fun candidateForms(input: String): List<String> {
        return analyze(input).map { it.baseForm }
    }

    /**
     * Analyze the input and return possible base forms + short reasoning chain.
     */
    fun analyze(input: String): List<DeconjugationCandidate> {
        val normalized = input.trim()
        if (normalized.isBlank()) return emptyList()

        val explanations = LinkedHashMap<String, MutableList<String>>()
        explanations[normalized] = mutableListOf("input")

        val seen = mutableSetOf(normalized)
        val queue = ArrayDeque<Node>()
        queue.add(Node(normalized, emptyList(), depth = 0))

        while (queue.isNotEmpty() && seen.size < MAX_CANDIDATES) {
            val node = queue.removeFirst()
            if (node.depth >= MAX_DEPTH) continue

            val steps = oneStep(node.form)
            for (step in steps) {
                if (step.form.isBlank() || step.form == node.form) continue
                if (step.form.length < 2) continue

                val chain = (node.reasons + step.reason).takeLast(3)
                val chainText = chain.joinToString(" -> ")

                val reasons = explanations.getOrPut(step.form) { mutableListOf() }
                if (chainText.isNotBlank() && chainText !in reasons) {
                    reasons.add(chainText)
                }

                if (seen.add(step.form)) {
                    queue.add(Node(step.form, chain, node.depth + 1))
                    if (seen.size >= MAX_CANDIDATES) break
                }
            }
        }

        return explanations
            .asSequence()
            .filter { (form, _) -> form != normalized }
            .map { (form, reasons) ->
                DeconjugationCandidate(
                    baseForm = form,
                    reason = reasons.firstOrNull().orEmpty().ifBlank { "deconjugated" }
                )
            }
            .sortedWith(compareBy<DeconjugationCandidate> { it.baseForm.length }.thenBy { it.baseForm })
            .toList()
    }

    private fun oneStep(form: String): List<Step> {
        val out = mutableListOf<Step>()

        addPoliteForms(form, out)
        addPastForms(form, out)
        addTeForms(form, out)
        addNegativeForms(form, out)
        addCausativePassiveForms(form, out)
        addIAdjectiveForms(form, out)
        addAuxiliaryChains(form, out)
        addDesiderativeForms(form, out)
        addConditionalForms(form, out)
        addContractedNegatives(form, out)
        addVolitionalForms(form, out)
        addPotentialForms(form, out)
        addSuruForms(form, out)
        addKuruForms(form, out)
        addIkuForms(form, out)
        addCopulaForms(form, out)

        return out.distinctBy { it.form }
    }

    private fun addPoliteForms(form: String, out: MutableList<Step>) {
        when {
            form.endsWith("ませんでした") -> addFromMasuStem(
                stem = form.removeSuffix("ませんでした"),
                reason = "polite negative past",
                out = out
            )

            form.endsWith("ました") -> addFromMasuStem(
                stem = form.removeSuffix("ました"),
                reason = "polite past",
                out = out
            )

            form.endsWith("ません") -> addFromMasuStem(
                stem = form.removeSuffix("ません"),
                reason = "polite negative",
                out = out
            )

            form.endsWith("ます") -> addFromMasuStem(
                stem = form.removeSuffix("ます"),
                reason = "polite non-past",
                out = out
            )
        }
    }

    private fun addFromMasuStem(stem: String, reason: String, out: MutableList<Step>) {
        if (stem.isBlank()) return

        // Ichidan stem + る
        addCandidate(stem + "る", "$reason (ichidan)", out)

        // Godan i-row stem -> dictionary form
        val converted = replaceLastChar(stem, iRowToU)
        if (converted != null) {
            addCandidate(converted, "$reason (godan)", out)
        }
    }

    private fun addPastForms(form: String, out: MutableList<Step>) {
        when {
            form.endsWith("った") -> {
                val stem = form.removeSuffix("った")
                addCandidates(stem, listOf('う', 'つ', 'る'), "past (godan)", out)
            }

            form.endsWith("いた") -> {
                val stem = form.removeSuffix("いた")
                addCandidate(stem + "く", "past (godan)", out)
            }

            form.endsWith("いだ") -> {
                val stem = form.removeSuffix("いだ")
                addCandidate(stem + "ぐ", "past (godan)", out)
            }

            form.endsWith("した") -> {
                val stem = form.removeSuffix("した")
                addCandidate(stem + "す", "past (godan)", out)
            }

            form.endsWith("んだ") -> {
                val stem = form.removeSuffix("んだ")
                addCandidates(stem, listOf('ぬ', 'ぶ', 'む'), "past (godan)", out)
            }

            form.endsWith("た") -> {
                val stem = form.removeSuffix("た")
                addCandidate(stem + "る", "past (ichidan)", out)
            }
        }
    }

    private fun addTeForms(form: String, out: MutableList<Step>) {
        when {
            form.endsWith("って") -> {
                val stem = form.removeSuffix("って")
                addCandidates(stem, listOf('う', 'つ', 'る'), "te-form (godan)", out)
            }

            form.endsWith("いて") -> {
                val stem = form.removeSuffix("いて")
                addCandidate(stem + "く", "te-form (godan)", out)
            }

            form.endsWith("いで") -> {
                val stem = form.removeSuffix("いで")
                addCandidate(stem + "ぐ", "te-form (godan)", out)
            }

            form.endsWith("して") -> {
                val stem = form.removeSuffix("して")
                addCandidate(stem + "す", "te-form (godan)", out)
            }

            form.endsWith("んで") -> {
                val stem = form.removeSuffix("んで")
                addCandidates(stem, listOf('ぬ', 'ぶ', 'む'), "te-form (godan)", out)
            }

            form.endsWith("て") -> {
                val stem = form.removeSuffix("て")
                addCandidate(stem + "る", "te-form (ichidan)", out)
            }
        }
    }

    private fun addNegativeForms(form: String, out: MutableList<Step>) {
        when {
            form.endsWith("なかった") -> {
                val stem = form.removeSuffix("なかった")
                addNegativeStemCandidates(stem, "negative past", out)
            }

            form.endsWith("ない") -> {
                val stem = form.removeSuffix("ない")
                addNegativeStemCandidates(stem, "negative", out)
            }
        }
    }

    private fun addNegativeStemCandidates(stem: String, reason: String, out: MutableList<Step>) {
        if (stem.isBlank()) return

        // Ichidan stem + る
        addCandidate(stem + "る", "$reason (ichidan)", out)

        // Godan a-row stem -> dictionary form
        val converted = replaceLastChar(stem, aRowToU)
        if (converted != null) {
            addCandidate(converted, "$reason (godan)", out)
        }
    }

    private fun addCausativePassiveForms(form: String, out: MutableList<Step>) {
        when {
            // Ichidan causative-passive
            form.endsWith("させられた") -> {
                val stem = form.removeSuffix("させられた")
                addCandidate(stem + "る", "causative-passive past (ichidan)", out)
            }

            form.endsWith("させられる") -> {
                val stem = form.removeSuffix("させられる")
                addCandidate(stem + "る", "causative-passive (ichidan)", out)
            }

            // Godan causative-passive
            form.endsWith("せられた") -> {
                val stem = form.removeSuffix("せられた")
                val converted = replaceLastChar(stem, aRowToU)
                if (converted != null) {
                    addCandidate(converted, "causative-passive past (godan)", out)
                }
            }

            form.endsWith("せられる") -> {
                val stem = form.removeSuffix("せられる")
                val converted = replaceLastChar(stem, aRowToU)
                if (converted != null) {
                    addCandidate(converted, "causative-passive (godan)", out)
                }
            }

            // Ichidan passive/potential
            form.endsWith("られた") -> {
                val stem = form.removeSuffix("られた")
                addCandidate(stem + "る", "passive/potential past (ichidan)", out)
            }

            form.endsWith("られる") -> {
                val stem = form.removeSuffix("られる")
                addCandidate(stem + "る", "passive/potential (ichidan)", out)
            }

            // Godan passive
            form.endsWith("れた") -> {
                val stem = form.removeSuffix("れた")
                val converted = replaceLastChar(stem, aRowToU)
                if (converted != null) {
                    addCandidate(converted, "passive past (godan)", out)
                }
            }

            form.endsWith("れる") -> {
                val stem = form.removeSuffix("れる")
                val converted = replaceLastChar(stem, aRowToU)
                if (converted != null) {
                    addCandidate(converted, "passive (godan)", out)
                }
            }

            // Ichidan causative
            form.endsWith("させた") -> {
                val stem = form.removeSuffix("させた")
                addCandidate(stem + "る", "causative past (ichidan)", out)
            }

            form.endsWith("させる") -> {
                val stem = form.removeSuffix("させる")
                addCandidate(stem + "る", "causative (ichidan)", out)
            }

            // Godan causative
            form.endsWith("せた") -> {
                val stem = form.removeSuffix("せた")
                val converted = replaceLastChar(stem, aRowToU)
                if (converted != null) {
                    addCandidate(converted, "causative past (godan)", out)
                }
            }

            form.endsWith("せる") -> {
                val stem = form.removeSuffix("せる")
                val converted = replaceLastChar(stem, aRowToU)
                if (converted != null) {
                    addCandidate(converted, "causative (godan)", out)
                }
            }
        }
    }

    private fun addIAdjectiveForms(form: String, out: MutableList<Step>) {
        when {
            form.endsWith("くなかった") -> {
                val stem = form.removeSuffix("くなかった")
                addCandidate(stem + "い", "i-adjective negative past", out)
            }

            form.endsWith("くない") -> {
                val stem = form.removeSuffix("くない")
                addCandidate(stem + "い", "i-adjective negative", out)
            }

            form.endsWith("かった") -> {
                val stem = form.removeSuffix("かった")
                addCandidate(stem + "い", "i-adjective past", out)
            }

            form.endsWith("くて") -> {
                val stem = form.removeSuffix("くて")
                addCandidate(stem + "い", "i-adjective conjunctive", out)
            }
        }
    }


    /**
     * て-form + auxiliary — the shape most verbs actually take in real text
     * (食べている, 走っていました, 書いてある, 持っていく, 食べてしまった).
     *
     * Only the auxiliary is stripped; what is left is still a て/で form, and
     * the search walks one more step to reach the dictionary form on its own.
     * The character before the auxiliary must be て or で, which is what stops
     * `る` from eating the last mora of every ichidan verb.
     */
    private fun addAuxiliaryChains(form: String, out: MutableList<Step>) {
        for (aux in TE_AUXILIARIES) {
            if (!form.endsWith(aux)) continue
            val head = form.dropLast(aux.length)
            val connector = head.lastOrNull() ?: continue
            if (connector != 'て' && connector != 'で') continue
            addCandidate(head, "te-form + auxiliary", out)
        }
    }

    /**
     * ～たい. The stem in front of it is the polite (masu) stem, so the same
     * ichidan / godan pair as ます: 食べたい → 食べる, 行きたい → 行く.
     * たかった and たくない reduce to たい through the i-adjective rules first.
     */
    private fun addDesiderativeForms(form: String, out: MutableList<Step>) {
        if (!form.endsWith("たい")) return
        addFromMasuStem(form.removeSuffix("たい"), "desiderative", out)
    }

    /**
     * ～ば. One map does both classes: 行けば → 行く through け→く, and
     * 食べれば → 食べる through れ→る.
     */
    private fun addConditionalForms(form: String, out: MutableList<Step>) {
        if (!form.endsWith("ば")) return
        val stem = form.removeSuffix("ば")
        replaceLastChar(stem, eRowToU)?.let { addCandidate(it, "conditional", out) }
        if (form.endsWith("なければ")) {
            addNegativeStemCandidates(form.removeSuffix("なければ"), "negative conditional", out)
        }
    }

    /** Spoken contractions of なければ: 食べなきゃ, 行かなくちゃ. */
    private fun addContractedNegatives(form: String, out: MutableList<Step>) {
        when {
            form.endsWith("なきゃ") ->
                addNegativeStemCandidates(form.removeSuffix("なきゃ"), "negative (spoken)", out)

            form.endsWith("なくちゃ") ->
                addNegativeStemCandidates(form.removeSuffix("なくちゃ"), "negative (spoken)", out)
        }
    }

    /** ～よう / ～おう: 食べよう → 食べる, 行こう → 行く. */
    private fun addVolitionalForms(form: String, out: MutableList<Step>) {
        when {
            form.endsWith("よう") ->
                addCandidate(form.removeSuffix("よう") + "る", "volitional (ichidan)", out)

            form.endsWith("う") -> {
                val stem = form.removeSuffix("う")
                replaceLastChar(stem, oRowToU)?.let {
                    addCandidate(it, "volitional (godan)", out)
                }
            }
        }
    }

    /**
     * Godan potential: 読める → 読む, 話せる → 話す.
     *
     * This one is deliberately noisy: 食べる is itself an え-row + る shape, so
     * every ichidan verb also yields a godan "dictionary form" (食ぶ) that is
     * usually not a word. A stray candidate costs one indexed lookup and
     * ranks below the real hit, whereas without the rule the potential form —
     * which is everywhere in ordinary Japanese — finds nothing at all.
     */
    private fun addPotentialForms(form: String, out: MutableList<Step>) {
        if (!form.endsWith("る") || form.length < 3) return
        val stem = form.removeSuffix("る")
        replaceLastChar(stem, eRowToU)?.let { addCandidate(it, "potential (godan)", out) }
    }

    /**
     * する verbs. 勉強した → 勉強する AND 勉強: dictionaries list some entries
     * only as the noun and some only as the suru-verb, so both are offered.
     * Without this, 勉強した deconjugated to 勉強す — a form that exists in no
     * dictionary, so the whole class of サ変 verbs found nothing.
     */
    private fun addSuruForms(form: String, out: MutableList<Step>) {
        val suffix = SURU_SUFFIXES.firstOrNull { form.endsWith(it) } ?: return
        val stem = form.dropLast(suffix.length)
        addCandidate(stem + "する", "suru verb", out)
        if (stem.isNotEmpty()) addCandidate(stem, "suru verb (noun)", out)
    }

    /**
     * 行く is the one godan verb whose て / た forms break the pattern: 行った
     * and 行って come from 行く, not from the 行う / 行つ / 行る the ~った rule
     * offers. Without this, the single most common motion verb in the language
     * could not be looked up in its past or te-form.
     */
    private fun addIkuForms(form: String, out: MutableList<Step>) {
        for ((suffix, base) in IKU_FORMS) {
            if (!form.endsWith(suffix)) continue
            addCandidate(form.dropLast(suffix.length) + base, "iku (irregular)", out)
        }
    }

    /** 来る is irregular in kana: きた / きて / こない all lead to くる. */
    private fun addKuruForms(form: String, out: MutableList<Step>) {
        val suffix = KURU_SUFFIXES.firstOrNull { form.endsWith(it) } ?: return
        val stem = form.dropLast(suffix.length)
        addCandidate(stem + "くる", "kuru verb", out)
        if (stem.isNotEmpty()) addCandidate(stem + "来る", "kuru verb (kanji)", out)
    }

    /**
     * な-adjectives and the copula: 静かだった / 静かじゃない / 元気でした all
     * describe the plain word in front of them. They used to fall through to
     * the godan past rules and produce 静かだう, 静かじゃる and similar.
     */
    private fun addCopulaForms(form: String, out: MutableList<Step>) {
        val suffix = COPULA_SUFFIXES.firstOrNull { form.endsWith(it) } ?: return
        addCandidate(form.dropLast(suffix.length), "copula / na-adjective", out)
    }

    private fun addCandidates(stem: String, endings: List<Char>, reason: String, out: MutableList<Step>) {
        if (stem.isBlank()) return
        endings.forEach { ending ->
            addCandidate(stem + ending, reason, out)
        }
    }

    private fun addCandidate(form: String, reason: String, out: MutableList<Step>) {
        val candidate = form.trim()
        if (candidate.length < 2) return
        if (candidate.isBlank()) return
        out += Step(candidate, reason)
    }

    private fun replaceLastChar(input: String, mapping: Map<Char, Char>): String? {
        if (input.isBlank()) return null
        val last = input.last()
        val mapped = mapping[last] ?: return null
        return input.dropLast(1) + mapped
    }
}
