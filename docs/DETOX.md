# DETOX: remove Fire TV's ads, tracking and bloatware

`scripts/detox.sh` cleans a Fire TV Stick over Wi-Fi ADB, one layer at a time. Nothing is rooted, flashed or deleted: Amazon's apps are *disabled* (`pm disable-user`), settings are changed through the same switches the TV's menus use, and every layer has an `undo`. Tested on a Fire TV Stick 4K Plus, Fire OS 8 (Android 11). Fire OS 7 should behave the same; packages that are absent are skipped.

## Quick start (about 10 minutes)

1. **Turn on ADB debugging** (steps below).
2. **Set up the client once:** `python3 -m venv .venv && .venv/bin/pip install -r tools/adb/requirements.txt`, then `echo 'TV_HOST=<tv-ip>' > .env` (the file is git-ignored).
3. **Approve the key:** `scripts/tv.sh --probe`, then choose Allow (always) on the TV.
4. **Look first:** `scripts/detox.sh status` (read-only).
5. **Rehearse:** `scripts/detox.sh all --dry-run` prints every command as `would run: ...` and changes nothing.
6. **Run it:** `scripts/detox.sh all`. It takes a snapshot, then asks you to type `DETOX`.
7. **Check:** `scripts/detox.sh status`, press Home, open each of your apps once.

### Enable ADB debugging

1. Settings > My Fire TV > About. Select the device name seven times until "You are now a developer" appears.
2. Back out one level. Developer options is now listed. Open it and turn **ADB debugging** on. (Some firmware also needs "Apps from Unknown Sources" for sideloading, not for detox.)
3. Settings > Network: note the Stick's IP address and use it as `TV_HOST`.
4. The first connection shows "Allow USB debugging?" on the TV. Tick "Always allow" and select OK.

## The layers

| Layer | What it does | Why | Undo |
|---|---|---|---|
| `snapshot` | Saves every setting and package state the script touches to `backup/detox-pre.txt` | So `undo` restores your real values, not guesses | n/a (read-only on the TV) |
| `privacy` | `limit_ad_tracking`, `amz_limit_ad_tracking`, `non_amz_limit_ad_tracking` = 1; interest-based ads, app usage data, device usage data, usage-metrics upload/marketing = 0 | Same switches as Settings > Preferences > Privacy, set explicitly so none stays on | `undo privacy` |
| `screensaver` | Disables `com.amazon.ftv.screensaver`, sets Android's plain `com.android.dreams.basic/.Colors` | Amazon's screensaver can show Prime Video ads | `undo screensaver` |
| `background` | `appops set <pkg> RUN_IN_BACKGROUND ignore` and `RUN_ANY_IN_BACKGROUND ignore` for the telemetry and ads packages Fire OS refuses to disable | They stay installed but cannot run while idle | `undo background` |
| `dns` | `private_dns_mode=hostname`, `private_dns_specifier=$DNS_HOST` (default `dns.adguard-dns.com`) | Ad and tracker domains resolve to nothing for every app on the Stick | `undo dns` |
| `debloat` | Disables every installed package in [`config/debloat.txt`](../config/debloat.txt) | Telemetry, ACR (viewing-habit recognition), advertising ID, shopping and ads, preloaded apps, tutorials, Wi-Fi password cloud upload | `undo debloat` |
| `updates` | Disables the firmware-update packages and restricts their background running | **Optional, risky both ways** (see below) | `undo updates` |

`all` runs: snapshot (only if none exists), privacy, screensaver, background, dns, debloat. It does **not** run `updates`. `DNS_HOST=dns.nextdns.io scripts/detox.sh dns` picks another resolver.

Fire OS refuses to disable a few packages ("protected package"). `debloat` reports them and carries on; `background` restricts them instead.

### Clock and certificate (strict Private DNS can lock the Stick out)

A Fire TV Stick has no battery clock. After a power-off it restores the time it last saved, which is behind by however long the previous session ran. The resolver's TLS certificate has a "valid from" date. If the restored clock is earlier than that date (a resolver rotating its certificate can re-arm this), strict Private DNS (`private_dns_mode=hostname`) fails and the network never counts as validated.

- **Symptoms:** Amazon apps (Appstore, the Fire TV YouTube app) say there is no internet; Netflix still plays; Settings shows Wi-Fi connected; Settings may report "Private DNS is broken"; the date on screen is days behind.
- **Cause:** Fire OS only schedules a time sync after the network validates, and its time servers are DNS names. Validation needs a correct clock, and the clock needs validation.
- **Recovery** (about 2 minutes, from a Mac, with `scripts/tv.sh`):
  1. `scripts/tv.sh 'settings put global private_dns_mode opportunistic'`, then wait about 30 seconds for the clock to sync.
  2. `scripts/tv.sh 'settings put global private_dns_mode hostname'`.
  3. Reconnect Wi-Fi so the network re-validates: `scripts/tv.sh 'nohup sh -c "svc wifi disable; sleep 3; svc wifi enable" >/dev/null 2>&1 &'`. Flipping the mode back without the reconnect left it "broken" for over 10 minutes.
