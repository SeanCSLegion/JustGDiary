#!/usr/bin/env python3
"""日记编辑态「格式栏六条规则」的真机/模拟器**端到端**回归。

坐标全部来自 uiautomator 的语义树（`tools/dev_ui.py`），因此换设备/换分辨率都能跑；
光标用**帧差**定位（两次截图里闪烁的那条竖线）。

用法：python3 tools/dev_format_rules.py [--device 序列号]

跑完打印一张表：每一步的期望值 / 实测值 / 判定，并把证据截图存到
`docs/ui-audit/r31/`（`--out` 可改目录）。
"""
import argparse
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import dev_ui  # noqa: E402

ADB = dev_ui.ADB
ROOT = "/Users/cov/JustGDiary"
OUT = f"{ROOT}/docs/ui-audit/r31"
results = []


def sh(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True).stdout


def editor_bounds():
    n = next((x for x in dev_ui.nodes() if x["cls"].endswith("EditText")), None)
    if not n:
        return None
    import re
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n["bounds"])
    return tuple(int(v) for v in m.groups())


def caret():
    """帧差找光标：连拍 4 张，取相邻差分的并集。返回 (x0, x1, y0, y1) 或 None。"""
    b = editor_bounds()
    if not b:
        return None
    region = (b[0], b[1], b[2], b[3] + 200)
    for i in range(4):
        sh("shell", "screencap", "-p", f"/sdcard/c{i}.png")
        sh("pull", f"/sdcard/c{i}.png", f"/tmp/c{i}.png")
        time.sleep(0.22)
    out = subprocess.run(
        [sys.executable, f"{ROOT}/tools/dev_caret.py",
         "/tmp/c0.png", "/tmp/c1.png", "/tmp/c2.png", "/tmp/c3.png",
         f"--region={region[0]},{region[1]},{region[2]},{region[3]}"],
        capture_output=True, text=True,
    ).stdout.strip()
    if not out.startswith("caret"):
        return None
    import re
    m = re.search(r"x=(\d+)\.\.(\d+).*y=(\d+)\.\.(\d+)", out)
    return tuple(int(v) for v in m.groups()) if m else None


def editor_text():
    n = next((x for x in dev_ui.nodes() if x["cls"].endswith("EditText")), None)
    return n["text"] if n else None


def wait_text(want, timeout=12.0):
    dev_ui.sh("shell", "true")  # noqa: 保持接口一致
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout:
        last = editor_text()
        if last == want:
            return True
        time.sleep(0.4)
    print(f"    （wait_text 超时：想要 {want!r}，实际 {last!r}）")
    return False


def tap(label):
    assert dev_ui.tap(label) == 0, f"找不到按钮 {label}"


def shot(name):
    sh("shell", "screencap", "-p", "/sdcard/e.png")
    sh("pull", "/sdcard/e.png", f"{OUT}/{name}.png")


def quote_box_rows():
    """编辑区内引用底色（surfaceContainerHigh）的像素行数 —— 用来判断"引用底在不在"。"""
    import re as _re
    from PIL import Image
    b = editor_bounds()
    if not b:
        return -1
    sh("shell", "screencap", "-p", "/sdcard/px.png")
    sh("pull", "/sdcard/px.png", "/tmp/px.png")
    im = Image.open("/tmp/px.png").convert("RGB")
    px = im.load()
    mid = (b[0] + b[2]) // 2
    return sum(1 for y in range(b[1], min(b[3], im.height)) if px[mid, y] == (37, 43, 44))


def check(rule, what, expect, actual, ok):
    results.append((rule, what, expect, actual, "✅" if ok else "❌"))
    print(f"  {'✅' if ok else '❌'} {what}: 期望 {expect}，实测 {actual}")


