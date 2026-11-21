# JustGDiary（Android）迁移方案 v1 · 待确认

> 本文是**方案**，不是实施记录。**经你确认后**才开始建工程、写代码。
> 依据：对 `/Users/cov/JustADiary` 全量源码（60 个 Swift 文件 / 13,418 行）+ `docs/` 七篇设计文档 +
> 13 张设计稿的逐项通读，以及 Google 官方文档的版本核对。
>
> 源端事实基线：`bundle id = com.cov.justdiary`、iOS 27+、SwiftUI、**无任何 Swift Package 外部依赖**、
> 197 条本地化键（zh-Hans / en）、以本地数据为核心（无账号/云同步；系统级反地理编码和用户手动触发的 GitHub Release 更新会访问网络）。
>
> **实施记录见 [`docs/migration-progress.md`](migration-progress.md)**（环境与工具链实测值、每个里程碑的
> 交付物与验收证据、踩过的坑）。本文件是方案，那份是记录。

---

## 0. 结论摘要

| 问题 | 结论 |
| --- | --- |
| 能不能完整迁移 | **能**。5 个页面、4 类服务、1 套数据层、1 套设计令牌全部有 Android 落地路径，无阻塞项 |
| 真正需要第三方库的地方 | **UI 层零第三方**（采用 Material 3，不引入任何玻璃/模糊库）。数据与平台层只用 Google 官方库：`play-services-location`（定位）、`kotlinx.serialization`、`androidx.*`。图片加载可自写或用 `coil`（可选）；图表**自绘** |
| 设计语言 | **Google Material Design（Material 3）**，启用动态取色（Material You）与自适应导航。**不模仿 iOS 的 Liquid Glass、不照抄 iOS 界面**；只对齐功能、交互与动画（已确认，见 §7） |
| 最大工作量 | ① 富文本编辑器（iOS 约 2,700 行，需重写编辑内核 + span 桥接）② 首页日历与 morph 动画（iOS 约 1,700 行，需手写插值与绘制） |
| 数据能否跨端互通 | **能**。表结构、`content_json`、备份 zip（SQLite 文件 + manifest + images）都可 1:1 保真 |
| 包名 | `com.cov.justdiary`（与 iOS 完全一致，**不做任何变体**） |
| 工程结构 | 与 iOS 同构：`app / ui / viewmodel / service / data / model / res`，逐目录对应 |
| 不做的部分 | iPad / Mac 三栏版面、iPhone Duo 折痕预留、`FlowLightOverlay`（iOS 侧本就未接入）、任何玻璃模糊与光斑背景 |
| 规模预估 | 约 11,000–14,000 行 Kotlin + 资源；按下面 M0–M9 分阶段推进 |

### 0.1 已确认的决策（2026-09-17）

| 决策 | 结论 |
| --- | --- |
| **开工节奏** | ✅ **开工**：按 M0→M9 顺序推进（本轮先交付可编译的 M0 工程骨架，再逐阶段推进） |
| **设计语言（Q1）** | ✅ **采用 Google Material Design（Material 3）**。**不模仿 Liquid Glass**、不照抄 iOS 界面；原项目只作为**功能、交互、动画**的参考，视觉按 Google 规范重新设计。**不引入 `haze`** |
| **`minSdk`（Q2）** | ✅ **33（Android 13）** —— 每应用语言与 `POST_NOTIFICATIONS` 原生、平台 SQLite 确定含 FTS5、`android.icu.text.Collator` 与 `java.time` 全可用；代价是覆盖设备范围收窄 |
| **备份互通（Q3）** | ✅ **必须与 iOS 双向互导，严格保真** → 表名/列名/字符串取值/`content_json`/zip 布局全部冻结为契约（见 §4.1、§4.7.2） |
| **动态取色（Q4）** | ✅ **启用**：Android 12+ 默认跟随壁纸（Material You），设置页提供开关（默认开），关闭或不可用时回落品牌色 `#2563EB` 色板 |
| **`auto_time`（Q5）** | ✅ 判定为 iOS 侧 bug：**做成真实有效的开关**（关闭即不自动写入开始时间） |
| **定位方案（Q6）** | ✅ **运行时探测 GMS**：有 GMS 走 `FusedLocationProviderClient`，无 GMS 退原生 `LocationManager` + `Geocoder`；抽成接口，实现可替换 |
| **提醒精度（Q7）** | ✅ **`WorkManager`**（不用精确闹钟，规避 `SCHEDULE_EXACT_ALARM` 撤销与 Play 政策风险） |
| **运行验证（Q8）** | ✅ 本轮**不做安装运行验证**（本机无 AVD / 系统镜像 / 真机）。验收口径 = **编译通过 + JVM 单测通过**；instrumented 测试与观感比对后续补一次 |
| **横屏导航（Q10）** | ✅ **用 Android 原生自适应导航**：紧凑宽度 `NavigationBar`、中等/扩展宽度 `NavigationRail`。**不照抄 iOS 的底部悬浮条**，横屏分栏数值按 Rail 重算 |
| **编辑器路线（Q11）** | ✅ 按推荐路线直接开工：`AndroidView` + `EditText` + `Spannable` |
| **数据层框架（Q12）** | ✅ 以"**两边完全对齐**"为准 → `SQLiteOpenHelper` + 裸 SQL，与 iOS 逐条对照（见 §4.7.1） |
| **失败处理（Q13）** | ✅ **显式报错**（不保留 iOS 的静默降级） |
| 补充 | 服务层分析报告补归档为 `docs/migration-analysis/04-services-and-platform.md`；文档与 M0 代码一起提交 |

---

## 1. 迁移范围

### 1.1 做

| 页面 / 能力 | iOS 文件 | 说明 |
| --- | --- | --- |
| 首页日历 | `Views/Home/*`（1,931 行） | 月历 / 年历 / 周历 + 镜头缩放 morph + 翻月翻年 + 左右分栏（横屏） |
| 日记阅读 | `Views/Diary/ReadTextView` 等 | 段落样式 + 行内样式 + 内联图片 + 条目内搜索 + 分享长图 |
| 日记编辑 | `Views/Diary/RichTextEngine` 等 | 语义段落样式 + 行内样式 + 列表 / 待办 + 插入图片 + 时间位置 |
| 足迹 | `Views/Footprint/*` | 三级地点聚合 + 年度柱状图 + 年份筛选 |
| 搜索 | `Views/Search/*` | FTS 全文搜索 + 时间/地点筛选 + 关键词高亮 + 分页 |
| 设置 | `Views/Settings/*` | 5 张卡 / 14 个设置行 / 语言即时切换 / 备份导入导出 |
| 服务 | `Services/*` | 定位、提醒、分享、触感、主题、本地化、农历、日志、深链 |
| 自适应 | `Views/Components/AdaptiveLayout` | 竖屏 + **手机横屏**（可用宽度 750dp → medium 档） |

### 1.2 不做 / 特殊处理

| 项 | 处理 |
| --- | --- |
| iPad / Mac 三栏 | 不迁移。宽度分档保留 ≥1000dp 判定，但一律走手机版面（后续按需开） |
| iPhone Duo 折痕 / `reservedRegion` | 只保留「左右安全区分开读」的写法，不实现 |
| 系统浮条避让（横屏 `safeAreaInsets` 实测值） | iOS 是 `TabView(.sidebarAdaptable)` 的系统浮条；Android 是自绘底部导航，改为 `WindowInsets` 派生 |
| `FlowLightOverlay` 流光 | iOS 侧零实例化的未接组件 → Android 不实现（保留设计令牌备查） |

---

## 2. 结构框架对齐

### 2.1 目录映射（保持"同名同职责"）

| iOS | Android | 职责 |
| --- | --- | --- |
| `App/JustDiaryApp.swift` | `app/JustDiaryApp.kt` + `app/AppContainer.kt` | 入口、进程级单例装配（替代 `@UIApplicationDelegateAdaptor`） |
| `Views/RootView.swift` | `ui/root/RootScreen.kt` + `MainActivity.kt` | 导航宿主、Tab、编辑器全屏路由、语言/主题驱动 |
| `Views/Components/DesignSystem.swift` | `ui/theme/DesignSystem.kt` | 令牌：`Radius` / `Spacing` / `TypeSize` |
| `Views/Components/Theme.swift`（`Services/Theme.swift`） | `ui/theme/Theme.kt` + `ThemeTokens.kt` | 品牌色、明暗色板、混合色计算 |
| `Views/Components/DynamicType.swift` | `ui/theme/DynamicType.kt` | 设计字号 → 系统缩放 + 逐档上限 |
| `Views/Components/Components.swift` | `ui/components/*` | `GlassChip` / `GlassActionChip` / `GlassCountBadge` / `PressableGlassIcon` / `diaryCard` / `GlassEmptyState` / `TabBarClearance` / `AppAlertItem` |
| `Views/Components/AdaptiveLayout.swift` | `ui/components/AdaptiveLayout.kt` | 三档宽度 + 安全区 |
| `Views/Home/*` | `ui/home/*` | 见 §6 |
| `Views/Diary/*` | `ui/diary/*` | 见 §5 |
| `Views/Footprint/*`、`Views/Search/*`、`Views/Settings/*` | `ui/footprint/*`、`ui/search/*`、`ui/settings/*` | 页面 |
| `ViewModels/*`（`@Observable`） | `viewmodel/*ViewModel.kt`（`StateFlow` + `ViewModel`） | 一一对应 |
| `Services/*` | `service/*` | 一一对应 |
| `Data/DiaryRepository.swift` | `data/DiaryRepository.kt` | 一一对应 |
| `Data/SQLite.swift` | `data/db/*`（Room + `SupportSQLiteDatabase` 原始 SQL） | 手写 SQL 包装层 → Room 3 |
| `Models/*` | `model/*` | `DiaryModels.kt` / `ContentPart.kt` / `DateUtil.kt` / `Lunar.kt` / `AppSettings.kt` |
| `Resources/Localizable.xcstrings` | `res/values/strings.xml` + `res/values-zh-rCN/strings.xml` | 197 键同名保留 |
| `Resources/Assets.xcassets/*.colorset` | `res/values/colors.xml` + `res/values-night/colors.xml` | 13 个令牌色 |
| `JustDiaryTests/*` | `app/src/test/**`（JVM） | `content_json` 编解码 / 往返不变量 |
| `JustDiaryUITests/*` | `app/src/androidTest/**`（Compose UI Test） | 编辑、导入、morph 常量、设置行 |
| `tools/seed_sample_diary.py` | `tools/seed_sample_diary.py`（改 adb + Android 路径） | 示例数据 |
| `docs/*` | `docs/*`（随本仓库走） | 设计文档，**同源同步维护** |

### 2.2 目标工程树

```
JustGDiary/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml           # 版本目录（唯一版本真源）
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/cov/justdiary/   # ← 包名固定
│       │   │   ├── app/          JustDiaryApp.kt, AppContainer.kt, MainActivity.kt
│       │   │   ├── ui/
│       │   │   │   ├── root/     RootScreen.kt, AppTab.kt, LaunchIntent.kt
│       │   │   │   ├── home/     HomeScreen.kt, MonthCanvas.kt, YearCanvas.kt,
│       │   │   │   │             WeekRowCanvas.kt, CalendarLayout.kt, MorphLayer.kt,
│       │   │   │   │             DragPagePager.kt, DayContentPane.kt, CalendarPane.kt
│       │   │   │   ├── diary/    DiaryScreen.kt, EditorScreen.kt, RichTextEngine.kt,
│       │   │   │   │             PartsCodec.kt, EditorTextArea.kt, FontToolbar.kt,
│       │   │   │   │             ReadTextView.kt, MediaViews.kt, LocationChip.kt
│       │   │   │   ├── footprint/ FootprintScreen.kt, YearBarChart.kt, PlaceTree.kt
│       │   │   │   ├── search/   SearchScreen.kt, HighlightedText.kt, FilterSheets.kt
│       │   │   │   ├── settings/ SettingsScreen.kt, SettingsCards.kt
│       │   │   │   ├── components/ GlassChip.kt, GlassPanel.kt, DiaryCard.kt,
│       │   │   │   │             GlassEmptyState.kt, IconBadge.kt, AppAlert.kt,
│       │   │   │   │             BlobBackground.kt, TabBarClearance.kt, AdaptiveLayout.kt
│       │   │   │   └── theme/    Theme.kt, ThemeTokens.kt, DesignSystem.kt,
│       │   │   │                 DynamicType.kt, Type.kt
│       │   │   ├── viewmodel/    AppState.kt, HomeViewModel.kt, DiaryViewModel.kt,
│       │   │   │                 FootprintViewModel.kt, SearchViewModel.kt, SettingsViewModel.kt
│       │   │   ├── service/      LocationService.kt, ReminderService.kt, ImageShareService.kt,
│       │   │   │                 Haptics.kt, L10n.kt, Lunar.kt, BackupService.kt,
│       │   │   │                 ZipArchive.kt, FtsSegment.kt, SearchUtil.kt,
│       │   │   │                 FootprintDataService.kt, MediaStore.kt, Log.kt
│       │   │   ├── data/         DiaryRepository.kt, JustDiaryDatabase.kt, Dao*.kt, Migrations.kt
│       │   │   └── model/        DiaryModels.kt, ContentPart.kt, AppSettings.kt, DateUtil.kt
│       │   └── res/
│       │       ├── values/           strings.xml(en), colors.xml, themes.xml, dimens.xml
│       │       ├── values-zh-rCN/    strings.xml
│       │       ├── values-night/     colors.xml
│       │       ├── xml/              locales_config.xml, file_paths.xml, backup_rules.xml
│       │       └── mipmap-*/         图标
│       ├── test/                 JVM 单元测试
│       └── androidTest/          Compose UI 测试
├── tools/seed_sample_diary.py
└── docs/android-migration-plan.md      # 本文
```

