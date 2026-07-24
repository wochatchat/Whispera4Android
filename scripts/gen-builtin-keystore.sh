#!/usr/bin/env sh
# scripts/gen-builtin-keystore.sh
#
# Generates the **built-in** release keystore committed to the repository so
# anyone who clones can build a signed release APK with zero setup.
#
# This key is PUBLIC and shared — it is documented in the README as a test key.
# Anyone who clones gets it; do NOT use this for Play Store releases.
# For a private key see ./scripts/gen-keystore.sh (gitignored output).
#
# Runs only ONCE at project init; output: app/keystore/builtin-release.p12.
# Works in any POSIX shell. Prefers keytool (JDK), falls back to openssl when
# no JVM is available (useful for CI containers / minimal sandboxes).

set -eu
SCRIPT_DIR="$(dirname "$0")"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KS_DIR="$REPO_ROOT/app/keystore"
KS="$KS_DIR/builtin-release.p12"
mkdir -p "$KS_DIR"

if [ -f "$KS" ]; then
  echo "Builtin keystore already exists: $KS — refusing to overwrite."
  exit 0
fi

STORE_PASS="whispera"
KEY_PASS="whispera"   # same password for both for convenience
ALIAS="whispera-builtin"
SUBJECT="/CN=Whispera4Android Builtin Test Key/O=Whispera4Android/C=CN"

if command -v keytool >/dev/null 2>&1; then
  keytool -genkeypair -v -noprompt \
    -keystore "$KS" -storetype PKCS12 \
    -keyalg RSA -keysize 2048 -validity 36500 \
    -alias "$ALIAS" \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "$SUBJECT" \
    -ext "BasicConstraints=CA:FALSE" \
    -ext "KeyUsage=digitalSignature,keyEncipherment" \
    -ext "ExtendedKeyUsage=codeSigning"
elif command -v openssl >/dev/null 2>&1; then
  # openssl path: generate self-signed RSA certificate -> PKCS12 keystore.
  TMP="$(mktemp -d)"
  openssl req -new -newkey rsa:2048 -x509 -keyout "$TMP/key.pem" -out "$TMP/cert.pem" \
    -days 36500 -nodes -subj "$SUBJECT" \
    -addext "basicConstraints=CA:FALSE" \
    -addext "keyUsage=digitalSignature,keyEncipherment"
  openssl pkcs12 -export -in "$TMP/cert.pem" -inkey "$TMP/key.pem" -out "$KS" \
    -name "$ALIAS" -passout "pass:$STORE_PASS"
  rm -rf "$TMP"
else
  echo "✗ keytool and openssl both missing. Install either JDK 17+ or openssl." >&2
  exit 1
fi

echo "✓ Builtin keystore written: $KS"
echo "  alias:     $ALIAS"
echo "  passwords: $STORE_PASS  (PUBLIC, documented as test-only)"
echo
echo "⚠ This key is committed to the repo and is therefore PUBLIC."
echo "  Anyone who clones can sign APKs with it. NEVER use it for Play Store releases."
echo "  For your own private key, run ./scripts/gen-keystore.sh instead."
