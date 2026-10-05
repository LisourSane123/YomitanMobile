# Kindle → Anki (desktop)

Turns the words you looked up on a Kindle (Vocabulary Builder) into Anki
flashcards: the same cards the YomitanMobile phone app makes, with the
sentence from the book, pitch accent, kanji breakdown and audio. It skips
words that are already in your Anki collection.

Plug the Kindle in, click **Sync from Kindle**. That's it.

## What you need

1. **Anki** (desktop) with the **AnkiConnect** add-on: in Anki, *Tools → Add-ons
   → Get Add-ons…*, code `2055492159`, then restart Anki. Anki must be running
   (the program starts it if it can find it).
2. **The dictionaries**: the same Yomitan `.zip` files the phone app installs.
   Only the term dictionary (e.g. Jitendex) is required; a frequency list,
   pitch accents (kanjium) and KANJIDIC make the cards complete. Point to
   them once in **Settings**.
3. *(optional)* **Audio**: Kanji alive's native-speaker recordings
   (`--install-native-audio`, ~74 MB) and/or VOICEVOX installed with its own
   downloader. Without either, cards are made without audio.

## Install

Download the package for your system from the latest
[*Kindle desktop* build](../../actions/workflows/kindle-desktop.yml) (Artifacts):

| System | File | Notes |
|---|---|---|
| Windows | `Kindle-Anki-1.0.0.msi` | Unsigned: SmartScreen asks once — *More info → Run anyway*. |
| macOS | `Kindle-Anki-1.0.0.dmg` | Unsigned: right-click the app → *Open* the first time. |
| Debian, Ubuntu, Mint… | `kindle-anki_1.0.0_amd64.deb` | `sudo apt install ./kindle-anki_1.0.0_amd64.deb` |
| Any other Linux | `Kindle-Anki-linux-x64.tar.gz` | Unpack, run `Kindle-Anki/bin/Kindle-Anki`. |
| Anything with Java 17+ | `kindle-sync.jar` | `java -jar kindle-sync.jar` |

Each package carries its own Java; nothing else to install.

## Getting vocab.db off the Kindle

Older Kindles show up as a USB drive on every system. Newer ones (2021+
Paperwhite and later) use MTP:

- **Windows**: works out of the box (through Explorer).
- **Linux**: works on KDE (KIO) and GNOME and most others (gio). Unlock the
  Kindle's screen first.
- **macOS**: macOS cannot read MTP by itself. Either install libmtp
  (`brew install libmtp`) or copy `system/vocabulary/vocab.db` off the
  Kindle with any MTP tool and use **From a vocab.db file…**.

If automatic copying ever fails, **From a vocab.db file…** always works.

## Automatic sync on plug-in (Linux)

`tools/kindle-sync/install.sh` installs a user service that runs the sync
whenever a Kindle is plugged in, with desktop notifications. It needs systemd
and a desktop session that starts `graphical-session.target` (KDE, GNOME).

## Command line

`java -jar kindle-sync.jar --help` — the same flags as `tools/kindle-sync/kindle-sync.sh`:
`--dry-run`, `--all`, `--deck`, `--vocab`, `--refresh-cards`, `--restyle`, …

## Build it yourself

```
./gradlew :kindle-desktop:fatJar      # build/libs/kindle-sync.jar
./gradlew :kindle-desktop:jpackage    # an installer for the system you are on
```
