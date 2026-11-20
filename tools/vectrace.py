#!/usr/bin/env python3
"""把 logo 位图转成矢量（SVG）。

    python tools/vectrace.py            # 生成 tools/logo/svg/ 下全部成品
    python tools/vectrace.py --preview  # 顺带渲染大图做核对

依赖：vtracer, pillow, numpy, resvg-py
"""
import os
import re
import sys
from collections import deque

import numpy as np
import vtracer
from PIL import Image, ImageFilter, ImageOps

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "logo", "svg")
TMP = os.path.join(ROOT, "build", "vectrace")
PREVIEW = os.path.join(TMP, "preview")

SRC_RAVEN = r"Z:\乌鸦logo.jpeg"
SRC_PRINT = (
    r"C:\Users\C9370\.workbuddy\clipboard-images"
    r"\clipboard-2026-11-19T16-28-40-761Z-9d26833e.jpg"
)

# spline 曲线 + 去小噪点 + 多保留尖角
SPLINE = dict(
    colormode="binary",
    hierarchical="stacked",
    mode="spline",
    filter_speckle=4,
    corner_threshold=60,
    length_threshold=3.5,
    max_iterations=10,
    splice_threshold=45,
    path_precision=8,
)


# ---------- 位图预处理 ----------

def otsu(gray: np.ndarray) -> int:
    hist = np.bincount(gray.ravel(), minlength=256).astype(np.float64)
    total = hist.sum()
    omega = np.cumsum(hist) / total
    mu = np.cumsum(hist * np.arange(256)) / total
    denom = omega * (1.0 - omega)
    denom[denom == 0] = 1e-12
    return int(np.argmax((mu[-1] * omega - mu) ** 2 / denom))


def load_gray(path: str) -> np.ndarray:
    im = Image.open(path).convert("L")
    im = ImageOps.autocontrast(im, cutoff=0.2)
    return np.array(im)


def binarize(g: np.ndarray, thr: int, denoise: bool = True) -> np.ndarray:
    m = (g < thr).astype(np.uint8)
    if denoise:
        im = Image.fromarray(m * 255, "L")
        im = im.filter(ImageFilter.MedianFilter(3))
        m = (np.array(im) > 127).astype(np.uint8)
    return m


def label(mask: np.ndarray):
    """四连通标记，返回 {cid: (ys, xs)}（只留面积 > 200 的块）。"""
    h, w = mask.shape
    lab = np.zeros((h, w), np.int32)
    out, cur = {}, 0
    ys, xs = np.nonzero(mask)
    for y0, x0 in zip(ys.tolist(), xs.tolist()):
        if lab[y0, x0]:
            continue
        cur += 1
        q = deque([(y0, x0)])
        lab[y0, x0] = cur
        yy, xx = [], []
        while q:
            y, x = q.popleft()
            yy.append(y)
            xx.append(x)
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not lab[ny, nx]:
                    lab[ny, nx] = cur
                    q.append((ny, nx))
        if len(yy) > 200:
            out[cur] = (np.array(yy), np.array(xx))
    return out


def grow(mask: np.ndarray, radius: int) -> np.ndarray:
    im = Image.fromarray(mask.astype(np.uint8) * 255, "L")
    im = im.filter(ImageFilter.MaxFilter(radius * 2 + 1))
    return (np.array(im) > 127)


def save_mask(mask: np.ndarray, name: str) -> str:
    p = os.path.join(TMP, name)
    Image.fromarray(np.where(mask, 0, 255).astype(np.uint8), "L").save(p)
    return p


def trace(src: str, dst: str, **kw) -> None:
    vtracer.convert_image_to_svg_py(src, dst, **kw)


