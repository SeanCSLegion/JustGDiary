# JustADiary (iOS/SwiftUI) → Android(Kotlin) 迁移技术报告

> 来源仓库：`/Users/cov/JustADiary`（只读精读，未做任何修改）
> 目标仓库：`/Users/cov/JustGDiary`（applicationId 固定 `com.cov.justdiary`，见目标仓库 README）
> 报告范围：数据层（SQLite/FTS）、模型、存储布局、备份格式、搜索、足迹聚合、设置、iOS API 依赖、逐项迁移判定
> 证据格式：`文件:行号`，行号以本次精读时的仓库状态为准。

---

## 0. 结论摘要（先看这 10 条）

1. **数据库是"一个日记 = 多个片段(edit_block)"的两级模型**：`diary`（一天一条，`day_key` 唯一）1—N `edit_block`（正文片段，可带时间/位置/富文本 JSON），外键**完全没有**建约束（`PRAGMA foreign_keys=OFF`，`DiaryRepository.swift:23`），一致性全靠代码。
2. **FTS 确认是 FTS5**，建表语句原文：`CREATE VIRTUAL TABLE IF NOT EXISTS diary_fts USING fts5(diary_id UNINDEXED, text, tokenize='unicode61');`（`DiaryRepository.swift:214`）。存储的是**按天**的全部正文拼接，不是按片段。
3. **CJK 分词是"应用层注入空格"方案**，不是真正的分词器：在相邻两个 CJK 字符之间插一个空格，让 `unicode61` 把它们切成单字 token；写入侧（`DiaryRepository.swift:572`）与查询侧（`DiaryRepository.swift:888`）调用**同一个** `FtsSegment.segment`；`desegment` 只用于把结果里的空格去掉做显示（`:928`）。算法规则见 §1.4。
4. **Android 上 `diary_fts` 必须手写 raw SQL**。Room 2.x（`androidx.room`）只有 `@Fts3`/`@Fts4`、没有 FTS5；Room 3.0（`androidx.room3`）才出现 `Fts5`/`FtsOptions`（新包名）。而本项目搜索路径本来就是一整段裸 SQL（`EXISTS(MATCH)` 子查询 + `snippet()`），注解帮不上忙。平台 SQLite 的 FTS5 实际上 API 24+ 就有（[CommonsWare](https://commonsware.com/AndroidArch/pages/chap-roomfts-001.html)），所以 `minSdk ≥ 24` 时 **FTS5 属"自然迁移"，但必须走 `SQLiteOpenHelper`/`RoomDatabase.openHelper.writableDatabase` 的 raw SQL**。
5. **Android 上的中文处理推荐原样复刻 iOS**：继续用 FTS5/FTS4 + `unicode61` + 同一套"相邻 CJK 插空格"。好处是与 iOS 备份**索引二进制兼容**（备份里的 `diary_fts` 行已经是加过空格的文本）。FTS3/4 的 `simple` 与 `unicode61` 都把 CJK 视为 token 字符（连续 CJK 串 = 一个 token），所以降级到 FTS4 也能用同一套算法；只有 `icu` 分词器依赖 `SQLITE_ENABLE_ICU`（Android 平台 SQLite 通常未开启）不可依赖。
6. **时区语义是隐藏的跨端地雷**：`*_utc` 全是 epoch **毫秒** Int64，但 `day_key`（`YYYY-MM-DD`）是用**设备当前时区** + 可配置"日记开始时间"（默认 4 点）现算的（`DateUtil.swift:51-59`），库里**不存时区**。Android 必须用 `ZoneId.systemDefault()` + `dayStartHour` 复刻，否则跨端/跨时区会串天。
7. **足迹页没有任何聚合 SQL**：`allFootprintRows()`（`:330-359`）是一条 JOIN 明细查询，国家/省份/城市三级树、`stats`、年度统计**全部在 Swift 内存里算**（`FootprintDataService.swift:62-136`）。所谓"年度统计 SQL"实际不存在。这正是"不依赖打包地理边界"的含义：只做文本分列的聚合，不需要坐标、也不需要任何 GeoJSON（`FootprintDataService.swift:36-39`）。
8. **备份是"整库 SQLite 快照 + 原图 + manifest"的标准 zip**，扩展名 `.jdiary`：zip 内 `manifest.json` / `db/just_diary.db` / `images/*`（`BackupService.swift:90-101`），manifest `formatVersion == 1`（`:80,161`）。DB 快照用 SQLite Online Backup API（`:217-234`），**Android 没有对应公开 API**，需改 `VACUUM INTO` 或 checkpoint+copy（§5.4）。
9. **图片目录是 `<Documents>/images/`，新图命名 `img_<epochMillis>.jpg`**，入库前降采样到最长边 2048、JPEG q=0.85（`DiaryViewModel.swift:489-497`）；备份导入的图片重命名为 `imp_<stamp>_<n>.<ext>`（`BackupService.swift:186`）；`content_json` 里的引用是相对键 `images/<basename>`（`ContentPart.swift:209-215`）。
10. **设置存 App Group UserDefaults**（`group.com.cov.justdiary`，`AppSettings.swift:30-31`），10 个业务 key + 3 个内部 key；Android 用 DataStore/SharedPreferences 可自然迁移，但 **App Group 的跨进程/跨 App 共享语义在 Android 没有一对一对应**。

---

## 1. 数据库 schema 全量清单

### 1.1 连接与全局 PRAGMA

| 项 | 值 | 证据 |
|---|---|---|
| 打开标志 | `SQLITE_OPEN_CREATE \| SQLITE_OPEN_READWRITE \| SQLITE_OPEN_FULLMUTEX` | `JustDiary/Data/SQLite.swift:15` |
| busy timeout | `sqlite3_busy_timeout(h, 3000)` = **3000 ms** | `SQLite.swift:21` |
| journal | `PRAGMA journal_mode=WAL;` | `SQLite.swift:22` |
| 外键 | `PRAGMA foreign_keys=OFF;` | `SQLite.swift:23` |
| schema 版本 | `PRAGMA user_version`（读写封装 `SQLite.userVersion`） | `SQLite.swift:36-45` |
| 事务 | `BEGIN IMMEDIATE;` … `COMMIT;`，失败 `ROLLBACK` | `SQLite.swift:83-93` |
| 参数绑定 | text/double/int64/bool→int64/NSNumber→double/其他→NULL；`SQLITE_TRANSIENT` | `SQLite.swift:101-120` |
| 列映射 | INTEGER→Int64, FLOAT→Double, TEXT→String, BLOB→Data | `SQLite.swift:124-148` |

> 迁移注意：`FULLMUTEX` + 单串行队列 + `BEGIN IMMEDIATE` 三者共同保证"单写者"。Room 默认用 WAL + 自己的事务执行器；要复刻"立即写锁"语义得用 `beginTransactionNonExclusive()`（WAL 下的 DEFERRED）不够，需要 `execSQL("BEGIN IMMEDIATE")` 或 `beginTransaction()`（EXCLUSIVE）。实际影响很小（单进程单写者），但**`BEGIN IMMEDIATE` 会影响并发读写的锁等待行为**，建议保留。

### 1.2 表 / 索引 DDL 原文

schema 创建集中在 `DiaryRepository.migrateSchema()`（`:94-139`）与 `migrateLocationMeta()`（`:141-208`）。**全仓库没有任何 `CREATE VIEW` / `CREATE TRIGGER`**（对 `CREATE (TABLE|INDEX|VIEW|VIRTUAL|TRIGGER)` 全量 grep，只有 `DiaryRepository.swift` 与 `SQLite.swift` 命中）。

#### (1) `diary` — 一天一条日记（`:97-106`）

```sql
CREATE TABLE IF NOT EXISTS diary (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  day_key TEXT NOT NULL UNIQUE,
  summary TEXT NOT NULL DEFAULT '',
  search_text TEXT NOT NULL DEFAULT '',
  created_utc INTEGER NOT NULL,
  updated_utc INTEGER NOT NULL
);
```

| 列 | 类型 | 约束/默认 | 语义 |
|---|---|---|---|
| `id` | INTEGER | PK AUTOINCREMENT | 自增主键 |
| `day_key` | TEXT | NOT NULL **UNIQUE** | 本地日历日 `YYYY-MM-DD`（受"日记开始时间"影响），是天然业务主键 |
| `summary` | TEXT | NOT NULL DEFAULT `''` | 列表摘要，= `substr(group_concat(block.search_text,' '),1,40)`（`:553-559`） |
| `search_text` | TEXT | NOT NULL DEFAULT `''` | 当天全部片段扁平文本，`group_concat(...,' ')`（`:553-559`） |
| `created_utc` | INTEGER | NOT NULL | epoch **ms**；建天时 = 首个片段的 `start_time_utc`（`:428`） |
| `updated_utc` | INTEGER | NOT NULL | epoch **ms**；随片段写入/更新刷新（`:551-559`） |

#### (2) `edit_block` — 片段（`:107-127`，重建版 `:147-167` 完全同构）

```sql
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
```

| 列 | 类型 | 约束/默认 | 语义（精确取值） |
|---|---|---|---|
| `id` | INTEGER | PK AUTOINCREMENT | 片段主键 |
| `diary_id` | INTEGER | NOT NULL，**无 REFERENCES** | 归属日记（逻辑外键） |
| `start_time_utc` | INTEGER | NOT NULL | 片段发生时刻，epoch ms；决定片段排序（`:325` `ORDER BY start_time_utc ASC`） |
| `loc_text` | TEXT | NOT NULL DEFAULT `''` | 展示用地址文本，`" · "` 连接（`LocationService.swift:122-157`） |
| `latitude`/`longitude` | REAL | NOT NULL DEFAULT `0` | 0/0 视为无坐标（`noLocSql` 与回填都这么判） |
| `content_json` | TEXT | NOT NULL DEFAULT `'[]'` | 富文本文档，当前 v2 信封 `{"v":2,"parts":[...]}`（`ContentPart.swift:153-164`） |
| `search_text` | TEXT | NOT NULL DEFAULT `''` | `ContentFlatten.flattenContent(content_json)`，**部件之间无分隔符**（`:180-182`） |
| `loc_precision` | TEXT | NOT NULL DEFAULT `'none'` | `none/province/city/district/street/exact`（`DiaryModels.swift:84-93`） |
| `loc_quality` | TEXT | NOT NULL DEFAULT `'none'` | `none/coarse/precise`（`DiaryModels.swift:95-99`）；**有坐标即 precise**（`:200-204`） |
| `country`/`country_code` | TEXT | NOT NULL DEFAULT `''` | 反地理编码结果（MapKit `CLPlacemark`） |
| `region1` | TEXT | NOT NULL DEFAULT `''` | 省/一级行政区（`administrativeArea ?? subAdministrativeArea`，`:1021`） |
| `region2` | TEXT | NOT NULL DEFAULT `''` | 市/二级（`subAdministrativeArea ?? locality`，`:1022`） |
| `region3` | TEXT | NOT NULL DEFAULT `''` | 区县（仅当 `locality != nil && subAdministrativeArea != nil` 才取 `subLocality`，`:1023`） |
| `created_utc`/`updated_utc` | INTEGER | NOT NULL | 插入时都 = `start_time_utc`（`:441,462`）；更新时 `updated_utc` = `now`（`:484`） |

**外键：无。触发器：无。**

#### (3) `diary_flag` — 日历圆点用的反规范化表（`:128`）

```sql
CREATE TABLE IF NOT EXISTS diary_flag (day_key TEXT PRIMARY KEY);
```

写入点：`addBlock` 时 `INSERT OR IGNORE`（`:432`）；删除最后一片段时 `DELETE`（`:522`）；导入/重算后整表由 `diary` 重建（`:740-741`、`:796-797`）。读取：`getDiaryFlagsRange`（`:317-321`），Home 用区间字符串比较取当年的点（`HomeViewModel.swift:19-24`）。

#### (4) 索引（`:129-132`，重建路径 `:182-185` 重复创建）

```sql
CREATE INDEX IF NOT EXISTS idx_block_diary           ON edit_block(diary_id);
CREATE INDEX IF NOT EXISTS idx_block_start           ON edit_block(start_time_utc);
CREATE INDEX IF NOT EXISTS idx_block_country_region  ON edit_block(country, region1, diary_id);
CREATE INDEX IF NOT EXISTS idx_block_diary_quality   ON edit_block(diary_id, loc_quality);
```

> 注意：索引建在 `edit_block` 上，**没有任何索引建在 `diary.day_key` 之外的列**（`day_key` 由 UNIQUE 自带索引）；`diary_fts` 是虚拟表自带影子表（`diary_fts_data/_idx/_content/_docsize/_config`）。

#### (5) `diary_fts` — FTS5（`:210-218`）

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS diary_fts USING fts5(diary_id UNINDEXED, text, tokenize='unicode61');
```

- 确认是 **FTS5**（不是 FTS3/4）：`:214`。
- 列 0 = `diary_id UNINDEXED`（不索引、但**会被存储**，所以可以 `WHERE diary_id = ?` 直接删除/定位）；列 1 = `text`（唯一被索引的列，也是 `snippet`/`highlight` 取词的列）。
- tokenizer 显式写死 `unicode61`（SQLite 默认也是 unicode61，但代码显式声明）。
- 是**普通（非 external-content / 非 contentless）FTS5 表**：内容自带存储，`snippet()` 才可用。
- 能力探测：`isFtsTableExists` 查 `sqlite_master`（`:35-37`）；`ensureFtsTable` 用 `do/catch` 把创建失败记为 `ftsSupported = false`（`:210-218`），此后搜索退化为纯 LIKE。

### 1.3 FTS 索引的写入内容与"按天"粒度（迁移必须知道）

- **一条 `diary_fts` 行 = 一天**，不是一片段：`upsertFtsEntry(diaryId:text:)` 先 `DELETE FROM diary_fts WHERE diary_id = ?` 再插一条（`:568-574`）。
- 写进去的 `text` = `FtsSegment.segment(diary.search_text)`，而 `diary.search_text` = 当天所有块 `search_text` 用 **空格** 连接（`:553-559`）。
- 块的 `search_text` = 各部件文本 **无分隔符** 连接：`flattenPart` 对 `items` 用 `items.joined()`（无分隔符，`ContentPart.swift:174-176`），`flattenContent` 用 `map(flattenPart).joined()`（无分隔符，`:180-182`）。**后果**：相邻段落的边界不产生空格，中文跨段落会连成一片；对 FTS 分词无害（反正逐字切开），但 LIKE 回退与摘要的切分点会受影响。
- 重建索引：`rebuildSearchIndex()` 分 500 行一批事务，重算 `search_text`、`diary.summary/search_text`（用 `group_concat`）、清空并重灌 `diary_fts`，最后写 `search_index_version`（`:257-285`）。触发条件：`SettingsStore.searchIndexVersion < DiaryRepository.searchIndexVersion(=3)`（`:13-14,31-33,249-255`）。

### 1.4 `FtsSegment.swift` 的 CJK 逐字规则（精确）

文件：`JustDiary/Services/FtsSegment.swift`（共 72 行）。

**isCJK（`:4-9`）** —— 只看 `Character` 的**第一个** Unicode scalar：

```swift
guard let scalar = ch.unicodeScalars.first else { return false }
return (0x4E00...0x9FFF).contains(scalar.value)   // CJK 统一表意文字
    || (0x3400...0x4DBF).contains(scalar.value)   // 扩展 A
    || (0x3000...0x303F).contains(scalar.value)   // CJK 符号与标点
```

**segment（写入前/查询前，`:11-23`）** —— 相邻两个 CJK 之间插一个 U+0020，其余不动：

```swift
for ch in text {
    if let p = prev, isCJK(p), isCJK(ch) { out.append(" ") }
    out.append(ch); prev = ch
}
```

精确行为（可直接照抄成 Kotlin 单测）：
- `"深圳湾"` → `"深 圳 湾"`
- `"abc深圳"` → `"abc深 圳"`（中英边界**不**插空格）
- `"深圳abc"` → `"深 圳abc"`
- `"你好，世界"` → `"你 好 ， 世 界"`（U+FF0C 全角逗号**不在** `0x3000-0x303F`，所以**不**插空格：`"你 好，世 界"`）
- `"你好。世界"` → `"你 好 。 世 界"`（U+3002 在范围内，插）
- 扩展 A 汉字、CJK 标点（、。〈〉《》「」等）都被当作 CJK。
- **未覆盖**：日文假名（U+3040-30FF 除 3000-303F 外）、韩文谚文（U+AC00-D7AF）、CJK 兼容表意（U+F900-FAFF）、香港增补字（U+2xxx 区）。这些字符在 `unicode61` 下属于 category `Lo`（token 字符），**连续一段会变成一个 token**——即"假名/韩文整句只能整串匹配"，这是与 iOS 一致的行为（iOS 也这样），迁移时**保持现状**即可，不要"顺手修好"以免与 iOS 语义分叉。

**desegment（仅显示层，`:25-71`）**：
- 逐个字符扫描；遇到空格时向后跳过 `<...>` 标签找下一个非空格字符，若"前一个非空格字符是 CJK"且"下一个非空格字符是 CJK"，则**丢掉这个空格**；否则保留。
- `<` 到 `>` 之间的内容原样整体拷贝（保护 `<hl>`/`</hl>`），且标签不参与 `lastWasCJK` 传递。
- 不处理 `…`（FTS5 snippet 的省略号），`…` 旁边的空格会保留。
- 只在 snippet 展示路径调用一次（`:928`）；UI 侧高亮另有 `SearchUtil.highlightSnippet`（`SearchUtil.swift:61-82`）。

**写入/查询是否同一套逻辑**：**是**。
- 写入：`upsertFtsEntry` → `FtsSegment.segment(searchText)`（`:572`）。
- 查询：`runSearch` → 每个用户词 `FtsSegment.segment($0.replacingOccurrences(of: "\"", with: ""))`（`:888`）。
- 唯一的不对称是显示用的 `desegment`，它不参与 MATCH。

**字符规范化（大小写 / 全角半角）**：
- **应用层完全没有做任何归一化**：没有 lowercased、没有 `precomposedStringWithCanonicalMapping`、没有 NFKC、没有全角转半角。大小写/全半角差异**全部交给 SQLite `unicode61`**。
- `unicode61` 的行为（[SQLite FTS5 官方文档 §4.3.1](https://www.sqlite.org/fts5.html)、FTS3/4 文档同）：token 字符 = Unicode 6.1 general category `L*`、`N*`、`Co`；其余为分隔符；**按 Unicode 6.1 规则做大小写折叠**；默认 `remove_diacritics=1` 会去掉拉丁字母的变音符（`A/a/À/à` 等价）。
- 由此推出的确切结论：`unicode61` **不做宽度归一（无 NFKC）**。全角拉丁 `Ａ`(U+FF21) 只会折叠到全角 `ａ`(U+FF41)，**不会**变成 ASCII `a`；全角数字同理。所以 `"ABC"` 搜不到 `"ＡＢＣ"`，iOS/Android 都一样。
- LIKE 回退路径（`:889-890`）**零归一化**，靠 SQLite `LIKE` 的默认 ASCII-only 大小写不敏感；中文/全角无任何等价处理。

### 1.5 迁移/演进链（Android 需要等价实现）

| 阶段 | 条件 | 动作 | 证据 |
|---|---|---|---|
| v0 → v1 | `user_version < 1` | 建 `diary`/`edit_block`/`diary_flag` + 4 索引；`user_version = 1` | `:97-134` |
| v1 → v3 | `user_version < 3` | `migrateLocationMeta()`；`user_version = 3` | `:135-138` |
| 位置元数据重建 | `edit_block` 无 `region1` 列 | 建 `edit_block_new` → 用 `province/city/district` 拷到 `region1/2/3`（缺失列用 `''`）→ `DROP` 旧表 → `RENAME` → 重建 4 索引（`:145-186`） | `:141-186` |
| 增量补列 | 有 `region1` 但无 `loc_quality` | `ALTER TABLE edit_block ADD COLUMN loc_quality TEXT NOT NULL DEFAULT 'none';` | `:187-189` |
| 回填精度 | `loc_precision IN ('none','')` | `region3→district`，`region2→city`，`region1→province`，`loc_text非空→province`，否则 `none` | `:190-199` |
| 回填质量 | `loc_quality IN ('none','')` | 坐标非 0 → `precise`，否则 `coarse` | `:200-204` |
| 修正 region1 | `region1='' AND region2<>''` | `region1 = region2` | `:205-207` |
| content 格式迁移 | `content_format_version < 2` | 每 500 行一个事务，`ImagePathUtil.normalizeContent` 重写 `content_json`；文本不变故不重建 FTS；写 `content_format_version = 2` | `:228-247`（版本常量 `ContentPart.swift:155`） |
| 索引回填 | `search_index_version < 3` 或 FTS 表缺失 | `rebuildSearchIndex()` | `:249-255,257-285` |

> **乐观迁移**：`migrateSchema` 里 `let v = db.userVersion` 只读一次，v0 库会先建表（`userVersion=1`）再继续走 `v<3` 分支（`v` 仍是 0），这是有意的"顺序执行"。
> **迁移失败被吞**：`prepare()` 用 `do/catch` 只记日志（`:59-66`），不会把错误抛给 UI；Android 端若用 Room 的 `Migration`，失败会直接抛 `IllegalStateException` 崩溃——需要决定是复刻"静默降级"还是收紧。

### 1.6 与 Room 的 schema 兼容性（重要）

- 如果要**直接用 Room 打开 iOS 导出的 `.db` 文件**：不可行。Room 会在库里维护 `room_master_table`（identity hash），且启动时校验实体与真实 schema；iOS 库里没有这张表，校验会失败。可选做法：(a) 导入时用**裸 `SQLiteDatabase`** 打开备份库、通过 DAO/裸 SQL 灌进 Room 库；(b) 整个数据层放弃 Room，直接用 `SQLiteOpenHelper`（与 iOS 的 `SQLite.swift` 封装一一对应，最省心）；(c) 用 `createFromFile` + 完全对齐的 schema + 手工写入正确的 identity hash（脆弱，不推荐）。
- 若用 Room 且只做"新建库"：`@Entity` 里**不要加外键/触发器**（iOS 没有），`@PrimaryKey(autoGenerate = true)` 对应 `INTEGER PRIMARY KEY AUTOINCREMENT`（Room 生成的就是 AUTOINCREMENT），`day_key` 要 `@ColumnInfo` + `@Index(unique = true)`（索引名会变成 Room 默认命名 `index_diary_day_key`，与 iOS 的 UNIQUE 约束索引名不同——只要是新建库无所谓；若要严格对齐就用裸 SQL 建表）。
- `diary_flag` 是 TEXT 主键表，Room 支持。

---

## 2. 磁盘存储布局

| 内容 | 路径 / 命名 | 常量与证据 |
|---|---|---|
| 数据库 | `<Documents>/just_diary.db` | `DiaryRepository.dbPath()` `:39-43`；可用 `dbPathOverride` 覆盖（`:28`） |
| WAL 边车 | `just_diary.db-wal` / `-shm` | `PRAGMA journal_mode=WAL`（`SQLite.swift:22`） |
| 图片目录 | `<Documents>/images/` | `imagesDir()` `:45-49`；`imagesDirOverride`（`:29`） |
| 新插入图片 | `img_<Int64(epochMs)>.jpg` | `DiaryViewModel.swift:490-497` |
| 插入前降采样 | 最长边 **2048 px**，JPEG **q=0.85** | `DiaryViewModel.swift:489-490` |
| 备份导入的图片 | `imp_<stamp>_<序号>.jpg`，`stamp` = 导出/导入开始时的 epochMs（`:144`），扩展名缺失时默认 `jpg` | `BackupService.swift:185-192` |
| `content_json` 里的图片引用 | `images/<basename>`（相对键，永远带 `images/` 前缀） | `ImagePathUtil.normalizeSrcKey` `ContentPart.swift:209-215`；解析 `resolveImagePath` `:233-237` |
| 图片显示降采样 1 | 富文本：`max(displayW, displayH) * 3` | `Views/Diary/MediaViews.swift:25`、`Views/Diary/RichTextEngine.swift:827` |
| 图片显示降采样 2 | 缩略图：**1600 px** | `Views/Diary/MediaViews.swift:151` |
| 图片内存缓存 | `NSCache`：`countLimit = 200`，`totalCostLimit = 150 * 1024 * 1024`（150 MB）；cost = `bytesPerRow * height` | `Services/MediaStore.swift:50-66` |
| 纯降采样实现 | `jpegData(q:0.9)` 后 `CGImageSourceCreateThumbnailAtIndex`（`kCGImageSourceThumbnailMaxPixelSize`，`CreateThumbnailFromImageAlways`，`WithTransform`） | `MediaStore.swift:78-118` |
| ContentPart 解析缓存 | 容量 **300**，FIFO 淘汰（`order.removeFirst()`） | `MediaStore.swift:7-37` |
| 导出临时目录 | `<tmp>/jdiary_export_<epochMs>/`，内含 `db/`、`images/` | `BackupService.swift:36-46` |
| 导出产物 | `<tmp>/JustDiary_yyyyMMdd_HHmmss.jdiary` | `:103-108` |
| 导入临时目录 | `<tmp>/jdiary_import_<epochMs>/`，备份库落到 `<tmp>/db/backup.db` | `:144-175` |
| UI 测试日志 | `<NSHomeDirectory()>/Documents/import-result.log`、`import-ok.log`、`import-fail.log` | `:125-138` |
| 布局调试日志 | `.../chrome.log`（仅调试） | `Views/Components/AdaptiveLayout.swift:213-217` |

其它：
- 图片文件被引用计数清理：`cleanupImages(from:to:)`（编辑保存后删旧图，`:576-584`）、`removeUnreferencedImages`（删除片段后删图，`:586-593`）、`cleanupOrphanImages()`（启动维护，扫描全部 `content_json` 收集引用再删目录里未被引用的文件，`:595-615`）。
- 导出时图片会**按 basename 去重改名**：冲突时 `name_1.jpg`、`name_2.jpg`（`BackupService.swift:58-66`）。
- 图片没有"缩略图文件"落盘：所有缩略都是运行时降采样 + 内存缓存。Android 迁移时若需要落盘缩略图缓存，是新增能力（可选）。

---

## 3. 关键数据模型

### 3.1 实体定义（全部为 `struct`，值语义，**所有字段非可空**）

`JustDiary/Models/DiaryModels.swift`：

| 类型 | 字段（类型，非空性） | 行号 |
|---|---|---|
| `DiaryRecord` | `id: Int64`、`dayKey: String`、`summary: String`、`searchText: String`、`createdUtc: Int64`、`updatedUtc: Int64` | `:3-10` |
| `EditBlock` | `id`、`diaryId`、`startTimeUtc`(Int64)、`locText`(String)、`latitude`/`longitude`(Double)、`contentJson`、`searchText`、`locPrecision`、`locQuality`、`country`、`countryCode`、`region1`、`region2`、`region3`(String)、`createdUtc`/`updatedUtc`(Int64) | `:12-30` |
| `LocRegion` | `country`、`countryCode`、`region1`、`region2`、`region3`、`locQuality`（全 String） | `:32-39` |
| `LocFilter` | `country`、`region1`、`noLoc: Bool` | `:41-45` |
| `LocOption` | `country`、`region1`、`count: Int`（Hashable） | `:47-51` |
| `FootprintRow` | `id`、`dayKey`、`startTimeUtc`、`latitude`、`longitude`、`locText`、`locQuality`、`locPrecision`、`country`、`countryCode`、`region1..3`、`summary` | `:53-68` |
| `SearchResultItem` | `id`、`dayKey`、`summary`、`snippet`、`updatedUtc` | `:70-76` |
| `SearchPageResult` | `items: [SearchResultItem]`、`hasMore: Bool`、`total: Int`（`total == -1` 表示库未就绪） | `:78-82,881` |

枚举常量（都是 `String`，持久化值稳定，**未知值必须能解码不抛错**）：
- `LocPrecision`：`none/province/city/district/street/exact`，`all = [exact, street, district, city, province]`（`:84-93`）。
- `LocQuality`：`none/coarse/precise`（`:95-99`）。

### 3.2 富文本模型（`content_json`）

`JustDiary/Models/ContentPart.swift`：

- `TextRun`（`:11-57`）：`text: String` **必填**；`bold/italic/strike/underline: Bool?` 可空。语义约定：
  - 解码时"存在但为 `false`"→ 规范化为 `nil`（`flag()` `:54-56`）；
  - 编码时**只写开启的样式**（`if bold == true { encode }`，`:45-52`）；
  - 未知 key（v1 的 `size`）被忽略（`Codable` 默认行为 + 注释 `:31-34`）。
- `ContentPart`（`:61-115`）：`style: String` 必填（解码时缺省回落到 `"body"`，`:105`）；可空字段 `text`、`items: [String]?`、`done: [Bool]?`、`src: String?`、`runs: [TextRun]?`、`w: Double?`、`h: Double?`、`align: String?`。兼容 v1：`style` 缺失时读旧键 `type`（`:80-83,101-104`）。
- `ContentPartStyle`（`:123-145`）：`title/heading/body/list/todo/quote/image`；v1 HTML 名映射 `h1→title`、`h2→heading`、`p→body`、`ul→list`、`img→image`（`:134-140`），`normalized()` 应用映射（`:142-144`）。
- `ContentDocument`（`:153-164`）：`v: Int`、`parts: [ContentPart]`；**`currentVersion = 2`**（`:155`）。
- `ContentFlatten`：
  - `flattenPart`（`:167-178`）：优先级 `runs` → `text` → `items`（`items.joined()` **无分隔符**）→ `""`；
  - `flattenContent`（`:180-182`）：`parts.map(flattenPart).joined()`（**无分隔符**）；
  - `parseContent`（`:184-194`）：先试 v2 信封 `ContentDocument`，失败再试裸数组 v1，解析失败返回 `[]`；
  - `serializeContent`（`:198-205`）：`JSONEncoder` + `.sortedKeys` + `.withoutEscapingSlashes`；编码失败返回 `"{}"`。
  - **序列化是确定性的**（key 排序、不转义 `/`），所以 `DiaryRepository.swift:238` 的 `if normalized != json` 字符串比较才成立；Android 若用 kotlinx.serialization 的默认输出会因 key 顺序/转义不同而"永远不相等"，导致每次启动都重写全库——**必须显式配置确定性输出或用解析后对象比较**。
- `ImagePathUtil`（`:208-242`）：`normalizeSrcKey`、`normalizeContent`（顺带做 v1→v2 升级）、`resolveImagePath`、`collectImageSrcs`。

### 3.3 时间戳语义与时区（精确）

| 事实 | 证据 |
|---|---|
| 所有 `*_utc` 是 **epoch 毫秒** `Int64`（不是秒、不是 SQLite 时间字符串） | `Int64(Date().timeIntervalSince1970 * 1000)` `:484,528,678`；`BackupService.swift:36,144` |
| `day_key` 是 **本地日历日** `YYYY-MM-DD`（零填充，字符串比较即时间序） | `DateUtil.dayKeyOf` `DateUtil.swift:11-14` |
| 日历 = 公历 + `TimeZone.current` + `Locale.current` | `DateUtil.swift:4-9` |
| `day_key` 由"本地小时 < `dayStartHour` 则算前一天"决定 | `DateUtil.dayKeyForUtc` `:51-59` |
| `dayStartHour` 默认 **4**，可被用户改（改后触发全库 `recomputeDayKeys`） | `AppSettings.swift:4`；`SettingsViewModel.applyDayStart()`；`DiaryRepository.recomputeDayKeys` `:749-800` |
| 库里**不存时区**，不存 UTC 日期串 | schema §1.2 无任何时区列 |
| 片段排序用 `start_time_utc ASC` | `:325` |
| 搜索/足迹的时间筛选是 `day_key` 字符串区间比较 | `:884`、`SearchViewModel.swift:10-29` |

**跨端含义**：`day_key` 是"写入当时的设备本地时区 + 当日 4 点"派生值，且**持久化**。Android 必须：
1. 用 `Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())` + `LocalTime` 比较 `dayStartHour`；
2. `recomputeDayKeys` 用**当前**时区重算（与 iOS 一致，会因用户换时区而变化）；
3. 字符串排序保持 `LocalDate.toString()`（ISO-8601 零填充）。

`recomputeDayKeys` 的冲突处理很讲究（`:749-800`）：先用 `tmp#<id>` 占位避免 `day_key UNIQUE` 冲突，再写最终值；当目标 key 被另一条也在移动的记录占用且该记录目标不同，则记为 `conflicts` 并跳过（返回给设置页提示）。

