package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry

/**
 * "hablando" is not a word to learn — "hablar" is.
 *
 * Spanish needs no conjugation rules in this app, and that is a property of
 * the data rather than a shortcut: kty-es-en has 1.16M headwords of which only
 * a small fraction are lemmas, because Wiktionary files every inflected form
 * as an entry whose whole content is a pointer back to the base —
 * `["hablar", ["gerund"]]`, `["decir", ["third-person singular preterite
 * indicative"]]`, `["casa", ["plural"]]`. Irregulars included: dijo, fue,
 * tenía and durmiendo all name their lemma this way, which no suffix table
 * could do.
 *
 * The parser renders that shape as `hablar (gerund)` and tags the entry
 * `non-lemma` ([YomitanDictionaryParser.formatFormOfDefinition]), so both
 * halves of the fact survive into the database and this reads them back.
 *
 * It is deliberately strict about what counts:
 *
 *  * EVERY sense of the merged entry has to be a non-lemma one. "buenas" is
 *    both the plural of "bueno" and an interjection of its own ("¡buenas!"),
 *    and a word that exists in its own right must not be replaced by the
 *    word it is also a form of.
 *  * the base has to look like a headword — one or two words, no digits, and
 *    not the form itself.
 *
 * Where one form is a form of two different words — "casas" is the plural of
 * "casa" and the second person of "casar" — the first sense wins, because
 * nothing in the entry says which the text meant. That is the same assumption
 * the homophone rule makes for Japanese, and wrong in the same rare way.
 *
 * **A word that is also a form of another word is left alone**, and that is
 * the deliberate half of this rule. "puesto" is the participle of "poner" and
 * a market stall, "va" is how "ir" is said and a Mexican "okay", "haya" is the
 * subjunctive of "haber" and a beech tree — but "parte" is a core noun that
 * is also "he departs" (the deck's eighth card, 364 uses), and "nada" is a
 * top-hundred pronoun that is also "he swims". Following the pointer would
 * turn those into cards for "partir" and "nadar". Frequency cannot
 * settle it either, which was worth finding out: these lists count SURFACES,
 * so a common verb form outranks its own infinitive (puesto 122 against poner
 * 481) exactly as a real noun outranks the verb it collides with (parte 72),
 * and the two cases are indistinguishable by rank. So the deck keeps a card
 * for the form, with the other sense's gloss on the back — about fifteen per
 * two hundred in Don Quijote. A card with a real word on it beats silently
 * deleting "nada" and "parte" from the deck.
 *
 * English gets nothing from this: kty-en-pl writes its form-of senses as
 * Polish prose ("forma czasu przeszłego prostego czasownika to make"), which
 * is not machine-readable, so English inflections are handled by
 * [com.yomitanmobile.util.EnglishLemmatizer] instead.
 */
object FormOf {

    const val NON_LEMMA = "non-lemma"

    /** The lemma this entry is an inflected form of, or null. */
    fun baseOf(entry: MergedWordEntry): String? =
        baseOf(entry.partsOfSpeech, entry.definitions, entry.primaryExpression)

    /** The same for a single dictionary row, before results are merged. */
    fun baseOf(entry: WordEntry): String? =
        baseOf(listOf(entry.partsOfSpeech), entry.definitions, entry.expression)

    private fun baseOf(
        partsOfSpeech: List<String>,
        definitions: List<String>,
        expression: String
    ): String? {
        val base = when {
            isFormOf(partsOfSpeech) -> definitions.firstNotNullOfOrNull { baseOfDefinition(it) }
            isApocopic(partsOfSpeech) -> definitions.firstNotNullOfOrNull { apocopicBaseOf(it) }
            else -> null
        } ?: return null
        return base.takeIf { !it.equals(expression, ignoreCase = true) }
    }

    /** Whether every sense of this entry is an inflected form of something else. */
    fun isFormOf(entry: MergedWordEntry): Boolean = isFormOf(entry.partsOfSpeech)

    private fun isFormOf(partsOfSpeech: List<String>): Boolean {
        if (partsOfSpeech.isEmpty()) return false
        return partsOfSpeech.all { isNonLemma(it) }
    }

    private fun isNonLemma(tags: String): Boolean =
        tags.split(',', ';', ' ', '\t', '\n').any { it.trim().lowercase() == NON_LEMMA }

    /**
     * `(before the noun) Apocopic form of bueno ("good, fine")` → `bueno`.
     *
     * The other half of Spanish inflection, and the half Wiktionary writes as
     * English prose instead of as a pointer: gran, buen, primer, tercer,
     * ningún, algún, san, cien. They are counted as certain, like a pure
     * non-lemma entry, because nothing else is spelled that way — Don Quijote
     * used gran 721 times and ningún 311, and each was a card whose back read
     * "Apocopic form of grande".
     *
     * Gated on the `abbv` tag the dictionary puts on exactly these entries, so
     * an ordinary gloss that happens to mention a form is not read as one.
     */
    private fun apocopicBaseOf(definition: String): String? =
        APOCOPIC.find(definition)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isApocopic(partsOfSpeech: List<String>): Boolean = partsOfSpeech.any { tags ->
        tags.split(',', ';', ' ', '\t', '\n').any { it.trim().lowercase() == ABBREVIATION }
    }

    private val APOCOPIC = Regex("""[Aa]pocopic form of ([^\s,;.()"\u201c\u201d]+)""")

    private const val ABBREVIATION = "abbv"

    /** `hablar (gerund)` → `hablar`; anything else → null. */
    fun baseOfDefinition(definition: String): String? {
        val match = FORM_OF.matchEntire(definition.trim()) ?: return null
        val base = match.groupValues[1].trim()
        if (base.isEmpty() || base.any { it.isDigit() }) return null
        // A gloss, not a headword: "a kind of tree (Andes)" would otherwise
        // read as the word "a kind of tree".
        if (base.count { it == ' ' } > MAX_BASE_WORDS - 1) return null
        return base
    }

    private val FORM_OF = Regex("""([^()]+)\(([^()]+)\)""")

    /** "darse cuenta" is a headword; a sentence is not. */
    private const val MAX_BASE_WORDS = 3
}
