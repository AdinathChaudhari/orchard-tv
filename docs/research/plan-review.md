# Plan review (Opus, 2026-10-19)

An adversarial review of `../PLAN.md` Rev 1. The Fable planner then wrote Rev 2.
**Result: 23 issues raised, 23 accepted (item 18 with changes), 0 rejected.** The resolution table is at the bottom; the full reasoning is in PLAN.md section 6 (Review log).

---

## Full review (verbatim)

Orchard plan review: 23 issues, ranked by severity. The plan has three bugs that break the reset path, plus unclear scope on "original Fire TV".

Checked by reading the files: `PLAN.md`, the 5 research files, the dumbtv and tv-vibehack sources, both LICENSE files, `scripts/undo.sh`, `config/debloat.txt`, the Mac toolchain, and Home on Fire (HoF) source fetched from GitHub (`HijackService.java`, `MainActivity.java`). The TV was not touched. The plan's Mac facts are correct and the brief is wrong: openjdk@21 is installed as a Homebrew keg, the SDK is at `/opt/homebrew/share/android-commandlinetools` with build-tools 34.0.0 and only `platforms/android-34`, so `platforms;android-30` must still be installed.

**MUST FIX: the reset path can break or lie**

1. **CrashGuard can switch off the user's working Home redirect during development (§2.2, Phases 2–5).** Until Phase 6 Orchard is not HoF's target, but CrashGuard strips HoF after 3 crashes in 60 s. A buggy build crashing 3 times while the manager tunes it with `am start` turns off the Projectivy redirect without telling anyone. Home then lands on Amazon.
   - Fix: arm the HoF stripping only when `orchard.conf` has `crashguard_strip=on`. Set that flag at G6a.
   - Fix: also require that the crashing launch was a Home launch. HoF's intent carries `CATEGORY_HOME`; `am start` does not.
   - Fix: before G6a, CrashGuard only sets `safe_mode`.
   - Add an R6b row: crash 3 times with the flag off and confirm the HoF entry is still present.

2. **Race condition in `returnToFireTv()` (§2.1 steps 2→5).** `putString` returns before the system unbinds HoF's accessibility service. The Amazon-home `startActivity` fires a few ms later, so HoF may still be bound, see Amazon home arrive, and redirect straight back to its target. `HijackService.launchTarget` runs on every Amazon-home arrival.
   - The Mac script already does `sleep 1`; the in-app path does not.
   - Fix: after the write, poll `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` until HoF is gone (every 100 ms, up to 2 s), then launch Amazon home.
   - Fix: if HoF is still listed after 2 s, show "Hold Home on the remote".
   - Add to R2: repeat it 5 times in a row; 5/5 must land on Amazon home.

3. **Menu key collides with HoF's long-press Menu.** Verified in `HijackService.onKeyEvent`/`handleMenuKey`: HoF does not consume Menu DOWN. After 500 ms it opens its own config screen and swallows the repeats and the UP.
   - If Orchard opens the Quick Panel on DOWN, every long-press opens both the panel and HoF's config.
   - Fix: open the Quick Panel only on `ACTION_UP` with `!isCanceled()`, no repeats seen, and hold time under 450 ms.
   - Fix: ignore a DOWN that never gets its UP.
   - Add an on-device check (user, physical remote): short Menu opens the panel only; long Menu opens HoF only.

4. **"Go back to the original Fire TV" is reduced to "Amazon home" without asking the user.** The in-app reset leaves 30 Amazon packages disabled, updates blocked, AdGuard DNS on, Projectivy, HoF and Orchard installed. The user may mean a fully stock Stick.
   - The app cannot re-enable packages: `WRITE_SECURE_SETTINGS` is not enough, that needs `pm enable` from the Mac.
   - Fix: ask the user one question before Phase 0: "Amazon home only, or undo everything (debloat, DNS, updates)?"
   - Fix, either way: the confirm sheet states what it does NOT undo and names the Mac command.
   - Fix: add `launcher.sh factory-stock`, which runs `reset-to-stock`, then `undo.sh all`, then uninstalls Orchard. It needs its own user gate.
   - Fix: add an `undo.sh orchard` layer, so `undo.sh all` knows Orchard exists.

