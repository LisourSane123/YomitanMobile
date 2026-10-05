package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.ScanToken
import com.yomitanmobile.domain.model.TextScanFilters
import com.yomitanmobile.domain.model.TextScanSkipReason
import com.yomitanmobile.util.EnglishLemmatizer
import com.yomitanmobile.util.JapaneseTokenizer

/**
 * The part of the text scan that depends on the language being read.
 *
 * [TextScanPlanner] owns the pipeline — the fixed rule order, the skip
 * counters, the study-order score, the "one word, one card" merge — and that
 * is exactly what must not be written twice: its contract is that the first
 * rule to reject a word owns the counter and `selected + skipped == distinct
 * words`, and a second copy of the chain for English would drift from it by
 * the first bug fix. What differs between languages is which rule says what,
 * so the rules move out here and the chain stays where it is.
 *
 * Every method has a default that does nothing, because most of them exist
 * for one language only: kana filters and segmentation noise are meaningless
 * for English, and English's capital letters are meaningless for Japanese.
 */
interface ScanRules {

    /** Grammatical scaffolding this reader necessarily already has. */
    fun isStoplisted(word: String, entry: MergedWordEntry?): Boolean

    /**
     * Not a word at all, whatever the dictionary says — and not something the
     * user can switch off. Runs before the dictionary lookup, so [entry] may
     * be null.
     */
    fun isNoise(word: String, entry: MergedWordEntry?): Boolean = false

    /** A piece of a word that the dictionary happens to also list. */
    fun isFragment(word: String, entry: MergedWordEntry): Boolean = false

    /** A script the user asked not to make cards from, or null. */
    fun scriptSkipReason(word: String, filters: TextScanFilters): TextScanSkipReason? = null

    /** A "word" that only exists because segmentation had to cut somewhere. */
    fun isSegmentationNoise(word: String, entry: MergedWordEntry): Boolean = false

    /** Grammar common enough that a card for it teaches nothing. */
    fun isEverydayGrammar(entry: MergedWordEntry, occurrences: Int): Boolean

    /** A word this text uses as somebody's name and that is nothing else. */
    fun isNameOnly(token: ScanToken, entry: MergedWordEntry): Boolean = false

    /** A word the reader has plus a grammatical particle (自分で, 今から). */
    fun isKnownBlend(entry: MergedWordEntry, isInAnki: (MergedWordEntry) -> Boolean): Boolean = false

    /** The collection holds the dictionary form this word is an inflection of. */
    fun isInflectionInAnki(
        word: String,
        entry: MergedWordEntry,
        isInAnki: (MergedWordEntry) -> Boolean
    ): Boolean = false

    /** Collapses inflections filed as headwords of their own onto their base. */
    fun mergeParadigms(
        words: List<ScanToken>,
        entries: Map<String, MergedWordEntry>
    ): Pair<List<ScanToken>, Map<String, MergedWordEntry>> = words to entries

    /**
     * Tie-break when two spellings of one word appeared equally often: higher
     * wins. Japanese prefers the form carrying kanji; a language with one
     * spelling per word has nothing to prefer.
     */
    fun spellingPreference(word: String): Int = 0

    companion object {
        fun forLanguage(language: AppLanguage): ScanRules = when (language) {
            AppLanguage.JAPANESE -> JapaneseScanRules
            else -> LatinScanRules(language)
        }
    }
}

/** Everything [TextScanPlanner] was doing before other languages existed. */
object JapaneseScanRules : ScanRules {

    override fun isStoplisted(word: String, entry: MergedWordEntry?) =
        TextScanPlanner.isStoplisted(word, entry)

    override fun isNoise(word: String, entry: MergedWordEntry?) =
        NoiseRules.isBlacklisted(word, entry) ||
            NoiseRules.isBareNumber(word) ||
            NoiseRules.isEmphaticNoise(word) { it in TextScanPlanner.FUNCTION_WORDS }

