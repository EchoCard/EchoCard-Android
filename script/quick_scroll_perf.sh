#!/usr/bin/env bash
# 纯 adb：自动打开 CallMate → 滑动列表 → gfxinfo（不跑 Gradle）
# 用法：./script/quick_scroll_perf.sh [滑动次数，默认 25]
# 环境变量：SKIP_OPEN=1 不自动启动（你已手动打开）；OPEN_WAIT_SEC=3 启动后等待秒数
set -euo pipefail
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=com.vaca.callmate
N="${1:-25}"
OPEN_WAIT_SEC="${OPEN_WAIT_SEC:-2.5}"

if ! "$ADB" devices | awk 'NR>1 && $2=="device"{found=1} END{exit found?0:1}'; then
  echo "未检测到设备。" >&2
  exit 1
fi

if [[ -z "${SKIP_OPEN:-}" ]]; then
  echo "正在启动 ${PKG} …"
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
    "$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "${PKG}/.MainActivity" >/dev/null
  # 等首屏与 BLE 初始帧稳定
  sleep "$OPEN_WAIT_SEC"
fi

SIZE=$("$ADB" shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1)
W=${SIZE%x*}
H=${SIZE#*x}
# 屏幕中线，自下向上滑（与手指上滑列表一致）
X=$((W / 2))
Y1=$((H * 75 / 100))
Y2=$((H * 28 / 100))

echo "设备分辨率 ${W}x${H}，将执行 ${N} 次 input swipe（${X},${Y1}）->（${X},${Y2}）"
"$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null || true
for ((i = 1; i <= N; i++)); do
  "$ADB" shell input swipe "$X" "$Y1" "$X" "$Y2" 220
done

echo ""
echo "=== gfxinfo 摘要 ==="
"$ADB" shell dumpsys gfxinfo "$PKG" | head -40
echo ""
"$ADB" shell dumpsys gfxinfo "$PKG" | grep -E "Total frames rendered|Janky frames|90th percentile|Slow UI thread|Frame deadline missed" || true
