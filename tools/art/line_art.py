"""Monoline artwork for Voice Alarm.

One set of primitives (circles, lines, arcs, polylines, dots) is emitted both
as Android vector drawables (the in-app art and the adaptive launcher icon)
and rendered with Pillow for the README images, so they always match.

    python3 tools/art/line_art.py
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")
WHITE = (242, 243, 245)
RED = (255, 93, 108)
BG = (5, 6, 8)


def pt(cx, cy, r, deg):
    """Point on a circle; degrees clockwise from 12 o'clock."""
    a = math.radians(deg)
    return cx + r * math.sin(a), cy - r * math.cos(a)


# ---- primitives: (kind, points, width, color, alpha) ---------------------------------

def circle(cx, cy, r, w, color=WHITE, alpha=1.0):
    return ("poly", [pt(cx, cy, r, d) for d in range(0, 361, 4)], w, color, alpha)


def arc(cx, cy, r, a0, a1, w, color=WHITE, alpha=1.0):
    n = max(4, int(abs(a1 - a0) / 3))
    return ("poly", [pt(cx, cy, r, a0 + (a1 - a0) * i / n) for i in range(n + 1)], w, color, alpha)


def line(x0, y0, x1, y1, w, color=WHITE, alpha=1.0):
    return ("poly", [(x0, y0), (x1, y1)], w, color, alpha)


def hand(cx, cy, deg, length, w, tail=0.0, color=WHITE, alpha=1.0):
    x0, y0 = pt(cx, cy, -tail, deg)
    x1, y1 = pt(cx, cy, length, deg)
    return line(x0, y0, x1, y1, w, color, alpha)


def dot(cx, cy, r, color=WHITE, alpha=1.0):
    return ("dot", [(cx, cy)], r, color, alpha)


def rounded_rect(x0, y0, x1, y1, r, w, color=WHITE, alpha=1.0):
    pts = []
    for (cx, cy, a0) in ((x1 - r, y0 + r, 0), (x1 - r, y1 - r, 90), (x0 + r, y1 - r, 180), (x0 + r, y0 + r, 270)):
        pts += [pt(cx, cy, r, a0 + d) for d in range(0, 91, 10)]
    pts.append(pts[0])
    return ("poly", pts, w, color, alpha)


def crescent(cx, cy, r, ox, oy, r2, w, color=WHITE, alpha=1.0):
    """Outline of circle (cx, cy, r) minus circle (cx+ox, cy+oy, r2)."""
    def inside2(p):
        return math.hypot(p[0] - cx - ox, p[1] - cy - oy) < r2
    outer = [pt(cx, cy, r, d / 2) for d in range(0, 720)]
    start = next(i for i in range(len(outer)) if inside2(outer[i - 1]) and not inside2(outer[i]))
    outer = outer[start:] + outer[:start]
    outer = [p for p in outer if not inside2(p)]
    inner = [pt(cx + ox, cy + oy, r2, d / 2) for d in range(0, 720)]
    inner = [p for p in inner if math.hypot(p[0] - cx, p[1] - cy) < r]
    # Walk the inner arc from the end of the outer arc back to its start.
    e = outer[-1]
    k = min(range(len(inner)), key=lambda i: math.hypot(inner[i][0] - e[0], inner[i][1] - e[1]))
    inner = inner[k:] + inner[:k]
    if math.hypot(inner[-1][0] - outer[0][0], inner[-1][1] - outer[0][1]) > math.hypot(inner[0][0] - outer[0][0], inner[0][1] - outer[0][1]):
        inner = inner[::-1]
    pts = outer + inner[::3] + [outer[0]]
    return ("poly", pts, w, color, alpha)


def sparkle(cx, cy, s, w, alpha=1.0):
    return [line(cx - s, cy, cx + s, cy, w, alpha=alpha), line(cx, cy - s, cx, cy + s, w, alpha=alpha)]


# ---- scenes (viewport units) ---------------------------------------------------------

def clock_scene(cx, cy, r, w, voice=True):
    s = [
        circle(cx, cy, r, w),
        hand(cx, cy, -60, r * 0.5, w * 1.15),
        hand(cx, cy, 60, r * 0.76, w * 0.85),
        hand(cx, cy, 200, r * 0.84, w * 0.5, tail=r * 0.18, color=RED),
        dot(cx, cy, w * 1.25),
        dot(cx, cy, w * 0.55, color=BG),
    ]
    for q in (0, 90, 180, 270):
        x0, y0 = pt(cx, cy, r * 0.86, q)
        x1, y1 = pt(cx, cy, r * 0.93, q)
        s.append(line(x0, y0, x1, y1, w * 0.6, alpha=0.45))
    if voice:
        s += [arc(cx, cy, r * 1.22, 26, 58, w * 0.85, alpha=0.75), arc(cx, cy, r * 1.42, 22, 62, w * 0.85, alpha=0.4)]
    return s


