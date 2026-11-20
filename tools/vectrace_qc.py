#!/usr/bin/env python3
"""生成「原图放大 vs 矢量放大」的对照图，用来验矢量化质量。

    python tools/vectrace_qc.py <原图> <svg> <输出png> <x> <y> <w> <h> [缩放]
"""
import os
import sys

import resvg_py
from PIL import Image

src, svg, out, x, y, w, h = sys.argv[1:8]
x, y, w, h = int(x), int(y), int(w), int(h)
scale = int(sys.argv[8]) if len(sys.argv) > 8 else 4

orig = Image.open(src).convert("RGB")
ow, oh = orig.size
box = (x, y, x + w, y + h)
left = orig.crop(box).resize((w * scale, h * scale), Image.NEAREST)

data = resvg_py.svg_to_bytes(svg_path=svg, width=ow)
vec = Image.open(__import__("io").BytesIO(bytes(data))).convert("RGBA")
white = Image.new("RGBA", vec.size, (255, 255, 255, 255))
white.alpha_composite(vec)
vec = white.convert("RGB").resize((ow, oh), Image.LANCZOS)
right = vec.crop(box).resize((w * scale, h * scale), Image.LANCZOS)

gap = 24
canvas = Image.new("RGB", (w * scale * 2 + gap, h * scale + 34), (240, 240, 240))
canvas.paste(left, (0, 34))
canvas.paste(right, (w * scale + gap, 34))
canvas.save(out)
print("saved", out, canvas.size)
