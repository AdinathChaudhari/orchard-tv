# Research report: reusable parts of tv-vibehack, dumbtv and Home on Fire

Read-only pass. I touched nothing on the TV and wrote no files in the repos. Paths: `VH` = `tv-vibehack/launcher`, `DT` = `dumbtv/launcher`. Home on Fire (`HoF`) source was read from GitHub (`toolicious/home-on-fire`, v1.5.2, GPLv3, 69 stars, last push 2026-09-22) using `gh api`.

## 0. Key findings

1. **The reset button can live inside the launcher without GPL code.**
   - HoF's only external hook is the secure setting `enabled_accessibility_services`. Its entry is `io.github.toolicious.homeonfire/.HijackService`.
   - If our app is granted `WRITE_SECURE_SETTINGS` once (`adb shell pm grant com.<ours> android.permission.WRITE_SECURE_SETTINGS`, the same grant HoF uses), our app can remove that one entry from the colon-separated list. Home then returns to Amazon's stock home.
   - Re-adding the entry is the "go Apple TV again" toggle.
   - This is plain Android API (`Settings.Secure.putString`). HoF's `AccessibilityHelper` shows the logic, but it is trivial and we should write our own.
   - HoF does not re-enable itself. Its only `enableOurService` call is the UI toggle (`MainActivity.java` ~line 467), and `BootReceiver` is a no-op when the service is off.
2. **HoF cannot be configured from outside.**
   - The target app is `SharedPreferences` file `home_on_fire`, key `target_package`, with `MODE_PRIVATE`.
   - HoF has no exported broadcast receivers or intent extras. Its `BootReceiver` is exported but only handles `BOOT_COMPLETED`. `TargetPickerActivity` is not exported.
   - The user must pick our launcher once in HoF's "Choose target app…" chip. The picker lists anything with `LEANBACK_LAUNCHER` or `LAUNCHER`.
   - `run-as` will not work on a release APK.
   - Later changes to our package name would require re-picking.
3. **How HoF launches the target** (`HijackService.launchTarget`, lines ~1563-1580):
   - It tries `pm.getLeanbackLaunchIntentForPackage(target)` first, then `getLaunchIntentForPackage`.
   - It adds `CATEGORY_HOME` if the target has a HOME activity (`canHandleHome` probe).
   - It sets `FLAG_ACTIVITY_NEW_TASK` only, with no `CLEAR_TOP`. A running instance is brought to front, so our home activity must handle `onNewIntent` and resume cleanly.
   - Contract for our manifest: one activity with both `MAIN/LEANBACK_LAUNCHER` and `MAIN/HOME/DEFAULT` filters. Both tv-vibehack and dumbtv already do this.
4. **HoF's escape gestures** (all on by default):
   - Long-press Home escapes to Amazon home. It works via the Quick Settings panel window, detected as `com.amazon.tv.quicksettings.ui` or `com.amazon.tv.settings.v2` with a `hud.HudActivity` class.
   - Double-press Home within ~1 s escapes too.
   - It then calls `performGlobalAction(GLOBAL_ACTION_HOME)` and sets a bypass window so it does not hijack straight back.
   - Back or OK on Amazon's focused Home tab returns to the target.
   - Menu long-press (~500 ms) opens HoF's config screen.
   - All of this keeps working if our launcher crashes, because it lives in HoF's process, not ours.
5. **Home redirect on Fire OS 8 is a redirect, not a replacement.**
   - Amazon's launcher stays the system home activity.
   - HoF reacts when `com.amazon.tv.launcher` / `com.amazon.tv.launcher.ui.HomeActivity_vNext` comes to the foreground, so expect a ~250 ms flash of Amazon home on every Home press (HoF README, "Known limitations").
   - No app-switch delay on Fire OS 8 (`MASK_SUPPORTED = SDK_INT <= 28`). Fire OS 7's 5 s lock and masking overlay do not apply to us.
   - The black-screen tradeoff is acceptable. Make our window's first frame black and fast (see take-list item 9).
