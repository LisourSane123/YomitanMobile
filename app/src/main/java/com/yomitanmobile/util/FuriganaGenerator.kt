package com.yomitanmobile.util

import com.yomitanmobile.domain.model.FuriganaSegment
import com.yomitanmobile.domain.model.MergedWordEntry

/**
 * Builds tappable furigana segments for an arbitrary Japanese sentence that did
 * not ship ruby annotations from the dictionary (plain-JMDict examples, seeded
 * [com.yomitanmobile.data.local.entity.Sentence] rows, and the ~14% of
 * Jitendex example sentences that carry no `<ruby>` at all).
 *
 * The reading-distribution core is a faithful port of Yomitan's
 * `distributeFurigana` / `distributeFuriganaInflected`
 * (`ext/js/language/ja/japanese.js`): a recursive backtracking segmentiser that
 * spreads a kana reading across the kanji/kana groups of a term, plus an
 * inflected variant that shares the dictionary stem with the surface form and
 * carries the conjugated okurigana tail as plain kana. That inflected path is
 * what lets a conjugated verb/adjective in a sentence (食べた, 行きました, 高くて…)
 * still reveal furigana — the previous greedy-only matcher could annotate
 * dictionary-form words exclusively, which is why most kanji in real sentences
 * stayed untappable.
 *
 * The sentence is tokenised by greedy longest-match against a supplied
 * expression→reading map (built from the installed dictionary); each candidate
 * substring is tried directly and, failing that, deconjugated via
 * [JapaneseDeconjugator] so its dictionary form can be looked up. Words absent
 * from the map fall through as plain, reading-less text.
 *
 * Pure and dictionary-agnostic: the DB lookup lives in the repository, this
 * object only needs the resolved readings so it stays unit-testable.
 */
object FuriganaGenerator {

    /** Upper bound on the length of a dictionary word (surface form) we try to match. */
    const val MAX_WORD_LEN = 15

    /**
     * Every expression worth looking up in the dictionary before calling
     * [generate]: each kanji-containing substring (length 1..[maxWordLen]) of
     * [sentence] plus, for the inflected path, the deconjugated dictionary
     * forms of those substrings. Kana-only runs need no furigana, so they are
     * skipped.
     */
    fun candidateExpressions(sentence: String, maxWordLen: Int = MAX_WORD_LEN): Set<String> {
        val out = HashSet<String>()
        for (start in sentence.indices) {
            if (!MergedWordEntry.isKanji(sentence[start])) continue
            val maxEnd = minOf(sentence.length, start + maxWordLen)
            for (end in (start + 1)..maxEnd) {
                val sub = sentence.substring(start, end)
                out.add(sub)
                // A conjugated surface (食べた) is never a headword, so also
                // offer its dictionary forms (食べる) for the reading lookup.
                // Only okurigana-bearing forms inflect, so restrict the
                // (relatively costly) deconjugation to substrings ending in
                // kana — a pure-kanji compound like 果物 never conjugates.
                if (isKana(sub.last())) {
                    for (base in JapaneseDeconjugator.candidateForms(sub)) {
                        if (MergedWordEntry.containsKanji(base)) out.add(base)
                    }
                }
            }
        }
        return out
    }

    /**
     * Split [sentence] into furigana segments using [readings] (expression →
     * kana reading). Each kanji-anchored run is matched greedily longest-first,
     * directly and then via deconjugation. Adjacent reading-less runs are
     * coalesced. Concatenating every segment's `text` reproduces [sentence].
     */
    fun generate(
        sentence: String,
        readings: Map<String, String>,
        maxWordLen: Int = MAX_WORD_LEN
    ): List<FuriganaSegment> {
        val out = mutableListOf<FuriganaSegment>()
        var i = 0
        while (i < sentence.length) {
            var matched = false
            if (MergedWordEntry.isKanji(sentence[i])) {
                val maxLen = minOf(maxWordLen, sentence.length - i)
                var len = maxLen
                while (len >= 1 && !matched) {
                    val sub = sentence.substring(i, i + len)
                    if (MergedWordEntry.containsKanji(sub)) {
                        val segs = segmentsFor(sub, readings)
                        if (segs != null) {
                            segs.forEach { appendSegment(out, it) }
                            i += len
                            matched = true
                        }
                    }
                    len--
                }
            }
            if (!matched) {
                appendSegment(out, FuriganaSegment(sentence[i].toString(), ""))
                i++
            }
        }
        return out
    }

