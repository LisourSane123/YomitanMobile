#!/usr/bin/env bash
# Installs and starts the user service that runs kindle-sync.sh whenever a
# Kindle is plugged in. No root needed.
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$UNIT_DIR"
sed "s|@SCRIPT@|$HERE/kindle-watch.sh|" "$HERE/kindle-watch.service" > "$UNIT_DIR/kindle-watch.service"
systemctl --user daemon-reload
# An earlier version was wanted by default.target; `disable` drops that link
# before `enable` makes the one for graphical-session.target.
systemctl --user disable kindle-watch.service 2>/dev/null || true
systemctl --user enable kindle-watch.service
systemctl --user restart kindle-watch.service
echo "kindle-watch.service running. Logs: journalctl --user -u kindle-watch -f"
