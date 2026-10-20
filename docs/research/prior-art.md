# Prior-art report: Apple TV-style launchers for Fire TV Stick 4K Plus

Method: web search and fetch plus a local directory listing. Several claims below are secondhand or unverified, and each is marked. Many fetches failed: the Android Authority and Android Magazine pages returned 403 or empty, and the Reddit search found no actual Reddit thread content. No adb/tv.sh commands were run.

## 1. Launcher survey

| Project | License | Stack | Verdict |
|---|---|---|---|
| **Arc Launcher** (Apr 2026) | Developer says open source. License unconfirmed. Press describes it as a fork of LTvLauncher, which forks FLauncher, so it is likely GPL-3.0 (inferred, unverified). | Flutter (inherited) | Closest prior art: Apple TV-inspired grid, dock, gradient, photo and video backgrounds, "Sections" categories. The developer built it after being disappointed with Fire TV Stick launchers. |
| **AT4K** | Unknown. GitHub repo holds no code. | Unknown | Pitched as "inspired by Apple TV's approach to a simple, no-nonsense, ad-free UI". Has favourites, drag-drop layout, themes, and watch-next surfacing. |
| **FLauncher** | GPLv3 (confirmed on GitLab) | Flutter | Original base. Discontinued, last release Apr 2023. |
| **LtvLauncher** | GPL-3.0 (confirmed) | Flutter + Dart, Drift DB | Best feature list among FLauncher descendants (see below). |
| **Projectivy** | Closed source | Native | UX reference only. Categories and channels, spacing controls. Custom wallpapers and icon customisation are paid. |
| **Wolf Launcher** | Closed | Native | Simple categories and icon size control. Widgets need USB debugging, and only weather worked for one reviewer. Hard to keep as default on Fire TV. |
| **rockon999 Leanback Launcher port** | Unverified | Leanback | Ran on unrooted Fire TV, but without recommendations. Not a full replacement. |
| **lonelytransistor/LauncherAndroidTV** | Unverified | Unknown | Lightweight, movie/series focused. 2023 alpha, not Apple TV-like. |
| **tv-vibehack `com.gathin.tvhome`** (local, MIT) | MIT | Plain Java Views | The only MIT code that is also Apple-flavoured. See section 3. |
| **dumbtv `tv.dumbtv.home`** (local, MIT) | MIT | Plain Java, no permissions | Local `launcher/` holds `build.sh` and `install.sh` (a plain shell build), not Gradle. |

**License implication:** Every Apple-style launcher found on the web (FLauncher, LtvLauncher, Arc) is GPL-3.0 or descended from GPL code. For an MIT project, borrow their ideas only. Copy no code from them. Home on Fire is also GPLv3, so integrate with it as a separate app only.

**LtvLauncher feature list (GPL, ideas only):**
- Continue Watching, built from Watch Next cards sorted by latest playback time.
- Status bar with weather (Open-Meteo) and a daily network data usage tracker.
- Notification overlay and drawer.
- Four card styles (Modern, Premium, Classic, Capsule), accent colors, and day/night wallpapers.
- Pure-black OLED background.
- HDMI input switching.
- A "Home Button Fix" accessibility service for Fire TV.
- OLED screensaver whose clock shifts every 30 s.
- Timestamped config backups with a D-pad restore picker.
- Category reorder, rename, and hide, in grid or row layouts.