---

## 3. 技术栈基线

已实测本机环境：**Android Studio 2026.1**、SDK Platform **android-37.0（API 37 = Android 17，extension 22）**、
build-tools 36.0.0、Studio 内置 JBR **JDK 25**、无 AVD / 无系统镜像 / 无连接设备。
（Maven / Gradle 源连通性已验证：`dl.google.com` 与 `services.gradle.org` 可达。）

| 项 | 取值 | 理由 |
| --- | --- | --- |
| 语言 | **Kotlin**（2.2+） | 你的要求；与 Swift 的 `struct/enum/extension` 语义最接近 |
| UI | **Jetpack Compose + Material 3** | Google 官方推荐；声明式与 SwiftUI 同构，`@Observable`↔`StateFlow`、`View`↔`@Composable` 一一对应 |
| 构建 | **AGP 9.4.0 + Gradle 9.7.1**（wrapper） | 已在 M0 实测编译通过并固化到 `gradle/libs.versions.toml`。⚠️ **AGP 9.0 起内置 Kotlin 支持**，不能再应用 `org.jetbrains.kotlin.android`（会直接构建失败）；Kotlin 2.4.20 由 AGP 内置提供 |
| `compileSdk` | **37** | 本机唯一已装平台 |
| `targetSdk` | **36** | 先对齐 Android 16 行为变更（edge-to-edge 强制、预测性返回），37 待验证 |
| `minSdk` | **33**（已确认，见 §0.1） | 平台 SQLite 确定含 FTS5；`POST_NOTIFICATIONS`、`localeConfig`、`java.time`、`android.icu.util.ChineseCalendar`、`android.icu.text.Collator`、动态取色全部原生可用 |
| 持久化 | **`SQLiteOpenHelper` + 裸 SQL**（已确认，见 §4.7.1） | FTS5 建表/`MATCH`/`snippet()`、备份合并、`recomputeDayKeys`、足迹 JOIN、搜索 SQL **本来全是裸 SQL**；可与 iOS 的 `Data/SQLite.swift` + `DiaryRepository` 的 SQL **逐条对照**，跨端契约可直接 diff 校验，也不需要 KSP/identity hash |
| 序列化 | **kotlinx.serialization** | 与 iOS `Codable` 对应；`content_json` 需精确控制键序与转义 |
| 异步 | **Kotlin Coroutines + Flow** | 对应 iOS 的 `async/await` + 串行队列 + `@Observable` |
| DI | **不用 Hilt**：`AppContainer` 手工装配单例 | iOS 侧就是 `DiaryRepository.shared` 等单例，手工容器结构更对称、构建更简单 |
| 图片 | **Coil 3**（可选）或自写 `BitmapFactory` + `LruCache` | iOS 是自写的 `DiaryImageStore`；自写可与 iOS 结构对称 |
| 设计 | **Material 3 + 动态取色（Material You）+ 自适应导航** | **零第三方 UI 库**，不引入任何玻璃/模糊库（见 §7） |
| 图表 | **自绘 Compose Canvas** | 全 app 只有 1 张柱状图，见 §11.2 |
| 定位 | **运行时探测 GMS**：`FusedLocationProviderClient`（有 GMS）/ 原生 `LocationManager`（无 GMS） | 已确认，见 §8.1 |
| 测试 | JUnit4 + Robolectric（JVM）/ Compose UI Test（instrumented） | 对应 iOS 的 `JustDiaryTests` / `JustDiaryUITests` |

---

## 4. 数据层迁移

### 4.1 表结构 1:1 保真（关键决策）

**保留 iOS 的表名、列名、字符串取值、索引名不变**，这样备份包可以跨端互通、文档与 SQL 可以直接对照。
Room 用 `@ColumnInfo(name = "...")` 显式对齐。

```sql
-- diary：一天一条
CREATE TABLE diary (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  day_key TEXT NOT NULL UNIQUE,          -- "YYYY-MM-DD"（按 dayStartHour 归日）
  summary TEXT NOT NULL DEFAULT '',
  search_text TEXT NOT NULL DEFAULT '',
  created_utc INTEGER NOT NULL,          -- UTC 毫秒
  updated_utc INTEGER NOT NULL
);

-- edit_block：一条日记的多个"片段"
CREATE TABLE edit_block (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  diary_id INTEGER NOT NULL,
  start_time_utc INTEGER NOT NULL,
  loc_text TEXT NOT NULL DEFAULT '',
  latitude REAL NOT NULL DEFAULT 0,
  longitude REAL NOT NULL DEFAULT 0,
  content_json TEXT NOT NULL DEFAULT '[]',   -- 见 §5.1
  search_text TEXT NOT NULL DEFAULT '',
  loc_precision TEXT NOT NULL DEFAULT 'none',-- none|province|city|district|street|exact
  loc_quality TEXT NOT NULL DEFAULT 'none',  -- none|coarse|precise
  country TEXT NOT NULL DEFAULT '',
  country_code TEXT NOT NULL DEFAULT '',
  region1 TEXT NOT NULL DEFAULT '',          -- 省
  region2 TEXT NOT NULL DEFAULT '',          -- 市
  region3 TEXT NOT NULL DEFAULT '',          -- 区县
  created_utc INTEGER NOT NULL,
  updated_utc INTEGER NOT NULL
);

CREATE TABLE diary_flag (day_key TEXT PRIMARY KEY);   -- 日历上"有日记"的日期标记

CREATE VIRTUAL TABLE diary_fts USING fts5(diary_id UNINDEXED, text, tokenize='unicode61');

CREATE INDEX idx_block_diary           ON edit_block(diary_id);
CREATE INDEX idx_block_start           ON edit_block(start_time_utc);
CREATE INDEX idx_block_country_region  ON edit_block(country, region1, diary_id);
CREATE INDEX idx_block_diary_quality   ON edit_block(diary_id, loc_quality);

PRAGMA user_version = 3;
```

迁移历史也要一起搬：`migrateSchema()`（v1 建表、v3 位置元数据重建，`province/city/district` → `region1/2/3`）
与 `migrateContentFormat()`（v1 → v2 内容格式，只跑一次，由 `SettingsStore.contentFormatVersion` 保证）。

### 4.2 并发模型

| iOS | Android |
| --- | --- |
| 所有 DB 访问在串行队列 `runOnQueue`（`DiaryRepository`） | Room 单写 + `Dispatchers.IO` 限并发；`DiaryRepository` 内用 `Mutex` 串行化写路径 |
| `nonisolated` 标注 + `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` | `suspend` 函数 + `withContext(Dispatchers.IO)`；ViewModel 默认 `viewModelScope`（主线程） |
| `NotificationCenter`（`.diaryVersionChanged` / `.uiTickChanged`） | `MutableSharedFlow` / `StateFlow`（`DiaryRepository.version`、`AppState.uiTick`） |

### 4.3 全文搜索（FTS）方案

iOS 现状：`CREATE VIRTUAL TABLE ... USING fts5(..., tokenize='unicode61')`，**失败时 `ftsSupported = false` 并降级为 LIKE**
（`DiaryRepository.ensureFtsTable`）。中文靠 `FtsSegment.segment` 在**相邻 CJK 字符间插空格**，因为
unicode61 会把整串中文当成一个 token。

Android 侧照搬同一策略（这是唯一能保证搜索结果与 iOS 一致的方案）：

1. Room **不做** `@Fts4` 注解实体，而是在 `RoomDatabase.Callback.onCreate/onOpen` 里用
   `SupportSQLiteDatabase.execSQL` 执行与 iOS **逐字相同**的 `CREATE VIRTUAL TABLE IF NOT EXISTS diary_fts USING fts5(...)`；
2. 建表失败（该设备 SQLite 无 FTS5）→ 与 iOS 一样置 `ftsSupported = false`，搜索降级到
   `LIKE ... ESCAPE '\'` + 手动窗口高亮（对应 `SearchUtil.highlightSegments`）；
3. `FtsSegment.kt` 逐条移植（CJK 区间 `4E00–9FFF`、`3400–4DBF`、`3000–303F`；`segment` 插空格、`desegment` 去空格且跳过 `<...>` 标签）；
4. `snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24)` 与 `desegment` 组合；分页 `limit = 200`；查询防抖 250ms。

> **FTS5 可用性（已确认口径）**：平台 SQLite 的 FTS5 自 **API 24+ 实际可用**，本项目 `minSdk 33` → **确定可用**，
> 不需要 `io.github.requery:sqlite-android` 之类的自带 SQLite 方案。
> 但**仍保留** iOS 那套"建表失败即 `ftsSupported = false` → 退 `LIKE ESCAPE '\'`"的降级（与 iOS 同构、成本极低）。
> **Room 的版本差异**（若改走 Room 路线）：Room 2.x 只有 `@Fts3`/`@Fts4`，**没有 `@Fts5`**；
> `@Fts5` 是 **Room 3.0（`androidx.room3`）**才引入的。但即便注解可用，`UNINDEXED` 列、相关子查询 `EXISTS(... MATCH ?)`
> 与 `snippet()` 仍只能手写 SQL，注解只省下建表。
> **降级到 FTS4 时的唯一改动点**：`snippet()` 签名从 FTS5 的 `(列号, start, end, ellipsis, tokens)`
> 变为 FTS4 的 `(table, start, end, ellipsis, col, tokens)`；`UNINDEXED` 要改写为 `notindexed=`。
> （`FtsOptions.TOKENIZER_*` 与 `tokenize=icu` 都不解决中文——Android 平台 SQLite 未编入 ICU tokenizer，
> 所以"应用层手工切分"是必须的，不是可选项。第三方 `wangfenjin/simple` 是 FTS5 专用扩展，会改变分词结果、
> 使 iOS 备份里的 `diary_fts` 索引失效，只宜作二期且需整库重建。）

### 4.4 备份 / 导入导出

格式完全保留（**跨端可互导**）：

```
JustDiary_yyyyMMdd_HHmmss.jdiary   （= ZIP）
├── manifest.json      { formatVersion: 1, includeSettings, settingsJson, ... }
├── db/just_diary.db   （SQLite 快照；Android 用 VACUUM INTO 或事务内复制，对应 iOS 的 sqlite3_backup）
└── images/img_<ms>.jpg （按 src 去重改名）
```

| 环节 | Android 实现 |
| --- | --- |
| 打包 | `java.util.zip.ZipOutputStream`（原生，替代自写 `ZipArchive`） |
| 解压 | `ZipInputStream`；CRC32 由 java.util.zip 自带校验 |
| DB 快照 | `VACUUM INTO 'path'`（SQLite 3.27+，API 30+）或 `db.query("...")` 逐表导出；低版本回退到"关闭连接 + 文件复制" |
| 导出 | 写 `cacheDir/export/*.jdiary` → `FileProvider` + `ACTION_SEND`（分享）；或 SAF `CreateDocument` 另存 |
| 导入 | SAF `OpenDocument` → 解压 → `mergeBackupDatabase(mode)`，**保留 skip / overwrite 两档语义与结果统计弹窗** |
| 需用户确认 | 是否要求备份与 iOS **双向**兼容（见 §14-Q3） |

### 4.5 媒体存储

| 项 | iOS | Android |
| --- | --- | --- |
| 目录 | `Documents/images/` | `filesDir/images/`（私有目录，无需权限） |
| 命名 | `images/img_<毫秒时间戳>.jpg`（**保持同名规则**，备份互通） | 同 |
| 降采样 | ImageIO，`maxPixel 2048` | `BitmapFactory.Options.inSampleSize` + `ExifInterface`（androidx） |
| 压缩 | JPEG `quality 0.85` | `Bitmap.compress(JPEG, 85)` |
| 缓存 | 自写 `DiaryImageStore`（以 `src` 为键） | Coil 3（内存+磁盘 LRU），或自写 `LruCache`（与 iOS 结构对称） |

### 4.6 设置存储

- iOS：`UserDefaults(suiteName: "group.com.cov.justdiary")`，key 为 snake_case，外加 `LockedBox` 内存缓存与非法值回退。
- Android：`SharedPreferences`（`MODE_PRIVATE`），**保留完全相同的 key 名与取值域**，使备份中的 `settingsJson` 可直接应用：

| key | 类型 | 默认 | 取值 |
| --- | --- | --- | --- |
| `app_language` | String | `system` | `system` / `zh` / `en` |
| `theme_mode` | String | `system` | `light` / `dark` / `system` |
| `day_start_hour` | Int | `4` | 0–23 |
| `week_start` | String | `monday` | `monday` / `sunday` |
| `auto_time` | Bool | `true` | ⚠️ iOS 侧**无任何消费者**（死设置）——见 §14-Q5 |
| `auto_loc` | Bool | `true` | |
| `allow_history_edit` | Bool | `false` | |
| `remind_enabled` | Bool | `true` | |
| `remind_hour` / `remind_minute` | Int | `21` / `0` | |
| `search_index_version` | Int | `3` | 与仓库常量比对 |
| `content_format_version` | Int | `2` | 一次性迁移标记 |
| `region_geocode_skip_v2` | Set\<Int\> | 空 | 反地理编码失败、不再重试的 block id |

