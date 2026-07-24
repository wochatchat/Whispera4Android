#!/usr/bin/env bash
# scripts/install.sh
#
# Install a built APK on a connected device (USB or TCP/IP adb).
# Usage: ./scripts/install.sh app/build/outputs/apk/.../*.apk
#        (defaults to "pick the newest one" if no path given)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if ! command -v adb >/dev/null; then
  echo "✗ adb not found. Install platform-tools (Android SDK)."
  exit 1
fi

# Pick an APK from CLI arg, or auto-discover.
APK=""
if [[ $# -ge 1 ]]; then
  APK="$1"
else
  APK="$(find "$REPO_ROOT/app/build/outputs/apk" -name '*.apk' -printf '%T@ %p\n' 2>/dev/null \
         | sort -nr | head -1 | awk '{print $2}')"
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "✗ No APK found. Run ./scripts/build-debug.sh first."
  exit 1
fi
echo "Installing: $APK"
echo "On device:  $(adb get-state 2>/dev/null || echo 'offline')"
adb install -r "$APK"
echo
echo "✓ Installed. Launch with:"
echo "  adb shell am start -n com.whispera.android/.ui.MainActivity"
