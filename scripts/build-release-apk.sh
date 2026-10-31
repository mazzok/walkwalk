#!/usr/bin/env bash
# Builds and checks the signed release APK for the given version, e.g. `build-release-apk.sh 1.4.2`.
# semantic-release runs this in its prepare step, so a failure here stops the release before the
# tag or GitHub release is created. Output: dist/app-release.apk
set -euo pipefail

version="${1:?usage: build-release-apk.sh <version>}"
apk=app/build/outputs/apk/release/app-release.apk

rm -rf dist app/build/outputs/apk/release
./gradlew assembleRelease -PreleaseVersion="$version"

if [[ ! -f "$apk" ]]; then
  echo "error: $apk not found. Is the release signing configured (ANDROID_KEYSTORE_PATH etc.)?" >&2
  exit 1
fi

build_tools="$(printf '%s\n' "${ANDROID_HOME:?ANDROID_HOME not set}"/build-tools/* | sort -V | tail -n 1)"
apksigner="$build_tools/apksigner"
[[ -f "$apksigner" ]] || apksigner="$apksigner.bat" # Windows SDK

# The APK must carry the version semantic-release calculated.
badging="$("$build_tools/aapt2" dump badging "$apk")"
badging="${badging%%$'\n'*}" # first line; piping into head would SIGPIPE aapt2 under pipefail
echo "$badging"
if [[ "$badging" != *"versionName='$version'"* ]]; then
  echo "error: APK versionName does not match $version" >&2
  exit 1
fi

# The APK must be signed, and with the expected key if its fingerprint is known.
certs="$("$apksigner" verify --print-certs "$apk")"
echo "$certs"
if [[ -n "${EXPECTED_CERT_SHA256:-}" ]]; then
  expected="$(echo "$EXPECTED_CERT_SHA256" | tr -d ': ' | tr '[:upper:]' '[:lower:]')"
  if ! grep -qi "SHA-256 digest: $expected" <<<"$certs"; then
    echo "error: APK is not signed with the expected release key ($expected)" >&2
    exit 1
  fi
fi

mkdir -p dist
cp "$apk" dist/app-release.apk
echo "Built dist/app-release.apk ($version)"