def _fmt(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def bake(d: str, dx: float, dy: float) -> str:
    """把 path 的 translate 烘进坐标里。

    vtracer 只输出 M / C / Z 三种绝对命令，所以逐对加偏移即可。烘焙是必需的：
    path 自己的 transform 会给 userSpaceOnUse 渐变带来同样的位移，渐变就对不上了。
    """
    toks = re.findall(r"[MCZ]|-?\d+(?:\.\d+)?(?:e[-+]?\d+)?", d)
    out, i = [], 0
    while i < len(toks):
        t = toks[i]
        out.append(t)
        i += 1
        pairs = 1 if t == "M" else 3 if t == "C" else 0
        for _ in range(pairs):
            out.append(_fmt(float(toks[i]) + dx))
            out.append(_fmt(float(toks[i + 1]) + dy))
            i += 2
    # M/C 之后可以省略命令字母，这里保留全部字母，输出更啰嗦但绝对正确
    return " ".join(out)


def paths_of(svg_file: str, fill: str | None = None) -> str:
    """取出 <path/> 片段：烘焙 transform、坐标取两位小数、可选替换 fill。"""
    s = open(svg_file, encoding="utf-8").read()
    s = s[s.index("<path"): s.rindex("</svg>")]

    def repl(m):
        d, rest = m.group(1), m.group(2)
        tr = re.search(r'transform="translate\(([-\d.]+),?\s*([-\d.]+)\)"', rest)
        dx, dy = (float(tr.group(1)), float(tr.group(2))) if tr else (0.0, 0.0)
        f = fill or (re.search(r'fill="([^"]+)"', rest).group(1) if 'fill="' in rest else "#000000")
        return f'<path d="{bake(d, dx, dy)}" fill="{f}"/>'

    return re.sub(r'<path d="([^"]+)"([^>]*)/>', repl, s).strip()


# ---------- 灰度渐变重建 ----------

def gradient_stops(g: np.ndarray, yy: np.ndarray, xx: np.ndarray, nbins: int = 10, edge: int | None = None):
    """按像素灰度沿最优方向分箱，返回 (四端点, [(offset, gray), ...])。"""
    X = np.c_[xx, yy, np.ones(len(xx))].astype(float)
    coef, *_ = np.linalg.lstsq(X, g[yy, xx], rcond=None)
    a, b = coef[0], coef[1]
    if abs(a) < 1e-6 and abs(b) < 1e-6:
        a, b = 1.0, 0.0
    norm = np.hypot(a, b)
    a, b = a / norm, b / norm
    t = a * xx + b * yy
    t0, t1 = float(t.min()), float(t.max())
    cx, cy = float(xx.mean()), float(yy.mean())
    half = (t1 - t0) / 2.0
    p0 = (cx - a * half, cy - b * half)
    p1 = (cx + a * half, cy + b * half)

    idx = np.clip(((t - t0) / max(t1 - t0, 1e-6) * nbins).astype(int), 0, nbins - 1)
    vals = g[yy, xx]
    stops = []
    for i in range(nbins):
        sel = idx == i
        if not sel.any():
            continue
        v = float(np.median(vals[sel]))
        off = (i + 0.5) / nbins
        stops.append((round(off, 4), int(round(min(255, max(0, v))))))
    # 首尾延伸到 0 / 1，避免端头出现硬边
    stops[0] = (0.0, stops[0][1])
    stops[-1] = (1.0, stops[-1][1])
    if edge is not None:
        # 形状是在灰度=阈值处硬切的，渐变必须在那条边上刚好接近白，切口才看不出来
        stops[-1] = (1.0, edge)
    # 合并相邻同色
    merged = [stops[0]]
    for off, v in stops[1:]:
        if v != merged[-1][1]:
            merged.append((off, v))
    return (p0, p1), merged


def hexgray(v: int) -> str:
    return "#%02X%02X%02X" % (v, v, v)


def grad_el(gid: str, axis, stops) -> str:
    (x1, y1), (x2, y2) = axis
    body = "".join(
        f'<stop offset="{off}" stop-color="{hexgray(v)}"/>' for off, v in stops
    )
    return (
        f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
        f'x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}">{body}</linearGradient>'
    )


# ---------- 两张图各自的处理 ----------

def build_raven() -> None:
    g = load_gray(SRC_RAVEN)
    t = otsu(g)
    print(f"  otsu={t}")
    m = binarize(g, t)
    p = save_mask(m, "raven_bin.png")
    dst = os.path.join(OUT, "乌鸦logo.svg")
    trace(p, dst, **SPLINE)
    print(f"  → 乌鸦logo.svg  {os.path.getsize(dst) / 1024:.1f} KB")


def build_print_rich() -> None:
    """无线打印：黑主体 + WiFi 弧线/纸线用真渐变还原。"""
    g = load_gray(SRC_PRINT).astype(np.int16)
    H, W = g.shape
    dark = binarize(g.astype(np.uint8), 140)
    comps = label(dark)

    arc_ids, line_ids, main_ids = [], [], []
    for cid, (yy, xx) in comps.items():
        area = len(yy)
        mg = float(g[yy, xx].mean())
        if yy.max() < 0.40 * H and xx.max() < 0.60 * W and area > 3000:
            arc_ids.append(cid)
        elif area < 4000 and mg > 55:
            line_ids.append(cid)
        else:
            main_ids.append(cid)
    print(f"  弧线块 {arc_ids} / 纸线块 {line_ids} / 主体块 {len(main_ids)} 个")

    def mask_of(ids):
        m = np.zeros((H, W), bool)
        for cid in ids:
            yy, xx = comps[cid]
            m[yy, xx] = True
        return m

    arc_core, line_core, main = mask_of(arc_ids), mask_of(line_ids), mask_of(main_ids)

    # 弧线与纸线是渐变淡出的，阈值要放宽把尾巴也包进来；但不能越过主体，
    # 最内一条弧线离鸟头只有几个像素，dilate 太狠会把两者粘成一块。
    # 190 而不是更宽的阈值：更高的阈值会让等值线在末端拉成一条竖直的边，
    # 190 时轮廓刚好能收回成圆头，看起来和原图淡出的末端一致。
    soft = g < 190
    keep_out = grow(main, 3)
    arc_mask = grow(arc_core, 34) & soft & ~keep_out
    line_mask = grow(line_core, 16) & soft & ~keep_out

    p_main = save_mask(main, "print_main.png")
    p_arc = save_mask(arc_mask, "print_arc.png")
    p_line = save_mask(line_mask, "print_line.png")
    for p, name in ((p_main, "print_main"), (p_arc, "print_arc"), (p_line, "print_line")):
        trace(p, os.path.join(TMP, name + ".svg"), **SPLINE)
    # 渐变拟合：在完整形状上取样（含淡出的尾巴），只在纯白边缘处排除
    def group_pixels(mask):
        sel = mask & (g < 250)
        yy, xx = np.nonzero(sel)
        return yy, xx

    arc_axis, arc_stops = gradient_stops(g, *group_pixels(arc_mask), nbins=12, edge=225)
    line_axis, line_stops = gradient_stops(g, *group_pixels(line_mask), nbins=8)
    print("  弧线渐变", arc_stops)
    print("  纸线渐变", line_stops)

    svg = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
        f'viewBox="0 0 {W} {H}">',
        "<defs>",
        grad_el("arcGrad", arc_axis, arc_stops),
        grad_el("lineGrad", line_axis, line_stops),
        "</defs>",
        '<g>',
        paths_of(os.path.join(TMP, "print_main.svg"), "#000000"),
        "</g>",
        '<g>',
        paths_of(os.path.join(TMP, "print_arc.svg"), "url(#arcGrad)"),
        "</g>",
        '<g>',
        paths_of(os.path.join(TMP, "print_line.svg"), "url(#lineGrad)"),
        "</g>",
        "</svg>",
    ]
    dst = os.path.join(OUT, "无线打印-渐变.svg")
    open(dst, "w", encoding="utf-8").write("\n".join(svg))
    print(f"  → 无线打印-渐变.svg  {os.path.getsize(dst) / 1024:.1f} KB")

    # 单色版：所有元素一律纯黑，几何和渐变版共用同一份 mask，方便两版叠着比
    flat = main | arc_mask | line_mask
    p = save_mask(flat, "print_flat.png")
    dst = os.path.join(OUT, "无线打印-单色.svg")
    trace(p, dst, **SPLINE)
    print(f"  → 无线打印-单色.svg  {os.path.getsize(dst) / 1024:.1f} KB")


