package com.yomitanmobile.domain.model

data class WordEntry(
    val id: Long = 0,
    val expression: String,
    val reading: String,
    val definitions: List<String>,
    val frequency: Int = 0,
    val pitchAccent: String = "",
    val partsOfSpeech: String = "",
    val dictionaryName: String = "",
    val exampleSentence: String = "",
    val exampleSentenceTranslation: String = "",
    val audioFile: String = ""
) {
    fun definitionText(): String = definitions.joinToString("; ")

    fun displayText(): String = expression.ifBlank { reading }

    fun frequencyLabel(): String = when {
        frequency <= 0 -> ""
        frequency <= 1000 -> "★★★ Top 1K"
        frequency <= 3000 -> "★★★ Top 3K"
        frequency <= 5000 -> "★★ Top 5K"
        frequency <= 10000 -> "★ Top 10K"
        frequency <= 20000 -> "Top 20K"
        frequency <= 50000 -> "Top 50K"
        else -> "#$frequency"
    }
}
