package com.yomitanmobile.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MeaningExample(
    val sentenceHtml: String,
    val sentenceText: String = "",
    val translation: String = ""
)

@Serializable
data class MeaningBlock(
    val meaning: String,
    val examples: List<MeaningExample> = emptyList()
)