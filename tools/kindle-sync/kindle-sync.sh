#!/usr/bin/env bash
# Kindle Vocabulary Builder → Anki desktop.
#
# Finds a connected Kindle, copies system/vocabulary/vocab.db off it (the
# Kindle is only ever read), dumps the Japanese lookups made since the last
# run and hands them to KindleSync (app/src/test/.../tools/KindleSync.kt),
# which runs the app's own pipeline and adds the cards through AnkiConnect.
#
#   kindle-sync.sh [--dry-run] [--all] [--deck NAME] [--wait SECONDS]
#
#   --dry-run   only report what would be added
#   --all       ignore the last-run marker and process every lookup
#   --deck      target deck (default: Japanese). It has to sit inside what
#               AutoReorder is configured to sort (its `search_to_sort`,
#               `deck:Japanese is:new` here) or the new cards are never put in
#               frequency order — and, with `shift_existing` on, are pushed
#               behind the whole existing new queue. A subdeck counts.
#   --wait      how long to wait for the Kindle to appear (default: 0)
#   --vocab     use this vocab.db instead of the Kindle's (testing)
#   --no-sync   skip the AnkiWeb sync before and after (on by default)
#   --refresh-audio
#               re-record the Audio field of every from_kindle card with the
#               current voice, in place (review history kept); no Kindle needed
#   --pull-settings
#               only copy the phone's card style over adb and exit (see below)
#
# Environment: KINDLE_SYNC_DICT (Jitendex zip), KINDLE_SYNC_FREQ (frequency
# zip), KINDLE_SYNC_PITCH, KINDLE_SYNC_KANJI, KINDLE_SYNC_REPO (checkout of
# YomitanMobile). The phone's card style is read from
# ~/.local/share/kindle-sync/settings.json (an app backup's settings.json),
# refreshed automatically whenever the phone is reachable over adb.
set -euo pipefail

DECK=Japanese
DRY_RUN=false
ALL=false
WAIT=0
VOCAB=
SYNC=true
REFRESH_AUDIO=false
PULL_SETTINGS=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true ;;
        --all) ALL=true ;;
        --deck) DECK="$2"; shift ;;
        --wait) WAIT="$2"; shift ;;
        --vocab) VOCAB="$2"; shift ;;
        --no-sync) SYNC=false ;;
        --refresh-audio) REFRESH_AUDIO=true ;;
        --pull-settings) PULL_SETTINGS=true ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

REPO="${KINDLE_SYNC_REPO:-$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)}"
DICT="${KINDLE_SYNC_DICT:-$HOME/yomitan-dicts/jitendex-yomitan.zip}"
FREQ="${KINDLE_SYNC_FREQ:-$HOME/yomitan-dicts/JPDB_v2.2_Frequency.zip}"
PITCH="${KINDLE_SYNC_PITCH:-$HOME/yomitan-dicts/kanjium_pitch_accents.zip}"
KANJI="${KINDLE_SYNC_KANJI:-$HOME/yomitan-dicts/KANJIDIC_english.zip}"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}/kindle-sync"
# VOICEVOX as its own downloader lays it out (models/vvms, dict, onnxruntime).
VOICEVOX="${KINDLE_SYNC_VOICEVOX:-$DATA/voicevox/core}"
VOICEVOX_RUNTIME="$(ls "$VOICEVOX"/onnxruntime/lib/libvoicevox_onnxruntime.so* 2>/dev/null | head -1 || true)"
# A folder of native recordings (local-audio-yomichan, a Forvo dump…), used
# before any TTS. File names are read the way the phone reads them.
AUDIO_ARCHIVE="${KINDLE_SYNC_AUDIO:-$DATA/audio-archive}"
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/kindle-sync"
ANKI_CONNECT="${KINDLE_SYNC_ANKI:-http://127.0.0.1:8765}"
mkdir -p "$STATE" "$DATA"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log() { echo "[kindle-sync] $*" >&2; }
notify() { command -v notify-send >/dev/null && notify-send -a "Kindle → Anki" "Kindle → Anki" "$1" || true; }
# The one message a run ends with, success or not — the service is silent
# otherwise.
fail() { log "$1"; notify "Nie wykonano: $1"; exit 1; }

