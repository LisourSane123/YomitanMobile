package com.yomitanmobile.data.local.dao

data class FrequencyUpdate(
    val expression: String,
    val reading: String?,
    val frequency: Int,
    // The label the source list ships (rank as a string, or a bucketed
    // label). Blank falls back to [frequency] at the storage layer.
    val displayValue: String = ""
)

data class JlptUpdate(
    val expression: String,
    val reading: String?,
    val level: Int
)

/** Lightweight (expression, reading) projection for furigana generation. */
data class ExpressionReading(
    val expression: String,
    val reading: String,
    val frequency: Int
)
