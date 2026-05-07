package com.yomitanmobile.util

data class SentenceRubySegment(
    val text: String,
    val reading: String? = null
)

object SentenceRubyFormatter {

    fun buildSegments(
        sentence: String,
        targetExpression: String,
        targetReading: String
    ): List<SentenceRubySegment> {
        val trimmedSentence = sentence.trim()
        if (trimmedSentence.isBlank()) return emptyList()

        val target = targetExpression.trim()
        val reading = targetReading.trim()

        val candidates = buildCandidateMatches(target, reading)
        val match = candidates.firstOrNull { candidate ->
            val index = trimmedSentence.indexOf(candidate)
            index >= 0
        } ?: return listOf(SentenceRubySegment(trimmedSentence))

        val start = trimmedSentence.indexOf(match)
        val end = start + match.length
        val prefix = trimmedSentence.substring(0, start)
        val suffix = trimmedSentence.substring(end)

        return buildList {
            if (prefix.isNotBlank()) add(SentenceRubySegment(prefix))
            add(SentenceRubySegment(match, if (reading.isNotBlank()) reading else target.ifBlank { match }))
            if (suffix.isNotBlank()) add(SentenceRubySegment(suffix))
        }
    }

    fun buildRubyHtml(
        sentence: String,
        targetExpression: String,
        targetReading: String
    ): String {
        val segments = buildSegments(sentence, targetExpression, targetReading)
        if (segments.isEmpty()) return ""

        return segments.joinToString(separator = "") { segment ->
            if (segment.reading.isNullOrBlank()) {
                InputSanitizer.escapeHtml(segment.text)
            } else {
                val escapedText = InputSanitizer.escapeHtml(segment.text)
                val escapedReading = InputSanitizer.escapeHtml(segment.reading)
                "<ruby>$escapedText<rt>$escapedReading</rt></ruby>"
            }
        }
    }

    private fun buildCandidateMatches(targetExpression: String, targetReading: String): List<String> {
        val candidates = mutableListOf<String>()
        if (targetExpression.isNotBlank()) {
            candidates.add(targetExpression)
            if (targetExpression.length > 1) {
                candidates.add(targetExpression.dropLast(1))
            }
            if (targetExpression.length > 2) {
                candidates.add(targetExpression.dropLast(2))
            }
        }
        if (targetReading.isNotBlank()) {
            candidates.add(targetReading)
        }
        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it }
            .sortedByDescending { it.length }
    }
}