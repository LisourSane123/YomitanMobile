package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.domain.model.ExamplePair
import com.yomitanmobile.domain.model.MergedWordEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end furigana check against a curated sample of REAL Jitendex data
 * (v4.3-4 Yomitan release, term banks 1/8/31), stored under
 * `test/resources/jitendex/real_sample_term_bank.json`.
 *
 * The synthetic fixtures in [YomitanExampleParsingTest] were written from
 * assumptions about the structured-content shape; this test feeds the parser
 * the actual bytes so a structural drift can't pass silently. It reports
 * coverage and asserts the invariants that make tappable furigana work.
 */
class RealJitendexFuriganaTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val examplesSerializer = ListSerializer(ExamplePair.serializer())

    @Test
    fun realExamplesProduceTappableFurigana() = runBlocking {
        val termBank = javaClass.classLoader!!
            .getResourceAsStream("jitendex/real_sample_term_bank.json")!!
            .readBytes().toString(Charsets.UTF_8)

        val zipBytes = createZip(
            mapOf(
                "index.json" to "{\"title\":\"Jitendex\",\"format\":\"3\",\"revision\":\"real\"}",
                "term_bank_1.json" to termBank
            )
        )

        val collected = mutableListOf<DictionaryEntry>()
        YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            onBatch = { batch, _ -> collected.addAll(batch) }
        )

        var entriesWithExamples = 0
        var totalExamples = 0
        var examplesWithSegments = 0
        var examplesWithRealReading = 0
        var concatMismatches = 0
        var dotsLeakedIntoJp = 0
        var dotsRevealedAsReading = 0
        val mismatchSamples = mutableListOf<String>()
        val dotReadingSamples = mutableListOf<String>()

        for (entry in collected) {
            val examples = decodeExamples(entry)
            if (examples.isNotEmpty()) entriesWithExamples++
            for (ex in examples) {
                totalExamples++
                if (ex.segments.isNotEmpty()) examplesWithSegments++

                // Invariant 1: segments must reproduce the plain jp exactly,
                // otherwise the rendered sentence is corrupted.
                val rebuilt = ex.segments.joinToString("") { it.text }
                if (ex.segments.isNotEmpty() && rebuilt != ex.jp) {
                    concatMismatches++
                    if (mismatchSamples.size < 5) mismatchSamples.add("jp=<${ex.jp}> rebuilt=<$rebuilt>")
                }

                // Invariant 2: the blanked-out keyword dots must never leak
                // into the plain sentence text.
                if (ex.jp.contains('●')) dotsLeakedIntoJp++

                // At least one kanji run should carry a real kana reading.
                val realReadings = ex.segments.filter {
                    it.reading.isNotBlank() && !it.reading.contains('●') && MergedWordEntry.containsKanji(it.text)
                }
                if (realReadings.isNotEmpty()) examplesWithRealReading++

                // How often the (revealable) reading is just the dot-mask — a
                // tap that shows "●●●" instead of a reading is a dead reveal.
                val dotReveals = ex.segments.filter { it.reading.contains('●') }
                if (dotReveals.isNotEmpty()) {
                    dotsRevealedAsReading++
                    if (dotReadingSamples.size < 5) {
                        dotReadingSamples.add("${ex.jp} -> " + dotReveals.joinToString { "${it.text}=${it.reading}" })
                    }
                }
            }
        }

        println("=== REAL JITENDEX FURIGANA REPORT ===")
        println("parsed entries:            ${collected.size}")
        println("entries with examples:     $entriesWithExamples")
        println("total examples:            $totalExamples")
        println("examples w/ segments:      $examplesWithSegments")
        println("examples w/ real reading:  $examplesWithRealReading")
        println("concat mismatches:         $concatMismatches  $mismatchSamples")
        println("dots leaked into jp:       $dotsLeakedIntoJp")
        println("examples revealing dots:   $dotsRevealedAsReading")
        dotReadingSamples.forEach { println("   dot-reveal: $it") }
        println("=====================================")

        assertTrue("no examples parsed from real data", totalExamples > 0)
        assertTrue("segments must reproduce jp (mismatches=$concatMismatches)", concatMismatches == 0)
        assertTrue("keyword dots leaked into plain jp ($dotsLeakedIntoJp)", dotsLeakedIntoJp == 0)
        assertTrue(
            "expected most examples to carry ruby segments, got $examplesWithSegments/$totalExamples",
            examplesWithSegments >= totalExamples * 0.9
        )
        assertTrue(
            "expected most examples to expose at least one real kana reading, got $examplesWithRealReading/$totalExamples",
            examplesWithRealReading >= totalExamples * 0.8
        )
    }

    private fun decodeExamples(entry: DictionaryEntry): List<ExamplePair> {
        if (entry.examplesJson.isBlank()) return emptyList()
        return json.decodeFromString(examplesSerializer, entry.examplesJson)
    }

    private fun createZip(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