    override fun isFragment(word: String, entry: MergedWordEntry) =
        NoiseRules.isKanaFragment(word, entry)

    override fun scriptSkipReason(word: String, filters: TextScanFilters) = when {
        filters.skipPlainKana && NoiseRules.isPlainKana(word) -> TextScanSkipReason.KANA_ONLY
        filters.skipKatakana && NoiseRules.isKatakanaWord(word) -> TextScanSkipReason.KATAKANA_ONLY
        else -> null
    }

    override fun isSegmentationNoise(word: String, entry: MergedWordEntry) =
        TextScanPlanner.isSegmentationNoise(word, entry)

    override fun isEverydayGrammar(entry: MergedWordEntry, occurrences: Int) =
        TextScanPlanner.isEverydayGrammar(entry, occurrences)

    override fun isNameOnly(token: ScanToken, entry: MergedWordEntry) =
        TextScanPlanner.isNameOnly(token, entry)

    override fun isKnownBlend(entry: MergedWordEntry, isInAnki: (MergedWordEntry) -> Boolean) =
        TextScanPlanner.isKnownBlend(entry, isInAnki)

    override fun isInflectionInAnki(
        word: String,
        entry: MergedWordEntry,
        isInAnki: (MergedWordEntry) -> Boolean
    ) = TextScanPlanner.isInflectionInAnki(word, entry, isInAnki)

    override fun mergeParadigms(
        words: List<ScanToken>,
        entries: Map<String, MergedWordEntry>
    ) = TextScanPlanner.mergeByParadigm(words, entries)

    override fun spellingPreference(word: String) = word.count { JapaneseTokenizer.isKanji(it) }
}

/**
 * English and Spanish.
 *
 * Much shorter than the Japanese rules, and that is the point rather than an
 * omission: most of what [JapaneseScanRules] does is repair work for
 * longest-match segmentation — fragments, blends, pieces of a name, a kana
 * "word" cut out of a sentence — and a language that writes its spaces down
 * produces none of it.
 *
 * Four rules remain, and each of them earned its place on a real scan
 * (Pride and Prejudice through [com.yomitanmobile.util.LatinTokenizer], the
 * English-Polish Wiktionary and wordfreq, via `BookScanHarness`):
 *
 *  * the closed classes — articles, pronouns, prepositions, auxiliaries —
 *    which top every frequency list and would otherwise be the first hundred
 *    cards of any deck, exactly as です and ます were;
 *  * one word, one card: Wiktionary files "said", "made" and "went" as
 *    headwords, and sixteen of the first hundred cards were inflections of
 *    another card in the same hundred;
 *  * the capital letter, which is how these languages mark a name — Elizabeth
 *    was the deck's second card at 583 occurrences;
 *  * what is not a word at all: chapter numbers, figures, stray initials.
 *
 * The stoplist itself is taken from the closed word classes of a grammar
 * rather than grown card by card the way the Japanese one was. Reading that
 * scan's first hundred showed nothing left in it that a grammar would call
 * scaffolding, but a second book may well; the grammar counter on the scan
 * screen is where that would show up.
 */
class LatinScanRules(private val language: AppLanguage) : ScanRules {

    private val stopWords = when (language) {
        AppLanguage.SPANISH -> SPANISH_FUNCTION_WORDS
        else -> ENGLISH_FUNCTION_WORDS
    }

    override fun isStoplisted(word: String, entry: MergedWordEntry?): Boolean {
        if (word.lowercase() in stopWords) return true
        if (entry == null) return false
        if (entry.primaryExpression.lowercase() in stopWords) return true
        return entry.alternativeExpressions.any { it.lowercase() in stopWords }
    }

