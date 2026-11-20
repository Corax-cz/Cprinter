"""
从 tools/logo/logo-src.jpg 生成 App 图标（自适应图标前景 + 传统位图）。

为什么要重做图标：原来那套是深色底（#171C26）配白色剪影，用户换新 logo 后
桌面图标还是黑的，看着跟 logo 不是同一个东西。新图本身就是「白底 + 黑乌鸦 +
深浅渐变 Wi-Fi」，所以这里**只做抠底，不调色** —— 保留原图的灰度层次。

抠底的做法是「白底黑图的精确 alpha 分解」：
    alpha = (232 - L) / (232 - 70)
L 是灰度，232 以上判为纸白（压掉 JPEG 噪点），70 以下判为实墨。中间线性过渡，
于是 Wi-Fi 弧线的灰色渐变会变成半透明的黑，叠在白底上跟原图一模一样。
不能直接二值化：弧线尾巴会断成锯齿。

产物：
    mipmap-*/ic_launcher_foreground.png   108dp 画布，图形占 62dp（自适应安全区）
    mipmap-*/ic_launcher.png              48dp 方图（旧启动器兜底）
    mipmap-*/ic_launcher_round.png        48dp 圆图

自适应图标的背景色在 values/colors.xml 的 ic_launcher_background，这里是白色。

用法： python tools/mkicon.py
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "tools/logo/logo-src.jpg")
RES = os.path.join(ROOT, "app/src/main/res")

WHITE_L = 232      # 灰度 >= 它算纸白（原图底是 254，JPEG 会在边上抖出 230 左右的脏点）
BLACK_L = 70       # 灰度 <= 它算实墨
SUPERSAMPLE = 3    # 圆角/圆形先按 3 倍画再缩，省得手工抗锯齿

# 自适应图标：108dp 画布，系统遮罩裁到中心 72dp，图形再留一点余量
ADAPTIVE_CANVAS = 108.0
ADAPTIVE_CONTENT = 67.0
# 传统图标（只给 Android 8 以下兜底，本项目 minSdk 29 实际用不到，但保持资源完整）
LEGACY_CANVAS = 48.0
LEGACY_CONTENT = 41.0
ROUND_CONTENT = 34.0


def ink_alpha(im):
    """灰度 → 0..255 的墨量蒙版。"""
    l = im.convert("L")

    def f(v):
        a = (WHITE_L - v) * 255.0 / (WHITE_L - BLACK_L)
        return 0 if a < 0 else (255 if a > 255 else int(a))

    return l.point(f)


def fit(alpha, box_px):
    """等比缩放到 box_px x box_px 内，返回 RGBA 黑色图。"""
    w, h = alpha.size
    s = min(box_px / w, box_px / h)
    nw, nh = max(1, round(w * s)), max(1, round(h * s))
    a = alpha.resize((nw, nh), Image.LANCZOS)
    im = Image.new("RGBA", (nw, nh), (0, 0, 0, 0))
    im.putalpha(a)
    return im


def white_square(size):
    """(画布, 边长) —— 白底方图，按 SUPERSAMPLE 放大，缩小时自然抗锯齿。"""
    big = size * SUPERSAMPLE
    canvas = Image.new("RGBA", (big, big), (255, 255, 255, 255))
    return canvas, big


def main():
    src = Image.open(SRC)
    alpha = ink_alpha(src)
    bbox = alpha.getbbox()
    if not bbox:
        raise SystemExit("图里找不到墨迹，检查源图")
    alpha = alpha.crop(bbox)
    print("源图 %s，墨迹包围盒 %s" % (src.size, bbox))

    dpi_scale = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

    for name, k in dpi_scale.items():
        out_dir = os.path.join(RES, "mipmap-" + name)
        os.makedirs(out_dir, exist_ok=True)

        # ── 自适应图标前景：透明画布，图形居中，占 62/108 ────────────────────
        size = round(ADAPTIVE_CANVAS * k)
        content = round(ADAPTIVE_CONTENT * k)
        fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        g = fit(alpha, content)
        fg.alpha_composite(g, ((size - g.width) // 2, (size - g.height) // 2))
        fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"))

        # ── 传统方图 ────────────────────────────────────────────────────────
        size = round(LEGACY_CANVAS * k)
        canvas, big = white_square(size)
        g = fit(alpha, round(LEGACY_CONTENT * k * SUPERSAMPLE))
        canvas.alpha_composite(g, ((big - g.width) // 2, (big - g.height) // 2))
        canvas.resize((size, size), Image.LANCZOS).save(
            os.path.join(out_dir, "ic_launcher.png")
        )

        # ── 传统圆图 ────────────────────────────────────────────────────────
        big = size * SUPERSAMPLE
        canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        ImageDraw.Draw(canvas).ellipse([0, 0, big - 1, big - 1], fill=(255, 255, 255, 255))
        g = fit(alpha, round(ROUND_CONTENT * k * SUPERSAMPLE))
        canvas.alpha_composite(g, ((big - g.width) // 2, (big - g.height) // 2))
        canvas.resize((size, size), Image.LANCZOS).save(
            os.path.join(out_dir, "ic_launcher_round.png")
        )

        print("  mipmap-%-8s 前景 %dpx 方图 %dpx" % (name, round(ADAPTIVE_CANVAS * k), size))

    # 预览：模拟桌面上的圆形遮罩效果，方便肉眼验收
    prev = os.path.join(ROOT, "shot/icon_preview.png")
    s = 216
    fgc = Image.new("RGBA", (s, s), (255, 255, 255, 255))
    fg = Image.open(os.path.join(RES, "mipmap-xxxhdpi/ic_launcher_foreground.png"))
    fg = fg.resize((s, s), Image.LANCZOS)
    fgc.alpha_composite(fg, (0, 0))
    mask = Image.new("L", (s * 4, s * 4), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, s * 4 - 1, s * 4 - 1], fill=255)
    mask = mask.resize((s, s), Image.LANCZOS)
    out = Image.new("RGBA", (s, s), (255, 255, 255, 255))
    out.paste(fgc, (0, 0), mask)
    out.save(prev)
    print("预览写出 %s" % prev)


if __name__ == "__main__":
    main()
