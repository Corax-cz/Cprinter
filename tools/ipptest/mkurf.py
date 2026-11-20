#!/usr/bin/env python3
"""生成一个 URF（Apple Raster / image/urf）样本，用来验证手机端的解码器。

为什么要自己造样本：AirPrint 的 URF 是苹果的事实标准，没有公开的生成器；
而「没有样本就写解析器」等于盲写。这个生成器按格式逐字段构造：

    文件头 12 B:  "UNIRAST\\0" + u32be 页数
    页头   32 B:  bpp / colorspace / duplex / quality / mediaType / inputSlot /
                  outputBin / copies / finishings(u32) / width / height / dpi / 保留 8B
    像素:         逐行 —— 1 字节「本行重复次数-1」，然后 PackBits 游程直到凑满行宽
                     c <  0x80 → 下一像素重复 c+1 次
                     c == 0x80 → 本行提前结束，余白
                     c >  0x80 → 接着 257-c 个像素原样照抄

生成器**故意**把四种情形都造出来，否则测不到对应分支：
  · 大量重复游程（白底、灰阶条）
  · 长短不一的行内字面量游程（圆、斜线、文字的锯齿边）
  · 行级重复（整行同色时用「本行重复 N 次」压掉）
  · 0x80 提前结束行（文字右侧的大片空白）

用法：
    python tools/ipptest/mkurf.py --dpi 300 --out testdata/stage4/urf300.urf
    python tools/ipptest/mkurf.py --dpi 300 --color --out testdata/stage4/urf300rgb.urf

灰度的 CRC32 会打在 stdout 上，直接和手机日志里的「灰度 CRC32=」对账 ——
比看纸面主观判断可靠得多。
"""

import argparse
import math
import struct
import sys
import zlib

sys.path.insert(0, __import__("os").path.dirname(__file__))
from mkpwg import Canvas, draw_page  # noqa: E402  同一套图案，便于横向对比

END_OF_ROW = 0x80


def packbits_row(px, i, n):
    """把 px[i:i+n] 编成游程。返回 (bytes, 消耗的像素数)。

    两个细节容易写错，这里都写清楚：
      · c < 0x80 是「重复 c+1 次」（标准 PackBits 在这里放字面量，URF 是反的）
      · c > 0x80 是「(257-c) 个字面量」，所以字面量最少 2 个、最多 128 个
    """
    if n <= 0:
        return b"", 0
    first = px[i]
    run = 1
    while run < n and run < 128 and px[i + run] == first:
        run += 1
    if run >= 2:
        return bytes([run - 1, first]), run

    # 单像素：向后找字面量，直到遇到「连续 3 个相同」（那更值得用重复编码）
    lit = 1
    while lit < n and lit < 128:
        if lit + 1 < n and px[i + lit] == px[i + lit - 1] == px[i + lit + 1]:
            break
        lit += 1
    if lit == 1:
        # 只有一个像素可用，只能退化成「重复 1 次」
        return bytes([0, first]), 1
    return bytes([257 - lit]) + bytes(px[i:i + lit]), lit


def encode_row(px, w):
    """编一行（单通道 8bpp）。表格行尾的大片白用 0x80 提前收尾。"""
    # 预先算出最后一个非白像素，之后「只剩白」的判断就是 O(1)
    last_non_white = -1
    for x in range(w - 1, -1, -1):
        if px[x] != 255:
            last_non_white = x
            break

    out = bytearray()
    i = 0
    while i < w:
        if i > last_non_white:
            remain = w - i
            if remain > 8:
                out.append(END_OF_ROW)      # 余白：一行一个字节就够了
                break
        chunk, used = packbits_row(px, i, w - i)
        out += chunk
        i += used
    return bytes(out)


def page_header(width, height, dpi, bpp, cs, duplex, quality):
    h = bytearray(32)
    h[0] = bpp
    h[1] = cs
    h[2] = duplex
    h[3] = quality
    h[4] = 1          # mediaType = stationery
    h[5] = 1          # inputSlot
    h[6] = 1          # outputBin
    h[7] = 1          # copies
    struct.pack_into(">I", h, 8, 0)                 # finishings
    struct.pack_into(">I", h, 12, width)
    struct.pack_into(">I", h, 16, height)
    struct.pack_into(">I", h, 20, dpi)
    return bytes(h)


