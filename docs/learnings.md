# Learnings: Fire TV Stick 4K Plus (Orchard launcher)

Things learned the hard way or verified while working on this Stick. Each entry has
the cause and the rule to follow. Last updated 2026-10-19.

Sources: the detox run (`scripts/detox.sh`, see [DETOX.md](DETOX.md)), the 5 research
files in this folder, and the Opus plan review ([plan-review.md](plan-review.md)), which read the
Home on Fire source and the Mac toolchain directly.

---

## 1. Talking to the Stick (ADB)

1. **Use `scripts/tv.sh` / `tools/adb/tv.py` only, never a bare `adb connect`.** `tv.py` holds the key
   the TV approved; a new adb key brings up a new "Allow" prompt on the TV.
2. **`monkey` does not launch apps here** (exit code 251). Use
   `cmd package resolve-activity --brief <pkg>` and then `am start -n <component>`.
3. **Wait about 10 s after pressing Home before taking a screenshot.** The first frame is black
   while the home screen loads.
4. **Every `input keyevent` call costs ~0.5–1 s** (it starts a new `input` process). For fast
   sequences put several keycodes in one call: `input keyevent 22 22 22 22`. Holding a key down
   can only be tested with the physical remote.
5. **YouTube's "playing" media session is always on** (position -1, speed 0). Ignore it when
   checking whether the TV is in use.

## 2. Fire OS 8 behaviour

1. **Some Amazon packages are "protected"** and `pm disable-user` refuses them (8 on this Stick).
   `appops set <pkg> RUN_IN_BACKGROUND ignore` restricts them instead.
2. **Fire OS ignores third-party HOME apps.** Only an accessibility-service redirect (Home on
   Fire) works on Fire OS 8. LauncherHijack, FTVLaunchX and Launcher Manager no longer work.
3. **Aggressive debloating can leave the Stick stuck on the boot logo.** Never disable the
   launcher, platform, input, systemui, settings or providers packages.
4. **Android 11 (API 30) has no `RenderEffect`**, so no live blur. Blur a small bitmap once
   (240x135) and draw it scaled.
5. **`DreamManager.testDream` (the "test" route) always crashes a non-windowless dream on Android 11 / Fire OS 8.**
   `DreamService.attach` calls `ATMS.startDreamActivity`, whose `enforceCallerIsDream` asks
   `isDreaming()`, and that is `false` for a test dream (`!mCurrentDreamIsTest`). Even Android Colors would throw
   `SecurityException` there (fixed upstream in 12). Test the real route only: set `screensaver_components`, then
   `am start -a android.intent.action.MAIN -n com.android.systemui/.Somnambulator` (resolves on the Stick; calls
   `IDreamManager.dream()`, `isTest=false`). The exception is thrown by framework code on the main thread, so a
   dream cannot catch it and it kills the whole process. Do NOT move the dream to its own `android:process`:
   `ATMS.startDreamActivity` launches the framework `DreamActivity` in the package's default process, and its
   `onCreate` casts the callback binder to the in-process `DreamServiceWrapper`; with the service in `:dream` the
   activity gets a `BinderProxy`, throws `ClassCastException` and kills the launcher process (seen on the Stick,
   2026-10-20 22:17, real route). The dream stays in the launcher process; the real route does not throw.
   `ACTION_APPLICATION_DETAILS_SETTINGS` resolves too. Still unverified: whether a second app with a
   HOME filter changes which home app the Stick picks.
6. **Count launches, not process starts.** `boot_attempts++` used to run in `OrchardApp.onCreate`, so a dream
   bind or a receiver-only process start (DebugReceiver while Orchard is dead) counted as a launch and was never
   cleared by a first frame. Three of those in a row made the next Home press look like a crash loop and stripped
   Home on Fire with no crash at all. The increment now runs in `HomeActivity.onCreate` (Application-stage
   crashes are still caught by the crashes-file window).

## 3. Home on Fire (read from its source; it is GPLv3, so never copy its code)

1. **Switching it off is one secure setting:** remove its id from
   `enabled_accessibility_services`. Our app can do this only after
   `pm grant … WRITE_SECURE_SETTINGS` from the Mac.
