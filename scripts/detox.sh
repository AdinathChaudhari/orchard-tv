#!/usr/bin/env bash
# detox.sh -- remove Amazon's ads, tracking and bloatware from a Fire TV Stick, one layer at a time.
#
# Usage: scripts/detox.sh <subcommand> [args] [--dry-run]
#   snapshot [--force]   READ-ONLY on the TV. Save the current value of every setting and package this script
#                        touches into backup/detox-pre.txt (git-ignored). Refuses to overwrite without --force.
#   privacy              ad-tracking limits, interest-based ads off, usage-data and metrics upload off
#   screensaver          disable Amazon's screensaver (it shows ads), use Android's plain "Colors" one
#   background           stop the telemetry/ads packages Fire OS will not let you disable from running in
#                        the background (appops RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND = ignore)
#   dns                  Private DNS to an ad-blocking resolver (DNS_HOST, default dns.adguard-dns.com)
#   debloat              pm disable-user --user 0 for config/debloat.txt; typed gate DEBLOAT
#   updates              OPTIONAL and risky both ways: disable the firmware-update packages; typed gate UPDATES.
#                        Not part of 'all'. Blocks security fixes; allowing updates can break the Home redirect.
#   all                  snapshot (if none yet), privacy, screensaver, background, dns, debloat; typed gate DETOX
#   status               READ-ONLY report per layer, plus a warning if a never-disable package is disabled
#   undo <layer|all>     restore from backup/detox-pre.txt when it exists, else the documented stock defaults
#   guard <package>      host-only: exit 1 when <package> is on config/never-disable.txt
#
# Every TV command goes through scripts/tv.sh (needs TV_HOST, see that file).
# --dry-run (or DRY_RUN=1) prints every TV-changing command as 'would run: ...' instead of running it; reads
# (settings get, pm list, appops get) still run because they never change the TV. Typed gates are skipped in a dry run.
# Safety: debloat/updates REFUSE any package on config/never-disable.txt. Take a snapshot before the first write.
# Overrides for tests: DEBLOAT_LIST, NEVER_LIST, DETOX_TV (path of a tv.sh stand-in), DETOX_SNAP.
set -eu

SCRIPTS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(dirname "$SCRIPTS_DIR")
TV=${DETOX_TV:-$SCRIPTS_DIR/tv.sh}
DEBLOAT_LIST=${DEBLOAT_LIST:-$ROOT/config/debloat.txt}
NEVER_LIST=${NEVER_LIST:-$ROOT/config/never-disable.txt}
SNAP=${DETOX_SNAP:-$ROOT/backup/detox-pre.txt}
DNS_HOST=${DNS_HOST:-dns.adguard-dns.com}

SAVER_PKG=com.amazon.ftv.screensaver
DREAM=com.android.dreams.basic/.Colors
# Packages Fire OS refuses to disable ("protected package"): restricted in the background instead.
BG_PKGS="com.amazon.ftvads.deeplinking com.amazon.tv.csapp com.amazon.device.metrics com.amazon.device.logmanager com.amazon.device.crashmanager com.fireos.usagestats.proxy"
# Firmware-update packages (optional 'updates' layer only). The first two are protected on Fire OS 8.
OTA_PKGS="com.amazon.device.software.ota com.amazon.device.software.ota.override com.amazon.tv.forcedotaupdater.v2 com.amazon.tv.easyupgrade"
BG_OPS="RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND"

# "namespace key" pairs this script reads or writes (snapshot saves all of them).
PRIVACY_KEYS="secure:limit_ad_tracking secure:amz_limit_ad_tracking secure:non_amz_limit_ad_tracking secure:usage_metrics_marketing_enabled secure:advertisingIdApp:interestBasedAds:value secure:USAGE_METRICS_UPLOAD_ENABLED secure:settingsApp:appUsageData:value secure:settingsApp:deviceUsageData:value"
SAVER_KEYS="secure:screensaver_enabled secure:screensaver_components secure:screensaver_default_component secure:screensaver_activate_on_sleep secure:screensaver_activate_on_dock"
DNS_KEYS="global:private_dns_mode global:private_dns_specifier"

