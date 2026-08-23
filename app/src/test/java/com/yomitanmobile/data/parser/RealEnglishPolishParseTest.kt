package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.domain.model.ExamplePair
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The English→Polish path against REAL bytes from kty-en-pl, stored under
 * `test/resources/kty_en_pl_sample.json`.
 *
 * The claim this pins down is the one the whole English mode rests on: that
 * kaikki-converted dictionaries need no parser work of their own, because
 * they mark up glosses and examples exactly the way Jitendex does. That was
 * an observation about a file, not a guarantee, so it is checked here
 * against the file itself rather than a fixture written from the assumption.
 */
class RealEnglishPolishParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a real entry yields its Polish gloss and a translated example`() = runBlocking {
        val termBank = javaClass.classLoader!!
            .getResourceAsStream("kty_en_pl_sample.json")!!
            .readBytes().toString(Charsets.UTF_8)

        val zipBytes = createZip(
            mapOf(
                // The real index.json declares its language pair; this is what
                // stamps the rows 'en' regardless of the mode the app was in.
                "index.json" to """{"title":"kty-en-pl","format":"3","revision":"2025.04.08","sourceLanguage":"en","targetLanguage":"pl"}""",
                "term_bank_1.json" to termBank
            )
        )

        val entries = mutableListOf<DictionaryEntry>()
        val result = parser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            defaultLanguage = "ja", // deliberately wrong; the index must win
            onBatch = { batch, _ -> entries.addAll(batch) }
        )

        assertEquals("en", result.sourceLanguage)

        val dog = entries.single { it.expression == "dog" }

        // The gloss is Polish, and it is a gloss — not the raw structured
        // content, and not the example text fused onto it.
        val definitions = json.decodeFromString(ListSerializer(String.serializer()), dog.definition)
        assertTrue(
            "expected a Polish gloss, got $definitions",
            definitions.any { it.contains("pies") }
        )
        // The gloss is the gloss alone. kaikki puts the example list behind a
        // <details> whose <summary> is a localised count ("1 przykład"); it
        // used to be concatenated onto the meaning, so every card for a word
        // with examples read "pies1 przykład".
        assertTrue(
            "example-count summary leaked into the gloss: $definitions",
            definitions.none { it.contains("przykład") }
        )

        // The example survives WITH its translation: an English sentence and
        // the Polish rendering of it. This is what lands on the card front
        // and under the meaning.
        val examples = json.decodeFromString(
            ListSerializer(ExamplePair.serializer()),
            dog.examplesJson
        )
        assertTrue("no examples parsed out of the real entry", examples.isNotEmpty())
        val first = examples.first()
        assertTrue("English side missing: $first", first.jp.contains("dog"))
        assertTrue("Polish side missing: $first", first.en.contains("pies"))

        // Each example knows which meaning it illustrates. Without this the
        // card cannot put a sentence under its gloss, and a four-sense word
        // renders as four definitions followed by four loose sentences.
        assertTrue(
            "examples are not attached to a sense: ${examples.map { it.definitionIndex }}",
            examples.all { it.definitionIndex in definitions.indices }
        )
        // "It is said that a dog is a human's best friend" illustrates the
        // animal, which is the first sense.
        assertEquals(0, first.definitionIndex)
        // Different senses, different examples — not everything piled onto one.
        assertTrue(
            "every example landed on the same sense: ${examples.map { it.definitionIndex }}",
            examples.map { it.definitionIndex }.distinct().size > 1
        )
    }

    private fun parser() = YomitanDictionaryParser()

    private fun createZip(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