SCENES = {
    "art_clock": (120, clock_scene(60, 64, 38, 2.2)),
    "art_moon": (120, [
        crescent(56, 64, 34, 17, -13, 30, 2.2),
        *sparkle(92, 30, 6, 1.6),
        dot(24, 34, 1.4, alpha=0.8), dot(98, 74, 1.1, alpha=0.6), dot(76, 18, 1.0, alpha=0.5), dot(30, 100, 1.0, alpha=0.4),
    ]),
    "art_voice": (120, [
        rounded_rect(48, 20, 72, 62, 12, 2.2),
        arc(60, 52, 22, 90, 270, 2.2),
        line(60, 74, 60, 90, 2.2), line(49, 90, 71, 90, 2.2),
        arc(60, 41, 32, 238, 302, 1.8, alpha=0.7), arc(60, 41, 40, 232, 308, 1.8, alpha=0.35),
        arc(60, 41, 32, 58, 122, 1.8, alpha=0.7), arc(60, 41, 40, 52, 128, 1.8, alpha=0.35),
    ]),
    # Adaptive icon foreground: keep everything inside the 66dp safe circle.
    "art_ic_launcher_foreground": (108, clock_scene(52, 56, 20, 2.0)),
}


def hexa(color, alpha=1.0):
    return "#%02X%02X%02X%02X" % (round(alpha * 255), *color)


def to_vector(size, prims, mono=False):
    out = []
    for kind, pts, w, color, alpha in prims:
        c = WHITE if mono else color
        if mono and color == BG:
            continue
        if kind == "dot":
            (x, y), r = pts[0], w
            d = f"M{x - r:.2f},{y:.2f}a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0Z"
            out.append(f'    <path android:fillColor="{hexa(c, alpha)}" android:pathData="{d}" />')
        else:
            d = "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts)
            out.append(f'    <path android:strokeColor="{hexa(c, alpha)}" android:strokeWidth="{w:g}" '
                       f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />')
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/art/line_art.py -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp" android:height="{size}dp"\n'
            f'    android:viewportWidth="{size}" android:viewportHeight="{size}">\n' + "\n".join(out) + "\n</vector>\n")


def render(img, prims, scale, ox=0.0, oy=0.0):
    """Draw primitives onto an RGBA image (already supersampled)."""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for kind, pts, w, color, alpha in prims:
        fill = (*color, round(alpha * 255))
        if kind == "dot":
            (x, y), r = pts[0], w * scale
            x, y = ox + x * scale, oy + y * scale
            d.ellipse([x - r, y - r, x + r, y + r], fill=fill)
            continue
        p = [(ox + x * scale, oy + y * scale) for x, y in pts]
        lw = max(1, round(w * scale))
        d.line(p, fill=fill, width=lw, joint="curve")
        for x, y in (p[0], p[-1]):
            d.ellipse([x - lw / 2, y - lw / 2, x + lw / 2, y + lw / 2], fill=fill)
    img.alpha_composite(layer)


def night_sky(w, h, seed=7, ss=1):
    img = Image.new("RGBA", (w, h), (*BG, 255))
    top = Image.linear_gradient("L").resize((w, h)).point(lambda v: 255 - v)
    lift = Image.new("RGBA", (w, h), (11, 13, 18, 255))
    img = Image.composite(lift, img, top.point(lambda v: int(v * 0.8)))
    d = ImageDraw.Draw(img)
    rnd = random.Random(seed)
    for _ in range(int(w * h / (ss * ss * 3200))):
        x, y = rnd.random() * w, rnd.random() * rnd.random() * h
        r = (0.5 + rnd.random() * rnd.random() * 1.6) * w / 1024
        a = int(255 * (0.1 + rnd.random() * rnd.random() * 0.5))
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, a))
    return img


def main():
    for name, (size, prims) in SCENES.items():
        with open(os.path.join(RES, "drawable", name + ".xml"), "w") as f:
            f.write(to_vector(size, prims))
    with open(os.path.join(RES, "drawable", "art_ic_launcher_monochrome.xml"), "w") as f:
        f.write(to_vector(108, SCENES["art_ic_launcher_foreground"][1], mono=True))

    docs = os.path.join(ROOT, "docs", "art")
    os.makedirs(docs, exist_ok=True)
    ss = 4
    # Store icon (512px): full-bleed night sky + the launcher clock.
    n = 512 * ss
    icon = night_sky(n, n, seed=3, ss=ss)
    render(icon, SCENES["art_ic_launcher_foreground"][1], n / 108)
    icon.resize((512, 512), Image.LANCZOS).convert("RGB").save(os.path.join(docs, "icon_512.png"))
    # Feature graphic (1024x500).
    W, H = 1024 * ss, 500 * ss
    fg = night_sky(W, H, ss=ss)
    render(fg, clock_scene(60, 64, 38, 2.2), 3.6 * ss, ox=40 * ss, oy=20 * ss)
    d = ImageDraw.Draw(fg)
    font_dir = os.path.join(RES, "font")
    title = ImageFont.truetype(os.path.join(font_dir, "manrope_extralight.ttf"), 92 * ss)
    sub = ImageFont.truetype(os.path.join(font_dir, "manrope_regular.ttf"), 30 * ss)
    d.text((500 * ss, 170 * ss), "Voice Alarm", font=title, fill=(*WHITE, 255))
    d.text((504 * ss, 290 * ss), "Wakes you with your own words.", font=sub, fill=(154, 157, 166, 255))
    fg.resize((1024, 500), Image.LANCZOS).convert("RGB").save(os.path.join(docs, "feature_graphic.png"))


if __name__ == "__main__":
    main()
