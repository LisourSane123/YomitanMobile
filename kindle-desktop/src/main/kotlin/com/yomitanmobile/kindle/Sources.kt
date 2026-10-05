package com.yomitanmobile.kindle

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.yomitanmobile.data.local.dao.FrequencyUpdate
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.data.mapper.toDomain
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.readCardStylePreferences
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.WordEntry
import org.json.JSONObject
import java.io.File

/**
 * The term dictionary, the frequency list and the pitch dictionary, read the
 * way the phone stores them — shared by the sync and the refresh so a
 * refreshed card is built from exactly the data a new one would be.
 *
 * Nothing is imported into a database: a Kindle run looks up a few hundred
 * words, so the zip is streamed past them, which costs seconds and no disk.
 */
class Dictionary private constructor(
    private val parser: YomitanDictionaryParser,
    private val dictZip: File,
    private val ranks: Map<String, List<Pair<String, Int>>>,
    /** Pitch by written form, as DictionaryDao's updatePitchAccent keys it. */
    val pitch: Map<String, String>
) {
    fun rankOf(expression: String, reading: String): Int =
        ranks[expression].orEmpty()
            .filter { it.first.isEmpty() || it.first == reading }
            .minOfOrNull { it.second } ?: 0

    /** Every entry whose written form is in [expressions] or reading in [readings]. */
    suspend fun collect(expressions: Set<String>, readings: Set<String>): List<WordEntry> {
        val out = ArrayList<WordEntry>()
        parser.parseFromZipStreaming(
            inputStream = dictZip.inputStream().buffered(),
            onBatch = { entries: List<DictionaryEntry>, _ ->
                for (entry in entries) {
                    if (entry.expression !in expressions && entry.reading !in readings) continue
                    // One list installed here, so it is the leading one: its
                    // number is what the card's Frequency field carries.
                    val rank = rankOf(entry.expression, entry.reading)
                    val domain = entry.copy(frequency = rank).toDomain()
                    out += domain.copy(
                        frequencyValue = if (rank > 0) rank.toString() else "",
                        pitchAccent = domain.pitchAccent.ifBlank { pitch[entry.expression].orEmpty() }
                    )
                }
            }
        )
        return out
    }

    companion object {
        suspend fun load(parser: YomitanDictionaryParser, config: Config): Dictionary {
            val dict = requireNotNull(config.dictionary) { "no dictionary configured" }
            val ranks = HashMap<String, MutableList<Pair<String, Int>>>()
            config.frequency?.takeIf { it.isFile }?.let { zip ->
                parser.parseFromZipStreaming(
                    inputStream = zip.inputStream().buffered(),
                    onBatch = { _, _ -> },
                    onMetaBatch = { updates: List<FrequencyUpdate>, _ ->
                        for (u in updates) {
                            if (u.frequency <= 0) continue
                            ranks.getOrPut(u.expression) { ArrayList(2) }.add(u.reading.orEmpty() to u.frequency)
                        }
                    }
                )
            }
            return Dictionary(parser, dict, ranks, loadPitch(parser, config.pitch))
        }

        /** Pitch positions by written form, as DictionaryDao's updatePitchAccent stores them. */
        suspend fun loadPitch(parser: YomitanDictionaryParser, zip: File?): Map<String, String> {
            val pitch = HashMap<String, String>()
            zip?.takeIf { it.isFile }?.let {
                parser.parseFromZipStreaming(
                    inputStream = it.inputStream().buffered(),
                    onBatch = { _, _ -> },
                    onMetaBatch = { _, pitches -> pitch.putAll(pitches) }
                )
            }
            return pitch
        }

        /** KANJIDIC rows for [wanted], for the card's kanji breakdown. */
        suspend fun loadKanji(parser: YomitanDictionaryParser, zip: File?, wanted: Set<String>): Map<String, KanjiEntry> {
            val kanji = HashMap<String, KanjiEntry>()
            if (wanted.isEmpty()) return kanji
            zip?.takeIf { it.isFile }?.let {
                parser.parseFromZipStreaming(
                    inputStream = it.inputStream().buffered(),
                    onBatch = { _, _ -> },
                    onKanjiBatch = { batch, _ -> batch.filter { k -> k.kanji in wanted }.forEach { k -> kanji[k.kanji] = k } }
                )
            }
            return kanji
        }
    }
}

