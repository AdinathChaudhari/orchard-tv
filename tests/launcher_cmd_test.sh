#!/usr/bin/env bash
# Host-only test (no TV): launcher.sh city and screensaver against a fake TV. Run: tests/launcher_cmd_test.sh
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
L=$ROOT/scripts/launcher.sh
fail=0
ok()  { printf 'PASS %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; fail=1; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/state"
export FAKE_LOG=$T/log FAKE_STATE=$T/state LAUNCHER_TV=$T/faketv.sh LAUNCHER_SNAP=$T/snap.txt

# Fake TV: logs every command; settings are kept in files so verify-after-put works.
cat >"$T/faketv.sh" <<'FAKE'
#!/bin/sh
echo "$1" >>"$FAKE_LOG"
case "$1" in
  "settings get secure "*) f="$FAKE_STATE/${1##* }"; if [ -f "$f" ]; then cat "$f"; else echo null; fi ;;
  "settings put secure "*) set -- $1; echo "$5" >"$FAKE_STATE/$4" ;;
  "cmd package query-services"*) echo "${FAKE_SERVICES-tv.orchard.home/.ClockDream}" ;;
  "am broadcast"*) echo "Broadcast completed: result=0, data=\"${FAKE_DATA-1}\"" ;;
  "dumpsys deviceidle whitelist") echo ",tv.orchard.home," ;;
  "cmd package resolve-activity"*) echo x ;;
  "dumpsys package "*) printf '%s\n' "${FAKE_PERM-x}" ;;
  *) echo x ;;
esac
FAKE
chmod +x "$T/faketv.sh"

