# Fire TV Stick 4K Plus: platform constraints, reset-to-stock design, and build toolchain

Research only. I touched no TV and wrote no repo files. I read local files and fetched the web pages cited inline. Items marked **[UNVERIFIED]** are not confirmed on this device or in a source.

## 0. Key findings

1. **No Java install is needed, only a PATH fix.** `openjdk@21` (21.0.11), plus `openjdk` and `openjdk@26`, are already installed as Homebrew kegs. They are keg-only, so `/usr/bin/java` is still the macOS stub.
2. **The Android SDK is already installed** at `/opt/homebrew/share/android-commandlinetools`. It holds `build-tools/34.0.0`, `platforms/android-34`, `cmdline-tools/latest`, `platform-tools` and `licenses`. The brief's "no SDK at ~/Library/Android/sdk" is true, but the Homebrew SDK is complete enough to build now.
3. **The best reset needs only one adb grant.** An in-launcher reset works if our app holds `WRITE_SECURE_SETTINGS`, granted once with `pm grant`. It removes Home on Fire's accessibility entry and launches Amazon's home. Home on Fire's own source performs this same write (see section 3).
4. **Home on Fire is the only redirect mechanism.** It cannot be toggled by intent or broadcast. The only controls are the accessibility setting, long-press or double-press Home, and its config screen.
5. **Official specs for the 4K Plus are in section 1.** The model number AFTMA08C15 appears on Amazon's page with no codename listed. `karat` is not on that page.

## 1. Hardware

Source: [Amazon device specifications](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html)

| | Fire TV Stick 4K Plus (2025), AFTMA08C15 |
|---|---|
| SoC | MediaTek MT8696D |
| CPU | 4x Cortex-A55, up to 1.7 GHz |
| GPU | Imagination GE9215, up to 650 MHz |
| RAM | 2 GB LPDDR4 |
| Storage | 8 GB |
| OS | Fire OS 8 |
| Video decode | up to 4K @ 60 fps |

