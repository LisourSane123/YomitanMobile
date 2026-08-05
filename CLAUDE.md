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

Prerequisites: JDK 17, Android SDK 35. The build pins JDK 17 via a Gradle toolchain (`kotlin { jvmToolchain(17) }` in `app/build.gradle.kts`), which Gradle locates automatically — no machine-specific path in the committed `gradle.properties`. If your default `java` is older than 17 and the toolchain can't find a JDK 17, set `org.gradle.java.home` in your user-level `~/.gradle/gradle.properties`.

## Architecture

Clean architecture in three layers: `data/`, `domain/`, `ui/`, wired together with Hilt DI.

### Domain layer (`domain/`)
- `model/` — pure Kotlin data classes: `WordEntry` (raw DB entry), `MergedWordEntry` (grouped search result), `AnkiCard`, `CardStylePreferences`
- `repository/DictionaryRepository.kt` — interface; the single abstraction boundary between data and domain
- `usecase/` — `SearchDictionaryUseCase`, `GetWordDetailUseCase`, `DictionaryManagementUseCases`

### Data layer (`data/`)
- **Room** (`data/local/`): `AppDatabase` at version 16 with migrations defined inline. Tables: `DictionaryEntry` (FTS-enabled via `DictionaryEntryFts`), `DictionaryInfo`, `ExportedWord`, `FavoriteWord`, `SearchHistory`, `KanjiEntry`, `Sentence`, `LookupCount`, `WordFrequency`, `JlptTag`, `AnkiCollectionWord`. Complex columns (lists, JSON) use `Converters.kt`.
- **Parser** (`data/parser/YomitanDictionaryParser`): streaming ZIP parser for Yomitan/Yomichan dictionary format. ZIP contains `index.json` + `term_bank_N.json` files. Handles both term dictionaries and meta dictionaries (frequency/pitch data).
- **Repository** (`data/repository/DictionaryRepositoryImpl`): delegates search to `DictionaryDao` which uses FTS for expression/reading and a separate path for EN definition search.
- **Anki** (`data/anki/AnkiCardCreator`): integrates with AnkiDroid via `AddContentApi`. Anki model name is `Yomitan-Mobile-v8` (stable — do not bump unless `FIELD_NAMES` changes). Fields: `Front`, `FrontContext`, `Reading`, `Meaning`, `PitchAccent`, `Frequency`, `Audio`, `Sentence`, `KanjiBreakdown`, `Summary`. `exportBatchToAnki()` is the bulk path used by the JLPT deck generator: it resolves model/deck/CSS once and writes through `addNotes` in chunks of 50, with no AI summaries and audio only on request.
- **Card engine** (`data/anki/MonolingualCardResolver`): switches the Meaning field between JP-EN and JP-JP. Sits between "what the search found" and "what gets written", so the detail-screen export and the JLPT batch share it. Rules: definition from the dictionary named in `CARD_MONOLINGUAL_DICTIONARY`; words missing from it keep their English gloss (a monolingual dictionary covers far fewer headwords, and a blank back is worse); example sentences stay but lose their English translation. The dictionary is chosen from the *installed* list, never hardcoded — the commercial 国語辞典 (三省堂, 明鏡, 新明解…) are only distributed on Drive/Mega and cannot be shipped as downloads, so users import their own zip; `日本語 Wiktionary` is offered as the freely licensed one-tap option.
- **Anki duplicate scan** (`data/anki/AnkiCollectionIndex` + `AnkiNoteFieldIndexer`): reads the whole AnkiDroid collection through `FlashCardsContract.Note` (selection = Anki search string) and indexes every short, purely-Japanese field value. Note-type agnostic on purpose, so it matches Core 2k/6k/10k, Kaishi 1.5k and hand-rolled note types without per-deck field mappings; `漢字[かんじ]` ruby fields are indexed under both the expression and the reading. Degrades to "unavailable" (never to false positives) when the provider can't be read.
- **Stored collection scan** (`data/anki/AnkiCollectionStore` + `anki_collection_words`): the scan is seconds of provider work, so it runs on demand from `AnkiScanScreen` and the result is persisted. Every duplicate check afterwards — mining (`DetailEvent.AlreadyInCollection`) and the JLPT generator — reads the stored copy. An empty store means "not scanned", never "you have nothing", so a missing scan can't silently swallow a card; and a failed rescan keeps the previous result rather than wiping it.
- **AI summaries** (`data/ai/`): optional, opt-in summary text on Anki exports. `AiSummaryService` talks to Gemini / DeepSeek / OpenAI against a hardcoded HTTPS host allowlist; the user supplies their own API key (header-based auth, never in the URL). The key lives in DataStore (`CARD_AI_API_KEY`) and is excluded from backups in `BackupManager` on both export and import.
- **Download** (`data/download/DictionaryDownloadManager`): validates HTTPS URLs against an allowlist before downloading; entries in `AvailableDictionaries` with a `sha256` set are checksum-verified after download.
- **Meta side tables** (`word_frequencies`, `jlpt_tags`): frequency ranks and JLPT levels are stored independently of the term rows, because `dictionary_entries` is deleted and re-inserted on every re-import (and a meta dictionary installed BEFORE its term dictionary has nothing to update). `DictionaryRepositoryImpl.reapplyStoredMeta()` runs `applyJlptLevelsFromTags()` + `applyFrequenciesFromTable()` after every import, so install order no longer matters. Both statements only improve a row — easiest JLPT level wins (5 = N5), best frequency rank wins. Removing this is what silently emptied the JLPT deck generator.
- **Backup** (`data/backup/BackupManager`): user-triggered folder backups (DB + whitelisted settings JSON) under `getExternalFilesDir`. WAL is checkpointed before the DB copy, and restore deletes stale `-wal`/`-shm` sidecars before overwriting — keep both invariants if touching this code.
- **Sentences**: example sentences come from two local sources only — Jitendex examples attached to each entry (with per-sense `definitionIndex`) and the pre-seeded `SentenceDao`. The former online Tatoeba fetch (`OnlineSentenceService`) was **removed**; there is no network sentence lookup and no consent flag anymore.

