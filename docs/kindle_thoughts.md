# Kindle Vocabulary Builder → Anki (laptop, Arch Linux)

Notatki projektowe. Nic z tego nie jest jeszcze zaimplementowane.

**Cel:** po podłączeniu Kindle do laptopa skrypt:
1. czyta słowa z Vocabulary Buildera,
2. wybiera japońskie,
3. sprawdza, czy są już w Anki,
4. z brakujących robi fiszki i je dodaje.

Całość ma działać jak Yomitan Mobile, najlepiej na jego kodzie.

---

## 1. Najważniejsza decyzja: wykorzystać kod apki, nie pisać go od nowa

Sam pomysł jest prosty: odczytać słowa z Kindle, znaleźć je w słowniku, sprawdzić, czy są w Anki, i dodać brakujące. Trudne są szczegóły, a apka ma je już rozwiązane i przetestowane na prawdziwych książkach:

- sprowadzanie form do słownikowej (食べました → 食べる),
- dopasowanie do Anki z wariantami pisowni (持って来る ↔ 持ってくる, pola z furiganą `漢字[かんじ]`),
- wybór rankingu z głównej listy częstotliwości,
- filtry słów gramatycznych i imion,
- format karty (typ notatki `Yomitan-Mobile-v8`).

Gdyby przepisać to w Pythonie, po miesiącu byłyby dwie wersje reguł, a karty z laptopa różniłyby się od kart z telefonu.

### Co da się użyć bez zmian

Sprawdzone: te pliki nie mają żadnych importów `android.*`:

| Plik | Rola |
|---|---|
| `data/parser/YomitanDictionaryParser.kt` | parser zip-ów słowników Yomitan |
| `util/JapaneseDeconjugator.kt` | forma odmieniona → słownikowa |
| `util/JapaneseTokenizer.kt` | segmentacja tekstu |
| `data/anki/AnkiNoteFieldIndexer.kt` | indeksowanie pól notatek do sprawdzania duplikatów |
| `data/anki/KanaSpellingVariants.kt` | warianty pisowni kanji/kana |
| `domain/usecase/WordFilterRules.kt` | filtry: archaizmy, imiona, gramatyka |
| `domain/model/MergedWordEntry.kt` | scalanie wyników |

Dodatkowo `app/src/test/.../tools/BookScanHarness.kt` już dziś uruchamia cały ten pipeline na zwykłej JVM, z zip-ów słowników zamiast bazy Room. Czyli da się to zrobić.

### Proponowana struktura

- **`:core`**: czysto kotlinowy moduł Gradle z powyższymi plikami. Zależą od niego apka i CLI.
- **`:kindle-sync`**: nowy moduł, program uruchamiany z terminala (JVM).

Jedyny większy kawałek pracy to **`AnkiCardCreator`**. Generowanie HTML/CSS karty jest w nim wymieszane z API AnkiDroida (`ContentValues`, TTS, `FileProvider`). Trzeba je rozdzielić:

- część budująca pola karty → `:core`,
- część androidowa, która tylko zapisuje notatkę → zostaje w apce.

Na tym podziale skorzysta też sama apka.

---

## 2. Etapy działania

### 2.1 Wykrycie Kindle i odczyt bazy

Słowa są w pliku `system/vocabulary/vocab.db` (SQLite) na Kindle:

- `WORDS`: `word`, `stem`, `lang`
- `LOOKUPS`: `word_key` (np. `ja:食べる`), `usage` (zdanie, w którym słowo wystąpiło), `timestamp`, `book_key`
- `BOOK_INFO`: tytuł i autorzy książki

Japońskie słowa odfiltrowuje się po `lang = 'ja'`, a dodatkowo po samym tekście (kanji lub kana).

**Wykrycie na Arch:**
- Reguła udev na vendor ID Amazonu (`1949`) z `ENV{SYSTEMD_USER_WANTS}`, która uruchamia usługę użytkownika systemd.
- Nie używać zwykłego `RUN+=` w udev: działa jako root i jest ubijany po kilku sekundach.

**Zasada:** plik najpierw skopiować do katalogu tymczasowego i otwierać tylko kopię. Nigdy nie piszemy po Kindle.

**Niewiadoma: sposób podłączenia.**
- Starsze Kindle podłączają się jako pamięć USB. udisks/udiskie montuje je w `/run/media/$USER/Kindle`.
- Nowsze modele (mniej więcej od 2021–2022 roku, nowszy firmware) używają MTP. Plik trzeba wtedy pobrać przez `libmtp` (`mtp-getfile`) albo zamontować przez `simple-mtpfs`.

