#!/usr/bin/env python3
"""日记编辑态「格式栏六条规则」的真机/模拟器**端到端**回归。

坐标全部来自 uiautomator 的语义树（`tools/dev_ui.py`），因此换设备/换分辨率都能跑；
光标按**颜色**定位（强调色的竖直细条，见 `tools/dev_caret.py --probe`；帧差只作兜底）。

用法：python3 tools/dev_format_rules.py [--device 序列号] [--day YYYY-MM-DD] [--out 目录]

跑完打印一张表：每一步的期望值 / 实测值 / 判定，并把证据截图存到 `--out`
（默认 `docs/ui-audit/r31`，文件名自动带上目录名前缀）。
"""
import argparse
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import dev_ui  # noqa: E402

ADB = dev_ui.ADB
ROOT = "/Users/cov/JustGDiary"
# 设备当天（`写日记` 只在今天出现）；`--day` 可覆盖。
DAY = subprocess.run([ADB, "shell", "date", "+%Y-%m-%d"], capture_output=True, text=True).stdout.strip()
OUT = f"{ROOT}/docs/ui-audit/r31"
results = []


def sh(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True).stdout


def editor_bounds():
    n = next((x for x in dev_ui.nodes() if x["cls"].endswith("EditText")), None)
    if not n:
        return None
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n["bounds"])
    return tuple(int(v) for v in m.groups())


def caret(shots=6, gap=0.3):
    """找光标：**先按颜色单张找**（`dev_caret.py --probe`），找不到再退回帧差。

    ⚠️ 为什么不再只靠帧差：Android 的光标约 500ms 亮 / 500ms 灭，而真机上单张
    `screencap` 就要几百毫秒 —— 采样没跨过闪烁周期时差分全空，会被误判成"找不到光标"
    （R32 在 MEIZU 21 上跑这 12 条时，4 条"失败"全是这个原因：实测光标高度
    正文 65 / 大标题 113 / 引用 56px、居中后 x=539，都是对的）。
    颜色判据与主题无关：饱和色（`max-min > 60`）的竖直细条（宽 ≤ 10px、高 20..220px）。
    """
    b = editor_bounds()
    if not b:
        return None
    region = (b[0], b[1], b[2], b[3] + 200)
    for i in range(shots):
        sh("shell", "screencap", "-p", f"/sdcard/c{i}.png")
        sh("pull", f"/sdcard/c{i}.png", f"/tmp/c{i}.png")
        out = subprocess.run(
            [sys.executable, f"{ROOT}/tools/dev_caret.py", "--probe", f"/tmp/c{i}.png",
             f"--region={region[0]},{region[1]},{region[2]},{region[3]}"],
            capture_output=True, text=True,
        ).stdout.strip()
        if out.startswith("caret"):
            return _caret_tuple(out)
        time.sleep(gap)

    # 兜底：帧差（跨一个闪烁周期）
    for i in range(shots, shots * 2):
        sh("shell", "screencap", "-p", f"/sdcard/c{i}.png")
        sh("pull", f"/sdcard/c{i}.png", f"/tmp/c{i}.png")
        time.sleep(gap)
    out = subprocess.run(
        [sys.executable, f"{ROOT}/tools/dev_caret.py",
         *[f"/tmp/c{i}.png" for i in range(shots, shots * 2)],
         f"--region={region[0]},{region[1]},{region[2]},{region[3]}"],
        capture_output=True, text=True,
    ).stdout.strip()
    return _caret_tuple(out)


def _caret_tuple(out):
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


def tap(label, tries=10, gap=0.6):
    """点一个语义节点：**等它位置稳定**再点，找不到就重试。

    ⚠️ 为什么要等稳定：菜单 / 面板出现时有一小段位移动画，按"刚 dump 到的坐标"立刻点
    会**点空**。R32 在真机上就是这样：`段落样式` 打开菜单后隔 1.0s 点 `大标题`，
    菜单还在滑入 → 点空 → 后面量到的光标高度仍是正文的 65px（看起来像"切样式没生效"）。
    等两次 dump 的 bounds 一致再点，同时把"还没画出来"（找不到）也一起解决。
    """
    last = None
    for i in range(tries):
        n = dev_ui.find(label)
        if n:
            bounds = n["bounds"]
            if last == bounds and dev_ui.tap(label) == 0:
                return
            last = bounds
        time.sleep(gap)
    raise AssertionError(f"找不到 / 点不中按钮 {label}")