6. **Confirmed on-device facts from tv-vibehack's README:** `cmd package set-home-activity` prints Success and does nothing on Fire OS. Disabling the stock launcher is protected and risky.
   - Never disable `com.amazon.tv.launcher`, `com.amazon.firelauncher`, `com.amazon.firehomestarter` (dumbtv `never-disable.txt`; our own README lists the same).
   - The existing `tv-vibehack/launcher/build-and-install.sh` runs `pm disable-user <stock launcher>` and `set-home-activity`. **Do not use that script on the Stick.** Write our own install script with no disable and no set-home.
7. **Fire OS gotcha from dumbtv (`docs/GOTCHAS.md`):** `android.settings.SETTINGS` resolves to a stub (`CTSDummySettingsHandler`) that reopens Amazon's home. Open settings with `Intent.ACTION_MAIN` plus category `android.intent.category.LEANBACK_SETTINGS` instead.
   - With HoF in the loop, the generic action would bounce back to our own launcher.
   - Documented on Fire OS 7.7; verify on Fire OS 8 on the device.
8. **Build toolchain.** The Mac has no JDK and no SDK. Two ways to build:
   - tv-vibehack: Gradle 8.9 wrapper, AGP 8.5.2, compileSdk 34. Needs JDK 17 plus SDK platform 34 and build-tools 34.0.0.
   - dumbtv: `build.sh` calls aapt2, `javac --release 8`, d8, zipalign and apksigner directly. Needs JDK 17 plus `platforms;android-28` (or 30) and build-tools.
   - Either way a one-time `brew install openjdk@17` and `sdkmanager` install is needed. The direct-tools route avoids Gradle's downloads and the AGP/Gradle compatibility maze, and builds in seconds. Do this at the start of the build phases. `/opt/homebrew/bin/gradle` is system Gradle and is not needed if the wrapper is used.

## 1. tv-vibehack launcher (MIT, Copyright 2026 Gathin)

### Build and size
- **Gradle:** wrapper `gradle-8.9-bin.zip`, AGP `8.5.2` (root `build.gradle`), `compileSdk 34`, `targetSdk 34`, **`minSdk 26`**, `JavaVersion.VERSION_17`.
- **Dependencies:** only `androidx.recyclerview:1.3.2` and `androidx.dynamicanimation:1.0.0`.
- **Release build:** `minifyEnabled true`, `shrinkResources true`, `-dontobfuscate`, signed with the debug key. Quoted APK size is ~250 KB, versus 1.6 MB unshrunk.
- **Install flow:** `build-and-install.sh` runs `pm install -r`, then `cmd package compile -m speed -f <pkg>` (pre-compiles so the first launch is not JIT-slow; good trick to keep), then `am start -W`.
- **Resources:**
  - Outfit variable font (`res/font/outfit.ttf`, 110 KB, SIL OFL; `FONT-LICENSE-OFL.txt` must ship with it).
  - Layouts `activity_main.xml`, `item_app.xml`, `dream_clock.xml`, popup and input rows.
  - Vector icons.
  - JPG launcher icon and banner (4 KB each, placeholders).
  - Translations: ca, de, es, zh-CN.

### Manifest (`AndroidManifest.xml`)
- **Permissions:** `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION` (only to read the Wi-Fi SSID on Android 10+).
- **Package visibility:** `<queries>` for `MAIN/LEANBACK_LAUNCHER`, required on API 30.
- **Main activity (`MainActivity`):**
  - Filters: `HOME/DEFAULT` and `LEANBACK_LAUNCHER`.
  - Attributes: `singleTask`, `stateNotNeeded`, `excludeFromRecents`, `clearTaskOnLaunch`, landscape, `configChanges` list. Theme `android:Theme.Material.NoActionBar.Fullscreen` with null window animation.
- **Services:**
  - `HomeKeyService` (maps `KEYCODE_ALL_APPS`) and `InputKeyService` (maps `KEYCODE_TV`), both opt-in accessibility services.
  - `ClockDream` (`DreamService`).
  - `HomeSettingsActivity` (no-display trampoline to `Settings.ACTION_HOME_SETTINGS`, useless on Fire OS).