### 3.4 "日记 = 多片段"关系模型

- 关系：`diary 1 ── N edit_block`，连接列 `edit_block.diary_id`；**没有物理外键**，也没有级联删除。
- 一天最多一条 `diary`（`day_key UNIQUE`）。**片段的 `start_time_utc` 可以落在任意天**；归属由写入时的 `dayStartHour` 规则决定（`addBlock` `:425`）。
- 生命周期：
  - 新建：`addBlock` 先 `INSERT OR IGNORE INTO diary(day_key,...)`，再查 `id`，`INSERT OR IGNORE diary_flag`，再插 block，最后 `touchDiaryTx` 刷新摘要（`:426-445`）。
  - 挂到已有日记：`addBlockToDiary`（编辑页"继续写"路径，`DiaryViewModel.swift:289`），只插 block + touch。
  - 改内容：`updateBlockContent`（`:477-501`），**只改 `content_json/search_text/updated_utc`，可选只改 `loc_text/loc_precision`**；坐标与 `region*` 永不在编辑态改写（位置是"写入时刻的事实"，见 `docs/location-recording.md`）。
  - 删片段：`deleteBlocks`，若某日记剩 0 块则连 `diary` + `diary_flag` + `diary_fts` 一起删（`:503-537`）。
  - 删一天：`deleteDiaryByDay` = 查日记 → 查块 id → `deleteBlocks`（`:545-549`）。
