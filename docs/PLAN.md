# Orchard — an Apple TV-style home for the Fire TV Stick 4K Plus

> **Historical document.** This is the design and build plan written before Orchard existed, in the private repo where it
> began. It mentions `undo.sh`, "detox", a debloat of the Stick and a second launcher; the detox now lives in this repo as `scripts/detox.sh` (see docs/DETOX.md). `scripts/launcher.sh factory-stock` resets the redirect, restores the saved settings, runs `detox.sh undo all` and uninstalls Orchard. TESTLOG.md references point to the unpublished raw log; see docs/TESTING.md for the summary.



Build plan, written 2026-10-19 by the Fable planner. **Rev 2** (same day) after an adversarial review;
every change is listed in § 6. Executed by an Opus manager that spawns Sonnet workers and reviews
every step on the real TV.

**Read first:** `research/` (five files, verbatim dossier). Confidence tags there ([V]/[E]/[H]/[M]/[D]) still
apply; every number in Section 0 is a *starting value* to tune on the device, except the safe area.

**Name:** Orchard (renamed from "Hearth" on 2026-10-19, before any code; repo name `orchard-tv`).
Package `tv.orchard.home`. Neither "Apple" nor "Fire TV" (both trademarks) goes in the app or repo name;
we describe it as "Apple TV-style home screen for Fire TV". No Apple fonts, icons, aerials, sounds or screenshots are shipped.

**Device facts:** Fire TV Stick 4K Plus (AFTMA08C15, "karat"), Fire OS 8.1.8.5, Android 11 / API 30, 2 GB RAM,
~2 GB free on `/data`, Wi-Fi ADB on port 5555, reached via `scripts/tv.sh '<cmd>'`. Home on Fire 1.5.2 (HoF) redirects
Home to the launcher; this plan began with a different launcher as the HoF target and switched to Orchard at gate G6a.

**Mac facts** (as planned; the shipped tooling is `tools/env.sh`, which detects these):
- JDK 17+ (built with Homebrew `openjdk@21`). `/usr/bin/java` is the macOS stub.
- Android SDK command-line tools with `build-tools/34.0.0` and `platforms/android-30`.
- ADB: the plan used a Python client (`tools/adb/tv.py`, `b64push.py`) with its own key instead of the `adb` binary, so the
  TV shows one "Allow" prompt for one key. The key path comes from `ORCHARD_ADB_KEY` (default `~/.android/adbkey`).
- TV address: `TV_HOST` (required; env or a git-ignored `.env`) and `TV_PORT` (default 5555). If DHCP moves the Stick, run
  `TV_HOST=<new-ip> scripts/launcher.sh status`; reserve the IP in the router (Settings -> My Fire TV -> About -> Network).
- Python: Pillow and numpy for wallpaper generation, `fonttools` for font subsetting.

---

## Q0. One question before Phase 0 (USER GATE, asked by the manager first)

> "Your 'go back to the original Fire TV' button: do you mean **(a)** Home goes back to Amazon's home
> screen, with the debloat, AdGuard DNS and update block kept (default; this is what the in-app button
> can do), or **(b)** a fully stock Stick (everything undone, which only the Mac can do)?"

Both exist whatever the answer: the in-app button is always (a) and says so; `scripts/launcher.sh
factory-stock` is (b). The answer only changes which one the README calls "the reset" and whether
`factory-stock` is tested in Phase 2 (yes if (b)). An app cannot do (b): re-enabling packages needs
`pm enable` from a shell, not `WRITE_SECURE_SETTINGS`.

**Answered 2026-10-19: (a).** The in-app button is "the reset" (Amazon home, detox kept). The user still
wants a guaranteed way back to a stock Stick if things go haywire, so `factory-stock` is **dry-run tested
in Phase 2 anyway** (task 2.6), and the README lists Amazon's own Settings → My Fire TV → Reset to
Factory Defaults as the no-Mac last resort (wipes logins; re-enables updates).

---

## 0. Product vision and design spec

**Rev 3 design changes (2026-10-19, premium pass; values marked [R3] below, all in `mockup/tokens.css`):**
1. Hero = a *backlit card in a dark room*: radial colour wash centred behind the card, the card lit by its own pre-blurred banner (glow), 1 px specular rim, contact + lift shadows. Card **560x315 r22 at x 1280, y 172** (was 640x360). `hero=blur` adds a full-bleed pre-blurred banner (option, not default). The shelf fades out while a grid row is focused.
2. Real banners pulled from the Stick are the mockup's default art (`?art=real`, local git-ignored `mockup/banners-real/`); `?art=placeholder` keeps the Phase 0.1 placeholders. Projectivy and Home on Fire have banners; only Downloader and Files use the icon-only fallback.
3. Liquid Glass stand-in for every control surface (dock band, Quick Panel, sheet, buttons, chip, Fire TV Home tile): pre-blurred wallpaper slice + low fill + **1 px gradient rim lit at the top-left** + refraction band inside the top edge + soft drop. No backdrop blur anywhere.
4. Focus: spring-like in-curve `PathInterpolator(0.34, 1.25, 0.64, 1)` over 240 ms, deeper shadow + tight contact shadow + **ambient glow in the app's vivid colour** under the focused tile, 1 px specular rim on the focused face.
5. Icon-only fallback is a dark back plate (L .26 → .09) with a soft halo and the icon floating on it with its own shadow, not a bright gradient.
6. Quick Panel is compact and centred (1000 wide, hint as one Caption 2 line under the tiles); Safe mode gets a shield glyph; tracking tightened (−0.02 em at ≥ 48 px).
7. The focused app's name stays in two places only: the shelf title and the label under the focused tile (the banner's own wordmark is the app's art). Placeholder wordmarks disappear with real art.

### 0.1 Vision (one paragraph)

Press Home and land on a quiet, dark, cinematic screen: a hero band on top that follows whatever app
is focused (its sharp banner on the right over a colour wash, its name on the left), a glass dock of
six favourites, and rows of large rounded tiles below. Focus is shown by lift (scale, shadow, gloss),
never by an outline. Everything is driven by the D-pad; a short Menu press opens a glass Quick Panel
whose second tile is "Return to Fire TV", the status strip always says `≡ Options`, and the last grid
tile is "Fire TV Home". It runs cold in under a second, stays under 110 MB, and never takes away the
user's way back to Amazon's home.

### 0.2 Canvas, scale and safe area  [V]

- Design canvas **1920x1080**. All layout math uses `s = screenWidthPx / 1920f` (measured in Phase 2;
  expected 1.0). Convert design px to device px with `px(v) = Math.round(v * s)`.
- Safe area: **80 px left/right, 60 px top/bottom**. Only backdrops bleed outside it.
- Grid, default: **6 columns**, tile **260x156** (5:3), gutter **40** → 6x260 + 5x40 = 1760 = 1920 - 2x80.
  Column x origins: `x_i = 80 + i*300`, i = 0..5.
- Grid, alternative tested in the mockup: **5 columns**, tile **320x192**, gutter 40 → 5x320 + 4x40 =
  1760. In 5 columns row 1 of the grid runs past the bottom safe edge (y 892..1084): this is a deliberate tvOS-style 'peek' of the next row, not a bug. 6 columns keeps row 1 inside the safe area (ends 1012). (The HIG width table says 260 at 6 columns; the legacy Xamarin page says 300x180; the sources
  conflict, so the user picks by eye at G0.) `Ui.COLS` and `Ui.TILE_W/H` carry the chosen set; all
  other numbers derive from them.

### 0.3 Home layout (design px, y from top, 6-column numbers)

| Band | y range | Content |
|---|---|---|
| Status strip (fixed overlay) | 60..110 | Left, always: `≡ Options` (Caption 2, white 60%) so the Menu key is discoverable. Next to it, a status chip (Caption 2) only when something is off-normal: `Fire TV home active`, `Redirect may be off in Home on Fire`, `Safe mode`, `adb grant missing`. Right: clock `21:42` (Headline 38, Medium, white 92%) and date `Sun 19 Oct` (Caption 1 25, white 60%) at x=1840 right edge. tvOS has no permanent clock; the mockup has a clock on/off switch and the user decides at G0 (default on; also a setting). |
| Top Shelf (hero band, scrolls with content) | 60..580 | **Backdrop [R3]:** a *radial* wash centred behind the card (centre at 80% x, 45% y of the band; radii 62% x 110%): the focused app's vivid colour at L 0.32 (saturation capped at 0.70; "lum" everywhere in §0 means HSL lightness, so Ui.java uses ColorUtils.colorToHSL and sets L directly) → the same hue at L 0.14 (`--hero-wash-mid-lum`) at 38% → `bg` at the edge; a white 6% light leak top-left; a vignette (black 55% at the left edge → 10% at 60%, and 35% at the bottom) for the title. Bleeds from y=0 to **600** and fades to the wallpaper over the bottom **240** px, so it is gone above the dock band. Cross-faded 280 ms (debounce 120 ms). `hero=blur` (option) adds the banner itself, pre-blurred (its own 240x135 stack blur, radius ~11) and stretched full-bleed at 35% alpha, the Top Shelf "static image blurred to fill" treatment. **While a grid row is focused the shelf fades out (250 ms) and the strip scrim is opaque.** [R3.1] **While the Quick Panel or the Reset sheet is open, the shelf card, its glow and the title/subtitle fade out (200 ms)**, so no sharp art shows through the glass; only the wash remains. **Art [R3]:** the app's banner drawn sharp at **560x315** (1.75x of 320x180, never larger; on device use the highest-density banner the APK ships, up to 640x360), radius 22, at x 1280..1840, y 172..487 (centre y 330, same as the title block). Under it a **glow**: the same banner pre-blurred (its own 240x135 bitmap, not the `hero=blur` one), drawn at 1.12x, offset +28 y, alpha 0.50; a contact shadow (y 4, blur 12, black 45%) and a lift shadow (y 32, blur 72, black 55%); a 1 px specular rim (inset white 38% top edge, 8% around). For icon-only apps the glow is a flat disc of the vivid colour at L 0.50, alpha 0.25 (`--hero-glow-flat-lum` / `--hero-glow-flat-alpha`). Banners with a near-white average (Netflix, YouTube) ~~draw the card at 0.85 brightness~~ [P4: dimming removed, the card stays bright white; on device "near-white" = mean rule or >= 55% white pixels, `Ui.NEAR_WHITE_FRACTION`] and keep the flat vivid glow; `?card=off` previews the shelf without the card (wash + title only) so the user can decide at G0. **Text:** app title (Title 2, 57, Medium, white, tracking −0.02 em) at x=80, baseline y=330; subtitle (Body 29, Regular, white 62%) at y=380, e.g. `Opened 2 h ago` or `Never opened`. No focusable items in the shelf. |
| Dock | tiles 600..756 | 6 tiles, glass band behind them: x 40..1880 [R3.1: 16 px wider per side so the focused first/last tile is not cramped], y 576..772 (pad 24 top, 16 bottom, so the focused label sits fully below the band), radius 32. **[R3] Band = Liquid Glass stand-in (§ 0.8):** the pre-blurred wallpaper slice under it at 90% + fill white 6.5% (painted once) + 1 px gradient rim (white 34% at the top-left corner → 7% along the sides → 20% at the bottom-right) + refraction band (white 12% → 0 over the top 28 px, plus white 10% → 0 over the top 45%) + drop shadow (y 24, blur 60, black 35%). The band never shows the hero wash through it (it samples the wallpaper only), which is why the wash ends at y 600. |
| Grid rows | row k top = 600 + 256·k, k ≥ 1 | 6 per row, same tile size, no band. Row pitch 256 = 156 + 100. Rows are generated from the catalogue (all apps not in the dock, minus hidden), **in the user's saved order (G0: own order; new installs append at the end)**, **then one last tile "Fire TV Home"** ([R3] a *glass* tile, the only glass among content so it reads as a control: fill white 11% → 4% diagonal, 1 px gradient rim, 64 px house glyph stroke 2.6 at white 92% raised 10 px, caption "Fire TV Home" Caption 2 white 62% 16 px above the bottom; hidden while the tile is focused, since the focus label names it) that opens the § 0.10 confirm sheet. Setting `firetv_tile=on|off`, default on. |
| Focused label | **scaled** tile bottom + 16 | App name, Body 29 Medium, white, centred under the focused tile only. The scaled tile is 1.12x, so its bottom is 9 px lower than the layout bottom: label top = layout bottom + 9 + 16. [R3] Fades in **240 ms with +8 px rise on the focus spring curve**. Unfocused tiles have no label (setting `labels=always` turns them on at white 62%). |

Scrolling: a vertical `ScrollView` holds shelf + dock + rows. When focus enters row k ≥ 1, scroll so the
focused row's top sits at **y = 600** (same band the dock used), 350 ms decelerate. When focus returns
to the dock, scroll to 0. The status strip is a fixed sibling on top. `clipChildren=false` and
`clipToPadding=false` on every container; 40 px of padding around rows so scaled tiles never clip.

### 0.4 Tiles and icons

- Tile art: `activityInfo.loadBanner(pm)` → application banner → `loadIcon`. Banners are 16:9; draw
  **centre-crop into 5:3** (loses ~3.5% top and bottom). [R3] Icon-only apps get a 3-layer fallback that
  reads as a backlit plate, not a bright gradient: a vertical back layer from the icon's vivid colour at
  **L 0.26 (top) to L 0.09 (bottom)**, a radial halo top-left (vivid at L 0.42, alpha 0.55, centre 18%/0%,
  radii 90%/110%), and the icon drawn at **96 px** centred, radius 22, with its own shadow (y 10, blur 24,
  black 45%) and a 1 px white 35% top edge. (The mockup's stand-in icon is a letter on a gradient L 0.58 → 0.40.)
  Every tile keeps a static 1 px inset edge at white 7% (not a focus outline).
- Rasterise each tile once into a **325x195 RGB_565** bitmap (1.25x of 260x156: enough for the 1.12
  focus scale, ~127 KB each, ~5 MB for 40 apps). Cache in `LruCache` (≤ 1/8 of `memoryClass`) and on
  disk as WebP q85 under `getCacheDir()/art/`. The shelf's 560x315 card (up to 640x360 on device) is a second, separate
  rasterisation kept for the focused app and its last neighbour only (≤ 2 decoded, ~450 KB each).
- Corner radius **14 px** on the 260-wide tile, drawn with a `BitmapShader` (CLAMP) on a paint with
  `ANTI_ALIAS_FLAG` + `FILTER_BITMAP_FLAG` and `canvas.drawRoundRect`. **Not** `clipPath` (its
  anti-aliasing is not guaranteed on hardware canvases). Works with RGB_565 art.
- `contentDescription` = app label on every tile.

### 0.5 Focus effect (the whole feel; all tunable constants live in `Ui.java`)

| Constant | Start value | Notes |
|---|---|---|
| `FOCUS_SCALE` | **1.12** | Apple is 1.10–1.15; tune by eye against the mockup. |
| `FOCUS_IN_MS` / `FOCUS_OUT_MS` | **240 / 160** [R3] | In: **`PathInterpolator(0.34f, 1.25f, 0.64f, 1f)`**, a spring-like curve with ~3% overshoot (y > 1 is legal for a cubic PathInterpolator). Out: `AccelerateInterpolator(1.2)`. The same in-curve drives the label rise, the Quick Panel tiles, buttons and the sheet's scale-in. |
| `PRESS_SCALE` / `PRESS_MS` | **0.96 / 80** | On Select: dip, then launch. |
| Shadow | lift y **28**, blur **56**, black **48%** + contact y **3**, blur **8**, black **40%** [R3] | One shared pre-rendered shadow bitmap holding both (tile size + 160 px padding, two `BlurMaskFilter` passes baked once). **Drawn by `TileRow.dispatchDraw` before its children**, under the focused child's *transformed* bounds (`child.getMatrix()` applied to its rect), alpha following the scale progress. Never drawn inside `TileView.onDraw` (a view's own layer clips at its bounds). No `elevation`. |
| Ambient glow [R3] | y **12**, blur **96**, the app's vivid colour at L **0.50**, alpha **0.65** [R3.1, was 0.45 / 0.50]; [R3.2] tiles whose art averages below L 0.2 (Apple TV, Spotify, ZEE5) use L **0.58**, alpha **0.85** (`--glow-dark-*`) so focus reads beyond scale alone; the focused face also lifts **3%** brightness (`--focus-brightness` 1.03, a `ColorMatrixColorFilter` on the face paint) | One shared radial alpha bitmap (white, tile size + 200 px) tinted per app with a `PorterDuffColorFilter(SRC_IN)`, drawn in the same `dispatchDraw` pass under the shadow, alpha following the scale progress. Colour = the tile art's vivid colour (computed once at rasterisation, cached with the art). One extra `drawBitmap` per frame while animating; nothing when idle. |
| Z-order | focused child drawn last | `TileRow.setChildrenDrawingOrderEnabled(true)` + `getChildDrawingOrder` returns the focused index last, so the 1.12x tile is never covered by its right neighbour. |
| Gloss + rim | diagonal white **14% → 0** from top-left to centre; 1 px rim white **32%** top edge, **10%** around [R3] | A `LinearGradient` created once per size change, drawn inside the rounded rect of the focused tile only; its offset follows the sway. The rim is a `drawRoundRect` stroke (1 px, `LinearGradient` top → bottom) on the focused tile only. Both fade with the focus progress. |
| Idle sway | `rotationY` ±**1.5°**, `rotationX` ±**1.0°**, period **6 s** sine | Only on the focused tile; `setCameraDistance(8000 * density)`. Starts 400 ms after focus settles, **stops after 3 cycles (18 s) or 20 s, whichever first, and in `onPause`**; restarts on the next focus change. Off under reduced motion. |
| Idle dim | after **3 s** idle, unfocused tiles alpha → **0.90**, focused scale +**0.02** | Restored on any key. One animation, then no redraws until the next key. |
| Edge bump | **6 px** nudge, 120 ms | When a D-pad press hits a row end. No wrap-around. |
| Reduced motion | all of the above replaced by a 150 ms alpha cross-fade of the label and a static 1.06 scale | Triggered by `Settings.Global.ANIMATOR_DURATION_SCALE == 0` or the `motion=reduced` setting. |

Rules: animate only `scaleX/Y`, `translationX/Y`, `rotationX/Y`, `alpha`. **No hardware layers**
(RenderNode transform animations do not need one, and a layer clips shadow and gloss). Fast D-pad
repeats cancel and retarget; never block input. An idle home screen must reach zero redraws: after 30 s
without a key, `dumpsys gfxinfo tv.orchard.home` "Total frames rendered" must stop rising (Phase 3 check 8).

### 0.6 Typography (Inter, SIL OFL)

| Style | px | Weight | Used for |
|---|---|---|---|
| Title 2 | 57 | Medium 500 | Top Shelf app title |
| Headline | 38 | Medium 500 | Clock, panel titles |
| Body | 29 | Medium 500 (labels) / Regular 400 (subtitle) | Tile labels, settings rows |
| Caption 1 | 25 | Regular 400 | Date, helper text |
| Caption 2 | 23 | Medium 500 | Status chip, `≡ Options` hint. **Nothing smaller.** |

Tracking [R3]: **−0.02 em at ≥ 48 px, −0.01 em at 29–47 px, +0.005 em at ≤ 25 px.** Bundle **Inter Regular, Medium, SemiBold** as static TTF subset to
Latin (target ≤ 120 KB each via `pyftsubset`). Task 1.4 reads Inter's `LICENSE.txt`/`OFL.txt`: if it
declares a Reserved Font Name, the subset files are renamed (`OrchardSans-*.ttf`, internal name table
too, `--name-IDs` kept consistent); if not (expected), they stay `inter_*.ttf`. Either way the full OFL
text ships in `THIRD_PARTY_NOTICES.txt`. Fallback if the download fails: Outfit from
`tv-vibehack/launcher/app/src/main/res/font/outfit.ttf` (the worker fetches Outfit's OFL text from the
upstream font repo, since tv-vibehack ships none). Never SF Pro.

### 0.7 Colour tokens (dark only)

