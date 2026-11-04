#!/usr/bin/env bash
# Capture a visible rendering defect without restarting or interacting with the app.
# Screenshots and the UI hierarchy contain private message text. Nothing is uploaded.
set -euo pipefail
umask 077

usage() {
    echo "Usage: $0 [-s SERIAL] [LABEL]" >&2
    echo "Keep the affected screen open. Output is stored in a private temporary directory." >&2
}
serial=""
label="before-scroll"
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s) [[ $# -ge 2 ]] || { usage; exit 2; }; serial="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        -*) usage; exit 2 ;;
        *) label="$1"; shift ;;
    esac
done
[[ "$label" =~ ^[a-zA-Z0-9-]+$ ]] || { echo "Use letters, digits and hyphens for LABEL." >&2; exit 2; }
adb_bin="${ADB:-adb}"
if ! command -v "$adb_bin" >/dev/null 2>&1; then
    candidate="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
    [[ -x "$candidate" ]] || { echo "adb not found. Set ANDROID_HOME or ADB." >&2; exit 1; }
    adb_bin="$candidate"
fi
if [[ -z "$serial" ]]; then
    devices=()
    while IFS= read -r device; do
        [[ -n "$device" ]] && devices+=("$device")
    done < <("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [[ ${#devices[@]} -eq 1 ]] || { echo "Connect and authorize one device, or select it with -s SERIAL." >&2; exit 1; }
    serial="${devices[0]}"
fi
adb=("$adb_bin" -s "$serial")
[[ "$("${adb[@]}" get-state)" == "device" ]] || { echo "Device not ready." >&2; exit 1; }
out="$(mktemp -d "${TMPDIR:-/tmp}/pocketpi-overlap-${label}.XXXXXX")"
remote="/data/local/tmp/pocketpi-overlap-$$.xml"
cleanup() {
    if ! "${adb[@]}" shell rm -f "$remote" >/dev/null 2>&1; then
        printf 'Device cleanup failed. After reconnecting, remove this temporary file: %s\n' "$remote" >&2
    fi
}
trap cleanup EXIT
printf 'Private capture: %s\nNo data is uploaded. Inspect files before sharing.\n' "$out"

# Capture pixels BEFORE attaching the accessibility client; hierarchy collection can itself
# trigger a redraw. The second image makes that side effect observable.
"${adb[@]}" exec-out screencap -p > "$out/01-before-hierarchy.png"
{
    "${adb[@]}" shell getprop ro.product.model
    "${adb[@]}" shell getprop ro.build.fingerprint
    "${adb[@]}" shell wm size
    "${adb[@]}" shell wm density
    "${adb[@]}" shell settings get system font_scale
    "${adb[@]}" shell dumpsys package de.robinrehbein.pocketpi | grep -E 'versionCode=|versionName='
} > "$out/device.txt" 2>&1 || true
"${adb[@]}" shell dumpsys window windows > "$out/windows.txt" 2>&1 || true
"${adb[@]}" shell dumpsys SurfaceFlinger --list > "$out/surfaces.txt" 2>&1 || true
"${adb[@]}" shell dumpsys gfxinfo de.robinrehbein.pocketpi > "$out/graphics.txt" 2>&1 || true
if "${adb[@]}" shell uiautomator dump "$remote" > "$out/hierarchy-status.txt" 2>&1; then
    "${adb[@]}" pull "$remote" "$out/hierarchy.xml" >> "$out/hierarchy-status.txt" 2>&1 || true
fi
"${adb[@]}" exec-out screencap -p > "$out/02-after-hierarchy.png"
printf '\nCapture complete: %s\n' "$out"
printf 'If safe, manually scroll a little and run again with label after-scroll.\n'
printf 'Do not restart or clear the app before capturing the original defect.\n'
