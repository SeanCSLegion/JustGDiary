#!/usr/bin/env python3
"""
生成一份用于测试的 `.jdiary` 备份包。

用途：手上的设备没有数据，很多界面（阅读页的富文本、足迹的地点树与趋势图、
搜索的命中高亮、导入导出本身）都测不到。这个脚本按**导入导出的真实格式**造一份包，
导入之后就有了一批可控的测试数据。

## 包结构与 App 的期望一致

```
JustDiary_test.jdiary        （标准 zip）
├── manifest.json            BackupManifest
├── db/just_diary.db         diary / edit_block / diary_flag
└── images/<name>.jpg        被 content_json 里 src 引用的图片
```

## 覆盖到的样式（**编辑模式下的全部**）

| 类别 | 取值 |
| --- | --- |
| 段落 | `title` `heading` `body` `quote` `list` `todo` `image` |
| 行内 | `bold` `italic` `strike` `underline`（含两两组合与三合一） |
| 对齐 | `align = "center"` |
| 待办 | `items` + `done`（含全未完成 / 部分完成 / 全完成） |
| 列表 | `items`（2–5 项） |
| 图片 | `src` + `w` + `h`（横图 / 竖图 / 方图各若干） |

## 数据分布

- 跨 2024 / 2025 / 2026 三年（足迹的趋势图需要 ≥2 年才有柱子）
- 跨 6 个国家/地区（足迹的三级树：国家 → 省 → 区域）
- 其中若干天**故意没有地点**（验证「未记录 M 篇」与非 0 值的空态）
- 同一份包里有 1 个片段的日子，也有 4 个片段的日子（阅读页的多片段布局）

用法：

    python3 tools/make_test_backup.py [输出路径]

默认输出到 `tools/out/JustDiary_test.jdiary`。
"""

import json
import os
import random
import sqlite3
import struct
import sys
import zipfile
from datetime import datetime, timedelta, timezone

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover
    print("需要 Pillow：python3 -m pip install pillow", file=sys.stderr)
    raise

# --------------------------------------------------------------------------- 常量

FORMAT_VERSION = 1
APP_VERSION = "1.0"
UTC = timezone.utc

# 与 JustDiarySchema.kt 完全一致（导入只读 diary / edit_block，
# 但列名与类型必须对得上，否则 readBackupBlocks 取不到值）
DDL = [
    """
    CREATE TABLE IF NOT EXISTS diary (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      day_key TEXT NOT NULL UNIQUE,
      summary TEXT NOT NULL DEFAULT '',
      search_text TEXT NOT NULL DEFAULT '',
      created_utc INTEGER NOT NULL,
      updated_utc INTEGER NOT NULL
    );
    """,
    """
    CREATE TABLE IF NOT EXISTS edit_block (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      diary_id INTEGER NOT NULL,
      start_time_utc INTEGER NOT NULL,
      loc_text TEXT NOT NULL DEFAULT '',
      latitude REAL NOT NULL DEFAULT 0,
      longitude REAL NOT NULL DEFAULT 0,
      content_json TEXT NOT NULL DEFAULT '[]',
      search_text TEXT NOT NULL DEFAULT '',
      loc_precision TEXT NOT NULL DEFAULT 'none',
      loc_quality TEXT NOT NULL DEFAULT 'none',
      country TEXT NOT NULL DEFAULT '',
      country_code TEXT NOT NULL DEFAULT '',
      region1 TEXT NOT NULL DEFAULT '',
      region2 TEXT NOT NULL DEFAULT '',
      region3 TEXT NOT NULL DEFAULT '',
      created_utc INTEGER NOT NULL,
      updated_utc INTEGER NOT NULL
    );
    """,
    "CREATE TABLE IF NOT EXISTS diary_flag (day_key TEXT PRIMARY KEY);",
    "CREATE INDEX IF NOT EXISTS idx_block_diary ON edit_block(diary_id);",
    "CREATE INDEX IF NOT EXISTS idx_block_start ON edit_block(start_time_utc);",
]

