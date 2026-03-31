import re

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "r") as f:
    content = f.read()

# Replace formatMeaningForCard
old_format_meaning = """    private fun formatMeaningForCard(definitions: List<String>): String {
        val meaningLines = definitions.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_MEANINGS_ON_CARD)
            .toList()

        return meaningLines.joinToString("<br>") { InputSanitizer.escapeHtml(it) }
    }"""

new_format_meaning = """    private fun formatMeaningForCard(definitions: List<String>): String {
        val meaningLines = definitions.asSequence()
            .map { it.trim().replace(";", ", ") }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_MEANINGS_ON_CARD)
            .toList()

        return meaningLines.joinToString("<br><br>") { InputSanitizer.escapeHtml(it) }
    }"""

content = content.replace(old_format_meaning, new_format_meaning)

# Replace kanjiHtml building
old_kanji_html = """        val kanjiHtml = if (kanjiData.isNotEmpty()) {
            kanjiData.joinToString("") { kanji ->
                "<div class='kanji-item'><span class='kanji-char'>${kanji.kanji}</span>" +
                (if (kanji.onyomi.isNotEmpty()) " On: ${kanji.onyomi}" else "") +
                (if (kanji.kunyomi.isNotEmpty()) " Kun: ${kanji.kunyomi}" else "") +
                (if (kanji.meanings.isNotEmpty()) "<br>Znaczenie: " + kanji.meanings else "") +
                "</div>"
            }
        } else "" """

new_kanji_html = """        val kanjiHtml = if (kanjiData.isNotEmpty()) {
            kanjiData.sortedBy { entry.expression.indexOf(it.kanji).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
                .joinToString("") { kanji ->
                    val cleanMeanings = kanji.meanings
                        .removePrefix("[")
                        .removeSuffix("]")
                        .split(",")
                        .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                        
                    "<div class='kanji-item'><span class='kanji-char'>${kanji.kanji}</span>" +
                    (if (kanji.onyomi.isNotEmpty()) " On: ${kanji.onyomi}" else "") +
                    (if (kanji.kunyomi.isNotEmpty()) " Kun: ${kanji.kunyomi}" else "") +
                    (if (cleanMeanings.isNotEmpty()) "<br>Znaczenie: $cleanMeanings" else "") +
                    "</div>"
                }
        } else """"

content = content.replace(old_kanji_html, new_kanji_html)

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "w") as f:
    f.write(content)
