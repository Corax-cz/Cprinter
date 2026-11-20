#!/usr/bin/env python3
"""复现 iOS 的 Get-Printer-Attributes 请求，dump 原始响应并逐字段解码。

存在的理由：iPhone 反复要同一组属性、从不 Create-Job，光看"响应 4834 字节"
判断不出问题。要么字节层面看编码，要么一直猜。

用法：
    python tools/ipptest/iosprobe.py 192.168.123.60            # iOS 那 4 项
    python tools/ipptest/iosprobe.py 192.168.123.60 --all      # iOS 那 33 项
    python tools/ipptest/iosprobe.py 192.168.123.60 --raw      # 额外 dump hex
"""
import sys
import os
import argparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ippclient import Writer, base_operation, post, printer_uri, VERSION_2_0, OP_GET_PRINTER_ATTRIBUTES

# 日志里 iPhone 发过的两组 requested-attributes，原样照抄。
IOS_SMALL = [
    "printer-state-reasons", "media-source-supported",
    "printer-input-tray", "printer-mandatory-job-attributes",
]
IOS_LARGE = [
    "copies-supported", "document-format-supported", "document-password-supported",
    "finishings-col-database", "finishings-supported", "output-bin-default",
    "printer-output-tray", "output-bin-supported", "identify-actions-supported",
    "job-account-id-supported", "jpeg-features-supported", "jpeg-k-octets-supported",
    "jpeg-x-dimension-supported", "jpeg-y-dimension-supported",
    "landscape-orientation-requested-preferred", "media-col-supported",
    "media-source-supported", "media-col-ready", "operations-supported",
    "pdf-k-octets-supported", "print-color-mode-supported",
    "print-quality-supported", "print-scaling-supported",
    "printer-charge-info-uri", "printer-input-tray", "media-type-supported",
    "printer-mandatory-job-attributes", "printer-state-reasons",
    "sides-supported", "job-presets-supported", "printer-more-info",
    "printer-supply-info-uri", "printer-strings-uri",
]

# RFC 8010 §3.5 的 value-tag 分配。注意 0x21 是 integer、0x34 是 begCollection ——
# 凭印象写会全错，之前就写过一版把 0x21 当 textWithoutLanguage 的表。
TAGS = {
    0x10: "unsupported", 0x11: "default", 0x12: "unknown", 0x13: "no-value",
    0x15: "not-settable", 0x16: "delete-attribute", 0x17: "admin-define",
    0x21: "integer", 0x22: "boolean", 0x23: "enum",
    0x30: "octetString", 0x31: "dateTime", 0x32: "resolution",
    0x33: "rangeOfInteger", 0x34: "begCollection",
    0x35: "textWithLanguage", 0x36: "nameWithLanguage", 0x37: "endCollection",
    0x41: "textWithoutLanguage", 0x42: "nameWithoutLanguage",
    0x44: "keyword", 0x45: "uri", 0x46: "uriScheme",
    0x47: "charset", 0x48: "naturalLanguage", 0x49: "mimeMediaType",
    0x4A: "memberAttrName",
}

STRING_TAGS = {0x41, 0x42, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A}


def parse_attrs(buf):
    """返回 [(groupName, tag, name, valueBytes)]，遇到编码错误抛异常。"""
    out = []
    if len(buf) < 8:
        raise ValueError(f"响应过短：{len(buf)} 字节")
    ver_major, ver_minor = buf[0], buf[1]
    status = int.from_bytes(buf[2:4], "big")
    rid = int.from_bytes(buf[4:8], "big")
    out.append(("HEADER", None, "version", f"{ver_major}.{ver_minor}"))
    out.append(("HEADER", None, "status", f"0x{status:04X}"))
    out.append(("HEADER", None, "request-id", str(rid)))

    p = 8
    group = "?"
    while p < len(buf):
        tag = buf[p]
        if tag <= 0x0F:          # delimiter
            group = {0x01: "operation", 0x02: "job", 0x04: "printer",
                     0x03: "end"}.get(tag, f"delim 0x{tag:02X}")
            out.append((group, tag, "", ""))
            p += 1
            continue
        if p + 3 > len(buf):
            raise ValueError(f"偏移 {p} 处属性名长度越界")
        nlen = int.from_bytes(buf[p + 1:p + 3], "big")
        if p + 3 + nlen > len(buf):
            raise ValueError(f"偏移 {p} 处属性名越界（nlen={nlen}）")
        name = buf[p + 3:p + 3 + nlen].decode("utf-8", "replace")
        p += 3 + nlen
        if p + 2 > len(buf):
            raise ValueError(f"属性 {name} 缺值长度")
        vlen = int.from_bytes(buf[p:p + 2], "big")
        p += 2
        value = buf[p:p + vlen]
        if len(value) != vlen:
            raise ValueError(f"属性 {name} 值被截断：要 {vlen} 实到 {len(value)}")
        p += vlen
        tname = TAGS.get(tag, f"tag 0x{tag:02X}")
        if tag == 0x34:          # begCollection
            out.append((group, tag, name, "<collection>"))
            depth = 1
            while depth and p < len(buf):
                mt = buf[p]
                if p + 3 > len(buf):
                    raise ValueError(f"collection 内偏移 {p} 越界")
                mnl = int.from_bytes(buf[p + 1:p + 3], "big")
                mname = buf[p + 3:p + 3 + mnl].decode("utf-8", "replace")
                p += 3 + mnl
                if p + 2 > len(buf):
                    raise ValueError(f"collection 成员 {mname!r} 缺长度")
                mvl = int.from_bytes(buf[p:p + 2], "big")
                p += 2
                mval = buf[p:p + mvl]
                if len(mval) != mvl:
                    raise ValueError(
                        f"collection 成员 {mname!r} 值被截断：要 {mvl} 实到 {len(mval)}"
                        f"（tag=0x{mt:02X}，偏移 {p}）")
                p += mvl
                # endCollection 也是标准属性格式 37 + 名长 + 值长，必须整条读完。
                # 只 p+=1 跳过 tag 会让后面 4 字节全部错位 —— 这个坑踩过。
                if mt == 0x37:
                    depth -= 1
                    out.append((group, mt, "", "<end>"))
                    continue
                if mt == 0x34:
                    depth += 1
                out.append((group, mt, "· " + mname, _fmt(mt, mval)))
        else:
            out.append((group, tag, name, _fmt(tag, value)))
    if p != len(buf):
        raise ValueError(f"解析未对齐：停在 {p}，总长 {len(buf)}")
    return out