- **Same chip as the 2nd-gen 4K (AFTKM).** The 4K Max uses the MT8696T (2.0 GHz CPU, 850 MHz GPU). See [CNX Software](https://www.cnx-software.com/2023/09/22/amazon-fire-tv-stick-4k-max-2023-features-a-2-ghz-mediatek-mt8696t-cpu-support-wifi-6e/).
- **No public MT8696 datasheet.** MediaTek has not published one, so these figures come from Amazon only ([computerbase thread](https://computerbase.de/forum/threads/google-tv-streamer-streaming-box-loest-chromecast-ab-und-ist-smart-home-hub.2205456/page-4)).
- **Storage on this unit.** From `backup/preflight.txt`, `/data` is about 5.1 GB total, 60% used, about 2.0 GB free. That is plenty for a launcher, but keep disk caches under 50 MB.
- **Usable RAM is well under 2 GB.** Fire OS, Alexa and the streaming apps take most of it.
  - Measurements from dumbtv's README, on a 1.4 GB Fire OS 7.7 TV:

    | Launcher | PSS |
    |---|---|
    | dumbtv home, on screen | 57-72 MB |
    | dumbtv home, while an app plays | about 37 MB |
    | Projectivy 4.72 | 135 MB |

  - **Budget for ours:** at most 110 MB PSS with home on screen, and under 40 MB once backgrounded. Release bitmaps in `onTrimMemory` and `onStop`.
- **Display** [UNVERIFIED]. Fire TV sticks commonly render the UI at 1920x1080 even on 4K TVs. Measure it at build time with `wm size`, `wm density` and `getprop dalvik.vm.heapgrowthlimit`. Design in dp against a 960x540 dp canvas and check on the device.
- **Performance rule of thumb.** The GE9215 handles transform animations (translation, scale, alpha, a few rotations) on a handful of views at 60 fps. It struggles with full-screen alpha blends over large images, repeated `Bitmap` creation, and overdraw. Use pre-blurred bitmaps (see section 5).

## 2. Fire OS 8 quirks for third-party launchers

Sources: [Amazon Fire OS 8 notes](https://developer.amazon.com/docs/fire-tv/fire-os-8.html), [Home on Fire README](https://github.com/toolicious/home-on-fire), local `dumbtv/launcher/README.md` and `Guard.java`.

- **Base.** Fire OS 8 is Android 11 (API 30) with Android 10 and 11 changes. Google services are absent.
- **No default-launcher setting.** The HOME intent filter is ignored for default selection. Amazon's `com.amazon.tv.launcher` is a protected package. It is never safe to disable it, and no `pm set-home-activity` is needed.
- **tv-vibehack's takeover trick is dangerous here.** It disables the stock launcher and runs `cmd package set-home-activity`. The detox README says this approach is why that launcher "can't take over Home". Do not do it.
- **The HOME key cannot be intercepted.** Per dumbtv's measurement on Fire OS 7, the key is handled before any accessibility key filter sees it. So redirectors watch for Amazon's home window appearing, then launch the target.
- **Amazon home window identifiers**, from Home on Fire's `HijackService`. Source: [HijackService.java](https://github.com/toolicious/home-on-fire/blob/main/src/io/github/toolicious/homeonfire/HijackService.java)
  - `com.amazon.tv.launcher` / `com.amazon.tv.launcher.ui.HomeActivity_vNext`
  - Kids launcher: `com.amazon.tahoe` / `com.amazon.tahoe.ftv.HomeActivity`
  - Apps grid: `com.amazon.venezia`
  - Long-press panel: `com.amazon.tv.quicksettings.ui`
- **Fire OS 7 versus 8 timing.**
  - **Fire OS 7 delay.** Home-triggered launches are held up to about 5 s (`APP_SWITCH_DELAY_TIME`). dumbtv works around it with an `AlarmManager` PendingIntent plus `alarm_manager_constants min_futurity=0`.
  - **Fire OS 8 behaviour.** Home on Fire's README says Fire OS 8 has no such hold. Amazon's home flashes about 250 ms.
  - **Not needed here.** Our device is Fire OS 8.1.8.5, and the detox log shows Home reached Projectivy 6 out of 6 times. Do not copy dumbtv's alarm trick.
- **Hiding the 250 ms flash.** Give our theme a `windowBackground` matching the launcher's dark backdrop. Set `windowAnimationStyle` to null and keep `singleTask`, `resumeWhilePausing` and `excludeFromRecents`, as dumbtv's manifest does.
- **Fragility.** Home on Fire says a September 2025 patch closed older workarounds, and future Amazon updates may break it. The detox setup already blocks firmware updates, which protects the redirect.
- **Other Fire OS 8 behaviours that touch us:**
  - **"Are you still watching?"** After 4 hours without interaction the user is sent back to home. Home on Fire redirects that to us, which is fine.
  - **Package visibility.** At targetSdk 30 you need a `<queries>` block (see section 5).
  - **Install format.** At targetSdk 30, `resources.arsc` must be stored uncompressed and 4-byte aligned. The `aapt2` and `zipalign -p 4` pipeline does this.
  - **Signing.** v1-only APKs cannot be installed on Fire OS 8, so sign with v2 or higher. `apksigner` does this by default for minSdk 24 and above.
- **Sideload policy.** Sideloading over ADB works normally on Fire OS 8 ([AFTVnews](https://www.aftvnews.com/sideloading-and-downloader-app-work-fine-on-new-2023-2nd-gen-fire-tv-stick-4k-4k-max/)). No Appstore policy applies to ADB installs, so there is no review and no content restriction. Do not submit to the Appstore. There is also a late-2026 Fire OS bug where Downloader is missing from the "Install unknown apps" list, which does not affect ADB installs ([AFTVnews](https://www.aftvnews.com/fire-tv-bug-is-preventing-downloader-and-other-apps-from-appearing-in-the-install-unknown-apps-list/)).

## 3. How Home on Fire works, and what breaks it

Sources: [README](https://github.com/toolicious/home-on-fire), [HijackService.java](https://github.com/toolicious/home-on-fire/blob/main/src/io/github/toolicious/homeonfire/HijackService.java), [AccessibilityHelper.java](https://github.com/toolicious/home-on-fire/blob/main/src/io/github/toolicious/homeonfire/AccessibilityHelper.java). GPLv3: integrate only, never copy code.

**Mechanism**
- **Service.** It is `io.github.toolicious.homeonfire/.HijackService` (an `AccessibilityService`). It handles `TYPE_WINDOW_STATE_CHANGED` and `TYPE_VIEW_FOCUSED` events.
- **Detection.** When Amazon's home window arrives, it launches the target (not Home itself). It first tries `getLeanbackLaunchIntentForPackage`, then `getLaunchIntentForPackage`.
  - It adds `CATEGORY_HOME` if the target declares it.
  - The only flag it sets is `FLAG_ACTIVITY_NEW_TASK`, so a running instance comes forward.
- **Boot and wake.** `RECEIVE_BOOT_COMPLETED` launches the target at boot. On `ACTION_SCREEN_ON` it waits 500 ms, then relaunches the target if Amazon home is in front and launch-on-boot is on.
- **No network.** The manifest has no INTERNET permission.
- **Config.** It lives in private SharedPreferences (key names not documented). Nothing external can edit it. There is no intent, broadcast or API to toggle it or change the target.

**Button behaviour (Fire OS 8)**
- **Short Home** in an app opens our target.
- **Double-press Home** within about 1 s goes to Amazon home.
- **Long-press Home** is the most reliable escape. It toggles between the target and Amazon home, and it sets a 5 s bypass (`LONGPRESS_REDIRECT_BYPASS_MS=5000`).
- **Long-press Menu** (about 500 ms) opens the Home on Fire config screen.
- **OK or Back** on Amazon home's highlighted Home icon opens the target.

**Enable and disable through Secure settings**
- It reads `enabled_accessibility_services` (colon-separated) and `accessibility_enabled` (int).
- It checks both ID forms: the short `io.github.toolicious.homeonfire/.HijackService` and the fully qualified `.../io.github.toolicious.homeonfire.HijackService`.
- **Enable** appends its ID and sets `accessibility_enabled=1`.
- **Disable** removes only its own IDs from the list and leaves `accessibility_enabled` untouched.
- Both need `WRITE_SECURE_SETTINGS`, granted once through adb. Without it a `SecurityException` is caught and the call returns false.

**What stops a redirect** (checked in this order by `handleAmazonHomeArrival`):
1. Redirect disabled, no target, or the target is Home on Fire itself.
2. A recent OK or Back inside Amazon's shell (deliberate navigation).
3. A bypass window is active (`bypassUntil`), set by long-press escapes.
4. Debounce: a second Amazon-home arrival within 1000 ms with no Back in between is ignored.
5. Amazon home was already in front (Home can't redirect from there), or the Apps grid is showing.

**Risks that apply to us**
- **Crash loop.** If our launcher crashes at startup, each Home press can relaunch it. Home on Fire documents no crash-loop breaker [UNVERIFIED].
- **Reinstall resets accessibility.** tv-vibehack notes that Android switches accessibility services off on reinstall. This applies to the service's own package, not to ours.
- **Pulling the plug on Home on Fire** (uninstall or disabling it) also frees Home immediately.
- **Projectivy conflict.** Home on Fire warns that "Replace Home" clashes with launchers that claim Home. Projectivy's own "Override launcher" stays off (already done).

## 4. Always-available reset: three options and a recommendation

Current Mac-side state: `scripts/undo.sh home` deletes the whole `enabled_accessibility_services` value, sets `accessibility_enabled 0`, and force-stops Home on Fire. It is blunt but correct while Home on Fire is the only service.

### Option A: in-launcher button (needs `WRITE_SECURE_SETTINGS`)
- **Setup.** `pm grant <our.package> android.permission.WRITE_SECURE_SETTINGS`, once.
  - The permission must also be declared in our manifest.
  - The grant is lost on uninstall.
  - It normally survives `install -r` [UNVERIFIED on Fire OS].
  - The earlier detox session had `pm grant` blocked by Claude Code's safety check, so the user must approve it.
- **Action ("Return to Fire TV").**
  1. Read the secure setting `enabled_accessibility_services` and split it on `:`.
  2. Remove both Home on Fire ID forms and keep every other entry.
  3. Write the result back, leaving `accessibility_enabled` as is.
  4. Launch Amazon home: `Intent(ACTION_MAIN).addCategory(CATEGORY_HOME).setPackage("com.amazon.tv.launcher").addFlags(NEW_TASK)`. dumbtv's `Guard.amazon()` does the same.
- **Why disabling is required.** Launching Amazon home alone would be redirected straight back by the still-active service. Home on Fire only allows a 5 s bypass for long-press.
- **Re-enable ("Switch back to our home").** Append the ID again and set `accessibility_enabled=1`.
  - Keep `LEANBACK_LAUNCHER` on our main activity so we appear in Amazon's Apps row while the redirect is off.
  - Without the grant, show an "adb needed" help screen.
- **Trade-offs:** one-click and fully on-device, but it depends on the adb grant and on Home on Fire's service ID.

### Option B: crash failsafe (inside the launcher plus Home on Fire's escapes)
- **Crash-loop breaker.** In a `Thread.setDefaultUncaughtExceptionHandler`, count crashes in a file or SharedPreferences. After 3 crashes in 60 s, run Option A's disable.
  - Then auto-set `safe_mode=1` so a later manual launch shows a safe screen.
  - This needs the grant too.
  - It does not cover native crashes or ANRs.
- **Manual escapes that survive any crash (all of Home on Fire's):** long-press Home, double-press Home, or long-press Menu to open its config and turn off "Replace Home".
- **Trade-off:** the breaker is only as good as the exception handler, and the manual escapes are not ours to extend.

### Option C: Mac-side full undo
- **New `scripts/reset-to-stock.sh` (idempotent).**
  - Remove only the Home on Fire entries from the accessibility list, preserving others. Write the empty case as `settings put secure enabled_accessibility_services ""`, because deleting the key behaves differently.
  - Force-stop our package and Home on Fire.
  - Start Amazon home with `am start -a android.intent.action.MAIN -c android.intent.category.HOME -p com.amazon.tv.launcher`.
  - Print the verification: `settings get secure enabled_accessibility_services`, and `cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN`.
- **Optional full removal.** `pm uninstall <our.package>` and `pm uninstall io.github.toolicious.homeonfire`. Keep the existing `undo.sh all` for the debloat layers.
- **Companion `restore-launcher.sh`** re-enables the redirect, so the user can switch freely.
- **Trade-off:** it needs the Mac and Wi-Fi ADB at <tv-ip>:5555, so it is the backup of last resort.

### Recommendation
- **Build all three, layered.**
  1. A visible "Return to Fire TV" button in the launcher settings, with confirmation (Option A).
  2. The crash-loop breaker (Option B).
  3. A new `scripts/reset-to-stock.sh` (Option C).
- **Keep Home on Fire's long-press escape ON.** It must never be disabled by us.
- **Never** disable or hide `com.amazon.tv.launcher`.
- **Test plan:** drive the real device through every path.
  - Press the reset button and confirm Home now lands on Amazon.
  - Trigger crashes deliberately.
  - Run the Mac script.
- **Acceptance:** verify with a screenshot about 10 s after Home.

## 5. Banners, icons, launching apps, memory

- **Banner spec.** Google's checklist specifies 320x180 px in `drawable-xhdpi`, with localized text identifying the app, set through `android:banner` on `<application>` or `<activity>` ([Android TV checklist](https://developer.android.com/training/tv/publishing/checklist)). Declare it on our launcher activity (the one with `LEANBACK_LAUNCHER`). dumbtv and tv-vibehack both do.
- **Fire OS banner handling** [UNVERIFIED]. I found no Amazon doc on it.
- **Package visibility on API 30.** Use the same `<queries>` block as tv-vibehack: an `<intent>` with `MAIN` and `LEANBACK_LAUNCHER`. Add a second `<intent>` for `LAUNCHER` if you want apps without TV filters. Avoid `QUERY_ALL_PACKAGES`.
- **Getting icons and banners.**
  - `ResolveInfo.activityInfo.loadBanner(pm)` falls back to the application banner, then `loadIcon(pm)`.
  - Rasterize each drawable once into a cached 480x270 bitmap, stored as WebP in `getCacheDir()` and in an `LruCache`.
  - Apps without a banner (Appstore, Downloader) start hidden in dumbtv. That is a sensible default.
- **Launching apps.**
  - Resolve with `getLeanbackLaunchIntentForPackage`, falling back to `getLaunchIntentForPackage`.
  - In shell tests use `cmd package resolve-activity` plus `am start -n`. `monkey` doesn't work here.
  - Optionally compile for speed after install, as tv-vibehack does: `cmd package compile -m speed -f <pkg>`.
- **Detecting the running app.**
  - There is no cheap, permission-free method on API 30.
  - Our launcher's own `onStop`/`onResume` tells us when we leave and return.
  - To show a "currently open" marker, use `UsageStatsManager.queryEvents` with `appops set <pkg> GET_USAGE_STATS allow` [UNVERIFIED on Fire OS].
  - Do not request notification access or TV-listings permissions.
- **Memory and bitmaps.**
  - `LruCache` sized at no more than 1/8 of `ActivityManager.getMemoryClass()`.
  - Decode with `inSampleSize`, and use `RGB_565` for opaque backdrops.
  - **Pre-blur** each backdrop once at roughly 1/8 scale (about 240x135) and scale it up. No `RenderEffect` on API 30.
  - Keep at most 2 backdrops decoded (the current and the incoming one for cross-fades).
  - Use only transform animations (`ViewPropertyAnimator` or `DynamicAnimation` springs), plain Views and no Compose. Avoid shadows and `RenderScript` per frame.
  - Free everything in `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` and rebuild on resume.
- **minSdk / targetSdk.**
  - Amazon recommends minSdk 30 for Fire OS 8 and targetSdk as the highest version you tested.
  - **Our pick:** minSdk 28, targetSdk 30, compiled against `android-30`. minSdk 28 keeps the launcher usable on Fire OS 7 too. A targetSdk above the OS version is allowed, but compiling against the 30 platform jar turns accidental newer-API use into compile errors.
  - dumbtv uses 28/28 and tv-vibehack uses 26/34 for comparison.
  - **Compile jar.** `android-34` is installed, so `android-30` needs `sdkmanager "platforms;android-30"` (about 60 MB). Or compile against 34 and guard with `Build.VERSION.SDK_INT`.

## 6. Leanest macOS toolchain (no Android Studio)

**Measured state:** SDK, build-tools 34.0.0, platform android-34 and `adb` 37.0.0 are installed. `openjdk@21` is installed but keg-only. System `gradle` is 9.6.1, which does not match tv-vibehack's AGP 8.5.2 (wrapper pins Gradle 8.9). Debug key: `~/.android/debug.keystore`.

**Recommended path: dumbtv-style `javac` + `aapt2` + `d8`.** It needs no Gradle and no AGP download, and it builds in seconds. It needs plain Java, no AndroidX and no Kotlin. That fits our plain-View constraint.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
BT=$ANDROID_HOME/build-tools/34.0.0
JAR=$ANDROID_HOME/platforms/android-34/android.jar     # or android-30 after sdkmanager

# optional, once: yes | sdkmanager --licenses ; sdkmanager "platforms;android-30"

# one-time release key (keep it, updates must use the same key; git-ignore it)
keytool -genkeypair -keystore release.keystore -alias home -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=tv home"

mkdir -p build/gen build/classes
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml build/res.zip \
  --min-sdk-version 28 --target-sdk-version 30 \
  --version-code 1 --version-name 1.0 --java build/gen -o build/app.apk
javac --release 8 -encoding UTF-8 -classpath $JAR -d build/classes $(find src build/gen -name '*.java')
$BT/d8 --release --min-api 28 --lib $JAR --output build $(find build/classes -name '*.class')
(cd build && zip -q -X app.apk classes.dex)
$BT/zipalign -f -p 4 build/app.apk build/aligned.apk
$BT/apksigner sign --ks release.keystore --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/launcher.apk build/aligned.apk
$BT/apksigner verify -v build/launcher.apk
$BT/aapt2 dump permissions build/launcher.apk
```

- **Reuse.** `dumbtv/launcher/build.sh` already does exactly this. Copy its structure into our repo; it is MIT. It treats warnings as errors (`-Werror`).
- **Debug signing.** For a debug build, replace the key with `~/.android/debug.keystore` using `--ks-pass pass:android`, alias `androiddebugkey`.
- **Key warning.** A build signed with one key cannot update an install signed with another. Uninstall first when switching keys, and back up settings.
- **dumbtv's apksigner/aapt2 flags are tested only on SDK 28.** Ours add `--target-sdk-version 30`; it may need `--rename-manifest-package` or `--auto-add-overlay` if resources conflict [UNVERIFIED].

**Fallback if we need AndroidX or Kotlin: the Gradle wrapper (tv-vibehack's approach).**

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
./gradlew assembleDebug        # first run downloads Gradle 8.9 and AGP 8.5.2
```

- Do not use the system Gradle 9.6.1 with AGP 8.5.2.
- Cost: several hundred MB downloaded, slow first run. Not recommended unless AndroidX is unavoidable.

**Installing on the Stick.** `/opt/homebrew/bin/adb` exists, but the approved key is held by the tv-vibehack client. Either use `adb connect <tv-ip>:5555` (accept a new prompt if shown), or use `GitHub/Pulled/tv-vibehack/tools/b64push.py` and `pm install -r` as tv-vibehack's `build-and-install.sh` does.
- One-time grant after the first install: `pm grant <our.package> android.permission.WRITE_SECURE_SETTINGS`. This needs user approval.

## 7. Facts to measure on the device later (read-only)

- `wm size; wm density; getprop dalvik.vm.heapgrowthlimit`
- `dumpsys meminfo` with Home showing, to measure the real PSS budget
- Whether the `WRITE_SECURE_SETTINGS` grant survives `install -r`
- Whether our declared `CATEGORY_HOME` filter affects Home on Fire's launch of our activity, or causes any chooser on Fire OS 8

Files read: `detox/README.md`, `detox/scripts/undo.sh`, `detox/scripts/tv.sh`, `dumbtv/launcher/{build.sh,AndroidManifest.xml,README.md,install.sh,src/tv/dumbtv/home/Guard.java}`, `tv-vibehack/launcher/{build.gradle,build-and-install.sh,app/src/main/AndroidManifest.xml}`.

Sources: [Home on Fire](https://github.com/toolicious/home-on-fire), [HijackService.java](https://github.com/toolicious/home-on-fire/blob/main/src/io/github/toolicious/homeonfire/HijackService.java), [AccessibilityHelper.java](https://github.com/toolicious/home-on-fire/blob/main/src/io/github/toolicious/homeonfire/AccessibilityHelper.java), [Amazon device specs](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html), [Amazon Fire OS 8 notes](https://developer.amazon.com/docs/fire-tv/fire-os-8.html), [Android TV checklist](https://developer.android.com/training/tv/publishing/checklist), [AFTVnews sideloading](https://www.aftvnews.com/sideloading-and-downloader-app-work-fine-on-new-2023-2nd-gen-fire-tv-stick-4k-4k-max/), [CNX Software](https://www.cnx-software.com/2023/09/22/amazon-fire-tv-stick-4k-max-2023-features-a-2-ghz-mediatek-mt8696t-cpu-support-wifi-6e/).
