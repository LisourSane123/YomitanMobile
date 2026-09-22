package com.yomitanmobile.data.download

import com.yomitanmobile.domain.model.CefrLevel
import com.yomitanmobile.util.Csv
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A CEFR word list (CEFR-J, Octanove) as a Yomitan tag dictionary — the same
 * shape the JLPT tag dictionary has, a level in the `freq` channel:
 * `[word, "freq", {"frequency": {"value": -1, "displayValue": "A1"}}]`. The
 * parser reads it into `jlpt_tags`, where CefrLevel's convention (higher =
 * easier) lets one rollup serve both scales.
 */
object LevelListConverter {

    /**
     * Word → its EASIEST level. A headword can list spelling variants
     * ("adviser/advisor", "a.m./A.M./am/AM"), each a word of its own; a word
     * listed under several parts of speech ("light": adjective A1, verb B1)
     * is known from its first level, which is the one a learner meets.
     */
    fun cefrLevels(csv: String): Map<String, CefrLevel> {
        val rows = Csv.parse(csv.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyMap()
        val header = rows.first().map { it.trim() }
        val word = header.indexOf("headword")
        val level = header.indexOf("CEFR")
        require(word >= 0 && level >= 0) { "CEFR list without headword/CEFR columns: $header" }
        val out = LinkedHashMap<String, CefrLevel>()
        for (row in rows.drop(1)) {
            val cefr = CefrLevel.fromLabel(row.getOrNull(level).orEmpty()) ?: continue
            for (spelling in row.getOrNull(word).orEmpty().split('/')) {
                val w = spelling.trim()
                if (w.isEmpty()) continue
                val current = out[w]
                if (current == null || cefr.dbValue > current.dbValue) out[w] = cefr
            }
        }
        return out
    }

    fun toYomitanZip(
        title: String,
        revision: String,
        sourceLanguage: String,
        attribution: String,
        levels: Map<String, CefrLevel>
    ): ByteArray {
        // Also the capitalised spellings a dictionary writes; a spelling two
        // words claim keeps the easier level.
        val tags = LinkedHashMap<String, CefrLevel>()
        for ((word, level) in levels) {
            for (spelling in FrequencyListConverter.spellings(word)) {
                val current = tags[spelling]
                if (current == null || level.dbValue > current.dbValue) tags[spelling] = level
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write(
                buildJsonObject {
                    put("title", title)
                    put("revision", revision)
                    put("format", 3)
                    put("sourceLanguage", sourceLanguage)
                    put("attribution", attribution)
                }.toString().toByteArray()
            )
            zip.closeEntry()
            tags.entries.chunked(10_000).forEachIndexed { bank, chunk ->
                zip.putNextEntry(ZipEntry("term_meta_bank_${bank + 1}.json"))
                val json = JsonArray(chunk.map { (spelling, level) ->
                    JsonArray(
                        listOf(
                            JsonPrimitive(spelling),
                            JsonPrimitive("freq"),
                            buildJsonObject {
                                putJsonObject("frequency") {
                                    put("value", -1)
                                    put("displayValue", level.label)
                                }
                            }
                        )
                    )
                })
                zip.write(json.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
