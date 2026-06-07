package com.yomitanmobile.data.download

/**
 * Represents a downloadable dictionary resource.
 */
data class DictionaryDownloadInfo(
    val id: String,
    val name: String,
    val descriptionPl: String,
    val descriptionEn: String,
    val category: DictionaryCategory,
    val url: String,
    val fileSize: String,
    val sha256: String? = null,
    val language: String = "EN"
)

fun DictionaryDownloadInfo.localizedDescription(isEnglish: Boolean): String {
    return if (isEnglish) descriptionEn else descriptionPl
}

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

    // Recommended primary dictionary. Jitendex is a Yomitan-format JMDict
    // derivative that ships JLPT tags + Tatoeba example sentences embedded as
    // structured-content nodes — both consumed by YomitanDictionaryParser.
    val jitendex = DictionaryDownloadInfo(
        id = "jitendex",
        name = "Jitendex (Recommended)",
        descriptionPl = "Wzbogacony JMdict z przykładowymi zdaniami i tagami JLPT. Zalecany słownik główny.",
        descriptionEn = "Enriched JMDict with example sentences and JLPT tags. Recommended primary dictionary.",
        category = DictionaryCategory.DICTIONARY,
        url = "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip",
        fileSize = "~37 MB",
        language = "EN"
    )

    // Fallback option for users who prefer a smaller download or who already
    // import their own JMDict-derived data. Lacks JLPT tags and example sentences.
    val jmdict = DictionaryDownloadInfo(
        id = "jmdict_english",
        name = "JMdict (English)",
        descriptionPl = "Główny słownik japońsko-angielski. ~200 000 wpisów. Najbardziej kompletny darmowy słownik.",
        descriptionEn = "Main Japanese-English dictionary. ~200,000 entries. The most complete free dictionary.",
        category = DictionaryCategory.DICTIONARY,
        url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip",
        fileSize = "~15 MB",
        language = "EN"
    )

    // Companion dictionary that ships JLPT level tags for ~8000 vocabulary
    // entries. Jitendex itself does not embed JLPT data, so we install this
    // alongside it. Format: term_meta_bank "freq" entries whose displayValue
    // is "N1"-"N5" — picked up by YomitanDictionaryParser as JLPT levels.
    val jlptVocab = DictionaryDownloadInfo(
        id = "jlpt_vocab",
        name = "JLPT Vocab Tags",
        descriptionPl = "Tagi poziomów JLPT (N1–N5) dla słów. Uzupełnienie do Jitendex.",
        descriptionEn = "JLPT level tags (N1-N5) for vocabulary. Companion to Jitendex.",
        category = DictionaryCategory.FREQUENCY,
        url = "https://github.com/stephenmk/yomitan-jlpt-vocab/releases/latest/download/jlpt.zip",
        fileSize = "~80 KB",
        language = "EN"
    )

    val all: List<DictionaryDownloadInfo> = listOf(
        jitendex,
        jlptVocab,
        jmdict,
        DictionaryDownloadInfo(
            id = "jmdict_forms",
            name = "JMdict Forms",
            descriptionPl = "Formy koniugacyjne i odmiany słów japońskich.",
            descriptionEn = "Conjugation forms and inflections of Japanese words.",
            category = DictionaryCategory.DICTIONARY,
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_forms.zip",
            fileSize = "~6 MB",
            language = "EN"
        ),
        DictionaryDownloadInfo(
            id = "jmnedict",
            name = "JMnedict (Names)",
            descriptionPl = "Słownik japońskich nazw własnych – imiona, nazwy miejsc itp.",
            descriptionEn = "Dictionary of Japanese proper names - personal names, place names, and more.",
            category = DictionaryCategory.DICTIONARY,
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMnedict.zip",
            fileSize = "~12 MB",
            language = "EN"
        ),
        DictionaryDownloadInfo(
            id = "kanjidic",
            name = "KANJIDIC",
            descriptionPl = "Szczegółowe informacje o kanji – znaczenia, odczyty, JLPT, grade.",
            descriptionEn = "Detailed kanji information - meanings, readings, JLPT, grade.",
            category = DictionaryCategory.KANJI,
            url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip",
            fileSize = "~1 MB",
            language = "EN"
        ),
        DictionaryDownloadInfo(
            id = "jpdb_freq",
            name = "JPDB Frequency v2.2",
            descriptionPl = "Ranking częstotliwości z jpdb.io – anime, manga, visual novels. Najnowsza wersja.",
            descriptionEn = "Frequency ranking from jpdb.io - anime, manga, visual novels. Latest version.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/JPDB_v2.2_Frequency_2024-10-13.zip",
            fileSize = "~5 MB",
            language = "EN"
        ),
        // BCCWJ: short- and long-unit word frequencies from the Balanced
        // Corpus of Contemporary Written Japanese — newspapers, books, formal
        // written text. Complements JPDB (media/colloquial) for learners
        // reading formal Japanese. ~1M entries; the import takes longer than
        // JPDB, so it's opt-in rather than part of the first-install bundle.
        DictionaryDownloadInfo(
            id = "bccwj_freq",
            name = "BCCWJ Frequency",
            descriptionPl = "Częstotliwości z korpusu BCCWJ – prasa, książki, teksty formalne. Uzupełnia JPDB.",
            descriptionEn = "Frequencies from the BCCWJ corpus - news, books, formal writing. Complements JPDB.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://github.com/Kuuuube/yomitan-dictionaries/raw/main/dictionaries/BCCWJ_SUW_LUW_combined.zip",
            fileSize = "~19 MB",
            language = "EN"
        ),
        DictionaryDownloadInfo(
            id = "kanjium_pitch",
            name = "Kanjium Pitch Accent",
            descriptionPl = "Słownik akcentu tonalnego (pitch accent) dla japońskiego. Pokazuje wzory akcentu dla słów.",
            descriptionEn = "Japanese pitch accent dictionary. Shows pitch patterns for words.",
            category = DictionaryCategory.PITCH_ACCENT,
            url = "https://github.com/toasted-nutbread/yomichan-pitch-accent-dictionary/releases/download/1.0.0/kanjium_pitch_accents.zip",
            fileSize = "~1 MB",
            language = "EN"
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Self-hosted frequency lists (TO WIRE UP).
    //
    // freq.txt lists four more frequency dictionaries — Japanese Wikipedia,
    // Anime & Drama (Subs2SRS), Innocent Corpus, and Narou — that are only
    // distributed via MarvNC's Google Drive. The downloader's HTTPS allowlist
    // (ALLOWED_DOWNLOAD_HOSTS in DictionaryDownloadManager) intentionally
    // permits GitHub hosts only, so Drive links can't be fetched.
    //
    // Plan: mirror those zips to a GitHub repo, then add an entry per list to
    // the `all` list above using this template (no code change beyond the URL):
    //
    //   DictionaryDownloadInfo(
    //       id = "wikipedia_freq",
    //       name = "Japanese Wikipedia Frequency",
    //       descriptionPl = "Częstotliwości z artykułów Wikipedii.",
    //       descriptionEn = "Frequencies from Japanese Wikipedia articles.",
    //       category = DictionaryCategory.FREQUENCY,
    //       url = "https://github.com/<you>/<repo>/raw/main/wikipedia_freq.zip",
    //       fileSize = "~? MB",
    //   ),
    //   // …same for anime_drama_freq, innocent_corpus_freq, narou_freq.
    //
    // Everything downstream (storage, per-source display, ordering settings)
    // already handles any number of FREQUENCY lists — the title in the zip's
    // index.json becomes the source label automatically.
    // ─────────────────────────────────────────────────────────────────────

    fun getByCategory(category: DictionaryCategory): List<DictionaryDownloadInfo> {
        return all.filter { it.category == category }
    }

    /**
     * Recommended dictionaries for first-time setup.
     * Jitendex (primary, with JLPT + examples), JPDB Frequency, Kanjium Pitch Accent.
     */
    // Recommended bundle = the four sources actually consumed by the app:
    //   • Jitendex — primary term dictionary (search results, definitions,
    //     parts of speech, example sentences attached to senses)
    //   • JLPT Vocab Tags — populates the JLPT badge for ~3200 entries per level
    //   • JPDB Frequency — populates the frequency rank shown above each word
    //   • Kanjium Pitch Accent — populates the pitch accent diagram
    //   • KANJIDIC — populates the kanji breakdown on Anki cards
    // Without KANJIDIC the kanji breakdown stays blank, so it has to be in
    // the recommended set, not buried under "advanced".
    val recommended: List<DictionaryDownloadInfo> = listOf(
        jitendex,
        jlptVocab,
        all.first { it.id == "jpdb_freq" },
        all.first { it.id == "kanjium_pitch" },
        all.first { it.id == "kanjidic" }
    )
}