### App enumeration (`AppAdapter.reload`)
- `pm.queryIntentActivities(MAIN + CATEGORY_LEANBACK_LAUNCHER, 0)`.
- Skips its own package and the `hidden_apps` config array.
- Banner: `ri.activityInfo.loadBanner(pm)`, falling back to `applicationInfo.loadBanner`.
- Icon and label from `ri.loadIcon` and `ri.loadLabel`.
- Tiles without a banner show a 48 dp icon plus a text label.
- Launch intent: `MAIN` + `LEANBACK_LAUNCHER`, `setComponent`, flags `NEW_TASK | RESET_TASK_IF_NEEDED`.
- Long-press opens `ACTION_APPLICATION_DETAILS_SETTINGS`.
- Sort: dock favourites first (order from `dock_apps` in `values/config.xml`), then alphabetical.

### Layout and visual constants (tvOS style)
- **Colours:** root `#000000`; tile placeholder `#1C1C1E`; dock band `#141416` with a 1 dp `#1FFFFFFF` edge.
- **Dock band:** drawn by `DockDecoration`. Padding 14 dp, corner radius 26 dp, item offsets top 6 dp and bottom 12 dp.
- **Tiles:** 180×101 dp (16:9), corner radius 16 dp, item padding 8 dp top and bottom, 4 columns.
- **Hero name:** Outfit weight 300, 48 sp, letterSpacing -0.02, left margin 68 dp, placed above the dock in code.
- **Top bar:** left and right padding 56 dp, top 26 dp.
- **Clock:** TextClock, Outfit w300, 52 sp, letterSpacing -0.04.
- **Greeting:** 12 sp, w500, caps, letterSpacing 0.22, colour `#8CFFFFFF`.
- **Date:** 14 sp, caps, letterSpacing 0.14, colour `#A6FFFFFF`.
- **Top pills:** height 42 dp, radius 21 dp, 15 sp.
  - Idle: gradient `#2EFFFFFF` to `#14FFFFFF` with a `#38FFFFFF` 1 dp stroke.
  - Focused: solid `#F5FFFFFF` with `#111111` text.
- **Popup panel (`glass_panel`):** gradient `#F22A2A2E` to `#F5161618`, 1 dp `#33FFFFFF` stroke, radius 20 dp. Rows 42 dp. Opens with a 220 ms DecelerateInterpolator(1.6) unfold (alpha, scaleY 0.92→1, translateY -8 dp→0).

### Focus handling and motion
- **Focus and keys:**
  - Tiles are `focusable` `FrameLayout` cards inside a `RecyclerView` `GridLayoutManager`.
  - A per-card `OnKeyListener` calls `MainActivity.onTileVerticalKey`, which handles D-pad itself.
  - Up and Down move exactly one row. Left and Right are swallowed at row ends so focus never wraps or escapes.
  - Up from the dock goes to the top bar. Pills swallow Up, and Left and Right at their ends.
  - `descendantFocusability="afterDescendants"` on the grid.
  - Last focus is remembered in statics (`lastFocused`, `lastFocusedKey` as `component.flattenToShortString()`) and restored in `onResume` via `grid.post(...)`. Uses `setHasStableIds(true)` with `hashCode()` of the component string.
  - Cell focus-change draws: card scale `1.10` (`FOCUS_SCALE`) with `OvershootInterpolator(1.6)`, 260 ms in and 180 ms out.
  - Tile name label fades in with translateY +4 dp (focused) or −6 dp (unfocused), 220 ms.
- **Floating glass highlight (`GlassView`, springs between tiles):**
  - **One** `GlassView` floats over the grid. It is not a blur, just a gradient fill.
  - Paints: fill `LinearGradient` `#3DFFFFFF`→`#12FFFFFF`; top specular `#66FFFFFF`→0 over the top 45%; 1.5 dp rim `#CCFFFFFF`; three widening glow strokes (alpha `0x30/i`, i=1..3, inset 6 dp).
  - Moved with `SpringAnimation` on `TRANSLATION_X` and a custom Y property. `SpringForce` stiffness 260, damping ratio 0.58 (bouncy). The Y spring works in "grid content space" with a `glassScrollShift` offset, so scrolling does not fight the spring (clever and worth keeping).
  - Fades in over 180 ms the first time. Hides over 160 ms when focus leaves tiles (global focus listener).
  - Can't-miss problem: the glass is drawn from layout coordinates (ignores the scale animation) and sized as `card * 1.10 + 2*pad`.
