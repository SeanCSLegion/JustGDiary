#!/usr/bin/env python3
"""**在段落行首回车**之后，上面那个空行不该带着下一段的样式（R33 续，用户 2026-10-01）。

用户原话："如果在引用的段前输入回车换行，现在是让上一个空行也是引用格式。但是我希望的是
那个空行应该是没有引用格式的。因为如果是引用行回车换行之后会自动取消引用，所以，
理论上是没有引用的空行的。"

对照表（`MOVE_HOME` 把光标放到段首 → `ENTER`）：

| 格式 | 上面那个空行 | 原来那一行 |
| --- | --- | --- |
| 引用 | **正文**（不是引用） | 仍然是引用 |
| 大标题 | 大标题（规则 3：回车延续） | 仍然是**大标题**（曾经被降级成正文） |
| 列表 | 正文（没有标记缩进） | 仍然是列表项 |

用法：python3 tools/dev_quote_split.py [--out 目录] [--day YYYY-MM-DD]
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import dev_format_rules as R  # noqa: E402
import dev_ui  # noqa: E402

ROOT = "/Users/cov/JustGDiary"
OUT = f"{ROOT}/docs/ui-audit/r33"
DAY = subprocess.run([dev_ui.ADB, "shell", "date", "+%Y-%m-%d"], capture_output=True, text=True).stdout.strip()

MOVE_HOME, DPAD_UP, ENTER = 122, 19, 66
# 真机（MEIZU 21 / 1080p）实测的光标高度基线：正文 65px、大标题 113px、引用 56px
TITLE_CARET_MIN = 95
results = []


def caret():
    """找光标 —— 直接用 `dev_format_rules.caret`（颜色探测 + 帧差兜底），返回 (x0, x1, y0, y1)。

    帧差兜底是必须的：深色主题下光标是浅色，"饱和色竖直细条"那条颜色判据不成立
    （实测：系统到点自动切深色之后，只靠颜色判据会一路报"找不到光标"）。
    """
    return R.caret()


def caret_height(c):
    """`dev_format_rules.caret` 返回 (x0, x1, y0, y1) —— 高度是 y1 - y0。"""
    return c[3] - c[2] + 1


def key(code, wait=1.4):
    R.sh("shell", "input", "keyevent", str(code))
    time.sleep(wait)


def check(what, expect, actual, ok):
    results.append((what, expect, actual, ok))
    print(f"  {'✅' if ok else '❌'} {what}: 期望 {expect}，实测 {actual}")


def shot(name):
    R.sh("shell", "screencap", "-p", "/sdcard/e.png")
    R.sh("pull", "/sdcard/e.png", f"{OUT}/r33-device-{name}.png")


def main():
    global OUT, DAY
    p = argparse.ArgumentParser()
    p.add_argument("--out", default=OUT)
    p.add_argument("--day", default=DAY)
    a = p.parse_args()
    OUT = a.out
    DAY = a.day
    os.makedirs(OUT, exist_ok=True)
    R.OUT = OUT
    R.DAY = DAY

    print("\nA · 引用段行首回车：上面那个空行不是引用")
    R.reset()
    R.tap("引用"); time.sleep(1.0)
    R.sh("shell", "input", "text", "Q"); R.wait_text("Q")
    key(MOVE_HOME)
    key(ENTER, wait=2.0)
    text = R.editor_text()
    shot("quote-split")
    check("文本是 '\\nQ'（空行 + 引用文字）", repr("\nQ"), repr(text), text == "\nQ")

    c = caret()
    check("引用文字那一行仍然是引用（光标在内缩处）", "x≈120", f"x={c[0]}" if c else "找不到",
          bool(c) and abs(c[0] - 120) <= 4)

    key(DPAD_UP); key(MOVE_HOME)
    c = caret()
    check("上面那个空行的光标贴列左（不是引用）", "x≈77", f"x={c[0]}" if c else "找不到",
          bool(c) and abs(c[0] - 77) <= 4)

    rows = R.quote_box_rows()
    check("引用底只盖住有文字的那一行", "60~100 行像素", f"{rows} 行", 60 <= rows <= 100)

    print("\nB · 大标题段行首回车：两条都是大标题")
    R.reset()
    R.tap("段落样式"); time.sleep(0.8); R.tap("大标题"); time.sleep(1.0)
    R.sh("shell", "input", "text", "T"); R.wait_text("T")
    key(MOVE_HOME)
    key(ENTER, wait=2.0)
    text = R.editor_text()
    shot("title-split")
    check("文本是 '\\nT'", repr("\nT"), repr(text), text == "\nT")
    c = caret()
    check("标题文字那一行**没有被降级**（光标还是标题高度）", f"h>{TITLE_CARET_MIN}",
          f"h={caret_height(c)}" if c else "找不到",
          bool(c) and caret_height(c) > TITLE_CARET_MIN)
    key(DPAD_UP); key(MOVE_HOME)
    c = caret()
    check("上面那个空行也是大标题（规则 3：回车延续）", f"h>{TITLE_CARET_MIN}",
          f"h={caret_height(c)}" if c else "找不到",
          bool(c) and caret_height(c) > TITLE_CARET_MIN)

    print("\nC · 列表项行首回车：上面那个空行不缩进")
    R.reset()
    R.tap("列表"); time.sleep(1.0)
    R.sh("shell", "input", "text", "L"); R.wait_text("• L")
    key(MOVE_HOME)
    key(ENTER, wait=2.0)
    text = R.editor_text()
    shot("list-split")
    check("文本是 '\\n• L'", repr("\n• L"), repr(text), text == "\n• L")
    key(DPAD_UP); key(MOVE_HOME)
    c = caret()
    check("空行的光标贴列左（没有被列表缩进漏过去）", "x≈77", f"x={c[0]}" if c else "找不到",
          bool(c) and abs(c[0] - 77) <= 4)

    print("\n================ 汇总 ================")
    bad = [r for r in results if not r[3]]
    for what, expect, actual, ok in results:
        print(f"{'✅' if ok else '❌'} {what}：期望 {expect}，实测 {actual}")
    print(f"\n{len(results) - len(bad)}/{len(results)} 通过（证据 {OUT}）")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
