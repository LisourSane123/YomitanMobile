package com.yomitanmobile.kindle

import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * What a run needs to know that is not on the Kindle: which dictionaries make
 * the cards, which deck they go to, how Anki is reached. Stored as a plain
 * properties file in [Platform.dataDir], written by the window's settings
 * dialog and read by both the window and the command line.
 *
 * The defaults are the shell version's — `~/yomitan-dicts/…`, the same
 * environment variables — so a machine that ran it works without a visit to
 * the settings.
 */
data class Config(
    /** Term dictionary (Jitendex). The only thing a run cannot do without. */
    val dictionary: File?,
    /** Frequency list; its rank is the card's Frequency field. */
    val frequency: File?,
    val pitch: File?,
    val kanji: File?,
    val deck: String,
    val ankiConnect: String,
    /** AnkiWeb sync before (the duplicate check needs a current collection) and after. */
    val syncAnkiWeb: Boolean,
    /**
     * The language of the grammar and usage labels on a card's back. The
     * phone writes them in its interface language; Polish here unless the
     * system says English.
     */
    val englishLabels: Boolean,
    /** The window's and the messages' language: "pl" or "en". */
    val language: String,
    /** VOICEVOX as its own downloader lays it out (models/vvms, dict, onnxruntime). */
    val voicevox: File,
    /** A folder of the user's own recordings, used before any voice. */
    val audioArchive: File,
    /** Kanji alive's recordings (install-native-audio). */
    val nativeAudio: File
) {
    val settingsJson: File get() = File(Platform.dataDir, "settings.json")

    fun save() {
        val p = Properties()
        dictionary?.let { p["dictionary"] = it.path }
        frequency?.let { p["frequency"] = it.path }
        pitch?.let { p["pitch"] = it.path }
        kanji?.let { p["kanji"] = it.path }
        p["deck"] = deck
        p["ankiConnect"] = ankiConnect
        p["syncAnkiWeb"] = syncAnkiWeb.toString()
        p["englishLabels"] = englishLabels.toString()
        p["language"] = language
        p["voicevox"] = voicevox.path
        p["audioArchive"] = audioArchive.path
        p["nativeAudio"] = nativeAudio.path
        FILE.parentFile.mkdirs()
        FILE.outputStream().use { p.store(it, "Kindle → Anki settings") }
    }

    /** What is missing before a run can start, in the user's words; empty when ready. */
    fun problems(): List<String> = buildList {
        if (dictionary == null || !dictionary.isFile) {
            add(tr("Nie wskazano słownika (np. Jitendex .zip) — Ustawienia.", "No dictionary chosen (e.g. Jitendex .zip) — open Settings."))
        }
    }

    companion object {
        val FILE: File get() = File(Platform.dataDir, "config.properties")

        fun load(): Config {
            val p = Properties()
            if (FILE.isFile) FILE.inputStream().use { p.load(it) }
            val dicts = File(System.getProperty("user.home"), "yomitan-dicts")
            fun file(key: String, env: String, default: String?): File? =
                (p.getProperty(key) ?: System.getenv(env) ?: default?.let { File(dicts, it).path })
                    ?.let(::File)?.takeIf { p.getProperty(key) != null || System.getenv(env) != null || it.isFile }
            val data = Platform.dataDir
            val language = p.getProperty("language") ?: if (Locale.getDefault().language == "pl") "pl" else "en"
            return Config(
                dictionary = file("dictionary", "KINDLE_SYNC_DICT", "jitendex-yomitan.zip"),
                frequency = file("frequency", "KINDLE_SYNC_FREQ", "JPDB_v2.2_Frequency.zip"),
                pitch = file("pitch", "KINDLE_SYNC_PITCH", "kanjium_pitch_accents.zip"),
                kanji = file("kanji", "KINDLE_SYNC_KANJI", "KANJIDIC_english.zip"),
                deck = p.getProperty("deck") ?: "Japanese",
                ankiConnect = p.getProperty("ankiConnect") ?: System.getenv("KINDLE_SYNC_ANKI") ?: "http://127.0.0.1:8765",
                syncAnkiWeb = p.getProperty("syncAnkiWeb")?.toBooleanStrictOrNull() ?: true,
                englishLabels = p.getProperty("englishLabels")?.toBooleanStrictOrNull() ?: (language != "pl"),
                language = language,
                voicevox = File(p.getProperty("voicevox") ?: System.getenv("KINDLE_SYNC_VOICEVOX") ?: "$data/voicevox/core"),
                audioArchive = File(p.getProperty("audioArchive") ?: System.getenv("KINDLE_SYNC_AUDIO") ?: "$data/audio-archive"),
                nativeAudio = File(p.getProperty("nativeAudio") ?: System.getenv("KINDLE_SYNC_NATIVE_AUDIO") ?: "$data/native-audio/kanjialive")
            )
        }
    }
}

/**
 * The window and the messages speak the configured language — Polish or
 * English, the two the phone app speaks. Set once at start from [Config.language].
 */
var polishUi: Boolean = Locale.getDefault().language == "pl"

fun tr(pl: String, en: String): String = if (polishUi) pl else en
