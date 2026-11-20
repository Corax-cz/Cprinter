#!/usr/bin/env python3
"""生成一个符合 PWG 5102.4 的 PWG Raster 样本，用来验证手机端的解码器。

为什么要自己造样本：手边没有 CUPS 的 rastertopwg，而「没有样本就写解析器」
等于盲写。这个生成器按规范逐字段构造页头，图案里放了三种能一眼看出的错误模式：

  · 外框不闭合 / 缺角   → 行宽（bytesPerLine）或行序错了
  · 圆不圆、斜线断成台阶 → 采样或位序错了
  · 灰阶条反相（黑底白块）→ 0/255 极性搞反了
  · 顶部文字整块黑/白    → 色彩空间判断错了

用法：
    python tools/ipptest/mkpwg.py --dpi 600 --out testdata/stage3/pwg600.pwg
"""

import argparse
import struct
import zlib

# 4x7 点阵字模（够画 "PWG 600" 这类标识）
FONT = {
    "P": ["1111", "1001", "1001", "1111", "1000", "1000", "1000"],
    "W": ["1001", "1001", "1001", "1001", "1001", "1111", "1001"],
    "G": ["0111", "1000", "1000", "1011", "1001", "1001", "0111"],
    " ": ["0000", "0000", "0000", "0000", "0000", "0000", "0000"],
    "0": ["0110", "1001", "1001", "1001", "1001", "1001", "0110"],
    "1": ["0010", "0110", "0010", "0010", "0010", "0010", "0111"],
    "2": ["0110", "1001", "0001", "0010", "0100", "1000", "1111"],
    "6": ["0111", "1000", "1000", "1111", "1001", "1001", "0111"],
    "3": ["1111", "0001", "0001", "0111", "0001", "0001", "1111"],
}


class Canvas:
    def __init__(self, w, h):
        self.w = w
        self.h = h
        self.buf = bytearray([255]) * (w * h)  # 255 = 白

    def px(self, x, y, v=0):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.buf[y * self.w + x] = v

    def rect(self, x0, y0, x1, y1, v=0, width=1):
        for t in range(width):
            for x in range(x0 + t, x1 - t + 1):
                self.px(x, y0 + t, v)
                self.px(x, y1 - t, v)
            for y in range(y0 + t, y1 - t + 1):
                self.px(x0 + t, y, v)
                self.px(x1 - t, y, v)

    def fill(self, x0, y0, x1, y1, v):
        for y in range(max(0, y0), min(self.h, y1 + 1)):
            row = y * self.w
            for x in range(max(0, x0), min(self.w, x1 + 1)):
                self.buf[row + x] = v

    def line(self, x0, y0, x1, y1, v=0, width=2):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        while True:
            for oy in range(width):
                for ox in range(width):
                    self.px(x0 + ox, y0 + oy, v)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def circle(self, cx, cy, r, v=0, width=2):
        # 按角度采样再连线：对这个半径级别足够圆，且比逐像素判定好写。
        import math
        steps = max(64, r * 4)
        prev = None
        for i in range(steps + 1):
            a = 2 * math.pi * i / steps
            p = (int(cx + r * math.cos(a)), int(cy + r * math.sin(a)))
            if prev:
                self.line(prev[0], prev[1], p[0], p[1], v, width)
            prev = p

    def text(self, x, y, s, scale, v=0):
        cx = x
        for ch in s.upper():
            rows = FONT.get(ch)
            if not rows:
                cx += 5 * scale
                continue
            for ry, line in enumerate(rows):
                for rx, bit in enumerate(line):
                    if bit == "1":
                        self.fill(
                            cx + rx * scale, y + ry * scale,
                            cx + rx * scale + scale - 1, y + ry * scale + scale - 1, v,
                        )
            cx += (len(rows[0]) + 1) * scale


