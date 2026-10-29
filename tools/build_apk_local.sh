#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo 'Brak ANDROID_HOME/ANDROID_SDK_ROOT. Zainstaluj Android Studio + Android SDK Platform 35.' >&2
  exit 2
fi
chmod +x ./gradlew
./gradlew :app:assembleDebug --stacktrace
APK="app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
sha256sum "$APK"
printf '\nGotowy APK: %s\n' "$(realpath "$APK")"