- `summary`/`search_text` 是**派生列**，每次写块都重算（`touchDiaryTx` `:551-566`）——这是全库最热的一条 SQL（含两个相关子查询）。

---

## 4. `DiaryRepository` 完整公开 API 清单

文件：`JustDiary/Data/DiaryRepository.swift`。类型为 `nonisolated final class`，单例 `static let shared`（`:11-12`）。

### 4.0 常量、静态状态、生命周期

| 成员 | 签名 / 值 | 语义与证据 |
|---|---|---|
| `searchLimit` | `static let = 200` | 单页上限；查询多取 1 条判断 `hasMore`（`:13,915,940-941`） |
| `searchIndexVersion` | `static let = 3` | 索引格式版本，存 UserDefaults `search_index_version`（`:14,31-33`） |
| `dbPathOverride` | `static var String?` | 测试注入 DB 路径（`:28`） |
| `imagesDirOverride` | `static var URL?` | 测试注入图片目录（`:29`） |
| `dbPath()` | `static func -> String` | `<Documents>/just_diary.db`（`:39-43`） |
| `imagesDir()` | `static func -> URL` | `<Documents>/images`（`:45-49`） |
| `prepare()` | `async` | 打开库 + 建目录 + 迁移 + FTS + content 迁移 + 索引回填，然后 `scheduleBackgroundMaintenance()`；`db != nil` 时幂等返回（`:51-72`） |
| `bumpDiaryVersion()` | `void` | 主线程 post `.diaryVersionChanged`，否则切主线程 post（`:74-82`） |
| `bumpUiTick()` | `void` | 同上，`.uiTickChanged`（`:84-92`） |
| `scheduleBackgroundMaintenance()` | `async` | = `ensureSearchIndex()` → `backfillBlockRegions()` → `cleanupOrphanImages()`（`:297-301`） |

### 4.1 CRUD / 写入（全部 `async throws`，除非注明）

| 方法签名（省略 `await`/`try`） | 语义 | 事务/并发 | 证据 |
|---|---|---|---|
| `addBlock(startTimeUtc: Int64, locText: String, latitude: Double, longitude: Double, locPrecision: String, region: LocRegion, contentJson: String, dayStartHour: Int) -> Int64` | 新建片段；按 `dayStartHour` 算 `day_key`，必要时建天，插 `diary_flag`，插块（含 `flattenContent` 生成的 `search_text` 与 `region.*`），`touchDiaryTx`，返回 blockId | 单事务（`inTransaction`，`BEGIN IMMEDIATE`） | `:421-447` |
| `addBlockToDiary(diaryId: Int64, startTimeUtc:..., locPrecision: String, region: LocRegion, contentJson: String) -> Int64` | 往已有日记追加片段；不建天、不碰 `diary_flag`；`touchDiaryTx` | 单事务 | `:449-468` |
| `updateBlockContent(blockId: Int64, contentJson: String, locText: String? = nil, locPrecision: String? = nil) throws -> Void` | 更新正文（normalize 后写 `content_json` + `search_text` + `updated_utc=now`）；**仅当两个可选位置参数同时非 nil** 才写 `loc_text`/`loc_precision`；随后 `touchDiaryTx`；事务提交后删掉不再引用的旧图 | 单事务 + 提交后文件清理 | `:477-501` |
| `deleteBlocks(_ ids: [Int64]) throws -> Void` | 批量删片段；先查出被删的 `content_json` 与受影响的 `diary_id`；删块；对每个日记：若剩 0 块则删 `diary`/`diary_flag`/`diary_fts`，否则 `touchDiaryTx`；最后删图片文件 | 单事务（文件删除在事务外） | `:503-537` |
| `deleteDiaryByDay(_ dayKey: String) async` | 删掉某天的全部片段（`getDiaryByDay` → `getBlocks` → `deleteBlocks`）；失败静默 `try?` | 多事务组合（非原子） | `:545-549` |
| `recomputeDayKeys(dayStartHour: Int) -> Int` | 改"日记开始时间"后按各天最小 `start_time_utc` 重算 `day_key`；用 `tmp#<id>` 两阶段写避免 UNIQUE 冲突；重建 `diary_flag`；返回冲突数 | 非事务（逐条 UPDATE，`try?` 包裹） | `:749-800` |
| `updateBlockRegions(id:country:countryCode:region1:region2:region3:locQuality:) async` | 反地理编码回填；**`WHERE id=? AND region1=''`** 做幂等/防覆盖 | 单条 UPDATE，无显式事务 | `:823-832` |
| `markRegionGeocodeSkipped(_ ids: [Int64])`（**非 async**） | 把反地理编码失败的 id 追加进 UserDefaults `region_geocode_skip_v2`，只保留最后 **2000** 个 | 直接写 UserDefaults | `:834-845` |

### 4.2 查询（只读）

| 方法签名 | 语义 | 证据 |
|---|---|---|
| `getDiaryByDay(_ dayKey: String) -> DiaryRecord?` | `SELECT * FROM diary WHERE day_key = ?` | `:309-315` |
| `getDiaryFlagsRange(fromKey: String, toKey: String) -> [String]` | 日历圆点；`day_key >= ? AND <= ?`，字符串区间 | `:317-321` |
| `getBlocks(diaryId: Int64) -> [EditBlock]` | `ORDER BY start_time_utc ASC` | `:323-328` |
| `allFootprintRows() -> [FootprintRow]` | 足迹页唯一数据源：`edit_block JOIN diary` 明细，`ORDER BY b.start_time_utc ASC`，带 `d.summary` | `:330-359` |
| `getLocationOptions(keyword:fromKey:toKey:) -> (options: [LocOption], noLocCount: Int)` | 地点筛选候选：①有省的行 `(country,region1)` 计数（`region1<>'' AND country<>''`）②只有国家的行 `(country,'')`；按 `country\u{1}region1` 合并；**取前 20**，排序 `count DESC, (country+region1) ASC`；另查 `noLocCount` | `:361-417` |