- **Hero and ambient glow (dock row only):**
  - On focusing a dock tile, the app name crossfades (120 ms out with −12 dp slide, 200 ms in).
  - A radial `GradientDrawable` ambient glow (center 0.32/0.42, radius 0.62·width, stops `0x5C`/`0x26`/`0x00` alpha) takes a colour from the app's banner.
  - `AppAdapter.vividColor` draws the banner into a 12×12 bitmap and picks the most saturated, not-too-dark pixel (score = S × min(1, V·1.4)). Below 0.25 it falls back to the average, lifted to V ≥ 0.55. Cached per entry.
  - Ambient colour change: fade to 0.35 in 200 ms, then back to 1 over 500 ms.
- **Tile entry:** `LayoutAnimationController` with `anim/tile_enter` (alpha 0→1, translateY 22%→0, scale 0.94→1, 420 ms decelerate_cubic, delay 0.06) on every resume.

### Screensaver (`ClockDream`)
- `DreamService`, `setInteractive(false)`, fullscreen, `setScreenBright(false)`.
- Clock (Outfit w200, 120 sp) and date (18 sp) over a radial glow.
- Every 60 s the clock fades out (900 ms), jumps to a random spot (10-90% of the free area) and fades in (1200 ms). The glow colour cross-fades over 8 s among 5 palette colours (`#2B5CFF`, `#00A39A`, `#8A3FFC`, `#E0661B`, `#D6336C`).
- Burn-in-safe and trivially cheap.
- Enable via adb: `settings put secure screensaver_components com.gathin.tvhome/.ClockDream` plus `screensaver_enabled 1`. Our Stick currently uses Android "Colors" (`com.android.dreams.basic/.Colors`). Verify Fire OS 8 honours a custom dream.

### Other
- **Network pill:** `ConnectivityManager` callbacks, SSID, VPN badge (`VpnBadge` is a `ReplacementSpan` capsule, green `#30D158` with ink `#06290F`).
- **Inputs popup:** `TvInputManager`; irrelevant on a Stick (button hides itself when no passthrough inputs).
- **Date options:** `Shamsi.java` is irrelevant.

### Quality issues and bugs to avoid
1. **Dock is positional, not pinned.** The first `COLUMNS` items are the dock. If fewer than 4 favourites are installed, the dock fills with alphabetical apps. Also the user's favourites are baked into `config.xml`, so they need a rebuild to change.
2. **Main-thread I/O.** `reload()` runs `queryIntentActivities` plus `loadBanner`/`loadIcon` for every app on the UI thread on every `onResume`, then `notifyDataSetChanged()`. `vividColor` also draws on the main thread. Fine for 20 apps, but cache and move off-thread.
3. **Redundant callbacks.** `networkCallback` and `vpnCallback` are identical; polling `getAllNetworks()` per minute tick.
4. **`ACCESS_FINE_LOCATION`** is only needed for the SSID. Skip it (it has to be granted over adb and is sensitive). Show "Wi-Fi" or the connection type instead.
5. **Deprecated and heavy API:**
   - `WifiManager.getConnectionInfo`.
   - `fontVariationSettings` in layouts needs API 28 (fine for minSdk 28+; their minSdk 26 only gets away with it).
   - Layout hard-codes dp sizes (180×101 tile, 145 dp row), so a different tile size means editing three places (`item_app`, `DockDecoration`, `MainActivity` math). Make sizes constants or dimens.
6. **Back is a no-op on Home** and `singleTask` with `clearTaskOnLaunch`. Fine for HoF's launch mode, but fails the "tile name when hero shown" case for non-dock rows: hero and ambient only exist for the dock row, so the rest of the grid has no top-shelf.
7. **Glass and scroll corner cases** are handled by hand (`glassTargetY`, `glassScrollShift`, `requestChildRectangleOnScreen`). It works but is fragile; any change to scroll behaviour needs a real-device pass.
8. **README claim not true for Fire:** "Fire OS won't let a third-party launcher take over Home". That is only true without HoF. With HoF it works (our Stick does exactly that with Projectivy).
9. `HomeKeyService`: `KEYCODE_ALL_APPS` mapping does nothing on Fire OS. Fire's Home key is not delivered to accessibility key filters at all (dumbtv `GOTCHAS.md` and HoF's `accessibility_config.xml` comment both say so).

