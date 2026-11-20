#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 tools/logo/logo-src.jpg 转成 Android VectorDrawable，供测试页与启动页共用。

为什么要矢量：位图放到纸上 / 启动页放大会糊（用户原话「都糊了」）。PdfDocument 的
canvas 本身就是矢量设备，`VectorDrawable.draw()` 直接落成 PDF 路径，600dpi 下也
是锐的，不经过位图缩放。

算法（纯标准库 + PIL，不依赖 numpy/opencv）：
  1. 灰度化 → Marching Squares 提等值线（亚像素，抗锯齿边取等值点）
  2. 线段首尾相接成闭合环
  3. Ramer–Douglas–Peucker 抽稀（容差 0.9px，误差万分之六）
  4. Catmull-Rom → 三次贝塞尔，输出 pathData
  5. 用 evenOdd 填充（环的绕向不必统一，小孔自然留白）

源图是带渐变的（Wi-Fi 弧线往右上淡出、打印机机身有高光），阈值取 150 而不是
128：太严会把淡出的弧线尾巴切掉，太松又会把高光吃掉。反正是给激光机打的，
最终必须是纯黑，渐变本来也保不住。

产物：
  app/src/main/res/drawable/ic_logo.xml       24x24 vector（启动页用）
  app/src/main/res/values/logo_path.xml       同一份 pathData 的字符串（打印用）
  shot/logo_trace_preview.png                 回填比对图（红=原图，蓝=矢量化后）

为什么要多出一份字符串：`VectorDrawable.draw()` 在带 tint 时会把整张图**栅格化成一张
位图**再贴上去——实测页面内容流里出现的是 `/Subtype /Image /Width 30 /Height 30`，
30x30 像素贴到 30pt 的纸上，等于自己把矢量又变回了糊的位图。所以打印这条路改成
拿同一份 pathData 用 `androidx.core.graphics.PathParser` 还原成 `Path` 直接画，
落进 PDF 才是真路径。两份内容同源（都由本脚本生成），不要手改其中任何一份。

