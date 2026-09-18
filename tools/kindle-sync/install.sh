#!/usr/bin/env bash
# Installs the user service, then prints the one root step (the udev rule).
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$UNIT_DIR"
sed "s|@SCRIPT@|$HERE/kindle-sync.sh|" "$HERE/kindle-sync.service" > "$UNIT_DIR/kindle-sync.service"
systemctl --user daemon-reload
echo "user service installed: $UNIT_DIR/kindle-sync.service"
echo
echo "Now, as root:"
echo "  sudo install -m644 '$HERE/99-kindle-sync.rules' /etc/udev/rules.d/"
echo "  sudo udevadm control --reload"
echo
echo "Logs: journalctl --user -u kindle-sync -f"
