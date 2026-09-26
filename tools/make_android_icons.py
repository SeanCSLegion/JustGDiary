#!/usr/bin/env python3
"""
把 iOS 的 Icon Composer 图标转成 Android 的启动图标资源。

## 源：`JustADiary/JustDiary.icon`

Xcode 的 Icon Composer 产物是一个**分层**图标包：

```
JustDiary.icon/
├── icon.json          底色 + 各层的堆叠顺序与效果
└── Assets/
    ├── 2-glass-plate.png      ← 最底（玻璃托片）
    ├── 3-paper 3.png          ← 纸张
    ├── 4-weather-photo.png    ← 天气小卡
    └── 5-writing-lines.png    ← 书写横线（最上）
```

`icon.json` 的 `fill.solid` 是 **Display-P3** 的 `0.39990,0.71387,0.97656`
（转成 sRGB ≈ `#44B8FF`）。层序按文件名里的序号：2 在底、5 在顶
（反序合成的结果只剩一张白纸，肉眼可辨）。

## 目标：Android 需要四套东西

| 产物 | 尺寸 | 用途 |
| --- | --- | --- |
| `mipmap-*dpi/ic_launcher.png` | 48/72/96/144/192 | API < 26 的传统图标（整块方形图） |
| `mipmap-*dpi/ic_launcher_round.png` | 同上 | 传统圆形图标（需求方要圆形时） |
| `mipmap-*dpi/ic_launcher_foreground.png` | 108/162/216/324/432 | 自适应图标的**前景**层（108dp 画布） |
| `mipmap-*dpi/ic_launcher_monochrome.png` | 同上 | API 33+ 主题图标（单色剪影） |

自适应图标的关键是**安全区**：画布 108dp，系统遮罩只保证中央 **72dp** 可见。
所以前景里的图案要缩到约 2/3，四周留透明，否则圆角遮罩会把纸角切掉。

## 与 iOS 的一处有意差异

iOS 的"液态玻璃"（`refractivity` / `specular`）是系统在图标上实时合成的光学效果，
**Android 没有对应机制**，也无法用静态 PNG 表达。这里保留玻璃托片这一层
（它本身有很淡的高光，max alpha ≈ 40），但**不模拟折射**。

用法：

    python3 tools/make_android_icons.py [iOS 仓库根目录]
"""

import json
import os
import re
import sys

from PIL import Image, ImageDraw

IOS_ROOT = sys.argv[1] if len(sys.argv) > 1 else "/Users/cov/JustADiary"
ICON_DIR = os.path.join(IOS_ROOT, "JustDiary.icon")
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

# 密度 → 传统图标边长 / 自适应图标画布边长（108dp）
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

# 自适应图标：108dp 画布里只有中央 72dp 一定可见（0.667）。
# 图案再占可见区的 88%，于是纸角不会被圆角遮罩切掉。
VISIBLE_RATIO = 72 / 108
CONTENT_RATIO_OF_VISIBLE = 0.88
# 合起来 = 图案（按**实际不透明外框**量）应占画布的比例。
FOREGROUND_RATIO = VISIBLE_RATIO * CONTENT_RATIO_OF_VISIBLE