def shot(name):
    """存一张证据截图到 `--out` 目录：文件名自动带上那一轮的前缀（目录名）。

    早先名字里写死了 "r31-"，换一轮就变成"r32 目录里躺着 r31-xxx.png"，两套混着没法看。
    """
    sh("shell", "screencap", "-p", "/sdcard/e.png")
    sh("pull", "/sdcard/e.png", f"{OUT}/{os.path.basename(OUT)}-{name}.png")


def quote_box_rows():
    """编辑区内**引用底色**的像素行数 —— 用来判断"引用底在不在"。

    底色**不写死**：在同一条竖线上先取"空白处"（编辑区底部）的颜色当背景，再数有多少行
    与它不同。这样深浅色主题都能用 —— 早先钉的是深色主题的 `(37, 43, 44)`，
    浅色主题下永远是 0 行，于是"空引用行回车结束引用"这条被误判成失败（R32 实测）。
    取靠右的一条竖线（`b[2] - 40`）：避开光标（在最左）与提示语（左对齐），
    但仍在引用底横跨的范围内。
    """
    from PIL import Image
    b = editor_bounds()
    if not b:
        return -1
    sh("shell", "screencap", "-p", "/sdcard/px.png")
    sh("pull", "/sdcard/px.png", "/tmp/px.png")
    im = Image.open("/tmp/px.png").convert("RGB")
    px = im.load()
    x = min(b[2] - 40, im.width - 1)
    y0, y1 = b[1], min(b[3], im.height)
    if y1 - y0 < 12:
        return -1
    bg = px[x, y1 - 6]
    return sum(1 for y in range(y0, y1 - 2) if px[x, y] != bg)


def check(rule, what, expect, actual, ok):
    results.append((rule, what, expect, actual, "✅" if ok else "❌"))
    print(f"  {'✅' if ok else '❌'} {what}: 期望 {expect}，实测 {actual}")


def reset():
    # 深链只用来"进到某一天的日记页"；**必须是今天** ——
    # 「写日记」只在今天出现（历史日记只能在设置里打开"允许修改"、且仍然不可新增），
    # 所以日期默认取设备当天，可以用 `--day` 覆盖（跑历史那天的验收时用）。
    sh("shell", "am", "force-stop", "com.cov.justdiary")
    time.sleep(2)
    sh("shell", "am", "start", "-a", "android.intent.action.VIEW",
       "-d", f"justdiary://editor?dayKey={DAY}")
    time.sleep(6)
    tap("写日记")
    time.sleep(3)


def main():
    global OUT
    p = argparse.ArgumentParser()
    global DAY
    p.add_argument("--out", default=OUT)
    p.add_argument("--day", default=DAY, help="目标日期（默认设备当天）")
    a = p.parse_args()
    OUT = a.out
    DAY = a.day

    # ---------------- 规则 6：光标字号跟着段落样式 ----------------
    print("\n规则 6 · 光标字号")
    reset()
    base = caret()
    check(6, "正文空行的光标高度", "~70px", f"{base[3] - base[2] + 1}px" if base else "找不到", bool(base))

    tap("段落样式"); tap("大标题"); time.sleep(1.0)
    title = caret()
    check(
        6, "切成大标题后的光标高度", "明显更高（>正文 ×1.4）",
        f"{title[3] - title[2] + 1}px" if title else "找不到",
        bool(title) and base and (title[3] - title[2]) > (base[3] - base[2]) * 1.4,
    )
    shot("device-title-caret")

    tap("段落样式"); tap("正文"); tap("引用"); time.sleep(1.0)
    quote = caret()
    check(
        6, "切成引用后的光标高度（引用字号更小）", "比正文矮",
        f"{quote[3] - quote[2] + 1}px" if quote else "找不到",
        bool(quote) and base and (quote[3] - quote[2]) < (base[3] - base[2]),
    )
    shot("device-quote-caret")

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
    shot("device-empty-centered")

    # ---------------- 规则 2/3：六选一互斥 ----------------
    print("\n规则 2/3 · 互斥与作用域")
    tap("居中"); time.sleep(0.8)  # 取消居中
    sh("shell", "input", "text", "L"); wait_text("L")
    tap("列表"); time.sleep(1.0)
    check(2, "点列表后行首出现项目符号", "• L", editor_text(), editor_text() == "• L")

    tap("段落样式"); tap("大标题"); time.sleep(1.0)
    check(2, "列表再切大标题：项目符号消失", "L", editor_text(), editor_text() == "L")
    shot("device-list-to-title")

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
    shot("device-centered-enter-blocked")

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
    shot("device-inline-stack")
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
