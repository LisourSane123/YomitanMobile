#!/usr/bin/env bash
# The one place kindle-sync talks to the desktop. Both halves of a run use it:
# kindle-sync.sh directly, KindleSync.kt through -Dkindle.notifyScript.
#
#   notify.sh progress MESSAGE   a step: one bubble, rewritten in place
#   notify.sh done MESSAGE       the result: the progress bubble goes away and
#                                the result arrives as a NEW bubble that stays
#                                until it is dismissed
#
# KINDLE_NOTIFY_ID_FILE holds the progress bubble's id between calls.
#
# Why the progress bubble never expires and the result is a new one: Plasma
# expires a bubble after ~5 s and sends NotificationClosed for it. A later
# `notify-send -r <id>` is answered with the same id and then shows nothing —
# the notification it names is dead. A run spends 20 s just starting Gradle,
# so every step after "Odczytano słówka", the result included, went into a
# dead bubble: two pop-ups, then silence, and no way to tell a finished run
# from a hung one. Measured with dbus-monitor on Plasma 6.7 (closed, reason 1).
#
# Backends: notify-send (any freedesktop desktop: KDE, GNOME, XFCE…), gdbus
# when libnotify's CLI is missing, osascript on macOS. Missing all of them is
# not an error — the log still has every line.
set -uo pipefail

kind="${1:-}"
message="${2:-}"
title="Kindle → Anki"
id_file="${KINDLE_NOTIFY_ID_FILE:-}"
id="$( [[ -n "$id_file" ]] && cat "$id_file" 2>/dev/null || true)"

remember() { [[ -n "$id_file" && "$1" =~ ^[0-9]+$ && "$1" != 0 ]] && echo "$1" > "$id_file"; return 0; }

close_progress() {
    [[ -n "$id" ]] || return 0
    command -v gdbus >/dev/null &&
        gdbus call --session --dest org.freedesktop.Notifications \
            --object-path /org/freedesktop/Notifications \
            --method org.freedesktop.Notifications.CloseNotification "$id" >/dev/null 2>&1
    [[ -n "$id_file" ]] && rm -f "$id_file"
    return 0
}

# A failed run is said with an error icon and critical urgency, which also
# keeps it on screen on servers that ignore the timeout of a normal one.
urgency=normal
icon=dialog-information
if [[ "$kind" == done && "$message" == "Nie wykonano"* ]]; then urgency=critical; icon=dialog-error; fi
[[ "$kind" == progress ]] && icon=emblem-synchronizing

send_linux() { # $1 = id to replace, or empty; prints the new id
    if command -v notify-send >/dev/null; then
        # -p (print id) needs libnotify 0.7.9+; an older one prints nothing,
        # and every step becomes its own bubble — noisy, but nothing is lost.
        notify-send -a "$title" -i "$icon" -u "$urgency" -t 0 -p ${1:+-r "$1"} "$title" "$message" 2>/dev/null
    elif command -v gdbus >/dev/null; then
        gdbus call --session --dest org.freedesktop.Notifications \
            --object-path /org/freedesktop/Notifications \
            --method org.freedesktop.Notifications.Notify \
            "$title" "${1:-0}" "$icon" "$title" "$message" '[]' \
            "{'urgency': <byte $([[ $urgency == critical ]] && echo 2 || echo 1)>}" 0 2>/dev/null |
            sed -E 's/^\(uint32 ([0-9]+),\)$/\1/'
    fi
}

send_macos() {
    # Notification Center has no replace: each step is its own entry, grouped
    # under the sender. AppleScript string escaping: backslash and quote.
    local m="${message//\\/\\\\}"; m="${m//\"/\\\"}"
    osascript -e "display notification \"$m\" with title \"$title\"" >/dev/null 2>&1
}

case "$(uname -s)" in
    Darwin)
        send_macos
        ;;
    *)
        case "$kind" in
            progress) remember "$(send_linux "$id")" ;;
            done) close_progress; send_linux "" >/dev/null ;;
            *) echo "usage: notify.sh progress|done MESSAGE" >&2; exit 2 ;;
        esac
        ;;
esac
exit 0