    /**
     * What a page of a book holds besides words: chapter numbers ("XVII"),
     * figures ("1920s"), a stray initial, and the sounds people make ("hmm",
     * "shh"). Each is checked against the frequency lists before it is
     * dropped, because a rule about SHAPE alone would take real words with it
     * — "did" and "mid" are made of Roman numerals, and "rhythm" has no vowel.
     */
    override fun isNoise(word: String, entry: MergedWordEntry?): Boolean {
        val w = word.lowercase()
        if (w.isEmpty()) return true
        if (w.any { it.isDigit() }) return true
        if (w.length == 1) return true
        val ranked = (entry?.frequency ?: 0) > 0
        if (ranked) return false
        if (w.all { it in ROMAN_NUMERALS }) return true
        return w.none { it in VOWELS }
    }

    /**
     * A name the text keeps capitalising mid-sentence, and that the corpus
     * does not know as an everyday word.
     *
     * The same shape as the Japanese honorific rule and for the same reason:
     * a novel's cast are dictionary words too, and Wiktionary lists them. The
     * capital is the signal, and only mid-sentence — every sentence opens with
     * one. What the capital alone cannot do is separate "Elizabeth" from
     * "Monday", since both are always capitalised; the frequency lists can,
     * because they are built from every kind of text and a character's name
     * is common only inside her own book.
     *
     * Both boundaries are measured, on Pride and Prejudice against wordfreq.
     *
     * HOW OFTEN: half the uses. A name is not capitalised every time it is
     * read — a novel opens sentences with its heroine's name constantly, and
     * those capitals say nothing — so Elizabeth scores 349 of 583 and
     * Charlotte 49 of 69, while the ordinary words that ever take a capital
     * stay far below (chapter 28 of 88, long 16 of 171, house 7 of 113).
     *
     * HOW RARE: rank 2 000. Elizabeth sits at 3 153, Charlotte at 4 922 and
     * Caroline at 6 995, while Monday (1 659), Saturday (1 430), Christmas
     * (1 289), French (841), England (775), English (644), London (535) and
     * God (233) are all inside it — words a learner wants. A name the lists
     * rank better than that stays a card, which is the right way round: it is
     * a word met outside this book too. The cost is visible and accepted:
     * "Mary" (1 723) keeps its card, and a title the book only ever uses
     * before a surname ("Colonel", 3 541) loses one.
     */
    override fun isNameOnly(token: ScanToken, entry: MergedWordEntry): Boolean {
        if (token.nameHits < NAME_CAPITAL_HITS) return false
        if (token.nameHits * 2 < token.occurrences) return false
        return entry.frequency <= 0 || entry.frequency > NAME_CAPITALISED_RANK
    }

    /**
     * One word, one card: "said" is not a word beside "say".
     *
     * Wiktionary lists every inflected form as a headword of its own — made,
     * went, thought, came, took, saw, given, days — so the tokeniser finds
     * them in the dictionary and stops there, exactly as JMdict's 近く stops
     * the Japanese one. Measured on Pride and Prejudice: sixteen of the first
     * hundred cards were inflections of another card in the same hundred.
     *
     * A form only collapses when the base is a word the SAME text used and
     * the dictionary knows: if a reader only ever meets "spoilt", the card
     * they get is "spoilt". That keeps the rule away from the ambiguous pairs
     * it cannot resolve — "saw" is a tool and "left" is a side — unless the
     * book also uses the verb, in which case one card for the pair is still
     * the better deck.
     */
    override fun mergeParadigms(
        words: List<ScanToken>,
        entries: Map<String, MergedWordEntry>
    ): Pair<List<ScanToken>, Map<String, MergedWordEntry>> {
        if (language != AppLanguage.ENGLISH) return mergeSpanishForms(words, entries)
        // The text's own spelling of each word, by lowercase key: "Miss" and
        // "miss" are one word, and a base is looked up without its capital.
        val present = HashMap<String, String>(words.size)
        for (token in words) {
            if (entries.containsKey(token.baseForm)) present.putIfAbsent(token.baseForm.lowercase(), token.baseForm)
        }
        val index = HashMap<String, String>()
        for (token in words) {
            val word = token.baseForm
            if (word !in entries) continue
            val irregular = EnglishLemmatizer.irregularBases(word).toSet()
            val base = EnglishLemmatizer.analyze(word.lowercase())
                .mapNotNull { candidate -> present[candidate]?.let { candidate to it } }
                .firstOrNull { (candidate, spelling) ->
                    candidate in irregular || plausibleBase(entries[spelling], entries[word])
                }
                ?.second
                ?: continue
            if (base == word) continue
            // No chains: a base that is itself filed as an inflection of
            // something else keeps this form where it is, rather than moving
            // it twice and losing track of the counts.
            index[word] = base
        }
        for ((word, base) in index.toList()) {
            if (base in index) index.remove(word)
        }
        return TextScanPlanner.mergeOnto(words, entries, index)
    }