5. **The crash failsafe misses most real failures.** The uncaught-exception handler does not see native crashes (hwui/Bitmap), ANRs, a hang or black screen, a crash before the handler is installed, or the system's "keeps stopping" kill.
   - Fix: add a startup marker. Increment `boot_attempts` in internal storage at `Application.onCreate` line 1. Clear it after the first frame draws, using a `ViewTreeObserver.OnDrawListener` and then a post.
   - Fix: if `boot_attempts >= 3`, show SafeModeView. Strip HoF only under the item 1 rules.
   - Fix: keep `safe_mode` and the crash log in `getFilesDir()`, not the hand-editable `/sdcard` config. That also avoids slow external-storage writes inside a dying process.
   - Fix: SafeModeView's "Return to Fire TV" row must show the grant-missing instructions when there is no grant.

6. **Declaring `HOME` could change how the Stick picks its home app.** Installing a new HOME-capable app can reset the preferred home or bring up a "choose home app" dialog. The research marks this unverified. Projectivy's install is a weak precedent only.
   - Fix: add R0 before and after the G2a install: `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`, plus a raw `input keyevent 3` screenshot. The outputs must match.
   - Fallback: build without the HOME filter. HoF only adds `CATEGORY_HOME` when the target declares it, and Back-loop handling then rests on our own "Back is consumed" rule.

7. **The status chip and Quick Panel can report the wrong state.**
   - `redirectOn()` only reads the accessibility list. If the user turns off HoF's "Replace Home" (`hijack_enabled`, a private pref we cannot read), Orchard still says the redirect is on.
   - The "Switch Orchard home on" tile actually restores HoF's current target, which is Projectivy until G6a.
   - Fix: label the tile "Turn Home redirect back on (goes to: last target)".
   - Fix: record `last_home_launch_at` whenever HoF launches us with `CATEGORY_HOME`. Show "Redirect may be off in Home on Fire" when the service is listed but no Home launch has arrived in this boot after a Home press.

8. **Device changes outside any gate, and no snapshot before them.**
   - Task 2.2 runs `dumpsys deviceidle whitelist +tv.orchard.home` and `cmd package compile -m speed -f`; G2a's wording mentions neither.
   - Phase 4 runs `settings put global animator_duration_scale 0`, then "restore to 1" without reading the original value. It may be 0.5.
   - Phase 5 changes `screensaver_components`; G5 asks first, but the original value is not saved.
   - Fix: in Phase 2, before G2a, write `backup/orchard-pre.txt` with:
     - `enabled_accessibility_services`, `accessibility_enabled`
     - `animator_duration_scale`, `screensaver_components`, `screensaver_enabled`
     - the `deviceidle whitelist` output
     - `resolve-activity` HOME
   - Fix: every undo restores from that file.
   - Fix: add deviceidle and compile to G2a's wording. Add a G4 gate for the animator-scale toggle.

9. **The debug crash hook is open to every app.** `--ez orchard.crash true` is "honoured in all builds" on an exported activity. Any app on the Stick could crash Orchard 3 times and, once item 1's flag is armed, turn off the user's Home.
   - Fix: honour it only when `orchard.conf` has `debug_crash=1`. The manager sets that through adb for R6 and removes it afterwards.

**MUST FIX: rendering and performance**

10. **The focused tile's shadow and gloss get clipped, and its neighbour draws over it.**
    - A hardware layer covers only the view's bounds. Shadow drawn in `TileView.onDraw` with 128 px of padding is cut off during every focus animation, then pops in when the animation ends.
    - With "no elevation", a LinearLayout draws later children on top. The 1.12x focused tile is covered by its right neighbour.
    - Fix: draw the shared shadow in `TileRow.dispatchDraw` before the children, using the focused child's transformed bounds.
    - Fix: draw the focused child last via `setChildrenDrawingOrderEnabled(true)` + `getChildDrawingOrder`.
    - Fix: drop the hardware layer. RenderNode transform animations do not need one.
    - Add a check: a screenshot halfway through the focus animation shows no clipped shadow and no neighbour overlap.

11. **Memory: too-large tile bitmaps and full-size blur copies.**
    - 520x312 ("2x") art is about 325 KB per tile. With ~40 apps that is ~13 MB, against a cache cap of memoryClass/8, so the cache will churn.
    - A 1.12x focus scale only needs about 292x175. Use 1.25x, i.e. 325x195, about 127 KB per tile.
    - Never create the 1920x1080 upscaled blur bitmap; that is 8 MB in ARGB.
    - Draw the 240x135 blur with `drawBitmap(src, dst, filterPaint)`. Glass panels sample the same small bitmap with a source rect.
    - Rounded corners: `clipPath` anti-aliasing is not guaranteed. Use `BitmapShader` + `drawRoundRect` with `ANTI_ALIAS_FLAG`, which works with RGB_565 art.