## 2. dumbtv launcher (MIT, "dumbtv contributors", package `tv.dumbtv.home`)

### Build (`DT/build.sh`, no Gradle)
- Steps: `aapt2 compile` → `aapt2 link --min-sdk-version 28 --target-sdk-version 28` → `javac --release 8 -Xlint:all -Werror` against `platforms/android-28/android.jar` → `d8 --release --min-api 28` → `zip` → `zipalign -p 4` → `apksigner sign`.
- Creates `release.keystore` on first run (password `dumbtvhome`). Stable signing key: a different key will refuse to update the installed APK.
- Prints the permission count.
- Result: 41 KB, zero permissions, no AndroidX, no Kotlin. Source is 8 Java files; `Home.java` is 22.5 KB. Plain `Activity`; no fragments, no RecyclerView.
- `install.sh` flags: `--build`, `--undo`. It checks a pinned SHA-256 in `version.env`.
- **Undo pattern worth copying:** read `enabled_accessibility_services`, strip our entry, keep the other services, and clear the setting with `'""'` first, `sleep 1`, then write the list. The clear-first step makes the accessibility manager rebind a service whose process died.

### Manifest
- One activity `Home` (`singleTask`, `resumeWhilePausing`, `clearTaskOnLaunch`, `excludeFromRecents`, landscape, long `configChanges`) with both HOME and LEANBACK_LAUNCHER filters.
- A second activity `Options` and the `Guard` accessibility service.
- No `<queries>`: targetSdk 28 means no package-visibility filtering on this API 30 device. (We target a higher SDK, so we need `<queries>` as in tv-vibehack.)

### App enumeration (`Catalog.apps`)
- Collects `LEANBACK_LAUNCHER` first, then `LAUNCHER` (non-TV apps) as a second pass, deduplicated by package. Sorted with `Collator`.
- Leanback apps show by default. Non-leanback apps are hidden unless the user ticks them in Options (`apps.show`). The Fire "Appstore", "Downloader" and similar start hidden.
- Hide, order and rename are saved in `home.conf`.
- Banner via `info.loadBanner(pm)`, falling back to `loadIcon`. Art loads on a single-thread executor and the row is invalidated after each load. A `packages` receiver (`PACKAGE_ADDED/REMOVED/CHANGED`) reloads and preserves already-loaded art.
- The package receiver and art cache are better than tv-vibehack's per-resume reload.

### Focus, UI, drawing
- **Layout:** `ScrollView` with a vertical `LinearLayout`; each row is a `HorizontalScrollView` holding a `LinearLayout` strip. Clip flags are off so scaled tiles overflow.
- **Tile:** custom `View` that draws everything in one `onDraw`: banner, clip path with 10 dp corners, an accent ring (2.5 dp stroke, accent colour) for focus, a dashed ring when "moving".
- **Focus effect:** scale 1.08 over 110 ms (fast, no overshoot). Pivot set to the card's centre, so the label area doesn't skew.
- **Up from chips (Options/Settings buttons):** a custom `focusSearch` override picks the strip child nearest horizontally (`nearest()`). Good pattern for a vertical move into a horizontal row.
- **Back** jumps to the first tile, **Menu** opens the tile menu or Options.
- **Move mode:** a `dispatchKeyEvent` override swallows keys. Left and Right reorder, anything else drops the tile and `saveOrder` persists.
- **Visual language is the opposite of Apple:** accent focus ring, flat `#1C1D21` tiles, "sans-serif-light" system font. Take the architecture, not the look.
- **Memory trick:** `onTrimMemory` calls the hidden `WindowManagerGlobal.trimMemory(TRIM_MEMORY_COMPLETE)` by reflection, to drop the hidden window's ~35 MB GPU context while an app plays. Wrapped in try/catch, so it fails safe, but hidden-API restrictions may block it on API 30; test it before relying on it.
- **Measured claims (Fire OS 7.7, 1.4 GB):** home on screen 57-72 MB PSS, ~37 MB PSS while an app plays, and Home → home screen 0.16-0.25 s. Projectivy was 135 MB.