    /**
     * The same job for Spanish, done by the dictionary instead of by rules.
     *
     * [ScanEntryResolver] has already replaced a conjugated form's entry with
     * its lemma's (hablando → hablar, dijo → decir), so a token whose entry is
     * headed by another word IS that word, whatever the text spelled it. All
     * that is left is to say so, which then makes the counts add up and puts
     * the lemma on the card front — a card fronted "hablando" teaches a form,
     * not a verb.
     *
     * No plausibility guard here and none needed: this is not a guess from
     * letters, it is what the entry says about itself.
     */
    private fun mergeSpanishForms(
        words: List<ScanToken>,
        entries: Map<String, MergedWordEntry>
    ): Pair<List<ScanToken>, Map<String, MergedWordEntry>> {
        val index = HashMap<String, String>()
        for (token in words) {
            val headword = entries[token.baseForm]?.primaryExpression ?: continue
            if (headword.isBlank() || headword.equals(token.baseForm, ignoreCase = true)) continue
            index[token.baseForm] = headword
        }
        return TextScanPlanner.mergeOnto(words, entries, index)
    }

    /**
     * Whether a base reached by a suffix rule is commonly enough used to BE
     * the base of this form; see [mergeParadigms].
     */
    private fun plausibleBase(base: MergedWordEntry?, form: MergedWordEntry?): Boolean {
        val baseRank = base?.frequency ?: 0
        val formRank = form?.frequency ?: 0
        if (baseRank <= 0 || formRank <= 0) return true
        return baseRank <= formRank * BASE_RANK_SLACK
    }

    /**
     * The collection holds the base form of this inflection — "make" is in
     * Anki, so "made" is not a card to create.
     */
    override fun isInflectionInAnki(
        word: String,
        entry: MergedWordEntry,
        isInAnki: (MergedWordEntry) -> Boolean
    ): Boolean {
        if (language != AppLanguage.ENGLISH) return false
        return EnglishLemmatizer.analyze(word.lowercase()).any { base ->
            isInAnki(
                entry.copy(
                    primaryExpression = base,
                    reading = base,
                    alternativeExpressions = emptyList()
                )
            )
        }
    }

    /**
     * The dictionary's own tags, when it has any. The bilingual dictionaries
     * these languages use are Wiktionary exports, which mostly do not tag
     * parts of speech the way JMdict does — so in practice the stoplist above
     * is what carries this rule, and the tag path is here for the entries
     * that do carry tags rather than as the main defence.
     */
    override fun isEverydayGrammar(entry: MergedWordEntry, occurrences: Int) =
        TextScanPlanner.isEverydayGrammar(entry, occurrences)