usage() { sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; }
die() { printf 'detox.sh: %s\n' "$*" >&2; exit 1; }

# ---- shared TV helpers (same shape as launcher.sh) -----------------------------------------------

tv_read() { local out; out=$("$TV" "$1") || die "cannot reach the TV (check TV_HOST and that the Stick is on): $1"; printf '%s\n' "$out" | tr -d '\r'; }
tv_get() { local g; g=$(tv_read "$1") || return 1; printf '%s\n' "$g" | sed -n 1p; }
tv_write() {
  if [ "${DRY_RUN:-0}" = 1 ]; then printf 'would run: %s\n' "$1"; return 0; fi
  "$TV" "$1" >/dev/null || die "TV command failed (is the Stick reachable?): $1"
}
ALL_CONFIRMED=0     # set by cmd_all after its DETOX gate; an exported value cannot skip a gate
confirm() {  # $1 = word to type, $2 = what will happen. Skipped in a dry run and when a parent already asked.
  local ans
  if [ "${DRY_RUN:-0}" = 1 ] || [ "$ALL_CONFIRMED" = 1 ]; then return 0; fi
  printf '%s\nType %s and press Return to continue: ' "$2" "$1" >&2
  if ! IFS= read -r ans; then printf '\nrefused: no confirmation on stdin. Nothing was changed.\n' >&2; return 1; fi
  if [ "$ans" != "$1" ]; then printf 'refused: confirmation word did not match. Nothing was changed.\n' >&2; return 1; fi
  return 0
}

# ---- config files and the never-disable guard ----------------------------------------------------

# Entries of a list file: comments and blanks dropped, first word only.
cfg_list() { [ -f "$1" ] || die "missing list file: $1"; sed -E 's/[[:space:]]*#.*$//; s/^[[:space:]]+//; s/[[:space:]]+$//' "$1" | awk 'NF { print $1 }'; }

valid_pkg() { [[ $1 =~ ^[A-Za-z0-9._]+$ ]]; }

# 0 when $1 is on the never-disable list (exact match, or prefix match for entries ending in .*).
is_protected() {
  local pat
  valid_pkg "$1" || return 0                    # an odd-looking name is never worth risking
  while IFS= read -r pat; do
    # shellcheck disable=SC2254
    case "$1" in $pat) return 0 ;; esac
  done < <(cfg_list "$NEVER_LIST")
  return 1
}

cmd_guard() {
  [ $# -eq 1 ] || die "usage: guard <package>"
  if is_protected "$1"; then printf 'refused: %s is on the never-disable list\n' "$1"; return 1; fi
  printf 'ok: %s is not on the never-disable list\n' "$1"
}

# ---- package and setting state -------------------------------------------------------------------

PKG_ALL="" PKG_DIS="" PKG_LOADED=0
load_pkgs() {
  [ "$PKG_LOADED" = 1 ] && return 0
  PKG_ALL=$(tv_read 'pm list packages'); PKG_DIS=$(tv_read 'pm list packages -d'); PKG_LOADED=1
}
pkg_state() {  # enabled | disabled | absent
  load_pkgs
  if printf '%s\n' "$PKG_DIS" | grep -qx "package:$1"; then echo disabled
  elif printf '%s\n' "$PKG_ALL" | grep -qx "package:$1"; then echo enabled
  else echo absent; fi
}

# One adb call reads many settings: prints "<ns>.<key>=<value>" lines.
read_settings() {  # args: ns:key ...
  local s ns key cmd=""
  for s in "$@"; do ns=${s%%:*}; key=${s#*:}; cmd="$cmd echo $ns.$key=\$(settings get $ns $key);"; done
  tv_read "$cmd"
}
# One adb call reads many app-ops: prints "appops.<pkg>.<op>=<mode>" (mode: allow ignore deny default absent).
read_appops() {  # args: pkg ...
  local p op cmd="" raw line mode
  for p in "$@"; do for op in $BG_OPS; do
    cmd="$cmd echo appops.$p.$op=\$(appops get $p $op 2>&1 | head -n 1);"
  done; done
  raw=$(tv_read "$cmd")
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    mode=${line#*=}
    case "$mode" in
      *": allow"*) mode=allow ;; *": ignore"*) mode=ignore ;; *": deny"*) mode=deny ;;
      *": foreground"*) mode=foreground ;; *": default"*) mode=default ;;
      *nknown*|*xception*|*rror*) mode=absent ;; *) mode=default ;;
    esac
    printf '%s=%s\n' "${line%%=*}" "$mode"
  done <<<"$raw"
}

