#!/usr/bin/env python3
"""Orchard Rev 6: the time-of-day sky. Writes mockup/wall/sky-<key>.webp (8 clear keyframes + `horizon` and `sunrise`, the vivid hand-over frames at the sunset / sunrise flip), sky-<key>-oc.webp (the
overcast step of each), sun-glow.png, moon-glow.png and rain-tile.png, and the per-keyframe scrim top colours (into tokens.css). 1920x1080, smooth gradients interpolated in OKLab
(so a pink never passes through a muddy grey on its way to blue), ~0.9% noise to kill banding (was 1.5%: the spread it put in a text zone widened the narrow band where neither text colour reads). Pure Pillow + numpy.

Run:  ~/.venvs/main/bin/python tools/make_sky.py            (all files)
      ~/.venvs/main/bin/python tools/make_sky.py --sheet X  (also a contact sheet PNG, for palette review)

Design constraint (the reason the dark and the light keyframes look the way they do): the UI text colour is decided by the
Rev 4 theme rule (flip at sunrise/sunset), so every DARK-theme keyframe (night, dawn, sunset, dusk) keeps the part of the sky
that text sits on (y < 80 %) dark enough for white text, and lets the bright horizon glow live in the lowest ~15 %; every
LIGHT-theme keyframe (morning, noon, afternoon, golden) stays light enough for dark text everywhere. tools/check_contrast.py
gates this on the real pixels."""
import sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
WALL = ROOT / "mockup/wall"
W, H = 1920, 1080
rng = np.random.default_rng(20261020)

# ---------- OKLab helpers ----------
def srgb2lin(c): c = c / 255.0; return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
def lin2srgb(c): c = np.clip(c, 0, 1); return 255.0 * np.where(c <= 0.0031308, c * 12.92, 1.055 * c ** (1 / 2.4) - 0.055)
M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566], [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099], [0.0259040371, 0.7827717662, -0.8086757660]])
def rgb2lab(rgb): lms = srgb2lin(np.asarray(rgb, float)) @ M1.T; return np.cbrt(lms) @ M2.T
def lab2rgb(lab): lms = (np.asarray(lab, float) @ np.linalg.inv(M2).T) ** 3; return lin2srgb(lms @ np.linalg.inv(M1).T)
def hexc(h): return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], float)
def ss(x): x = np.clip(x, 0, 1); return x * x * (3 - 2 * x)

def pchip(xs, ys, x):
    """monotone cubic (Fritsch-Carlson) through (xs, ys): no overshoot, no kinks at the stops (a smoothstep per segment leaves visible bands)"""
    xs = np.asarray(xs, float); ys = np.asarray(ys, float); h = np.diff(xs); d = np.diff(ys) / h; n = len(xs)
    m = np.zeros(n); m[0], m[-1] = d[0], d[-1]
    for i in range(1, n - 1):
        m[i] = 0.0 if d[i - 1] * d[i] <= 0 else 3 * (h[i - 1] + h[i]) / ((2 * h[i] + h[i - 1]) / d[i - 1] + (h[i] + 2 * h[i - 1]) / d[i])
    i = np.clip(np.searchsorted(xs, x) - 1, 0, n - 2); t = (x - xs[i]) / h[i]
    h00, h10, h01, h11 = 2 * t**3 - 3 * t**2 + 1, t**3 - 2 * t**2 + t, -2 * t**3 + 3 * t**2, t**3 - t**2
    return h00 * ys[i] + h10 * h[i] * m[i] + h01 * ys[i + 1] + h11 * h[i] * m[i + 1]

def vgrad(stops):
    """vertical gradient, stops = [(y_frac, '#hex'), ...], monotone-cubic in OKLab; returns HxWx3 OKLab"""
    ys = [s[0] for s in stops]; labs = np.array([rgb2lab(hexc(s[1])) for s in stops])
    y = np.clip(np.linspace(0, 1, H), ys[0], ys[-1])
    col = np.stack([pchip(ys, labs[:, c], y) for c in range(3)], axis=1)
    return np.repeat(col[:, None, :], W, axis=1)

def glow(lab, cx, cy, rx, ry, col, amt, power=2.0):
    """soft elliptical glow of colour `col` (hex), amt = peak mix, mixed in OKLab"""
    yy, xx = np.mgrid[0:H, 0:W]
    d = np.sqrt(((xx / W - cx) / rx) ** 2 + ((yy / H - cy) / ry) ** 2)
    a = (np.clip(1 - d, 0, 1) ** power * amt)[..., None]
    return lab * (1 - a) + rgb2lab(hexc(col)) * a

def finish(lab, noise=0.009):
    rgb = lab2rgb(lab)
    n = rng.normal(0, 255 * noise, rgb.shape[:2])[..., None] * 0.8 + rng.normal(0, 255 * noise * 0.5, rgb.shape)   # mostly luminance noise, a little chroma
    return np.clip(rgb + n, 0, 255)

