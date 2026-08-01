#!/usr/bin/env bash

set -euo pipefail

readonly PADDLE_LITE_TAG="v2.14-rc"
readonly PADDLE_LITE_COMMIT="28fe23f222de1865e01f0ab41d2494a1222be4f0"
readonly ANDROID_API_LEVEL="24"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$MODULE_DIR/../.." && pwd)"
PATCH_FILE="$SCRIPT_DIR/paddle-lite-v2.14-rc-ndk29.diff"
PYTHON_SHIM_SOURCE="$SCRIPT_DIR/paddle_lite_python_shim.sh"

property() {
    local key="$1"
    awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$ROOT_DIR/version.properties"
}

readonly NDK_VERSION="$(property PADDLE_OCR_NDK_VERSION)"
readonly CMAKE_VERSION="$(property PADDLE_OCR_CMAKE_VERSION)"

if [[ -z "$NDK_VERSION" || -z "$CMAKE_VERSION" ]]; then
    echo "Cannot read Paddle OCR NDK/CMake versions from $ROOT_DIR/version.properties" >&2
    exit 1
fi

for command_name in git make perl awk shasum; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Missing required command: $command_name" >&2
        exit 1
    fi
done

SDK_ROOT=""
for candidate in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "/opt/homebrew/share/android-commandlinetools" "$HOME/Library/Android/sdk"; do
    if [[ -n "$candidate" && -d "$candidate/ndk/$NDK_VERSION" && -x "$candidate/cmake/$CMAKE_VERSION/bin/cmake" ]]; then
        SDK_ROOT="$candidate"
        break
    fi
done

if [[ -z "$SDK_ROOT" ]]; then
    echo "Cannot find NDK $NDK_VERSION and CMake $CMAKE_VERSION under the Android SDK" >&2
    exit 1
fi

PYTHON_BIN="${PADDLE_LITE_PYTHON:-}"
if [[ -z "$PYTHON_BIN" && -x /opt/homebrew/bin/python3 ]]; then
    PYTHON_BIN="/opt/homebrew/bin/python3"
elif [[ -z "$PYTHON_BIN" ]]; then
    PYTHON_BIN="$(command -v python3 || true)"
fi

if [[ -z "$PYTHON_BIN" || ! -x "$PYTHON_BIN" ]]; then
    echo "Python 3 is required. Set PADDLE_LITE_PYTHON to its absolute path." >&2
    exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/autojs6-paddle-lite.XXXXXX")"
SOURCE_DIR="$WORK_DIR/Paddle-Lite"
STAGE_DIR="$WORK_DIR/stage"
SHIM_DIR="$WORK_DIR/bin"
KEEP_WORK_DIR="${PADDLE_LITE_KEEP_WORK_DIR:-0}"