# ---- 1. find the Kindle and copy vocab.db --------------------------------
# Older Kindles are USB mass storage (mounted by udisks); newer ones, the
# 2021+ Paperwhite included, are MTP only. On KDE the MTP device is held by
# Dolphin's kio worker, so libmtp cannot open it — go through KIO when it is
# there, gio otherwise.
copy_vocab() {
    local dest="$WORK/vocab.db" dir
    for dir in /run/media/"$USER"/Kindle /media/"$USER"/Kindle /run/media/"$USER"/*/ /media/"$USER"/*/; do
        if [[ -f "$dir/system/vocabulary/vocab.db" ]]; then
            cp "$dir/system/vocabulary/vocab.db" "$dest" && { log "vocab.db from $dir (mass storage)"; return 0; }
        fi
    done
    if command -v kioclient >/dev/null; then
        local device
        device="$(kioclient ls mtp:/ 2>/dev/null | grep -i kindle | head -1 || true)"
        if [[ -n "$device" ]]; then
            local storage path
            while IFS= read -r storage; do
                [[ -z "$storage" || "$storage" == "." ]] && continue
                # Storage names have spaces in them ("Internal Storage"), and
                # whether a KIO url wants them raw or encoded is not something
                # to find out on the one evening the Kindle is plugged in.
                for path in "mtp:/$device/$storage/system/vocabulary/vocab.db" \
                            "mtp:/${device// /%20}/${storage// /%20}/system/vocabulary/vocab.db"; do
                    if kioclient --noninteractive copy "$path" "file://$dest" 2>/dev/null; then
                        log "vocab.db from $path (kio)"
                        return 0
                    fi
                done
            done < <(kioclient ls "mtp:/$device" 2>/dev/null)
        fi
    fi
    if command -v gio >/dev/null; then
        local root
        root="$(gio mount -li 2>/dev/null | grep -i 'activation_root=mtp://.*kindle' -m1 | sed 's/.*activation_root=//' || true)"
        if [[ -n "$root" ]]; then
            gio mount "$root" 2>/dev/null || true
            local storage
            # Whatever the device calls its storage, rather than a guess at it.
            while IFS= read -r storage; do
                [[ -z "$storage" ]] && continue
                gio copy "${storage%/}/system/vocabulary/vocab.db" "$dest" 2>/dev/null &&
                    { log "vocab.db from $storage (gio)"; return 0; }
            done < <(gio list "$root" -u 2>/dev/null)
            for storage in "Internal Storage" "Internal%20Storage"; do
                gio copy "${root}${storage}/system/vocabulary/vocab.db" "$dest" 2>/dev/null &&
                    { log "vocab.db from ${root}${storage} (gio)"; return 0; }
            done
        fi
    fi
    return 1
}

# A Kindle on the USB bus, by the vendor id the udev watcher matches on. Tells
# "no Kindle here" apart from "a Kindle that will not hand over vocab.db",
# which are two different things to do something about.
kindle_on_usb() {
    local id
    for id in /sys/bus/usb/devices/*/idVendor; do
        [[ -r "$id" && "$(cat "$id")" == 1949 ]] && return 0
    done
    return 1
}

# ---- 1b. the phone's card style -------------------------------------------
# The app's backups live in its external files dir, which adb can read. A
# phone that is not plugged in is the normal case during a Kindle run, so this
# never fails the run — it says which step did not happen and leaves the
# settings.json of the last successful pull in place, which is still the
# phone's style. Every branch logs: a style silently guessed from old cards is
# how the fonts drift apart between the two collections.
pull_phone_settings() {
    local adb backups latest
    adb="$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")"
    if [[ ! -x "$adb" ]]; then
        log "phone settings: no adb (install android-tools or the platform-tools)"
        return 1
    fi
    if ! "$adb" get-state >/dev/null 2>&1; then
        log "phone settings: no phone over adb, keeping $([[ -f "$DATA/settings.json" ]] && echo "the last pull" || echo "none")"
        return 1
    fi
    backups=/sdcard/Android/data/com.yomitanmobile/files/yomitan_backups
    latest="$("$adb" shell "ls -1d $backups/*/ 2>/dev/null | sort | tail -1" | tr -d '\r')"
    if [[ -z "$latest" ]]; then
        log "phone settings: no backup in $backups — make one in the app (Ustawienia → kopia zapasowa)"
        return 1
    fi
    if ! "$adb" pull "${latest%/}/settings.json" "$DATA/settings.json.new" >/dev/null 2>&1; then
        log "phone settings: ${latest%/}/settings.json could not be pulled"
        rm -f "$DATA/settings.json.new"
        return 1
    fi
    mv "$DATA/settings.json.new" "$DATA/settings.json"
    log "phone settings refreshed from $latest"
}