reset() { : >"$T/log"; rm -f "$T"/state/* "$T/snap.txt"; unset FAKE_SERVICES FAKE_DATA FAKE_PERM; }
run() { out=$("$@" 2>&1); rc=$?; }   # sets $out, $rc
logged() { grep -q -- "$1" "$T/log"; }
mksnap() { printf 'date=x\nsecure.screensaver_components=%s\nsecure.screensaver_enabled=%s\n' "$1" "$2" >"$T/snap.txt"; }

# ---- screensaver on
reset; mksnap a/.B 1
run bash -c 'echo nope | "$0" screensaver on' "$L"
if [ "$rc" != 0 ] && [ ! -s "$T/log" ]; then ok "on + 'nope': refused, TV never contacted"; else bad "on + nope (rc=$rc log=$(cat "$T/log"))"; fi

reset; mksnap a/.B 1
run bash -c 'echo SCREENSAVER | "$0" screensaver on' "$L"
if [ "$rc" = 0 ] && logged 'settings put secure screensaver_components tv.orchard.home/.ClockDream' && logged 'settings put secure screensaver_enabled 1'; then ok "on + SCREENSAVER: both puts, exit 0"; else bad "on + SCREENSAVER (rc=$rc $out)"; fi

reset; mksnap a/.B 1
run bash -c '"$0" screensaver on --dry-run </dev/null' "$L"
if [ "$rc" = 0 ] && [[ $out == *'would run: settings put secure screensaver_components'* ]] && ! logged 'settings put' && [[ $out != *'Type SCREENSAVER'* ]]; then ok "on --dry-run: would run, no put, no prompt"; else bad "on --dry-run (rc=$rc $out)"; fi

reset; mksnap a/.B 1; export FAKE_SERVICES="com.other/.Dream"
run bash -c 'echo SCREENSAVER | "$0" screensaver on' "$L"
if [ "$rc" != 0 ] && [[ $out == *'ClockDream not found'* ]] && ! logged 'settings put'; then ok "on without ClockDream: refused, no put"; else bad "on without ClockDream (rc=$rc $out)"; fi

# ---- screensaver off
reset; mksnap x.y/.Y 0
run "$L" screensaver off
if [ "$rc" = 0 ] && logged 'settings put secure screensaver_components x.y/.Y' && logged 'settings put secure screensaver_enabled 0'; then ok "off restores snapshot X/.Y and 0"; else bad "off snapshot (rc=$rc $out)"; fi

STOCK='settings put secure screensaver_components com.android.dreams.basic/com.android.dreams.basic.Colors'
reset; mksnap null null
run "$L" screensaver off
if [ "$rc" = 0 ] && logged "$STOCK" && logged 'settings put secure screensaver_enabled 1' && ! logged 'settings delete'; then ok "off with null snapshot: Colors and 1"; else bad "off null (rc=$rc $out)"; fi

reset
run "$L" screensaver off
if [ "$rc" = 0 ] && logged "$STOCK" && logged 'settings put secure screensaver_enabled 1'; then ok "off without snapshot: Colors and 1"; else bad "off no snapshot (rc=$rc $out)"; fi

reset; mksnap x.y/.Y 0
run "$L" screensaver off --dry-run
if [ "$rc" = 0 ] && [[ $out == *'would run: settings put secure screensaver_components x.y/.Y'* ]] && ! logged 'settings put'; then ok "off --dry-run: no put"; else bad "off --dry-run (rc=$rc $out)"; fi

reset
run "$L" screensaver bogus
if [ "$rc" = 2 ]; then ok "screensaver bogus: usage, exit 2"; else bad "screensaver bogus (rc=$rc)"; fi

# ---- city
reset
run "$L" city bengaluru
if [ "$rc" = 0 ] && logged '--es city_id bengaluru' && logged '-f 0x20 -n tv.orchard.home/.DebugReceiver -a tv.orchard.home.SET_CITY' && [[ $out == *'city set: bengaluru'* ]]; then ok "city bengaluru: exit 0, city_id bengaluru"; else bad "city bengaluru (rc=$rc $out)"; fi

reset; export FAKE_DATA=0
run "$L" city bengaluru
if [ "$rc" = 0 ] && [[ $out == *'city unchanged'* ]]; then ok "city data 0: unchanged, exit 0"; else bad "city unchanged (rc=$rc $out)"; fi

reset
run "$L" city New Delhi
if [ "$rc" = 0 ] && logged '--es city_id newdelhi'; then ok "city New Delhi: city_id newdelhi"; else bad "city New Delhi (rc=$rc $out)"; fi

reset
run "$L" city "a'b;rm"
if [ "$rc" = 0 ] && grep -q -- '--es city_id abrm$' "$T/log"; then ok "city a'b;rm: city_id abrm"; else bad "city injection (rc=$rc $out)"; fi

reset
run "$L" city 51.5072 -0.1276 'Lon;don'
if [ "$rc" = 0 ] && logged '--es city_lat 51.5072 --es city_lon -0.1276 --es city_name '"'London'"; then ok "city lat lon label: extras + name London"; else bad "city coords (rc=$rc $out | $(cat "$T/log"))"; fi

reset
run "$L" city 51.5 -0.12
if [ "$rc" = 0 ] && ! logged city_name && logged '--es city_lat 51.5'; then ok "city lat lon without label: no city_name"; else bad "city no label (rc=$rc $out)"; fi

reset
run "$L" city 91 0
if [ "$rc" != 0 ] && [ ! -s "$T/log" ]; then ok "city 91 0: rejected on the Mac, nothing sent"; else bad "city 91 0 (rc=$rc)"; fi

reset
run "$L" city 10 181
if [ "$rc" != 0 ] && [ ! -s "$T/log" ]; then ok "city 10 181: rejected on the Mac"; else bad "city 10 181 (rc=$rc)"; fi

reset; export FAKE_DATA=-1
run "$L" city atlantis
if [ "$rc" != 0 ] && [[ $out == *'unknown city'* ]]; then ok "city data -1: unknown city, nonzero"; else bad "city -1 (rc=$rc $out)"; fi

reset; export FAKE_DATA=""
run "$L" city bengaluru
if [ "$rc" != 0 ] && [[ $out == *'no answer from Orchard'* ]]; then ok "city no data: no answer, nonzero"; else bad "city no data (rc=$rc $out)"; fi

reset
run "$L" city bengaluru --dry-run
if [ "$rc" = 0 ] && [[ $out == *'would run: am broadcast'* ]] && [ ! -s "$T/log" ]; then ok "city --dry-run: nothing sent"; else bad "city --dry-run (rc=$rc $out)"; fi

reset
run "$L" city --dry-run
if [ "$rc" != 0 ] && [ ! -s "$T/log" ]; then ok "city (no name) --dry-run: dies, nothing sent"; else bad "city empty (rc=$rc)"; fi

# ---- status additions
reset
run "$L" status
if [[ $out == *'uses-permission'* ]] && [[ $out == *'screensaver_enabled'* ]] && [[ $out == *'orchard city'* ]]; then ok "status prints new lines"; else bad "status (rc=$rc $out)"; fi
if [[ $out == *'WRITE_SECURE_SETTINGS granted no, INTERNET requested no'* ]]; then ok "status: no permissions -> no, no"; else bad "status perms none ($out)"; fi
reset; export FAKE_PERM='android.permission.INTERNET: granted=true
android.permission.WRITE_SECURE_SETTINGS: granted=true'
run "$L" status
if [[ $out == *'WRITE_SECURE_SETTINGS granted yes, INTERNET requested yes'* ]] && [[ $out != *'syntax error'* ]]; then ok "status: permissions -> yes, yes"; else bad "status perms ($out)"; fi

# ---- syntax + sibling test
bash -n "$L" && ok "bash -n launcher.sh" || bad "bash -n launcher.sh"
if "$ROOT/tests/detox_guard_test.sh" >"$T/detox.out" 2>&1; then ok "detox_guard_test.sh still passes"; else bad "detox_guard_test.sh"; cat "$T/detox.out"; fi

exit $fail
