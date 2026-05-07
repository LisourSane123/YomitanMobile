package com.yomitanmobile.util

object PartOfSpeechFormatter {

    fun formatTags(partsOfSpeech: List<String>): List<String> {
        val tokens = partsOfSpeech
            .flatMap { raw -> raw.split(Regex("[\\s,;]+")) }
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val labels = mutableListOf<String>()
        for (token in tokens) {
            val normalized = token.lowercase()
            val label = toLabel(normalized)
            if (label != null && label !in labels) {
                labels.add(label)
            }
        }
        return labels
    }

    private fun toLabel(token: String): String? {
        return when {
            token == "n" || token == "noun" -> "noun"
            token == "pn" || token == "pronoun" -> "pronoun"
            token == "exp" || token == "expression" -> "expression"
            token == "int" || token == "interjection" -> "interjection"
            token == "adj-i" || token == "i-adjective" -> "i-adjective"
            token == "adj-na" || token == "na-adjective" -> "na-adjective"
            token == "vs" || token == "vs-i" || token == "vs-s" || token == "suru" || token == "suru verb" -> "suru verb"
            token == "v1" || token == "ichidan" || token == "ichidan verb" -> "1-dan verb"
            token.startsWith("v5") || token == "godan" || token == "godan verb" -> "5-dan verb"
            token == "vk" || token == "kuru" || token == "kuru verb" -> "kuru verb"
            token == "vz" -> "zuru verb"
            token == "vi" || token == "intransitive" || token == "intransitive verb" -> "intransitive verb"
            token == "vt" || token == "transitive" || token == "transitive verb" -> "transitive verb"
            token == "translative" || token == "translative verb" -> "transitive verb"
            token == "*" -> null
            token.startsWith("adj") -> "adjective"
            token.startsWith("v") || token == "verb" -> "verb"
            token.startsWith("jlpt") -> null
            else -> token
        }
    }
}
