# YomitanMobile

A Japanese dictionary and Anki flashcard creation app for Android built with Kotlin, Jetpack Compose, and Material 3.

## Features

### Dictionary Search
- Import Yomitan-compatible dictionary ZIP files (term banks in JSON format)
- Fast full-text search by kanji, hiragana, katakana, or romaji
- **Smart result consolidation** — entries with the same reading are merged into a single result with numbered definitions and alternative forms (e.g. kanji vs katakana variants)
- Prefix matching for partial queries
- Search history with recall

### Word Details
- Large kanji/kana display with reading annotation
- Numbered definitions list (merged from all dictionary entries)
- Alternative expression forms (e.g. 食べラーユ / タベラーユ shown together)
- Pitch accent diagrams (heiban, atamadaka, nakadaka, odaka patterns)
- Parts of speech labels
- Example sentences with translations
- Audio playback via TTS (Text-to-Speech)

### Anki Integration
- One-tap export to AnkiDroid with a custom card template
- Auto-generated HTML cards with expression, reading, meaning, pitch accent, frequency, audio, and example sentences
- Deck selection (existing or new)
- Duplicate detection — warns before re-exporting the same word
- Dark-themed card styling

### Dictionary Management
- Import dictionaries from local ZIP files
- Download popular dictionaries directly from the app
- Support for frequency and pitch accent meta-dictionaries (applied to existing entries)
- Per-dictionary entry counts and info
- Delete individual dictionaries

### Additional Features
- Light/Dark mode toggle
- Home screen search widget
- Export statistics (total lookups, exported cards, top decks)
- Exported words tracking

## Architecture

```
com.yomitanmobile/
├── data/
│   ├── anki/          # AnkiDroid API integration & card creation
│   ├── audio/         # TTS audio playback
│   ├── download/      # Dictionary download manager
│   ├── local/
│   │   ├── dao/       # Room DAOs (DictionaryDao, SearchHistoryDao, etc.)
│   │   ├── database/  # Room database definition
│   │   ├── entity/    # Room entities (DictionaryEntry, SearchHistory, etc.)
│   │   └── converter/ # Room type converters
│   ├── mapper/        # Entity ↔ Domain model mappers
│   ├── parser/        # Yomitan ZIP/JSON dictionary parser
│   └── repository/    # Repository implementations
├── domain/
│   ├── model/         # Domain models (WordEntry, MergedWordEntry, AnkiCard)
│   ├── repository/    # Repository interfaces
│   └── usecase/       # Use cases (SearchDictionary, GetWordDetail, etc.)
├── di/                # Hilt dependency injection modules
├── ui/
│   ├── search/        # Search screen (query input + merged result list)
│   ├── detail/        # Word detail screen (merged view + Anki export)
│   ├── settings/      # Settings & dictionary management
│   ├── download/      # Dictionary download screen
│   ├── statistics/    # Usage statistics screen
│   ├── setup/         # First-run setup
│   ├── navigation/    # Navigation graph
│   └── theme/         # Material 3 theming
├── widget/            # Home screen search widget
└── util/              # Input sanitization utilities
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Database:** Room (SQLite) with FTS (Full-Text Search)
- **DI:** Hilt (Dagger)
- **Async:** Kotlin Coroutines + Flow
- **Serialization:** kotlinx.serialization
- **Anki API:** AnkiDroid Content Provider API
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)

## How It Works

1. **Import a dictionary** — Go to Settings → Import or Download a Yomitan-compatible dictionary ZIP. The parser reads `index.json` for metadata and `term_bank_*.json` files for entries, inserting them into Room in batches. Frequency and pitch accent meta-dictionaries update existing entries.

2. **Search** — Type a word in the search bar. The app queries Room using a combined exact + prefix match query. Raw results are then **consolidated**: all entries sharing the same reading (hiragana) are grouped into one `MergedWordEntry` with merged definitions, the best kanji expression chosen as primary, and alternative forms preserved.

3. **View details** — Tap a result to see the full merged entry: large kanji display, reading, all definitions numbered, alternative forms, pitch accent diagram, example sentences, and audio playback.

4. **Export to Anki** — Tap the + button to create an AnkiDroid flashcard. The app generates an HTML card with all merged definitions, selects or creates a deck, checks for duplicates, optionally generates TTS audio, and inserts via the AnkiDroid Content Provider API.

## Building

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

This project is provided as-is for personal and educational use.
