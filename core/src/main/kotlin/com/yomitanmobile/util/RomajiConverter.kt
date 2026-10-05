package com.yomitanmobile.util

/**
 * Utility for converting romaji (Latin alphabet) to hiragana.
 * Supports standard Hepburn romanization.
 */
object RomajiConverter {

    /**
     * Convert a romaji string to hiragana.
     * Returns the converted hiragana string.
     */
    fun toHiragana(romaji: String): String {
        val input = expandMacrons(romaji.lowercase().trim())
        if (input.isEmpty()) return ""

        val result = StringBuilder()
        var i = 0

        while (i < input.length) {
            // "nn" is ん — but only when nothing that could open a な-mora
            // follows it. In konnichiwa / annai / sannen the first n closes the
            // previous mora and the second one opens に / な / ね; consuming
            // both gave こんいちわ, あんい, さんえん — none of them words, so
            // romaji search for them found nothing at all.
            if (input[i] == 'n' && i + 1 < input.length && input[i + 1] == 'n') {
                val after = input.getOrNull(i + 2)
                if (after != null && after in "aiueoy") {
                    result.append('ん')
                    i++
                    continue
                }
            }

            // Handle double consonants (っ)
            if (i + 1 < input.length && input[i] == input[i + 1] && input[i] != 'n' && input[i] != 'a' && input[i] != 'i' && input[i] != 'u' && input[i] != 'e' && input[i] != 'o') {
                result.append('っ')
                i++
                continue
            }

            // Try 4-char match
            if (i + 3 < input.length) {
                val four = input.substring(i, i + 4)
                val match4 = ROMAJI_TO_HIRAGANA[four]
                if (match4 != null) {
                    result.append(match4)
                    i += 4
                    continue
                }
            }

            // Try 3-char match
            if (i + 2 < input.length) {
                val three = input.substring(i, i + 3)
                val match3 = ROMAJI_TO_HIRAGANA[three]
                if (match3 != null) {
                    result.append(match3)
                    i += 3
                    continue
                }
            }

            // Try 2-char match
            if (i + 1 < input.length) {
                val two = input.substring(i, i + 2)
                val match2 = ROMAJI_TO_HIRAGANA[two]
                if (match2 != null) {
                    result.append(match2)
                    i += 2
                    continue
                }
            }

            // Try 1-char match
            val one = input.substring(i, i + 1)
            val match1 = ROMAJI_TO_HIRAGANA[one]
            if (match1 != null) {
                result.append(match1)
                i++
                continue
            }

            // Handle 'n' before non-vowel or end of string
            if (input[i] == 'n') {
                if (i + 1 >= input.length || (input[i + 1] !in "aiueoy" && input[i + 1] != 'n')) {
                    result.append('ん')
                    i++
                    continue
                }
            }

            // Pass through non-romaji characters (including spaces, numbers, etc.)
            result.append(input[i])
            i++
        }

        return result.toString()
    }

    /**
     * Check if a string looks like romaji (Latin letters only).
     */
    fun isRomaji(text: String): Boolean {
        return text.all {
            it.isLetter() && it.code < 128 || it in MACRONS || it == ' ' || it == '-'
        }
    }

    /**
     * Long vowels written with a macron or circumflex, the way textbooks and
     * dictionaries print them (gakkou as gakkō, tooi as tōi). No phone keyboard
     * has them, but they are what a user pastes from a page — and untouched
     * they stayed as Latin letters in the middle of the kana, matching nothing.
     */
    private fun expandMacrons(input: String): String {
        if (input.none { it in MACRONS }) return input
        return buildString(input.length + 4) {
            for (ch in input) {
                when (ch) {
                    'ā', 'â' -> append("aa")
                    'ī', 'î' -> append("ii")
                    'ū', 'û' -> append("uu")
                    'ē', 'ê' -> append("ee")
                    'ō', 'ô' -> append("ou")
                    else -> append(ch)
                }
            }
        }
    }

    private const val MACRONS = "\u0101\u012B\u016B\u0113\u014D\u00E2\u00EE\u00FB\u00EA\u00F4"

    private val ROMAJI_TO_HIRAGANA = mapOf(
        // Vowels
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",

        // K-row
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",

        // S-row
        "sa" to "さ", "si" to "し", "shi" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",

        // T-row
        "ta" to "た", "ti" to "ち", "chi" to "ち", "tsu" to "つ", "tu" to "つ", "te" to "て", "to" to "と",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",

        // N-row
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "nn" to "ん",

        // H-row
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "fu" to "ふ", "he" to "へ", "ho" to "ほ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",

        // M-row
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",

        // Y-row
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",

        // R-row
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",

        // W-row
        "wa" to "わ", "wi" to "ゐ", "we" to "ゑ", "wo" to "を",

        // N (standalone handled separately)

        // G-row (dakuten)
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",

        // Z-row
        "za" to "ざ", "zi" to "じ", "ji" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",

        // D-row
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",

        // B-row
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",

        // P-row (handakuten)
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",

        // Special
        "-" to "ー"
    )
}
