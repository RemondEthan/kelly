#!/bin/bash
set -euo pipefail

# Ship the desktop entry as a dpkg-owned file instead of using
# xdg-desktop-menu, which fails when system menu directories are absent.
shopt -s nullglob
DEBS=(kelly_*.deb)
if [ ${#DEBS[@]} -eq 0 ]; then
    echo "No Kelly deb package found in $PWD" >&2
    exit 1
fi

for DEB in "${DEBS[@]}"; do
    PACKAGE_DIR=$(mktemp -d)
    trap 'rm -rf "$PACKAGE_DIR"' EXIT
    dpkg-deb -R "$DEB" "$PACKAGE_DIR"

    DESKTOP="$PACKAGE_DIR/opt/kelly/lib/kelly-kelly.desktop"
    test -f "$DESKTOP"
    if ! grep -q '^StartupWMClass=' "$DESKTOP"; then
        echo "StartupWMClass=com.mordor.kelly.app.Kelly" >> "$DESKTOP"
    fi
    install -Dm644 "$DESKTOP" "$PACKAGE_DIR/usr/share/applications/kelly-kelly.desktop"

    for SCRIPT in postinst prerm; do
        sed -i -E '\@^[[:space:]]*xdg-desktop-menu (install|uninstall) /opt/kelly/lib/kelly-kelly\.desktop[[:space:]]*$@c\        : # Desktop entry is managed by dpkg in /usr/share/applications.' "$PACKAGE_DIR/DEBIAN/$SCRIPT"
        if grep -q 'xdg-desktop-menu' "$PACKAGE_DIR/DEBIAN/$SCRIPT"; then
            echo "Unexpected xdg-desktop-menu command in $SCRIPT" >&2
            exit 1
        fi
    done

    dpkg-deb --root-owner-group -b "$PACKAGE_DIR" "$DEB"
    rm -rf "$PACKAGE_DIR"
    trap - EXIT
done
