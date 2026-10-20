#!/usr/bin/env bash
# launcher.sh -- Orchard launcher: build, install and reset tooling (docs/PLAN.md 1.2, 2.3).
#
# Usage: scripts/launcher.sh <subcommand> [args]
#   build [--out-dir DIR]     run app/build.sh (host tests + signed APK)
#   test                      build, print only the host-test section
#   push [apk]                push the APK (tools/adb/b64push.py) to /data/local/tmp/orchard.apk
#   install [apk]             push, pm install -r, compile -m speed -f, deviceidle whitelist +
#   grant                     pm grant tv.orchard.home WRITE_SECURE_SETTINGS
#   snapshot [--force]        write backup/orchard-pre.txt once (read-only on the TV)
#   status                    read-only report: redirect, grant, focus, packages, HOME resolve, debug flags
#                             (prints 'flags unreadable' when adb cannot read orchard.conf, the case on SDK 30)
#   reset-to-stock            strip Home on Fire's entry, open Amazon's home
#   restore-home              put the snapshot's accessibility list back, press-Home equivalent
#   restore-settings          animator scale, screensaver, deviceidle entry back to the snapshot
#   factory-stock [--dry-run] typed gate "STOCK": reset-to-stock, restore-settings, detox.sh undo all, uninstall Orchard
#   uninstall                 typed gate "UNINSTALL": pm uninstall tv.orchard.home
#   shot [name]               screenshot into shots/<name>.png
#   key KEY [KEY ...]         one `input keyevent` call
#   perf [KEY ...]            gfxinfo reset, keys, gfxinfo + meminfo summary
#   city <name> | city <lat> <lon> [label]   set the weather / sunset city live (DebugReceiver)
#   screensaver on|off [--dry-run]   typed gate SCREENSAVER: Orchard Clock as Fire TV screensaver; off restores the snapshot (else Android Colors)
#
# Every TV command goes through scripts/tv.sh, which honours TV_HOST / TV_PORT.
# --dry-run (or DRY_RUN=1) prints the TV-changing commands of the reset subcommands instead of running
# them; reads (settings get, dumpsys, ...) still run because they never change the TV.
# Needs TV_HOST (env or .env at the repo root); see scripts/tv.sh. Debloat, ad-blocking and DNS live in scripts/detox.sh; factory-stock calls its 'undo all'.
set -eu

SCRIPTS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(dirname "$SCRIPTS_DIR")
TV=${LAUNCHER_TV:-$SCRIPTS_DIR/tv.sh}

PKG=tv.orchard.home
HOF=io.github.toolicious.homeonfire
HOF_ID1="$HOF/.HijackService"
HOF_ID2="$HOF/io.github.toolicious.homeonfire.HijackService"
CONF=/sdcard/Android/data/$PKG/files/orchard.conf
SNAP=${LAUNCHER_SNAP:-$ROOT/backup/orchard-pre.txt}
HOME_CMD='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

APP_DIR=$ROOT/app
APK=$APP_DIR/build/orchard.apk
SHOTS_DIR=$ROOT/shots
REMOTE_APK=/data/local/tmp/orchard.apk
B64PUSH=$ROOT/tools/adb/b64push.py
PY=${ORCHARD_PYTHON:-$ROOT/.venv/bin/python}

