#!/usr/bin/env python3
"""Rasterise the AkaTerminal launcher art (twilight: a prompt and the akane sun on the horizon).

The vector drawables in app/src/main/res/drawable/ic_launcher_{background,foreground}.xml are
the source of truth; this script draws the same geometry with Pillow for the places that need
bitmaps (legacy mipmaps, the store icon). Run from the repo root:

    python3 scripts/render_launcher_icon.py

Geometry is in the adaptive-icon viewport (108 x 108 units, the visible mask is the centre 72).
"""
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"

SUMI_TOP = (0x07, 0x0A, 0x16)
KONJO = (0x1B, 0x28, 0x52)
KACHI = (0x0B, 0x0F, 0x1E)
AKANE = (0xD2, 0x38, 0x3F)
SHU = (0xEE, 0x6A, 0x2E)
SHIRONERI = (0xEE, 0xF0, 0xF5)

# Shapes (keep in sync with the vector drawables).
CHEVRON = [(35, 42), (46, 52), (35, 62)]
CHEVRON_W = 6.5
SUN_C, SUN_R = (64, 68), 13
LINE_Y, LINE_H, LINE_X0, LINE_X1 = 67, 3, 28, 84
GLOW_C, GLOW_R = (64, 68), 36


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def stops_at(stops, t):
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            return lerp(c0, c1, 0 if t1 == t0 else (t - t0) / (t1 - t0))
    return stops[-1][1]


def background(size):
    k = size / 108
    img = Image.new("RGB", (size, size))
    px = img.load()
    stops = [(0.0, SUMI_TOP), (0.62, KONJO), (1.0, KACHI)]
    rows = [stops_at(stops, y / (size - 1)) for y in range(size)]
    gx, gy, gr = GLOW_C[0] * k, GLOW_C[1] * k, GLOW_R * k
    for y in range(size):
        r0 = rows[y]
        for x in range(size):
            d = math.hypot(x - gx, y - gy) / gr
            a = max(0.0, 1.0 - d) ** 2 * 0.45
            px[x, y] = lerp(r0, AKANE, a)
    return img


def thick_polyline(points, w):
    """Outline of a polyline with square caps and mitred joins."""
    h = w / 2
    def norm(p, q):
        dx, dy = q[0] - p[0], q[1] - p[1]
        l = math.hypot(dx, dy)
        return dx / l, dy / l
    left, right = [], []
    n = len(points)
    for i, p in enumerate(points):
        if i == 0:
            d = norm(p, points[1]); p = (p[0] - d[0] * h, p[1] - d[1] * h)
            left.append((p[0] - d[1] * h, p[1] + d[0] * h)); right.append((p[0] + d[1] * h, p[1] - d[0] * h))
        elif i == n - 1:
            d = norm(points[i - 1], p); p = (p[0] + d[0] * h, p[1] + d[1] * h)
            left.append((p[0] - d[1] * h, p[1] + d[0] * h)); right.append((p[0] + d[1] * h, p[1] - d[0] * h))
        else:
            d0, d1 = norm(points[i - 1], p), norm(p, points[i + 1])
            bx, by = d0[0] + d1[0], d0[1] + d1[1]
            bl = math.hypot(bx, by); bx, by = bx / bl, by / bl
            cos = bx * d0[0] + by * d0[1]
            m = h / cos
            left.append((p[0] - by * m, p[1] + bx * m)); right.append((p[0] + by * m, p[1] - bx * m))
    return left + right[::-1]


def foreground(size, mono=False):
    k = size / 108
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # Sun: top half disc, shu at the top fading to akane at the horizon.
    sun = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sp = sun.load()
    top, bottom = (SUN_C[1] - SUN_R) * k, SUN_C[1] * k
    for y in range(int(top), int(bottom) + 1):
        c = SHIRONERI if mono else lerp(SHU, AKANE, (y - top) / max(1, bottom - top))
        for x in range(int((SUN_C[0] - SUN_R) * k), int((SUN_C[0] + SUN_R) * k) + 1):
            sp[x, y] = c + (255,)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).pieslice(
        [(SUN_C[0] - SUN_R) * k, (SUN_C[1] - SUN_R) * k, (SUN_C[0] + SUN_R) * k, (SUN_C[1] + SUN_R) * k],
        180, 360, fill=255,
    )
    sun.putalpha(ImageChops.multiply(sun.getchannel("A"), mask))
    img.alpha_composite(sun)
    # Horizon: akane at the ends fading out, shu at the brightest point.
    line = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    lp = line.load()
    x0, x1 = LINE_X0 * k, LINE_X1 * k
    for x in range(int(x0), int(x1) + 1):
        t = (x - x0) / (x1 - x0)
        edge = min(1.0, min(t, 1 - t) / 0.18)
        c = SHIRONERI if mono else stops_at([(0, AKANE), (0.64, SHU), (1, AKANE)], t)
        a = 255 if mono else round(255 * edge)
        for y in range(round(LINE_Y * k), round((LINE_Y + LINE_H) * k)):
            lp[x, y] = c + (a,)
    img.alpha_composite(line)
    # Prompt chevron.
    d = ImageDraw.Draw(img)
    d.polygon([(x * k, y * k) for x, y in thick_polyline(CHEVRON, CHEVRON_W)], fill=SHIRONERI + (255,))
    return img


def full_icon(size, ss=4):
    big = size * ss
    canvas = background(big).convert("RGBA")
    canvas.alpha_composite(foreground(big))
    # Legacy icon: the adaptive mask's visible centre (72 of 108 units) in a rounded square.
    k = big / 108
    crop = canvas.crop((int(18 * k), int(18 * k), int(90 * k), int(90 * k))).resize((big, big), Image.LANCZOS)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=big * 0.22, fill=255)
    crop.putalpha(mask)
    return crop.resize((size, size), Image.LANCZOS)


def main():
    for folder, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        full_icon(px).save(RES / f"mipmap-{folder}/ic_launcher.png", optimize=True)
    full_icon(512).convert("RGB").save(ROOT / "fastlane/metadata/android/en-US/images/icon.png", optimize=True)


if __name__ == "__main__":
    main()
