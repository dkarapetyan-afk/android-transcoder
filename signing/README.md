# Release signing (encrypted)

Plaintext `release.jks` and `keystore.properties` stay gitignored. CI decrypts these files with the GitHub Actions repository secret `SIGNING_PASSPHRASE`.

```bash
export SIGNING_PASSPHRASE=...   # same value as the GitHub secret
./scripts/decrypt-signing.sh    # writes gitignored files at the repo root
./scripts/encrypt-signing.sh    # rewrite the .enc files after rotating keys
```

Encryption is OpenSSL AES-256-CBC with PBKDF2 (600000 iterations). Do not commit the passphrase or the decrypted files.