usage() { sed -n '4,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; }
die() { printf 'launcher.sh: %s\n' "$*" >&2; exit 1; }

# ---- shared TV helpers ---------------------------------------------------------------------------

# Raw read (never changes the TV). Output on stdout, CR removed.
tv_read() { local out; out=$("$TV" "$1") || die "cannot reach the TV (check TV_HOST and that the Stick is on): $1"; printf '%s\n' "$out" | tr -d '\r'; }
# First line of a read.
tv_get() { local g; g=$(tv_read "$1") || return 1; printf '%s\n' "$g" | sed -n 1p; }
# TV-changing command for the reset subcommands: run it, or print it in dry-run mode.
tv_write() {
  if [ "${DRY_RUN:-0}" = 1 ]; then printf 'would run: %s\n' "$1"; return 0; fi
  "$TV" "$1" >/dev/null || die "TV command failed (is the Stick reachable?): $1"
}
# Run on the TV, print output, fail unless it contains $1 (pm/cmd exit 0 even on failure).
tv_expect() {
  local want=$1 out; shift
  out=$("$TV" "$@") || { printf '%s\n' "$out"; die "TV command failed: $*"; }
  printf '%s\n' "$out"
  [[ $out == *"$want"* ]] || die "expected '$want' from: $*"
}
pause() { [ "${DRY_RUN:-0}" = 1 ] || sleep "$1"; }

# ---- accessibility list (same rules as AccessibilityList.java) -----------------------------------

# Split ':', trim, drop "", "null", both HoF ids and duplicates; other services keep their order.
strip_hof() {
  printf '%s\n' "$1" | tr ':' '\n' | awk -v a="$HOF_ID1" -v b="$HOF_ID2" '
    { gsub(/^[ \t\r]+|[ \t\r]+$/, "") }
    $0 == "" || $0 == "null" || $0 == a || $0 == b || seen[$0]++ { next }
    { out = out (out == "" ? "" : ":") $0 }
    END { print out }'
}
has_hof() {
  printf '%s\n' "$1" | tr ':' '\n' | awk -v a="$HOF_ID1" -v b="$HOF_ID2" \
    '{ gsub(/^[ \t\r]+|[ \t\r]+$/, "") } $0 == a || $0 == b { f = 1 } END { exit !f }'
}
append_hof() { local s; s=$(strip_hof "$1"); if [ -z "$s" ]; then printf '%s\n' "$HOF_ID1"; else printf '%s:%s\n' "$s" "$HOF_ID1"; fi; }
put_a11y() {  # never deletes the key; "" when empty
  if [ -z "$1" ]; then tv_write 'settings put secure enabled_accessibility_services ""'
  else tv_write "settings put secure enabled_accessibility_services $1"; fi
}

snap_get() { [ -f "$SNAP" ] || return 1; sed -n "s/^$1=//p" "$SNAP" | sed -n 1p; }
need_snap() { [ -f "$SNAP" ] || die "no snapshot at $SNAP. Run: launcher.sh snapshot"; }

# Typed confirmation. Refuses on EOF/empty stdin or any other text. Runs before any TV contact.
confirm() {  # $1 = word to type, $2 = what will happen
  local ans
  printf '%s\nType %s and press Return to continue: ' "$2" "$1" >&2
  if ! IFS= read -r ans; then printf '\nrefused: no confirmation on stdin. Nothing was changed.\n' >&2; return 1; fi
  if [ "$ans" != "$1" ]; then printf 'refused: confirmation word did not match. Nothing was changed.\n' >&2; return 1; fi
  return 0
}

# ---- build half (task 1.5b) ----------------------------------------------------------------------

cmd_build() { "$APP_DIR/build.sh" "$@"; }

cmd_test() {  # build.sh runs the host tests first; a full build is ~3 s
  ( set -o pipefail; "$APP_DIR/build.sh" "$@" | sed -n '/== host tests/,/^== /p' )
}

cmd_push() {
  local apk=${1:-$APK}
  [[ -f $apk ]] || die "no APK at $apk (run: launcher.sh build)"
  [[ $apk == /* ]] || apk=$PWD/$apk
  [[ -x $PY ]] || die "no Python at $PY (python3 -m venv .venv && .venv/bin/pip install -r tools/adb/requirements.txt)"
  "$PY" -I "$B64PUSH" "$apk" "$REMOTE_APK"
}

cmd_install() {
  cmd_push "$@"
  echo "== pm install -r"
  tv_expect Success "pm install -r $REMOTE_APK"
  echo "== compile -m speed -f"
  tv_expect Success "cmd package compile -m speed -f $PKG"
  echo "== deviceidle whitelist +"
  "$TV" "dumpsys deviceidle whitelist +$PKG"
  # A fresh install leaves the Stick on the Amazon home, and Home on Fire redirects only when that home comes to the
  # front: pressing Home from it stays put. Start Orchard once so the next Home press (from any app) lands here.
  echo "== am start HomeActivity"
  "$TV" "am start -n $PKG/.HomeActivity" >/dev/null || true
}

cmd_grant() {
  local cmd="pm grant $PKG android.permission.WRITE_SECURE_SETTINGS" out
  echo "+ $cmd"
  out=$("$TV" "$cmd") || die "TV command failed"
  [[ -z $out ]] || { printf '%s\n' "$out"; die "grant failed"; }
  echo "granted: WRITE_SECURE_SETTINGS -> $PKG"
}

cmd_shot() {
  local name=${1:-shot-$(date +%Y%m%d-%H%M%S)} dest
  name=${name%.png}
  [[ $name =~ ^[A-Za-z0-9._-]+$ ]] || die "shot name: letters, digits, . _ - only"
  mkdir -p "$SHOTS_DIR"
  dest=$SHOTS_DIR/$name.png
  "$TV" 'screencap -p /sdcard/orchard.png' >/dev/null || die "screencap failed"
  "$TV" --pull /sdcard/orchard.png "$dest" >/dev/null || die "pull failed"
  [[ -s $dest ]] || die "empty screenshot at $dest"
  echo "$dest"
}

keys_ok() { local k; for k in "$@"; do [[ $k =~ ^[A-Za-z0-9_]+$ ]] || die "bad keycode: $k"; done; }

cmd_key() {  # key DPAD_RIGHT DPAD_RIGHT ENTER -> one `input keyevent` process
  [[ $# -ge 1 ]] || die "usage: key KEYCODE [KEYCODE ...]   e.g. key DPAD_RIGHT DPAD_RIGHT ENTER"
  keys_ok "$@"
  "$TV" "input keyevent $*"
}

cmd_perf() {
  keys_ok "$@"
  "$TV" "dumpsys gfxinfo $PKG reset" >/dev/null
  if [[ $# -gt 0 ]]; then "$TV" "input keyevent $*" >/dev/null; fi
  sleep 1
  echo "== gfxinfo $PKG"
  "$TV" "dumpsys gfxinfo $PKG | grep -E 'Total frames|Janky|percentile|Number (Missed|High|Slow)'"
  echo "== meminfo $PKG"
  "$TV" "dumpsys meminfo $PKG | grep -E 'Java Heap|Native Heap|Graphics|TOTAL'"
}

# ---- reset half (task 1.5a) ----------------------------------------------------------------------

cmd_snapshot() {
  local force=0 tmp idle idle_orchard=0
  [ "${1:-}" = "--force" ] && force=1
  if [ -f "$SNAP" ] && [ "$force" != 1 ]; then
    printf 'snapshot exists: %s (refusing to overwrite; use: launcher.sh snapshot --force)\n' "$SNAP" >&2; return 1
  fi
  tmp=$(mktemp "${TMPDIR:-/tmp}/orchard-pre.XXXXXX") || die "mktemp failed"
  idle=$(tv_read 'dumpsys deviceidle whitelist') || { rm -f "$tmp"; exit 1; }
  case "$idle" in *",$PKG,"*) idle_orchard=1 ;; esac
  {
    printf '# orchard-pre snapshot. Read by restore-home / restore-settings / status. Do not hand-edit.\n'
    printf 'date=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
    printf 'secure.enabled_accessibility_services=%s\n' "$(tv_get 'settings get secure enabled_accessibility_services')"
    printf 'secure.accessibility_enabled=%s\n' "$(tv_get 'settings get secure accessibility_enabled')"
    printf 'secure.screensaver_components=%s\n' "$(tv_get 'settings get secure screensaver_components')"
    printf 'secure.screensaver_enabled=%s\n' "$(tv_get 'settings get secure screensaver_enabled')"
    printf 'global.animator_duration_scale=%s\n' "$(tv_get 'settings get global animator_duration_scale')"
    printf 'home_resolve=%s\n' "$(tv_read "$HOME_CMD" | tail -n 1)"
    printf 'pm_path.homeonfire=%s\n' "$(tv_get "pm path $HOF")"
    printf 'pm_path.orchard=%s\n' "$(tv_get "pm path $PKG")"
    printf 'deviceidle_orchard=%s\n' "$idle_orchard"
    printf '%s\n' "$idle" | sed 's/^/# deviceidle: /'
  } >"$tmp.body" 2>"$tmp" || { rm -f "$tmp" "$tmp.body"; die "snapshot failed"; }
  # die inside $(...) only exits the subshell, so check the body is complete before keeping it.
  if ! grep -q '^home_resolve=.' "$tmp.body" || ! grep -q '^secure.enabled_accessibility_services=.' "$tmp.body" \
     || ! grep -q '^secure.accessibility_enabled=.' "$tmp.body"; then
    rm -f "$tmp" "$tmp.body"; die "snapshot incomplete; nothing written"
  fi
  rm -f "$tmp"; mkdir -p "$(dirname "$SNAP")"; mv "$tmp.body" "$SNAP"
  printf 'wrote %s\n' "$SNAP"; sed -n '/^[a-z]/p' "$SNAP"
}

# READ-ONLY: settings get, dumpsys, pm path, cmd package resolve-activity, cat. Nothing here writes.
cmd_status() {
  local cur perm home want flags p
  cur=$(tv_get 'settings get secure enabled_accessibility_services') || exit 1
  printf 'enabled_accessibility_services = %s\n' "$cur"
  printf 'accessibility_enabled          = %s\n' "$(tv_get 'settings get secure accessibility_enabled')"
  if has_hof "$cur"; then printf 'HoF redirect                   = ON (HoF entry present)\n'
  else printf 'HoF redirect                   = OFF (no HoF entry)\n'; fi
  perm=$(tv_read "dumpsys package $PKG" | grep 'WRITE_SECURE_SETTINGS' || true)
  case "$perm" in *granted=true*) printf 'WRITE_SECURE_SETTINGS grant    = yes\n' ;;
                  *) printf 'WRITE_SECURE_SETTINGS grant    = no\n' ;; esac
  perm=$(tv_read "dumpsys package $PKG" | grep -E 'android.permission.(WRITE_SECURE_SETTINGS|INTERNET)' || true)
  printf 'uses-permission                = WRITE_SECURE_SETTINGS granted %s, INTERNET requested %s\n' \
    "$(case "$perm" in *WRITE_SECURE_SETTINGS*granted=true*) echo yes ;; *) echo no ;; esac)" \
    "$(case "$perm" in *android.permission.INTERNET*) echo yes ;; *) echo no ;; esac)"
  printf 'screensaver_components         = %s\n' "$(tv_get 'settings get secure screensaver_components')"
  printf 'screensaver_enabled            = %s\n' "$(tv_get 'settings get secure screensaver_enabled')"
  printf 'orchard city                   = %s\n' "$(get_city)"
  printf 'focus                          = %s\n' "$(tv_read 'dumpsys window' | grep -m1 'mCurrentFocus' | sed 's/^[ \t]*//')"
  for p in "$HOF" "$PKG"; do
    printf 'pm path %-27s = %s\n' "$p" "$(tv_get "pm path $p")"
  done
  printf 'orchard LEANBACK resolve       = %s\n' "$(tv_read "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p $PKG" | tail -n 1)"
  home=$(tv_read "$HOME_CMD" | tail -n 1)
  printf 'HOME resolves to               = %s\n' "$home"
  if [ -f "$SNAP" ]; then
    want=$(snap_get home_resolve)
    if [ "$home" = "$want" ]; then printf 'HOME vs snapshot               = same\n'
    else printf 'HOME vs snapshot               = DIFFERENT (snapshot: %s)\n' "$want"; fi
  else printf 'HOME vs snapshot               = no snapshot yet\n'; fi
  # SDK 30: the shell user cannot read /sdcard/Android/data/<pkg> (ext_data_rw), so say so instead of "no flags".
  flags=$(tv_read "cat $CONF 2>&1 | grep -E '^debug_|Permission denied|No such file'" || true)
  case "$flags" in
    *"Permission denied"*) printf 'debug flags                    = flags unreadable (adb cannot read orchard.conf; set/clear via am start --es orchard.debug / --ez orchard.debug_clear true)\n' ;;
    *debug_*) printf 'WARNING debug flags in orchard.conf:\n%s\n' "$flags" ;;
    *) ;;
  esac
  return 0
}

