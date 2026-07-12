# Paddle OCR native library

This module links Paddle Lite `v2.14-rc` for the embedded PP-OCRv5 engine. The
checked-in ARM libraries can be rebuilt from the official Paddle Lite source
without keeping a local source checkout.

## Rebuild Paddle Lite

From the AutoJs6 repository root, run:

```bash
bash libs/paddleocr/scripts/rebuild_paddle_lite.sh
```

The script:

1. clones Paddle Lite at tag `v2.14-rc`, pinned to commit
   `28fe23f222de1865e01f0ab41d2494a1222be4f0`;
2. reads the NDK and CMake versions from `version.properties`;
3. applies the NDK 29, Clang 21, and Python 3.14 compatibility changes;
4. builds CPU/OpenCL plus extra OCR operators for `arm64-v8a` and
   `armeabi-v7a` with Android API 24 and `c++_shared`;
5. replaces the checked-in Paddle Lite headers and shared libraries only after
   both ABI builds succeed.

Paddle Lite's `publish_inference` target already emits stripped shared
libraries; the wrapper does not strip them a second time.

The compatibility changes remove obsolete NDK `sources/cxx-stl` paths, delete
an invalid FlatBuffers assignment operator for a reference member, and prevent
Paddle Lite's `ast.py` helper from shadowing Python's standard-library `ast`.

Required tools are Git, Make, Perl, Python 3, Android NDK, and Android CMake.
Set `ANDROID_SDK_ROOT` when the SDK is not in a standard macOS location. Useful
optional environment variables are:

- `LITE_BUILD_THREADS`: build parallelism, default `8`;
- `PADDLE_LITE_PYTHON`: absolute Python 3 path;
- `PADDLE_LITE_KEEP_WORK_DIR=1`: retain the temporary source and build tree.

Successful builds remove the temporary tree automatically. Failed builds keep
it and print its path so the full per-ABI logs remain available for diagnosis.

After rebuilding, verify the Android module with:

```bash
./gradlew :libs:paddleocr:clean :libs:paddleocr:assembleDebug :libs:paddleocr:assembleRelease
```
