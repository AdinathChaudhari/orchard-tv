#!/usr/bin/env python3
"""Orchard Rev 4 + Rev 6: WCAG contrast check. Part 1 (Rev 4): the light theme over the static day wall (a fixed Light theme). Part 2 (Rev 6): every sky keyframe in its own theme, then the rendered sweeps (dawn/dusk flips minute by minute, round-the-clock x weather).
Original Rev 4 header: WCAG contrast check for the light (and, informationally, the dark) theme.
Reads the colour tokens straight from mockup/tokens.css (the light block overrides :root), composites them over the
worst-case backgrounds the UI really draws on, and exits 1 if any text pair is below 4.5:1 in the LIGHT theme.
Worst cases: the darkest day-wallpaper pixel after the vignette (+ a golden-hour tint), glass panels over that,
the Top Shelf wash for every app colour, the focused-row fill, the mid-point of the dawn/dusk cross-fade."""
import re, sys, json, colorsys
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
CSS = (ROOT / "mockup/tokens.css").read_text()

def block(sel):
    m = re.search(r"(?m)^" + re.escape(sel) + r"\s*\{(.*?)\n\}", CSS, re.S)
    return dict(re.findall(r"--([\w-]+)\s*:\s*([^;]+);", re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S)))
DARK = block(":root")
LIGHT = {**DARK, **block(':root[data-theme="light"]')}

def col(v):
    v = v.strip()
    if v.startswith("#"):
        v = v[1:]
        if len(v) == 3: v = "".join(c * 2 for c in v)
        return np.array([int(v[i:i+2], 16) for i in (0, 2, 4)] + [1.0])
    m = re.match(r"rgba?\(([^)]*)\)", v)
    p = [float(x) for x in m.group(1).split(",")]
    return np.array(p[:3] + [p[3] if len(p) > 3 else 1.0])
def over(fg, bg):          # composite fg (rgba) over an opaque bg (rgb)
    a = fg[3]; return fg[:3] * a + np.asarray(bg[:3]) * (1 - a)
def lin(c): c = np.asarray(c, float) / 255; return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
lin_arr = lin
def L(rgb): r, g, b = lin(rgb); return 0.2126 * r + 0.7152 * g + 0.0722 * b
def ratio(a, b): la, lb = L(a), L(b); hi, lo = max(la, lb), min(la, lb); return (hi + 0.05) / (lo + 0.05)
def hsl_tone(hexs, target, satmax):
    r, g, b = [int(hexs[i:i+2], 16) / 255 for i in (1, 3, 5)]
    h, l, s = colorsys.rgb_to_hls(r, g, b); s = min(s, satmax)
    return np.array([x * 255 for x in colorsys.hls_to_rgb(h, target, s)])

