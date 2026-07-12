#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REBUILD_PADDLE_LITE=0
KEEP_ALL_APKS=0

usage() {
    cat <<'EOF'
Usage: bash scripts/build_autojs6_release.sh [options]

Build the AutoJs6 INRT release template and the universal main-app release APK.

Options:
  --rebuild-paddle-lite  Rebuild Paddle Lite native libraries first (slow).
  --keep-all-apks        Keep per-ABI APKs produced by Gradle.
  -h, --help             Show this help.
EOF
}

for argument in "$@"; do
    case "$argument" in
        --rebuild-paddle-lite)
            REBUILD_PADDLE_LITE=1
            ;;
        --keep-all-apks)
            KEEP_ALL_APKS=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $argument" >&2
            usage >&2
            exit 2
            ;;
    esac
done

cd "$ROOT_DIR"

if [[ "$REBUILD_PADDLE_LITE" == "1" ]]; then
    bash libs/paddleocr/scripts/rebuild_paddle_lite.sh
fi

./gradlew :app:assembleInrtRelease
./gradlew :app:clean :app:assembleAppRelease

RELEASE_DIR="$ROOT_DIR/app/build/outputs/apk/app/release"
UNIVERSAL_APK="$(find "$RELEASE_DIR" -maxdepth 1 -type f -name 'autojs6-v*-universal.apk' -print | head -n 1)"

if [[ -z "$UNIVERSAL_APK" || ! -f "$UNIVERSAL_APK" ]]; then
    echo "Expected universal APK was not created under: $RELEASE_DIR" >&2
    exit 1
fi

if [[ "$KEEP_ALL_APKS" != "1" ]]; then
    find "$RELEASE_DIR" -maxdepth 1 -type f -name 'autojs6-v*.apk' ! -name "$(basename "$UNIVERSAL_APK")" -delete
fi

echo "Release APK: $UNIVERSAL_APK"
