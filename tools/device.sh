#!/usr/bin/env bash
# 无线调试联调助手。USB 口被打印机占着，所以全程走 WiFi。
#
#   ./tools/device.sh port <端口>   连接无线调试（端口在手机「无线调试」主界面）
#   ./tools/device.sh install       装 APK
#   ./tools/device.sh stage0        清日志 → 拉起 App → 提示点按钮 → 抓 Stage 0 日志
#   ./tools/device.sh logcat        持续看 WPrint 日志
#   ./tools/device.sh shot <文件>   截屏
#
# 注意：stage0 只做只读探测时不会碰设备；独占模式需要手动停掉 Termux 里的打印服务。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="$ROOT/toolchain/android-sdk/platform-tools/adb.exe"
PKG="com.cawzen.printserver"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
IP="${WPRINT_IP:-192.168.123.60}"

case "${1:-}" in
  port)
    [ -z "${2:-}" ] && { echo "用法: $0 port <端口>"; exit 1; }
    "$ADB" connect "$IP:$2"
    "$ADB" -s "$IP:$2" shell getprop ro.product.model
    ;;
  install)
    "$ADB" install -r -g "$APK" && "$ADB" shell am force-stop "$PKG"
    ;;
  stage0)
    "$ADB" logcat -c
    "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
    echo "已拉起 App。请在手机上点「Stage 0 只读」，5 秒后自动抓日志。"
    sleep 5
    "$ADB" logcat -d -s WPrint/Stage0:* WPrint/Stage1:* AndroidRuntime:E | tail -40
    ;;
  logcat)
    "$ADB" logcat -v time WPrint/Stage0:* WPrint/Stage1:* *:S
    ;;
  shot)
    "$ADB" exec-out screencap -p > "${2:-D:/tmp/shot.png}" && echo "saved ${2:-D:/tmp/shot.png}"
    ;;
  *)
    sed -n '2,20p' "$0"
    ;;
esac