cleanup() {
    local exit_code="$?"
    if [[ "$KEEP_WORK_DIR" == "1" || "$exit_code" != "0" ]]; then
        echo "Paddle Lite work directory kept at: $WORK_DIR"
    else
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT

echo "Paddle Lite: $PADDLE_LITE_TAG ($PADDLE_LITE_COMMIT)"
echo "Android NDK: $NDK_VERSION"
echo "CMake: $CMAKE_VERSION"
echo "Python: $PYTHON_BIN"
echo "Work directory: $WORK_DIR"

git clone --depth 1 --branch "$PADDLE_LITE_TAG" https://github.com/PaddlePaddle/Paddle-Lite.git "$SOURCE_DIR"

actual_commit="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [[ "$actual_commit" != "$PADDLE_LITE_COMMIT" ]]; then
    echo "Unexpected Paddle Lite commit: $actual_commit" >&2
    exit 1
fi

git -C "$SOURCE_DIR" apply --check "$PATCH_FILE"
git -C "$SOURCE_DIR" apply "$PATCH_FILE"

# Both Paddle Lite OpenMP and its internal thread pool fail under MuMu's
# ARM64-to-x86 Houdini translation layer. Keep CPU inference single-threaded
# for compatibility; real ARM devices remain functional.
perl -0pi -e 's/(set\(WITH_MKLML \$\{WITH_MKL\}\))/set(LITE_WITH_OPENMP OFF CACHE STRING "Disable OpenMP for AutoJs6 Android compatibility" FORCE)\n\n$1/' \
    "$SOURCE_DIR/CMakeLists.txt"

# Paddle Lite v2.14-rc has a helper named ast.py. On Python 3.14 it shadows the
# standard-library ast module while importing logging. Rename the helper and
# update its imports without changing its implementation.
mv "$SOURCE_DIR/lite/tools/cmake_tools/ast.py" "$SOURCE_DIR/lite/tools/cmake_tools/paddle_lite_ast.py"
perl -pi -e 's/from ast import/from paddle_lite_ast import/g' "$SOURCE_DIR"/lite/tools/cmake_tools/*.py

mkdir -p "$STAGE_DIR" "$SHIM_DIR"
ln -s "$SDK_ROOT/ndk/$NDK_VERSION" "$WORK_DIR/android-ndk-r29"

# gen_opencl_code.py does not import Paddle Lite's parser. Run a temporary copy
# outside cmake_tools so Python cannot confuse the local parser with stdlib ast.
cp "$PYTHON_SHIM_SOURCE" "$SHIM_DIR/python"
chmod +x "$SHIM_DIR/python"

export AUTOJS6_PYTHON_BIN="$PYTHON_BIN"
export AUTOJS6_PYTHON_SHIM_DIR="$SHIM_DIR"
export NDK_ROOT="$WORK_DIR/android-ndk-r29"
export LITE_BUILD_THREADS="${LITE_BUILD_THREADS:-8}"
export PATH="$SHIM_DIR:$SDK_ROOT/cmake/$CMAKE_VERSION/bin:$PATH"

build_abi() {
    local paddle_arch="$1"
    local android_abi="$2"
    local publish_dir
    local source_lib
    local build_log="$WORK_DIR/build-$android_abi.log"

    echo "Building Paddle Lite for $android_abi"
    if ! (
        cd "$SOURCE_DIR"
        bash lite/tools/build_android.sh \
            "--arch=$paddle_arch" \
            --toolchain=clang \
            --android_stl=c++_shared \
            "--android_api_level=$ANDROID_API_LEVEL" \
            --with_extra=ON \
            --with_opencl=ON
    ) >"$build_log" 2>&1; then
        tail -n 200 "$build_log" >&2
        exit 1
    fi
    grep -E 'compiler identification is Clang|Built target publish_inference$' "$build_log" | tail -n 4

    publish_dir="$SOURCE_DIR/build.lite.android.$paddle_arch.clang/inference_lite_lib.android.$paddle_arch.opencl"
    source_lib="$publish_dir/cxx/lib/libpaddle_light_api_shared.so"
    if [[ ! -f "$source_lib" ]]; then
        echo "Missing build output: $source_lib" >&2
        exit 1
    fi

    mkdir -p "$STAGE_DIR/libs/$android_abi"
    cp "$source_lib" "$STAGE_DIR/libs/$android_abi/libpaddle_light_api_shared.so"

    if [[ "$paddle_arch" == "armv8" ]]; then
        cp -R "$publish_dir/cxx/include" "$STAGE_DIR/include"
    fi
}

build_abi armv8 arm64-v8a
build_abi armv7 armeabi-v7a

NDK_PREBUILT_ROOT="$NDK_ROOT/toolchains/llvm/prebuilt"
case "$(uname -s)" in
    Darwin)
        host_candidates=(darwin-arm64 darwin-x86_64)
        ;;
    Linux)
        host_candidates=(linux-x86_64)
        ;;
    *)
        echo "Unsupported host OS for llvm-readelf: $(uname -s)" >&2
        exit 1
        ;;
esac

NDK_HOST_TAG=""
for host_candidate in "${host_candidates[@]}"; do
    if [[ -x "$NDK_PREBUILT_ROOT/$host_candidate/bin/llvm-readelf" ]]; then
        NDK_HOST_TAG="$host_candidate"
        break
    fi
done

if [[ -z "$NDK_HOST_TAG" ]]; then
    echo "Cannot find llvm-readelf under $NDK_PREBUILT_ROOT" >&2
    exit 1
fi

READ_ELF="$NDK_PREBUILT_ROOT/$NDK_HOST_TAG/bin/llvm-readelf"
"$READ_ELF" -h "$STAGE_DIR/libs/arm64-v8a/libpaddle_light_api_shared.so" | grep 'AArch64' >/dev/null
"$READ_ELF" -h "$STAGE_DIR/libs/armeabi-v7a/libpaddle_light_api_shared.so" | grep 'ARM' >/dev/null

mkdir -p "$MODULE_DIR/PaddleLite/cxx/libs/arm64-v8a" "$MODULE_DIR/PaddleLite/cxx/libs/armeabi-v7a"
cp "$STAGE_DIR/libs/arm64-v8a/libpaddle_light_api_shared.so" \
    "$MODULE_DIR/PaddleLite/cxx/libs/arm64-v8a/libpaddle_light_api_shared.so"
cp "$STAGE_DIR/libs/armeabi-v7a/libpaddle_light_api_shared.so" \
    "$MODULE_DIR/PaddleLite/cxx/libs/armeabi-v7a/libpaddle_light_api_shared.so"
cp -R "$STAGE_DIR/include/." "$MODULE_DIR/PaddleLite/cxx/include/"

echo "Updated Paddle Lite artifacts:"
shasum -a 256 "$MODULE_DIR"/PaddleLite/cxx/libs/*/libpaddle_light_api_shared.so
