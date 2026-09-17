package com.yomitanmobile.data.download

import com.yomitanmobile.domain.model.AppLanguage

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
    /**
     * Language the *glosses* are written in ("EN", "JA", "PL"). Display
     * only — it tells the user what they will be reading, and says nothing
     * about which language the dictionary teaches.
     */
    val language: String = "EN",
    /**
     * Language this dictionary is FOR. Decides whether it is offered at all:
     * a Japanese learner is never shown an English Wiktionary and vice
     * versa. Every pre-existing entry teaches Japanese, hence the default.
     */
    val studyLanguage: AppLanguage = AppLanguage.JAPANESE
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

    // ── English → Polish ─────────────────────────────────────────────────
    //
    // There is no JMdict for English. No freely licensed English-Polish
    // dictionary exists in Yomitan format outside the Wiktionary
    // conversions, and the commercial ones (PWN, Diki) are not
    // redistributable at all — so coverage is assembled from three files
    // rather than taken from one:
    //
    //   • kty-en-pl — Polish glosses for ~79 000 English headwords, about a
    //     quarter of which carry an English example sentence WITH its Polish
    //     translation. This is what cards are built from.
    //   • kty-en-en — English definitions for essentially all of English.
    //     This is what the Polish-first lookup falls back TO: a word no
    //     Polish translator ever reached is still worth a card with an
    //     English definition, and it carries examples for many of the words
    //     kty-en-pl leaves bare.
    //   • kty-en-ipa — pronunciations, which fill the slot furigana occupies
    //     on a Japanese card.
    //
    // All three are pinned to the same dated release + sha256 as the
    // Japanese Wiktionary above; `latest/download` would let the content
    // change underneath a hash we published.
    private const val WIKTIONARY_RELEASE =
        "https://github.com/yomidevs/wiktionary-to-yomitan/releases/download/" +
            "v2025-04-08-10-54-36-00-00"

    val wiktionaryEnPl = DictionaryDownloadInfo(
        id = "wiktionary_en_pl",
        name = "Wiktionary EN→PL",
        descriptionPl = "Słownik angielsko-polski z Wikisłownika. ~79 000 haseł, wiele z przykładami zdań wraz z tłumaczeniem. Główny słownik dla angielskiego.",
        descriptionEn = "English-Polish dictionary from Wiktionary. ~79,000 headwords, many with example sentences and their translation. Primary dictionary for English.",
        category = DictionaryCategory.DICTIONARY,
        url = "$WIKTIONARY_RELEASE/kty-en-pl.zip",
        fileSize = "~4 MB",
        sha256 = "64cb29e22ad7a48d653a8b88bdf01132c6f880ddaecdaaf399e780ece6b19e04",
        language = "PL",
        studyLanguage = AppLanguage.ENGLISH
    )

    val wiktionaryEnEn = DictionaryDownloadInfo(
        id = "wiktionary_en_en",
        name = "Wiktionary EN→EN",
        descriptionPl = "Angielsko-angielski Wikisłownik. Rezerwa dla słów bez polskiego tłumaczenia oraz źródło dodatkowych przykładów. Uwaga: ~2,7 GB po imporcie, który trwa kilkadziesiąt minut.",
        descriptionEn = "English-English Wiktionary. Fallback for words with no Polish gloss, and a source of extra examples. Note: ~2.7 GB once imported, and the import takes tens of minutes.",
        category = DictionaryCategory.DICTIONARY,
        url = "$WIKTIONARY_RELEASE/kty-en-en.zip",
        fileSize = "~120 MB",
        sha256 = "3cf1f90ea930cf890293085d990e55415236a92cf935b0df6081627369033219",
        language = "EN",
        studyLanguage = AppLanguage.ENGLISH
    )

    val wiktionaryEnIpa = DictionaryDownloadInfo(
        id = "wiktionary_en_ipa",
        name = "Wymowa IPA (EN)",
        descriptionPl = "Transkrypcja fonetyczna IPA dla ~82 000 angielskich słów. Wypełnia pole wymowy na fiszkach.",
        descriptionEn = "IPA pronunciations for ~82,000 English words. Fills the pronunciation field on cards.",
        category = DictionaryCategory.PITCH_ACCENT,
        url = "$WIKTIONARY_RELEASE/kty-en-ipa.zip",
        fileSize = "~2 MB",
        sha256 = "224ee34ab05970cedd102741c1b5d9cb690eddfab7f2d37adddcdfb6c5a6e9c4",
        language = "EN",
        studyLanguage = AppLanguage.ENGLISH
    )

    // ── Spanish → English ────────────────────────────────────────────────
    //
    // Better covered than English→Polish, by a wide margin: kty-es-en is
    // 21 MB against kty-en-pl's 4 MB, because English is a far better served
    // target language on Wiktionary than Polish is.
    //
    // It is also shaped differently. 1.16M headwords, of which only a small
    // fraction are lemmas — Wiktionary lists every inflected Spanish form as
    // its own entry pointing back at the base word ("hablando" → "hablar
    // (gerund)"). That is why there is no Spanish lemmatiser in the app: the
    // dictionary already resolves the conjugations, irregulars included,
    // which no rule table could.
    //
    // The size that matters is the one after import, not the download.
    val wiktionaryEsEn = DictionaryDownloadInfo(
        id = "wiktionary_es_en",
        name = "Wiktionary ES→EN",
        descriptionPl = "Słownik hiszpańsko-angielski z Wikisłownika. 1,16 mln haseł wraz z formami odmienionymi (hablando → hablar). Import trwa kilkanaście minut i zajmuje ~600 MB.",
        descriptionEn = "Spanish-English dictionary from Wiktionary. 1.16M headwords including inflected forms (hablando → hablar). Import takes several minutes and needs ~600 MB.",
        category = DictionaryCategory.DICTIONARY,
        url = "$WIKTIONARY_RELEASE/kty-es-en.zip",
        fileSize = "~21 MB",
        sha256 = "e6600cbff3639ac1cac56b56a556c7fd4ce13346f0bd3bff7329213ebfd0d99e",
        language = "EN",
        studyLanguage = AppLanguage.SPANISH
    )

    val wiktionaryEsEs = DictionaryDownloadInfo(
        id = "wiktionary_es_es",
        name = "Wiktionary ES→ES",
        descriptionPl = "Hiszpańsko-hiszpański Wikisłownik. Rezerwa dla haseł bez angielskiego tłumaczenia. Bardzo duży — ~1,4 GB po imporcie.",
        descriptionEn = "Spanish-Spanish Wiktionary. Fallback for entries with no English gloss. Very large - ~1.4 GB once imported.",
        category = DictionaryCategory.DICTIONARY,
        url = "$WIKTIONARY_RELEASE/kty-es-es.zip",
        fileSize = "~37 MB",
        sha256 = "3dc2de5f45fd263ee4fade493b8e3012a705c7d1751cd9529442b1e2fab4d8d3",
        language = "ES",
        studyLanguage = AppLanguage.SPANISH
    )

    val wiktionaryEsIpa = DictionaryDownloadInfo(
        id = "wiktionary_es_ipa",
        name = "Wymowa IPA (ES)",
        descriptionPl = "Transkrypcja fonetyczna IPA dla hiszpańskich słów. Wypełnia pole wymowy na fiszkach.",
        descriptionEn = "IPA pronunciations for Spanish words. Fills the pronunciation field on cards.",
        category = DictionaryCategory.PITCH_ACCENT,
        url = "$WIKTIONARY_RELEASE/kty-es-ipa.zip",
        fileSize = "~11 MB",
        sha256 = "494e866bce88be014b9511957a0a81707a1fb540270e3a5e487d8cbccda53aa5",
        language = "ES",
        studyLanguage = AppLanguage.SPANISH
    )

    /**
     * Everything on offer, in the order the download screen draws it.
     *
     * Exactly THREE frequency lists live here, one per register, and that is
     * a decision rather than a state of the port:
     *
     *   • JPDB — anime, manga, light novels and visual novels. The default,
     *     and the one in [recommended].
     *   • BCCWJ — news, books, formal writing. The balanced written corpus.
     *   • CEJC — recorded everyday conversation.
     *
     * Seven of them were offered before, and the extra four (CSJ, NWJC,
     * Aozora Bunko, and the ones still waiting for a mirror) bought almost
     * nothing: only the LEADING list's numbers reach a card, an Anki reorder
     * addon or a rarity cut, so every list past the first is a tiebreak for
     * words the leader does not know. Three registers cover what a learner
     * actually chooses between; more of them turn `FrequencyDisplayScreen`
     * into a list to scroll and each one costs another rollup pass.
     *
     * All three ship RANKS, not occurrence counts — 1 is the commonest word
     * and the numbers climb as words get rarer, which is the direction the
     * card's `Frequency` field, the search order and the "Top N" tiers all
     * read (see [com.yomitanmobile.domain.model.FrequencyDirection]). Keep it
     * that way: a count-based list added here would be detected at import and
     * stored honestly, but it would put a five-million on a card next to a
     * four-digit one from another list and mean the opposite by it.
     *
     * A list the user wants anyway still goes in by hand through the
     * "import dictionary from file" path — nothing below is a whitelist for
     * what the app can read, only for what it offers.
     */
    val all: List<DictionaryDownloadInfo> = listOf(
        jitendex,
        wiktionaryEnPl,
        wiktionaryEsEn,
        wiktionaryEsIpa,
        wiktionaryEsEs,
        wiktionaryEnEn,
        wiktionaryEnIpa,
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
            descriptionPl = "Ranking częstotliwości z jpdb.io – anime, manga, light novele, visual novele. Lista domyślna. Im niższa liczba, tym częstsze słowo.",
            descriptionEn = "Frequency ranking from jpdb.io - anime, manga, light novels, visual novels. The default list. Lower number = commoner word.",
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
            descriptionPl = "Częstotliwości z korpusu BCCWJ – prasa, książki, teksty formalne. Uzupełnia JPDB. Im niższa liczba, tym częstsze słowo.",
            descriptionEn = "Frequencies from the BCCWJ corpus - news, books, formal writing. Complements JPDB. Lower number = commoner word.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://raw.githubusercontent.com/Kuuuube/yomitan-dictionaries/d6fde809e3f26eb5aed6d41896f332179044998c/dictionaries/BCCWJ_SUW_LUW_combined.zip",
            fileSize = "~19 MB",
            sha256 = "7d17054735e738d02e9f7f62fdad5d6e592a458abd93301367500d04d0c000c3",
            language = "EN"
        ),
        // Everyday spoken Japanese: NINJAL's conversation corpus, recorded
        // real-life talk. The single best list for "what people actually say"
        // — words like うん / そう / ちょっと rank at the top here and nowhere
        // near it in a written corpus.
        //
        // Third and last of the frequency lists on offer; see the note above
        // `all` for why the catalogue stops at three.
        DictionaryDownloadInfo(
            id = "cejc_freq",
            name = "CEJC (Conversation)",
            descriptionPl = "Częstotliwości z korpusu codziennych rozmów (NINJAL CEJC). Najlepsza lista dla języka mówionego. Im niższa liczba, tym częstsze słowo.",
            descriptionEn = "Frequencies from the Corpus of Everyday Japanese Conversation (NINJAL). The best list for spoken Japanese. Lower number = commoner word.",
            category = DictionaryCategory.FREQUENCY,
            url = "https://raw.githubusercontent.com/forsakeninfinity/CEJC_yomichan_freq_dict/" +
                "854ed02b791a9ca247d0752fb22d34f1ab3c650f/releases/" +
                "Corpus%20of%20Everyday%20Japanese%20Conversation.zip",
            fileSize = "~2 MB",
            sha256 = "273c603b7ea285debfd8b7d41dc326f97b2fd42c2ed94f22e6dafc1c9cbd8a6b",
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
    // Deliberately NOT offered.
    //
    // CC100, Innocent Corpus, Narou, Anime & Drama and the sixteen YouTube
    // lists are distributed through MarvNC's Google Drive folder or catbox,
    // and ALLOWED_DOWNLOAD_HOSTS in DictionaryDownloadManager permits GitHub
    // only. Mirroring them is no longer the blocker though — the catalogue is
    // capped at three lists on purpose (see the note on `all`), so a new one
    // would have to replace a register rather than join the list.
    //
    // CSJ (spontaneous speech), NWJC (web) and Aozora Bunko (literary) were
    // offered and removed for that reason. Their FrequencyCorpus labels stay,
    // so a user who imports one by hand still gets it described properly.
    // ─────────────────────────────────────────────────────────────────────

    fun getByCategory(category: DictionaryCategory): List<DictionaryDownloadInfo> {
        return all.filter { it.category == category }
    }

    /** Everything on offer for one study language, in list order. */
    fun forLanguage(language: AppLanguage): List<DictionaryDownloadInfo> =
        all.filter { it.studyLanguage == language }

    fun getByCategory(
        category: DictionaryCategory,
        language: AppLanguage
    ): List<DictionaryDownloadInfo> =
        all.filter { it.category == category && it.studyLanguage == language }

    /**
     * The first-install bundle for a language.
     *
     * English deliberately leaves kty-en-en out: 120 MB in front of a first
     * run is a bad trade for a fallback most lookups never reach. It is one
     * tap away in the download screen, and the app points there when a word
     * turns out to have no Polish gloss.
     */
    fun recommendedFor(language: AppLanguage): List<DictionaryDownloadInfo> =
        when (language) {
            AppLanguage.JAPANESE -> recommended
            AppLanguage.ENGLISH -> listOf(wiktionaryEnPl, wiktionaryEnIpa)
            AppLanguage.SPANISH -> listOf(wiktionaryEsEn, wiktionaryEsIpa)
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
