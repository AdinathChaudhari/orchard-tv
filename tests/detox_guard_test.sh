#!/usr/bin/env bash
# Host-only test (no TV): the never-disable guard in scripts/detox.sh. Run: tests/detox_guard_test.sh
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=$ROOT/scripts/detox.sh
fail=0
ok()  { printf 'PASS %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; fail=1; }

# 1. guard refuses protected packages (exact and wildcard) and allows a telemetry package
for p in com.amazon.tv.launcher com.android.systemui com.android.providers.contacts android tv.orchard.home; do
  if "$D" guard "$p" >/dev/null; then bad "guard allowed $p"; else ok "guard refuses $p"; fi
done
"$D" guard com.amazon.tv.acr >/dev/null && ok "guard allows com.amazon.tv.acr" || bad "guard refused com.amazon.tv.acr"
if "$D" guard 'bad name;rm' >/dev/null; then bad "guard allowed a malformed name"; else ok "guard refuses malformed name"; fi

# 2. debloat.txt and never-disable.txt do not intersect (checked through the guard, so wildcards count)
hits=0
while read -r p; do
  if ! "$D" guard "$p" >/dev/null; then printf '  overlap: %s\n' "$p"; hits=$((hits + 1)); fi
done < <(sed -E 's/[[:space:]]*#.*$//' "$ROOT/config/debloat.txt" | awk 'NF { print $1 }')
[ "$hits" = 0 ] && ok "debloat.txt ∩ never-disable.txt is empty" || bad "$hits overlaps"

# 3. end to end with a fake TV: a protected package smuggled into the list is never disabled
T=$(mktemp -d); export DETOX_SNAP="$T/snap.txt"; trap 'rm -rf "$T"' EXIT
printf 'com.amazon.tv.launcher\ncom.amazon.tv.acr\ncom.android.providers.media\n' >"$T/list.txt"
cat >"$T/faketv.sh" <<'FAKE'
#!/bin/sh
echo "$1" >>"$FAKE_LOG"
settings() { if [ "$3" = limit_ad_tracking ]; then echo 0; elif [ "$3" = private_dns_mode ]; then echo off; else echo null; fi; }
appops() { echo "Unknown operation"; }
case "$1" in
  "pm list packages -d") ;;
  "pm list packages") printf 'package:com.amazon.tv.launcher\npackage:com.amazon.tv.acr\npackage:com.android.providers.media\n' ;;
  "pm disable-user"*) echo "Package ${1##* } new state: disabled-user" ;;
  *"settings get"*|*"appops get"*) eval "$1" ;;
esac
FAKE
chmod +x "$T/faketv.sh"
for mode in dry real; do
  : >"$T/log"
  env DEBLOAT_LIST="$T/list.txt" DETOX_TV="$T/faketv.sh" FAKE_LOG="$T/log" \
    ${DRY:+} bash -c '[ "$1" = dry ] && set -- --dry-run; if [ "${1:-}" = --dry-run ]; then "$0" debloat --dry-run; else echo DEBLOAT | "$0" debloat; fi' "$D" "$mode" "$mode" >"$T/out" 2>&1
  [ "$mode" = dry ] && ! grep -q 'would run: pm disable-user --user 0 com.amazon.tv.launcher' "$T/out" && grep -q 'REFUSED com.amazon.tv.launcher' "$T/out" \
    && grep -q 'would run: pm disable-user --user 0 com.amazon.tv.acr' "$T/out" && ok "dry-run refuses launcher, plans acr" || { [ "$mode" = dry ] && bad "dry-run guard ($(cat "$T/out"))"; }
  if [ "$mode" = real ]; then
    if grep -q 'pm disable-user --user 0 com.amazon.tv.launcher\|com.android.providers.media' "$T/log"; then bad "real run sent a protected disable"
    elif grep -q 'pm disable-user --user 0 com.amazon.tv.acr' "$T/log" && grep -q 'REFUSED com.amazon.tv.launcher' "$T/out"; then ok "real run: protected packages never reached the TV"
    else bad "real run unexpected ($(cat "$T/out"))"; fi
  fi
done

# 4. the typed gate: no confirmation word, nothing sent
: >"$T/log"
DEBLOAT_LIST="$T/list.txt" DETOX_TV="$T/faketv.sh" FAKE_LOG="$T/log" "$D" debloat </dev/null >/dev/null 2>&1
grep -q 'pm disable-user' "$T/log" && bad "debloat ran without the typed gate" || ok "debloat refuses without the typed gate"

# 5. every single layer snapshots before its first write (stock values, not detoxed ones)
for layer in privacy screensaver background dns debloat updates; do
  rm -f "$T/snap.txt"; : >"$T/log"
  echo "DEBLOAT" | DEBLOAT_LIST="$T/list.txt" DETOX_TV="$T/faketv.sh" FAKE_LOG="$T/log" "$D" "$layer" >/dev/null 2>&1
  [ "$layer" = updates ] && { rm -f "$T/snap.txt"; : >"$T/log"; echo UPDATES | DETOX_TV="$T/faketv.sh" FAKE_LOG="$T/log" "$D" updates >/dev/null 2>&1; }
  if [ -f "$T/snap.txt" ] && grep -q '^secure.limit_ad_tracking=0$' "$T/snap.txt"; then ok "$layer snapshots stock values"; else bad "$layer wrote without a snapshot"; fi
done

exit $fail