- **`settings put global ntp_server <ip>` does nothing on Fire OS 8.** Amazon's own time service replaces Android's, and it never reads that setting.
- **Decision: Orchard repairs it itself (`DnsGuard`).** About 20 seconds after Orchard's first frame at boot it checks two things that need no extra permission: the clock is behind the last wall time Orchard saved, and a lookup of a random never-cached name fails while Private DNS is `hostname` with the detox resolver. Two failed checks 30 seconds apart start the same recovery as above, automatically: `opportunistic` (wait for the network, then for the clock to jump forward; one `auto_time_sync_enabled` 0/1 nudge if it does not), `off` for 3 seconds, `hostname` again, then a lookup confirms strict mode works. The Stick uses the router's DNS for roughly a minute (ads are not blocked in that window). It only acts when the mode is `hostname` and the specifier is the detox default (`dns.adguard-dns.com`; a custom specifier is left alone); it re-reads both settings before every write and gives up if you changed them; at most 3 runs and 14 writes per boot; a run never ends in `off`. If strict mode does not come back after 2.5 minutes it falls back to `opportunistic`, retries once after 5 minutes, and otherwise stays `opportunistic` with a notice on Home until the next restart, when the next start of Orchard puts your `hostname` setting back. Settings > Repair Private DNS turns it off; Settings > Private DNS status shows the state and, when selected, runs a manual check (it respects the toggle, skips the stale-clock and cooldown gates, and needs the same two failed lookups as the automatic check, 30 seconds apart; it does nothing while a repair is running). While the repair is paused, selecting it first re-reads the settings: if you already put `hostname` back by hand it goes idle (`aborted_user`) and the Home notice clears; otherwise it runs one manual repair, still inside the per-boot run and write caps. The Home notice and the Settings row never say "paused" once the mode is `hostname` again. The detection checks are capped per boot (persisted, so a restart of Orchard does not reset them), and a forced debug check never teaches the guard a new specifier. A run that ends `nonet` (Wi-Fi was down) uses up that boot's detection checks; select Private DNS status once the network is back. A new boot is also detected from Android's `boot_count`. With the Home redirect off you are on Amazon's home and Orchard may not run at all, so nothing repairs in that case.
- **Without Orchard (manual recovery):** the three steps above. Do them soon after boot; a flip 10 minutes or more later needed the Wi-Fi reconnect because the resolver's validation thread had backed off (docs/learnings.md 2.10).
- **Alternative (not installed): AdGuard on the router, opportunistic on the Stick.** Run `DNS_MODE=opportunistic scripts/detox.sh dns` (or set the router's DNS to AdGuard and leave Private DNS opportunistic). The clock cannot lock you out, but you lose per-device strict DoT: if the router's resolver is unreachable the filter is skipped, the router must be configurable (and "forget network" on the Stick erases a static Wi-Fi DNS), and IPv6 router advertisements can bypass the filter.

## Safety rules

- **Never-disable list.** [`config/never-disable.txt`](../config/never-disable.txt) (launcher, platform, input, SystemUI, Settings, providers, shell, Bluetooth, plus Orchard and Home on Fire) is checked on every disable path. A package on it is refused even if you add it to `debloat.txt`. `scripts/detox.sh guard <pkg>` tests one name without touching the TV, and `tests/detox_guard_test.sh` proves the refusal.
- **Typed gates.** `debloat` asks for `DEBLOAT`, `updates` for `UPDATES`, `all` for `DETOX`. No answer, no change. `--dry-run` skips the gates and changes nothing.
- **Snapshot first.** `all` takes one automatically and never overwrites it, so re-running cannot replace your stock values with detoxed ones. Overwrite deliberately with `snapshot --force`.
- **Boot-loop warning.** Aggressive debloat can leave a Stick stuck on the boot logo; one Stick of this model on Fire OS 8.1 was reported to do so after disabling launcher-adjacent providers, sync and setup packages. The shipped list is deliberately conservative. Add packages one at a time, restart, and confirm the remote and Home still work before adding more.

## Undo

```sh
scripts/detox.sh undo all          # every layer, newest first
scripts/detox.sh undo debloat      # or one layer
scripts/launcher.sh factory-stock  # type STOCK: Orchard, Home redirect, and 'detox.sh undo all'
```

`undo` reads `backup/detox-pre.txt` when it exists and otherwise uses the documented stock defaults (Amazon's screensaver component, `limit_ad_tracking=1`, no Private DNS). Packages that were already disabled before detox stay disabled. Without a Mac: Settings > Applications > Manage Installed Applications re-enables a disabled app. Last resort: Settings > My Fire TV > Reset to Factory Defaults.

## The `updates` layer

Blocking firmware updates stops Fire OS from changing under a working Home redirect, but it also blocks security fixes, and an old build can lose app compatibility. Allowing updates risks a firmware that breaks the redirect or re-enables packages. Neither choice is free, so the layer is opt-in, gated, and left out of `all`. Two of its four packages are protected on Fire OS 8, which is why it also restricts their background running.

## What cannot be blocked

- **Prime Video and live sports/live TV:** ads are stitched into the video stream from the same servers as the content, so DNS blocking the ad blocks the show. Prime Video's only fix is Amazon's ad-free add-on.
- **YouTube:** ads come from the same domains as the video. A DNS filter cannot separate them; a third-party client would, and this kit does not install one.
- **Other streaming apps:** some use separate ad servers (DNS helps), some stitch ads (it does not). Test one app at a time; if an app breaks, `scripts/detox.sh undo dns`.
- **Vega OS sticks** (Fire TV Stick 4K Select and newer Stick HD models) are not Android. ADB access and every command here is unsupported.

## Credits

The ADB client, the idea of a per-device Fire OS package list and `pm disable-user` batching come from cleanyourtv (formerly tv-vibehack, MIT). The background-restriction idea comes from dumbtv (MIT). The never-disable list follows the do-not-touch lists in firestrip and community boot-loop reports. See the README acknowledgements and `THIRD_PARTY_NOTICES.txt`.