### 4.7 数据层补充：逐行分析带来的修正与必须保真的细节

#### 4.7.1 存储路线：`SQLiteOpenHelper` + 裸 SQL（推荐）

| 方案 | 评价 |
| --- | --- |
| **① `SQLiteOpenHelper` + 裸 SQL**（推荐） | 与 iOS 的 `Data/SQLite.swift`（155 行手写封装）+ `DiaryRepository`（1,039 行）**逐条对照**：`onCreate/onUpgrade` 对应 `migrateSchema/migrateLocationMeta`，`SQLiteStatement` 对应 `prepare/bind/step`。**FTS5 建表、`MATCH`、`snippet()`、备份合并、`recomputeDayKeys`、足迹 JOIN、搜索 SQL 本来全是裸 SQL**，Room 注解只省下三张简单表的建表。跨端 SQL 契约可以直接 diff 校验 |
| ② Room 管普通表 + FTS5 走 `openHelper.writableDatabase.rawQuery` | 可行，但要维护两套 schema 真源，且 Room 的 schema 校验会与手写迁移摩擦 |
| ③ Room 3.0（`androidx.room3`）+ `@Fts5` | 注解最现代，但新包名/新版本稳定性待验证；`UNINDEXED`、相关子查询与 `snippet()` 仍要手写 |
| ④ 自带 SQLite（`io.github.requery:sqlite-android`） | **本项目不需要**：`minSdk 33` 下平台 FTS5 确定可用 |

> Room 无法直接打开 iOS 导出的 `.db`（缺 `room_master_table` 的 identity hash）；不过本方案的导入本来就是
> "解压 → 逐天合并进自己的库"，不依赖直接挂载备份库，所以这一条不构成选择依据。

#### 4.7.2 必须保真的跨端数据契约（冻结项）

1. 表名 / 列名 / 类型 / `NOT NULL` / 默认值，以及 FTS5 建表语句**逐字一致**；
2. `content_json` v2 信封、`style` 字符串、`TextRun` 只写开启样式、v1 兼容解码；
3. 图片引用键 `images/<basename>`；
4. 备份 zip 布局 `manifest.json` / `db/just_diary.db` / `images/*`，`formatVersion == 1`，manifest 字段名；
5. `search_text` / `summary` 的派生规则 + FTS `text` 的"相邻 CJK 插空格"分词；
6. epoch **毫秒**时间戳 + `YYYY-MM-DD` `day_key` + `dayStartHour` 语义（库内**不存时区**）；
7. 设置 key 名与默认值；8. 备份导入的**整天** skip/overwrite 语义与统计字段名。

#### 4.7.3 连接与事务（照搬 iOS）

`PRAGMA journal_mode=WAL`、`busy_timeout=3000`、**`foreign_keys=OFF`**、`SQLITE_OPEN_FULLMUTEX`；
事务用 **`BEGIN IMMEDIATE`**（不支持嵌套）；**全库无外键、无触发器、无视图**（一致性全靠代码）；
`PRAGMA user_version` 做 schema 版本（v1 → v3），迁移链见 §4.1。

#### 4.7.4 需要复刻的"既有行为"（含怪癖）

| 项 | 说明 |
| --- | --- |
| `summary` / `search_text` 是派生列 | 每次写块重算：`summary = substr(group_concat(block.search_text,' '),1,40)`、`search_text = group_concat(...,' ')`（全库最热 SQL） |
| `search_text` 部件间**无分隔符** | `flattenContent` 的 `joined()` 不带分隔符 → 相邻段落的 CJK 会连成一片。**这是既有行为，必须复刻**，顺手"修好"会让跨端搜索结果分叉 |
| `diary_flag` 是反规范化表 | 日历"有日记"标记；`addBlock` 时 `INSERT OR IGNORE`，删最后片段时 `DELETE`，导入/重算后由 `diary` 整表重建 |
| `recomputeDayKeys` 两阶段写 | 用 `tmp#<id>` 临时键绕开 `day_key UNIQUE` 冲突；目标键被别的移动记录占用且目标不同则计 `conflicts` 并跳过，返回值给设置页提示 |
| `mergeBackupDatabase(stats: inout)` | Kotlin 没有 `inout` 跨线程写 → **必须改成返回 `BackupStats`** |
| 备份/导入当前**全内存** | iOS 把整个 zip 拼成 `Data`、导入把 zip 解成 `[String: Data]`。Android 堆更紧 → **必须流式**（导出边写 `ZipOutputStream`，导入用 `ZipFile` 随机读 + 单图流式落盘），否则大库必 OOM |
| DB 快照 | iOS 用 `sqlite3_backup`（含 WAL 未 checkpoint 数据）→ Android **无公开等价**，用 `VACUUM INTO`（目标文件必须不存在、不能在事务内）；`minSdk 33` 满足 SQLite 3.27+ 要求 |
| 导入兼容旧 schema | 用 `PRAGMA table_info(edit_block)` 探测列：`region1` / `loc_quality` / `country_code` 缺失时回落 `province/city/district`、由坐标是否 0 推导 `loc_quality` |
| 导入末尾整库重建索引 | `rebuildSearchIndex()` 无条件跑一次 → 即使分词器有差异也能自愈 |
| 搜索没有 rank | **只有 `ORDER BY day_key DESC`**，无 BM25；`LIMIT 201 OFFSET n`（`+1` 探 `hasMore`）；`snippet(...,24)`；LIKE 回退片段窗口 ±18 字；关键词防抖 250 ms |
| 地点候选上限 | 地点筛选列表最多 **20** 项，`count DESC` 再按 `country+region1` 升序；`noLoc` 单独返回计数 |
| 反地理编码回填 | 每批 **30** 条；`WHERE id=? AND region1=''` 幂等；失败 id 进 `region_geocode_skip_v2`，上限 2000（是 `Set` 无序截断，**不是 LRU**） |
| `content_format_version` | **备份恢复不重置**（它标记的是存储格式迁移，不是偏好） |
| 缓存规格 | 图片缓存 200 项 / 150 MB；`ContentPart` 缓存 300 项 FIFO；内联图渲染降采样 `max(storedW, storedH) × 3`；预览 1600 px |
| 足迹**没有聚合 SQL** | `allFootprintRows()` 是一条 `edit_block JOIN diary` 明细查询，三级树 / 统计 / 年度趋势**全在内存算**；`region3` **不进路径**；节点 `count` = 经过该节点的行数；年度按年升序、`days` 去重 dayKey、`places` 去重完整路径键 |

#### 4.7.5 两处对第三方库判定的修正

| 项 | 原判定 | 修正 |
| --- | --- | --- |
| 足迹排序 `localizedStandardCompare`（区域感知 + 数字自然序） | 分析报告判"需第三方（icu4j）" | **不需要**：`android.icu.text.Collator`（平台 API 24+）带 `setNumericCollation(true)`，`minSdk 33` 可用；实施时用单测与 iOS 的排序结果对齐验证 |
| 反地理编码 `region1/2/3` 映射 | — | 三条规则必须逐条复刻：`region1 = adminArea ?? subAdminArea`、`region2 = subAdminArea ?? locality`、`region3` 仅当 `locality != nil && subAdminArea != nil` 时取 `subLocality`。**换地理源会改变同一坐标的 region，从而改变足迹分布** —— 这是 Q6 决策的直接后果 |

---

## 5. 编辑器迁移（最大工作量）

### 5.1 存储格式：`content_json` v2（**必须逐字节兼容**）

```jsonc
// v2（当前）
{"v":2,"parts":[
  {"style":"title","runs":[{"text":"标题"}]},
  {"style":"body","runs":[{"text":"正文"},{"text":"粗","bold":true}]},
  {"style":"quote","align":"center","runs":[{"text":"引用"}]},
  {"style":"list","items":["甲","乙"]},
  {"style":"todo","items":["甲"],"done":[true]},
  {"style":"image","src":"images/img_1737000000000.jpg","w":1200,"h":900}
]}

// v1（旧，需能读、读到就升级）
[{"type":"h1","runs":[{"text":"标题","size":22}]},
 {"type":"p","runs":[{"text":"正文","size":15,"bold":false}]}]
```

规则（来自 `ContentPart.swift`，Android 必须完全一致）：

| 项 | 规则 |
| --- | --- |
| 信封 | `{"v":2,"parts":[…]}`；v1 是裸数组 |
| `style` 取值 | `title` / `heading` / `body` / `list` / `todo` / `quote` / `image`（**未知值不抛错，按 `body` 处理**） |
| v1 兼容 | `type` → `style`；`h1`→title、`h2`→heading、`p`→body、`ul`→list、`img`→image；`run.size` **丢弃** |
| run 字段 | `text` + `bold`/`italic`/`strike`/`underline`；**只写 `true`**，未开启省略（不写 `false`） |
| part 可选字段 | `text`（无行内样式时的紧凑形式）、`items`+`done`（列表/待办）、`src`+`w`+`h`（图片）、`align` |
| 编码格式 | Swift `JSONEncoder` 的 `.sortedKeys` + `.withoutEscapingSlashes` → **键按字母序、`/` 不转义、中文不转 `\uXXXX`** |
| 图片路径 | 统一 `images/xxx.jpg`（`ImagePathUtil.normalizeSrcKey` 补前缀） |
| 落库前处理 | 所有保存/导入路径都过 `normalizeContent`（parse + serialize），顺带完成 v1→v2 升级 |

> **Kotlin 实现要点**：用 `kotlinx.serialization` 时把属性**按字母序声明**（`align, done, h, items, runs, src, style, text, w`）以匹配 `sortedKeys`；
> 数字格式需注意 Swift 的 `Double` 会写成 `1200` 而 Kotlin 默认写 `1200.0` —— 若要**字节级一致**（备份 diff 友好），
> 给 `w`/`h` 写一个"整数则不输出小数位"的自定义序列化器。语义层面不一致不影响读取，但会影响备份文件的可比性。

### 5.2 技术路线（结论：`AndroidView` + `EditText` + `Spannable`）

iOS 侧编辑器是 **UIKit 桥接**：`RichTextView` 用 `UIViewRepresentable` 包 `UITextView`，
`RichTextEngine`（1,024 行）在 `NSRange` ↔ `content_json` 之间做双向映射，段落样式以**自定义 attributed-string 键**
（`.diaryBlockStyle` / `.diaryDesignSize`）挂在文本上。

Android 三条候选路线：

| 路线 | 结论 |
| --- | --- |
| **A. `AndroidView` 包 `EditText` + `Spannable`**（**推荐**） | 与 iOS 的"UIKit 桥接"结构**完全对称**；IME（中文拼音/手写）、选区、光标、`StaticLayout` 换行、大文本性能都由平台保证；段落样式用**自定义 `ParagraphStyle`/`CharacterStyle` 子类**承载（对应 iOS 的自定义 attributed 键）。风险最低 |
| B. 纯 Compose `BasicTextField` + `TextFieldState` | ❌ 不可行。TextFieldState **不支持 `AnnotatedString`、不支持局部样式**（Google issue 389978748）；`OutputTransformation` 只能改文本不能加样式，`VisualTransformation` 是只读绘制层 |
| C. 第三方富文本库（WordPress-Aztec、RichEditor-Android、ComposeTextEditor 等） | ❌ 都会引入一套自己的文档模型与 HTML 往返，与 `content_json` 语义不对齐，反而更贵 |

推荐路线 A 的分工：

```
PartsCodec.kt          content_json ⇄ List<ContentPart>（纯 Kotlin，可 JVM 单测）
SpannableBridge.kt     List<ContentPart> ⇄ Spannable（BlockStyleSpan / InlineStyleSpan）
RichTextEngine.kt      选区查询与样式开关、段落样式应用、列表/待办重排、图片插入
EditorTextArea.kt      AndroidView(AppCompatEditText) + 焦点/IME/最小高度/占位符
ReadTextView.kt        同一个 SpannableBridge 产出只读 Spannable 给 TextView —— 保证"编辑所见 = 阅读所见"
FontToolbar.kt         样式菜单 + 行内样式按钮 + 引用 + 列表 + 待办 + 插入图片
```

**必须保留的往返不变量**（iOS 用测试守住，Android 同样要守）：

1. 段落样式是**语义**的：字号由样式派生（28 / 22 / 17 / 15），**绝不落库**；调整字号梯级不得改写已有日记；
2. 存储里没有 `size`、没有 `false` 标志；
3. 全 12 档系统字号下"加载 → 解析 → 保存 → 再解析"后块样式、对齐、文本、行内样式、待办状态、列表分组不变；
4. 未知样式值按 `body` 处理而不抛异常。

> iOS 侧踩过的坑要在 Android 上主动规避：**不能从绘制字号反推块类型**（开启动态字体后 17pt 正文会画成 ~25.5pt，反推会把正文判成标题）。
> Android 侧同理：`Spannable` 里绝不能只用 `AbsoluteSizeSpan` 表达段落层级，必须有独立的块样式 span。

### 5.3 编辑器交互清单（与 iOS 对齐）

