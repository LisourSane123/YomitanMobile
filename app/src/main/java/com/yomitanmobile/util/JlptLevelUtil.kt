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
     * Extract JLPT level from JMDict tags.
     * Tags are typically in format "jlpt-1", "jlpt-2", "jlpt-3", "jlpt-4", "jlpt-5"
     * where jlpt-1 = N1 (hardest), jlpt-5 = N5 (easiest)
     * 
     * Falls back to frequency-based estimation if no JLPT tag found.
     */
    fun getLevel(tagsString: String, frequency: Int = 0): JlptLevel? {
        // First try to extract JLPT tag from tags string
        if (tagsString.isNotBlank()) {
            val jlptTag = extractJlptTag(tagsString)
            if (jlptTag != null) return jlptTag
        }

        // Fallback: estimate from frequency (lower number = higher frequency = more basic)
        if (frequency > 0) {
            return when {
                frequency <= 500 -> JlptLevel.N5       // Top 500 most common words
                frequency <= 2000 -> JlptLevel.N4      // Top 2000
                frequency <= 8000 -> JlptLevel.N3      // Top 8000
                frequency <= 15000 -> JlptLevel.N2     // Top 15000
                frequency <= 50000 -> JlptLevel.N1     // Top 50000
                else -> null
            }
        }

        return null
    }

    /**
     * Extract JLPT level from JMDict tags and parts of speech.
     * Looks through tags and partsOfSpeech for JLPT level information.
     */
    fun getLevelFromTags(
        tagsAndPartsOfSpeech: String,
        frequency: Int = 0
    ): JlptLevel? {
        return getLevel(tagsAndPartsOfSpeech, frequency)
    }

    /**
     * Extract JLPT tag from a comma-separated tags string.
     * Looks for patterns like "jlpt-1", "jlpt-2", "jlpt-3", "jlpt-4", "jlpt-5"
     * where jlpt-1 = N1, jlpt-2 = N2, jlpt-3 = N3, jlpt-4 = N4, jlpt-5 = N5
     */
    private fun extractJlptTag(tagsString: String): JlptLevel? {
        if (tagsString.isBlank()) return null

        val tags = tagsString.split(",").map { it.trim().lowercase() }
        
        for (tag in tags) {
            when {
                tag.contains("jlpt-1") || tag == "jlpt-1" -> return JlptLevel.N1
                tag.contains("jlpt-2") || tag == "jlpt-2" -> return JlptLevel.N2
                tag.contains("jlpt-3") || tag == "jlpt-3" -> return JlptLevel.N3
                tag.contains("jlpt-4") || tag == "jlpt-4" -> return JlptLevel.N4
                tag.contains("jlpt-5") || tag == "jlpt-5" -> return JlptLevel.N5
            }
        }

        return null
    }

}
