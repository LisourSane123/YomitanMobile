package com.yomitanmobile.domain.model

/**
 * What each frequency list was counted from.
 *
 * The installed name comes out of the dictionary's own `index.json` — "JPDBv2",
 * "BCCWJ_SUW_LUW_combined", "CEJC-LUW" — which says who made it and nothing
 * about what it counted. Since the whole point of having several is that they
 * disagree (a word common in conversation can be rare in print), the screen
 * that ranks them has to say which is which.
 *
 * Matching is by substring on the lowercased name, so it survives the version
 * suffixes and separators these files carry.
 */
object FrequencyCorpus {

    data class Label(val pl: String, val en: String, val ja: String = en)

    private val BY_KEY: List<Pair<String, Label>> = listOf(
        "jpdb" to Label("anime, visual novele, light novele", "anime, visual novels, light novels", "アニメ、ビジュアルノベル、ライトノベル"),
        "wordfreq" to Label("wiele źródeł: Wikipedia, książki, napisy, wiadomości", "many sources: Wikipedia, books, subtitles, news", "多くの出典: Wikipedia、本、字幕、ニュース"),
        "opensubtitles" to Label("napisy filmowe i serialowe — język mówiony", "film and TV subtitles — spoken language", "映画・ドラマの字幕 — 話し言葉"),
        "bccwj" to Label("prasa, książki, teksty formalne", "news, books, formal writing", "ニュース、本、書き言葉"),
        "cejc" to Label("rozmowy codzienne", "everyday conversation", "日常会話"),
        "csj" to Label("mowa spontaniczna, wykłady", "spontaneous speech, lectures", "自発的な話し言葉、講演"),
        "nwjc" to Label("internet: blogi, fora", "the web: blogs, forums", "ウェブ: ブログ、掲示板"),
        "aozora" to Label("literatura klasyczna", "classical literature", "古典文学"),
        "innocent" to Label("light novele", "light novels", "ライトノベル"),
        "netflix" to Label("napisy filmowe i serialowe", "film and TV subtitles", "映画・ドラマの字幕"),
        "wikipedia" to Label("Wikipedia", "Wikipedia"),
        "novel" to Label("powieści", "novels", "小説"),
        "anime" to Label("anime", "anime", "アニメ"),
        "drama" to Label("dramy", "TV drama", "ドラマ"),
        "youtube" to Label("YouTube", "YouTube"),
        "vn" to Label("visual novele", "visual novels", "ビジュアルノベル")
    )

    /** What this list counted, or null when the name says nothing we know. */
    fun labelFor(dictionaryName: String): Label? {
        val name = dictionaryName.lowercase()
        return BY_KEY.firstOrNull { (key, _) -> key in name }?.second
    }

    /** "CEJC-LUW — rozmowy codzienne", or the bare name when unknown. */
    fun describe(dictionaryName: String, isEnglish: Boolean): String {
        val label = labelFor(dictionaryName) ?: return dictionaryName
        return "$dictionaryName — ${if (isEnglish) label.en else label.pl}"
    }
}