| 交互 | 实现 |
| --- | --- |
| 段落样式菜单 | `IconButton`(textformat.size) + `DropdownMenu`：大标题 / 小标题 / 正文；引用单独按钮（`text.quote`，同时是引用底色标记） |
| 行内样式 | 粗体 / 斜体 / 删除线 / 下划线 / 居中；选区为空时切换"后续输入"状态 |
| 列表 / 待办 | 无序列表圆点；待办为可点勾选框 + 渐变填充，勾选状态落 `done[]` |
| 插入图片 | Photo Picker（`ActivityResultContracts.PickVisualMedia`，**无需存储权限**）→ 降采样 2048 / JPEG 85 → `images/img_<ms>.jpg` → 插入 `image` part |
| 时间 | 打开编辑器时自动写入 `start_time_utc`（对应 `auto_time`） |
| 位置 | 见 §8.1（规则严格照搬 `docs/location-recording.md`） |
| 放弃修改 | **不是撤销栈**：保存进入编辑时的快照，点"放弃修改" → 二次确认 → 恢复快照（iOS 语义） |
| 格式栏位置 | 键盘弹起贴键盘上方 8dp；收起时贴系统手势条上方 12dp（`WindowInsets.ime` / `navigationBars` 派生，**不写死 84dp**） |
| 输入区最小高度 | 160dp × 正文字号动态系数（大字号下约 240dp） |
| 占位文字 | 高度按字体行高计算，不写死 22dp |

### 5.4 必须主动规避的"静默数据损坏"暗坑（**最高优先级**）

这些是 iOS 侧真实踩过或真实存在的坑，Android 重写时**必然重现**，必须在设计阶段就堵住：

| # | 坑 | iOS 证据 | Android 规避方式 |
| --- | --- | --- | --- |
| **E1** | **字号反解会永久损坏块类型**。不能用"绘制字号 ÷ 系数"近似反解段落样式：实测漂移 `15 → 18.75`，一旦 ≥ 阈值就把**正文永久变成标题**（存进库、不可逆） | `RichTextEngine.swift:173-183`（兜底必须对自产字号做**精确匹配**，容差 < 0.01） | 块样式**只由显式 span 决定**；`AbsoluteSizeSpan` 永不参与样式推断；反解仅作最后兜底且用精确匹配 |
| **E2** | **绘制字号绝不能落库**。开启动态字体后 17pt 正文画成 ~25.5pt，拿绘制值回推会把正文判成标题 | 同上 + `docs/design-system.md` §3.3 | 落库只写语义 `style`；字号每次加载时由样式重新推导 |
| **E3** | **段落级编辑必须从后往前**。删除段首 marker 会改变后续所有偏移，正序处理会错位 | `RichTextEngine.swift:330-333` | 段落重排/列表合并一律逆序遍历 |
| **E4** | **IME 组合区（composition range）必须跳过**。iOS 侧同一个根因是"UIKit 从第二个字符起丢自定义 run 属性"；Android 会以 composition range 形式出现 | iOS **完全没有** marked text 代码（全仓无 `markedTextRange`） | `Spannable` 上做格式操作时先检查 `BaseInputConnection.getComposingSpanStart/End`，跳过组合区 —— **这是净新增工作量，不是移植量** |
| **E5** | **全量替换会摧毁撤销栈且是 O(n)**。iOS 每次 `apply()` 都 `replaceCharacters(in: 全文)` | `RichTextEngine.swift:737-744`；全仓无 `undoManager` | 用增量 span 操作 + 挂 `androidx`/平台 `UndoManager`（API 23+）——**白送的功能增强**，顺带修掉性能问题 |

### 5.5 编辑器可移植资产的分层（**不能整文件搬运**）

`RichTextEngine.swift`（1,024 行）**不是纯模型层**，它 `import UIKit`，内部混了三层。按类型切分：

| 层 | iOS 位置 | 可移植性 |
| --- | --- | --- |
| 纯枚举 / 常量（字号梯级、行距比例、段后距） | `RichTextEngine.swift:19-184` | ✅ 直接移植（行距 = `designSize × 0.13`，引用 `× 0.5`；段后距 title 0.4 / heading 0.3 / quote 0.4 / body 0） |
| 编解码 `PartsCodec` | `RichTextEngine.swift:802-1023` | ✅ 直接移植（配合 `ContentPart.swift:11-242` 全量） |
| UIKit 控制器（`RichEditorController`） | `RichTextEngine.swift:186-745` | ❌ 层间映射要重写（`NSTextStorage`→`SpannableStringBuilder`、`NSTextAttachment`→`ImageSpan`、`NSAttributedString.Key`→自定义标记 span） |
| 第二套 UIKit 桥接（阅读态） | `ReadTextView.swift`（166 行，全量） | ❌ 重写为只读 `TextView` + 同一套 `SpannableBridge` |
| 图片采集 | `MediaViews.swift:104-137`（`PHPickerViewController`，**不是** SwiftUI `PhotosPicker`） | ⚠️ 换 Photo Picker；参数照搬：`maxPixel 2048`、JPEG **0.85**、`img_<epochMillis>.jpg`、键 `images/<name>`；渲染再降到 `max(storedW, storedH) × 3`；预览 `maxPixel 1600`；插入宽 `min(viewWidth-24, 343)`、高 `max(40, ratio × maxW)` |
| 分享长图（**第三套独立渲染管线**） | `ImageShareService.swift`（458 行） | ⚠️ 常量必须逐项对齐：720 / 108 / 48 / 564 / `dateY 64` / `footerH 34`；每样式字号 **36 / 32 / 31 / 30，且不走 Dynamic Type**；自研逐字符换行（CJK 墨迹比 **0.87**）；与编辑器零耦合 |

### 5.6 落地顺序建议（把风险前置）

1. **第 0 步（不依赖任何 UI 决策）**：以 iOS 真实 `content_json` 作为 **golden file** 做字节级兼容验证
   （含 `sortedKeys` 键序与 `withoutEscapingSlashes` 对齐）——这是后续全部工作的正确性基线；
2. 再做 `PartsCodec` + `SpannableBridge` 的纯 Kotlin 层 + 全 12 档字号往返单测；
3. 最后接 `EditText` UI 与格式栏。若对编辑体验有疑问，可先用**同一最小需求**（中文 IME + 选区加粗 +
   段落样式切换 + 100KB 文本性能）做一次小样对比，再决定是否需要更深的 Compose 方案。

---

## 6. 首页日历与 morph 动画

### 6.1 布局数学（常量原样搬）

| 形态 | 版面 |
| --- | --- |
| 竖屏（402×874dp 基准） | `‹ 年月` 胶囊 + 「今天」 → **72dp 标题槽**（32pt 月标题）→ **30dp 星期栏** → 六行月格（有日记=下划线、今天=圆环、选中=实心圆、未来=35% 不透明） |
| 横屏（可用 750×382dp） | 左栏月历 **345dp** + 右栏日记 **≈356dp**，两栏间 16dp，页面自身边距 16dp |
| 横屏·高度不足 | 左栏降级为**周条**（一行 7 天，横向翻周） |
| 横屏·年份 | **不提供年份切换**（整块头部隐藏，右栏标题行也没有年份胶囊，上下滑跨月自然跨年） |
| 横屏·回到今日 | 「今天」放在右栏标题行 |

日历密度按可用高度三档降级（替代"月↔周整屏 morph"）：

```
可用高度 ≥ 6×60 + 标题  → 月格（含农历行）
可用高度 ≥ 6×44 + 标题  → 月格（隐藏农历行）
否则                    → 周条（单行）
```

实现方式：`Canvas` + `DrawScope`（对应 iOS 的 `MonthCanvas` / `YearCanvas` / `WeekRowCanvas`）。
`CalendarLayout.kt` 集中所有度量常量：`bigTitleH(72)`、`weekdayHeaderH(30)`、`miniMetrics`、
`portraitGridOriginY`、`fullMonthGridRect` —— **morph 槽位与真实排版必须共用同一组常量**，
否则动画收尾会整体跳一下（iOS 侧为此复发过一次，Android 同样要由测试守住）。

### 6.2 morph 动画

| 项 | iOS 现状 | Android 方案 |
| --- | --- | --- |
| 年↔月 / 月↔周曲线 | `timingCurve(0.2, 0.75, 0.3, 1.0)`，0.6s（年↔月原先用 `easeInOut`，因"起步半拍慢"而统一） | `CubicBezierEasing(0.2f, 0.75f, 0.3f, 1f)` + `tween(600)`，**同一曲线** |
| 驱动 | `withAnimation` + 自定义插值（frame / opacity / scale / cornerRadius） | `updateTransition` + `Animatable`；形状 morph 可用 `androidx.graphics.shapes.Morph` |
| 起止 | 起点=月历/年历真实排版，终点=另一形态真实排版 | 同；由 `CalendarLayout` 共用常量保证 |
| 性能 | 三图层 `ZStack` + `opacity(0)` 仍每帧重建 → 55 个 Canvas/帧；修复为 morph 期间**不构建**底层 layer、禁用分页器不再构建邻页 | 同样处理：morph 期间 `if (!morphing) {...}`，`HorizontalPager` 用 `beyondViewportPageCount = 0` |
| 减弱动效 | `CalendarLayout.reducedMorphAnimation`（0.18s 线性），状态照常切换 | `ValueAnimator.areAnimatorsEnabled() == false` → 切换 0.18s 线性 spec |
| 调试开关 | `-slow-morph` / `SLOW_MORPH=1` 放慢到 3s；`-morph-log` 写逐帧日志（`#if DEBUG`） | `BuildConfig.DEBUG` + `Intent` extra / 系统属性；帧日志用 `Choreographer.FrameCallback` |

### 6.3 手势

| 手势 | iOS | Android |
| --- | --- | --- |
| 竖屏点年月胶囊 → 年历 morph | tap | tap |
| 竖屏点日期 → 周视图 morph | tap | tap |
| 上下滑翻月 | `DragPagePager`（竖直） | `VerticalPager`（`beyondViewportPageCount = 0`，翻页收尾把 `current` 与 `offset=0` 放进**同一个无动画事务**，避免"旧偏移渲染新页"的一帧跳变） |
| 横屏翻月/翻周 | 同上，左栏内 | 同上 |
| 翻月后选中日 | 跟随到新月份的同一天（月末按当月天数夹住） | 同 |
| 年月切换的无障碍 | 可调节动作（`accessibilityAdjustableAction`） | `Modifier.semantics { customActions = ... }` + `a11y_prev_day/next_day/prev_year/next_year` 文案（iOS 侧此处缺标签，Android 一并补全） |

### 6.4 精确常量与可直接当单测断言的数值

**常量**（`CalendarLayout.swift:103-193`）：
`morphDuration 0.6`（DEBUG 下 `-slow-morph` / `SLOW_MORPH=1` → 3.0）、`weekdayHeaderH 30`、`bigTitleH 72`、
`compactMonthTitleH 34`、`compactWeekdayHeaderH 26`、`weekStripH 68`、`dayTitleH 40`、`yearTitleH 62`、
`yearPad 16`、`yearSpacing 6`、`miniPad 6`、`miniTitleH 17`、`minimumRowHeight 44`。

**公式**：
`monthCellW = w/7`；`weekdayFontSize = clamp(cellW×0.26, 11, 14)`；`monthCellH = (areaH-72-30)/6`；
`fullMonthGridRect = (0, 102, w, h-102)`；`yearCardSize = ((w-32-12)/3, (h-62-8-18)/4)`；
`miniDayFont = clamp(min(cellW×0.95, cellH×0.5), 9, 14)`；
`yearCardRect.x = 16 + col×(cardW+6)`、`.y = 70 + row×(cardH+6)`（`col=(m-1)%3`、`row=(m-1)/3`）；
`monthMetrics.dayFont = clamp(cellH×0.34, 13, 20)`、`.lunarFont = clamp(cellH×0.18, 9, 11)`。

**密度判定**：`resolve(availableHeight, rows, wantsLunar)` → `perRow = round(avail/rows)`；
`≥60 → .month(lunar:wants)`、`≥44 → .month(lunar:false)`、`else → .weekStrip`。
注意：**竖屏不走 `resolve`**，农历显隐只由**语言**决定；**横屏才由高度决定**。
横屏手机 402dp 高时 `paneH = max(220, 402-8-(20+44)) = 330` → **恒为 `.month(lunar:false)`**，
周条触发条件是 `paneH - 60 < 44 × rows`（402dp 高时不会触发）。

**状态标识**：日记标记是**宽 `min(20, cellW×0.5)`、圆角 1dp 的 2dp 高下划线**（不是圆点）；
今天 = 1.5dp 描边圆环；选中 = 实心 `primary` 圆（`circleD = min(cellW-3, contentH+14, cellH-3)`）；未来 `alpha × 0.35`。
行分隔线 `outlineVariant@0.35`、高 1dp、`y = rowY - 0.5`、宽 `cellW×7`，**整行不属本月则不画**。

**翻页阈值**（`DragPagePager.swift:58-104`）：`minimumDistance 8`；拖动偏移夹在 `±pageSize`；
橡皮筋 `c = pageSize×0.25`、`c·x/(x+c)`；翻页判定 `goNext = predictedEnd < -90 || (predictedEnd < 0 && translation < -60)`（**90 预测 / 60 实际**）；
翻页动画 `.snappy(0.3)`。

**范围**：`allMonthKeys` = 1900…2100 × 12 = **2,412** 键；`allYears` = **201** 项；`allWeekKeys` 按 `weekStart` 缓存。