def run(T, name, strict):
    fails = []
    def chk(label, fg, bg, need=4.5):
        r = ratio(over(col(fg), bg) if isinstance(fg, str) else fg, bg)
        ok = r >= need
        print(f"  {'ok  ' if ok else 'FAIL'} {r:5.2f}  {label}")
        if not ok and strict: fails.append(label)
    print(f"[{name}]")
    wall_file = "day.webp" if name == "light" else "dusk.webp"
    im = np.asarray(Image.open(ROOT / "mockup/wall" / wall_file).convert("RGB")).astype(float)
    lum = (0.2126 * im[..., 0] + 0.7152 * im[..., 1] + 0.0722 * im[..., 2])
    dark_px, light_px = im.reshape(-1, 3)[lum.argmin()], im.reshape(-1, 3)[lum.argmax()]
    vig = np.array([92, 84, 76], float) if name == "light" else np.array([11, 11, 16], float)
    va = float(T["day-vignette-alpha"]) if name == "light" else float(T["wall-vignette-alpha"])
    worst = dark_px * (1 - va) * (1 - va * 0.7) + vig * va * 0.9   # corner vignette then the bottom fade, conservative
    walls = {"wallpaper worst case": worst if name == "light" else light_px}
    # golden hour: warm overlay at its strongest (light only)
    gold = over(np.array([255, 146, 62, float(T["golden-a1"])]), worst)
    if name == "light": walls["wallpaper worst + golden-hour tint"] = gold
    for wl, bg in walls.items():
        for tk in ("text", "text2"):
            chk(f"{tk} on {wl}", T[tk], bg)
    # text3 (hints, "keeps" line) only ever sits on glass, the flat bg, or the settings/city left column: gate it there, not on the far corner
    if name == "light":
        reg = im[230:450, 80:600].reshape(-1, 3); rl = 0.2126 * reg[:, 0] + 0.7152 * reg[:, 1] + 0.0722 * reg[:, 2]
        left_worst = reg[rl.argmin()] * (1 - va)
        chk("text3 on the settings left column (darkest wallpaper pixel there)", T["text3"], left_worst)
        chk("text3 on the flat bg (Safe mode)", T["text3"], col(T["bg"])[:3])
    # glass surfaces drawn over the worst wallpaper
    for gname in ("glass-thin", "glass", "glass-thick"):
        g = col(T[gname]); surf = over(g, worst if name == "light" else light_px)
        for tk in ("text", "text2", "text3"):
            chk(f"{tk} on {gname} over wallpaper", T[tk], surf)
    # focused control
    fill = col(T["btn-focus-fill"])[:3]
    chk("focused text on focused fill", T["btn-focus-text"], fill)
    chk("focused value (17,17,17,.6) on focused fill", "rgba(17,17,17,0.6)", fill)
    # 'dim value in a list row' (City list 'Sunset HH:MM', Settings values): colour only, no ad-hoc opacity, so it is checkable here
    if name == "light":
        idx = open(ROOT / "mockup/index.html").read()
        dim_rule = re.search(r"\.row \.val \.dim\{([^}]*)\}", idx).group(1)
        if "opacity" in dim_rule:
            print("  FAIL  .row .val .dim carries an ad-hoc opacity (unchecked contrast)"); fails.append("dim opacity")
        chk("dim value in a list row, unfocused (text2) on the worst wallpaper", T["text2"], worst)
        chk("dim value in a list row, unfocused (text2) on glass over the worst wallpaper", T["text2"], over(col(T["glass"]), worst))
        chk("dim value in a list row, focused (17,17,17,.6) on focused fill", "rgba(17,17,17,0.6)", fill)
    # shelf wash for every app colour: origin colour and the mid stop, text drawn near the left (blends to bg)
    if name != "light": return fails
    banners = json.loads((ROOT / "mockup/banners/colors.json").read_text())
    vivid = ["#e71c25", "#0a8bfc", "#ff0b0b", "#987beb", "#1dc057", "#387ceb", "#d6980f", "#a91113", "#6e7390", "#fe8904", "#F2994A", "#3FAE7A", "#b71717", "#5B6477"] + list(banners.values())
    worst_wash = None
    for v in vivid:
        c0 = hsl_tone(v, float(T["hero-lum"]), float(T["hero-sat-max"])); c1 = hsl_tone(v, float(T["hero-wash-mid-lum"]), float(T["hero-sat-max"]))
        for bgc in (c0, c1):
            r = ratio(over(col(T["text2"]), bgc), bgc)
            if worst_wash is None or r < worst_wash[0]: worst_wash = (r, v, bgc)
    r, v, bgc = worst_wash
    chk(f"text2 (shelf subtitle) on the worst shelf wash ({v})", T["text2"], bgc)
    chk("text (shelf title) on the same wash", T["text"], bgc)
    # dawn/dusk: the old fixed-box wallpaper-only model is gone (it passed while the render failed). The sweep below measures the real render.
    return fails