### Config (`Config.java`)
- Single `key=value` file at `/sdcard/Android/data/tv.dumbtv.home/files/home.conf` (falls back to `getFilesDir()`).
- The file's mtime and length act as a signature. `refresh()` on `onResume` picks up hand edits made over adb; `version()` increments on every change.
- Atomic write via tmp file + `renameTo`. Unknown keys are kept. Lists are comma-separated.
- Keys: accent, tiles, labels, clock, inputs, background, dim, `apps.order`, `apps.hide`, `apps.show`, `name.<pkg>`.
- `adb pull` and `adb push` are the export and import path.
- Wallpaper: `wallpaper.jpg|png|webp` next to the config, decoded with `inSampleSize` to screen size. Useful for a user-supplied wallpaper without extra permissions.

### Guard (`Guard.java`, Fire OS 7 specific, **do not port**)
- Accessibility service watching `typeWindowStateChanged`.
- Detects Amazon's home (`com.amazon.tv.launcher` / `com.amazon.tv.launcher.ui.HomeActivity_vNext`), covers it with a `TYPE_ACCESSIBILITY_OVERLAY` window, and launches the launcher through an `AlarmManager.setExact` + `PendingIntent` (system_server is exempt from the 5 s app-switch delay).
- Needs `settings put global alarm_manager_constants min_futurity=0` (undone by `--undo`).
- **Why we skip it:** Fire OS 8 has no app-switch delay, and Home on Fire already does the redirect. Two accessibility services doing the same job would fight each other.
- **Useful ideas only:**
  - A "cover" overlay is a fallback if the 250 ms Amazon-home flash is bothersome.
  - Opening Amazon home deliberately (`Guard.amazon`: `ACTION_MAIN` + `CATEGORY_HOME` + `setPackage(com.amazon.tv.launcher)`, then pausing the guard) is the same idea as our "open stock home" action. Under HoF the bypass would not be armed, so use the secure-setting approach above instead.

### Helper scripts worth reading when we write our install script
- `scripts/launcher.sh`: `dumpsys deviceidle whitelist +<pkg>` (stop Doze killing the launcher).
- `scripts/launcher.sh`: the post-install check that `mResumedActivity` is ours.
- `scripts/restore.sh`: restores settings from a pre-change snapshot.

### Quality issues
- Fully custom `HorizontalScrollView` rows with all tiles created eagerly. Fine for ~30 apps; a RecyclerView is better beyond that.
- `onFocusChanged` calls `animate()` per tile; no shared spring. Fine, but no "floating highlight" behaviour.
- `targetSdkVersion 28` is deprecated and Play-incompatible, but sideloading works. For us: pick `targetSdk 30`.
- `Options` uses `AlertDialog` with `DeviceDefault` dark theme; spartan.
- `release.keystore` password is hard-coded; irrelevant for sideload, but generate our own.

## 3. Home on Fire (GPLv3-or-later, integrate only, never copy)

