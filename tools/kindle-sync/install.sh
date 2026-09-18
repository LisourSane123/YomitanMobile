#!/usr/bin/env bash
# Installs and starts the user service that runs kindle-sync.sh whenever a
# Kindle is plugged in. No root needed.
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$UNIT_DIR"
sed "s|@SCRIPT@|$HERE/kindle-watch.sh|" "$HERE/kindle-watch.service" > "$UNIT_DIR/kindle-watch.service"
systemctl --user daemon-reload
systemctl --user enable --now kindle-watch.service
echo "kindle-watch.service running. Logs: journalctl --user -u kindle-watch -f"
