<!--
GitHub metadata — authored here, applied by hand in repo Settings (NOT rendered on the page):
About:  Replace Fire TV's ad-filled home with an Apple TV-style launcher, plus one-command reset to stock — plain Android Views on Fire OS 8, installed over adb, redirected by Home on Fire.
Topics (ranked): fire-tv, firestick, android-tv, launcher, home-screen, tvos-style, apple-tv-style, fire-os, adb, sideload, ad-free, android, java, home-on-fire, fire-tv-stick, custom-launcher
Social preview: docs/media/hero.png at 1280x640 once the asset exists (render -> hero.png via Tools/image_compress.py --glass).
-->

# orchard-tv — Apple TV-style Home Screen for Fire TV

**Your apps. No ads. One way back.**  
*Orchard is a tiny Java launcher for the Fire TV Stick that shows only the apps you chose, with reset tools that return the Stick to Amazon's home from the app, the remote, or a Mac.*

![License: MIT](https://img.shields.io/badge/license-MIT-green)
![Java 8 / Android API 30](https://img.shields.io/badge/java%208-android%20api%2030-blue)
![Fire OS 8](https://img.shields.io/badge/fire%20os-8-orange)

<!-- <p align="center"><img src="docs/media/hero.png" alt="Orchard home screen on a TV" width="720"></p>
     Waiting for docs/media/hero.png (not rendered yet). Never ship a broken link. -->

Orchard (`tv.orchard.home`) has a Top Shelf that previews the focused app, a dock, an app grid, a Quick Panel and a clock screensaver. It asks for no network access and about 280 KB of space. It never reads Amazon's recommendations, so none are shown.

## The map

| I want to… | Tool | What it is |
|---|---|---|
| **Build** the launcher | [`app/`](app/) | Plain Java, no libraries; host tests run first, then a signed `orchard.apk` |
| **Install** and check it | [`scripts/launcher.sh`](scripts/launcher.sh) | Build, snapshot, install, grant, and a read-only `status` over Wi-Fi adb |
| **Get back** to Fire TV | [`scripts/launcher.sh`](scripts/launcher.sh) `reset-to-stock` | Takes the redirect out and opens Amazon's home; also in the app and on the remote |
| **Run** one command on the Stick | [`scripts/tv.sh`](scripts/tv.sh) | Sends a single adb shell command using the key the TV approved |
| **Preview** in a browser | [`mockup/`](mockup/) | Open `mockup/index.html`; placeholder art, same layout |
| **Read** the spec and gotchas | [`docs/PLAN.md`](docs/PLAN.md), [`docs/learnings.md`](docs/learnings.md) | Design spec, Fire OS 8 findings, test summary in [`docs/TESTING.md`](docs/TESTING.md) |

## Quick start

1. **Enable ADB** on the Stick: Settings > My Fire TV > Developer options > ADB debugging.
2. **Set up the adb client** once: `python3 -m venv .venv && .venv/bin/pip install -r tools/adb/requirements.txt`.
3. **Add the Stick's IP** to a git-ignored `.env` as `TV_HOST=<tv-ip>`, then run `scripts/tv.sh --probe` and approve the prompt on the TV.
4. **Build and install** (the grant is one permission, only for "Return to Fire TV"):

```bash
source tools/env.sh        # finds JDK 17+, build-tools 34.0.0, android-30
app/build.sh               # host tests, then app/build/orchard.apk
scripts/launcher.sh snapshot && scripts/launcher.sh install && scripts/launcher.sh grant
```

5. **Install Home on Fire** (separate app) and choose Orchard as its target.

Home now opens Orchard. That's it — everything below is detail.

---

## Demo

A real build, install and status run (device values removed):

```
$ app/build.sh
== host tests
PASS DebugFlagsTest
PASS ShelfTextTest
...
== aapt2
== javac
== d8 / sign
built app/build/orchard.apk  279669 bytes  1 uses-permission entries

$ scripts/launcher.sh install
== pm install -r
Success
== compile -m speed -f
Success
== deviceidle whitelist +

$ scripts/launcher.sh status
HoF redirect                   = ON (HoF entry present)
WRITE_SECURE_SETTINGS grant    = yes
pm path tv.orchard.home        = package:/data/app/.../base.apk
HOME resolves to               = com.amazon.tv.launcher/.ui.HomeActivity_vNext
HOME vs snapshot               = same
```

<!-- Screenshots: add a width-capped <table> of docs/media/screenshot-home.png, screenshot-grid.png,
     screenshot-panel.png, screenshot-reset.png, screenshot-settings.png once they exist. -->

---

## How it works

Fire OS ignores third-party Home apps, so Orchard cannot be chosen as the default launcher. [Home on Fire](https://github.com/toolicious/home-on-fire) (GPLv3, installed separately, never copied here) watches for Amazon's home opening through an accessibility service and redirects to Orchard. Orchard's only permission, `WRITE_SECURE_SETTINGS`, lets its own reset sheet switch that redirect off.

The reset tools work in layers, so one failure never strands you:

| Layer | Where | What it does |
|---|---|---|
| **A** | In the app | Settings > Return to Fire TV removes the redirect and opens Amazon's home |
| **B** | On the remote | Long-press Home always opens Amazon's home; Orchard never intercepts it |
| **C** | From a Mac | `scripts/launcher.sh reset-to-stock`, `restore-home`, `restore-settings`, `factory-stock` (type `STOCK`) |
| **D** | Crash failsafe | Three crashing Home launches strip the redirect and open a safe-mode screen |

`--dry-run` prints the TV commands without running them. Last resort is Amazon's Settings > My Fire TV > Reset to Factory Defaults.

Settings live in `orchard.conf` and are changed in Orchard's Settings screen. Android 11 blocks adb from that folder, so debug flags go through the launch intent:

```bash
scripts/tv.sh 'am start -n tv.orchard.home/.HomeActivity --es orchard.debug "debug_card=banner;debug_motion=reduced"'
scripts/tv.sh 'am start -n tv.orchard.home/.HomeActivity --ez orchard.debug_clear true'
```

---

## Requirements

```bash
# macOS
brew install openjdk@21 android-commandlinetools python
sdkmanager "platforms;android-30" "build-tools;34.0.0"
```

- **Stick:** Fire TV Stick 4K Plus on Fire OS 8 (Android 11). Tested on one device, Fire OS 8.1.8.5.
- **Mac:** JDK 17+, Python 3 for the adb client.
- **Home on Fire** installed on the Stick.

> **Gotcha: Fire OS ignores custom Home apps.** Installing Orchard alone changes nothing; Home on Fire plus the adb `WRITE_SECURE_SETTINGS` grant are required. Long-press Home always escapes to Amazon's home, by design.

---

## FAQ

**Why not just set a default launcher?**
Fire OS 8 has no such setting and ignores third-party HOME apps. The accessibility-service redirect is the only route that works on current firmware.

**How do I go back to Fire TV?**
Use Settings > Return to Fire TV in Orchard, long-press Home on the remote, or run `scripts/launcher.sh reset-to-stock`. `factory-stock` also restores the saved settings and removes Orchard.

**Is the UI 4K or 1080p?**
The Stick renders the UI at 1920x1080 and scales it to a 4K panel, so Orchard is laid out for 1080p.

**Will it work on Vega OS sticks?**
No. Newer Vega OS sticks are not Android, so none of this applies.

---

## License

MIT — see [LICENSE](LICENSE). Not affiliated with Apple or Amazon; Apple TV and Fire TV are trademarks of their owners. No real app logos are redistributed; the mockup uses generated placeholders.

## Acknowledgements

- [cleanyourtv (formerly tv-vibehack)](https://github.com/Gathin23/cleanyourtv) — MIT adb client and tooling that `tools/adb/` builds on
- [dumbtv](https://github.com/humblemuzzu/dumbtv) — MIT; the background-restriction idea and a small-launcher reference
- [Home on Fire](https://github.com/toolicious/home-on-fire) — the redirect that makes a custom Home possible; used as an integration only
- [Inter](https://rsms.me/inter/) — the typeface, subset for the UI

See [`THIRD_PARTY_NOTICES.txt`](THIRD_PARTY_NOTICES.txt) for licence texts.