- **Components:** `MainActivity` (exported, launcher and leanback), `TargetPickerActivity`, `LogViewerActivity`, `LoaderPreviewActivity` (not exported). `HijackService` (accessibility, `exported="false"`). `BootReceiver` (exported but permission-guarded). **No intents, broadcasts or content providers for external control.**
- **Permissions:** `BIND_ACCESSIBILITY_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `WRITE_SECURE_SETTINGS` (granted via adb, no `INTERNET`).
- **`<queries>`:** `LEANBACK_LAUNCHER`, `LAUNCHER`, `android.settings.SETTINGS`, and package `com.amazon.tv.quicksettings.ui`.
- **`accessibility_config.xml`:** events `typeWindowStateChanged|typeViewFocused`; flags `flagRequestFilterKeyEvents`, `flagRetrieveInteractiveWindows`, `flagReportViewIds`; `canRetrieveWindowContent=true`; `notificationTimeout=100`. No `packageNames` filter. The XML comment states that `KEYCODE_HOME` is system-reserved and not delivered to the service.
- **SharedPreferences `home_on_fire`:** `target_package` (default empty), `launch_on_boot` (default true), `hijack_enabled` (default true), `menu_longpress_launch` (true), `escape_longpress` (true), `escape_doublepress` (true), `launch_key_enabled`, `launch_keycode`, `amazon_keycode`, `launch_window`, `amazon_window`, `custom_maps`, `verbose_logging`, `mask_*` (Fire OS 7 only).
- **Enabling and disabling the service** (`AccessibilityHelper`):
  - `SERVICE_ID = io.github.toolicious.homeonfire/.HijackService`; `SERVICE_ID_FULL` is the fully qualified form.
  - Enable: append to `enabled_accessibility_services` with `:`, then `accessibility_enabled=1`.
  - Disable: filter the entry out of the list, leaving other services alone.
  - Both need `WRITE_SECURE_SETTINGS`.
  - The Mac-side command `scripts/tv.sh 'settings get secure enabled_accessibility_services'` should read `io.github.toolicious.homeonfire/.HijackService` (our detox README confirms).
- **`BootReceiver`:** launches the target after boot only if `launch_on_boot` is on **and** the service is enabled. HoF also relaunches on wake from standby (`ACTION_SCREEN_ON`, registered dynamically). That means our launcher also comes up after wake when HoF is on.
- **Known limitations** (README):
  - ~250 ms Amazon-home flash on Fire OS 8.
  - A kids profile can lock you in.
  - A Home press while Amazon home is already in front (including Amazon's Apps screen) cannot redirect.
  - Back or OK on the highlighted Home tab gets you out of that state.
- **License handling:** GPLv3-or-later. Our launcher stays a separate app that only does these things: appears in HoF's picker, declares HOME and LEANBACK_LAUNCHER, and edits a public Android setting. No HoF code or resources are copied. Document that and name the integration.

## 4. Ranked "take this" list

| # | Item | Source (file) | What and why |
|---|---|---|---|
| 1 | Floating glass highlight with springs | `VH/.../GlassView.java`, `MainActivity.onTileFocused` | One shared highlight moved by `SpringAnimation` (stiffness 260, damping 0.58) gives the "Apple TV focus glide". Keep the scroll-shift trick. Tune damping up (~0.7-0.75) for less wobble. |
| 2 | Banner-colour ambient glow and hero name | `AppAdapter.vividColor`, `MainActivity.showHero/setAmbient` | Cheap radial glow from a 12×12 sample; no blur. Extend the hero beyond the dock row (today only the dock row gets it). |
| 3 | Dock band, 4-col grid, tile 180×101, radius 16 | `DockDecoration.java`, `item_app.xml`, `tile_bg.xml` | Close to the Apple look. Make the dock real (pinned list in settings) instead of positional. |
| 4 | Focus logic (swallow edge keys, restore by component key, stable ids) | `MainActivity.onTileVerticalKey/focusTile`, `AppAdapter` | Prevents the classic focus-escape bugs. Verify on the real device anyway. |
| 5 | Package receiver, async art loading, art cache | `DT/.../Home.java` (`loadApps/loadArt`, `packages` receiver), `Catalog.java` | Replaces per-resume `reload()`. No main-thread I/O. |
| 6 | Config file model (`home.conf`) with signature refresh and atomic write | `DT/.../Config.java` | Adb-pullable settings, hand-editable. Use this idea for the app order, hidden apps, dock list and wallpaper. |
| 7 | Clock screensaver (drifting clock, cross-fading glow) | `VH/.../ClockDream.java`, `dream_clock.xml` | Burn-in-safe and tiny. Check on-device that Fire OS 8 accepts a custom dream. |
| 8 | Accessibility-service list editing (read, filter, clear, re-add) | `DT/install.sh` (`set_guard`), HoF `AccessibilityHelper` (logic only) | Core of the reset button. Use `SERVICE_ID` plus its fully qualified variant when matching. |
| 9 | Fast first frame and cheap draw | `DT/.../Tile.java` (single `onDraw`), `Home.onTrimMemory`, `cmd package compile -m speed -f` | Black window theme, no window animation, precompiled install. Test `trimMemory` reflection on API 30. |
| 10 | Direct-tools build script | `DT/build.sh` | No Gradle, `javac --release 8`, builds in seconds. Switch platform to android-30. Use if we avoid AndroidX. |
| 11 | Outfit variable font (OFL) | `VH/.../res/font/outfit.ttf` + `FONT-LICENSE-OFL.txt` | Light, geometric look close to the thin Apple type, without using Apple fonts. Bundle the licence file. |
| 12 | `LEANBACK_SETTINGS` intent for Settings | `DT/Home.openSettings` | Avoids the Amazon-home bounce. Verify on Fire OS 8. |
| 13 | Wallpaper decode with `inSampleSize` | `DT/Home.decode` | Cheap user wallpaper without permissions. Pair with a pre-blurred bitmap (API 30 has no live blur). |

## 5. "Avoid this" list

1. `VH/launcher/build-and-install.sh` as written. It disables the stock launcher and calls `set-home-activity`, which does nothing on Fire OS and is dangerous. Write our own installer. No `pm disable-user` on anything near the home path.
2. `DT/Guard.java` and the `alarm_manager_constants min_futurity=0` setting. Fire OS 7 only, and it conflicts with HoF.
3. `HomeKeyService` and `InputKeyService`. No `KEYCODE_ALL_APPS` or `KEYCODE_TV` use on Fire.
4. `ACCESS_FINE_LOCATION` and Wi-Fi SSID code. Needs a permission grant, and the Stick is not the use case.
5. Calling `getAllNetworks()` every minute and the duplicate network callbacks.
6. Loading banners and computing colours on the main thread in `onResume`.
7. Accent-ring focus (dumbtv) and any visual from dumbtv. Different language from Apple's.
8. `Settings.ACTION_SETTINGS` and `ACTION_HOME_SETTINGS` (`HomeSettingsActivity`, "Default home" tile). Dead on Fire OS.
9. Anything that touches HoF code or resources. GPLv3 contaminates an MIT project.
10. Do not request TV-listings or recommendations permissions. Neither repo does; keep it that way.
11. Do not rely on `onKeyEvent` for Home. Fire OS never delivers it.
12. dumbtv's `targetSdk 28`. Use 30 or higher; this makes `<queries>` necessary, as in tv-vibehack.

## 6. Reset design implications (requirement 2)

- **Inside the launcher:** a settings row "Return to Amazon Fire TV home" that:
  1. Removes `io.github.toolicious.homeonfire/.HijackService` (and the full-class variant) from `enabled_accessibility_services`, keeping other services.
  2. Fires `ACTION_MAIN` + `CATEGORY_HOME` with `setPackage("com.amazon.tv.launcher")`.
  - A second row, "Switch back to Apple-style home", re-adds the entry and `accessibility_enabled=1`.
  - Needs the one-time `pm grant ... WRITE_SECURE_SETTINGS`. If the grant is missing, show the exact adb command (HoF does the same with a toast) and fall back to telling the user to hold Home.
- **Always-available fallbacks (HoF process, so they work even if we crash):** long-press Home, double-press Home, Menu long-press for HoF's config.
- **Mac side:** the existing `scripts/undo.sh home` in the author's separate detox project already puts Home back; extend it to also remove our launcher's entries if we add any. Keep a full `undo all` that never disables Amazon packages.
- **Test on the device** (cannot be proved by unit tests): Home press lands on ours; removing the HoF entry makes Home land on Amazon; long-press Home works with our launcher in front; crash our launcher on purpose (`am force-stop`) and confirm Home still recovers; reboot and re-check; wait ~10 s before screenshots.

## 7. Not verified, to settle during the build

- Whether Fire OS 8 honours a custom `DreamService` selection.
- Whether `LEANBACK_SETTINGS` opens the real settings on Fire OS 8.
- Whether the `WindowManagerGlobal.trimMemory` reflection works under API 30 hidden-API rules.
- Whether disabling HoF's service while HoF's UI is open is rejected (should not be, since the write is just a secure-setting change).
- The exact size of the ~250 ms flash on this Stick.

Next action (under 2 minutes): run `brew install openjdk@17 && brew install --cask android-commandlinetools`, then `sdkmanager "platforms;android-30" "build-tools;34.0.0"`, so the build phase can start.