**建议直接作为 Compose 单测断言的一组推演值**：

| 场景 | 值 |
| --- | --- |
| 竖屏 402×874 | `cellW 57.43`、`cellH 118`、`dayFont 20`、`lunarFont 11`、`contentH 40`、`circleD 54`、`weekdayFont 14`、网格起点 `(0, 102)` |
| 横屏 750×402 | `paneH 330`、`headerH 60`、`rows5 → cellH 54` / `rows6 → 45`、恒 `.month(lunar:false)`、`calendarW 345`、`dayW 356.5`、`cellW 49.29`、`dayFont 15.3 / 18.36`、`circleD 34.3 / 36.4`、内容左边界 `78` |

### 6.5 Compose 实现注意事项（性能与正确性）

1. **morph 不要用 `SharedTransitionLayout`**。iOS 是**手写单个 `progress: Double` + 纯函数 lerp**（`MorphViews.swift:5-25, 97-117`），
   而不是 `matchedGeometryEffect`；网格的连续变形只有"一个进度 + 逐项插值"能做出来。Compose 对应 `Animatable<Float>` / `animateFloatAsState`，
   插值项 = frame（`lerp(Rect)`）/ 字号（`lerp(DayMetrics)`）/ opacity（`clamp01((t-a)/b)`）/ scale / offset；**cornerRadius 不参与 morph**。
2. **progress 必须在绘制阶段读取**，否则每帧触发**重组**（而不是重绘）。含 12 个迷你月 Canvas 的 morph 视图成本会显著高于 iOS 的 `Animatable`。
   做法：`Modifier.drawBehind { val p = progressState.value }` 或 `graphicsLayer { }` lambda，或把整个 morph 收进**一个 Canvas**。
3. **文字测量缓存要调大**。iOS 侧自建 LRU 上限 **24,000** 条并把字号量化到 **0.5pt**（`CalendarGrids.swift:28-34, 137-167`）；
   Compose `TextMeasurer` 默认缓存极小，必须显式放大或自建 LRU，否则 morph 每帧重解析文字会掉帧。
4. **morph 期间不要构建底层 layer**（iOS 靠这个省掉每帧 42 个 Canvas）；分页器 `beyondViewportPageCount = 0`。
5. **删除整个 `contentInset` 逻辑**：iOS 横屏左侧 62pt 避让是"系统浮条变左侧竖排胶囊"造成的，
   **Android 不存在**（导航栏在底部或改成 Rail）——这是本次迁移**最大的减法**，照抄会白留一条 62dp 空白。
6. **状态栏/导航栏避让只做一次**：iOS 侧因为 `HomeView` 与 `MonthPane` 各减一次 `safeArea.leading`，
   第一版把日历推到 x≈222。Compose 侧只在最外层读一次 `WindowInsets`。
7. 用**一个 `Canvas` 画一个月**（对应 iOS 每月 1 个 Canvas）；每页 `.clipRect()`；`weeksCache` / `weekKeysCache` 照建。
8. iOS 侧已知的两处死代码**不要照搬**：`AdaptiveLayout.splitsSearch` 从未被使用（SearchView 只用 `isPortrait`）；
   `DayContentView.showFutureToast` 从未被调用（所以"未来日期 toast"路径实际不存在）。

---

## 7. 设计系统：采用 Material 3，**不模仿 Liquid Glass**

> **方向已确认（2026-09-17，Q1/Q10）**：Android 侧以 **Google Material Design（Material 3）为设计语言**，
> **不模仿 iOS 的 Liquid Glass**，也**不照抄 iOS 的界面**。原项目提供的是**功能、交互与动画**的参考；
> 视觉表现（材质、层级、导航形态、控件形状）一律按 **Google 官方规范**重新设计。
> 目标不是"看起来像 iOS 版"，而是"**同一个产品，用 Android 原生的方式表达**"。

### 7.1 迁移原则（已修订）

| 维度 | 处理方式 |
| --- | --- |
| **功能** | **完全对齐**：五个页面、14 个设置项、5 条位置规则、备份导入导出、FTS 搜索、足迹三级聚合，一项不少 |
| **交互** | **对齐路径与结果，不对齐控件外观**：点日期进日记、上下滑翻月、点胶囊进年历、长按/点击的语义、二次确认的触发条件都保留；但"胶囊""浮条""玻璃面板"这类 iOS 控件形态换成 M3 对应控件 |
| **动画** | **对齐节奏与语义**：morph 的时长/曲线/插值语义（0.6s / `timingCurve(0.2,0.75,0.3,1.0)` / 减弱动效降级 0.18s 线性）、翻页阈值、状态切换时机全部保留；但动画的**表达方式**用 Compose/M3 的惯用法（如 M3 motion scheme、shape morph） |
| **视觉** | **重新设计**：按 M3 的色彩（含动态取色）、层级（surface container + tonal elevation）、形状、排版、导航规范执行；**不引入任何玻璃模糊** |
| **数据** | **严格保真**（见 §4.7.2），与视觉无关 |

由此产生的三个直接结论：

1. **不引入 `haze`**（原 §7.4 的三档玻璃方案作废）—— 第三方 UI 库归零；
2. **横屏用 Android 原生导航**（Q10）：紧凑宽度用 **`NavigationBar`**，中等/扩展宽度用 **`NavigationRail`**（而非照抄 iOS 的底部悬浮条），横屏分栏数值按 Rail 宽度重算；
3. **启用动态取色**（Q4）：Android 12+ 默认使用 **Material You** 壁纸取色；不可用时回落到品牌色板。品牌色 `#2563EB` 作为**动态取色的种子色 / 回落色**。

### 7.2 Material 3 落地映射（替代原"液态玻璃映射"）

原 iOS 控件 → **Android 侧按 M3 规范重新设计**（不是"换个材质照抄"）：

| iOS 原控件（功能语义） | Android 侧设计（Material 3） |
| --- | --- |
| 内容卡片 `diaryCard` | **`Card` / `OutlinedCard`**（`surfaceContainerLow` + `outlineVariant` 描边 + M3 elevation），圆角用 M3 shape scale（`medium` 12dp / `large` 16dp），不再用 20dp 的 iOS 令牌 |
| 顶部"‹ 2026年"与"今天"（导航+快捷动作） | **`TopAppBar`**（M3 标准）+ 溢出动作；年份入口用 `IconButton` 或 `AssistChip` |
| 底部悬浮导航条（主导航） | **`NavigationBar`**（紧凑）/ **`NavigationRail`**（中等、扩展，含横屏）—— 官方自适应导航规范 |
| 编辑器格式栏（操作面板） | **`BottomAppBar`** 或 M3 工具栏；行内样式用 **`IconToggleButton`**，段落样式用 `DropdownMenu` |
| 搜索框（搜索入口） | **`DockedSearchBar` / `SearchBar`**（M3 搜索组件，自带形状与色调） |
| 各类"胶囊"按钮 | **`FilledTonalButton` / `OutlinedButton` / `AssistChip` / `FilterChip`**（按语义选，不按 iOS 外观选） |
| 未来日期 toast | **`Snackbar`**（M3 标准） |
| sheet（模态） | **`ModalBottomSheet`**（M3 标准，自带 scrim 与 drag handle） |
| 复选框类待办勾选 | **`Checkbox`**（M3）；列表项用 `ListItem` |
| 设置行（图标徽章 + 标题 + 副标题 + 尾值/开关） | **`ListItem`**（leading icon / headline / supporting text / trailing）+ `Switch` / `SegmentedButton` / `DropdownMenu` |
| 月份/年份切换 | M3 无内置日历控件 → **自绘 Canvas 月格**（保留原布局数学与手势），视觉按 M3 色彩与形状 |
| 圆角令牌（`Radius.card 20` 等） | **改用 M3 shape scale**（`extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28`），避免与 M3 组件内部圆角冲突；自绘的日历/图表可保留原数值 |

### 7.3 系统设置适配（保留原对齐目标）

| 系统设置 | Android 做法 |
| --- | --- |
| 浅色 / 深色 / 跟随系统 | `values-night` + `AppCompatDelegate.setDefaultNightMode`；应用内 `theme_mode` 三值映射 |
| **动态取色（Material You）** | **默认启用**：Android 12+ 用 `dynamicLightColorScheme/dynamicDarkColorScheme`；不可用或用户关闭时回落品牌色板（seed `#2563EB`）。设置页提供开关 |
| 文字大小（动态字体） | `sp` 天然随 `fontScale` 缩放；**逐档上限**用 `LocalDensity` 覆写 `fontScale = min(systemFontScale, cap)`，上限表照搬（默认字号下系数恰为 1） |
| 日历文字 | 与 iOS 一致地单独用**半速**系数（自定义 Canvas 绘制，需手动缩放） |
| 减弱动态效果 | `ValueAnimator.areAnimatorsEnabled()` / `Settings.Global.ANIMATOR_DURATION_SCALE`；开启时 morph → 0.18s 线性 |
| 增强对比度 | `AccessibilityManager.isHighTextContrastEnabled` → 卡片描边加粗（沿用 iOS 的 0.5dp/45% → 1.5dp/90% 规则） |
| 品牌色 | 作为动态取色的**种子**与回落色；不再"刻意固定"（这是 Q4 的确认结果，属有意与 iOS 分叉） |

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `Radius.card` | 20.dp | 内容卡片、列表行、hero 面板 |
| `Radius.panel` | 26.dp | 悬浮控件面：编辑器格式栏、sheet |
| `Radius.field` | 14.dp | 输入框、卡片内次级面 |
| `Radius.image` | 14.dp | 卡片内联图片 |
| `Radius.badge` | 12.dp | 图标徽章 |
| `Radius.bar` | 4.dp | 图表柱、进度条 |
| `Radius.hairline` | 1.dp | 2dp 高的"有日记"标记线 |
| `Radius.concentric(outer, inset)` | `outer - inset` | 嵌套圆角公式**原样保留**（Material 无等价概念，手算即可） |
| `Spacing.*` / `TypeSize.*` | 照搬 | 只收敛已被重复使用的值，不重新排版 |

字号梯级（设计字号，非最终绘制值）：`32 / 28 / 24 / 22 / 20 / 18 / 17 / 16 / 15 / 13 / 12 / 11`，
编辑器四档：`title 28 / heading 22 / body 17 / quote 15`。

### 7.4 动态字体上限表（照搬 `DesignSystem.swift:124-136, 143-156`）

| 设计字号 | 32 | 28 | 24 | 22 | 20 | 18 | 17 | 16 | 15 | 13 | 12 | 11 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 缩放上限（近似） | 1.40 | 1.40 | 1.45 | 1.45 | 1.45 | 1.50 | 1.50 | 1.55 | 1.60 | 1.65 | 1.70 | 1.75 |
| AX5 实际 | 44.8 | 39.2 | 34.8 | 31.9 | 29 | 27 | 25.5 | 24.8 | 24 | 21.5 | 20.4 | 19.3 |

> ⚠️ **方向不要搞反**：上限随字号**变小而变大**（小字号相对增幅更大），这样缩放后整条梯级才保持单调。
> 上表的"缩放上限"是由 AX5 实测值反推的近似值，**M2 阶段必须从 `Views/Components/DesignSystem.swift:124-136`
> 逐字核对后再固化到 `DesignTokens.kt`**；代码里的不变量测试只断言"缩放后严格递减"，因此换成精确值后测试依然有效。

编辑器四档对应 Apple 文本样式与上限：title1 → 28 / **1.40**、title2 → 22 / **1.45**、body → 17 / **1.50**、subheadline → 15 / **1.60**（与上表一致）。
**日历是唯一的例外**：`calendarMultiplier = 1 + (multiplier(for: 15) - 1) × 0.5`（半速），作用于月格日号、农历行与星期栏 ——
这是独立的业务规则，**不要统一成全局系数**。

### 7.5 色彩、背景与"不做什么"

**动态取色（Q4 已确认：启用）**：

```
Android 12+ 且用户未关闭  →  dynamicLightColorScheme(ctx) / dynamicDarkColorScheme(ctx)
其它情况                  →  以品牌色 #2563EB 为 seed 生成 ColorScheme（浅/深两套）
```

- 设置页提供「跟随壁纸取色」开关，**默认开**；关闭即回落到品牌色板。
- 品牌色 `#2563EB` 同时是动态取色的**种子色**与**回落色**；`primary` / `onPrimary` / `primaryContainer` 等语义角色
  一律从 `MaterialTheme.colorScheme` 取，**组件里不再出现硬编码十六进制色**。
- 与 iOS 的差异是**有意的**：iOS 侧品牌色刻意固定，Android 侧按 Google 规范跟随壁纸。

**自绘组件（日历 / 图表 / 分享长图）需要的语义色**（从 `MaterialTheme.colorScheme` 映射，保留 iOS 语义但换取值来源）：