### 4.3 搜索与索引

| 方法签名 | 语义 | 证据 |
|---|---|---|
| `search(keyword:fromKey:toKey:filter:offset:) -> SearchPageResult` | 全库搜索一页；见 §6 | `:871-877`（实现 `:879-943`） |
| `ensureSearchIndex() async` | 索引未就绪则 `rebuildSearchIndex()`（`try?` 吞错） | `:287-295` |
| `searchIndexReady() -> Bool`（**非 async**） | `SettingsStore.searchIndexVersion >= 3` | `:303-305` |

### 4.4 统计 / 备份 / 维护

| 方法签名 | 语义 | 证据 |
|---|---|---|
| `rawBlocksForBackup() -> [(Int64, String)]` | 导出用：全部 `(blockId, content_json)` | `:619-624` |
| `countDiaries() -> Int` | `SELECT COUNT(*) FROM diary` | `:626-631` |
| `countBlocks() -> Int` | `SELECT COUNT(*) FROM edit_block` | `:633-638` |
| `mergeBackupDatabase(path: String, mode: String, copyImage: @escaping (String) -> String, stats: inout BackupStats) throws` | 把备份库合并进主库；`mode == "skip"` 跳过已存在的天，否则整天覆盖；见 §5.3 | `:640-745` |
| `blocksNeedingRegionBackfill(limit: Int) -> [(id: Int64, latitude: Double, longitude: Double)]` | 取 `(lat<>0 OR lng<>0) AND region1=''` 的前 `limit` 条，排除 skip 名单 | `:804-821` |
| `backfillBlockRegions() async` | 每次最多 **30** 条，逐条 `ReverseGeocoder.reverse`，成功的写回、失败的进 skip 名单 | `:847-867` |
| `cleanupOrphanImages() async` | 扫描全部 `content_json` 收集 `src`，删除 `images/` 下未被引用的文件 | `:595-615` |

### 4.5 并发模型（`runOnQueue`）

```swift
private let queue = DispatchQueue(label: "com.cov.justdiary.db")          // :17
private func runOnQueue<T>(_ block: @escaping () -> T) async -> T          // :978-984
private func runOnQueue<T>(_ block: @escaping () throws -> T) async throws -> T  // :986-996
```

- **所有** DB 访问都在这一条串行队列上；`async` 只是把结果 `withCheckedContinuation` 桥回来。因此 `SQLite` 句柄实际是**单线程**使用，`FULLMUTEX` 只是保险。
- 事务边界：`inTransaction` 用 `BEGIN IMMEDIATE`（`SQLite.swift:83-93`）。没有嵌套事务支持。
- 调用方（ViewModel）并发触发多个 async 方法时，**顺序不确定但不会交错**，因为队列串行。
- `mergeBackupDatabase` 用 `stats: inout BackupStats` 跨越 async 边界，函数内先 `var working = stats` 再在队列里累加，最后 `stats = working`（`:642,744`）。Android 应改成返回 `BackupStats`（Kotlin 不能对 `inout` 参数做跨线程写）。
- 非 DB 的共享状态：`SettingsStore` 用 `LockedBox<AppSettings?>` + `NSLock` 缓存（`AppSettings.swift:33,89-108`）；`ContentPartCache` 用 `NSLock`（`MediaStore.swift:10-11`）；`DiaryImageStore` 用 `NSCache`（线程安全自带）。
- Android 建议：`Dispatchers.IO.limitedParallelism(1)` 或 Room 的单连接 + `withTransaction`；`SettingsStore` 用 DataStore（自带线程安全）。

---

## 5. 备份 / 导入导出格式

实现：`JustDiary/Services/BackupService.swift`（247 行）+ `JustDiary/Services/ZipArchive.swift`（250 行）。

### 5.1 容器：自实现的 ZIP（非 Zip64、非加密、无时间戳）

写（`ZipArchive.create` `:11-81`）：
- 结构：每个 entry = local file header(30B) + name + data；最后 central directory + EOCD。签名 `0x04034b50` / `0x02014b50` / `0x06054b50`。
- `version needed to extract = 20`（2.0）、`general purpose flag = 0`、`last mod time/date = 0`、`internal/external attrs = 0`、`extra/comment = 0`。
- 压缩：`compressRawDeflate`（`:148-162`）用 Apple `compression_encode_buffer(..., COMPRESSION_ZLIB)`。注释明确说明"实测 Apple Compression 的 COMPRESSION_ZLIB 输出本身就是 raw deflate（无 zlib 头），直接写入即为标准 zip method 8"（`:144-147`）。**压缩失败或压缩后不小于原始大小 → 退回 method 0（store）**（`:160`）。
- `crc32` 用标准多项式 `0xEDB88320` 查表（`:205-224`），初始/末尾取反。
- 大小上限：name ≤ UInt16.max，data ≤ UInt32.max，否则 `ZipError.tooLarge`（`:17-18`）——**没有 Zip64**。

读（`ZipArchive.extract` `:83-127`）：
- 从尾部向前最多 `22 + 65535` 字节找 EOCD（`:129-140`），读 central directory 的 `centralStart`/`count`。
- 每个 entry 从 central directory 读 method/crc/compSize/uncompSize/nameLen/extraLen/commentLen/localOffset，再到 local header 拿真实数据偏移（`:89-112`）。
- method 只支持 **0（store）与 8（deflate）**，其它抛 `unsupportedMethod`（`:115-121`）。
- 解压用 `compression_decode_buffer(..., COMPRESSION_ZLIB)`；若失败且检测到 zlib 包装（`(raw[0] & 0x0F) == 0x08 && ((b0<<8|b1) % 31 == 0)`），剥掉 2 字节头 + 4 字节 adler32 再试（`:166-182`）。
- **强校验**：`result.count == uncompSize`（`:122`）且 `CRC32.compute(result) == expectedCrc`（`:123`），否则 `ZipError.invalid` / `.checksumMismatch`。
- 返回 `[String: Data]`，**整个备份一次性读进内存**（`:150-151`）。

### 5.2 zip 内文件清单与 manifest

导出（`:32-112`）：

```
manifest.json
db/just_diary.db
images/<basename>[.jpg/png/...]
```

`BackupManifest`（`:5-15`，Swift `Codable`，输出是 Swift JSONEncoder 的默认（未排序）key 顺序）：

| 字段 | 类型 | 值 | 证据 |
|---|---|---|---|
| `formatVersion` | Int | **1**（硬编码；导入要求 `== 1`） | `:80,161` |
| `appVersion` | String | `SettingsStore.appVersion` = `CFBundleShortVersionString`，缺省 `"1.0"` | `:81`、`AppSettings.swift:84-86` |
| `exportedAt` | Int64 | epoch **ms** | `:82,36` |
| `includeSettings` | Bool | 用户在设置页勾选 | `:83` |
| `diaryCount` | Int | `COUNT(*) FROM diary` | `:77,84` |
| `blockCount` | Int | `COUNT(*) FROM edit_block` | `:78,85` |
| `imageCount` | Int | 实际拷贝成功的图片数 | `:86` |
| `images` | `[BackupImageRef]` | `{src: "images/x.jpg", file: "images/x.jpg"}`（`file` 在 zip 内是路径，`src` 是 DB 里的键） | `:17-20,51-74` |
| `settingsJson` | String | `AppSettings` 的 JSON 字符串（**snake_case key**，见 §8）；`includeSettings == false` 时 `""` | `:88,236-240` |

导出流程要点：
- 先 `ensureSearchIndex()` 保证导出库索引是最新的（`:35`）。
- DB 快照用 **SQLite Online Backup API**：`sqlite3_open_v2(..., SQLITE_OPEN_READONLY)` 源 + `sqlite3_backup_init/step(-1)/finish`（`:217-234`）→ 快照包含 WAL 中未 checkpoint 的数据。
- 图片从 `rawBlocksForBackup()` 的 `content_json` 解析出 `src` 收集（只导被引用的、且文件存在的），按 basename 去重改名（`:50-74`）。
- 文件名 `JustDiary_yyyyMMdd_HHmmss.jdiary`（`DateFormatter` 用设备时区，`:103-105`），落在 `FileManager.temporaryDirectory`。

### 5.3 导入流程与兼容处理

`importBackup(fileURL:mode:)`（`:142-215`）：

1. 读整个文件 → `ZipArchive.extract`（`:150-151`）。
2. manifest 查找顺序：`manifest.json` → `jdiary_export/manifest.json`（**兼容旧的带目录前缀布局**，`:153-159`）；找不到抛 `BackupError.invalidManifest`。
3. 版本校验：`formatVersion == 1`，否则 `invalidVersion`（`:161`）。**没有向后兼容的迁移分支**——v2 格式必须在新旧端同时升级。
4. DB 查找：`db/just_diary.db` → `jdiary_export/db/just_diary.db`（`:171-173`），写到 `<tmp>/db/backup.db`。
5. 图片：所有 `images/` 前缀 entry 收集进内存字典（`:167-170`）。
6. `srcMap = manifest.images` 的 `src → file`（`:177`）。
7. `copyImage(src)` 闭包（`:181-199`）：把备份里的图片写到 `<Documents>/images/imp_<stamp>_<n>.<ext>`，返回新键 `images/<newName>`；同一 `src` 在一天内复用映射；扩展名缺失时默认 `jpg`；写失败返回原键（不中断导入）。
8. `repo.mergeBackupDatabase(path:mode:copyImage:stats:)`（`:204`）。
9. 若 `manifest.settingsJson` 非空且能解码成 `AppSettings` → `SettingsStore.save` 并置 `settingsRestored = true`（`:207-211`）。
10. `stats.importedImages = importedImages`（闭包计数回填，`:205`）。

`mergeBackupDatabase`（`:640-745`）的兼容/校验细节：
- 通过 `PRAGMA table_info(edit_block)` 探测备份库的老 schema：`hasRegion(region1)`、`hasQuality(loc_quality)`、`hasCountryCode(country_code)`（`:650-653`）。
- 逐天处理（备份库 `ORDER BY day_key ASC`，`:654`）：
  - `mode == "skip"` 且主库已有该 `day_key` → `skippedDays++`，跳过（`:658-661`）。
  - 否则先算 `importedDays++` / `overwrittenDays++`（`:674-677`），主库事务内：已存在则删 `edit_block` + `diary` + `diary_flag` + `diary_fts`（`:666-674`），再插 `diary`（沿用备份 `created_utc`/`updated_utc`，缺失用 now，`:679-680`）。
  - 每个块：`ImagePathUtil.normalizeContent`（v1→v2 + 图片路径归一）→ `parseContent` → 逐 part 用 `copyImage` 改写 `src` → `serializeContent` → 算 `search_text`（`:685-694`）。
  - `loc_quality`：有列用列值；否则由 `lat/lng` 是否为 0 推导 `precise/coarse`（`:695-702`）。
  - `region1/2/3`：新 schema 用 `region1/2/3`；旧 schema 回落 `province/city/district`（`:719-721`）。
  - `dayText` 累加块的 `search_text`（**空格连接**），非空则写 `diary.summary = prefix(40)` 与 `search_text`（`:725-731`）。
  - FTS 行重建：`upsertFtsEntry`（`:732-737`）。
- 全部天处理完后：`DELETE FROM diary_flag; INSERT OR IGNORE ... SELECT day_key FROM diary;`（`:740-741`），最后 `rebuildSearchIndex()` 整库重建（`:742`）。
- 统计写入 `stats`（`inout`，`:744`）：`importedDays/skippedDays/overwrittenDays/importedBlocks/importedImages/settingsRestored`（结构见 `:22-29`）。
- **粒度**：整天替换或整天跳过，**没有块级合并**；`mode` 只有 `"skip"` 与其它（视为覆盖），没有真正的增量。

UI 测试自动导入入口：`runAutoImport()`（`:114-140`），由启动参数 `-ui-test-import <path>` / `-ui-test-import-overwrite` 触发，结果写 `Documents/import-*.log`。

### 5.4 Android 迁移要点（备份）

- zip 可以用 `java.util.zip.ZipOutputStream` / `ZipInputStream` / `CRC32` 自然迁移；**但必须保留**：method 0/8 兼容、CRC32 校验、zlib 头容错（`Inflater(nowrap=false)` 重试）、`.jdiary` 扩展名与内部路径布局（跨端互换的契约）。
- `sqlite3_backup` **没有 Android 公开等价 API**：改用
  - Android 11+ (API 30+, SQLite 3.27+)：`VACUUM INTO '<path>'`（注意不能在事务里、目标文件必须不存在）；
  - 或 `PRAGMA wal_checkpoint(TRUNCATE)` 后直接复制 `.db`（要求没有并发写）。
