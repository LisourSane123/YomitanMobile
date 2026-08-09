package com.yomitanmobile.data.text

/**
 * File kinds the text scanner can read. Detection is by extension first
 * (a content-provider Uri often carries no usable MIME type) with a
 * content sniff as the fallback, so a subtitle file saved as `.txt` still
 * gets its timestamps stripped.
 */
enum class TextFileFormat(val extensions: List<String>, val label: String) {
    SRT(listOf("srt"), "SubRip (.srt)"),
    ASS(listOf("ass", "ssa"), "Advanced SubStation (.ass)"),
    VTT(listOf("vtt"), "WebVTT (.vtt)"),
    EPUB(listOf("epub"), "EPUB"),
    PDF(listOf("pdf"), "PDF"),
    PLAIN(listOf("txt", "text", "md", "csv", "lrc"), "Tekst (.txt)");

    companion object {
        fun fromFileName(name: String): TextFileFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isBlank()) return null
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}
