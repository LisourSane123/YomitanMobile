package com.yomitanmobile.util

/**
 * JLPT (Japanese Language Proficiency Test) level lookup utility.
 * Extracts JLPT levels from JMDict tags (e.g., "jlpt-1", "jlpt-2", etc.)
 * 
 * JMDict format: tags are stored in the partsOfSpeech field and include JLPT level
 * Levels: N5 (easiest) → N1 (hardest)
 */
object JlptLevelUtil {

    enum class JlptLevel(val label: String, val color: Long) {
        N5("N5", 0xFF4CAF50),   // Green - easiest
        N4("N4", 0xFF8BC34A),   // Light green
        N3("N3", 0xFFFFC107),   // Amber
        N2("N2", 0xFFFF9800),   // Orange
        N1("N1", 0xFFF44336);   // Red - hardest

        companion object {
            fun fromString(s: String): JlptLevel? = entries.firstOrNull { it.label == s }
        }
    }

    /**
     * Extract JLPT level only from JMDict tags.
     * Tags are typically in format "jlpt-1", "jlpt-2", "jlpt-3", "jlpt-4", "jlpt-5"
     * where jlpt-1 = N1 (hardest), jlpt-5 = N5 (easiest).
     *
     * No frequency fallback is used: result is either a JLPT level from tags or null.
     */
    fun getLevel(tagsString: String): JlptLevel? {
        // First try to extract JLPT tag from tags string
        if (tagsString.isNotBlank()) {
            val jlptTag = extractJlptTag(tagsString)
            if (jlptTag != null) return jlptTag
        }
        return null
    }

    /**
     * Extract JLPT level from JMDict tags and parts of speech.
     * Looks through tags and partsOfSpeech for JLPT level information.
     */
    fun getLevelFromTags(
        tagsAndPartsOfSpeech: String
    ): JlptLevel? {
        return getLevel(tagsAndPartsOfSpeech)
    }

    /**
     * Extract JLPT tag from a tags string.
     * Accepts common formats like: "jlpt-1", "jlpt-n1", "n1", "n2", etc.
     */
    private fun extractJlptTag(tagsString: String): JlptLevel? {
        if (tagsString.isBlank()) return null

        // Parse tag-like chunks to avoid false positives (e.g. "jlpt-3000").
        val chunks = tagsString
            .lowercase()
            .split(',', ';', '|', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Accepted forms:
        // - jlpt-1 / jlpt_1 / jlpt 1
        // - jlpt-n1 / jlpt_n1 / jlpt n1
        // - n1, n2, n3, n4, n5 (standalone, when surrounded by non-alphanumeric)
        // Also allows optional separators between n and digit: "jlpt n 1".
        val jlptPrefixPattern = Regex("""(?<![a-z0-9])jlpt[\s:_-]*n?[\s:_-]*([1-5])(?![a-z0-9])""")
        val standaloneLevelPattern = Regex("""(?<![a-z0-9])n([1-5])(?![a-z0-9])""")

        val matches = mutableListOf<Int>()
        
        for (chunk in chunks) {
            // Try jlpt-prefixed pattern first
            jlptPrefixPattern.find(chunk)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { matches.add(it) }
            
            // Try standalone n1-n5 pattern if no jlpt prefix found in this chunk
            if (!chunk.contains("jlpt")) {
                standaloneLevelPattern.find(chunk)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { matches.add(it) }
            }
        }

        if (matches.isEmpty()) return null

        // If multiple JLPT tags are present across merged entries,
        // prefer the most advanced/hardest level (N1) to avoid underestimating difficulty.
        val mostAdvanced = matches.minOrNull() ?: return null
        return when (mostAdvanced) {
            1 -> JlptLevel.N1
            2 -> JlptLevel.N2
            3 -> JlptLevel.N3
            4 -> JlptLevel.N4
            5 -> JlptLevel.N5
            else -> null
        }
    }


}
