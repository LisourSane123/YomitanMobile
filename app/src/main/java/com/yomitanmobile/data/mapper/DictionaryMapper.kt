package com.yomitanmobile.data.mapper

import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.domain.model.ExamplePair
import com.yomitanmobile.domain.model.WordEntry
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

    return WordEntry(
        id = id,
        expression = expression,
        reading = reading,
        definitions = defList,
        frequency = frequency,
        pitchAccent = pitchAccent,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = dictionaryName,
        exampleSentence = exampleSentence,
        exampleSentenceTranslation = exampleSentenceTranslation,
        audioFile = audioFile,
        jlptLevel = jlptLevel,
        examples = examples
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
