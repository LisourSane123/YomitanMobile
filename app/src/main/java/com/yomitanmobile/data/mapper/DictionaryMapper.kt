package com.yomitanmobile.data.mapper

import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.domain.model.ExamplePair
import com.yomitanmobile.domain.model.KanjiInfo
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.util.NotesExtractor
import com.yomitanmobile.util.UsageTagExtractor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private val examplePairListSerializer = ListSerializer(ExamplePair.serializer())

fun DictionaryEntry.toDomain(): WordEntry {
    val defList = try {
        json.decodeFromString<List<String>>(definition)
    } catch (e: Exception) {
        listOf(definition)
    }

    val examples = if (examplesJson.isBlank()) {
        emptyList()
    } else {
        try {
            json.decodeFromString(examplePairListSerializer, examplesJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Peel any "(usually kana) …" / "(formal) …" prefixes off the gloss text
    // so the UI can render them as a chip instead of leaving them inline.
    // Conservative — unrecognized leading parens pass through untouched.
    val (usageTags, cleanedDefs) = UsageTagExtractor.extractAll(defList)

    // Pull cross-references ("see also X", "cf. Y", "→ Z") and explicit
    // "Note: …" prefixes out of the glosses. They render in a separate
    // card at the bottom of the detail screen so the meaning column stays
    // focused on the actual gloss.
    val notesResult = NotesExtractor.extractAll(cleanedDefs)

    return WordEntry(
        id = id,
        expression = expression,
        reading = reading,
        definitions = notesResult.definitions,
        frequency = frequency,
        pitchAccent = pitchAccent,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = dictionaryName,
        exampleSentence = exampleSentence,
        exampleSentenceTranslation = exampleSentenceTranslation,
        audioFile = audioFile,
        jlptLevel = jlptLevel,
        examples = examples,
        usageTags = usageTags,
        notes = notesResult.notes
    )
}

/**
 * Map a stored [KanjiEntry] into the UI-facing [KanjiInfo]. The `meanings`
 * column is a JSON string list (same encoding the Anki export reads); we
 * decode it here, falling back to a lenient bracket-split for older rows
 * written before the serializer was consistent.
 */
fun KanjiEntry.toKanjiInfo(): KanjiInfo {
    val meaningList = if (meanings.isBlank()) {
        emptyList()
    } else {
        try {
            json.decodeFromString(ListSerializer(String.serializer()), meanings)
                .map { it.trim() }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            meanings.removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                .filter { it.isNotBlank() }
        }
    }
    return KanjiInfo(
        kanji = kanji,
        onyomi = com.yomitanmobile.util.KanjiReadingFormatter.format(onyomi),
        kunyomi = com.yomitanmobile.util.KanjiReadingFormatter.format(kunyomi),
        meanings = meaningList
    )
}

fun WordEntry.toEntity(): DictionaryEntry {
    val encodedExamples = if (examples.isEmpty()) {
        ""
    } else {
        json.encodeToString(examplePairListSerializer, examples)
    }
    return DictionaryEntry(
        id = id,
        expression = expression,
        reading = reading,
        definition = json.encodeToString(
            ListSerializer(String.serializer()), definitions
        ),
        frequency = frequency,
        pitchAccent = pitchAccent,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = dictionaryName,
        exampleSentence = exampleSentence,
        exampleSentenceTranslation = exampleSentenceTranslation,
        audioFile = audioFile,
        jlptLevel = jlptLevel,
        examplesJson = encodedExamples
    )
}
