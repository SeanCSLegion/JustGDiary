#!/usr/bin/env python3
"""真机验证小工具：从两张（或多张）截图里找出**闪烁的光标**。

Android 的光标约 500ms 闪一次，两次截图在文本区内的差异像素就是光标
（以及极少量的抗锯齿噪声）。项目里 R30 就是这么定位光标的（"帧差定位光标"）。

用法：
  python3 tools/dev_caret.py a.png b.png [--region x0,y0,x1,y1]
"""
import sys
from PIL import Image, ImageChops

def diff_box(paths, region=None):
    ims = [Image.open(p).convert("RGB") for p in paths]
    if region:
        ims = [im.crop(region) for im in ims]
    w, h = ims[0].size
    acc = Image.new("L", (w, h), 0)
    for i in range(len(ims) - 1):
        d = ImageChops.difference(ims[i], ims[i + 1]).convert("L")
        acc = ImageChops.lighter(acc, d)
    # 阈值：排除抗锯齿/压缩噪声
    mask = acc.point(lambda v: 255 if v > 40 else 0)
    box = mask.getbbox()
    if not box:
        return None
    # 只保留"竖直细长"的连通块：按列统计
    px = mask.load()
    cols = [x for x in range(w) if any(px[x, y] for y in range(h))]
    if not cols:
        return None
    x0, x1 = min(cols), max(cols)
    rows = [y for y in range(h) if any(px[x, y] for x in range(x0, x1 + 1))]
    y0, y1 = min(rows), max(rows)
    if region:
        x0 += region[0]; x1 += region[0]; y0 += region[1]; y1 += region[1]
    return (x0, y0, x1, y1)

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    region = None
    for a in sys.argv[1:]:
        if a.startswith("--region"):
            region = tuple(int(v) for v in a.split("=", 1)[1].split(","))
    box = diff_box(args, region)
    if not box:
        print("NO-CARET")
    else:
        x0, y0, x1, y1 = box
        print(f"caret x={x0}..{x1} (w={x1 - x0 + 1}) y={y0}..{y1} (h={y1 - y0 + 1}) center_x={(x0 + x1) / 2:.1f}")