### 2.2 Które lookupy przetwarzać

- Znacznik czasu ostatnio przetworzonego lookupa trzymamy w `~/.local/state/kindle-sync/`. Każde podłączenie bierze tylko nowe słowa.
- Znacznik to tylko przyspieszenie. Ochroną przed duplikatami jest sprawdzenie w Anki, tak jak w apce.

### 2.3 Rozpoznanie słowa

- Kindle zapisuje w `stem` swoją formę podstawową, ale dla japońskiego bywa ona po prostu zaznaczonym fragmentem, czyli formą odmienioną albo kawałkiem słowa.
- Dlatego `stem` i `word` przechodzą przez deconjugator i słowniki, tak jak w skanerze tekstu.
- Z `usage` powstaje zdanie źródłowe na przodzie karty, w tym samym polu co w skanerze (`FrontContext`).
- Tytuł książki trafia do tagu.

### 2.4 Słowniki na laptopie

- Te same zip-y co w apce (Jitendex, listy częstotliwości) w `~/.local/share/kindle-sync/`.
- Parsowanie Jitendexu przy każdym uruchomieniu trwa za długo jak na coś, co ma działać samo po podłączeniu. Przy pierwszym imporcie zapisujemy je do lokalnej bazy SQLite (xerial sqlite-jdbc) i potem czytamy z niej.
- Kolejność list częstotliwości i wybór głównej: plik konfiguracyjny albo, lepiej, eksport ustawień z backupu apki. Wtedy laptop przypisze dokładnie ten sam ranking co telefon.

### 2.5 Anki

**Ważne ograniczenie:** kolekcja na laptopie jest aktualna tylko po synchronizacji z AnkiWeb. Porównanie z niezsynchronizowaną kolekcją da duplikaty kart dodanych wczoraj na telefonie.

Kolejność działań:
1. synchronizacja,
2. sprawdzenie duplikatów,
3. dodanie kart,
4. ponowna synchronizacja.

**Wybór: AnkiConnect** (dodatek do Anki desktop, HTTP na `localhost:8765`). Ma wszystko, co potrzebne:

| Akcja | Do czego |
|---|---|
| `sync` | synchronizacja przed i po |
| `findNotes` + `notesInfo` | skan kolekcji, który potem przechodzi przez `AnkiNoteFieldIndexer` |
| `createModel` | typ notatki `Yomitan-Mobile-v8`, jeśli go brak |
| `addNotes` | dodanie kart |
| `storeMediaFile` | audio |

Wymaga uruchomionego Anki, ale skrypt może go sam włączyć i poczekać, aż port odpowie.

**Odrzucona alternatywa:** bezpośredni zapis do pliku kolekcji przez pythonowy pakiet `anki`. To znaczy dodawanie Pythona obok Kotlina i ryzyko uszkodzenia bazy, gdy Anki jest akurat otwarte.

### 2.6 Wynik

- Powiadomienie przez `notify-send`, np. „Kindle: 23 nowe słowa, 17 fiszek dodanych, 6 już w Anki”, plus log.
- Tryb `--dry-run`, który tylko wypisuje, co by dodał. Przyda się na start i do strojenia filtrów.

---

## 3. Otwarte pytania

1. **Model Kindle.** Rozstrzyga, czy łączymy się jako pamięć USB, czy przez MTP. Najprościej: podłączyć i sprawdzić `lsusb` oraz `lsblk`.
2. **AnkiConnect i uruchamianie Anki desktop.** Czy to pasuje? Czy kolekcja synchronizuje się przez AnkiWeb, czy jakiś własny serwer?
3. **W pełni automatycznie czy z potwierdzeniem.** Dodawać od razu po podłączeniu, czy najpierw pokazać listę do odhaczenia? Propozycja: automat plus `--dry-run` i dopracowane filtry.
4. Mniej istotne:
   - talia docelowa: osobna „Kindle” czy ta sama co z telefonu,
   - czy przy pierwszym uruchomieniu przetworzyć całą dotychczasową historię wyszukiwań.

---

## 4. Inna droga (dla porządku)

Kindle podłączony kablem OTG do telefonu i ekran „import z Kindle” w samej apce, bez laptopa. Odpada cała warstwa z Anki desktop i synchronizacją, ale nie spełnia założenia „na laptopie”.