# --pull-settings is that step on its own: run it once with the phone plugged
# in, and every later Kindle run uses the style it left behind.
if [[ "$PULL_SETTINGS" == true ]]; then
    if pull_phone_settings; then
        notify "Wykonano: styl fiszek pobrany z telefonu."
    else
        fail "nie pobrano stylu z telefonu, szczegóły w logu"
    fi
    exit 0
fi

# --refresh-audio works on cards already in Anki: no Kindle, no lookups.
if [[ "$REFRESH_AUDIO" == false ]]; then
    deadline=$((SECONDS + WAIT))
    until { [[ -n "$VOCAB" ]] && cp "$VOCAB" "$WORK/vocab.db"; } || copy_vocab; do
        if (( SECONDS >= deadline )); then
            if kindle_on_usb; then
                fail "Kindle jest podłączony, ale nie udało się odczytać vocab.db (odblokuj ekran, sprawdź tryb USB)"
            fi
            fail "nie znaleziono Kindle"
        fi
        sleep 3
    done
    # Keep the last copy around: it is the evidence when a card looks wrong.
    cp "$WORK/vocab.db" "$STATE/vocab.last.db"
    log "vocab.db copied"

    # ---- 2. the lookups since the last run -------------------------------------
    SINCE=0
    if [[ "$ALL" == false && -f "$STATE/last_timestamp" ]]; then
        SINCE="$(cat "$STATE/last_timestamp")"
    fi
    # Tabs and newlines inside the sentence would break the TSV.
    sqlite3 -readonly -separator $'\t' "$WORK/vocab.db" "
        SELECT w.word, w.stem,
               replace(replace(replace(l.usage, char(9), ' '), char(10), ' '), char(13), ' '),
               l.timestamp, coalesce(b.title, '')
        FROM LOOKUPS l
        JOIN WORDS w ON w.id = l.word_key
        LEFT JOIN BOOK_INFO b ON b.id = l.book_key
        WHERE w.lang = 'ja' AND l.timestamp > $SINCE
        ORDER BY l.timestamp;" > "$WORK/lookups.tsv"
    COUNT=$(wc -l < "$WORK/lookups.tsv")
    log "$COUNT new lookups since $SINCE"
    if (( COUNT == 0 )); then
        notify "Wykonano: brak nowych słów w Vocabulary Builderze."
        exit 0
    fi

    pull_phone_settings || true
fi

# ---- 2c. the voice for the Audio field ------------------------------------
# VOICEVOX's models come with terms of use that a person has to accept, so
# they are not fetched here — docs/kindle_thoughts.md has the one-time step.
# Without them the cards are made without audio rather than not at all.
[[ -n "$VOICEVOX_RUNTIME" && -d "$VOICEVOX/models/vvms" ]] || log "VOICEVOX missing in $VOICEVOX, cards go without audio"

# ---- 3. Anki must be answering ---------------------------------------------
if ! curl -s -m 3 "$ANKI_CONNECT" -d '{"action":"version","version":6}' | grep -q '"result"'; then
    if command -v anki >/dev/null && ! pgrep -x anki >/dev/null && ! pgrep -f '/usr/bin/anki' >/dev/null; then
        log "starting Anki"
        (setsid anki >/dev/null 2>&1 &)
    fi
    for _ in $(seq 1 40); do
        curl -s -m 3 "$ANKI_CONNECT" -d '{"action":"version","version":6}' | grep -q '"result"' && break
        sleep 3
    done
fi
if ! curl -s -m 3 "$ANKI_CONNECT" -d '{"action":"version","version":6}' | grep -q '"result"'; then
    fail "Anki (AnkiConnect) nie odpowiada, nic nie dodano"
fi

