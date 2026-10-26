package com.yomitanmobile.data.parser

import com.yomitanmobile.data.local.entity.DictionaryEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pixiv's dictionaries put three things round an article's summary: the
 * category it sits in ("←寝る"), and a footer with the logo and a
 * "pixivで読む" link. They are marked under `data.pixiv`, which the parser did
 * not read, so every definition came out "←寝る; 熟睡していることをあらわす擬音。; pixivで読む".
 * The entry below is ぐっすり exactly as Pixiv Light [2025-12-11] ships it.
 */
class PixivDictionaryParseTest {

    @Test
    fun `only the summary of a Pixiv article is its definition`() = runBlocking {
        val gussuri = """["ぐっすり","ぐっすり","","",513,[{"type":"structured-content","content":[""" +
            """{"tag":"div","data":{"pixiv":"parent-link"},"style":{"fontWeight":"bold"},"content":{"tag":"a","content":"←寝る","href":"?query=寝る"}},""" +
            """{"tag":"ul","content":[{"tag":"li","content":"熟睡していることをあらわす擬音。"}],"data":{"pixiv":"summary"}},""" +
            """{"tag":"div","data":{"pixiv":"footer"},"content":[{"tag":"span","content":[{"tag":"img","path":"assets/pixiv-logo.png","alt":"pixiv"}," ",""" +
            """{"tag":"a","href":"https://dic.pixiv.net/a/ぐっすり","content":"pixivで読む"}],"data":{"pixiv":"read-more-link"}}]}]}],0,""]"""
        // 旺文社国語辞典 第十一版: a plain string, senses on lines of their own.
        val shitoshito = """["しとしと","しとしと","","",1,["しと‐しと\n（副）雨が静かに降るようす。「―降る春雨」"],62626,""]"""

        val all = parse("[$gussuri,$shitoshito]")

        assertEquals("""["熟睡していることをあらわす擬音。"]""", all.single { it.expression == "ぐっすり" }.definition)
        assertEquals(
            """["しと‐しと\n（副）雨が静かに降るようす。「―降る春雨」"]""",
            all.single { it.expression == "しとしと" }.definition
        )
    }

    private fun parse(bank: String): List<DictionaryEntry> = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write("""{"title":"Pixiv Light","format":3,"revision":"1"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("term_bank_1.json"))
            zip.write(bank.toByteArray())
            zip.closeEntry()
        }
        val all = mutableListOf<DictionaryEntry>()
        YomitanDictionaryParser().parseFromZipStreaming(
            inputStream = ByteArrayInputStream(out.toByteArray()),
            onBatch = { batch, _ -> all.addAll(batch) }
        )
        all
    }
}
