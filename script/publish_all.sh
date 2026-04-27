#!/usr/bin/env bash
# 依次上传 MCU 固件与 Android APK（均走 /echocard/api/firmware/upload）
# 用法:
#   ./publish_all.sh <mcu.bin> <mcu_device> <mcu_version_semver> <apk_path>
# 示例:
#   ./publish_all.sh ../mcu/build/main.bin callmate-sf32lb52j 1.0.3 app/build/outputs/apk/release/app-release.apk
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FW_ROOT="$(cd "$ROOT/../../firmware_server" 2>/dev/null && pwd)" || FW_ROOT=""

if [[ -z "${BASE_URL:-}" ]]; then
  echo "❌ 请设置环境变量 BASE_URL（固件服务器地址，例如 http://your-server）" >&2
  exit 1
fi
if [[ -z "${UPLOAD_TOKEN:-}" ]]; then
  echo "❌ 请设置环境变量 UPLOAD_TOKEN（固件上传认证 token）" >&2
  exit 1
fi
UPLOAD_URL="${BASE_URL}/echocard/api/firmware/upload"

if [[ $# -lt 4 ]]; then
  echo "用法: $0 <mcu.bin> <mcu_device> <mcu_version> <apk_path>" >&2
  exit 1
fi

MCU_BIN="$1"
MCU_DEVICE="$2"
MCU_VERSION="$3"
APK="$4"

if [[ ! -f "$MCU_BIN" ]]; then
  echo "MCU 文件不存在: $MCU_BIN" >&2
  exit 1
fi
if [[ ! -f "$APK" ]]; then
  echo "APK 不存在: $APK" >&2
  exit 1
fi

VERSION_CODE="$(grep -E '^\s*versionCode\s*=' "$ROOT/app/build.gradle.kts" | head -1 | tr -cd '0-9')"

upload() {
  local file="$1" device="$2" version="$3" label="$4"
  echo "=== 上传 $label ==="
  echo "  file=$file device=$device version=$version"
  RESP=$(curl -s -w "\n%{http_code}" -X POST \
    -H "Authorization: Bearer ${UPLOAD_TOKEN}" \
    -F "file=@$file" \
    -F "device=$device" \
    -F "version=$version" \
    "$UPLOAD_URL")
  HTTP_BODY=$(echo "$RESP" | sed '$d')
  HTTP_CODE=$(echo "$RESP" | tail -n 1)
  if [[ "$HTTP_CODE" != "200" ]]; then
    echo "❌ 失败 HTTP $HTTP_CODE" >&2
    echo "$HTTP_BODY" >&2
    exit 1
  fi
  echo "✅ OK"
  echo "$HTTP_BODY" | python3 -m json.tool 2>/dev/null || echo "$HTTP_BODY"
}

upload "$MCU_BIN" "$MCU_DEVICE" "$MCU_VERSION" "MCU 固件"
upload "$APK" "callmate-android" "$VERSION_CODE" "Android APK"

if [[ -n "$FW_ROOT" && -f "$FW_ROOT/query_firmware.sh" ]]; then
  echo "提示: 可用 $FW_ROOT/query_firmware.sh 验证 list/latest"
fi
