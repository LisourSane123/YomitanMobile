package com.yomitanmobile.data.anki

import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Runs the kanji tally over a REAL AnkiDroid/Anki collection on a desktop JVM,
 * through the same indexer the phone's scan uses — so the numbers the screen
 * will show can be read before anything is built on them.
 *
 * Skipped unless pointed at a field dump (the same one `BookScanHarness`
 * takes):
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*KanjiTallyRealCollectionTest" \
 *   -Danki.fields=/path/fields.txt
 * ```
 *
 * One note per line, fields separated by U+001F. Dump it from a COPY of the
 * collection, never the live file:
 * `sqlite3 collection.anki2 "select replace(replace(flds, char(10), ' '), char(13), '') from notes" > fields.txt`
 */
class KanjiTallyRealCollectionTest {

    @Test
    fun tallyRealCollection() {
        val path = System.getProperty("anki.fields").orEmpty()
        Assume.assumeTrue("pass -Danki.fields=<dump>", path.isNotEmpty() && File(path).isFile)

        var notes = 0
        val words = HashSet<String>(1 shl 14)
        // How many of one note's stored spellings carry kanji. A note written
        // with furigana yields the expression, the reading AND the mixed
        // spellings in between (持[も]って 来[く]る → 持って来る, 持ってくる,
        // もってくる), so one note can put 持 into the word list twice. This is
        // what that costs, measured rather than guessed at.
        var notesWithKanjiSpellings = 0
        var kanjiSpellings = 0
        File(path).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            notes++
            val keys = AnkiNoteFieldIndexer.keysFromNote(line.replace('␟', '\u001f'))
            words += keys
            val withKanji = keys.count { key -> key.any { KanjiTally.isKanji(it) } }
            if (withKanji > 0) {
                notesWithKanjiSpellings++
                kanjiSpellings += withKanji
            }
        }

        val tally = KanjiTally.of(words)
        println("notes: $notes")
        println("words in scan: ${tally.wordCount}, of which ${tally.wordsWithKanji} carry kanji")
        println("distinct kanji: ${tally.distinctKanji}, occurrences: ${tally.totalOccurrences}")
        println(
            "kanji-bearing spellings per note: " +
                "%.2f".format(kanjiSpellings.toDouble() / notesWithKanjiSpellings.coerceAtLeast(1)) +
                " ($kanjiSpellings over $notesWithKanjiSpellings notes)"
        )
        println("--- top 40 by words")
        for (row in tally.counts.take(40)) {
            println("${row.kanji}\t${row.words}\t${row.occurrences}")
        }
        val once = tally.counts.count { it.words == 1 }
        println("--- $once characters sit in exactly one word")
    }
}