- **内存**：现在导出把整个 zip 在内存里拼（`Data`），导入把整个 zip 解成 `[String: Data]`（DB + 全部原图都在堆上）。Android 堆更紧，必须改成**流式**：导出边写边 `ZipOutputStream`，导入用 `ZipFile` 随机读取 + 单图流式拷贝。
- 备份里的 DB 是完整 schema（含 `diary_fts`）。若 Android 不打算复用 iOS 的 FTS 索引（例如改用第三方分词器），导入后必须**无条件重建**（现有代码在 `mergeBackupDatabase` 末尾已经整库重建，天然兼容）。

---

## 6. 搜索行为

实现：`DiaryRepository.runSearch`（`:879-943`）+ `SearchUtil`（`Services/SearchUtil.swift`）+ `SearchViewModel`（312 行）。

### 6.1 查询构造（逐段）

输入：`keyword`（来自 `SearchViewModel.combinedKeyword` = 已提交关键词数组 + 当前输入，空格连接，`SearchViewModel.swift:82-93`）、`fromKey/toKey`（时间范围）、`filter: LocFilter`、`offset`。

1. **分词**：`keyword.trimmed.split(whereSeparator: isWhitespace)`（`:882-883`）。没有引号/AND/OR 语法支持——用户输入被当作一组"与"词项。
2. **时间**：`whereParts = ["d.day_key >= ?", "d.day_key <= ?"]`（`:884`）。范围值由 `TimeRangeKind.dayKeyRange`（`SearchViewModel.swift:10-29`）给出：`all → ("0000-01-01","9999-12-31")`、`thisWeek → weekFirst..today`、`thisMonth → monthFirst..today`、`thisYear → 当年1月1日..today`、`custom → 排序后的两天`。周起始跟随设置 `week_start`（`DateUtil.weekFirst` `DateUtil.swift:74-85`）。
3. **正文匹配**（仅当有词项）：
   - FTS 查询串：每个词**去掉双引号**后 `FtsSegment.segment`，再包双引号，用空格连接（`:888`）→ 例如 `深圳 天气` → `"深 圳" "天 气"`。因为 FTS5 里空格是**隐式 AND**，每个词又各自成短语，所以语义是"每个词都必须出现（作为相邻 token 序列）"。
   - LIKE 回退：每个词 `d.search_text LIKE ? ESCAPE '\'`，参数 `%` + `SearchUtil.escapeLikeTerm(term)` + `%`，多个用 **AND** 连接（`:889-890`）；`escapeLikeTerm` 转义 `\`、`%`、`_`（`SearchUtil.swift:4-13`）。
   - 合取：`(EXISTS(SELECT 1 FROM diary_fts WHERE diary_fts.diary_id = d.id AND diary_fts MATCH ?) OR (like AND like ...))`（`:891-894`）。FTS 不可用时 `matchSql` 为字面 `1=0`（`:893`），整体退化成 LIKE。
4. **地点筛选**：`!noLoc && !country.isEmpty` → `EXISTS (SELECT 1 FROM edit_block b WHERE b.diary_id = d.id AND b.country = ? AND b.region1 = ?)`（`:898-901`）。传 `region1 = ""` 即"只看国家"。
5. **无地点筛选**：`filter.noLoc` → `noLocSql`（`:22-26`），即当天**没有任何**片段带 `loc_quality<>'none'` 或 `loc_text/country/region1/2/3` 非空或坐标非 0。
6. **总数**：同一 `whereSql` 的 `SELECT COUNT(*) FROM diary d`（`:907-908`）。
7. **片段高亮（snippet）**：`(SELECT snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24) FROM diary_fts WHERE diary_fts.diary_id = d.id AND diary_fts MATCH ? LIMIT 1)`，无词项时为 `''`（`:909-911`）。参数是 FTS5 签名：列号 1 = `text`，最多 **24** 个 token，省略号 `…`。
8. **列表 SQL**：
   ```sql
   SELECT d.id, d.day_key, d.summary, d.updated_utc, <snipSql> AS snip
   FROM diary d WHERE <whereSql>
   ORDER BY d.day_key DESC LIMIT ? OFFSET ?;
   ```
   绑定顺序：先 snippet 的 `ftsQuery`，再 `whereSql` 的参数（时间、ftsQuery、like…、地点），最后 `searchLimit+1 = 201`、`offset`（`:912-923`）。
9. **后处理**：snippet 非空 → `desegment`；仍为空且有词项 → `SearchUtil.buildFallbackSnippet(diary.summary, keyword)`（`:926-933`）。`hasMore = items.count > 200`，超出截断（`:940-941`）。

### 6.2 排序与结果结构

- **排序只有 `day_key DESC`**（`:922`）——**没有 BM25 / rank / `ORDER BY rank`**。FTS 的 `rank` 列完全没用。
- 结果：`SearchPageResult{ items:[SearchResultItem], hasMore, total }`；`SearchResultItem{ id, dayKey, summary, snippet, updatedUtc }`（`DiaryModels.swift:70-82`）。
- 分页：`SearchViewModel.doSearch(reset:)` 每次取一页，`offset += result.items.count`（`SearchViewModel.swift:294-302`）；`reset` 时清空重来。
- 竞态：`currentSearchId` 自增 + 返回后比对，丢弃过期结果（`:275-292`）；输入 250 ms 防抖（`:153-160`）。
- 索引未就绪提示：`indexing = !searchIndexReady()` 后再 `ensureSearchIndex()`（`:274-280`）。

### 6.3 高亮实现（两条路径）

| 路径 | 实现 | 证据 |
|---|---|---|
| FTS snippet（首选） | SQLite `snippet()` 直接产出 `<hl>…</hl>` 包裹的片段，`desegment` 去掉注入空格后交给 `SearchUtil.highlightSnippet` 拆成 `Seg{text, hit}` | `:909-911,926-929`；`SearchUtil.swift:61-82` |
| LIKE / 无 FTS 回退 | `buildFallbackSnippet`：在 `diary.summary`（≤40 字，**不是全文**）里找第一个命中，向前后各取 **18** 个字符，两侧加 `…`；再用 `NSRegularExpression`（`caseInsensitive`）把每个词替换成 `<hl>词</hl>` | `SearchUtil.swift:84-125` |
| UI 关键词高亮（与 snippet 无关） | `highlightSegments`：对文本 `lowercased()`，逐词找子串、收集 range、按起点排序、合并重叠/相邻，再切段 | `SearchUtil.swift:20-59` |

注意回退路径的窗口只有 40 字摘要（`summary = substr(...,1,40)`），所以 LIKE 命中但 FTS 不可用时的 snippet 信息量很小。

### 6.4 Android 搜索实现建议

- SQL 可**逐字照搬**（表名列名一致），前提是 `diary_fts` 用同一句 `CREATE VIRTUAL TABLE ... fts5(...)`。
- `snippet()` 在 **FTS5** 下签名是 `(列号, start, end, ellipsis, tokens)`，与代码一致（官方文档 §5.1.3）；**FTS4 下签名不同**：`snippet(table, start, end, ellipsis, columnIndex, tokens)`——若要降级到 FTS4，必须改这一处。
- FTS4 还有一个差异：`DELETE FROM diary_fts WHERE diary_id = ?` 依赖 `diary_id` 列可被 WHERE 过滤。FTS4 不支持 `UNINDEXED` 关键字（要用 `notindexed=diary_id`），且对非索引列做等值删除的行为需实测；更稳的做法是用 `rowid = diary_id` 做映射（本项目 `diary_id` 恰好与 `diary.id` 一一对应，但 FTS5 的 rowid 目前是自增插入序，不是 `diary_id`——迁移时可以把 FTS5 的 rowid 设成 `diary_id` 彻底简化删除/更新）。
- LIKE 大小写与转义：`ESCAPE '\'` 在 Android SQLite 同样支持；`%`/`_`/`\` 转义逻辑可直接翻译。

---

## 7. 足迹聚合逻辑

实现：`JustDiary/Services/FootprintDataService.swift`（137 行）+ `ViewModels/FootprintViewModel.swift`（109 行）。

### 7.1 数据获取：一条明细 JOIN，**没有聚合 SQL**

```sql
SELECT b.id, d.day_key, b.start_time_utc, b.latitude, b.longitude, b.loc_text,
       b.loc_quality, b.loc_precision, b.country, b.country_code,
       b.region1, b.region2, b.region3, d.summary
FROM edit_block b JOIN diary d ON d.id = b.diary_id
ORDER BY b.start_time_utc ASC;
```
（`:330-359`，逐行映射到 `FootprintRow`）

**"年度统计 SQL"并不存在**——所有聚合都是 Swift 内存计算。

### 7.2 三级聚合的确切规则

- `hasPlace(row)`：`country || region1 || region2 || region3 || locText` 任一非空（`:47-50`）。这是"已定位"的判定，比旧的"地图版"宽松（旧版还要求可用坐标或同城/同省质心，见注释 `:43-46`）。
- `path(of:)`（`:53-60`）：非空组件依次取 `[country, region1, region2]`；**`region3`（区县）不进入路径**，只在 `locText` 里体现；若前三者全空但 `locText` 非空，则用 `[locText]` 作为单级路径。所以树实际是"国家→省→市"最多三级，缺级自动塌缩（municipality 如 北京市会 country=中国, region1=北京市, region2=北京市，形成两级）。
- `stats`（`:62-76`）：`countries` = 去重 country；`provinces` = 去重 `country + \u{1} + region1`；`cities` = 去重 `country + \u{1} + region1 + \u{1} + region2`；`blocks` 计数；`days` = 去重 dayKey。分隔符常量 `separator = "\u{1}"`（`:41`）。
- `buildTree`（`:78-114`）：对每行按 `path(of:)` 前缀累积建 trie；每个节点 `count` = **经过该节点的行数**（即自身 + 后代）；`id` = `\u{1}` 连接的路径（跨次加载稳定）；子节点排序 = `count DESC`，再按名称 `localizedStandardCompare(...) == .orderedAscending`（**区域感知的自然序**，`:97-103`）。
- `buildYearly`（`:117-136`）：`year = dayKey.prefix(4)`（要求 `dayKey.count >= 4`）；每年 `days` = 去重 dayKey；`places` = 去重 **完整路径键**（不是叶子城市数）；输出按年份**升序**（`byYear.keys.sorted()`）。返回 `FootprintYearStat{year, days, places}`（`:20-25`）。

### 7.3 ViewModel 侧筛选

`FootprintViewModel.reload()`（`:66-95`）：
1. `rows = allFootprintRows()`；`located = rows.filter(hasPlace)`。
2. `yearFilter != "all"` → `dayKey.hasPrefix(year)`。
3. `timeKind != .all` → `dayKey >= range.0 && <= range.1`（字符串比较）。
4. 用过滤后的行算 `stats` / `nodes` / `yearly`；`locatedCount = filtered.count`。
5. `unlocated = rows.count - located.count`（**不受筛选影响**，注释 `:83`）。
6. 年份列表来自**未按时间筛选**的已定位行（`:86-90`），降序。
7. `pruneExpanded()` 清理已不存在的展开状态（`:97-108`）。

### 7.4 "不依赖打包地理边界"的含义（迁移判定关键）

- 源码注释（`:36-39`，逐字）："Unlike the previous map implementation this is purely a data aggregation: it needs no coordinates and no bundled boundary data, so it works for every country rather than only those with GeoJSON shipped in the app."
- README 对应表述："足迹页是**纯数据聚合**（`FootprintDataService`）：不依赖坐标，也不需要任何打包的地理边界数据，因此对任何国家都可用"。
- 结论：足迹模块**没有任何 SQL、GeoJSON、地图 SDK 依赖**，纯粹是 `List<FootprintRow>` 上的 Kotlin 集合运算 → **"自然迁移"**。唯一需要在 Android 上小心对齐的是：
  1. `localizedStandardCompare`（区域感知 + 数字自然序）→ `java.text.Collator` 接近但**不处理数字自然序**；如要逐字对齐需补一个数字块比较器或引 `icu4j`（`RuleBasedCollator` 同理不含 numeric 模式；ICU 的 `Collator.setNumericCollation(true)` 才有）。
  2. `\u{1}` 分隔符（`Char(1)`）在 Kotlin 里可以用 `'\u0001'`；注意不要用 `String.split` 拆 id。
  3. `region1/2/3` 的填值完全依赖反向地理编码的**字段映射**（§9），Android 换地理编码源后同一坐标可能得到不同的 `region1/2/3`，从而让足迹分布与 iOS 不一致——这是**数据源差异**，不是代码差异，需要产品决策（是否统一到同一地理服务）。

---

## 8. 设置 / 配置存储

### 8.1 介质

- `UserDefaults(suiteName: "group.com.cov.justdiary")`，失败回落 `.standard`（`Models/AppSettings.swift:30-31`）。是 **App Group**（iOS 上用于主 App + 扩展共享）。
- 读：`SettingsStore.load()` 逐 key 读取并校验，结果缓存在 `LockedBox<AppSettings?>`（`NSLock`）里（`:33-54`）。
- 写：`SettingsStore.save(_:)` 先更新缓存再逐 key `set`（`:56-69`）。
- 备份里的 `settingsJson` 是 `AppSettings` 的 Swift JSONEncoder 输出，key 是 `CodingKeys` 指定的 snake_case（`:15-26`）。

### 8.2 key 全量清单