# Last data="..." of an am broadcast (empty when Orchard did not answer).
bcast_data() { printf '%s\n' "$1" | sed -n 's/.*data="\([^"]*\)".*/\1/p' | sed -n 1p; }
get_city() {
  local out d
  out=$("$TV" "am broadcast -f 0x20 -n $PKG/.DebugReceiver -a $PKG.GET_CITY" 2>/dev/null | tr -d '\r') || out=""
  d=$(bcast_data "$out")
  if [ -n "$d" ]; then printf '%s' "$d"; else printf 'unknown (Orchard not answering)'; fi
}

cmd_reset_to_stock() {
  local cur
  cur=$(tv_get 'settings get secure enabled_accessibility_services') || exit 1
  put_a11y "$(strip_hof "$cur")"
  pause 1
  tv_write "am force-stop $PKG"
  tv_write "am force-stop $HOF"
  tv_write 'am start -a android.intent.action.MAIN -c android.intent.category.HOME -p com.amazon.tv.launcher'
  pause 1
  cmd_status
}

cmd_restore_home() {
  local want="" list cur
  if [ -f "$SNAP" ]; then want=$(snap_get secure.enabled_accessibility_services); fi
  if [ -n "$want" ] && has_hof "$want"; then
    list=$want
  else
    if [ -f "$SNAP" ]; then printf 'note: snapshot has no HoF entry; using current list + HoF\n' >&2; fi
    cur=$(tv_get 'settings get secure enabled_accessibility_services') || exit 1
    list=$(append_hof "$cur")
  fi
  put_a11y "$list"
  tv_write 'settings put secure accessibility_enabled 1'
  pause 1
  # HoF only redirects a Home press made from ANOTHER app (not while Amazon home is in front), so put Orchard in
  # front first (a harmless launch of the app's own Home activity, no effect on any package state), then press Home.
  # It lands on whatever HoF currently targets (Orchard).
  tv_write "am start -n $PKG/.HomeActivity"
  pause 2
  tv_write 'input keyevent 3'
  pause 1
  cmd_status
}

