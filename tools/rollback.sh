#!/usr/bin/env bash
# 回滚到某个 tag 并重新编译（不改动当前工作区的历史，使用独立 worktree）
# 用法：tools/rollback.sh v1.6.1
set -euo pipefail

TAG="${1:?用法: tools/rollback.sh <tag 如 v1.6.1>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="/tmp/folior-rollback-$TAG"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE_BIN="${GRADLE_BIN:-/opt/gradle-8.7/bin/gradle}"

rm -rf "$DIR"
git -C "$ROOT" worktree add --detach "$DIR" "$TAG"

cd "$DIR"
"$GRADLE_BIN" assembleRelease --no-daemon

cp "$DIR/app/build/outputs/apk/release/app-release.apk" "/workspace/册页Folior-回滚-$TAG.apk"
echo "==> 回滚版本已编译：/workspace/册页Folior-回滚-$TAG.apk"
echo "==> 注意：如需把该版本恢复为当前开发线，执行 git revert / git reset 后另行提交"