SKY_DARK = ("night", "dawn", "sunset", "dusk")      # keyframes drawn under the DARK token set (white text); the other four are under the LIGHT set (dark text)
ZONES = {   # where text really sits on the home screen: (x0, y0, x1, y1, tokens that sit there)
    "status strip":      (60, 40, 1860, 140, ("text", "text2", "hint")),
    "shelf title/sub":   (60, 230, 1250, 440, ("text", "text2")),
    "focus label":       (60, 640, 1860, 860, ("text",)),                  # #flabel, full --text, anywhere under a focused tile
    "tile labels":       (60, 780, 1860, 840, ("text2",)),                 # .lbl, only with Settings -> Tile labels = Always, dock row
}
PANEL = (460, 60, 1460, 760)        # Quick Panel (y 60..376) and the reset sheet (centred, y ~330..750): glass-thick over the sky

def worst_px(im, L_, x0, y0, x1, y1, dark):
    """the colour a glyph can really land on: the mean of the worst 2 % of the zone's pixels (lightest for a dark keyframe, darkest for a light one).
    The single most extreme pixel is a noise speck or a star, not something a glyph sits on."""
    reg = im[y0:y1, x0:x1].reshape(-1, 3); rl = L_[y0:y1, x0:x1].reshape(-1)
    idx = np.argsort(rl); k = max(8, int(len(idx) * 0.02))
    return reg[idx[-k:]].mean(0) if dark else reg[idx[:k]].mean(0)

def sky_static():
    """Rev 6 gate 1: every sky keyframe (clear, overcast, rain-dimmed) against its OWN theme's text colours, over the real pixels of each text zone, and the glass surfaces
    (panel / sheet / settings rows through the 55 % dimmer). This proves the palette; gate 2 (render sweep) proves the whole UI: glow tiles, the halo, the flips."""
    fails = []
    print("[sky keyframes, static, each in its own theme]")
    dim_rain = float(DARK["wx-rain-dim"])
    for key in ("night", "dawn", "morning", "noon", "afternoon", "golden", "sunset", "dusk"):
        dark = key in SKY_DARK; T = DARK if dark else LIGHT
        for var in ("clear", "overcast", "rain"):
            f = ROOT / f"mockup/wall/sky-{key}{'-oc' if var != 'clear' else ''}.webp"
            im = np.asarray(Image.open(f).convert("RGB")).astype(float)
            if var == "rain":
                im = im * (1 - dim_rain)
                if not dark: va = float(DARK["wx-rain-day-a"]); im = im * (1 - va) + np.array([float(x) for x in DARK["wx-rain-day"].split(",")]) * va   # the day slate veil (full strength: the worst case for dark text)
            L_ = 0.2126 * im[..., 0] + 0.7152 * im[..., 1] + 0.0722 * im[..., 2]
            res = []
            for zn, (x0, y0, x1, y1, toks) in ZONES.items():
                px = worst_px(im, L_, x0, y0, x1, y1, dark)
                for tk_ in toks:
                    fg = np.array([*col(T["text"])[:3], float(T["strip-opacity-hint"])]) if tk_ == "hint" else col(T[tk_])
                    res.append((ratio(over(fg, px), px), f"{key}/{var}: {tk_} in {zn}"))
            px = worst_px(im, L_, *PANEL, dark)                      # glass-thick (panel, sheet) over the sky, all three text colours (phint / keeps use text3)
            surf = over(col(T["glass-thick"]), px)
            for tk_ in ("text", "text2", "text3"): res.append((ratio(over(col(T[tk_]), surf), surf), f"{key}/{var}: {tk_} on glass-thick (panel, sheet)"))
            px = worst_px(im, L_, 80, 140, 1840, 1040, dark)         # settings / city rows (glass-thin), through the sky dimmer (bg at --sky-dimmer)
            dim = over(np.array([*col(T["bg"])[:3], float(DARK["sky-dimmer"])]), px); row = over(col(T["glass-thin"]), dim)
            for tk_ in ("text", "text2"): res.append((ratio(over(col(T[tk_]), row), row), f"{key}/{var}: {tk_} on a settings row (dimmed sky)"))
            px = worst_px(im, L_, 80, 140, 620, 620, dark)           # the left column (title, hint, current city): only here text2 / text3 sit on the bare dimmed sky
            dim = over(np.array([*col(T["bg"])[:3], float(DARK["sky-dimmer"])]), px)
            for tk_ in ("text2", "text3"): res.append((ratio(over(col(T[tk_]), dim), dim), f"{key}/{var}: {tk_} on the settings column (dimmed sky)"))
            bad = [(r, l) for r, l in res if r < 4.5]
            for r, l in bad: print(f"  FAIL {r:5.2f}  {l}"); fails.append(l)
            m = min(r for r, _ in res)
            print(f"  {'ok  ' if not bad else 'FAIL'} {m:5.2f}  {key}/{var}: worst of {len(res)} text pairs ({'dark' if dark else 'light'} tokens)")
    return fails

