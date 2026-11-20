#!/usr/bin/env bash
# 在手机上跑 hostref，生成参考输出，再拉回 PC 与 APK 的输出比对。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-$ROOT/toolchain/android-sdk/platform-tools/adb.exe}"
[ -f "$ADB" ] || ADB="$ROOT/toolchain/android-sdk/platform-tools/adb"
BIN="$ROOT/tools/hostref/hostref_aarch64"
OUTDIR="$ROOT/testdata/reference"

mkdir -p "$OUTDIR"

"$ADB" push "$BIN" /data/local/tmp/hostref >/dev/null
"$ADB" shell chmod 755 /data/local/tmp/hostref
"$ADB" shell mkdir -p /data/local/tmp/hostref_out

# 200x200 @ 600dpi：与 APK 的 Stage 1 自洽测试用同一组参数
"$ADB" shell /data/local/tmp/hostref /data/local/tmp/hostref_out 200 200 600
"$ADB" shell /data/local/tmp/hostref /data/local/tmp/hostref_out 200 200 600 duplex || true

for f in pattern_200x200_gray.bin pattern_200x200_packed.bin pattern_200x200_stream.bin; do
  "$ADB" pull "/data/local/tmp/hostref_out/$f" "$OUTDIR/$f" >/dev/null
  echo "pulled: $OUTDIR/$f"
done

echo
echo "PC 侧 sha256："
( cd "$OUTDIR" && sha256sum pattern_200x200_stream.bin 2>/dev/null || certutil -hashfile pattern_200x200_stream.bin SHA256 )
