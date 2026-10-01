#!/usr/bin/env python3
"""**不同格式混排**时逐行的光标 x（R33 用户特别点名的那一条）。

造一篇"引用 / 正文 / 列表 / 引用"的文档，然后用 `MOVE_HOME` + `DPAD_UP` 一行一行地把光标
挪到行首，每挪一次量一次光标的 x：**引用行要落在 16dp 内缩处（真机 x=120）**，
正文与列表行贴列左边（真机 x=77）。

用法：python3 tools/dev_quote_mixed.py [--out 目录] [--day YYYY-MM-DD]
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

MOVE_HOME = 122
DPAD_UP = 19


def caret():
    """找光标 —— 直接用 `dev_format_rules.caret`（颜色探测 + 帧差兜底），返回 (x0, x1, y0, y1)。

    只用它的 x。帧差兜底是必须的：深色主题下光标是浅色，"饱和色竖直细条"那条颜色判据不成立。
    """
    return R.caret()


def key(code, wait=1.4):
    R.sh("shell", "input", "keyevent", str(code))
    time.sleep(wait)


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

    R.reset()
    # 引用 Q1 → 回车（新行正文）→ 正文 B1 → 回车 → 列表 L1 → 回车（结束列表）→ 引用 Q2
    R.tap("引用"); time.sleep(1.0)
    R.sh("shell", "input", "text", "Q1"); R.wait_text("Q1")
    key(66)
    R.sh("shell", "input", "text", "B1"); R.wait_text("Q1\nB1")
    key(66)
    R.tap("列表"); time.sleep(1.0)
    R.sh("shell", "input", "text", "L1"); R.wait_text("Q1\nB1\n• L1")
    key(66)                       # 空条目回车 → 结束列表
    R.tap("引用"); time.sleep(1.0)
    R.sh("shell", "input", "text", "Q2"); R.wait_text("Q1\nB1\n• L1\nQ2")
    time.sleep(0.8)
    R.sh("shell", "screencap", "-p", "/sdcard/e.png")
    R.sh("pull", "/sdcard/e.png", f"{OUT}/r33-device-mixed-caret.png")
    text = R.editor_text()
    print(f"文档 = {text!r}")
    if text != "Q1\nB1\n• L1\nQ2":
        print("❌ 文档没造出来，后面的量测没有意义")
        return 1

    # 末行（引用 Q2 的文字之后）→ 每往上一行都先 `MOVE_HOME` 再量（DPAD_UP 会**保持列**，
    # 不先归位的话量到的是"上一行里离原列最近的那个位置"，不是行首）
    steps = [
        ("引用 Q2 的文字之后", None),
        ("引用 Q2 行首", [MOVE_HOME]),
        ("列表 L1 行首", [DPAD_UP, MOVE_HOME]),
        ("正文 B1 行首", [DPAD_UP, MOVE_HOME]),
        ("引用 Q1 行首", [DPAD_UP, MOVE_HOME]),
    ]
    expected = {
        "引用 Q2 的文字之后": None,
        "引用 Q2 行首": 120,
        "列表 L1 行首": 77,
        "正文 B1 行首": 77,
        "引用 Q1 行首": 120,
    }
    bad = 0
    for label, keys in steps:
        for k in keys or []:
            key(k)
        c = caret()
        want = expected[label]
        if c is None:
            print(f"❌ {label}：找不到光标（期望 x≈{want}）")
            bad += 1
            continue
        if want is None:
            print(f"✅ {label}：x={c[0]}（光标可见即可）")
        elif abs(c[0] - want) <= 4:
            print(f"✅ {label}：x={c[0]}（期望 ≈{want}）")
        else:
            print(f"❌ {label}：x={c[0]}（期望 ≈{want}）")
            bad += 1
    print(f"\n{'全部通过' if not bad else f'{bad} 条不通过'}（证据 {OUT}/r33-device-mixed-caret.png）")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
