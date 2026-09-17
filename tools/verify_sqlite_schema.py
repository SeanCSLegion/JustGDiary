#!/usr/bin/env python3
"""用宿主 sqlite3 真执行一遍 schema 与关键查询，验证 SQL 正确性。

为什么需要它：本机没有 AVD / 真机，数据层无法用 instrumented 测试验证。
但 schema DDL、FTS5 建表、`snippet()` 调用、`NO_LOC_SQL`、派生列重算这些
**都是纯 SQL**，可以脱离 Android 直接用 SQLite 执行验证。

与 Android 的差异：这里验证的是宿主 SQLite 的方言与 FTS5 行为，不覆盖 Android
framework 层（`SQLiteOpenHelper`、绑定、游标）。后者由 JVM 单测与后续真机验证覆盖。

用法：  python3 tools/verify_sqlite_schema.py
"""
import re
import sqlite3
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA_KT = ROOT / "app/src/main/java/com/cov/justdiary/data/db/JustDiarySchema.kt"

failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {label}" + (f"  — {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(label)


def load_constants() -> dict:
    """从 Kotlin 源码里提取 SQL 常量，保证验证的就是代码里那一份文本。"""
    src = SCHEMA_KT.read_text(encoding="utf-8")
    out: dict = {}

    # 先匹配单行字符串，再用三引号字符串覆盖 —— 否则 `"""` 的开头会被单行模式误配成空串
    for m in re.finditer(r'const val (\w+): String\s*=\s*"(?!")', src):
        start = m.end()
        end = src.index('"', start)
        out[m.group(1)] = src[start:end].replace('\\"', '"').replace("\\'", "'").strip()
    for m in re.finditer(r'const val (\w+): String\s*=\s*"""(.*?)"""', src, re.S):
        out[m.group(1)] = m.group(2).strip()

    m = re.search(r"val CREATE_INDICES: List<String> = listOf\((.*?)\)\n", src, re.S)
    if m:
        out["CREATE_INDICES"] = re.findall(r'"([^"]+)"', m.group(1).replace('\\"', '"'))

    return out


def segment(text: str) -> str:
    """FtsSegment.segment 的 Python 版（仅用于本脚本；Kotlin 版有独立单测）。"""
    def is_cjk(ch: str) -> bool:
        cp = ord(ch)
        return 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0x3000 <= cp <= 0x303F

    out = []
    prev = None
    for ch in text:
        if prev is not None and is_cjk(prev) and is_cjk(ch):
            out.append(" ")
        out.append(ch)
        prev = ch
    return "".join(out)


def main() -> int:
    sql = load_constants()
    print(f"从 JustDiarySchema.kt 提取到 {len(sql)} 个 SQL 常量\n")

    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=OFF;")

    # ---------------------------------------------------------------- schema
    print("[schema]")
    db.execute(sql["CREATE_DIARY"])
    db.execute(sql["CREATE_EDIT_BLOCK"])
    db.execute(sql["CREATE_DIARY_FLAG"])
    for index in sql["CREATE_INDICES"]:
        db.execute(index)
    db.execute(sql["CREATE_FTS"])
    check("三张表 + 4 个索引 + FTS5 虚拟表建表成功", True)

    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    check("表名与 iOS 完全一致", tables >= {"diary", "edit_block", "diary_flag", "diary_fts"}, str(tables))

    cols = {r[1] for r in db.execute("PRAGMA table_info(edit_block)")}
    expected_cols = {
        "id", "diary_id", "start_time_utc", "loc_text", "latitude", "longitude", "content_json",
        "search_text", "loc_precision", "loc_quality", "country", "country_code",
        "region1", "region2", "region3", "created_utc", "updated_utc",
    }
    check("edit_block 列名与 iOS 完全一致（含 region1/2/3）", cols == expected_cols, str(cols ^ expected_cols))

    indices = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='index'")}
    check(
        "索引名与 iOS 一致",
        {"idx_block_diary", "idx_block_start", "idx_block_country_region", "idx_block_diary_quality"} <= indices,
        str(indices),
    )

    # ---------------------------------------------------------------- 写入与派生列
    print("\n[写入与派生列]")
    day_key = "2026-09-15"
    db.execute(sql["INSERT_DIARY_IF_ABSENT"], (day_key, 1000, 1000))
    diary_id = db.execute(sql["SELECT_DIARY_ID_BY_DAY"], (day_key,)).fetchone()[0]
    db.execute(sql["INSERT_DIARY_FLAG_IF_ABSENT"], (day_key,))

    blocks = [
        (diary_id, 1000, "南山区 · 深圳市 · 广东省 · 中国", 22.54, 113.95,
         '{"parts":[{"runs":[{"text":"搬来深圳第一周"}],"style":"title"},'
         '{"runs":[{"text":"楼下肠粉很温柔"}],"style":"body"}],"v":2}',
         "搬来深圳第一周楼下肠粉很温柔", "exact", "precise", "中国", "CN", "广东省", "深圳市", "南山区", 1000, 1000),
        (diary_id, 2000, "", 0.0, 0.0,
         '{"parts":[{"style":"body","text":"晚上沿着深圳湾走了很久"}],"v":2}',
         "晚上沿着深圳湾走了很久", "none", "coarse", "", "", "", "", "", 2000, 2000),
    ]
    for b in blocks:
        db.execute(sql["INSERT_BLOCK"], b)
        db.execute(sql["UPDATE_BLOCK_INDEX_TEXT"], (b[5], b[6], db.execute("SELECT last_insert_rowid()").fetchone()[0]))

    db.execute(sql["TOUCH_DIARY"], (diary_id, diary_id, 2000, diary_id))
    summary, search_text = db.execute(
        "SELECT summary, search_text FROM diary WHERE id=?", (diary_id,)
    ).fetchone()
    check("summary 取前 40 字", summary.startswith("搬来深圳第一周"), summary)
    check("search_text 用空格连接各片段", search_text == blocks[0][6] + " " + blocks[1][6], search_text)

    # ---------------------------------------------------------------- FTS + CJK
    print("\n[FTS5 与 CJK 切分]")
    db.execute(sql["FTS_DELETE_BY_DIARY"], (diary_id,))
    db.execute(sql["FTS_INSERT"], (diary_id, segment(search_text)))

    phrase = '"' + segment("深圳湾") + '"'
    hit = db.execute(
        "SELECT diary_id, snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24) "
        "FROM diary_fts WHERE diary_fts MATCH ?",
        (phrase,),
    ).fetchall()
    check("分词后能用 FTS 子串命中中文", len(hit) == 1 and hit[0][0] == diary_id, str(hit))
    check("snippet 产出 <hl> 高亮标签", bool(hit) and "<hl>" in hit[0][1], str(hit))

    # 不分词则查不到 —— 这正是必须做 CJK 切分的原因
    db.execute(sql["FTS_DELETE_BY_DIARY"], (diary_id,))
    db.execute(sql["FTS_INSERT"], (diary_id, search_text))
    raw = db.execute("SELECT count(*) FROM diary_fts WHERE diary_fts MATCH ?", ('"深圳湾"',)).fetchone()[0]
    check("不分词时整串中文无法命中（切分是必要的）", raw == 0, f"count={raw}")

    # ---------------------------------------------------------------- 搜索 WHERE 形态
    print("\n[搜索语句形态]")
    where = (
        "d.day_key >= ? AND d.day_key <= ? AND "
        "(EXISTS (SELECT 1 FROM diary_fts WHERE diary_fts.diary_id = d.id AND diary_fts MATCH ?) "
        "OR (d.search_text LIKE ? ESCAPE '\\'))"
    )
    db.execute(sql["FTS_DELETE_BY_DIARY"], (diary_id,))
    db.execute(sql["FTS_INSERT"], (diary_id, segment(search_text)))
    rows = db.execute(
        f"SELECT d.id, (SELECT snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24) FROM diary_fts "
        f"WHERE diary_fts.diary_id = d.id AND diary_fts MATCH ? LIMIT 1) AS snip "
        f"FROM diary d WHERE {where} ORDER BY d.day_key DESC LIMIT 201 OFFSET 0;",
        (phrase, "0000-01-01", "9999-12-31", phrase, "%深圳%"),
    ).fetchall()
    check("搜索语句（含 ESCAPE 与 snippet 子查询）语法与绑定顺序正确", len(rows) == 1, str(rows))
    check("snippet 子查询取到高亮片段", bool(rows) and "<hl>" in (rows[0][1] or ""), str(rows))

    count = db.execute(f"SELECT COUNT(*) AS cnt FROM diary d WHERE {where};",
                       ("0000-01-01", "9999-12-31", phrase, "%深圳%")).fetchone()[0]
    check("总数查询与列表查询使用同一 WHERE", count == 1, str(count))

    # ---------------------------------------------------------------- 未记录地点
    print("\n[未记录地点判定]")
    no_loc = sql["NO_LOC_SQL"]
    n = db.execute(f"SELECT COUNT(*) AS cnt FROM diary d WHERE d.day_key >= ? AND d.day_key <= ? AND {no_loc};",
                   ("0000-01-01", "9999-12-31")).fetchone()[0]
    check("有地点的一天不计入「未记录」", n == 0, f"count={n}")

    db.execute("UPDATE edit_block SET country='', region1='', region2='', region3='', loc_text='', "
               "loc_quality='none', latitude=0, longitude=0 WHERE id=2")
    n = db.execute(f"SELECT COUNT(*) AS cnt FROM diary d WHERE d.day_key >= ? AND d.day_key <= ? AND {no_loc};",
                   ("0000-01-01", "9999-12-31")).fetchone()[0]
    check("全部片段无地点时才计入「未记录」", n == 0, f"count={n}（第 1 个片段仍有地点，应为 0）")

    # ---------------------------------------------------------------- 足迹
    print("\n[足迹明细 JOIN]")
    fp = db.execute(sql["FOOTPRINT_ROWS"]).fetchall()
    check("足迹查询返回全部片段且带 day_key/summary", len(fp) == 2 and fp[0][1] == day_key and fp[0][13].startswith("搬来"), str(len(fp)))

    # ---------------------------------------------------------------- 日期重算
    print("\n[day_key 重算]")
    db.execute(sql["UPDATE_DIARY_DAY_KEY"], ("tmp#1", diary_id))
    tmp_ok = db.execute("SELECT day_key FROM diary WHERE id=?", (diary_id,)).fetchone()[0] == "tmp#1"
    check("两阶段重算可用临时键绕开 UNIQUE 冲突", tmp_ok)
    db.execute(sql["UPDATE_DIARY_DAY_KEY"], ("2026-09-16", diary_id))
    db.execute(sql["CLEAR_DIARY_FLAGS"])
    db.execute(sql["REBUILD_DIARY_FLAGS"])
    flags = [r[0] for r in db.execute("SELECT day_key FROM diary_flag")]
    check("diary_flag 可由 diary 整表重建", flags == ["2026-09-16"], str(flags))

    print()
    if failures:
        print(f"❌ {len(failures)} 项失败：")
        for f in failures:
            print(f"   - {f}")
        return 1
    print("✅ 全部 SQL 验证通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
