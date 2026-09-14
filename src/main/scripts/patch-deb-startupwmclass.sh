#!/bin/bash
set -e

DEB=$(ls kelly_*.deb 2>/dev/null | head -1)
[ -z "$DEB" ] && exit 0

TMPDIR=$(mktemp -d)
dpkg-deb -R "$DEB" "$TMPDIR"

DESKTOP=$(find "$TMPDIR" -name "*.desktop" -not -path "*/runtime/*" | head -1)
if [ -n "$DESKTOP" ] && ! grep -q "StartupWMClass" "$DESKTOP"; then
    echo "StartupWMClass=com.mordor.kelly.app.Kelly" >> "$DESKTOP"
fi

dpkg-deb -b "$TMPDIR" "$DEB"
rm -rf "$TMPDIR"
