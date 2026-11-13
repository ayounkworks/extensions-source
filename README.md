# AyounkWorks Extensions

Custom Mihon extension repository based on the Keiyoushi extension source code.

## Add to Mihon

Copy and paste this repository URL into **Mihon → Settings → Browse → Extension repos**:

`https://raw.githubusercontent.com/ayounkworks/extensions-source/repo/index.pb`

## Build and publishing

The `main` branch contains extension source code. GitHub Actions builds signed APK/JAR artifacts and publishes repository metadata to the `repo` branch.

The `repo` branch contains:
- `index.pb` — gzip-compressed protobuf repository index
- `index.json` — readable index representation
- `repo.json` — repository metadata
- `*.apk` and `*.jar` — published extension artifacts
- `icon.png` — repository icon

## Maintainer configuration

Configure these GitHub Actions secrets before running signed builds:
- `SIGNING_KEY` — base64-encoded JKS signing keystore
- `ALIAS` — signing key alias
- `KEY_STORE_PASSWORD` — keystore password
- `KEY_PASSWORD` — key password

The fingerprint in `repo/index.json` and `repo/repo.json` must match the certificate used to sign APKs. Keep the existing signing key to preserve update compatibility for installed extensions.

## Upstream

This repository is an independent community fork based on [Keiyoushi Extensions](https://github.com/keiyoushi/extensions-source). It is not affiliated with Mihon or Keiyoushi.