snap_get() { [ -f "$SNAP" ] || return 1; sed -n "s/^$1=//p" "$SNAP" | sed -n 1p; }
all_keys() { printf '%s %s %s' "$PRIVACY_KEYS" "$SAVER_KEYS" "$DNS_KEYS"; }
all_pkgs() { { cfg_list "$DEBLOAT_LIST"; printf '%s\n' $SAVER_PKG $BG_PKGS $OTA_PKGS; } | awk '!seen[$0]++'; }

# Documented stock values (Fire OS 8, Fire TV Stick 4K Plus), used by undo when there is no snapshot.
stock_default() {  # $1 = ns.key
  case "$1" in
    secure.screensaver_enabled|secure.screensaver_activate_on_sleep|secure.screensaver_activate_on_dock) echo 1 ;;
    secure.screensaver_components|secure.screensaver_default_component) echo "$SAVER_PKG/.app.services.ScreensaverService" ;;
    secure.limit_ad_tracking|secure.amz_limit_ad_tracking) echo 1 ;;
    secure.non_amz_limit_ad_tracking|secure.usage_metrics_marketing_enabled|secure.advertisingIdApp:interestBasedAds:value| \
    secure.USAGE_METRICS_UPLOAD_ENABLED|secure.settingsApp:appUsageData:value|secure.settingsApp:deviceUsageData:value) echo 0 ;;
    global.private_dns_mode|global.private_dns_specifier) echo null ;;
    *) return 1 ;;
  esac
}

