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
#   --deck      target deck (default: test_kindle)
#   --wait      how long to wait for the Kindle to appear (default: 0)
#
# Environment: KINDLE_SYNC_DICT (Jitendex zip), KINDLE_SYNC_FREQ (frequency
# zip), KINDLE_SYNC_REPO (checkout of YomitanMobile).
set -euo pipefail

DECK=test_kindle
DRY_RUN=false
ALL=false
WAIT=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true ;;
        --all) ALL=true ;;
        --deck) DECK="$2"; shift ;;
        --wait) WAIT="$2"; shift ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

REPO="${KINDLE_SYNC_REPO:-$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)}"
DICT="${KINDLE_SYNC_DICT:-$HOME/yomitan-dicts/jitendex-yomitan.zip}"
FREQ="${KINDLE_SYNC_FREQ:-$HOME/yomitan-dicts/JPDB_v2.2_Frequency.zip}"
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/kindle-sync"
ANKI_CONNECT="${KINDLE_SYNC_ANKI:-http://127.0.0.1:8765}"
mkdir -p "$STATE"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log() { echo "[kindle-sync] $*" >&2; }
notify() { command -v notify-send >/dev/null && notify-send -a "Kindle → Anki" "Kindle → Anki" "$1" || true; }

# ---- 1. find the Kindle and copy vocab.db --------------------------------
# Older Kindles are USB mass storage (mounted by udisks); newer ones, the
# 2021+ Paperwhite included, are MTP only. On KDE the MTP device is held by
# Dolphin's kio worker, so libmtp cannot open it — go through KIO when it is
# there, gio otherwise.
copy_vocab() {
    local dest="$WORK/vocab.db" dir
    for dir in /run/media/"$USER"/Kindle /media/"$USER"/Kindle /run/media/"$USER"/*/ /media/"$USER"/*/; do
        if [[ -f "$dir/system/vocabulary/vocab.db" ]]; then
            cp "$dir/system/vocabulary/vocab.db" "$dest" && return 0
        fi
    done
    if command -v kioclient >/dev/null; then
        local device
        device="$(kioclient ls mtp:/ 2>/dev/null | grep -i kindle | head -1 || true)"
        if [[ -n "$device" ]]; then
            local storage
            while IFS= read -r storage; do
                [[ -z "$storage" || "$storage" == "." ]] && continue
                if kioclient --noninteractive copy \
                    "mtp:/$device/$storage/system/vocabulary/vocab.db" "file://$dest" 2>/dev/null; then
                    return 0
                fi
            done < <(kioclient ls "mtp:/$device" 2>/dev/null)
        fi
    fi
    if command -v gio >/dev/null; then
        local root
        root="$(gio mount -li 2>/dev/null | grep -i 'activation_root=mtp://.*kindle' -m1 | sed 's/.*activation_root=//' || true)"
        if [[ -n "$root" ]]; then
            gio mount "$root" 2>/dev/null || true
            local storage
            for storage in "Internal Storage" "Internal%20Storage"; do
                gio copy "${root}${storage}/system/vocabulary/vocab.db" "$dest" 2>/dev/null && return 0
            done
        fi
    fi
    return 1
}

deadline=$((SECONDS + WAIT))
until copy_vocab; do
    if (( SECONDS >= deadline )); then
        log "no Kindle found (or vocab.db unreadable)"
        exit 1
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
    notify "Brak nowych słów w Vocabulary Builderze."
    exit 0
fi

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
    log "AnkiConnect is not answering on $ANKI_CONNECT"
    notify "Anki (AnkiConnect) nie odpowiada — nic nie dodano."
    exit 1
fi

# ---- 4. the app's pipeline -------------------------------------------------
OUT="$STATE/last-run"
mkdir -p "$OUT"
rm -f "$OUT/summary.txt"
(cd "$REPO" && ./gradlew -q :app:testDebugUnitTest --tests "*KindleSync" --rerun \
    -Dkindle.lookups="$WORK/lookups.tsv" \
    -Ddict.zip="$DICT" \
    -Dfreq.zip="$FREQ" \
    -Dkindle.deck="$DECK" \
    -Dkindle.dryRun="$DRY_RUN" \
    -Danki.connect="$ANKI_CONNECT" \
    -Dout.dir="$OUT") >&2

if [[ ! -f "$OUT/summary.txt" ]]; then
    log "KindleSync produced no summary (see the Gradle output above)"
    notify "Błąd synchronizacji — szczegóły w logu."
    exit 1
fi
SUMMARY="$(cat "$OUT/summary.txt")"
log "$SUMMARY"
log "report: $OUT/kindle-sync.tsv"

# The marker only moves when cards were really written.
if [[ "$DRY_RUN" == false ]]; then
    tail -1 "$WORK/lookups.tsv" | cut -f4 > "$STATE/last_timestamp"
fi

eval "$(echo "$SUMMARY" | tr ' ' '\n' | grep -E '^[a-z_]+=[0-9a-z]+$')"
notify "Kindle: $lookups nowych wyszukań, $new fiszek do talii $DECK, $in_anki już w Anki."