RECORDS = []
LARGE = ()          # Rev 6 fix round 2: no large-text relaxation any more. Every element, the shelf title and the focus label included, needs the full target
TARGET = 4.6        # the gate's metric is a touch kinder than an independent pixel check (4.34 vs 4.53 on the same state), so the gate aims 0.1 above WCAG's 4.5
def render_sweep(only_wx=None, only_t=None):
    """Rev 6 gate 2: Playwright renders of the whole home screen. One 'shown' and one 'hidden' (all text colour:transparent) screenshot per state; per element,
    glyph-core pixels (top diff) vs the hidden backdrop give WCAG contrast. Needs TARGET (4.6:1) for every element: no large-text relaxation. Transitions are switched off so every state is its final one.
    Sweeps: (A) EVERY minute across sunset-16..+16 and sunrise-16..+16 (the 30-minute cross-fade + margin, the halo at its cap) in clear, cloudy, rain AND fog; run twice, row 0 focused and row 1 focused (status-strip scrim on, focus label one row lower);
    (B) every 30 minutes round the clock x clear / cloudy / rain / fog; (C) the keyframe hold times."""
    import io
    try: from playwright.sync_api import sync_playwright
    except Exception: print("  SKIP render sweep (playwright not installed): the sky gate did NOT run"); return ["render sweep unavailable"]
    url = (ROOT / "mockup/index.html").as_uri(); fails = []; worst = {}
    boxes = {"shelf subtitle": ".hsub", "shelf title": ".htitle", "greeting": "#greet", "date": "#date", "clock": "#clock", "Options hint": "#hint", "weather caption": "#wx .cap", "weather rain chance": "#wx .wxr", "weather high/low": "#wx .wxhl", "weather temperature": "#wxt", "focus label": "#flabel"}
    rng = "(sels)=>Object.fromEntries(Object.entries(sels).map(([n,s])=>{const e=document.querySelector(s);if(!e)return [n,null];const r=document.createRange();r.selectNodeContents(e);const b=r.getBoundingClientRect();return [n,{x:b.x,y:b.y,width:b.width,height:b.height}]}))"
    hide = "".join(f"{sel}{{color:transparent!important;-webkit-text-fill-color:transparent!important}}" for sel in boxes.values())
    def measure(pg, tag):
        bbs = pg.evaluate(rng, boxes)
        shown = np.asarray(Image.open(io.BytesIO(pg.screenshot())).convert("RGB")).astype(float)
        h = pg.add_style_tag(content=hide); hidden = np.asarray(Image.open(io.BytesIO(pg.screenshot())).convert("RGB")).astype(float)
        pg.evaluate("document.querySelectorAll('style').forEach((s,i,l)=>{if(i==l.length-1)s.remove()})")
        for name, bb in bbs.items():
            if not bb or bb["width"] < 2: continue
            x0, y0, x1, y1 = int(bb["x"]), int(bb["y"]), int(bb["x"] + bb["width"]) + 1, int(bb["y"] + bb["height"]) + 1
            sh, hd = shown[y0:y1, x0:x1], hidden[y0:y1, x0:x1]
            d = np.abs(sh - hd).sum(-1)
            if (d > 30).sum() < 3: continue
            m = d >= np.percentile(d, max(0, 100 - 100 * min(0.12, (d > 30).mean()) * 0.6))
            r = ratio(sh[m].mean(0), hd[m].mean(0))
            fgc = sh[m].mean(0); lf = L(fgc); hl = hd.reshape(-1, 3); ll = 0.2126 * lin(hl[:, 0]) + 0.7152 * lin(hl[:, 1]) + 0.0722 * lin(hl[:, 2])
            pr = (np.maximum(lf, ll) + 0.05) / (np.minimum(lf, ll) + 0.05)
            r = min(r, float(np.percentile(pr, 2)))     # strict: the glyph colour against the 2nd-percentile worst backdrop pixel anywhere in the text box (halo kept, text hidden)
            RECORDS.append((name, r, tag))
            if name not in worst or r < worst[name][0]: worst[name] = (r, tag)
    with sync_playwright() as p:
        br = p.chromium.launch(); pg = br.new_page(viewport={"width": 1920, "height": 1080})
        pg.goto(url + "?debug=1&time=21:42&wx=clear"); pg.wait_for_timeout(1200)
        pg.add_style_tag(content="*,*::before,*::after{transition:none!important;animation:none!important}#flabel.show{opacity:1!important}")   # animations off would leave the focus label at its opacity-0 start
        f = re.findall(r"(\d\d):(\d\d)", pg.inner_text("#dbgsun")); rise, sset = [int(a) * 60 + int(b) for a, b in f[:2]]
        keyt = pg.evaluate("()=>{const o=window.__orchard,s=o.sunToday();return ['night','dawn','morning','noon','afternoon','golden','sunset','dusk'].map(k=>o.keyTime(k,s))}")
        def state(t, wx):
            pg.evaluate("([t,wx])=>{window.__orchard.S.sky='auto';window.__orchard.setWx(wx);window.__orchard.setTime(t)}", [t, wx])
            pg.evaluate("window.__orchard.skyReady()"); pg.wait_for_timeout(110)
        flips = list(range(sset - 16, sset + 17)) + list(range(rise - 16, rise + 17))
        grid = sorted(set(list(range(0, 1440, 30)) + [int(x) for x in keyt]))
        passes = [("", False)] + ([("r1 ", True)] if "--no-row1" not in sys.argv else [])
        for ptag, down in passes:        # r1 = the grid's second row focused: the opaque status-strip scrim is on (tinted per keyframe), and the focus label sits one row lower
            if down:
                pg.keyboard.press("ArrowDown"); pg.wait_for_timeout(600)
            for wx in (only_wx or ("clear", "cloudy", "rain", "fog")):          # every minute, every weather, +-16 min round BOTH flips (the old sweep stepped every 4th minute in three of the four and missed 18:00 in fog)
                for t in (only_t or flips):
                    state(t, wx); measure(pg, f"{ptag}{wx} {t // 60:02d}:{t % 60:02d}")
            if "--flips" in sys.argv: continue
            for wx in ("clear", "cloudy", "rain", "fog"):
                for t in grid:
                    state(t, wx); measure(pg, f"{ptag}{wx} {t // 60:02d}:{t % 60:02d}")
        br.close()
    for must in ("focus label", "weather high/low", "shelf subtitle", "date", "clock", "greeting", "Options hint"):
        if must not in worst: print(f"  FAIL  zone never measured: {must}"); fails.append(f"zone {must} not measured")
    for name, (r, tag) in worst.items():
        need = 3.0 if name in LARGE else TARGET; ok = r >= need
        print(f"  {'ok  ' if ok else 'FAIL'} {r:5.2f}  rendered sky sweep, {name}: worst at {tag}, need {need}")
        if not ok: fails.append(f"render {name}")
    return fails

def main():
    f = run(LIGHT, "light", True)
    f += sky_static()
    if "--static" not in sys.argv:
        print("[sky, rendered sweeps: flips every minute, weather x 30 min grid]"); f += render_sweep()
    run(DARK, "dark", False)   # informational: the fixed Dark theme keeps the Rev 4 static dusk wall and is not gated here
    print("\nLIGHT THEME:", "PASS (every text pair >= 4.5:1)" if not f else "FAIL: " + "; ".join(f))
    sys.exit(1 if f else 0)

if __name__ == "__main__": main()