restore_one() {  # $1 = namespace, $2 = key; value comes from the snapshot ("null" -> delete)
  local v
  v=$(snap_get "$1.$2") || true
  if [ -z "$v" ]; then printf 'skip %s.%s: not in snapshot\n' "$1" "$2" >&2; return 0; fi
  if [ "$v" = null ]; then tv_write "settings delete $1 $2"; else tv_write "settings put $1 $2 $v"; fi
}
cmd_restore_settings() {
  need_snap
  restore_one global animator_duration_scale
  restore_one secure screensaver_components
  restore_one secure screensaver_enabled
  if [ "$(snap_get deviceidle_orchard)" = 1 ]; then tv_write "dumpsys deviceidle whitelist +$PKG"
  else tv_write "dumpsys deviceidle whitelist -$PKG"; fi
  printf 'restored animator scale, screensaver and deviceidle entry from %s\n' "$SNAP"
}

cmd_uninstall() {
  if [ "${DRY_RUN:-0}" != 1 ]; then
    confirm UNINSTALL "This runs: pm uninstall $PKG (Orchard only; Home on Fire stays)." || return 1
  fi
  tv_write "pm uninstall $PKG"
}

cmd_factory_stock() {
  need_snap
  if [ "${DRY_RUN:-0}" != 1 ]; then
    confirm STOCK "factory-stock: strip Home on Fire from the redirect list, restore snapshot settings (animation scale, screensaver, battery whitelist), uninstall Orchard. Then run detox.sh undo all: re-enable disabled Amazon packages, ad settings, background limits, Private DNS. Logins are kept; Home on Fire stays installed." || return 1
  else
    printf '== dry run: nothing below is executed ==\n'
  fi
  cmd_reset_to_stock
  cmd_restore_settings
  # detox.sh reads backup/detox-pre.txt when present, else documented stock defaults; DRY_RUN is inherited.
  bash "$SCRIPTS_DIR/detox.sh" undo all || die "detox.sh undo all failed; Orchard was not uninstalled"
  tv_write "pm uninstall $PKG"
  cmd_status
}

