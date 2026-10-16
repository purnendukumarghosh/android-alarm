#!/usr/bin/env python3
"""
Procedural 3D artwork for Voice Alarm ("Neon Night").

Everything is rendered per pixel from simple 3D primitives (spheres, tori,
capsules, ellipsoids) with a key light from the top-left, Blinn-Phong
specular, a fake environment reflection for chrome, Fresnel rim light, soft
contact shadows and additive neon bloom. Rendered at 4x and downsampled for
anti-aliasing. Deterministic: re-running produces identical files.

Usage:  python3 tools/art/render_art.py            (from the repo root)
Needs:  numpy, Pillow
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
FONT_XB = os.path.join(RES, "font", "manrope_extrabold.ttf")
FONT_MD = os.path.join(RES, "font", "manrope_medium.ttf")
SS = 4  # supersampling factor


def hexc(h):
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (1, 3, 5)], np.float32)


CYAN, GREEN, BLUE = hexc("#00E5FF"), hexc("#2BFF88"), hexc("#3D7BFF")
VIOLET, RED, MAGENTA = hexc("#8A5CFF"), hexc("#FF2E63"), hexc("#FF3CAC")
NAVY, DEEP, WHITE = hexc("#0A0F24"), hexc("#05070F"), np.ones(3, np.float32)


def norm(v):
    v = np.asarray(v, np.float32)
    return v / np.linalg.norm(v)


LIGHT = norm([-0.55, -0.8, 0.95])     # x right, y down, z towards the viewer
HALF = norm(LIGHT + np.array([0, 0, 1], np.float32))


def mix(a, b, t):
    t = np.asarray(t, np.float32)
    if t.ndim:
        t = t[..., None]
    return a * (1 - t) + b * t


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


# ---------------------------------------------------------------- blur helpers

def _box(a, r, axis):
    if r < 1:
        return a
    pad = [(0, 0)] * a.ndim
    pad[axis] = (r + 1, r)
    c = np.cumsum(np.pad(a, pad), axis=axis, dtype=np.float32)
    n = a.shape[axis]
    hi = np.take(c, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis)
    lo = np.take(c, np.arange(0, n), axis=axis)
    return (hi - lo) / (2 * r + 1)


def blur(a, sigma):
    """Gaussian blur approximated by three box passes per axis (zero padded)."""
    if sigma <= 0.5:
        return a
    w = math.sqrt(12 * sigma * sigma / 3 + 1)
    r = max(1, int(w / 2))
    for _ in range(3):
        a = _box(a, r, 0)
        a = _box(a, r, 1)
    return a


# ---------------------------------------------------------------- scene

class Scene:
    def __init__(self, size, units_px, center=(0.0, 0.0)):
        self.n = size * SS
        self.size = size
        self.s = units_px * SS                   # pixels per scene unit
        self.cx = self.n / 2 - center[0] * self.s
        self.cy = self.n / 2 - center[1] * self.s
        self.P = np.zeros((self.n, self.n, 3), np.float32)   # premultiplied color
        self.A = np.zeros((self.n, self.n), np.float32)      # alpha
        self.E = np.zeros((self.n, self.n, 3), np.float32)   # emission for bloom

    # bounding box in pixels for a unit-space rectangle
    def box(self, x0, y0, x1, y1):
        px0 = max(0, int(self.cx + x0 * self.s) - 2)
        px1 = min(self.n, int(self.cx + x1 * self.s) + 3)
        py0 = max(0, int(self.cy + y0 * self.s) - 2)
        py1 = min(self.n, int(self.cy + y1 * self.s) + 3)
        if px1 <= px0 or py1 <= py0:
            return None
        xs = (np.arange(px0, px1, dtype=np.float32) + 0.5 - self.cx) / self.s
        ys = (np.arange(py0, py1, dtype=np.float32) + 0.5 - self.cy) / self.s
        X, Y = np.meshgrid(xs, ys)
        return (slice(py0, py1), slice(px0, px1)), X, Y

    def over(self, sl, rgb, alpha):
        a = np.clip(alpha, 0, 1).astype(np.float32)
        self.P[sl] = rgb * a[..., None] + self.P[sl] * (1 - a[..., None])
        self.A[sl] = a + self.A[sl] * (1 - a)

    def emit(self, sl, rgb, amount):
        self.E[sl] += rgb * np.asarray(amount, np.float32)[..., None]

    def shadow(self, cx, cy, rx, ry, strength=0.6, softness=0.08, color=(0, 0, 0)):
        """Soft elliptical contact shadow (drawn over what is already there)."""
        b = self.box(cx - rx * 2, cy - ry * 2 - softness * 3, cx + rx * 2, cy + ry * 2 + softness * 3)
        if b is None:
            return
        sl, X, Y = b
        d = ((X - cx) / rx) ** 2 + ((Y - cy) / ry) ** 2
        m = (d <= 1).astype(np.float32)
        m = blur(m, softness * self.s)
        self.over(sl, np.array(color, np.float32), m * strength)

    def halo(self, cx, cy, r, color, strength):
        b = self.box(cx - r, cy - r, cx + r, cy + r)
        if b is None:
            return
        sl, X, Y = b
        d = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2) / r
        a = np.clip(1 - d, 0, 1) ** 2.2 * strength
        self.over(sl, color, a)

    def finish(self, bloom=((0.035, 0.9), (0.12, 0.55))):
        """Adds neon bloom and downsamples. Returns an RGBA PIL image."""
        if self.E.any():
            small = self.E.reshape(self.n // 4, 4, self.n // 4, 4, 3).mean(axis=(1, 3))
            glow = np.zeros_like(small)
            for sigma, k in bloom:
                glow += blur(small, sigma * self.s / 4) * k
            glow = np.repeat(np.repeat(glow, 4, axis=0), 4, axis=1)
            add = glow + self.E
            lum = np.clip(add.max(axis=2), 0, 1)
            self.P = self.P + add
            self.A = np.clip(self.A + lum * (1 - self.A), 0, 1)
        rgb = np.where(self.A[..., None] > 1e-4, self.P / np.maximum(self.A[..., None], 1e-4), 0)
        rgba = np.dstack([np.clip(rgb, 0, 1), self.A])
        img = Image.fromarray((rgba * 255 + 0.5).astype(np.uint8), "RGBA")
        # premultiply-aware downsample: work on premultiplied channels
        pre = np.dstack([np.clip(self.P, 0, None), self.A]).astype(np.float32)
        h = self.size
        pre = pre.reshape(h, SS, h, SS, 4).mean(axis=(1, 3))
        a = pre[..., 3:4]
        rgb = np.where(a > 1e-4, pre[..., :3] / np.maximum(a, 1e-4), 0)
        out = np.dstack([np.clip(rgb, 0, 1), np.clip(a, 0, 1)])
        return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA")


# ---------------------------------------------------------------- shading

def shade(N, base, ka=0.22, kd=0.85, ks=0.55, power=48, rim=None, rim_k=0.55, metal=0.0, env_tint=None):
    ndl = np.clip(np.einsum("...k,k->...", N, LIGHT), 0, 1)
    ndh = np.clip(np.einsum("...k,k->...", N, HALF), 0, 1)
    ndv = np.clip(N[..., 2], 0, 1)
    up = 0.5 - 0.5 * N[..., 1]
    base = np.broadcast_to(base, N.shape).astype(np.float32)
    col = base * (ka * (0.55 + 0.6 * up) + kd * ndl)[..., None]
    if metal > 0:
        # reflection vector's vertical component picks a sky/horizon/floor color
        ry = 2 * ndv * N[..., 1]
        tint = base if env_tint is None else np.broadcast_to(env_tint, N.shape)
        sky = mix(tint * 1.25, WHITE, 0.55)
        floor = tint * 0.12
        env = np.where((ry < 0)[..., None], mix(tint * 0.9, sky, np.clip(-ry * 1.6, 0, 1)), mix(tint * 0.9, floor, np.clip(ry * 1.6, 0, 1)))
        col = mix(col, env, metal)
    col = col + (ks * ndh ** power)[..., None]
    if rim is not None:
        col = col + np.asarray(rim, np.float32) * ((1 - ndv) ** 3 * rim_k)[..., None]
    return col


# ---------------------------------------------------------------- primitives
# Each returns (slice, coverage alpha, normals, extra) or None.

def sphere(sc, cx, cy, r):
    b = sc.box(cx - r, cy - r, cx + r, cy + r)
    if b is None:
        return None
    sl, X, Y = b
    dx, dy = (X - cx) / r, (Y - cy) / r
    d2 = dx * dx + dy * dy
    m = (d2 <= 1).astype(np.float32)
    z = np.sqrt(np.clip(1 - d2, 0, 1))
    N = np.dstack([dx, dy, z])
    return sl, m, N, np.sqrt(d2)


def ellipsoid(sc, cx, cy, rx, ry, rz, angle=0.0):
    rr = max(rx, ry)
    b = sc.box(cx - rr, cy - rr, cx + rr, cy + rr)
    if b is None:
        return None
    sl, X, Y = b
    ca, sa = math.cos(angle), math.sin(angle)
    u = (X - cx) * ca + (Y - cy) * sa
    v = -(X - cx) * sa + (Y - cy) * ca
    q = (u / rx) ** 2 + (v / ry) ** 2
    m = (q <= 1).astype(np.float32)
    z = rz * np.sqrt(np.clip(1 - q, 0, 1))
    nu, nv, nz = u / rx ** 2, v / ry ** 2, z / rz ** 2
    nx = nu * ca - nv * sa
    ny = nu * sa + nv * ca
    N = np.dstack([nx, ny, nz])
    N /= np.linalg.norm(N, axis=2, keepdims=True) + 1e-6
    return sl, m, N, (u, v, q)


def torus(sc, cx, cy, R, t, a0=None, a1=None):
    """Ring lying in the picture plane. Optional angle range in degrees (0 = right, clockwise)."""
    b = sc.box(cx - R - t, cy - R - t, cx + R + t, cy + R + t)
    if b is None:
        return None
    sl, X, Y = b
    dx, dy = X - cx, Y - cy
    d = np.sqrt(dx * dx + dy * dy) + 1e-6
    q = d - R
    m = (np.abs(q) <= t).astype(np.float32)
    ang = np.degrees(np.arctan2(dy, dx))
    if a0 is not None:
        span = (ang - a0) % 360
        m *= (span <= (a1 - a0) % 360).astype(np.float32)
    z = np.sqrt(np.clip(t * t - q * q, 0, None))
    N = np.dstack([q / t * dx / d, q / t * dy / d, z / t])
    return sl, m, N, ang


def capsule(sc, x0, y0, x1, y1, r):
    b = sc.box(min(x0, x1) - r, min(y0, y1) - r, max(x0, x1) + r, max(y0, y1) + r)
    if b is None:
        return None
    sl, X, Y = b
    vx, vy = x1 - x0, y1 - y0
    L2 = vx * vx + vy * vy
    t = np.clip(((X - x0) * vx + (Y - y0) * vy) / L2, 0, 1)
    px, py = X - (x0 + t * vx), Y - (y0 + t * vy)
    d2 = (px * px + py * py) / (r * r)
    m = (d2 <= 1).astype(np.float32)
    z = np.sqrt(np.clip(1 - d2, 0, 1))
    N = np.dstack([px / r, py / r, z])
    return sl, m, N, t


def disc(sc, cx, cy, r, dome=0.25):
    """Flat-ish disc with a shallow dome (glass face)."""
    b = sc.box(cx - r, cy - r, cx + r, cy + r)
    if b is None:
        return None
    sl, X, Y = b
    dx, dy = (X - cx) / r, (Y - cy) / r
    d2 = dx * dx + dy * dy
    m = (d2 <= 1).astype(np.float32)
    N = np.dstack([dx * dome, dy * dome, np.ones_like(dx)])
    N /= np.linalg.norm(N, axis=2, keepdims=True)
    return sl, m, N, np.sqrt(d2)


def put(sc, prim, rgb_fn, alpha=1.0, emit=None):
    if prim is None:
        return
    sl, m, N, extra = prim
    rgb = rgb_fn(N, extra)
    sc.over(sl, rgb, m * alpha)
    if emit is not None:
        color, amount = emit
        sc.emit(sl, color, m * amount)


# ---------------------------------------------------------------- the clock

def neon_sweep(ang):
    """Cyan → blue → violet → magenta → cyan around the ring (ang in degrees)."""
    stops = [(-180, MAGENTA), (-120, VIOLET), (-60, CYAN), (0, BLUE), (60, VIOLET), (120, MAGENTA), (180, MAGENTA)]
    out = np.zeros(ang.shape + (3,), np.float32)
    for (a0, c0), (a1, c1) in zip(stops, stops[1:]):
        sel = (ang >= a0) & (ang <= a1)
        t = (ang - a0) / (a1 - a0)
        out[sel] = mix(c0, c1, t)[sel]
    return out


def draw_clock(sc, cx=0.0, cy=0.0, k=1.0, detail=True, waves=True, hour=7, minute=0):
    R, t = 0.52 * k, 0.085 * k
    # shadow on the "floor"
    sc.shadow(cx, cy + 0.86 * k, 0.5 * k, 0.065 * k, strength=0.55, softness=0.05 * k)
    # legs
    for s in (-1, 1):
        put(sc, capsule(sc, cx + s * 0.30 * k, cy + 0.50 * k, cx + s * 0.42 * k, cy + 0.74 * k, 0.05 * k),
            lambda N, e: shade(N, hexc("#2A2F6B"), metal=0.6, env_tint=VIOLET, rim=CYAN, rim_k=0.4))
        put(sc, sphere(sc, cx + s * 0.425 * k, cy + 0.75 * k, 0.058 * k),
            lambda N, e: shade(N, hexc("#3A2F8A"), metal=0.7, env_tint=VIOLET, rim=MAGENTA))
    # hammer post + knob
    put(sc, capsule(sc, cx, cy - 0.55 * k, cx, cy - 0.72 * k, 0.035 * k),
        lambda N, e: shade(N, hexc("#3B3F86"), metal=0.75, env_tint=BLUE))
    # bells (domed, tilted outward), FLAME chrome
    for s in (-1, 1):
        bx, by = cx + s * 0.40 * k, cy - 0.50 * k
        ang = math.radians(s * 38)
        prim = ellipsoid(sc, bx, by, 0.235 * k, 0.17 * k, 0.2 * k, angle=ang)
        if prim is not None:
            sl, m, N, (u, v, q) = prim
            dome = (v <= 0.03 * k).astype(np.float32)  # flat bottom lip
            base = mix(RED, MAGENTA, np.clip((u / (0.235 * k) + 1) / 2, 0, 1))
            rgb = shade(N, base, metal=0.55, env_tint=MAGENTA, ks=0.9, power=60, rim=hexc("#FFC2E2"), rim_k=0.5)
            sc.over(sl, rgb, m * dome)
            sc.emit(sl, MAGENTA, m * dome * (1 - np.clip(N[..., 2], 0, 1)) ** 2 * 0.35)
        put(sc, sphere(sc, bx + s * 0.015 * k, by - 0.19 * k, 0.038 * k),
            lambda N, e: shade(N, hexc("#FF7AB8"), metal=0.6, env_tint=MAGENTA, ks=0.9))
    put(sc, sphere(sc, cx, cy - 0.75 * k, 0.05 * k),
        lambda N, e: shade(N, hexc("#7C88FF"), metal=0.7, env_tint=BLUE, ks=0.9))
    # body side (extrusion) visible below the face → cylinder thickness
    put(sc, disc(sc, cx + 0.012 * k, cy + 0.07 * k, R + t * 1.05, dome=0.9),
        lambda N, e: shade(N, mix(hexc("#2B2470"), hexc("#120E3A"), np.clip(e, 0, 1)), metal=0.5, env_tint=VIOLET, ks=0.4))
    # back plate
    put(sc, disc(sc, cx, cy, R + t * 0.95, dome=0.6),
        lambda N, e: shade(N, hexc("#1B1F55"), metal=0.4, env_tint=BLUE, ks=0.3))
    # glass face
    rf = R - t * 0.55

    def face(N, d):
        base = mix(hexc("#1E2770"), hexc("#090D2C"), np.clip(d, 0, 1) ** 1.4)
        col = shade(N, base, ka=0.5, kd=0.45, ks=0.9, power=90)
        vignette = smooth(0.78, 1.0, d)                      # ring occlusion near the edge
        return col * (1 - 0.55 * vignette)[..., None]

    prim = disc(sc, cx, cy, rf, dome=0.35)
    put(sc, prim, face)
    # ticks (neon)
    for i in range(12):
        a = math.radians(i * 30)
        dx, dy = math.sin(a), -math.cos(a)
        major = i % 3 == 0
        if not detail and not major:
            continue
        if major:
            r0, r1, w = 0.33 * k, 0.40 * k, 0.022 * k
            put(sc, capsule(sc, cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, w),
                lambda N, e: shade(N, mix(CYAN, WHITE, 0.35), ka=0.9, kd=0.4, ks=0.6), emit=(CYAN, 0.55))
        else:
            rr = 0.37 * k
            put(sc, sphere(sc, cx + dx * rr, cy + dy * rr, 0.014 * k),
                lambda N, e: shade(N, mix(CYAN, BLUE, 0.4), ka=0.9, kd=0.3), emit=(CYAN, 0.3))
    # hand shadows on the face
    hour_a = math.radians((hour % 12 + minute / 60) * 30)
    min_a = math.radians(minute * 6)
    for a, length, w in ((hour_a, 0.22 * k, 0.034 * k), (min_a, 0.32 * k, 0.025 * k)):
        b = capsule(sc, cx + 0.018 * k, cy + 0.03 * k, cx + 0.018 * k + math.sin(a) * length, cy + 0.03 * k - math.cos(a) * length, w * 1.3)
        if b is not None:
            sl, m, N, _ = b
            sc.over(sl, np.zeros(3, np.float32), blur(m, 0.02 * k * sc.s) * 0.45)
    # hands
    for a, length, w in ((hour_a, 0.22 * k, 0.034 * k), (min_a, 0.32 * k, 0.025 * k)):
        put(sc, capsule(sc, cx, cy, cx + math.sin(a) * length, cy - math.cos(a) * length, w),
            lambda N, e: shade(N, hexc("#EAF6FF"), ka=0.55, kd=0.6, ks=0.9, power=40, rim=CYAN, rim_k=0.6))
    if detail:
        sec_a = math.radians(130)
        put(sc, capsule(sc, cx - math.sin(sec_a) * 0.08 * k, cy + math.cos(sec_a) * 0.08 * k,
                        cx + math.sin(sec_a) * 0.36 * k, cy - math.cos(sec_a) * 0.36 * k, 0.009 * k),
            lambda N, e: shade(N, MAGENTA, ka=0.9, kd=0.3), emit=(MAGENTA, 0.45))
    put(sc, sphere(sc, cx, cy, 0.05 * k),
        lambda N, e: shade(N, hexc("#FF4F8F"), metal=0.6, env_tint=MAGENTA, ks=1.0, power=80))
    # glass reflection band across the upper face
    b = sc.box(cx - rf, cy - rf, cx + rf, cy + rf)
    if b is not None:
        sl, X, Y = b
        dx, dy = (X - cx) / rf, (Y - cy) / rf
        inside = (dx * dx + dy * dy <= 0.93).astype(np.float32)
        arc = np.sqrt((dx + 0.25) ** 2 + (dy + 0.9) ** 2)
        band = smooth(0.62, 0.72, arc) * (1 - smooth(1.02, 1.22, arc)) * smooth(-0.95, -0.2, -dy)
        sc.over(sl, WHITE, inside * band * 0.16)
    # the neon chrome ring
    prim = torus(sc, cx, cy, R, t)
    if prim is not None:
        sl, m, N, ang = prim
        base = neon_sweep(ang)
        rgb = shade(N, base * 0.85, metal=0.65, env_tint=base, ks=1.0, power=70, rim=WHITE, rim_k=0.35)
        sc.over(sl, rgb, m)
        sc.emit(sl, base, m * 0.28)
    # speech waves (it talks!)
    if waves:
        for i, (rad, alpha) in enumerate(((0.72, 1.0), (0.86, 0.85), (1.0, 0.65))):
            span = 34 - i * 4
            put(sc, torus(sc, cx, cy, rad * k, (0.026 if detail else 0.04) * k, -span, span),
                lambda N, e: shade(N, mix(GREEN, CYAN, 0.25), ka=1.0, kd=0.3, ks=0.6),
                alpha=alpha, emit=(GREEN, 0.75 * alpha))


# ---------------------------------------------------------------- microphone

def draw_mic(sc, cx=0.0, cy=0.0, k=1.0):
    head_top, head_bot, hr = cy - 0.42 * k, cy - 0.12 * k, 0.26 * k
    sc.shadow(cx, cy + 0.66 * k, 0.42 * k, 0.06 * k, strength=0.55, softness=0.05 * k)
    # orbit rings: back halves first
    rings = ((0.62, 0.15, CYAN, MAGENTA), (0.80, 0.2, VIOLET, CYAN))

    def ring(rx, ry, c0, c1, back):
        b = sc.box(cx - rx - 0.05, cy - 0.27 * k - ry - 0.05, cx + rx + 0.05, cy - 0.27 * k + ry + 0.05)
        if b is None:
            return
        sl, X, Y = b
        u, v = (X - cx) / (rx * k), (Y - (cy - 0.27 * k)) / (ry * k)
        d = np.sqrt(u * u + v * v)
        w = 0.018 / max(rx, ry)
        m = smooth(w, 0, np.abs(d - 1)) * ((v < 0) if back else (v >= 0)).astype(np.float32)
        col = mix(c0, c1, np.clip((u + 1) / 2, 0, 1))
        sc.over(sl, mix(col, WHITE, 0.3), m * (0.55 if back else 0.95))
        sc.emit(sl, col, m * (0.35 if back else 0.7))

    for rx, ry, c0, c1 in rings:
        ring(rx * k, ry * k, c0, c1, True)
    # base + stem + yoke
    put(sc, ellipsoid(sc, cx, cy + 0.56 * k, 0.28 * k, 0.075 * k, 0.12 * k),
        lambda N, e: shade(N, hexc("#2A2F6B"), metal=0.7, env_tint=VIOLET, ks=0.9, rim=CYAN, rim_k=0.5))
    put(sc, capsule(sc, cx, cy + 0.14 * k, cx, cy + 0.54 * k, 0.04 * k),
        lambda N, e: shade(N, hexc("#3B3F86"), metal=0.75, env_tint=BLUE, ks=0.9))
    put(sc, torus(sc, cx, cy - 0.27 * k, 0.36 * k, 0.034 * k, 10, 170),
        lambda N, e: shade(N, hexc("#4A3FA8"), metal=0.75, env_tint=VIOLET, ks=0.9, rim=MAGENTA, rim_k=0.5))
    for s in (-1, 1):
        put(sc, sphere(sc, cx + s * 0.36 * k, cy - 0.27 * k, 0.05 * k),
            lambda N, e: shade(N, hexc("#FF4F8F"), metal=0.6, env_tint=MAGENTA, ks=1.0))
    # head with grille
    prim = capsule(sc, cx, head_top, cx, head_bot, hr)
    if prim is not None:
        sl, m, N, tt = prim
        b = sc.box(cx - hr, head_top - hr, cx + hr, head_bot + hr)
        _, X, Y = b
        g = 0.045 * k
        gx, gy = (X - cx) / g, (Y - head_top) / g
        offs = (np.floor(gy) % 2) * 0.5
        hole = np.sqrt((gx + offs - np.round(gx + offs)) ** 2 + (gy - np.round(gy)) ** 2)
        holes = smooth(0.32, 0.22, hole) * np.clip(N[..., 2] * 1.4, 0, 1)
        base = mix(hexc("#2B3170"), hexc("#151A45"), holes * 0.9)
        rgb = shade(N, base, metal=0.6, env_tint=BLUE, ks=0.85, power=60, rim=CYAN, rim_k=0.6)
        band_y = cy - 0.205 * k
        band = smooth(0.026 * k, 0.014 * k, np.abs(Y - band_y))
        rgb = mix(rgb, mix(CYAN, WHITE, 0.3) * (0.6 + 0.4 * N[..., 2:3].squeeze(-1))[..., None], band)
        sc.over(sl, rgb, m)
        sc.emit(sl, CYAN, m * band * 0.8)
    # front halves of the rings
    for rx, ry, c0, c1 in rings:
        ring(rx * k, ry * k, c0, c1, False)
    # sparkles
    for (sx, sy, sr, c) in ((0.62, -0.72, 0.05, CYAN), (-0.68, -0.52, 0.035, MAGENTA), (0.7, 0.18, 0.03, GREEN), (-0.58, 0.3, 0.025, CYAN)):
        sparkle(sc, cx + sx * k, cy + sy * k, sr * k, c)


def sparkle(sc, x, y, r, color):
    b = sc.box(x - r * 3, y - r * 3, x + r * 3, y + r * 3)
    if b is None:
        return
    sl, X, Y = b
    u, v = np.abs(X - x) / r, np.abs(Y - y) / r
    star = np.clip(1 - (np.sqrt(u) + np.sqrt(v)) / 1.6, 0, 1) ** 1.5
    sc.over(sl, mix(color, WHITE, 0.6), np.clip(star * 1.6, 0, 1))
    sc.emit(sl, color, star * 0.9)


# ---------------------------------------------------------------- moon

def draw_moon(sc, cx=0.0, cy=0.0, k=1.0):
    r = 0.5 * k
    sc.halo(cx - 0.05 * k, cy, 0.95 * k, VIOLET, 0.35)
    prim = sphere(sc, cx, cy, r)
    if prim is not None:
        sl, m, N, d = prim
        b = sc.box(cx - r, cy - r, cx + r, cy + r)
        _, X, Y = b
        cut = np.sqrt((X - (cx + 0.26 * k)) ** 2 + (Y - (cy - 0.16 * k)) ** 2) / (0.44 * k)
        crescent = m * smooth(0.985, 1.02, cut)
        base = mix(hexc("#E9E4FF"), hexc("#9C8CFF"), np.clip((X - cx) / r * 0.5 + 0.5, 0, 1))
        # craters: small inverted bowls
        crater = np.zeros_like(d)
        for (ux, uy, ur) in ((-0.28, 0.12, 0.09), (-0.18, -0.24, 0.06), (-0.05, 0.3, 0.07), (-0.36, -0.06, 0.045)):
            cd = np.sqrt((X - (cx + ux * k)) ** 2 + (Y - (cy + uy * k)) ** 2) / (ur * k)
            crater = np.maximum(crater, smooth(1.0, 0.75, cd))
        Nc = N.copy()
        rgb = shade(Nc, base * (1 - 0.18 * crater)[..., None], ka=0.35, kd=0.9, ks=0.35, power=25, rim=CYAN, rim_k=0.9)
        sc.over(sl, rgb, crescent)
        edge = crescent * (1 - smooth(0.0, 0.08, cut - 0.985)) * 0.0
        sc.emit(sl, VIOLET, crescent * (1 - np.clip(N[..., 2], 0, 1)) ** 2 * 0.5 + edge)
    for (sx, sy, sr, c) in ((0.5, -0.55, 0.07, CYAN), (0.66, 0.12, 0.04, MAGENTA), (-0.62, -0.58, 0.045, GREEN), (0.28, 0.6, 0.03, CYAN)):
        sparkle(sc, cx + sx * k, cy + sy * k, sr * k, c)


def text_layer(img, xy, text, size, colors, font=FONT_XB, glow=True, anchor="la"):
    """Neon gradient text with glow, composited onto img (RGBA)."""
    f = ImageFont.truetype(font, size)
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).text(xy, text, font=f, fill=255, anchor=anchor)
    m = np.asarray(mask, np.float32) / 255
    ys, xs = np.nonzero(m > 0)
    if len(xs) == 0:
        return img
    x0, x1 = xs.min(), xs.max()
    t = np.clip((np.arange(img.size[0]) - x0) / max(1, x1 - x0), 0, 1)[None, :]
    c0, c1 = colors
    grad = mix(c0, c1, np.broadcast_to(t, m.shape))
    base = np.asarray(img, np.float32) / 255
    rgb, a = base[..., :3], base[..., 3]
    if glow:
        g = blur(m, size * 0.18)[..., None] * grad * 0.9
        rgb = rgb + g * (1 - m[..., None])
        a = np.clip(a + g.max(axis=2) * 0.8, 0, 1)
    rgb = mix(rgb, grad, m)
    a = np.maximum(a, m)
    out = np.dstack([np.clip(rgb, 0, 1), a])
    return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA")


# ---------------------------------------------------------------- backgrounds

def backdrop(w, h, glows=((0.1, 0.1, 0.9, BLUE, 0.35), (0.95, 0.25, 0.7, CYAN, 0.14), (0.9, 0.95, 0.8, MAGENTA, 0.2))):
    y = np.linspace(0, 1, h, dtype=np.float32)[:, None]
    x = np.linspace(0, 1, w, dtype=np.float32)[None, :]
    rgb = mix(hexc("#121845"), DEEP, np.clip(y * 1.1, 0, 1)) * np.ones((h, w, 1), np.float32)
    for gx, gy, gr, gc, ga in glows:
        d = np.sqrt(((x - gx) * w / max(w, h)) ** 2 + ((y - gy) * h / max(w, h)) ** 2) / gr
        rgb = rgb + gc * (np.clip(1 - d, 0, 1) ** 2 * ga)[..., None]
    return Image.fromarray((np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8), "RGB").convert("RGBA")


# ---------------------------------------------------------------- outputs

def save(img, path, size=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if size and img.size != (size, size):
        img = img.resize((size, size), Image.LANCZOS)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path, ROOT), img.size)


def render_clock(size, k_units, waves=True, detail=True, cx=-0.1):
    sc = Scene(size, k_units, center=(0.0, 0.03))
    sc.halo(cx, 0.0, 1.05, VIOLET, 0.28)
    sc.halo(cx + 0.1, -0.1, 0.7, CYAN, 0.10)
    draw_clock(sc, cx=cx, cy=-0.02, detail=detail, waves=waves)
    return sc.finish()


def main():
    nodpi = os.path.join(RES, "drawable-nodpi")
    # illustrations (720 px)
    save(render_clock(720, 720 / 2.15), os.path.join(nodpi, "art_clock.png"))
    sc = Scene(720, 720 / 2.15)
    sc.halo(0, -0.1, 1.0, BLUE, 0.3)
    draw_mic(sc, 0, 0.02)
    save(sc.finish(), os.path.join(nodpi, "art_voice.png"))
    sc = Scene(720, 720 / 2.15)
    draw_moon(sc, -0.08, 0.04)
    img = sc.finish()
    img = text_layer(img, (500, 120), "z", 70, (CYAN, GREEN))
    img = text_layer(img, (560, 60), "z", 52, (GREEN, CYAN))
    save(img, os.path.join(nodpi, "art_moon.png"))

    # adaptive launcher icon: 108dp canvas, subject inside the 66dp safe zone
    fg = Scene(432, 432 / 2.95, center=(-0.02, 0.03))
    draw_clock(fg, cx=-0.1, cy=-0.02, detail=False, waves=True)
    fg_img = fg.finish(bloom=((0.03, 0.8), (0.09, 0.4)))
    bg_img = backdrop(432, 432, glows=((0.2, 0.15, 0.8, BLUE, 0.45), (0.85, 0.9, 0.7, MAGENTA, 0.25), (0.8, 0.2, 0.6, CYAN, 0.12)))
    for name, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        save(fg_img, os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"), px)
        save(bg_img.convert("RGB"), os.path.join(RES, f"mipmap-{name}", "ic_launcher_background.png"), px)

    # docs: 512 icon (as a squircle mask preview) + feature graphic
    docs = os.path.join(ROOT, "docs", "art")
    icon = Image.alpha_composite(bg_img, fg_img).resize((512, 512), Image.LANCZOS)
    mask = Image.new("L", (512, 512), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 511, 511), radius=115, fill=255)
    rounded = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    rounded.paste(icon, (0, 0), mask)
    save(rounded, os.path.join(docs, "icon_512.png"))

    fgr = backdrop(1024, 500)
    art = render_clock(460, 460 / 2.15)
    fgr.alpha_composite(art, (30, 20))
    fgr = text_layer(fgr, (500, 150), "Voice Alarm", 84, (CYAN, GREEN))
    fgr = text_layer(fgr, (504, 270), "Wakes you with your words.", 34, (hexc("#F2F5FF"), hexc("#A3ACD9")), font=FONT_MD, glow=False)
    fgr = text_layer(fgr, (504, 330), "“Good morning! Time to wake up. For interview.”", 22, (MAGENTA, VIOLET), font=FONT_MD, glow=False)
    save(fgr.convert("RGB"), os.path.join(docs, "feature_graphic.png"))


if __name__ == "__main__":
    main()