/**
 * The phone's card style. An app backup's `settings.json` is the real thing —
 * it is read through [readCardStylePreferences], the one path the phone itself
 * uses, so every validation on it applies here too. Without one, the fonts are
 * read off the cards the phone wrote most recently: random fonts are the only
 * style setting that lands in a note's FIELDS (everything else is the note
 * type's CSS, which the phone keeps in sync itself).
 */
fun phoneStyle(config: Config, anki: AnkiConnect, log: (String) -> Unit): CardStylePreferences {
    val settings = config.settingsJson
    if (settings.isFile) {
        log("style: from ${settings.path}")
        return readCardStylePreferences(preferencesFromBackup(settings.readText()))
    }
    val fonts = runCatching { anki.recentFrontFonts() }.getOrDefault(emptySet())
    log("style: no phone settings.json, fonts read off recent cards: $fonts")
    return if (fonts.isEmpty()) CardStylePreferences()
    else CardStylePreferences(randomFontsEnabled = true, randomFonts = fonts)
}

/** BackupManager's `{ key: { "t": type, "v": value } }` back into DataStore preferences. */
fun preferencesFromBackup(text: String): Preferences {
    val root = JSONObject(text)
    val prefs = mutablePreferencesOf()
    for (name in root.keys()) {
        val entry = root.optJSONObject(name) ?: continue
        runCatching {
            when (entry.optString("t")) {
                "bool" -> prefs[booleanPreferencesKey(name)] = entry.getBoolean("v")
                "int" -> prefs[intPreferencesKey(name)] = entry.getInt("v")
                "long" -> prefs[longPreferencesKey(name)] = entry.getLong("v")
                "float" -> prefs[floatPreferencesKey(name)] = entry.getDouble("v").toFloat()
                "double" -> prefs[doublePreferencesKey(name)] = entry.getDouble("v")
                "string" -> prefs[stringPreferencesKey(name)] = entry.getString("v")
                "stringset" -> prefs[stringSetPreferencesKey(name)] =
                    entry.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            }
        }
    }
    return prefs
}

/**
 * Copies the newest app backup's settings.json off the phone over adb, so
 * Kindle cards wear the phone's fonts and colours. A phone that is not plugged
 * in is the normal case during a Kindle run, so this never fails a run — it
 * says which step did not happen and keeps the last pull, which is still the
 * phone's style.
 */
fun pullPhoneSettings(config: Config, log: (String) -> Unit): Boolean {
    val adb = Platform.adb() ?: run {
        log("phone settings: no adb (install Android platform-tools)")
        return false
    }
    if (Platform.run(adb, "get-state", timeoutSeconds = 10) == null) {
        // A plugged-in phone that has not accepted this computer is listed as
        // "unauthorized" — a different fix from "not plugged in".
        if (Platform.run(adb, "devices", timeoutSeconds = 10)?.contains("unauthorized") == true) {
            log("phone settings: the phone is plugged in but has not allowed USB debugging — accept the prompt on its screen")
        } else {
            log("phone settings: no phone over adb, keeping ${if (config.settingsJson.isFile) "the last pull" else "none"}")
        }
        return false
    }
    val backups = "/sdcard/Android/data/com.yomitanmobile/files/yomitan_backups"
    val latest = Platform.run(adb, "shell", "ls -1d $backups/*/ 2>/dev/null | sort | tail -1", timeoutSeconds = 20)
        ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: run {
        log("phone settings: no backup in $backups — make one in the app (Ustawienia → kopia zapasowa)")
        return false
    }
    val partial = File(config.settingsJson.path + ".new")
    config.settingsJson.parentFile.mkdirs()
    if (Platform.run(adb, "pull", "$latest/settings.json", partial.path, timeoutSeconds = 30) == null || !partial.isFile) {
        log("phone settings: $latest/settings.json could not be pulled")
        partial.delete()
        return false
    }
    partial.copyTo(config.settingsJson, overwrite = true)
    partial.delete()
    log("phone settings refreshed from $latest")
    return true
}
