package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.CardProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The parts of an `.apkg` that are pure data: the JSON blobs Anki keeps in its
 * `col` row, and the two derived values every note carries.
 *
 * Kept apart from the SQLite writing so all of it can be asserted on in a JVM
 * test — the file format is undocumented and the only defence against getting
 * a field wrong is to pin what we write.
 *
 * Schema 11 on purpose. It is the old collection layout, and it is the one
 * every Anki and AnkiDroid still imports; the newer schema keeps note types in
 * protobuf columns that a third-party writer has no business generating.
 */
internal object ApkgSchema {

    const val SCHEMA_VERSION = 11

    /** Anki's field separator inside `notes.flds`. */
    const val FIELD_SEPARATOR = ''

    /** Queue value that means "suspended" on a card row. */
    const val QUEUE_SUSPENDED = -1
    const val QUEUE_NEW = 0

    /**
     * The first field's checksum, as Anki computes it: the first 8 hex digits
     * of the SHA-1 of the stripped field, read as a number.
     *
     * Anki uses it for duplicate detection in the browser. A wrong value does
     * not corrupt anything, but it makes "find duplicates" blind to the notes
     * we wrote, which is precisely the audience for a deck built in bulk.
     */
    fun fieldChecksum(field: String): Long {
        val text = stripHtml(field)
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 8).toLong(16)
    }

    /**
     * What Anki calls a field's "sort" text: the markup taken out, so the
     * browser's sort column and duplicate check compare words rather than
     * tags. Deliberately blunt — this is not a parser, and the fields we write
     * carry our own generated HTML, not arbitrary documents.
     */
    fun stripHtml(value: String): String = value
        .replace(Regex("\\[sound:[^]]*]"), "")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    /** A note's globally unique id. Anki only requires that it be unique. */
    fun guid(seed: String, salt: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$seed|$salt".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** `notes.flds`: the field values joined by the separator. */
    fun joinFields(fields: Array<String>): String = fields.joinToString(FIELD_SEPARATOR.toString())

    /**
     * The note type, in the shape the `col.models` map wants.
     *
     * The templates and CSS are the app's own — the same strings the provider
     * path writes — so a deck imported from a file looks exactly like one
     * written straight into AnkiDroid.
     */
    fun model(
        modelId: Long,
        profile: CardProfile,
        css: String,
        frontTemplate: String,
        backTemplate: String,
        deckId: Long,
        now: Long
    ): JSONObject {
        val fields = JSONArray()
        profile.fieldNames.forEachIndexed { index, name ->
            fields.put(
                JSONObject()
                    .put("name", name)
                    .put("ord", index)
                    .put("sticky", false)
                    .put("rtl", false)
                    .put("font", "Arial")
                    .put("size", 20)
                    .put("media", JSONArray())
            )
        }

        val template = JSONObject()
            .put("name", "Card 1")
            .put("ord", 0)
            .put("qfmt", frontTemplate)
            .put("afmt", backTemplate)
            .put("bqfmt", "")
            .put("bafmt", "")
            .put("did", JSONObject.NULL)
            .put("bfont", "")
            .put("bsize", 0)

        return JSONObject()
            .put("id", modelId)
            .put("name", profile.modelName)
            .put("type", 0)
            .put("mod", now / 1000)
            .put("usn", -1)
            .put("sortf", 0)
            .put("did", deckId)
            .put("tmpls", JSONArray().put(template))
            .put("flds", fields)
            .put("css", css)
            .put("latexPre", "")
            .put("latexPost", "")
            .put("latexsvg", false)
            .put("req", JSONArray().put(JSONArray().put(0).put("any").put(JSONArray().put(0))))
            .put("tags", JSONArray())
            .put("vers", JSONArray())
    }

    /** One deck plus the default deck Anki insists on finding at id 1. */
    fun decks(deckId: Long, deckName: String, now: Long): JSONObject {
        fun deck(id: Long, name: String) = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("mod", now / 1000)
            .put("usn", -1)
            .put("lrnToday", JSONArray().put(0).put(0))
            .put("revToday", JSONArray().put(0).put(0))
            .put("newToday", JSONArray().put(0).put(0))
            .put("timeToday", JSONArray().put(0).put(0))
            .put("collapsed", false)
            .put("browserCollapsed", false)
            .put("desc", "")
            .put("dyn", 0)
            .put("conf", 1)
            .put("extendNew", 0)
            .put("extendRev", 0)

        return JSONObject()
            .put("1", deck(1L, "Default"))
            .put(deckId.toString(), deck(deckId, deckName))
    }

    /** The one deck config every deck here points at. */
    fun deckConfig(now: Long): JSONObject {
        val config = JSONObject()
            .put("id", 1)
            .put("name", "Default")
            .put("mod", now / 1000)
            .put("usn", -1)
            .put("maxTaken", 60)
            .put("autoplay", true)
            .put("timer", 0)
            .put("replayq", true)
            .put(
                "new",
                JSONObject()
                    .put("bury", false)
                    .put("delays", JSONArray().put(1).put(10))
                    .put("initialFactor", 2500)
                    .put("ints", JSONArray().put(1).put(4).put(0))
                    .put("order", 1)
                    .put("perDay", 20)
            )
            .put(
                "rev",
                JSONObject()
                    .put("bury", false)
                    .put("ease4", 1.3)
                    .put("ivlFct", 1.0)
                    .put("maxIvl", 36500)
                    .put("perDay", 200)
                    .put("hardFactor", 1.2)
            )
            .put(
                "lapse",
                JSONObject()
                    .put("delays", JSONArray().put(10))
                    .put("leechAction", 1)
                    .put("leechFails", 8)
                    .put("minInt", 1)
                    .put("mult", 0.0)
            )
            .put("dyn", 0)
            .put("newMix", 0)
            .put("newPerDayMinimum", 0)
            .put("interdayLearningMix", 0)
            .put("reviewOrder", 0)
        return JSONObject().put("1", config)
    }

    /** Collection-wide config. Only the fields Anki reads on import matter. */
    fun collectionConfig(deckId: Long): JSONObject = JSONObject()
        .put("nextPos", 1)
        .put("estTimes", true)
        .put("activeDecks", JSONArray().put(deckId))
        .put("sortType", "noteFld")
        .put("timeLim", 0)
        .put("sortBackwards", false)
        .put("addToCur", true)
        .put("curDeck", deckId)
        .put("newBury", true)
        .put("newSpread", 0)
        .put("dueCounts", true)
        .put("curModel", null)
        .put("collapseTime", 1200)
}
