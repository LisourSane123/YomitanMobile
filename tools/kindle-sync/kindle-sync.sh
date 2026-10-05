#!/usr/bin/env bash
# Kindle Vocabulary Builder → Anki desktop, from the command line.
#
# The work is done by the desktop program in kindle-desktop/ — the same one a
# double-click opens as a window, on Linux, Windows and macOS. This script is
# what the Linux plug-in watcher (kindle-watch.sh) runs, and it takes the flags
# it always took:
#
#   kindle-sync.sh [--dry-run] [--all] [--deck NAME] [--wait SECONDS] [--vocab FILE]
#                  [--no-sync] [--pull-settings] [--refresh-audio] [--refresh-cards]
#                  [--exclude WORD] [--restyle] [--install-native-audio] [--quiet]
#
# `kindle-sync.sh --help` lists them. Settings (dictionaries, deck, language)
# live in ~/.local/share/kindle-sync/config.properties, written by the window's
# Settings dialog; the old KINDLE_SYNC_* environment variables still work.
#
# The jar is rebuilt when the code under it changed, so a `git pull` takes
# effect on the next plug-in instead of running yesterday's build.
set -euo pipefail

REPO="${KINDLE_SYNC_REPO:-$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)}"
JAR="$REPO/kindle-desktop/build/libs/kindle-sync.jar"

stale() {
    [[ ! -f "$JAR" ]] && return 0
    [[ -n "$(find "$REPO/core/src/main" "$REPO/kindle-desktop/src/main" "$REPO/kindle-desktop/build.gradle.kts" \
        -newer "$JAR" -print -quit 2>/dev/null)" ]]
}

if stale; then
    echo "[kindle-sync] building the desktop program…" >&2
    if ! (cd "$REPO" && ./gradlew -q :kindle-desktop:fatJar) >&2; then
        # A failed build must not end in silence either: the watcher has no
        # terminal, so the desktop is told.
        command -v notify-send >/dev/null &&
            notify-send -a "Kindle → Anki" -u critical -t 0 "Kindle → Anki" \
                "Nie wykonano: nie udało się zbudować programu (journalctl --user -u kindle-watch)." || true
        exit 1
    fi
fi

exec java -Xmx3g -jar "$JAR" "$@"
