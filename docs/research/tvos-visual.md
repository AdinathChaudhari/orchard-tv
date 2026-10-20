# Apple TV home screen: visual and motion spec for a 1920x1080 recreation

**Reliability.** I could only retrieve Apple's HIG through its JSON endpoint. Apple's docs have no numbers for the home-screen grid, focus timings, spring constants, sounds, or the Control Center layout. Every figure below is tagged **[V]** (verified against an Apple source), **[3P]** (third-party or legacy source) or **[E]** (my estimate, to be tuned on the real device). I did not find or watch any reference video. The links in section 12 are pages I fetched or searched, plus Apple Design Resources, which I did not open.

## 1. Canvas and safe area [V]

Source: [Apple HIG, Layout](https://developer.apple.com/design/human-interface-guidelines/layout).

- tvOS points map 1:1 to pixels at 1920x1080, which is the @1x canvas.
- Safe area is **60 px top and bottom, 80 px left and right**.
- The HIG grid table gives a 40 px horizontal gutter and at least 100 px vertical spacing between unfocused rows. Unfocused content widths by column count:

| Columns | Item width (px) |
|---|---|
| 4 | 410 |
| 5 | 320 |
| 6 | 260 |
| 7 | 217 |
| 8 | 184 |
| 9 | 160 |

- **Recommended grid:** 6 columns of 260x156 px (the 5:3 icon ratio), 40 px gutters. Check: 6x260 + 5x40 = 1760 px, which equals 1920 minus 2x80. Tune on the device.

## 2. App icon [V unless tagged]

Sources: [HIG App icons](https://developer.apple.com/design/human-interface-guidelines/app-icons), [HIG Images](https://developer.apple.com/design/human-interface-guidelines/images).

- Aspect ratio is 5:3, landscape. Apple's layout size is 800x480 px. The home-screen asset is "small" 400x240 @1x (800x480 @2x), and the App Store asset is 1280x768 [3P: [Zype](https://support.zype.com/hc/en-us/articles/221132148-Apple-TV-App-Images), [Kodeco](https://www.kodeco.com/933-tvos-top-shelf-tutorial-static-and-interactive)].
- Each icon has 2 to 5 layers. The back layer is opaque, with unmasked rectangular layers and clear foreground edges.
- The system applies the rounded-rect mask, with concentric edges. The corner radius is not published. **Use about 10 to 12 px at 260 wide [E]**, roughly 4.5% of the width.
- Do not bake specular highlights, shadows, bevels, blurs or glows into the art. The system draws them.
- The legacy Xamarin guide lists unfocused 300x180 and focused 370x222 [3P: [Microsoft Learn](https://learn.microsoft.com/en-us/previous-versions/xamarin/ios/tvos/app-fundamentals/icons-images)]. That is a focus scale of 1.233.
- Apple's current layered-image formula, as relayed by a search summary: focused safe zone is unfocused longest side + 70 pt, and actual size is focused safe zone x 106%.
- **Labels appear only on focus [E].** The label is white, medium weight, about 29 to 31 px, centred below the icon with a soft fade-in. Unfocused icons have no label.
- Apple icon artwork and SF Symbols cannot be copied. For our own launcher, generate icons from each app's own banner or icon. Where an app has none, make a 2-layer fallback: a gradient back layer plus an initial or glyph on the front.

## 3. Focus effect

Verified qualitative behaviour, from [HIG Images, Parallax](https://developer.apple.com/design/human-interface-guidelines/images) and [HIG Focus](https://developer.apple.com/design/human-interface-guidelines/focus-and-selection):

- The focused item elevates to the foreground, sways, and its surface illuminates.
- After a period of inactivity, out-of-focus content dims and the focused element expands.
- Five states must be visually distinct: unfocused, focused, highlighted (pressed, with a brief invert and animation), selected, unavailable.
- Focus must never move without user input.

Numbers to implement (**all [E] unless tagged**):

| Property | Value |
|---|---|
| Scale on focus | **1.15**. A forum report gives 1.158 [3P: [Apple forums](https://developer.apple.com/forums/thread/48813)], and the Xamarin sizes imply 1.23. Use a range of 1.12 to 1.20. |
| Focus animation | 200 to 250 ms with a decelerate (ease-out) curve, running on the UI thread via `ViewPropertyAnimator`. |
| Idle expansion | After about 2 to 3 s without input, scale grows a further 1.02 to 1.04 and the unfocused icons dim by about 10%. |
| Lift shadow | Soft, black, y-offset about 20 to 30 px, blur about 40 to 60 px, about 35% opacity. It fades in as the item scales up. |
| Specular highlight | A diagonal white-to-transparent gradient sheen (about 12 to 18% alpha) across the top-left half of the icon. |
| Tilt and parallax | The Siri Remote touch surface drives a 3D tilt of about ±5 to 8 degrees, and the layers shift against each other. The D-pad has no touch surface, so use a **slow idle sway** instead: about 6 s period, ±1.5 degrees, with the highlight sliding in sync. |
| Layer parallax | Background 1 to 2, middle 3 to 5, foreground 5 to 8 [3P: design-skill guides, not Apple numbers]. |
| Press feedback | On OK, scale down to about 0.96 for 80 ms, then launch. |

The 3D tilt and highlight sweep need `camera distance` set on the view, or a cheap `Canvas` and `Matrix` skew. On a 2 GB Stick, animate only the focused icon.

## 4. Top Shelf [V]

Sources: [HIG Top Shelf](https://developer.apple.com/design/human-interface-guidelines/top-shelf), [WWDC19 session 211](https://developer.apple.com/videos/play/wwdc2019/211/).

- Top Shelf is the area above the grid. It shows content for whichever app is focused in the dock row.
- **Static fallback:** 2320x720 pt. tvOS flips and blurs it to fill 16:9 and fade into the background. It is not focusable.
- **Layouts:**
  - Carousel actions: full-screen video or image with Play and More Info buttons.
  - Carousel details: the same, plus metadata.
  - Sectioned content row: one labelled, focusable row. A label appears when an item is focused.
  - Scrolling inset banner: 1940x692 actual, 1740x620 safe, 3 to 8 banners. It auto-advances on a timer until focused, then loops.
- **Sectioned-row item sizes (actual / unfocused):**

| Shape | Actual | Unfocused |
|---|---|---|
| Poster 2:3 | 404x608 | 333x570 |
| Square | 608x608 | 500x500 |
| 16:9 | 908x512 | 782x440 |

  Mixed shapes scale to the tallest height, about 500 px.
- **Full-screen Top Shelf (Apple TV app):** previews start immediately when the Apple TV app is focused, and the dock slides away.
- **Recreation [E]:**
  - The shelf takes about the top 55% of the screen, about 600 px of 1080, and the dock and grid sit below.
  - A bottom gradient fades from transparent to the wallpaper over about 200 px, so the shelf melts into the grid.
  - For third-party tiles, use a sectioned row of 16:9 cards (782x440 unfocused) or a static 2320x720 hero.
  - Auto-advance every 6 to 8 s with a 400 to 600 ms cross-fade.
  - When an app is focused in the grid, cross-fade to that app's shelf in about 300 ms.
  - Our launcher has no TV-listings permission, so the shelf can only show things we can build ourselves: recently used apps, a clock and weather card, or user-chosen artwork.

## 5. Home layout and dock [mostly E]

- **Top row (dock) [E]:** the first row holds the user's favourite apps, and the shelf follows the focused one. The remaining icons wrap in rows below, at 6 columns.
- **Vertical scroll [E]:** moving down scrolls the content up. The focused row stays in a fixed band, about 60% down the screen, while other rows slide with a spring or decelerate curve of about 350 ms.
- **Row spacing [V min]:** at least 100 px between unfocused rows (icon height 156 + about 100 = about 256 px pitch).
- **Edge peeking [V]:** partly hidden off-screen content should stay symmetric on both sides.
- **tvOS 26 dock [3P]:** the dock edges sparkle (9to5Mac). A MacStories summary describes Liquid Glass as adding depth to controls, buttons and app icon borders.

## 6. Wallpaper and blur [E]

- The wallpaper is a soft, dark, slowly shifting gradient or blurred artwork. On Apple TV the home background follows the focused app's colour and is blurred. The Top Shelf image is flipped, blurred and faded into it [V].
- **Implementation on API 30:** there is no live blur.
  - Pre-blur wallpapers to about 1/8 resolution (240x135), then upscale with bilinear filtering.
  - Cross-fade on focus change over about 400 ms.
  - Add a dark scrim: a black 25 to 35% overlay gradient that is darker at the bottom, for text legibility.
  - Pre-bake a "glass" panel by blurring the wallpaper slice behind it, then add a 1 px white 15% border and an inner top highlight.

## 7. Typography [V]

Source: [HIG Typography](https://developer.apple.com/design/human-interface-guidelines/typography). The system font is SF Pro, with SF Text below 40 pt and SF Display above.

| Style | Weight | Size (px) | Leading |
|---|---|---|---|
| Title 1 | Medium | 76 | 96 |
| Title 2 | Medium | 57 | 66 |
| Title 3 | Medium | 48 | 56 |
| Headline | Medium | 38 | 46 |
| Subtitle 1 | Regular | 38 | 46 |
| Callout | Medium | 31 | 38 |
| Body | Medium | 29 | 36 |
| Caption 1 | Medium | 25 | 32 |
| Caption 2 | Medium | 23 | 30 |

- The default text size is 29 px and the **minimum is 23 px**.
- **Free substitute:** Inter (SIL OFL), and the closest match is Inter Display for the large sizes. Bundle two static weights (Regular and Medium, or Semibold for emphasis), not the variable font, to save memory. Apple's Medium baseline maps to Inter Medium (500). Add about -0.01 em tracking at 48 px and above.
- Do not bundle SF Pro or SF Symbols.

## 8. Colours in dark mode [E]

Apple publishes no tvOS home palette. Use these:

| Token | Value |
|---|---|
| Background base | `#000000` to `#0B0B10` |
| Primary text | `#FFFFFF` |
| Secondary text | white at 60% |
| Glass fill | white at 8 to 12% |
| Glass border | white at 15 to 20% |
| Shadow | black at 35 to 50% |
| Accent | `#0A84FF` for toggles and focus rings. Buttons themselves use white fill with black text when focused. |

On Apple TV a focused button is inverted: white fill, dark label.

## 9. Control Center and app switcher [V + E]

- **tvOS 26 [3P]:**
  - Control Center opens from the TV button.
  - Its platters use clear glass, so the playing video shows through (9to5Mac).
  - Apple's newsroom says it keeps video front and centre. It lists playback controls, sleep timer, audio adjustments and a "Movie night" scene ([newsroom](https://www.apple.com/newsroom/2025/06/apple-tv-brings-a-beautiful-redesign-and-enhanced-home-entertainment-experience/)).
- **Layout [E]:**
  - A row of rounded glass tiles near the top-right or centre: profile, search, sleep, now playing, HomeKit and others.
  - About 6 to 8 tiles, each about 150 to 200 px square with a 24 to 32 px radius.
  - Focus lifts a tile with the same 1.1 scale.
  - Entry is a slide-down plus fade, about 250 ms.
- **Our "Quick Panel":** hold or double-press a key. It carries tiles for Clock, Wi-Fi, and **Reset to Fire TV**. The reset tile must be reachable by D-pad in at most 2 presses.
- **App switcher [E]:** a double-press of the TV button shows a horizontal row of card previews. Cards are about 16:9, about 640 px wide, with a slight overlap. Swipe up on a card to close it. On Android, use `UsageStats` or recents only if permissions allow. Otherwise build a "recent apps" row from our own launch log.

## 10. Screensaver, transitions, sound

- **Screensaver [V: [Apple support](https://support.apple.com/guide/tv/use-screen-savers-atvbfa799b87/tvos)]:**
  - Types are Aerials, Memories and Slideshows, and Portraits (with a clock overlay).
  - Aerials are slow-motion videos in four categories (Cityscape, Earth, Landscape, Underwater).
  - It starts after an idle timer and exits on any press, returning to the previous context.
  - Aerial videos are Apple's, so we cannot ship them. Use a Ken Burns slideshow of user photos or bundled CC0 images, plus a slow clock.
  - The existing `ClockDream` class in tv-vibehack is the base for that.
- **Launch animation [E]:**
  - The icon scales up and fills the screen, about 350 to 400 ms.
  - The wallpaper and other icons fade out. On return (Back), the app zooms down into its icon slot.
  - Android: `ActivityOptions.makeScaleUpAnimation` from the focused icon's bounds, or `makeClipRevealAnimation`.
- **Sounds [E]:**
  - A very soft focus tick, about 30 to 50 ms, and a slightly lower select click. Both are optional, off by default, and routed through `SoundPool` at low volume.
  - Generate original sounds. Apple's are proprietary.

## 11. tvOS 26 (Liquid Glass) versus tvOS 18

| Area | tvOS 18 | tvOS 26 |
|---|---|---|
| Material | Static blur, flat platters | Real-time reflection and refraction on glass, with specular highlights |
| Dock | Dimmed or blurred | Glass edges that sparkle |
| Control Center and player controls | Opaque or blurred | Clear glass over the video |
| App icons | Parallax layered | Same parallax, with subtle glass depth on borders per MacStories (not described for icons by 9to5Mac) |
| Apple TV app | Horizontal tiles | Vertical cinematic posters, more titles per screen |
| Profiles | Manual | Optional picker on wake |
| Hardware | All | Glass effects only on Apple TV 4K 2nd gen and later |

Reviewers say the home screen itself changed little: [AppleInsider](https://appleinsider.com/articles/25/09/14/tvos-26-review-high-on-polish-light-on-new-features-for-apple-tv), [PCWorld](https://www.pcworld.com/article/2847724/apple-tv-isnt-changing-much-in-2025.html). The visible changes are the glass dock and glass panels.

**Cheap Liquid Glass on API 30:**
1. A pre-blurred wallpaper slice.
2. A 1 to 2 px white edge at 25% alpha, brighter at the top.
3. An inner gradient (white 12% to transparent).
4. A static diagonal highlight that slides with focus.

No shader is needed.

## 12. Links to check

- [Apple Design Resources, tvOS](https://developer.apple.com/design/resources/#tvos-apps): Parallax Previewer and templates (not opened).
- [WWDC19 session 211, Mastering the Living Room With tvOS](https://developer.apple.com/videos/play/wwdc2019/211/).
- [Apple newsroom, tvOS 26](https://www.apple.com/newsroom/2025/06/apple-tv-brings-a-beautiful-redesign-and-enhanced-home-entertainment-experience/).
- [MacStories, tvOS 26 overview](https://www.macstories.net/news/tvos-26-the-macstories-overview/) and the [9to5Mac tvOS 26 Control Center piece](https://9to5mac.com/?p=1009925).
- [Parallax View, open-source tvOS parallax implementation](https://github.com/PGSSoft/parallaxview), a useful reference for tilt maths.

## 13. Build checklist and open questions

1. Check the home-grid numbers (6 columns, 1.15 scale, 250 ms) by eye against a real Apple TV or video. I found no authoritative numbers.
2. Do not use the Aerial videos, SF Pro, SF Symbols or Apple sounds.
3. Keep focus and shadow animations on one focused view, and use pre-blurred bitmaps only.
4. Put "Reset to Fire TV" in the Quick Panel and in Settings, and keep long-press Home as the crash-proof exit.

Files read for this task: none locally. No files were written and nothing was run on the TV.