    private companion object {
        /** Capitalised mid-sentence this often, a word is being used as a name. */
        const val NAME_CAPITAL_HITS = 3

        /**
         * How much rarer than its own inflection a base may be and still be
         * believed. Three times over is generous for a real pair (great 135 /
         * greater ~1 600) and nowhere near enough for a suffix accident
         * (mother 568 / moth, five figures).
         */
        const val BASE_RANK_SLACK = 3

        /**
         * Rank inside which an always-capitalised word is a word rather than
         * a name; see [LatinScanRules.isNameOnly] for where the number is
         * measured.
         */
        const val NAME_CAPITALISED_RANK = 2_000
        const val ROMAN_NUMERALS = "ivxlcdm"
        const val VOWELS = "aeiouyáéíóúüàèìòùâêîôûäëïöüãõå"
    }
}

/**
 * The English closed classes: articles, pronouns, prepositions, conjunctions,
 * auxiliaries and the handful of adverbs and discourse words that behave like
 * them. Content words are NOT here — "way", "thing" and "time" are common
 * enough that the user's own "I know the commonest N words" setting is the
 * right instrument for them, not a list nobody can see.
 */
private val ENGLISH_FUNCTION_WORDS = setOf(
    // articles and determiners
    "a", "an", "the", "this", "that", "these", "those", "each", "every", "either",
    "neither", "some", "any", "no", "none", "all", "both", "few", "many", "much",
    "more", "most", "less", "least", "other", "others", "another", "such", "same",
    "own", "enough", "several",
    // pronouns
    "i", "me", "my", "mine", "myself", "you", "your", "yours", "yourself", "yourselves",
    "he", "him", "his", "himself", "she", "her", "hers", "herself", "it", "its", "itself",
    "we", "us", "our", "ours", "ourselves", "they", "them", "their", "theirs", "themselves",
    "who", "whom", "whose", "which", "what", "whatever", "whoever", "whichever",
    "someone", "somebody", "something", "anyone", "anybody", "anything", "everyone",
    "everybody", "everything", "nobody", "nothing", "one", "ones", "oneself",
    // be / have / do / modals
    "be", "am", "is", "are", "was", "were", "been", "being",
    "have", "has", "had", "having", "do", "does", "did", "doing", "done",
    "will", "would", "shall", "should", "can", "could", "may", "might", "must",
    "ought", "need", "dare", "used",
    // prepositions and particles
    "of", "to", "in", "on", "at", "by", "for", "with", "without", "within", "from",
    "into", "onto", "upon", "over", "under", "above", "below", "between", "among",
    "through", "throughout", "during", "before", "after", "since", "until", "till",
    "against", "about", "across", "along", "around", "behind", "beside", "besides",
    "beyond", "despite", "except", "inside", "outside", "near", "off", "out", "up",
    "down", "toward", "towards", "per", "via",
    // conjunctions and connectives
    "and", "or", "but", "nor", "so", "yet", "if", "unless", "because", "though",
    "although", "while", "whereas", "whether", "than", "then", "as", "when",
    "whenever", "where", "wherever", "why", "how", "however", "therefore", "thus",
    "hence", "moreover", "furthermore", "nevertheless", "otherwise", "instead",
    "meanwhile", "anyway", "besides",
    // degree, time and discourse adverbs that carry no vocabulary
    "not", "only", "just", "very", "too", "also", "even", "still", "already", "again",
    "ever", "never", "always", "often", "sometimes", "usually", "rarely", "seldom",
    "almost", "quite", "rather", "really", "perhaps", "maybe", "indeed", "here",
    "there", "now", "soon", "later", "ago", "once", "twice", "far", "away", "back",
    "well", "yes", "no", "ok", "okay", "oh", "ah", "eh", "hmm", "hey", "hi", "hello",
    "please", "thanks", "thank", "sir", "madam", "mr", "mrs", "ms", "dr",
    // the numbers a story counts with
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "first", "second", "third", "last", "next"
)

/**
 * The same closed classes in Spanish, with the inflected forms of the four
 * verbs that carry the grammar (ser, estar, haber, tener) — Spanish has no
 * lemmatiser in the app, so each form has to be named.
 */
