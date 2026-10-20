#!/usr/bin/env python3
"""Export the Rev 6 sky keyframes, glows and rain tiles from mockup/wall/ into app/res/drawable-nodpi/.

Sky keyframes: 1920x1080 -> 960x540 (LANCZOS, RGB), then fresh 1.0 % luminance-weighted noise (seeded, reproducible) so
8-bit banding cannot appear after the downscale averaged the original noise away. WebP quality 92, method 6.
Glows and rain tiles are copied pixel-for-pixel (RGBA PNG, optimize).

Run:  python3 -I tools/export_sky_assets.py [--check]
"""
import argparse
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
WALL = ROOT / "mockup/wall"
OUT = ROOT / "app/res/drawable-nodpi"
KEYS = ["night", "dawn", "sunrise", "morning", "noon", "afternoon", "golden", "horizon", "sunset", "dusk"]
COPIES = {"sun-glow.png": "sun_glow.png", "moon-glow.png": "moon_glow.png",
          "rain-tile.png": "rain_tile.png", "rain-tile-light.png": "rain_tile_light.png"}
SIZE = (960, 540)
NOISE = 0.010
SEED = 20261020
NAME_RE = re.compile(r"^[a-z0-9_]+$")


def res_name(fname):
    stem = Path(fname).stem
    if not NAME_RE.match(stem):
        sys.exit(f"invalid Android resource name: {stem!r} (from {fname})")
    return stem


def sky(src, dst, rng):
    im = Image.open(src).convert("RGB").resize(SIZE, Image.LANCZOS)
    a = np.asarray(im, np.float32)
    lum = rng.normal(0, 255 * NOISE, a.shape[:2])[..., None] * 0.8
    chroma = rng.normal(0, 255 * NOISE * 0.5, a.shape)
    out = np.clip(np.rint(a + lum + chroma), 0, 255).astype(np.uint8)
    Image.fromarray(out, "RGB").save(dst, "WEBP", quality=92, method=6)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="print what would change, write nothing")
    args = ap.parse_args()
    rng = np.random.default_rng(SEED)
    jobs = []
    for k in KEYS:
        for suf in ("", "-oc"):
            jobs.append(("sky", WALL / f"sky-{k}{suf}.webp", OUT / (res_name(f"sky_{k}{suf.replace('-', '_')}") + ".webp")))
    for s, d in COPIES.items():
        jobs.append(("copy", WALL / s, OUT / (res_name(d) + ".png")))
    for kind, src, dst in jobs:
        if not src.exists():
            sys.exit(f"missing source: {src.relative_to(ROOT)}")
        if kind == "copy":
            im = Image.open(src)
            if src.name.startswith("rain-tile") and im.size != (384, 384):
                sys.exit(f"{src.name}: expected 384x384, got {im.size}")
            if src.name.endswith("glow.png"):
                print(f"{src.name}: {im.size[0]}x{im.size[1]}")
        state = "update" if dst.exists() else "new"
        if args.check:
            print(f"[check] {state}: {dst.relative_to(ROOT)}")
            continue
        OUT.mkdir(parents=True, exist_ok=True)
        if kind == "sky":
            sky(src, dst, rng)
        else:
            im = Image.open(src).convert("RGBA")
            im.save(dst, "PNG", optimize=True)
        with Image.open(dst) as o:
            print(f"{dst.name}: {o.size[0]}x{o.size[1]} {dst.stat().st_size} bytes")


if __name__ == "__main__":
    main()