# 地点库：覆盖三级树的不同深度（国家 / 国家·省 / 国家·省·区）
PLACES = [
    ("中国", "CN", "上海市", "浦东新区", "浦东新区 · 上海市 · 中国", 31.2304, 121.4737),
    ("中国", "CN", "上海市", "黄浦区", "黄浦区 · 上海市 · 中国", 31.2314, 121.4844),
    ("中国", "CN", "北京市", "朝阳区", "朝阳区 · 北京市 · 中国", 39.9219, 116.4433),
    ("中国", "CN", "广东省", "深圳市", "深圳市 · 广东省 · 中国", 22.5431, 114.0579),
    ("中国", "CN", "浙江省", "杭州市", "杭州市 · 浙江省 · 中国", 30.2741, 120.1551),
    ("日本", "JP", "东京都", "涩谷区", "涩谷区 · 东京都 · 日本", 35.6640, 139.6982),
    ("日本", "JP", "京都府", "东山区", "东山区 · 京都府 · 日本", 35.0037, 135.7788),
    ("法国", "FR", "法兰西岛", "巴黎", "巴黎 · 法兰西岛 · 法国", 48.8566, 2.3522),
    ("美国", "US", "加利福尼亚州", "旧金山", "旧金山 · 加利福尼亚州 · 美国", 37.7749, -122.4194),
    ("澳大利亚", "AU", "新南威尔士州", "悉尼", "悉尼 · 新南威尔士州 · 澳大利亚", -33.8688, 151.2093),
    ("冰岛", "IS", "", "", "冰岛", 64.1466, -21.9426),
]

TITLES = [
    "出发", "雨天", "深夜食堂", "路上", "第一次", "旧地重游", "换季", "周末",
    "加班之后", "海边", "山里", "临时的决定", "告别", "一个人的下午", "搬家",
]
BODY = [
    "今天走了很久，脚有点酸，但心情是好的。",
    "路边的树开始变颜色了，风里有一点凉。",
    "本来没打算出门，最后还是背着包走了。",
    "在一家很小的店里坐了一下午，只点了一杯咖啡。",
    "遇到一只橘猫，蹲在台阶上看了我很久。",
    "天快黑的时候下起雨，躲在天桥下面等了二十分钟。",
    "把一直拖着的事情做完了，比想象中容易。",
    "晚上煮了面，加了两个蛋。",
]
QUOTES = [
    "慢慢来，比较快。",
    "所有的远行都是从门口那一步开始的。",
    "记下来，就不算丢掉。",
]


def rgb(seed):
    r = random.Random(seed)
    return (r.randint(60, 220), r.randint(60, 220), r.randint(60, 220))


