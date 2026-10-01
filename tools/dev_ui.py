#!/usr/bin/env python3
"""用 uiautomator 的**真实语义树**驱动 App（坐标不写死，任何设备/分辨率都能用）。

真机验证用的小工具：找节点 → 点它 → 打字 / 按回车 → 读 EditText 的文本 → 截图。
比写死坐标的脚本可靠：Compose 的语义树里 `content-desc` 就是我们设的 a11y 标签。

用法（都走 `adb`，设备用 `-s` 指定，默认取唯一在线的那个）：

    python3 tools/dev_ui.py dump
    python3 tools/dev_ui.py tap 写日记
    python3 tools/dev_ui.py tap 引用
    python3 tools/dev_ui.py text abc
    python3 tools/dev_ui.py key 66
    python3 tools/dev_ui.py editor-text
    python3 tools/dev_ui.py shot out.png
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = "/Users/cov/Library/Android/sdk/platform-tools/adb"
NS = "{http://schemas.android.com/apk/res/android}"


def sh(*args, **kw):
    return subprocess.run([ADB, *args], capture_output=True, text=True, **kw).stdout


def dump_xml():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh("shell", "cat", "/sdcard/ui.xml")


def attr(n, name):
    """uiautomator 的属性**没有命名空间前缀**（`text=` 而不是 `android:text=`），
    但不同版本/设备上两种都见过，所以两个都试。"""
    return n.get(name) or n.get(NS + name) or ""


def nodes():
    xml = dump_xml()
    xml = xml[xml.index("<hierarchy"):]
    root = ET.fromstring(xml)
    return [
        {
            "text": attr(n, "text"),
            "desc": attr(n, "content-desc"),
            "cls": attr(n, "class"),
            "bounds": attr(n, "bounds"),
            "node": n,
        }
        for n in root.iter("node")
    ]


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    x0, y0, x1, y1 = (int(v) for v in m.groups())
    return (x0 + x1) // 2, (y0 + y1) // 2


def find(label, want_class=None):
    """按 content-desc 或 text 找第一个匹配的节点。"""
    for n in nodes():
        if n["desc"] == label or n["text"] == label:
            if want_class is None or n["cls"].endswith(want_class):
                return n
    return None


def tap(label):
    n = find(label)
    if not n:
        print(f"NOT-FOUND {label}")
        return 1
    x, y = center(n["bounds"])
    sh("shell", "input", "tap", str(x), str(y))
    print(f"tap {label} @ {x},{y}")
    return 0


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if cmd == "dump":
        for n in nodes():
            if n["desc"] or n["text"]:
                print(f'{n["cls"].split(".")[-1]:12} | {n["text"]!r:40} | {n["desc"]!r:20} | {n["bounds"]}')
    elif cmd == "tap":
        sys.exit(tap(sys.argv[2]))
    elif cmd == "text":
        sh("shell", "input", "text", sys.argv[2])
        print("text", sys.argv[2])
    elif cmd == "key":
        sh("shell", "input", "keyevent", sys.argv[2])
        print("key", sys.argv[2])
    elif cmd == "editor-text":
        n = find("", want_class="EditText") or next((x for x in nodes() if x["cls"].endswith("EditText")), None)
        if not n:
            print("NO-EDITTEXT")
            sys.exit(1)
        print(f'editor text = {n["text"]!r}  bounds={n["bounds"]}')
    elif cmd == "wait-text":
        # 等编辑框的文本变成期望值（模拟器/真机的输入注入有延迟，脚本里硬 sleep 不可靠）
        want = sys.argv[2]
        timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 10.0
        import time
        t0 = time.time()
        last = None
        while time.time() - t0 < timeout:
            n = next((x for x in nodes() if x["cls"].endswith("EditText")), None)
            last = n["text"] if n else None
            if last == want:
                print(f"ok ({time.time() - t0:.1f}s) text={last!r}")
                sys.exit(0)
            time.sleep(0.4)
        print(f"TIMEOUT want={want!r} last={last!r}")
        sys.exit(1)
    elif cmd == "editor-bounds":
        n = next((x for x in nodes() if x["cls"].endswith("EditText")), None)
        print(n["bounds"] if n else "NO-EDITTEXT")
    elif cmd == "shot":
        sh("shell", "screencap", "-p", "/sdcard/ui.png")
        sh("pull", "/sdcard/ui.png", sys.argv[2])
        print("saved", sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
