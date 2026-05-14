package com.yomitanmobile.domain.usecase

import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.domain.repository.DictionaryRepository
import com.yomitanmobile.util.WordCategoryClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Outcome of a [ReclassifyCategoriesUseCase] run, surfaced as a snackbar
 * after the user taps "Recompute categories" in Settings.
 */
data class ReclassifyResult(
    /** Rows whose `exportCategories` was rewritten. */
    val updated: Int,
    /** Rows skipped because of a manual override (intentionally preserved). */
    val skippedManual: Int,
    /** Rows whose source dictionary is no longer installed; we can't reclassify them. */
    val skippedMissing: Int
)

/**
 * Walks every `exported_words` row, re-fetches the matching dictionary
 * entry, and rewrites the multi-label `export_categories` column using
 * the current classifier rules. The user override (`manual_category`) is
 * left untouched.
 *
 * Lossy when the source dictionary has been deleted — those rows are
 * counted in [ReclassifyResult.skippedMissing] and keep their existing
 * `export_categories`.
 */
class ReclassifyCategoriesUseCase @Inject constructor(
    private val exportedWordDao: ExportedWordDao,
    private val repository: DictionaryRepository
) {
    suspend operator fun invoke(): ReclassifyResult = withContext(Dispatchers.IO) {
        val rows = exportedWordDao.getAllExports()
        var updated = 0
        var skippedManual = 0
        var skippedMissing = 0

        for (row in rows) {
            if (row.manualCategory.trim().isNotEmpty()) {
                skippedManual++
                continue
            }

            val readingKey = row.reading.ifBlank { row.expression }
            val candidates = runCatching {
                repository.getEntriesByReading(readingKey)
            }.getOrDefault(emptyList())

            // Match on expression. A reading like たべる resolves to several
            // dictionary entries (食べる, 食う variants); we want the one
            // whose expression matches the exported row.
            val source = candidates.firstOrNull { it.expression == row.expression }
                ?: candidates.firstOrNull()  // fallback: best-effort

            if (source == null) {
                skippedMissing++
                continue
            }

            val categories = runCatching {
                WordCategoryClassifier.classifyAll(source)
            }.getOrDefault(listOf(WordCategoryClassifier.CATEGORY_OTHER))

            val primary = categories.firstOrNull()
                ?: WordCategoryClassifier.CATEGORY_OTHER
            val csv = categories.joinToString(",")

            // Only write if something actually changed — keeps the
            // operation cheap on a no-op re-run.
            if (primary != row.exportCategory || csv != row.exportCategories) {
                exportedWordDao.updateExportCategories(row.id, primary, csv)
            }
            updated++
        }

        ReclassifyResult(
            updated = updated,
            skippedManual = skippedManual,
            skippedMissing = skippedMissing
        )
    }
}