NOSTARS = {}      # keyframe name -> its star-free base (night only), used for the -oc twin

# ---------- the eight clear keyframes ----------
def stars(img, n=95):
    """a few faint static stars: soft 1-2 px dots, upper 62% only, alpha .22 .. .6. The glyph-free band y < 150 stays empty (status strip text)."""
    yy, xx = np.mgrid[0:H, 0:W]
    out = img.copy()
    for _ in range(n):
        x = rng.uniform(40, W - 40); y = rng.uniform(150, H * 0.62)
        a = rng.uniform(0.30, 0.75) * (1 - y / (H * 0.62)) ** 0.5 ; r = rng.choice([0.9, 1.1, 1.5], p=[0.55, 0.35, 0.10])
        d2 = (xx[int(y) - 4:int(y) + 5, int(x) - 4:int(x) + 5] - x) ** 2 + (yy[int(y) - 4:int(y) + 5, int(x) - 4:int(x) + 5] - y) ** 2
        k = (a * np.exp(-d2 / (2 * r * r)))[..., None]
        sl = out[int(y) - 4:int(y) + 5, int(x) - 4:int(x) + 5]
        sl[:] = sl * (1 - k) + np.array([225, 232, 255.0]) * k
    return out

def key_night():
    lab = vgrad([(0, "#050A1C"), (0.45, "#0A1536"), (0.82, "#12215A"), (1.0, "#1B2F78")])
    lab = glow(lab, 0.5, 1.15, 0.90, 0.45, "#31489A", 0.45, 1.6)
    base = finish(lab, 0.008); NOSTARS["night"] = base      # the overcast / fog twin is made from the star-free base: a cloud deck has no stars
    return stars(base)

def key_dawn():
    """deep blue -> violet -> rose -> peach at the (left, east) horizon; DARK theme (white text) so it stays dark above y 80 %"""
    lab = vgrad([(0, "#0A1034"), (0.38, "#17206A"), (0.60, "#2E2C78"), (0.76, "#443080"), (0.84, "#63388A"), (0.89, "#A04A8A"), (0.93, "#D8668C"), (0.97, "#F59A8E"), (1.0, "#FFC49C")])
    lab = glow(lab, 0.16, 1.10, 0.78, 0.36, "#FFC79A", 0.50, 1.4)
    return finish(lab)

def key_morning():
    """soft clear blue, a pale warm haze at the horizon; LIGHT theme"""
    lab = vgrad([(0, "#98BCEA"), (0.40, "#B1D0F0"), (0.76, "#D3E4F5"), (1.0, "#F6EADB")])
    lab = glow(lab, 0.14, 1.04, 0.80, 0.50, "#FFD9B0", 0.72, 1.4)
    return finish(lab)

def key_noon():
    """bright, near white, a faint warm glow high in the sky; LIGHT theme"""
    lab = vgrad([(0, "#BDD6F2"), (0.52, "#DAE8F8"), (1.0, "#F9F6EF")])
    lab = glow(lab, 0.5, -0.04, 0.66, 0.60, "#FFF2D6", 0.60, 1.4)
    return finish(lab)

def key_afternoon():
    lab = vgrad([(0, "#A3C4EC"), (0.50, "#C6DAF0"), (0.84, "#E9E3D9"), (1.0, "#F8E0BF")])
    lab = glow(lab, 0.86, 1.02, 0.78, 0.52, "#FFD08E", 0.78, 1.4)
    return finish(lab)

def key_golden():
    """warm amber / gold, sun low on the right; still LIGHT theme (dark text)"""
    lab = vgrad([(0, "#A7B6DE"), (0.34, "#DDC8BE"), (0.64, "#F5CE9C"), (0.86, "#FFB968"), (1.0, "#FF9B44")])
    lab = glow(lab, 0.84, 1.04, 0.74, 0.50, "#FFC070", 0.60, 1.4)
    return finish(lab)

def key_sunset():
    """orange -> purple -> indigo, real sunset colours; DARK theme: indigo above, the orange lives low and to the right (west)"""
    lab = vgrad([(0, "#0E1340"), (0.30, "#231F6C"), (0.55, "#3B2E80"), (0.74, "#55297A"), (0.82, "#6E2F72"), (0.88, "#A03F68"), (0.93, "#D4583E"), (0.97, "#F27C3A"), (1.0, "#FF9A44")])
    lab = glow(lab, 0.82, 1.10, 0.72, 0.30, "#FF8A3C", 0.50, 1.6)
    return finish(lab)