def lin(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def gam(c):
    c = max(0.0, min(1.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def p3_to_srgb(r, g, b):
    """Display-P3 → sRGB（D65）。纯色底用，够精确。"""
    R, G, B = lin(r), lin(g), lin(b)
    X = 0.4865709 * R + 0.2656677 * G + 0.1982173 * B
    Y = 0.2289746 * R + 0.6917385 * G + 0.0792869 * B
    Z = 0.0000000 * R + 0.0451134 * G + 1.0439444 * B
    r2 = 3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z
    g2 = -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z
    b2 = 0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z
    return tuple(round(gam(c) * 255) for c in (r2, g2, b2))


def load_manifest():
    with open(os.path.join(ICON_DIR, "icon.json"), encoding="utf-8") as f:
        return json.load(f)


def parse_fill(manifest):
    solid = manifest.get("fill", {}).get("solid", "")
    m = re.match(r"display-p3:\s*([\d.]+),\s*([\d.]+),\s*([\d.]+)", solid)
    if not m:
        raise SystemExit(f"看不懂的 fill：{solid!r}")
    return p3_to_srgb(float(m.group(1)), float(m.group(2)), float(m.group(3)))


def layer_names(manifest):
    """按**绘制顺序**（底 → 顶）列出各层图片名。

    文件名前缀的数字就是层序，直接用它排序最稳 —— `icon.json` 里 groups 的
    数组顺序在 Icon Composer 的不同版本里含义不同，靠它判断容易反。
    """
    names = []
    for group in manifest.get("groups", []):
        for layer in group.get("layers", []):
            name = layer.get("image-name")
            if name:
                names.append(name)
    return sorted(names, key=lambda n: int(re.match(r"(\d+)", n).group(1)))


def compose(size, fill, names, background=True):
    """把各层叠起来。`background=False` 时底色透明（给自适应图标的前景用）。"""
    base = Image.new("RGBA", (size, size), fill + (255,) if background else (0, 0, 0, 0))
    for name in names:
        layer = Image.open(os.path.join(ICON_DIR, "Assets", name)).convert("RGBA")
        if layer.size != (size, size):
            layer = layer.resize((size, size), Image.LANCZOS)
        base = Image.alpha_composite(base, layer)
    return base


def alpha_bbox(img):
    """不透明内容的外框。

    ⚠️ 必须按外框缩放，**不能**把整块 1024 画布等比缩 —— 源图四周本来就留了空白
    （还有一层很淡的玻璃托片），直接缩会让纸在安全区里只占三分之一（实测就是这样）。
    """
    return img.getchannel("A").getbbox()


def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def main():
    manifest = load_manifest()
    fill = parse_fill(manifest)
    names = layer_names(manifest)
    print(f"底色 sRGB = #%02X%02X%02X" % fill)
    print("层序（底→顶）:", " → ".join(names))

    written = []

    # 1024 的母版：整图（带底）与前景（透明底）
    master = compose(1024, fill, names, background=True)
    artwork = compose(1024, fill, names, background=False)
    master.save("/tmp/icon/master.png")
    artwork.save("/tmp/icon/artwork.png")

    for density, (legacy_px, adaptive_px) in DENSITIES.items():
        out_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)

        # 1) 传统方形图标：整图缩到目标尺寸
        master.resize((legacy_px, legacy_px), Image.LANCZOS).save(
            os.path.join(out_dir, "ic_launcher.png")
        )
        written.append(f"mipmap-{density}/ic_launcher.png")

        # 2) 传统圆形图标：整图裁圆
        round_img = master.resize((legacy_px, legacy_px), Image.LANCZOS).convert("RGBA")
        round_img.putalpha(circle_mask(legacy_px))
        round_img.save(os.path.join(out_dir, "ic_launcher_round.png"))
        written.append(f"mipmap-{density}/ic_launcher_round.png")

        # 3) 自适应前景：把**图案外框**缩到安全区里、居中、四周透明
        box = alpha_bbox(artwork)
        cropped = artwork.crop(box)
        side = max(cropped.size)
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.paste(cropped, ((side - cropped.width) // 2, (side - cropped.height) // 2))
        inner = round(adaptive_px * FOREGROUND_RATIO)
        scaled = square.resize((inner, inner), Image.LANCZOS)
        fg = Image.new("RGBA", (adaptive_px, adaptive_px), (0, 0, 0, 0))
        fg.paste(scaled, ((adaptive_px - inner) // 2, (adaptive_px - inner) // 2), scaled)
        fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"))
        written.append(f"mipmap-{density}/ic_launcher_foreground.png")

        # 4) 主题图标（单色剪影）：取前景的 alpha 作为形状
        mono = Image.new("RGBA", (adaptive_px, adaptive_px), (0, 0, 0, 0))
        alpha = fg.getchannel("A")
        mono.paste(Image.new("RGBA", (adaptive_px, adaptive_px), (255, 255, 255, 255)), (0, 0), alpha)
        mono.save(os.path.join(out_dir, "ic_launcher_monochrome.png"))
        written.append(f"mipmap-{density}/ic_launcher_monochrome.png")

    print(f"\n写入 {len(written)} 个文件到 {os.path.normpath(RES)}")
    print(f"底色资源请设为 #%02X%02X%02X" % fill)
    return fill


if __name__ == "__main__":
    main()
