#!/usr/bin/env bash
# Task 1.4: download Inter, check OFL for a Reserved Font Name, subset to Latin.
# Output: app/res/font/inter_{regular,medium,semibold,thin}.ttf, app/assets/OFL.txt
# Usage: [FONTS='Thin'] tools/subset_fonts.sh [work-dir]   (work-dir must be new/empty; default: mktemp)
# FONTS = weights to (re)build, default 'Regular Medium SemiBold Thin'. Thin is digits only (the screensaver clock).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
VER=4.1
URL="https://github.com/rsms/inter/releases/download/v$VER/Inter-$VER.zip"
PY="${PY:-python3}"   # needs fonttools: pip install fonttools
WORK="${1:-$(mktemp -d)}"; mkdir -p "$WORK"
[ -z "$(ls -A "$WORK")" ] || { echo "work dir not empty: $WORK" >&2; exit 1; }

curl -fsSL -o "$WORK/inter.zip" "$URL"
unzip -q "$WORK/inter.zip" -d "$WORK/x"          # untrusted data: only read fonts + licence
LIC="$WORK/x/LICENSE.txt"

# Reserved Font Name check: OFL declares one as "with Reserved Font Name <X>" in the copyright line.
if grep -qiE 'with Reserved Font Names?' "$LIC"; then
  echo "Inter declares a Reserved Font Name: rename subsets (OrchardSans-*) before shipping" >&2
  exit 2
fi
echo "no Reserved Font Name declared: files keep the Inter name"

mkdir -p "$HERE/app/res/font" "$HERE/app/assets"
cp "$LIC" "$HERE/app/assets/OFL.txt"             # NOT in res/font: aapt2 rejects non-font files there
UNI="U+0020-007E,U+00A0-00FF,U+0131,U+0152-0153,U+02C6,U+02DA,U+02DC,U+2013-2014,U+2018-201A,U+201C-201E,U+2022,U+2026,U+2039-203A,U+20AC,U+2122,U+2190-2193,U+2212,U+00D7,U+25CF"
for w in ${FONTS:-Regular Medium SemiBold Thin}; do
  lw="$(echo "$w" | tr 'A-Z' 'a-z')"
  if [ "$w" = Thin ]; then U="U+0030-0039"; F=''; else U="$UNI"; F='kern,liga,calt,tnum,case'; fi
  "$PY" -I -m fontTools.subset "$WORK/x/extras/ttf/Inter-$w.ttf" \
    --unicodes="$U" --layout-features="$F" \
    --no-hinting --desubroutinize --name-IDs='*' --notdef-outline \
    --output-file="$HERE/app/res/font/inter_$lw.ttf"
done
ls -l "$HERE"/app/res/font/
