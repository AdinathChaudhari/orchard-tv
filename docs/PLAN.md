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

- `ClockDream` (tv-vibehack idea, rewritten): clock Title 1 76 Medium drifting to a random spot every
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
| Permissions | **`WRITE_SECURE_SETTINGS` only** (granted once by adb) | Needed for the in-launcher reset. No network, location, usage stats, TV listings, notification access. |
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
        OrchardApp.java           Application: boot_attempts++ first line, installs CrashGuard, holds ArtCache
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
sign` (v2+v3) → `apksigner verify` → print size and `aapt2 dump permissions` (must list exactly one
`uses-permission`). Output: `launcher/app/build/orchard.apk`. Target size ≤ 1.2 MB (fonts + 3
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
  before any handler exists): line 1 of `OrchardApp.onCreate()` does `state.boot_attempts++` (sync write to
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
`launchable-activity` with both categories and `targetSdkVersion:'30'`; APK ≤ 1.2 MB;
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
