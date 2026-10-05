package com.yomitanmobile.data.ai

/**
 * Supported AI providers for the optional summary integration on Anki
 * exports. Each provider has a stable [storageValue] used in DataStore
 * (so renaming display names doesn't break preferences) and a default
 * model the call will use when the user doesn't override it.
 *
 * The OpenAI option lives here too because DeepSeek's API mirrors
 * OpenAI's chat-completions schema — a third "compatible" entry covers
 * any other endpoint that speaks the same wire format (e.g.
 * self-hosted llama.cpp servers with OpenAI compatibility).
 */
enum class AiProvider(
    val storageValue: String,
    val displayName: String,
    val defaultModel: String
) {
    GEMINI("gemini", "Google Gemini", "gemini-3.1-flash-lite"),
    DEEPSEEK("deepseek", "DeepSeek", "deepseek-chat"),
    OPENAI("openai", "OpenAI", "gpt-4o-mini");

    companion object {
        fun fromStorage(value: String?): AiProvider =
            values().firstOrNull { it.storageValue == value } ?: GEMINI
    }
}

/**
 * Default prompt template. Placeholders ({expression}, {reading},
 * {language}, {meaning}) are substituted at call time. Encoded as a
 * single string (no newlines) so DataStore round-trips cleanly.
 */
const val AI_DEFAULT_PROMPT: String =
    "You are a Japanese language tutor. Concisely summarize the Japanese word " +
    "\"{expression}\" (reading: \"{reading}\") in {language} for a flashcard. " +
    "Two to three sentences. Mention key nuances and common usage. " +
    "Do not repeat the word or reading at the start. " +
    "Existing dictionary glosses (do not just repeat them): {meaning}"
