package com.yomitanmobile.data.mapper

import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.domain.model.MeaningBlock
import com.yomitanmobile.domain.model.MeaningExample
import com.yomitanmobile.domain.model.WordEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

fun DictionaryEntry.toDomain(): WordEntry {
    val (defList, meaningBlocks) = parseDefinitionPayload(definition)
    val firstExample = meaningBlocks.firstOrNull()?.examples?.firstOrNull()

    return WordEntry(
        id = id,
        expression = expression,
        reading = reading,
        definitions = defList,
        meaningBlocks = meaningBlocks,
        frequency = frequency,
        pitchAccent = pitchAccent,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = dictionaryName,
        exampleSentence = exampleSentence.ifBlank { firstExample?.sentenceText.orEmpty() },
        exampleSentenceTranslation = exampleSentenceTranslation.ifBlank { firstExample?.translation ?: "" },
        audioFile = audioFile
    )
}

fun WordEntry.toEntity(): DictionaryEntry {
    val payload = if (meaningBlocks.isNotEmpty()) {
        json.encodeToString(ListSerializer(MeaningBlock.serializer()), meaningBlocks)
    } else {
        json.encodeToString(ListSerializer(String.serializer()), definitions)
    }

    return DictionaryEntry(
        id = id,
        expression = expression,
        reading = reading,
        definition = payload,
        frequency = frequency,
        pitchAccent = pitchAccent,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = dictionaryName,
        exampleSentence = exampleSentence,
        exampleSentenceTranslation = exampleSentenceTranslation,
        audioFile = audioFile
    )
}

private fun parseDefinitionPayload(raw: String): Pair<List<String>, List<MeaningBlock>> {
    return try {
        when (val element = json.parseToJsonElement(raw)) {
            is JsonArray -> {
                val objects = element.mapNotNull { item ->
                    if (item is JsonObject && item["meaning"] != null) {
                        runCatching { json.decodeFromJsonElement(MeaningBlock.serializer(), item) }.getOrNull()
                    } else null
                }
                if (objects.isNotEmpty()) {
                    objects.map { it.meaning } to objects
                } else {
                    val list = json.decodeFromJsonElement(ListSerializer(String.serializer()), element)
                    list to emptyList()
                }
            }
            else -> listOf(raw) to emptyList()
        }
    } catch (_: Exception) {
        listOf(raw) to emptyList()
    }
}
