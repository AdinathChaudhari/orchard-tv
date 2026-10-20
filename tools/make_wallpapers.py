#!/usr/bin/env python3
"""Orchard Phase 0.1: original wallpapers + placeholder banners (no real logos)."""
import colorsys, json, shutil
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
WALL = ROOT / "mockup/wall"
BAN = ROOT / "mockup/banners"
RES = ROOT / "app/res/drawable-nodpi"
rng = np.random.default_rng(7)

def hexc(h): return np.array([int(h[i:i+2], 16) for i in (1, 3, 5)], float)

# (top-left colour, bottom-right colour)
WALLS = {"dusk": ("#2E2A6B", "#17181C"), "ember": ("#5A0F14", "#050304"), "fog": ("#3A4656", "#050608")}

def wallpaper(a, b, w=1920, h=1080):
    y, x = np.mgrid[0:h, 0:w]
    t = (x / w * 0.45 + y / h * 0.55)            # diagonal
    t = np.clip(t, 0, 1)[..., None] ** 0.9
    img = hexc(a) * (1 - t) + hexc(b) * t
    img += rng.normal(0, 255 * 0.015, img.shape)  # ~1.5% noise
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))

FONTS = ["/System/Library/Fonts/Helvetica.ttc", "/System/Library/Fonts/SFNS.ttf", "/Library/Fonts/Arial.ttf"]
def font(size):
    for f in FONTS:
        try: return ImageFont.truetype(f, size)
        except OSError: pass
    return ImageFont.load_default(size)

# slug: (bg, fg, text, glyph)
BANNERS = {
    "netflix":   ("#B0070F", "#FFFFFF", "Netflix", "bars"),
    "jiohotstar":("#1B2A6B", "#FFD54A", "JioHotstar", "star"),
    "sonyliv":   ("#14161C", "#E8E8EE", "SonyLIV", "circle"),
    "jiotv":     ("#C2185B", "#FFFFFF", "JioTV+", "play"),
    "zee5":      ("#5B1FA8", "#FFFFFF", "ZEE5", "ring"),
    "appletv":   ("#101010", "#F2F2F7", "Apple TV", "rect"),
    "spotify":   ("#1DB954", "#0B140E", "Spotify", "waves"),
    "vlc":       ("#F57C00", "#FFFFFF", "VLC", "cone"),
    "youtube":   ("#D81F26", "#FFFFFF", "YouTube", "play"),
    "drivecast": ("#2F6FED", "#FFFFFF", "Drivecast", "cast"),
}

def glyph(d, kind, cx, cy, r, fg, bg):
    if kind == "bars":
        for i in range(3): d.rectangle([cx-r+i*r*0.7, cy-r, cx-r+i*r*0.7+r*0.45, cy+r], fill=fg)
    elif kind == "star":
        import math
        pts = [(cx+(r if i%2==0 else r*0.45)*math.sin(i*math.pi/5), cy-(r if i%2==0 else r*0.45)*math.cos(i*math.pi/5)) for i in range(10)]
        d.polygon(pts, fill=fg)
    elif kind == "circle": d.ellipse([cx-r, cy-r, cx+r, cy+r], fill=fg)
    elif kind == "play": d.polygon([(cx-r*0.7, cy-r), (cx-r*0.7, cy+r), (cx+r, cy)], fill=fg)
    elif kind == "ring": d.ellipse([cx-r, cy-r, cx+r, cy+r], outline=fg, width=int(r*0.3))
    elif kind == "rect": d.rounded_rectangle([cx-r, cy-r*0.7, cx+r, cy+r*0.7], r*0.2, outline=fg, width=int(r*0.25))
    elif kind == "waves":
        for i in range(3):
            rr = r*(0.45+i*0.35); d.arc([cx-rr, cy+r*0.5-rr, cx+rr, cy+r*0.5+rr], 215, 325, fill=fg, width=int(r*0.22))
    elif kind == "cone": d.polygon([(cx, cy-r), (cx-r*0.8, cy+r), (cx+r*0.8, cy+r)], fill=fg)
    elif kind == "cast":
        for i in range(3): d.arc([cx-r*(i+1)/3-r, cy-r*(i+1)/3+r*0.3, cx+r*(i+1)/3-r, cy+r*(i+1)/3+r*0.3+r], 270, 360, fill=fg, width=int(r*0.2))
        d.rectangle([cx-r, cy+r*0.3, cx-r+r*0.25, cy+r*0.3+r*0.25], fill=fg)

def banner(bg, fg, text, kind, w=320, h=180):
    im = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(im)
    glyph(d, kind, 52, 62, 22, fg, bg)
    size = 34
    f = font(size)
    while d.textlength(text, font=f) > w - 36 and size > 12:
        size -= 1; f = font(size)
    d.text((18, 120), text, font=f, fill=fg)
    return im

def vivid(im):
    """12x12 sample, score = saturation * value-ish; return most vivid pixel as #rrggbb."""
    s = im.convert("RGB").resize((12, 12), Image.BILINEAR)
    best, score = None, -1
    for px in s.getdata():
        hh, ss, vv = colorsys.rgb_to_hsv(*(c/255 for c in px))
        sc = ss * (0.35 + vv)
        if sc > score: best, score = px, sc
    return "#%02x%02x%02x" % best

def main():
    for name, (a, b) in WALLS.items():
        p = WALL / f"{name}.webp"
        wallpaper(a, b).save(p, "WEBP", quality=80)
        shutil.copy(p, RES / f"wall_{name}.webp")
    colors = {}
    for slug, (bg, fg, text, kind) in BANNERS.items():
        im = banner(bg, fg, text, kind)
        im.save(BAN / f"{slug}.png")
        colors[slug] = vivid(im)
    (BAN / "colors.json").write_text(json.dumps(colors, indent=2) + "\n")
    print("ok", len(WALLS), "wallpapers,", len(BANNERS), "banners")

if __name__ == "__main__":
    main()
