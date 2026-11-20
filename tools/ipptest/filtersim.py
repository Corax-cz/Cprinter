#!/usr/bin/env python3
"""按 Android BIPS 的提问方式去问一遍打印机，看它回了什么。

为什么单独写：`ippcheck.py` 发的是「全量」请求（不带 requested-attributes），
走的是服务器的不过滤分支。而 BIPS / iOS 都会点名要属性，走的是**过滤分支**。
两条分支的代码不同，全量通过不代表过滤通过 —— 这一条已经踩过。

用法：
    python filtersim.py 192.168.123.6 [--port 8631]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ippclient import (  # noqa: E402
    Writer, base_operation, post, printer_uri,
    VERSION_1_1, OP_GET_PRINTER_ATTRIBUTES,
)
from ippcheck import parse_strict  # noqa: E402

# 直接抄自 Android bips 的 GetCapabilitiesTask 实际发出的列表（logcat 里抓的）。
BIPS_ASKS = [
    "media-supported",
    "media-type-supported",
    "output-bin-supported",
    "print-color-mode-supported",
    "print-quality-supported",
    "printer-output-tray",
    "printer-resolution-supported",
    "sides-supported",
    "printer-device-id",
    "epcl-version-supported",
    "pclm-raster-back-side",
    "pclm-strip-height-preferred",
    "pclm-compression-method-preferred",
    "pclm-source-resolution-supported",
    "pwg-raster-document-sheet-back",
    "document-format-details-supported",
    "media-ready",
    "media-col-ready",
    "print-scaling-supported",
    "print-scaling-default",
    "job-pages-per-set-supported",
    # 下面这些是 bips 的 parse_printerAttributes 隐含依赖的，一份完整的能力
    # 描述没有它们会走默认值，容易表现为「列表里有、点下去不可用」。
    "printer-state",
    "printer-state-reasons",
    "printer-is-accepting-jobs",
    "printer-name",
    "printer-info",
    "printer-make-and-model",
    "printer-uuid",
    "document-format-supported",
    "document-format-default",
    "media-default",
    "sides-default",
    "copies-supported",
    "copies-default",
    "orientation-requested-supported",
    "urf-supported",
    "printer-uri-supported",
]


def ask(host, port, attrs):
    w = base_operation(Writer(VERSION_1_1, OP_GET_PRINTER_ATTRIBUTES, 1),
                       printer_uri(host, port))
    # 0x44 = keyword。1setOf 只第一条带属性名，后续留空（RFC 8010 §3.1.5）。
    w.attr(0x44, "requested-attributes", attrs[0].encode())
    for a in attrs[1:]:
        w.attr(0x44, "", a.encode())
    w.done()
    headers, payload = post(host, port, w.done())
    return payload


def names(payload):
    out = []
    for _group, _tag, name, _val in parse_strict(payload):
        # 组标记与 end-of-attributes 的 name 是空串，靠它过滤足够。
        if name and name not in out:
            out.append(name)
    return out


def main():
    if len(sys.argv) < 2:
        raise SystemExit("用法：python filtersim.py <host> [--port 8631]")
    host = sys.argv[1]
    port = 8631
    if "--port" in sys.argv:
        port = int(sys.argv[sys.argv.index("--port") + 1])

    payload = ask(host, port, BIPS_ASKS)
    status = int.from_bytes(payload[2:4], "big")
    got = names(payload)
    print(f"{host}:{port}  status=0x{status:04x}  返回 {len(payload)} 字节 / {len(got)} 个属性")
    missing = [a for a in BIPS_ASKS if a not in got]
    if missing:
        print(f"\n被点名却没回（{len(missing)}）：")
        for a in missing:
            print("  -", a)
    else:
        print("\n点名的属性全部返回")
    extra = [n for n in got if n not in BIPS_ASKS]
    if extra:
        print(f"\n没点名也回了（{len(extra)}）：")
        for a in extra[:20]:
            print("  +", a)


if __name__ == "__main__":
    main()
