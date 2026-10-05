package com.yomitanmobile.kindle

import com.yomitanmobile.data.anki.AnkiCollectionMatch
import com.yomitanmobile.data.anki.AnkiNoteFieldIndexer
import com.yomitanmobile.domain.model.CardProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The few AnkiConnect actions this needs. */
class AnkiConnect(private val endpoint: String) {

    /** Whether AnkiConnect answers at all — Anki running, with the add-on. */
    fun isUp(): Boolean = runCatching {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.outputStream.use { it.write("""{"action":"version","version":6}""".toByteArray()) }
        JSONObject(connection.inputStream.bufferedReader().readText()).has("result")
    }.getOrDefault(false)

    fun call(action: String, params: JSONObject = JSONObject()): Any? {
        val body = JSONObject().put("action", action).put("version", 6).put("params", params)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 120_000
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val error = response.opt("error")
        if (error != null && error != JSONObject.NULL) throw IllegalStateException("$action: $error")
        return response.opt("result")
    }

    /**
     * Every note of the collection through the same indexer the phone's
     * scan uses. Fields are rejoined with Anki's own separator, which is
     * the string [AnkiNoteFieldIndexer.collectKeysFromNote] expects.
     */
    fun collectionIndex(): AnkiCollectionMatch.Index {
        val ids = call("findNotes", JSONObject().put("query", "deck:*")) as JSONArray
        val keys = HashSet<String>(1 shl 14)
        val all = (0 until ids.length()).map { ids.getLong(it) }
        for (chunk in all.chunked(500)) {
            val infos = call("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
            for (i in 0 until infos.length()) {
                val fields = infos.getJSONObject(i).optJSONObject("fields") ?: continue
                val ordered = fields.keys().asSequence()
                    .map { fields.getJSONObject(it) }
                    .sortedBy { it.optInt("order") }
                    .joinToString("") { it.optString("value") }
                AnkiNoteFieldIndexer.collectKeysFromNote(ordered, keys)
            }
        }
        return AnkiCollectionMatch.Index(keys, all.size, available = true)
    }

    /**
     * The profile's note type. An existing one with the right fields is
     * used as it is — its styling may be what the phone synced — and a
     * new one is only created when the collection has none.
     */
    fun ensureModel(profile: CardProfile, css: String, front: String, back: String): String {
        val name = profile.modelName
        val names = call("modelNames") as JSONArray
        if ((0 until names.length()).any { names.getString(it) == name }) {
            val fields = call("modelFieldNames", JSONObject().put("modelName", name)) as JSONArray
            val have = (0 until fields.length()).map { fields.getString(it) }
            check(have == profile.fieldNames.toList()) {
                "note type $name has fields $have, expected ${profile.fieldNames.toList()}"
            }
            return name
        }
        call(
            "createModel",
            JSONObject()
                .put("modelName", name)
                .put("inOrderFields", JSONArray(profile.fieldNames.toList()))
                .put("css", css)
                .put(
                    "cardTemplates",
                    JSONArray().put(JSONObject().put("Name", "Card 1").put("Front", front).put("Back", back))
                )
        )
        return name
    }

    /**
     * The fonts the phone's random-font setting put on its most recent
     * cards (`<span style="font-family: 'X'">` in Front).
     */
    fun recentFrontFonts(): Set<String> {
        val ids = call("findNotes", JSONObject().put("query", "\"note:${CardProfile.JAPANESE.modelName}*\" -tag:from_kindle -tag:kindle")) as JSONArray
        val recent = (0 until ids.length()).map { ids.getLong(it) }.sorted().takeLast(300)
        if (recent.isEmpty()) return emptySet()
        val infos = call("notesInfo", JSONObject().put("notes", JSONArray(recent))) as JSONArray
        return (0 until infos.length()).flatMapTo(LinkedHashSet()) { i ->
            val front = infos.getJSONObject(i).optJSONObject("fields")?.optJSONObject("Front")?.optString("value").orEmpty()
            FONT.findAll(front).map { it.groupValues[1] }.toList()
        }
    }

    fun storeMedia(file: File) {
        call("storeMediaFile", JSONObject().put("filename", file.name).put("path", file.absolutePath))
    }

    /**
     * AutoReorder's `reorder_cards`, over AnkiConnect. The add-on only runs
     * when Anki starts or from its Tools menu, and AnkiConnect cannot call
     * into another add-on, so the ten lines are restated here — same
     * search, same field, same stable sort (cards in their current order,
     * an empty or non-numeric field sorts last), and the same
     * `reposition_new_cards(start 0, step 1, shift_existing)`. Positions
     * are written with `setSpecificValueOfCard`, which goes through
     * `update_card`, so the change syncs like any other.
     *
     * Returns how many cards changed position; 0 when the order already
     * holds, which is also when the add-on leaves the collection alone.
     */
    fun reorder(config: ReorderConfig): Int {
        val ids = call("findCards", JSONObject().put("query", config.search)) as JSONArray
        val cards = cardsInfo((0 until ids.length()).map { ids.getLong(it) })
            .sortedWith(compareBy<CardPosition> { it.due }.thenBy { it.id })
        val byFrequency = compareBy<CardPosition> { it.frequency(config.field) }
        val sorted = cards.sortedWith(if (config.reverse) byFrequency.reversed() else byFrequency)
        if (sorted.map { it.id } == cards.map { it.id }) return 0

        val updates = ArrayList<Pair<Long, Long>>()
        if (config.shiftExisting) {
            // Anki moves every OTHER new card at or past the start out of the way.
            val others = call("findCards", JSONObject().put("query", "is:new -(${config.search})")) as JSONArray
            cardsInfo((0 until others.length()).map { others.getLong(it) })
                .filter { it.due >= 0 }
                .forEach { updates += it.id to it.due + sorted.size }
        }
        sorted.forEachIndexed { position, card ->
            if (card.due != position.toLong()) updates += card.id to position.toLong()
        }
        for (chunk in updates.chunked(200)) {
            val actions = JSONArray()
            for ((card, due) in chunk) {
                actions.put(
                    JSONObject().put("action", "setSpecificValueOfCard").put(
                        "params",
                        JSONObject().put("card", card).put("keys", JSONArray(listOf("due")))
                            .put("newValues", JSONArray(listOf(due)))
                    )
                )
            }
            call("multi", JSONObject().put("actions", actions))
        }
        return sorted.withIndex().count { (position, card) -> card.due != position.toLong() }
    }

    /**
     * The words among [words] that more than one note now holds, by the
     * rule every duplicate check here uses: a whole field, indexed by
     * [AnkiNoteFieldIndexer], found through the same search the phone's
     * live check runs.
     */
    fun duplicatesOf(words: List<Pair<String, String>>): List<String> = words.mapNotNull { (word, reading) ->
        val search = AnkiCollectionMatch.liveSearch(listOf(word), reading) ?: return@mapNotNull null
        val ids = call("findNotes", JSONObject().put("query", search)) as JSONArray
        val key = AnkiNoteFieldIndexer.normalizeKey(word)
        val holding = (0 until ids.length()).map { ids.getLong(it) }.chunked(500).sumOf { chunk ->
            val infos = call("notesInfo", JSONObject().put("notes", JSONArray(chunk))) as JSONArray
            (0 until infos.length()).count { i ->
                val fields = infos.getJSONObject(i).optJSONObject("fields") ?: return@count false
                val ordered = fields.keys().asSequence().map { fields.getJSONObject(it) }
                    .sortedBy { it.optInt("order") }.joinToString("\u001f") { it.optString("value") }
                val keys = HashSet<String>()
                AnkiNoteFieldIndexer.collectKeysFromNote(ordered, keys)
                key in keys
            }
        }
        word.takeIf { holding > 1 }
    }

    /** How many cards a search matches. */
    fun countCards(query: String): Int =
        (call("findCards", JSONObject().put("query", query)) as JSONArray).length()

    private fun cardsInfo(ids: List<Long>): List<CardPosition> = ids.chunked(500).flatMap { chunk ->
        val infos = call("cardsInfo", JSONObject().put("cards", JSONArray(chunk))) as JSONArray
        (0 until infos.length()).map { i ->
            val info = infos.getJSONObject(i)
            val fields = info.optJSONObject("fields") ?: JSONObject()
            CardPosition(
                id = info.getLong("cardId"),
                due = info.getLong("due"),
                fields = fields.keys().asSequence().associateWith { fields.getJSONObject(it).optString("value") }
            )
        }
    }

    fun ensureDeck(deck: String) {
        call("createDeck", JSONObject().put("deck", deck))
    }

    /** Null when added, otherwise the reason AnkiConnect gave. */
    fun addNote(deck: String, model: String, fields: Map<String, String>, tags: List<String>): String? =
        try {
            call(
                "addNote",
                JSONObject().put(
                    "note",
                    JSONObject()
                        .put("deckName", deck)
                        .put("modelName", model)
                        .put("fields", JSONObject(fields))
                        .put("tags", JSONArray(tags))
                )
            )
            null
        } catch (e: Exception) {
            e.message
        }
}

private data class CardPosition(val id: Long, val due: Long, val fields: Map<String, String>) {
    /** AutoReorder's get_frequency: the field as an int, anything else last. */
    fun frequency(field: String): Long = fields[field]?.trim()?.toLongOrNull() ?: Long.MAX_VALUE
}

/** `font-family: 'X'` in a Front field, which is how the phone's random fonts land on a card. */
private val FONT = Regex("font-family: '([^']+)'")

/** What AutoReorder is configured to do, read from the add-on itself. */
data class ReorderConfig(
    val search: String,
    val field: String,
    val reverse: Boolean,
    val shiftExisting: Boolean
)

/**
 * AutoReorder's settings, from the add-on's `meta.json` (where Anki keeps
 * the user's edits) over its `config.json` (the shipped defaults). Null
 * when the add-on is absent or disabled — then nothing is reordered,
 * exactly as Anki itself would not.
 */
fun reorderConfig(dir: File?): ReorderConfig? {
    dir ?: return null
    val meta = File(dir, "meta.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    if (meta.optBoolean("disabled", false)) return null
    val defaults = File(dir, "config.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) } ?: JSONObject()
    val user = meta.optJSONObject("config") ?: JSONObject()
    fun value(key: String): Any? = if (user.has(key)) user.get(key) else defaults.opt(key)
    val search = value("search_to_sort") as? String ?: return null
    return ReorderConfig(
        search = search,
        field = value("sort_field") as? String ?: "Frequency",
        reverse = value("sort_reverse") as? Boolean ?: false,
        shiftExisting = value("shift_existing") as? Boolean ?: true
    )
}
