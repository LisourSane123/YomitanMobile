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
- **Anki** (`data/anki/AnkiCardCreator`): integrates with AnkiDroid via `AddContentApi`. Anki model name is `Yomitan-Mobile-v8` (stable — do not bump unless `FIELD_NAMES` changes). Fields: `Front`, `FrontContext`, `Reading`, `Meaning`, `PitchAccent`, `Frequency`, `Audio`, `Sentence`, `KanjiBreakdown`, `Summary`. `Frequency` holds the **raw frequency rank as a plain number** ("4821"), empty when no frequency dictionary ranks the word — it is rendered on neither template, so its only consumer is Anki itself (addons like AutoReorder sort new cards by it and need a sortable number). The starred tier label (`frequencyLabel()`, `Top 1K`…) is a UI-only thing for the search and detail screens — do not put it back on the card. `exportBatchToAnki()` is the bulk path used by the JLPT deck generator: it resolves model/deck/CSS once and writes through `addNotes` in chunks of 50, with no AI summaries and audio only on request.
- **Card engine** (`data/anki/MonolingualCardResolver`): switches the Meaning field between JP-EN and JP-JP. Sits between "what the search found" and "what gets written", so the detail-screen export and the JLPT batch share it. Rules: definition from the dictionary named in `CARD_MONOLINGUAL_DICTIONARY`; words missing from it keep their English gloss (a monolingual dictionary covers far fewer headwords, and a blank back is worse); example sentences stay but lose their English translation. The dictionary is chosen from the *installed* list, never hardcoded — the commercial 国語辞典 (三省堂, 明鏡, 新明解…) are only distributed on Drive/Mega and cannot be shipped as downloads, so users import their own zip; `日本語 Wiktionary` is offered as the freely licensed one-tap option.
- **Anki duplicate scan** (`data/anki/AnkiCollectionIndex` + `AnkiNoteFieldIndexer`): reads the whole AnkiDroid collection through `FlashCardsContract.Note` (selection = Anki search string) and indexes every short, purely-Japanese field value. Note-type agnostic on purpose, so it matches Core 2k/6k/10k, Kaishi 1.5k and hand-rolled note types without per-deck field mappings; `漢字[かんじ]` ruby fields are indexed under both the expression and the reading. Three rules keep prose out of the index: a raw-field ceiling (40 chars, loose because ruby carries readings inline), a finished-key ceiling (16, the real headword limit) and a maximum of two whitespace-separated runs — a spaced ruby sentence (`私[わたし] は 毎日[まいにち] …`) strips down to a flawless Japanese key that nothing else would reject. **The word count is not a card count and never will be**: one note yields both its written form and its reading, and one note can back several cards, so words > notes > cards is the expected shape, not a bug. Degrades to "unavailable" (never to false positives) when the provider can't be read.
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
- **Substring pass**: search used to be prefix-only, so 欲 found 欲しい but never 食欲 — half of what a kanji lookup is for. `DictionaryDao.searchContains` runs in the same parallel batch and its results are appended LAST, so the word itself and its prefix matches still outrank the compounds it merely appears in. The query excludes prefix matches itself (they are the frequent rows and would otherwise eat the whole LIMIT) and matches via an `id IN (…UNION…)` subquery so the unavoidable `LIKE '%x%'` scan stays inside the small `expression`/`reading` indexes instead of pulling every `definition` blob through the page cache. `SearchDictionaryUseCase.shouldSearchSubstring` gates it: a single kanji yes, a single kana no (it would match a large share of the dictionary, per keystroke, to return noise).
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

### Text scanner (subtitles / EPUB → cards)
`TextScanScreen` → `TextScanViewModel` → `TextFileReader` + `JapaneseTokenizer` → `TextScanPlanner` (pure) → `AnkiCardCreator.exportBatchToAnki()`.

Turns a file the user actually watched or read into cards for the words they don't know yet.

