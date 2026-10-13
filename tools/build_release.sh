#!/bin/bash
set -euo pipefail

MRSS_BUILD_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$MRSS_BUILD_ROOT"

if [[ -f .local/env.sh ]]; then
    source .local/env.sh
fi

if [[ ! -f android/key.properties ]]; then
    echo "缺少 android/key.properties，请先配置正式签名密钥。" >&2
    exit 1
fi

./android/gradlew -p android :app:assembleRelease "$@"
echo "正式 APK：$MRSS_BUILD_ROOT/build/app/outputs/apk/release/app-release.apk"
