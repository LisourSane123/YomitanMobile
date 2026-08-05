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

    // The only freely licensed Japanese-Japanese dictionary that can be
    // offered as a one-tap download. Every 国語辞典 the community recommends
    // (三省堂, 明鏡, 新明解, 大辞泉, 岩波, 広辞苑) is a converted commercial
    // product handed around on Drive/Mega — not redistributable, and not on a
    // host the downloader is allowed to touch. Those go in through the
    // "import dictionary from file" path in Settings instead.
    //
    // Wiktionary is thinner than a real 国語辞典 but its definitions are
    // genuinely Japanese, so it makes the JP-JP card engine usable out of the
    // box. Pinned to a dated release + sha256.
    val wiktionaryJaJa = DictionaryDownloadInfo(
        id = "wiktionary_ja_ja",
        name = "日本語 Wiktionary (JP-JP)",
        descriptionPl = "Słownik japońsko-japoński z Wikisłownika. Wolna licencja — działa z trybem fiszek JP-JP.",
        descriptionEn = "Japanese-Japanese dictionary from Wiktionary. Freely licensed - powers the JP-JP card mode.",
        category = DictionaryCategory.DICTIONARY,
        url = "https://github.com/yomidevs/wiktionary-to-yomitan/releases/download/" +
            "v2025-04-08-10-54-36-00-00/kty-ja-ja.zip",
        fileSize = "~8 MB",
        sha256 = "fa74b473d060d86a3cb0fd27c46535d1d70c4e4ab5b47ab1df39d89fc63a0b2c",
        language = "JA"
    )

    val all: List<DictionaryDownloadInfo> = listOf(
        jitendex,
        jlptVocab,
        jmdict,
        wiktionaryJaJa,
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
        // The two Kuuuube files are pinned to a specific commit (not `main`)
        // and carry a sha256, so upstream can't silently swap the content we
        // install. The `latest/download` release URLs above intentionally
        // stay unpinned — their content changes every upstream release and
        // that's the point of offering them.
        DictionaryDownloadInfo(
            id = "jpdb_freq",
            name = "JPDB Frequency v2.2",
            descriptionPl = "Ranking częstotliwości z jpdb.io – anime, manga, visual novels. Najnowsza wersja.",
            descriptionEn = "Frequency ranking from jpdb.io - anime, manga, visual novels. Latest version.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://raw.githubusercontent.com/Kuuuube/yomitan-dictionaries/d6fde809e3f26eb5aed6d41896f332179044998c/dictionaries/JPDB_v2.2_Frequency_2024-10-13.zip",
            fileSize = "~5 MB",
            sha256 = "1468be7ebf7b920f63f76747fe0a571add70d788afc6e55b154c678de24fb9e5",
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
            url = "https://raw.githubusercontent.com/Kuuuube/yomitan-dictionaries/d6fde809e3f26eb5aed6d41896f332179044998c/dictionaries/BCCWJ_SUW_LUW_combined.zip",
            fileSize = "~19 MB",
            sha256 = "7d17054735e738d02e9f7f62fdad5d6e592a458abd93301367500d04d0c000c3",
            language = "EN"
        ),
        // ── Frequency lists covering the registers JPDB (media/fiction) and
        // BCCWJ (formal writing) miss. All four are rank-based Yomitan meta
        // dictionaries pinned to a commit + sha256, same rule as above.
        //
        // Everyday spoken Japanese: NINJAL's conversation corpus, recorded
        // real-life talk. The single best list for "what people actually say"
        // — words like うん / そう / ちょっと rank at the top here and nowhere
        // near it in a written corpus.
        DictionaryDownloadInfo(
            id = "cejc_freq",
            name = "CEJC (Conversation)",
            descriptionPl = "Częstotliwości z korpusu codziennych rozmów (NINJAL CEJC). Najlepsza lista dla języka mówionego.",
            descriptionEn = "Frequencies from the Corpus of Everyday Japanese Conversation (NINJAL). The best list for spoken Japanese.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://raw.githubusercontent.com/forsakeninfinity/CEJC_yomichan_freq_dict/" +
                "854ed02b791a9ca247d0752fb22d34f1ab3c650f/releases/" +
                "Corpus%20of%20Everyday%20Japanese%20Conversation.zip",
            fileSize = "~2 MB",
            sha256 = "273c603b7ea285debfd8b7d41dc326f97b2fd42c2ed94f22e6dafc1c9cbd8a6b",
            language = "JA"
        ),
        // Spontaneous speech (lectures, monologues) — complements CEJC's
        // dialogue with the register you meet in talks and presentations.
        DictionaryDownloadInfo(
            id = "csj_freq",
            name = "CSJ (Spoken)",
            descriptionPl = "Częstotliwości z Korpusu Mowy Spontanicznej (CSJ) – wykłady, wypowiedzi mówione.",
            descriptionEn = "Frequencies from the Corpus of Spontaneous Japanese (CSJ) - lectures and spoken monologue.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://raw.githubusercontent.com/Maltesaa/CSJ_and_NWJC_yomitan_freq_dict/" +
                "9902cc61eb8bfd9b5f99ad74e46349200777c103/CSJ%20releases/" +
                "Corpus%20of%20Spontaneous%20Japanese%20-%20CSJ.zip",
            fileSize = "~3 MB",
            sha256 = "2d3fd1735129f4d55871ce65125cd45197b8f28894d1fbb59ebd140c9de207c8",
            language = "JA"
        ),
        // Web Japanese: blogs, forums, shops. The register of most casual
        // written Japanese online, which neither BCCWJ nor JPDB covers.
        DictionaryDownloadInfo(
            id = "nwjc_freq",
            name = "NWJC (Web)",
            descriptionPl = "Częstotliwości z korpusu japońskiego internetu (NINJAL NWJC) – blogi, fora, sklepy.",
            descriptionEn = "Frequencies from the NINJAL Web Japanese Corpus - blogs, forums, shops.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://raw.githubusercontent.com/Maltesaa/CSJ_and_NWJC_yomitan_freq_dict/" +
                "9902cc61eb8bfd9b5f99ad74e46349200777c103/NWJC%20releases/" +
                "NINJAL%20Web%20Japanese%20Corpus%20-%20NWJC.zip",
            fileSize = "~8 MB",
            sha256 = "b20b9e6e29f4abf4e9a1a37a59314ea66cf5868cad1a94014847381bda23e8e8",
            language = "JA"
        ),
        // Literary/classical vocabulary from Aozora Bunko. Keyed by kanji
        // compound (jukugo) with no readings, so it ranks written vocabulary
        // rather than spoken forms — the counterpart to CEJC.
        DictionaryDownloadInfo(
            id = "aozora_freq",
            name = "Aozora Bunko (Literary)",
            descriptionPl = "Częstotliwości złożeń kanji z Aozora Bunko – literatura klasyczna i formalna.",
            descriptionEn = "Kanji-compound frequencies from Aozora Bunko - classical and literary Japanese.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://raw.githubusercontent.com/MarvNC/yomitan-dictionaries/" +
                "574961e823e33fb36b6b86778a0d6b606af29c25/dl/%5BFreq%5D%20Aozora%20Bunko.zip",
            fileSize = "~1 MB",
            sha256 = "116009c3034d97a16b257fda10f2138067815986c954bffbb5c93aad60faa867",
            language = "JA"
        ),
        DictionaryDownloadInfo(
            id = "kanjium_pitch",
            name = "Kanjium Pitch Accent",
            descriptionPl = "Słownik akcentu tonalnego (pitch accent) dla japońskiego. Pokazuje wzory akcentu dla słów.",
            descriptionEn = "Japanese pitch accent dictionary. Shows pitch patterns for words.",
            category = DictionaryCategory.PITCH_ACCENT,
            // Versioned (1.0.0) release asset — stable enough to pin a hash.
            url = "https://github.com/toasted-nutbread/yomichan-pitch-accent-dictionary/releases/download/1.0.0/kanjium_pitch_accents.zip",
            fileSize = "~1 MB",
            sha256 = "f89db29fd2cdec90fe6965d4ef1d92bfb59e201fc7b2f7b39f49a6cda2e99871",
            language = "EN"
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Frequency lists that still need a mirror (TO WIRE UP).
    //
    // These are only distributed through MarvNC's Google Drive folder or
    // catbox, and ALLOWED_DOWNLOAD_HOSTS in DictionaryDownloadManager permits
    // GitHub hosts only (so a pinned URL + sha256 can't be swapped under us):
    //
    //   • CC100            – web crawl corpus, the list learnjapanese.moe pairs
    //                        with JPDB
    //   • Innocent Corpus  – ~5000 visual novel scripts
    //   • Narou            – 小説家になろう web novels
    //   • Anime & Drama    – subs2srs subtitle corpus
    //   • YouTube (x16)    – domain-specific spoken lists
    //
    // Once mirrored to a GitHub repo, each becomes one entry in `all` above —
    // no code change beyond the URL, because storage, per-source display and
    // the ordering settings already handle any number of FREQUENCY lists (the
    // zip's index.json title becomes the source label automatically):
    //
    //   DictionaryDownloadInfo(
    //       id = "cc100_freq",
    //       name = "CC100 (Web crawl)",
    //       descriptionPl = "…", descriptionEn = "…",
    //       category = DictionaryCategory.FREQUENCY,
    //       url = "https://raw.githubusercontent.com/<user>/<repo>/<commit>/cc100.zip",
    //       fileSize = "~? MB",
    //       sha256 = "<sha256sum of the zip>",
    //   ),
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