def page_header(width, height, dpi, total_pages):
    """按 cups_page_header2_t 的字段顺序拼 1796 字节。"""
    h = bytearray(1796)

    def u32(off, val):
        struct.pack_into(">I", h, off, val & 0xFFFFFFFF)

    def name(off, s):
        b = s.encode("ascii")[:63]
        h[off:off + len(b)] = b

    # 4 个 64 字节字符串
    name(0, "PWG Raster")
    name(64, "")
    name(128, "")
    name(192, "")

    u32(256, 0)              # AdvanceDistance
    u32(260, 0)              # AdvanceMedia
    u32(264, 0)              # Collate
    u32(268, 0)              # CutMedia
    u32(272, 0)              # Duplex
    u32(276, dpi)            # HWResolution[0]
    u32(280, dpi)            # HWResolution[1]
    for i in range(4):       # ImagingBoundingBox，单位磅
        u32(284 + 4 * i, 0)
    u32(300, 0)              # InsertSheet
    u32(304, 0)              # Jog
    u32(308, 0)              # LeadingEdge
    u32(312, 0)              # Margins[0]
    u32(316, 0)              # Margins[1]
    u32(320, 0)              # ManualFeed
    u32(324, 0)              # MediaPosition
    u32(328, 0)              # MediaWeight
    u32(332, 0)              # MirrorPrint
    u32(336, 0)              # NegativePrint
    u32(340, 1)              # NumCopies
    u32(344, 0)              # Orientation
    u32(348, 0)              # OutputFaceUp
    u32(352, 595)            # PageSize[0]，A4 = 595pt
    u32(356, 842)            # PageSize[1]
    u32(360, 0)              # Separations
    u32(364, 0)              # TraySwitch
    u32(368, 0)              # Tumble

    u32(372, width)          # cupsWidth
    u32(376, height)         # cupsHeight
    u32(380, 0)              # cupsMediaType
    u32(384, 8)              # cupsBitsPerColor
    u32(388, 8)              # cupsBitsPerPixel
    u32(392, width)          # cupsBytesPerLine
    u32(396, 0)              # cupsColorOrder = CHUNKED
    u32(400, 18)             # cupsColorSpace = CUPS_CSPACE_SW (sGray)
    u32(404, 0)              # cupsCompression = none
    u32(408, 0)              # cupsRowCount
    u32(412, 0)              # cupsRowFeed
    u32(416, 0)              # cupsRowStep
    u32(420, 1)              # cupsNumColors

    u32(452 + 4 * 0, total_pages)  # cupsInteger[0] = TotalPageCount
    return bytes(h)


def draw_page(c, dpi, label):
    w, h = c.w, c.h
    margin = int(dpi * 0.15)
    c.rect(margin, margin, w - margin, h - margin, 0, max(2, dpi // 150))

    # 顶部标识
    c.text(int(w * 0.12), int(h * 0.08), label, max(4, dpi // 60), 0)

    # 中间同心圆：验证几何与圆度
    cx, cy = w // 2, int(h * 0.42)
    for i, r in enumerate((int(dpi * 0.45), int(dpi * 0.75), int(dpi * 1.05))):
        c.circle(cx, cy, r, 0, max(2, dpi // 200))

    # 两条对角线 + 中心十字：错位会立刻看出来
    c.line(margin, margin, w - margin, h - margin, 0, max(1, dpi // 300))
    c.line(w - margin, margin, margin, h - margin, 0, max(1, dpi // 300))
    c.line(cx - dpi // 3, cy, cx + dpi // 3, cy, 0, max(2, dpi // 150))
    c.line(cx, cy - dpi // 3, cx, cy + dpi // 3, 0, max(2, dpi // 150))

    # 底部灰阶条：8 级，从黑到白。极性反了会变成「从白到黑」，一眼可见。
    # 高度只给 2%（约 6mm）：原来占 8% 页高、整页通栏，第一段是纯黑，
    # 打出来就是一条又长又宽的实心黑带 —— 判读信息量不变，墨却省了四倍。
    bar_y0, bar_y1 = int(h * 0.845), int(h * 0.865)
    seg = (w - 2 * margin) // 16
    for i in range(8):
        v = int(255 * i / 7)
        x0 = margin + i * seg
        c.fill(x0, bar_y0, x0 + seg - 1, bar_y1, v)
        c.rect(x0, bar_y0, x0 + seg - 1, bar_y1, 0, max(1, dpi // 400))

    # 细线组：验证行对齐（每 4 像素一根，行错位会糊成一片）
    y = int(h * 0.91)
    for i in range(0, w - 2 * margin, 8):
        c.line(margin + i, y, margin + i, y + dpi // 12, 0, 2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dpi", type=int, default=600, choices=[300, 600])
    ap.add_argument("--paper", default="A4")
    ap.add_argument("--pages", type=int, default=1)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    sizes = {"A4": (210.0, 297.0), "LETTER": (215.9, 279.4)}
    mw, mh = sizes.get(a.paper.upper(), sizes["A4"])
    w = round(mw / 25.4 * a.dpi)
    h = round(mh / 25.4 * a.dpi)

    out = bytearray()
    crcs = []
    for p in range(a.pages):
        c = Canvas(w, h)
        # 每页带页号：多页时才能从纸面（以及 CRC）判断是不是串序或重复。
        draw_page(c, a.dpi, "PWG%d %d" % (a.dpi, p + 1))
        # 8bpp 无压缩时，客户端解码出的灰度数组应当与这里的 buf 逐字节相同。
        # 把这个 CRC 打出来，就能和手机端日志里的值直接对账，不需要反解 PCL。
        crcs.append(zlib.crc32(bytes(c.buf)) & 0xFFFFFFFF)
        out += struct.pack(">I", 0x52615332)          # "RaS2"
        out += page_header(w, h, a.dpi, a.pages)
        out += c.buf

    with open(a.out, "wb") as f:
        f.write(out)

    print("wrote %s: %d page(s) %dx%d @%ddpi, %d bytes" % (a.out, a.pages, w, h, a.dpi, len(out)))
    for i, crc in enumerate(crcs):
        print("  page %d  gray CRC32 = %08X" % (i + 1, crc))


if __name__ == "__main__":
    main()