    /**
     * Furigana for a single surface form [surface], or null if no reading is
     * known. Tries the surface as a headword first (direct distribution), then
     * each deconjugated dictionary form (inflected distribution over [surface]).
     */
    private fun segmentsFor(surface: String, readings: Map<String, String>): List<FuriganaSegment>? {
        readings[surface]?.let { return distributeFurigana(surface, it) }
        if (isKana(surface.last())) {
            for (base in JapaneseDeconjugator.candidateForms(surface)) {
                val baseReading = readings[base] ?: continue
                return distributeFuriganaInflected(base, baseReading, surface)
            }
        }
        return null
    }

    // ---- Yomitan furigana distribution (port of japanese.js) ----

    private data class Group(val isKana: Boolean, val text: String, val textNormalized: String?)

    /**
     * Distribute [reading] across the kanji/kana groups of [term]. Port of
     * Yomitan's `distributeFurigana`: kana groups anchor the alignment and
     * non-kana (kanji/latin/digit) groups absorb the reading between anchors.
     * Ambiguous or unalignable readings collapse to a single (term → reading)
     * segment so the reading is still revealable.
     */
    fun distributeFurigana(term: String, reading: String): List<FuriganaSegment> {
        if (reading == term) return listOf(FuriganaSegment(term, ""))

        val groups = mutableListOf<Group>()
        var isKanaPre: Boolean? = null
        for (c in term) {
            val isKana = isKana(c)
            if (isKana == isKanaPre) {
                val last = groups.removeAt(groups.lastIndex)
                groups.add(last.copy(text = last.text + c))
            } else {
                groups.add(Group(isKana, c.toString(), null))
                isKanaPre = isKana
            }
        }
        val normGroups = groups.map {
            if (it.isKana) it.copy(textNormalized = toHiragana(it.text)) else it
        }
        val readingNormalized = toHiragana(reading)
        return segmentizeFurigana(reading, readingNormalized, normGroups, 0)
            ?: listOf(FuriganaSegment(term, reading))
    }

    private fun segmentizeFurigana(
        reading: String,
        readingNormalized: String,
        groups: List<Group>,
        groupsStart: Int
    ): List<FuriganaSegment>? {
        val groupCount = groups.size - groupsStart
        if (groupCount <= 0) {
            return if (reading.isEmpty()) mutableListOf() else null
        }
        val group = groups[groupsStart]
        val textLength = group.text.length
        if (group.isKana) {
            val tn = group.textNormalized
            if (tn != null && readingNormalized.startsWith(tn)) {
                val segments = segmentizeFurigana(
                    reading.substring(textLength),
                    readingNormalized.substring(textLength),
                    groups,
                    groupsStart + 1
                )
                if (segments != null) {
                    val out = segments.toMutableList()
                    if (reading.startsWith(group.text)) {
                        out.add(0, FuriganaSegment(group.text, ""))
                    } else {
                        out.addAll(0, getFuriganaKanaSegments(group.text, reading))
                    }
                    return out
                }
            }
            return null
        } else {
            var result: List<FuriganaSegment>? = null
            var i = reading.length
            while (i >= textLength) {
                val segments = segmentizeFurigana(
                    reading.substring(i),
                    readingNormalized.substring(i),
                    groups,
                    groupsStart + 1
                )
                if (segments != null) {
                    if (result != null) return null // ambiguous — bail to whole-word
                    val out = segments.toMutableList()
                    out.add(0, FuriganaSegment(group.text, reading.substring(0, i)))
                    result = out
                }
                if (groupCount == 1) break
                i--
            }
            return result
        }
    }

