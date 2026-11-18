#!/usr/bin/env python3
"""P2-10：文档与构建配置的“事实”校验。

用法：在仓库根目录执行
    python3 tools/verify_docs.py

检查项（全部是静态、无网络的）：
  1. README / TODO 里的测试总数一致；若本地有 test-results，则与实际用例数一致；
  2. TODO 里不再有未完成的 P1（P1-8 / P1-10 是明确不做的 [—]）；
  3. 设计文档不再写“日记页尚未开工”；
  4. R36 记录存在；
  5. release 已开启 R8/资源压缩，且 proguard 规则文件存在。

CI 在 testDebugUnitTest 之后跑它；本地没有测试结果时只做静态检查。
"""

from __future__ import annotations

import glob
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
failures: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def actual_test_count() -> int | None:
    files = glob.glob(str(ROOT / "app/build/test-results/testDebugUnitTest/TEST-*.xml"))
    if not files:
        return None
    total = 0
    for path in files:
        root = ET.parse(path).getroot()
        total += int(root.get("tests", 0))
    return total


def documented_counts() -> set[int]:
    todo = read("TODO.md").split("\n## 1.", 1)[0]
    text = read("README.md") + "\n" + todo
    # 只认 README 与 TODO 当前摘要；后续章节保留带日期的历史测试记录。
    return {int(m) for m in re.findall(r"(\d{3,5})\s*(?:条|个)\s*JVM\s*(?:单测|测试)", text)}


def check_test_counts() -> None:
    counts = documented_counts()
    if not counts:
        fail("README/TODO 找不到“N 条 JVM 测试”的计数")
        return
    actual = actual_test_count()
    if actual is not None:
        if counts != {actual}:
            fail(f"文档测试计数 {sorted(counts)} 与实际 {actual} 不一致")
    elif len(counts) != 1:
        fail(f"文档里的测试计数不一致：{sorted(counts)}")


def check_p1_complete() -> None:
    todo = read("TODO.md")
    open_p1 = re.findall(r"^### \[ \] P1-\d+", todo, flags=re.MULTILINE)
    if open_p1:
        fail(f"TODO 还有未完成的 P1：{open_p1}")
    for closed in ("P1-8", "P1-10"):
        if f"### [—] {closed}" not in todo:
            fail(f"{closed} 应当是明确的 [—]（不做）")


def check_design_docs() -> None:
    if "日记页尚未开工" in read("docs/android-design/README.md"):
        fail("docs/android-design/README.md 仍写“日记页尚未开工”")
    if "R36" not in read("docs/migration-progress.md"):
        fail("docs/migration-progress.md 缺少 R36 记录")


def check_release_config() -> None:
    gradle = read("app/build.gradle.kts")
    if "isMinifyEnabled = true" not in gradle:
        fail("release 没有开启 isMinifyEnabled = true")
    if "isShrinkResources = true" not in gradle:
        fail("release 没有开启 isShrinkResources = true")
    if not (ROOT / "app/proguard-rules.pro").is_file():
        fail("缺少 app/proguard-rules.pro")
    if not (ROOT / "version.properties").is_file():
        fail("缺少 version.properties")


def main() -> int:
    check_test_counts()
    check_p1_complete()
    check_design_docs()
    check_release_config()
    if failures:
        print("❌ 文档 / 配置校验失败：")
        for item in failures:
            print("  -", item)
        return 1
    print("✅ 文档 / 配置校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
