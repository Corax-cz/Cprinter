#!/usr/bin/env bash
# 构建 debug APK。
#
# 为什么要绕一道虚拟盘符：项目位于含中文的目录（用户指定），而
# AGP 会直接拒绝非 ASCII 路径，CMake 在中文路径下会直接崩（0xC0000409，
# 实测 3.22.1 和 3.31.6 都一样）。所以用 subst 把项目映射成 W:\，
# 物理文件仍在 D:\workbody\无线打印安卓客户端，只是暴露给工具链的路径是 ASCII。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WINROOT="$(cygpath -w "$ROOT")"
SUBST="/c/Windows/System32/subst.exe"

if [ ! -d /w/app ]; then
  echo "建立虚拟盘符: W: -> $WINROOT"
  "$SUBST" W: "$WINROOT"
fi
if [ ! -d /w/app ]; then
  echo "无法建立 W: 盘符（可能已被占用）。请手动执行：subst W: \"$WINROOT\"" >&2
  exit 1
fi

cd /w
source /w/toolchain/env.sh
./gradlew :app:assembleDebug "$@"
ls -l app/build/outputs/apk/debug/*.apk