| iOS 令牌 | 原值（浅 / 深） | Android 取值来源 |
| --- | --- | --- |
| `bg` | #FAF9FD / #101318 | `colorScheme.surface`（或 `background`） |
| `onSurface` | #191C20 / #E1E2E8 | `colorScheme.onSurface` |
| `onSurfaceVariant` | #44474F / #C5C6D0 | `colorScheme.onSurfaceVariant` |
| `outlineVariant` | #C5C6D0 / #44474F | `colorScheme.outlineVariant` |
| `primaryContainer` | #DBEAFE / #1E3A8A | `colorScheme.primaryContainer` |
| `quoteBg` | #E4EAF9 / #2B3344 | `colorScheme.surfaceContainerHighest`（引用块底色） |
| `shadowColor` | #000 7.8% / 14.9% | `colorScheme.shadow` + M3 elevation（不再手写透明度） |
| 品牌 `primary` | #2563EB | `colorScheme.primary`（动态时由壁纸决定） |
| 计算色 `glowColor` | primary@15% | `primary.copy(alpha = 0.15f)`（仅用于自绘光晕，若非必要则删） |

**背景**：iOS 的三光斑渐变（`blobA/B/C` + 页面底色）属 iOS 特有的视觉语言 → **Android 不再复刻**。
改用 M3 的表面层级：页面用 `colorScheme.surface`，必要时用 `surfaceContainerLowest/Low` 做分区；
`FlowLightOverlay`（iOS 侧本就零实例化）**不实现**。

**明确不做的事**：

1. **不引入 `haze`、不做任何背景模糊**（原 §7.4 三档玻璃方案作废）；
2. 不复刻 iOS 的玻璃面板、玻璃胶囊、悬浮玻璃 Tab 条、`BlobBackground` 光斑；
3. 不照抄 iOS 的 20/26/14dp 圆角令牌到 M3 组件上（自绘的日历/图表除外，那里保留原数值以免布局数学变化）；
4. 不引入 `material-icons-extended`（体积大且已不推荐）；图标用 Material Symbols 按需导出或 `material-icons-core`；
5. 不因为"iOS 有"就保留一个 Android 上无意义的控件形态 —— 功能的**可达性**必须一致，承载控件的形态按 M3 重新选型。

---

## 8. 服务层迁移

### 8.1 定位（**需重新设计实现，规则 100% 照搬**）

iOS 实现要点：`CLLocationManager.requestWhenInUseAuthorization()` → `CLLocationUpdate.liveUpdates()` 取第一个点，
与 `Task.sleep(8s)` **竞速**，谁先返回用谁；未授权直接返回 nil；反向地理编码用
`MKReverseGeocodingRequest`（并按应用内语言设 `preferredLocale`）。

**规则照搬（这是数据正确性，不是 UI 细节）**：

| 场景 | 行为 |
| --- | --- |
| 新建片段 | 打开编辑器时获取**一次**；胶囊显示"位置获取中…"，失败变"重新获取位置" |
| 新建片段保存时还没有位置 | **二次确认**（取消 / 仍然保存）；确认后按无位置保存，此后再不能添加 |
| 编辑已有片段 | **永不重新获取**，只显示已记录位置并允许调整**显示精度** |
| 编辑无位置片段 | 只读"未记录地点"，不提供获取入口 |
| 精度档位 | `none/province/city/district/street/exact`；**精确授权→`exact`，模糊授权→`province`** |
| 精度调整 | 只改 `loc_text` + `loc_precision`，**不动经纬度与地区列**（足迹聚合不受影响） |
| 坐标有、地址空 | 按"没有位置"处理，不落一条永远补不上的记录 |
| 新建时 | 显式清空上一条的位置（否则会串味落库） |
| 回填历史 | 仅开关打开时触发，每次上限 30 条，失败 id 写入 `region_geocode_skip_v2` |

**取值与逆地理编码的实现选择（需你选，见 §14-Q6）**：

| 方案 | 依赖 | 适用 |
| --- | --- | --- |
| `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY, CancellationSignal)` + `android.location.Geocoder` | `play-services-location`（Google 官方库）+ GMS | 目标市场以 Google 生态为主；与 iOS 行为最接近 |
| 原生 `LocationManager.getCurrentLocation(...)`（API 30+）+ `Geocoder`（API 33+ 有异步 `GeocodeListener`） | 无 | 完全原生、零依赖；provider 融合要自己处理 |
| 高德 / 百度 定位+逆地理编码 SDK | 闭源第三方 + key | 目标含国内、无 GMS 环境更稳；但引入非 Google 生态依赖与合规事项 |

地址字段映射（iOS `CLPlacemark` → Android `Address`）基本一一对应：
`country` / `isoCountryCode` / `administrativeArea` / `subAdministrativeArea` / `locality` / `subLocality` / `thoroughfare` / `name`
→ `getCountryName()` / `getCountryCode()` / `getAdminArea()` / `getSubAdminArea()` / `getLocality()` / `getSubLocality()` / `getThoroughfare()` / `getFeatureName()`。

### 8.2 提醒（**需重新设计**）

iOS：`UNCalendarNotificationTrigger(repeats: false)`，identifier 固定 `"daily_reminder"`，靠 `rearm()` 反复重排；
排期时若"今天已写"则顺延到明天；权限只在用户打开开关时请求。

Android 的实现与政策约束：

| 项 | 方案 |
| --- | --- |
| 通知渠道 | `NotificationChannel`（API 26+ 必须） |
| 通知权限 | `POST_NOTIFICATIONS` 运行时权限（API 33+），**只在用户打开提醒开关时请求**（同 iOS 时机） |
| 定时 | **推荐 `WorkManager` OneTimeWorkRequest**：算到下一个提醒时刻的延迟，Worker 内投递通知并重排下一天；接受 Doze 下数分钟偏差 |
| 精确定时 | `AlarmManager.setExactAndAllowWhileIdle` + `SCHEDULE_EXACT_ALARM`（API 31+，**用户可随时撤销**，需运行时 `canScheduleExactAlarms()` 检查）；`USE_EXACT_ALARM` 只允许闹钟/日历类应用，**日记提醒用它会违反 Play 政策** |
| 开机重排 | `RECEIVE_BOOT_COMPLETED` + `BOOT_COMPLETED` 接收器（iOS 不需要） |
| 额外收益 | 通知可带 `PendingIntent` 直接打开当天编辑器（iOS 侧无此能力） |
| 业务逻辑 | `nextTriggerDate(hour, minute, skipIfTodayWritten)` 与 `markTodayWritten()` 纯逻辑，可 1:1 移植并有单测 |

### 8.3 分享长图

- 绘制：`Bitmap.createBitmap(1440, h)` + `Canvas`（或 `DrawScope`）。iOS 版式常量**逐值平移**：
  逻辑宽 720、`blockLeft=108`、`side=48`、`contentWidth=564`、`dateY=64`、`footerH=34`、`scale=2`、不透明背景。
- 文字换行：iOS 手写逐字符测量 → Android 用 `StaticLayout` + `Paint.measureText`（更稳）。
- 内容：日期（62pt bold）→ 起始行 → 渐隐发丝线 → 时间轴竖线 + 每块节点圆点/光晕/时间/地点 → 各 part（title 36 / heading 32 / quote 圆角框 / list / todo 勾选框 / image）→ 底部署名。
- **不含地图、不含经纬度**（只有地点文字）。
- 分享：写 `cacheDir/share/*.png` → `FileProvider` → `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`。
- 调色板：iOS 侧是 Asset 令牌的**手工副本**（第三份漂移风险）→ Android 统一从 `colors.xml` 读取。
- ⚠️ 顺手修掉 iOS 的 bug：`module_desc` 键在 catalog 中不存在，导出长图底部实际渲染成字面量 `· module_desc ·`。Android 用正确文案。

### 8.4 主题 / 本地化 / 日期 / 农历 / 触感 / 日志 / 深链

| 能力 | Android 方案 |
| --- | --- |
| 主题 | `Theme.kt` + `values-night`；`theme_mode` 三值 → `AppCompatDelegate.setDefaultNightMode`；`primary` 固定 #2563EB |
| 本地化 | `strings.xml`（默认 en）+ `values-zh-rCN`，**197 个 key 同名保留**；位置化占位符 `%1$@`/`%2$ld` → `%1$s`/`%2$d` |
| 应用内即时切换语言 | `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-Hans"))`；`<application android:localeConfig="@xml/locales_config">` + `AppLocalesMetadataHolderService`（33 以下持久化）；「跟随系统」= 空 LocaleList。Activity 自动重建替代 iOS 的 `.id()` 整树重建 —— **需确认 ViewModel 状态（草稿、展开节点、搜索结果）在重建后存活**（`SavedStateHandle`） |
| 日期 / 时间 | `java.time`（`LocalDate`/`ZoneId.systemDefault()`，`minSdk 33` 原生可用，无需 desugaring）+ `DateTimeFormatter`；12/24 小时制用 `android.text.format.DateFormat.is24HourFormat(context)`（**小时制跟设备、AM/PM 跟应用语言**，与 iOS 规则一致） |
| 日记日归并 | `dayKeyForUtc(utc, dayStartHour)` 原样移植；`day_start_hour` 变更时重算 `day_key` 并提示冲突（对应 `recomputeDayKeys`） |
| 农历 | `android.icu.util.ChineseCalendar`（API 24+ 系统 API）：`EXTENDED_YEAR` / `MONTH` / `DAY_OF_MONTH` / `IS_LEAP_MONTH`；干支/生肖字符串表照搬 |
| 触感 | `View.performHapticFeedback` 或 `VibratorManager` + `VibrationEffect.createPredefined(EFFECT_TICK)`（API 29+） |
| 日志 | `android.util.Log` 薄封装（对应 `os.Logger` 的 `app/db/backup/location/editor` 分类） |
| 深链 | manifest `intent-filter`：`scheme="justdiary"`、host `editor`、query `dayKey` → `MainActivity.onNewIntent`；`LaunchIntent` 语义照搬 |
| 权限跳转 | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` |
| 生命周期 | `ProcessLifecycleOwner` 替代 `didBecomeActive` / `scenePhase`；`rearm()` 的 5 个调用点全部保留 |

---

## 9. 逐页迁移清单

### 9.1 搜索页（582 + 312 行）

- 结构：页头 + 结果数徽章 → **`DockedSearchBar`/`SearchBar`（M3 搜索组件，非玻璃胶囊）** → 筛选面板（竖屏上下两组；**横屏左右并排**，省约 60dp）→ **关键词胶囊行**（`InputChip`，每个可单独点掉 + "清除全部条件"）→ 生效条件摘要行 → 结果列表。
- 筛选：时间 `全部/本周/本月/今年/自定义`（本周按 `week_start`）；地点 `全部/未记录/自定义`（带本地搜索与按条数排序的选项列表）。
- 行为：输入 **250ms 防抖**；回车把词提交为胶囊；`snippet(...,'<hl>','</hl>',24)` + `desegment`；无 FTS 时 ±18 字窗口 + 正则包 `<hl>`；分页 `limit 200` + "加载更多"。
- 高亮：优先解析 `<hl>`，否则按关键词重算分段；命中用 `primary` 前景 + `primaryContainer` 背景。
- 空状态三态（索引中 / 有条件无结果 / 无条件）；滚动时隐藏顶部控件。
- 结果行：日期胶囊 + 2 行摘要 + `timeOf(updated_utc)` + chevron → 点击打开当天日记。

### 9.2 足迹页（336 行）

- 结构：页头 + 「已标注 N 处 · 未记录 M 篇」→ 年份胶囊横滚（`全部` + 数据中出现年份倒序）→ 统计卡（5 格：省市 / 城市 / 国家 / 片段 / 天数）→ 年度柱状图 → 地点三级清单（国家→省→市，可展开，按条数降序）。
- **柱状图自绘**（`Canvas`）：单色柱 + 4dp 圆角 + 左轴网格与数值标签；高度竖屏 132dp / 宽屏 200dp；坐标轴文字走动态字号。
- 横屏：统计行 → 「趋势图（flex）| 清单（固定宽）」并排；块高 `max(140, min(190, screenH − 262))`，清单宽 `min(320, max(240, contentWidth × 0.4))`；清单内部滚动。
- 聚合：`FootprintDataService` 纯数据聚合（**不依赖坐标、不需要打包地理边界**，任何国家可用）；省/市 key 用 `\u{1}` 拼接去重（照搬，保证与 iOS 备份数据一致）。

### 9.3 设置页（490 行）

- 结构：5 张卡（通用 / 日记规则 / 提醒 / 数据 / 关于），列数只随宽度变：**1 列(<680dp) → 2 列(≥680dp) → 3 列(≥1000dp)**。
- 行样式：38dp 圆角方形图标徽章 + 标题 + 副标题 + 尾值/开关；分隔线从标题文字开始、不缩进到图标下面；行高 `minHeight 56dp`。
- 取值控件：语言 / 主题 / 周起始 / 导出范围 → **菜单 + 勾选**；导入方式（跳过/覆盖）→ **确认对话框**（因为它是破坏性动作）；「新一天开始时间」→ 单轮 Picker（只需小时）；提醒时间 → `TimePicker`。
- 14 行行为与存储 key 见 §4.6 与 §8。
- 「自动插入时间」在 iOS 是**死设置**（无消费者）→ 见 §14-Q5。

---

## 10. 迁移判定总表（你特别关心的部分）

### 10.1 自然迁移（Android 原生 / 官方库直接支持，结构可 1:1 对照）

| # | 能力 | Android 载体 |
| --- | --- | --- |
| 1 | SQLite 关系数据 + 索引 + 迁移 | **`SQLiteOpenHelper` + `SQLiteStatement`（平台原生）**，裸 SQL 与 iOS 逐条对照（见 §4.7.1） |
| 2 | 备份 ZIP 打包解压 | **`java.util.zip`**（原生，替代自写 ZipArchive） |
| 3 | 日记日/日期时间运算 | **`java.time`**（`minSdk 33` 原生，无需 desugaring） |
| 4 | 农历 | **`android.icu.util.ChineseCalendar`**（系统 API，API 24+） |
| 5 | 12/24 小时制 | **`android.text.format.DateFormat.is24HourFormat()`** |
| 6 | 本地化资源与运行时切语言 | **`strings.xml` + `AppCompatDelegate.setApplicationLocales` + `localeConfig`**（比 iOS 的手工 .lproj 查表更简单） |
| 7 | 设置存储 | **`SharedPreferences`** |
| 8 | 选图片 | **Photo Picker**（`PickVisualMedia`，无需存储权限） |
| 9 | 分享图片 / 导出文件 / 导入文件 | **`FileProvider` + `ACTION_SEND` + SAF(`CreateDocument`/`OpenDocument`)**，零权限 |
| 10 | 触感反馈 | **`HapticFeedbackConstants` / `VibrationEffect`**（原生） |
| 11 | 日志 | **`android.util.Log`** |
| 12 | 图片解码降采样 | **`BitmapFactory.Options.inSampleSize`** + androidx `ExifInterface` |
| 13 | 长图绘制（Canvas / 渐变 / 圆角 / 文字换行） | **`android.graphics.Canvas` + `StaticLayout` + `Shader`**（API 平移，版式常量照搬） |
| 14 | 深链 `justdiary://editor?dayKey=` | **manifest `intent-filter` + `onNewIntent`** |
| 15 | 分页滑动（翻月/翻周） | **`VerticalPager` / `HorizontalPager`**（Foundation） |
| 16 | 动画与插值 | **`updateTransition` / `Animatable` / `CubicBezierEasing`** |
| 17 | 自适应宽度分档与安全区 | **`WindowSizeClass` + `WindowInsets`**（阈值 <700 / 700–999 / ≥1000dp 原样保留） |
| 18 | 设置项即时生效（语言/主题/周起始） | `StateFlow` + Activity recreate / `values-night` |
| 19 | 通知与提醒业务逻辑 | **`NotificationManagerCompat` + `WorkManager`**（排期规则照搬） |
| 20 | 状态栏/导航栏 insets 避让 | **`WindowInsets.safeDrawing / ime / navigationBars`**（替代 iOS 实测 safeArea 常量） |