用法： python tools/mklogo.py [源图] [阈值]
"""
import math
import os
import sys

from PIL import Image, ImageChops, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "tools/logo/logo-src.jpg")
OUT_XML = os.path.join(ROOT, "app/src/main/res/drawable/ic_logo.xml")
OUT_PATH = os.path.join(ROOT, "app/src/main/res/values/logo_path.xml")
OUT_PREVIEW = os.path.join(ROOT, "shot/logo_trace_preview.png")

THRESHOLD = int(sys.argv[2]) if len(sys.argv) > 2 else 150   # 0..255，小于它就是墨
VIEWPORT = 24.0      # vector 的 viewport 边长
MARGIN = 0.5         # 图形四周留白（viewport 单位）
RDP_TOL = 0.9        # px @ 原图分辨率
CURVE_SAMPLES = 16   # 反算预览时的曲线细分


# ───────────────────────────── 1. marching squares ──────────────────────────

def trace_contours(path):
    im = Image.open(path).convert("L")
    W, H = im.size
    data = im.load()

    # 先把整张图读进一维数组：纯 Python 里 list-of-list 索引比 data[x, y] 快得多
    px = [0] * (W * H)
    for y in range(H):
        row = y * W
        for x in range(W):
            px[row + x] = data[x, y]

    # 邻接表：点 key -> 该点上的边 id 列表。不能只存「起点→终点」——一个点上
    # 可能同时挂着两三条边（相邻格共用顶点、歧义格），只存一条会互相覆盖，
    # 环就断成几十段碎线（第一版就是这么错的，IoU 只有 0.05）。
    nodes = {}
    edges = []

    def add_edge(a, b):
        ka, kb = key(a), key(b)
        if ka == kb:
            return
        eid = len(edges)
        edges.append((ka, kb))
        nodes.setdefault(ka, []).append(eid)
        nodes.setdefault(kb, []).append(eid)
    for y in range(H - 1):
        for x in range(W - 1):
            tl = px[y * W + x]
            tr = px[y * W + x + 1]
            br = px[(y + 1) * W + x + 1]
            bl = px[(y + 1) * W + x]
            idx = (8 if tl < THRESHOLD else 0) | (4 if tr < THRESHOLD else 0) | \
                  (2 if br < THRESHOLD else 0) | (1 if bl < THRESHOLD else 0)
            if idx in (0, 15):
                continue

            def cross(a, b, pa, pb):
                # a、b 是阈值化的正负关系，pa/pb 是两端灰度值
                if a == b:
                    return 0.5
                t = (THRESHOLD - pa) / float(pb - pa)
                return min(1.0, max(0.0, t))

            top = (x + cross(tl < THRESHOLD, tr < THRESHOLD, tl, tr), y)
            right = (x + 1, y + cross(tr < THRESHOLD, br < THRESHOLD, tr, br))
            bottom = (x + cross(bl < THRESHOLD, br < THRESHOLD, bl, br), y + 1)
            left = (x, y + cross(tl < THRESHOLD, bl < THRESHOLD, tl, bl))

            center = (tl + tr + br + bl) / 4.0
            inside = center < THRESHOLD

            pairs = {
                1: [(left, bottom)], 2: [(bottom, right)], 3: [(left, right)],
                4: [(top, right)], 6: [(top, bottom)], 7: [(top, left)],
                8: [(top, left)], 9: [(top, bottom)], 11: [(top, right)],
                12: [(left, right)], 13: [(bottom, right)], 14: [(left, bottom)],
            }
            if idx == 5:      # TR + BL
                pairs[5] = [(top, left), (bottom, right)] if inside else [(top, right), (bottom, left)]
            if idx == 10:     # TL + BR
                pairs[10] = [(top, right), (bottom, left)] if inside else [(top, left), (bottom, right)]

            for a, b in pairs[idx]:
                add_edge(a, b)

    # ── 2. 把边串成闭合环（无向图遍历，每条边只走一次） ────────────────────
    used = [False] * len(edges)
    loops = []
    for eid0 in range(len(edges)):
        if used[eid0]:
            continue
        used[eid0] = True
        ka, kb = edges[eid0]
        loop = [ka, kb]
        cur = kb
        while True:
            nxt = None
            for eid in nodes.get(cur, ()):
                if not used[eid]:
                    used[eid] = True
                    a, b = edges[eid]
                    nxt = b if a == cur else a
                    break
            if nxt is None or nxt == loop[0]:
                break
            loop.append(nxt)
            cur = nxt
        if len(loop) > 12:
            loops.append([(k[0] / 2.0, k[1] / 2.0) for k in loop])
    return loops, (W, H)


def key(p):
    return (int(round(p[0] * 2)), int(round(p[1] * 2)))


# ───────────────────────────── 3. RDP 抽稀 ──────────────────────────────────

def rdp(points, tol):
    if len(points) < 3:
        return points
    # 闭合环：先找离起点最远的点拆成两段，避免把环的「接缝」当端点
    a, b = points[0], points[len(points) // 2]
    return _rdp(points, a, b, tol)


def _rdp(pts, a, b, tol):
    stack = [(0, len(pts) - 1)]
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        ax, ay = pts[i]
        bx, by = pts[j]
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy) or 1e-9
        best, bestd = -1, -1.0
        for k in range(i + 1, j):
            px_, py_ = pts[k]
            d = abs(dy * (px_ - ax) - dx * (py_ - ay)) / norm
            if d > bestd:
                best, bestd = k, d
        if bestd > tol:
            keep[best] = True
            stack.append((i, best))
            stack.append((best, j))
    return [p for p, k in zip(pts, keep) if k]


# ──────────────────── 4. Catmull-Rom → 三次贝塞尔 pathData ───────────────────

def to_path(loops):
    cmds = []
    for pts in loops:
        n = len(pts)
        if n < 3:
            continue
        # 闭合：以首点为起点，逐段 Catmull-Rom
        p0 = pts[-1]
        cmds.append("M%.2f,%.2f" % (pts[0][0], pts[0][1]))
        for i in range(n):
            p1 = pts[i]
            p2 = pts[(i + 1) % n]
            pm1 = pts[i - 1] if i > 0 else p0
            p3 = pts[(i + 2) % n]
            c1 = (p1[0] + (p2[0] - pm1[0]) / 6.0, p1[1] + (p2[1] - pm1[1]) / 6.0)
            c2 = (p2[0] - (p3[0] - p1[0]) / 6.0, p2[1] - (p3[1] - p1[1]) / 6.0)
            cmds.append("C%.2f,%.2f %.2f,%.2f %.2f,%.2f" % (
                c1[0], c1[1], c2[0], c2[1], p2[0], p2[1]))
        cmds.append("Z")
    return "".join(cmds)


def flatten(loops, samples=CURVE_SAMPLES):
    """把 Catmull-Rom 的环细分成多边形，用于回填验证。"""
    polys = []
    for pts in loops:
        n = len(pts)
        poly = []
        for i in range(n):
            p1 = pts[i]
            p2 = pts[(i + 1) % n]
            pm1 = pts[i - 1] if i > 0 else pts[-1]
            p3 = pts[(i + 2) % n]
            c1 = (p1[0] + (p2[0] - pm1[0]) / 6.0, p1[1] + (p2[1] - pm1[1]) / 6.0)
            c2 = (p2[0] - (p3[0] - p1[0]) / 6.0, p2[1] - (p3[1] - p1[1]) / 6.0)
            for s in range(samples):
                t = s / float(samples)
                mt = 1 - t
                x = (mt ** 3 * p1[0] + 3 * mt * mt * t * c1[0] +
                     3 * mt * t * t * c2[0] + t ** 3 * p2[0])
                y = (mt ** 3 * p1[1] + 3 * mt * mt * t * c1[1] +
                     3 * mt * t * t * c2[1] + t ** 3 * p2[1])
                poly.append((x, y))
        polys.append(poly)
    return polys


def main():
    loops, (W, H) = trace_contours(SRC)
    print("环数 %d，原始点数 %d" % (len(loops), sum(len(l) for l in loops)))

    loops = [rdp(l, RDP_TOL) for l in loops]
    loops = [l for l in loops if len(l) >= 3]
    print("抽稀后 %d 点" % sum(len(l) for l in loops))

    # 按墨迹包围盒缩放到 viewport，等比、居中
    xs = [p[0] for l in loops for p in l]
    ys = [p[1] for l in loops for p in l]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    span = max(x1 - x0, y1 - y0)
    scale = (VIEWPORT - MARGIN * 2) / span
    ox = (VIEWPORT - (x1 - x0) * scale) / 2.0 - x0 * scale
    oy = (VIEWPORT - (y1 - y0) * scale) / 2.0 - y0 * scale

    scaled = [[(p[0] * scale + ox, p[1] * scale + oy) for p in l] for l in loops]
    path = to_path(scaled)

    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!--\n'
        '    乌鸦 + Wi-Fi + 打印机 的 logo（单色矢量）。\n\n'
        '    由 tools/mklogo.py 从 tools/logo/logo-src.jpg 自动描线生成，不要手改\n'
        '    pathData —— 换图请改脚本的源文件后重跑。\n\n'
        '    fillType 必须是 evenOdd：路径里有多个闭环（身体、羽缝、眼睛、纸上的横线），\n'
        '    绕向不统一时 nonZero 会把镂空填死。\n\n'
        '    填充色用 @color/raven_ink 而不是写死黑色：启动页在深色模式下要白乌鸦，\n'
        '    靠 values-night 覆盖同一个颜色名即可，不用第二份 pathData。\n\n'
        '    values/logo_path.xml 里的字符串是同一份 pathData，给打印用 —— 原因见\n'
        '    tools/mklogo.py 顶部注释（VectorDrawable 带 tint 时会栅格化成位图）。\n'
        '-->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="24"\n'
        '    android:viewportHeight="24">\n'
        '    <path\n'
        '        android:fillColor="@color/raven_ink"\n'
        '        android:fillType="evenOdd"\n'
        '        android:pathData="%s" />\n'
        '</vector>\n' % path
    )
    os.makedirs(os.path.dirname(OUT_XML), exist_ok=True)
    with open(OUT_XML, "w", encoding="utf-8", newline="\n") as f:
        f.write(xml)

    # 同一份 pathData 的字符串版：打印时用 PathParser 还原成 Path 直接画。
    # 只有 M / C / Z 三种绝对命令，PathParser 解析没有歧义。
    string_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!--\n'
        '    由 tools/mklogo.py 生成，和 drawable/ic_logo.xml 同源，不要手改。\n'
        '    测试页把这段 pathData 用 PathParser 还原成 Path 再画，才是真矢量；\n'
        '    直接 draw VectorDrawable 会被它自己的 bitmap 缓存栅格化（30x30 像素）。\n'
        '-->\n'
        '<resources>\n'
        '    <string name="logo_path" translatable="false">%s</string>\n'
        '</resources>\n' % path
    )
    with open(OUT_PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(string_xml)
    print("写出 %s / %s（%.1f KB pathData）" % (OUT_XML, OUT_PATH, len(path) / 1024.0))

    # ── 回填验证：用 evenOdd（XOR 合成）重新栅格化，与原图叠色比对 ──────────
    polys = flatten(loops)
    mask = Image.new("1", (W, H), 0)
    for poly in polys:
        layer = Image.new("1", (W, H), 0)
        ImageDraw.Draw(layer).polygon([(p[0], p[1]) for p in poly], fill=1)
        mask = ImageChops.logical_xor(mask, layer)

    orig = Image.open(SRC).convert("L").point(lambda v: 255 if v < THRESHOLD else 0).convert("1")
    both = ImageChops.logical_and(orig, mask).convert("L").point(lambda v: 255 if v else 0)
    only_orig = ImageChops.logical_and(orig, ImageChops.invert(mask)).convert("L")
    only_vec = ImageChops.logical_and(mask, ImageChops.invert(orig)).convert("L")

    inter = sum(both.point(lambda v: 1 if v else 0).getdata())
    a = sum(orig.convert("L").point(lambda v: 1 if v else 0).getdata())
    b = sum(mask.convert("L").point(lambda v: 1 if v else 0).getdata())
    union = a + b - inter
    print("重合度 IoU = %.4f（原图 %d px / 矢量 %d px）" % (inter / union, a, b))

    prev = Image.new("RGB", (W, H), "white")
    prev.paste((255, 170, 170), mask=only_orig.convert("L"))
    prev.paste((120, 140, 255), mask=only_vec.convert("L"))
    prev.paste((0, 0, 0), mask=both)
    prev.save(OUT_PREVIEW)
    print("预览 %s" % OUT_PREVIEW)


if __name__ == "__main__":
    main()
