package com.yomitanmobile.data.download

/**
 * Represents a downloadable dictionary resource.
 */
data class DictionaryDownloadInfo(
    val id: String,
    val name: String,
    val description: String,
    val category: DictionaryCategory,
    val url: String,
    val fileSize: String,
    val language: String = "EN"
)

enum class DictionaryCategory {
    DICTIONARY,
    FREQUENCY,
    PITCH_ACCENT,
    KANJI
}

/**
 * Built-in list of available dictionaries for download.
 * URLs point to GitHub releases of community-maintained Yomitan dictionaries.
 */
object AvailableDictionaries {

    val jmdict = DictionaryDownloadInfo(
        id = "jmdict_english",
        name = "JMdict (English)",
        description = "Główny słownik japońsko-angielski. ~200 000 wpisów. Najbardziej kompletny darmowy słownik.",
        category = DictionaryCategory.DICTIONARY,
        url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip",
        fileSize = "~15 MB",
        language = "EN"
    )

    val all: List<DictionaryDownloadInfo> = listOf(
        jmdict,
        DictionaryDownloadInfo(
            id = "jmdict_forms",
            name = "JMdict Forms",
            description = "Formy koniugacyjne i odmiany słów japońskich.",
            category = DictionaryCategory.DICTIONARY,
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_forms.zip",
            fileSize = "~6 MB",
            language = "EN"
        ),
        DictionaryDownloadInfo(
            id = "jmnedict",
            name = "JMnedict (Names)",
            description = "Słownik japońskich nazw własnych – imiona, nazwy miejsc itp.",
            category = DictionaryCategory.DICTIONARY,
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            fileSize = "~12 MB",
            language = "EN"
        ),
        DictionaryDownloadInfo(
            id = "kanjidic",
            name = "KANJIDIC",
            description = "Szczegółowe informacje o kanji – znaczenia, odczyty, JLPT, grade.",
            category = DictionaryCategory.KANJI,
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip",
            fileSize = "~1 MB",
            language = "EN"
        ),
        DictionaryDownloadInfo(
            id = "jpdb_freq",
            name = "JPDB Frequency v2.2",
            description = "Ranking częstotliwości z jpdb.io – anime, manga, visual novels. Najnowsza wersja.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/JPDB_v2.2_Frequency_2024-10-13.zip",
            fileSize = "~3 MB",
            language = "EN"
        ),
        DictionaryDownloadInfo(
            id = "kanjium_pitch",
            name = "Kanjium Pitch Accent",
            description = "Słownik akcentu tonalnego (pitch accent) dla japońskiego. Pokazuje wzory akcentu dla słów.",
            category = DictionaryCategory.PITCH_ACCENT,
            url = "https://github.com/toasted-nutbread/yomichan-pitch-accent-dictionary/releases/download/1.0.0/kanjium_pitch_accents.zip",
            fileSize = "~1 MB",
            language = "EN"
        )
    )

    fun getByCategory(category: DictionaryCategory): List<DictionaryDownloadInfo> {
        return all.filter { it.category == category }
    }
}