| Token | Value |
|---|---|
| `bg` | `#0B0B10` (window background; also the first frame while the Amazon flash is covered) |
| `text` / `text2` / `text3` | white 100% / **62%** / **42%** [R3] |
| `glass_thin` / `glass` / `glass_thick` | [R3] white **7%** / white **12%** / **`#0E0E14` at 74%** over the blurred wallpaper slice. Hairlines are no longer flat: every glass surface carries the § 0.8 gradient rim. |
| `shadow` | black **48%** [R3] |
| `accent` | `#3D8BFF` (toggles only; never for focus) |
| Focused button | fill `#F2F2F7`, text `#111111` (Apple's inverted-button rule) |

### 0.8 Wallpaper, blur and glass on API 30

- Three bundled original wallpapers (generated by `tools/make_wallpapers.py`, 1920x1080 WebP q80,
  dark two-tone gradients with 1.5% noise to kill banding): `dusk` (indigo → charcoal), `ember`
  (deep red → black), `fog` (slate → black). Plus `black` and a user file
  `/sdcard/Android/data/tv.orchard.home/files/wallpaper.jpg|png|webp` (dumbtv pattern).
- Blur = **stack blur in Java on a 240x135 copy, radius 6**. Done once per wallpaper change on a
  background thread; result cached to `getCacheDir()/blur_<hash>.png`. **The 240x135 bitmap is never
  upscaled into a 1920x1080 bitmap** (8 MB ARGB). It is drawn with `canvas.drawBitmap(src, srcRect,
  dstRect, filterPaint)`; the GPU does the bilinear upscale for free. Glass panels sample the same small
  bitmap with a source rect proportional to the panel's screen rect.
- Shelf backdrop [R3.1]: a `RadialGradient` wash from the app's vivid colour, cross-faded by alpha over
  **280 ms**, **120 ms debounce**; the glow behind the card is a pre-blurred banner bitmap. Two blurred
  bitmaps per app, both 240x135 stack blur, built once off-thread when the art is rasterised: the **glow**
  (radius ~5 = `--hero-glow-blur` 40 design px, saturation x1.3 = `--hero-glow-sat`, applied as a
  `ColorMatrix` at blur time) and the optional `hero=blur` **full-bleed** one (radius ~11 =
  `--hero-bl-blur` 90 design px, saturation x1.35). They are NOT one shared bitmap. Banners with a
  near-white average (Netflix, YouTube) skip the banner glow and use a flat disc of the vivid colour at
  L 0.50 instead, so they do not bloom grey. The glass blur is radius 6 on 240x135 = 48 design px
  (`--glass-blur` 48).
- [R3] Glass panel (Liquid Glass stand-in) = the blurred wallpaper slice behind it + fill (`glass_thick`
  for panel/sheet, `dock_fill` white 6.5% for the dock band, `glass_thin` for panel tiles/rows, `glass`
  for buttons/chip) + **1 px gradient rim** (`drawRoundRect` stroke with a `LinearGradient` at 115°:
  white 34% at the top-left corner → 7% from 38% to 70% → 20% at the bottom-right corner) + **refraction
  band** (white 12% → 0 over the top 28 px) + inner gradient white 10% → 0 over the top 45% + 1 px top
  highlight white 28% + drop shadow (y 24, blur 60, black 35%; one pre-rendered 9-patch-style bitmap).
  No shader beyond `LinearGradient`/`RadialGradient`/`BitmapShader`, no RenderEffect, no backdrop blur.
- [R3.2] Device notes for Phases 3/4 (the mockup uses CSS that has no direct View equivalent; do **not** reach for `saveLayer`, `RenderEffect` or `backdrop-filter`):
  - The shelf's bottom fade (a CSS `mask-image`) = bake the fade into the wash bitmap, or `ComposeShader(RadialGradient, LinearGradient, PorterDuff.Mode.DST_IN)`.
  - The elliptical radial wash (62% x 110%) = `RadialGradient` + `setLocalMatrix(scale(1, ry/rx))`; Android's `RadialGradient` is circular.
  - Every `filter: blur()` in the mockup sits on a static image and stands for a pre-blurred 240x135 bitmap. Every animation is transform or opacity (plus colour on buttons and rows).
  - Panel, sheet and dock band all use the same slice: wallpaper bitmap source rect = the element's screen rect, blurred copy, then the fill.
- [R3] Wallpaper vignette baked into the wallpaper bitmap by `make_wallpapers.py`: radial black 40% at the
  corners (clear inside 45% of a 130%x100% ellipse at 50%/40%) plus black 45% → 0 over the bottom 45%.
- [R3] Focus/press/sway/label are the only per-frame animations; every blur, rim, glow and shadow is a
  bitmap or gradient prepared once. Costly on this hardware and therefore **not** used: live blur of any
  kind, per-tile glows (focused tile only), hardware layers, `elevation`.

### 0.9 Quick Panel (Menu key)

- [R3.1] While the panel is open the shelf card, glow, title and subtitle are hidden (200 ms fade), so the glass samples only the wallpaper.
- **Menu key rule (coexists with Home on Fire, verified against its source):** HoF does not consume
  Menu DOWN; after 500 ms it opens its own config screen and swallows the repeats and the UP. Orchard
  therefore opens the panel **only on `ACTION_UP`**, and only if `!event.isCanceled()`, no repeat DOWN
  was seen for this press, and `upTime − downTime < 450 ms`. A DOWN that never gets its UP is forgotten.
  Result: short Menu = Quick Panel; long Menu = HoF's screen; never both.
- [R3] Slides down from the status strip: **260 ms on `PathInterpolator(0.22, 1.1, 0.36, 1)`** in, 200 ms
  accelerate out. Thick glass sheet (§ 0.8), **compact and centred: x 460..1460 (1000 wide), y 60..376
  (316 tall), radius 32**. Under the tiles one centred Caption 2 line, baseline y 356, white 42% with the
  key names at 62%: `Menu or Back to close · hold Home for Amazon's menu`.
- [R3.2] Panel labels use `text-wrap: balance` in the mockup; on device set the two-line breaks by hand (`Return to\nFire TV`, `Fire TV\nSettings`). Icons sit at a fixed top (`--panel-icon-top` 44 px inside the tile) so all four share one baseline; labels hang below.
- Tiles 200x200, radius 28, gap 32, **top y 96, from x=512** (glass_thin + gradient rim; focused = white
  fill, dark text, 1.08x, rim off): **1 Settings, 2 Return to Fire TV, 3 Screensaver, 4 Fire TV Settings**. A fifth "Sleep" tile is nice-to-have (an app cannot put the Stick to sleep
  without a system permission; the tile would only show the hint "hold Home for Amazon's menu").
  Focus lands on tile 1; the reset tile is one Right away.
- Tile 2 states: `Return to Fire TV` (normal) · `Turn Home redirect back on (goes to: <last target>)`
  when the HoF entry is missing, where `<last target>` is "Orchard" after G6a and "Projectivy" before ·
  `Return to Fire TV (needs adb grant)` when `!hasGrant()`.
- Back or Menu closes it. It never auto-dismisses.

### 0.10 Reset confirm sheet

- [R3.1] While the sheet is open the shelf card, glow, title and subtitle are hidden (200 ms fade), same as the panel, so no sharp art reads through the glass. **[R3.2]** The dock and grid tiles sit under the sheet too, so the sheet and the panel carry their own **pre-blurred wallpaper slice** (the same construction as the dock band, § 0.8) under the `glass_thick` fill: the slice is opaque, so no tile art can read through.

- Centre sheet 880x420, thick glass, title Headline 38 "Return to Fire TV home?", body Body 29 at 62%:
  "Home will open Amazon's home screen until you switch Orchard back on (Apps row → Orchard → Options)."
  Then a Caption 1 line at 42%: "Keeps: disabled Amazon apps, AdGuard DNS, update block, Projectivy,
  Home on Fire, Orchard. For a fully stock Stick run `launcher.sh factory-stock` on the Mac."
- Two buttons: **Cancel** (focused by default) and **Return to Fire TV**. Buttons 360x88, radius 44,
  glass with gradient rim; focused = white fill, dark text, 1.04x. [R3] Sheet radius **36**, scales in from
  0.94 on the focus spring curve over a 72% black veil.
- If HoF is still listed 2 s after the write (§ 2.1 step 3), the sheet's body changes to
  "Could not switch the redirect off. Hold Home on the remote for Amazon's home screen." with one OK button.

### 0.11 Screensaver and sounds

- **Superseded by Rev 5 (§ 8.1 to 8.3).** Old text: `ClockDream` (tv-vibehack idea, rewritten): clock Title 1 76 Medium drifting to a random spot every
  60 s, soft radial glow cycling 5 colours over 8 s, `setScreenBright(false)`.
- Sounds: none in v1. (Nice-to-have: original 40 ms focus tick via `SoundPool`, off by default.)

### 0.12 Remote mapping

| Key | Home screen | Panel / sheet |
|---|---|---|
| D-pad | spatial focus; edges bump, no wrap | between tiles / buttons |
| Select | press dip + launch | activate |
| Long Select (600 ms) | tile context menu (Phase 5) | — |
| Back | close panel → scroll to top + focus dock 1 → **consumed** (never finishes the activity) | close |
| Menu | **short press, on UP** (§ 0.9): open Quick Panel. Long press belongs to HoF. | close (same rule) |
| Home | handled by Fire OS + HoF; we never see it | — |

---

## 1. Architecture

### 1.1 Decisions

| Decision | Choice | Why |
|---|---|---|
| UI toolkit | **Plain Android Views, custom `TileView` with one `onDraw`** | 2 GB Stick; Compose adds ~8 MB APK, a runtime and startup cost; the only animated properties are GPU-composited transforms. dumbtv proves 60 MB PSS with this approach. |
| Libraries | **None** (no AndroidX, no Kotlin) | Keeps the build at `javac + d8` (seconds, no Gradle download). Springs are 40 lines of `Choreographer` code. ~25 apps do not need RecyclerView. |
| minSdk / targetSdk / compile | **28 / 30 / android-30** | targetSdk 30 = the device; compiling against the 30 jar makes newer APIs a compile error. `<queries>` is required at targetSdk 30. |
| Build | **dumbtv-style direct tools** (`aapt2`, `javac --release 8`, `d8`, `zipalign -p 4`, `apksigner`) plus **host-JVM unit tests** for the pure-Java reset helpers | See 1.4. Fallback only if a library becomes unavoidable: tv-vibehack's Gradle wrapper (not system Gradle 9.6). |
| Home takeover | **None.** HoF redirects Home to us; we declare `HOME` + `LEANBACK_LAUNCHER`, **unless R0 (§ 2.4) shows that declaring HOME changes the Stick's home resolution**, in which case the HOME filter is dropped (HoF only adds `CATEGORY_HOME` when the target declares it; nothing else depends on it). | Fire OS ignores default-home; disabling `com.amazon.tv.launcher` is unsafe. |
| Permissions | **`WRITE_SECURE_SETTINGS` only** (granted once by adb) | Needed for the in-launcher reset. [Rev 4: plus `INTERNET`, a normal permission with no grant, for the one weather call; see § 7.] No location, usage stats, TV listings, notification access. |
| Config, two files | **Settings:** `key=value` at `/sdcard/Android/data/tv.orchard.home/files/orchard.conf` (fallback `getFilesDir()/orchard.conf`), atomic write, mtime signature: pullable, hand-editable, diffable over adb (dumbtv idea). **Runtime state:** `getFilesDir()/state.conf` (`safe_mode`, `boot_attempts`, `last_home_launch_at`, `last_crash`) and `getFilesDir()/crashes`: internal storage, fast to write from a dying process, never hand-edited. **Finding (P4, 2026-10-19): on the Stick (SDK 30) adb cannot read or write `/sdcard/Android/data/tv.orchard.home/` (`drwxrws--- u0_a121:ext_data_rw`; the shell user is not in `ext_data_rw`), so no flag can be set by editing the file.** Debug flags (`debug_crash`, `debug_slow_anim`, `debug_card`, `debug_motion`) are set through an `am start` channel instead: `--es orchard.debug "debug_card=banner;debug_motion=reduced"` writes only `debug_*` keys into Orchard's own `orchard.conf`, and `--ez orchard.debug_clear true` removes them (`DebugFlags.java`, host-tested). `crashguard_strip` is never accepted this way (it is armed only at G6a, from a path the manager controls). `launcher.sh status` prints "flags unreadable" because it cannot cat the file. | |
| Licence | **MIT** for Orchard. Code adapted from tv-vibehack and dumbtv (both MIT) keeps their full copyright + permission notices in `launcher/THIRD_PARTY_NOTICES.txt` (also packaged as `assets/NOTICES.txt` and shown under Settings → About). **Zero code or resources from Home on Fire (GPLv3).** Workers receive only § 1.5 (the integration contract); never the HoF source, and never `research/repo-mining.md`'s line references into GPL code. | |

### 1.2 File map

```
detox/
  backup/orchard-pre.txt          Phase 2: device snapshot before the first Orchard install (§ 2.3)
  launcher/
    PLAN.md                      this file
    THIRD_PARTY_NOTICES.txt      full MIT texts (tv-vibehack: Copyright (c) 2026 Gathin;
                                 dumbtv: Copyright (c) 2026 dumbtv contributors) + Inter OFL
    research/*.md                dossier (verbatim)
    mockup/                      Phase 0: index.html, tokens.css, mock.js, wall/*.webp, banners/*.png
    tools/make_wallpapers.py     Phase 0: generates app/res/drawable-nodpi/wall_*.webp
    tools/subset_fonts.sh        Phase 1: Inter → Latin subset TTFs (+ RFN check)
    shots/                       on-device screenshots (git-ignored)
    app/
      build.sh                   Phase 1 (from dumbtv/launcher/build.sh, adapted; runs test/ first)
      version.env                VERSION_CODE / VERSION_NAME
      release.keystore, .keypass git-ignored; created once by build.sh
      AndroidManifest.xml
      assets/NOTICES.txt         copy of THIRD_PARTY_NOTICES.txt (build.sh copies it)
      res/
        values/{strings,styles,colors,dimens}.xml
        font/inter_regular.ttf inter_medium.ttf inter_semibold.ttf + OFL.txt
        drawable-nodpi/wall_dusk.webp wall_ember.webp wall_fog.webp banner.png icon.png
      test/                      host-JVM tests, plain JUnit-free main() asserts, run by build.sh
        AccessibilityListTest.java  ConfigTest.java  CrashWindowTest.java
      src/tv/orchard/home/
        OrchardApp.java           Application: installs CrashGuard, holds ArtCache; boot_attempts++ is HomeActivity.onCreate line 1
        HomeActivity.java        root screen, key routing, scroll, focus memory, panel host, first-frame clear
        SafeModeView.java        plain black fallback screen (Phase 2 UI, reused by CrashGuard)
        FireTvBridge.java        Android side: read/write secure setting, poll AccessibilityManager, launch Amazon home, grant check
        AccessibilityList.java   PURE JAVA: strip/append HoF ids in a ':'-separated list (host-tested)
        CrashGuard.java          uncaught handler + boot-attempt marker, crash window (pure-Java part host-tested)
        Config.java              settings file: parse, atomic write, signature refresh (pure-Java part host-tested)
        State.java               runtime state file in getFilesDir() (same parser as Config)
        AppCatalog.java          enumerate apps, package receiver, dock/hidden/order
        AppEntry.java            model (component, label, banner/icon drawable refs, lastOpened)
        ArtCache.java            background rasterise, LruCache, WebP disk cache, vivid colour
        TileView.java            one-onDraw tile: art via BitmapShader, gloss, label, sway, press dip
        TileRow.java             horizontal LinearLayout of TileViews: shared shadow in dispatchDraw,
                                 focused-last drawing order, edge-bump, nearest()
        TopShelfView.java        hero band: colour-wash cross-fade, sharp banner, title, subtitle
        StatusStrip.java         `≡ Options` hint, status chip, clock/date
        Wallpaper.java           load bundled/user wallpaper, downscale, blur, disk cache
        Blur.java                stack blur (int[] in place)
        QuickPanel.java          glass sheet with 4–5 tiles (Phase 5; Phase 2 ships a plain list)
        ConfirmSheet.java        two-button glass sheet
        SettingsActivity.java    list UI (Phase 5)
        ClockDream.java          DreamService screensaver (Phase 5)
        Ui.java                  s-scale, px(), fonts, every constant from Section 0 (created in 1.2, owned by 1.2 then 3.5)
  scripts/
    tv.sh                        (exists; honours TV_HOST / TV_PORT)
    undo.sh                      (exists; `home` gets the strip-not-delete fix, new `orchard` layer, `all` includes it)
    launcher.sh                  NEW Phase 1: build|test|push|install|grant|snapshot|status|
                                 reset-to-stock|restore-home|factory-stock|shot|key|perf|uninstall
```

Phase 1 task 1.2 creates **every** `src/` file above as a compiling stub (class, public method
signatures, config key names as `static final String` constants in `Config.Keys`), so later tasks fill
bodies in their own files and never add a file another task also needs.

### 1.3 What is taken from where

| From | File | How it is used |
|---|---|---|
| dumbtv (MIT) | `launcher/build.sh` | Structure of `app/build.sh`: same tool chain, `--target-sdk-version 30`, android-30 jar, own keystore with random password in `.keypass`. Full MIT notice in `THIRD_PARTY_NOTICES.txt` + a one-line pointer comment at the top of the file. |
| dumbtv | `launcher/install.sh` `set_guard()` | Logic of strip-keep-others + clear-then-write + `sleep 1` for `scripts/launcher.sh reset-to-stock`/`restore-home`. Rewritten for `tv.sh`. |
| dumbtv | `src/.../Config.java` | Pattern (signature refresh, atomic rename, unknown keys kept). Rewrite, do not copy wholesale; notice as above. |
| dumbtv | `src/.../Catalog.java`, `Home.java` package receiver + art executor | Pattern for `AppCatalog` + `ArtCache`: leanback first, launcher second pass, dedupe by package, `PACKAGE_ADDED/REMOVED/CHANGED` receiver, single-thread executor. |
| dumbtv | `Home.java` `nearest()` focusSearch override, `onTrimMemory` reflection trick | `TileRow.nearest()`; the `WindowManagerGlobal.trimMemory` reflection is **tested in Phase 3** and kept only if it works on API 30. |
| dumbtv | `docs/GOTCHAS.md` | `LEANBACK_SETTINGS` intent for "Fire TV Settings" tile; never `ACTION_SETTINGS`. |
| tv-vibehack (MIT) | `AppAdapter.vividColor` | Same 12x12 sample + saturation score for the fallback gradient and the shelf wash. |
| tv-vibehack | `MainActivity.onTileVerticalKey`, `lastFocusedKey` restore, edge-key swallowing | Focus logic in `HomeActivity`/`TileRow`. |
| tv-vibehack | `GlassView.java` paints (`#3DFFFFFF→#12FFFFFF` fill, `#66FFFFFF` specular, 1.5 dp rim) | Reused for Quick Panel tile focus platter (Phase 5, nice-to-have). Not used for app tiles (Apple shows lift, not a slab). |
| tv-vibehack | `ClockDream.java`, `dream_clock.xml` | Base for our `ClockDream` (rewritten with Inter and Section 0.11 numbers). |
| tv-vibehack | `build-and-install.sh` `cmd package compile -m speed -f` | In `launcher.sh install` (named in gate G2a). **Never** its `pm disable-user` / `set-home-activity` lines. |
| tv-vibehack | `tools/tv.py`, `tools/b64push.py` | Transport for every TV command and the APK push. |
| Home on Fire (GPLv3) | nothing | Integration only, § 1.5. Workers never see its source. |

### 1.4 Toolchain: exact commands (Phase 1, task 1.1)

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | /opt/homebrew/bin/sdkmanager --licenses >/dev/null
/opt/homebrew/bin/sdkmanager "platforms;android-30"          # ~60 MB download, one-time
ls "$ANDROID_HOME/platforms/android-30/android.jar" "$ANDROID_HOME/build-tools/34.0.0/aapt2"
"$JAVA_HOME/bin/javac" -version                               # javac 21.0.11
```

`app/build.sh` (adapted from dumbtv) must: **(0) host tests:** `javac -d build/test test/*.java
src/tv/orchard/home/{AccessibilityList,Config,CrashGuard}.java` (the pure-Java parts; these three files
must not import `android.*`; Android-touching code lives in `FireTvBridge`/`State`/`OrchardApp`) then
`java -cp build/test` each test class, abort on failure → `aapt2 compile --dir res` → `aapt2 link -I
android-30.jar --min-sdk-version 28 --target-sdk-version 30 --version-code $VERSION_CODE --version-name
$VERSION_NAME -A assets` → `javac --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8` → `d8
--release --min-api 28 --lib android-30.jar` → `zip -X classes.dex` → `zipalign -f -p 4` → `apksigner
sign` (v2+v3) → `apksigner verify` → print size and `aapt2 dump permissions` (must list exactly two
`uses-permission` entries: `android.permission.WRITE_SECURE_SETTINGS` and `android.permission.INTERNET`). Output: `launcher/app/build/orchard.apk`. Target size ≤ 1.6 MB since Rev 6 (was 1.2 MB; the 20 sky webps plus glow and rain tiles are ~1.2 MB of it; fonts + 3
wallpapers), ≤ 400 KB code. `build.sh` takes an optional `--out-dir` so git worktrees do not share `build/`.

### 1.5 Home on Fire integration contract (the only HoF material workers get)

1. Manifest: `HomeActivity` exported, `launchMode=singleTask`, `stateNotNeeded`, `clearTaskOnLaunch`,
   `resumeWhilePausing`, `excludeFromRecents`, `screenOrientation=landscape`, both intent filters
   (`MAIN+HOME+DEFAULT`, `MAIN+LEANBACK_LAUNCHER`), `android:banner` 320x180 set on the activity.
   Theme: `Theme.Material.NoActionBar.Fullscreen`, `windowBackground=#0B0B10`, `windowAnimationStyle=@null`.
   The HOME filter is removed if R0 fails (§ 1.1).
2. `onNewIntent` + `onResume` must restore state cheaply: HoF brings the running instance forward with
   `FLAG_ACTIVITY_NEW_TASK` (and `CATEGORY_HOME` when we declare it).
3. `<queries>`: intents `MAIN+LEANBACK_LAUNCHER` and `MAIN+LAUNCHER`, plus packages
   `com.amazon.tv.launcher` and `io.github.toolicious.homeonfire` (so `getLaunchIntentForPackage`
   and "is HoF installed" work under package visibility).
4. The user picks Orchard as HoF's target **once**, in HoF's own UI (Phase 6 gate). Nothing else can.
5. We never disable HoF's long-press/double-press escapes and never touch `com.amazon.tv.launcher`.
6. Public facts we rely on: HoF's accessibility service id has two spellings (`HOF_IDS` in § 2.1); HoF
   launches the target with `CATEGORY_HOME` only if the target declares it; HoF opens its own screen on a
   **500 ms** Menu long-press and swallows that press's repeats and UP; HoF re-launches its target whenever
   Amazon's home comes to the front while the service is bound; HoF's "Replace Home" toggle is a private
   preference we cannot read. Every Orchard launch that arrives with `CATEGORY_HOME` writes
   `last_home_launch_at=now` to `state.conf`.

---

## 2. Reset-to-Fire-TV design (delivered and tested in Phase 2)

Four layers. Each one works without the others. Layers A–C answer Q0(a); layer D answers Q0(b).

### 2.1 Layer A: in-launcher "Return to Fire TV" (needs the one-time `pm grant`)

Pure-Java part, `AccessibilityList` (host-tested, § 1.4 step 0):

```
HOF_IDS = { "io.github.toolicious.homeonfire/.HijackService",
            "io.github.toolicious.homeonfire/io.github.toolicious.homeonfire.HijackService" }

strip(list):   split ':' → trim → drop "", "null", HOF_IDS (both forms) and duplicates → join ':'
               null input or the literal "null" → ""; other services and their order are kept
append(list):  strip(list) then append HOF_IDS[0] (never twice); "" → HOF_IDS[0]
contains(list): any HOF_IDS present after the same normalisation
```

Android part, `FireTvBridge`:

```
hasGrant():     checkSelfPermission(WRITE_SECURE_SETTINGS) == GRANTED
redirectOn():   AccessibilityList.contains(Settings.Secure enabled_accessibility_services)
hofInstalled(): pm.getPackageInfo("io.github.toolicious.homeonfire") succeeds
redirectDoubtful(): redirectOn() && state.last_home_launch_at < boot time && a Home press was seen
                    since boot (HomeActivity.onUserLeaveHint followed by our own return without
                    CATEGORY_HOME) → chip "Redirect may be off in Home on Fire" (HoF's private
                    "Replace Home" toggle cannot be read; this is the best signal we have)

returnToFireTv():
  1. putString(enabled_accessibility_services, AccessibilityList.strip(current))  ("" when empty; never delete the key)
  2. leave accessibility_enabled untouched; state.set(redirect_off_at, now)
  3. WAIT for the unbind: poll AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)
     every 100 ms, up to 2 s, until no entry's id is in HOF_IDS. (putString returns before the system
     unbinds HoF; launching Amazon home while HoF is still bound makes HoF redirect straight back.)
  4. if still listed after 2 s: show the "Hold Home on the remote" sheet (§ 0.10) and stop
  5. startActivity(Intent(ACTION_MAIN).addCategory(CATEGORY_HOME).setPackage("com.amazon.tv.launcher").addFlags(NEW_TASK))
  6. finish nothing; HomeActivity stays alive but behind (singleTask, excludeFromRecents)

switchBackOn():
  1. putString(enabled_accessibility_services, AccessibilityList.append(current)); putInt(accessibility_enabled, 1)
  2. state.set(redirect_off_at, "")
  3. The next Home press lands on HoF's *current* target (Projectivy until G6a, Orchard after); the UI says so.
```

Where it is visible (all must exist by the end of Phase 5; Phase 2 ships the plain versions):
- Status strip, always: `≡ Options`. Quick Panel tile 2 (one Right press after Menu) → confirm sheet
  (Cancel focused) → `returnToFireTv()`.
- Last grid tile **Fire TV Home** → the same confirm sheet (setting `firetv_tile`, default on).
- Settings → row "Return to Fire TV home" → same sheet.
- Status chip top-left when `!redirectOn()`: `Fire TV home active`; the Quick Panel's tile 2 becomes
  **Turn Home redirect back on (goes to: Projectivy|Orchard)** → `switchBackOn()`.
- When `!hasGrant()`: the tile and the rows still exist; selecting them shows a sheet with the exact
  command `scripts/launcher.sh grant` and the line "Or hold Home on the remote for Amazon's home screen."
  SafeModeView's row does the same.
- The confirm sheet always states what it does **not** undo and names `launcher.sh factory-stock` (§ 0.10).

### 2.2 Layer B: crash failsafe (works if Orchard crashes, hangs, or dies natively)

Two detectors, one policy.

- **Boot-attempt marker** (catches native crashes, ANRs, black screens, "keeps stopping" kills, crashes
  before any handler exists): the first statement of `HomeActivity.onCreate()` does `state.boot_attempts++` (not the Application: dream binds and receiver-only process starts are not launches; Application-stage crashes are still caught by the crashes-file window) (sync write to
  `getFilesDir()/state.conf`). `HomeActivity` clears it to 0 after the first real frame: a
  `ViewTreeObserver.OnDrawListener` that removes itself and `post()`s the write. A hang or black screen
  therefore keeps the counter rising on the next launch.
- **Uncaught-exception handler** (`CrashGuard`, installed right after the marker): appends
  `System.currentTimeMillis()` to `getFilesDir()/crashes` (keeps the last 10), writes
  `state.last_crash=<class>`, then chains to the previous handler (the process dies).
  `CrashGuard.window(timestamps, now)` (pure Java, host-tested) returns true when ≥ 3 fall within 60 s.
- **Policy, evaluated at the next launch and inside the handler:** `looping = boot_attempts >= 3 ||
  window(...)`.
  - Always: `state.safe_mode=1` → `HomeActivity` shows `SafeModeView` instead of the home.
  - Strip HoF (`returnToFireTv()`) **only if all three hold**: `hasGrant()`, `orchard.conf` has
    `crashguard_strip=on` (the manager sets it at G6a; before that Orchard is not the Home target, so a
    buggy dev build must never switch the user's Projectivy redirect off), and the launch that is
    crashing/looping arrived with `CATEGORY_HOME` (an `am start` from the Mac does not; a real Home press
    through HoF does).
- `SafeModeView`: black screen, three rows: **Try Orchard again** (clears `safe_mode` and
  `boot_attempts`), **Return to Fire TV** (Layer A; shows the grant-missing instructions when
  `!hasGrant()`), **Reset Orchard settings** (deletes `orchard.conf`, clears flags). Plus a Caption line
  with `last_crash`.
- Debug hook for the test: `am start -n tv.orchard.home/.HomeActivity --ez orchard.crash true` throws a
  `RuntimeException("test crash")` after `onCreate`, and `--ez orchard.hang true` blocks the main thread
  before the first frame. **Honoured only when `orchard.conf` contains `debug_crash=1`** (the activity is
  exported; without the flag any app on the Stick could crash Orchard three times). The manager writes the
  flag with `tv.sh 'am start -n tv.orchard.home/.HomeActivity --es orchard.debug debug_crash=1'` for R6
  (adb cannot write `/sdcard/Android/data/tv.orchard.home` on SDK 30; see 1.x config row) and removes it
  afterwards with `--ez orchard.debug_clear true`. `launcher.sh status` cannot read the file on this Stick
  and prints "flags unreadable" (it still warns when it can read a leftover flag).
- Outside our process, unchanged: HoF's **long-press Home**, **double-press Home**, **long-press
  Menu → HoF settings → Replace Home off**. We never alter those.

### 2.3 Layer C: Mac-side undo (`scripts/launcher.sh`, idempotent)

```
snapshot:        write backup/orchard-pre.txt once (refuses to overwrite; `snapshot --force` to redo):
                   settings get secure enabled_accessibility_services / accessibility_enabled /
                   screensaver_components / screensaver_enabled; settings get global animator_duration_scale;
                   dumpsys deviceidle whitelist; cmd package resolve-activity --brief -a android.intent.action.MAIN
                   -c android.intent.category.HOME; pm path of HoF/Projectivy; date
reset-to-stock:  strip HOF_IDS (same rules as AccessibilityList, in sh: keep other services); put "" when empty;
                 sleep 1; am force-stop tv.orchard.home; am force-stop io.github.toolicious.homeonfire;
                 am start -a android.intent.action.MAIN -c android.intent.category.HOME -p com.amazon.tv.launcher;
                 status
restore-home:    enabled_accessibility_services := the value in backup/orchard-pre.txt (falls back to
                 strip + append HOF_IDS[0] if the file is missing); accessibility_enabled 1;
                 am start our LEANBACK activity; status
restore-settings: animator_duration_scale, screensaver_components, screensaver_enabled and the deviceidle
                 whitelist entry back to backup/orchard-pre.txt values (used by Phase 4/5 undo and by factory-stock)
factory-stock:   (USER GATE, typed confirmation "STOCK") reset-to-stock → restore-settings →
                 scripts/undo.sh all (Orchard layer uninstalls Orchard; HoF and Projectivy stay unless the
                 user also says so) → status.  Answers Q0(b).
uninstall:       pm uninstall tv.orchard.home          (USER GATE; never uninstalls HoF/Projectivy by itself)
status:          enabled_accessibility_services, accessibility_enabled, grant state
                 (dumpsys package tv.orchard.home | grep -A1 'runtime permissions' / WRITE_SECURE_SETTINGS),
                 mCurrentFocus, pm path of HoF/Projectivy/Orchard, resolve LEANBACK of tv.orchard.home,
                 resolve HOME (diffed against the snapshot), debug flags present in orchard.conf (warning)
```

`TV_HOST`/`TV_PORT` pass through to `tv.sh`. `scripts/undo.sh` changes: `home` strips HoF's entries
instead of deleting the whole key (still correct for the Projectivy setup); new layer `orchard` =
`launcher.sh reset-to-stock` + `restore-settings` + `pm uninstall tv.orchard.home`; `all` runs `orchard`
first (newest layer first). `undo.sh all` keeps its "never disable Amazon packages" rule. Factory reset
remains the last resort and is documented in README.

### 2.4 Reset test matrix (run on the real TV in Phase 2, repeated in Phase 6)

| # | Action | Expected | Check |
|---|---|---|---|
| R0 | Before and after the G2a install: `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` and a raw `input keyevent 3` + 10 s screenshot | Identical output and the same landing screen (Projectivy) | diff; if it differs, rebuild without the HOME filter (§ 1.1) and repeat |
| R1 | `launcher.sh snapshot` then `launcher.sh status` | `backup/orchard-pre.txt` written; HoF entry present, grant absent | printed |
| R2 | Open Orchard (setup build) via `am start`, Select "Return to Fire TV". **Repeat 5 times** (switch back on between runs) | 5/5: HoF entry gone, Amazon home in front | `tv.sh 'settings get secure enabled_accessibility_services'` = `""` or other services only; screenshot after 10 s shows Amazon home; none of the 5 lands back on Projectivy |
| R3 | `tv.sh 'input keyevent 3'` (Home) | Stays on Amazon home (no redirect) | screenshot after 10 s |
| R4 | Open Orchard from Amazon Apps row (or `am start`), Select "Turn Home redirect back on" | HoF entry back, `accessibility_enabled=1`; the value equals the snapshot | settings get; `dumpsys accessibility | grep -A3 "Enabled services"` |
| R5 | `input keyevent 3` | Home lands on Projectivy (still HoF's target) | screenshot after 10 s |
| R6 | Set `debug_crash=1`; 3x `am start ... --ez orchard.crash true` within 60 s; then 3x `--ez orchard.hang true` with `am force-stop` between | After the 3rd of each: `safe_mode=1`; **HoF entry still present** (flag off, no `CATEGORY_HOME`) | settings get; `tv.sh 'run-as'` is unavailable, so Safe-mode screen on next launch proves the flag |
| R6b | Same as R6 with `crashguard_strip=on` but launched by `am start` (no `CATEGORY_HOME`) | HoF entry still present | settings get |
| R6c | Phase 6 only: `crashguard_strip=on`, Orchard is the target, 3 Home presses each crashing (`debug_crash=1` + a `crash_on_home=1` test flag) | HoF entry stripped, Amazon home in front | settings get; screenshot |
| R7 | `am start` Orchard | Safe-mode screen; "Try Orchard again" clears flags; "Return to Fire TV" row shows the grant text when the grant is absent | screenshot |
| R8 | `launcher.sh reset-to-stock` then `launcher.sh restore-home` | Both idempotent, run twice each; final state = R1 plus grant | status output |
| R9 | User, physical remote: long-press Home while Orchard is in front | Amazon home appears | user confirms |
| R10 | `tv.sh reboot` (USER GATE) | After boot Home → Projectivy; Orchard still installed and granted | status |
| R11 | User, physical remote, Phase 5: short Menu; then hold Menu 1 s | Short: Quick Panel only. Long: HoF's screen only, no panel | user confirms, screenshots |
| R12 | Off-device, every build: host tests in `app/test/` | All pass; mutation check: comment out the "drop duplicates" line in `AccessibilityList.strip`, a test fails, restore it | `build.sh` log |

---

## 3. Phases

Worker contract (every task): the worker reads `PLAN.md` Section 0/1 and the named research files
(never `repo-mining.md`'s HoF sections, never HoF source), touches only the listed files, never runs a
TV command unless the task says "on-device", and returns: files written, commands run, what was
verified, and open questions. Additions to shared files (`AndroidManifest.xml`, `res/values/*.xml`,
`Ui.java`, `Config.Keys`) are returned **as text** for the manager to merge, never written by a parallel
worker. **Each parallel worker builds in its own git worktree** (`git worktree add ../orchard-wt-<task>`)
with `build.sh --out-dir`, so nobody compiles another worker's half-written files; the manager merges
and runs the full `build.sh` once per group. The manager reviews the diff and runs every on-device step
itself with `scripts/tv.sh` / `scripts/launcher.sh`, taking screenshots into `launcher/shots/` (absolute
path: `./shots/`).

Before any on-device step: `scripts/tv.sh 'dumpsys window | grep -m1 mCurrentFocus'`. If a streaming
app is in front, stop and ask the user (the YouTube "playing" flag is always stuck on; ignore it).

Screenshot recipe: `scripts/tv.sh 'screencap -p /sdcard/orchard.png' && scripts/tv.sh --pull /sdcard/orchard.png ./shots/<name>.png`
(`tv.sh` `cd`s into tv-vibehack first, so local paths must be absolute). Wait ~10 s after Home before
shooting; the first frame is black.

Key recipe: each `tv.sh 'input keyevent …'` call costs 0.5–1 s (a new `input` process), so a *sequence*
goes in **one call**: `scripts/tv.sh 'input keyevent 22 22 22 22 22'` (API 30 accepts several keycodes;
they are injected back to back). One key per call only when a screenshot is needed in between. Codes:
HOME 3, BACK 4, DPAD_UP 19, DOWN 20, LEFT 21, RIGHT 22, CENTER 23, MENU 82. Injected Home still shows
Amazon's home window, so HoF redirects it like a real press; HoF's long-press escape and real key
hold/repeat timing need the **physical** remote (user).

Mid-animation screenshots: `orchard.conf` `debug_slow_anim=10` multiplies every Orchard duration by 10
(honoured only with the flag, read once at start). No device setting is touched for this.

Every phase carries **+0.5 day buffer** for focus regressions found on the device.

### Phase 0 — Clickable HTML mockup  (USER GATE G0: approve the look)

**Goal:** the user approves the exact look before any Android code. The mockup is the visual contract;
`tokens.css` numbers are copied verbatim into `Ui.java` and `dimens.xml`.

| Task | Worker | Files | Parallel |
|---|---|---|---|
| 0.1 Wallpapers + placeholder banners | one Sonnet | `launcher/tools/make_wallpapers.py`, outputs `launcher/mockup/wall/{dusk,ember,fog}.webp` (1920x1080) and `launcher/app/res/drawable-nodpi/wall_*.webp`; plus `launcher/mockup/banners/*.png`: **10 banner-sized (320x180) placeholders** with a flat colour, a fake word-mark drawn as text, and a small glyph, i.e. what real app banners look like, not ideal gradients | yes |
| 0.2 Mockup | one Sonnet | `launcher/mockup/index.html`, `tokens.css`, `mock.js` | yes (uses CSS gradients until 0.1 lands) |

0.2 details: a fixed 1920x1080 stage scaled to fit the window (`transform: scale()`); Inter from Google
Fonts for the mockup only; screens: Home (shelf + dock of 6 + 2 grid rows + the last "Fire TV Home" tile,
using the user's real app names from README: Netflix, JioHotstar, SonyLIV, JioTV+, ZEE5, Apple TV,
Spotify, VLC, YouTube, Drivecast, with real banners by default and the 0.1 placeholders via `?art=placeholder`; a fresh clone without the git-ignored `banners-real/` defaults to the placeholders (`mockup/real-art.js` sets `window.ORCHARD_REAL_ART`; it is committed as `false`, and the banner-pull step flips it to `true` locally, so opening from `file://` makes no failing request; `?art=real` forces real art), and any app whose files all fail to load gets the designed icon-only fallback), Quick Panel, Reset
confirm sheet (with the "keeps…" line), Settings list, Safe-mode screen. Keys: arrows = D-pad, Enter =
Select, Escape = Back, **M on keyup** = Menu, R = toggle reduced motion. All Section 0.5 timings as CSS
transitions; the focused label sits under the *scaled* tile. Query switches: `?screen=` opens any
screen; **`?cols=5|6`** (default 6) and **`?clock=on|off`** (default on) so the user decides both at
G0; `?hero=wash|blur` shows the § 0.3 design with and without the full-bleed pre-blurred banner;
[R3] `?art=real|placeholder` (default real) switches between the banners pulled from the Stick
(`mockup/banners-real/`, git-ignored, local only) and the Phase 0.1 placeholders. The status strip
shows `≡ Options`. Rev 3 screenshots: `launcher/shots/premium-*.png`.

**Acceptance:** opens from `file://`, all five screens reachable by keyboard only, focus never lost,
tile scale/shadow/gloss/label match 0.5, the shadow is never clipped and the focused tile is drawn above both
neighbours with its shadow and glow spilling onto them unclipped (at 1.12x a 260 px tile grows 15.6 px per side into a 40 px gutter, so the tile bodies do not touch), reset reachable three ways (Menu + Right; last grid tile; Settings), no console errors.
The manager opens it in a browser and screenshots each screen and both column counts into
`launcher/shots/mock-*.png`.

**Gate G0:** show the user the mockup (open the file, or publish it as a private artifact) and ask:
approve, or list changes; plus the two decisions (5 or 6 columns; clock on or off). Loop until approved.
Record the approved tokens in `PLAN.md` § 0 if changed.

**G0 passed 2026-10-19** on the Rev 3 (premium) mockup. User decisions: **6 columns**, **clock on**,
**grid in the user's own order** (not alphabetical). Follow-ups carried into the build: drop the fake
Downloader/Files demo tiles; hide Projectivy and Home on Fire from the grid by default (Hidden apps);
the Opus review's open polish items (hero card duplicating the focused tile and greying white banners,
empty band above the dock when a grid row is focused) are fixed in Phase 4 (Top Shelf polish).

**Phase 4 (P4) changes pending the user's review:** shelf card default = emblem (app icon on a backlit plate, Ui.HERO_EMBLEM_*), card=banner keeps the Rev 3 banner card without the 0.85 dimming (removed), card=off; grid rows anchor at y 470 (Ui.GRID_ANCHOR_Y, tokens --grid-anchor-y) instead of 600 so the row above stays visible under the strip; strip scrim opaque to 120, clear at 190; new orchard.conf debug flags debug_card and debug_motion; G4 deferred.

**Rollback:** none (no TV). **Estimate:** ~3 h agent time, plus the user's review.

### Phase 1 — Toolchain, skeleton, build pipeline, Mac scripts, host tests  (no TV, no gates)

**Goal:** `launcher/app/build.sh` runs the host tests and produces a signed `orchard.apk` whose only
permission is `WRITE_SECURE_SETTINGS`; `scripts/launcher.sh` exists and is dry-run tested; the reset
code compiles and its pure-Java parts are tested.

| Task | Files | Parallel |
|---|---|---|
| 1.1 Toolchain | runs § 1.4 commands; writes `launcher/tools/env.sh` exporting JAVA_HOME/ANDROID_HOME/BT/JAR | first |
| 1.2 Build + skeleton (**owner of every shared file**) | `app/build.sh` (with test step and `--out-dir`), `app/version.env`, `app/AndroidManifest.xml`, `res/values/*`, `Ui.java` (all § 0 constants, named), `Config.Keys` constants, **compiling stubs for every class in § 1.2** with their public signatures, `OrchardApp.java`, `HomeActivity.java` (shows `SafeModeView` stub for now), `.gitignore` additions (`launcher/app/build/`, `launcher/app/release.keystore`, `launcher/app/.keypass`, `launcher/shots/`) | after 1.1, before 1.3/1.6 |
| 1.3 Reset core | fills `AccessibilityList.java`, `FireTvBridge.java` (with the unbind poll), `CrashGuard.java`, `Config.java`, `State.java`, `SafeModeView.java` (D-pad list with the three rows + status lines: grant, redirect, HoF installed, version) | after 1.2 (own worktree) |
| 1.4 Fonts + notices | `launcher/tools/subset_fonts.sh` → `res/font/inter_*.ttf` + `OFL.txt` (download Inter from github.com/rsms/inter releases; **check the OFL for a Reserved Font Name, rename if present**; fallback Outfit); `launcher/THIRD_PARTY_NOTICES.txt` with the two full MIT notices + OFL; `build.sh` copies it to `assets/NOTICES.txt` | parallel |
| 1.5a Mac script, reset half | `scripts/launcher.sh` subcommands `snapshot`, `status`, `reset-to-stock`, `restore-home`, `restore-settings`, `factory-stock` (gated), `uninstall` (gated); `scripts/undo.sh` `home` fix + `orchard` layer + `all` | parallel |
| 1.5b Mac script, build half | `launcher.sh` `build`, `test`, `push` (b64push), `install` (`pm install -r`, `cmd package compile -m speed -f`, `dumpsys deviceidle whitelist +`), `grant` (prints and runs `pm grant`), `shot`, `key` (multi-keycode), `perf` (`gfxinfo reset` → keys → `gfxinfo`, `meminfo`) | parallel; manager merges the two halves into one file |
| 1.6 Host tests | `app/test/AccessibilityListTest.java` (empty, null, `"null"`, both id forms, duplicates, trailing `:`, other services kept and ordered, append never doubles), `ConfigTest.java` (parse, unknown keys kept, atomic write to a temp dir, signature refresh), `CrashWindowTest.java` (2 in 60 s → false, 3 in 60 s → true, 3 across 61 s → false, keeps last 10). Then the **mutation check**: break one line in each helper, confirm a failing test, restore. | after 1.3 |

**Acceptance:** `launcher/app/build.sh` exits 0 in < 45 s with the test step printing 3x PASS and the
mutation log recorded in `launcher/TESTLOG.md`; `apksigner verify` ok; `aapt2 dump permissions
build/orchard.apk` shows exactly `android.permission.WRITE_SECURE_SETTINGS`; `aapt2 dump badging` shows
`launchable-activity` with both categories and `targetSdkVersion:'30'`; APK ≤ 1.6 MB (raised from 1.2 MB in Rev 6 for the sky assets);
`scripts/launcher.sh status` runs read-only against the TV and prints its lines (the manager may run
this: read-only). `launcher.sh factory-stock` and `uninstall` refuse without the typed confirmation
(tested with an empty stdin). Keystore created and the user told to back it up
(`launcher/app/release.keystore` + `.keypass`; without them, updates need an uninstall).

**Rollback:** delete `launcher/app/build/`. **Estimate:** ~5 h.

### Phase 2 — First install + the reset works  (USER GATES G2a install, G2b grant, G2c reset test, G2d reboot)

**Goal:** Orchard (setup build) is on the Stick as a normal app, not yet HoF's target. The reset layers
pass the matrix in § 2.4. Projectivy stays the Home target.

| Task | Files | Parallel |
|---|---|---|
| 2.1 Device facts (on-device, read-only, manager) | `DEVICE.md` (not published): `wm size`, `wm density`, `getprop dalvik.vm.heapgrowthlimit`, `getprop ro.build.version.sdk`, `dumpsys meminfo | head -40`, `pm list packages | grep -E 'homeonfire|spocky'`; **`launcher.sh snapshot`** → `backup/orchard-pre.txt`; R0 "before" half | first |
| 2.2 Install (manager) | `scripts/launcher.sh build && scripts/launcher.sh install` → `pm install -r /data/local/tmp/orchard.apk`, `cmd package compile -m speed -f tv.orchard.home`, `dumpsys deviceidle whitelist +tv.orchard.home`; then R0 "after" half | after G2a |
| 2.3 Grant (manager) | `scripts/launcher.sh grant` → `pm grant tv.orchard.home android.permission.WRITE_SECURE_SETTINGS` | after G2b |
| 2.4 Reset matrix R1–R8, R6b (manager, on-device) | screenshots `shots/p2-R*.png`; log in `launcher/TESTLOG.md`; `debug_crash` flag removed at the end | after G2c |
| 2.5 R9 + R10 (user + manager) | user does long-press Home; manager runs `tv.sh reboot` after G2d, waits 60 s, runs `status` | last |
| 2.6 factory-stock dry run (always; Q0 = a but the user wants it proven) | `launcher.sh factory-stock --dry-run` prints every command it would run; the user reads the list | after 2.4 |

**Gates, exact wording for the user:**
- **G2a:** "Install `orchard.apk` (setup build, 1 permission) on the Stick with `pm install -r`, mark it
  compiled (`cmd package compile -m speed`) and exempt it from Doze (`deviceidle whitelist`)? This does
  not change Home; I snapshot the home-app resolution and accessibility settings first
  (`backup/orchard-pre.txt`) and compare after. Also OK to re-install updates of `tv.orchard.home` for the
  rest of this project without asking each time?"
- **G2b:** "Run `pm grant tv.orchard.home android.permission.WRITE_SECURE_SETTINGS`? Needed for the in-launcher reset button."
- **G2c:** "Run the reset test now? For a few minutes the Home button will go to Amazon's home and back to Projectivy, five times."
- **G2d:** "Reboot the Stick to confirm the setup survives a restart?"

**Acceptance:** every row of § 2.4 (except R6c, R11) passes and is logged with the command, output, and
screenshot name. `settings get secure enabled_accessibility_services` ends exactly as the snapshot.
R0 before/after identical (or the HOME filter was dropped and R0 repeated). Cold start of the setup
build via `am start -W` reports `TotalTime` < 800 ms.

**Rollback:** `scripts/launcher.sh restore-home` (puts HoF back from the snapshot), then
`scripts/launcher.sh uninstall` (gate). Projectivy is untouched throughout. **Estimate:** ~3 h including gates.

### Phase 3 — Home screen core  (gate G3 only if the grant was lost on reinstall)

**Goal:** the real home: catalogue, dock, grid rows, tiles with full focus effect, wallpaper + blur,
status strip, launching apps, focus memory, Back/Menu handling (Menu opens the plain Phase 2 list for now).

| Task | Files | Parallel group |
|---|---|---|
| 3.1 Catalogue + art | `AppCatalog.java`, `AppEntry.java`, `ArtCache.java` (325x195 RGB_565 tile art + 640x360 shelf art drawn at 560x315, its 240x135 glow bitmap and 240x135 full-bleed bitmap, vivid colour, near-white flag); reads sizes from `Ui` constants already defined in 1.2 | A (own worktree) |
| 3.2 Tile + row | `TileView.java` (BitmapShader round rect, gloss, label under the scaled tile, press dip), `TileRow.java` (shared shadow in `dispatchDraw`, focused-last drawing order, edge-bump, `nearest()`) | A (own worktree) |
| 3.3 Wallpaper + blur + strip | `Wallpaper.java`, `Blur.java` (240x135, drawn scaled, never upscaled into a bitmap), `StatusStrip.java` (`≡ Options`, chip, clock) | A (own worktree) |
| 3.4a Home assembly, layout | `HomeActivity.java`: ScrollView, dock band, rows from catalogue + "Fire TV Home" tile, scroll rule § 0.3, wallpaper binding, first-frame `boot_attempts` clear | B, after A merged |
| 3.4b Home assembly, input | `HomeActivity.java`: key routing § 0.12 incl. the Menu UP rule, `lastFocusedKey` restore, launch with press dip, `onTrimMemory` release, `onStop`/`onPause` art/backdrop/sway release, `CATEGORY_HOME` → `last_home_launch_at` | B, after 3.4a |
| 3.5 On-device tuning (manager + one worker for fixes) | `Ui.java` numbers; `launcher/TESTLOG.md` | C |

Group A tasks never touch each other's files, nor `Ui.java`/manifest/strings (additions come back as
text); each must compile on its own via `build.sh` in its worktree (stub callers from 1.2 already exist).

**On-device verification (manager):**
1. `scripts/launcher.sh install` then `launcher.sh status` (confirm grant survived `install -r`;
   if not → **G3** re-grant).
2. `scripts/tv.sh 'am start -W -n tv.orchard.home/.HomeActivity'` → `TotalTime` < 1000 ms cold,
   < 300 ms warm.
3. Screenshot `p3-home.png` after 3 s: dock of 6, one grid row, `≡ Options`, clock (if on), wallpaper,
   no label on unfocused tiles, focused tile visibly larger with shadow and in front of both neighbours.
4. D-pad sweep in single calls: `input keyevent 22 22 22 22 22`, `21 21 21 21 21`, `20 22 22 22`, `19`,
   `20 20` → screenshot after each call; focus never disappears; edge presses do not wrap.
5. Mid-animation check: set `debug_slow_anim=10`, press RIGHT, screenshot at ~1 s: shadow not clipped
   at the tile's bounds, no neighbour drawn over the scaled tile. Remove the flag.
6. Select on the first dock tile → the app opens (`dumpsys window | grep mCurrentFocus` shows it);
   `input keyevent 3` → Projectivy (still the target; expected). `am start` Orchard again → the same
   tile is focused (focus memory).
7. Perf, two ways. Injected: `gfxinfo reset`, `input keyevent 22 22 22 22 22 21 21 21 21 21 20 20 19 19`
   x2, `gfxinfo | grep -E "Janky|90th|99th"` → Janky < 10%, 99th < 32 ms. **User, physical remote:**
   hold RIGHT 3 s, then DOWN 3 s; focus stays visible and the scroll keeps up (user says yes/no); the
   manager runs `gfxinfo` after. `meminfo | grep "TOTAL PSS"` → ≤ 110 MB on screen; after opening
   another app and waiting 30 s → ≤ 45 MB. **P4 measurement: 55.2 MB (Graphics 26.8 MB, not app-held); inside the 60 MB failure line of the risk table, 45 MB stays the goal, to be re-attributed on device.**
8. Idle: leave the home untouched 30 s; `gfxinfo` "Total frames rendered" read at 30 s and 40 s must be
   equal (sway stopped, no per-frame redraw).
9. Back on the home: Orchard stays in front (`mCurrentFocus` unchanged).

**Acceptance:** all 9 checks logged; the `trimMemory` reflection either proven (PSS drop) or removed.
**Rollback:** `launcher.sh restore-home` is unaffected; a broken build is simply re-installed over.
**Estimate:** 2.5–3 days + 0.5 buffer.

### Phase 4 — Top Shelf and motion polish  (USER GATE G4: animator-scale toggle)

**Goal:** the hero band and the last 20% of the feel.

| Task | Files | Order |
|---|---|---|
| 4.1 Top Shelf | `TopShelfView.java` (radial colour wash cross-fade, banner drawn sharp at 560x315 with its pre-blurred glow bitmap (flat vivid disc for near-white banners), contact + lift shadow, 1 px specular rim, title, subtitle from `recent.<pkg>`); its `HomeActivity` hook (debounced focus listener) | first (own file in parallel; hook merged first) |
| 4.2 Motion | `TileView.java`: idle sway with the 3-cycle/20 s stop, idle dim, edge bump, reduced-motion switch; `HomeActivity`: scroll animation, label rise | hook second |
| 4.3 Launch log + launch animation | `AppCatalog`: write `recent.<pkg>=<ms>` on launch (key named in `Config.Keys` since 1.2); `ActivityOptions.makeScaleUpAnimation` from the tile bounds (keep only if Fire OS honours it) | hook third |

The three `HomeActivity` hooks are applied **one at a time, 4.1 → 4.2 → 4.3**, each followed by a build.
The non-`HomeActivity` parts run in parallel worktrees.

**On-device:** screenshots `p4-shelf-<app>.png` for 3 dock apps (title, wash colour and banner change);
`p4-row1.png` with the shelf scrolled away; perf re-run (same thresholds; sway must not raise Janky
above 10%); idle check 8 again. **G4:** "Set `animator_duration_scale` to 0 for one screenshot to test
the reduced-motion path, then restore it to the snapshot value (`<value from backup/orchard-pre.txt>`)?"
→ no sway in the screenshot → `launcher.sh restore-settings`. **Rollback:** reinstall previous APK (keep
`launcher/app/build/orchard-p3.apk`). **Estimate:** 1.5 days + 0.5 buffer.

**P4 manager round 2 (2026-10-19 23:00):** on-device pass; fixed emblem-plate banding (dither), banner-mode glow box (transparent margin, premultiplied blur, radius 12), Netflix/YouTube near-white detection. See TESTLOG.

**P4 verification (2026-10-19):** see TESTLOG.md section "Phase 4 - Top Shelf and motion polish (verification ...)". Host PASS (6 tests, mutation check, rule greps, Ui vs tokens). On-device D1-D10 DEFERRED to the manager (worker barred from the TV); G4 deferred (needs `settings put global`; reduced path via `debug_motion=reduced`).

### Phase 5 — Quick Panel, Settings, context menu, screensaver  (gate G5 optional: set ClockDream as screensaver)

| Task | Files | Order |
|---|---|---|
| 5.1 Quick Panel + confirm sheet | `QuickPanel.java`, `ConfirmSheet.java`, glass drawing per § 0.8/0.9; tile 2 states per § 0.9; `HomeActivity` hook: Menu-UP rule → panel, Back | `HomeActivity` hook first |
| 5.2a Settings shell | `SettingsActivity.java`: list framework, toggles: Labels (focus/always), Wallpaper (dusk/ember/fog/black/user file), Motion (full/reduced), Clock (on/off, 12/24), Fire TV tile (on/off), Return to Fire TV (sheet), About (version, MIT, OFL, credits, full `assets/NOTICES.txt` scrollable) | parallel (own worktree); manifest/strings additions returned as text |
| 5.2b Settings lists | Dock apps (pick ≤ 6, order), Hidden apps; both D-pad pickers | after 5.2a |
| 5.2c Grid order (G0 decision) | Manual grid order: long-press Select on a tile → "Move", D-pad moves it, Select drops; order saved as a package list in config; newly installed apps append at the end (before "Fire TV Home"); uninstalled apps drop out. ~2 h | after 5.2b |
| 5.3 Context menu | long-Select on a tile: Add to/Remove from dock, Move left/right (dock), Hide, App info (only if `ACTION_APPLICATION_DETAILS_SETTINGS` resolves on the device: check first, hide the row otherwise); `HomeActivity` hook | `HomeActivity` hook after 5.1; uses 5.2b's keys (named since 1.2) |
| 5.4 ClockDream | `ClockDream.java`; manifest service + strings returned as text, merged by the manager | parallel |

**On-device:** `tv.sh 'cmd package resolve-activity -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:tv.orchard.home'`
first (decides 5.3's App-info row). Menu (single `input keyevent 82`, which injects DOWN+UP within the
450 ms window) → `p5-panel.png`; Menu, RIGHT, CENTER → `p5-confirm.png` (Cancel focused, "keeps…" line
visible); CENTER on Cancel → panel closes; full R2 (x5)/R4 again through the panel and through the
"Fire TV Home" tile; **R11 with the physical remote (user)**; Settings screenshots; dock edit persists
across `am force-stop` + relaunch (`cat orchard.conf`); "Fire TV Settings" tile opens Amazon's settings
(`LEANBACK_SETTINGS`) and Back returns to Orchard. Screensaver: `tv.sh 'cmd package resolve-activity -n
com.android.systemui/.Somnambulator'` first; **G5** asks before `settings put secure
screensaver_components tv.orchard.home/.ClockDream` (snapshot value named in the question); test with
Somnambulator if it resolves, else `dumpsys dreams` after `settings put secure screensaver_activate_on_sleep`;
if Fire OS ignores it, `launcher.sh restore-settings` and mark the feature "not supported on Fire OS 8".
**Estimate:** 1.5 days + 0.5 buffer.

**P5 verification (host):** 9x PASS, APK 269 KB, 1 uses-permission, manifest and rule greps clean, KeyRulesTest mutation caught; no bugs found. On-device V0-V13 are pending the manager. Details: `TESTLOG.md`, section "Phase 5 - Quick Panel, Settings, context menu, screensaver".

**P5 manager round 1 (2026-10-19 23:40):** host audit PASS (9x PASS, 269 KB, mutations caught, tokens match); build installed; App info, LEANBACK_SETTINGS and Somnambulator resolve. All UI checks DEFERRED: Stick asleep (display off). See TESTLOG.

### Phase 6 — Switch-over and soak  (USER GATES G6a target switch, G6b reboot, G6c Projectivy removal — default NO)

**Goal:** Orchard becomes the Home target. Projectivy stays installed as the fallback.

1. Manager: `launcher.sh install` of the release build (`version.env` 1.0.0), `launcher.sh status`,
   full § 2.4 matrix R1–R8 + R6b again on the final build; `launcher.sh perf` numbers logged.
2. **G6a:** "Switch Home on Fire's target from Projectivy to Orchard now? You do it on the remote:
   hold Menu → Home on Fire → Choose target app → Orchard. (Or let me drive it with key events while you
   watch.) I will then arm Orchard's crash failsafe (`crashguard_strip=on` in `orchard.conf`), which
   switches Home back to Amazon's home only if Orchard crashes 3 times in a row from a Home press."
   Only HoF's own UI can change the target.
3. Manager: `input keyevent 3`, wait 10 s, `p6-home.png` shows Orchard; repeat 6 times; open Netflix,
   Hotstar, YouTube, ZEE5 by Select and Home back each time. Then **R6c** (armed failsafe) and R7, then
   `restore-home` and the flags cleaned.
4. **G6b:** reboot; after 90 s, `status` + screenshot shows Orchard. (dumbtv notes ~15 s of
   `FallbackHome` after boot; that is normal.)
5. R9 with the physical remote (long-press Home → Amazon → long-press again → Orchard).
6. Soak: 7 days of normal use. The user reports anything odd; the manager checks
   `launcher.sh perf` and `logcat -d | grep -E "orchard|AndroidRuntime"` once mid-week (read-only).
7. **G6c (default NO):** "Uninstall Projectivy now that Orchard is the home?" Recommended answer: keep it
   until a month has passed.

**Rollback (any time, < 1 min):** on the remote: hold Menu → Home on Fire → target = Projectivy. From the
Mac: `launcher.sh reset-to-stock` (Amazon home) or re-pick Projectivy in HoF. **Estimate:** 0.5 day + soak.

### Phase 7 — Nice-to-have (only after the soak passes)

Must vs nice: everything above is must. Nice, in order: (1) Recent row from the launch log,
(2) config backup/restore with a D-pad picker (`orchard.conf.<ts>` files), (3) day/night wallpaper
switch by clock, (4) original focus tick sound (off by default), (5) Quick Panel glass platter spring
(tv-vibehack `GlassView` numbers, damping 0.72). One worker each, one on-device check each.

**Total:** about 8–9 working days of agent + manager time before the 7-day soak (Phase 0 ~3 h, 1 ~5 h,
2 ~3 h, 3 ≈ 3–3.5 d, 4 ≈ 2 d, 5 ≈ 2 d, 6 ≈ 0.5 d), plus the user's time at 10 gates.

---

## 4. Risks and mitigations

| Risk | Mitigation | Early signal |
|---|---|---|
| **Jank on the Stick** (A55 + GE9215, 2 GB) | Transform-only animations, no hardware layers, blur drawn from a 240x135 bitmap, 325x195 RGB_565 art, no per-frame allocation, sway stops after 20 s; perf thresholds in Phase 3/4; sway is the first thing to cut. | `gfxinfo` Janky > 10%, frame count rising while idle |
| **Focus bugs** (focus lost, wraps, escapes to a hidden view, grid row vs dock jump) | Only `TileView`s and panel buttons are focusable; `TileRow` swallows edge keys; `nearest()` for vertical moves; `lastFocusedKey` restore; every screen has a Back route; the D-pad sweep is run on the device after every phase; +0.5 day buffer per phase. | focus invisible in a screenshot |
| **Shadow clipped / neighbour overlap** | Shadow drawn by the row, focused child drawn last, no layers; mid-animation screenshot with `debug_slow_anim`. | a hard edge on the shadow in `p3-` shots |
| **Home on Fire breakage** (its service id changes, its picker loses us, it stops redirecting, "Replace Home" switched off) | We depend only on its public service id (two forms) and its picker. Status chip shows "Fire TV home active" when the entry is missing and "Redirect may be off in Home on Fire" when the entry is present but no Home launch has arrived. HoF's escapes stay on. `launcher.sh status` is the first diagnostic. | Home lands on Amazon |
| **Declaring HOME changes the Stick's home resolution** | R0 before/after; drop the HOME filter if it differs. | `resolve-activity` output differs or a chooser dialog appears |
| **Fire OS update** | Already blocked (layer 1). If it happens anyway: README "After a Fire OS update" checklist + `launcher.sh status`; worst case `reset-to-stock`. | `pm list packages -d | wc -l` ≠ 30 |
| **Memory pressure while an app plays** | `onStop` releases backdrops and art; `onTrimMemory` drops caches; PSS target ≤ 45 MB in background; `trimMemory` reflection only if proven. | PSS > 60 MB in background |
| **Crash loop takes Home hostage** | Boot-attempt marker + uncaught handler; safe-mode screen always; HoF stripped only when armed (G6a), granted, and the loop came from Home presses; HoF long-press escape outside our process. | `boot_attempts` ≥ 3, `crashes` file grows |
| **A dev build strips the user's Projectivy redirect** | `crashguard_strip` is off until G6a; `am start` launches never count as Home launches; `debug_crash` hook off unless flagged; R6/R6b prove it. | HoF entry missing after a dev crash |
| **Lost signing key** | Keystore + `.keypass` git-ignored; user told to back them up in Phase 1; without them `pm uninstall` then reinstall (settings file on `/sdcard` survives uninstall only if we copy it; Phase 7 backup feature covers it). | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| **Menu key and HoF's long-press Menu** | Panel opens on Menu UP under 450 ms only; HoF swallows the UP of a long press, so a long press never reaches us. R11 on the remote. Fallback: long-Select on the clock, or Back-Back-Menu. | Quick Panel and HoF's screen both open |
| **Device settings changed without a record** | `launcher.sh snapshot` before G2a; `restore-settings` reads only from it; every gate names the snapshot value it restores to. | a `settings get` differs from `backup/orchard-pre.txt` with no gate logged |
| **adb key / transport / IP change** | Only `tv.py`'s trusted key; never `adb connect`. b64push for the APK (≈ 25 chunks). `TV_HOST` override; README asks for a DHCP reservation. | TV shows an "Allow USB debugging?" dialog; `tv.sh` times out |

---

## 5. Definition of done, maintenance, docs

### 5.1 Done means

1. Q0 answered and recorded; gates G0, G2a–d, G4, G6a–b passed and logged in `launcher/TESTLOG.md` with screenshots.
2. § 2.4 reset matrix passes on the release build, R2 at 5/5, R11 confirmed by the user; `launcher.sh
   reset-to-stock` and `restore-home` are idempotent; the host tests pass and the mutation check is logged.
3. Perf on the device: cold start < 1 s, warm < 300 ms, Janky < 10%, PSS ≤ 110 MB on screen and ≤ 45 MB in background, zero redraws when idle.
4. The approved mockup and the device screenshots match on: layout bands, tile size/scale, label behaviour, shelf, panel and sheet.
5. 7-day soak without a crash (`crashes` file empty, `boot_attempts` never ≥ 2) and without Home landing on Amazon uninvited.
6. `THIRD_PARTY_NOTICES.txt` is complete and shown under About; no HoF code or resources anywhere in `launcher/`.

### 5.2 Maintenance

- Monthly or after anything odd: `scripts/launcher.sh status` and `scripts/launcher.sh perf`.
- To update Orchard: bump `launcher/app/version.env`, `scripts/launcher.sh build && scripts/launcher.sh install`
  (same key, no uninstall), then `status`.
- If Home shows Amazon's home: open Orchard from the Apps row → Options → "Turn Home redirect back on";
  or `scripts/launcher.sh restore-home`.
- To leave Orchard entirely: `scripts/launcher.sh reset-to-stock` (Home → Amazon), optionally `uninstall`.
  To go back to Projectivy instead: hold Menu → Home on Fire → target = Projectivy. For a stock Stick:
  `scripts/launcher.sh factory-stock`.
- If the Stick's IP changes: `TV_HOST=<ip> scripts/launcher.sh status`; reserve the IP in the router.
- Keep `launcher/app/release.keystore` + `.keypass` backed up outside the repo.

### 5.3 Docs to update at the end of Phase 6 (one worker, one task)

- `detox/README.md`: add **Layer 7: Orchard launcher** (what, why, how, checked), update
  "What the TV is like now", "Undo" (new `launcher.sh` commands incl. `factory-stock`, the `undo.sh
  orchard` layer and the strip-not-delete change to `undo.sh home`; which one is "the reset" per Q0),
  "Maintenance", a "Find the Stick's IP" note (Settings → My Fire TV → About → Network; reserve it in the
  router; `TV_HOST`), "Repos and tools looked at" (dumbtv and tv-vibehack now "Yes: ideas + build
  script"), and the "Files in this repo" table (`launcher/`, `scripts/launcher.sh`, `backup/orchard-pre.txt`).
- `./README.md` (new, short): build, install, reset (three on-screen places + Mac),
  licences (MIT; Inter OFL; full notices in `THIRD_PARTY_NOTICES.txt`; "integrates with Home on Fire,
  GPLv3, no code shared").
- `detox/.gitignore`: build output, keystore, `.keypass`, `shots/`.
- Commit on `main` once per phase with a one-line message; attribution line per the
  session's reminder. Nothing in this repo names media titles, only app names.

---

## 6. Review log (Rev 2, 2026-10-19)

Adversarial review returned 23 issues. Each was checked against the HoF source (`HijackService.java`:
`handleMenuKey` schedules on DOWN, fires at 500 ms, `menuLongPressFired` swallows repeats + UP;
`launchTarget` on every Amazon-home arrival), `scripts/undo.sh`, `scripts/tv.sh` and the research files.

| # | Verdict | Change / reason |
|---|---|---|
| 1 | accepted | CrashGuard strips HoF only when `crashguard_strip=on` (set at G6a), granted, and the looping launch carried `CATEGORY_HOME`; otherwise safe mode only. R6b added. (§ 2.2) |
| 2 | accepted | `returnToFireTv` polls `AccessibilityManager` until HoF is unbound (100 ms x 20) before launching Amazon home; "Hold Home" sheet on timeout; R2 is 5/5. (§ 2.1, § 0.10) |
| 3 | accepted | Quick Panel opens on Menu `ACTION_UP` only, not cancelled, no repeats, < 450 ms; R11 on the physical remote. (§ 0.9, § 0.12) |
| 4 | accepted | Q0 asks the user "Amazon home only, or everything?"; confirm sheet lists what is kept and names the Mac command; `launcher.sh factory-stock` (gated) and `undo.sh orchard` layer added. (§ Q0, § 0.10, § 2.3) |
| 5 | accepted | `boot_attempts` marker at `OrchardApp.onCreate` line 1, cleared after the first drawn frame; runtime state in `getFilesDir()/state.conf`; SafeModeView shows grant text. (§ 2.2) |
| 6 | accepted | R0 `resolve-activity` HOME + raw Home screenshot before/after the install; fallback build without the HOME filter. (§ 1.1, § 2.4) |
| 7 | accepted | Tile relabelled "Turn Home redirect back on (goes to: …)"; `last_home_launch_at` recorded on `CATEGORY_HOME` launches; chip "Redirect may be off in Home on Fire". (§ 0.9, § 2.1) |
| 8 | accepted | `launcher.sh snapshot` → `backup/orchard-pre.txt` before G2a; `restore-settings` restores from it; deviceidle + compile named in G2a; new G4 for the animator-scale toggle. (§ 2.3, Phase 2/4) |
| 9 | accepted | `--ez orchard.crash` honoured only with `debug_crash=1` in `orchard.conf`; `status` warns if the flag is left on. (§ 2.2) |
| 10 | accepted | Shadow drawn in `TileRow.dispatchDraw` under the transformed bounds; focused child drawn last; no hardware layers; mid-animation check via `debug_slow_anim`. (§ 0.5, Phase 3 check 5) |
| 11 | accepted | Art 325x195 RGB_565; blur drawn from 240x135 with `drawBitmap(src,dst)`, never upscaled; `BitmapShader` + `drawRoundRect` instead of `clipPath`. (§ 0.4, § 0.8) |
| 12 | accepted | Sway stops after 3 cycles / 20 s and in `onPause`; idle check: frame count flat over 30–40 s. (§ 0.5, Phase 3 check 8) |
| 13 | accepted | Key sequences in one `input keyevent 22 22 …` call; user holds RIGHT/DOWN 3 s on the remote; `gfxinfo` after both. (§ 3 key recipe, Phase 3 check 7) |
| 14 | accepted | 1.2 owns `Ui.java`, manifest, `res/values`, `Config.Keys` and stubs for every class; parallel tasks return shared-file additions as text; `HomeActivity` hooks serialised 4.1→4.2→4.3 and 5.1→5.3; one git worktree per parallel worker, `build.sh --out-dir`. (§ 1.2, § 3) |
| 15 | accepted | 3.4 → 3.4a/3.4b, 5.2 → 5.2a/5.2b, 1.5 → 1.5a/1.5b. (Phases 1, 3, 5) |
| 16 | accepted | `AccessibilityList`, `Config` parse/write and `CrashGuard.window` are pure Java, tested by `app/test/*.java` on the host inside `build.sh`, mutation-checked (R12). (§ 1.4, Phase 1 task 1.6) |
| 17 | accepted | Hero = vivid-colour wash gradient + sharp banner at 640x360 on the right + title/subtitle; no blurred banner; mockup uses banner-sized placeholders with fake word-marks and a `?hero=wash|blur` comparison. (§ 0.3, Phase 0) |
| 18 | accepted (modified) | Mockup gets `?cols=5|6` and `?clock=on|off`, decided at G0; focused label sits under the scaled tile (+9 px). 6 columns stays the default because the HIG width table is the only first-party number; 5 columns = 320x192, not 300x180, so the row still fills the 1760 px safe width. (§ 0.2, § 0.3) |
| 19 | accepted | Permanent `≡ Options` hint in the status strip; last grid tile "Fire TV Home" (setting, default on) opens the same confirm sheet. (§ 0.3, § 2.1) |
| 20 | accepted | `THIRD_PARTY_NOTICES.txt` with both full MIT notices + OFL, packaged as `assets/NOTICES.txt` and shown under About; workers get § 1.5 only, never HoF source or `repo-mining.md`'s GPL line references; task 1.4 checks Inter's OFL for a Reserved Font Name and renames the subset if one exists (none expected). (§ 1.1, § 0.6, Phase 1) |
| 21 | accepted | Phase 3 = 2.5–3 days, Phase 4 = 1.5 days, Phase 5 = 1.5 days, +0.5 day buffer per phase; total ≈ 8–9 working days before the soak. (§ 3) |
| 22 | accepted | `ACTION_APPLICATION_DETAILS_SETTINGS` and `Somnambulator` resolved on the device before use; App-info row hidden if unresolved; screensaver restored from the snapshot. (Phase 5) |
| 23 | accepted (already partly true) | `tv.sh` already honours `TV_HOST`/`TV_PORT`; `launcher.sh` passes them through; README gets the DHCP-reservation note and where to read the IP on the TV. (§ Mac facts, § 5.2, § 5.3) |

Nothing was rejected. Not touched by this revision: the TV, `scripts/undo.sh`, `scripts/tv.sh`.

---

## 7. Rev 4: light theme + weather (2026-10-20, mockup done, device build not started)

User decisions: (1) automatic **light/dark by real sunrise/sunset** for the user's city, computed on the device (NOAA, no network), with a smooth cross-fade around sunset/sunrise; Settings → Theme: Auto (sunset) / Light / Dark. (2) **Temperature chip** (glyph, current temperature, today's high/low, sunset time) from Open-Meteo for a city set once in Settings; Settings → Weather on/off (default ON), Units °C/°F/auto, City. The mockup is the visual contract (`mockup/` with `?time=HH:MM`, `?theme=auto|light|dark`, `?weather=off|offline`, `?units=`, `?city=`, `?wx=clear|cloud|rain|haze`, `?debug=1`; key **T** cycles 06:00, sunrise, 12:00, sunset-30m, sunset, sunset+20m, 22:00 and shows a slider, Shift+T hides it). Shots: `fire-tv-detox/launcher/shots/theme-*.png`. Every effect stays inside the § 0.8 rules: pre-blurred bitmaps, alpha / translate / scale only, no RenderEffect, no live blur.

### 7.1 New tokens (all in `mockup/tokens.css`, copy verbatim into `Ui.java` / `dimens.xml`)

| Group | Tokens |
|---|---|
| Phase windows (minutes, relative to today's sunrise / sunset) | `dawn-start -15`, `dawn-len 30` (cross-fade sunrise-15 .. sunrise+15), `dusk-start -15`, `dusk-len 30` (sunset-15 .. sunset+15), `golden-len 45`, `golden-ramp 15`, `golden-fade 25`, `theme-flip-at 0.43`, `theme-tick-s 30` |
| Cross-fade timing (ms) | `theme-fade-ms 900` (alpha fade when the user changes Theme or the clock jumps), `theme-flip-ms 0` (colour tokens switch instantly at the flip, no colour animation), `theme-hero-fade-ms 1500` (user-initiated Theme change only: shelf card layer old to new) |
| Golden hour | `golden-a1 .50`, `golden-a2 .20`, `golden-rgb1 255,146,62`, `golden-rgb2 255,206,140` |
| Day wallpaper | `wall/day.webp` (`tools/make_wallpapers.py --day-only`, also `app/res/drawable-nodpi/wall_day.webp`), `day-vignette-alpha .10`, `day-vignette-rgb 92,84,76`, `wall-night-rgb 11,11,16` |
| Light colour set (`:root[data-theme="light"]`, overrides every theme-dependent token) | `bg #F4F1EC`, `text #1B1B1F`, `text2` 76 %, `text3` 68 %, `glass-thin` white 46 %, `glass` white 66 %, `glass-thick` `#FAF8F4` 80 %, `glass-outline` 13 % warm grey (1 px), `dock-fill` white 52 %, `btn-focus-fill #FFFFFF`, `shadow` warm 26 %, rim alphas 95 / 40 %, `drop-rgb 52,40,28`, `veil #E9E4DC`, `tog-off`, `fth-*`, `wx-*` glyph colours |
| Softer lift (light) | `shadow-alpha .22` (dark .48), `contact-alpha .20`, `glass-drop-alpha .16`, `hero-art-shadow-alpha .26`, `glow-alpha .38` |
| Top Shelf wash (light) | `hero-lum .80` (dark .32), `hero-sat-max .50` (dark .70), `hero-wash-mid-lum .90`, `hero-dim-left .30`; emblem plate and icon-only tile plates are NOT themed (dark `.34/.12`, `.26/.09` in both themes, like banners; mid-tone light plates read muddy and the white letters measured 2.4 to 2.8:1) |
| Weather chip | `wx-glyph 44`, `wx-gap 14`, `wx-temp-gap 16`, `wx-name-max-w 420`, `wx-clock-gap 56`, `wx-hl-opacity 1`, `wx-hl-ms 200`, `wx-refresh-min 30`, `wx-stale-min 180` |
| Settings list scroll (15 rows) | `list-view-h 860`, `list-fade 36`, `list-scroll-ms 220` |

Phases: **night** (day layer alpha p = 0) → **dawn** (sunrise-15 .. +15, p = smoothstep) → **day** (p = 1) → **golden hour** (warm overlay alpha g ramps 0 → 1 over 15 min starting 45 min before sunset, holds) → **dusk** (sunset-15 .. +15, p falls by smoothstep, g falls to 0 over 25 min from the start of dusk) → night. Auto mode only: forced Light/Dark never shows the golden tint. Mockup default clock is 21:42 (the old static strip clock), so every existing link still opens in the dark theme.

Contrast, computed by `tools/check_contrast.py` (reads the tokens, exits 1 below 4.5:1): light `text` 8.7:1 / `text2` 5.2:1 on the darkest day-wallpaper pixel (after vignette), `text3` 4.8:1 on its worst real location (settings left column), 5.0 to 5.5:1 on every glass, focused row 18.9:1 (value text 4.9:1), shelf subtitle 5.2:1 on the worst app wash (`#987beb`). The cross-fade itself cannot hold 4.5:1 for every pixel (a half-faded wallpaper is a mid-grey); `theme-flip-at 0.43` puts the colour flip where dark and light text are both closest, and the render sweep gates the dip (Rev 5, § 8.5: the sweep now covers the whole 30 minutes and passes at 4.5:1 for every element).

### 7.2 Device implementation notes

- **Sun maths: `SunCalc.java`, pure Java, no Android imports**, a line-for-line twin of the mockup's `Sun` object (NOAA / Meeus: Julian day, equation of time, declination, hour angle at zenith 90.833 degrees, one refinement pass at the event). Input: lat, lon, local date, UTC offset (`TimeZone.getTimeZone(id).getOffset(noon)`, so DST is right). Output: `rise`, `set` in minutes after local midnight, or polar day / polar night. **Host tests** in `app/test/SunCalcTest.java` (run by `build.sh`, mutation-checked like the other host tests): New Delhi 2026-06-21 05:24 / 19:22 (+5:30), London 2026-06-21 04:43 / 21:21 (+1), London 2026-12-21 08:04 / 15:53 (0), New York 2026-06-21 05:25 / 20:31 (-4), New Delhi 2026-10-19 06:24 / 17:48 (all +/- 1 min, taken from the mockup run; 17:48 not the 17:46 of the brief), Tromso 2026-06-21 polar day and 2026-12-21 polar night, equator equinox 06:04 / 18:11. `ThemePhase.at(minute, sun)` returns `(name, p, g)` and has its own table test over the same windows.
- **Layers, never live effects.** `WallpaperView` draws up to three bitmaps with `Paint.setAlpha`: the night wallpaper (existing blur cache), the day wallpaper (`wall_day.webp`, plus its own 240x135 blurred copy cached as `blur_day_<hash>.png`), and a baked golden-hour overlay (a 240x135 gradient bitmap only, drawn scaled up with the alpha from `g`; no live `RadialGradient` / `LinearGradient` shader, full-width shaders are GPU-bound on the Stick). Glass slices (dock band, panel, sheet) draw the night crop, then the day crop at alpha p: two `drawBitmap` calls from the same small bitmaps. The shelf backdrop is two pre-built bitmaps (dark and light wash), the light one drawn over the dark one with `Paint` alpha = the same `p` as the day wallpaper (one extra `drawBitmap`), so dawn and dusk blend smoothly with no two-tone band. The `theme-hero-fade-ms` fade of the shelf card layer is used only for a user-initiated Theme change.
- **Colour tokens**: a `Theme` object holds two ARGB sets (dark = § 0.7, light = § 7.1). At the flip (`p` crosses `theme-flip-at`) the whole set (colours and numeric tokens) switches **instantly**: no `ArgbEvaluator` and no colour property animation (it would re-render every text view per frame, outside the scale / translate / rotate / alpha rule). The wallpaper cross-fade (p about .43 is a mid-grey) carries the change visually. If a softer flip is wanted later, use an alpha-only dip on the chrome text views (1 to .5 to 1 over `theme-flip-ms`, colours swapped at the low point). Banners and app art are never themed.
- **When it evaluates**: `HomeActivity.onResume`, a 30 s `Handler` tick while resumed (stopped in `onPause`, no wake-ups under the screensaver), and the runtime-registered broadcasts `ACTION_TIME_CHANGED`, `ACTION_DATE_CHANGED`, `ACTION_TIMEZONE_CHANGED`, plus city / Theme setting changes. Sunrise and sunset are recomputed when the local date or the city changes.
- **Weather: `WeatherClient.java`**, one `HttpURLConnection` GET on a background thread (connect 5 s, read 8 s, body capped at 8 KB), https only (no cleartext config):
  `https://api.open-meteo.com/v1/forecast?latitude=<lat>&longitude=<lon>&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1`
  No key, no account. Only the city's coordinates rounded to 2 decimals (about 1 km) are sent. WMO code to glyph (pure function, host-tested): 0, 1 clear (sun by day, moon by night from `is_day`), 2, 3 cloud, 45, 48 haze, 51 to 67 and 80 to 82 and 95 to 99 rain, snow codes shown as cloud. Sunset on the chip comes from `SunCalc`, not from the API, so it exists offline.
- **Cache and refresh**: result written atomically to `getFilesDir()/weather.json` with `fetchedAt`. Refetch only when the cache is older than `wx-refresh-min` (30 min), when the city changes, or on Settings → Weather → on; never more than one request in flight; no retry loop (next try = next resume after the interval). Units are applied at display time, so a Units change never refetches.
- **Offline rule**: any failure (no route, timeout, bad JSON, HTTP error) keeps showing the cached value until it is older than `wx-stale-min` (180 min), then the chip is hidden. **Never a toast, dialog, spinner or error text on the home screen.** Sunset-based theming is unaffected (no network needed). The mockup shows this with `?weather=offline` (chip gone, clock stays right-aligned).
- **Permission**: `<uses-permission android:name="android.permission.INTERNET"/>` (normal permission, granted at install, no `pm grant`). Orchard goes from one `uses-permission` to two; update the `build.sh` check ("exactly one"), the Phase 1 and Phase 2 acceptance lines that say "exactly `WRITE_SECURE_SETTINGS`", and `launcher.sh status` (it should list both). Weather OFF means the app makes no request at all.
- **City storage**: `city_id` (or `city_lat`, `city_lon`, `city_name`, `city_tz`) in `orchard.conf`; the list in the mockup (12 cities) ships as `res/raw/cities.csv`. For a city that is not listed, `launcher.sh city <lat> <lon> [label]` (Rev 5, § 8.3, replaces the earlier `set-city`) writes the keys over the DUMP-guarded broadcast channel (the adb file-write path is closed on this Stick, § 1.1); the timezone then comes from the TV's own zone. First boot with no city set: theme = Dark, chip hidden, and the Settings → City row shows "Not set".
- **Settings**: four new rows at the top (Theme, Weather, Units, City), then the existing ones; 15 rows no longer fit, so the list scrolls by translate with a 36 px alpha fade at the cut edges (`list-view-h 860`). The City row opens the D-pad list (Select sets the city and returns to Settings with `hideCities()`; the Settings City row value confirms it, no toast. Back returns without change). The list fades at the cut edges use the built-in `setVerticalFadingEdgeEnabled` or an overlay of the same pre-blurred wallpaper slice with a baked alpha gradient; never an offscreen mask layer (`saveLayer` + `DST_IN`).
- **Weather chip vs panels**: the chip is 72 px tall, taller than the 50 px status strip band that the Quick Panel and the sheet cover (y 60 to 110), so `#wx` is `visibility:hidden` while the screen is panel or sheet (same rule as Safe mode, which hides it with `display:none`).
- **Dim values in list rows** (City list `Sunset HH:MM`): colour only, `text2` on unfocused rows and `rgba(17,17,17,.6)` on the focused row, no ad-hoc opacity; `tools/check_contrast.py` gates both (and fails if `.row .val .dim` ever carries an `opacity`).
- **`?time=live`** takes y/m/d from the city's timezone (Intl), so sunrise and sunset are today's; the static strip date is Tue 20 Oct 2026 (Rev 6; sunrise 06:11, sunset 17:58 in Bengaluru).
- **Docs to touch when the device build lands**: § 0.7 ("dark only"), § 1.1 permissions row (done above), § 1.4 / Phase 1 / Phase 2 permission counts, README "What it does not do" (it will now say: one weather request, nothing else leaves the TV), `THIRD_PARTY_NOTICES.txt` (Open-Meteo asks for attribution under CC BY 4.0 for its free tier: add one line under Settings → About).

---

## 8. Rev 5: screensaver + city (2026-10-20, mockup done, device build not started)

User decisions, all visible in the mockup (`mockup/index.html?screen=dream`, Settings → Screensaver, Settings → City). Shots: `fire-tv-detox/launcher/shots/dream-rev2-*.png`, `theme-midfade-*.png`, `city-list-bengaluru-default.png`.

### 8.1 Decisions

1. **Default city = Bengaluru** (12.9716, 77.5946, `Asia/Kolkata`), first in the built-in list, default in the mockup (`?city=` still overrides; chip, sunrise and sunset are NOAA for Bengaluru: 2026-10-19 sunrise 06:10, sunset 17:59). Changing it must be easy: Settings → City on the TV, or one Mac command (§ 8.3).
2. **Screensaver (ClockDream) = a giant, minimal digital clock**, replacing § 0.11:
   - Inter **Thin** digits, ink height **560 px** (font size 560 / .727 = 770 px; Inter cap height is .727 em), white at **85 %** on **pure black**, **24-hour `HH:MM`**, no seconds, **proportional figures, each glyph centred in a fixed-width cell** (Rev 6: the colon is **two drawn circles**, 26 px with centres 190 px apart, see 9.7) so the digits never jump (Inter's tabular `1` carries a foot slab and left a wide hole in `21`; the default `one` glyph has none; checked in the font: `one` 833 vs `one.tf` 1328 units). Cell = 1328/2048 = **.6484 em** for a digit, 550/2048 = **.2686 em** for the colon cell (the colon itself is the two drawn circles), pitch = cell - 0.125 em tracking (Rev 6 retune). The mockup's ink group is nudged **-0.031 em** (about -24 px) left so the margins are even (the device build uses nudge 0, see below) (ink margins 118 / 114 px for `00:00` and 118 / 109 for `08:08` at the -12 / +12 px shifts, worst 104 px (`00:02` at +12), never under 96 px overscan). **Device build (2026-10-20):** the mockup renders Google Fonts' variable Inter, whose Thin side-bearings differ from the shipped Inter 4.1 Thin subset; with the shipped metrics the -0.031 em nudge leaves only 92 px (`00:00` at -12), while **nudge 0** centres the ink (116 / 116 px for `00:00`, 116 / 114 for `08:08`, worst 114 px), so `ClockFormat.NUDGE_EM` is 0 and `ClockFormatTest` pins those shipped-font numbers. The mockup token stays -0.031 for the mockup's font.
   - One small line beneath: `Tue 20 Oct · 24° Bengaluru` (Inter Regular 44 px, white 56 %, 64 px under the digits, +0.03 em tracking). The weather part (` · 24° Bengaluru`) is hidden when Weather is off or the chip would be hidden (offline / stale); the date stays. Units follow Settings → Units.
   - Optical centre: the group (digits + line, 685 px of ink) is centred 24 px **above** the canvas middle (ink centre y = 511 of 1080), because the heavy digits sit above the light line.
   - **Burn-in guard:** the whole group shifts by a table offset once a minute inside a **24 x 24 px box** (±12 px; 12 offsets, index = epoch-minute % 12), and **dims to 40 %** alpha after **10 minutes** of dreaming. Redraw once per minute.
   - **Theme: always black**, never themed (night-safe), whatever the day theme is. Nothing from `Theme` is read.
   - Enable with `scripts/launcher.sh screensaver on|off` (§ 8.4), a user-gated command. Settings → Screensaver (and the Quick Panel's Screensaver tile) opens a **preview**; the row shows the hint "To use it as Fire TV's screensaver run: `scripts/launcher.sh screensaver on`". Any key (or click in the mockup) exits the preview back to where it was opened.
3. Mockup controls: `?screen=dream`, `?dreamshift=0..N` (pins the burn-in offset N and freezes the minute tick), `?dreamdim=1` (the 10-minute dim, applied at once), plus the existing `?weather=off|offline`, `?city=`, `?time=live`. Without `dreamshift` / `dreamdim` the preview runs the real minute tick and the 10-minute dim timer.

New tokens (all in `mockup/tokens.css`, copy into `dimens.xml`): `dream-digit-h 560`, `dream-track -0.115`, `dream-fg .85`, `dream-line-size 44`, `dream-line-gap 64`, `dream-line-a .56`, `dream-line-track .03`, `dream-lift 24`, `dream-shift-box 24`, `dream-dim .40`, `dream-dim-min 10`. Rev 5 theme tokens: `tw-peak .90`, `tw-top 92,86,170`, `tw-mid 196,116,152`, `tw-low 244,170,116`, `tw-glow 255,150,90`, `tw-glow-a .45`, `halo-icon`.

### 8.2 Device recipe: `ClockDream.java` (DreamService, one custom View)

- `ClockDream extends DreamService`: `setInterruptible(true)`, `setFullscreen(true)`, `setScreenBright(false)`, `setContentView(new ClockView(this))`. No XML layout, no child views, no `Theme` access, no network.
- `ClockView extends View`, **no hardware layer** (software, black background). A full-screen `LAYER_TYPE_HARDWARE` is a 1920x1080 RGBA texture (about 8 MB) held for a view that redraws once a minute, on a 1.5 GB Stick. `setTranslationX/Y` and `setAlpha` are already RenderNode properties that move or fade the view without calling `onDraw`, so no layer is needed. If the 1500 ms dim animation stutters in a device test, enable the layer only for its duration: `animate().alpha(.4f).setDuration(1500).withLayer()`. `onDraw` draws **one `drawText` per cell** with cached `Paint`s: `Paint.setTypeface(inter_thin)`, `setTextSize(770.3f)` (560 / .727 = 770.3 px), letter-spacing 0, colour white at alpha 217. Cell pitch = (.6484 - .125) em x 770.3 = 403 px for a digit, (.2686 - .125) em x 770.3 = 111 px for the colon; draw each digit at `cellLeft + (pitch - measureText(ch)) / 2` (centred in its cell); the colon is two drawn circles (26 px, centres 190 px apart) with `drawCircle`, not a glyph; group x origin nudged by `ClockFormat.NUDGE_EM` (0 on the device; the mockup's -0.031 em does not fit the shipped font). Baseline = box bottom, so the ink box is 560 px tall: the same ink-pixel maths as the mockup (`#dtime` line-height = digit height). The small line (`inter_regular`, 44 px, alpha 143) is one more `drawText`.
- **Choreographer-free, one wake-up per minute:** `handler.postDelayed(tick, 60_000 - now % 60_000 + 20)` on `onDreamingStarted`; `tick` rebuilds the strings, calls `setTranslationX/Y` from the 12-entry offset table (index = epoch-minute % 12), `invalidate()`s once, and re-posts to the next minute boundary. `onDreamingStopped` / `onDetachedFromWindow` `removeCallbacks`. No `Choreographer.postFrameCallback`, no `ValueAnimator` for the clock, no per-second anything.
- **Shift and dim by translation / alpha only:** the shift is `View.setTranslationX/Y` (a hardware-layer property, no redraw); the dim is `animate().alpha(.40f).setDuration(1500)` posted `10 * 60_000` ms after start. Neither re-runs `onDraw`.
- **Font:** add **`app/res/font/inter_thin.ttf`** to `tools/subset_fonts.sh` (`Inter-Thin.ttf` from the same Inter 4.1 zip, `--unicodes=U+0030-0039` digits only, no layout features). Size: a digits-only subset is under 10 KB; if the full Latin set is used instead, expect about +60 KB. **Licence:** the same Inter 4.1 SIL OFL 1.1 file already in `app/assets/OFL.txt`; the script's Reserved Font Name check (exit 2 if declared) covers the new file, so Thin keeps the Inter name, and `THIRD_PARTY_NOTICES.txt` already lists Inter (no change).
- **Host tests** (pure Java `ClockFormat`): `HH:MM` zero-padding at 00:05 / 09:07 / 23:59, the offset table stays inside ±12 px and cycles through all 12 entries over 12 minutes, the line formatter (weather on / off / offline, °C and °F), and the delay-to-next-minute maths (never < 1 ms, never > 60 020 ms). **Rev 6 additions: the worst-case ink margin** (`ClockFormat.inkExtent`, from the Inter Thin advance and side-bearing table): for **`00:00` and `08:08`** (the widest strings; every digit cell is the same width, only the side-bearings differ) at the largest left shift (-12 px) and right shift (+12 px) the ink must stay **>= 96 px from the canvas edge** (mockup measurement with the Rev 6 tokens: ink margins 118 / 114 px for 00:00 and 118 / 109 for 08:08 at the -12 / +12 px shifts, worst 104 px (00:02 at +12), never under 96 px); also assert it for the first digit 0/1/2 at -12 and the last digit 0-9 at +12. The test fails if `dream-track`, the digit height or the nudge is retuned below 96.
- **Acceptance on the TV (gate G5b):** `launcher.sh screensaver on`, then start the dream without waiting for the timeout (`am start -n com.android.systemui/.Somnambulator`, resolved on the device first, as in Phase 5), shot after 1 min (offset changed), `dumpsys gfxinfo` flat (no frames between ticks), digits are 560 px tall on a 1080p shot, any key returns to the launcher.

### 8.3 City: `scripts/launcher.sh city`

```
scripts/launcher.sh city <name>                  # from the built-in list: bengaluru, "new delhi", mumbai, ...
scripts/launcher.sh city <lat> <lon> [label]     # any place, e.g. city 51.5072 -0.1276 London
```

- **Mechanism (live, no restart, no force-stop):** a DUMP-guarded broadcast to the existing `DebugReceiver` (same guard as § 1.1: only adb shell and system can send it), with a new action:
  `am broadcast -n tv.orchard.home/.DebugReceiver -a tv.orchard.home.SET_CITY --es city_id bengaluru`
  `am broadcast -n tv.orchard.home/.DebugReceiver -a tv.orchard.home.SET_CITY --es city_lat 12.9716 --es city_lon 77.5946 --es city_name Bengaluru`
  Extras are all strings (no `--ef` parsing surprises). The result data is `1` (applied), `0` (same as before), `-1` (rejected); the script prints it and fails on `-1`. The receiver writes `city_id` (listed) or `city_lat`, `city_lon`, `city_name` (custom, `city_id` removed) into `orchard.conf`, then calls `OrchardApp.notifySettingsChanged()` exactly as the debug-flag path does: `HomeActivity` re-reads, `SunCalc` / `ThemePhase` recompute, the weather cache is invalidated and refetched, the chip and the screensaver line update. A running ClockDream reads the city on its next minute tick.
- **Validation (pure Java `CityCommand`, host-tested):** lat -90..90, lon -180..180, finite; label optional (default `lat,lon` to 2 decimals), at most 24 chars, only letters, digits, space, `.`, `-`, `'` (anything else dropped, so the `;` / `=` of the conf format cannot be injected); a listed name matches case-insensitively with spaces and hyphens ignored (`"New Delhi"`, `newdelhi`, `new-delhi`). The timezone of a custom city is the TV's own zone (§ 7.2); a listed city carries its own `tz`.
- **Built-in city table:** one source, `res/raw/cities.csv` (`id,name,cc,lat,lon,tz,hi,lo,cond`: the 12 rows in the mockup's `CITIES`, Bengaluru first). Settings → City reads it for the list; `CityCommand` reads it for the by-name match; `launcher.sh city <name>` only forwards the name (it does **not** keep its own copy of the table, so the two can never drift), and prints the receiver's `-1` as "unknown city; use <lat> <lon>". Default when nothing is set: **Bengaluru** (replaces § 7.2's "Not set / chip hidden" first-boot rule: the row shows Bengaluru from the first boot).
- **Settings → City (TV):** unchanged D-pad list (Select sets and returns), now with Bengaluru first and checked by default; the left-hand hint reads `Not listed? On the Mac run scripts/launcher.sh city <lat> <lon> [label]`.
- `launcher.sh status` prints the current city (`city_id` / custom name + lat, lon) from the same keys when readable; `usage()` gets the two `city` lines.

### 8.4 Screensaver on/off: `scripts/launcher.sh screensaver on|off`

- **`on` is user-gated** (a typed gate like `factory-stock`'s `STOCK`: it prints what it will change and needs the word `SCREENSAVER`; `--dry-run` prints the commands without asking). It only touches two secure settings, after making sure the snapshot exists (`snapshot` is run first if `backup/orchard-pre.txt` is missing, because `off` depends on it):
  `settings put secure screensaver_components tv.orchard.home/.ClockDream`
  `settings put secure screensaver_enabled 1`
  then verifies with `settings get` (and `cmd package resolve-service`-style check that `ClockDream` resolves; fails with the manifest hint if not).
- **`off` is snapshot-restoring, never a blind delete:** it restores `secure.screensaver_components` and `secure.screensaver_enabled` to the values in `backup/orchard-pre.txt` (the same `restore_one` helper `restore-settings` uses). If the snapshot has no value (`null` / missing), `screensaver_components` goes back to **Android Colors** (`com.android.dreams.basic/com.android.dreams.basic.Colors`) and `screensaver_enabled` to `1`. It prints before / after. `restore-settings` and `factory-stock` already restore the screensaver from the snapshot, so they undo `on` too.
- The Quick Panel Screensaver tile and Settings → Screensaver never write settings: they open the preview only (Fire TV may still start its own screensaver after a timeout; the Mac command is how the user opts in).

### 8.5 Theme review leftovers (folded in)

1. **Halo device recipe.** The mockup's `--halo` is four identical `text-shadow: 0 0 16px <bg at alpha ts>` layers (a single CSS shadow is too faint at the 0.4 alpha cap). On the device, draw it as **four stacked `setShadowLayer(16 px radius, 0, 0, haloArgb)` passes** per text view (a thin `HaloTextView` that sets the layer and calls `super.onDraw` four times, shadow colour = the current theme's `bg` at alpha `ts`; only runs while `ts > 0`, i.e. 30 minutes twice a day), **or one retuned layer** (radius 16 px, alpha raised until the measured contrast of `tools/check_contrast.py` matches; measure it, do not guess). Android's `setShadowLayer` radius is a blur radius, CSS `text-shadow` blur 16 px is a 8 px sigma, so tune by eye against `theme-midfade-*.png` and gate by the sweep. The same recipe covers the Options icon (next item) and `#flabel`.
2. **Hamburger (Options) icon halo.** The glyph is a stroked vector, so `text-shadow` never touched it; the mockup now gives `#hint svg` a matching `filter: drop-shadow(0 0 16px var(--hc))` stack (`--halo-icon`, four passes; token `halo-passes` 4 is the count both mockup stacks and the device `Halo.java` must share). Device: the icon is drawn by the same `HaloTextView` pass (or `Paint.setShadowLayer` on the stroke paint; hardware canvas supports it for text only, so draw the three bars as a glyph from the icon font or add a pre-baked halo bitmap behind it at alpha `ts`).
3. **`tools/check_contrast.py` sweep widened.** The render sweep now covers **sunset ±16 and sunrise ±16 minutes** (33 renders each, the whole 30-minute cross-fade plus a margin) and measures three more elements: `#flabel` (focus label), `#wx .wxhl` (high / low) and `#wxt` (temperature), besides the existing shelf title / subtitle, date, clock, Options hint and weather caption. Result with the Rev 5 dusk palette: every text element passes 4.5:1 (shelf title gate 3:1) at every minute, exit 0.
4. **"Flat muddy mauve-grey" mid-fade fixed with a deliberate dusk palette.** A straight night to day alpha blend passes through a grey mauve at p ~ .5. Rev 5 adds a fourth pre-rendered layer between the day wall and the golden overlay: **`#twilight`**, a baked gradient (indigo `92,86,170` at the top, rose `196,116,152` at 58 %, amber `244,170,116` at the bottom, plus a warm low-left glow `255,150,90` at .45), drawn with **alpha = `tw-peak` x 4p(1-p)** (0 at both ends, .90 at the middle of the cross-fade). The wallpaper now travels indigo, rose, warm instead of through grey (`theme-midfade-*.png`). Device: one more 240x135 bitmap in `WallpaperView` (and in the glass slices) drawn with `Paint.setAlpha`, no shader; the token flip stays at `theme-flip-at .43`, and the halo / sweep above still gate legibility. The new layer is used only while `0 < p < 1`, so the steady day and night looks are unchanged. **Rev 5b:** the shelf backdrops (`hb-d`, `hb-l`) are near-opaque, so their `(1-p)` / `p` blend averaged to grey over the hero band and hid the twilight (top-half saturation .10 at 17:48). Both bitmaps now draw with alpha x `1 - hero-tw-cut x 4p(1-p)` (`hero-tw-cut .85`), so the dusk palette reads through the hero band mid-fade (saturation .27 to .35 in the top half, measured by pixel sample at 17:48 and 06:20); alpha only, no new bitmap.
5. Shots for the three mid-fade states use New Delhi (`?city=newdelhi&time=17:40|17:48|06:20`, sunset 17:48 / sunrise 06:24: the times the review used); Bengaluru equivalents (`?time=17:59`, `?time=06:10`) are saved as `theme-midfade-bengaluru-*.png`.

### 8.6 Docs to touch when the device build lands

§ 0.11 (done above: superseded), § 1.2 file map (`ClockDream.java`, `ClockView.java`, `ClockFormat.java`, `CityCommand.java`, `res/raw/cities.csv`, `res/font/inter_thin.ttf`), § 1.4 / `subset_fonts.sh`, README (screensaver and city commands), `launcher.sh` usage block (`city`, `screensaver`), `docs/learnings.md` if the Somnambulator or `calt` colon steps surprise.

---

## 9. Rev 6: time-of-day sky + rain (2026-10-20, mockup done, device build not started)

User decisions, all visible in the mockup (`mockup/index.html`, Bengaluru, Theme Auto). Shots: `fire-tv-detox/launcher/shots/sky-*.png`. Generator: `tools/make_sky.py` (Pillow + numpy). Gate: `tools/check_contrast.py` (extended, § 9.8). Every new number is a token in `mockup/tokens.css` section "Rev 6" (copy into `dimens.xml` / `Ui.java`).

### 9.1 What changed (one paragraph per user item)

1. **Sky wallpaper from the real sun.** The wallpaper under everything is now a sky chosen by the **NOAA solar elevation** (refraction-corrected) of the city, not by sunrise / sunset alone. Eight keyframes plus two vivid hand-over frames (`horizon` at sunset and its mirror `sunrise`, § 9.2), cross-faded by elevation as pre-rendered bitmaps (alpha only). The Rev 4 light/dark token rule is untouched (flip at `theme-flip-at` .43 of the sunrise / sunset cross-fade, about elevation -0.6 at sunrise and -1.0 at sunset); the sky just replaces the wallpaper layers beneath it. A soft sun glow (azimuth sets its x, elevation its y) and a moon glow (positioned by the time of night) are single baked tiles moved by `translate` and faded by alpha.
2. **Greeting** beside the date and clock, Caption 1 weight (25 px, `text2`): `Good morning` 05:00 to 11:59, `Good afternoon` 12:00 to 16:59, `Good evening` 17:00 to 20:59, `Good night` 21:00 to 04:59 (`greet-*` tokens, local minute each starts). It is the first item of the right-hand group `Good evening · Tue 20 Oct   21:42` on one baseline. The weather chip sits to the **left of that whole group** (flex, `wx-clock-gap` 56 px), so adding the greeting can only push the chip left; they cannot overlap. `Settings → Clock` off hides all of it, as before.
3. **Weather tints the sky** (§ 9.4) and rain draws falling drops (§ 9.5).
4. **Rain in the chip.** The glyph switches to the rain glyph while it is raining now; row 1 reads `24° · Rain 70%` (the `·` and chance are one Caption 1 span, then the high / low); row 2 reads `Rain likely from 16:00` when any **upcoming** hour today (after the current one) has `precipitation_probability >= 40` (`rain-likely-pct`), else the existing `Bengaluru · Sunset 17:58`. While it is raining now the chip already says `Rain 70%`, so the current hour is never announced as a forecast (there is no `Rain likely now` string). Screensaver line: `Tue 20 Oct · 24° · Rain 70%` when raining now, `Tue 20 Oct · 24° · Rain from 16:00` when rain is coming, else the old `Tue 20 Oct · 24° Bengaluru`.
5. **Clock polish**: § 9.7.

Mockup controls: `?sky=auto|night|dawn|sunrise|morning|noon|afternoon|golden|horizon|sunset|dusk` (pins the sky to that keyframe; with no `?time` the clock jumps to the time that keyframe holds in the current city), `?wx=clear|cloudy|rain|fog`, `?rainchance=NN` (the chance used by the chip and the hourly mock; at 40 or more the 16:00 to 19:59 hours qualify), the **T** cycle now steps `dawn, morning, noon, golden, sunset, dusk, night` (found by scanning today's elevation curve; it clears a `?sky` pin), **D** toggles the rain drops layer for review (forces it on when it is not raining, off when it is). Settings → Wallpaper gained `sky` (the default) before `dusk / ember / fog`. A fixed **Light or Dark theme keeps the Rev 4 static walls** (a fixed Light on a night sky would put dark text on navy); the sky is for Theme = Auto. The mock default weather for Bengaluru changed from cloud to **clear** so the plain mockup shows the sky; `?wx=cloudy` gives the old look. The static strip date is now Tue 20 Oct (sunrise 06:11, sunset 17:58).

### 9.2 Keyframes (elevation thresholds)

Files: `mockup/wall/sky-<key>.webp` (clear) and `sky-<key>-oc.webp` (the overcast step) for the 8 keyframes and `horizon`, 1920x1080, about 380 KB each at quality 93 (noise is what makes them big; on the device ship them at 960x540 or 1280x720 RGB_565 and let the GPU scale: a smooth gradient survives it, and each bitmap is then 1 to 2 MB in memory, four live at most). Gradients are interpolated in **OKLab** with a monotone cubic through the stops (no kinks, no bands, a pink never passes through grey on its way to blue), plus one soft horizon glow, plus **1.5 % noise** (luminance-weighted) so 8-bit banding cannot appear. Only `night` carries stars: about 95 static, faint, 1 to 2 px dots in the upper 62 %, none in the status-strip band. **`sky-night-oc.webp` has none** (fix round 2: it is made from the star-free base, so cloud and rain hide the stars; fog, which only brings the overcast set in at .30 plus a veil, shows them very faintly).

| Keyframe | Theme drawn under | Holds while elevation is | Bengaluru 20 Oct | Look |
|---|---|---|---|---|
| `night` | dark | below -17 (rising: below -16) | 19:05 to 05:07 | deep navy, a lighter blue haze at the bottom, stars; moon glow |
| `dawn` | dark | -7 .. -6.4 rising | 05:45 to 05:48 | deep blue, violet, then rose and peach at the left (east) horizon |
| `sunrise` | flips here (hand-over frame, mirror of `horizon`) | -2.4 .. -1.4 rising | 06:04 to 06:07 | the sun on the east horizon: violet-blue top, plum, rose, peach low-left. Replaces the straight dawn > morning blend, whose half-way point was a dusty slate-blue (shot `sky-flip-sunrise-0610.png`) |
| `morning` | light | 0.03 .. 8 rising | 06:12 to 06:47 | soft clear blue, a pale warm haze low-left |
| `noon` | light | above 45 | 09:29 to 14:39 | bright near-white blue, a faint warm glow high in the sky |
| `afternoon` | light | 22 .. 14 setting | 16:22 to 16:56 | clear blue, slightly warmer, warm haze low-right |
| `golden` | light | 6 .. -0.07 setting | 17:31 to 17:56 | pale violet-blue top, peach, amber, gold at the right (west) horizon |
| `horizon` | flips here (not gated in its own theme) | -1.47 .. -2.48 setting (a hand-over frame) | 18:02 to 18:06 | the sun on the horizon: violet-blue top, plum, rose, then coral and peach-orange in the lowest 16 % only. Dark enough everywhere above y 72 % for white text; the vivid part lives where only tile art sits (§ below) |
| `sunset` | dark | -4.85 .. -5 setting | 18:16 (a brief hold; it fades to dusk by 18:37) | indigo, deep violet, magenta, then orange; the orange lives in the lowest 12 % only |
| `dusk` | dark | -10 .. -12 setting | 18:36 to 18:45 | dark indigo to violet, a muted rose glow at the bottom right |

Hold elevations used by the mockup's presets (T key, `?sky=`): dawn -6.7 rising, sunrise -2.4 rising, morning +4 rising, noon = solar noon, afternoon +18, golden +3, horizon -2.5 setting, sunset -5.2, dusk -11, night -25 (setting side).

**Why the windows sit where they do, and why `horizon` / `sunrise` exist (fix round, measured).** The UI text colour flips at the Rev 4 rule (a time rule: `theme-flip-at` .43 of the 30-minute cross-fade, which lands at elevation about **-0.8 rising and -0.9 setting** at Bengaluru). A sky at the flip is mid-luminance for *both* text colours, and the narrow band where neither reads is L (relative luminance) .183 .. .225 (white needs L <= .183 for 4.5:1, the old `#1B1B1F` needed L >= .225). Two things close it. (1) **The flip text colour is the strongest the set has**: at the halo peak the text rises to `--text-hi` (dark set `#FFFFFF` at alpha 1, light set `#08080B`, alpha 1; before, alpha .92 / .94 `#1B1B1F`), which moves the dark-text need down to L >= .175: the two bands now overlap and the gap is gone. (2) **The top band crosses L ~ .19 at the flip**, found by measuring the raw sky (all text transparent, halo off) minute by minute and sliding the windows until the last dark-theme minute and the first light-theme minute both clear 4.5:1: `sky-sm` (sunrise > morning) **-1.37 .. 0.03** and `sky-gs` (golden > horizon) **-0.07 .. -1.47**, each about **3.5 minutes** (fix round 2; round 1 had 14-minute windows, the first version 6 and 2 minutes). Why so narrow: the sky has to cross L ~ .19 *between* two adjacent minutes (the last dark-theme minute needs a sky light enough for dark text on one side, the other a sky dark enough for white text), and the more a window drifts through mid-luminance the less the sky moves between those two minutes. An independent pixel check at target 4.6:1 in all four weathers passed on neither a 14-minute (clear 17:59 4.18 .. 4.4) nor a 5-minute window, and passed on a 3.5-minute one; the position was found by moving the window in 0.03 degree steps (0.06 degree is worth about 0.2:1). **Overcast frames of the four hand-over keyframes (`golden`, `horizon`, `sunrise`, `morning`) are flattened only 8 % (not 25 %) toward the mean**: the flattening lifted the dark `horizon` top band and lowered the light `golden` one, squeezing exactly the step the flip needs, and made cloud and rain the worst weathers. The cost is a quicker colour change around sunrise and sunset (about 6 mean |d| a minute through the window, 13 in its last minute 06:11 to 06:12; `tools/check_sky_handover.py` shows these steps never flash). A straight golden-amber to sunset-indigo blend is a flat mauve-grey at its half-way point, and a straight dawn-to-morning blend is a dusty slate-blue, so each side passes through a vivid hand-over frame (`horizon` / its mirror `sunrise`), whose **mid bands are kept dark** (L <= .17 up to y 72 %): white text (the focus caption sits at y ~800) still reads on it just before the flip. The halo (§ 9.8) carries what is left.

### 9.3 Cross-fade rule (`SkyPhase.java`, pure Java, host-tested)

```
e, az = solarPosition(lat, lon, date, utcOffset, minuteOfDay)        // NOAA, refraction-corrected e
rising = minuteOfDay < solarNoon
path = rising ? [night>dawn, dawn>sunrise, sunrise>morning, morning>noon] : [noon>afternoon, afternoon>golden, golden>horizon, horizon>sunset, sunset>dusk, dusk>night]
for (from, to, a, b) in path:                 // a, b = the window's tokens sky-XX-a, sky-XX-b (degrees)
    f = (e - a) / (b - a)                     // works for a rising sun (a < b) and a setting one (a > b)
    if f <= 0: return hold(from)
    if f <  1: return mix(from, to, smoothstep(f))     // draw `from` at alpha 1, then `to` at alpha smoothstep(f)
return hold(last)
```
Windows (tokens `sky-nd-a/b` ... `sky-dn-a/b`): night>dawn -16 to -7, dawn>sunrise -6.4 to -2.41, sunrise>morning -1.37 to 0.03, morning>noon 8 to 45, noon>afternoon 45 to 22, afternoon>golden 14 to 6, golden>horizon -0.07 to -1.47, horizon>sunset -2.48 to -4.85, sunset>dusk -5 to -10, dusk>night -12 to -17. **Low-sun days:** windows above 3 degrees are multiplied by `min(1, emaxToday / 50)` (floor .15) so London in December still reaches `noon` and the solar-noon hand-over from the rising to the setting path is continuous (the pinned `noon` keyframe holds from `emax` down). **Polar:** `e` is clamped, the path just holds the first or last keyframe.
**Hand-over (no flash).** When a window ends and the next begins, the new pair's A is the old pair's B (dawn>sunrise becomes sunrise>morning at 06:03 to 06:04, golden>horizon becomes horizon>sunset, horizon>sunset becomes sunset>dusk, ...), and the picture on screen already *is* that keyframe. Rule, in `SkyView` / `SkyPhase.nextPair`: **if `new.a == old.b` and `new.a != old.a` and `old.f >= 0.9`, swap the layer roles with no animation for that one frame**: `skyA` takes the bitmap that `skyB` showed, `skyB` takes the new B bitmap, `skyB` alpha = the new `f`; do the same for the scrim pair (`scA` / `scB`) and for the overcast (`-oc`) pair. On the device that is: swap the two `Bitmap` references and set the alpha without starting an animator (cancel a running one). Without it `skyB` got the next keyframe at alpha ~1 and faded to 0 over 900 ms, which flashed a slate-blue morning sky under white text at 06:03 to 06:04 and an orange-grey one at 18:05 to 18:06 (mean |d| 33 and 19 at 120 ms against end states 4.3 and 0.3; shot `sky-rev3-flash-fixed-0604-mid.png`). The 900 ms fade stays for time jumps (T key, slider, `ACTION_TIME_CHANGED`) and weather changes (those never swap layers). **Check:** `tools/check_sky_handover.py` finds every hand-over minute of the day (the ten for Bengaluru on 2026-10-20) and, with only the sky visible, steps the clock one minute in clear / cloudy / rain / fog and compares frames at 0, 60, 120, 300, 600 and 1100 ms with the frame before the step: no frame may differ by more than max(5, the step's own end-state change + 2). Measured: 0.0 to 2.5 at every hand-over except the last minute of the sunrise window (06:11 to 06:12, a real 13 to 15 change that the fade only spreads, overshoot < 0.3). Mutation check: with the swap disabled it fails (23.5 at 18:15 to 18:16 clear).

Recompute on the existing 30 s theme tick (and on resume, city, date and time-change broadcasts). At 0.24 degrees a minute the mix moves under 1 % per 30 s tick away from the narrow windows, so no per-frame work exists. A time jump (T key, slider, `ACTION_TIME_CHANGED`) cross-fades over `sky-fade-ms` (900 ms): alpha animation on two `ImageView`s, nothing else. Reduced motion: no fade.

**Status-strip scrim (row >= 1 focused).** The scrim stays **opaque to y 120** and clear by 190, but is no longer the flat `--bg` slab: it is the sky's own top colour. `make_sky.py` writes a top RGB per keyframe (mean of rows 30 .. 130 of each bitmap, clear and `-oc`) into `tokens.css` (`--sky-top-<key>[-oc]`); the scrim is the same layer stack as the sky (keyframe A, B at alpha `f`; the overcast pair at alpha `w`; the fog veils at the same alphas; the rain dim) in those flat colours, so contrast is decided by the same sweep. Device: one `GradientDrawable` colour pair (top colour to the same colour at alpha 0) per keyframe, alpha cross-faded like the bitmaps, no animated colour. Static walls / Safe mode keep the `--bg` scrim.

Device drawing (`SkyView`, one custom View, no child views): draw `skyA` (opaque), `skyB` with `Paint` alpha `f`; when overcast, the same two from the `-oc` set on top with alpha `w`; then the veils, the glows, the drops. Four full-screen bitmap draws at worst, **no shader, no blur, no `RenderEffect`**. The glass slices (dock band, panel, sheet) keep their 240x135 pre-blurred copies: build one for the current (A, B, overcast) triple whenever it changes (every few minutes at most) and draw it with the `f` mix exactly as Rev 4 did with the day wall.

### 9.4 Weather tint rule

| Condition (WMO code) | Overcast set alpha `w` | Extra | Sun / moon glow x |
|---|---|---|---|
| clear (0, 1) | 0 | none | 1 |
| cloud (2, 3) | `wx-oc-cloud` 1 | none | `wx-glow-cloud` .35 |
| rain (51 to 67, 80 to 82, 95 to 99) | `wx-oc-rain` 1 | black `rdim` alpha `wx-rain-dim` .01 (more black pushes the flip minutes under 4.6:1), **a day slate veil** `rdimD` (`wx-rain-day` 96,110,136 at `wx-rain-day-a` .20 x `smooth((p - flip)/(1 - flip))`: 0 at the theme flip, full by day, the same shape as the fog day veil, so a rainy day is visibly darker than a cloudy one), the drops layer | 0 |
| fog / haze (45, 48) | `wx-oc-fog` .30 | soft veil | `wx-glow-fog` .45 |

- **Overcast = one step**: each `-oc` bitmap is its clear twin with colour pulled **45 % toward its own luminance**, the vertical contrast flattened 25 % toward the mean (**8 %** for `golden`, `horizon`, `sunrise`, `morning`, § 9.2), and **3 % darker by day / 14 % darker by night** (`sunrise` 6 %, so its overcast twin keeps the white-text side of the flip) (a bright day sky must stay bright: dark text sits on it). It is a baked image, so there is no live colour change; the clear to overcast change is an alpha cross-fade of the `-oc` pair (900 ms). Pairs, not per-pixel maths, are what keeps this inside "pre-rendered bitmaps cross-faded by alpha".
- **Fog veil**: a flat colour layer drawn with Paint alpha only (a `drawColor`, no bitmap): `255,255,255` at alpha .42 x `smooth((p - flip) / (1 - flip))` (day) plus `150,160,184` at alpha .20 x `smooth((flip - p) / flip)` (night), with `flip` = `theme-flip-at` .43. **Each veil is 0 exactly at the theme flip** and full only at its own end: the hand-over frame is mid-luminance and a veil that lifts it failed both text colours (the old `p` / `1 - p` weights left a .17 white veil at 18:00 and measured 3.8 .. 4.2:1 in fog). A night fog never becomes a white veil that white text would drown in.
- Weather off or offline: the sky is the clear one (no data, never an error).
- The Open-Meteo condition comes from the same call as the chip (§ 9.6); a change fades in over 900 ms.

### 9.5 Rain drops layer (recipe and cost)

- **Two 384 x 384 RGBA tiles, the same geometry, picked by theme**: `rain-tile.png` (white-blue `246,250,255`, for the dark sky, layer alpha `rain-alpha` .25) and **`rain-tile-light.png` (slate `60,80,110`, layer alpha `rain-alpha-l` .25 x `smooth((p - flip)/(1 - flip))`, so it is 0 at the theme flip and full by day)**. White streaks at .25 did not show on a bright overcast day sky (fix round 2: slate ones do, shot `sky-rev3-rain-1000.png`); the shader bitmap is swapped at the theme flip, same layer, nothing else changes. Tile detail (the white one; the light one is identical in shape) (about 15 KB): 46 streaks, 22 to 46 px long, about 1.5 px wide, **12 degrees off vertical**, tails fading in toward the head, white-blue `246,250,255`; drawn wrapped in both axes, so it tiles seamlessly. Generated by `make_sky.py rain_tile()`.
- **One layer**, `width = canvas`, `height = canvas + 384`, filled with the tile (`BitmapShader` REPEAT_REPEAT on the device), **layer alpha `rain-alpha` .25**, `translationY` animated 0 to 384 px, linear, `384 / rain-speed 260` = **1.48 s per loop**, infinite. Seamless because the tile repeats every 384 px vertically. Only while it is **raining now** (`weather_code` in the rain set) **and** Orchard is the resumed home screen; the animator is cancelled in `onPause`, on the screensaver, and when Reduced motion is on.
- **Reduced motion**: static, the same layer at `translationY` = half a tile, same alpha.
- **Cost**: one 384 px tile bitmap (590 KB ARGB_8888, or 147 KB if the tile is made 192 px and drawn at 2x; the mockup number is the safe one), one full-screen `drawRect` with a repeat shader per frame **only while raining**, no extra offscreen layer, no per-drop objects. It animates `translationY` of a View (a RenderNode property: no `onDraw` re-run), so the Stick composites one extra textured quad at 60 fps. Measure with `dumpsys gfxinfo` during the G4 pass; if janky, drop the loop to 30 fps by stepping the animator (`TimeInterpolator` quantised to 2 frames), or fall back to the static layer.
- The drops are white at .25 over the (already dimmed) dark rain sky, slate at up to .25 over the light one (review leftovers round: was .34, too heavy by day). `check_contrast.py` renders them (every rain state of its sweeps has the layer on, with the animation frozen at its start position), so the gate includes their lift of the backdrop behind text.

### 9.6 Open-Meteo fields (extends § 7.2)

`https://api.open-meteo.com/v1/forecast?latitude=<lat>&longitude=<lon>&current=temperature_2m,weather_code,is_day&hourly=precipitation_probability&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1`

- **`current.weather_code`** (WMO, already used for the glyph) now also drives the sky tint (§ 9.4) and "raining now" (51 to 67, 80 to 82, 95 to 99).
- **`hourly.precipitation_probability`** (24 integers, % for each local hour of today; `forecast_days=1`): the chip's `Rain 70 %` while raining uses the **current hour's** value; the sub-line's `Rain likely from HH:00` is the first **upcoming** hour (after the current one) with a value `>= 40`; the current hour is the chip's `Rain 70%`, never a forecast. The body stays tiny (24 values); the 8 KB cap in § 7.2 still holds.
- **Cache 30 minutes** (`wx-refresh-min`), unchanged rule: one `weather.json` holds current + hourly + daily; no retry loop; stale after `wx-stale-min` (180 min) hides the chip **and** the sky falls back to the clear one; `Settings → Weather` off makes no request and the sky is clear. The sky itself needs **no** network: the elevation is pure maths, so a TV that is offline still shows the right time-of-day sky, only the weather tint is missing.
- Units, sunset on the chip: unchanged (§ 7.2).

### 9.7 Clock polish (the Rev 5 review leftovers, done)

1. **Colon = two circles**, **26 px** diameter (the Inter Thin stem is 17 px, so 1.5x; the old font glyph dots measured 40 px and were heavy next to Thin figures), centres **190 px** apart (1/3 and 2/3 of the 560 px digit height, symmetric about the middle; the glyph's dots sat at 180 and 543, the lower one on the baseline). Cell width unchanged (`dream-colon-cell` .2686 em + tracking). Tokens `dream-colon-d 26`, `dream-colon-gap 190`. **Device: two `drawCircle` calls per tick, no colon glyph**, so `subset_fonts.sh` for `inter_thin.ttf` keeps **digits only** (`--unicodes=U+0030-0039`, no `case` / `calt` features needed): the § 8.2 "verify the raised colon survives subsetting" step is gone.
2. **Edge margin**: the colon change moved the group, and a first measurement showed `00:00` at -12 px only **88 px** from the left edge. Retuned `dream-track` -0.115 to **-0.125 em** and `dream-nudge` -0.045 to **-0.031 em**; measured on the real render: **`00:00` 118 px left / 114 px right, `08:08` 118 / 109 at the -12 / +12 px extremes**, and over every possible first digit (0, 1, 2) and last digit (0 to 9) the worst anywhere is **104 px** (`00:02` at +12). Requirement: **at least 96 px**. The `ClockFormat` host test (§ 8.2) now asserts it.
3. **Dusk text halo softened**: `halo-cap` (the alpha cap of the dark halo behind white text at dusk and before sunrise) **.40 to .30**, **in every weather** (token; the pass count is `halo-passes`, now 4, see § 9.8). Fix round 1 had kept .40 whenever it was cloudy, rainy or foggy (`halo-cap-wx`); that exception is **removed** (the dark smudge around `Netflix / Opened 2 h ago` in cloud at 18:00 was clearly visible). The **pale halo behind dark text** on the light side of the flip has its own cap `halo-cap-light` **.40** (a pale smudge on a light sky is far less visible than a dark one, and the contrast of dark text at the flip needs it). The text colour rises to `--text-hi` over the first half of each cap (`--tn = min(1, ts / (cap * tn-ramp .5))`), so every strip element, the Options hint included, is at full strength as the halo builds. Every element clears 4.6:1 in the sunset +-16 / sunrise +-16 minute-by-minute render sweep (§ 9.8).
4. **§ 8 shot reference** now points at `dream-rev2-*.png` (§ 8 header, done). The `ClockFormat` worst case (`00:00` and `08:08` at -12 / +12 px, >= 96 px of ink margin) is in § 8.2's host-test list.

### 9.8 Contrast gate (`tools/check_contrast.py`, extended)

Exit 1 below **4.6:1** for every element (fix round 2: WCAG asks 4.5; the gate aims 0.1 higher because an independent pixel check, which measures the glyph core against the 2nd-percentile backdrop in the tight *ink* box instead of the text box, reads 0.1 to 0.2 lower on the same state, and the first fix round's 4.53 / 4.68 / 4.66 became 4.34 / 4.45 / 4.48 there). **No large-text 3:1 relaxation any more**: the shelf title and the focus label need the full target. A full run is about 25 minutes (`--static` skips the sweeps, `--flips` skips the 30-minute grid, `--no-row1` skips the second-row pass). Four gates now run:

1. Rev 4 gate (unchanged): light tokens over the static day wall (a fixed Light theme), glass, shelf wash.
2. **Sky keyframes, static**: the 8 keyframes (not `horizon`, a transitional frame that only the sweep covers) x {clear, overcast, rain-dimmed}, each against **its own theme's** text colours, over the **worst 2 %** of the pixels in each real text zone (status strip, shelf title / subtitle, the focused-label band, the dock-row tile labels) plus the glass-thick panel / sheet and the Settings rows and left column through the `sky-dimmer` (.70); dark keyframes use the lightest pixels, light ones the darkest. The 2 %-mean (not the single most extreme pixel) is deliberate: one noise speck or star is not something a glyph sits on.
3. **Rendered sweeps** (Playwright, 1920x1080, transitions off so every state is final; one "text shown" and one "all text transparent" screenshot per state, glyph-core pixels vs backdrop): **(A)** **every minute** across sunset -16 .. +16 and sunrise -16 .. +16 in **all four weathers** (the old sweep stepped every 4th minute in cloudy / rain / fog and never landed on 18:00 in fog, the flip minute), run **twice**: grid row 0 focused, and grid row 1 focused (the opaque status-strip scrim is then on and the focus label sits one row lower); **(B)** every 30 minutes round the clock x clear / cloudy / rain / fog, again in both row states; **(C)** the 8 keyframe hold times. Elements: shelf title, subtitle, greeting, date, clock, Options hint, weather caption, rain chance, high / low, temperature, **focus label** (`#flabel`). The sweep fails if any of these zones was never measured (the first fix-round gate silently skipped `#flabel`: with animations off it stayed at its opacity-0 start frame). **Strict metric**: per element the gate takes the glyph colour (mean of the glyph-core pixels) against the **2nd-percentile worst backdrop pixel anywhere in the text box** (text transparent, halo kept), not just the pixels under the glyph core; the lower of the two ratios counts. **Every element** (shelf title and focus caption included) needs 4.6:1; the round-1 3:1 large-text rule is gone (it let the focus label pass at 3.09 and the title at 4.14, against the user's wording 'every element still clears 4.5:1'). The static keyframe gate also applies the rain-day slate veil to the light keyframes' rain variant.
4. Informational: the fixed Dark theme over the static dusk wall (predates Rev 4).

Result (2026-10-20, Bengaluru, **review leftovers round**): **PASS**, full run, exit 0, every element >= 4.6:1, with `rain-alpha-l` .25, `halo-cap-light` .40, `halo-passes` 4 (both stacks in `index.html` have four layers) and `hero-tw-cut` .85. Worst rendered contrast per element over every state of the sweeps (clear / cloudy / rain / fog, row 0 and row 1 focused, every minute at both flips, 30-minute grid, keyframe hold times): shelf subtitle 4.81 (clear 18:00), shelf title 4.69 (clear 18:00), greeting / date 5.26 (row 1 fog 05:56), clock 5.22 (rain 17:59), Options hint 4.87 (clear 06:09), weather caption 5.22 (clear 06:09), high-low 5.20 (rain 17:59), temperature 4.94 (row 1 clear 18:00), rain chance 5.09 (rain 06:10), focus label 4.61 (rain 17:59). Static keyframes worst 4.69:1 (`golden/rain`). `tools/check_sky_handover.py` passes. What the leftovers round tried: the first attempt (`halo-cap-light` .30, six passes, `rain-alpha-l` .25) failed the focus label at rain 17:59 with 4.46, so the pale cap went back to .40 and the halo to four passes (the pale-halo smudge review item is met by the lighter pass count); the light rain alpha .25 stayed. Hero text at the four flip minutes (row 0, 06:09 / 06:10 / 17:59 / 18:00): shelf title worst 4.69 (clear 06:09 / 18:00 at 4.73 / 4.69), 5.4 to 6.0 at 06:10 and 17:59; shelf subtitle worst 4.81 (clear 18:00), 5.7 to 6.3 at 06:10 and 17:59. The title is under 4.8 at 06:09 and 18:00 in clear; lowering `hero-tw-cut` did not help (title worst over the four minutes at cut .85 / .80 / .75 / .70 / .65 / .60 = 4.69 / 4.69 / 4.65 / 4.69 / 4.62 / 4.56, and the flips sweep fails the 4.6 gate at .65 and .60), so it stays at .85, the value with the full-run pass. The independent ink-box check of fix round 2 (4.74 at worst) was not re-run this round. Previous round (fix round 2, kept for the record): shelf subtitle 4.95, shelf title 4.69, greeting / date / caption / high-low 5.26, clock 5.32, Options hint 4.84, temperature 4.94, rain chance 5.09, focus label 4.63; tokens that moved then: `sky-sm` -1.37 .. 0.03 and `sky-gs` -0.07 .. -1.47 (3.5-minute windows, § 9.2), `halo-cap-wx` removed, `halo-cap-light` .40 (new), `tn-ramp` .5 (new), the overcast flatten of the four hand-over frames 8 % (make_sky.py), the rain-day veil and the light rain tile (§ 9.4, § 9.5), `hb-sky` .45 (§ 9.10).

### 9.9 Device implementation notes

- **Files** (new): `SkyPhase.java` (the path / window maths of § 9.3, pure Java), extend `SunCalc.java` with `position(lat, lon, date, utcOffset, minute)` returning elevation and azimuth (twin of the mockup's `Sun.pos`; host test, values from the mockup run: Bengaluru 2026-10-20 12:03 elevation 66.67 azimuth 179.1, 06:11 elevation -0.27 azimuth 100.4, 17:58 elevation -0.40 azimuth 259.5, 23:59 elevation -87.2; New Delhi 2026-10-20 solar noon 12:06 elevation 51.0; all +- .3 degrees), `SkyView.java` (replaces `WallpaperView`'s three-layer draw when Theme = Auto; a fixed theme keeps `WallpaperView` as in Rev 4), `res/drawable-nodpi/sky_*.webp` (8 + 8, downscaled), `sun_glow.png`, `moon_glow.png`, `rain_tile.png`, `GreetingFormat` (pure, host-tested: 04:59 Good night, 05:00 morning, 11:59 morning, 12:00 afternoon, 16:59 afternoon, 17:00 evening, 20:59 evening, 21:00 night).
- **Fix round 2 device notes**: `SkyView` keeps `prevA / prevB / prevF`; when `newA == prevB && newA != prevA && prevF >= .9` it swaps the bitmap references (clear pair, `-oc` pair, scrim colour pair) and sets the alpha without an animator (cancel a running one), otherwise it animates alpha over 900 ms as before (§ 9.3). Extra assets: `rain_tile_light.png` (slate tile, picked at the theme flip), no new keyframe bitmaps. The day slate veil is one more `drawColor` with Paint alpha `.20 x smooth((p - .43) / .57)` (rain only), the light drops layer alpha is `.25 x` the same factor. `halo-cap` .30 / `halo-cap-light` .40 / `tn-ramp` .5 replace `halo-cap-wx`. `SkyPhaseTest` gains: the swap fires for every `new.a == old.b` pair of the path, never for a time jump (`old.f < .9`) or a weather change.
- **Host tests** (`SkyPhaseTest`): the hold and mix at every window edge for a rising and a setting sun, continuity at solar noon for emax 66 and for emax 14 (London December: no jump), the polar clamp, `f` monotone in `e`, the weather table of § 9.4, "rain likely" hour selection (none, a later hour, 40 exactly, **the current hour is skipped**, nothing after 19:00 falls back), the chip strings (`24° · Rain 70%`, `Rain likely from 16:00`, the fallback; there is no `Rain likely now`).
- **Glows**: sun x = `W * lerp(.08, .92, .5 + .5 sin(az - 180 deg))` (east on the left, west on the right, through the equator-ward side), y = `H * lerp(1.00, .12, (e / 70)^.8)`, alpha ramps from -1.5 to 5 degrees x `.60`; moon: no ephemeris, an arc by the fraction of the night elapsed, x `.28 to .66`, y `.52 up to .46 and back` (the band just above the dock row: the home backdrop hides the sky above y ~360 and fades out by 600, the hero card sits right of x 1280, the shelf title above y 400), alpha ramps from -4 to -12 degrees x `.90`. The moon tile is a tight bright core (r 11 px, cool white `208,224,255`) in a small blue halo (sigma 30 px) and a faint wide one (sigma 72 px), no broad grey skirt: a moon, not a smudge. Both multiplied by the weather glow factor. 720 and 360 px ARGB tiles (sun about 27 KB, moon about 5 KB on disk).
- **Memory budget on a 1.5 GB Stick** (estimate, measure on the device): 4 sky bitmaps (2 clear + 2 overcast, 1280x720 RGB_565 = 1.8 MB each), glow tiles about 2 MB, rain tile .6 MB, blurred slices unchanged: about 11 MB, released in `onStop` with the other wallpaper bitmaps.
- **Docs to touch when the device build lands**: § 0.8 (wallpaper), § 1.2 file map (`SkyPhase`, `SkyView`, `GreetingFormat`, the sky drawables), § 7.2 (weather URL: add `hourly=precipitation_probability`), `THIRD_PARTY_NOTICES.txt` (none: the sky is generated here, original), README feature list ("a sky that follows the sun").

### 9.10 Fix round 2 (2026-10-20): what changed and why

1. **No hand-over flash** (must): the layer-role swap of § 9.3, plus `tools/check_sky_handover.py` (mutation-checked: fails with the swap disabled).
2. **Strip elements at the flip** (must): the text colour reaches `--text-hi` over the first half of the halo cap (`tn-ramp` .5), the flip windows narrowed to 3.5 minutes and re-placed (§ 9.2), the overcast flatten of the four hand-over frames cut to 8 %, the pale halo cap `halo-cap-light` .40; the Options hint, greeting and weather caption are now 4.84 / 5.26 / 5.26 in the gate and 4.74 or better in the independent ink-box check (was 4.34 / 4.45 / 4.48).
3. **Daytime rain visible** (should): the light-theme drops are a slate tile (`rain-tile-light.png`) and a day slate veil (`rdimD`, `scDimD` on the status-strip scrim) darkens a rainy day one step below a cloudy one; both ramp in from 0 at the theme flip (a streak or veil at the flip costs dark text its contrast). Shots `sky-rev3-rain-1000.png`, `sky-rev3-rain-1759.png`, `sky-rev3-rain-0610.png`. Both are plain alpha layers; the device rules hold (no bitmap-per-frame, no live blur, no animated colour). Not done: a baked `-rain` bitmap step (the flat veil does the job with no new asset).
4. **No stars through cloud** (should): `sky-night-oc.webp` is made from the star-free base (`make_sky.py`). Fog night still shows a few very faint ones (its overcast alpha is .30), left as is.
5. **Halo** (should): the weather exception is removed, the dark halo cap is .30 in every weather (§ 9.7 item 3); the pale one is .40.
6. **Large text** (should): lifted, not relaxed. The focus label and the shelf title now clear 4.6:1 (4.63 / 4.69) and the gate no longer has a 3:1 rule (§ 9.8).
7. **Daytime Home reads as three different skies** (should): by day the light shelf backdrop is drawn at `hb-sky` .45 of its alpha while the sky is on (Theme Auto, Wallpaper Sky), so most of the screen shows the sky instead of a pink-white wash; `morning` and `afternoon` got stronger low warm glows (peach east-left, amber west-right). Shots `sky-rev3-morning.png`, `-noon.png`, `-afternoon.png`, `-golden.png`: soft blue, near-white, lavender over amber, amber. Contrast re-gated (§ 9.8).
8. **Clock leftovers** (§ 9.7 items 1, 2, 4): already done in round 1 and re-checked: colon circles 26 px, `00:00` / `08:08` at -12 px >= 96 px, the worst case is in the § 8.2 `ClockFormat` host test, § 8 points at `dream-rev2-*.png`.

### Rev 6 follow-ups (fixer round)

- APK budget is 1.6 MB (code still ≤ 400 KB; classes.dex is ~162 KB compressed). Re-export skies smaller with `tools/export_sky_assets.py` if it grows past that.
- Device tooling: use this repo's `scripts/launcher.sh` (it has `city` and `screensaver`) with `LAUNCHER_TV` pointing at the private `fire-tv-detox/scripts/tv.sh`. The older fork in `fire-tv-detox/scripts/launcher.sh` has neither command; leave it as is (its `undo.sh` calls it by name) and keep the private `tv.sh` out of this repo.
- Device round still to measure: `dumpsys meminfo` during a `debug_wx` flip (SkyView holds up to 8 sky bitmaps, ~17 MB, for the 900 ms fade; cap at 4 if PSS spikes against the 120 MB limit), TopShelfView's main-thread light/dark bake, and `gfxinfo` dock sweep (Janky < 10 %).
