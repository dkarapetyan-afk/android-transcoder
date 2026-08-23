#!/usr/bin/env bash
# Encrypt release.jks and keystore.properties into signing/*.enc.
# Usage: SIGNING_PASSPHRASE=... ./scripts/encrypt-signing.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ITER=600000
ENC_DIR="$ROOT/signing"

if [[ -z "${SIGNING_PASSPHRASE:-}" ]]; then
  echo "SIGNING_PASSPHRASE is not set." >&2
  exit 1
fi

encrypt() {
  local src="$1"
  local dest="$2"
  if [[ ! -f "$src" ]]; then
    echo "Missing $src" >&2
    exit 1
  fi
  openssl enc -aes-256-cbc -pbkdf2 -iter "$ITER" -salt \
    -in "$src" -out "$dest" -pass env:SIGNING_PASSPHRASE
}

mkdir -p "$ENC_DIR"
encrypt "$ROOT/release.jks" "$ENC_DIR/release.jks.enc"
encrypt "$ROOT/keystore.properties" "$ENC_DIR/keystore.properties.enc"
chmod 644 "$ENC_DIR/release.jks.enc" "$ENC_DIR/keystore.properties.enc"
echo "Wrote signing/release.jks.enc and signing/keystore.properties.enc"