### 10.2 需第三方库（**只有 3 个候选**）

| # | 能力 | 库 | 许可证 | 必要性 | 备选 |
| --- | --- | --- | --- | --- | --- |
| — | **UI 层：无第三方** | — | — | 采用 Material 3 后，原方案唯一必需的 `haze`（玻璃模糊）**已不需要**（Q1 决策）；导航、搜索、卡片、按钮、sheet、芯片、开关全部由 `androidx.compose.material3` 提供 | — |
| 2 | 图片加载与缓存（内联图片） | **`io.coil-kt.coil3:coil-compose`** | Apache-2.0 | 可选。iOS 是自写 `DiaryImageStore`；Android 可自写 `BitmapFactory` + `LruCache` | 自写（与 iOS 结构更对称） |
| 3 | 柱状图 | 建议**不引入** | — | 全 app 仅 1 张柱状图（单色 + 4dp 圆角 + 左轴），自绘约 100–150 行，可控性最好 | Vico（Apache-2.0，Compose 原生，最贴近 Swift Charts 写法）/ MPAndroidChart（View 体系，需 `AndroidView`，维护放缓） |

**明确不需要**的常见依赖：Hilt（手工 `AppContainer` 已足够、更对称）、Retrofit/OkHttp（更新使用标准库 `HttpURLConnection`；应用主体仍是本地数据）、
Gson/Moshi（用 kotlinx.serialization）、Glide、Accompanist 权限（用 `ActivityResultContracts`）、
SQLCipher（无加密需求）、Room KMP、Compose Multiplatform（不共享代码，避免额外复杂度）。
Google 官方库（`play-services-location`、`androidx.*`）不计入"第三方"。

### 10.3 需重新设计（无常用等价物或语义不同）

| # | 能力 | 为什么 | 方案与风险 |
| --- | --- | --- | --- |
| 1 | **富文本编辑器** | iOS 是 UITextView + NSAttributedString 自定义键；Compose 的 `TextFieldState` **不支持 `AnnotatedString`/局部样式** | `AndroidView` + `EditText` + `Spannable` + 自定义 span（与 iOS 结构对称）；`content_json` 复用。**最大工作量** |
| 2 | **年↔月 morph** | SwiftUI 的隐式动画 + Canvas 组合，Compose 无 1:1 对应 | `Canvas` + `updateTransition` 手写插值；共用 `CalendarLayout` 常量防跳变。**第二大工作量** |
| 3 | **滚动/翻页收尾不跳变** | `DragPagePager` 的"同事务提交 current+offset"技巧 | `Pager` 的 `settle` 行为需实测调参 |
| 4 | **视觉需要按 M3 重新设计**（不是"换材质照抄"） | 原方案要复刻 iOS 的玻璃/光斑/控件形态，而 Q1 决定采用 Google 设计语言 | 按 §7.2 的映射表逐控件重新选型（`Card`/`TopAppBar`/`NavigationBar`/`NavigationRail`/`SearchBar`/`ListItem`/`ModalBottomSheet`/`Snackbar`…）；**功能的可达性与交互路径必须一致**，外观允许不同 |
| 5 | **动态字体逐档上限** | Compose 只有全局 `fontScale` | `LocalDensity` 覆写 + 逐档上限表；日历半速缩放单独处理 |
| 6 | **提醒的精确投递** | iOS 系统保证日历触发器精确；Android 精确闹钟有权限与政策限制 | WorkManager（±数分钟）+ 文案容差；或 `SCHEDULE_EXACT_ALARM`（可被撤销）。**需你确认可接受的精度** |
| 7 | **反向地理编码** | Apple 用自家服务；Android `Geocoder` 后端依 GMS/厂商 | 见 §8.1 三选一；国内需第三方地图 SDK |
| 8 | **分享图文字换行** | iOS 手写逐字符测量 + ink ratio | 改用 `StaticLayout`（更稳），ink ratio 表保留给 quote 框高度 |
| 9 | **横屏系统浮条避让** | iOS 浮条由系统提供（横屏变左侧竖排胶囊） | Android 自绘底部导航 + `WindowInsets`；横屏不模仿 iOS 的侧边浮条 |
| 10 | **设置页语言即时切换后的状态存活** | iOS 靠整树重建 + 显式清缓存 | Activity recreate 会重建 ViewModel 之外的状态，需 `SavedStateHandle` 兜住草稿/展开节点/搜索条件 |

---

## 11. 第三方库清单（含许可证与替代）

| 库 | 版本策略 | 许可证 | 用途 | 若不用 |
| --- | --- | --- | --- | --- |
| ~~`dev.chrisbanes.haze:haze`~~ | — | — | **不采用**（Q1 决策：采用 Material 3，不做玻璃模糊） | — |
| `io.coil-kt.coil3:coil-compose` | 最新稳定 | Apache-2.0 | 内联图片加载/缓存 | 自写 `BitmapFactory` + `LruCache` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 随 Kotlin | Apache-2.0 | `content_json` / manifest / settingsJson | 手写 JSON（不建议） |
| ~~`androidx.room:*`~~ | — | — | **不用**（见 §4.7.1）：FTS5、搜索、合并、重算、足迹 JOIN 全是裸 SQL，Room 省不下什么，还引入 KSP 与 identity hash | 平台 `SQLiteOpenHelper` |
| `androidx.work:work-runtime-ktx` | 2.x | Apache-2.0 | 提醒排期 | `AlarmManager`（权限/政策问题更多） |
| `androidx.appcompat:appcompat` | 1.7+ | Apache-2.0 | 应用内语言切换（`setApplicationLocales`） | 系统 `LocaleManager`（仅 API 33+） |
| `androidx.compose.*` / `material3` | BOM + `material3` **1.4.0 稳定**（Expressive 组件在 1.5.0-alpha，需 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`） | Apache-2.0 | UI | — |
| `com.google.android.gms:play-services-location` | 21.x | Google 官方（客户端库 Apache-2.0，运行时依赖闭源 GMS） | 定位 | 原生 `LocationManager` |
| `org.robolectric:robolectric` | 最新 | Apache-2.0 | JVM 侧毫秒级单测（对应 iOS `JustDiaryTests`） | 纯 JVM 测试（把逻辑与 Android 依赖解耦） |

> `MaterialExpressiveTheme` / `HorizontalFloatingToolbar` / `ButtonGroup` / `MotionScheme` 目前均需 opt-in 且 API 随 alpha 变动。
> 策略：**基线 M3 1.4.0 保证可发布**，Expressive 组件仅在格式栏/按钮组等局部接入，并在 `libs.versions.toml` 里 pin 死版本。

---

## 12. 分阶段实施计划

| 阶段 | 内容 | 产出与验收 | 规模 |
| --- | --- | --- | --- |
| **M0 工程骨架** | Gradle/AGP/Kotlin/Compose 配置、包名 `com.cov.justdiary`、版本目录、`MainActivity` + 空导航、主题骨架、单元测试与 UI 测试跑通 | 空 App 可编译、可安装、可跑测试；版本组合固化 | 小 |
| **M1 数据层** | ① 以 iOS 真实 `content_json` 作 golden file 做**字节级兼容验证**（不依赖任何 UI 决策的正确性基线）② Room 实体/Dao/迁移、FTS 建表与探测降级、`FtsSegment`、`PartsCodec`（v1/v2）、`DiaryRepository` 全 API、备份 zip 导入导出、媒体存储、设置存储 | 端到端：写入 → 查询 → 导出 → 导入 → 数据一致；golden file 与编解码单测全绿 | 大 |
| **M2 设计系统** | M3 主题（动态取色 + 品牌色回落）、Typography + 动态字体上限、M3 组件封装（`Card`/`TopAppBar`/`NavigationBar`/`NavigationRail`/`ListItem`/`ModalBottomSheet`/`Snackbar`/`FilterChip`）、日期格式化、自绘日历所需语义色 | 组件预览页；与 iOS **逐功能**对照（观感按 M3，不比对 iOS 截图） | 中大 |
| **M3 首页日历** | 布局常量、月/年/周 Canvas、手势、翻页、morph 动画、回到今日 | 竖屏三形态 + 横屏分栏可用；morph 收尾不跳变（自动化断言守住常量） | 大 |
| **M4 日记阅读** | 段落/行内渲染、内联图片、位置行、条目内搜索、分享长图 | 阅读观感与 iOS 一致；导出长图逐值对齐 | 中 |
| **M5 日记编辑** ★ | `EditText`+`Spannable` 引擎、格式栏、样式菜单、列表/待办、插图片、位置胶囊与规则、放弃修改、保存路径 | 往返不变量测试全绿；位置规则 5 条场景全对 | **大（最大）** |
| **M6 足迹 + 搜索** | 统计卡、自绘柱状图、三级清单、筛选；FTS 搜索 + 高亮 + 筛选 + 分页 | 搜索结果与 iOS 同源数据下一致 | 中大 |
| **M7 设置 + 服务** | 5 张卡 14 行、语言即时切换、主题、周起始、开始时间重算、自动时间/地点、位置精度、历史编辑开关、提醒、备份导入导出、关于 | 14 行全部生效；语言切换后状态存活 | 中大 |
| **M8 横屏与自适应** | 宽度三档、首页左右分栏、足迹并排、搜索条件压行、设置列数、insets | 横屏逐屏比对设计稿（`docs/design/landscape/screens/ph-*-landscape.png`） | 中 |
| **M9 打磨** | 无障碍（TalkBack、可调节动作、缺失文案补齐）、动态字体全量走查、减弱动效、增强对比、morph 帧率实测、回归测试补齐 | 与 iOS 的 5 类显示设置行为对齐 | 中 |

依赖关系：M1/M2 可并行 → M3/M4/M5 可并行 → M6/M7 可并行 → M8 → M9。

**验证前提（已按 Q8 决策调整）**：本机**没有 AVD / 系统镜像 / 连接设备**，且本轮**不做安装运行验证**。
因此阶段验收口径为：
① `./gradlew assembleDebug` 编译通过；② JVM 单元测试（含 golden file 字节级兼容、`FtsSegment`、
`content_json` 往返、`dayKeyForUtc`、足迹聚合、备份 zip 往返）全绿；③ 纯逻辑代码不依赖 Android 框架，便于在 JVM 上验证。
**instrumented 测试、真实安装、观感比对、morph 帧率实测**留待后续补一次运行验证——
届时需要装系统镜像（建议 API 36/37 的 `default` 与 `google_apis_playstore` 各一，用于覆盖无 GMS 与有 GMS 两条定位路径）或插真机。

### 12.1 相对工作量权重（以 M5 编辑器为基准 1.0）

| 阶段 | 相对权重 | 说明 |
| --- | --- | --- |
| M5 编辑器 | **1.00** | 唯一需要重写"文本编辑内核 + span 桥接"的模块；详细分析给出的绝对口径是**约 68 人天（含 20% 缓冲 ≈ 82 人天）**，其中编辑器内核 26.5、数据模型 8.0、`PartsCodec` 8.5、阅读态 6.0、媒体 8.75、UI 集成 10.5 |
| M3 首页日历 | ~0.55 | Canvas 手绘 + 手写 morph 插值 + 手势/分页 + 常量体系 |
| M1 数据层 | ~0.50 | schema/迁移/FTS/备份/媒体/设置 + golden file |
| M6 足迹 + 搜索 | ~0.40 | 聚合 SQL + 自绘图表 + FTS 查询/高亮/筛选 |
| M7 设置 + 服务 | ~0.35 | 14 行设置 + 语言即时切换 + 提醒排期 |
| M2 设计系统 | ~0.35 | M3 主题 + 动态取色 + 动态字体 + 组件封装 |
| M4 日记阅读 | ~0.30 | 段落/行内渲染 + 分享长图（第三套渲染管线） |
| M8 横屏自适应 | ~0.20 | 分档/分栏/insets |
| M9 打磨 | ~0.25 | 无障碍 + 动态字体走查 + 帧率实测 |
| M0 骨架 | ~0.05 | 工程配置 |

> 这只是**规模判断**，不是排期承诺：Android 侧"自然迁移"占比约 57%（集中在语义模型、格式常量、交互契约这三个最核心的面上），
> 真正的新增不确定性主要来自编辑器内核、IME 组合区、视觉重设计与 morph 性能四处。

---

## 13. 测试与验收

| 层级 | 对应 iOS | Android 内容 |
| --- | --- | --- |
| JVM 单元测试 | `JustDiaryTests/ContentFormatTests` | `content_json` v1/v2 编解码、只写开启的行内样式、全 12 档字号往返不变量、待办状态与列表分组、未知样式按 body、图片路径归一化；`FtsSegment` 分词/反分词；`dayKeyForUtc` 跨日；`Lunar` 干支生肖；`FootprintDataService` 聚合；备份 zip 往返；`nextTriggerDate` 排期 |
| JVM 单元测试 | `JustDiaryTests/AdaptiveLayoutTests` | 宽度三档判定、日历密度三档、横屏分栏比例、`Radius.concentric` 公式 |
| Compose UI 测试 | `JustDiaryUITests/EditorTypeSizeUITests` | 块样式三轮重存不变、放弃修改恢复、输入区随字号放大、无位置保存后的只读态 |
| Compose UI 测试 | `SmokeUITests` / `ImportUITests` / `FootprintUITests` / `LanguageThemeUITests` / `LandscapeLayoutUITests` | 五个页面冒烟、备份导入两模式、足迹筛选、语言主题即时生效、横屏版面 |
| Compose UI 测试 | `MorphPerfUITests` | morph **收尾位置等于真实图层起点**（用共用常量断言，防止跳变）；帧率用 `Choreographer` 打点 |
| 手工验收 | — | 与 iOS 13 张设计稿逐屏比对（竖屏 3 张 + 横屏 5 张 + 阅读/搜索/设置） |

---

## 14. 决策记录（**全部已确认，无待定项**）

| # | 决策 | 结论 |
| --- | --- | --- |
| ~~**Q1**~~ | 设计语言 / 玻璃方案 | ✅ **采用 Google Material Design（Material 3），不模仿 Liquid Glass，不照抄 iOS 界面**。原项目只作为功能、交互、动画的参考；视觉按 Google 规范重新设计。**不引入 `haze`**（详见 §7） |
| ~~**Q2**~~ | `minSdk` | ✅ **33（Android 13）** |
| ~~**Q3**~~ | 备份是否需要与 iOS **双向**互导 | ✅ **需要，严格保真** |
| ~~**Q4**~~ | Material You 动态取色 | ✅ **启用**（Android 12+ 默认跟随壁纸，设置页可关，回落品牌色 `#2563EB`）；与 iOS 品牌色固定不同，属有意分叉 |
| ~~**Q5**~~ | iOS 的「自动插入时间」是死设置 | ✅ 判定为 iOS 侧 bug → **做成真实有效的开关** |
| ~~**Q6**~~ | 定位与反向地理编码 | ✅ **运行时探测 GMS**：有 GMS 走 `FusedLocationProviderClient`，无 GMS 退原生 `LocationManager` + `Geocoder`；`LocationService`/`GeocodingService` 抽成接口 |
| ~~**Q7**~~ | 提醒精度 | ✅ **`WorkManager`**（不用精确闹钟） |
| ~~**Q8**~~ | 运行验证方式 | ✅ **本轮不做运行验证**；验收口径 = 编译通过 + JVM 单测通过 |
| ~~**Q9**~~ | 覆盖形态 | ✅ **只做手机竖屏 + 手机横屏**（不做 iPad/Mac/桌面宽屏三栏） |
| ~~**Q10**~~ | 横屏导航形态 | ✅ **用 Android 原生自适应导航**：紧凑 `NavigationBar`、中等/扩展 `NavigationRail`；**不照抄 iOS 底部悬浮条**，横屏分栏与日历可用高度按 Rail 重算 |
| ~~**Q11**~~ | 编辑器路线 | ✅ **直接开工**：`AndroidView` + `EditText` + `Spannable`（纯 Compose 路线已排除） |
| ~~**Q12**~~ | 数据层框架 | ✅ **`SQLiteOpenHelper` + 裸 SQL**，以"两边完全对齐"为准（见 §4.7.1） |
| ~~**Q13**~~ | 迁移/建库失败时的行为 | ✅ **显式报错**（不保留 iOS 的静默降级） |

