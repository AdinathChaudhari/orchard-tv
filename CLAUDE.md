# orchard-tv

Orchard (`tv.orchard.home`): an Apple TV-style home-screen launcher for a Fire TV Stick (Fire OS 8, API 30), plus the
Mac tools to build it, install it and reset the Stick to Amazon's home. Public, MIT. Plain Java, no Gradle, no libraries.
Scope is launcher + reset tools only; a debloat or DNS setup is not part of this repo.

## Layout

- `app/` Android app. `src/tv/orchard/home/`, `res/`, `test/` (host tests), `build.sh`, `version.env`.
- `tools/` `env.sh` (finds JDK 17+ and the SDK), `make_wallpapers.py` (Pillow/numpy), `subset_fonts.sh` (fonttools),
  `adb/` (`tv.py`, `b64push.py`, `requirements.txt`; adapted from tv-vibehack, MIT).
- `scripts/` `launcher.sh` (build, install, status, reset), `tv.sh` (one adb shell command).
- `mockup/` HTML preview of the design. `real-art.js` must stay `false`; `banners-real/` is git-ignored and never committed.
- `docs/` PLAN.md (historical spec), TESTING.md (scrubbed results), learnings.md, research/.

## Build, test, install

```sh
source tools/env.sh && app/build.sh                      # host tests + signed APK (~3 s)
python3 -m venv .venv && .venv/bin/pip install -r tools/adb/requirements.txt   # once
echo 'TV_HOST=<tv-ip>' > .env                            # git-ignored
scripts/launcher.sh snapshot && scripts/launcher.sh install && scripts/launcher.sh grant
scripts/launcher.sh status                               # read-only
```

`scripts/tv.sh` needs `TV_HOST` (env or `.env`); it has no default. The adb key is `ORCHARD_ADB_KEY` (default
`~/.android/adbkey`). Use `tv.sh`, never a bare `adb connect` (a second key means a second "Allow" prompt).

## Reset safety rules

1. Every TV change is preceded by `launcher.sh snapshot`; `restore-*` read only from `backup/orchard-pre.txt`. Never hard-code "restore to 1".
2. `factory-stock` (type `STOCK`) = reset-to-stock + restore-settings + uninstall Orchard. `uninstall` (type `UNINSTALL`) = Orchard only. Both refuse without the typed word. Use `--dry-run` first.
3. Never auto-uninstall on a failed check; failure handling only switches the redirect off.
4. Never interfere with long-press Home (the escape to Amazon's home) or Home on Fire's long-press Menu.
5. Claude Code blocks `pm grant` and `reboot` from workers: treat them as user gates.

## Gotchas (full list in docs/learnings.md)

- `monkey` does not launch apps here; use `cmd package resolve-activity --brief` then `am start -n`.
- adb cannot read `/sdcard/Android/data/<pkg>` on SDK 30; debug flags go in via `am start --es orchard.debug`.
- No `RenderEffect` on API 30: blur a small bitmap once. Full-width shaders are GPU-bound on the Stick; bake gradients to bitmaps.
- Home on Fire is GPLv3: integrate through its public service id, never copy its code.
- Keep tile art 325x195 RGB_565; stop idle animations after 20 s.
- The signing key (`app/release.keystore`, `app/.keypass`) is git-ignored and irreplaceable for updates; never commit it.

## Public-repo rules

No private IPs, absolute home paths, emails, adb keys, device snapshots (`backup/`), real app logos or raw device logs.
Before every commit, grep the tree (not git-ignored dirs only) for private LAN IP addresses, home-directory paths, and email addresses.
Use `/usr/bin/grep -rnI`; plain `grep` here skips git-ignored dirs.

## House docs

Repo conventions (README, About, topics, hero image): `../../docs/README.md`, `readme-guide.md`,
`about-image-language.md`, `image-processing.md`. Every repo keeps a git-ignored `docs/media/ABOUT-IMAGE.md`.
