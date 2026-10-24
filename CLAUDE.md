# orchard-tv

The whole Fire TV kit: a detox (ad blocking, tracking off, bloatware disabled, ad-blocking DNS; `scripts/detox.sh`) plus
Orchard (`tv.orchard.home`), an Apple TV-style home-screen launcher for a Fire TV Stick (Fire OS 8, API 30), and the Mac
tools to build it, install it and reset the Stick to Amazon's home. Public, MIT. Plain Java, no Gradle, no libraries. The APK is about 1.4 MB (budget 1.6 MB).

## Layout

- `app/` Android app. `src/tv/orchard/home/`, `res/`, `test/` (host tests), `build.sh`, `version.env`.
- `tools/` `env.sh` (finds JDK 17+ and the SDK), `make_wallpapers.py` (Pillow/numpy), `make_sky.py` (the Rev 6 sky keyframes, glow and rain tiles into `mockup/wall/`), `check_contrast.py` (WCAG gate incl. the sky; ~25 min, `--static` skips the render sweeps), `check_sky_handover.py` (a live minute step never flashes the sky; ~2 min), `export_sky_assets.py` (mockup sky frames into `app/res`), `subset_fonts.sh` (fonttools; Inter Thin included),
  `adb/` (`tv.py`, `b64push.py`, `requirements.txt`; adapted from tv-vibehack, MIT).
- `scripts/` `detox.sh` (snapshot, privacy, screensaver, background, dns, debloat, updates, status, undo), `launcher.sh`
  (build, install, status, reset, `city`, `screensaver on|off`), `tv.sh` (one adb shell command).
- `config/` `debloat.txt` (packages to disable), `never-disable.txt` (refused on every disable path).
- `tests/` `detox_guard_test.sh` (host-only: guard refusals, list intersection, fake-TV end to end), `launcher_cmd_test.sh` (dry-run checks of `city` and `screensaver`, `status` against a fake TV).
- `mockup/` HTML preview of the design. `real-art.js` must stay `false`; `banners-real/` is git-ignored and never committed.
- `docs/` DETOX.md (the detox guide), PLAN.md (historical spec), TESTING.md (scrubbed results), learnings.md, research/.

## Build, test, install

```sh
source tools/env.sh && app/build.sh                      # host tests + signed APK (~3 s)
python3 -m venv .venv && .venv/bin/pip install -r tools/adb/requirements.txt   # once
echo 'TV_HOST=<tv-ip>' > .env                            # git-ignored
scripts/launcher.sh snapshot && scripts/launcher.sh install && scripts/launcher.sh grant
scripts/launcher.sh status                               # read-only
```

After `install` the Stick shows the Amazon home; `install` ends by starting Orchard once, because Home on Fire redirects only when that home comes to the front (Home pressed *from* it stays put).

`scripts/tv.sh` needs `TV_HOST` (env or `.env`); it has no default. The adb key is `ORCHARD_ADB_KEY` (default
`~/.android/adbkey`). Use `tv.sh`, never a bare `adb connect` (a second key means a second "Allow" prompt).

## Sky, weather and city (Revs 4 to 6; spec in docs/PLAN.md sections 7 to 9)

- Pure Java, host-tested (marked `PURE JAVA`, no `android.*`): `Solar` (NOAA rise, set, elevation), `SkyPhase`, `ThemePhase`, `Greeting`, `RainText`, `ClockFormat`, `CityCommand`, `WeatherParse`, `Cities`. Android side: `SkyView`, `SkyAssets`, `WallStack`, `RainLayer`, `Halo`, `Theme`, `Ambient`, `AmbientState`, `WeatherClient`, `WxGlyph`, `ClockDream`.
- Data: `res/raw/cities.csv` (one city table, Bengaluru first and default), 20 sky webps plus sun, moon and rain tiles in `res/drawable-nodpi`, `res/font/inter_thin.ttf`, `app/test/fixtures/open-meteo-bengaluru.json`.
- Numbers come from `mockup/tokens.css` into `Ui.java` / `Theme.java`; the mockup is the visual contract. Retune a token in both places, then run `tools/check_contrast.py`.
- Debug flags (same intent and broadcast channel as the others): `debug_time=HH:MM` pins every time-derived element (header clock, greeting, sky, theme, sunrise/sunset caption, chip glyph, screensaver clock; the date stays real), `debug_wx=clear|cloudy|rain|fog` overrides the weather code (temperature stays real), `debug_offline=1` fakes a failed fetch (chip hides). Clear them with `orchard.debug_clear`, with a fresh nonce, before the last screenshot.
- City: `scripts/launcher.sh city <name>|<lat> <lon> [label]` is a DUMP-guarded `SET_CITY` broadcast to `DebugReceiver`; `CityCommand` validates it (label stripped to letters, digits, space, `.`, `-`, `'`). `screensaver on` needs the typed word `SCREENSAVER`; `off` restores the snapshot, else Android Colors.
- Private DNS repair: `DnsGuardPolicy` (PURE JAVA, host-tested: every decision, the phase table, resume) and `DnsGuard` (android driver: settings, probe, State, one `HandlerThread`). Hooks: `OrchardApp.onCreate` (resume), `HomeActivity` first frame / ambient minute / onStop / chip, Settings rows, `DebugReceiver` action `DNS_GUARD` (`--es cmd check|run|status|reset`, DUMP-guarded). `build.sh` generates `BuildStamp` (the build time, lowest plausible clock). Config key `dns_guard`; test keys `debug_dns_fast|restore_host|force` are shell-channel only.
- Idle rule: no redraws except the minute tick and the slow rain layer (only while raining). Alpha and translate only, pre-rendered bitmaps, no `RenderEffect`, no animated colour.

