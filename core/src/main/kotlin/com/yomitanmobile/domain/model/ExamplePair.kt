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
    val definitionIndex: Int = -1,
    /**
     * The Japanese sentence broken into furigana segments, preserving the ruby
     * readings the parser used to discard. Each [FuriganaSegment] is either a
     * plain run of text (blank [FuriganaSegment.reading]) or a kanji base paired
     * with its kana reading. Empty for older imports / plain-JMDict data — the
     * UI then falls back to rendering [jp] as-is. Concatenating every segment's
     * `text` reproduces [jp].
     */
    val segments: List<FuriganaSegment> = emptyList()
)

/**
 * One chunk of a furigana-annotated sentence. [reading] is blank for text that
 * carries no ruby (kana, punctuation, latin); non-blank when [text] is a kanji
 * run and [reading] its kana furigana. Kept as a defaulted field on
 * [ExamplePair] so existing serialized examples deserialize unchanged.
 */
@Serializable
data class FuriganaSegment(
    val text: String,
    val reading: String = ""
)