private val SPANISH_FUNCTION_WORDS = setOf(
    // articles, determiners and contractions
    "el", "la", "los", "las", "lo", "un", "una", "unos", "unas", "del", "al",
    "este", "esta", "esto", "estos", "estas", "ese", "esa", "eso", "esos", "esas",
    "aquel", "aquella", "aquello", "aquellos", "aquellas", "cada", "todo", "toda",
    // The demonstratives written with the accent older texts use, and the
    // relative "cuanto": Don Quijote used éste 220 times, cuanto 349, mí 502
    // and vos 212, and every one of them was a card.
    "éste", "ésta", "éstos", "éstas", "ése", "ésa", "ésos", "ésas", "aquél", "aquélla",
    "cuanto", "cuanta", "cuantos", "cuantas", "cuánto", "cuánta", "cuántos", "cuántas",
    "todos", "todas", "otro", "otra", "otros", "otras", "mismo", "misma", "mismos",
    "mismas", "mucho", "mucha", "muchos", "muchas", "poco", "poca", "pocos", "pocas",
    "tanto", "tanta", "tantos", "tantas", "alguno", "alguna", "algunos", "algunas",
    "ninguno", "ninguna", "cualquier", "cualquiera", "demás", "varios", "varias",
    // pronouns
    "yo", "me", "mi", "mí", "mis", "mío", "mía", "conmigo", "tú", "te", "ti", "tu", "tus", "tuyo",
    "tuya", "contigo", "usted", "ustedes", "vos", "él", "ella", "ello", "le", "les", "se",
    "su", "sus", "suyo", "suya", "consigo", "nosotros", "nosotras", "nos", "nuestro",
    "nuestra", "nuestros", "nuestras", "vosotros", "vosotras", "os", "vuestro",
    "vuestra", "ellos", "ellas", "quien", "quién", "quienes", "que", "qué", "cual",
    "cuál", "cuales", "cuáles", "cuyo", "cuya", "algo", "alguien", "nada", "nadie",
    // ser / estar / haber / tener
    "ser", "soy", "eres", "es", "somos", "sois", "son", "era", "eras", "éramos",
    "eran", "fui", "fue", "fuimos", "fueron", "seré", "será", "serán", "sería",
    "sea", "seas", "sean", "siendo", "sido",
    "estar", "estoy", "estás", "está", "estamos", "están", "estaba", "estabas",
    "estaban", "estuve", "estuvo", "estuvieron", "esté", "estén", "estando", "estado",
    "haber", "he", "has", "ha", "hemos", "habéis", "han", "había", "habías", "habían",
    "hubo", "hubiera", "habrá", "habría", "hay", "habiendo", "habido",
    "tener", "tengo", "tienes", "tiene", "tenemos", "tienen", "tenía", "tenían",
    "tuve", "tuvo", "tuvieron", "tendrá", "tendría", "tenga", "tengan", "teniendo",
    // prepositions and conjunctions
    "a", "ante", "bajo", "con", "contra", "de", "desde", "durante", "en", "entre",
    "hacia", "hasta", "mediante", "para", "por", "según", "sin", "sobre", "tras",
    "y", "e", "o", "u", "ni", "pero", "sino", "aunque", "porque", "pues", "si",
    "como", "cuando", "cuándo", "donde", "dónde", "mientras", "aunque", "así",
    "entonces", "además", "también", "tampoco", "sin embargo", "luego",
    // degree, time and discourse adverbs
    "no", "sí", "ya", "muy", "más", "menos", "solo", "sólo", "casi", "aún", "aun",
    "todavía", "siempre", "nunca", "jamás", "ahora", "antes", "después", "hoy",
    "ayer", "mañana", "aquí", "ahí", "allí", "allá", "acá", "bien", "mal", "tan",
    "aparte", "quizá", "quizás", "aunque", "claro", "vale", "hola", "gracias",
    "señor", "señora", "don", "doña",
    // the numbers a story counts with
    "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
    "primero", "primera", "segundo", "segunda", "último", "última"
)
