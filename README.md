# YomitanMobile

Android app for Japanese dictionary lookup and fast card mining to AnkiDroid.

Built with Kotlin, Jetpack Compose, Room, Coroutines, and Hilt.

## What You Get

- Yomitan ZIP import (terms + meta dictionaries)
- Fast JP/EN/Romaji search
- JP deconjugation hints (base-form suggestions)
- Word details with pitch accent, frequency, examples, audio
- One-tap Anki export with duplicate detection and quality score
- Optional online sentence fetch (requires explicit consent)
- Optional front-side context sentence with highlighted target word
- Share-to-app support (Android ACTION_SEND text/plain)

## Quick Start

Prerequisites:
- JDK 17
- Android SDK 34

Build and install:

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Run tests:

```bash
./gradlew :app:testDebugUnitTest
```

## HOW TO

Full user/developer walkthrough is here:

- [docs/HOW_TO.md](docs/HOW_TO.md)

Includes:
- first setup
- dictionary import/download
- search modes and deconjugation usage
- Anki export flow
- front-context sentence option
- share-to-app flow
- troubleshooting

## Architecture Snapshot

```text
com.yomitanmobile/
├── data/
│   ├── anki/          # AnkiDroid integration + card HTML/model templates
│   ├── audio/         # TTS playback and synthesis helpers
│   ├── download/      # dictionary download + validation
│   ├── local/         # Room entities/dao/database/converters
│   ├── parser/        # Yomitan ZIP parser (streaming)
│   └── repository/    # data-layer implementations
├── domain/
│   ├── model/         # app/domain models
│   ├── repository/    # interfaces
│   └── usecase/       # business use cases
├── di/                # Hilt modules
├── ui/                # Compose screens
├── util/              # sanitization, deconjugation, helpers
└── widget/            # app widgets
```

## Notes About Search Consolidation

Search results are merged using expression + reading (not reading-only) to reduce incorrect homophone grouping.

## Privacy and Network

- Core lookup/export/history operates locally on-device.
- Network is used for dictionary download.
- Optional sentence API is used only if user enables consent.

See:

- [PRIVACY_POLICY.md](PRIVACY_POLICY.md)

## Audit Report

Current senior audit report is available here:

- [docs/AUDIT_2026-04-18.md](docs/AUDIT_2026-04-18.md)

## License

Provided as-is for personal and educational use.
