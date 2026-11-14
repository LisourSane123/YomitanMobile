package com.yomitanmobile.kindle

import com.yomitanmobile.data.anki.MonolingualChain
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.domain.model.WordEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The JP-JP chain on the real cleaned zips. Skipped unless MONO_DICTS names a
 * folder holding them (monolingual/dicts in this repository).
 */
class MonolingualDictionaryLiveTest {
    @Test
    fun realChain() = runBlocking {
        val dir = System.getenv("MONO_DICTS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val parser = YomitanDictionaryParser()
        val chain = Config.DEFAULT_MONOLINGUAL.map { MonolingualDictionary(parser, File(dir, it)) }.filter { it.zip.isFile }
        assumeTrue(chain.size == 2)
        val words = listOf("猫", "走る", "突き付ける", "突きつける", "どきどき", "ゆっくり", "しがみ付く", "食べる").map {
            WordEntry(expression = it, reading = "", definitions = listOf("EN"))
        }
        val t = System.currentTimeMillis()
        val out = MonolingualChain.apply(words, chain.map { it.title }, lookup = { title, e -> chain.first { it.title == title }.lookup(e) })
        println("titles: ${chain.map { it.title }} in ${System.currentTimeMillis() - t} ms")
        for (w in out) println("${w.expression}\t[${w.meaningSource.ifEmpty { "EN" }}]\t${w.definitions.joinToString(" | ").take(400).replace("\n", "⏎")}")
        val ono = File(dir!!.parentFile, "[Monolingual Onomatopoeia] surasura.zip")
        if (ono.isFile) println("surasura headwords: ${MonolingualDictionary(parser, ono).headwords().size}")
    }
}