---

## 15. 风险登记

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 富文本编辑器（M5） | 最大工作量，可能返工 | 先做 `PartsCodec` + `SpannableBridge` 的纯 Kotlin 层并单测，再接 UI；往返不变量测试先行 |
| morph 动画性能 | 掉帧（iOS 侧实测曾 55 Canvas/帧） | 同事务提交、morph 期间不构建底层 layer、分页器不预建邻页；用 `Choreographer` 实测帧间隔 |
| FTS5 在 Android 上是否存在 | 搜索能力差异 | 运行时探测 + LIKE 兜底（沿用 iOS 的 `ftsSupported` 设计）；M1 实测各代 API |
| 精确闹钟政策 | 上架风险 | 用 WorkManager，不用 `USE_EXACT_ALARM` |
| 无 GMS 环境 | 定位/地理编码不可用 | 按 Q6 决策选型；抽象 `LocationService` 接口，实现可替换 |
| Compose Expressive 是 alpha | API 漂移、构建不稳定 | pin 版本；只局部使用；核心界面走稳定 1.4.0 |
| Activity 重建导致草稿丢失 | 数据丢失 | `SavedStateHandle` + 编辑器草稿持久化；旋转与语言切换专项测试 |
| iOS 侧的已知缺陷被一起搬过来 | 观感问题 | 已识别 `module_desc` 缺键、`auto_time` 死设置、`NSUserNotificationsUsageDescription` 非法键，Android 侧不复制 |
| 设计令牌三处漂移（`Assets.xcassets` / `ImageShareService` 硬编码 / 设计稿 CSS） | 观感不一致 | Android 侧统一从 `colors.xml` + `DesignSystem.kt` 取，禁止第二份硬编码 |

---

## 16. 支撑本方案的详细分析报告

本文是决策层方案；以下两份**逐行分析报告**（含大量 `文件:行号` 证据与逐项判定表）已随仓库归档，实施时按需查阅：

| 报告 | 内容 |
| --- | --- |
| `docs/migration-analysis/01-data-layer.md` | 数据层：表/索引 DDL 原文与逐列语义、连接与 PRAGMA、迁移链、`FtsSegment` 精确规则与样例、`DiaryRepository` 全部公开 API 签名、备份 zip 字节级布局与导入合并逻辑、搜索 SQL 原文与 `snippet()` 差异、足迹聚合算法、13 个设置 key、25 条 iOS API 映射、46 项判定表、跨端数据契约清单 |
| `docs/migration-analysis/02-editor-and-richtext.md` | 编辑器与富文本：`content_json` 字段级规范、13 条往返不变量（I1–I13）、`RichEditorController` 逐成员分析、UIKit 依赖清单、字号梯级与上限表、格式栏 9 按钮 + 顶栏 8 按钮、插图 8 步链路、三条路线 10 维度评分（含各库维护状态实测）、42 项功能点判定表、分模块人天、12 条暗坑 checklist |
| `docs/migration-analysis/03-home-calendar-and-motion.md` | 首页日历与动画（**iOS 侧参考**）：14 处玻璃控件位置表（仅用于理解 iOS 的控件分层，**Android 不复刻**）、27 行 Compose 映射总表、逐组件功能规格、竖/横屏数值推演、morph 插值公式全集、`DragPagePager` 阈值、Canvas 缓存策略、动态字体与无障碍细节 |
| `docs/migration-analysis/04-services-and-platform.md` | 服务层与平台：设置项全量清单与副作用的调用点、定位完整流程（8 秒超时、region1/2/3 的确切取值表达式、精度档位）、提醒（identifier、`renextTriggerDate` 规则、9 个调度调用点）、分享长图渲染常量与发现的 9 个 bug、主题 13 组明暗配对色值与引用次数、本地化机制与 key 统计、日期/农历/启动生命周期、35 条 iOS API 映射与判定表 |
| `docs/migration-progress.md` | **实施记录**：环境与工具链实测值、M0 交付物与验收证据、踩到的坑、与方案的偏差、下一步计划 |

> 这些报告由只读分析产出，**未改动 `/Users/cov/JustADiary` 任何文件**（其中一份曾被写入 iOS 仓库根目录，已移入本仓库归档）。

## 附录 A：已核对的 Google 官方/权威参考

- [Material 3 / Material Design 官方指南](https://developer.android.com/develop/ui/compose/designsystems/material3)（采用的组件与规范基线；稳定版 `material3` 1.4.0）
- [Material You 动态取色](https://developer.android.com/develop/ui/views/theming/dynamic-colors)（Q4 启用）
- [自适应导航：NavigationBar / NavigationRail](https://developer.android.com/develop/ui/compose/layouts/adaptive)（Q10 采用）
- [Compose `BasicTextField`/`TextFieldState` 不支持 `AnnotatedString` 与局部样式（issue 389978748）](https://issuetracker.google.com/issues/389978748)
- [Android 14 起精确定时闹钟默认被拒](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)（Q7 选 WorkManager 的依据）
- [Android 16 行为变更（edge-to-edge、预测性返回）](https://developer.android.com/about/versions/16/behavior-changes-16)
- ~~[Haze · Platforms](https://chrisbanes.github.io/haze/latest/blur/platforms/)~~ —— 记录备查：原方案曾考虑用 Haze 做玻璃模糊（API 33+ 最优、31/32 需 workaround、≤30 退化 scrim）；**Q1 决定采用 Material 3 后已不采用**

## 附录 B：源端关键常量速查（供实现时对照）

| 项 | 值 | 出处 |
| --- | --- | --- |
| 编辑器字号梯级 | title 28 / heading 22 / body 17 / quote 15 | `docs/editor-typography.md` §1 |
| 段落样式持久化名 | `title` / `heading` / `body` / `list` / `todo` / `quote` / `image` | `ContentPart.swift:123-131` |
| v1 兼容名 | `h1`→title、`h2`→heading、`p`→body、`ul`→list、`img`→image | `ContentPart.swift:134-140` |
| 图片 | `images/img_<ms>.jpg`，maxPixel 2048，JPEG 0.85 | `DiaryViewModel.swift:488-503` |
| 定位超时 | **8 秒** | `LocationService.swift:57` |
| 定位精度 | 精确→`exact`，模糊→`province` | `LocationService.swift:104-105` |
| 历史回填上限 | 每次 **30 条** | `DiaryRepository.swift:848` |
| 搜索 | FTS5 `unicode61` + CJK 逐字插空格；防抖 250ms；分页 200；snippet 24 词 | `DiaryRepository.swift:214,886-911`；`SearchViewModel.swift:153-160` |
| 提醒 | id `daily_reminder`，一次触发 + rearm，默认 21:00 | `ReminderService.swift` |
| 备份 | `JustDiary_yyyyMMdd_HHmmss.jdiary`，`formatVersion: 1` | `BackupService.swift:79-108` |
| 分享长图 | 逻辑宽 720、scale 2、`blockLeft 108`、`side 48`、`contentWidth 564` | `ImageShareService.swift:10-17,218-221` |
| 周起始 | 默认 `monday`；`weekdayIndex`：周日制 `wd-1`、周一制 `(wd+5)%7` | `DateUtil.swift:74-85` |
| 新一天开始 | 默认 **4 点** | `AppSettings.swift` / `DateUtil.swift:51-59` |
| 宽度分档 | <700 紧凑 / 700–999 中等 / ≥1000 宽 | `AdaptiveLayout.swift:30-34` |
| 布局阈值 | `splitsMasterDetail` = contentWidth≥700 且 width>height；`splitsDashboard` = ≥900 | `AdaptiveLayout.swift:61-70` |
| 横屏实测可用宽 | **750pt**（874 − 62 − 62） | `docs/adaptive-layout-plan.md` §3.1.1 |
| 减弱动效 morph | **0.18s 线性** | `docs/animation-and-accessibility.md` §2.3 |
| morph 曲线 | `timingCurve(0.2, 0.75, 0.3, 1.0)`，0.6s | 同上 §1.2 |
| 增强对比度描边 | 0.5pt/45% → 1.5pt/90% + 外描边 | 同上 §2.4 |
