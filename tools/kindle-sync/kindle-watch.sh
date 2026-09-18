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

udevadm monitor --udev --subsystem-match=usb/usb_device --property | {
    action=
    vendor=
    while IFS= read -r line; do
        case "$line" in
            ACTION=*) action="${line#ACTION=}" ;;
            ID_VENDOR_ID=*) vendor="${line#ID_VENDOR_ID=}" ;;
            "")
                # End of one event block. Amazon/Lab126 is vendor 1949.
                if [[ "$action" == add && "$vendor" == 1949 ]]; then
                    echo "Kindle connected"
                    # flock -n: a second plug event while a run is going is dropped.
                    flock -n "$LOCK" "$HERE/kindle-sync.sh" --wait 90 --deck test_kindle &
                fi
                action=
                vendor=
                ;;
        esac
    done
}
