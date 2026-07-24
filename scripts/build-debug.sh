#!/usr/bin/env bash
# scripts/build-debug.sh
#
# Build a debug APK for Whispera4Android (signed with the Android debug key).
# Use this for development and self-testing.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

# Check that the sherpa-onnx AAR has been downloaded by setup_models.sh.
AAR="app/libs/sherpa-onnx-1.13.4.aar"
if [[ ! -f "$AAR" ]]; then
  echo "✗ $AAR not found. Run ./scripts/setup_models.sh first."
  exit 1
fi

# Check that at least the VAD model is in place (TTS/ASR only required at runtime,
# not at build time, but absence usually means the user misconfigured).
if [[ ! -f "app/src/main/assets/models/silero_vad/silero_vad.onnx" ]]; then
  echo "⚠ VAD model not found under app/src/main/assets/models/silero_vad/"
  echo "  Run ./scripts/setup_models.sh now for a fully-bundled APK."
  echo "  or proceed with online-only LiteCloud flavor: ./scripts/build-debug.sh --litecloud"
else
  echo "✓ VAD model present."
fi

flavor="${1:-offlineFull}"
echo "Assembling $flavor / debug …"
./gradlew "assemble${flavor^}"Debug --console=plain --info
APK="${REPO_ROOT}/app/build/outputs/apk/${flavor}/debug/app-${flavor}-debug.apk"
echo
echo "✓ Built: $APK"
ls -lh "$APK"
echo
echo "Install on a connected device:"
echo "  ./scripts/install.sh $APK"
