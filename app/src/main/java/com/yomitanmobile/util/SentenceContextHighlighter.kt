package com.yomitanmobile.util

/**
 * Builds safe HTML for a context sentence and highlights a target token.
 */
object SentenceContextHighlighter {

    fun buildHighlightedSentenceHtml(
        sentence: String,
        preferredTokens: List<String>
    ): String {
        val trimmedSentence = sentence.trim()
        if (trimmedSentence.isBlank()) return ""

        val escapedSentence = InputSanitizer.escapeHtml(trimmedSentence)
        val tokens = preferredTokens
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }

        for (token in tokens) {
            val escapedToken = InputSanitizer.escapeHtml(token)
            if (escapedToken.isBlank()) continue
            if (!escapedSentence.contains(escapedToken)) continue

            return escapedSentence.replace(
                escapedToken,
                "<strong class=\"context-highlight\">$escapedToken</strong>"
            )
        }

        return escapedSentence
    }
}
