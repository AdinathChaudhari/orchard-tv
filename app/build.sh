#!/usr/bin/env bash
# Orchard build: host tests, then aapt2 + javac + d8 + zipalign + apksigner. No Gradle, no libraries.
# Structure adapted from dumbtv/launcher/build.sh (MIT, see THIRD_PARTY_NOTICES.txt).
# Usage: build.sh [--out-dir DIR]     (default DIR = app/build; use a separate DIR per git worktree)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
APP=$PWD

OUT=$APP/build
while [[ $# -gt 0 ]]; do
  case $1 in
    --out-dir) [[ $# -ge 2 ]] || { echo "error: --out-dir needs a value" >&2; exit 2; }
               mkdir -p "$2"; OUT=$(cd "$2" && pwd); shift 2 ;;
    *) echo "usage: build.sh [--out-dir DIR]" >&2; exit 2 ;;
  esac
done

# shellcheck disable=SC1091
source version.env
# shellcheck disable=SC1091
source ../tools/env.sh

[[ -f ${JAR:-} ]] || { echo "error: android-30 jar missing ($JAR): sdkmanager 'platforms;android-30'" >&2; exit 1; }
[[ -x ${BT:-}/aapt2 ]] || { echo "error: build-tools missing ($BT)" >&2; exit 1; }
JAVAC=$JAVA_HOME/bin/javac
[[ -x $JAVAC ]] || { echo "error: no javac in $JAVA_HOME" >&2; exit 1; }

PKG=src/tv/orchard/home
# Pure-Java host-testable classes: every src file carrying the marker text "PURE JAVA" (bash 3.2 safe).
PURE=()
while IFS= read -r f; do PURE+=("$f"); done < <(grep -l 'PURE JAVA' "$PKG"/*.java | LC_ALL=C sort)
for f in ${PURE[@]+"${PURE[@]}"}; do
  if grep -q '^import android\.' "$f"; then
    echo "error: $f is marked PURE JAVA but imports android.*" >&2; exit 1
  fi
done

mkdir -p "$OUT"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/orchard-build.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

# 0. host tests: every class marked PURE JAVA is compiled with test/*Test.java and must not import android.*
echo "== host tests"
echo "host-test classpath: ${#PURE[@]} pure classes"
mkdir -p "$OUT/test"
rm -rf "$OUT/test"/*
shopt -s nullglob
TESTS=(test/*Test.java)
shopt -u nullglob
"$JAVAC" --release 8 -Xlint:all -Xlint:-options -Xlint:-this-escape -Werror -encoding UTF-8 -d "$OUT/test" \
  ${TESTS[@]+"${TESTS[@]}"} "${PURE[@]}"
if [[ ${#TESTS[@]} -eq 0 ]]; then
  echo "no host tests yet (pure classes compiled without android.*)"
else
  for t in "${TESTS[@]}"; do
    cls=$(basename "$t" .java)
    if "$JAVA_HOME/bin/java" -cp "$OUT/test" "tv.orchard.home.$cls"; then echo "PASS $cls"; else echo "FAIL $cls" >&2; exit 1; fi
  done
fi

# keystore (created once; keep both files, updates must be signed with the same key)
KEYSTORE=${ORCHARD_KEYSTORE:-$APP/release.keystore}
KEYPASS_FILE=${ORCHARD_KEYPASS_FILE:-$APP/.keypass}
if [[ ! -f $KEYSTORE || ! -f $KEYPASS_FILE ]]; then
  if [[ -f $KEYSTORE ]]; then echo "error: $KEYSTORE exists but $KEYPASS_FILE is missing" >&2; exit 1; fi
  (umask 077; openssl rand -hex 16 | tr -d '\n' > "$KEYPASS_FILE")
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias orchard -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$(cat "$KEYPASS_FILE")" -keypass "$(cat "$KEYPASS_FILE")" \
    -dname "CN=Orchard Home" >/dev/null
  echo "created signing key $KEYSTORE + $KEYPASS_FILE: BACK BOTH UP (updates need the same key)"
fi
KEYPASS=$(cat "$KEYPASS_FILE")

# 1. resources + manifest
echo "== aapt2"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/assets"
if [[ -f ../THIRD_PARTY_NOTICES.txt ]]; then cp ../THIRD_PARTY_NOTICES.txt "$WORK/assets/NOTICES.txt"; fi
[[ -d assets ]] && cp -R assets/. "$WORK/assets/"
"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml "$WORK/res.zip" \
  --min-sdk-version 28 --target-sdk-version 30 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A "$WORK/assets" --java "$WORK/gen" -o "$WORK/app.apk"

# 2. compile (-this-escape: JDK 21 lint; flags every View subclass that calls a setter in its constructor)
echo "== javac"
find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JAVAC" --release 8 -Xlint:all -Xlint:-options -Xlint:-this-escape -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"

# 3. dex, package, align, sign
echo "== d8 / sign"
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)
"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
rm -f "$OUT/orchard.apk" "$OUT/orchard.apk.idsig"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --v2-signing-enabled true --v3-signing-enabled true --out "$OUT/orchard.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify "$OUT/orchard.apk"

PERMS=$("$BT/aapt2" dump permissions "$OUT/orchard.apk" | sed -n "s/^uses-permission.*name='\([^']*\)'.*/\1/p" | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')
printf 'built %s  %s bytes  permissions: %s\n' \
  "$OUT/orchard.apk" "$(wc -c < "$OUT/orchard.apk" | tr -d ' ')" "$PERMS"
[[ $PERMS == "android.permission.INTERNET android.permission.WRITE_SECURE_SETTINGS" ]] || {
  echo "error: expected exactly WRITE_SECURE_SETTINGS + INTERNET, got: $PERMS" >&2; exit 1; }
