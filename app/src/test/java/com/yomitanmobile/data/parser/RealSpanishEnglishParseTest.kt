package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.entity.DictionaryEntry
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
 * The Spanish→English path against REAL bytes from kty-es-en.
 *
 * Spanish is shaped unlike the other two languages: of its 1.16M headwords,
 * the overwhelming majority are inflected forms whose whole definition is a
 * pointer back to the lemma. Both shapes have to survive the import — the
 * lemma with its English glosses, and the form-of entry with the label that
 * says WHICH form the user just looked up.
 */
class RealSpanishEnglishParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a lemma and one of its inflected forms both import`() = runBlocking {
        val termBank = javaClass.classLoader!!
            .getResourceAsStream("kty_es_en_sample.json")!!
            .readBytes().toString(Charsets.UTF_8)

        val zipBytes = createZip(
            mapOf(
                "index.json" to """{"title":"kty-es-en","format":"3","revision":"2025.04.08","sourceLanguage":"es","targetLanguage":"en"}""",
                "term_bank_1.json" to termBank
            )
        )

        val entries = mutableListOf<DictionaryEntry>()
        val result = YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(zipBytes),
            defaultLanguage = "ja", // deliberately wrong; the index must win
            onBatch = { batch, _ -> entries.addAll(batch) }
        )

        assertEquals("es", result.sourceLanguage)

        val lemma = definitionsOf(entries.single { it.expression == "hablar" })
        assertTrue(
            "expected an English gloss for the lemma, got $lemma",
            lemma.any { it.contains("speak", ignoreCase = true) || it.contains("talk", ignoreCase = true) }
        )

        // The form-of entry: without the fix in formatFormOfDefinition this
        // read simply "hablar", so a card gave no hint that the word looked up
        // was the gerund.
        val form = definitionsOf(entries.single { it.expression == "hablando" })
        assertTrue("base form missing from $form", form.any { it.contains("hablar") })
        assertTrue("form label missing from $form", form.any { it.contains("gerund") })
    }

    private fun definitionsOf(entry: DictionaryEntry): List<String> =
        json.decodeFromString(ListSerializer(String.serializer()), entry.definition)

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
