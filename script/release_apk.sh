#!/usr/bin/env bash
# 构建 release APK 并默认上传到固件服务（device=callmate-android，version=versionCode 字符串）
# 运行前会自动将 app/build.gradle.kts 中 versionCode +1，并按规则递增 versionName。
# SKIP_BUMP=1：不修改版本号（仅编译当前版本）。
# SKIP_UPLOAD=1：不上传（仅编译）。
# CLEAN=1：先 clean 再编译。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_KTS="${ROOT}/app/build.gradle.kts"
APK="${ROOT}/app/build/outputs/apk/release/app-release.apk"

if [[ "${SKIP_UPLOAD:-}" != "1" ]]; then
  if [[ -z "${BASE_URL:-}" ]]; then
    echo "❌ 请设置环境变量 BASE_URL（固件服务器地址，例如 http://your-server）" >&2
    exit 1
  fi
  if [[ -z "${UPLOAD_TOKEN:-}" ]]; then
    echo "❌ 请设置环境变量 UPLOAD_TOKEN（固件上传认证 token）" >&2
    exit 1
  fi
fi
UPLOAD_URL="${BASE_URL:-}/echocard/api/firmware/upload"

bump_versions_in_gradle() {
  python3 - "$GRADLE_KTS" <<'PY'
import re
import sys
path = sys.argv[1]

def bump_version_name(name: str) -> str:
    name = name.strip()
    parts = [p for p in name.split(".") if p != ""]
    if not parts:
        return "1.0.1"
    if len(parts) == 1:
        return f"{parts[0]}.0.1"
    if len(parts) == 2:
        return f"{parts[0]}.{parts[1]}.1"
    try:
        parts[-1] = str(int(parts[-1]) + 1)
    except ValueError:
        parts.append("1")
    return ".".join(parts)

with open(path, encoding="utf-8") as f:
    s = f.read()
m_code = re.search(r"\bversionCode\s*=\s*(\d+)", s)
m_name = re.search(r'\bversionName\s*=\s*"([^"]*)"', s)
if not m_code or not m_name:
    print("无法解析 versionCode / versionName", file=sys.stderr)
    sys.exit(1)
old_code = int(m_code.group(1))
old_name = m_name.group(1)
new_code = old_code + 1
new_name = bump_version_name(old_name)
s = re.sub(r"\bversionCode\s*=\s*\d+", f"versionCode = {new_code}", s, count=1)
s = re.sub(r'\bversionName\s*=\s*"[^"]*"', f'versionName = "{new_name}"', s, count=1)
with open(path, "w", encoding="utf-8") as f:
    f.write(s)
print(f"{old_code}->{new_code}", f'"{old_name}"->"{new_name}"')
PY
}

if [[ "${SKIP_BUMP:-}" != "1" ]]; then
  echo "=== bump versionCode & versionName ($GRADLE_KTS) ==="
  bump_versions_in_gradle
else
  echo "=== SKIP_BUMP=1，不修改版本号 ==="
fi

echo "=== assembleRelease ==="
if [[ "${CLEAN:-}" == "1" ]]; then
  ./gradlew :app:clean
fi
./gradlew :app:assembleRelease

if [[ ! -f "$APK" ]]; then
  echo "未找到 release APK: $APK" >&2
  echo "请检查 key/release_signing.properties 或 local.properties 签名配置" >&2
  exit 1
fi
echo "APK: $APK"

VERSION_CODE="$(grep -E '^\s*versionCode\s*=' "$GRADLE_KTS" | head -1 | tr -cd '0-9')"
if [[ -z "$VERSION_CODE" ]]; then
  echo "无法从 app/build.gradle.kts 解析 versionCode" >&2
  exit 1
fi
VERSION_NAME="$(grep -E '^\s*versionName\s*=' "$GRADLE_KTS" | head -1 | sed -E 's/.*versionName *= *"([^"]*)".*/\1/')"
echo "versionCode=${VERSION_CODE} versionName=${VERSION_NAME}"

if [[ "${SKIP_UPLOAD:-}" != "1" ]]; then
  # 避免全角括号与 $ 相邻时部分 bash + set -u 误解析为未绑定变量
  echo "=== upload APK (device=callmate-android, version=${VERSION_CODE}) ==="
  RESP=$(curl -s -w "\n%{http_code}" -X POST \
    -H "Authorization: Bearer ${UPLOAD_TOKEN}" \
    -F "file=@${APK}" \
    -F "device=callmate-android" \
    -F "version=${VERSION_CODE}" \
    "${UPLOAD_URL}")
  HTTP_BODY=$(echo "$RESP" | sed '$d')
  HTTP_CODE=$(echo "$RESP" | tail -n 1)
  if [[ "$HTTP_CODE" == "200" ]]; then
    echo "✅ 上传成功"
    echo "$HTTP_BODY" | python3 -m json.tool 2>/dev/null || echo "$HTTP_BODY"
  else
    echo "❌ 上传失败 HTTP $HTTP_CODE" >&2
    echo "$HTTP_BODY" >&2
    exit 1
  fi
else
  echo "跳过上传（SKIP_UPLOAD=1）。默认会上传。"
fi