# Put one setting back: snapshot value, else documented stock value. "null" means the key was unset: delete it.
restore_key() {  # $1 = ns:key
  local ns=${1%%:*} key=${1#*:} v
  v=$(snap_get "$ns.$key" || true)
  [ -n "$v" ] || v=$(stock_default "$ns.$key" || true)
  [ -n "$v" ] || { printf 'skip %s.%s: no snapshot value and no stock default\n' "$ns" "$key" >&2; return 0; }
  if [ "$v" = null ]; then tv_write "settings delete $ns $key"; else tv_write "settings put $ns $key $v"; fi
}
put_key() { tv_write "settings put ${1%%:*} ${1#*:} $2"; }

# ---- snapshot ------------------------------------------------------------------------------------

cmd_snapshot() {
  local force=0 tmp p s
  [ "${1:-}" = "--force" ] && force=1
  if [ -f "$SNAP" ] && [ "$force" != 1 ]; then
    printf 'snapshot exists: %s (refusing to overwrite; use: detox.sh snapshot --force)\n' "$SNAP" >&2; return 1
  fi
  tmp=$(mktemp "${TMPDIR:-/tmp}/detox-pre.XXXXXX") || die "mktemp failed"
  {
    printf '# detox-pre snapshot: values before detox.sh changed anything. Read by undo and status. Do not hand-edit.\n'
    printf 'date=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
    # shellcheck disable=SC2046
    read_settings $(all_keys)
    # shellcheck disable=SC2086
    read_appops $BG_PKGS $OTA_PKGS
    while IFS= read -r p; do printf 'pkg.%s=%s\n' "$p" "$(pkg_state "$p")"; done < <(all_pkgs)
  } >"$tmp" || { rm -f "$tmp"; die "snapshot failed; nothing written"; }
  for s in secure.limit_ad_tracking global.private_dns_mode; do
    grep -q "^$s=." "$tmp" || { rm -f "$tmp"; die "snapshot incomplete ($s missing); nothing written"; }
  done
  mkdir -p "$(dirname "$SNAP")"; mv "$tmp" "$SNAP"
  printf 'wrote %s (%s lines)\n' "$SNAP" "$(grep -c '^[a-z]' "$SNAP")"
}

# Snapshot only when none exists: re-running 'all' must never overwrite the stock values with detoxed ones.
snapshot_if_missing() {
  if [ -f "$SNAP" ]; then printf 'snapshot kept: %s\n' "$SNAP"; else cmd_snapshot; fi
}

# ---- layers --------------------------------------------------------------------------------------

# The only code path that runs pm disable-user. Guard first, then state, then the command.
REFUSED=0 DONE=0 SKIPPED=0 FIREOS_REFUSED=0
disable_pkg() {
  local p=$1 st out
  if is_protected "$p"; then
    printf 'REFUSED %s: on the never-disable list\n' "$p"; REFUSED=$((REFUSED + 1)); return 0
  fi
  st=$(pkg_state "$p")
  case "$st" in
    absent) printf 'skip %s: not installed\n' "$p"; SKIPPED=$((SKIPPED + 1)); return 0 ;;
    disabled) printf 'skip %s: already disabled\n' "$p"; SKIPPED=$((SKIPPED + 1)); return 0 ;;
  esac
  if [ "${DRY_RUN:-0}" = 1 ]; then printf 'would run: pm disable-user --user 0 %s\n' "$p"; return 0; fi
  out=$("$TV" "pm disable-user --user 0 $p" 2>&1 | tr -d '\r') || die "TV command failed: pm disable-user $p"
  case "$out" in
    *"new state: disabled"*) printf 'disabled %s\n' "$p"; DONE=$((DONE + 1)) ;;
    *) printf 'protected package (Fire OS refused): %s\n' "$p"; FIREOS_REFUSED=$((FIREOS_REFUSED + 1)) ;;
  esac
}
enable_pkg() {  # re-enable is always safe; only touches packages that exist
  local st; st=$(pkg_state "$1")
  if [ "$st" = absent ]; then printf 'skip %s: not installed\n' "$1"; return 0; fi
  tv_write "pm enable --user 0 $1"
}
summary() { printf 'summary: disabled %s, skipped %s, refused by guard %s, refused by Fire OS %s\n' "$DONE" "$SKIPPED" "$REFUSED" "$FIREOS_REFUSED"; }

layer_privacy() {
  local k
  echo "== privacy"
  snapshot_if_missing
  for k in secure:limit_ad_tracking secure:amz_limit_ad_tracking secure:non_amz_limit_ad_tracking; do put_key "$k" 1; done
  for k in secure:usage_metrics_marketing_enabled secure:advertisingIdApp:interestBasedAds:value \
           secure:USAGE_METRICS_UPLOAD_ENABLED secure:settingsApp:appUsageData:value secure:settingsApp:deviceUsageData:value; do
    put_key "$k" 0
  done
}

layer_screensaver() {
  echo "== screensaver"
  snapshot_if_missing
  put_key secure:screensaver_components "$DREAM"
  put_key secure:screensaver_enabled 1
  disable_pkg "$SAVER_PKG"
}

layer_background() {
  local p op
  echo "== background (appops ignore for packages Fire OS will not disable)"
  load_pkgs
  snapshot_if_missing
  for p in $BG_PKGS; do
    if [ "$(pkg_state "$p")" = absent ]; then printf 'skip %s: not installed\n' "$p"; continue; fi
    for op in $BG_OPS; do tv_write "appops set $p $op ignore"; done
  done
}

