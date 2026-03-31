import re

with open("app/src/main/java/com/yomitanmobile/data/parser/YomitanDictionaryParser.kt", "r") as f:
    text = f.read()

# Add KanjiEntry support
import_kanji = "import com.yomitanmobile.data.local.entity.KanjiEntry\n"
if "KanjiEntry" not in text:
    text = text.replace("import com.yomitanmobile.data.local.entity.DictionaryEntry", "import com.yomitanmobile.data.local.entity.DictionaryEntry\n" + import_kanji)

# Modify parseFromZipStreaming
old_signature = """suspend fun parseFromZipStreaming(
        inputStream: InputStream,
        onProgress: (ImportProgress) -> Unit = {},
        onBatch: suspend (List<DictionaryEntry>, String) -> Unit,
        onMetaBatch: suspend (Map<String, Int>, Map<String, String>) -> Unit = { _, _ -> }
    ): ParseResult"""

new_signature = """suspend fun parseFromZipStreaming(
        inputStream: InputStream,
        onProgress: (ImportProgress) -> Unit = {},
        onBatch: suspend (List<DictionaryEntry>, String) -> Unit,
        onMetaBatch: suspend (Map<String, Int>, Map<String, String>) -> Unit = { _, _ -> },
        onKanjiBatch: suspend (List<KanjiEntry>, String) -> Unit = { _, _ -> }
    ): ParseResult"""

text = text.replace(old_signature, new_signature)

# Add logic for kanji_bank handling
old_meta_bank = """                        name.contains("term_meta_bank_") && name.endsWith(".json") -> {"""

new_kanji_logic = """                        name.contains("kanji_bank_") && name.endsWith(".json") -> {
                            try {
                                val content = zip.bufferedReader().readText()
                                val jsonArray = json.decodeFromString<JsonArray>(content)
                                val KANJI_CHUNK_SIZE = 2000
                                val batch = mutableListOf<KanjiEntry>()

                                for (item in jsonArray) {
                                    try {
                                        val kanjiArr = item.jsonArray
                                        if (kanjiArr.size < 5) continue
                                        
                                        val character = safeString(kanjiArr[0])
                                        val onyomi = safeString(kanjiArr[1])
                                        val kunyomi = safeString(kanjiArr[2])
                                        val meaningsArr = parseDefinitions(kanjiArr[4])
                                        
                                        val encodedMeanings = json.encodeToString(
                                            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer()),
                                            meaningsArr
                                        )

                                        if (character.isNotBlank()) {
                                            batch.add(
                                                KanjiEntry(
                                                    kanji = character,
                                                    onyomi = onyomi,
                                                    kunyomi = kunyomi,
                                                    meanings = encodedMeanings,
                                                    dictionaryName = name // Use zip name temporarily or fix below
                                                )
                                            )
                                        }

                                        if (batch.size >= KANJI_CHUNK_SIZE) {
                                            onKanjiBatch(batch.toList(), name)
                                            batch.clear()
                                        }
                                    } catch (_: Exception) {}
                                }
                                if (batch.isNotEmpty()) {
                                    onKanjiBatch(batch, name)
                                }
                            } catch (_: Exception) {}
                        }
                        name.contains("term_meta_bank_") && name.endsWith(".json") -> {"""

text = text.replace(old_meta_bank, new_kanji_logic)

with open("app/src/main/java/com/yomitanmobile/data/parser/YomitanDictionaryParser.kt", "w") as f:
    f.write(text)
