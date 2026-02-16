package com.yomitanmobile.domain.model

data class AnkiCard(
    val front: String,
    val reading: String,
    val meaning: String,
    val pitchAccent: String,
    val frequency: String,
    val audioFileName: String,
    val sentence: String
) {
    fun toFieldArray(): Array<String> = arrayOf(
        front, reading, meaning, pitchAccent, frequency, audioFileName, sentence
    )
}