## Network rules

1. Orchard declares exactly two permissions, `WRITE_SECURE_SETTINGS` and `INTERNET`; `build.sh` fails on anything else. Do not add a third.
2. The only outbound call is `WeatherClient` to Open-Meteo `api.open-meteo.com` over HTTPS: coordinates rounded to 2 decimals, no key, no identifiers, no other host, no analytics. INTERNET is also used, only while the clock looks stale after a power-off, a Private DNS repair is running, or the user selects Settings > Private DNS status, for DNS-only lookups of `<random>-dnsotls-ds.metric.gstatic.com`, the name Android's own NetworkMonitor resolves on every network check; nothing is ever sent to that host.
3. Run it on a background thread with timeouts; never block the UI. Refetch at most every 30 minutes, cache in `getFilesDir()`, and on any failure hide the chip.
4. Settings > Weather off must stop all requests. Keep the Open-Meteo attribution (CC BY 4.0) in Settings and `THIRD_PARTY_NOTICES.txt`.
5. The README privacy wording is "one optional weather call" plus the DNS-only health lookups sentence; keep it true if the network use ever changes.

## Detox safety rules

1. `--dry-run` first: `scripts/detox.sh all --dry-run` prints `would run: ...` and changes nothing. Reads still run.
2. `config/never-disable.txt` is checked in `disable_pkg`, the only code path that runs `pm disable-user`. Never add a bypass; extend the list instead. `tests/detox_guard_test.sh` must pass (mutation-check any change to the guard).
3. `snapshot` before the first write; `all` keeps an existing `backup/detox-pre.txt` (never overwrites it). `undo` restores from it, else from documented stock defaults.
4. Typed gates: `debloat` DEBLOAT, `updates` UPDATES, `all` DETOX. `updates` is never part of `all` (risky both ways).
5. Keep `config/debloat.txt` conservative (bootloop risk on Fire OS 8.1); add one package at a time and restart between.
6. Disable, never uninstall, Amazon packages. `factory-stock` calls `detox.sh undo all`.
7. The kit's own packages (`tv.orchard.home`, Home on Fire) are on the never-disable list.

## Reset safety rules

1. Every TV change is preceded by `launcher.sh snapshot`; `restore-*` read only from `backup/orchard-pre.txt`. Never hard-code "restore to 1".
2. `factory-stock` (type `STOCK`) = reset-to-stock + restore-settings + `detox.sh undo all` + uninstall Orchard. `uninstall` (type `UNINSTALL`) = Orchard only. Both refuse without the typed word. Use `--dry-run` first.
3. Never auto-uninstall on a failed check; failure handling only switches the redirect off.
4. Never interfere with long-press Home (the escape to Amazon's home) or Home on Fire's long-press Menu.
5. Claude Code blocks `pm grant` and `reboot` from workers: treat them as user gates.

## Gotchas (full list in docs/learnings.md)

- `monkey` does not launch apps here; use `cmd package resolve-activity --brief` then `am start -n`.
- adb cannot read `/sdcard/Android/data/<pkg>` on SDK 30; debug flags go in via `am start --es orchard.debug "k=v" --es orchard.debug_nonce "$(date +%s)"` (cold start needs a fresh nonce: `am start` extras are replayed as the task root intent after process death, and without the gate `debug_art=placeholder` came back as letter plates) or live via `am broadcast -n tv.orchard.home/.DebugReceiver --es set/unset ...` (no nonce, applies at once, never replayed).
- No `RenderEffect` on API 30: blur a small bitmap once. Full-width shaders are GPU-bound on the Stick; bake gradients to bitmaps.
- Home on Fire is GPLv3: integrate through its public service id, never copy its code.
- Keep tile art 325x195 RGB_565; stop idle animations after 20 s.
- `settings put global ntp_server` does nothing on Fire OS 8 (Amazon's TimeService replaces Android's); a stale clock under strict Private DNS is repaired by `DnsGuard` (manual steps in docs/DETOX.md).
- The Appstore opens only through the component override in `LaunchOverrides` (`com.amazon.venezia/.ade.ADEHomeActivity`); its LAUNCHER activity bounces to Amazon's home. An explicit hide still beats `HiddenRules.SYSTEM_ALLOW`.
- macOS `/bin/bash` is 3.2: write `case` patterns with a leading `(` inside `$( ... )`, or avoid `case` there.
- DnsGuard: never probe a cacheable name (random label under the Android probe suffix only); never write `off` as the last step of a run; persist state before every settings write; re-read both settings and abort on any change it did not make.
- The signing key (`app/release.keystore`, `app/.keypass`) is git-ignored and irreplaceable for updates; never commit it.

## Public-repo rules

No private IPs, absolute home paths, emails, adb keys, device snapshots (`backup/`), raw device logs, or real app logos outside `docs/media/screenshot-real-*.{jpg,gif}` (the user approved real-app screenshots for the README on 2026-10-20; `mockup/banners-real/` stays git-ignored).
Before every commit, grep the tree (not git-ignored dirs only) for private LAN IP addresses, home-directory paths, and email addresses.
Use `/usr/bin/grep -rnI`; plain `grep` here skips git-ignored dirs.

## House docs

Repo conventions (README, About, topics, hero image): `../../docs/README.md`, `readme-guide.md`,
`about-image-language.md`, `image-processing.md`. Every repo keeps a git-ignored `docs/media/ABOUT-IMAGE.md`.