12. **The idle sway animates forever.** A focused tile on the idle home screen redraws every frame indefinitely: constant CPU/GPU load and heat on an A55 Stick, and a jank risk when the app goes to the background.
    - Fix: stop the sway after 3 cycles or 20 s, and in `onPause`.
    - Add a check: after 30 s idle, `dumpsys gfxinfo` total frame count stops rising.

13. **The perf tests cannot measure what they claim.**
    - Each `tv.sh 'input keyevent X'` call starts an `input` process (~0.5–1 s on this CPU), so "400 ms apart" is impossible. 30 separate presses only measure slow navigation.
    - Fix: `input keyevent 22 22 22 22 22` in one call (API 30 accepts several keycodes) for fast sequences.
    - Fix: add a user test with the physical remote: hold RIGHT and DOWN for 3 s each, check that focus stays visible and the scroll does not fall behind.
    - Fix: measure `gfxinfo` across both.

**MUST FIX: parallel tasks and worker sizing**

14. **Parallel tasks write to the same files and the same build tree.**
    - Task 1.2's `HomeActivity` shows `SafeModeView`, while 1.3 writes `SafeModeView` at the same time. 1.2 cannot pass a build on its own.
    - Phase 3 group A: 3.1 (ArtCache sizes) and 3.3 (StatusStrip fonts/px) both need `Ui.java`, which is assigned to 3.2.
    - 4.1, 4.2 and 4.3 all edit `HomeActivity`.
    - 5.1 and 5.3 edit `HomeActivity` (Menu wiring, long-press Select).
    - 5.2 and 5.4 both edit `AndroidManifest.xml` and `strings.xml`.
    - `Config.java` is edited by 1.3, 4.3 and 5.2/5.3.
    - Several workers each running `build.sh` over one `src/` tree will compile each other's half-written files.
    - Fix: 1.2 owns `Ui.java`, the manifest, all `res/values`, and stub classes for every planned file (signatures plus key names), created up front.
    - Fix: later tasks fill their own files only and return manifest/strings additions as text for the manager to merge.
    - Fix: hook changes inside `HomeActivity` run one at a time, in order 4.1 → 4.2 → 4.3 and 5.1 → 5.3.
    - Fix: each parallel worker builds in its own git worktree.

15. **Some tasks are too big for one Sonnet worker.** Split each into parts with separate acceptance checks:
    - 3.4 → 3.4a (layout, scroll rule, catalogue binding) and 3.4b (key routing, focus memory, press-and-launch, trim/stop release).
    - 5.2 → settings shell + toggles, then the dock picker/order and hidden-apps lists.
    - 1.5 → `launcher.sh` reset/status/restore, then build/push/install/shot/key/perf.

16. **No off-device tests for the code the reset depends on.** Strip/append of the accessibility list, Config parse/atomic write and the CrashGuard time window are pure Java.
    - Fix: put them in plain-Java helpers with `tools/test/*.java` run by host `javac`/`java` in `build.sh`. Cover:
      - empty string, null, `"null"`, both ID forms, duplicates, trailing ':', other services kept
    - Mutation-check them (break one line and confirm a test fails).

**SHOULD FIX: design gaps against real tvOS**

17. **The hero band will be a colour blur.** Banners are 320x180 and often contain the logo as text. Blurring that to 120x72 and stretching it over 1920x640 gives a colour smear. That leaves 54% of the screen for one title line.
    - Fix: hero = a gradient from the banner's vivid colour, plus the sharp banner drawn no larger than 2x (640x360) at the right, plus the title and subtitle.
    - Fix: try it in the G0 mockup with real banner-sized placeholders, not ideal gradients.

18. **Mockup should test 5 columns too.** I believe the tvOS home grid is 5 columns of about 300x180, not 6x260 (unverified; the research sources conflict: the Xamarin 300x180 vs the HIG width table). Also, tvOS has no permanent clock on the home screen.
    - Fix: add a `?cols=5|6` switch and a clock on/off switch to the mockup, and decide at G0.
    - Fix: the focused label must sit below the scaled tile (bottom + 9 px + 16), not the unscaled one.

19. **The reset is not visible on screen.** It is reached only through the Menu key, and nothing on screen says Menu exists. The user asked for a visible option.
    - Fix: a permanent Caption 2 hint in the status strip: "≡ Options".
    - Fix: optionally, a last grid tile "Fire TV Home" that opens the same confirm sheet (the HIG research recommended this; the plan dropped it).