### UI layer (`ui/`)
MVVM with Jetpack Compose. Each screen has a paired `ViewModel`. Navigation is defined in `ui/navigation/AppNavHost.kt` with sealed `Screen` routes.

Key screens: `SearchScreen` (main), `DetailScreen` (word details + Anki export), `DictionaryDownloadScreen`, `SetupScreen`, `FavoritesScreen`, `StatisticsScreen`, `SettingsScreen`, `CardStyleScreen`, `JlptDeckScreen`, `AnkiScanScreen` (collection scan + detected-word list).

### Search pipeline
`SearchViewModel` → `SearchDictionaryUseCase` → `DictionaryRepository` → `DictionaryDao`

- **JP mode**: detects kanji/kana input, generates inflection candidates via `JapaneseDeconjugator.analyze()` (max 24, depth 3), then `invokeWithAlternatives()` runs the literal query + candidates **in parallel** (`coroutineScope` + `async`) and merges by entry ID, literal query first.
- **EN mode**: FTS on definition text via `searchByDefinition`.
- **Romaji mode**: converts via `RomajiConverter` then searches as JP.
- Mode is auto-detected from the script per keystroke; there is deliberately NO user-facing mode toggle (the app should figure out intent itself). Romaji input is covered automatically by the EN-mode fallback, which converts the query to hiragana and merges those results in. `SearchViewModel.toggleSearchMode()` + `manualModeOverride` + the ROMAJI enum value survive as internal, tested machinery, but no UI calls them.
- Debounce is 100 ms in `SearchViewModel`; results are merged via `MergedWordEntry.mergeEntries()` which groups by `(expression, reading)` key.

### JLPT deck generator
`JlptDeckScreen` → `JlptDeckViewModel` → `JlptDeckPlanner` (pure, `domain/usecase/`) → `AnkiCardCreator.exportBatchToAnki()`.

Builds a whole JLPT level as cards without mining. Candidates are the union of dictionary entries tagged with the level (`jlpt_level` column, fed from `jlpt_tags`) and `JlptVocabulary.wordsForLevel()` resolved against the installed dictionaries. The built-in list is curated and deliberately small (844 words across all five levels), so **deck completeness comes from the `JLPT Vocab Tags` meta dictionary** (~8000 words, in `AvailableDictionaries`); when no installed dictionary tags the selected level, `JlptDeckScreen` says so upfront rather than after an empty analysis.

`JlptDeckPlanner` matches tags token by token: `MergedWordEntry.partsOfSpeech` holds one *joined* tag string per source entry ("n, v5r, arch"), not one tag per element — comparing whole strings against the tag sets matched nothing. The proper-name check prefers the entry's own tags and only falls back to the dictionary name when there are none, because a merged entry carries a single dictionary name for the whole group.

`JlptDeckPlanner` applies the filters in a fixed order (no definition → proper name → unranked → too rare → archaic → already in Anki → already mined → over limit) so each skipped word is counted exactly once and `selected + skipped == candidates`. Kept words are sorted most-frequent-first, which is also the order a capped deck keeps.

Generated cards are deliberately NOT written to `exported_words`: they aren't mined, so counting them would swamp the mining statistics. The "don't recreate what I already have" guarantee comes from the stored collection scan instead, which also covers Core/Kaishi and previously generated levels. Notes are tagged `yomitan-mobile`, `jlpt-nX`, `auto-generated` so they stay findable and bulk-deletable in Anki.

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
