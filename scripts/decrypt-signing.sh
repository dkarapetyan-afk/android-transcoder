#!/usr/bin/env bash
# Decrypt signing/*.enc to release.jks and keystore.properties at the repo root.
# Usage: SIGNING_PASSPHRASE=... ./scripts/decrypt-signing.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ITER=600000
ENC_DIR="$ROOT/signing"
umask 077

if [[ -z "${SIGNING_PASSPHRASE:-}" ]]; then
  echo "SIGNING_PASSPHRASE is not set." >&2
  exit 1
fi

decrypt() {
  local src="$1"
  local dest="$2"
  if [[ ! -f "$src" ]]; then
    echo "Missing $src" >&2
    exit 1
  fi
  openssl enc -d -aes-256-cbc -pbkdf2 -iter "$ITER" \
    -in "$src" -out "$dest" -pass env:SIGNING_PASSPHRASE
}

decrypt "$ENC_DIR/release.jks.enc" "$ROOT/release.jks"
decrypt "$ENC_DIR/keystore.properties.enc" "$ROOT/keystore.properties"
echo "Decrypted release.jks and keystore.properties"
