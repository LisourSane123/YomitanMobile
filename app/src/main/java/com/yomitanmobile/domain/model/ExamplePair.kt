package com.yomitanmobile.domain.model

import kotlinx.serialization.Serializable

/**
 * A single example sentence with its translation, sourced from the dictionary
 * (Jitendex embeds Tatoeba sentence pairs in its structured-content definitions).
 *
 * [definitionIndex] points back to the gloss in [WordEntry.definitions] this
 * example belongs to. A value of -1 means the example is not attached to a
 * specific gloss (legacy data, online sentence-API results, plain JMDict
 * imports). UIs can render examples directly under their corresponding meaning.
 */
@Serializable
data class ExamplePair(
    val jp: String,
    val en: String,
    val definitionIndex: Int = -1
)
