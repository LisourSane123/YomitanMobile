package com.yomitanmobile.domain.model

data class ImportResult(
    val success: Boolean,
    val dictionaryName: String,
    val entriesImported: Int,
    val errorMessage: String? = null
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
