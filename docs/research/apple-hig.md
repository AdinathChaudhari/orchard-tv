# tvOS HIG and Apple design principles, distilled for an Android TV launcher

**Source reliability.** Apple's HIG pages (developer.apple.com/design/human-interface-guidelines/...) are JavaScript-rendered. WebFetch returned only page titles, so none of the numbers below were read from the live pages.
- The fetch model's own recalled typography table was wrong. It said Body is 39pt, but Apple's published tvOS styles are listed below.
- Confidence tags: **[H]** I'm confident (published Apple values). **[M]** recalled, verify before treating as binding. **[D]** my derived recommendation, not an Apple number.
- Only the WWDC25 "Meet Liquid Glass" principles (session 102, https://developer.apple.com/videos/play/wwdc2025/102/) were confirmed by search.
- Next step for the orchestrator: have the Fable planner treat [M] items as tunable constants and check them against screenshots on the device.

## 1. Core principles

**Classic Apple principles (clarity, deference, depth):**
- Content is the hero and chrome recedes. For a launcher, apps and artwork lead, and the UI around them is quiet.
- Depth is conveyed by layering, shadow and parallax. It is not conveyed by borders or outlines.
- Clarity: one focused thing at a time, large legible type, no clutter.

**Liquid Glass (WWDC25, session 102) [H on names, M on detail]:**
- **Hierarchy:** controls and navigation sit on a functional glass layer above the content layer and draw attention to the content.
- **Harmony:** the shapes and feel of the UI echo the hardware and the content, so it feels cohesive.
- **Consistency:** shared language across platforms and sizes, with each platform keeping its own character.
- Rules to apply:
  - Glass is for controls and chrome only, never for content tiles.
  - Don't stack glass on glass.
  - Keep one consistent corner radius family.
- **API 30 constraint:** no live blur. Fake glass with a pre-blurred wallpaper bitmap, a white overlay of about 8–14% alpha, a 1dp hairline highlight at about 18% white, and a soft shadow.

## 2. Focus and selection (the core of the tvOS feel)

- **Exactly one item is focused at all times.** Focus never disappears, there is no pointer, and the first launch lands on a sensible default (the first app tile, or the last-used one).
- **Focus is shown by lift, not by outline.** The focused item gets:
  - A scale-up. System app icons go 400x240 to 440x264, which is **1.10x** [M]. Cards and posters use about 1.05–1.10x. [D]
  - A larger, softer shadow that suggests the item is lifting. [D: blur about 24–32dp, y-offset about 12–16dp, alpha about 0.35–0.45]
  - A specular "shine" and parallax tilt on layered art.
  - Siblings do not dim. At most, defocused items drop slightly in contrast.
- **Focus-engine rules:**
  - Movement is spatial, to the nearest item in the swipe direction.
  - Rows remember the last-focused column when you re-enter them.
  - Focus guides or sections bridge uneven layouts, for example a dock row below a ragged grid.
  - Never trap focus. Every screen has a Back route.
  - Wrap-around is not the default. Stop at the edges and give a tiny "bump" animation (4–6dp nudge). [D]
- **Timing [D]:**
  - Focus-in animation 200–250ms with a decelerate (ease-out) curve. Focus-out is about 150–200ms.
  - Use a spring or `DecelerateInterpolator(1.5)`.
  - Never block input during animation, and cancel and retarget on rapid D-pad repeats.
  - Fast D-pad holds should skip intermediate animations.
- **Selection vs. focus:** press (click) gives a brief press-down (scale about 0.97 for 80ms [D]), then the action runs. Long-press opens a context or edit mode (reorder, remove, jiggle).

## 3. Layered images and parallax

- tvOS app icons are **2–5 layers** [H]. The back layer is opaque and full-bleed, and the others are transparent.
- Sizes [M]:
  - Home-screen icon: **400x240pt**, shipped as 800x480px.
  - App Store icon: **1280x768px**.
- Parallax tilt on focus is driven by the Siri Remote touch surface (about ±15° for the icon effect [M]). With a D-pad there is no touch, so synthesize it:
  - On focus, apply a short 2.5D settle: layers shift by a few dp depending on depth, over about 300ms.
  - Optionally add idle "breathing" drift of about ±2dp over 4s on the focused tile only. This is cheap with `translationX/Y` on 2–3 child views, but disable it on low frame rate.
- Layer cap: **3 layers** (background, middle, foreground or logo) is enough on this hardware. Keep each tile at about 1 bitmap plus a logo.
- Corner radius: icons use a continuous ("squircle") rounded rectangle, about **12–14pt** on a 240pt-tall icon [M]. Apply the same radius to the focus shadow and shine so they match.
- Do not use Apple icons, wallpapers or aerial videos. Use "Apple TV-style" naming. Draw original gradient wallpapers and neutral generated icons.

## 4. Top Shelf

- Image sizes [M]:
  - Standard: **1920x720** (3840x1440 @2x).
  - Wide: **2320x720** (4640x1440 @2x).
  - tvOS 13+ shows full-bleed dynamic content on the top row, with a carousel or sectioned content row.
- Launcher equivalent: when the first-row app is focused, the top ~45% of the screen shows a big hero band. It carries a backdrop of the app's art or a wallpaper-derived gradient, plus app title and subtitle.
- Do NOT request TV-listings or recommendations permissions. Use static per-app hero art or a user-picked wallpaper, plus local "recently used" data.
- The hero crossfades over about 250–300ms as focus moves between apps, with a 120ms debounce so fast scrolling doesn't thrash bitmaps. [D]

## 5. Safe area and layout

- **tvOS safe area [H]:** canvas is **1920x1080pt**, with **60pt** top and bottom and **80pt** left and right. Keep all interactive and text content inside it. Only backgrounds and artwork bleed to the edges.
- **Android mapping [D]:** Fire OS renders at 1080p, usually at around 213 or 320 dpi, so dp dimensions vary. Verify first with `adb shell wm size; wm density` (read-only but still an adb command, so run it at build time, not in the research phase).
  - Do all layout in a scale factor `s = screenWidthPx / 1920`.
  - Margins are then 80s horizontal and 60s vertical in px. Google's TV guidance of 5% (48dp x 27dp on a 960x540dp screen) is equivalent.
- **Grid [M]:** tvOS uses uniform columns with at least **40pt** between items. Typical top-row shelves show a **row of 5–7 app icons at 240pt tall**, with about 40–50pt gaps. Big home screens use rows with a title (Title 3/Headline) above.
- **Row rhythm [D]:**
  - Row pitch is about icon height plus 100–140pt, so the focus scale and shadow have room and rows aren't clipped.
  - Never clip a focused tile. Use `clipChildren=false` and `clipToPadding=false` on parents, and leave about 24dp of padding around a focused tile.
- **Home layout:**
  - Top status strip: clock, date and Wi-Fi at the right edge of the safe area, Headline size.
  - Hero band for the focused item.
  - Row 1: app shelf with a large dock-style row of icons.
  - Row 2: optional shortcuts (Settings, Reset to Fire TV, Inputs).
  - The dock is 6–8 visible tiles. Horizontal scroll with focus-centered ("center-locked") scrolling after the third tile is a good feel. [D]

## 6. Typography (10-foot)

tvOS system text styles, in SF Pro (pt on a 1920x1080 canvas) [M]:

| Style | Size | Weight |
|---|---|---|
| Title 1 | 76 | Medium |
| Title 2 | 57 | Medium |
| Title 3 | 48 | Medium |
| Headline | 38 | Bold |
| Subhead | 38 | Regular |
| Body | 29 | Medium |
| Callout | 31 | Medium |
| Caption 1 | 26 | Medium |
| Caption 2 | 23 | Medium |

- Rules:
  - Smallest legible text is about **23pt**. Nothing below that.
  - Prefer Medium or Bold. Light and Thin weights vanish on TVs.
  - Tight tracking on big titles, line length under about 60 characters.
  - Use up to 2 text lines for labels and truncate with an ellipsis.
- Use `Typeface` default (Roboto) or bundle an open font such as Inter. SF Pro can't be bundled. Roboto Medium at the sizes above is close enough.
- Convert via `sp = px / (fontScale*density)`. Simpler: use `TypedValue.COMPLEX_UNIT_PX` with `s * ptValue`.

## 7. Color, dark mode, materials

- tvOS defaults to a dark, content-forward appearance. Light mode exists, but the home is mostly dark, and a launcher should default to **dark**.
- Palette [D]:
  - Primary text: white at 92–100% alpha. Secondary text: white at 60%. Tertiary text: white at 40%.
  - Background: pre-blurred wallpaper darkened by about 25–40% with a bottom gradient scrim so the labels stay legible.
  - Accent: one color only, used for toggles and the focus tint. Apple's system blue is about `#0A84FF`. Choose our own accent to avoid trade-dress issues.
- Never rely on color alone for state. Focus has shape or scale cues too, and contrast stays at least 4.5:1 for text.
- Avoid pure white (`#FFF`) large fills. TVs bloom and overscan. Use `#F2F2F7`-ish.
- Keep color banding down. Dither gradients, and bake them into the wallpaper bitmap with noise.
- **Materials:** use 3 translucency tiers [D]. Ultra-thin chrome is white at about 8%, regular glass is white at about 14% with a hairline, and thick (for settings panels) is black at about 45% over the blurred bitmap. Use one blur radius of about 25dp, pre-baked at about 1/4 resolution (480x270) and scaled up.

## 8. Motion and animation

- Principles: motion is purposeful, smooth, interruptible and never decorative-only.
- Durations [D]:

| Interaction | Duration |
|---|---|
| Focus scale | 200–250ms |
| Screen push or pop | 300–350ms |
| Hero crossfade | 250–300ms |
| Panel open | 250ms, decelerate |
| Panel close | 200ms, accelerate |

- Easing: `DecelerateInterpolator` or `PathInterpolator(0.2, 0, 0, 1)` for entering, `AccelerateInterpolator` for exiting. A spring (`SpringAnimation`, damping ratio about 0.7, stiffness about 300) works for the lift.
- Budget for this Stick:
  - Animate only `scaleX`, `scaleY`, `translation` and `alpha`. These are hardware-layer cheap.
  - Set `setLayerType(LAYER_TYPE_HARDWARE)` only during animations.
  - No animated blur and no per-frame bitmap work.
  - Target 60fps, and degrade to no parallax drift if frames drop. Check with `dumpsys gfxinfo <pkg>` janky-frame percentage on the real device.
- Honor the "Reduce motion" accessibility setting by replacing scale and parallax with a crossfade.

## 9. Remote and input

- The Siri Remote has a click-pad (swipe, click), Back (Menu), Play/Pause, TV (Home) and Siri buttons. The Fire remote is D-pad plus Select, Back, Home, Menu, and media keys.
- Mapping [D]:

| Remote | Result |
|---|---|
| D-pad | Spatial focus move |
| Select | Launch |
| Long-press Select | Context menu (move, remove, details) |
| Back | Leave panel or mode, then return to the top of home, then do nothing on home itself |
| Menu (the 3-line key) | Quick panel (settings, reset) |

- Back on the root home screen must **not** close the launcher (the launcher would flash and relaunch, a bad loop). Consume it silently.
- Hold-to-repeat must be supported. Accept D-pad key repeats and throttle animation, not logic.
- Never require typing, text entry or gestures that need a touchpad. Offer big on/off toggles and a few stepper choices.

## 10. Accessibility

- Text at least 23pt, with contrast of at least 4.5:1 for body text and at least 3:1 for large text.
- Provide `contentDescription` on every focusable tile, and make sure focus order matches visual order, so TalkBack (the Fire TV "VoiceView") reads sensibly.
- Respect Reduce Motion, `Settings.Global.ANIMATOR_DURATION_SCALE` and a bold-text option. Offer larger tile and text scale options.
- Don't make anything time-limited. Don't auto-dismiss a panel that holds the user's reset action.

## 11. 10-foot UI general rules (Google TV, Material TV, Amazon Fire TV)

- Assume 10 feet viewing distance, a 5% overscan safe zone, and no precision input.
- Fire TV launcher specifics:
  - Declare `android.intent.category.HOME` and `LEANBACK_LAUNCHER`, and use `android:banner` (320x180dp) for apps without an icon.
  - Look up apps via `getLeanbackLaunchIntentForPackage` first and `getLaunchIntentForPackage` as a fallback.
  - Don't use `monkey`. Use `am start -n`.
- Keep the visible top-level choices to 7 or fewer per screen, and flatten navigation to 2 levels.
- Acknowledge every input within 100ms, even if only by moving focus.
- Don't use hover, tooltips or small close buttons.

## 12. "Return to original Fire TV" (design guidance)

- Make it a first-class row item, not buried. Put "Original Fire TV" in the dock's last tile and also in the quick panel.
- Behavior [D]:
  1. Tap shows a confirm sheet with two choices ("Use Fire TV home until I switch back" and "Cancel"), with focus defaulting to the safe choice.
  2. Confirm launches Amazon's home. Because disabled Amazon packages need re-enabling, the plan must include a restore script on the Mac and an in-launcher path that does the least privileged thing possible.
- The long-press-Home escape stays outside our launcher entirely, so a crash can't remove it.
- Dispose of any dangerous toggles (disabling Home on Fire's redirect) behind this one clear action. Show what state the Stick is in as a small status chip ("Custom home on").

## 13. Actionable checklist (exact numbers)

1. Layout base 1920x1080, scale `s = widthPx/1920`. Safe area is **80px sides and 60px top and bottom**, scaled by `s`.
2. Min gap between tiles **40**, preferred row pitch icon height + **100–140**.
3. App tile **400x240** base (focused **440x264**), corner radius about **14**.
4. Focus scale **1.10** (cards 1.05), shadow blur about 28 and y about 14, duration **220ms** decelerate, one focused item always.
5. Parallax: **3 layers max**, shift ±**6–10** px by depth, **300ms** settle, disabled under Reduce Motion.
6. Hero band top about **45%** of the screen, crossfade **280ms**, 120ms debounce.
7. Text: Title 1 **76**, Title 2 **57**, Title 3 **48**, Headline **38** bold, Body **29**, Caption 2 minimum **23**, Medium weight or heavier.
8. Dark default. Text alpha **100 / 60 / 40**, contrast at least 4.5:1, bottom scrim gradient on hero art.
9. Glass tiers: white **8% / 14%** plus black **45%** thick, 1dp hairline at 18% white, one pre-blurred bitmap at 1/4 size.
10. Animate only transform and alpha, target 60fps, and verify on-device with `dumpsys gfxinfo`.
11. Back on home is consumed. Select launches, long-press Select opens the context menu, Menu opens the quick panel.
12. Quick panel contains a visible **Original Fire TV** action. Mac-side full undo is separate, and long-press Home stays as the last escape.
13. No Apple fonts, icons, trademarks or aerials. Name it "Apple TV-style".
14. Never clip focused tiles (`clipChildren=false`, 24dp padding).
15. Verify all focus and D-pad behavior on the real Stick. Screenshot about 10s after pressing Home.

## Sources
- Apple HIG tvOS, app icons, typography and focus pages (titles confirmed, bodies not retrievable): https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos, https://developer.apple.com/design/human-interface-guidelines/app-icons, https://developer.apple.com/design/human-interface-guidelines/typography, https://developer.apple.com/design/human-interface-guidelines/focus-and-selection
- WWDC25 "Meet Liquid Glass" (session 102): https://developer.apple.com/videos/play/wwdc2025/102/
- https://www.createwithswift.com/exploring-a-new-visual-language-liquid-glass/
- https://wwdcnotes.com/documentation/wwdc25-356-get-to-know-the-new-design-system/
