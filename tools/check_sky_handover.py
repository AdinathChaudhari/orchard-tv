#!/usr/bin/env python3
"""Orchard Rev 6 fix round 2: the sky hand-over check. A live minute step must never flash.
When a cross-fade window ends and the next begins (dawn>sunrise becomes sunrise>morning at 06:03 -> 06:04, golden>horizon becomes horizon>sunset at 18:05 -> 18:06 in Bengaluru on 2026-10-20),
the picture on screen is already the keyframe the new pair starts from, so mock.js swaps the layer roles with no transition (PLAN 9.3). Before that rule the old B layer faded out
over 900 ms under a freshly set next keyframe: a slate-blue frame at 120 ms that differed from both end states by 19 .. 33 mean |d|.
This script steps the clock one minute (as the 30 s live tick does), with ONLY the sky layer visible, and compares frames at 0, 60, 120, 300, 600 and 1100 ms with the frame before the step.
The hand-over minutes are FOUND by scanning today's minutes for 'new A == old B' (they move with the windows in tokens.css); the two named above are two of them. Pass = at every moment
of the fade the mean |d| stays below max(LIMIT (5), the step's own end-state change + OVERSHOOT (2)) at every hand-over, in clear, cloudy, rain and fog: a step may change the sky
as much as the cross-fade moves it (the narrow flip windows move it a lot in 3.5 minutes), but no frame of the fade may differ from the frame before the step by more than the picture it ends on. --all also steps 05:50..06:39 and 17:40..18:29 minute by minute (information only).
Run: ~/.venvs/main/bin/python tools/check_sky_handover.py [--all]"""
import io, sys
from pathlib import Path
import numpy as np
from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
URL = (ROOT / "mockup/index.html").as_uri() + "?debug=1&time=21:42&wx=clear"
LIMIT = 5.0; OVERSHOOT = 2.0
SWAP_JS = """()=>{const o=window.__orchard,out=[];let prev=null;for(let t=240;t<1380;t++){o.setTime(t);const k=o.S.skyState;if(prev&&k.a!==prev.a&&k.a===prev.b)out.push([t-1,t]);prev={a:k.a,b:k.b}}return out}"""   # every minute at which the new pair starts from the old pair's B

def shot(pg): return np.asarray(Image.open(io.BytesIO(pg.screenshot())).convert("RGB")).astype(float)

def main():
    fails = []; allm = "--all" in sys.argv
    with sync_playwright() as p:
        br = p.chromium.launch(); pg = br.new_page(viewport={"width": 1920, "height": 1080}); errs = []
        pg.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
        pg.goto(URL); pg.wait_for_timeout(1000); pg.add_style_tag(content="#stage>*:not(#sky){visibility:hidden!important}")
        swaps = pg.evaluate(SWAP_JS); print("  hand-over minutes found (clear sky, today):", ", ".join(f"{a // 60:02d}:{a % 60:02d}->{b // 60:02d}:{b % 60:02d}" for a, b in swaps))
        steps = [(f"{a // 60:02d}:{a % 60:02d} -> {b // 60:02d}:{b % 60:02d}", a, b) for a, b in swaps]
        STEPSET = set(steps)   # (name, a, b) of every hand-over: these must pass; the --all extras only inform
        if allm: steps += [(f"{x // 60:02d}:{x % 60:02d} -> +1", x, x + 1) for x in list(range(350, 400)) + list(range(1060, 1110))]
        for name, a, b in steps:
            for wx in ("clear", "cloudy", "rain", "fog"):
                pg.evaluate("([t,wx])=>{window.__orchard.S.sky='auto';window.__orchard.setWx(wx);window.__orchard.setTime(t)}", [a, wx])
                pg.evaluate("window.__orchard.skyReady()"); pg.wait_for_timeout(1300)
                A = shot(pg); pg.evaluate("t=>window.__orchard.setTime(t)", b)
                worst = 0.0; spent = 0
                for ms in (0, 60, 120, 300, 600, 1100):
                    pg.wait_for_timeout(max(0, ms - spent)); spent = max(spent, ms)
                    worst = max(worst, float(np.abs(shot(pg) - A).mean()))
                pg.wait_for_timeout(1200); end = float(np.abs(shot(pg) - A).mean())
                lim = max(LIMIT, end + OVERSHOOT); ok = worst < lim; important = (name, a, b) in STEPSET
                if important or not ok: print(f"  {'ok  ' if ok else ('FAIL' if important else 'info')} {worst:5.2f}  {name} ({wx}): worst frame of the fade vs the frame before the step (the step itself changes the sky by {end:.2f}), need < {lim:.2f}")
                if important and not ok: fails.append(f"{name} {wx}")
        print("  console errors:", errs); br.close()
    print("\nSKY HAND-OVER:", "PASS" if not fails and not errs else "FAIL: " + "; ".join(fails + errs))
    sys.exit(1 if fails or errs else 0)

if __name__ == "__main__": main()
