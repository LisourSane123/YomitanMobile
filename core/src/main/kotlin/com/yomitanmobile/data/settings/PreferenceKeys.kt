package com.yomitanmobile.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * Every DataStore key the app stores a setting under.
 *
 * Lived in MainActivity's companion, which made the card-style reader — the
 * only path from stored settings to a card — an Android class by association.
 * The desktop Kindle tool reads the same keys out of a backup's settings.json.
 */
object PreferenceKeys {
    val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
    
    // The language being studied ("ja" / "en"). Absent = never chosen,
    // which is what routes first launch to the language screen; an
    // upgrading install reads as absent too and is sent there once,
    // with Japanese pre-selected to match its existing data.
    val APP_LANGUAGE = stringPreferencesKey("app_language")
    val ANKI_DECK_NAME = stringPreferencesKey("anki_deck_name")
    val THEME_MODE = stringPreferencesKey("theme_mode") // "system", "light", "dark"
    
    // The user's own pronunciation archive: the SAF tree they picked and a
    // readable name for it. The index over the folder lives in Room
    // (`audio_files`); these two only say which folder it was built from,
    // so the settings screen can name it and a re-index can repeat itself.
    val AUDIO_ARCHIVE_URI = stringPreferencesKey("audio_archive_uri")
    val AUDIO_ARCHIVE_LABEL = stringPreferencesKey("audio_archive_label")
    
    // Card style preferences
    val CARD_EXPRESSION_BOLD = booleanPreferencesKey("card_expression_bold")
    val CARD_EXPRESSION_FONT_SIZE = intPreferencesKey("card_expression_font_size")
    val CARD_READING_FONT_SIZE = intPreferencesKey("card_reading_font_size")
    val CARD_MEANING_FONT_SIZE = intPreferencesKey("card_meaning_font_size")
    val CARD_FRONT_CONTEXT_SENTENCE_FONT_SIZE = intPreferencesKey("card_front_context_sentence_font_size")
    val CARD_BACK_SENTENCE_FONT_SIZE = intPreferencesKey("card_back_sentence_font_size")
    val CARD_FONT_FAMILY = stringPreferencesKey("card_font_family")
    val CARD_BACKGROUND_COLOR = stringPreferencesKey("card_background_color")
    val CARD_EXPRESSION_COLOR = stringPreferencesKey("card_expression_color")
    val CARD_READING_COLOR = stringPreferencesKey("card_reading_color")
    val CARD_MEANING_COLOR = stringPreferencesKey("card_meaning_color")
    val CARD_ACCENT_COLOR = stringPreferencesKey("card_accent_color")
    // Furigana reading color on example sentences. Empty string = inherit
    // the surrounding sentence text color ("same as text").
    val CARD_FURIGANA_COLOR = stringPreferencesKey("card_furigana_color")
    val CARD_SHOW_PITCH = booleanPreferencesKey("card_show_pitch")
    val CARD_PITCH_ACCENT_STYLE = stringPreferencesKey("card_pitch_accent_style")
    val CARD_SHOW_FREQUENCY = booleanPreferencesKey("card_show_frequency")
    val CARD_SHOW_SENTENCE = booleanPreferencesKey("card_show_sentence")
    val CARD_SHOW_FRONT_CONTEXT_SENTENCE = booleanPreferencesKey("card_show_front_context_sentence")
    val CARD_RANDOM_FONTS_ENABLED = booleanPreferencesKey("card_random_fonts_enabled")
    val CARD_RANDOM_FONTS = stringSetPreferencesKey("card_random_fonts")
    val TTS_RANDOM_VOICES_ENABLED = booleanPreferencesKey("tts_random_voices_enabled")
    /** Card audio and the detail screen's play button use VOICEVOX when its voice is installed. */
    val TTS_VOICEVOX_ENABLED = booleanPreferencesKey("tts_voicevox_enabled")
    val TTS_RANDOM_VOICES = stringSetPreferencesKey("tts_random_voices")
    val CARD_SHOW_SECTION_DIVIDERS = booleanPreferencesKey("card_show_section_dividers")
    val CARD_SHOW_WORD_DIVIDER = booleanPreferencesKey("card_show_word_divider")
    
    // AI summary integration. Gated behind CARD_AI_SUMMARY_ENABLED so the
    // network call only happens when the user explicitly opts in. The
    // prompt is a template — placeholders like {expression}, {reading},
    // {meaning}, {language} get substituted before the call.
    val CARD_AI_SUMMARY_ENABLED = booleanPreferencesKey("card_ai_summary_enabled")
    val CARD_AI_PROVIDER = stringPreferencesKey("card_ai_provider")
    val CARD_AI_API_KEY = stringPreferencesKey("card_ai_api_key")
    val CARD_AI_PROMPT = stringPreferencesKey("card_ai_prompt")
    // Optional per-provider model override. When blank, AiSummaryService
    // falls back to AiProvider.defaultModel (Gemini → gemini-3.1-flash-lite,
    // DeepSeek → deepseek-chat, OpenAI → gpt-4o-mini).
    val CARD_AI_MODEL = stringPreferencesKey("card_ai_model")
    
    // Comma-separated list of CardSection.storageValue, e.g.
    // "pitch,summary,meaning,sentence,audio,kanji". CardSection.decode
    // tolerates missing/extra tokens so an old saved order doesn't
    // hide new sections after an upgrade.
    val CARD_SECTION_ORDER = stringPreferencesKey("card_section_order")
    
    // Card meaning engine: which language the Meaning field is written in.
    // "EN" (default) uses whatever the search dictionaries give; "JA" pulls
    // the definition from a monolingual dictionary named by
    // CARD_MONOLINGUAL_DICTIONARY. See MonolingualCardResolver.
    val CARD_MEANING_LANGUAGE = stringPreferencesKey("card_meaning_language")
    val CARD_MONOLINGUAL_DICTIONARY = stringPreferencesKey("card_monolingual_dictionary")
    
    // Daily goal
    val DAILY_GOAL_COUNT = intPreferencesKey("daily_goal_count") // 0 = disabled
    
    // Frequency display: priority order of installed lists (comma-separated
    // dictionary titles, highest priority first) + whether to show every
    // list's rank at once or just the top-priority one.
    val FREQUENCY_DISPLAY_ORDER = stringPreferencesKey("frequency_display_order")
    val FREQUENCY_SHOW_ALL = booleanPreferencesKey("frequency_show_all")
    
    // "Only the leading list counts." On: a word the leading list does not
    // know is left unranked instead of borrowing another list's number, so
    // every rank the app stores — and stamps on a card — is on one scale.
    val FREQUENCY_STRICT_LEADING = booleanPreferencesKey("frequency_strict_leading")
}