# ---- city + screensaver (docs/PLAN.md 8.3, 8.4) ---------------------------------------------------

cmd_city() {
  local num='^-?[0-9]+([.][0-9]+)?$' id lat lon label="" cmd what out d form
  [ $# -ge 1 ] || die "usage: city <name> | city <lat> <lon> [label]"
  if [ $# -ge 2 ] && [[ $1 =~ $num ]] && [[ $2 =~ $num ]]; then
    lat=$1; lon=$2; shift 2
    awk -v a="$lat" -v b="$lon" 'BEGIN { exit !(a+0 >= -90 && a+0 <= 90 && b+0 >= -180 && b+0 <= 180) }' \
      || die "out of range: lat must be -90..90 and lon -180..180 (got $lat $lon)"
    if [ $# -gt 0 ]; then
      label=$(printf '%s' "$*" | LC_ALL=C tr -cd 'A-Za-z0-9 .-' | cut -c1-24 | sed 's/[[:space:]]*$//')
    fi
    cmd="am broadcast -f 0x20 -n $PKG/.DebugReceiver -a $PKG.SET_CITY --es city_lat $lat --es city_lon $lon"
    [ -z "$label" ] || cmd="$cmd --es city_name '$label'"
    what="$lat,$lon${label:+ $label}"; form=coord
  else
    id=$(printf '%s' "$*" | LC_ALL=C tr 'A-Z' 'a-z' | LC_ALL=C tr -cd 'a-z0-9')
    [ -n "$id" ] || die "empty city name. Use: launcher.sh city <name> | city <lat> <lon> [label]"
    cmd="am broadcast -f 0x20 -n $PKG/.DebugReceiver -a $PKG.SET_CITY --es city_id $id"
    what=$id; form=name
  fi
  if [ "${DRY_RUN:-0}" = 1 ]; then printf 'would run: %s\n' "$cmd"; return 0; fi
  out=$("$TV" "$cmd" | tr -d '\r') || die "TV command failed (is the Stick reachable?)"
  d=$(printf '%s\n' "$out" | sed -n 's/.*data="\(-\{0,1\}[0-9][0-9]*\)".*/\1/p' | sed -n 1p)
  case "$d" in
    1) printf 'city set: %s\n' "$what" ;;
    0) printf 'city unchanged: %s\n' "$what" ;;
    -1) if [ "$form" = name ]; then die "unknown city: $what. Use: launcher.sh city <lat> <lon> [label]"
        else die "rejected by Orchard: $lat $lon"; fi ;;
    *) die "no answer from Orchard (installed? run launcher.sh install)" ;;
  esac
}

