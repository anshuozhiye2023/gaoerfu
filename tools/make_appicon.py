# -*- coding: utf-8 -*-
"""生成 iOS App 图标（1024×1024 PNG）。

为什么用代码画而不是设计稿：
  App Store Connect 上传时**强制**要求 AppIcon，缺了会报 ITMS-90022/90023，
  而编译阶段完全不报错——所以这张图标是「能把包传上去」的最小必要条件。
  这是可直接用的占位图标；有正式品牌稿时替换同名 png 即可，其余不用动。

设计要点（都是为小尺寸可读性服务的）：
  · iOS 会自动切圆角，所以资源必须是**满幅正方形、不带透明、不带圆角**。
  · 60px 下能看清的只有「一个大白圆 + 一圈白线 + 几个点」，所以构图只留这三样。
  · 球体、光环、待收小球三者**半径必须错开**。初版把小球画在球体半径以内，
    白底白点叠在一起直接看不见，整张图只剩一个波点大圆——务必保持外置。
  · 超采样 4 倍再 LANCZOS 缩回，边缘自然平滑，不用手写抗锯齿。

用法：
  python tools/make_appicon.py
  # 默认写到 ios/GolfApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

SIZE = 1024          # 最终边长
SS = 4               # 超采样倍数
S = SIZE * SS

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "ios", "GolfApp", "Assets.xcassets", "AppIcon.appiconset",
)

# 调色板
BG_DARK = (9, 84, 56)
BG_LIGHT = (34, 192, 137)
WHITE = (255, 255, 255)
DIMPLE = (206, 226, 217)

BALL_R = 0.235       # 主球半径 / 画布边长
RING_R = 0.318       # 光环半径
RING_W = 0.024       # 光环线宽


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def draw_bg(img):
    """对角线渐变 + 左上柔光。"""
    d = ImageDraw.Draw(img)
    for y in range(S):
        t = y / (S - 1)
        d.line([(0, y), (S, y)], fill=lerp(BG_DARK, BG_LIGHT, t * 0.85))

    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = S * 0.26, S * 0.20
    for i in range(60, 0, -1):
        t = i / 60.0
        r = S * 0.60 * t
        gd.ellipse([cx - r, cy - r, cx + r, cy + r],
                   fill=WHITE + (int(44 * (1 - t) ** 1.6),))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.06))
    img.alpha_composite(glow)


def draw_ball(img, cx, cy, r):
    """主球：白底 + 细密凹陷。阴影只留一点，重了会显脏。"""
    sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    sd.ellipse([cx - r * 1.04, cy - r * 0.98, cx + r * 1.08, cy + r * 1.12],
               fill=(0, 38, 26, 74))
    sh = sh.filter(ImageFilter.GaussianBlur(r * 0.18))
    img.alpha_composite(sh)

    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.ellipse([cx - r, cy - r, cx + r, cy + r], fill=WHITE + (255,))

    step = r * 0.255
    dim_r = r * 0.052
    rows = int(r / step) + 2
    for row in range(-rows, rows + 1):
        py = cy + row * step
        off = (step / 2) if (row % 2) else 0.0
        for col in range(-rows, rows + 1):
            px = cx + col * step + off
            if math.hypot(px - cx, py - cy) <= r - dim_r * 2.1:
                ld.ellipse([px - dim_r, py - dim_r, px + dim_r, py + dim_r],
                           fill=DIMPLE + (255,))
    img.alpha_composite(layer)


def draw_ring(img, cx, cy, r):
    """收束光环：留一段缺口，避免看起来像靶心。

    不要在弧的两端补圆帽 —— PIL 的 arc 描边是往包围盒内侧铺开的，用
    三角函数算出的端点会落在弧线外侧，补出来是两个凸起的小疙瘩，像渲染坏了。
    平头端点在图标尺寸下完全看不出，直接留平头。
    """
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.arc([cx - r, cy - r, cx + r, cy + r],
           start=-28, end=262,           # 缺口留在右上
           fill=WHITE + (205,), width=int(S * RING_W))
    img.alpha_composite(layer)


def draw_balls(img, cx, cy, r):
    """光环上的待收小球。半径必须小于 r*0.30 才能和主球拉开层次。"""
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for ang, size in ((96.0, 0.052), (176.0, 0.044), (240.0, 0.038)):
        a = math.radians(ang)
        px = cx + math.cos(a) * r
        py = cy + math.sin(a) * r
        rr = S * size
        ld.ellipse([px - rr, py - rr, px + rr, py + rr], fill=WHITE + (255,))
    img.alpha_composite(layer)


def main():
    img = Image.new("RGBA", (S, S), BG_DARK + (255,))
    draw_bg(img)

    cx, cy = S * 0.50, S * 0.505
    r = S * BALL_R

    draw_ball(img, cx, cy, r)
    draw_ring(img, cx, cy, S * RING_R)
    draw_balls(img, cx, cy, S * RING_R)

    out = img.convert("RGB").resize((SIZE, SIZE), Image.LANCZOS)

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "icon-1024.png")
    out.save(path, "PNG", optimize=True)
    print("wrote", path, os.path.getsize(path), "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