    private fun getFuriganaKanaSegments(text: String, reading: String): List<FuriganaSegment> {
        val out = mutableListOf<FuriganaSegment>()
        var start = 0
        var state = reading[0] == text[0]
        for (i in 1 until text.length) {
            val newState = reading[i] == text[i]
            if (state == newState) continue
            out.add(FuriganaSegment(text.substring(start, i), if (state) "" else reading.substring(start, i)))
            state = newState
            start = i
        }
        out.add(FuriganaSegment(text.substring(start), if (state) "" else reading.substring(start)))
        return out
    }

    /**
     * Furigana for an inflected [source] whose dictionary form is [term] with
     * kana [reading]. Port of Yomitan's `distributeFuriganaInflected`: the stem
     * shared between term and source keeps its distributed reading; the
     * conjugated tail is appended as plain kana.
     */
    fun distributeFuriganaInflected(term: String, reading: String, source: String): List<FuriganaSegment> {
        val termNormalized = toHiragana(term)
        val readingNormalized = toHiragana(reading)
        val sourceNormalized = toHiragana(source)

        var mainText = term
        var reading2 = reading
        var stemLength = getStemLength(termNormalized, sourceNormalized)

        // The source may be derived from the reading rather than the term.
        val readingStemLength = getStemLength(readingNormalized, sourceNormalized)
        if (readingStemLength > 0 && readingStemLength >= stemLength) {
            mainText = reading
            stemLength = readingStemLength
            reading2 = source.substring(0, stemLength) + reading.substring(stemLength)
        }

        val segments = mutableListOf<FuriganaSegment>()
        if (stemLength > 0) {
            mainText = source.substring(0, stemLength) + mainText.substring(stemLength)
            val segments2 = distributeFurigana(mainText, reading2)
            var consumed = 0
            for (segment in segments2) {
                val start = consumed
                consumed += segment.text.length
                if (consumed < stemLength) {
                    segments.add(segment)
                } else if (consumed == stemLength) {
                    segments.add(segment)
                    break
                } else {
                    if (start < stemLength) {
                        segments.add(FuriganaSegment(mainText.substring(start, stemLength), ""))
                    }
                    break
                }
            }
        }

        if (stemLength < source.length) {
            val remainder = source.substring(stemLength)
            val last = segments.lastOrNull()
            if (last != null && last.reading.isEmpty()) {
                segments[segments.lastIndex] = last.copy(text = last.text + remainder)
            } else {
                segments.add(FuriganaSegment(remainder, ""))
            }
        }
        return segments
    }

    private fun getStemLength(t1: String, t2: String): Int {
        val minLength = minOf(t1.length, t2.length)
        if (minLength == 0) return 0
        var i = 0
        while (true) {
            if (i >= t1.length || i >= t2.length) break
            val c1 = t1.codePointAt(i)
            val c2 = t2.codePointAt(i)
            if (c1 != c2) break
            val charLength = Character.charCount(c1)
            i += charLength
            if (i >= minLength) {
                if (i > minLength) i -= charLength
                break
            }
        }
        return i
    }

    private fun appendSegment(out: MutableList<FuriganaSegment>, seg: FuriganaSegment) {
        if (seg.text.isEmpty()) return
        if (seg.reading.isBlank()) {
            val last = out.lastOrNull()
            if (last != null && last.reading.isEmpty()) {
                out[out.lastIndex] = last.copy(text = last.text + seg.text)
                return
            }
            out.add(FuriganaSegment(seg.text, ""))
        } else {
            out.add(seg)
        }
    }

    private fun isKana(c: Char): Boolean {
        val code = c.code
        // Hiragana block + katakana block (which already includes the ー mark).
        return code in 0x3040..0x309F || code in 0x30A0..0x30FF
    }

    /** Katakana → hiragana so surface kana can be matched against readings. */
    private fun toHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val code = c.code
            // Katakana block U+30A1..U+30F6 maps onto hiragana by −0x60. The
            // prolonged-sound mark ー (U+30FC) and iteration marks are shared,
            // so they pass through unchanged.
            sb.append(if (code in 0x30A1..0x30F6) (code - 0x60).toChar() else c)
        }
        return sb.toString()
    }
}