| key | 类型 | 默认值 | 合法值/校验 | 语义 | 证据 |
|---|---|---|---|---|---|
| `day_start_hour` | Int | **4** | 无显式校验 | 一天的开始小时（<4 点算前一天） | `AppSettings.swift:4,39` |
| `auto_time` | Bool | **true** | — | 新建片段自动填时间 | `:5,40` |
| `auto_loc` | Bool | **true** | — | 新建片段自动取位置 | `:6,41` |
| `allow_history_edit` | Bool | **false** | — | 是否允许编辑历史日记 | `:7,42` |
| `theme_mode` | String | **"system"** | `light`/`dark`/`system`，非法回落 `system` | 主题 | `:8,43,49` |
| `app_language` | String | **"system"** | `system`/`zh`/`en`，非法回落 `system` | 应用内语言 | `:9,44,50` |
| `week_start` | String | **"monday"** | `monday`/`sunday`，非法回落 `monday` | 周起始（影响搜索"本周"） | `:10,45,51` |
| `remind_enabled` | Bool | **true** | — | 每日提醒开关 | `:11,46` |
| `remind_hour` | Int | **21** | — | 提醒小时 | `:12,47` |
| `remind_minute` | Int | **0** | — | 提醒分钟 | `:13,48` |
| `search_index_version` | Int | **0**（`integer(forKey:)`） | 与 `DiaryRepository.searchIndexVersion = 3` 比较 | FTS 索引格式版本（**数据版本，不是用户偏好**） | `:71-74`；`DiaryRepository.swift:31-33` |
| `content_format_version` | Int | **0** | 与 `ContentDocument.currentVersion = 2` 比较 | `content_json` 存储格式版本（**备份恢复不会重置它**，注释 `:76-78`） | `:79-82` |
| `region_geocode_skip_v2` | `[Int64]`（数组） | `[]` | 只保留最后 2000 个 | 反地理编码失败的 block id 黑名单 | `DiaryRepository.swift:839,843,838` |

其它：`appVersion` 不落盘，直接读 `Bundle.main.infoDictionary["CFBundleShortVersionString"]`，缺省 `"1.0"`（`:84-86`）。

### 8.3 Android 映射

- 10 个业务 key + `content_format_version` + `search_index_version` → DataStore(Preferences) 或 SharedPreferences，**key 名与类型逐字保留**（便于文档/跨端排障）。
- `region_geocode_skip_v2` 是数组 → DataStore 存 `StringSet`（`"1","2"`）或一个 JSON 字符串；注意"只保留最后 2000"的滚动语义（`Array(skip.suffix(2000))`，但 `Set` 无序，这里实际是**任意 2000 个**，不是"最近 2000 个"——迁移时若追求一致可保持同样"近似 LRU"语义，或改进但不影响正确性）。
- **App Group 没有 Android 对应物**：iOS 用 App Group 是为了与扩展/小组件共享；Android 若未来有 App Widget/Quick Settings Tile，需要 `SharedPreferences` + `ContentProvider` 或直接 `DataStore` 单进程共享（同包名同进程即可）。设置备份/恢复（`settingsJson`）不受影响。
- 设置变更的副作用需要一并迁移：改 `day_start_hour` → `recomputeDayKeys` + `bumpDiaryVersion`；开 `auto_loc` → 申请定位权限 + `backfillBlockRegions()`；改 `theme_mode` → `DayDraw.clearCache()` + `bumpUiTick()`；改语言 → `bumpUiTick()` + `ReminderService.rearm()`（`SettingsViewModel.swift:38-129`、`AppConfigService.swift:16-22`）。

---

## 9. iOS 专有 API 依赖点（逐条 + Android 对应）

按全仓库 `import` 统计（`JustDiary/**/*.swift`）：`SwiftUI`×28、`Foundation`×24、`UIKit`×12、`os`×10、`Observation`×5、`MapKit`×3、`CoreLocation`×3、`SQLite3`×2、`UserNotifications`×1、`UniformTypeIdentifiers`×1、`PhotosUI`×1、`ImageIO`×1、`Compression`×1、`Charts`×1。

| # | iOS API | 使用点（证据） | 用途 | Android 方案 |
|---|---|---|---|---|
| 1 | `SQLite3` C API（`sqlite3_open_v2`/`prepare_v2`/`bind_*`/`step`/`finalize`/`last_insert_rowid`/`column_*`） | `Data/SQLite.swift:2,15-148` | DB 封装 | `android.database.sqlite.SQLiteDatabase` / `SQLiteStatement`（或 Room + `openHelper.writableDatabase`）；`FULLMUTEX` 不需要（Java 层同步） |
| 2 | `sqlite3_backup_init/step/finish` | `Services/BackupService.swift:217-234` | 导出整库快照（含 WAL） | **无公开等价**：`VACUUM INTO`（API 30+）或 `wal_checkpoint(TRUNCATE)` + 文件拷贝 |
| 3 | `PRAGMA user_version` | `Data/SQLite.swift:36-45` | schema 版本 | 同名 PRAGMA 可用；Room 用 `@Database(version=)` + `Migration`（但会引入 `room_master_table`） |
| 4 | `FileManager`（`urls(for:.documentDirectory)`、`temporaryDirectory`、`createDirectory`、`copyItem`、`removeItem`、`contentsOfDirectory`、`fileExists`） | `DiaryRepository.swift:41-56,582-613`、`BackupService.swift:37-108,145-191`、`ContentPart.swift:235-236`、`DiaryViewModel.swift:494-497` | 目录/文件操作 | `java.io.File` + `context.filesDir` / `context.cacheDir`（`getExternalFilesDir` 视需要）；`listFiles` |
| 5 | `Foundation`：`URL`/`Data`/`Date`/`DateFormatter`/`JSONEncoder`/`JSONDecoder`/`NSRegularExpression`/`NSString`/`ProcessInfo`/`NotificationCenter`/`NSLock`/`NSCache`/`Bundle` | 全仓库 | 通用 | `kotlin` 标准库、`java.time`、`kotlinx.serialization`/`org.json`、`Regex`、`java.util.concurrent`、`android.os.Bundle`、`LruCache`、`BuildConfig`/`PackageManager` |
| 6 | `CoreLocation`：`CLLocationManager`（权限）、`CLLocationUpdate.liveUpdates()`（async 流，8 s 超时）、`CLLocationCoordinate2D`/`CLLocation`、`CLPlacemark` | `Services/LocationService.swift:1-99`、`DiaryRepository.swift:2,853,1012-1013` | 定位 + 权限 | `FusedLocationProviderClient`（play-services-location，第三方）或 `LocationManager`/`getCurrentLocation`（API 30+）；权限用 `ActivityResultContracts.RequestMultiplePermissions` |
| 7 | `MapKit`：`MKReverseGeocodingRequest` + `MKMapItem.placemark` | `DiaryRepository.swift:3,1014-1016`、`Services/LocationService.swift:71-77`、`Services/MKMapItem+Placemark.swift` | 反地理编码，取 `country/countryCode/administrativeArea/subAdministrativeArea/locality/subLocality/thoroughfare/name` | `android.location.Geocoder.getFromLocation`（平台，但设备/区域依赖、需网络）或 Google Geocoding / Mapbox / 高德等 Web API（第三方）。注意 `MKMapItem+Placemark.swift:4-18` 记录了 Apple 新 API 缺失结构化字段的问题——Android 侧同样要保证能拿到 `adminArea/locality/subLocality/subAdminArea` |
| 8 | `os.Logger`（5 个 category：app/db/backup/location/editor） | `Services/Log.swift:1-9`；调用点如 `DiaryRepository.swift:65,645` | 日志 | `android.util.Log` 或 Timber（第三方，推荐）；category → tag |
| 9 | `Compression`：`compression_encode_buffer` / `compression_decode_buffer` + `COMPRESSION_ZLIB` | `Services/ZipArchive.swift:2,148-195` | zip 的 raw deflate | `java.util.zip.Deflater(level, true)` / `Inflater(true)`；`CheckedOutputStream(CRC32())` |
| 10 | `ImageIO`：`CGImageSourceCreateWithData`、`CGImageSourceCreateThumbnailAtIndex`（`kCGImageSourceThumbnailMaxPixelSize`、`CreateThumbnailFromImageAlways`、`WithTransform`、`ShouldCache*`） | `Services/MediaStore.swift:3,105-118` | 图片降采样（不整图解码） | `BitmapFactory.decodeFile/Stream` + `inJustDecodeBounds` 算 `inSampleSize`（API 28+ 可用 `ImageDecoder.setTargetSampleSize`）；或 Coil/Glide（第三方，带缓存/生命周期） |
| 11 | `UIKit`：`UIImage`、`jpegData/pngData`、`UIGraphicsImageRenderer`、`UIBezierPath`、`UIFont`/`UIFontMetrics`、`UITraitCollection`、`UIDocumentPickerViewController`、`UIApplication.openSettingsURLString`、`ShareLink`/`UIActivityViewController` | `MediaStore.swift:2,78-98`、`DiaryViewModel.swift:489-497`、`Views/Settings/SettingsView.swift:461-489`、`Services/ImageShareService.swift` | 图片编解码、圆角、分享图渲染、文档选择、跳设置 | `Bitmap`/`Bitmap.compress(JPEG,85)`、`Canvas`/`Paint`、`Typeface`+`sp`、`ActivityResultContracts.OpenDocument`、`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`、`Intent.createChooser` + `FileProvider` |
| 12 | `SwiftUI`（28 文件）+ `Observation`（`@Observable`）+ `Charts` | 全部 Views/ViewModels | UI/状态/图表 | Jetpack Compose + `androidx.lifecycle.ViewModel` + `StateFlow`；图表用 Vico / MPAndroidChart 或 Compose Canvas 自绘 |
| 13 | `UserNotifications`：`UNUserNotificationCenter`、`UNCalendarNotificationTrigger`、`UNAuthorizationOptions` | `Services/ReminderService.swift:2,8-53` | 每日提醒 | `NotificationManager` + `AlarmManager.setExactAndAllowWhileIdle`（需 `SCHEDULE_EXACT_ALARM`）或 `WorkManager`（不精确）；渠道 `NotificationChannel`（API 26+）；"今天已写就跳过"逻辑照搬 |
| 14 | `UniformTypeIdentifiers`：`UIDocumentPickerViewController(forOpeningContentTypes: [.data])` | `Views/Settings/SettingsView.swift:2,467` | 选 `.jdiary` 文件 | `ActivityResultContracts.OpenDocument` + MIME `*/*`（`.jdiary` 非常规 MIME，需在 `AndroidManifest` 里用 `mimeType` 或自定义扩展名过滤） |
| 15 | `PhotosUI`：`PhotosPicker` | `Views/Diary/MediaViews.swift:3` | 选图插入 | `ActivityResultContracts.PickVisualMedia`（Photo Picker，API 19+ 经 Play services，API 33+ 原生）或 `ACTION_GET_CONTENT` |
| 16 | `Codable` / `JSONEncoder`（`.sortedKeys`, `.withoutEscapingSlashes`）/ `JSONDecoder` | `ContentPart.swift:11-206`、`BackupService.swift:6-20,91,160`、`AppSettings.swift:3-27` | `content_json`、manifest、settings JSON | `kotlinx.serialization`（配 `encodeDefaults=false`、显式 `@SerialName`）；**必须复刻确定性输出**（key 排序 + 不转义 `/`），或改为"解析后对象比较"，否则 `normalizeContent` 的字符串比较会失效 |
| 17 | `Bundle.main.infoDictionary["CFBundleShortVersionString"]` | `AppSettings.swift:85` | manifest `appVersion` | `BuildConfig.VERSION_NAME` / `PackageInfo.versionName` |
| 18 | `ProcessInfo.processInfo.arguments`（`-ui-test-import`、`-ui-test-no-location`、`-morph-log`、`-ui-test-reset-data` 等） | `BackupService.swift:116-119`、`LocationService.swift:38`、`HomeViewModel.swift:35` | UI 测试钩子 | Instrumentation arguments（`InstrumentationRegistry.getArguments()`）或 Intent extras |
| 19 | `Locale.current` / `Calendar(identifier:.gregorian)` / `Locale.preferredLanguages` / `localizedStandardCompare` | `DateUtil.swift:4-9`、`L10n.swift:4-15`、`FootprintDataService.swift:101` | 日历/语言/排序 | `java.time` + `ZoneId.systemDefault()`、`java.util.Locale`、`LocaleList`；自然序需 `java.text.Collator` + 数字比较或 ICU `setNumericCollation` |
| 20 | `DispatchQueue`（串行队列）、`Task`/`async-await`/`Task.detached`/`withCheckedContinuation`/`withThrowingTaskGroup` | `DiaryRepository.swift:17,978-996`、`BackupService.swift:33,143`、`LocationService.swift:46-63` | 并发 | Kotlin 协程：`Dispatchers.IO.limitedParallelism(1)`、`withContext`、`supervisorScope` + `withTimeout` |
| 21 | `NotificationCenter`（`.diaryVersionChanged` / `.uiTickChanged`） | `DiaryRepository.swift:6-9,74-92` | 应用内刷新信号 | `MutableSharedFlow`/`StateFlow`（进程内）；或 `LocalBroadcastManager`/`Flow` |
| 22 | `CFBundle` 本地化：`Localizable.strings` + `NSDictionary(contentsOf:)` 逐 `.lproj` 查表 | `Services/L10n.swift:44-70` | 应用内即时切换语言 | `res/values/strings.xml` + `values-zh`；API 33+ 用 `LocaleManager.setApplicationLocales`（AppCompat 用 `AppCompatDelegate.setApplicationLocales`），可即时生效 |
| 23 | `UIImpactFeedbackGenerator`（Haptics） | `Services/Haptics.swift` | 触感 | `View.performHapticFeedback` / `Vibrator` + `VibrationEffect` |
| 24 | `UIScreen`/`UITraitCollection`/`UIFontMetrics`（动态字体） | `Views/Components/DynamicType.swift`、`DesignSystem.swift` | 动态字号 | Compose `sp` + `LocalDensity`/fontScale；或 `TextView` 自动缩放 |
| 25 | `NSHomeDirectory()` | `BackupService.swift:121-137` | UI 测试日志路径 | `context.filesDir` |

