# Testing summary

Scrubbed summary of how Orchard was tested during its 2026-10-19/20 build, on one Fire TV Stick 4K Plus
(Fire OS 8.1.8.5, Android 11 / API 30, 2 GB RAM). The raw working log (per-round notes, screenshots, device
identifiers) is not published. Numbers below are from that log.

## Host tests

`app/build.sh` compiles the pure-Java classes with `-Werror` and runs every `app/test/*Test.java` (plain `main()`
asserts) before building. 23 test classes, all PASS on the final build. The build then produces a signed
`orchard.apk` (1,465,712 bytes on the final build; budget 1.6 MB) and fails unless it declares exactly two permissions (`WRITE_SECURE_SETTINGS` and `INTERNET`, the latter for the optional weather request). `tests/launcher_cmd_test.sh` (24 checks on the `city` and `screensaver` commands) and `tests/detox_guard_test.sh` also pass.

Mutation checks were run on the first three test classes (redirect-list handling, crash window, config parsing):
each deliberate bug in the code under test had to be caught. Two mutants survived the first round (crash-file
length cap, comment-line parsing); a test was added for each and the re-run caught them. The tests also found one
real bug (a junk line in the crash file left a phantom entry).

## Sky, weather, city and clock (Revs 4 to 6)

New pure-Java classes each have a host test: `Solar` (checked against fixed astral values for Bengaluru on 2026-10-20: sunrise 06:10:44, sunset 17:57:56, noon elevation 66.67 degrees), `SkyPhase`, `ThemePhase`, a one-day sky table test, `Greeting`, `RainText`, `ClockFormat` (including worst-case ink margins at the largest drift, never under the 96 px overscan), `CityCommand`, and `WeatherParse` against a recorded Open-Meteo JSON fixture (including WMO codes 94, 95 and 96 at the rain boundary). A scratch mutation of that boundary made the test fail; the unmutated control passed. The Mac-side contrast gate (`tools/check_contrast.py`) checks every sky and weather state.

## Overnight verification on the Stick (2026-10-20)

Run in two rounds on the same Stick, after checking that no real playback was active. All PASS unless noted.

| Check | Result |
|---|---|
| Install | `launcher.sh install` succeeded; both permissions granted |
| Home | Fire TV Settings, then Home, opened Orchard 5/5 with real logos, live night sky and the weather chip (Open-Meteo fetch ok) |
| Pinned times | `debug_time` 06:00, 12:00, 18:10, 22:00 clear, 10:00 rain, 07:00 fog: header clock, greeting, caption and theme all follow the pinned time; no restart on `unset` |
| City | `city Pune`, then `city Bengaluru`, applied live in the same process; temperatures matched the host's Open-Meteo reading |
| Reset | Return to Fire TV opens Amazon's home and Home stays there; "Turn redirect on" restores the saved list and Home opens Orchard again |
| Clock screensaver | Started by the system idle path and by the dream route; one process, Back returns to Home, no crash; the clock drifted between minutes |
| Cold start | 733-788 ms through the Home on Fire relaunch path (under 1 s) |
| Idle | 2 frames in 30 s |
| Rain | 299-300 frames per 10 s, 0-0.3 % janky after settling |
| Memory | 84.7 MB PSS at the end of the session |
| End state | Redirect entry present, accessibility on, grant present, crash guard armed, Home opens Orchard |

The README sky, rain, tour, Quick Panel, Settings and clock images are real captures from this run, cropped, scaled and encoded only. The night Settings capture shows the real clock in its header, because Settings ignores `debug_time`.

Open items: dock jank of 6-11 % at 18:10 under a spaced 10-key loop (0 % by the standard method; suspected per-frame shadow layer on stroked icons); foreground memory can pass 120 MB under repeated pinned-time switching, and any app round trip releases it to about 81-85 MB; Quick Panel overlaps the "Fire TV home active" pill when the redirect is off. ClockDream is the active Fire TV screensaver on the test Stick (set during overnight verification; `scripts/launcher.sh screensaver off` reverts).

## Phases

| Phase | Host | On the TV |
|---|---|---|
| 1 Foundation | passed | passed |
| 2 Install and reset tooling | passed | passed (R0-R8, R6b, R10 reboot); R9 needs the physical remote |
| 3 Home screen core | passed | passed |
| 4 Top Shelf and motion | passed | passed |
| 5 Quick Panel, Settings, menu, screensaver | passed | passed; ClockDream verified as the system screensaver (idle path + Somnambulator) |
| 6 Switch-over | passed | passed (redirect target set to Orchard, crash failsafe, reboot) |

## Performance (final build)

| Measure | Result |
|---|---|
| Cold start | 508-782 ms (target under 1000) |
| Hot start | 72-125 ms (target under 300) |
| Janky frames, 26-key burst | 0.00 %, 50th percentile 8 ms, 99th 12-13 ms |
| Idle redraws | none (frame count unchanged over 10 s) |
| Memory on screen | 64-92 MB PSS (limit 110) |
| Memory in background, 30 s after Home | 53-60 MB in a normal session (target 45): open issue |

The largest single win: baking the Top Shelf gradients into small bitmaps once instead of drawing full-width shaders.
That took dock-row movement from 97.9 % janky frames to under 4 %.

## Reset matrix

All run with the Stick on Wi-Fi ADB and a snapshot taken first.

| Case | What it checks | Result |
|---|---|---|
| R1 | snapshot + status | PASS |
| R2 x5 | in-app "Return to Fire TV" opens Amazon's home every time | PASS 5/5 |
| R3 x5 | Home press afterwards stays on Amazon's home | PASS |
| R4 x5 | "Turn Home redirect back on" restores the saved list | PASS |
| R5 x5 | Home press goes to the launcher again | PASS |
| R6 / R6b / R6c / R7 | crash-loop failsafe: three crashing Home launches strip the redirect and open Amazon's home; safe mode screen | PASS (after a debug-channel fix) |
| R8 | `reset-to-stock` and `restore-home`, each run twice | PASS (idempotent) |
| R9 | long-press Home opens Amazon's home | needs the physical remote |
| R10 | reboot: Home still opens Orchard | PASS |
| R11 | short Menu opens Orchard's panel only; long Menu opens the redirect app's screen only | needs the physical remote |

## Known issues at release

1. Background memory is above the 45 MB target (see above). The system never sends the trim signal within a minute
   of leaving the app on this Stick; trimming by hand proved the release path works.
2. A Select on a tile with no launch intent (for example Amazon Settings) does nothing, silently.
3. After an adb reboot, Fire OS can show an "Unable to Detect Your Remote" dialog on top of any launcher. Dismiss it
   with `input keyevent 23`.
4. Hold-key scrolling (hold RIGHT or DOWN for 3 s) can only be checked with a physical remote.