- **Multi-file** (`JapaneseTokenizer.Accumulator`): several files are scanned as ONE body of text (a season of subtitles, a series of volumes) — counts add up, and the first-occurrence offset comes from the earliest file. `TextScanViewModel` sorts the picked documents by file name first, which is what puts `ep01, ep02, …` in watching order; the deck name and Anki tag default to the files' common name prefix.
- **Reading** (`data/text/`): `TextExtraction` handles SRT / VTT / ASS-SSA / TXT / EPUB, `TextFileReader` owns the `Uri`/ContentResolver side and a 64 MB ceiling. Format detection is extension-first with a content sniff fallback (SAF Uris usually report `application/octet-stream`). Charset detection tries strict UTF-8 first — it is the only self-validating one of the three encodings Japanese subtitles come in — then Shift_JIS, then EUC-JP. EPUB chapters are read straight out of the ZIP (no `content.opf` spine parse: word extraction doesn't care about reading order), and `<rt>`/`<rp>` furigana is dropped **before** tag stripping, otherwise every reading would be tokenised as its own word.
- **Segmentation** (`util/JapaneseTokenizer`): longest-match against the installed dictionaries' expressions *and* readings (`DictionaryRepository.getSurfaceLexicon()`, held in memory for the scan — hundreds of thousands of substring tests cannot go through SQLite), falling back to `JapaneseDeconjugator` so 食べました reaches 食べる instead of being chopped into 食. Deconjugation only fires for 2+ character forms ending in kana, and every surface→base decision is memoised per document. Deliberately no Kuromoji: its IPADIC is ~5 MB of data the app already has, and "longest wins" is the right bias for vocabulary lists.
- **Source sentences**: the tokeniser walks the text sentence by sentence and keeps the first usable sentence (6–90 chars) each word appeared in. `TextScanViewModel.withSourceSentence()` puts it in `WordEntry.exampleSentence` — the first candidate `AnkiCardCreator.pickFrontContextSentence()` looks at — so it lands on the card FRONT under the word, highlighted by the existing `SentenceContextHighlighter`. Nothing in the card builder needed changing; the batch forces `showFrontContextSentence = true` in its copy of the style prefs, because asking for source sentences means asking for that slot regardless of the global card-style setting.
- **Card order** = study order: AnkiDroid introduces new cards in insertion order, so `TextScanPlanner.studyScore()` decides what gets learned first — 0.5 × global frequency rank (log scale), 0.35 × occurrences in the scanned text, 0.15 × how early the word appears. The earliness term is what makes a series scan front-load the words from volume 1.
- **Filtering** (`domain/usecase/TextScanPlanner`): same contract as `JlptDeckPlanner` — fixed rule order, first rejection owns the counter, `selected + skipped == distinct words`. Grammar is dropped by two cooperating rules: the `FUNCTION_WORDS` stoplist (particles, です/ます, する/いる…) and the tag-based `WordFilterRules.isFunctionWord`. The stoplist alone was never enough — longest-match segmentation emits compounds (それでも, ということ, かもしれない) that are single dictionary entries and can't be enumerated by hand, but all carry a `conj`/`prt`/`aux` tag. `assumeKnownTopRank` is the other half of "the first hundred cards are stuff I know": frequency-first ordering means the head of every deck is by definition what an intermediate reader already has, and only the user can say where that line is. `knownTokenCount` counts only words skipped as already-in-Anki / already-mined / function words / assumed-known, so the "you already know X% of this text" figure is not inflated by words dropped for being rare.
- **Shared rules** (`domain/usecase/WordFilterRules`): the archaic, proper-name and function-word tag rules; both planners answer the same question about the same data, so they share one implementation. All three are "all tags, not any": a merged entry carries every sense's tags, so 自分 (`pn` + `n`) and a word JMnedict also lists as a surname must survive. Inflection paradigms and bare `exp` are neutral — they say how a word conjugates or that it is a phrase, never what it is.
- Cards are tagged `yomitan-mobile`, `text-scan` and a slug of the file name, and — like the JLPT generator — are NOT written to `exported_words`.
- `FrequencyTier` (`domain/model/`) is the one definition of the Top-1K…Top-50K bands, shared by the scanner's range chips and the frequency badges on `WordEntry`/`MergedWordEntry`.
- **PDF is not supported**: Android has no text-extraction API (`PdfRenderer` only rasterises), so it would need a third-party parser. `TextFileReader` rejects PDFs with a clear message rather than pretending.

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
