#!/usr/bin/env bash
# 生成 CallMate release keystore（RSA 2048，约 27 年有效）
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${SCRIPT_DIR}/release.keystore"
if [[ -f "$OUT" ]]; then
  echo "已存在: $OUT（删除后再运行以重新生成）" >&2
  exit 1
fi
if [[ -z "${KEYSTORE_PASSWORD:-}" ]]; then
  echo "请设置环境变量 KEYSTORE_PASSWORD" >&2
  exit 1
fi
keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias callmate \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=CallMate, OU=Mobile, O=Vaca, L=Unknown, ST=Unknown, C=CN"
echo "已生成: $OUT"
