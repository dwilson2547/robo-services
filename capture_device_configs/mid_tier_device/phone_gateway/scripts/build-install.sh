#!/usr/bin/env bash
# Build the debug APK and install it on the connected phone.
# Requires ANDROID_HOME (or ANDROID_SDK_ROOT) pointing at an Android SDK with
# platform 34 + build-tools installed, and a JDK 17 on PATH.
set -euo pipefail
cd "$(dirname "$0")/../android_app"

: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
echo "installed. Start the app, grant permissions, sign in, press Start capture."