def _fmt(tag, value):
    """把一个 value 字段渲染成可读串。"""
    if tag == 0x21:
        return str(int.from_bytes(value, "big", signed=True))
    if tag == 0x22:
        return "true" if value == b"\x01" else "false"
    if tag == 0x23:
        return str(int.from_bytes(value, "big", signed=True))
    if tag == 0x33:            # rangeOfInteger：两个 int32
        lo = int.from_bytes(value[0:4], "big", signed=True)
        hi = int.from_bytes(value[4:8], "big", signed=True)
        return f"{lo}..{hi}"
    if tag == 0x32:            # resolution：x(4) y(4) units(1)
        x = int.from_bytes(value[0:4], "big", signed=True)
        y = int.from_bytes(value[4:8], "big", signed=True)
        return f"{x}x{y} dpi" if value[8:9] == b"\x03" else f"{x}x{y} dpcm"
    if tag in (0x35, 0x36) and len(value) >= 2:
        # 带语言的字符串：langLen(2) lang(n) textLen(2) text(n)
        ll = int.from_bytes(value[0:2], "big")
        tl = int.from_bytes(value[2 + ll:4 + ll], "big")
        return value[4 + ll:4 + ll + tl].decode("utf-8", "replace")
    if tag in STRING_TAGS:
        return value.decode("utf-8", "replace")
    if tag in (0x13, 0x30):
        return value.hex(" ")
    return value.hex(" ")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("--port", type=int, default=8631)
    ap.add_argument("--all", action="store_true", help="用 iOS 那 33 项")
    ap.add_argument("--raw", action="store_true", help="dump 原始 hex")
    args = ap.parse_args()

    want = IOS_LARGE if args.all else IOS_SMALL
    w = base_operation(Writer(VERSION_2_0, OP_GET_PRINTER_ATTRIBUTES, 1),
                       printer_uri(args.host, args.port))
    # requested-attributes 是 1setOf keyword：同名属性重复写即可。
    for a in want:
        w.keyword("requested-attributes", a)
    payload = w.done()

    print(f"请求 requested-attributes {len(want)} 项，body {len(payload)} 字节")
    headers, resp = post(args.host, args.port, payload)
    print(f"响应 {len(resp)} 字节\n")

    if args.raw:
        for i in range(0, len(resp), 16):
            chunk = resp[i:i + 16]
            print(f"  {i:04X}  {chunk.hex(' '):<48}  "
                  f"{''.join(chr(c) if 32 <= c < 127 else '.' for c in chunk)}")
        print()

    try:
        rows = parse_attrs(resp)
    except ValueError as e:
        print(f"✗ 解码失败：{e}")
        return 1

    returned = set()
    for group, tag, name, val in rows:
        if tag in (0x01, 0x02, 0x04, 0x03):
            print(f"\n[{group}]")
            continue
        if tag is None:
            print(f"  {name} = {val}")
            continue
        if name.startswith("· "):
            print(f"    {name[2:]:<28} = {val}")
            continue
        returned.add(name)
        t = TAGS.get(tag, f"0x{tag:02X}")
        print(f"  {name:<42} ({t}) = {val}")

    print(f"\n── 请求了但没返回（{len(set(want) - returned)} 项）──")
    missing = [a for a in want if a not in returned]
    if missing:
        for a in missing:
            print(f"  • {a}")
    else:
        print("  （无）")
    print(f"\n解码正常，共 {len(rows)} 条记录，已对齐到结尾。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
