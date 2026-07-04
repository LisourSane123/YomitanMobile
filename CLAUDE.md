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

Prerequisites: JDK 17, Android SDK 34. The build pins JDK 17 via a Gradle toolchain (`kotlin { jvmToolchain(17) }` in `app/build.gradle.kts`), which Gradle locates automatically — no machine-specific path in the committed `gradle.properties`. If your default `java` is older than 17 and the toolchain can't find a JDK 17, set `org.gradle.java.home` in your user-level `~/.gradle/gradle.properties`.

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
- **Sentences**: example sentences come from two local sources only — Jitendex examples attached to each entry (with per-sense `definitionIndex`) and the pre-seeded `SentenceDao`. The former online Tatoeba fetch (`OnlineSentenceService`) was **removed**; there is no network sentence lookup and no consent flag anymore.

### UI layer (`ui/`)
MVVM with Jetpack Compose. Each screen has a paired `ViewModel`. Navigation is defined in `ui/navigation/AppNavHost.kt` with sealed `Screen` routes.

Key screens: `SearchScreen` (main), `DetailScreen` (word details + Anki export), `DictionaryDownloadScreen`, `SetupScreen`, `FavoritesScreen`, `StatisticsScreen`, `SettingsScreen`, `CardStyleScreen`.

### Search pipeline
`SearchViewModel` → `SearchDictionaryUseCase` → `DictionaryRepository` → `DictionaryDao`

- **JP mode**: detects kanji/kana input, generates inflection candidates via `JapaneseDeconjugator.analyze()` (max 24, depth 3), then `invokeWithAlternatives()` runs the literal query + candidates **in parallel** (`coroutineScope` + `async`) and merges by entry ID, literal query first.
- **EN mode**: FTS on definition text via `searchByDefinition`.
- **Romaji mode**: converts via `RomajiConverter` then searches as JP.
- Mode is auto-detected from the script per keystroke, unless the user manually picks one via the toggle (`manualModeOverride` in `SearchViewModel` pins it until the query is cleared) — this is the only way to reach ROMAJI mode, which auto-detect never produces.
- Debounce is 100 ms in `SearchViewModel`; results are merged via `MergedWordEntry.mergeEntries()` which groups by `(expression, reading)` key.

### Result merging
`MergedWordEntry.mergeEntries()` groups `WordEntry` items by `(expression, reading)`. Within each group it picks the primary (prefers kanji + lower frequency rank), merges definitions, and collects alternatives. This avoids homophone grouping errors. Dedup of the gloss list and remapping of each example's `definitionIndex` onto the merged positions happen together in `mergeDefinitionsAndExamples()` — so the detail screen and Anki export can group examples by `definitionIndex` and trust it, with no compensation logic of their own.

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
`SearchWidgetProvider` reaches the singleton DAO graph through the Hilt `WidgetEntryPoint` (`EntryPointAccessors.fromApplication`) instead of building its own Room instance. `QuickSearchWidgetProvider` touches no database.

## Known issues
- Many strings are hardcoded in Compose screens as inline `tr(pl, en)` bilingual literals rather than `strings.xml` resources. This is the app's deliberate i18n strategy (EN/PL only, driven by `LocaleHelper`), not a bug — but it means adding a third language would require a real resource migration.