---

## 10. 迁移判定表

判定口径：`自然迁移` = Android 原生/官方库可直接支持；`需第三方库` = 官方能力不足，需引库并给出候选；`需重新设计` = 无法一对一，需说明取舍。

### 10.1 数据层

| # | 迁移项（iOS 实现证据） | 判定 | Android 方案 / 取舍 |
|---|---|---|---|
| 1 | 三张表 + 4 索引 DDL（`DiaryRepository.swift:97-132`） | **自然迁移** | `SQLiteOpenHelper.onCreate/onUpgrade` 原样执行 SQL（最省心）；或 Room `@Entity`。**不加外键、不加触发器**；若用 Room，索引名与 iOS 不同（新库无所谓） |
| 2 | `PRAGMA user_version` 迁移链（`:94-208`） | **自然迁移** | `onUpgrade`/Room `Migration`；建议保留"迁移失败只记日志不崩溃"的宽松策略（`:59-66`） |
| 3 | FTS5 虚拟表 `diary_fts(diary_id UNINDEXED, text, tokenize='unicode61')`（`:214`） | **需重新设计**（`minSdk ≥ 24` 时是"裸 SQL 的自然迁移"） | **必须手写 raw SQL**：`db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS diary_fts USING fts5(diary_id UNINDEXED, text, tokenize='unicode61')")` + `rawQuery`。若坚持用 Room 的 `@Entity`，最接近的是 `@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)`，但会丢掉 FTS5、`snippet()` 签名不同、`UNINDEXED` 需换 `notindexed`，因此**不值得**。若 `minSdk < 24`，需 `需第三方库`：`io.github.requery:sqlite-android`（自带较新 SQLite/FTS5）或 `net.zetetic:sqlcipher-android`（需加密时） |
| 4 | `snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24)`（`:910`） | **自然迁移**（FTS5 裸 SQL） | FTS5 下签名一致（列号在前）。**降到 FTS4 时签名变为 `(table, start, end, ellipsis, col, tokens)`，必须改**；`tokens` 上限 64，24 合法 |
| 5 | `EXISTS(... MATCH ?)` + `LIKE ... ESCAPE '\'` 混合（`:889-894`） | **自然迁移** | SQL 原样；`ESCAPE` 语义一致 |
| 6 | CJK 逐字分词 `FtsSegment`（`FtsSegment.swift:4-23`） | **自然迁移** | 纯 Kotlin 对象复刻（无平台依赖）；`unicode61` 在 FTS3/4/5 都有且对 CJK 的行为一致（连续 CJK = 1 token），所以插空格的必要性/效果与 iOS 完全相同 |
| 7 | `desegment` 显示层还原（`:25-71`） | **自然迁移** | 纯字符串算法，Kotlin 直译；注意 `<hl>` 标签保护与 `…` 不处理这两个细节 |
| 8 | 串行队列 + `BEGIN IMMEDIATE`（`:17,978-996`；`SQLite.swift:83-93`） | **自然迁移** | `Dispatchers.IO.limitedParallelism(1)` 或 Room 单连接；事务用 `beginTransactionNonExclusive()`（WAL）或 `execSQL("BEGIN IMMEDIATE")` 严格对齐 |
| 9 | `content_json` Codable + 确定性序列化（`ContentPart.swift:11-206`） | **自然迁移**（有陷阱） | `kotlinx.serialization`；必须复刻 `.sortedKeys` + `.withoutEscapingSlashes`，否则 `normalizeContent` 的字符串比较（`:238`）每次都不等 → 启动时全库重写。**更稳的做法**：改为比较解析后的对象（`parts == normalizedParts`） |
| 10 | 图片 `src` 相对键 `images/<basename>`（`:209-237`） | **自然迁移** | 保持键格式不变（跨端备份契约） |
| 11 | 图片降采样（ImageIO thumbnail）（`MediaStore.swift:78-118`） | **自然迁移** | `BitmapFactory.Options.inSampleSize` + `inJustDecodeBounds`；或 `ImageDecoder.setTargetSampleSize`（API 28+） |
| 12 | `NSCache` 图片缓存 + 300 条 ContentPart 缓存（`MediaStore.swift:7-66`） | **自然迁移** | `android.util.LruCache`（按 KB 计 size）；或 Coil/Glide 自带（`需第三方库`，但非必须） |

### 10.2 存储与备份

| # | 迁移项 | 判定 | 方案 / 取舍 |
|---|---|---|---|
| 13 | DB 文件 `<Documents>/just_diary.db` + WAL（`:39-43`） | **自然迁移** | `context.filesDir/just_diary.db`；WAL 由 `enableWriteAheadLogging()` 开启。注意**路径不同**：跨端不能直接文件互换，只能走备份 zip |
| 14 | 图片目录与命名 `images/img_<ms>.jpg`（`:45-49`；`DiaryViewModel.swift:490-497`） | **自然迁移** | `File(context.filesDir,"images")`；命名规则照抄（含毫秒时间戳） |
| 15 | `sqlite3_backup` 整库快照（`BackupService.swift:217-234`） | **需重新设计** | 无公开等价 API。首选 `VACUUM INTO ?`（API 30+，SQLite 3.27+）；低版本用 `PRAGMA wal_checkpoint(TRUNCATE)` + 文件复制（需保证无并发写，`prepare()` 期间可接受）。**注意 `VACUUM INTO` 目标文件必须不存在**，与现有 `snapshotDatabase` 语义略有差异 |
| 16 | 自实现 ZIP（raw deflate + CRC32，`ZipArchive.swift`） | **自然迁移** | `java.util.zip.ZipOutputStream` / `ZipInputStream` / `CRC32`；**保留**：method 0/8、CRC32 校验、zlib 包装容错、无 Zip64 时的大小上限检查 |
| 17 | 导出/导入**全内存** `Data` / `[String: Data]`（`:102,150-151`） | **需重新设计** | Android 堆更小：导出改流式 `ZipOutputStream`（DB 与每张图 `copyTo`），导入用 `ZipFile` 随机访问、单图流式写盘。否则大库（50 MB+ 图片）必 OOM |
| 18 | manifest.json（字段见 §5.2） | **自然迁移** | `kotlinx.serialization`/Moshi，`@SerialName` 对齐字段名；`formatVersion` 必须保持 `1` |
| 19 | `.jdiary` + `yyyyMMdd_HHmmss` 命名 | **自然迁移** | 保持同名规则（跨端互换）；`OpenDocument` + MIME 过滤 |
| 20 | 导入兼容旧 schema（`province/city/district`、无 `loc_quality`、无 `country_code`）（`:650-723`） | **自然迁移** | 用 `PRAGMA table_info` 探测列名分支，逻辑直译 |
| 21 | `-ui-test-import` 自动导入与日志文件（`:114-140`） | **自然迁移** | Instrumentation args + `filesDir` 日志；便于 Android UI 测试复用同一批 fixture |

### 10.3 位置 / 搜索 / 足迹

| # | 迁移项 | 判定 | 方案 / 取舍 |
|---|---|---|---|
| 22 | 定位一次（`CLLocationUpdate.liveUpdates()` + 8 s 超时）（`LocationService.swift:34-68`） | **需第三方库** | `com.google.android.gms:play-services-location` 的 `FusedLocationProviderClient.getCurrentLocation` + `withTimeout(8_000)`；纯平台 `LocationManager.getCurrentLocation`（API 30+）可作 fallback。权限模型与"高精度"判定（`accuracyAuthorization == .fullAccuracy`）需映射到 `ACCESS_FINE_LOCATION` vs `COARSE` |
| 23 | 反地理编码（MapKit → country/region1/2/3）（`DiaryRepository.swift:1011-1031`） | **需重新设计** | `android.location.Geocoder` 是平台 API 但设备/区域依赖强（部分设备需网络或 Play services），字段粒度与 `CLPlacemark` 不完全对应。建议：统一走一个可替换的 `GeocodingService`，候选实现 ①平台 `Geocoder`（零依赖、不可靠）②Google Geocoding API（需 key、计费）③Mapbox/高德（国内合规更好）。**`region1 = adminArea ?? subAdminArea`、`region2 = subAdminArea ?? locality`、`region3` 仅在 `locality != nil && subAdminArea != nil` 时取 `subLocality` 这三条规则必须逐条复刻**（`:1021-1023`），否则足迹分布会变 |
| 24 | 位置回填（每批 30，失败进 UserDefaults 黑名单）（`:804-867`） | **自然迁移** | 协程 + DataStore；语义照搬（`WHERE id=? AND region1=''` 幂等） |
| 25 | 搜索查询构造 / 排序 / 分页（`:879-943`） | **自然迁移** | SQL 原样；`ORDER BY day_key DESC`；分页 `LIMIT 201 OFFSET n`（注意 OFFSET 深翻页性能，与 iOS 同等） |
| 26 | snippet 高亮 + `desegment`（`:909-929`） | **自然迁移** | 见 #4/#7 |
| 27 | LIKE 回退 snippet（40 字摘要、±18 窗口、正则高亮）（`SearchUtil.swift:84-125`） | **自然迁移** | Kotlin `Regex`（`IGNORE_CASE`）；`NSRegularExpression.escapedPattern` → `Regex.escape` |
| 28 | 足迹三级聚合 + 年度统计（`FootprintDataService.swift`） | **自然迁移** | 纯 Kotlin 集合运算；唯一风险是 `localizedStandardCompare` 的区域自然序（见 #33） |
| 29 | `TimeRangeKind` 时间范围（`SearchViewModel.swift:10-29`） | **自然迁移** | `java.time.LocalDate` + `WeekFields`（周起始跟随设置）；字符串区间比较照抄 |

### 10.4 设置 / 平台 / UI

| # | 迁移项 | 判定 | 方案 / 取舍 |
|---|---|---|---|
| 30 | 13 个 UserDefaults key（§8.2） | **自然迁移** | DataStore(Preferences)；key 名与默认值逐字保留；`[Int64]` 数组存 StringSet/JSON |
| 31 | App Group 共享（`group.com.cov.justdiary`）（`:30-31`） | **需重新设计** | Android 无等价物；同包名同进程直接共享 DataStore。若有 Widget/Tile，需 `ContentProvider` 或 `WorkManager` 读取同一 DataStore |
| 32 | `settingsJson` 备份/恢复 | **自然迁移** | JSON 字段名（snake_case）与 `AppSettings` 对齐 |
| 33 | `localizedStandardCompare`（`FootprintDataService.swift:101`） | **需第三方库** | `java.text.Collator` 只做区域排序、**不做数字自然序**。候选：`com.ibm.icu:icu4j` 的 `Collator.setNumericCollation(true)`；或自写"数字块 + Collator"比较器。地名场景影响小，可接受"
 | 
| 34 | 每日提醒（`ReminderService.swift`） | **需重新设计** | `WorkManager`（不精确，省电，推荐）或 `AlarmManager.setExactAndAllowWhileIdle`（需 `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` 权限，Play 政策受限）。"当天已写则顺延"、`rearm`、`cancelAll` 逻辑照搬 |
| 35 | 分享长图渲染（CoreGraphics 手绘 ~700 行：`ImageShareService.swift`） | **需重新设计** | Compose `Canvas` 或 `android.graphics.Canvas` 重写；文字测量（`boundingRect(with:)` / `NSString.size`）→ `Paint.measureText`/`StaticLayout`；注意 CJK/ASCII 墨迹比例常量（`:isCJK`/`isASCII` 分支）与绝对坐标布局需重算 |
| 36 | 应用内语言即时切换（`Localizable.strings` 逐 bundle 查表）（`L10n.swift`） | **自然迁移** | `res/values-zh` + `AppCompatDelegate.setApplicationLocales`（API 33+ 原生 `LocaleManager`），可即时生效 |
| 37 | 动态字体（`UIFontMetrics` + 上限）（`DynamicType.swift`、`DesignSystem.swift`） | **自然迁移** | Compose `sp` 自动跟 `fontScale`；Apple 字号梯级 28/22/17/15（`docs/editor-typography.md`）需人工映射到 Android 的 sp 档位 |
| 38 | 主题/深浅色（`Theme.swift` + Asset Catalog 动态色） | **自然迁移** | Compose `MaterialTheme` + `isSystemInDarkTheme()`；色板常量直译 |
| 39 | 图表（`Charts`） | **需第三方库** | Vico（Compose 原生，推荐）或 MPAndroidChart；需求简单时 Compose Canvas 自绘即可（年度天数趋势） |
| 40 | `@Observable` ViewModel + `NotificationCenter` 刷新 | **自然迁移** | `androidx.lifecycle.ViewModel` + `StateFlow`/`SharedFlow`；`.diaryVersionChanged`/`.uiTickChanged` → 两路 `SharedFlow` |
| 41 | 触感（`Haptics.swift`） | **自然迁移** | `HapticFeedbackConstants` / `VibrationEffect` |
| 42 | 文档选择 / 分享（`UIDocumentPickerViewController`、`ShareLink`、`UIActivityViewController`） | **自然迁移** | `ActivityResultContracts.OpenDocument`、`Intent.ACTION_SEND` + `FileProvider`（`res/xml/file_paths.xml` 暴露 `filesDir/images` 与缓存目录） |
| 43 | 选图（`PhotosUI`） | **自然迁移** | `ActivityResultContracts.PickVisualMedia` |
| 44 | 日志（`os.Logger` 5 category） | **自然迁移** / 可选**需第三方库** | `Log.d/i/w/e` 足够；要结构化/树形上报用 Timber |
| 45 | `DateUtil` 日历/周起始/dayStartHour（`DateUtil.swift`） | **自然迁移** | `java.time`：`LocalDate` + `ZoneId.systemDefault()` + `WeekFields.of(firstDayOfWeek, 1)`；**逐字复刻** `< dayStartHour 算前一天` |
| 46 | `recomputeDayKeys` 两阶段 `tmp#<id>` 写（`:749-800`） | **自然迁移** | 逻辑直译；注意 `day_key UNIQUE` + 临时键避免冲突 |

