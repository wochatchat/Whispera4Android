#!/usr/bin/env sh
#
# scripts/setup_models.sh
#
# Downloads all three ONNX models required by Whispera4Android and the sherpa-onnx
# AAR runtime, writing both into the project tree so a local `./build-release.sh`
# (or GitHub Actions) produces a self-contained offline APK.
#
# Targets (current URLs verified against github.com/k2-fsa/sherpa-onnx):
#   silero_vad        silero_vad.onnx                       ~2 MB
#   SenseVoice (int8) model.int8.onnx + tokens.txt           ~163 MB (vs 1 GB fp32)
#   Kokoro v1.1       model.onnx + tokens.txt + voices.bin  ~350 MB
#                     + espeak-ng-data/ + lexicon-*.txt
#
# Usage:
#   ./scripts/setup_models.sh                  # default: download to app/src/main/assets/models
#   ./scripts/setup_models.sh --lite           # only VAD + Sherpa AAR (skip ASR/TTS, for liteCloud)
#   ./scripts/setup_models.sh --asr-zipformer  # use Zipformer instead of SenseVoice (smaller ~80 MB)
#
# Requires: curl/wget, tar (with bzip2).
set -eu
SCRIPT_DIR="$(dirname "$0")"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/models"
AAR_DIR="$REPO_ROOT/app/libs"

LITE=0
ASR_CHOICE="sensevoice"
while [ $# -gt 0 ]; do
  case "$1" in
    --lite) LITE=1; shift ;;
    --asr-zipformer) ASR_CHOICE="zipformer"; shift ;;
    --assets) shift ;;
    *) echo "Unknown flag: $1" >&2; exit 2 ;;
  esac
done

echo "Whispera4Android setup_models.sh"
echo "Destination:  $DEST"
echo "ASR model:     $ASR_CHOICE"
mkdir -p "$DEST"

# Helper: download to a target path, using curl or wget (whichever is available).
dl() {
  url="$1"; out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --retry-delay 5 -o "$out" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --tries=3 --retry-connrefused -O "$out" "$url"
  else
    echo "✗ neither curl nor wget is available" >&2; exit 1
  fi
}

# ---------- 1) Sherpa-ONNX AAR (build-time dependency) ----------
echo
echo "→ sherpa-onnx AAR (build-time native library)"
mkdir -p "$AAR_DIR"
AAR="$AAR_DIR/sherpa-onnx-1.13.4.aar"
if [ ! -f "$AAR" ]; then
  dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar" "$AAR"
fi
echo "  ✓ $(ls -lh "$AAR" | awk '{print $5}')  $AAR"

# ---------- 2) Silero VAD ----------
echo
echo "→ Silero VAD"
mkdir -p "$DEST/silero_vad"
if [ ! -f "$DEST/silero_vad/silero_vad.onnx" ]; then
  dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" \
     "$DEST/silero_vad/silero_vad.onnx"
fi
echo "  ✓ $(ls -lh "$DEST/silero_vad/silero_vad.onnx" | awk '{print $5}')"

if [ "$LITE" -eq 1 ]; then
  echo
  echo "✓ --lite mode: skipping ASR / TTS downloads (liteCloud build)"
  du -sh "$DEST"/* 2>/dev/null || true
  exit 0
fi

# ---------- 3) ASR: SenseVoice int8 OR Zipformer ----------
if [ "$ASR_CHOICE" = "sensevoice" ]; then
  echo
  echo "→ SenseVoice ASR (int8, ~163 MB)"
  ASR_DIR="$DEST/sensevoice"
  mkdir -p "$ASR_DIR"
  if [ ! -f "$ASR_DIR/model.int8.onnx" ]; then
    tmp="$(mktemp -d)"
    url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    echo "  downloading $url …"
    dl "$url" "$tmp/sv.tar.bz2"
    echo "  extracting …"
    tar -xjf "$tmp/sv.tar.bz2" -C "$tmp"
    base="$(find "$tmp" -maxdepth 1 -type d -name '*sense-voice*' | head -1)"
    cp "$base/model.int8.onnx" "$ASR_DIR/"
    cp "$base/tokens.txt"      "$ASR_DIR/"
    rm -rf "$tmp"
  fi
  echo "  ✓ $(ls -lh "$ASR_DIR/model.int8.onnx" | awk '{print $5}')"
else
  echo
  echo "→ Zipformer streaming ASR (zh-14M, ~74 MB)"
  ASR_DIR="$DEST/zipformer"
  mkdir -p "$ASR_DIR"
  if [ ! -f "$ASR_DIR/encoder-epoch-99-avg-1.onnx" ]; then
    tmp="$(mktemp -d)"
    url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2"
    echo "  downloading $url …"
    dl "$url" "$tmp/zip.tar.bz2"
    echo "  extracting …"
    tar -xjf "$tmp/zip.tar.bz2" -C "$tmp"
    base="$(find "$tmp" -maxdepth 1 -type d -name '*zipformer*' | head -1)"
    # Copy the encoder/decoder/joiner + tokens that exist in streaming zipformer distros.
    cp "$base"/*.onnx "$ASR_DIR/" 2>/dev/null || true
    cp "$base"/tokens.txt "$ASR_DIR/" 2>/dev/null || true
    rm -rf "$tmp"
  fi
  echo "  ✓ $(ls "$ASR_DIR"/*.onnx | head -1)"
fi

# ---------- 4) TTS: Kokoro multi-lang v1.1 ----------
echo
echo "→ Kokoro TTS multi-lang v1.1 (~350 MB)"
KK_DIR="$DEST/kokoro-multi-lang-v1_1"
mkdir -p "$KK_DIR"
if [ ! -f "$KK_DIR/model.onnx" ]; then
  tmp="$(mktemp -d)"
  url="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2"
  echo "  downloading $url …"
  dl "$url" "$tmp/kk.tar.bz2"
  echo "  extracting …"
  tar -xjf "$tmp/kk.tar.bz2" -C "$tmp"
  base="$(find "$tmp" -maxdepth 1 -type d -name 'kokoro*' | head -1)"
  cp "$base/model.onnx"        "$KK_DIR/" 2>/dev/null || true
  cp "$base/tokens.txt"        "$KK_DIR/" 2>/dev/null || true
  cp "$base/voices.bin"        "$KK_DIR/" 2>/dev/null || true
  # Kokoro v1.1 ships lexicon-*.txt (lexicon-zh.txt, lexicon-gb-en.txt, lexicon-us-en.txt)
  # rather than a single lexicon.txt — copy all and adjust bałe our loader.
  cp "$base/lexicon"*.txt      "$KK_DIR/" 2>/dev/null || true
  # Symlink to lexicon.txt for code that reads it by that name.
  if [ ! -f "$KK_DIR/lexicon.txt" ] && [ -f "$KK_DIR/lexicon-zh.txt" ]; then
    cp "$KK_DIR/lexicon-zh.txt" "$KK_DIR/lexicon.txt"
  fi
  cp -r "$base/espeak-ng-data" "$KK_DIR/" 2>/dev/null || true
  rm -rf "$tmp"
fi
echo "  ✓ $(ls -lh "$KK_DIR/model.onnx" | awk '{print $5}')"

echo
echo "✓ All models installed under $DEST"
du -sh "$DEST"/* 2>/dev/null || true
echo
echo "Now run: ./scripts/build-release.sh   (or ./scripts/build-debug.sh)"
