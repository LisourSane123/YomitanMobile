package com.yomitanmobile.domain.model

data class ImportResult(
    val success: Boolean,
    val dictionaryName: String,
    val entriesImported: Int,
    val errorMessage: String? = null,
    /**
     * Set when the import finished but something the user should know about
     * went wrong on the way — today only a failed FTS rebuild, which leaves
     * meaning search unable to see the new dictionary until the next import.
     * Silence there meant a broken search with a success message on screen.
     */
    val warning: String? = null
)

data class ImportProgress(
    val currentFile: String,
    val filesProcessed: Int,
    val totalFiles: Int,
    val entriesProcessed: Int,
    val totalEntries: Int
) {
    val progressPercent: Float
        get() = if (totalFiles > 0) filesProcessed.toFloat() / totalFiles else 0f
}
