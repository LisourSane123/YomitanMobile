package com.yomitanmobile.domain.model

import kotlinx.serialization.Serializable

/**
 * A single example sentence with its translation, sourced from the dictionary
 * (Jitendex embeds Tatoeba sentence pairs in its structured-content definitions).
 */
@Serializable
data class ExamplePair(
    val jp: String,
    val en: String
)
