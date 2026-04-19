# YomitanMobile - HOW TO

Ten dokument opisuje najczestsze scenariusze uzycia aplikacji krok po kroku.

## 1. Build i uruchomienie

Wymagania:
- JDK 17
- Android SDK (API 34)
- local.properties ze wskazaniem sdk.dir

Komendy:

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 2. Pierwsze uruchomienie i slowniki

1. Otworz Ustawienia.
2. Wybierz:
- Pobierz slowniki z internetu, albo
- Importuj slownik Yomitan (.zip).
3. Poczekaj na zakonczenie importu.
4. Sprawdz czy slowniki sa widoczne na liscie zainstalowanych.

Uwagi:
- Meta-slowniki (czestotliwosc, pitch accent) nadpisuja/uzupelniaja dane istniejacych wpisow.
- Duze paczki moga importowac sie dluzej.

## 3. Wyszukiwanie slow

Tryby wyszukiwania:
- JP: japonski (kanji/kana)
- EN: po definicji angielskiej
- RM: romaji

Kroki:
1. Wejdz na ekran wyszukiwania.
2. Wpisz zapytanie.
3. W trybie JP skorzystaj z podpowiedzi form podstawowych (deconjugation), jesli sie pojawia.
4. Kliknij wynik, aby przejsc do szczegolow.

## 4. Eksport do Anki

1. Otworz szczegoly slowa.
2. Kliknij + (eksport).
3. Aplikacja pokaze score jakosci karty i powod.
4. Potwierdz eksport.
5. Przy pierwszym razie wybierz talie Anki.

Co trafia do karty:
- Slowo, czytanie, znaczenie
- Pitch accent (jezeli jest)
- Czestotliwosc (jezeli jest)
- Audio TTS (jezeli dostepne)
- Zdanie przykladowe (jezeli jest)
- Rozbicie kanji (jezeli dane sa dostepne)

## 5. Kontekst na froncie fiszki (nowa opcja)

Cel: pod slowem na froncie pokazac japonskie zdanie z pogrubionym targetem.

Jak wlaczyc:
1. Ustawienia -> Wyglad fiszki.
2. Wlacz przelacznik: Zdanie kontekstowe na froncie.
3. Zapisz ustawienia.
4. Eksportuj nowe karty.

Jak to dziala:
- Aplikacja bierze japonskie zdanie przykladowe.
- Probuje pogrubic najpierw expression, potem reading.
- Tekst jest sanitizowany (bezpieczny HTML).

## 6. Zdania z internetu (opcja)

Funkcja jest opcjonalna i wymaga zgody.

Jak wlaczyc:
1. Ustawienia -> Zgoda na API zdan (wlacz).
2. Wyglad fiszki -> Zdanie z internetu (API) (wlacz).

Jak dziala:
- Przy eksporcie aplikacja moze pobrac zdanie przykladowe online.
- Bez zgody funkcja nie jest aktywna.

## 7. Share to app (udostepnianie tekstu)

Mozesz wyslac tekst z innej aplikacji bezposrednio do YomitanMobile.

Kroki:
1. W innej aplikacji zaznacz tekst.
2. Wybierz Udostepnij.
3. Wybierz YomitanMobile.
4. Tekst trafi do pola wyszukiwania.

## 8. Najczestsze problemy

### Brak wynikow
- Sprawdz czy slownik jest zainstalowany.
- Sprawdz pisownie.
- W JP sprawdz podpowiedzi form podstawowych.

### Export do Anki nie dziala
- Sprawdz czy AnkiDroid jest zainstalowany.
- Sprawdz uprawnienie READ_WRITE_DATABASE.
- Sprawdz, czy wybrana talia istnieje lub utworz nowa.

### Build nie przechodzi
- Sprawdz JDK 17.
- Sprawdz local.properties i sdk.dir.
- Uruchom:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 9. Komendy developerskie

```bash
# testy jednostkowe
./gradlew :app:testDebugUnitTest

# build debug
./gradlew :app:assembleDebug

# testy + build
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
