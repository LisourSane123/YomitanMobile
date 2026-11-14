package com.yomitanmobile.data.anki

import com.yomitanmobile.util.InputSanitizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * One grammar point as a grammar dictionary explains it. Bunpro's entries
 * have all three parts; a dictionary that ships plain text (DOJG) has only
 * [explanation].
 */
data class GrammarPoint(
    val headword: String,
    val meaning: String,
    val explanation: String,
    /** Japanese sentence → its translation ("" when the dictionary gives none). */
    val examples: List<Pair<String, String>>,
    /** "N3" and the like, "" when the dictionary does not say. */
    val level: String
)

/**
 * A grammar dictionary in Yomitan's format (Bunpro, DOJG), read whole: they
 * are a thousand entries, and what matters is their list of headwords.
 *
 * Not read through [com.yomitanmobile.data.parser.YomitanDictionaryParser]:
 * that flattens an entry into gloss strings, and a grammar card needs the
 * entry's PARTS — Bunpro marks them `【 Meaning 】`, `【 Explanation 】`,
 * `【 Examples 】`, each followed by its block — to lay them out and to drop
 * the "Link to Bunpro" footer.
 *
 * Bunpro's reading column repeats the headword (既に has no すでに), so a
 * caller matching kana text has to come through the main dictionary's
 * spellings.
 */
class GrammarDictionary(val title: String, private val points: Map<String, List<GrammarPoint>>) {

    val size: Int get() = points.values.sumOf { it.size }

    /** Every point filed under the first of [keys] this dictionary lists; empty when it lists none. */
    fun find(keys: List<String>): List<GrammarPoint> =
        keys.firstNotNullOfOrNull { points[it.trim()]?.takeIf { found -> found.isNotEmpty() } }.orEmpty()

    companion object {
        fun read(zip: File): GrammarDictionary = ZipFile(zip).use { z ->
            val title = z.getEntry("index.json")
                ?.let { JSONObject(z.getInputStream(it).reader().readText()).optString("title") }
                ?.takeIf { it.isNotBlank() } ?: zip.nameWithoutExtension
            val points = LinkedHashMap<String, MutableList<GrammarPoint>>()
            for (entry in z.entries().asSequence().filter { it.name.substringAfterLast('/').startsWith("term_bank_") }.sortedBy { it.name }) {
                for (point in parseBank(z.getInputStream(entry).reader().readText())) {
                    points.getOrPut(point.headword) { ArrayList(1) } += point
                }
            }
            GrammarDictionary(title, points)
        }

        /** The rows of one term bank. */
        fun parseBank(json: String): List<GrammarPoint> {
            val rows = JSONArray(json)
            return (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONArray(i) ?: return@mapNotNull null
                val headword = row.optString(0).trim()
                val glossary = row.optJSONArray(5) ?: return@mapNotNull null
                if (headword.isEmpty()) return@mapNotNull null
                val parts = Parts()
                for (g in 0 until glossary.length()) {
                    when (val item = glossary.opt(g)) {
                        is String -> parts.explanation += item
                        is JSONObject -> if (item.optString("type") == "structured-content") readStructured(item.opt("content"), parts)
                    }
                }
                GrammarPoint(
                    headword = headword,
                    meaning = parts.meaning.joinToString("\n").trim(),
                    explanation = parts.explanation.joinToString("\n").trim(),
                    examples = parts.examples,
                    level = LEVEL.find(row.optString(7))?.value.orEmpty()
                ).takeIf { it.meaning.isNotEmpty() || it.explanation.isNotEmpty() }
            }
        }

        private class Parts {
            val meaning = ArrayList<String>()
            val explanation = ArrayList<String>()
            val examples = ArrayList<Pair<String, String>>()
        }

        /**
         * Bunpro's block: a `【 … 】` marker string, then the element it
         * labels. Text under an unknown marker, or under none, is explanation;
         * a block that is only a link (the footer) is dropped.
         */
        private fun readStructured(content: Any?, parts: Parts) {
            val items = when (content) {
                is JSONArray -> (0 until content.length()).map { content.opt(it) }
                null -> return
                else -> listOf(content)
            }
            var section = ""
            for (item in items) {
                if (item is String && MARKER.matches(item.trim())) {
                    section = MARKER.find(item.trim())!!.groupValues[1].trim().lowercase()
                    continue
                }
                if (item is JSONObject && item.optString("tag") == "a") continue
                when (section) {
                    "meaning" -> text(item).takeIf { it.isNotBlank() }?.let { parts.meaning += it.trim() }
                    "examples" -> listItems(item).forEach { example ->
                        // "日本語の文。\n Its translation."
                        val lines = example.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isNotEmpty()) parts.examples += lines.first() to lines.drop(1).joinToString(" ")
                    }
                    else -> text(item).takeIf { it.isNotBlank() }?.let { parts.explanation += it.trim() }
                }
            }
        }

        private fun listItems(node: Any?): List<String> = when {
            node is JSONObject && node.optString("tag") in setOf("ol", "ul") -> when (val c = node.opt("content")) {
                is JSONArray -> (0 until c.length()).map { text(c.opt(it)) }
                null -> emptyList()
                else -> listOf(text(c))
            }
            else -> listOf(text(node))
        }

        private fun text(node: Any?): String = when (node) {
            is String -> node
            is JSONArray -> (0 until node.length()).joinToString("") { text(node.opt(it)) }
            is JSONObject -> if (node.optString("tag") == "a") "" else text(node.opt("content"))
            else -> ""
        }

        private val MARKER = Regex("""【\s*([^】]+?)\s*】""")
        private val LEVEL = Regex("""N[1-5]""")

        /**
         * The Definition field of a grammar card: per point, the meaning, the
         * explanation, and the examples with their translations.
         * [GRAMMAR_CLASS] on the wrapper is how a refresh recognises a
         * grammar explanation and leaves it alone.
         */
        fun definitionHtml(points: List<GrammarPoint>): String = points.joinToString("<hr class=\"grammar-divider\">") { p ->
            buildString {
                append("<div class=\"$GRAMMAR_CLASS\">")
                if (p.meaning.isNotEmpty()) append("<div class=\"grammar-meaning\">").append(html(p.meaning)).append("</div>")
                if (p.explanation.isNotEmpty()) append("<div class=\"grammar-explanation\">").append(html(p.explanation)).append("</div>")
                if (p.examples.isNotEmpty()) {
                    append("<ol class=\"grammar-examples\">")
                    for ((japanese, translation) in p.examples) {
                        append("<li><div class=\"sentence-jp\">").append(html(japanese)).append("</div>")
                        if (translation.isNotEmpty()) append("<div class=\"sentence-translation\">").append(html(translation)).append("</div>")
                        append("</li>")
                    }
                    append("</ol>")
                }
                append("</div>")
            }
        }

        const val GRAMMAR_CLASS = "grammar-point"

        fun isGrammarDefinition(fieldHtml: String): Boolean = "class=\"$GRAMMAR_CLASS\"" in fieldHtml

        private fun html(text: String): String = InputSanitizer.escapeHtml(text.trim()).replace("\n", "<br>")
    }
}
