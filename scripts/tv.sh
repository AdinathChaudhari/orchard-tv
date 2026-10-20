#!/bin/sh
# Run one shell command on the Fire TV Stick over Wi-Fi ADB.
# Usage: scripts/tv.sh '<shell command>'      e.g. scripts/tv.sh 'pm list packages -d'
#        scripts/tv.sh --probe | --pull <remote> <local> | --push <local> <remote>
# Needs: TV_HOST (env or .env at the repo root) and a Python venv at .venv with tools/adb/requirements.txt
#   python3 -m venv .venv && .venv/bin/pip install -r tools/adb/requirements.txt
# Key: ORCHARD_ADB_KEY (default ~/.android/adbkey), created on first use; approve it on the TV once.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PY="${ORCHARD_PYTHON:-$ROOT/.venv/bin/python}"
[ -x "$PY" ] || { echo "tv.sh: no Python at $PY. Run: python3 -m venv .venv && .venv/bin/pip install -r tools/adb/requirements.txt" >&2; exit 1; }
exec "$PY" -I "$ROOT/tools/adb/tv.py" "$@"