def key_horizon():
    """(Rev 6 fix round: the mid bands are DARKER than before, L <= .17 up to y 72 %, so white text still reads here just before the flip; the vivid peach lives in the lowest 16 %, where only tile art sits.
    The sky windows put the top-band luminance crossing L ~ .19 exactly at the theme flip, which is the only moment neither text colour has margin.)
    the sun on the horizon (about elevation -1.0, exactly where the UI text colour flips): vivid, mid-luminance on purpose. The golden > sunset hand-over passes THROUGH this
    (muted violet-blue, rose, coral, peach-orange) instead of blending amber straight into indigo, which is a flat mauve-grey at the half-way point."""
    lab = vgrad([(0, "#4C4F9C"), (0.30, "#62569E"), (0.55, "#7C5898"), (0.72, "#94588C"), (0.84, "#B8688A"), (0.91, "#E88A76"), (1.0, "#FFB05A")])
    lab = glow(lab, 0.84, 1.04, 0.74, 0.50, "#FFC070", 0.55, 1.4)
    return finish(lab)

def key_dusk():
    lab = vgrad([(0, "#070C2A"), (0.42, "#161A56"), (0.70, "#2B2870"), (0.86, "#4E2F7A"), (0.94, "#8A3F78"), (1.0, "#C0604E")])
    lab = glow(lab, 0.84, 1.10, 0.64, 0.30, "#E27A50", 0.42, 1.6)
    return finish(lab)

def key_sunrise():
    """the sunrise hand-over frame: the `horizon` frame mirrored (the sun comes up in the east = left). dawn > sunrise > morning replaces the old straight dawn > morning blend, whose half-way
    point was a dusty slate-blue (deep blue + light blue = grey-lilac); passing through violet / rose / peach keeps it a sunrise."""
    return key_horizon()[:, ::-1].copy()

KEYS = {"night": key_night, "dawn": key_dawn, "morning": key_morning, "noon": key_noon, "afternoon": key_afternoon,
        "golden": key_golden, "horizon": key_horizon, "sunset": key_sunset, "dusk": key_dusk, "sunrise": key_sunrise}

HANDOVER = ("golden", "horizon", "sunrise", "morning")

def overcast(rgb, name):
    """one step of cloud: colour pulled 45 % toward its own luminance, 3 % darker by day / 14 % by night (a bright day sky stays bright: dark text sits on it), and the vertical contrast flattened 25 % toward the mean (8 % for the four hand-over frames)"""
    lum = (rgb * np.array([0.2126, 0.7152, 0.0722])).sum(-1, keepdims=True)
    out = rgb * 0.55 + lum * 0.45
    mean = out.mean(axis=(0, 1), keepdims=True)
    flat = 0.08 if name in HANDOVER else 0.25      # the four frames either side of the two theme flips keep their vertical contrast (a flattened top band is lighter on a dark frame and darker on a light one, which squeezes the text contrast at the flip in cloud / rain)
    out = out * (1 - flat) + mean * flat
    out = out * (0.86 if name in ("night", "dusk", "dawn", "sunset") else 0.94 if name == "sunrise" else 0.97)   # sunrise: a touch deeper, so the overcast step keeps the white-text side of the flip as readable as the clear one
    return np.clip(out + rng.normal(0, 255 * 0.008, out.shape), 0, 255)

# ---------- glow tiles + rain tile ----------
def radial_tile(size, stops_fn):
    yy, xx = np.mgrid[0:size, 0:size]; d = np.sqrt((xx - size / 2 + 0.5) ** 2 + (yy - size / 2 + 0.5) ** 2) / (size / 2)
    return stops_fn(np.clip(d, 0, 1))

def sun_glow(size=720):
    a = radial_tile(size, lambda d: (np.exp(-(d * 3.2) ** 2) * 0.55 + (1 - d) ** 3 * 0.30) * (d < 1))
    a = a * (1 - ss((np.sqrt(((np.mgrid[0:size, 0:size][1] - size / 2) ** 2 + (np.mgrid[0:size, 0:size][0] - size / 2) ** 2)) / (size / 2) - 0.85) / 0.15))
    rgba = np.zeros((size, size, 4)); rgba[..., 0], rgba[..., 1], rgba[..., 2] = 255, 238, 205; rgba[..., 3] = np.clip(a, 0, 1) * 255
    return Image.fromarray(rgba.astype(np.uint8), "RGBA")

def moon_glow(size=360):
    """a moon, not a smudge: a tight bright core (r ~ 11 px, cool white) in a small blue halo (sigma ~ 30 px) and a faint wider one (sigma ~ 70 px). No broad grey skirt."""
    d = radial_tile(size, lambda d: d); px = d * size / 2          # distance in px
    core = np.exp(-(px / 11.0) ** 4) * 1.0
    halo = np.exp(-(px / 30.0) ** 2) * 0.24 + np.exp(-(px / 72.0) ** 2) * 0.07
    a = np.clip(core + halo, 0, 1) * (1 - ss((d - 0.85) / 0.15))
    rgba = np.zeros((size, size, 4)); rgba[..., 0], rgba[..., 1], rgba[..., 2] = 208, 224, 255; rgba[..., 3] = a * 255
    return Image.fromarray(rgba.astype(np.uint8), "RGBA")

