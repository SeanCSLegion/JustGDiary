#!/usr/bin/env bash
# 跨端互操作验证：Android 写出的备份包能否被 **iOS 真实的代码** 读取。
#
# 为什么需要它：备份包的字节格式（PKZIP + manifest 字段名 + settingsJson 键名）
# 是"严格保真、可双向互导"这一要求里最容易出错的部分，而本机没有 Android 设备、
# 也不方便跑 iOS App。但 iOS 的 `ZipArchive.swift` 只依赖 Foundation + Compression，
# 可以直接编译执行 —— 于是可以拿它当**权威读取端**来验证 Android 的产物。
#
# 用法：tools/verify_backup_interop.sh
# （样本包由 ZipArchiveTest 的一个用例产出；脚本会在缺失时用 --rerun 强制它执行 ——
#   否则 Gradle 的构建缓存会让测试命中缓存、根本不跑，样本就不会生成。）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IOS_REPO="${IOS_REPO:-$(cd "$ROOT/../JustADiary" && pwd)}"
SAMPLE="$ROOT/app/build/tmp/cross-end/export.jdiary"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

if [[ ! -f "$SAMPLE" ]]; then
  echo "样本包不存在，先用 --rerun 强制跑生成样本的单测（绕过构建缓存）"
  (cd "$ROOT" && ./gradlew testDebugUnitTest --tests '*ZipArchiveTest*' --rerun >/dev/null)
fi

if [[ ! -f "$SAMPLE" ]]; then
  echo "仍找不到样本包：$SAMPLE"
  exit 1
fi
if [[ ! -f "$IOS_REPO/JustDiary/Services/ZipArchive.swift" ]]; then
  echo "找不到 iOS 仓库的 ZipArchive.swift（IOS_REPO=$IOS_REPO）"
  exit 1
fi

echo "编译 iOS 的 ZipArchive.swift（只读引用，不修改 iOS 仓库）"
# Swift 要求多文件编译时顶层语句位于 main.swift，因此先把验证器复制成 main.swift
cp "$ROOT/tools/cross_end_extract.swift" "$WORK/main.swift"
swiftc -O \
  "$IOS_REPO/JustDiary/Services/ZipArchive.swift" \
  "$WORK/main.swift" \
  -o "$WORK/cross_end_extract"

echo
"$WORK/cross_end_extract" "$SAMPLE"
