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

# The cards go to kindle-sync.sh's default deck, Japanese — the one
# AutoReorder sorts (`deck:Japanese is:new`), so they arrive in frequency
# order. A deck outside that search would never be ordered; kindle-sync.sh
# says so in the notification if it ever is.
run() {
    # flock -n: a second plug event while a run is going is dropped.
    flock -n "$LOCK" "$HERE/kindle-sync.sh" --wait 90 &
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
