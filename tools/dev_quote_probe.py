#!/usr/bin/env python3
"""引用相关的真机端到端回归（R33）：光标可见性 / 光标内缩 / 回车结束引用。

坐标全部来自 uiautomator 的语义树（`tools/dev_ui.py`）；光标按**颜色**定位
（强调色的竖直细条，`tools/dev_caret.py --probe`）。

用法：python3 tools/dev_quote_probe.py [--out 目录] [--day YYYY-MM-DD]

A 空文档点引用 → 光标在内缩处；打字之后光标仍然看得见（R33 之前是"被引用底盖住"）
B 有内容的引用行回车 → 新行回到正文（R33 之前是"引用延续"）
C 有内容时末尾的空引用行 → 光标也在内缩处（R33 之前停在列最左边）
D 空的引用行回车 → 引用结束、光标回到列最左边
"""
import argparse
import os
import shutil
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import dev_caret  # noqa: E402
import dev_format_rules as R  # noqa: E402
import dev_ui  # noqa: E402

ROOT = "/Users/cov/JustGDiary"
OUT = f"{ROOT}/docs/ui-audit/r33"
DAY = subprocess.run([dev_ui.ADB, "shell", "date", "+%Y-%m-%d"], capture_output=True, text=True).stdout.strip()
results = []


def caret():
    """找光标 —— 直接用 `dev_format_rules.caret`（颜色探测 + 帧差兜底）。

    返回 `(x0, x1, y0, y1)`（**注意不是** `dev_caret.probe_box` 的 `(x0, y0, x1, y1)`）。
    帧差兜底是必须的：深色主题下光标是浅色，"饱和色竖直细条"那条颜色判据不成立
    （实测：系统到点自动切深色之后，只靠颜色判据会一路报"找不到光标"）。
    """
    return R.caret()


def caret_visibility(tries=3):
    """连问几次"光标画出来了吗"（`R.caret` 内部已经跨过闪烁周期采样）。

    画在引用底**下面**的光标（R33 之前那个缺陷）任何一次都找不到 —— 这正是要抓的。
    """
    hits = [c for c in (R.caret() for _ in range(tries)) if c]
    return hits


def shot(name, want_caret=False, tries=10):
    """存证据截图。

    `want_caret` 时反复抓，直到这一张里**真的有光标** —— 光标是闪烁的，随手一张可能正好抓在
    熄灭的那半拍。判据两套：浅色主题用颜色（`dev_caret --probe`），深色主题光标是浅色、
    颜色判据不成立，改用**与上一张的帧差**（闪一下就是差异）。
    """
    path = f"{OUT}/r33-device-{name}.png"
    bounds = R.editor_bounds()
    region = None
    if bounds:
        region = (bounds[0], bounds[1], bounds[2], bounds[3] + 200)
        region_arg = f"--region={region[0]},{region[1]},{region[2]},{region[3]}"
    previous = None
    for _ in range(tries if (want_caret and region) else 1):
        R.sh("shell", "screencap", "-p", "/sdcard/e.png")
        R.sh("pull", "/sdcard/e.png", path)
        if not want_caret or not region:
            return
        if dev_caret.probe_box(path, region):
            return
        current = f"/tmp/shot-{name}.png"
        shutil.copy(path, current)
        if previous is not None:
            out = subprocess.run(
                [sys.executable, f"{ROOT}/tools/dev_caret.py", previous, current, region_arg],
                capture_output=True, text=True,
            ).stdout.strip()
            if out.startswith("caret"):
                return
        previous = current
        time.sleep(0.25)


def check(what, expect, actual, ok):
    results.append((what, expect, actual, ok))
    print(f"  {'✅' if ok else '❌'} {what}: 期望 {expect}，实测 {actual}")


def type_text(s, want):
    R.sh("shell", "input", "text", s)
    R.wait_text(want)


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

    print("\nA · 引用段里打字：光标必须看得见")
    R.reset()
    R.tap("引用")
    time.sleep(1.2)
    c = caret()
    check("空文档点引用：光标在内缩处", "x≈120", f"x={c[0]}" if c else "找不到",
          bool(c) and abs(c[0] - 120) <= 4)
    type_text("A", "A")
    time.sleep(0.6)
    hits = caret_visibility()
    check("引用里打上字之后光标仍然看得见", "≥1/3 次", f"{len(hits)}/3 次", len(hits) >= 1)
    if hits:
        check("光标落在字的右边（不在列最左）", "x>120", f"x={hits[0][0]}", hits[0][0] > 120)
    shot("quote-caret-typed", want_caret=True)

    print("\nB · 有内容的引用行回车 = 结束引用")
    rows_before = R.quote_box_rows()
    R.sh("shell", "input", "keyevent", "66")
    time.sleep(2.0)
    t = R.editor_text()
    after = caret()
    shot("quote-enter", want_caret=True)
    check("回车之后文本是 'A\\n'", repr("A\n"), repr(t), t == "A\n")
    check("回车之后光标贴列左（引用已结束）", "x≈77", f"x={after[0]}" if after else "找不到",
          bool(after) and abs(after[0] - 77) <= 4)
    rows_after = R.quote_box_rows()
    check("引用底不再跟着新行长出来", f"≈回车前的 {rows_before} 行", f"{rows_after} 行",
          rows_after <= rows_before + 10)

    print("\nC · 有内容时末尾的空引用行：光标也要在内缩处")
    R.reset()
    type_text("AA", "AA")
    R.sh("shell", "input", "keyevent", "66")
    time.sleep(1.5)
    R.tap("引用")
    time.sleep(1.2)
    c = caret()
    check("末尾空引用行的光标在内缩处（曾经是 x=77）", "x≈120", f"x={c[0]}" if c else "找不到",
          bool(c) and abs(c[0] - 120) <= 4)
    shot("trailing-quote-caret", want_caret=True)
    type_text("B", "AA\nB")
    time.sleep(0.6)
    hits = caret_visibility()
    check("在空引用行上打字后光标看得见", "≥1/3 次", f"{len(hits)}/3 次", len(hits) >= 1)
    shot("trailing-quote-typed", want_caret=True)

    print("\nD · 空引用行回车 = 取消本次引用（光标重置）")
    R.reset()
    type_text("AA", "AA")
    R.sh("shell", "input", "keyevent", "66")
    time.sleep(1.5)
    R.tap("引用")
    time.sleep(1.2)
    rows_before = R.quote_box_rows()
    R.sh("shell", "input", "keyevent", "66")
    time.sleep(2.0)
    rows_after = R.quote_box_rows()
    c = caret()
    shot("empty-quote-enter", want_caret=True)
    check("空引用行回车：引用底消失", "回车后有底=0",
          f"回车前 {rows_before} 行 / 后 {rows_after} 行", rows_before > 0 and rows_after == 0)
    check("空引用行回车：行数不变、光标回到列左", "x≈77 / 文本 'AA\\n'",
          f"x={c[0] if c else '找不到'} / {R.editor_text()!r}",
          bool(c) and abs(c[0] - 77) <= 4 and R.editor_text() == "AA\n")

    print("\n================ 汇总 ================")
    bad = [r for r in results if not r[3]]
    for what, expect, actual, ok in results:
        print(f"{'✅' if ok else '❌'} {what}：期望 {expect}，实测 {actual}")
    print(f"\n{len(results) - len(bad)}/{len(results)} 通过（证据在 {OUT}）")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
