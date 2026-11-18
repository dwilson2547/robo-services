#!/usr/bin/env bash
# Build the debug APK and install it on the USB-connected phone.
set -euo pipefail
cd "$(dirname "$0")/../android_app"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties

./gradlew assembleDebug
"$SDK/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