# AutoReorder, found by name: its folder is an AnkiWeb id, not a name.
REORDER_ADDON=
for meta in "$HOME"/.local/share/Anki2/addons21/*/meta.json; do
    if grep -q '"name": *"AutoReorder"' "$meta" 2>/dev/null; then REORDER_ADDON="$(dirname "$meta")"; fi
done

# ---- 4. the app's pipeline -------------------------------------------------
OUT="$STATE/last-run"
mkdir -p "$OUT"
rm -f "$OUT/summary.txt"

if [[ "$REFRESH_AUDIO" == true ]]; then
    (cd "$REPO" && ./gradlew -q :app:testDebugUnitTest --tests "*KindleSync.refreshAudio" --rerun \
        -Dkindle.refreshAudio=true \
        -Dpitch.zip="$PITCH" \
        -Dvoicevox.root="$VOICEVOX" \
        -Dvoicevox.onnxruntime="$VOICEVOX_RUNTIME" \
        -Daudio.archive="$AUDIO_ARCHIVE" \
        -Dkindle.sync="$SYNC" \
        -Danki.connect="$ANKI_CONNECT" \
        -Dout.dir="$OUT") >&2 || true
    [[ -f "$OUT/summary.txt" ]] || fail "nie udało się odświeżyć audio, szczegóły w logu"
    eval "$(tr ' ' '\n' < "$OUT/summary.txt" | grep -E '^[a-z_]+=-?[0-9a-z]+$')"
    MESSAGE="Wykonano: nowe audio w $refreshed z $notes fiszek from_kindle."
    if [[ "$synced_after" != "true" ]]; then MESSAGE+=" UWAGA: synchronizacja nie przeszła — kliknij Sync w Anki."; fi
    notify "$MESSAGE"
    exit 0
fi
(cd "$REPO" && ./gradlew -q :app:testDebugUnitTest --tests "*KindleSync" --rerun \
    -Dkindle.lookups="$WORK/lookups.tsv" \
    -Ddict.zip="$DICT" \
    -Dfreq.zip="$FREQ" \
    -Dpitch.zip="$PITCH" \
    -Dkanji.zip="$KANJI" \
    -Dkindle.settings="$DATA/settings.json" \
    -Dvoicevox.root="$VOICEVOX" \
    -Dvoicevox.onnxruntime="$VOICEVOX_RUNTIME" \
    -Daudio.archive="$AUDIO_ARCHIVE" \
    -Dkindle.deck="$DECK" \
    -Dkindle.dryRun="$DRY_RUN" \
    -Dkindle.sync="$SYNC" \
    -Danki.reorderAddon="$REORDER_ADDON" \
    -Danki.connect="$ANKI_CONNECT" \
    -Dout.dir="$OUT") >&2 || true

if [[ ! -f "$OUT/summary.txt" ]]; then
    fail "błąd przetwarzania albo synchronizacji przed dodaniem (nic nie dodano), szczegóły: journalctl --user -u kindle-watch"
fi
SUMMARY="$(cat "$OUT/summary.txt")"
log "$SUMMARY"
log "report: $OUT/kindle-sync.tsv"
# What the pipeline itself said. Gradle swallows a test's stdout, so this file
# is the only place a per-word failure (a word the voice refused, a note Anki
# would not take) is ever written down.
grep -E "not added|no recording|tts failed|not reordering|failed" "$OUT/run.log" 2>/dev/null | while IFS= read -r line; do
    log "$line"
done
eval "$(echo "$SUMMARY" | tr ' ' '\n' | grep -E '^[a-z_]+=-?[0-9a-z]+$')"

# The marker moves only when the lookups were really dealt with: cards were
# written, or there was nothing to write (everything already in Anki, filtered
# or not in the dictionary). A run that planned cards and added none — Anki
# refused the note type, the media write failed — must be repeatable, and
# without this the words were gone until the next --all.
if [[ "$DRY_RUN" == false ]] && (( added > 0 || new == 0 )); then
    tail -1 "$WORK/lookups.tsv" | cut -f4 > "$STATE/last_timestamp"
elif [[ "$DRY_RUN" == false ]]; then
    log "marker not moved: $new cards planned, $added added — the run repeats next time"
fi
MESSAGE="Wykonano: $added nowych fiszek w talii $DECK ($lookups wyszukań, $in_anki już było w Anki)."
if [[ "$DRY_RUN" == false ]] && (( added == 0 && new > 0 )); then
    MESSAGE="Nie wykonano: $new fiszek do dodania, żadna nie weszła — spróbuję ponownie przy następnym podłączeniu."
fi
if [[ "$reordered" != "-1" ]]; then MESSAGE+=" Kolejność ustawiona (AutoReorder)."; fi
if [[ "$reorder_covers" == "false" ]]; then
    MESSAGE+=" UWAGA: AutoReorder nie obejmuje talii $DECK — nowe fiszki nie są ułożone wg częstości."
fi
if [[ "$synced_after" != "true" ]]; then MESSAGE+=" UWAGA: synchronizacja po dodaniu nie przeszła — kliknij Sync w Anki."; fi
notify "$MESSAGE"