def add_viewbox(path: str) -> None:
    """补 viewBox：只有 width/height 的 SVG 嵌到别的容器里不会跟着缩放。"""
    s = open(path, encoding="utf-8").read()
    tag = re.search(r"<svg[^>]*>", s).group(0)
    if "viewBox" in tag:
        return
    w = re.search(r'width="([\d.]+)"', tag).group(1)
    h = re.search(r'height="([\d.]+)"', tag).group(1)
    s = s.replace(tag, tag.replace("<svg", f'<svg viewBox="0 0 {w} {h}"', 1), 1)
    open(path, "w", encoding="utf-8").write(s)


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(TMP, exist_ok=True)
    print("[1/2] 乌鸦 logo")
    build_raven()
    print("[2/2] 无线打印 logo")
    build_print_rich()
    for name in sorted(os.listdir(OUT)):
        if name.endswith(".svg"):
            add_viewbox(os.path.join(OUT, name))
    if "--preview" in sys.argv:
        os.makedirs(PREVIEW, exist_ok=True)
        import resvg_py

        for name in sorted(os.listdir(OUT)):
            if not name.endswith(".svg"):
                continue
            png = os.path.join(PREVIEW, name.replace(".svg", ".png"))
            with open(png, "wb") as f:
                f.write(bytes(resvg_py.svg_to_bytes(svg_path=os.path.join(OUT, name), width=2000)))
            print("  preview:", os.path.basename(png))


if __name__ == "__main__":
    main()
