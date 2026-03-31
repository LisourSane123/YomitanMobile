import re

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "r") as f:
    text = f.read()

# Update exportToAnki parameters
old_export = "suspend fun exportToAnki(entry: WordEntry, tts: TextToSpeech?, deckName: String = DEFAULT_DECK_NAME, stylePrefs: CardStylePreferences? = null): Result<Long> {"
new_export = "suspend fun exportToAnki(entry: WordEntry, tts: TextToSpeech?, deckName: String = DEFAULT_DECK_NAME, stylePrefs: CardStylePreferences? = null, kanjiData: List<com.yomitanmobile.data.local.entity.KanjiEntry> = emptyList()): Result<Long> {"
text = text.replace(old_export, new_export)

# Update createAnkiCard formatting
old_create = "            val card = createAnkiCard(entry, audioFileName)"
new_create = """            val kanjiHtml = if (kanjiData.isNotEmpty()) {
                kanjiData.joinToString("") { kanji ->
                    "<div class='kanji-item'><span class='kanji-char'>${kanji.kanji}</span>" +
                    (if (kanji.onyomi.isNotEmpty()) " On: ${kanji.onyomi}" else "") +
                    (if (kanji.kunyomi.isNotEmpty()) " Kun: ${kanji.kunyomi}" else "") +
                    (if (kanji.meanings.isNotEmpty()) "<br>Znaczenie: ${kanji.meanings.joinToString(", ")}" else "") +
                    "</div>"
                }
            } else ""
            val card = createAnkiCard(entry, audioFileName).copy(kanjiBreakdown = kanjiHtml)"""
text = text.replace(old_create, new_create)

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "w") as f:
    f.write(text)
