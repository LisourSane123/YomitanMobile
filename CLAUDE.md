# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Run all unit tests
./gradlew :app:testDebugUnitTest

# Run a single test class
./gradlew :app:testDebugUnitTest --tests "com.yomitanmobile.util.JlptLevelUtilTest"

# Build release APK (minified)
./gradlew :app:assembleRelease
```

Prerequisites: JDK 17, Android SDK 34. The `gradle.properties` hardcodes `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk` — override locally if your JDK path differs.

## Architecture

Clean architecture in three layers: `data/`, `domain/`, `ui/`, wired together with Hilt DI.

### Domain layer (`domain/`)
- `model/` — pure Kotlin data classes: `WordEntry` (raw DB entry), `MergedWordEntry` (grouped search result), `AnkiCard`, `CardStylePreferences`
- `repository/DictionaryRepository.kt` — interface; the single abstraction boundary between data and domain
- `usecase/` — `SearchDictionaryUseCase`, `GetWordDetailUseCase`, `DictionaryManagementUseCases`

### Data layer (`data/`)
- **Room** (`data/local/`): `AppDatabase` at version 9 with migrations defined inline. Tables: `DictionaryEntry` (FTS-enabled via `DictionaryEntryFts`), `DictionaryInfo`, `ExportedWord`, `FavoriteWord`, `SearchHistory`, `KanjiEntry`, `Sentence`. Complex columns (lists, JSON) use `Converters.kt`.
- **Parser** (`data/parser/YomitanDictionaryParser`): streaming ZIP parser for Yomitan/Yomichan dictionary format. ZIP contains `index.json` + `term_bank_N.json` files. Handles both term dictionaries and meta dictionaries (frequency/pitch data).
- **Repository** (`data/repository/DictionaryRepositoryImpl`): delegates search to `DictionaryDao` which uses FTS for expression/reading and a separate path for EN definition search.
- **Anki** (`data/anki/AnkiCardCreator`): integrates with AnkiDroid via `AddContentApi`. Anki model name is `Yomitan-Mobile-v7` (stable — do not bump unless fields change). Fields: `Front`, `FrontContext`, `Reading`, `Meaning`, `PitchAccent`, `Frequency`, `Audio`, `Sentence`, `KanjiBreakdown`.
- **Download** (`data/download/DictionaryDownloadManager`): validates HTTPS URLs against an allowlist before downloading.
- **Sentence** (`data/sentence/OnlineSentenceService`): optional, requires user consent. Gate all calls behind the consent flag.

### UI layer (`ui/`)
MVVM with Jetpack Compose. Each screen has a paired `ViewModel`. Navigation is defined in `ui/navigation/AppNavHost.kt` with sealed `Screen` routes.

Key screens: `SearchScreen` (main), `DetailScreen` (word details + Anki export), `DictionaryDownloadScreen`, `SetupScreen`, `FavoritesScreen`, `StatisticsScreen`, `SettingsScreen`, `CardStyleScreen`.

### Search pipeline
`SearchViewModel` → `SearchDictionaryUseCase` → `DictionaryRepository` → `DictionaryDao`

- **JP mode**: detects kanji/kana input, calls `JapaneseDeconjugator.candidateForms()` to generate inflection candidates (max 24, depth 3), then `invokeWithAlternatives()` does sequential `first()` calls per candidate and merges by entry ID (audit finding: potential latency for long candidate lists).
- **EN mode**: FTS on definition text via `searchByDefinition`.
- **Romaji mode**: converts via `RomajiConverter` then searches as JP.
- Debounce is 100 ms in `SearchViewModel`; results are merged via `MergedWordEntry.mergeEntries()` which groups by `(expression, reading)` key.

### Result merging
`MergedWordEntry.mergeEntries()` groups `WordEntry` items by `(expression, reading)`. Within each group it picks the primary (prefers kanji + lower frequency rank), merges definitions, and collects alternatives. This avoids homophone grouping errors.

### Utils (`util/`)
- `InputSanitizer`: sanitizes FTS queries and Anki card content
- `JapaneseDeconjugator`: rule-based, no external library
- `RomajiConverter`: romaji → hiragana conversion for search
- `WordCategoryClassifier`: classifies entries into JLPT/POS categories using JMDict tags
- `JlptLevelUtil`: JLPT level from JMDict tags with expression-level fallback
- `SentenceContextHighlighter`: highlights target word inside an example sentence

### DI (`di/`)
Three Hilt modules: `AppModule` (singleton services), `DatabaseModule` (Room + DAOs), `RepositoryModule` (binds interface → impl).

### Widgets (`widget/`)
`SearchWidgetProvider` and `QuickSearchWidgetProvider` — each creates its own Room instance on update (audit finding: extra I/O cost).

## Known issues (from audit 2026-04-18)
- Many strings are hardcoded in Compose screens (not in `strings.xml`) — i18n incomplete.
- JP search does sequential `first()` per deconjugation candidate; latency can spike for long candidate lists (`SearchDictionaryUseCase:48`).
- Widgets instantiate Room directly instead of going through a shared DI entry point (`SearchWidgetProvider:52`).
