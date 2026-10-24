#!/usr/bin/env bash
# 发布一个新版本：改版本号 → 编译 → 归档 APK → 提交 → 打 tag →（有远端则）推送
# 用法：tools/release.sh 1.8.0 "一句话说明本次主要改动"
set -euo pipefail

VERSION="${1:?用法: tools/release.sh <版本号 如 1.8.0> <提交说明>}"
MSG="${2:?缺少提交说明}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE_BIN="${GRADLE_BIN:-/opt/gradle-8.7/bin/gradle}"

CODE=$(grep -oP 'versionCode = \K[0-9]+' "$GRADLE_FILE")
NEW_CODE=$((CODE + 1))
sed -i "s/versionCode = $CODE/versionCode = $NEW_CODE/" "$GRADLE_FILE"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" "$GRADLE_FILE"

echo "==> 编译 v$VERSION (versionCode $NEW_CODE)"
cd "$ROOT"
"$GRADLE_BIN" assembleRelease --no-daemon

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
OUT="/workspace/册页Folior-v$VERSION.apk"
cp "$APK" "$OUT"
echo "==> APK: $OUT"

git add -A
git commit -m "v$VERSION: $MSG"
git tag -a "v$VERSION" -m "v$VERSION — $MSG"

if git remote get-url origin >/dev/null 2>&1; then
  echo "==> 推送到远端"
  git push origin HEAD --tags
else
  echo "==> 未配置远端，已本地提交并打 tag。配置后执行：git push -u origin HEAD --tags"
fi
