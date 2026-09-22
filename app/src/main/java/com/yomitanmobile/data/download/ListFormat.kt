package com.yomitanmobile.data.download

import java.io.File

/**
 * A list published in its maker's own format, converted into a Yomitan
 * dictionary on install — no English or Spanish frequency list, and no CEFR
 * list, exists in Yomitan's. See [FrequencyListConverter], [LevelListConverter].
 */
enum class ListFormat {
    WORDFREQ_MSGPACK,
    SUBTITLE_COUNTS,
    CEFR_CSV;

    /** The downloaded [file] as a Yomitan dictionary zip titled [title]. */
    fun toYomitanZip(file: File, title: String, revision: String, sourceLanguage: String, attribution: String): ByteArray =
        when (this) {
            WORDFREQ_MSGPACK, SUBTITLE_COUNTS -> {
                val format = if (this == WORDFREQ_MSGPACK) FrequencyListConverter.Format.WORDFREQ_MSGPACK
                else FrequencyListConverter.Format.SUBTITLE_COUNTS
                val words = file.inputStream().use { FrequencyListConverter.rankedWords(format, it) }
                FrequencyListConverter.toYomitanZip(title, revision, sourceLanguage, attribution, words)
            }
            CEFR_CSV -> LevelListConverter.toYomitanZip(
                title, revision, sourceLanguage, attribution,
                LevelListConverter.cefrLevels(file.readText(Charsets.UTF_8))
            )
        }
}