### 10.5 重点明确回答

#### Q1：Room 是否支持 SQLite FTS5？`@Fts4` 与 FTS5 的差距？是否必须手写 raw SQL？

**结论：Room 2.x 不支持 FTS5，必须手写 raw SQL；Room 3.0（`androidx.room3`）才引入 `Fts5`/`FtsOptions`，但即便如此，本项目的搜索路径仍建议裸 SQL。**

事实与差距：

1. **Room 2.x（`androidx.room`）只提供 `@Fts3` 与 `@Fts4`**，没有 `@Fts5`；FTS 支持从 Room 2.1 开始（[CommonsWare: Room and Full-Text Searching](https://commonsware.com/AndroidArch/pages/chap-roomfts-001.html)）。`FtsOptions` 的 tokenizer 常量是 `TOKENIZER_SIMPLE`/`TOKENIZER_PORTER`/`TOKENIZER_ICU`/`TOKENIZER_UNICODE61`（[FtsOptions 参考](https://developer.android.google.cn/reference/androidx/room/FtsOptions)）。
2. **Room 3.0 才有 FTS5**：API 参考中已存在 `androidx.room3.Fts5` 与 `androidx.room3.FtsOptions`（[Fts5](https://developer.android.google.cn/reference/kotlin/androidx/room3/Fts5)、[FtsOptions](https://developer.android.google.cn/reference/kotlin/androidx/room3/FtsOptions)），即注解名为 `@Fts5`。若采用 Room 3，需接受新包名/新版本的迁移成本与稳定性风险。
3. **即便注解可用，差距仍然存在**：
   - FTS5 表是 `diary_id UNINDEXED` + `text` 两列。Room 的 FTS 实体要求一个 `rowid` 主键，列必须映射成实体属性；`UNINDEXED`（FTS5）/`notindexed`（FTS4）这类列选项在注解里表达力有限，容易生成与 iOS 不同的 DDL。
   - 本项目的查询是 **相关子查询 + FTS 辅助函数**：`EXISTS(SELECT 1 FROM diary_fts WHERE diary_fts.diary_id = d.id AND diary_fts MATCH ?)` 与 `snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24)`。这类 SQL 在 Room 里只能靠 `@Query` 手写（`@Query` 支持 `MATCH` 与辅助函数，但不校验、不生成 schema），**注解能省的只有建表**。
   - FTS4 的 `snippet()` 参数顺序与 FTS5 不同（FTS5: 列号在前；FTS3/4: `table, start, end, ellipsis, col, tokens`），`DELETE ... WHERE diary_id = ?` 在 FTS4 上的行为也要重新验证。
4. **平台可用性**：FTS3 一直有；FTS4 自 API 11；FTS5 在 API 24+ 实际可用（CommonsWare 原文："FTS5 … is only likely to be available on API Level 24 and higher devices"）。**更稳的做法是用 `PRAGMA compile_options` 在目标设备上核验 `ENABLE_FTS5`**，并在建表失败时像 iOS 一样降级到 LIKE（iOS 的 `ftsSupported = false` 路径，`:210-218`）。
5. **推荐方案（按优先级）**：
   - **A（首选，与 iOS 最大程度同构）**：数据层用 `SQLiteOpenHelper`（不用 Room），把 `SQLite.swift` + `DiaryRepository.swift` 的 SQL 逐条直译；FTS5 建表/查询/删除全部裸 SQL。跨端备份可互换、迁移风险最低。
   - **B**：Room 管普通表，FTS5 用 `roomDatabase.openHelper.writableDatabase.execSQL/rawQuery` 手写（Room 不会管理该虚拟表，需自行在 `Callback.onOpen` 里建表/迁移）。
   - **C**：Room 3.0 + `@Fts5`（新，需评估版本稳定性）。
   - **D**：`minSdk < 24` 或要加密 → `io.github.requery:sqlite-android`（自带 SQLite + FTS5；`需第三方库`）。

#### Q2：CJK 分词在 Android 上如何处理？

**结论：首选"原样复刻 iOS"——`unicode61` + 应用层在相邻 CJK 之间插空格；写入与查询共用同一函数。不要用 `icu` 或第三方分词器，除非愿意放弃与 iOS 的索引兼容。**

1. **复刻 iOS 方案（推荐）**：
   - Kotlin `object FtsSegment` 直译 `FtsSegment.swift:4-23` 的标量区间判断与插空格逻辑（`0x4E00-0x9FFF`、`0x3400-0x4DBF`、`0x3000-0x303F`，只看第一个 scalar）。
   - `diary_fts` 用 `tokenize='unicode61'`；写入 `segment(searchText)`，查询 `segment(term)` 后包双引号成短语。
   - 可行性依据：`unicode61`（FTS3/4/5 均有）把 CJK（category `Lo`）视为 token 字符，**连续 CJK 串 = 一个 token**——这正是 iOS 必须插空格的原因；插空格后每字独立成 token，短语查询 `"深 圳"` 就能实现"跨字子串匹配"。FTS3/4 的 `simple` 分词器同样把 codepoint ≥128 视为 eligible，行为一致，所以**即使只能退到 FTS4，这套算法也照样成立**（只需改 `snippet()` 签名与删除方式）。
   - 附带好处：**直接导入 iOS 备份时 `diary_fts` 的行已经是加过空格的文本**，索引可复用；反之亦然。这是唯一"零语义漂移"的方案。
   - 单元测试：把 §1.4 的 6 组样例（`深圳湾`、`abc深圳`、`深圳abc`、`你好，世界`、`你好。世界`、含扩展 A/兼容字的串）作为 Kotlin 测试固化，防止有人"优化"成 `lowercase()`/NFKC 而与 iOS 分叉。
2. **第三方中文分词（不推荐用于首版）**：`wangfenjin/simple`（[GitHub](https://github.com/wangfenjin/simple)）是 **FTS5 专用**的可加载扩展，支持中文分词 + 拼音 + 可选 jieba；但：
   - 必须能 `load_extension` 或静态链接，需要为每个 ABI 打包 `.so`，且底层 SQLite 必须带 FTS5；
   - 引入 `simple_query()`/`simple_snippet()` 等新函数，查询构造要改写；
   - **会改变分词结果 → iOS 备份里的 `diary_fts` 索引全部失效，导入后必须整库重建**，且两端搜索结果不再一致（召回更高但排序/命中不同）。若产品接受"Android 检索能力更强"这一差异，可作为二期增强（只影响 FTS 表，不影响 `diary`/`edit_block` 的跨端数据契约）。
3. **不要用的选项**：
   - `tokenize=icu`：需要 SQLite 编译时开启 `SQLITE_ENABLE_ICU`（[SQLite FTS3/4 文档](https://www.sqlite.org/fts3.html)：`-DSQLITE_ENABLE_ICU`），Android 平台 SQLite 通常未开启，`PRAGMA compile_options` 可现场核验；
   - `FtsOptions.TOKENIZER_SIMPLE` / `tokenize=simple`：ASCII-only 的大小写与分隔符规则，对中文没有分词能力（连续 CJK 仍是一个 token），并不能解决 CJK 问题；
   - `trigram` 分词器（FTS5 3.34+）：能做子串匹配且不需要插空格，但 Android 平台 SQLite 版本需 ≥ 3.34（API 34 左右）且索引体积膨胀、中文短语语义不同——属于"需重新设计"，不建议为了省事切换。
4. **顺带修正的"坑"**：`FtsSegment` 只覆盖 CJK 表意与 CJK 标点，不含假名/谚文/兼容表意/CJK 扩展 B+（`0x20000+`，即 Unicode 13 的扩展 G 等）。若 Android 端打算扩展覆盖面，**必须两端同时改**并 bump `search_index_version`，否则跨端索引不一致。

---

## 附录 A：关键常量速查（迁移时逐字保留）

| 常量 | 值 | 位置 |
|---|---|---|
| DB 文件名 | `just_diary.db` | `DiaryRepository.swift:42` |
| 图片目录 | `images` | `:48` |
| 新图前缀 | `img_<epochMs>.jpg` | `DiaryViewModel.swift:492` |
| 导入图前缀 | `imp_<stamp>_<n>.<ext>` | `BackupService.swift:186` |
| 插入降采样 | 2048 px / JPEG 0.85 | `DiaryViewModel.swift:489-490` |
| 显示降采样 | `max(w,h)*3` / 1600 px | `MediaViews.swift:25,151` |
| 图片缓存 | 200 项 / 150 MB | `MediaStore.swift:53-54` |
| ContentPart 缓存 | 300 项 | `MediaStore.swift:13` |
| 搜索页大小 | 200（+1 探测） | `DiaryRepository.swift:13` |
| 搜索索引版本 | 3 | `:14` |
| snippet token 数 | 24，标记 `<hl>`/`</hl>`，省略号 `…` | `:910` |
| 回退 snippet 窗口 | ±18 字 | `SearchUtil.swift:98-99` |
| content 格式版本 | 2 | `ContentPart.swift:155` |
| 备份格式版本 | 1 | `BackupService.swift:80,161` |
| 备份文件名 | `JustDiary_yyyyMMdd_HHmmss.jdiary` | `:105` |
| 导出临时目录 | `jdiary_export_<epochMs>` | `:38` |
| 导入临时目录 | `jdiary_import_<epochMs>` | `:145` |
| 回填批量 | 30 | `:848` |
| geocode skip 上限 | 2000 | `:838` |
| 时间戳单位 | epoch **ms** | `:484` 等 |
| dayStartHour 默认 | 4 | `AppSettings.swift:4` |
| 索引重建批大小 | 500 | `:232,260` |
| busy timeout | 3000 ms | `SQLite.swift:21` |
| 足迹分隔符 | `\u{1}` | `FootprintDataService.swift:41` |
| 地点候选上限 | 20 | `:407` |
| App Group | `group.com.cov.justdiary` | `AppSettings.swift:30` |

## 附录 B：跨端数据契约（必须保持不变，否则破坏备份互换）

1. `just_diary.db` 的表名/列名/类型/默认值（§1.2）与 FTS5 建表语句。
2. `content_json` 的 v2 信封 `{"v":2,"parts":[...]}`、`style` 字符串、`TextRun` 只写 true 的样式、v1 兼容解码（`type`/HTML 名/`size` 忽略）。
3. 图片引用键 `images/<basename>`。
4. 备份 zip 布局 `manifest.json` / `db/just_diary.db` / `images/*`，`formatVersion == 1`，manifest 字段名。
5. `search_text`/`summary` 的派生规则与 FTS `text` 的"逐字插空格"分词（保证两边索引一致）。
6. epoch 毫秒时间戳 + `YYYY-MM-DD` `day_key` + `dayStartHour` 语义。
7. 设置 key 名与默认值。
8. 备份导入的整天 skip/overwrite 语义与统计字段。

## 附录 C：建议的 Android 落地顺序（供排期参考）

1. `SQLiteOpenHelper` + schema 迁移链（§1）→ 与 iOS 库文件做一次"打开-读写-备份往返"对照测试。
2. `FtsSegment` Kotlin 版 + FTS5 建表/写入/删除 + 搜索 SQL（§1.4/§6）→ 用 iOS 导出的 `.jdiary` 做检索结果对齐测试（同关键词、同命中集合、同 snippet 文本）。
3. 备份 zip 流式导出/导入 + `VACUUM INTO` 快照（§5）→ 双向导入测试（iOS→Android→iOS）。
4. 位置服务与反地理编码适配层（§9 #6/#7）→ 字段映射规则单测。
5. 足迹聚合（§7）→ 用固定 fixture 列表做树/年度统计的快照测试。
6. 设置 DataStore（§8）+ 副作用（dayStartHour 重算、语言切换、提醒重排）。
7. UI/Compose 层与分享长图渲染（§10 #35）。