Sources:
- [FLauncher GitLab](https://gitlab.com/flauncher/flauncher)
- [LtvLauncher](https://github.com/LeanBitLab/LtvLauncher)
- [AT4K](https://github.com/avadhesh18/at4k)
- [Arc coverage, tuttoandroid](https://www.tuttoandroid.net/news/2026/04/13/arc-launcher-google-tv-apple-tv-open-source-1148607/)
- [Arc coverage, Android Authority](https://www.androidauthority.com/google-tv-arc-launcher-3656748/)
- [Arc coverage, Android Magazine](https://androidmagazine.eu/en/2026/04/24/The-best-launcher-for-Google-TV-that-no-one-told-you-about-miniminimalist-interface-and-breath-apple-tv/)
- [FLauncher on XDA](https://www.xda-developers.com/flauncher-android-tv/)

## 2. Performance notes

- **Flutter launchers are the heavy option.** Flutter adds a runtime, an engine, and Skia/Impeller. That is a poor fit for the Stick's ~2 GB RAM, and Flutter is also why the code can't be reused here. I found no benchmark numbers. Treat "Flutter is heavier than plain Views on this hardware" as a reasoned inference, not a measurement.
- **Autoplay video is the main Fire TV lag source.** Tom's Guide reports that Amazon's own home autoplay previews cause sluggishness. Skip autoplay video on the home screen. Allow video wallpaper only as an opt-in, and pause it whenever another app is in front.
- **Home-button latency is a known user complaint.** Android TV home presses taking several seconds were reported on a Sony TV (the same Sony thread suggests disabling unused preinstalled apps). Keep launcher cold start small: lazy-load icons, cache a pre-blurred wallpaper to disk, and avoid heavy work in `onCreate`.
- **Blur options on API 30:**
  - Google's RenderScript Intrinsics Replacement Toolkit (Apache-2.0 per AOSP; verify). It is multithreaded and NEON-accelerated, and Google's migration guide says it may roughly double performance.
  - HokoBlur: Box, Stack, and Gaussian algorithms, with Java, native, and OpenGL schemes. License unverified.
  - Plain approach: downscale to 1/8 to 1/16, run a stack or box blur in Java, then upscale. At that scale it is cheap. Do it once per wallpaper change and cache the result as a file, not per frame.
  - `RenderEffect` is Android 12+ only, so it is out on API 30.
- **Tilt, parallax, and spring libraries:** the search found no library worth adopting for tilt or parallax focus cards. Build them in-house:
  - Use `androidx.dynamicanimation` `SpringAnimation` (Apache-2.0, small), or hand-roll a spring with `Choreographer`.
  - For focus scale, animate `scaleX` and `scaleY` only. For tilt, set `rotationX` and `rotationY` plus `cameraDistance`. Both are GPU-composited properties.
  - For a specular shine, draw a pre-rendered gradient bitmap shifted by a translation. Avoid per-frame `LinearGradient` allocation.
  - I did not verify the specific numbers here (SpringAnimation stiffness, damping, tilt degrees). The design phase should choose them and check them on-device.

## 3. What to borrow from the local MIT repos

I only listed filenames and did not read the code, so these are pointers for the planner, not conclusions.
- **tv-vibehack launcher** (`tv-vibehack/launcher/app/src/main/java/com/gathin/tvhome/`) holds these files:
  - `MainActivity.java`, `AppAdapter.java`
  - `DockDecoration.java`, `GlassView.java`
  - `ClockDream.java`, a screensaver (Dream)
  - `HomeKeyService.java`, `InputKeyService.java`
  - `HomeSettingsActivity.java`
  - `Shamsi.java`, `VpnBadge.java`

  Its dock, glass, and clock-dream pieces are the natural starting points.
- **dumbtv launcher** (`dumbtv/launcher/`) holds `build.sh`, `install.sh`, `release-check.sh`, and `version.env`. It is a no-Gradle build, which may help given the Mac has no JDK and no Android SDK. Check how `build.sh` gets its toolchain.
- **Toolchain gap:** a JDK and the Android SDK are both missing, and they block every build path. Resolve that before any code phase.

## 4. User-loved features and pain points

Evidence is thin. No Reddit thread content was retrieved. This comes from the Android Magazine and tuttoandroid coverage (via search-result summaries), AFTVNews, the Tablo forum, and the Tom's Guide article. Treat the weight of each point as low-to-medium confidence.

**Wanted:**
- A clean, ad-free home with no sponsored rows.
- Control over layout: reorder apps, hide apps, categories.
- A fast, smooth feel.
- Custom wallpapers.
- Sideloaded apps shown properly.

**Hated:**
- Clutter, with no way to reorder or remove Amazon entries (Tablo forum).
- Autoplay lag (Tom's Guide).
- The home button reverting to the stock launcher. Arc users work around it with Button Mapper.
- Paywalled customisation (Projectivy).
- Broken or unstable widgets (Wolf).
- Launchers that stop working after Amazon blocklists the method. AFTVNews says the Launcher Manager approach was blacklisted, and a July 2023 workaround also stops updates and sleep.

## 5. Ranked top 10 features

Items 1 and 2 are the user's hard requirements. They follow from the feature evidence above and the hard requirements.

1. **Always-reachable "Return to Fire TV home" and reset.** Provide:
   - A visible entry in settings and in a quick panel.
   - Long-press Home as the escape hatch, which keeps working if the launcher crashes.
   - A Mac-side undo script.
   - If Home on Fire can be toggled by `adb` or intent, offer an in-launcher "disable redirect" action. This is untested.
2. **Fast cold start with a pre-blurred cached wallpaper.** Skip autoplay video. Pause everything when the launcher is not visible.
3. **Apple-style focus: scale up, tilt, specular shine, spring easing, and a soft shadow.** Build it in-house with plain Views and GPU-composited properties.
4. **Top shelf (hero row) plus app rows.** Use a bottom-aligned wallpaper blur and large icon cards. Without TV-listings permissions, the shelf can only show app-defined shortcuts or a user-pinned item.
5. **A dock row of favourites.** Build it from the existing `DockDecoration` and `GlassView` ideas in tv-vibehack.
6. **App management: reorder, hide, rename, and categories**, persisted locally. The wanted list ranks this high, and Projectivy and LtvLauncher both have it.
7. **Wallpaper and theme: day/night wallpapers, a pure-black OLED option, accent color.** Keep these free, since paywalled customisation is a stated complaint.
8. **Clock and screensaver (`ClockDream`).** Shift the clock position periodically (LtvLauncher does 30 s) to limit burn-in.
9. **Recents and "continue" row.** Use the app's last-launched time. Do not use Watch Next cards, which would need extra permissions.
10. **Config backup and restore with a D-pad picker.** This pairs naturally with the reset requirement.

Not recommended: weather widgets, notification overlays, HDMI input switching, and Watch Next aggregation. They add permissions and weight, and they aren't priority wants.

## 6. Unresolved before planning

- The Arc Launcher repo URL and license are unconfirmed. Search GitHub for "Arc Launcher" or "LeanBitLab", then read its LICENSE.
- Whether Home on Fire can be toggled from an `adb` command or an intent is not researched. Check its README and source (read-only).
- No Fire TV frame-time numbers were found for any launcher. Plan to measure on the real device, for example with `dumpsys gfxinfo`.

Next action (under 2 min): open `dumbtv/launcher/build.sh` and see what toolchain it needs.