def make_image(path, w, h, label):
    """造一张一眼能认出来的图片（渐变 + 大字 + 尺寸标注）。"""
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    c1, c2 = rgb(label), rgb(label + "x")
    for y in range(h):
        t = y / max(1, h - 1)
        d.line(
            [(0, y), (w, y)],
            fill=tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3)),
        )
    # 中央画一个对比方块，方便确认图片确实被渲染出来（而不是一片纯色）
    d.rectangle(
        [w * 0.25, h * 0.35, w * 0.75, h * 0.65],
        fill=(255, 255, 255),
        outline=(30, 30, 30),
        width=max(2, w // 80),
    )
    d.text((w * 0.3, h * 0.45), f"{label}\n{w}x{h}", fill=(20, 20, 20))
    img.save(path, "JPEG", quality=82)


# --------------------------------------------------------------------------- 内容块


def runs(*pairs):
    """`(text, {bold: True})` 列表 → `runs` 数组（只写非空字段，保持字节稳定）。"""
    out = []
    for text, flags in pairs:
        item = {"text": text}
        for k in ("bold", "italic", "strike", "underline"):
            if flags.get(k):
                item[k] = True
        out.append(item)
    return out


def style_showcase(image_src=None):
    """把编辑模式支持的**全部**样式塞进一组 parts —— 一天一屏看不完，所以分几天铺开。"""
    parts = [
        {"style": "title", "text": "字体样式自检"},
        {"style": "heading", "text": "小标题"},
        {"style": "body", "text": "这一段是正文，用来和下面的标题、引用对比字号。"},
        {"style": "quote", "text": random.choice(QUOTES)},
        {"style": "list", "items": ["列表第一项", "列表第二项", "列表第三项"]},
        {"style": "todo", "items": ["买牛奶", "取快递", "写日记"], "done": [True, False, False]},
        {
            "style": "body",
            "runs": runs(
                ("普通 ", {}),
                ("加粗", {"bold": True}),
                (" / ", {}),
                ("斜体", {"italic": True}),
                (" / ", {}),
                ("删除线", {"strike": True}),
                (" / ", {}),
                ("下划线", {"underline": True}),
                (" / ", {}),
                ("三个一起", {"bold": True, "italic": True, "underline": True}),
            ),
        },
        {"style": "body", "align": "center", "text": "这一行是居中的"},
        {"style": "heading", "text": "待办全完成的样子"},
        {"style": "todo", "items": ["起床", "刷牙"], "done": [True, True]},
    ]
    if image_src:
        parts.append({"style": "image", "src": image_src, "w": 1200, "h": 800})
    return parts


def simple_day(idx, image_srcs):
    """普通的一天：2–4 个片段，含一到两张图。"""
    parts = [
        {"style": "title", "text": random.choice(TITLES)},
        {"style": "body", "text": random.choice(BODY)},
    ]
    if idx % 3 == 0:
        parts.append({"style": "quote", "text": random.choice(QUOTES)})
    if idx % 4 == 0:
        parts.append({"style": "list", "items": ["带伞", "充电宝", "水杯"]})
    if idx % 5 == 0:
        parts.append(
            {"style": "todo", "items": ["订票", "收拾行李"], "done": [True, False]}
        )
    if image_srcs and idx % 2 == 0:
        src = image_srcs[idx % len(image_srcs)]
        parts.append({"style": "image", "src": src, "w": 1200, "h": 900})
    return parts


def search_text_of(parts):
    """与 App 一样，把可搜索的文本拼起来（用空格分词，CJK 之间也插空格）。"""
    chunks = []

    def add(text):
        if text:
            chunks.append(text)

    for p in parts:
        add(p.get("text"))
        for item in p.get("items") or []:
            add(item)
        for r in p.get("runs") or []:
            add(r.get("text"))
    joined = " ".join(chunks)
    # 相邻 CJK 之间插空格（与 `FtsSegment` 同构，便于 MATCH 命中）
    out = []
    for i, ch in enumerate(joined):
        if i and "\u4e00" <= joined[i - 1] <= "\u9fff" and "\u4e00" <= ch <= "\u9fff":
            out.append(" ")
        out.append(ch)
    return "".join(out)


# --------------------------------------------------------------------------- 主流程


def build(out_path):
    random.seed(20260926)
    work = os.path.dirname(os.path.abspath(out_path))
    img_dir = os.path.join(work, "images")
    os.makedirs(img_dir, exist_ok=True)

    # ---- 图片：横 / 竖 / 方各几张
    specs = [
        (1200, 900, "横图A"), (1600, 900, "宽幅B"), (900, 1600, "竖图C"),
        (1000, 1000, "方图D"), (1400, 1050, "横图E"), (1080, 1920, "长竖F"),
    ]
    images = []
    for i, (w, h, label) in enumerate(specs, 1):
        name = f"test_{i:02d}.jpg"
        make_image(os.path.join(img_dir, name), w, h, label)
        images.append({"name": name, "src": f"images/{name}", "w": w, "h": h})

    srcs = [im["src"] for im in images]

    db_path = os.path.join(work, "just_diary.db")
    if os.path.exists(db_path):
        os.remove(db_path)
    con = sqlite3.connect(db_path)
    for stmt in DDL:
        con.execute(stmt)

    # ---- 日期：跨三年，且**刻意留下一些没有地点的日子**
    today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    plan = []
    # 2026 年 9 月：密集（当月日历上能看到多条短横线）
    for d in range(1, 27, 2):
        plan.append(today.replace(day=d))
    # 往前逐月铺，跨到 2024
    for months_back in range(1, 26):
        base = today - timedelta(days=30 * months_back)
        for off in (3, 11, 19):
            plan.append(base - timedelta(days=off))

    plan = sorted({d.date() for d in plan})
    diag = {"days": 0, "blocks": 0, "located": 0, "unlocated": 0}

    for i, day in enumerate(plan):
        has_place = (i % 7) != 3          # 每 7 天留一天没有地点
        showcase = (i % 11) == 5          # 有几天专门铺满样式

        midnight = datetime(day.year, day.month, day.day, tzinfo=UTC)
        # 归属日：以 UTC+8 的当地时刻写，换算回 UTC 存储
        local_base = midnight + timedelta(hours=-8)

        blocks = []
        if showcase:
            blocks.append((local_base + timedelta(hours=9), style_showcase(srcs[i % len(srcs)])))
        blocks.append((local_base + timedelta(hours=8, minutes=40), simple_day(i, srcs)))
        if i % 3 == 1:
            blocks.append((local_base + timedelta(hours=13, minutes=5), [
                {"style": "heading", "text": "下午"},
                {"style": "body", "text": random.choice(BODY)},
                {"style": "todo", "items": ["回邮件", "打电话"], "done": [True, True]},
            ]))
        if i % 5 == 2:
            blocks.append((local_base + timedelta(hours=21, minutes=20), [
                {"style": "heading", "text": "夜里"},
                {"style": "body", "text": random.choice(BODY)},
                {"style": "image", "src": srcs[(i + 3) % len(srcs)], "w": 1000, "h": 1000},
            ]))

        summary = ""
        for _, parts in blocks:
            for p in parts:
                if p.get("style") == "title":
                    summary = p.get("text", "")
                    break
            if summary:
                break
        if not summary:
            summary = random.choice(BODY)[:18]

        all_text = search_text_of([p for _, parts in blocks for p in parts])
        day_key = day.isoformat()
        created = int(local_base.timestamp() * 1000)
        cur = con.execute(
            "INSERT INTO diary(day_key, summary, search_text, created_utc, updated_utc)"
            " VALUES(?,?,?,?,?)",
            (day_key, summary, all_text, created, created),
        )
        diary_id = cur.lastrowid
        con.execute("INSERT OR REPLACE INTO diary_flag(day_key) VALUES(?)", (day_key,))
        diag["days"] += 1

        for k, (when, parts) in enumerate(blocks):
            if has_place:
                place = PLACES[(i + k) % len(PLACES)]
                country, code, r1, r2, loc_text, lat, lon = place
                precision, quality = "exact", "exact"
            else:
                country = code = r1 = r2 = loc_text = ""
                lat = lon = 0.0
                precision = quality = "none"
            text = search_text_of(parts)
            con.execute(
                "INSERT INTO edit_block(diary_id, start_time_utc, loc_text, latitude, longitude,"
                " content_json, search_text, loc_precision, loc_quality, country, country_code,"
                " region1, region2, region3, created_utc, updated_utc)"
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    diary_id,
                    int(when.timestamp() * 1000),
                    loc_text,
                    lat,
                    lon,
                    json.dumps({"parts": parts, "v": 2}, ensure_ascii=False),
                    text,
                    precision,
                    quality,
                    country,
                    code,
                    r1,
                    r2,
                    "",
                    int(when.timestamp() * 1000),
                    int(when.timestamp() * 1000),
                ),
            )
            diag["blocks"] += 1
            diag["located" if has_place else "unlocated"] += 1

    con.commit()
    con.close()

    # ---- manifest + zip
    manifest = {
        "formatVersion": FORMAT_VERSION,
        "appVersion": APP_VERSION,
        "exportedAt": int(datetime.now(tz=UTC).timestamp() * 1000),
        "includeSettings": False,
        "diaryCount": diag["days"],
        "blockCount": diag["blocks"],
        "imageCount": len(images),
        "images": [{"src": im["src"], "file": f"images/{im['name']}"} for im in images],
        "settingsJson": "",
    }

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        z.write(db_path, "db/just_diary.db")
        for im in images:
            z.write(os.path.join(img_dir, im["name"]), f"images/{im['name']}")

    size = os.path.getsize(out_path)
    print(f"✅ {out_path}  ({size / 1024:.0f} KB)")
    print(
        f"   {diag['days']} 天 · {diag['blocks']} 片段"
        f"（有地点 {diag['located']} / 无地点 {diag['unlocated']}）· {len(images)} 张图"
    )
    print("   导入：设置 → 导入备份 → 选择该文件")
    return out_path


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "out", "JustDiary_test.jdiary"
    )
    build(target)
