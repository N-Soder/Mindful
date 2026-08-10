#!/usr/bin/env bash
#
# FORK-ONLY: Build a signed arm64 release APK.
#
# Wraps the toolchain setup that this fork needs and that isn't discoverable from
# the repo alone. See also: docs/FORK.md
#
# Usage: ./scripts/build-apk.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export PATH="$HOME/dev/flutter/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

KEYSTORE_ENV="$HOME/keystores/mindful-fork.env"

# Without these the release signingConfig has no storeFile and Gradle fails with a
# confusing "missing required property" error.
if [[ ! -f "$KEYSTORE_ENV" ]]; then
  echo "✗ Missing $KEYSTORE_ENV"
  echo "  Release signing reads KEYSTORE_FILE / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD"
  echo "  from the environment. Restore your keystore backup before building."
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "$KEYSTORE_ENV"
set +a

echo "→ Building signed arm64 release APK..."
flutter build apk --release --split-per-abi --target-platform android-arm64

APK="build/app/outputs/flutter-apk/app-arm64-v8a-release.apk"

# Verify the APK exists rather than trusting an exit code - piping a Flutter build
# through anything makes $? report the last command in the pipe, not Gradle.
if [[ ! -f "$APK" ]]; then
  echo "✗ Build reported success but no APK was produced at $APK"
  exit 1
fi

echo ""
echo "✓ $APK ($(du -h "$APK" | cut -f1))"

# Confirm it's signed with the fork key and not a debug key.
APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | head -1 || true)"
if [[ -n "$APKSIGNER" ]]; then
  echo "→ Signature:"
  "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -E "DN:|SHA-256" | sed 's/^/  /'
fi