def rain_tile(size=384, n=46, seed=11, rgb=(246, 250, 255)):
    """one seamless 1x tile of thin diagonal streaks (12 deg from vertical, 22 .. 46 px long, 1.4 px wide, tails fade). Streaks wrap in both axes so it repeats cleanly.
    Used at layer alpha .25 and translated down; white, so it reads on both the dark and the light sky."""
    r = np.random.default_rng(seed); S = 4                       # 4x supersample for clean 1 px lines
    big = Image.new("L", (size * S, size * S), 0)
    import math
    from PIL import ImageDraw
    dr = ImageDraw.Draw(big); ang = math.radians(12)
    for _ in range(n):
        x, y = r.uniform(0, size), r.uniform(0, size); L = r.uniform(22, 46); a = r.uniform(0.45, 1.0)
        steps = 12
        for ox in (-size, 0, size):
            for oy in (-size, 0, size):
                for i in range(steps):
                    t0, t1 = i / steps, (i + 1) / steps
                    f = (t0 + t1) / 2; v = int(255 * a * (0.25 + 0.75 * f))           # tail fades in toward the head
                    p0 = ((x + ox - math.sin(ang) * L * (1 - t0)) * S, (y + oy - math.cos(ang) * L * (1 - t0)) * S)
                    p1 = ((x + ox - math.sin(ang) * L * (1 - t1)) * S, (y + oy - math.cos(ang) * L * (1 - t1)) * S)
                    dr.line([p0, p1], fill=v, width=int(1.5 * S))
    alpha = big.resize((size, size), Image.LANCZOS)
    rgba = Image.new("RGBA", (size, size), (*rgb, 0)); rgba.putalpha(alpha)
    return rgba

def write_top_tokens(rows):
    """the status-strip scrim is opaque to y 120 and fades out by 190: tint it with each keyframe's own top colour (mean of rows 30..130, the strip text rows), so it never reads as a flat slab.
    Written into mockup/tokens.css between the sky-top markers; mock.js cross-fades the layers by the same f / overcast alpha as the sky itself."""
    css = ROOT / "mockup/tokens.css"; t = css.read_text(); i0 = t.index("/* sky-top:begin */") + len("/* sky-top:begin */"); i1 = t.index("/* sky-top:end */")
    body = "\n" + "\n".join(f"  --sky-top-{k}: {r},{g},{b};" for k, (r, g, b) in rows.items()) + "\n  "
    css.write_text(t[:i0] + body + t[i1:])

def save(rgb, path, q=93):
    Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).save(path, "WEBP", quality=q, method=6)

def main():
    WALL.mkdir(exist_ok=True)
    sheet = []; tops = {}
    for name, fn in KEYS.items():
        clear = fn(); oc = overcast(NOSTARS.get(name, clear), name)
        save(clear, WALL / f"sky-{name}.webp"); save(oc, WALL / f"sky-{name}-oc.webp")
        for nm, im in ((name, clear), (name + "-oc", oc)): tops[nm] = tuple(int(round(v)) for v in np.clip(im[30:130].reshape(-1, 3).mean(0), 0, 255))
        sheet.append((name, clear, oc))
        print("wrote", name, (WALL / f"sky-{name}.webp").stat().st_size // 1024, "KB")
    write_top_tokens(tops)
    sun_glow().save(WALL / "sun-glow.png", optimize=True); moon_glow().save(WALL / "moon-glow.png", optimize=True)
    rain_tile().save(WALL / "rain-tile.png", optimize=True)
    rain_tile(rgb=(60, 80, 110)).save(WALL / "rain-tile-light.png", optimize=True)     # slate twin for the light sky (white streaks do not show on a bright day sky); same geometry, picked by theme
    if "--sheet" in sys.argv:
        out = Path(sys.argv[sys.argv.index("--sheet") + 1]); tw, th = 480, 270
        im = Image.new("RGB", (tw * 4 + 50, (th * 2 + 10) * 2 + 20), (20, 20, 20))
        for i, (name, c, o) in enumerate(sheet):
            x, y = 10 + (i % 4) * (tw + 10), 10 + (i // 4) * (2 * th + 20)
            im.paste(Image.fromarray(c.astype(np.uint8)).resize((tw, th), Image.LANCZOS), (x, y))
            im.paste(Image.fromarray(o.astype(np.uint8)).resize((tw, th), Image.LANCZOS), (x, y + th + 4))
        im.save(out); print("sheet", out)

if __name__ == "__main__":
    main()