**SHOULD FIX: licences, estimates, unverified intents**

20. **Licence obligations are understated.**
    - MIT requires the full copyright and permission notice whenever code is adapted: "Copyright (c) 2026 Gathin" and "Copyright (c) 2026 dumbtv contributors". "Attribution comment" is not enough.
    - Fix: add `launcher/THIRD_PARTY_NOTICES.txt` with both MIT texts plus Inter OFL, and show it under About.
    - Fix: workers get only the integration contract, never the HoF source or `repo-mining.md`'s line references to GPL code.
    - Fix: check Inter's `OFL.txt` for a Reserved Font Name. If there is one, the `pyftsubset` output must be renamed.

21. **Estimates are optimistic.** Phase 3's 1.5 days assumes focus, z-order and scroll work on the first device pass. Items 10, 13 and 14 make that unlikely, and every on-device loop needs the manager plus 10 s screenshot waits.
    - Fix: Phase 3 is 2.5–3 days, Phase 4 is 1.5 days.
    - Fix: add half a day of buffer per phase for focus regressions.

22. **Intents and the screensaver test are unverified on Fire OS 8.**
    - `ACTION_APPLICATION_DETAILS_SETTINGS` (context-menu "App info") may hit the same Amazon stub as `ACTION_SETTINGS`. Fix: resolve it on the device in Phase 5 and hide the row if it fails.
    - `com.android.systemui/.Somnambulator` may not exist on Fire OS. Fix: check with `cmd package resolve-activity` first, and restore `com.android.dreams.basic/.Colors` from the snapshot in item 8.

23. **Mac-side undo assumes the Stick keeps IP <tv-ip>.** If DHCP changes it, `reset-to-stock` fails.
    - Fix: `launcher.sh` reads the IP from an env/config value.
    - Fix: README says to reserve the IP in the router and how to find the IP on the TV (Settings → My Fire TV → About → Network).


---

## How each issue was resolved (planner, Rev 2)

1. accepted: CrashGuard strips HoF only when armed at G6a + granted + CATEGORY_HOME launch; R6b added.
2. accepted: returnToFireTv polls AccessibilityManager until HoF unbinds before launching Amazon home; R2 5/5.
3. accepted: Quick Panel on Menu ACTION_UP only, <450 ms, no repeats; R11 remote check.
4. accepted: Q0 asks the user; sheet lists what is kept; factory-stock command and undo.sh orchard layer added.
5. accepted: boot_attempts marker + first-frame clear; state in getFilesDir(); SafeModeView grant text.
6. accepted: R0 HOME resolve-activity before/after install; fallback without HOME filter.
7. accepted: tile relabelled with target; last_home_launch_at; "Redirect may be off" chip.
8. accepted: snapshot before G2a, restore-settings from it, G2a wording, new G4 gate.
9. accepted: debug crash hook gated by debug_crash=1; status warns if left on.
10. accepted: shadow in TileRow.dispatchDraw, focused-last draw order, no hardware layers, mid-animation check.
11. accepted: 325x195 art, no 1920x1080 blur bitmap, BitmapShader round rect.
12. accepted: sway stops after 3 cycles/20 s and onPause; idle frame-count check.
13. accepted: multi-keycode single input call; user hold test; gfxinfo on both.
14. accepted: 1.2 owns shared files + stubs; text merges; serialised HomeActivity hooks; worktree per worker.
15. accepted: 3.4, 5.2, 1.5 split into a/b parts.
16. accepted: pure-Java helpers with host tests in build.sh, mutation-checked (R12).
17. accepted: hero = colour wash + sharp 640x360 banner; mockup uses realistic banner placeholders.
18. accepted (modified): cols/clock switches in mockup, label under scaled tile; 6 cols stays default, 5-col alt is 320x192 to fill the safe width.
19. accepted: permanent "≡ Options" hint; "Fire TV Home" last grid tile.
20. accepted: THIRD_PARTY_NOTICES.txt with full MIT texts + OFL shown in About; workers get integration contract only; OFL RFN check in 1.4.
21. accepted: Phase 3 2.5–3 d, Phase 4 1.5 d, Phase 5 1.5 d, +0.5 d buffer per phase.
22. accepted: App-info and Somnambulator intents resolved on device first; screensaver restored from snapshot.
23. accepted (already partly true): tv.sh honours TV_HOST; launcher.sh passes it through; README gets IP reservation note.

**Rejected:** none.