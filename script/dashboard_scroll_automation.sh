#!/usr/bin/env bash
# 自动滑动测试 + gfxinfo 采样与简要分析（需 USB 调试设备已连接）
# 用法：在 CallMateAndroid 目录执行
#   chmod +x script/dashboard_scroll_automation.sh && ./script/dashboard_scroll_automation.sh

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=com.vaca.callmate
TEST_CLASS=com.vaca.callmate.DashboardScrollAutomationTest

if ! "$ADB" devices | awk 'NR>1 && $2=="device"{found=1} END{exit found?0:1}'; then
  echo "错误：未检测到已连接设备（adb devices）。" >&2
  exit 1
fi

echo "=== 1) 重置 GPU 帧统计 ==="
"$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null || true

echo "=== 2) 运行仪器测试：$TEST_CLASS ==="
cd "$ROOT"
set +e
# 去掉 --no-daemon 可复用 Gradle Daemon，二次运行明显更快
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
  2>&1 | tee /tmp/callmate_dashboard_scroll_test.log
GRADLE_EXIT=$?
set -e

echo ""
echo "=== 3) Logcat 性能日志（CallMateScrollPerf）==="
"$ADB" logcat -d -s "$PKG" CallMateScrollPerf:I 2>/dev/null | tail -20 || true

echo ""
echo "=== 4) gfxinfo 摘要（测试后）==="
GFX_OUT=$("$ADB" shell dumpsys gfxinfo "$PKG" 2>/dev/null || true)
echo "$GFX_OUT" | head -45

echo ""
echo "=== 5) 自动提取关键行 ==="
echo "$GFX_OUT" | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Slow UI thread|Frame deadline missed|Number Missed Vsync" || true

echo ""
if [[ "$GRADLE_EXIT" -eq 0 ]]; then
  echo "Gradle / 仪器测试：成功"
else
  echo "Gradle / 仪器测试：失败 (exit $GRADLE_EXIT)，完整日志：/tmp/callmate_dashboard_scroll_test.log" >&2
fi
exit "$GRADLE_EXIT"