layer_dns() {
  echo "== dns ($DNS_HOST)"
  [[ $DNS_HOST =~ ^[A-Za-z0-9.-]+$ ]] || die "DNS_HOST looks wrong: $DNS_HOST"
  snapshot_if_missing
  # DNS_MODE=opportunistic: the clock-immune alternative (docs/DETOX.md); default stays strict.
  local mode=${DNS_MODE:-hostname}
  case "$mode" in (hostname|opportunistic) ;; (*) die "DNS_MODE must be hostname or opportunistic: $mode" ;; esac
  put_key global:private_dns_mode "$mode"
  put_key global:private_dns_specifier "$DNS_HOST"
}

layer_debloat() {
  local p
  echo "== debloat ($DEBLOAT_LIST)"
  confirm DEBLOAT "debloat: disable every installed package on config/debloat.txt (never-disable.txt is refused). Disabled, not deleted; 'detox.sh undo debloat' re-enables them. Aggressive debloat can bootloop a Stick: keep the list conservative." || return 1
  snapshot_if_missing
  while IFS= read -r p; do disable_pkg "$p"; done < <(cfg_list "$DEBLOAT_LIST")
  summary
}

layer_updates() {
  local p op
  echo "== updates (OPTIONAL: blocks Fire OS updates)"
  confirm UPDATES "updates: disable the firmware-update packages. This blocks security fixes AND stops Fire OS changing under a working setup. Risky both ways. 'detox.sh undo updates' reverses it." || return 1
  snapshot_if_missing
  for p in $OTA_PKGS; do
    disable_pkg "$p"
    if [ "$(pkg_state "$p")" != absent ]; then for op in $BG_OPS; do tv_write "appops set $p $op ignore"; done; fi
  done
  summary
}

cmd_all() {
  confirm DETOX "all: snapshot, then privacy, screensaver, background, dns and a conservative debloat. Nothing is deleted; 'detox.sh undo all' reverses it. Firmware updates are NOT touched." || return 1
  ALL_CONFIRMED=1    # shell variable only, never read from the environment
  snapshot_if_missing
  layer_privacy; layer_screensaver; layer_background; layer_dns; layer_debloat
  echo "done. Next: scripts/detox.sh status"
}

# ---- status (READ-ONLY: settings get, pm list, appops get) ----------------------------------------

cmd_status() {
  local p n=0 dis=0 abs=0 st line bad=0
  echo "== privacy"
  # shellcheck disable=SC2086
  read_settings $PRIVACY_KEYS | sed 's/^/  /'
  echo "== screensaver"
  # shellcheck disable=SC2086
  read_settings $SAVER_KEYS | sed 's/^/  /'
  printf '  pkg.%s=%s\n' "$SAVER_PKG" "$(pkg_state "$SAVER_PKG")"
  echo "== background"
  # shellcheck disable=SC2086
  read_appops $BG_PKGS | sed 's/^/  /'
  echo "== dns"
  # shellcheck disable=SC2086
  read_settings $DNS_KEYS | sed 's/^/  /'
  echo "== debloat"
  while IFS= read -r p; do
    n=$((n + 1)); st=$(pkg_state "$p")
    case "$st" in disabled) dis=$((dis + 1)) ;; absent) abs=$((abs + 1)) ;; esac
  done < <(cfg_list "$DEBLOAT_LIST")
  printf '  listed %s: disabled %s, enabled %s, not installed %s\n' "$n" "$dis" "$((n - dis - abs))" "$abs"
  echo "== updates"
  for p in $OTA_PKGS; do printf '  pkg.%s=%s\n' "$p" "$(pkg_state "$p")"; done
  echo "== safety"
  while IFS= read -r line; do
    case "$line" in *'*') continue ;; esac            # exact entries only
    if [ "$(pkg_state "$line")" = disabled ]; then printf '  WARNING never-disable package is disabled: %s (run: scripts/tv.sh "pm enable --user 0 %s")\n' "$line" "$line"; bad=1; fi
  done < <(cfg_list "$NEVER_LIST")
  [ "$bad" = 1 ] || echo "  no never-disable package is disabled"
  if [ -f "$SNAP" ]; then echo "  snapshot: present (undo restores from it)"; else echo "  snapshot: none yet (undo uses stock defaults). Run: scripts/detox.sh snapshot"; fi
  return 0
}

