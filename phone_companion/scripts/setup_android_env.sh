#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TOOLS_DIR="${PROJECT_ROOT}/.tools"
ANDROID_SDK_ROOT="${TOOLS_DIR}/android-sdk"
JDK_DIR="${TOOLS_DIR}/jdk-21"
GRADLE_DIR="${TOOLS_DIR}/gradle-8.9"
CMDLINE_TOOLS_DIR="${ANDROID_SDK_ROOT}/cmdline-tools/latest"

mkdir -p "${TOOLS_DIR}" "${ANDROID_SDK_ROOT}/cmdline-tools"

if [[ ! -x "${JDK_DIR}/bin/java" ]]; then
  echo "[setup] Installing Temurin JDK 21..."
  curl -fsSL -o "${TOOLS_DIR}/jdk21.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  rm -rf "${JDK_DIR}" "${TOOLS_DIR}/jdk-tmp"
  mkdir -p "${TOOLS_DIR}/jdk-tmp"
  tar -xzf "${TOOLS_DIR}/jdk21.tar.gz" -C "${TOOLS_DIR}/jdk-tmp" --strip-components=1
  mv "${TOOLS_DIR}/jdk-tmp" "${JDK_DIR}"
fi

if [[ ! -x "${GRADLE_DIR}/bin/gradle" ]]; then
  echo "[setup] Installing Gradle 8.9..."
  curl -fsSL -o "${TOOLS_DIR}/gradle-8.9-bin.zip" \
    "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
  rm -rf "${GRADLE_DIR}"
  unzip -q "${TOOLS_DIR}/gradle-8.9-bin.zip" -d "${TOOLS_DIR}"
fi

if [[ ! -x "${CMDLINE_TOOLS_DIR}/bin/sdkmanager" ]]; then
  echo "[setup] Installing Android command-line tools..."
  curl -fsSL -o "${TOOLS_DIR}/commandlinetools-linux.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf "${CMDLINE_TOOLS_DIR}" "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools"
  unzip -q "${TOOLS_DIR}/commandlinetools-linux.zip" -d "${ANDROID_SDK_ROOT}/cmdline-tools"
  mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${CMDLINE_TOOLS_DIR}"
fi

export JAVA_HOME="${JDK_DIR}"
export ANDROID_SDK_ROOT
export PATH="${JAVA_HOME}/bin:${GRADLE_DIR}/bin:${CMDLINE_TOOLS_DIR}/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

set +o pipefail
yes | sdkmanager --licenses >/dev/null
set -o pipefail
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

if [[ ! -f "${PROJECT_ROOT}/android_app/gradlew" ]]; then
  echo "[setup] Generating Gradle wrapper..."
  cd "${PROJECT_ROOT}/android_app"
  gradle wrapper --gradle-version 8.9
fi

cat > "${PROJECT_ROOT}/android_app/local.properties" <<EOF
sdk.dir=${ANDROID_SDK_ROOT}
EOF

rm -f "${TOOLS_DIR}/jdk21.tar.gz" "${TOOLS_DIR}/gradle-8.9-bin.zip" "${TOOLS_DIR}/commandlinetools-linux.zip"

echo "[setup] Done."
echo "export JAVA_HOME=\"${JAVA_HOME}\""
echo "export ANDROID_SDK_ROOT=\"${ANDROID_SDK_ROOT}\""
echo "export PATH=\"${JAVA_HOME}/bin:${GRADLE_DIR}/bin:${CMDLINE_TOOLS_DIR}/bin:${ANDROID_SDK_ROOT}/platform-tools:\$PATH\""
