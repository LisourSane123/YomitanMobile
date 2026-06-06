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
        // Expand each preferred token into its inflected surface forms so a
        // sentence that uses the word in a conjugated shape (dictionary 食べる
        // vs. sentence 食べた) still gets highlighted. Longest-first matching
        // then prefers the fullest occurring inflection over any base prefix.
        val tokens = preferredTokens
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { JapaneseConjugator.inflectedForms(it) }
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
