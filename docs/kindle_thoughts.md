# Kindle Vocabulary Builder → Anki (laptop, Arch Linux)

Notatki projektowe.

**Stan (2026-09-18): pierwsza wersja działa, na razie do talii `test_kindle`.**

- `tools/kindle-sync/kindle-sync.sh`: wykrywa Kindle, kopiuje `vocab.db`, zrzuca nowe lookupy do TSV, sprawdza, czy działa AnkiConnect (w razie potrzeby uruchamia Anki), wysyła powiadomienie.
- `app/src/test/.../tools/KindleSync.kt`: cała logika na kodzie apki (deconjugator, `ScanEntryResolver`, `AnkiNoteFieldIndexer` na całej kolekcji desktopowej, `AnkiCardCreator.createAnkiCard`). Działa tak jak `BookScanHarness`, czyli jako test Gradle z `-D…`, pod Robolectrikiem, bo `AnkiCardCreator` wymaga `Context`. Moduł `:core` (sekcja 1) nadal jest do zrobienia. Obecny sposób omija go bez kopiowania reguł.
- `tools/kindle-sync/install.sh` → `kindle-watch.service`: usługa użytkownika, która słucha `udevadm monitor` i po podłączeniu urządzenia o vendor ID 1949 uruchamia jeden przebieg. Nie wymaga roota. Reguła udev z `SYSTEMD_USER_WANTS` z sekcji 2.1 wymagała pliku w `/etc`, więc z niej zrezygnowałem. Przez cały przebieg nic się nie wyświetla, na końcu przychodzi jedno powiadomienie „Wykonano: …” albo „Nie wykonano: …”.
- **Karty na poziomie telefonu:** te same zip-y co w katalogu apki (kanjium pitch, KANJIDIC), pola budowane przez `AnkiCardCreator.rebuildFields` (losowa czcionka, wykres akcentu, rozbiór kanji), lokalizacja `pl` w Robolectricu, żeby etykiety były po polsku.
  - Ustawienia stylu pochodzą z `settings.json` backupu apki (`~/.local/share/kindle-sync/settings.json`). Kopia odświeża się przez adb za każdym razem, gdy telefon jest podłączony. Bez niej czcionki są odczytywane z ostatnich 300 kart telefonu. To jedyne ustawienie stylu, które trafia do pól karty, bo reszta siedzi w CSS note type'a, a ten synchronizuje telefon.
  - Audio: **VOICEVOX** (neuronowy, offline, modele w `~/.local/share/kindle-sync/voicevox/core`) przez `data/audio/voicevox/VoicevoxSpeaker`, **tę samą klasę, której używa apka**, uruchamianą na desktopowych bindingach Javy. Python (`tts.py`, venv, Open JTalk) został usunięty. Open JTalk był pierwszą wersją i brzmiał robotycznie. Głośność była w porządku, zła była jakość. VOICEVOX dostaje **czytanie z akcentem wpisanym z kanjium** (notacja AquesTalk: `ツキツケ'ル`), więc nagranie mówi dokładnie to, co karta pokazuje na wykresie akcentu. Głos jest losowany per słowo spośród neutralnych lektorów (VOICEVOX Nemo 女声1-3/男声1-2, No.7 アナウンス), a nie spośród głosów postaci. Głośność wyrównuje `WavLoudness` (RMS −18 dBFS, szczyty najwyżej −1 dBFS), ten sam kod co na telefonie. Nazwa pliku zawiera silnik (`yomitan_kindle_vv_…`), bo AnkiDroid cache'uje media po nazwie.
  - Instalacja modeli VOICEVOX (raz, ręcznie, bo trzeba zaakceptować regulamin głosów; przy publikacji nagrań wymaga on podpisu „VOICEVOX:postać”): `download-linux-x64 --exclude c-api -o ~/.local/share/kindle-sync/voicevox/core` z releases `VOICEVOX/voicevox_core`.
  - **Archiwum nagrań ma pierwszeństwo przed TTS:** folder `~/.local/share/kindle-sync/audio-archive` (albo `KINDLE_SYNC_AUDIO`), indeksowany przez `AudioKeys` z apki, więc nazwy plików są rozumiane tak jak na telefonie. Nagranie jest normalizowane do tej samej głośności co TTS, a nazwa pliku ma prefiks `yomitan_kindle_ar_`. Na dyskach nie było żadnego archiwum. Wolne źródła są za małe (Lingua Libre ma 1043 japońskie nagrania, Commons 248). Praktyczne źródło to paczka local-audio-yomichan (JapanesePod101, NHK16, 新明解8, Forvo, kilka GB). Nie da się jej legalnie redystrybuować, więc użytkownik przynosi ją sam, tak jak w apce. Seryjne pobieranie nagrań z endpointu JapanesePod101 zablokował tryb auto.
  - `kindle-sync.sh --refresh-audio` nagrywa pole Audio wszystkich fiszek `from_kindle` od nowa, w miejscu (`updateNoteFields`), z zachowaniem historii powtórek.
- Odpowiedzi na otwarte pytania:
  1. Paperwhite `1949:9981` to **MTP**, nie pamięć USB. Na KDE urządzenie trzyma kio-worker Dolphina, więc libmtp (`mtp-getfile`) dostaje „device busy”. Plik ściągamy przez `kioclient copy mtp:/…`, a `gio` i zamontowana pamięć USB są fallbackiem.
  2. AnkiConnect działa.
  3. Karty dodawane są automatycznie. `--dry-run` tylko raportuje, a raport z każdego przebiegu trafia do `~/.local/state/kindle-sync/last-run/kindle-sync.tsv`.
- Kindle w `stem` zapisuje dla nieznanego słowa **czytanie** hasła dopasowanego na początku zaznaczenia (親族会議 → しんぞく, ブリッヂオ → ぶり). Takie dopasowanie liczy się więc tylko wtedy, gdy hasło jest zapisane na początku zaznaczenia.
- `usage` to okno tekstu, a nie zdanie. Pierwszy lookup w książce ciągnie za sobą stronę tytułową, dlatego zdanie jest wycinane.
- **Sync z AnkiWeb przed i po** (akcja `sync` w AnkiConnect). Jeśli sync przed dodaniem nie przejdzie, przebieg się zatrzymuje i nic nie dodaje. Jeśli nie przejdzie ten po dodaniu, karty są już w kolekcji, a powiadomienie o tym ostrzega.
- **AutoReorder po dodaniu:** wtyczka sama uruchamia się tylko przy starcie Anki i z menu, a AnkiConnect nie może jej wywołać. Jej algorytm (10 linijek) jest więc powtórzony w `KindleSync.AnkiConnect.reorder`, z ustawieniami czytanymi z konfiguracji wtyczki (`meta.json` nadpisuje `config.json`). Pozycje zapisuje `setSpecificValueOfCard` (`update_card`), więc zmiana synchronizuje się jak każda inna. Sprawdzone: na talii Japanese wynik zgadza się z tym, co zostawiła wtyczka (0 przesunięć). Wtyczka sortuje tylko `deck:Japanese is:new`, więc karty w `test_kindle` ustawi dopiero po przełączeniu talii.

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