def gray_of_rgb(buf):
    """把 24bpp RGB 摊平成灰度，规则与手机端一致（Rec.601）。"""
    out = bytearray(len(buf) // 3)
    for i in range(len(out)):
        r, g, b = buf[3 * i], buf[3 * i + 1], buf[3 * i + 2]
        out[i] = (r * 77 + g * 151 + b * 28) >> 8
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dpi", type=int, default=300, choices=[300, 600])
    ap.add_argument("--paper", default="A4")
    ap.add_argument("--pages", type=int, default=1)
    ap.add_argument("--color", action="store_true", help="用 24bpp sRGB 而不是 8bpp 灰度")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    sizes = {"A4": (210.0, 297.0), "LETTER": (215.9, 279.4)}
    mw, mh = sizes.get(a.paper.upper(), sizes["A4"])
    w = round(mw / 25.4 * a.dpi)
    h = round(mh / 25.4 * a.dpi)

    bpp = 24 if a.color else 8
    cs = 1 if a.color else 0          # 1 = sRGB, 0 = W（灰度）

    out = bytearray(b"UNIRAST\x00" + struct.pack(">I", a.pages))
    crcs = []

    for p in range(a.pages):
        c = Canvas(w, h)
        draw_page(c, a.dpi, "URF%d %d" % (a.dpi, p + 1))

        if a.color:
            # 24bpp：把灰度画布展开成 RGB（灰 r=g=b），再走同一套游程编码。
            rgb = bytearray(w * h * 3)
            for i in range(w * h):
                v = c.buf[i]
                rgb[3 * i] = v
                rgb[3 * i + 1] = v
                rgb[3 * i + 2] = v
            crcs.append(zlib.crc32(bytes(gray_of_rgb(rgb))) & 0xFFFFFFFF)
            out += page_header(w, h, a.dpi, bpp, cs, 1, 4)
            step = 3
            row_bytes = rgb
        else:
            crcs.append(zlib.crc32(bytes(c.buf)) & 0xFFFFFFFF)
            out += page_header(w, h, a.dpi, bpp, cs, 1, 4)
            step = 1
            row_bytes = c.buf

        # 行级压缩：相邻行完全相同时，把「本行重复 N 次」写在上一次的行首。
        groups = []          # [(repeat, payload)]
        for y in range(h):
            row = row_bytes[y * w * step:(y + 1) * w * step]
            payload = encode_row(row, w) if step == 1 else encode_row_rgb(row, w)
            if groups and groups[-1][1] == payload and groups[-1][0] < 256:
                groups[-1][0] += 1
            else:
                groups.append([1, payload])

        for repeat, payload in groups:
            out.append(repeat - 1)
            out += payload

    with open(a.out, "wb") as f:
        f.write(out)

    print("wrote %s: %d page(s) %dx%d @%ddpi %dbpp, %d bytes" % (
        a.out, a.pages, w, h, a.dpi, bpp, len(out)))
    for i, crc in enumerate(crcs):
        print("  page %d  gray CRC32 = %08X" % (i + 1, crc))


def encode_row_rgb(row, w):
    """24bpp 版本：同一个游程算法，只是「一个像素」是 3 字节。"""
    # 复用单通道逻辑不划算（像素宽度不同），这里单独写一份紧凑实现。
    last_non_white = -1
    for x in range(w - 1, -1, -1):
        if row[3 * x:3 * x + 3] != b"\xff\xff\xff":
            last_non_white = x
            break

    def px_bytes(i):
        return row[3 * i:3 * i + 3]

    out = bytearray()
    i = 0
    while i < w:
        if i > last_non_white:
            if w - i > 8:
                out.append(END_OF_ROW)
                break

        first = px_bytes(i)
        run = 1
        while run < w - i and run < 128 and px_bytes(i + run) == first:
            run += 1
        if run >= 2:
            out.append(run - 1)
            out += first
            i += run
            continue

        lit = 1
        while lit < w - i and lit < 128:
            if lit + 1 < w - i and px_bytes(i + lit) == px_bytes(i + lit - 1) == px_bytes(i + lit + 1):
                break
            lit += 1
        if lit == 1:
            out.append(0)
            out += first
            i += 1
        else:
            out.append(257 - lit)
            for k in range(lit):
                out += px_bytes(i + k)
            i += lit
    return bytes(out)


if __name__ == "__main__":
    main()
