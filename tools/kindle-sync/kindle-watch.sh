#!/usr/bin/env bash
# Waits for a Kindle to be plugged in and runs kindle-sync.sh once per plug.
#
# Listens to udev's own event stream, which needs no root — the alternative, a
# udev rule with SYSTEMD_USER_WANTS, needs a file in /etc. Runs as the user
# service kindle-watch.service (see install.sh); kindle-sync.sh is silent
# until it ends with a single desktop notification.
set -uo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
LOCK="${XDG_RUNTIME_DIR:-/tmp}/kindle-sync.lock"

# Amazon/Lab126. The same id the udev events carry.
VENDOR=1949

# How long one run may take before it is stopped. Generous: the longest real
# run on this machine (80 cards, audio synthesised for each) took under a
# minute. See [run].
RUN_TIMEOUT=30m

# Starts one run. The cards go to kindle-sync.sh's default deck, Japanese —
# the one AutoReorder sorts (`deck:Japanese is:new`), so they arrive in
# frequency order. A deck outside that search would never be ordered;
# kindle-sync.sh says so in the notification if it ever is.
#
# One run at a time, and a plug-in that arrives during one is dropped — a
# single plug already produces several usb_device events.
#
# Three things about this lock, each of them learned the hard way:
#
#   -o   the command's CHILDREN must not inherit the lock file descriptor.
#        kindle-sync.sh asks adb for the phone's card style, and adb answers
#        by starting a fork-server that daemonises and lives until the next
#        reboot — holding the inherited fd, and with it the lock. The service
#        then worked exactly once per boot and dropped every later plug-in.
#   -E   so a busy lock is distinguishable from a failed run, and can be said
#        out loud. The silence is what let the above go unnoticed for days:
#        the events were in the journal, the run was not, and nothing said why.
#   timeout
#        a run that hangs (a Kindle that never finishes mounting, AnkiConnect
#        never answering) would hold the lock just as permanently. A bounded
#        failure is recoverable; an unbounded one is the same bug again.
run() {
    {
        local status=0
        timeout --signal=TERM --kill-after=30s "$RUN_TIMEOUT" \
            flock -n -E 99 -o "$LOCK" "$HERE/kindle-sync.sh" --wait 90 || status=$?
        case "$status" in
            0) ;;
            99) echo "a sync is already running — this event ignored" ;;
            124|137) echo "the sync did not finish within $RUN_TIMEOUT and was stopped" ;;
            *) echo "the sync exited with $status" ;;
        esac
    } &
}


# A Kindle already plugged in when the service starts — after a reboot with the
# cable in, or after Restart=on-failure — never produces an `add` event, so the
# run would wait for an unplug/replug that the user has no reason to perform.
for id in /sys/bus/usb/devices/*/idVendor; do
    if [[ -r "$id" && "$(cat "$id")" == "$VENDOR" ]]; then
        echo "Kindle already connected at startup"
        run
        break
    fi
done

# stdbuf: udevadm writes to a pipe in blocks, so without it an event can sit
# unread in the buffer until the next ones fill it up.
stdbuf -oL udevadm monitor --udev --subsystem-match=usb/usb_device --property | {
    action=
    vendor=
    while IFS= read -r line; do
        case "$line" in
            ACTION=*) action="${line#ACTION=}" ;;
            ID_VENDOR_ID=*) vendor="${line#ID_VENDOR_ID=}" ;;
            "")
                # End of one event block.
                if [[ "$action" == add && "$vendor" == "$VENDOR" ]]; then
                    echo "Kindle connected"
                    run
                fi
                action=
                vendor=
                ;;
        esac
    done
}

# Only reached when udevadm's stream ends — udev restarting, or the monitor
# being killed. Nothing is being watched any more, so say so and fail, which
# is what makes systemd start a new one (Restart=always).
echo "udev event stream ended; exiting so the service restarts" >&2
exit 1