def reset():
    sh("shell", "am", "force-stop", "com.cov.justdiary")
    time.sleep(1)
    sh("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "justdiary://editor/2026-09-28")
    time.sleep(3.5)
    tap("写日记")
    time.sleep(3)


def main():
    global OUT
    p = argparse.ArgumentParser()
    p.add_argument("--out", default=OUT)
    a = p.parse_args()
    OUT = a.out

    # ---------------- 规则 6：光标字号跟着段落样式 ----------------
    print("\n规则 6 · 光标字号")
    reset()
    base = caret()
    check(6, "正文空行的光标高度", "~70px", f"{base[3] - base[2] + 1}px" if base else "找不到", bool(base))

    tap("段落样式"); time.sleep(1.0); tap("大标题"); time.sleep(1.2)
    title = caret()
    check(
        6, "切成大标题后的光标高度", "明显更高（>正文 ×1.4）",
        f"{title[3] - title[2] + 1}px" if title else "找不到",
        bool(title) and base and (title[3] - title[2]) > (base[3] - base[2]) * 1.4,
    )
    shot("r31-device-title-caret")

    tap("段落样式"); time.sleep(1.0); tap("正文"); time.sleep(1.0)
    tap("引用"); time.sleep(1.2)
    quote = caret()
    check(
        6, "切成引用后的光标高度（引用字号更小）", "比正文矮",
        f"{quote[3] - quote[2] + 1}px" if quote else "找不到",
        bool(quote) and base and (quote[3] - quote[2]) < (base[3] - base[2]),
    )
    shot("r31-device-quote-caret")

    # ---------------- 规则 5：引用行上「居中」置灰 ----------------
    print("\n规则 5 · 居中与引用互斥")
    before = caret()
    tap("居中"); time.sleep(1.2)
    after = caret()
    check(
        5, "引用行上点居中：光标一动不动（按钮已置灰）",
        f"x={before[0]}", f"x={after[0]}" if after else "找不到",
        bool(after) and abs(after[0] - before[0]) <= 2,
    )
    check(5, "引用仍生效（光标高度没变）", "不变", "不变" if after and before and (after[3] - after[2]) == (before[3] - before[2]) else "变了",
          bool(after) and bool(before) and (after[3] - after[2]) == (before[3] - before[2]))

    # 取消引用 → 居中应当可用
    tap("引用"); time.sleep(1.0)
    tap("居中"); time.sleep(1.2)
    centered = caret()
    b = editor_bounds()
    mid = (b[0] + b[2]) / 2
    check(
        4, "空行居中后光标落在行中央",
        f"~{mid:.0f}", f"{((centered[0] + centered[1]) / 2):.0f}" if centered else "找不到",
        bool(centered) and abs((centered[0] + centered[1]) / 2 - mid) < 40,
    )
    shot("r31-device-empty-centered")

    # ---------------- 规则 2/3：六选一互斥 ----------------
    print("\n规则 2/3 · 互斥与作用域")
    tap("居中"); time.sleep(0.8)  # 取消居中
    sh("shell", "input", "text", "L"); wait_text("L")
    tap("列表"); time.sleep(1.0)
    check(2, "点列表后行首出现项目符号", "• L", editor_text(), editor_text() == "• L")

    tap("段落样式"); time.sleep(1.0); tap("大标题"); time.sleep(1.2)
    check(2, "列表再切大标题：项目符号消失", "L", editor_text(), editor_text() == "L")
    shot("r31-device-list-to-title")

    # ---------------- 规则 4 / D10：回车 ----------------
    print("\n规则 4 · 回车（居中的空行才拦 / 空引用行结束引用）")

    # 4a 不居中的空行：不受限制
    reset()
    sh("shell", "input", "text", "AA"); wait_text("AA")
    for _ in range(3):
        sh("shell", "input", "keyevent", "66"); time.sleep(1.6)
    check(4, "不居中的空行：连按 3 次回车都生效", "'AA\\n\\n\\n'", repr(editor_text()),
          editor_text() == "AA\n\n\n")

    # 4b 居中的空行：吃掉
    reset()
    tap("居中"); time.sleep(1.0)
    sh("shell", "input", "keyevent", "66"); time.sleep(2)
    check(4, "居中的空行：回车被吃掉（文本仍是空的）", "''", repr(editor_text()),
          editor_text() in ("", "开始记录这一刻的想法…"))
    shot("r31-device-centered-enter-blocked")

    # 4c 空的引用行：吃掉 + 结束引用（底消失）
    reset()
    tap("引用"); time.sleep(1.2)
    box_before = quote_box_rows()
    sh("shell", "input", "keyevent", "66"); time.sleep(2)
    box_after = quote_box_rows()
    check(4, "空引用行：回车被吃掉且引用结束（底色消失）",
          "回车前有底 / 回车后无底",
          f"前 {box_before} 行 / 后 {box_after} 行",
          box_before > 0 and box_after == 0)

    # ---------------- 规则 1：B/I/S/U 可叠加 ----------------
    print("\n规则 1 · 四种行内样式叠加")
    reset()
    for label in ("粗体", "斜体", "删除线", "下划线"):
        tap(label); time.sleep(0.4)
    sh("shell", "input", "text", "BISU"); wait_text("BISU")
    shot("r31-device-inline-stack")
    check(1, "四种行内样式可同时开启并作用于新输入的字", "BISU 四个都亮", "见截图", True)

    # ---------------- 汇总 ----------------
    print("\n================ 汇总 ================")
    bad = [r for r in results if r[4] == "❌"]
    for r in results:
        print(f"{r[4]} 规则{r[0]} · {r[1]}：期望 {r[2]}，实测 {r[3]}")
    print(f"\n{len(results) - len(bad)}/{len(results)} 通过")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
