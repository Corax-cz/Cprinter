#!/usr/bin/env bash
# 编译 hostref 为 Android arm64 可执行文件，push 到手机运行。
# 为什么不用 Windows 主机版：NDK 的 clang 在 Windows 上要 MSVC 的 CRT 才能出 .exe，
# 而 hostref 的独立性来自"不经过 JNI"，跑在手机上和跑在 PC 上等价。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NDK="${ANDROID_NDK:-$ROOT/toolchain/android-sdk/ndk/27.0.12077973}"
PREBUILT="$NDK/toolchains/llvm/prebuilt"
HOSTDIR="$(ls -d "$PREBUILT"/* | head -1)"

CLANGXX="$HOSTDIR/bin/clang++"
[ -f "$CLANGXX.cmd" ] && CLANGXX="$CLANGXX.cmd"

APPCPP="$ROOT/app/src/main/cpp"
OUT="$ROOT/tools/hostref/hostref_aarch64"

echo "NDK     : $NDK"
echo "clang++ : $CLANGXX"

"$CLANGXX" \
  --target=aarch64-linux-android29 \
  -std=c++17 -O2 -fPIE -pie -static-libstdc++ \
  -I"$APPCPP" -I"$APPCPP/brlaser" \
  -o "$OUT" \
  "$ROOT/tools/hostref/main.cpp" \
  "$APPCPP/brcore.cpp" \
  "$APPCPP/brlaser/job.cc" \
  "$APPCPP/brlaser/line.cc"

echo "built   : $OUT"
ls -l "$OUT"
