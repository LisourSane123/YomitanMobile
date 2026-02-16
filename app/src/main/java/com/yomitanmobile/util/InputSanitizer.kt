package com.yomitanmobile.util

/**
 * Utility for sanitizing user input to prevent XSS, SQL injection,
 * and other injection attacks throughout the application.
 */
object InputSanitizer {

    /**
     * Sanitize text for safe inclusion in HTML content (Anki cards).
     * Escapes HTML special characters to prevent XSS.
     */
    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    /**
     * Sanitize FTS search query to prevent FTS syntax errors.
     * Escapes special characters used in SQLite FTS syntax.
     */
    fun sanitizeFtsQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return ""
        // Escape double quotes for FTS, wrap in quotes for exact phrase matching
        val escaped = trimmed.replace("\"", "\"\"")
        return "\"$escaped\"*"
    }

    /**
     * Sanitize a general text query (for LIKE queries).
     * Prevents SQL wildcard injection via LIKE patterns.
     */
    fun sanitizeLikeQuery(query: String): String {
        return query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    /**
     * Sanitize deck name input for Anki operations.
     * Removes potentially dangerous characters while preserving
     * valid deck naming characters (including ::  for sub-decks).
     */
    fun sanitizeDeckName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return "Mining Deck"
        // Allow alphanumeric, spaces, hyphens, underscores, colons (for sub-decks), Japanese chars
        return trimmed
            .replace(Regex("[<>\"';&|`\$\\\\]"), "")
            .take(200) // Reasonable max length
            .ifBlank { "Mining Deck" }
    }

    /**
     * Sanitize a file name to prevent path traversal attacks.
     */
    fun sanitizeFileName(name: String): String {
        return name
            .replace("..", "")
            .replace("/", "")
            .replace("\\", "")
            .replace("\u0000", "")
            .take(255)
    }
}
