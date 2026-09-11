package com.yomitanmobile.data.anki

/**
 * The spellings one word can legitimately have, derived from its reading.
 *
 * Japanese writes the same word several ways and decks disagree about which:
 * JMdict's headword is 持って来る, Core writes 持ってくる, a hand-made note may
 * have もってくる. A duplicate check comparing strings sees three different
 * words and re-creates a card the user has been studying for a year — the
 * compound verbs (～て来る, ～て行く, ～て見る, ～て仕舞う) are where this bites
 * hardest, because their auxiliary half is the part decks write in kana.
 *
 * Rather than keeping a list of those auxiliaries, the reading is used to do
 * it properly: the kana in the expression anchors the reading, which says what
 * every kanji block stands for, and each block can then be written either way.
 * 持って来る + もってくる → 持って来る, 持ってくる, もって来る, もってくる.
 *
 * Alignment is strict on purpose: anything it cannot line up exactly (an
 * irregular reading like 今日/きょう spread over two kanji, a katakana word, a
 * missing reading) yields no variants at all, so the caller falls back to the
 * plain string comparison it did before.
 */
internal object KanaSpellingVariants {

    /** 2^4 spellings covers every compound; beyond that it is not a headword. */
    private const val MAX_GROUPS = 4

    /**
     * All mixed kanji/kana spellings of [expression], including the expression
     * itself and the all-kana form. Empty when the reading cannot be aligned.
     */
    fun of(expression: String, reading: String): Set<String> {
        val expr = expression.filterNot { it.isWhitespace() }
        val read = toHiragana(reading.filterNot { it.isWhitespace() })
        if (expr.isEmpty() || read.isEmpty()) return emptySet()

        val segments = segment(expr)
        val kanjiCount = segments.count { it.isKanji }
        if (kanjiCount == 0 || kanjiCount > MAX_GROUPS) return emptySet()

        // Walk the reading alongside the expression: a kana segment must appear
        // verbatim, and whatever sits between two kana segments is the reading
        // of the kanji block that separates them.
        val readings = arrayOfNulls<String>(segments.size)
        var pos = 0
        segments.forEachIndexed { i, segment ->
            if (!segment.isKanji) {
                val kana = toHiragana(segment.text)
                val at = read.indexOf(kana, pos)
                // A kana segment the reading does not contain means the two
                // spellings are not the same word at all.
                if (at < 0) return emptySet()
                if (i == 0 && at != 0) return emptySet()
                if (i > 0) {
                    val previous = read.substring(pos, at)
                    if (previous.isEmpty()) return emptySet()
                    readings[i - 1] = previous
                }
                pos = at + kana.length
            } else if (i == segments.lastIndex) {
                val tail = read.substring(pos)
                if (tail.isEmpty()) return emptySet()
                readings[i] = tail
                pos = read.length
            }
        }
        if (pos != read.length) return emptySet()
        if (segments.indices.any { segments[it].isKanji && readings[it] == null }) return emptySet()

        val out = LinkedHashSet<String>(1 shl kanjiCount)
        val kanjiIndices = segments.indices.filter { segments[it].isKanji }
        for (mask in 0 until (1 shl kanjiCount)) {
            val builder = StringBuilder(expr.length + read.length)
            segments.forEachIndexed { i, segment ->
                if (!segment.isKanji) {
                    builder.append(segment.text)
                } else {
                    val bit = kanjiIndices.indexOf(i)
                    val useReading = (mask shr bit) and 1 == 1
                    builder.append(if (useReading) readings[i] else segment.text)
                }
            }
            out.add(builder.toString())
        }
        return out
    }

    private class Segment(val text: String, val isKanji: Boolean)

    private fun segment(value: String): List<Segment> {
        val out = ArrayList<Segment>(4)
        var start = 0
        while (start < value.length) {
            val kanji = AnkiNoteFieldIndexer.isKanji(value[start])
            var end = start + 1
            while (end < value.length && AnkiNoteFieldIndexer.isKanji(value[end]) == kanji) end++
            out.add(Segment(value.substring(start, end), kanji))
            start = end
        }
        return out
    }

    /** Katakana readings (JMdict writes some) compare equal to hiragana ones. */
    private fun toHiragana(value: String): String = buildString(value.length) {
        for (ch in value) {
            append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
        }
    }
}