# ---- undo ----------------------------------------------------------------------------------------

undo_privacy() { local k; echo "== undo privacy"; for k in $PRIVACY_KEYS; do restore_key "$k"; done; }
undo_screensaver() {
  local k was; echo "== undo screensaver"
  was=$(snap_get "pkg.$SAVER_PKG" || true)
  if [ "$was" = disabled ]; then printf 'skip %s: was already disabled before detox\n' "$SAVER_PKG"
  else enable_pkg "$SAVER_PKG"; fi
  for k in $SAVER_KEYS; do restore_key "$k"; done
}
undo_background() {
  local p op m
  echo "== undo background"
  for p in $BG_PKGS; do
    if [ "$(pkg_state "$p")" = absent ]; then continue; fi
    for op in $BG_OPS; do
      m=$(snap_get "appops.$p.$op" || true)
      case "$m" in allow|ignore|deny|foreground|default) ;; *) m=allow ;; esac
      tv_write "appops set $p $op $m"
    done
  done
}
undo_dns() { local k; echo "== undo dns"; for k in $DNS_KEYS; do restore_key "$k"; done; }
undo_debloat() {
  local p was
  echo "== undo debloat"
  while IFS= read -r p; do
    was=$(snap_get "pkg.$p" || true)
    if [ "$was" = disabled ]; then printf 'skip %s: was already disabled before detox\n' "$p"; continue; fi
    enable_pkg "$p"
  done < <(cfg_list "$DEBLOAT_LIST")
}
undo_updates() {
  local p op m
  echo "== undo updates"
  for p in $OTA_PKGS; do
    if [ "$(snap_get "pkg.$p" || true)" != disabled ]; then enable_pkg "$p"; fi
    if [ "$(pkg_state "$p")" = absent ]; then continue; fi
    for op in $BG_OPS; do
      m=$(snap_get "appops.$p.$op" || true)
      case "$m" in allow|ignore|deny|foreground|default) ;; *) m=allow ;; esac
      tv_write "appops set $p $op $m"
    done
  done
}

cmd_undo() {
  local what=${1:-}
  [ -f "$SNAP" ] && printf 'restoring from %s\n' "$SNAP" || printf 'no snapshot: restoring documented stock defaults\n'
  case "$what" in
    privacy) undo_privacy ;; screensaver) undo_screensaver ;; background) undo_background ;;
    dns) undo_dns ;; debloat) undo_debloat ;; updates) undo_updates ;;
    all) undo_updates; undo_debloat; undo_dns; undo_background; undo_screensaver; undo_privacy ;;
    *) die "usage: undo privacy|screensaver|background|dns|debloat|updates|all" ;;
  esac
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
  # Fail fast (and cache the package lists in THIS shell, not in a $(...) subshell) before any TV work.
  case "$sub" in guard|-h|--help|help|"") ;; *) load_pkgs ;; esac
  case "$sub" in
    guard) cmd_guard "$@" ;;
    snapshot) cmd_snapshot "$@" ;;
    privacy) layer_privacy ;;
    screensaver) layer_screensaver ;;
    background) layer_background ;;
    dns) layer_dns ;;
    debloat) layer_debloat ;;
    updates) layer_updates ;;
    all) cmd_all ;;
    status) cmd_status ;;
    undo) cmd_undo "$@" ;;
    -h|--help|help) usage ;;
    *) usage; exit 2 ;;
  esac
}

main "$@"
