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
5. **Unverified until tested on the device:** whether `ACTION_APPLICATION_DETAILS_SETTINGS`
   and `com.android.systemui/.Somnambulator` resolve, and whether installing a second app with a
   HOME filter changes which home app the Stick picks. Resolve each one before relying on it.

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
