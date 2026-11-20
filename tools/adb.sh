#!/usr/bin/env bash
# 统一的 adb 调用入口：自动发现并连接无线调试设备，然后把参数透传给 adb。
#
#   ./tools/adb.sh devices
#   ./tools/adb.sh shell am force-stop com.cawzen.printserver
#   ./tools/adb.sh install -r -g app/build/outputs/apk/debug/app-debug.apk
#   ./tools/adb.sh forward tcp:8631 tcp:8631
#
# 为什么需要这一层：
#   1. adb server 会随调用它的 shell 一起退出，下一次调用是全新进程，上一次
#      `adb connect` 的 TCP 连接就没了 —— 所以每次都得重连，不能只在开头连一次。
#   2. 无线调试的端口每次重启手机/切开关都会变，不能硬编码。这里从 mDNS 现查，
#      查不到再退回上次成功过的端口（缓存文件），都失败才报错。
#   3. 同一台设备经 mDNS 会出现两个序列号（`adb-xxx._adb-tls-connect._tcp` 和
#      `IP:port`），同时存在时 adb 会报 "more than one device" —— 所以统一显式
#      指定 -s，不让 adb 自己挑。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="$ROOT/toolchain/android-sdk/platform-tools/adb.exe"
CACHE="$ROOT/build/.wprint_serial"
IP="${WPRINT_IP:-192.168.123.60}"

"$ADB" start-server >/dev/null 2>&1 || true

# 第一优先：已经在线的设备，直接用，**不重新 connect**。
#
# 这不只是为了快。MIUI 每收到一次 adb connect 就会弹一个「已连接到无线调试」的
# 悬浮横幅，它悬在状态栏下方、覆盖 ~250px 高度，而且会**吞掉触摸事件**（实测
# 自动化点击全部落空）。调试打印界面时它正好盖住「选择打印机」那一行，反复重连
# 就等于反复把界面弄坏。
CANDIDATES=$("$ADB" devices 2>/dev/null | tr -d '\r' \
  | awk '$2=="device" && ($1 ~ /_adb-tls-connect/ || $1 ~ /:[0-9]+$/) {print $1}')
SERIAL=$(echo "$CANDIDATES" | head -1)

# 局域网里同时开着无线调试的手机不止一台（调试这台 + 另一台 Termux 那台），
# adb 返回的顺序不保证，而且端口号每次重启都变。按「插着打印机的那台」挑：
# `dumpsys usb` 里 host_connected=true。这一条踩过 —— 以为在调 A 机，实际一直在
# 看 B 机，于是「版本是 0.2.0」「USB 枚举到 0 个设备」全是另一台手机的实情，
# 结论完全跑偏。
if [ -n "$CANDIDATES" ] && [ "$(echo "$CANDIDATES" | wc -l)" -gt 1 ]; then
  PICK=""
  for s in $CANDIDATES; do
    if "$ADB" -s "$s" shell dumpsys usb 2>/dev/null | grep -q "host_connected=true"; then
      PICK="$s"
      break
    fi
  done
  # 都没插打印机才退回到「装了本 App 的那台」。
  if [ -z "$PICK" ]; then
    for s in $CANDIDATES; do
      if "$ADB" -s "$s" shell pm path com.cawzen.directprint 2>/dev/null | grep -q package:; then
        PICK="$s"
        break
      fi
    done
  fi
  [ -n "$PICK" ] && SERIAL="$PICK"
fi

if [ -n "${WPRINT_SERIAL:-}" ]; then
  SERIAL="$WPRINT_SERIAL"
fi

if [ -z "$SERIAL" ]; then
  # 没在线设备才走 mDNS 发现。需要一点时间，最多等 8 秒。
  for _ in $(seq 1 8); do
    SERIAL=$("$ADB" mdns services 2>/dev/null \
      | awk '/_adb-tls-connect|_adb-tcp/ {print $3; exit}')
    [ -n "$SERIAL" ] && break
    sleep 1
  done

  # mDNS 不可用时退回缓存端口（例如路由器屏蔽了组播）。
  if [ -z "$SERIAL" ] && [ -f "$CACHE" ]; then
    SERIAL="$(cat "$CACHE")"
  fi
fi

if [ -z "$SERIAL" ]; then
  echo "找不到无线调试设备。" >&2
  echo "请在手机上打开「开发者选项 → 无线调试」，确认与 PC 在同一网段（$IP），" >&2
  echo "或显式指定：WPRINT_SERIAL=<ip:port> $0 $*" >&2
  exit 1
fi

mkdir -p "$(dirname "$CACHE")"
# 只有确实不在线才连。
if ! "$ADB" devices 2>/dev/null | tr -d '\r' | awk -v s="$SERIAL" '$1==s && $2=="device" {found=1} END {exit !found}'; then
  "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
fi
echo "$SERIAL" > "$CACHE"

exec "$ADB" -s "$SERIAL" "$@"
