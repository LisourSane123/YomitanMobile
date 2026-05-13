# Production Readiness — TO FIX

Audit performed 2026-05-13 against `alternate_way` branch. Target: production release next week.

Legend:
- [ ] pending
- [x] done

**Status 2026-05-13 EOD:** all P0 items shipped. `./gradlew :app:assembleRelease :app:testDebugUnitTest` is green. Release engineer still needs to create `app/keystore.properties` from the template before signing the production APK.

**Status 2026-05-13 evening:** all P0 + all P1 except #12 (tests for repo/backup/DetailViewModel) shipped. Highlights:
- P1-9: discovered the FTS sanitizer was emitting FTS5 phrase-prefix syntax (`"foo"*`) into FTS4 — every English search had been silently throwing inside `.catch` and returning empty results. Rewritten to per-token `term*` syntax. New tests cover empty/whitespace/operators/control-chars/long input/Japanese script.
- P1-8: LIKE wildcards now escaped via `ESCAPE '\'` clause + sanitizer call in repo.
- P1-11: WebView gets `allowFileAccess=false`, `allowContentAccess=false`.
- P1-13: numeric formats pinned to `Locale.US`.
- Sanitizer test suite expanded from 2 to 14 cases.

---

## P0 — RELEASE BLOCKERS (must fix before shipping)

- [x] **1. Release build is broken.** `./gradlew :app:assembleRelease` fails on AnkiDroid lint rule `DirectDateInstantiation`.
  - File: `app/src/main/java/com/yomitanmobile/data/backup/BackupManager.kt:43`
  - Replace `Date()` with `Calendar.getInstance().time` or `System.currentTimeMillis()`.

- [x] **2. No release signing config.** `app/build.gradle.kts:27-35` has `release { isMinifyEnabled = true }` but no `signingConfig`. APK cannot be installed or published.
  - Add `signingConfigs { create("release") { ... } }` reading from `keystore.properties` (outside VCS) or wire up Play App Signing.

- [x] **3. ProGuard rules nearly empty + R8 minify ON.** `app/proguard-rules.pro` only keeps `kotlinx.serialization`. Risks: `@Serializable` data classes (`ExamplePair`, `CardSection`) renamed, custom `WidgetEntryPoint` reflective access broken, possible Hilt graph breakage.
  - Add keeps for `@Serializable` classes, Hilt entry points, `data/local/entity/**`.
  - Full release-APK smoke test: import → search → favorite → widget → Anki export.

- [x] **4. `targetSdk = 34` will be rejected by Play Store.** As of 2026, new apps need API 35+.
  - Bump `targetSdk` to 35 (or 36). Smoke-test permissions and edge-to-edge.

- [x] **5. Backup/restore corrupts running app state.** `BackupManager.kt:83` closes the DB mid-flight; Hilt singleton DAOs hold stale handles. Any subsequent DAO call throws `IllegalStateException`.
  - Force-exit after restore, OR show "Restart app" dialog blocking nav, OR refuse to close DB.

- [x] **6. AI API key leaks via backup.** Backup writes `datastore_prefs.pb` (plaintext `CARD_AI_API_KEY`) to `getExternalFilesDir`, world-readable to file managers and USB MTP.
  - Exclude `CARD_AI_API_KEY` from backup, OR move to `EncryptedSharedPreferences`, OR prompt user before backing up keys.

- [x] **7. Lossy SQLite settings during import can corrupt DB.** `DictionaryRepositoryImpl.kt:43-57` sets `synchronous = OFF` + `journal_mode = MEMORY`. Process kill during multi-GB import = corrupted user data (favorites/history/exports).
  - Drop `synchronous=OFF`; keep `WAL+NORMAL` throughout.

---

## P1 — High risk, fix this week

- [x] **8. `searchCombined` LIKE wildcards not escaped.** `DictionaryDao.kt:43-44` — typing `_` or `%` produces wildcard behavior. Sanitizer exists (`InputSanitizer.sanitizeLikeQuery`) but is never called. Apply it in `DictionaryRepositoryImpl.searchCombined`.

- [x] **9. FTS sanitizer produces non-standard syntax.** `InputSanitizer.kt:31` returns `"$escaped"*`. Valid FTS5 phrase-prefix, invalid FTS4. `.catch` swallows errors, user sees empty results. Add tests confirming FTS5 + non-empty results for ASCII/kana/kanji.

- [x] **10. Search throws on FTS operators in user input.** No stripping of `AND/OR/NOT/NEAR`, `(`, `^`, `:`, control chars. Add tests for these plus very long input (>256 chars).

- [x] **11. WebView defense-in-depth.** `CardStyleScreen.kt:350` has `javaScriptEnabled = false` ✓ but missing `allowFileAccess = false` and `allowContentAccess = false`.

- [ ] **12. Missing tests on critical paths.** No tests for: `InputSanitizer`, `DictionaryRepositoryImpl`, `BackupManager`, `DetailViewModel` (Anki export). Anki export is the money path; regressions there are catastrophic.

- [x] **13. Locale-dependent `String.format`.** `StatisticsScreen.kt:738-739`, `StatisticsViewModel.kt:165,287` — `%.1f` formats with comma in pl-PL. Use `String.format(Locale.US, ...)`.

---

## P2 — Should fix soon, not blockers

- [ ] **14. Missing migrations 1→6.** DB version 11, migrations only 6→11. OK for the first release (versionCode=1, no historical users) — but any internal beta with older schema will crash on upgrade. Confirm no testers have older DBs.

- [ ] **15. `android:allowBackup="false"` deprecated on Android 12+.** `AndroidManifest.xml:15` — switch to `android:dataExtractionRules` pointing to an explicit deny-all rules XML.

- [ ] **16. Outdated dependencies.** 22 deps behind (Compose BOM 2023.10 → 2026.05, Hilt 2.50 → 2.59, Room 2.6.1 → 2.8.4, etc.). Some have CVE/perf fixes. Plan post-launch.

- [ ] **17. Locale switching without app-bundle config.** `MainActivity.kt:108` does dynamic locale switching but bundle isn't configured non-split. Non-default locales may be missing at runtime in AAB build.
  - Add `bundle { language { enableSplit = false } }`.

- [ ] **18. Widget exported=true with no permission.** Required for AppWidgetManager but receivers should not read untrusted intent extras. Confirm.

- [ ] **19. Hardcoded strings in widget XML.** `res/layout/widget_search.xml`, `widget_quick_search.xml` — `"Szukaj"`, `"Wpisz słowo po japońsku..."`, `"✨ Słowo dnia"`, `"検索"`, `"JP"`, `"Yomitan Mobile"`. Move to `strings.xml` / `strings-en.xml`.

- [ ] **20. Log calls remain in release APK.** Add to ProGuard:
  ```
  -assumenosideeffects class android.util.Log {
      public static *** d(...); public static *** v(...); public static *** i(...);
  }
  ```

---

## What is OK (verified)

- Download manager: HTTPS-only, host allowlist re-checked on redirect, size caps, ZIP magic, SHA-256, mutex.
- ZIP parser: per-entry + total caps, no filesystem writes (no path traversal), defensive try/catch.
- AI service: host allowlist, HTTPS-only, redirects disabled, response capped, timeouts, errors don't throw.
- WebView JS disabled where used.
- HTML escaping for Anki cards.
- Widget DAO via Hilt entry point (CLAUDE.md issue (d) fixed).
- All DAO queries use bind parameters (no SQL string concat).
- Mutex on download prevents concurrent imports.
