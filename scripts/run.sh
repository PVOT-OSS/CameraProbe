#!/usr/bin/env bash
# SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
# SPDX-License-Identifier: Apache-2.0
#
# Build, install and run CameraProbe on a device, then collect the results.
#   scripts/run.sh [-s serial] [-o outdir] [-p probe,probe] [--images] [--no-build] [--timeout seconds]
# Results land in <outdir>/<manufacturer>-<device>-<buildId>/<timestamp>/ (default outdir: ./results).

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PKG=org.prauga.cameraprobe
ADB_BIN=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}

serial=""
outdir="${PROBE_RESULTS:-$ROOT/results}"
probes=""
images=0
build=1
timeout=900

while [ $# -gt 0 ]; do
    case "$1" in
    -s) serial=$2; shift 2 ;;
    -o) outdir=$2; shift 2 ;;
    -p) probes=$2; shift 2 ;;
    --images) images=1; shift ;;
    --no-build) build=0; shift ;;
    --timeout) timeout=$2; shift 2 ;;
    -h|--help) sed -n '4,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument $1" >&2; exit 1 ;;
    esac
done

adb() { "$ADB_BIN" ${serial:+-s "$serial"} "$@"; }

if [ "$build" = 1 ]; then
    if [ -z "${JAVA_HOME:-}" ] && ! command -v javac >/dev/null; then
        JAVA_HOME=$(ls -d "$HOME"/.gradle/jdks/*-21-* 2>/dev/null | grep -vE '\.(gz|lock)$' | head -1 || true)
        [ -n "$JAVA_HOME" ] && export JAVA_HOME
    fi
    (cd "$ROOT" && ./gradlew -q assembleDebug)
fi

user=$(adb shell am get-current-user | tr -d '\r')
remote="/storage/emulated/$user/Android/data/$PKG/files/latest"

adb install --user "$user" -r -g "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb shell am force-stop --user "$user" "$PKG"
adb logcat -G 16M >/dev/null 2>&1 || true
adb logcat -c

tmp=$(mktemp -d)
adb logcat -v threadtime >"$tmp/logcat.txt" &
logcat_pid=$!
trap 'kill $logcat_pid 2>/dev/null || true; rm -rf "$tmp"' EXIT

adb shell input keyevent KEYCODE_WAKEUP
adb shell am start --user "$user" -n "$PKG/.ProbeActivity" ${probes:+--es only "$probes"} >/dev/null
echo "running${probes:+ $probes} on $(adb shell getprop ro.product.model | tr -d '\r')..."

waited=0
until adb shell tail -n 1 "$remote/report.txt" 2>/dev/null | tr -d '\r' | grep -qx DONE; do
    sleep 3
    waited=$((waited + 3))
    if [ "$waited" -ge "$timeout" ]; then
        echo "timed out after ${timeout}s; collecting what exists" >&2
        break
    fi
done
sleep 1
kill "$logcat_pid" 2>/dev/null || true

adb pull "$remote/." "$tmp/pull" >/dev/null
name=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print("-".join(d[k] for k in ("manufacturer","device","buildId")).lower().replace(" ","_"))' "$tmp/pull/run.json")
tags=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("halLogTags",""))' "$tmp/pull/run.json")
dest="$outdir/$name/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$dest"
cp -r "$tmp/pull/." "$dest/"
[ "$images" = 1 ] || rm -f "$dest"/*.jpg "$dest"/*.dng

# Only camera-related log lines are kept: the full logcat carries other apps' data.
pattern="PraugaProbe|CameraService|Camera3-|CameraDevice|camera\.provider|Fatal signal|F DEBUG|AndroidRuntime"
[ -n "$tags" ] && pattern="$pattern|${tags//,/|}"
grep -E " ($pattern)" "$tmp/logcat.txt" >"$dest/hal-log.txt" || true

# Static camera metadata only; the service event history names other apps.
adb shell dumpsys media.camera | tr -d '\r' |
    awk '/^== / { skip = /Camera service events/ } !skip' |
    grep -v 'Client package' >"$dest/dumpsys-media.camera.txt" || true

echo "results: $dest"
grep -E '^(!!|  !!)' "$dest/report.txt" | head -20 || true
