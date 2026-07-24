#!/usr/bin/env bash
# scripts/setup_models.sh
#
# Downloads all three ONNX models required by Whispera4Android and pushes them
# to a connected Android device (or writes them to app/src/main/assets/models/
# for bundling, depending on the flavor you pick).
#
# Models used (sizes are approximate, post-unpack):
#
#   silero_vad (VAD)         ~ 2 MB
#   sherpa-onnx-sense Voice (~500 MB, optional)
#   sherpa-onnx-zipformer-zh (~80 MB, lighter alternative to SenseVoice)
#   kokoro-multi-lang-v1.1   (~200 MB, TTS, supports 103 speakers)
#
# Run from the repo root. Requires: curl, wget, unzip, tar; `adb` optional.
#
# Usage:
#   ./scripts/setup_models.sh                       # download to ./app/assets/models
#   ./scripts/setup_models.sh --device              # push via adb to /data/local/tmp/whispera/models
#   ./scripts/setup_models.sh --assets    (default) # same as above; bundled-model flavor packs them

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/models"
mode="assets"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) mode="device"; shift ;;
    --assets) mode="assets"; shift ;;
    --zipformer) asr="zipformer"; shift ;;
    *) echo "Unknown flag: $1"; exit 2 ;;
  esac
done

echo "Whispera4Android setup_models.sh"
echo "Mode: $mode"
echo "Destination: $DEST"
mkdir -p "$DEST"

# ---------- 1) Silero VAD ----------
echo "→ Silero VAD"
mkdir -p "$DEST/silero_vad"
if [[ ! -f "$DEST/silero_vad/silero_vad.onnx" ]]; then
  curl -sSL -o "$DEST/silero_vad/silero_vad.onnx" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
fi

# ---------- 2) ASR: SenseVoice ONNX ----------
echo "→ SenseVoice ASR"
SV_DIR="$DEST/sensevoice"
mkdir -p "$SV_DIR"
if [[ ! -f "$SV_DIR/model.onnx" ]]; then
  # The SenseVoice model is distributed by sherpa-onnx as a single archive.
  tmp="$(mktemp -d)"
  echo "Downloading archive..."
  curl -sSL -o "$tmp/sv.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
  echo "Extracting..."
  tar -xjf "$tmp/sv.tar.bz2" -C "$tmp"
  cp "$tmp/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/"*.onnx "$SV_DIR"/ 2>/dev/null || true
  cp "$tmp/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/"*.txt "$SV_DIR"/ 2>/dev/null || true
  rm -rf "$tmp"
fi

# ---------- 3) TTS: Kokoro multi-lang v1.1 ----------
echo "→ Kokoro TTS"
KK_DIR="$DEST/kokoro-multi-lang-v1_1"
mkdir -p "$KK_DIR"
if [[ ! -f "$KK_DIR/model.onnx" ]]; then
  tmp="$(mktemp -d)"
  echo "Downloading archive..."
  curl -sSL -o "$tmp/kk.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-kokoro-multi-lang-v1_1-zh_en-20250521.tar.bz2"
  echo "Extracting..."
  tar -xjf "$tmp/kk.tar.bz2" -C "$tmp"
  base="$(find "$tmp" -maxdepth 1 -type d -name 'kokoro*' | head -1)"
  cp "$base/model.onnx"          "$KK_DIR/" 2>/dev/null || true
  cp "$base/tokens.txt"          "$KK_DIR/" 2>/dev/null || true
  cp "$base/voices.bin"          "$KK_DIR/" 2>/dev/null || true
  cp "$base/lexicon.txt"         "$KK_DIR/" 2>/dev/null || true
  cp -r "$base/espeak-ng-data"   "$KK_DIR/" 2>/dev/null || true
  rm -rf "$tmp"
fi

# ---------- 4) Sherpa-ONNX AAR for the build ----------
echo "→ sherpa-onnx AAR"
AAR_DIR="$REPO_ROOT/app/libs"
mkdir -p "$AAR_DIR"
if [[ ! -f "$AAR_DIR/sherpa-onnx-1.13.4.aar" ]]; then
  curl -sSL -o "$AAR_DIR/sherpa-onnx-1.13.4.aar" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar"
fi

echo
echo "✓ Models installed under $DEST"
du -sh "$DEST"/* 2>/dev/null || true
echo
if [[ "$mode" == "device" ]]; then
  echo "Pushing models via adb to /data/local/tmp/whispera/models/ ..."
  adb shell run-as com.whispera.android mkdir -p /data/local/tmp/whispera 2>/dev/null || true
  adb push "$DEST" /data/local/tmp/whispera/models
  echo "✓ Pushed. (No-op: app reads from ctx.filesDir; use --assets flavor instead for bundled APKs.)"
fi
echo "Done."
