#!/usr/bin/env python3
"""真机验证小工具：从截图里找出**光标**。两种模式：

1. **单张按颜色找**（`--probe`，推荐）：光标是主题强调色的 **6px 宽竖条**，
   在编辑区里找"颜色饱和、宽 ≤ 10px、高 20..220px 的竖直连通块"即可。
   与闪烁相位无关，因此稳定 —— R32 在 MEIZU 21 上实测：正文 65px / 大标题 113px /
   引用 56px / 居中后 x=539，一次就对。
2. **多张帧差**（不带 `--probe`）：Android 的光标约 500ms 闪一次，相邻两张截图的差异
   像素就是光标。R30 用的就是这个办法，但它要求采样**跨过闪烁周期**，而真机上单张
   `screencap` 就要几百毫秒 —— 相位没跨过去就得到"找不到光标"（R32 的 12 条端到端
   回归里，4 条"失败"全是这个原因）。留着当兜底。

用法：
  python3 tools/dev_caret.py --probe a.png [--region x0,y0,x1,y1]
  python3 tools/dev_caret.py a.png b.png c.png [--region x0,y0,x1,y1]
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

def probe_box(path, region=None):
    """单张按颜色找光标：饱和色的竖直细条（宽 ≤ 10px、高 20..220px）。

    "饱和"的判据与主题无关：`max(RGB) - min(RGB) > 60 且 max(RGB) > 90`。
    正文是灰黑、引用底与竖条是灰、勾选框 18dp 宽 —— 都不会被误判成光标。
    """
    im = Image.open(path).convert("RGB")
    if region:
        im = im.crop(region)
    w, h = im.size
    px = im.load()

    def saturated(p):
        # 光标是主题强调色（浅色下 `(0, 133, 119)` 一类的**青绿**）：红通道明显低于最高通道。
        # 只判"饱和"会把地点胶囊的浅青（185, 242, 250）也算进来 —— 真机上就是这么误判的。
        return max(p) - min(p) > 60 and max(p) > 90 and p[0] < max(p) * 0.55

    cols = {}
    for x in range(w):
        ys = [y for y in range(h) if saturated(px[x, y])]
        if ys:
            cols[x] = (min(ys), max(ys))
    # 先按列把连续的饱和像素分组，再对**每条完整的竖条**做判定。
    # ⚠️ 不能在 run 还没收集完时就下结论：那会量到一条被截断的竖条（真机上出现过
    # "光标 2px 宽、65px 高"的错值 —— 而它其实是 6px 宽、107px 高的标题光标）。
    # 光标是实心竖条（真机 6px 宽）；判据：宽度 2..12、高度 20..260、且**填得实**。
    runs = []
    run = []
    for x in sorted(cols):
        if run and x - run[-1] > 1:
            runs.append(run)
            run = []
        run.append(x)
    if run:
        runs.append(run)

    for xs in runs:
        if not 2 <= len(xs) <= 12:
            continue
        y0 = min(cols[c][0] for c in xs)
        y1 = max(cols[c][1] for c in xs)
        height = y1 - y0 + 1
        if not 20 <= height <= 260:
            continue
        # 光标是**闪烁**的（亮 → 灭之间还有一段淡入淡出）：淡出过程中顶端/底端的像素会
        # 变成半透明（不再是"饱和色"），要求"填得实"会把一条完整的光标截短 ——
        # 真机上就量到过 65px，而实际是 113px。这里放宽到 60%。
        filled = sum(1 for c in xs for y in range(y0, y1 + 1) if saturated(px[c, y]))
        if filled < len(xs) * height * 0.6:
            continue
        ox, oy = (region[0], region[1]) if region else (0, 0)
        return (xs[0] + ox, y0 + oy, xs[-1] + ox, y1 + oy)
    return None


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    region = None
    for a in sys.argv[1:]:
        if a.startswith("--region"):
            region = tuple(int(v) for v in a.split("=", 1)[1].split(","))
    if "--probe" in sys.argv:
        box = probe_box(args[0], region) if args else None
    else:
        box = diff_box(args, region)
    if not box:
        print("NO-CARET")
    else:
        x0, y0, x1, y1 = box
        print(f"caret x={x0}..{x1} (w={x1 - x0 + 1}) y={y0}..{y1} (h={y1 - y0 + 1}) center_x={(x0 + x1) / 2:.1f}")