DREAM=$PKG/.ClockDream
STOCK_DREAM=com.android.dreams.basic/com.android.dreams.basic.Colors
ss_show() { printf '%s: screensaver_components = %s, screensaver_enabled = %s\n' "$1" \
  "$(tv_get 'settings get secure screensaver_components')" "$(tv_get 'settings get secure screensaver_enabled')"; }

cmd_screensaver() {
  local mode=${1:-} svc comp en
  case "$mode" in on|off) ;; *) usage; exit 2 ;; esac
  if [ "$mode" = on ]; then
    if [ "${DRY_RUN:-0}" != 1 ]; then
      confirm SCREENSAVER "screensaver on: sets secure.screensaver_components to $DREAM and secure.screensaver_enabled to 1. Undo: launcher.sh screensaver off." || return 1
    fi
    if [ ! -f "$SNAP" ]; then
      if [ "${DRY_RUN:-0}" = 1 ]; then printf 'note: no snapshot at %s; a real run takes one first (read-only)\n' "$SNAP" >&2
      else cmd_snapshot || die "snapshot failed; screensaver off depends on it"; fi
    fi
    svc=$(tv_read 'cmd package query-services --brief -a android.service.dreams.DreamService')
    case "$svc" in *"$DREAM"*) ;; *) die "ClockDream not found: install the current APK (launcher.sh install)" ;; esac
    ss_show before
    tv_write "settings put secure screensaver_components $DREAM"
    tv_write "settings put secure screensaver_enabled 1"
    if [ "${DRY_RUN:-0}" != 1 ]; then
      [ "$(tv_get 'settings get secure screensaver_components')" = "$DREAM" ] || die "verify failed: screensaver_components did not take"
      [ "$(tv_get 'settings get secure screensaver_enabled')" = 1 ] || die "verify failed: screensaver_enabled did not take"
      ss_show after
    fi
  else
    comp=$(snap_get secure.screensaver_components 2>/dev/null || true)
    en=$(snap_get secure.screensaver_enabled 2>/dev/null || true)
    if [ -z "$comp" ] || [ "$comp" = null ] || ! [[ $comp =~ ^[A-Za-z0-9_./\$:,-]+$ ]]; then
      printf 'note: no usable screensaver_components in the snapshot; using Android Colors\n' >&2; comp=$STOCK_DREAM
    fi
    if ! [[ $en =~ ^[0-9]+$ ]]; then
      printf 'note: no usable screensaver_enabled in the snapshot; using 1\n' >&2; en=1
    fi
    ss_show before
    tv_write "settings put secure screensaver_components $comp"
    tv_write "settings put secure screensaver_enabled $en"
    [ "${DRY_RUN:-0}" = 1 ] || ss_show after
  fi
}

# ---- dispatch ------------------------------------------------------------------------------------

main() {
  local sub=${1:-} a
  if [ $# -gt 0 ]; then shift; fi
  local -a rest=()
  for a in "$@"; do
    case "$a" in --dry-run) DRY_RUN=1; export DRY_RUN ;; *) rest+=("$a") ;; esac
  done
  set -- ${rest[@]+"${rest[@]}"}
  case "$sub" in
    build|test|push|install|grant|shot|key|perf) "cmd_$sub" "$@" ;;
    snapshot) cmd_snapshot "$@" ;;
    status) cmd_status ;;
    city) cmd_city "$@" ;;
    screensaver) cmd_screensaver "$@" ;;
    reset-to-stock) cmd_reset_to_stock ;;
    restore-home) cmd_restore_home ;;
    restore-settings) cmd_restore_settings ;;
    factory-stock) cmd_factory_stock ;;
    uninstall) cmd_uninstall ;;
    -h|--help|help) usage ;;
    *) usage; exit 2 ;;
  esac
}

main "$@"
