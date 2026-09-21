package com.yomitanmobile.data.audio

/**
 * Word → recording, for a pack of native-speaker recordings whose word list
 * is known (see [KanjiAlive]) rather than read off file names.
 *
 * Built from the keys [AudioKeys] gives the user's own archive, with one
 * difference that follows from knowing the word: a word written with kanji is
 * found ONLY by its spelling AND reading. The user's archive has to match a
 * bare spelling (a file called 食べる.mp3 says nothing else) and a bare
 * reading; the pack knows both, and either alone would pick the wrong
 * recording. The spelling alone: Kanji alive recorded 足跡 as そくせき, and a
 * card for 足跡/あしあと was handed it. The reading alone: 橋, 箸 and 端 are
 * all はし with three different accents, and the accent is the reason to want
 * a native speaker at all. A word written in kana has nothing to confuse, and
 * is found by its kana.
 *
 * One addition, below all of those: a recording of a する-verb also answers,
 * last, for the noun it is built on. Kanji alive recorded 受験する, not 受験 —
 * 245 of one real collection's cards are like that — and the recording says
 * the noun with its own accent, followed by する. A native voice saying the
 * word plus する beats a synthesised one saying the word alone, but it must
 * never beat a recording of the word itself, so it sits under every priority
 * the exact words use.
 */
class NativeAudioIndex private constructor(
    /** key → (priority, file name); lower priority wins. */
    private val best: Map<String, Pair<Int, String>>
) {

    val size: Int get() = best.size

    /** The file that best says this word, or null. */
    fun find(expression: String, reading: String): String? =
        AudioKeys.lookupKeys(expression, reading)
            .mapNotNull { best[it] }
            .minByOrNull { it.first }
            ?.second

    /**
     * key, priority, file per line — what the pack keeps next to its files.
     * The columns are split on U+001F, not a tab: a pair key already HAS a
     * tab in it ([AudioKeys] joins spelling and reading with one).
     */
    fun serialize(): String = buildString {
        for ((key, value) in best) {
            append(key).append(COLUMN).append(value.first).append(COLUMN).append(value.second).append('\n')
        }
    }

    companion object {
        /** Added to every key a する-verb's recording gives its noun. */
        const val NOUN_OF_SURU_PENALTY = 3

        fun build(recordings: List<KanjiAlive.Recording>): NativeAudioIndex {
            val best = HashMap<String, Pair<Int, String>>(recordings.size * 4)
            fun offer(key: String, priority: Int, file: String) {
                val existing = best[key]
                // Ties go to the first recording listed: the word list's own
                // order, so the index is the same on every device.
                if (existing == null || priority < existing.first) best[key] = priority to file
            }
            // See the class doc: a kanji word by its spelling+reading pair
            // only, a kana word by its kana.
            fun keys(expression: String, reading: String) =
                AudioKeys.keysForWord(expression, reading)
                    .filter { (key, _) -> AudioKeys.isPairKey(key) || !AudioKeys.hasKanji(expression) }

            for (recording in recordings) {
                for ((key, priority) in keys(recording.expression, recording.reading)) {
                    offer(key, priority, recording.fileName)
                }
                val noun = recording.expression.removeSuffix(SURU)
                val nounReading = recording.reading.removeSuffix(SURU)
                val isSuruVerb = recording.expression.endsWith(SURU) && recording.reading.endsWith(SURU) &&
                    noun.isNotEmpty() && nounReading.isNotEmpty()
                if (isSuruVerb) {
                    for ((key, priority) in keys(noun, nounReading)) {
                        offer(key, priority + NOUN_OF_SURU_PENALTY, recording.fileName)
                    }
                }
            }
            return NativeAudioIndex(best)
        }

        fun parse(text: String): NativeAudioIndex {
            val best = HashMap<String, Pair<Int, String>>()
            for (line in text.lineSequence()) {
                val parts = line.split(COLUMN)
                if (parts.size != 3) continue
                val priority = parts[1].toIntOrNull() ?: continue
                best[parts[0]] = priority to parts[2]
            }
            return NativeAudioIndex(best)
        }

        private const val SURU = "する"
        private const val COLUMN = '\u001f'
    }
}