2. **Unbinding is not instant.** Launching Amazon home straight after the write can be
   redirected back. Poll `AccessibilityManager` until the service is gone (up to 2 s) first.
3. **It owns long-press Menu** (opens its config after 500 ms and swallows the key-up). Our
   Menu actions must fire only on a short press's `ACTION_UP`.
4. **Its "Replace Home" switch is a private preference.** Other apps can't read it, so "redirect
   on" can only be inferred, for example from whether a Home launch actually arrived.
5. **Long-press Home always escapes to Amazon's home.** This is the failsafe of last resort;
   never interfere with it.

## 4. Building the launcher (Orchard)

1. **Mac toolchain is mostly present:** Homebrew `openjdk@21` (keg-only, not on PATH) and the
   SDK at `/opt/homebrew/share/android-commandlinetools` (build-tools 34.0.0). Missing:
   `platforms;android-30`. `/usr/bin/java` is only the macOS stub.
2. **Plain Views beat Compose on a 2 GB Stick.** Both reference launchers use plain Views.
3. **Focus z-order:** draw the shared shadow in the row's `dispatchDraw` and draw the focused
   child last. A hardware layer clips the shadow during animation.
4. **Memory:** keep tile art at 325x195 RGB_565 (~127 KB). Never create a 1920x1080 blur bitmap
   (8 MB). Stop idle animations after 20 s so the home screen doesn't redraw forever.
5. **MIT reuse needs the full licence text** (Gathin; dumbtv contributors) in
   `THIRD_PARTY_NOTICES.txt`, not a code comment. Check Inter's OFL for a Reserved Font Name
   before subsetting it.

## 5. Running multi-agent work on the TV

1. **Never auto-uninstall on a failed check.** A retried "try again" was counted as a failure,
   and the rollback uninstalled both apps. Failure handling now only switches the redirect off.
2. **Claude Code blocks `pm grant` and `reboot` from workers.** Plan these as user gates and
   don't let workers try to get around the block.
3. **Every device change needs a snapshot first** (accessibility list, animator scale,
   screensaver, deviceidle whitelist, HOME resolution), and undo restores from it. Never
   hard-code "restore to 1".
4. **Parallel workers must not share files or build trees.** One task owns the shared files
   (manifest, `Ui.java`, `res/values`); others return their additions as text for the manager
   to merge; each worker builds in its own worktree.
5. **Crash failsafes must not act during development.** A crash-loop breaker that switches
   off Home on Fire stays disarmed until Orchard is actually the Home target (gate G6a).

## 6. Ads and expectations

1. **Prime Video, live sports and live TV ads are stitched into the video stream.** DNS can't
   block them without blocking the show.
2. **ZEE5 ads might be blockable** if they come from separate ad servers (NextDNS log test,
   still open).
3. **A "reset to original" button inside the app can only bring back Amazon's home.**
   Re-enabling packages, DNS and updates needs `pm enable` / `settings put` from the Mac
   (`launcher.sh factory-stock`).

## 7. Launcher on the Stick (Phase 4 findings)

1. **adb cannot touch `/sdcard/Android/data/<pkg>` on SDK 30.** The dir is `drwxrws--- u0_a121:ext_data_rw` and
   the shell user is not in `ext_data_rw`: `ls`/`cat`/`echo >>` return Permission denied. Debug flags and the
   wallpaper drop therefore cannot go through the file. Use `am start --es orchard.debug "k=v;k=v"` (only
   `debug_*` keys) and `--ez orchard.debug_clear true`; `crashguard_strip` is refused on this channel.
   **am start extras are replayed as the task root intent after process death.** A screenshot run started with
   `--es orchard.debug "debug_art=placeholder"`; later another app's memory use killed Orchard, Android recreated
   HomeActivity with the original root intent, and `onCreate` re-applied the flag: the user saw letter plates instead
   of logos. Fix: `onCreate` applies extras only with a one-time `--es orchard.debug_nonce <value>` that differs from
   the last consumed one (`DebugNonce`, stored in state.conf); `onNewIntent` (live delivery) needs none. The shell form
   `am broadcast -n tv.orchard.home/.DebugReceiver --es set/unset ...` is never replayed, and now bumps the settings
   epoch and notifies the live HomeActivity (`OrchardApp.notifySettingsChanged`) so `ArtCache.setPlaceholder` is
   recomputed and tiles + shelf rebuild without a force-stop (before, the process kept drawing placeholders).
