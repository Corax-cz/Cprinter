#!/usr/bin/env python3
"""界面小工具：dump 当前控件树 / 按文字点击 / 列出可见文字。

为什么不用固定坐标：ColorOS 的打印设置页列表顺序会变（Cprinter / 默认打印服务 /
Android CUPS Print / 添加服务 的先后不固定），写死坐标必然点错，而点错的后果是
悄悄进了别的页面，排查结论就全歪了。

用法：
  python tools/ui.py texts            列出所有可见文字 + 中心坐标
  python tools/ui.py tap "默认打印服务"  点这一行（取该文字的父行中心，x 靠左避开开关）
  python tools/ui.py tap "启动 IPP 服务" --raw   直接点文字控件本身
"""
import re
import time
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = r"D:/workbody/无线打印安卓客户端"
ADB = ROOT + "/toolchain/android-sdk/platform-tools/adb.exe"


def adb(*args, shell=True):
    cmd = [ADB, "shell", *args] if shell else [ADB, *args]
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def serial():
    forced = None
    if "--serial" in sys.argv:
        forced = sys.argv[sys.argv.index("--serial") + 1]
    if forced:
        return forced

    # adb server 会随调用它的进程一起退出，所以这里每次都要自己拉起并等 mDNS
    # 发现完成（`devices` 在 server 刚起时是空的，等 8 秒左右才齐）。
    subprocess.run([ADB, "start-server"], capture_output=True)
    cache = ROOT + "/build/.wprint_serial"
    try:
        with open(cache, encoding="utf-8") as f:
            cached = f.read().strip()
        if cached:
            subprocess.run([ADB, "connect", cached], capture_output=True)
    except OSError:
        cached = ""
    for _ in range(12):
        out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
        online = [l.split()[0] for l in out.splitlines()[1:]
                  if len(l.split()) >= 2 and l.split()[1] == "device"]
        if cached and cached in online:
            return cached
        if online:
            # 多台在线时按「插着打印机的那台」挑，和 tools/adb.sh 同一套规则。
            if len(online) > 1:
                for s in online:
                    r = subprocess.run([ADB, "-s", s, "shell", "dumpsys usb"],
                                       capture_output=True, text=True, encoding="utf-8",
                                       errors="replace").stdout
                    if "host_connected=true" in r:
                        return s
            return online[0]
        time.sleep(1)
    raise SystemExit("没有在线设备")


S = serial()


def sh(*args):
    return subprocess.run([ADB, "-s", S, "shell", *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout


def dump():
    sh("uiautomator dump /sdcard/ui.xml")
    subprocess.run([ADB, "-s", S, "pull", "/sdcard/ui.xml", ROOT + "/build/ui.xml"],
                   capture_output=True)
    return ET.parse(ROOT + "/build/ui.xml")


def bounds(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not m:
        return None
    return tuple(int(x) for x in m.groups())


def walk(node, parent=None, acc=None):
    if acc is None:
        acc = []
    acc.append((node, parent))
    for ch in node:
        walk(ch, node, acc)
    return acc


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "texts"
    tree = dump()
    root = tree.getroot()
    nodes = walk(root)

    if cmd == "texts":
        for n, _ in nodes:
            t = n.get("text") or n.get("content-desc") or ""
            if t.strip():
                b = bounds(n)
                if b:
                    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
                    print(f"{t!r:45} center=({cx},{cy}) clickable={n.get('clickable')}")
        return

    if cmd == "tap":
        target = sys.argv[2]
        raw = "--raw" in sys.argv
        for n, parent in nodes:
            t = n.get("text") or n.get("content-desc") or ""
            if t.strip() != target:
                continue
            if raw:
                b = bounds(n)
                cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            else:
                # 往上找第一个 clickable 的祖先当「整行」，点它左侧 1/4 处，
                # 免得正好落在行尾的 Switch 上把开关拨了。
                row = n
                p = parent
                while p is not None and p.get("clickable") != "true":
                    row, p = p, None
                    # 手动向上：用 parent 索引不可靠，直接退回到文字节点本身
                    break
                b = bounds(n)
                cx, cy = b[0] + 40, (b[1] + b[3]) // 2
            print(f"tap {target!r} at ({cx},{cy})")
            sh(f"input tap {cx} {cy}")
            return
        raise SystemExit(f"没找到文字：{target}")


if __name__ == "__main__":
    main()
