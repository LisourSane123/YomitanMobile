package com.yomitanmobile.data.audio

import com.yomitanmobile.util.KanaScript
import java.io.File

/**
 * Everything the archive guesses about file naming.
 *
 * Kept apart from the SAF walk so it can be tested on the JVM: this is the
 * part that breaks when someone brings an archive shaped differently, and
 * names are the only thing worth asserting on.
 *
 * There is no standard for those names, so both halves of a path are read and
 * neither is assumed to hold a particular thing:
 *
 *   食べる.mp3          → expression
 *   食べる_たべる.mp3    → expression + reading
 *   たべる - 食べる.mp3  → the same, written the other way round
 *   食べる/たべる.mp3    → folder is the expression, file the reading
 *
 * Every Japanese piece becomes a key of its own and a pair becomes a third,
 * stronger key. A bare reading is indexed and matched too — unlike the
 * dictionary lookup, where きく must not answer for 聞く, a homophone's
 * recording IS the right pronunciation of both.
 */
object AudioKeys {

    const val PRIORITY_PAIR = 0
    const val PRIORITY_EXPRESSION = 1
    const val PRIORITY_READING = 2

    private val SEPARATORS = arrayOf("_", "-", "–", "—", "・", "、", ",")

    /** Keys for one file, with their priorities. */
    fun keysFor(fileName: String, parentName: String): List<Pair<String, Int>> {
        val base = fileName.substringBeforeLast('.')
        return keysForPieces(splitPieces(base) + splitPieces(parentName))
    }

    /**
     * Keys for a recording whose word is KNOWN rather than read off a file
     * name — a pack that ships a word list beside romanised file names.
     * Same keys, same priorities as a file called `expression_reading`.
     */
    fun keysForWord(expression: String, reading: String): List<Pair<String, Int>> =
        keysForPieces(listOf(expression, reading))

    private fun keysForPieces(raw: List<String>): List<Pair<String, Int>> {
        val pieces = raw
            .map { it.trim() }
            .filter { it.isNotEmpty() && isJapanese(it) }
            .distinct()
        if (pieces.isEmpty()) return emptyList()

        val keys = LinkedHashMap<String, Int>()
        // A pair — whichever way round it was written — is the strongest claim
        // the file makes, so both orderings are stored and the lookup asks for
        // the one it wants.
        if (pieces.size >= 2) {
            for (a in pieces) for (b in pieces) {
                if (a == b) continue
                keys.putIfAbsent(normalize(pairKey(a, b)), PRIORITY_PAIR)
            }
        }
        for (piece in pieces) {
            val key = normalize(piece)
            // A kana-only piece is a reading, anything carrying kanji is a
            // spelling — and a spelling identifies a word far better, so it
            // outranks the reading when both are on offer.
            val priority = if (hasKanji(piece)) PRIORITY_EXPRESSION else PRIORITY_READING
            val existing = keys[key]
            if (existing == null || priority < existing) keys[key] = priority
        }
        return keys.map { it.key to it.value }
    }

    /** The keys a lookup asks for. Priority in the table decides the winner. */
    fun lookupKeys(expression: String, reading: String): List<String> {
        val keys = LinkedHashSet<String>()
        val expressions = variants(expression)
        val readings = variants(reading)
        // A kana headword is its own reading, and pairing its two scripts
        // against each other would ask for a key meaning "食べる written as
        // 食べる" — noise that no archive files anything under.
        if (expression.trim() != reading.trim()) {
            for (e in expressions) for (r in readings) {
                if (e.isBlank() || r.isBlank() || e == r) continue
                keys += normalize(pairKey(e, r))
            }
        }
        expressions.filter { it.isNotBlank() }.forEach { keys += normalize(it) }
        readings.filter { it.isNotBlank() }.forEach { keys += normalize(it) }
        // Bounded well under SQLite's 999-parameter ceiling: variants() returns
        // at most three spellings per side.
        return keys.toList()
    }

    /** Both scripts of a kana string, so archive and dictionary can disagree. */
    private fun variants(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += trimmed
        if (trimmed.any { KanaScript.isKatakana(it) }) out += KanaScript.toHiragana(trimmed)
        if (trimmed.any { KanaScript.isHiragana(it) }) out += KanaScript.toKatakana(trimmed)
        return out.toList()
    }

    private fun splitPieces(value: String): List<String> =
        value.split(*SEPARATORS).flatMap { it.split(" - ") }

    private fun pairKey(first: String, second: String) = "$first\t$second"

    private fun normalize(value: String) = value.replace(" ", "").replace("　", "")

    private fun isJapanese(value: String) = value.any {
        KanaScript.isHiragana(it) || KanaScript.isKatakana(it) || isKanji(it)
    }

    fun hasKanji(value: String) = value.any { isKanji(it) }

    /** A spelling+reading key, as [keysFor] and [lookupKeys] build them. */
    fun isPairKey(key: String) = '\t' in key

    private fun isKanji(ch: Char) = ch in '一'..'鿿'
}