2. **Full-width `ComposeShader` rects are GPU-bound on the Stick.** Four of them (wash, leak, two vignettes)
   under the Top Shelf measured Janky 97.9% on dock-row moves; skipping them gave 4.9%. Bake gradients into
   quarter-resolution bitmaps once and draw filtered bitmaps with only the paint alpha changing.
3. **The "redirect may be off" chip needs Orchard to have been the HOME target once.** While Projectivy is
   Home on Fire's target, Orchard is never launched as HOME, so the old test (Home press seen, no HOME launch)
   was always true. `RedirectCheck` requires an earlier HOME launch on record.
4. **Background memory (open):** 55.2 MB PSS 30 s after Home vs the 45 MB target; Graphics keeps 26.8 MB after
   onStop. Orchard drops art, shelf, wallpaper and baked shelf bitmaps in onStop; the remaining Graphics is most
   likely the window surface buffers and HWUI pipeline caches that the app cannot free from Java. Target is
   not revised until the next on-device measurement attributes it (`dumpsys meminfo` Graphics vs Native).
5. **`debug_time` must pin every time-derived element**, not just the sky. The header clock and the screensaver
   clock read `System.currentTimeMillis()`, so media shot with a pinned minute showed the real time in the
   header (README stills read "06:00" over a "21:27" raw capture). `ClockFormat.pinnedMillis` now feeds
   `StatusStrip` (via `AmbientState.clockMs`) and `ClockFace`; the date stays real. Source README media from raw
   captures only, never from retouched sets.
6. **Rain streaks vanish on light skies** by construction: a slate tile at alpha .25 over a 0.72-luma sky peaks
   at 0.066 contrast, and the Stick's H.264 halves it. Light-sky streak alpha now tracks the sky
   (`SkyPhase.rainAlphaLight`: .20 / (luma - .305), clamped .25-.55, times the day factor, which still ramps from 0
   at the theme flip, so mid-luminance text contrast is untouched). Capture rain at 720p (`screenrecord --size
   1280x720`): the Stick's 1080p readback gives ~11 fps, 720p ~27 fps. Trim to exactly 2 rain loops (2.954 s) for
   a seamless GIF; scale to 960 px, since 640 px turns a 1.4 px streak into 0.5 px.
7. **Memory peak came from un-recycled keyframes, not an unbounded cache.** After ~10 `debug_time` switches Graphics
   climbed 21 -> 57 MB and total PSS read 132-137 MB under rain (idle 100 MB; fresh 55-68 MB);
   `am send-trim-memory RUNNING_LOW` dropped 132 -> 100 MB, so the memory was garbage waiting for the GC. Each
   960x540 keyframe is 2 MB of pixels plus a GL texture, and `SkyView.prune()` only nulled the reference. Fix:
   pruned / released keyframes and evicted Halo sprites are recycled explicitly one second later (sky ones on the
   sky thread so a queued bake finishes reading first); `onTrimMemory >= RUNNING_LOW` calls `Halo.clear()` and
   `SkyView.trim()`. Re-measure on the TV: fresh, 20 switches, 15 s rain, `dumpsys meminfo` (target <= 120 MB).
8. **After `launcher.sh install` the Stick sits on the Amazon home**, and Home on Fire redirects only when that home
   comes to the front, so Home pressed from it stays there. `cmd_install` now ends with `am start
   tv.orchard.home/.HomeActivity`; from any app, Home then lands on Orchard.
9. **Measure janky frames only after the screen settles.** The first dock sweep within ~4 s of a `debug_time`
   switch reads 30-57 % janky because it counts the switch's own frames; wait 20 s after a switch.
