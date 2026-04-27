#!/usr/bin/env bash
# Reset GPU frame stats, wait for manual interaction, then print gfx summary for CallMate.
# Usage: ./script/profile_gfx.sh [seconds_wait]
set -euo pipefail
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=com.vaca.callmate
WAIT="${1:-12}"

if ! "$ADB" devices | awk 'NR>1 && $2=="device"{found=1} END{exit found?0:1}'; then
  echo "No device in 'adb devices'. Connect USB debugging." >&2
  exit 1
fi

"$ADB" shell am force-stop "$PKG" || true
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
"$ADB" shell dumpsys gfxinfo "$PKG" reset
echo "Profiling: use the app for ${WAIT}s (scroll tabs, open lists)…"
sleep "$WAIT"
echo "--- gfxinfo (head) ---"
"$ADB" shell dumpsys gfxinfo "$PKG" | head -55
