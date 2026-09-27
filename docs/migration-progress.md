# 迁移实施记录（JustGDiary）

本文件是**实施记录**（做了什么、实测到什么、踩了什么坑），与 `docs/android-migration-plan.md`（**方案**）分开维护。
里程碑定义见方案 §12。

---

## 环境与工具链（M0 实测固化）

| 项 | 实测值 | 备注 |
| --- | --- | --- |
| Android Studio | 2026.1 | `/Applications/Android Studio.app` |
| JDK | **Android Studio 内置 JBR 25.0.3** | 本机**没有**系统 `java`，构建必须设 `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` |
| Gradle | **9.7.1**（wrapper） | 用 `gradle wrapper` 生成 |
| AGP | **9.4.0** | ⚠️ **AGP 9.0 起内置 Kotlin 支持**，不能再应用 `org.jetbrains.kotlin.android`（否则直接构建失败） |
| Kotlin | **2.4.20** | 由 AGP 内置提供；`org.jetbrains.kotlin.plugin.compose` 与 `...plugin.serialization` 仍照常应用 |
| Compose BOM | **2026.09.00** | `androidx.compose.ui` 1.12.1 |
| Compose Material 3 | **1.4.0**（稳定版） | Expressive 组件仍在 1.5.0-alpha，本方案不依赖 |
| SDK Platform | **android-37.0（API 37 = Android 17）** | 本机唯一已装平台 |
| build-tools | 36.0.0 | |
| `compileSdk` / `targetSdk` / `minSdk` | **37 / 36 / 33** | 与方案 §3 一致 |
| 模拟器 / 真机 | **无** | 无 AVD、无系统镜像、无连接设备 → 本轮不做安装运行验证（方案 Q8） |

### 网络（重要）

本机网络对构建生态有三个必须处理的点，已写进仓库配置：

| 目标 | 实测 | 处理 |
| --- | --- | --- |
| `repo.maven.apache.org`（Gradle 的 `mavenCentral()`） | **TLS 被重置**（`SSL peer shut down incorrectly`），构建直接失败 | `settings.gradle.kts` 里把 **阿里云镜像**（`maven.aliyun.com/repository/public`，实测 6–8 MB/s）放在首位，`google()` / `mavenCentral()` 作为末位兜底 |
| `services.gradle.org`（Gradle 发行包） | ~30 KB/s（144 MB 需 80 分钟） | `gradle-wrapper.properties` 的 `distributionUrl` 指向**华为云镜像**（实测 ~6 MB/s），并附 `distributionSha256Sum` 校验 |
| `dl.google.com`（Google Maven） | ~6 MB/s，正常 | 无需镜像；`google()` 保持官方源 |

> 若要清理成"完全官方源"的仓库，只需还原 `settings.gradle.kts` 的仓库列表与 `gradle-wrapper.properties` 的 `distributionUrl`（注释里已给出官方 URL）。

---

## M0 —— 工程骨架 ✅ 已完成

### 交付物

| 文件/目录 | 说明 |
| --- | --- |
| `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties` | 工程与仓库配置（含镜像说明） |
| `gradle/libs.versions.toml` | **唯一版本真源** |
| `gradlew` / `gradlew.bat` / `gradle/wrapper/*` | Gradle 9.7.1 wrapper |
| `app/build.gradle.kts` | `namespace`/`applicationId` = `com.cov.justdiary`，minSdk 33 / targetSdk 36 / compileSdk 37，Compose + 序列化，debug **不加** `applicationIdSuffix` |
| `app/src/main/AndroidManifest.xml` | 权限（定位/通知/开机）、`localeConfig`、深链 `justdiary://editor` |
| `res/values` + `res/values-zh-rCN` | 双语资源骨架、DayNight 主题、窗口底色、`locales_config.xml` |
| `res/drawable` + `res/mipmap-anydpi-v26` | 自适应启动图标（M0 占位，M9 换正式 Material Symbols） |
| `JustDiaryApplication.kt` / `app/AppContainer.kt` | 进程入口 + 手工依赖容器（不引入 Hilt） |
| `service/Log.kt` | 对应 iOS `Services/Log.swift`：分类 `app/db/backup/location/editor`，tag 形如 `JustDiary.db` |
| `ui/theme/{Color,Theme,DesignTokens}.kt` | M3 主题 + **动态取色默认开启** + 品牌色回落；`Radius.concentric` 公式；字阶与缩放上限 |
| `ui/root/{AppTab,RootScreen}.kt` | 自适应导航：紧凑 `NavigationBar` / ≥600dp `NavigationRail` |
| `app/MainActivity.kt` | 唯一 Activity（`AppCompatActivity` + edge-to-edge + Compose） |
| `app/src/test/**/DesignTokensTest.kt` | 5 个纯 JVM 单测 |

### 验收证据

```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 8s
36 actionable tasks: 34 executed, 2 up-to-date

> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 38s
com.cov.justdiary.ui.theme.DesignTokensTest: tests=5 failures=0 errors=0 skipped=0 time=0.015s

# aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
package: name='com.cov.justdiary' versionCode='1' versionName='1.0' ... compileSdkVersion='37'
minSdkVersion:'33'
targetSdkVersion:'36'
```

单测覆盖的不变量：嵌套圆角公式（含下限 0）、编辑器字阶严格递减、日历半速缩放、
**缩放上限不得让字阶倒挂**（`size × ceiling` 严格递减）。

### M0 踩到的坑（已解决）

1. **AGP 9 内置 Kotlin**：应用 `org.jetbrains.kotlin.android` 会直接报错
   `The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0`。
   → 从根与模块的 `plugins {}` 中移除；`kotlin { compilerOptions { jvmTarget } }` 仍然有效。
2. **Maven Central 不可直连**：见上方网络表；必须配镜像，否则依赖解析阶段就 TLS 失败。
3. **无系统 JDK**：`java` 不在 PATH 上，必须显式指定 Studio 的 JBR。
4. **`material-icons-core` 的版本来自 BOM**（被冻结在 1.7.8，是遗留版本，BOM 里仍有映射）；
   正式图标在 M9 换成 Material Symbols 资源。

### 与方案的偏差

- 方案 §7.2 曾把"圆角令牌改用 M3 shape scale"写成全面替换；实现时把 `Radius` 保留为
  **仅供自绘组件使用**的度量（日历/图表/分享长图沿用原布局数学），M3 组件一律用 `MaterialTheme.shapes`。
- 自适应导航的断点 M0 先用单阈值 600dp，M8 阶段细化为 M3 窗口尺寸类（compact/medium/expanded）。

---

## M1 —— 数据层 ✅ 已完成

计划见方案 §4 与 §4.7，`docs/migration-analysis/01-data-layer.md` 是逐行参考。

### 已完成

| 交付物 | 说明 |
| --- | --- |
| `model/ContentPart.kt` | `content_json` v2 编解码 + v1 兼容 + `ImagePathUtil`；**与 iOS 逐字节一致** |
| `model/DateUtil.kt` | `dayKeyOf` / `dayKeyForUtc`（"日记日"规则）/ `weekFirst` / `weekdayIndex` / `relativeDays` … |
| `model/DiaryModels.kt` | `DiaryRecord` / `EditBlock` / `LocRegion` / `LocFilter` / `LocOption` / `FootprintRow` / `SearchResultItem` / `SearchPageResult` / 精度与质量档位 |
| `model/AppSettings.kt` | 13 个设置 key、默认值、**纯函数取值校验**（可 JVM 单测）、语言 → locale 映射 |
| `service/FtsSegment.kt` | CJK 切分 / 反切分，按**字素簇**遍历（对齐 Swift 的 `Character` 语义） |
| `service/SearchUtil.kt` | LIKE 转义、关键词高亮分段、无 FTS 时的 ±18 字兜底摘要 |
| `service/SettingsStore.kt` | `SharedPreferences` 实现（key 与 iOS 同名）；三个内部状态独立读写 |
| `data/db/JustDiarySchema.kt` | 全部 DDL 与 SQL 常量（表/索引/FTS5/迁移/查询/重算/回填），44+ 条逐字对齐 iOS |
| `data/db/JustDiaryOpenHelper.kt` | 打开与迁移链 v0→v1→v3、FTS5 建表与降级探测、WAL、`foreign_keys=OFF` |
| `data/SearchSql.kt` | 搜索语句构造（纯函数，可单测 SQL 文本与**参数顺序**） |
| `data/DiaryRepository.kt` | 读 / 写 / 搜索 / 索引重建 / 日期重算 / 地区回填 / 图片清理 / **备份合并**；单并发调度器 + `BEGIN IMMEDIATE` |
| `data/BackupModels.kt` | `BackupManifest` / `BackupImageRef` / `BackupStats` / `ImportMode` / `BackupException` |
| `model/AppSettingsJson.kt` | `settingsJson` 的传输 DTO —— **只含 10 个业务 key**（数据版本刻意排除） |
| `service/ZipArchive.kt` | `.jdiary` 的**流式**读写（`java.util.zip`），导入时强校验 CRC32 与长度 |
| `service/BackupService.kt` | 导出（`VACUUM INTO` 快照 + 图片去重改名 + 清单）与导入（skip/overwrite 整天合并 + 图片 `imp_<stamp>_<n>.jpg`） |
| `service/MediaStore.kt` | 图片导入（最长边 2048 / JPEG 85 / `img_<ms>.jpg`）与降采样解码（`ImageDecoder`，自动处理 EXIF 方向）+ LRU 缓存 |
| `app/AppContainer.kt` | 数据层/服务层的进程级装配（全部 lazy） |
| `app/src/test/resources/golden/*.json` | **真实 iOS 编码器产出的 golden file** |
| `tools/verify_sqlite_schema.py` | 用宿主 SQLite 真执行 schema 与查询的验证脚本（18 项断言） |
| `tools/verify_backup_interop.sh` + `tools/cross_end_extract.swift` | 用 **iOS 真实的 `ZipArchive.swift`** 解 Android 产出的备份包（15 项断言） |

### 验证证据（三层，全部实测）

```
1) ./gradlew clean assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
   70 个 JVM 单测通过
     ContentFormatTest   15  （含与 iOS golden file 的字节级比对）
     DateUtilTest        14
     SearchSqlTest       13  （SQL 文本与绑定参数顺序）
     BackupModelsTest     9  （manifest 字段名 / settingsJson 键集合 / 数据版本不被重置）
     FtsSegmentTest       7
     ZipArchiveTest       7  （含 CRC 损坏检测、store/deflate、1.5MB 流式往返）
     DesignTokensTest     5

2) python3 tools/verify_sqlite_schema.py            →  18 项断言全部 PASS
   直接从 Kotlin 源码提取 DDL 灌进宿主 SQLite 真执行：
   表名/列名/索引名与 iOS 一致 · FTS5+CJK 可子串命中 · snippet 产出 <hl> ·
   不分词则查不到（证明切分必要）· 未记录判定 · 足迹 JOIN · day_key 两阶段重算

3) tools/verify_backup_interop.sh                   →  15 项断言全部 PASS
   编译 **iOS 真实的 ZipArchive.swift** 去解 Android 写出的 .jdiary：
   entry 列表与长度正确 · manifest.json 能被 iOS 的 JSONDecoder 解码 ·
   settingsJson 的 snake_case 键能被 iOS 的 AppSettings CodingKeys 解码 ·
   db 是合法 SQLite · 图片字节未损坏
```

### golden file 是怎么来的（可复现）

不是手写期望值，而是**编译 iOS 真实的 `ContentPart.swift` 并执行**得到的：

```bash
swiftc -O /Users/cov/JustADiary/JustDiary/Models/ContentPart.swift main.swift -o golden && ./golden
# main.swift 复刻 JustDiaryTests/ContentFormatTests.swift 的 storedFixture/editorFixture/v1 字面量
```

它当场纠正了两个**方案文档里写错的细节**：

1. **信封键序**：实际是 `{"parts":[…],"v":2}`（`sortedKeys` 的字母序），
   而 `docs/editor-typography.md` 的示例写的是 `{"v":2,…}` —— 按文档实现就会与 iOS 不逐字节一致；
2. **数字格式**：`w`/`h` 输出 `300` 而不是 `300.0` → Kotlin 侧为此写了
   `SwiftDoubleSerializer`（整数时不输出小数位）。

### 与 iOS 的有意差异（3 处，都是"不复制缺陷"）

| # | iOS 行为 | Android 行为 | 理由 |
| --- | --- | --- | --- |
| D1 | 搜索无 FTS 时仍把 `ftsQuery` 加进绑定参数，但它对应的占位符并不存在（`matchSql` 是 `1=0`）| 只在 `ftsEnabled` 时才加该参数 | iOS 的绑定失败被静默忽略；**Android 会直接抛 `SQLiteException`**。这是必须修的，否则无 FTS 的设备一搜索就崩 |
| D2 | LIKE 兜底用**没去掉引号**的原词（引号只在 FTS 分支被剥掉）| LIKE 也用去掉引号后的词 | 否则带引号的输入在 LIKE 分支永远匹配不到 |
| D3 | 删片段时按被删片段自己的 json 删图片文件，不检查是否仍被别的片段引用 | 先查库确认无引用才删 | 备份导入会按 `src` 复用映射，使两个片段共用同一张图；照抄会删掉另一个片段仍在用的图 |

> D1/D2 只影响搜索实现，D3 只影响本地文件清理 —— **库结构、`content_json`、FTS 文本、备份格式都不变**。

### 仍未验证的一层（**运行验证时的第一优先项**）

schema 与查询 SQL 已用宿主 SQLite 真执行验证，但**这三层还没有自动化测试**：

1. `SQLiteOpenHelper` 的 `onCreate/onUpgrade` 实际执行（含 `VACUUM INTO`、`beginTransactionNonExclusive`）；
2. `execSQL`/`rawQuery` 的参数绑定与游标列映射（`blockFrom`/`diaryFrom`）；
3. `mergeBackupDatabase` 的整天 skip/overwrite 行为与统计。

它们都需要一台 Android 设备或模拟器（Robolectric 亦可，但需先确认其 SQLite 是否编入 FTS5）。
按已确认的决策 Q8，本轮不做安装运行验证 —— **这三层已在 M9 用 Robolectric 补上**
（`DiaryRepositoryRobolectricTest`，10 条；见文末 M9 一节）。

## M2 —— 设计系统 ✅ 已完成

### 交付物

| 文件 | 说明 |
| --- | --- |
| `ui/theme/DesignTokens.kt` | `Radius` / `Spacing` / `TypeSize` **逐字对齐** iOS；`DynamicTypeMetrics` 移植缩放上限表与文本样式映射 |
| `ui/theme/Type.kt` | 把设计字号梯级映射到 M3 的 15 个排版角色（`justDiaryTypography(fontScale)`）；`EditorTypography` 的行距/段后距比例与最小输入高度 |
| `ui/theme/Theme.kt` | M3 主题 + **动态取色（默认开启）** + 品牌色回落；`themeMode` 三值映射；`LocalIncreasedContrast`（增强对比度） |
| `ui/components/Components.kt` | 共享原语：`DiaryCard`（含增强对比度描边规则）、`PageHeader`、`SectionTitle`、`IconBadge`、`EmptyState`、`ContentWidth` 约束 |
| `service/DateFormats.kt` | 日期/时间的**语义槽位**（年 / 月 / 日+星期 / 区间 / 时刻 / 相对日 / 起始行 / 小时档位）；小时制跟设备、AM/PM 跟应用语言 |
| `res/values/date_formats.xml` + `values-zh-rCN/` | 19 个语义槽位模板，**key 名与 iOS String Catalog 同名** |
| `model/AppSettings.kt` / `SettingsStore.kt` | 新增 **Android 专有**设置 `dynamic_color`（默认开，不进 `settingsJson`） |

### 验证证据

```
./gradlew assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
  97 个 JVM 单测通过
    DynamicTypeMetricsTest  12  ← 新增
    DateFormatsTest         13  ← 新增（直接读真实的 strings.xml 模板）
    ContentFormatTest       15
    DateUtilTest            14
    SearchSqlTest           13
    BackupModelsTest         9
    FtsSegmentTest           7
    ZipArchiveTest           7
    DesignTokensTest         4
    ThemeModeTest            3  ← 新增
```

两个值得单独说的断言：

1. **复现 iOS 文档里的 AX5 表**：`DynamicTypeMetricsTest` 断言
   `size × effectiveMultiplier(size, fontScale=2.0)` 等于 iOS 注释里记录的
   32→44.8、28→39.2、24→34.8、22→31.9、20→29、18→27、17→25.5、16→24.8、
   15→24、13→21.45、12→20.4、11→19.25 —— 这证明上限表的移植没有抄错。
2. **日期槽位直接读资源文件**：`DateFormatsTest` 不在测试里手抄模板，而是解析
   `res/values/date_formats.xml` 与 `values-zh-rCN/`，于是它同时守住模板内容与拼接逻辑，
   并断言两份资源的 key 集合完全一致。

### 动态字体的 Android 等价物（与 iOS 的差异）

iOS 有"每个文本样式一条曲线"；Android 只有一个全局 `fontScale`。因此规则是：

```
effectiveMultiplier(size, fontScale) = min(ceiling(size), max(1, fontScale))
```

- 默认字号（`fontScale = 1`）→ 全部为 1，**默认外观与设计完全一致**；
- `fontScale` 取到 2.0 时正好复现 iOS 的 AX5 实测表（因为那些值就是各档上限）；
- 由于各档上限不同且 `min` 单调，缩放后**字阶永不倒挂**（已在 8 个 `fontScale` 上断言）。

实现细节：`sp` 会被 Compose 自动乘一次 `fontScale`，所以 `effectiveSp()` 先把系统缩放除掉，
让最终绘制值恰好等于 `designSize × min(ceiling, fontScale)`（有单测守住这个换算）。

### 第 4 处有意不复制 iOS 的缺陷（D4）

`L10n.dayStartLabel(hour)` 把**原始 0–23 的小时**直接塞进 12 小时制的模板，
而模板是 `下午 %1$ld:00` / `%1$ld:00 PM` —— 于是 13 点会显示成
**"下午 13:00"（中文）/ "13:00 PM"（英文）**。这里先把小时归一到 12 小时制再套模板，
档位**区间**仍按原始小时判定（0 / 1–5 / 6–11 / 12 / 13–17 / 18–23）。

## M3 —— 首页日历与 morph ✅ 已完成

### 交付物

| 文件 | 说明 |
| --- | --- |
| `ui/home/CalendarLayout.kt` | **纯数学层**（不依赖 Compose）：全部度量常量、`DayMetrics` 与插值、年/月/周三种度量、年历卡片几何、密度降级、命中测试、morph 插值（年↔月 / 月↔周）、翻页数学 |
| `ui/home/CalendarGrid.kt` | 网格与键表：固定 6 行、尾部空行裁剪、**2412** 个月份键、**201** 个年份、**10488** 个周键（按 `weekStart` 缓存） |
| `ui/home/CalendarCanvas.kt` | Compose 绘制：`MonthCanvas`（日号/农历/选中圆/今天圆环/有日记下划线/行分隔线）、`WeekRowCanvas`、`YearCanvas`（12 张迷你月） |
| `ui/home/HomeScreen.kt` | 首页装配：三种形态、竖屏上下滑翻月、月↔周 morph、年月胶囊→年历 morph、减弱动态效果、横屏左右分栏（345dp 日历列） |
| `service/Lunar.kt` | 农历：名称表 + 干支/生肖 + 可注入的日历提供者（真机用 `android.icu.util.ChineseCalendar`） |
| `ui/root/RootScreen.kt` | 首页已接入真实实现（其余三页仍是占位） |

### 验证证据

```
./gradlew assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
  136 个 JVM 单测通过（本轮新增 31 个日历/农历测试）
    CalendarMorphTest       13  ← morph 插值与翻页阈值
    CalendarLayoutTest      10  ← 布局几何与命中测试
    CalendarGridTest         8  ← 网格、键表、缓存
    LunarTest                8  ← 名称表、干支、可注入换算
```

三个最关键的断言：

1. **复现竖屏与横屏的两组实测值**：竖屏 402×874 → `cellW 57.43 / cellH 118 / dayFont 20 /
   lunarFont 11 / contentH 40 / circleD 54`；横屏 750×330 → `rows6: cellH 45 / dayFont 15.3`、
   `rows5: cellH 54 / dayFont 18.36`。
2. **morph 落点与真实图层同源**：`yearMonthMorph(progress = 0)` 的 `cellH` 必须等于 118、
   `dayFont` 必须等于 20 —— 这正是 iOS 侧"收尾不跳变"的守卫方式。
   这里还纠正了一个易错点：`fullMonthGridRect` 接收的是**日历区**尺寸（高 810 = 874 − 64），
   **不是整屏尺寸**；用 874 会算出 `cellH 128.67`，与真实图层差 10pt。
3. **键表规模**：`MONTH_KEYS` 2412、`YEARS` 201、`weekKeys` 10488（周日起始从 1899-12-31 起算）。

### 一处以**源码**为准、与既有分析报告不一致的地方

分析报告 `03-home-calendar-and-motion.md` §10 把横屏 6 行的 `circleD` 写成 **34.3**，
按源码公式（`CalendarGrids.swift:24-26, 76-84`）应为 **33.3**：
`dayFont 15.3 + 4 = 19.3`，再 `min(cellW-3, 19.3+14, cellH-3) = 33.3`。
横屏 5 行的 36.36 与报告一致，可反证公式本身没错，是报告那一格的数字笔误。
**实现以源码为准**，并在测试注释里记录了这处差异。

### 另一处语义坑（已用命名规避）

农历的 `.year` 字段（Foundation 与 ICU 一致）是**六十甲子内的循环年（1..60）**，不是公历年。
`sexagenary` 的 `(y-1) % 10 / % 12` 公式依赖这个语义：循环年 **43** 才是"丙午"（对应公历 2026），
若误传 2026 会算出"己酉"（首页年历标题就会错）。Kotlin 侧字段命名为 `cycleYear`，
并在 `LunarTest` 里**同时断言正确值与误用值**，把这个坑钉住。

### Android 与 iOS 的实现差异（本里程碑）

| 项 | iOS | Android |
| --- | --- | --- |
| 翻页手势 | 自写 `DragPagePager`（三页 ZStack + offset） | 同样的自写数学（[CalendarLayout.DragPagerMath]），手势用 `detectVerticalDragGestures` |
| 「预测终点」 | SwiftUI 的 `predictedEndTranslation` | `translation + velocity × 0.2s`（UIKit 投影量级的近似）；**阈值 90 / 60 与 iOS 完全相同** |
| 文字测量缓存 | 自建 LRU 24000 + 字号量化 0.5pt | `TextMeasurer(cacheSize = 512)` + 同样的 0.5 量化 |
| morph 驱动 | 手写 `Animatable` 单进度（`MorphViews.swift`） | 同一个思路：`Animatable(0f)` + `CalendarLayout` 的纯函数插值（**不用** `SharedTransitionLayout`） |
| 减弱动态效果 | `.linear(0.18)` | `ValueAnimator.areAnimatorsEnabled()` → `tween(180, LinearEasing)` |

## M4 —— 日记阅读与分享长图 ✅ 已完成

### 交付物

| 文件 | 说明 |
| --- | --- |
| `ui/diary/ReadLayout.kt` | 阅读渲染模型（段落 / 列表待办 / 图片三种形态 + 行内 span）、图片显示尺寸规则、在篇内搜索 [DiarySearch]（段落内匹配 + 上一个/下一个） |
| `ui/diary/DiaryReadScreen.kt` | 阅读页：日期 + 分享、条目内搜索栏（关键词/序号/上下一个）、逐段落渲染（引用底色块、列表圆点、待办勾选框、内联图片）、命中高亮 |
| `data/ShareLayout.kt` | **分享长图版式**（纯函数）：画布常量、各样式字号/行高、贪心换行、墨迹行高、图片高度、整张图的游标累加 |
| `service/ImageShareRenderer.kt` | 用 `android.graphics.Canvas` 按 [ShareLayout] 的坐标绘制位图（背景渐变 + 光斑、发丝线、时间轴与节点、各段落、页脚），并写出 PNG |
| `service/ShareIntents.kt` + `res/xml/file_paths.xml` + manifest `<provider>` | 通过 `FileProvider` 分享长图与备份包（`ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION`） |
| `service/LocationResolver.kt` | 位置文案：由地区列重建地址、记录态/新建态的可选精度档、`exact` 档的地点名与街道去重 |

### 验证证据

```
./gradlew clean assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
  171 个 JVM 单测通过（本轮新增 35 个）
    ReadLayoutTest          15  ← 渲染模型、图片尺寸规则、在篇内搜索
    ShareLayoutTest         13  ← 分享图版式常量与游标累加
    LocationResolverTest     7  ← 地址重建与精度档位
```

三个关键断言：

1. **分享图常量逐项对齐**：`WIDTH 720 / BLOCK_LEFT 108 / SIDE 48 / TIMELINE_LINE_X 61 /
   TIMELINE_LINE_W 3 / DATE_Y 64 / FOOTER_H 34`，以及由它们推出的 **`CONTENT_WIDTH 564`**；
   各样式字号/行高（title 36/52、heading 32/46、quote 30/40、list+todo 30/55、body 31/50）。
2. **整张图的高度可手算校验**：测试按 iOS 的游标算式独立算一遍总高，与实现一致
   （`DATE_Y + DATE_ADVANCE + 起始行 + 发丝线 26 + 时间 56 + 地点 + 各段落 + 块间距 36 + 页脚 34`）。
3. **图片尺寸两条规则**：显示宽 `min(可用宽-24, 343)`、显示高 `max(40, ratio × 宽)`、
   解码目标 `max(storedW, storedH) × 3`；`ratio` 缺失时用 0.75。

### 测试抓到的一处**实现抄错**（值得记）

第一版我把"起始行高度"写成了 `wrappedHeight`（含墨迹与内边距）。iOS 的 `startLineHeight`
其实只算 **行数 × 40**，不带墨迹 —— 于是整张分享图会高出 24pt。
测试断言"总高 = 手算值"时把它抓了出来。**这类错误没有测试就只能靠肉眼看图**。

顺带记录一处不是笔误的地方：iOS 的引用块高度用 `max(1, 行数 - 1) × 行高 + 墨迹 + 40`，
**单行引用也会多留一行高**。这与实现一致，我把它写进了测试注释以免后人"顺手修掉"。

### 有意不复制 iOS 的缺陷（D5–D7）

| # | iOS 行为 | 这里 |
| --- | --- | --- |
| **D5** | 地点行固定按 34 前进，但它是**可换行**文本 → 长地址会与下一块重叠 | 按实际换行行数 × 34 前进（有测试断言块高随之变化） |
| **D6** | 大标题/小标题固定 52+12 / 46+10，**不换行** → 长标题溢出框外 | 按实际行数 × 行高 + 内边距 |
| **D7** | 逐字符用 `boundingRect` 实测量宽 | 把量宽抽象成注入接口：真机用 `Paint.measureText`，测试用墨迹比近似 → **换行结果确定、可断言** |

> D7 是本里程碑唯一的近似：分享图是**本地生成物**，不属于跨端数据契约，
> 因此"真机换行位置与 iOS 基本一致"即可，不追求逐像素一致。

## M5 —— 富文本编辑器（**进行中**：内核已完成）

M5 是本项目最大的一块，拆成两步做：**先做可验证的纯内核**（本轮完成），再做 Android 视图层（下一轮）。

### 已完成：纯编辑内核

| 文件 | 说明 |
| --- | --- |
| `ui/diary/EditorModel.kt` | 行模型 [EditorLine]、与 `content_json` 的双向转换、段落级操作（块样式/对齐/引用/列表/待办）、行内样式切换、Enter/Backspace 的行增删、图片行、**倒序/区间重建**工具、`FormatGate`（IME 组合区闸门） |
| `ui/diary/EditorHistory.kt` | 撤销/重做栈（纯逻辑，可断言） |
| `ui/diary/SpannableBridge.kt` | `EditorLine` ↔ `Spannable`：块样式用自定义 `BlockStyleSpan` 承载，行内样式映射到 `StyleSpan`/`StrikethroughSpan`/`UnderlineSpan`，列表/待办标记是真实字符 |
| `service/LocationRules.kt` | 位置的 5 条规则 + 精度档位 + "坐标有、地址空视为没有位置" + 记录档位原文恢复 |

### 架构：为什么把内核做成纯逻辑

iOS 的 `RichTextEngine`（1,024 行）直接操作 `NSTextStorage`，块类型挂在 `NSAttributedString`
的**自定义键**上 —— 因为 UIKit 会按固定的一组键从光标处重新同步 `typingAttributes`，
自定义键**从输入第二个字符起丢失**，于是它不得不加一层"用字号精确匹配"的兜底
（方案 §5.4 的 E1/E2，实测漂移 `15 → 18.75`，跨过阈值就把正文**永久变成标题**）。

Android 侧把状态拆成两层，让内核完全可测：

```
List<EditorLine>    ← 纯模型：段落样式、对齐、行内 run、标记（本轮）
       ↕ SpannableBridge
Spannable           ← EditText 的真实文本（IME、选区、平台撤销由控件负责）
```

**关键收益**：`Spannable` 天然支持自定义 `ParagraphStyle`，块样式由 [BlockStyleSpan] 显式承载，
**字号完全不参与样式判定** —— E1/E2 这两个坑在 Android 结构上就不存在，不需要"精确匹配"的兜底。

### 验证证据

```
./gradlew assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
  223 个 JVM 单测通过（本轮 +52）
    EditorModelTest        33  ← 5 个暗坑的对应断言
    LocationRulesTest      11  ← 位置 5 条规则
    EditorHistoryTest       8  ← 撤销/重做语义
```

方案 §5.4 的 5 个"静默数据损坏"暗坑，现在都有可断言的守卫：

| 坑 | 本轮的对应实现/断言 |
| --- | --- |
| **E1/E2** 不能用绘制字号反解块样式 | 模型里**没有字号输入**；`setBlockStyle` 设什么读什么，逐档验证四种样式往返 |
| **E3** 段落操作必须倒序 | `setBlockStyle` 改为**按区间整体重建**（列表行会摊平成多行，逐下标改必错位）；另有一条**对照测试**演示正序就地改会得到错误结果 |
| **E4** IME 组合区必须跳过 | `FormatGate.canApplyFormat` 的相交判定，含选区反向、相接不重叠等边界 |
| **E5** 全量替换摧毁撤销栈 | `EditorHistory` 的压栈/撤销/重做/分支清空/深度上限 |

另外两条与 iOS 一致的细节也钉住了：

- **开启列表/待办前先把该行重置为正文**（对应 iOS 的 `restyle(to: .body, cancelCenter: true)`），
  否则会出现"带项目符号的大标题"；
- **连续列表项合并成一条 `list`**、连续待办合并成一条 `todo` 且 `done` 数组并行
  （与 `PartsCodec` 的分组规则一致）。

### 有意不复制 iOS 的一处行为（D8）

iOS 的 `shouldBlockNewline` 在"空段落 + 居中"时**直接吞掉 Enter**，用户无法在居中段落后面另起一行。
这里允许换行，新行继承居中 —— 吞掉按键属于"按键无响应"，比多一个空行更难理解。

## M5 —— 富文本编辑器 ✅ 已完成

### 已完成：两层结构

| 文件 | 说明 |
| --- | --- |
| `ui/diary/EditorModel.kt` | **纯内核**：行模型 `EditorLine`、与 `content_json` 的双向转换、段落级操作、行内样式、Enter/Backspace、图片行、区间重建、`FormatGate` |
| `ui/diary/EditorSelection.kt` | **纯逻辑**：扁平字符偏移 ↔（行号, 行内偏移）换算（含标记前缀、行间 `\\n`、越界夹住） |
| `ui/diary/EditorHistory.kt` | 撤销/重做栈 |
| `ui/diary/SpannableBridge.kt` | `EditorLine` ↔ `Spannable`：`BlockStyleSpan`（块样式）、`ImagePayloadSpan`（图片来源，对应 iOS 的 `PayloadAttachment`） |
| `ui/diary/EditorTextArea.kt` | `AndroidView` + `AppCompatEditText` 宿主，含 watcher 回环隔断、光标保持、组合区检测 |
| `ui/diary/FontToolbar.kt` | 格式栏（M3 `FilterChip` 组）：样式菜单 / 引用 / 列表 / 待办 / B I S U / 图片 |
| `ui/diary/EditorScreen.kt` | 编辑页：保存 / 放弃修改（快照 + 二次确认）/ 位置胶囊与保存前确认 / 自动时间 / 图片插入（Photo Picker） |
| `service/LocationRules.kt` | 位置 5 条规则、精度档位、记录档位原文恢复 |

### 验证证据

```
./gradlew assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
  239 个 JVM 单测通过（本里程碑共 +68）
    EditorModelTest        40  ← 5 个暗坑 + 图片往返 + 回车继承标记
    LocationRulesTest      11  ← 位置 5 条规则
    EditorSelectionTest     9  ← 偏移换算
    EditorHistoryTest       8  ← 撤销/重做语义
```

方案 §5.4 的 5 个"静默数据损坏"暗坑的守卫：

| 坑 | 守卫 |
| --- | --- |
| **E1/E2** 不能用绘制字号反解块样式 | 块样式由 `BlockStyleSpan` 显式承载，**骨架里没有字号输入**；测试逐档验证四种样式往返 |
| **E3** 段落操作必须倒序 | `setBlockStyle` **按区间整体重建**；另有一条**对照测试**演示"逐下标正序改"会因列表行摊平而错位 |
| **E4** IME 组合区必须跳过 | `FormatGate` 判定 + `EditorTextState.composing`（组合期间格式栏整栏禁用） |
| **E5** 全量替换摧毁撤销栈 | `EditorHistory`；`EditorTextArea` 只在模型被工具栏改动时回写文本，用户输入不回写 |

### 过程中修掉的一个**数据丢失**问题（值得记）

第一版的 `EditorScreen` 里，插入的图片没有地方承载 `src` —— `EditorLine` 只有文本字段，
于是"插入图片 → 保存"会**丢图**。修法是给 `EditorLine` 加 `imageSrc/imageWidth/imageHeight`，
并用 `ImagePayloadSpan`（继承 `ImageSpan`、把 `src` 挂在 span 上）穿过 EditText 往返 ——
这正是 iOS 用 `PayloadAttachment` 的做法。测试现在断言"图片段落往返后 `src` 与宽高不变"。

顺带记录一条**与 iOS 一致**的规范化：纯文本段落（`text` 形式）经编辑器往返后会变成 `runs` 形式。
这不是 bug —— 编辑器只生产 runs，`text` 是留给手写/导入内容的紧凑形式；已在测试注释里写明。

### 有意不复制 iOS 的两处行为（D8、D9）

| # | iOS 行为 | 这里 |
| --- | --- | --- |
| **D8** | `shouldBlockNewline` 在"空段落 + 居中"时**吞掉 Enter** | 允许换行、新行继承居中（吞按键属于"按键无响应"） |
| **D9** | 标记是文本附件，换行时不继承 → 在列表里按回车会打断列表 | 新建的空行继承上一行的标记（`inheritMarkersForNewLines`，有测试） |

### 尚未接线（不属于编辑器本身，记在 M7）

1. **编辑页还没有接入应用壳**：首页的日内容区仍是占位（M4 只做了阅读页渲染），
   因此"点日记卡片 → 打开编辑页"这条路径要在 M7 接 `DiaryRepository` 时打通；
2. `EditorScreen.loadImage` 目前默认返回 null（图片行显示透明占位）——
   `src` 会正常落库，接上 `MediaStore` 后即显示真实图片；
3. 编辑器内的图片行**不做行内排版**（iOS 是正文里的文本附件）：M9 可以补上 `ImageSpan` 的实际尺寸。

## M6 —— 足迹 + 搜索 ✅ 已完成

### 交付物

| 文件 | 说明 |
| --- | --- |
| `data/FootprintAggregator.kt` | **纯聚合**：三级路径的塌缩规则、`\u0001` 去重统计、树的计数与排序、年度趋势、自然序排序 |
| `data/FootprintViewState.kt` | 足迹页的完整视图状态（统计 / 树 / 趋势 / 已标注 / 未记录 / 年份候选）+ 年份与时间范围筛选 |
| `data/SearchFilters.kt` | 时间范围四档换算、关键词胶囊（提交/移除/清除）、生效条件摘要、分页累积 |
| `ui/footprint/FootprintScreen.kt` | 足迹页：页头 + 摘要、年份胶囊、5 格统计卡、**自绘年度柱状图**、可展开的地点树、横屏并排版式 |
| `ui/search/SearchScreen.kt` | 搜索页：搜索框、关键词胶囊行、时间/地点筛选、生效条件摘要、结果列表（`<hl>` 高亮）、三态空状态、加载更多 |
| `ui/root/RootScreen.kt` | 首页 / 足迹 / 搜索接入真实实现（设置页仍占位，M7 接） |

### 验证证据

```
./gradlew assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
  272 个 JVM 单测通过（本轮 +33）
    SearchFiltersTest          18
    FootprintAggregatorTest    15
```

几个值得记的断言：

1. **三级路径的塌缩**：直辖市（只有 `country`+`region1`+`region2`）自然塌缩为"中国/北京市/北京市"；
   三者全空但 `loc_text` 非空时用 `loc_text` 当一级节点；**`region3` 永不进路径**。
2. **树的计数是"经过该节点的行数"**：父节点 ≥ 子节点之和，排序为 `count` 降序 + 名称自然序；
   测试用"反向字典序"当注入的排序策略，证明排序确实可替换。
3. **"未记录"的计数不受筛选影响**（iOS 注释明确的行为）：选中某一年后，
   `unlocatedCount` 与年份候选都不变 —— 否则选中一年后其它年份会从胶囊里消失。
4. **时间范围到"今天"为止**（不是月末/年末）；自定义范围两端会排序，
   用户先点后一个日期也不会得到空结果。
5. **自然序**：`第2章` 排在 `第10章` 之前（[FootprintAggregator.NaturalPlaceOrder]）。

### 与 iOS 的一处平台差异（已隔离）

iOS 用 `localizedStandardCompare` 排序，它是**区域感知 + 数字自然序**。
Android 侧对应的是 `android.icu.text.Collator.setNumericCollation(true)`（平台 API 24+），
但它是 Android framework API、**在 JVM 单测里不可用**。因此这里把排序抽成可注入的
[PlaceOrder]：默认实现用 `java.text.Collator`（JVM 与 Android 都有，区域感知、无数字序），
另提供一个不依赖平台的 [NaturalPlaceOrder] 用于"数字自然序"这条规则本身的断言；
真机接线时换成 ICU 版本即可（方案 §4.7.5 已记录该结论）。

### 图表：自绘而不是引库

全 app 只有这一张柱状图（年度记录天数），自绘约 100 行：
单色柱 + 4dp 圆角、左侧 Y 轴三档网格与数值（0 / 中值 / 最大值）、X 轴年份、柱顶数值。
按方案 §11.2 的结论，不值得为此引入 Vico / MPAndroidChart。

### 尚未接线（记在 M7）

1. 三个页面目前拿到的是**空状态** —— `DiaryRepository` → 页面状态的接线在 M7 与设置页一起做；
2. 搜索的地点筛选目前只有"全部 / 未记录"两档，国家+一级地区的完整选择器待接
   （模型层的 `LocFilter` 与国家/地区选项查询在 M1/M6 已完成）；
3. 编辑页仍未接入应用壳（点日记卡片 → 打开编辑页）。

### M7 起（下一轮）

按方案 §12 进入 **M7 设置 + 服务**：5 张卡的设置页与 14 个设置行、语言即时切换
（`AppCompatDelegate.setApplicationLocales`）、主题、周起始、开始时间重算、提醒（`WorkManager`）、
备份导入导出（SAF + M1 的流式 zip）、定位服务（GMS 探测）、以及**把数据层接到各页面的 ViewModel**。

---

## M7 —— 设置 + 服务（**进行中**：平台服务层已完成）

本轮完成"**设置能改、服务能跑**"的一层：定位、提醒、地区回填、设置副作用执行器，
以及设置页本身（含 15 个设置行）。**数据层接线（`DiaryRepository` → 各页面 ViewModel）
与编辑页入口仍未接**，见文末。

### 交付物

| 文件 | 说明 |
| --- | --- |
| `service/LocationService.kt` | 定位 + 反地理编码。**运行时探测 GMS**：有 Play services 走 `FusedLocationProviderClient`，否则退原生 `LocationManager.getCurrentLocation`；两者都在 **8 秒超时**内（与 iOS `Task.sleep(for: .seconds(8))` 竞速一致）。反地理编码用平台 `Geocoder`（API 33+ 的异步回调版），`PlacemarkText` → `Address` 字段映射成扩展函数 |
| `service/RegionBackfiller.kt` | 历史片段的地区列回填（iOS `DiaryRepository.backfillBlockRegions` + `ReverseGeocoder`）：每轮 30 条、`region1 = ''` 保证幂等、失败 id 记入 2000 条上限的跳过名单 |
| `service/ReminderScheduler.kt` | 每日提醒。`WorkManager` 一次性任务 + 投递后自排下一天；`DailyReminderWorker` 到点**再确认一次**（今天没写、且确实过了提醒时刻）再发通知，避免 Doze 延后到第二天仍然推送 |
| `app/AppSettingsApplier.kt` | 设置副作用的**平台执行器**：外观（`AppCompatDelegate.setDefaultNightMode`）、语言（`setApplicationLocales`）、`day_key` 重算、地区回填、提醒重排，外加进程启动序列 `bootstrap()` 与回前台的 `onForeground()` |
| `app/MainActivity.kt` | 主题/动态取色由**设置驱动**（观察 `SettingsViewModel.state`），启动序列挂起执行不阻塞首帧，回前台重排提醒 |
| `ui/settings/SettingsViewModel.kt` | 设置页 ViewModel：状态、15 个设置项的写入口（全部经 `AppSettingsApplier.commit`）、SAF 导入导出的落盘、权限事件 |
| `ui/settings/SettingsScreen.kt` | 设置页：**通用 / 日记规则 / 提醒 / 数据 / 关于** 五张卡，行的条件显示规则照搬 iOS；额外一行 Android 专有的**动态取色** |
| `res/values*/strings.xml` | 设置页 60+ 条文案，key 与 iOS `Localizable.xcstrings` 同名同值（`%d` → 位置化 `%1$d`） |

### 验证证据

```
./gradlew clean assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
  306 个 JVM 单测通过（本轮 +10）
    RegionBackfillerTest   9
```

### 一条"两套映射"的坑（新增测试专门盯住它）

iOS 对 `region1` 有**两套不同规则**，且都在生产路径上：

| 路径 | `region1` |
| --- | --- |
| 记录新位置（`LocationResolver.resolve`） | `administrativeArea ?? ""` |
| 回填历史片段（`ReverseGeocoder.reverse`） | `administrativeArea ?? subAdministrativeArea ?? ""` |

回填时把 `region1` 统一成"记录新位置"的规则看起来更"干净"，但会让**同一份备份在两端回填出不同的列值**，
进而让搜索的地区筛选与足迹的层级出现跨端差异。因此两套映射都保留，并且都抽成纯函数：

- `LocationRules.regionFromLivePlacemark(placemark, quality)`
- `RegionBackfiller.reversePlacemark(placemark)`

`RegionBackfillerTest` 里有一条测试**同时断言两者**：同一条 placemark（缺 `administrativeArea`）下，
回填给"南山区"、记录新位置给 `""` —— 谁把其中一条"顺手统一"掉，测试立刻红。

### 与 iOS 的落地差异（都是平台事实，不是取舍）

| iOS | Android | 为什么 |
| --- | --- | --- |
| `UNCalendarNotificationTrigger` 精确触发 | `WorkManager` 一次性任务（±数分钟） | 精确闹钟要 `SCHEDULE_EXACT_ALARM`（用户可撤销）；`USE_EXACT_ALARM` 只对闹钟/日历类应用开放，日记提醒用它会违反 Play 政策（决策 Q7） |
| `CLLocationManager.requestWhenInUseAuthorization()` 随处可调 | 必须回到 Activity 的 `ActivityResultLauncher` | 因此 ViewModel 只发 `SettingsUiEvent`，由 Composable 持有 launcher 执行，结果回投 ViewModel |
| 系统会自动抑制 `region1` 已空的重复回填 | 更新语句带 `AND region1 = ''` | 与 iOS 的 SQL 一字不差，保证幂等 |
| iOS 在 `requestPermission()` 之后**立刻**发起回填（存在竞态，首轮多半空跑） | 只在**已有授权**时回填 | 否则首轮 30 条会全被记进"放弃重试"名单，之后再也补不上（这是一处**修正**，记为 D10） |
| `Menu` / 滚轮 `Picker` / `UIDocumentPicker`+`ShareLink` | `DropdownMenu` / 滚动列表对话框 + M3 `TimePicker` / SAF `CreateDocument`+`OpenDocument` | 平台原生等价物 |
| app 版本号取自 `Info.plist` | `PackageManager.getPackageInfo().versionName` | 同上 |

### 有意"暂时不完美"的一处：行图标

设置页的 15 个行图标目前取 `material-icons-core` 里**语义最近**的图标（同一张卡内不重复），
不是 iOS 的 SF Symbol 对应物。没有改用 `material-icons-extended`：那个 aar 有 **35.7 MB**，
而 release 的 `isMinifyEnabled = false`，为十几个图标把整包打进 APK 不划算。
真正的 Material Symbols 图标（矢量 XML，按需引入）留在 M9，与方案 §M9 一致。

### 数据接线 + 日记页（M7 下半，已完成）

这一半把"服务能跑"变成"页面能用"：四个标签页全部接上仓库，读/写/分享/深链一条链打通。

| 文件 | 说明 |
| --- | --- |
| `ui/home/HomeViewModel.kt` | 首页：日记标记（年份区间 = **今年与当前页所在年的最小/最大 ±1**，与 iOS 一致）、选中日内容 |
| `ui/footprint/FootprintViewModel.kt` | 足迹：`allFootprintRows()` **只读一次**并缓存，筛选在内存里做；展开集合由它持有，筛选后剪掉已消失的节点 |
| `ui/search/SearchViewModel.kt` | 搜索：250ms 输入防抖、关键词胶囊、时间四档、地点筛选（国家 + 一级地区的候选来自仓库）、分页加载 |
| `ui/diary/DiaryPageViewModel.kt` | 日记页（iOS `DiaryViewModel`，534 行的对应物）：读/写切换、新建与编辑、保存（含**跨日确认**）、批量删除、待办勾选、页内搜索（150ms 防抖 + 命中步进）、位置快照与精度调整、分享长图 |
| `ui/diary/DiaryPageScreen.kt` | 日记页宿主：`isRead` 决定渲染阅读页还是编辑页；返回键经 `BackHandler` 走"有未保存内容先确认"；分享长图用 `Intent.ACTION_SEND` |
| `ui/root/RootScreen.kt` | 自适应导航 + **全屏日记页路由**（对应 iOS 的 `.fullScreenCover`）+ `dataVersion` 失效机制（对应 iOS 的 `.diaryVersionChanged`） |
| `app/LaunchIntent.kt` | 深链 `justdiary://editor?dayKey=…`（也接受 `justdiary://editor/YYYY-MM-DD`）；等 Compose 树就绪后再打开，与 iOS 的 `LaunchIntent` 同一时机 |

页面上的新交互（对应 iOS 的 `handleBlockTap` / 长按 / 顶栏按钮）：

- **点片段 → 进编辑**；**长按 → 多选**（多选态顶栏变成"数量 + 删除"）；
- 阅读页顶栏：搜索开关、**写日记**（仅当天）、分享；
- 首页横屏右栏的**当日内容**（`DayContentPane`）：片段卡片 + "写日记 / 继续写日记"，
  未来的日期显示"这一天还没有到来"（与 `DayContentView` 的条件一致）。

### 一处"看不见但要紧"的接线：编辑器脏标记

iOS 的 `DiaryViewModel.handleBack` 问的是 `controller.isEmpty()`；Android 侧编辑内容在
`EditorScreen` 内部（纯模型 `List<EditorLine>`），宿主看不到。因此给编辑器加了
`onDirtyChange: (Boolean) -> Unit`，由它把"内容或历史与进入时不同"上报给日记页 ——
否则返回键永远不会弹"未保存内容"的确认。

### 空态文案里的一个事实

之前四屏统一的 `m0_placeholder_*` 占位文案随 `SkeletonScreen` 一起删除了：
四个标签页现在都是真实实现，占位文案留在资源里只会误导。

---

## M8 —— 横屏与自适应 ✅ 已完成

### 交付物

| 文件 | 说明 |
| --- | --- |
| `ui/adaptive/AdaptiveLayout.kt` | **唯一**的版面判据（纯数据 + 纯计算），外加 `LocalAdaptiveLayout`、`rememberAdaptiveLayout`、`AdaptiveLayoutHost` |
| `ui/adaptive/AdaptiveLayoutTest.kt` | 17 条临界值断言（档位边界、分栏的双条件、主栏公式、设置列数、页面级数值） |
| `ui/root/RootScreen.kt` | 几何**只在这里读一次** → 解析成 `AdaptiveLayout` → 经 CompositionLocal 下发；导航形态改由 `navForm` 决定；侧栏分支补 `windowInsetsPadding(safeDrawing)` |
| `ui/home/HomeScreen.kt` | 删除本地的 `LANDSCAPE_SPLIT_THRESHOLD_DP=700` / `LANDSCAPE_CALENDAR_W_DP=345`，改用 `splitsMasterDetail` + `masterWidth` |
| `ui/footprint/FootprintScreen.kt` | 并排判定改由自适应层给；趋势图高 `trendHeight`、并排块高 `footprintBlockHeight`、清单宽 `footprintListWidth` |
| `ui/search/SearchScreen.kt` | 时间 / 地点两组筛选：横屏左右并排、竖屏上下堆叠（省约 60dp 高度） |
| `ui/settings/SettingsScreen.kt` | `LazyVerticalGrid`：1 列（<680dp）→ 2 列（≥680dp）→ 3 列（≥1000dp） |
| `ui/diary/DiaryPageScreen.kt` | 全屏路由没有外层 `Scaffold`，系统栏避让自己做 |

### 两组阈值，都有出处

| 用途 | 阈值 | 出处 |
| --- | --- | --- |
| 主导航形态 | `<600dp` 底栏 / `≥600dp` 侧栏 | Material 3 窗口尺寸类（决策 Q10） |
| 内容分栏与列数 | `<700` / `700–999` / `≥1000dp` | iOS `AdaptiveLayout` 阈值原样保留（决策 Q17） |
| 首页/足迹分栏 | 内容宽 `≥700dp` **且** 宽 > 高 | `splitsMasterDetail` |

### 一条最容易写错的规则（有专门的测试）

**分栏必须同时满足"内容宽够"与"宽 > 高"**：竖屏即使 800dp 宽也保持单栏 ——
因为竖屏的年/月/周三态 morph 交互是另一套，一旦宽屏竖屏分栏就会把整套手势改掉。
`AdaptiveLayoutTest.portrait never splits even when wide enough` 就钉这一条。

另外两条值得记的：

1. **主栏公式在 750dp 内容宽下必须得到 345dp** —— 这正是设计稿横屏左栏的宽度，
   测试直接断言 `0.46 × 750 = 345`，这样"公式被改成别的比例"会立刻红；
2. **限宽不能把窄屏再缩一圈**：`contentColumn()` 是"上限 660 / 下限 240"，
   竖屏 402dp 应当得到 370（402 − 2×16），而不是被算成 290 —— iOS 侧因为这个
   叠了两次边距，日记页顶部卡片一度比下面的明显偏小。

### 验证证据

```
./gradlew clean assembleDebug testDebugUnitTest   →  BUILD SUCCESSFUL
  323 个 JVM 单测通过（本轮 +17）
    AdaptiveLayoutTest   17
```

`tools/verify_sqlite_schema.py`（18 条）与 `tools/verify_backup_interop.sh`（15 条，用 iOS 真实的
`ZipArchive.swift` 读 Android 写出的 `.jdiary`）均通过。

---

## M9 —— 打磨（**部分完成**）

### 本轮完成：全量文案资源化（语言切换真正生效）

在此之前只有设置页走资源，其余四屏仍写死中文 —— 切到英文后 4/5 的界面不变，
而这在 iOS 侧是完整本地化的。因此把四屏（首页 / 足迹 / 搜索 / 日记阅读 / 编辑器 / 格式栏）
与日记页的所有对话框文案搬进资源：

| 内容 | 数量 | 做法 |
| --- | --- | --- |
| 来自 iOS catalog 的 key | 78 | key 同名同值；`%1$ld → %1$d`、`%@ → %1$s` 按 Android 的格式化规则改写 |
| Android 专有（iOS 用符号或无此文案） | 12 | 已单独注释：`a11y_expand/collapse/remove`、`ok`、`share_chooser_title`、`date_month_short`、`footprint_trend_empty`、`search_no_result`、`read_search_no_match`、`editor_location_precision`、`editor_discard_revert_msg`、`loc_precision_none` |

三个实现要点：

1. **文件级常量改成 `@Composable` 取值属性**：
   `private val TITLE: String @Composable get() = stringResource(R.string.footprint_title)` ——
   调用点一个字都不用改，而"在非组合上下文误用"会在编译期报错（正是我们要的护栏）。
2. **原本是普通函数的文案函数改成 `@Composable`**：`summaryLabel`、`resultCountLabel`、
   `precisionLabel`、`blockStyleLabel`、`timeRangeOptions`。
3. **两处必须"先取值再用"**：
   - 分享面板标题在 `LaunchedEffect`（非组合作用域）里用 → 在组合期先取好；
   - 年历迷你月的标题在 **Canvas 绘制 lambda** 里用 → `YearCanvas` 的 `monthLabel` 改成**必传参数**
     （原先写死 `monthValue + "月"`，切英文后年历仍是中文）。

另外把两个"文案在非 UI 层"的地方也接了语言：

- `SearchFilters.activeFilterItems` 新增可注入的 `noLocLabel`（纯逻辑层不能依赖 Android 资源，
  真机由 `SearchViewModel` 传 `context.getString(...)`）；
- `FootprintViewModel` 的地点排序接上 `android.icu.text.Collator.setNumericCollation(true)`，
  即"数字自然序"（`第2章` 在 `第10章` 之前）—— 对应 iOS 的 `localizedStandardCompare`，
  补上了 M6 留下的那处平台差异；纯逻辑层仍保留可注入的 `PlaceOrder` 供 JVM 断言。

### 一处自检脚本

资源键与占位符的一致性用一次性脚本核对过：`values/` 162 键 vs `values-zh-rCN/` 161 键
（只差 `app_name`，它刻意 `translatable="false"`），且**没有任何键的 `%n$s/%n$d` 数量不一致** ——
这类不一致会在真机上抛 `IllegalFormatException`，值得每次改文案后跑一遍。

### 仍然留着的（需要真机 / 未做）

| 项 | 为什么留着 |
| --- | --- |
| 真的 Material Symbols 图标 | `material-icons-extended` 的 aar 有 **35.7 MB**，而 release 的 `isMinifyEnabled = false`；为 15 个图标把整包打进 APK 不划算。当前用 `material-icons-core` 里语义最近的图标（同一张卡内不重复），并把映射集中在 `SettingsIcons` 一处 |
| 编辑器内联图片的排版细节 | 需要真机看换行与占位高度；逻辑（`EditorModel.insertImageLine` + 宽高比）已就位 |
| `-slow-morph` / `-morph-log` 调试开关 | 需要一个能跑的设备才有意义（方案 §6.2 已定方案：`BuildConfig.DEBUG` + `Intent` extra + `Choreographer` 帧日志） |
| morph 帧率实测 | 同上：无设备/无 AVD（本机 `adb devices` 为空） |
| TalkBack 走查、年月切换的 `customActions` | 需要 TalkBack；`a11y_*` 文案已在资源里备好 |
| ~~运行时验证（建表/迁移/参数绑定/编辑器往返）~~ | **已用 Robolectric 补上**（`DiaryRepositoryRobolectricTest`，10 条） |
| 真机上的安装运行验证（安装/启动/交互/帧率/TalkBack） | 本机无设备与 AVD（`adb devices` 为空），按 Q8 的验收口径不做 |

### M9 续：内联图片不再变形、调试开关、无障碍入口

| 项 | 内容 |
| --- | --- |
| 内联图片尺寸 | 新增纯函数 [ImageDisplaySize]（`ui/diary/ImageDisplaySize.kt`）：**按自然宽度显示、不超可用宽度、不小于 40，高度按原始宽高比**。原先 `ImageSpan` 的边界是写死的正方形（64×64），任何非正方形图片都会被**拉伸变形**。编辑器通过 `BoxWithConstraints` 拿到可用宽度传给 `toSpannable`，转屏/分栏变化时重建；阅读页的占位块改用已有的 `ReadLayout.imageDisplayHeight` 按比例撑开，图片加载完不会跳版 |
| 调试开关 | 新增 `app/DebugFlags.kt`：`-slow-morph` / `SLOW_MORPH=1` 把 morph 放慢到 3s（对应 iOS 的同一开关），`-morph-log` 在 morph 结束时打一行"帧数 / 总时长 / 平均帧时长"。判定逻辑是纯函数 `resolve(extra, env)`，有单测 |
| 无障碍入口 | 日历容器挂 `customActions`：上/下个月、上/下一年（`a11y_prev_month` 等 4 条新文案）。iOS 侧此处**只有手势没有标签**，Android 一并补全（方案 §6.3）；翻月逻辑抽成 `pageMonth(delta)`，分页器与无障碍动作共用一份，"选中日跟到新月份同一天、月末夹住"只有一处实现 |
| 图标 RTL | `ArrowBack` / `List` 换成 `Icons.AutoMirrored`（`supportsRtl="true"` 下方向才正确） |

本轮新增测试 14 条：`ImageDisplaySizeTest`（8，含"竖构图不设高度上限""小图不缩到 40 以下""缺 w/h 时回落到位图"）
与 `DebugFlagsTest`（6，含"环境变量只认 1"这种静默失效点）。


### M9 续（三）：数据层的**运行时**验证（Robolectric）

在此之前项目里所有断言都是"给定输入 → 期望输出"的纯函数测试 —— SQL 字符串、参数绑定、
游标列名、迁移链**一次都没有真的被执行过**。本轮接上 Robolectric 宿主，
新增 `DiaryRepositoryRobolectricTest`（10 条），把下面这些只可能在运行时暴露的问题纳入验收：

| 断言 | 为什么它重要 |
| --- | --- |
| fresh install 走完 v0 → v1 → v3 的迁移链，三张表 + FTS 表 + `user_version` 都对 | DDL 里任何一处列名/索引/FTS 语句写错，此前只会在真机上炸 |
| 新片段写入后能按 `day_key` 读回，且位置列、`content_json`、`search_text` 逐列正确 | 覆盖 `execSQL` 参数绑定与 `blockFrom` 游标映射 |
| `day_key` 归属与 `dayStartHour` 一致（写入与查询两侧同一规则） | 跨日归属是数据正确性的地基 |
| 编辑片段**不改坐标**，只改精度与文案 | 这是 `LocationRules` 的核心规则，之前只有纯函数断言 |
| 关键词搜索与地区筛选都能命中；不存在的词命中 0 | 覆盖 `SearchSql` + FTS5/LIKE 两条路径 |
| 删掉当天最后一条片段后，`diary`/`diary_flag` 一起消失 | 覆盖 `deleteBlocks` 的清理分支 |
| `day_key` 重算幂等（同一 `dayStartHour` 无冲突） | 覆盖两阶段临时键写法 |
| 编辑器 `List<EditorLine>` → `Spannable` → `List<EditorLine>` 往返：段落数、文本、待办 items/done、图片 `src/w/h` 一个不丢 | **这条在 JVM 上做不了**（需要真实的 `SpannableStringBuilder` 与 `ImageSpan`），而它正是"图片会不会丢"的关键路径 |
| 空白行按设计被丢弃、全空编辑器保留一个可编辑行 | 与 iOS `PartsCodec.parts(from:)` 的既有规则一致 |

Robolectric 运行时要下载 `android-all-instrumented`（约 100MB）；直连 `repo1.maven.org`
在本机网络下会被 TLS 重置，因此 `app/build.gradle.kts` 里把
`robolectric.dependency.repo.url` 指到阿里云镜像（已确认该镜像有全部版本）。
这只是**测试期**依赖，不进 APK。

**过程中抓到的一处测试自身错误**（值得记）：我最初把图片段落写成 `style = body` + `src = ...`，
于是 `partsToLines` 走的是"普通段落"分支 —— 文本为空 → 空白行被丢弃 → 断言 5 段变 4 段。
真正的规则是**图片段落必须 `style = image`**（iOS 的 `ContentPartStyle.image`）。
这条如果只靠阅读代码很难发现，是 Robolectric 宿主把它逼出来的。

### M9 续（四）：备份链路的端到端运行时验证（Robolectric，5 条）

`tools/verify_backup_interop.sh` 证明的是"**单测构造的**样例包能被 iOS 的 `ZipArchive.swift` 读懂"；
这一轮补的是"**真的走一遍导出、真的走一遍导入**"（`BackupServiceRobolectricTest`）：

| 断言 | 说明 |
| --- | --- |
| 导出包结构 = `manifest.json` + `db/just_diary.db`（+ `images/<name>`），manifest 字段齐全 | 跨端契约的写入侧 |
| `settingsJson` **只含 10 个业务设置项**，`search_index_version` / `content_format_version` 不在其中 | 这两个是数据版本而非偏好，恢复备份不能重置它们（Q3） |
| 被引用的图片真的进了包 | `collectImageRefs` 的按 src 去重与改名 |
| 导入 `SKIP`：已存在的日期整天跳过，`skippedDays` 计上、本机内容不变 | `mergeBackupDatabase` 的 skip 分支 |
| 导入 `OVERWRITE`：已存在的日期被整天替换（内容变了、片段换新） | overwrite 分支 |
| 带设置的包导入后设置被恢复（`settingsRestored`）、不带设置的包**不碰**本地设置 | 与 iOS 一致的"仅数据 / 数据+设置"两种导出 |
| 图片导入后按 `imp_<stamp>_<n>.<ext>` **改名落盘**，且 `content_json` 里的 src 被同步重写、字节逐一致 | 直接覆盖同名文件会让"两个片段共用一张图"的备份互相踩到（iOS 侧踩过这个坑） |

**过程中抓到的两处"测试自己写错"**（都值得记，因为它们都是很容易误判成实现 bug 的地方）：

1. `assertEquals(0, skipped.skippedDays)` —— 在 OVERWRITE 之后又断言了 SKIP 那一次的结果对象；
2. 断言导入后的图片文件名与原来相同 —— 实现**故意改名**（避免共用图片互相覆盖），
   正确断言是"落盘恰好一张 `imp_*` + 内容逐字节一致 + `content_json` 指向新文件名"。

到本轮为止，`docs/migration-progress.md` 里原先列的"三件需要设备才能验证的事"**全部有自动化覆盖了**：
建表/迁移、参数绑定与游标映射、`mergeBackupDatabase` 的整天 skip/overwrite。
仍然只有"安装到真机、看动画帧率、跑 TalkBack"这类事需要设备。

### M9 续（五）：搜索页的完整地点选择器

M6 留下的最后一个缺口：搜索的地点筛选当时只有"全部 / 未记录"两档，
而 `SearchViewModel` 里的候选查询（`getLocationOptions`）与 `LocOption` 模型从 M1 起就有，
UI 一直没接。本轮补上：

- 筛选行变成 **全部地点 / 未记录地点 / 已选地点（可点掉） / 选择地点…** 四枚胶囊；
- 点"选择地点…"打开对话框：本地搜索框 + "按日记数量排序"提示 + 候选列表
  （`国家 · 一级地区`，右侧是条数；只有国家的项标注"仅国家"）；标题与空态都有文案；
- 选项来自 `DiaryRepository.getLocationOptions(keyword, from, to)` —— 与当前时间范围联动，
  因此"选了今年"再挑地点时，候选条数就是今年的条数（与 iOS 一致）。

至此四个页面的交互都与 iOS 对齐，`SearchViewModel` 里不再有"写了但没界面"的状态。

---

## R1 —— 首次上机实测与首页返工 ✅ 已完成

**背景**：M0–M9 全部在"没有设备"的前提下完成，几何靠 JVM 单测守住。装上模拟器后，
单测测不到的那一类问题（**可用空间的真实假设**与**无障碍树**）集中暴露出来。
本轮先补了一份界面规范 [`docs/design-spec.md`](design-spec.md)，再按它返工首页。

### 环境（本轮实测）

| 项 | 实测值 |
| --- | --- |
| 模拟器 | Pixel 10 Pro AVD，`1280 × 2856 @ 480dpi` = **426.7 × 952dp**，fontScale 1.0 |
| 系统 | API 37（Android 17） |

> 单测里一直用的参考尺寸是 iOS 的 `402 × 874`。Android 这台机器**更高**（952dp），
> 而首轮的竖屏日历数学是照搬 iOS 的 `max(320, screenH - 64)` —— 这就是缺陷的温床。

### 修掉的 8 个问题

| # | 现象 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | 月历每行 ~126dp，日号之间隔一整屏空白 | `portraitAreaHeight` 照搬 iOS，行高被屏幕高度拉大，而格子内容只有 40dp | 行高固定 `PORTRAIT_CELL_H = 56dp`，区高 = `星期栏 + 行数 × 56` 并封顶 |
| 2 | 月历占满整屏，选中日内容无处可放 | 同上 + iOS 靠整屏 morph 把内容推到下一屏 | 竖屏改「月历 + 选中日面板」两段，面板 `weight(1f)` |
| 3 | **日历在无障碍树里完全不存在** | `MonthCanvas` / `WeekRowCanvas` 是 Canvas 手绘，一个节点都没有 | 新建 `CalendarDayGrid.kt`，日格改成真实 Compose 组件（每格一个可聚焦节点） |
| 4 | 年历的迷你月日号互相叠住 | 照搬 iOS 的"完整 6 行日号"，Android 年历卡片只有 ~36dp 高 | 年历改「月份名 + 记录圆点」，`YEAR_MIN_AREA_H = 470dp` |
| 5 | 点日期要**点两次**才进日记 | 照搬 iOS 的"点日期 → 整屏 morph 到周态" | 点日期 = 选中 + 换下方面板；**周态整体移除**（信息与面板重复） |
| 6 | 顶栏层级语义不对（`AssistChip` 装标题） | 照搬 iOS 的玻璃胶囊 | M3 顶栏：`titleLarge` + `TextButton(今天)` + 可点标题块 |
| 7 | **横屏旋转直接崩屏** | `portraitAreaHeight` 的 `coerceIn` 区间在矮窗口反转（`max 217 < min 240`） | 上下界由构造保证 `floor ≤ preferred ≤ available`，并加回归断言 |
| 8 | 硬编码 `TODAY_LABEL`、`monthValue + "月"` | 迁移时漏了资源化 | 全部进 `strings.xml`（`values` + `values-zh-rCN`），补 12 条键 |
| 9 | 日格**能读不能点**（`clickable=false`），整屏 100+ 碎片节点 | `clickable` 与 `semantics(mergeDescendants)` 的顺序生成了两个节点 | `clickable` + `clearAndSetSemantics`：每格一个 `Button`，35 格全部可点 |
| 10 | **空日记页没有写入口**（只有搜索框和分享） | `DiaryReadScreen.onWrite` 有参数但从没接线 | `DiaryPageScreen` 传 `onWrite = if (canEditToday) viewModel::enterWrite else null` |

### 交付物

| 文件 | 说明 |
| --- | --- |
| `docs/design-spec.md` | **新增**：界面设计规范（令牌、组件、自适应、首页规范、无障碍、缺陷清单） |
| `ui/home/CalendarDayGrid.kt` | **新增**：日格 / 星期栏的真实组件实现（替代 `MonthCanvas` / `WeekRowCanvas`） |
| `ui/home/HomeScreen.kt` | 重写：M3 顶栏 + 月历 + 当日面板；周态移除；两个 v1 bug 修掉 |
| `ui/home/CalendarCanvas.kt` | 只保留年历；年历卡片层 + 圆点渲染 + 12 个可聚焦卡片节点 |
| `ui/home/CalendarLayout.kt` | `portraitAreaHeight` / `cellHeightFor` / `yearAreaHeight` 三条新的纯函数；`YEAR_TITLE_H` 62 → 44 |
| `service/DateFormats.kt` | `dayTitleFor`：日格无障碍文案（不查资源，35+ 格共用） |
| `app/src/test/**/CalendarLayoutTest.kt` | 竖屏版面按新公式重写；补 0/h 极矮窗口、单调性、年历区高四条回归 |
| `app/src/test/**/CalendarMorphTest.kt` | morph 端点改为与各自真实图层同源（月用月区高、年用年区高） |

### 验收证据

```
> Task :app:testDebugUnitTest
356 tests completed, 0 failed          # 原 352 + 本轮新增/改写 4 条

python3 tools/verify_sqlite_schema.py  # 全部 SQL 验证通过
bash    tools/verify_backup_interop.sh # Android 写出的备份包可被 iOS 的 ZipArchive 完整读取
```

模拟器实测（逐条见 `design-spec.md` §8）：竖屏 6 行 × 56dp、**35 个日格全部是可点的
`Button` 无障碍节点**、点日期换内容、上下滑翻月、今天、年历 12 卡片、横屏分栏不崩、
深色跟随 M3；并跑通一条端到端链：面板「写日记」→ 编辑器输入 → 保存（含"未获取到位置"
的二次确认）→ 阅读页 → 返回首页后**面板显示该片段、日格状态变为「有日记」**。

### 与方案的偏差（有意）

- **年历区高与月历区高解耦**（470 vs 366）。年历要放 12 张卡片 × 6 行圆点，
  比月历吃高；两者各用各的高度，morph 的两个端点分别取各自真实图层，收尾仍然不跳。
- **顶栏补了一个显式的月 ↔ 周按钮**（Android 专有）。iOS 的周态没有回月态的入口
  （只能点「今天」），照抄会让人进了周态出不来。点日期的语义与 iOS 一致：
  月态点日期进周态、周态点已选中的那天进日记。
- 未验证项收窄为：morph 帧率实测、TalkBack 真人走读、其余三屏的横屏/深色逐条断言。

### 收尾修正（二）：竖屏只显示日历，预览只在周态 / 横屏

用户指出：**"谁让你在月视图的时候显示日记预览的？"** —— 这条同样是产品决策被我自作主张了。

- 竖屏：月态与年态都是**整屏**日历，**没有**预览；只有周态是「周条 + 下方预览」；
- 横屏 / 宽窗口：左右双栏，左栏日历（三态都在左栏），右栏预览（始终显示）。

顺带修掉两个连带问题：

1. 月历原先用 `displayedWeeks`（按月份裁到 5 行）→ 5 行时下方留一大片空白。
   改用 `CalendarGrid.weeks` 的**固定 6 行**，行高与版面都稳定；
2. 月格行高改成由**可用高度反算**（`(区高 − 星期栏) / 行数`）并夹在 44…96dp，
   不再是固定 56dp —— 952dp 的屏上得到 96dp，与 iOS 整屏月历的观感一致。
   行高封顶后网格比可用高矮，年↔月 morph 的月端点因此必须取
   `星期栏 + 行数 × 行高`（封顶后的值），否则收尾会跳（用未封顶值算出 119 vs 真实 96）。

`CalendarLayout.cellHeightFor` 的语义也随之明确：`preferred`(56) 只是"够高时的内容下限"，
**不参与夹取** —— 否则矮窗口里会永远返回 56、压不下去。

### 收尾修正（一）：周态加回来

第一版 R1 我判断"周态与当日面板信息重复"就把它删了 —— **这个判断是错的**，
用户明确要求加回来。已恢复为与 iOS 一致的三态（月 / 周 / 年）：

- `HomeViewMode` 恢复 `WEEK`，`weekProgress` 与 `yearProgress` 并行；
- 月 ↔ 周 morph 用回 `CalendarLayout.monthWeekMorph`（几何端点跟着 §5.1 的新区高走：
  `cellH` 56 → 周条 68，选中行落点 = 星期栏底部 30dp）；
- 周条新增横向翻周（iOS `weekLayer` 的 `DragPagePager(axis: .horizontal)` 等价物）：
  拖动跟手、松手按 60dp 位移判定，落定后 `snapTo(0)` 与新一周同帧提交；
- 日历带高在月态（星期栏 + 行数 × 行高）与周态（星期栏 + 周条 68）之间切换，
  容器高度突变不做布局动画，行级位移由 `rowOffsets` / `rowAlphas` 驱动。

**教训**：设计取舍该问，不该自己砍功能。单测能守住的只是"几何算得对"，
"这个交互要不要"是产品决策，属于用户。

---

## R2 —— 界面设计方案 v2 落地 ✅ 已完成

规范：[`docs/ui-design-plan.md`](ui-design-plan.md)（取代 `design-spec.md` 的五条旧写法，
冲突清单见 v2 附录 B）。本轮把方案里的 P0–P5 全部落到了代码，并在
Pixel 10 Pro AVD（1280 × 2856 @480dpi = 426.7 × 952dp）上逐屏实测。

### 修掉的缺陷

| # | 现象 | 根因 | 结果 |
| --- | --- | --- | --- |
| D1 | 搜索页永远停在「正在准备搜索索引…」，三档空态一次都看不到 | `indexNotReady` 由 `total < 0` 推导，而"无条件不查库"恰好把 `total` 留成 `-1` | `indexing` 变成**独立字段**；空查询给 `total = 0`。实测：空库下显示「搜索你的日记 + 输入关键词…」 |
| D2 | **年历整屏消失**：年态下旋转 → 转回 → 切深色，12 张卡片全不见 | 年层可见性挂在动画值 `yearProgress` 上，而它不随配置变更恢复（`mode` 是 `rememberSaveable`，`Animatable` 不是） | 两个进度条**从恢复出来的 `mode` 初始化** ⇒ 恢复即终值。实测：同一序列下 12 张卡片都在 |
| D4 | 月历画相邻月日期（8/31、10/1…） | `CalendarDayGrid` 的 `showAdjacent` 默认 `true`，iOS 是 `false` | 默认 `false`；相邻月**留空**。实测：9 月恰好 30 个日格节点，`dump` 里没有 8 月 / 10 月的日期 |
| D5 | 月态下方留 270dp 空白带 | 行高被 `MAX_CELL_H = 96` 封顶，且顶栏里塞了"2026年 九月"、没有页内大标题 | 恢复 72dp 大标题槽（32sp「九月」）+ `cellH = (区高 − 72 − 30) / 6` **不封顶**（参考设备 108dp）。6 行月铺满，5 行月正好留一行白 |
| D6 | 年历 12 张 128 × 168dp 的卡片只有一个月名 + 一个点 | 首轮以"卡片只有 ~50dp 高"为由把迷你月历删了，实测卡片高 168dp，前提不成立 | 恢复 **12 个真正的迷你月历**（日号 + 当月高亮 + 今天标记 + 有日记下划线）。这也是年月 morph 的落点 |
| D7 | 顶栏多一个日历图标按钮，月/周两态图标完全一样 | 首轮自己加的（iOS 没有这个入口） | **删除**；回月态改为周态顶栏左侧的 `‹ 九月`（与 iOS 一致） |
| D9 | 横屏右栏标题出现两个"今天" | 标题行同时画了相对日文案和「今天」按钮 | 标题行只留按钮 |
| D13 | **上下滑翻月完全失效** | 手势只写在 `MonthPager` 里，而它是死代码，月格上没有任何 `pointerInput` | 手势接到真实图层上（月态翻月 / 年态翻年）。实测：上滑 九月 → 十月，选中日跟到 10 月 20 日 |
| D3 | 「自动插入时间」两端都无效 | iOS 侧完全没消费；Android 侧消费了但语义不可感知（差几秒） | **两端统一为"显示开关"**：关闭时不显示开始时间（编辑器胶囊 / 阅读页 / 首页面板 / 分享长图），`start_time_utc` 照旧记录（`day_key` 依赖它，写 0 会掉到 1970）。iOS 侧已改并通过 `xcodebuild` 构建 |

### 首页三态（本轮主体）

- **月态**：顶栏（`▾ 2026年` + `今天`）→ 72dp 大标题槽 → 30dp 星期栏 → 固定 6 行网格；
  只画当月日期；行分隔线只在"该行含当月日期"时画；未来日期 35% 透明。
- **周态**：点日期触发**整屏 morph**（大标题槽上移淡出、星期栏上移到 y=0、选中行缩小到周条尺寸
  并落到 y=30）→ 星期栏 + 68dp 周条 + 40dp 日期行 + 当日内容。
  选中行在 morph 中**度量也插值**（`rowMetrics`），并把行高从"流动 y"换成"绝对 y"计算位移。
- **年态**：62dp 页内大标题（32sp `2026年` + 干支生肖）+ 3 × 4 迷你月；点某张卡 → 缩放 morph。
  morph 的**源卡片取当前选中月那一张**，不是固定 1 月（首轮从左上角飞过来，观感"动画不对"）。
- **分栏（宽 ≥ 700dp 且宽 > 高）**：左栏紧凑标题 34dp + 星期栏 26dp + 密铺网格
  （`resolveDensity`：perRow ≥ 60 显示农历、≥ 44 隐藏、否则周条）+ 1dp 分隔线 + 右栏当日内容。

### 实现上踩到的三个坑（已写进规范 §5.9）

1. 周态的色带只有 98dp，而月层天然要 ~640dp —— 普通 `Column` 会把它压缩，
   **最后一个子项（网格容器）分到 0 高度 ⇒ 周条完全不渲染**。必须
   `wrapContentHeight(unbounded = true, align = Alignment.Top)`，由外层 `clipToBounds()` 裁剪。
2. 分栏的三块放进 `Column` 会被按**高度**分配（日历占满左半边、右栏消失），必须在 `Row` 里。
3. 日格不写 `fillMaxHeight()` 时 `Box` 只包住内容（≈70dp），内容贴行顶、下面空一截。

### 验证

- `./gradlew testDebugUnitTest`：**355 条全绿**（`CalendarLayoutTest` / `CalendarMorphTest`
  按新几何重写：`cellHeightFor` 不封顶、行高跨月恒定、`coerceIn` 区间不反转；
  `SearchFiltersTest` 改成断言"索引未就绪是独立字段，与 total 解耦"）。
- 上机实测（截图见 `docs/ui-audit/` 与本节所列）：竖屏月 / 周 / 年、深色月 / 周 / 年、
  横屏分栏、上下滑翻月、点日期 morph、顶栏返回月态、年历 12 张卡片可聚焦。
- 无障碍：月态 30 个日格节点（只含当月），每格 `content-description` = 完整日期 + 状态。

---

## R3 —— Material 3 合规复查 ✅ 已完成

依据：Google 官方 Material 3（[m3.material.io](https://m3.material.io)）与
`androidx.compose.material3:material3:1.4.0` 的**令牌实现**（形状 / 动效 / 字号 / 组件高度的
数值直接从 AAR 字节码里取出核对，避免照抄博客里的过时数字）。
逐项核对表见 [`docs/ui-design-plan.md`](ui-design-plan.md) §3.7。

### 判定：16 项里 13 项 ✅、3 项有意偏离

修掉的**硬性违规**：

| 项 | Google 要求 | 本项目（改前） | 现状 |
| --- | --- | --- | --- |
| 触控目标 | **48dp** | `hitTarget = 44dp`（iOS HIG 的 44pt） | 48dp |
| 状态层 | 可交互元素必须有触摸反馈 | 日历格与年历卡片 `indication = null`，**完全没有反馈** | 日格 = 圆形涟漪（同 M3 date picker 的状态层）；年历卡片 = 有界涟漪 |
| 文字对比度 | 正文 ≥ 4.5:1、大字 ≥ 3:1 | 未来日期 `onSurface @35%` → **2.19:1**；周末列 `onSurfaceVariant @60%` → **3.16:1** | 全部改用语义色（`onSurfaceVariant`），不再用 alpha 压文字 |
| Small top app bar | 高度 **64dp** | 自定的 52dp | `HEADER_H = 64f`（行高随之 (区高−102)/6） |
| M3 组件字号 | 15 个角色用官方 type scale | 把自定梯级挂到角色上 → `titleLarge = 18`（官方 22）、`bodyLarge = 17`（官方 16）、`headlineSmall = 20`（官方 24）—— **顶栏 / 对话框 / 输入框 / 按钮全不是官方尺寸** | `MaterialTheme.typography` 改为 **`Typography()` 基线 + 本项目逐档封顶缩放**；自定梯级只服务内容层（编辑器段落、日历、自绘文本） |
| 形状梯级 | Expressive 梯级 `4 / 8 / 12 / 16 / 20 / 28 / 32 / 48 / full` | `panel 26`、`field 14`、`image 14` 不在梯级上 | `card 16`(large) / `panel 28`(extraLarge) / `field 12` / `image 12`（`badge 12`、`bar 4` 原本就对） |
| 动效 | 时长与缓动用官方令牌 | 600ms + iOS 的 `timingCurve(0.2, 0.75, 0.3, 1.0)` | **400ms（`medium4`）+ `easing.emphasized = (0.2, 0, 0, 1)`**；翻页 300ms(`medium2`) / 250ms(`medium1`) |

> 一处**更正我自己的判断**：改前 `Radius.card = 20` 其实**是**官方令牌
> （`largeIncreased`，M3 Expressive 新增），不是违规；真正不在梯级上的是 26 / 14 / 14。
> 已在规范 §3.7 里写明。

### 平台分工：两端各按自己的规范（R3 追加）

**iOS 按 Apple HIG，Android 按 Material 3** —— 已确认，并已在两边文档里写明，
免得以后被当作 bug"修"回去。

iOS 侧的依据（`../JustADiary`）：编辑器段落样式「字号取 Apple 的」
（`RichTextEngine.swift:9` 明确写 Title 1 = 28 / Title 2 = 22 / Body = 17 / Subheadline = 15，出自
HIG › Typography）；动态字体走 `UIFontMetrics(forTextStyle:)`；README 也写明
「照 Apple 备忘录的做法管理字体」。它对得起来。

因此**编辑器段落梯级按 M3 改了**（原先按 iOS 保留了 Apple 数值）：

| 段落样式 | iOS（Apple HIG） | Android（Material 3） |
| --- | --- | --- |
| 大标题 | Title 1 = **28** | `headlineMedium` = **28** |
| 小标题 | Title 2 = **22** | `titleLarge` = **22** |
| 正文 | Body = **17** | `bodyLarge` = **16** |
| 引用 | Subheadline = **15** | `bodyMedium` = **14** |

同时修掉一个连带问题：阅读页原先把大标题映射到 `displayMedium` ——
在 M3 基线里那是 **45sp**，不是段落标题该用的角色。现在映射到 `headlineMedium`（28sp），
样式 → 角色只有 `EditorTypography.textStyleFor` 一个映射点。

**行高也不再自算比例**：首轮沿用 iOS 的 `lineSpacing = 0.13 × 字号`（Apple 的 `lineSpacing` 是
"额外"行距，不是行高），在 Compose 里被近似成 `fontSize × 1.13`，既不是 Apple 的语义也不是
Material 的。现在直接用 M3 角色的官方行高（36 / 28 / 24 / 20）。

**互通不受影响**：`content_json` 存的是**语义样式**（`title`/`heading`/`body`/`quote`）与行内标志，
**字号从不落库**，各端渲染时按自己的规范推导。golden 字节级断言（Android 的
`app/src/test/resources/golden/`）与 `tools/verify_backup_interop.sh` 全部照常通过。

### 仍然偏离的点（规范 §3.8）

1. **首页分栏阈值 700dp**（M3 建议 840dp 起分栏）：日历 7 列至少 280dp + 正文列至少 320dp
   ≈ 700dp，手机横屏可用宽 750dp 正好落进分栏一侧；按 840dp 则手机横屏永不分栏，与 iOS 版不一致。
2. 周起始日 / 农历 / 干支生肖属区域设置内容，Material 无对应规范。

### 验证

- `./gradlew clean assembleDebug testDebugUnitTest`：**357 条全绿**
  （`DesignTokensTest` 改成断言 M3 梯级与 48dp；`CalendarLayoutTest` 的顶栏高度与
  行高期望值随 64dp 更新；新增两条把"`MaterialTheme.typography` 的 11 个角色 == M3 官方字号"
  与"编辑器梯级 == M3 角色字号"钉住 —— 这比截图更能防回归）；
- `tools/verify_sqlite_schema.py`、`tools/verify_backup_interop.sh` 均通过；
- 上机复测：月 / 周 / 年三态、点日期 morph、`‹ 九月` 回月态、年历 12 张卡可点、
  深色、设置页与其余三屏无溢出。

---

## R4 —— 按设计规范 v3 改造前四屏 ✅ 已完成（2026-09-26）

依据：[`docs/android-design/`](android-design/README.md)（界面设计规范 v3，取代 v2）。
**日记页尚未开工**（按计划等前四屏测试通过后再做）。

### 交付（5 个提交，每屏一个）

| # | 提交 | 范围 |
| --- | --- | --- |
| 1 | `3ed9d72` | **地基**：手机锁竖屏 / 平板自由旋转；分栏阈值 700 → **548.5**（iOS 当前值）、主栏下沿 280 → 260；补 `AssistPill` / `InputPill` / `CountBadge` / `SkeletonRows` / `BusyOverlay`；`DiaryCard` 去阴影改 tonal elevation；新增 `LocalReduceMotion` |
| 2 | `d4dbc27` | **首页**：morph 中撤下手势；分栏左栏接上下滑翻月（并带走选中日）；竖屏翻月不动选中日；顶栏两枚胶囊改 assist chip + 右胶囊文案规则；年态返回键；未来日 Snackbar；周态两条分隔线；宽窗口日历限宽 640dp；年历「今天 × 有日记」不再互斥 + 卡片底色；星期栏极短文案；导航图标换 Material Symbols |
| 3 | `9660595` | **设置页**：行高 64/56、标题 `bodyLarge`、副标题 `bodyMedium`、分隔线缩进 72dp、徽标 40dp 圆；开关行整行可点；版本行去箭头；覆盖按钮改 error 色；忙碌遮罩带 scrim；15 个图标全部换成 Material Symbols；**归属日重算改为先算再问再改，并修掉两端共有的数据损坏缺陷**（见下） |
| 4 | `ba41cdf` | **搜索页**：筛选收进卡片（两组 + 组标题）；条件摘要用 `InputPill` 逐条可撤销 + 清除全部；新增「自定义时间范围」ModalBottomSheet；地点选择器 `AlertDialog` → `ModalBottomSheet`；结果行本地化日期 + 独立时刻；加载态进度条 + 骨架行；平板横屏「左筛选 + 右结果」 |
| 5 | `6b2e1b0` | **足迹页**：年份芯片补「自定义」（与搜索页同构的 sheet）；年份选中态不再被 `timeKind` 绑架；趋势卡只在 ≥2 年份时渲染；树节点 40dp 徽标 → 18dp 裸图标（一级 globe / 下面 place）；计数 12sp 右对齐；空态真居中；横屏清单栏固定 300dp |

### 顺带修掉的数据损坏缺陷（两端共有）

`recomputeDayKeys` 的冲突判定写的是 `holderOf[row.oldKey]` + `newKeys[ch] != oldKey`，
而 `holderOf[k]` 按定义就是"新键 == k 的那一行"，内层条件**恒为假** —— 冲突分支从未进入、
`conflicts` 永远是 0。真撞车时（目标键被一个**不移动**的记录占着）第二阶段 `UPDATE` 会违反
`day_key UNIQUE`，iOS 侧被外层 `try?` 吞掉并当作"0 冲突"，但**临时键已经写进去了** ——
归属日残留成 `tmp#N`。

Android 现在按真实唯一约束判断（目标键被不移动的记录占着 → 记一次冲突并跳过），
并把纯逻辑抽到 `data/DayKeyPlan.kt`，**预览与执行共用同一个函数**；
`DayKeyPlanTest` 6 条断言（不移动 / 移动 / 键不变 / 无开始时间 / 撞车记冲突 / 相邻互换）。
`day_key` 在"不撞车"的所有情形下与 iOS 逐字一致，跨端契约不受影响。

### 验证

```
./gradlew clean assembleDebug            # 通过
./gradlew testDebugUnitTest --rerun-tasks # 365 条，0 失败
python3 tools/verify_sqlite_schema.py     # 通过
bash    tools/verify_backup_interop.sh    # 通过
```

上机（Pixel 10 Pro AVD，`docs/ui-audit/r4/`，18 张）：

| 场景 | 截图 |
| --- | --- |
| 月态 / 周态（`16天前`）/ 年态（今天圆 + 20 号日记标记 + 9 月卡片高亮） | `01`–`03` |
| 未来日期 Snackbar | `04` |
| 平板横屏分栏（左栏滑月 → 右栏跟着走到 10 月 26 日） | `05` |
| 平板竖屏（侧栏 + 日历限宽居中）/ 平板年历 | `06`、`07` |
| 手机翻月后右胶囊改显具体日期（`9月26日`） | `08` |
| 设置页（上 / 下）/ 平板两列 | `09`–`11`、`20` |
| 搜索：空态 / 结果（计数徽标 + 可撤销胶囊）/ 自定义时间 sheet / 地点 sheet / 平板横屏 | `12`–`15`、`19` |
| 足迹：空态 / 有数据（统计 + 三根柱 + 三级树）/ 展开 / 深色 | `16`、`17`、`22` |
| 深色首页 | `21` |
| 手机锁竖屏（强制 `user_rotation=1` 后截图仍是 1280 × 2856） | — |

### R4 自查补漏（提交 `178ceb9`）

对着规范逐条复核，补上三处缺口、修掉两处放大字号下的溢出：

| # | 问题 | 处置 |
| --- | --- | --- |
| 1 | 搜索框聚焦时没有「取消」（§1.2） | 输入框右侧追加「取消」，清空输入 + 收键盘，未聚焦不占位 |
| 2 | 条件摘要行缺「未提交的输入」那一枚（§1.4） | 新增 `SearchFilterItem.Kind.KeywordInput`；它与 `Keyword` 文案可能相同但点 ✕ 的动作不同。补 3 条单测，总数 365 → **368** |
| 3 | `PageHeader` 用了 `headlineLarge`（32sp） | 改为 `headlineMedium`（28sp）；副标题 `bodySmall` → `bodyMedium` |
| 4 | 足迹年份胶囊在 1.3× 字号下把「自定义」推出屏幕 | 横滑改 `FlowRow` 换行（与搜索页时间档同一处理） |
| 5 | 足迹页头右侧摘要会挤掉左侧标题 | 限宽 260dp + 允许折两行 + 右对齐 |

放大字号（`font_scale = 1.3`）下四屏各截一张复测，无裁切、无溢出；默认字号已还原。

窄屏复核（360dp 宽，`wm size 1080x2400` + `density 480`）：四屏各截一张，存
`docs/ui-audit/r4/narrow-360dp/`。结论：首页日历、搜索的筛选胶囊与条件行、
设置的行（副标题折两行、取值与箭头不被挤走）、足迹的年份胶囊（`FlowRow` 换到第二行）
全部正常 —— 设计是在 412dp 上做的，360dp 也站得住。

### R4 第二轮：用户复测反馈 + 对抗式评审（提交见下）

用两个独立子代理对前四屏做了一次**对抗式评审**（只读、各自 25 条发现），
连同用户复测的两轮反馈一起处理。

**用户反馈（第一轮）**

| 反馈 | 处置 |
| --- | --- |
| 首页「今天」按钮不要日历图标 | 去掉；同时补稳定语义（`contentDescription` 固定「回到今天」，可见文案走 `stateDescription`） |
| 搜索页按钮要左右滑动、不要多排 | 撤掉 `FlowRow`，回到单行 `horizontalScroll` |
| 足迹顶部按钮同理 | 改 `LazyRow` 单行横滑 |
| 年份按钮只显示真有日记的年份 + 保底自定义 | 候选改为「已定位 ∧ 当前时间范围」内出现过的年份；年份筛选不参与候选计算；「自定义」无条件渲染 |
| 搜索/足迹/设置顶部高度不一致 | 实测设置页标题在 **32dp/72dp**、另两屏在 16dp/60dp（页头塞进网格的 item，内边距叠了）。页头移出网格后三屏实测框完全一致 |

**用户反馈（第二轮）**

| 反馈 | 处置 |
| --- | --- |
| 设置卡片底部留白太多（按双行说明定的高，单行就空） | 行高 64/56 → **56/48**（是 `heightIn(min)`，双行会自然长高）；卡片内边距 12 → **4**；末行文字下方留白从 ~24dp 降到 ~12dp |
| 说明请尽量压到单行 | 7 条中文副标题改写（如「仅可修改或删除已有编辑块，历史日期不可新增内容」→「可改历史日记，但不可新增」），412dp 上全部单行 |
| 足迹顶部按钮以年份为准，不要显示具体日期，默认选中全部 | 「自定义」芯片文案固定，不再显示 `2026-01-01 – 2026-01-26`；年份与自定义改为**单一选择**（点年份清掉自定义区间），默认落在「全部」 |

**评审发现的两个 P0**

1. **首页周态/年态按返回键必崩** —— `PredictiveBackHandler` 没消费 progress flow，
   activity-compose 1.13.0 在协程结束后 `check(completed)` 抛 `IllegalStateException`。
2. **归属日重算的传递性冲突**（数据损坏）—— 被判为冲突的行同样不会腾空旧键，
   排在它后面的记录会被误判为可移动，写库时撞 `day_key UNIQUE`；
   而 `AppSettingsApplier` 会吞掉异常 → **新规则写进去了、`day_key` 还是旧的、没有任何提示**。
   改为迭代到不动点。

**评审的其余高价值项**

- 月↔年 morph 期间**日历区整屏空白 ~180ms**（两个图层互斥组合，年层 alpha 在 p<0.45 时是 0）
  → 两层同时在场，月层按 `1-p` 淡出并朝源月卡片等比缩小；
  年层淡入提前到 p=0.25，并加一条"任一帧至少一层可见"的单测
- 平板分栏是 **50/50** 而不是 `clamp(内容宽×0.46, 260, 440)` → 左栏改用 `adaptive.masterWidth`
- 周态仍挂着「上下滑翻月」手势 → 竖直拖一下会让周条变成"另一个月的第一周"、与下方日期脱节
  → 周态撤下该手势
- 首页状态在数据/设置变化后不重置 → 补 `dataVersion` / `settingsVersion` 两个入参，无动画回「今天 + 月态」
- 密度阈值 44 → **38/60 两档**，`roundToInt` → `floor`（补 3 条边界单测）
- 甩动判定 **单位混用**（累计是 dp、速度是 px/s）→ 速度也换算成 dp/s
- 当日空态用 emoji → 换 `AppIcons.Edit` / `DarkMode`
- 年历层未限宽（只有月历限了）→ 与月历同一个 `calendarW`
- 地点面板的搜索框**完全不生效** → 250ms 防抖后走 SQL `LIKE`，并 `ensureActive()`
- 足迹空态把**整页**换掉 → 只换清单卡，统计卡照常显示 0
- 搜索页缺 `dataVersion` / 设置版本刷新 → 补上
- 搜索结果行 a11y 节点丢了点击动作（`clearAndSetSemantics` 在外层，把内层 clickable 的
  `onClick`/`Role` 一起重置掉了）→ 新增 `DiaryCard(contentModifier=)`，语义放**clickable 之内**
- 搜索行日期用系统语言而模板用应用语言 → 改跟随应用内语言
- 命中高亮只加底色不改字色；树行高兜到 48dp；单键对话框「保存」→「好」；
  设置两列阈值 680 → 700；开关行内层 `Switch` 不再自带 `toggleable`；设置行统一 16/14 字号
- `06-components-and-overlays.md` 曾列出 **16 个并不存在的组件** → 整节按实现重写
- 上一轮的两张平板证据截图 md5 完全相同（"平板年历"其实没拍到）→ 重新拍摄，现已不同

### R4 第三轮：跟手翻页 + 两个"没反应"型 bug（提交见下）

**跟手翻页**（规范 §6.1/§9）：此前上下滑**完全不跟手**（`accumulated` 只用来算阈值），
现在拖动时位移直接作用到日历层、超过一页加橡皮筋阻尼、松手后补完动画（提交 220ms /
放弃 180ms）再换页。拖动方向按 `decide()` 的口径：向上拖 = 下一页从下方进来
（第一版取了负号，变成"手往上、内容往下"，靠截图才发现）。

**修掉两个"没反应"型 bug**（都不报错，只能靠实操发现）：

1. **年态没有翻页手势**（存在已久）—— `morphing` 写成 `yearProgress > 0.01f`，
   而年态的静止值是 1.0，于是年态永远被当作"morph 中"、手势被整条撤下。
   改为判「停在端点之间」。判据已写进 03-home.md §8.5。
2. **年态拖动露白**（上一轮引入）—— 上一轮只给月层加了邻页，年层只加了位移。
   现在两层共用 `PagerViewport`（跟手位移 + 邻页补齐 + 拖动时才组合邻页）。

**同时**：手势从"月层的修饰符"移到"日历区容器的修饰符"上 —— 年态下月层是不组合的，
挂在月层上本身就是年态失灵的原因之一。

**分栏阈值扣掉导航栏**：`contentWidth` 原来只扣安全区。实测（2560×1600@320dpi）
Rail 占 0–80dp、分隔线 80–81dp，共 81dp。后果：600–629dp 的横屏窗口按整窗算出
≥548.5 → 误判可分栏，而实际内容宽只有 519–548dp、详情栏不足 240dp。

**其余**：足迹柱状图 132 → **168dp**（分栏 200dp）；删掉从未使用的 `isWeekend`；
清掉 13 个死 import；选中月卡片底色统一到 `surfaceContainerHigh`（规范那一行同步改掉）；
`CALENDAR_BAND_TOP` 的错注释更正。单测 373 → **375**。

### R4 第四轮：把两条主链路真机走通 + 参数编辑改底部面板

前两轮抓到的都是"点了没反应"型 bug，所以这一轮改成**按交互逐条实操**。

**归属日重算链路（数据完整性，端到端走通）**
塞进去的测试数据恰好是"`day_key` 与 `start_time` 不自洽"的（`day_key` 写 2026-09-20、
而 `start_time` 落在 2025-09-20 00:40），正好用来验证迁移。
设置里把「新一天的开始时间」从 4:00 改成 2:00：

| 环节 | 结果 |
| --- | --- |
| 确认框文案 | 「有 **2 篇**日记会因为规则变化改变归属日，是否继续？」—— 与手算一致 |
| 点确认之前 | 行内取值仍是「凌晨 4:00」（**先算再问再改**） |
| 点继续之后 | `2026-09-20 → 2025-09-19`、`2026-09-25 → 2025-09-24`，另两条不动 |
| 临时键残留 | `SELECT COUNT(*) ... LIKE 'tmp#%'` = **0** |
| 设置行 | 变为「凌晨 2:00」 |

**地点面板的搜索框（此前完全不生效）**
输入 `zzz` → 列表只剩「全部地点 / 未记录地点」并出现「暂无地点数据」；
说明 SQL `LIKE` 真的在跑（修之前这个框做什么都不会变）。

**参数编辑改 `ModalBottomSheet`**（README 硬约束 3 / 06 §2.3）
「新一天的开始时间」与「提醒时间」原来都是 `AlertDialog`。现在都是底部面板：
拖柄 + 标题 + 内容 + 底部动作区（取消 / 保存），提醒时间用 M3 的表盘。

### R4 第五轮：时间范围校验 + 合并重复面板 + 导入对话框

**自定义时间范围加了校验与归一化**（此前是纯文本框、输入什么存什么）
- 范围过滤是**字符串比较**，所以 `2026-9-1` 与 `2026-09-01` 的字典序不同 →
  区间算出来是空的 → 界面上只显示"没有找到匹配的日记"，用户根本不知道是自己写错了。
- 新增 `data/DayKeyInput.kt`（纯逻辑）：收下 `2026-09-01` / `2026-9-1` / `2026/9/1` /
  `2026.9.1` / `20260901`，统一成 `YYYY-MM-DD`；`2026-02-30`、`2026-13-01`、年份越界
  一律返回 `null`。两端写反**自动交换**（手误不该报错）。
- 界面：格式不对时输入框转 error 态 + 下方一行红字说明，**「确定」禁用**。
- 两个 ViewModel 的 `applyCustomRange` 也各兜一次归一化（防御界面之外的调用点）。
- 补 9 条单测（375 → **384**），含"归一化后字符串序 == 时间序"这条核心性质。

**两处近似的范围面板合并**：`CustomRangeSheet`（搜索）与 `FootprintTimeSheet`（足迹）
是两份约 70 行的拷贝 —— 规范要求两处**完全同构**，第二次改动必然分叉。
现在收成 `ui/components/DateRangeSheet.kt`，两页共用。

**导入对话框**
- 正文原来直接复用行内副标题（「从其他设备恢复日记」）—— 那是**入口**的说明，
  放在一个即将覆盖数据的确认框里等于什么都没说。现在说清两种选择各自的后果，
  覆盖那一行用 error 色。
- 动作顺序原来是 取消 · **覆盖** · 跳过（破坏性的挤在中间、安全的占着最右确认位）。
  按 06 §2.2 改为 取消 · 跳过 · **覆盖（最右、error 色）**。

### R4 第六轮：平板全量回归（横竖屏 + 深色）

这一轮改过的东西太多（跟手翻页、morph 两层、分栏阈值扣 rail、两个面板），
所以把平板横竖屏与深色整体重跑一遍。

| 检查项 | 结果 |
| --- | --- |
| 平板横屏（1280×800）分栏 | 分隔线落在 **rail 81 + 主栏 440 = 521dp**，与 `clamp(内容宽×0.46,260,440)` 一致 |
| 分栏左栏上下滑翻月 | 十月；**右栏跟着走到 10月26日**（分栏翻月带走选中日），且右栏正确显示未来日空态 |
| 平板竖屏（800×1280） | 不出分栏；日历限宽 640dp；**深色正常** |
| 平板年历 | 与月历同一个限宽 |

**修掉一个自己引入的居中回归**：新抽的 `PagerViewport` 内层 Box 少了 `TopCenter` ——
日历与年历都是限宽 `min(640, 内容宽)`，而那一层是整宽的，于是平板竖屏上日历**贴左**、
右边空出一大块。三层 Box（视口 / 位移层 / 邻页）都补上居中。

### R4 第七轮：搜索出错态 + 足迹图表打磨 + 清理

**搜索"查库失败"不再伪装成"没有找到"**
- 原来失败时只把 `loading` 关掉 → 界面走空态分支显示「没有找到匹配的日记」。
  查库出问题时用户会以为自己真的没写过那篇 —— 这是最不该有的一种静默。
- 现在 `SearchUiState.error` 独立成一条：界面**优先**渲染错误块
  （`warning` 图标 + error 色 + 「重试」按钮），`hasError` 与 `isEmpty` 是两个正交状态。
- 顺带修掉评审指出的 `runCatching` 吞 `CancellationException`：那是我方主动取消
  （用户又敲了一个字），**不是**查询失败，更不该写任何状态 —— 现在直接 rethrow。
- 补 1 条单测钉住"零结果 ≠ 出错"（385 → **386**）。

**足迹趋势图**（规范 §2.4）
- 网格 3 条 → **5 档**（0/¼/½/¾/最大，`distinct()` 后按实际最大值收敛）；
  原来只画 0/中值/最大，中间一大段没有参照，柱子之间差多少看不出来。
- 网格线加 `outlineVariant @70%` —— 满不透明度会和柱子抢视觉重心。
- 柱子**最小高 3dp**：值为 0 的年份原来完全不画，那一列看起来像"没有数据"。

**统计数值字号**：22sp 常规 → **18sp 半粗 + 单行**。05 §2.3 写 20sp、01 §2.2 写 18sp
两处冲突，统一以 01 设计系统（`TypeSize.statValue`）为准，并把 05 那一行改掉。

**清理**
- 设置页删掉 11 个被 `AppIcons` 取代后遗留的 `Icons.Filled.*` import，
  以及那段早已过时的「M9 临时图标映射」KDoc（下一段注释已经说明改成了 `AppIcons`）。
- 窗口底色 `background` → `surface`：两者在 M3 里是 N98/N99，rail 形态下四周会差一档色。

### R4 第八轮：全新安装（空库）复核

此前所有验证都带着塞进去的测试数据 —— 而**新用户看到的是空库**，这一轮补上。

`adb shell pm clear com.cov.justdiary` 之后逐屏走：

| 屏 | 结果 |
| --- | --- |
| 首页 | 九月 · 今天实心圆 · **全月没有任何日记短横线**（标记读取正确） |
| 搜索 | 引导态「搜索你的日记 / 输入关键词，或选择时间与地点筛选」 |
| 足迹 | 「已标注 0 处 · 未记录 0 篇」；胶囊只剩 `全部` + `自定义`（**没有年份**，因为一个都没有）；统计卡 **0 值常驻**（不是整页空态）；清单卡空态 |
| 设置 | 全部行正常；「位置权限」显示**未授权**；说明都是单行 |

首屏专属路径也走了一遍：
- 空库下第一次搜索（需要现场建 FTS 索引）→ 正常返回「没有找到匹配的日记」，
  与"出错"是两个状态（这轮刚分开的那条）
- 首页点任意一天 → 周态；当日面板空态用 `edit` 图标；
  **非当天不给「写日记」入口**（与"历史日期只能改不能新增"一致）
- 周态左胶囊显示月份名（`九月` → 回月态），右胶囊显示相对日（`23天前`）

证据：`docs/ui-audit/r4/43–46`。

### R5 · 用户复测后的第一轮修复（提交 `9dd4e70` / `44b2e7d` / `0071b2c`）

用户第一次真正上手测（此前"看我一直没结束所以还没开始测"），一次性给了四屏的反馈。
其中首页那三条是同一个根因。

**首页：月态点击全被年历吃掉（一个根因，三个症状）**
- 「点击位置像是年视图上日期的位置」「进不了周视图」「一滑就跳出年历」都出在
  **年历层常驻**：`graphicsLayer { alpha = 0f }` **不阻止命中测试**，那层画布仍然占着版面、
  按**年历卡片的矩形**分发点击；拖动时又会以 `yearProgress = 1f`（完全不透明）
  组合出邻年页，于是月态一拖就把整屏年历滑进来。
- 改成与月层**对称的按需组合**（`mode == YEAR || 进度 > 0`）。
- 顺带把年历缩放锚点从"整层中心"改成**源月卡片中心**（原来像从屏幕正中炸开）。

**测试数据（用户要求："生成一批测试数据，按导入导出的格式生成备份文件"）**
- `tools/make_test_backup.py` → `tools/out/JustDiary_test.jdiary`：
  88 天 / 143 片段 / 6 图；**编辑模式全部段落样式**（title·heading·body·quote·list·todo·image）、
  四种行内样式、居中段落、待办三种完成度、跨 2024–2026、6 国铺满三级树、
  21 个片段**故意没有地点**。
- **导入路径端到端验证**：Robolectric 真的 `importBackup` 这个产物，
  断言 ≥80 天/≥140 片段/6 图落盘、七种段落样式与四种行内样式全在、
  图片 src 重写成 `images/imp_*`、SKIP 二次导入全跳过、内容可被 FTS 搜到。
- **渲染验证**：把包里的 DB 与图片直接装进设备（`run-as` 覆盖 databases 与 files/images），
  确认 —— 首页九月**奇数日全部出现日记短横线**；当日面板渲染出
  `8:40 · 东山区 · 京都府 · 日本` + 标题样式 + 正文 + **图片真的画出来了**；
  足迹页「已标注 **122** 处 · 未记录 **21** 篇」、年份胶囊 2026/2025/2024、
  统计 9/10/6/122/75、**三根柱子 12/32/31**、5 档网格、三级树（中国 54 / 日本 25 / 法国 14）。

**搜索页**
- **未回车的输入不再触发搜索**（`effectiveKeyword` 只看已提交关键词，
  `hasAnyCondition` 同步收紧，删掉 `KeywordInput` 那种 kind）。
- 自定义日期改成 **Material 官方 `DatePicker`**：两端是只读卡片 + 点开日历，
  起止互相约束（截图里 27–30 日是灰的）。搜索页与足迹页共用同一个 `DateRangeSheet`。

**设置页**
- 章节标题 12sp → **14sp**、上边距 0 → **12dp**（原来紧贴卡片上沿，
  就是用户说的"没和卡片圆角对齐、偏上"）。
- 「新一天的开始时间」「提醒时间」已是 `ModalBottomSheet`（上一轮改的）。

**农历**
- **只在中文环境显示**：新增纯函数 `LunarVisibility.showsLunar(appLanguage, systemLanguage)`
  （要处理 `"system"` 字面量）。`RootScreen` 此前**根本没传** `showLunar`，一直是默认 true。

**待办（下一轮）**
- 「切换语言时界面闪一下」：成因已定位 —— `AppCompatDelegate.setApplicationLocales`
  在 API 33+ 走 `LocaleManager`，**按设计会重建 Activity**。窗口底色
  （`window_background`）在明暗两套里都已配好，所以不该闪白；但"闪一下"本身
  还需要实测帧才能判断是重建还是别的，**未动手改**。
- 「设置里有没有可以换成官方 SDK 的部分」：还没逐个核对。

### R5 · 第二轮：语言切换闪烁的实测 + 官方组件审计

**「切换语言时整个界面闪一下」—— 实测是真的，而且是一帧纯黑**
用快速连续 `screencap` 抓了切换瞬间的 14 帧，逐帧算平均亮度：

```
f1 235.7 → f2 0.0 → f3 235.6 → … → f14 235.8
```

`f2` 亮度 **0.0**，即**整屏纯黑的一帧**。成因：`AppCompatDelegate.setApplicationLocales`
在 API 33+ 经系统 `LocaleManager`，**按设计会重建 Activity**；重建过程中旧窗口已销毁、
新窗口还没画出第一帧，中间那一帧没有任何窗口在画 —— 于是露黑。

窗口底色本身没问题（`window_background` 在 `values/` 与 `values-night/` 两套里都配了，
分别是 `#FDFBFF` / `#101318`），所以**不是"闪白"**，是重建的空档。

**处置：暂不改。** 要彻底消除只能绕开 Activity 重建（在 Compose 层用
`CompositionLocalProvider` 覆写 `LocalContext`/`LocalConfiguration`），
但代码里有多处**在组合之外**读 `appContext.getString(...)`
（例如 `SearchViewModel` 的"未记录地点"文案），那些路径会继续用旧语言 →
出现中英混排，比闪一帧更糟。这个取舍交给用户定，不擅自改。

**官方组件审计**：设置页逐个交互核对完 —— **已经全部是官方组件**
（`Switch` / `DropdownMenu` / `TimePicker` / `ModalBottomSheet` / `AlertDialog` /
SAF 文件选择器 / 系统权限弹窗 / 新换的 `DatePicker`）。唯一半自绘的是小时列表，
而 Material 3 **没有"只选小时"的控件**，`TimePicker` 会强制选分钟 →
按"前提是功能一致"**不替换**。结论写进 05 §3.0。

### R5 · 第三轮：双向交互审计（吸取"只测了一个方向"的教训）

翻页方向那个 bug（`d31bf02`）暴露的是**测试方法的盲区**：我只验了"向上拖 → 下一个月"，
而那恰好是对的方向。于是这一轮把**所有带方向 / 成对的交互**都补测两个方向。

| 交互 | 之前测过 | 这一轮补测 | 结果 |
| --- | --- | --- | --- |
| 月态翻月 | 只测了向上 | **向下** | ✅ 九月 →下拖→ 八月 →上拖→ 九月 →下拖→ 八月 |
| 年态翻年 | 没测 | **两个方向** | ✅ 下拖 → 2025年、上拖 → 2026年 |
| 周态翻周 | **完全没测过** | 左右都测 | ✅ 15号 →左滑→ 22号（4天前）→右滑→ 15号 |
| 周态竖直滑 | — | — | ✅ 无反应（**设计如此**：周态没有翻月手势） |
| 一周起始日 周一 → 周日 | **没测过** | 实际改一次 | ✅ 表头变 `日 一 二 三 四 五 六`，且**日期落格跟着变**：9/1（周二）落在「二」、9/6（周日）落在「日」 |
| 自动记录时间 开 → 关 | **没测过** | 实际关一次并看内容 | ✅ 当日面板从 `8:40 · 东山区 · 京都府 · 日本` 变成 **只有 `东山区 · 京都府 · 日本`** —— 时刻隐藏、地点保留，正是"只隐藏不删数据"的既有语义 |
| 设置开关本身 | 没测过 | 关一次并回读 | ✅ 无障碍节点 `checked` 由 true 变 false |

**方法上的结论（写给以后的自己）**：凡是"方向 / 开与关 / 增与删"的交互，
验证清单里必须**成对**列出来，不能只写一条。翻页那次就是只写了"向上滑翻月"。

### R5 · 第四轮：整屏检查（自查发现两处，修一处、回退一处）

按上一轮总结的检查项「**拖到一半必须看整屏**」，把年态与周态也过了一遍。

**月态两页之间有大块空洞 —— 已修（`81810ca`）**
九月（5 行）滑上去、十月出现之前，中间空了近两行。根因是**页高取了整个日历带**
（带高按固定 6 行分），5 行的月份在**页内**留一整行空白，下一页又从"带高"之后才开始。
改成页高 = 内容实际高度（标题槽 + 星期栏 + 网格），两页紧挨着。
静置时多出来的高度落在**页外**，观感与改前一致。

**周条横向拖动时右侧是空白 —— 自查发现，本轮未修**
周条只组合**本周**（`CalendarDayGrid(rows = listOf(week))`），所以横向拖出去的部分
就是空白，松手后新的一周再"啪"地出现。真机抓帧确认（评审早先也指出过，
见 07 §3.1 的 P1-8）。

**尝试过一版"三周并排"，但把周条整个画没了，已回退。** 现象与原因：
把每周套进 `Box(Modifier.width(stripWidth.dp))` 之后，`CalendarDayGrid` 内部的
`fillMaxHeight()` 拿不到高度约束（"只给宽度"的 Box 是**包裹内容**的），整条变成空白；
补 `fillMaxHeight()` 之后仍然空白，说明还有第二个原因没找出来（很可能是外层 Row
在 `align(Center)` 下的高度传递）。**没有把没修好的东西留在树上**，已 `git checkout` 回退，
工作区干净。

### R5 · 第五轮：胶囊统一 + 拖动帧率实测

**顶栏两枚胶囊统一为 `Outlined`**（用户："今天按钮的样式似乎和年份月份的按钮样式
不太一样，统一一下"）。原先右胶囊是 `Elevated`（浅底+阴影），在同一根顶栏上
看起来比左胶囊"高一级"，但两者只是"导航"与"回到今天"的区别，没有主次。三处「今天」一并统一。

**可拖范围 半页 → 一整页**。上一版取 `bandH * 0.5`，手指滑过约 4cm 内容就开始减速、
随后被橡皮筋拖住 —— 主观就是"顿挫、不完全跟手"。月份/年份是无边界列表，
没有理由限制拖动距离。> 这一条是**凭手感推断**的，下面的实测才是依据。

**真机帧率实测**（`dumpsys gfxinfo` + 一次 2.5s 纯拖动）：

| 指标 | 值 | 读法 |
| --- | --- | --- |
| Total frames | 223 | |
| 50th percentile | **14ms** | ≈71fps，**大部分帧是好的** |
| 90th / 95th | 25 / 30ms | 有周期性尖峰 |
| Janky frames | 61（**27%**） | |
| **Number Slow UI thread** | **61** | ← **瓶颈在 UI 线程** |
| 50th gpu percentile | 2ms | GPU 完全不是瓶颈 |

**结论**：卡顿在**组合/布局/分发**，不在绘制。中位数 14ms 说明不是持续掉帧，
而是**每隔一阵卡一下** —— 与用户说的"顿挫感"完全对应。

**已定位但本轮未改**：`Modifier.graphicsLayer { }` 的 block 是在**布局阶段**调用的
（`BlockGraphicsLayerModifier.measure`），所以在那里读 state 会**每帧让该子树重新 layout**
（而不是像我原先以为的"只让 layer 失效"）。下一步要把 measure/layout 的作用域量清楚，
否则容易把三只 Canvas 的重绘引回来。**这是"不完全跟手"的最后一个已知原因。**

### R5 · 第六轮：周条三周并排 —— 三次尝试、三次回退（如实记录）

目标：横向拖动周条时，拖出去的那一侧目前是**空白**（只组合了本周）。

**承诺过的"先做最小验证"这一步做到了** —— 读 `CalendarDayGrid` 的根布局发现：
它的每一行都自己设了显式高度（`Row(Modifier.fillMaxWidth().height(m.cellH.dp))`），
所以**高度约束根本不是原因**。这否掉了上一轮我写进文档的假设。

随后三次尝试与结果：

| # | 结构 | 结果 |
| --- | --- | --- |
| 1 | 外层 Row(裁切) → 内层 Row(位移) → 每周一层 `Box(width).fillMaxHeight()` | 整条**空白** |
| 2 | 单个 Row（裁切+位移同层）→ 三个网格直接做子项 | 整条**空白** |
| 3 | 外层 Row(裁切，不动) → 内层 Row(位移) → 三个网格直接做子项 | 整条**空白** |

第 3 次是我据"裁切与位移不可同层"的推断设计的（前两次各占了其中一个错），
**仍然空白** ⇒ 这个推断也不成立，真正的原因还没找到。

**处置：三次全部 `git checkout` 回退，工作区干净，装机确认周条恢复正常。**
不再在整屏里继续试错 —— 每试一次要一轮构建 + 装机 + 抓帧，而三只 Canvas 同屏的
变量太多。**下一步改为最小复现**：把 `WeekStrip` 单独放进一个只有一个 Box 的
测试 Composable（甚至一个 Robolectric/截图测试），只调"三周并排"这一件事，
确认结构成立之后再搬回首页。

**已排除的假设（这是本轮的真实产出）**：
1. ~~高度约束传不下去~~ —— 网格自带显式行高，与父约束无关；
2. ~~`clipToBounds()` 与 `translationX` 同层导致裁切窗被搬走~~ —— 拆成两层后仍空白。

### R5 · 第七轮：月态邻页只画出**前两行**（用户反馈，已精确定位到症状）

用户："月份上滑的时候，下一个月的日期会被空白遮挡。感觉原因是月份视图高度不足，
而导致下一个月的日期从本月的最后一行出现。相反，下滑的时候前一个月的日期出现就没有问题。"

**用户的方向是对的 —— 高度问题，但不是我先前以为的那一处。**
把拖动做深（让邻月整页进入视口）后抓帧，症状变得非常具体：

十月 2026 应有 **5 行**（1-4 / 5-11 / 12-18 / 19-25 / 26-31），
实际只画出**前 2 行**（1-4、5-11），**第 3~5 行完全缺失**，位置是空白。

所以不是"被空白遮挡"，而是**邻页的网格只渲染了两行**。这与我此前所有假设都不同：
- ~~页高不足~~ → 页高是内容高度（`72 + 30 + 行数 × cellH`），邻页不该被裁；
- ~~裁切窗被位移搬走~~ → 已排除；
- 新事实：邻页的 `CalendarDayGrid` 只产出 2 行。

**待查（下一轮的第一件事）**：`neighborMonthArgs` 里 `CalendarGrid.displayedWeeks(first, weekStart)`
对邻月返回的 `weeks.size` 到底是多少 —— 若是 5，则问题在绘制/高度；若是 2，则问题在这个纯函数。
这是个纯函数，**可以直接加一条 JVM 断言**（例如"2026-10 在周日起始下应得 5 行"），
不必再上真机试错。

**为什么之前没发现**：我每次拖动幅度都不够大 —— 邻页只露出标题和一两行，
"只画了两行"与"只露出两行"在截图上长得一模一样。
**教训：验证"下一页是否完整"必须把拖动做到底，让整页进入视口。**

（本轮试过的 `wrapContentHeight(unbounded)` 改法对症状无影响，已 `git checkout` 回退，工作区干净。）

### R5 · 第八轮：月态邻页缺失 —— 用日志把范围缩到"邻页自身渲染"

上一轮承诺"先加 JVM 断言，别再上真机试错"。做了，结论如下。

**1. 纯函数没问题（JVM 探针，已删）**
`CalendarGrid.displayedWeeks` 逐月打印：

```
2026-8 rows=6   2026-9 rows=5   2026-10 rows=5   2026-11 rows=6   (周一起始)
2026-8 rows=6   2026-9 rows=5   2026-10 rows=5   2026-11 rows=5   (周日起始)
```

十月确实是 **5 行** ⇒ 不是纯函数的问题。

**2. 邻页确实被组合了，参数也全对（真机日志）**
在 `neighborMonthArgs` 里打一行日志，真机拖动中抓到：

```
NEIGHBOR: offset=1 first=2026-10-01 rows=5 curRows=5
          cellH=95.242424 monthPageH=578.21216 bandH=673.4545
```

⇒ 邻页是十月、5 行、`cellH` 与当前页一致、页高 578dp 落在 673dp 的带里。
**相邻两页的参数完全正确**，问题只剩"邻页自己画出来只有两行"。

**3. 踩到的坑：这台 ROM 会吞掉 `Log.d`**
第一轮探针用 `Log.d("NEIGHBOR", …)`，`logcat` 里 **0 条**，
差点据此得出"邻页从未被组合"的错误结论。改成 `Log.e` 立刻就有。
**以后在这台机器上加日志一律用 `Log.e`/`Log.w`。**

**下一步（已缩小到一个函数）**：在邻页的 `CalendarSection` / `CalendarDayGrid` 里
用 `onSizeChanged` 打出"网格容器实际拿到的高度"和"行数"，
即可判定是**容器高度**还是**绘制**的问题 —— 不必再靠截图猜。

### R5 · 第九轮：邻页的**布局与数据都是对的** —— 问题在"可见窗口"

按计划加了 `onSizeChanged` 探针（`Log.e`，真机拖动中抓）：

```
17:24:51.876  GRIDROWS: anchor=2026-10-01 rows=5 h=1310px cellH=95.242424
17:24:51.877  GRIDBOX : month=2026-10-01 rows=5 p=0.0  h=1310px cellH=95.242424
17:24:51.877  GRIDBOX : month=2026-09-01 rows=5 p=0.0  h=1310px cellH=95.242424
```

1310px = 5 × 95.242 × 2.75 ✓ **正好五行**。所以：

- 邻页**被正确组合**（`anchor=2026-10-01`、`rows=5`）；
- 网格容器**拿到了完整的五行高度**（1310px，不是两行）；
- `p=0`（不处于 morph）、`cellHeight` 与当前页一致。

⇒ **布局与数据全对，问题只在"屏幕上实际看得见多少"**，即页与带的几何/裁切，
不是网格自己少画了行。

**这一条把范围又缩了一次**：不用再查 `CalendarGrid` / `CalendarDayGrid` / `metrics`，
要查的是"邻页在带里的可见窗口"。已知几何（真机）：

| 量 | 值 |
| --- | --- |
| 日历带 `bandH` | 673.45dp |
| 一页内容 `monthPageH` | 578.21dp（= 72 标题 + 30 星期栏 + 5 × 95.24） |
| 差 | **95.24dp = 整整一行** |

**下一步的一个明确疑点**：`PagerViewport` 的页高用的是 `monthPageH`（578），
而外层 `clipToBounds()` 的带高是 `bandH`（673）—— 两者差一行。
拖动时邻页从 578 处开始进入，而带只到 673，**邻页最多只能露出 95dp + 拖动量**。
若拖动量的上限（`dragRangeDp = bandH = 673`）没有真正生效（例如被
`clampOffset` 之外的因素限制），邻页就永远只能露出一两行 —— **这正好是观察到的现象**。
下一轮直接量"拖动到最大时 `dragOffsetPx` 是多少"，即可判定。

（本轮两处探针已 `git checkout` 回退，工作区干净。）

### R5 · 第十轮：量到了关键数字；又否掉一条假设

**量到了（真机 `Log.e`，一次大幅度上拖）**：

```
accumulated=-645.67  dragRangeDp=673.45  maxOffsetPx=1770.8px
bandH=673.45         monthPageH=578.21   cellH=95.24
```

⇒ **拖动量本身完全正常**（645dp，超过一整页 578dp；`clamp` 与自由行程都生效）。
上一轮怀疑的"拖动上限没生效"**被否掉**。

**新观察（这次抓到的是另一种切片）**：
- 早些时候抓到：邻页显示「标题 + 星期栏 + 第 1~2 行」，下面空白；
- 这次抓到：邻页显示**第 3~4 行**（12-18 / 19-25），**上下都是空白**。

两次合起来说明：**可见的不是"永远前两行"，而是一个约两行高、位置随拖动量移动的窗口。**
这正是"裁切窗跟着内容走"的特征。

**据此改了一版**（把 `clipToBounds()` 与 `graphicsLayer{translationY}` 拆到两层：
外层只做不动的裁切窗、内层承载内容并位移）—— **仍然无效**，已 `git checkout` 回退。

⇒ 至此**已排除**：
1. ~~`displayedWeeks` 纯函数~~（十月确为 5 行）
2. ~~邻页未被组合 / 参数错~~（日志证明参数全对）
3. ~~网格容器的布局高度~~（`onSizeChanged` = 1310px = 整五行）
4. ~~拖动量上限没生效~~（实测 645dp > 一页）
5. ~~`clipToBounds` 与 `translationX/Y` 同层~~（拆两层后无变化）

**下一步（建议做法，不再猜）**：给两层（当前页 / 邻页）各画一圈**调试边框 + 文字标注**
（`drawBehind { drawRect(style = Stroke) }` + 角落写"page=+1 h=…"），
一次装机就能看清"每一页的实际矩形、裁切窗的位置、内容画在哪"。
比继续改结构再截图有效得多 —— 这也正是本轮 `Log.e` 起作用的同一种思路：
**先把不可见的量变成可见的，再动代码。**

（本轮探针与实验性改动全部回退，工作区干净；截图存 r5-15。）

### R5 · 第十一轮：调试边框抓到"盒子和内容不在同一个地方"

按计划给每一页画了调试边框（这次边框放在 `graphicsLayer` **之后** —— 上一轮放在之前，
边框画在 layer 外面、不跟着动，等于白画）：

| 颜色 | 对象 |
| --- | --- |
| 洋红 | `PagerViewport` 的裁切窗（不动） |
| 蓝 | 邻页（page +1）的 Box |
| 红 | 当前页（page 0）的 Box |

**抓到的现象（截图 r5-17）**：
- 屏幕上显示的是**十月**的三行（1-4 / 5-11 / 12-18），占屏幕顶部往下约 310dp；
- 而**蓝框（邻页 Box）的上边缘在约 376dp 处**，其左右边线从 376dp 往下延伸。

⇒ **邻页的内容画在 64–376dp，而它自己的 Box 在 376dp 往下** ——
**盒子和内容不在同一个位置**，内容比盒子高了约 310dp。

这才是"邻页只露出一部分"的真身：不是被裁，而是**内容与盒子错位**。
前面所有"页高/裁切"的假设因此都不成立（它们解释不了错位）。

**下一步**：查 `CalendarSection` 内部让内容**向上偏移**的东西。可疑点集中在两处：
1. 网格容器上的 `.graphicsLayer { translationY = -shiftY * density }`
   （`shiftY = bigTitleH * p`，月态 p=0 应为 0 —— 但要确认邻页拿到的 `p` 确实是 0）；
2. `Column(Modifier.wrapContentHeight(unbounded = true, align = Alignment.Top))` ——
   在"父 Box 高于内容"时它的对齐行为。

**排查方法（沿用本轮有效的做法）**：再打一次 `Log.e`，把邻页的
`weekProgress`、`shiftY`、以及**内容自身的 `onSizeChanged` 高度与得到的 top 偏移**打出来。
一次装机即可定位，不再靠猜。

（调试边框已回退，工作区干净。）

### R5 · 第十二轮：接受用户的架构提议 —— 月态改连续无限滚动

用户："现在是一页只显示一个月的内容，其他月的内容都不显示。其实，可以将整个月视图
做成一个类似无限上下滑动的界面，每个月之间间隔一行。滑动变成类似无级的效果，
这样就会好做很多吧？"

**这个判断是对的。** 我做的是一个**手写的分页器**（同时组合上/中/下三页、
自己算页高与可拖范围、自己做橡皮筋与收尾动画），
根因就是"在用手写代码复刻框架已经做好的事"—— 于是每修一处就冒一处，
七轮里踩了：标题不换、两页空洞、邻页只露一部分（至今没查清）、拖动 27% 卡顿。

**已确认框架支持**：项目用的 Compose Foundation 1.12.1 里
`rememberSnapFlingBehavior` 存在（`androidx/compose/foundation/gestures/snapping/`），
即"惯性 + 吸附到 item 边界"是官方现成的。

**方案已写进 `03-home.md` §8.7**（含要删掉的 6 处手写件、要保持不变的 4 条语义）。
核心：

```kotlin
LazyColumn(
    state = listState,
    flingBehavior = rememberSnapFlingBehavior(listState),
    userScrollEnabled = !morphing,
) { items(MONTH_KEYS.size) { MonthBlock(it) } }
```

下一轮实施。**这是本轮的真实产出：确认了可行性 + 定下了删除清单与不变量**，
不再在旧结构上继续打补丁。

### R5 · 第十三轮：**月态改成连续无限滚动 —— 一次改动解决了磨了七轮的三个问题**

按用户提议实施（`03-home.md` §8.7）：手写分页器整体删除，换成

```kotlin
LazyColumn(
    state = monthListState,
    flingBehavior = rememberSnapFlingBehavior(monthListState),
    userScrollEnabled = !morphing,
) {
    items(CalendarGrid.MONTH_KEYS.size, key = { CalendarGrid.MONTH_KEYS[it] }) { index ->
        val key = CalendarGrid.MONTH_KEYS[index]
        CalendarSection(args = monthArgsAt(key, withMorph = key == monthKey))
        Spacer(Modifier.height(cellH.dp))     // 月与月之间空一行
    }
}
```

外加两处同步：`monthKey` 变了就 `scrollToItem`；停稳后若 `firstVisibleItemScrollOffset != 0`
就 `animateScrollToItem` 吸附到整月（慢拖没有 fling，框架不会自动吸附）。

`neighborMonthArgs` 泛化成 `monthArgsAt(key, withMorph)` —— **只有当前月那一项**
参与月↔周 morph，其余月份的 `weekProgress` 强制为 0。

**真机实测（一次全通过）**：
- 拖动中：九月的尾部与**十月紧接着**出现，**没有空白、没有错位、没有裁切**（r5-18）；
- 慢拖松手：**吸附回整月**（r5-19 左）；
- 点某一天：**照常进入周态**，周条与当日面板都对（r5-19 右）；
- 点「2026年」进年历、返回键回月态：**都正常**。

**这一改同时解决了**（全部是磨了很多轮的老问题）：
1. 邻页只露出一部分 / 盒子与内容错位 —— 结构上不再存在"我自己摆两页"；
2. 拖动顿挫、不够跟手 —— 滚动走框架的优化路径（还顺带消掉了
   `graphicsLayer` 块属布局阶段读取带来的每帧 layout）；
3. 两页之间空洞、页高不一致 —— 连续列表里没有"页高"这回事。

**并且天然适配不同高度的屏幕**（用户指出的额外好处）：内容自己有多高就多高，
不需要按屏幕反推"一页应该是多少 dp"。

验证：clean assembleDebug + 401 条 JVM 单测（--rerun-tasks，0 失败）+ SQL 校验。

### R5 · 第十四轮：周条横向拖动留白 —— 用同一个模式修好了

**这是我自查发现、并且前两轮尝试两次都失败（把整条周条画没）的问题。**
上一轮月态改用 `LazyColumn` 成功之后，同一个模式直接套过来就成了：

```kotlin
// 居中的窗口 + 框架滚动：一次放 7 周，当前周固定在正中，左右各有邻周
val state = rememberLazyListState(initialFirstVisibleItemIndex = WEEK_WINDOW_CENTER)
LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state)) {
    items(WEEK_WINDOW, key = { it }) { i ->
        val days = week.map { it.plusWeeks((i - WEEK_WINDOW_CENTER).toLong()) }
        CalendarDayGrid(rows = listOf(days), ...)
    }
}
```

停稳后：吸附到整周 → 若 `index != 中心` 则回调 `onPageWeek(index - 中心)`，
再 `scrollToItem(中心)` 把窗口**无动画地**拨回去（内容同时也被父层换成新的当前周）。

**真机实测**：拖动中显示的是**完整的一周**（16-22，四周有邻周），无空白；
松手吸附到整周；选中日从 15 前进到 22；今天（26）的圆环照常（r5-20）。

**为什么前两次失败**：我一直在"给一层 Row 手动加两个兄弟节点"，
反复卡在 `clipToBounds` / 高度约束 / 位移层的组合上。
**换成"窗口 + 框架滚动"之后，这些都不需要我关心了** ——
和月态是同一个教训：**不要手写框架已经做好的东西。**

### R5 · 第十五轮：用户给出 iOS 参考图；一次改一半的教训

用户（附 iOS 截图）："把当月、星期几栏固定在上面，让具体的日期都在下层进行无级滑动。
在每个月的分隔是对应位置向下偏移一周。在每个月的一号上方显示对应的月份。"

**目标版面**已按截图整理进 `03-home.md` §8.8（钉住的大标题 + 星期栏、
内联月标签贴在 1 号那一行上方、月间空一行）。

**本轮做错的一件事**：我只改了"其他月份用紧凑标签"（第 3 条），
**没有同时做钉住层**（第 1、2 条）。结果是滚动中间态出现
**一排没有任何列标题的日期** —— 比改之前更糟。已 `git checkout` 回退。

⇒ 教训写进 §8.8 要点 5：**钉住层与紧凑块必须同时上，改一半就是坏的。**
这与前面几轮的教训是同一条：**结构性改动要么整块做完并验证，要么不动。**

（本轮为验证"年态也换成列表"所做的改动也一并被这次回退带走了 —— 它当时只编译通过、
未上真机验证，属于不该留的半成品。下一轮与钉住层一起做。）

### R5 · 第十六轮：收尾（用户确认无其他问题）

用户："目前我没有别的问题了，你做好之后就停下吧。日记页之后再改。"

**本轮没有提交代码改动**，只做收尾：

- 试了把内联月份标签改画在**网格首行的叠层**里（想彻底消掉标签与 1 号之间的空隙，
  并顺带免掉溢出保护）。代码写完、编译通过，但**真机屏幕唤不醒**
  （多次 `KEYCODE_WAKEUP` / 电源键 + 滑动解锁，截图亮度始终 ~24，即全黑），
  **无法验证**。按本项目的规矩（未验证的改动不留），**已 `git checkout` 回退**。
- 工作区回到最后一个已验证的提交 `cfc8aaf`，并重跑全套校验。

### 首页交付状态（交给用户复测通过）

月 / 周 / 年三种视图**统一为"框架滚动"**，手写分页器与自绘翻页手势全部删除。
用户逐条反馈并已在真机验证的问题：

| 反馈 | 处置 |
| --- | --- |
| 翻页顶出空白 / 邻页只露一部分 / 顿挫 | 换 `LazyColumn` + `rememberSnapFlingBehavior`（月态后按用户要求去掉吸附） |
| 月份标题不该重复 | 钉住层（大标题 + 星期栏），随滚动换字 |
| 内联月标签位置 | 压在 1 号那一列上方；中文数字 / 英文缩写，固定区全写 |
| 标签溢出屏幕 | 按标签实测宽度夹住缩进 |
| 分割线范围 | 只跨"这一行真正有日期的列" |
|  месяцы 分隔线 | 画在月份开始之前，与行分隔线同粗细同颜色 |
| 上下间隔太大 | 行高改为 `max(列距 x 1.2, 内容下限 72dp)` |
| 农历被裁 | 内容下限 72dp；**分割线改为行顶叠层、不再占布局高度**（最后一行被挤出容器） |
| 日期与农历间隔大 | `LUNAR_TIGHTEN = 6dp` + `offset` 补偿光学居中 |
| 有日记的标记让格子错位 | `DiaryMark` 始终占位 |
| 今天按钮失效 | 周条与外部周同步；月态 `goToday` 无条件拨回当月 |

### ⚠️ 一次流程失误：回退源码后忘了重新装机

用户看到的是"月份标签太靠下、挤到 1 号"—— 那是**上一轮被回退掉的实验版本**。
原因：我 `git checkout` 回退了源码，但**没有把回退后的 APK 重新装到手机**，
设备上跑的还是那个"标签画进网格首行"的实验包。

**教训**：回退源码之后，**必须重新构建并装机**，否则"工作区干净"只说明代码库干净，
用户手上的东西没变。而且这次回退的原因恰恰是"无法验证"——
设备唤不醒导致我没能确认它有多糟，最后是**用户**替我发现的。

已重新安装 `a3fcea2` 构建出的 APK。用户确认：上一个版本是对的 ——
**月份标签就压在分割线上方那一行**，不下探、不挤占 1 号。

**已知遗留（都不影响使用）**：
1. ~~内联月份标签与 1 号之间还有约 10dp 空隙~~ —— **不改**：试过把标签下移贴近 1 号，
   用户实测"太靠下、挤占了 1 号"，而"标签压在分割线上方"这个版本用户认为是对的。
   **结论：这一点空隙是应有的留白，不是缺陷。**；
2. `HomeScreen` 里换掉分页器后留下的死代码（`PagerViewport`、`pagerGesture` 的
   `if (true) ... else` 分支、只被它们读的拖动状态）尚未清理；
3. 年态的翻年也已换成列表（与月/周一致），但用户尚未专门复测过。

## R6 · 月态改「连续月历流」：去掉吸附、无级跟手、周/年切换从哪来到哪去

**用户（2026-09-27）**："我需要你重新去掉吸附效果，重新尝试实现滑动无级，并且跟手。
周视图的月视图的切换都要从哪来到哪去，注意做好动画切换的位置计算和动画效果。
月视图和年视图的切换也要一并进行检查。对应的 iOS 版本已经基本实现了我的需求，
你可以参照 iOS 版本来实现 Android 版本的动画。"

### 做法：逐条移植 iOS `Views/Home/MonthFlow.swift`（commit `fbbfb83`）

| iOS | Android |
| --- | --- |
| `MonthFlowLayout`（纯几何，2400 个月的前缀偏移） | `MonthFlowLayout`（同结构，dp，可 JVM 断言） |
| `MonthFlowView`（`Animatable` 逐帧插值） | `MonthFlow`（`FlowScroll` + 延迟读取，见下） |
| `MonthFlowMorphSource`（冻结"按下那一刻屏幕上的行几何"） | 同名 data class + `MonthWeekMorph` |
| `dominantBlockIndex`（顶部大标题 = 占视口最多的那一块） | 同 |
| 拖动 2pt 起手 / 橡皮筋 / `stiffness 130 · damping 26` 弹簧 | `MonthFlowPhysics`（三个常数与 iOS 逐字相同） |
| 右缘滚动指示条 | `FlowScrollIndicator` |

**删掉的东西**：月态的 `LazyColumn` + `rememberSnapFlingBehavior` + "停稳后拨回项起点"
的兜底吸附；`CalendarSection` / `CalendarSectionArgs` / `neighborMonthArgs` /
`CalendarLayout.monthWeekMorph` / `monthWeekRowOffsets` / `MonthWeekMorphState`
（那套"第几行 × 行高"的推导正是 §8.9.2 那个严重 bug 的根因）。

### 关键实现点（踩过的坑都记在这里）

1. **偏移只在延迟 lambda 里读**：`Modifier.offset { }`（块位置）、`graphicsLayer { }`
   （年 morph 的缩放 / morph 层每一行）。组合期只读两个 `derivedStateOf`：
   "画哪几块"（收敛成 `IntRange`）与"顶部是哪个月"（收敛成块序号）——
   滚动过程中**这条流不重组**。首轮"不跟手、卡顿"的根因就是组合期读了位移。
2. **`AwaitPointerEventScope` 是受限协程作用域**：里面不能调 `Animatable.snapTo`
   这类 suspend 函数。所以偏移是普通快照状态，动画由协程里的 `Animatable`
   逐帧回写（`animateOffset`）。
3. **内容层的原点必须落在钉住层之下**：钉住层与内容是同一个 `Box` 里的叠层
   （不是 iOS 那种 `VStack`），少了 `offset(y = headerH)` 这一句，
   每个月都会画到标题底下去 —— 静止时那 72+30dp 正好把本月第一行整个盖掉。
4. **钉住层的透明度 = 可见性 × 年 morph 曲线**：少了"可见性"那一半，周态 / 年态里
   那颗大标题会穿在周条后面（周层没有底色，一眼可见）。
5. **小标题不能加固定高度**：Compose 在高度放不下**一整行**（含 `includeFontPadding`）
   时会把整行字**一个都不画** —— 21dp 带高下节点在、语义在、屏幕上什么都没有；
   把高度放到 60dp 立刻正常。现在让它自己量高，只用 `offset` 摆顶边。
6. **小标题要带底色**：Android 竖屏行高由列宽推出（73dp），上一行底部留白只有 ~19dp，
   放不下 21+3dp 的小标题 —— "1 号正好是周首日"的月份（约 1/7）会压到前一行的农历上。
   给这一行字一块 `surface` 底，压住的部分干净让位（iOS 行高 94pt 留白 29.5pt，不受影响）。
7. **`MonthWeekMorph` 只让"被点中的那一行"逐帧重组**（它的度量要从月格缩到周条），
   其余行的位置 / 透明度全在延迟 lambda 里算；进周态的 morph 起点与屏幕上那一帧
   **逐像素一致**（这是"从哪来到哪去"的前提）。

### 真机（Pixel 10 Pro AVD）实测

| 用例 | 结果 |
| --- | --- |
| 慢拖 300px 松手 | **停在原地不吸附**；标题按"占屏最多"换成十月；右胶囊改显示"9月27日" |
| 快甩 | 平滑减速到预测落点停住（`v=-7698dp/s → 跨 4249dp / 1.4s`，`--ez morph-log` 量到） |
| 滚到半月位置 → 点日期 ×2 → 返回 | **9月15日 回到完全相同的 bounds**（`[182,842][365,1061]`，改动前 / 改动后逐字相同）|
| 月 → 年 | 连续流原地缩小进"当前月那张卡"，年历 1.6 → 1 淡入（源卡片为锚点）|
| 年 → 月 | 点迷你月：网格从卡片长出来，**下个月的日期立即出现**（月末露在视口底部）|
| 今天 / 翻年 / 返回键 | 都正常（翻年的手势在这一轮重构里掉过一次，已补回）|
| 深色 | 正文与内联小标题配色正常 |

> **平板分栏（横屏）这一轮没有专门复测** —— 用户 2026-09-27："不要测试横屏了，
> 横屏问题之后一并测试。" 代码上它已经改成同一套连续月历流（紧凑小标题 + `(区高−标题
> 槽−星期栏)/6` 的行高），但**没有**按验收标准走一遍，别把它当成已验过的功能。

### 测试

- 新增 `MonthFlowLayoutTest`（**17 条**，逐条移植 iOS `MonthFlowLayoutTests`
  + 手感物理）：行高一致 / 月份之间不留空隙 / 小标题在分割线上方 / 静止偏移 /
  视口内容 / 可见块 / 小标题列号 / 顶部月份"过半才换" / `displayedWeekCount`
  与 `displayedWeeks` 在 **2412 个月**上逐月一致 / 弹簧三常数。
- `CalendarMorphTest` 里"第几行 × 行高"的那几条随数学一起删除，只留
  `weekStripAlpha`（周条的淡出必须比内容区更陡）。
- 全量 **416** 条 JVM 单测通过。

### 截图证据（`docs/ui-audit/`）

| 文件 | 内容 |
| --- | --- |
| `r6-1-continuous-flow-no-snap.png` | 左：月态静止；右：慢拖 260px 松手后**停在原地**（未吸附） |
| `r6-2-month-week-morph.png` | 左：月→周 morph 的中间帧（标题淡出、选中行上移、内容顶上来）；右：周态终态 |
| `r6-3-year-to-month.png` | 左：年→月 morph 的中间帧（网格从「10月」卡片长出来）；右：月态终态（下个月日期已就位） |

### 与 iOS 的两处刻意差异（平台要求，不是省事）

1. 每一个日期都是**真实的 Compose 组件**（TalkBack 逐格可读），不是 Canvas；
2. 钉住层带 `surface` 底色（iOS 靠 `clipped()` 切齐，不需要底色）。

## R6 补 · 行高对齐 iOS 的 94pt + 小标题压在 1 号正上方

**用户（2026-09-27）**："小月份要在 1 号正上方。另外，小月份和上一个月的最后要稍微隔开
一点，以小月份的上方为限。行高也按照 iOS 的 94pt 来设置，这方面对齐 iOS。
顶部大标题的问题，先不要改。"

| 用户的话 | 处置 |
| --- | --- |
| （横屏）| 用户 2026-09-27："不要测试横屏了，横屏问题之后一并测试。" —— 分栏左栏跟着换成了同一套连续流，但**留待之后一并测试**，这一轮不算验过 |
| 行高按 iOS 的 94pt | 改用 **iOS 同一条尺子**：`(区高 − 标题槽 − 星期栏) / 6`（`CalendarLayout.flowCellHeightFor`）。iPhone 402×874 上正是 94pt，本机（426.7×952dp）得 ~105dp —— 写死 94dp 反而会在底部漏出 0.7 行下个月，不是 iOS 的画面 |
| 小月份压在 1 号正上方 | 小标题的文字改成**在 1 号那一列里居中**（`TextAlign.Center`）。iOS 的 `frame(width: cellW, alignment: .bottom)` 默认也是居中；此前用的是 Compose 默认的起始对齐，看着偏左 |
| 小月份与上个月的最后隔开一点 | 行高一改，一行底部的留白从 **19dp → 35dp**，放得下小标题的 21 + 3dp，**顶边之上还剩约 11dp 空档**（实测：2 月那种"1 号是周首日"的最坏情况，农历底边与小标题字形顶边之间约 5.7dp）。小标题的 `surface` 底板因此**删掉**了（不再需要遮挡） |
| 顶部大标题先不要改 | 规则没动（仍是 iOS 的"占视口最多"）。行高变了之后它**自然**与 iOS 对齐：切换点从"往下 0.7 行"变成"往下 2 行" |

**为什么这次和 §8.9 那条"由列宽推出"相反**：那条是 2026-09-26 为了压"上下间隔太大"定的
（当时行高 114dp、列距 51dp，比例 2.2）。现在换成 iOS 尺子后行高 105dp、列距 61dp，
比例 1.7 —— 与 iOS 的 94pt / 57pt（1.65）几乎相同，观感就是 iOS 那一版。

**测试**：`MonthFlowLayoutTest` 补两条 ——
`the label keeps a gap above the previous month's last row`（留白 ≥ 小标题整块，且顶边之上
还有 ≥ 4dp 空档）、`row height fills the viewport with six rows like ios`（6 行 = 视口，
且字号放大时行高跟着长）；"静止画面"那条也按新尺子重写（6 行月正好铺满、5 行月露下个月第一行）。
全量 **417** 条 JVM 单测通过。

## R6 补 2 · 年历去掉特效、月↔年 morph 的"跳变"、morph 分割线、单点进周态

**用户（2026-09-27）**："1. 年视图的点击特效不要了，当前选中的月份卡片背景也去掉。
2. 年视图切换到月视图的动画明显有问题，现在完全是跳变，没有过渡动画。
3. 月视图切换年视图的动画也不流畅。4. 月视图去掉我之前说的先点击选中，再次点击跳转
周视图。点击直接进入周视图就行了。5. 月视图和周视图的切换中日期的动画过渡基本可以，
但是一些分割线的动画还是不对。"

| # | 根因 | 修法 |
| --- | --- | --- |
| 1 | 年历卡片有 `ripple()`，选中月还有一层 `cardBgSelected` 淡底 | 涟漪改 `indication = null`；卡片底**一律不画**（选中月只靠标题用 `primary` 区分） |
| 2 / 3 | **月层的可见性判据写错了**：`visible = mode == MONTH && !morphing` —— 一进年 morph 月层立刻变透明，而年层要 `p > 0.25` 才开始淡入 ⇒ **中间那段整屏空白**（抓帧确认过：连续两帧全白），最后月视图"啪"地出现 | `visible = (mode == MONTH && !weekMorphing) || yearMorphing`：年 morph 期间月层留在场上，与年层按 `1 − yearOpacity(p)` / `yearOpacity(p)` **交叉淡入**（两条曲线和为 1）；周 morph 期间月层仍然让开（否则与 morph 层重影） |
| 3（观感） | 年 morph 把**整条流**（含相邻月份）一起做仿射变换 ⇒ 邻月被压成一片横向涂抹 | 变换下沉到**每一块**：只有"顶部那一块"缩进/长出年历卡片，其余块按 `1 − clamp01(p·3)` 快速淡出 |
| 2（另一半） | `yearMorphing` / `weekMorphing` 在 `scope.launch` 里才置位 ⇒ 有一帧"`mode` 已翻、标志位还没置"，两层都不盖 | 标志位改成**与 `mode` 同一帧**同步置位（三个入口：`openYear` / `backToMonth` / `openMonthFromYear` / `setWeekMode`） |
| 4 | 2026-09-26 定的"先选中、再点一次才进周态" | 改回**点一下直接进周态**（选中日同时更新），与 iOS 一致。`onFlowDayTap` 仍然显式判 `mode`（月流常驻、透明度 0 不挡命中测试） |
| 5 | morph 层是**一行一个 `CalendarDayGrid`**，而 `CalendarDayGrid` 里"这一行画不画分割线"的条件是 `row > 0 \|\| dividerAboveFirst` —— 单行调用时 `row > 0` 恒假、`dividerAboveFirst` 又没传 ⇒ **所有分割线在 morph 第一帧就整条消失** | morph 的每一行都传 `showDividers = true` + `dividerAboveFirst = row.showDivider`，与连续流里"本块第一行不画、其余都画"逐字一致 |

**实测（AVD，逐帧抓图）**：
- 月→年：月层与年层交叉淡入、顶部那一块缩进"9月"卡片、邻月快速淡出，**没有白屏**；
- 年→月：网格从被点的卡片长出来、年历同步淡出，收尾正好接到真实月视图；
- 月→周：分割线**跟着行一起移动**（此前第一帧就没了），→ 周态收尾与真实周层一致；
- 单点 9月15日 直接进周态（`uiautomator dump` 里出现周条）。

全量 **417** 条 JVM 单测 + `assembleDebug` 通过，编译零警告。

## R7 · 月↔年 morph 照 iOS 重写：网格**重排**（不再压扁）+ 方向修正

**用户**："动画的修复，月视图切换年视图、年视图切换月视图，这两个动画先改。
月视图和周视图的切换等月和年的切换改好之后再改，等我确认。"

### 改法：新增 `YearMonthMorph.kt`（照着 iOS `YearMonthMorphView`）

| | 旧（R6） | 新（R7） |
| --- | --- | --- |
| 本月网格 | 把月历流那一块做**仿射变换**（`scaleX/scaleY`）—— 文字被压成涂抹 | 画在**插值出来的矩形**里，格子按 `rowCount` 行 × 7 列**重排**，字号在 `miniDayFont`（≈9-14）与月格字号（20）之间插值 —— **日期始终清晰** |
| 图层 | 复用月历流的块 | 独立图层：年历 + 网格 + 标题/星期栏 + 上下两条 peek，**月历流在 morph 期间整体隐藏** |
| 大标题 / 星期栏 | 用流的钉住层按曲线淡出 | morph 层自己画，位置不动、按 `late = ((1-p)-0.55)/0.45` 淡入 |
| 下个月那一条 | 靠流本身露出来 | 显式画（小标题 + 能看见的每一行）：月→年迅速消失 / 年→月跟着淡入（iOS 的 `peekFadesIn`）|
| 源卡片 | 会被年历再画一遍（双重影像） | `YearCanvas(hiddenMonth = 本月)`，与 iOS 的 `YearPageView(hiddenMonth:)` 一致 |

**帧率顺带变好了**：morph 期间月历流不再参与（不再每帧重组 ~100 个日期格），
模拟器实测 **17.1ms/帧（≈58fps）**，此前是 33ms/帧（≈30fps）。

### 用户复测又抓到一条：**横向原点没传下去**

用户："月视图切换到年视图的时候，计算位置比实际位置偏左。年视图到月视图的动画的起始
位置计算也不对。" —— 一针见血：`drawDayCell` / `drawCellDividers` 是以"网格左上角"为
原点画的（`cellCenterX(col, cellW) = (col+0.5)·cellW`），而我在 morph 里**没有把
`grid.rect.x` 加回去** —— 于是整块网格永远贴在最左边、只有宽度在缩：
月→年看起来"往左跑了"，年→月则"从左边长出来"而不是从卡片长出来。
修法：把网格左边缘 `gridLeft` 一路传给格子 / 分割线 / 小标题（相邻月那两条 peek 传 0，
它们本来就铺满整宽）。截图 `docs/ui-audit/r7-1…r7-3`（重拍）。

### 用户复测第 3 条：日期**不裁在钉住栏之下** + 进月态**乱选 1 号**

用户："只要计算选中月的日期的动画，其他日期的动画是不需要的，特别是前一个月的日期……
动画覆盖在顶部固定栏之上，导致在动画结束的时候，原本应该在固定栏下方的日期突然消失。"

两处一起修：

1. **morph 层的绘制没有裁在钉住栏之下**：连续流的内容是 `height(viewportH) + offset(headerH)
   + clip` —— 也就是"钉住栏以下"才画；morph 层却是**想画哪儿画哪儿**。于是
   "上个月露在视口里的那几行"画到了大标题与星期栏上面，而动画收尾换回真实图层时
   那些日期按内容区被裁掉 —— 用户看到的"突然消失"。现在整块网格 / 两条 peek / 小标题
   一律 `clipRect(top = 钉住栏底部)`，与连续流逐像素一致。
   顺带：上个月那几行**只在源矩形之上确实还露着时才画**（年→月时目标月已被拨到静止位置，
   上个月整块都在内容区之上，本来就一行都不该画）。
2. **`openMonthFromYear` 乱选 1 号**：iOS 的 `HomeViewModel.selectMonth(_:)` 只写
   `monthPage`，**不动 `selectedDate`**；我在 Android 里顺手把选中日设成了该月 1 号，
   于是每次从年历进月视图都会在 1 号上冒出一个"选中"效果，而那天从没被选过
   （用户第 4 条反馈）。现在与 iOS 一致：选中日保持原样，若它不在当前显示的月里，
   月视图**没有任何高亮**（顶栏右胶囊照实显示具体日期）。

实测：月→年**首帧与点之前逐像素相同（0 个像素差异，含滚到半月位置的情况）**；
年→月网格以被点卡片为中心展开、顶部再无越界日期；从年历点「10月」后月视图无高亮、
点「9月」后高亮回到真实的 9月27日。

### 用户复测第 4 条：交班"闪一下" + 年历里高亮的是**今天**不是选中日

1. **morph 画的格子与真实日期格差 4~11dp** —— 交班（morph ↔ 真实图层）那一帧整片日期
   会跳一下，用户："最后会闪一下"。
   量出来的数（AVD，行高 105dp 的格子）：真实格子「日号中心 = 行顶 + 41.3dp、
   农历中心 = +75.3dp」，而我用的是 `CalendarLayout.contentTop / lunarCenterY`
   （那是 iOS `DayDraw` 的公式）→ +45 / +64.5，**差 4~11dp**。
   根因：Android 的真实格子是 `CalendarDayCell` 的**组件布局** ——
   `Column[圆, 农历 Text, 标记]` 居中后再 `+LUNAR_TIGHTEN/2`、农历自己 `-LUNAR_TIGHTEN`，
   而农历 Text 的**盒子高度**（继承 `LocalTextStyle`，比字号高得多）才是决定性的。
   修法：morph 侧改成**照 `CalendarDayCell` 的结构算**（`cellLayout()`），农历盒子高度
   用同一个 `LocalTextStyle` + `TextMeasurer` 量一次、再按字号比例缩放。
   **验证**：把慢放临时拉到 30s，抓 morph 尾帧（p≈0.02）与交班后一帧比对 ——
   **0 个像素差异**（此前是十几万）。这个临时改动已还原（仍是 3s）。
2. **年历里的实心圆画的是"今天"**：`YearCanvas` 只认 `todayKey`，没有 `selectedKey`
   —— 用户："年视图下，选中日期不对，没有变为真实的选中日期，而是一直都是选中的今天。"
   修法：与月格、与 iOS `DayDraw` 同一套优先级 —— **选中 = 实心圆 + onPrimary**、
   **今天（未选中）= 圆环**、有日记 = 加粗 + 主色。实测：选中 9/17、今天 9/27 时，
   年历里 17 是实心圆、27 是圆环（截图 `docs/ui-audit/r7-5`）。

## R8 · 年↔月两端的"抖/闪" + 月↔周的内容块位置

### 年↔月：两端各用**不同的排版模型**，中间没有插值

- 月侧（`t = 1`）是 `CalendarDayCell`：`Column[圆, 农历, 标记]` 居中 + 3dp、农历 −6dp；
- 年侧（`t = 0`）是 `YearCanvas` 的迷你月：日号**直接居中在格子里**，圆半径
  `min(格宽, 格高) × 0.42`，选中/有日记加粗，标记挂在圆下方 `0.86 × 半径`。

morph 全程只用了**月侧**那套 → 到年侧端点就与真实年历对不上：
月→年收尾"抖一下"（用户），年→月起手"整屏闪一下"（用户：那张卡片的迷你月被
`hiddenMonth` 隐掉、换成我画的格子，两者不一样就闪）。
修法：按 `t` 把两套模型插值（`miniBlend = 1 - t`）—— 位置、半径、标记位置、字重一起插。
**验证**（30s 线性慢放）：年→月起手帧 vs 年历静止，整屏仅 **5450 / 3655680（0.15%）**
像素差异（基本都是年层自身 1% 缩放带来的边缘位移），卡片区仅 **860**。

### 月↔周：当日内容块**没有布局偏移**，全程高了 110dp

iOS `MonthWeekMorphView` 是 `content().offset(y: contentOffset)`，其中
`contentOffset = selectedY + weekStripH`（**布局**偏移）。Android 只写了
`graphicsLayer.translationY = (selectedY - headH)`，布局仍在 `y = 0` —— 于是整个当日
内容（"9月15日 周二 丙午年八月初五" + 空态插画）在 morph 全程高了
`headH + weekStripH = 110dp`，标题正好压在周条上（用户："周视图的日期栏出现时机和
位置问题"）。修法：补上 `.offset(y = contentTop.dp)`，位移仍取与终态之差 ——
与 iOS 逐字一致。**验证**：月→周尾帧 vs 周态静止 **0 个像素差异**；
周→月起手帧 vs 周态静止 **0**、尾帧 vs 月视图 **0**。

## R22 · 去掉月/周视图日期格的点击涟漪

用户："月视图和周视图的点击特效有点多余了，去掉吧。"

日期格改成 `clickable(indication = null)`。理由（写进代码注释了）：
格子是**高频、密排**的点按目标，涟漪会出现两种难看的情况 ——
① 与"选中日"的实心圆叠加，像点出了两个圆；
② 连续流里滚动时指尖划过的格子会各亮一下。
反馈仍在：松手瞬间选中圆就移到新日期。`interactionSource` 保留（`clickable` 需要它派发
press/release，TalkBack 的"已激活"播报也走它），只是不再画出来。
年历卡片更早就已经是 `indication = null`（点卡片的缩放动画本身就是反馈）。

## R21 · 周条翻周"先跳回再跳到新周"：落定方向的**负号丢了**

用户："周视图的左右切换还是有点问题……滑动之后，手离开，周视图会跳回前面，
然后再跳到新的周。这里你是不是什么参数设置有问题？"

确实是参数问题 —— **落定动画的目标值少了负号**：

```kotlin
// 错：step = +1（翻下一周）时内容往**右**飞一页
settleAnim.animateTo(targetValue = step * pageDp - dragDp, …)
// 对：内容要**左移**一页（新的一周从右边进来）
settleAnim.animateTo(targetValue = -step * pageDp - dragDp, …)
```

iOS 的 `DragPagePager` 写的是 `let delta = goNext ? -pageSize : pageSize`；
年份翻页那份 `commitPx = -step * pagePx` 也是同一约定 —— 我写周条时把负号抄丢了。
于是：拖动时内容**跟手左移**，松手后却往反方向飞一页，落到终点才换数据，
观感就是"跳回前面，然后再跳到新的周"。

顺带补上 iOS 的另一条：**新手势开始时先 `settleAnim.stop()`**，并把动画剩余量并进
手指位移（保持 `dragDp + settleAnim` 这个总位移连续）。不停的话，上一次的落定动画会与
手指的位移叠加 —— 这就是"不跟手"。

修完实测（真机日志）：

```
week settle: dragDp=-227.65 step=1 → 落定到 -392.73（= -pageDp，与拖动同向）
week commit: dragDp + anim = -227.65 + (-165.07) = -392.73（正好一页，提交前后像素等价）
```

行为验证：连续左滑 3 次 9/14 → 9/21 → 9/28 → 10/5 周，选中日 9/15 → 9/22 → 9/29 → 10/6；
右滑逐周回退；小幅慢拖回弹不变。

## R20 · 全面清理：死代码 / 冗余资源 / 性能；并确认动画**没有被限帧**

用户："进行彻底的代码检查……精简代码，优化性能，删除无效冗余资源之类的"、
"过渡动画帧率尽可能跑满，不要手动设定帧率上限，由系统决定"。

### 帧率：**没有、也不应该有上限**

逐项核过，全仓库**不存在**任何限帧手段：没有 `Surface.setFrameRate` /
`preferredDisplayModeId` / `fixedFrameRate` / `Choreographer` 手工节拍 / `withFrameMillis`
固定步长，manifest 与 theme 里也没有 `windowRefreshRate` 之类。三段 morph 全部由
`Animatable` 驱动 → 走 Compose 的**帧时钟** → 由系统（与屏幕当前刷新率，含 LTPO）决定每帧
何时到来；进度只在 `Modifier.offset {}` / `graphicsLayer {}` / Canvas 的 draw lambda 里读，
不触发重组，所以动画期间每帧只做绘制。唯一的 `withFrameNanos` 是 `-morph-log` 打开时的
**被动计数**（不改节奏），`MonthFlow` 里的 `delay(700)` 是滚动指示条的自动隐藏延时。

### 删除的死代码 / 冗余（逐条 grep 复核过引用数）

| 类别 | 内容 |
| --- | --- |
| 零引用常量 / 函数 | `QUICK_SETTLE_DURATION_MS`、`PORTRAIT_MIN_AREA_H`、`CELL_H_PER_COL_W`、`COMPACT_LABEL_DROP`、`MINIMUM_ROW_HEIGHT`（与 `MIN_CELL_H` 重复的 44）、`M3_EMPHASIZED`、`Rect.maxX`、`landscapePaneHeight`、`WEEK_PAGE_THRESHOLD`、`SLOW_MORPH_DURATION_MS`（`DebugFlags` 里那份才是活的） |
| 被取代的旧实现 | `CalendarLayout.yearMonthMorph` + `YearMonthMorphState`（生产已换成 `yearMonthGridMorph`，只有旧单测在喂它）+ 4 条对应旧测试；`portraitAreaHeight`（旧名转发） |
| 死状态 / 死方法 | `HomeViewModel.onMonthChanged`、`resetToToday`、`State.selectedDiary`（只写不读）；`HomeScreen.fadeProgress`、`val monthTitle` |
| 无用参数 | `YearCanvas(showLunar)`、`MonthFlowLabel(bandH)`、`monthAreaHeight(rows)`、`cellHeightFor(rows, preferred)`、`yearMonthGridMorph(rowCount)` |
| 孤立注释 | 指向已删声明的 5 处 KDoc（其中一处**未闭合**，把后面所有声明吞进注释里 —— 编译报错才暴露） |
| 资源 | 10 个旧版启动图标 PNG（`minSdk 33` + `mipmap-anydpi-v26` 自适应图标 ⇒ 永不参与解析）、空 `res/drawable/` 目录、FileProvider 里两条无人使用的路径（`exports` 暴露整个 cacheDir、`images` 无消费方） |

### 性能（按"每帧/每次重组"的实际开销排序改）

1. **morph 期间的文本测量**：字号原先逐帧连续插值 ⇒ `TextMeasurer` 的缓存键（含 `TextStyle`）
   每帧都是新的 ⇒ 每帧全 miss，31 格 × 2 次 ≈ 60 次文本整形/帧。现在字号**量化到 0.5dp**
   并把缓存从 256 提到 2048（量化后键规模约 1500）。
2. **农历换算**：`ChineseCalendar` 每次都要新建对象 + 天文推算，而结果只与日期有关。
   新增 `Lunar.dayLabelCached`（LRU 1024，按 `toEpochDay()` 键），绘制路径一律走它 ——
   此前"点一次日期要让 60~70 个格子各算一次 ICU"。
3. **组合期解析 JSON**：`ContentFlatten.parseContent` 原本每次重组都跑（长日记 5~30ms，
   主线程），改成 `remember(block.contentJson)`。
4. **`DateUtil.dayKeyOf`**：`String.format` → 手写零填充（月视图一次重组 240~320 次、
   年历一次绘制 365 次）。
5. **`MonthFlowLayout.of` 的缓存**：行高先量化 0.5dp 再当 key（resize / 分屏 / 字号动画期间
   行高逐帧变化，原本每帧重建 2412 个块），缓存满时改成淘汰最老一条而不是 `clear()` 全清。
6. **每帧/每格的小分配**：`SelectedMorphRow` 的 `listOf(row.week)`、周态的 `weekOf(...)`、
   每行的 `indices.filter`、每格的 `ripple(...)` 与 `RoundedCornerShape`、`dayTitleFor` 与
   无障碍串 —— 全部 `remember` 或提到文件级常量。

### 顺手修掉的两个真 bug

- **小标题的透明度被覆盖**：`MonthFlow` 里同一 `graphicsLayer` 内 `alpha` 被赋值**两次**，
  后一行（旧的 iOS 公式 `1 - clamp01(p * 2.2)`）把用户拍板的"直接出现"那版覆盖掉了 ——
  这正是"周→月小标题还是最后出现"的残留原因。
- **`DateUtil.dayKeyOf`** 改写时忘了年号也要补零（`DateUtilTest` 的 `0999-12-31` 挡住了）。

## R19 · 周条只能翻一次：`pointerInput` 协程里捕获了**组合期的值**

用户："周视图……左右切换不能一直切换下去？只能切换一次似乎？"

日志实测（真机 MEIZU 21，`Log.w`）：

```
pageWeek delta=1 selectedKey=2026-09-15 selectedDate=2026-09-15 next=2026-09-22  ← 第一次正确
pageWeek delta=1 selectedKey=2026-09-22 selectedDate=2026-09-15 next=2026-09-22  ← selectedKey 已更新，闭包里的 selectedDate 还是旧的
pageWeek delta=1 selectedKey=2026-09-22 selectedDate=2026-09-15 next=2026-09-22
```

根因：周条的翻页手势跑在 `pointerInput(stripWidth)` 的协程里，**那个块只在第一次组合时
启动一次**（key 不变），它捕获的是当时的 `pageWeek` 闭包 —— 而 `pageWeek` 用的是组合期算出的
**值** `selectedDate`（`remember(selectedKey) { … }`），那是个普通 `val`，被固化在闭包里。
于是每次翻周都算"进入周态那一天 + 7"，第二下之后永远原地踏步（连"右滑"也只会回到
`进入周态那天 − 7`，实测正是 9/15−7=9/8）。

修法：`pageWeek` **现读状态**（`DateUtil.parseDayKey(selectedKey)`），不再用组合期的值；
`onPageWeek` 另外用 `rememberUpdatedState` 包一层，保证手势回调拿到的是最新的那个函数。
（年份翻页没这个问题：`pageMonth` 读的是状态 `monthKey`，本身就是活的。）

真机实测（MEIZU 21）：连续左滑 4 次 → 9/14 → 9/21 → 9/28 → 10/5 → 10/12 周，选中日
9/15 → 9/22 → 9/29 → 10/6 → 10/13（保持星期几，与 iOS 一致）；右滑逐周回退。

> 附带发现：这台机器上 `Log.d` **不出现在 logcat**（被 ROM 过滤），`Log.w` 正常 ——
> 以后在真机上排查用 `Log.w` / `Log.i`。

## R18 · 年→月：本月小标题跑到顶栏上方闪一下（R17 放开了网格、把它也放出去了）

用户："从年视图到月视图的时候，原本不应该有动画的小标题（当前月的）也有了动画，
更关键的是小标题的动画被绘制在顶栏上方，导致小标题移动到最终位置，然后立即消失。"

因果很清楚：**R17 为了修"第一排数字被切"把网格的裁剪放开了，顺带把网格上面那个
"本月小标题"也放了出去**。而真实月视图里，**顶部那一块的**小标题本来就画在内容区上沿
之上（它借用上个月最后一行的留白，被钉住栏盖住、也被内容层裁掉）——**本来就不可见**，
iOS 的年 morph 干脆不画它。于是年→月时它出现在顶栏上方、一路挪到终点，交班后又被真实
图层藏起来，就是用户看到的"多了个动画 + 走完立刻消失"。

修法：**网格保持不裁**（R17 要的），只把**小标题**的 `drawText` 关进
`clipRect(top = 内容区上沿)`。这样两种情形都对：
静止进入时它在内容区之上 → 不画（与真实月视图一致）；
流水停在月中、小标题本来就露在内容区里时 → 照常画。

## R17 · 周条左右滑动改成 iOS 那套"一页一翻"；月→年网格不再被标题挡

用户两条："周视图下周不能左右滑动切换日期，滑动之后都会自动跳回到之前的日期……
iOS 滑动之后会自动切换选中的日期"、"月→年过程中，只有第一排的数字的上半部分会有一点
遮挡，动画结束后又恢复完整"。

### ① 周条：`LazyRow` + 官方吸附 → **一页一翻的 pager**（对齐 iOS `DragPagePager`）

旧实现是 `LazyRow` + `rememberSnapFlingBehavior`：惯性一次能翻好几周（实测一次快滑
直接跳 **4 周**、慢拖 20% 又回弹），与 iOS "滑动即切换一周、选中日跟着走一周"不是一回事。
现在复用仓库里已有的 `CalendarLayout.DragPagerMath`（它本身就是 `DragPagePager.swift`
的移植）写成横向三页 pager（上一周 / 本周 / 下一周），松手按"位移或速度"决定去留，
落定后 `onPageWeek(±1)` 把选中日挪一周、再清零偏移 —— 与 iOS `onPageChange` → `vm.select(+7天)` 同语义。

实测（uiautomator 读周条文本 + 选中日）：

| 手势 | 结果 |
| --- | --- |
| 小幅慢拖 120px | 回弹，不变（正确） |
| 大幅慢拖 700px | 9/14 周 → **9/21 周**，选中 9/15 → **9/22**（正好一周） |
| 快速 fling | 只翻一周（旧版会跳 4 周） |
| 向右 fling | 回一周（9/7 周 / 选中 9/8） |

### ② 月→年：网格画在标题**之上**、只有"上个月那几行"才裁

用户这次说清楚了：**是缩小的过程中、第一排大数字的上半部分被切一点**，动画结束又完整。
根因是我在 R7 为"挡上个月那几行"加的 `clipRect(top = 钉住栏底部)` —— 它把**本月网格**
也一起裁了，而网格在缩小过程中会往上走进标题带。iOS 的 `YearMonthMorphView` 里网格是
ZStack **最后**画的那个（在标题之上、不裁）。

改法：`Canvas` 移到 `MonthMorphHeader` **之后**（同 iOS 顺序），`clipRect` 只包住
"上个月那几行"（那才是必须挡的：收尾换回真实图层时流是按内容区裁的）。
实测：动画中段本月网格的行可以画到标题之上（未被切），收尾与真实图层一致。

## R16 · 周视图跳错周（真正的根因）+ 年层仍被切的最后一层裁剪

用户："月视图到周视图的时候，有概率……动画正确，但结束后周视图会突然跳变为另一周"、
"月到年的第一排还是有被切的问题"。

### ① 周条那一周：**由选中日直接推**（对齐 iOS `weekKey(selectedDate)`）

旧实现是"在**顶部大标题那个月**的 `displayedWeeks(monthFirst)` 里找选中日，
找不到就退回 `weeks.first()`"。连续流里"大标题的月份"和"点中那天所在的月"可以不一致：
流滚到大标题已是 **10 月**、屏幕上还露着 9 月下旬那几行时点 **9/27** ——
10 月的 6 行窗口是从 9/28 开始的，**里面根本没有 9/27**，于是退回 10 月第一周，
周条就跳了。这就是"有概率"的来源（取决于点的那天落不落在当月窗口里）。

修法：`CalendarGrid.weekOf(selectedKey, weekStart)` —— 选中日所在的那一周（可能跨月），
与 iOS 的 `CalendarLayout.weekKey(selectedDate)` 同一条语义。实测：点露出来的 10月1日
→ 周条正确显示"9月28日 周一 那一周"。单测
`week of a selected day follows the day even across months` 钉住三种情形
（跨月周、周日为一周之始、非法 key），并显式断言"10 月的窗口里没有 9/27"（旧写法出错点）。

### ② 年层最后一层裁剪：**是我自己加的那个 `clipToBounds()`**

R15 把顶栏改成悬浮后，年层已经能画到胶囊那一带；但它仍然被"TabContent 的
`padding(Scaffold 的 innerPadding)` + 我自己加的 `clipToBounds()`"卡在**状态栏下沿**。
iOS 的模型是内容可以一直画到**窗口顶部**（从状态栏底下穿过）—— 去掉那层裁剪即可。

实测（3s 慢放，量状态栏以下、胶囊那一带的墨迹）：动画中段 **3171 → 12256 / 13285**，
即年层的放大部分确实画到了窗口顶部；收尾回到 3000（只剩"今天"胶囊）。

## R15 · 曲线对齐 iOS + 顶栏改成**悬浮胶囊行**（日历铺满整屏）

用户两条："动画速率全都太快了，相较 iOS 版来说。也可能是动画曲线不一致的问题"、
"Android 版本为什么 material 3 就一定要顶栏不透明？为什么不能是也做成悬浮的？谷歌有规定吗？"

**① 曲线**：之前误用了 M3 的 `emphasizedDecelerate (0.05, 0.7, 0.1, 1.0)`，
而 iOS 是 `.timingCurve(0.2, 0.75, 0.3, 1.0, duration: 0.6)`。实测差别：

| | 走到"一半进度"所需时长 |
| --- | --- |
| iOS | **15%** 时长（≈90ms） |
| M3 emphasizedDecelerate | **6%** 时长（≈37ms） |

所以旧曲线是"开头一瞬全到位、后面大半时间几乎不动" —— 既显得快，又把所有后段淡入
（小标题、年层）压成瞬间。现在统一用 `CalendarLayout.IOS_MORPH_CURVE`，三个 morph 共用。

**② 顶栏**：查证结果 —— **Material 3 没有"顶栏必须不透明"这条规定**（`TopAppBar` 的颜色
可配、官方有 on-scroll 行为与透明变体、M3 Expressive 直接提供 `HorizontalFloatingToolbar`），
而且**我们自己的规范** `docs/android-design/02-adaptive-and-navigation.md` §3.4 写的就是
"首页｜**无 `TopAppBar`**：只有一行 64dp 的胶囊行"。之前实现成
`Column { 顶栏; 日历区 }`（顶栏**占位** + 日历区上沿 `clipToBounds()`）是我抄近路，
不是平台限制。现在改成：

```
Box(整屏, 唯一裁剪边界) {
    Box(日历区：y = 顶栏高、高 = bandH，几何一行未改) { 月流 / 周层 / 年层 / morph 层 }
    HomeTopBar(...)   // 悬浮在最上层，本身无背景
}
```

月视图**逐像素不变**（改前/改后截图差异 1381 px，全在状态栏的时间/电量那块），
而年↔月 morph 里年层放大后溢出的那一条现在能画到胶囊那一带（截图里能看到放大的
"2026年"标题与月份标签出现在顶部），不再被日历区上沿切断。

**已知仍与 iOS 不同**：底部导航栏还是 Scaffold 的 `bottomBar`（不透明、占位），
所以年层往**下**溢出的那一部分仍会被切；要做成 iOS 那样"内容穿到底栏底下"，
需要把底栏也改成悬浮（同理，规范里也还没写）。

## R14 · 小标题"最后才出现"：层级错了 + 曲线太晚

用户："现在周视图切换到月视图的时候，小标题还是最后出现的。"（此前两次也是这条）
实拍逐帧定位到**两个叠加原因**：

1. **层级**：morph 里小标题画在日期行**之前**（= 被行盖住）。真实流 `MonthFlowBlock`
   是先网格、后小标题（小标题在上层）。所以 morph 期间移动的日期行会整块盖住小标题，
   等行让开才露出来 —— 看着就是"最后才出现"。→ 把小标题移到日期行之后（仍在当日内容之下）。
2. **曲线**：`1 - clamp01(p * 2.2)`（iOS 那条）让它在 `p < 0.45` 才开始出现、`p < 0.2`
   才看得清。→ 改成 `clamp01((1 - p) * 2)`：**`p ≤ 0.5` 全不透明**、之上快速淡出，
   也就是"周→月时它一开始就在、月→周时它很快消失"。

实测（3s 慢放，按时间抓帧，量小标题带的墨迹）：改动前 0.25s/0.6s/0.95s 三帧**全为 0**
（被行盖住），1.3s 才见到一点点；改动后 **0.6s 就是完整的最终形态**（真实速度 600ms
≈ 120ms 处），与稳定态逐像素一致。

## R13 · `MONTH_GAP` 之后：morph 里"下个月那一条"还在用 `block.bottom`

用户："加大了月份之间的间隔之后，在年视图切换到月视图的时候，下一个月的日期和小标题在
最后会发生跳变，原因很可能是你没有把新的间隔放到动画位置计算中。" —— **完全正确**。

`YearMonthMorph` 里"下个月那一条"（peek）的 y 原来写的是 `block.bottom`（本月最后一行的底），
这在"块与块零间距"的年代等于下个月的块顶；加了 `MONTH_GAP = 10dp` 之后它**少了 10dp**，
peek 于是画高 10dp，morph 收尾换回真实图层时下个月的日期与小标题就跳一下。

修法：改用 `nextBlock.top`。实测（把 morph 停在 p=0 再与真实图层逐像素比）：

| | 整屏差异 | 底部 peek 区 |
| --- | --- | --- |
| 旧写法 `block.bottom` | 39 520 px | 7 898 px |
| 新写法 `nextBlock.top` | **0** | **0** |

顺带核对：上一块的 `prevBlock.bottom` 是**它自己最后一行**的底（用来判断"上个月还有没有
露在视口里"），语义正确，不用改；peek 的每一行用 `prevBlock.top + i * rowH` 绝对定位，
本来就不受影响。

## R12 · 年→月"最后闪一下 + 日期位置偏移"的**真正**真凶：`p = 0` 那一帧的提前返回

用户第二次报同一条（这次给了关键提示）：

> "年视图到月视图还是存在最后有一个闪烁的问题。并且……闪烁之后，界面上的日期位置、
> 小标题也会有一定的偏移。"

按这个提示用"把 morph 停在 p=0"的办法比对（临时 `delay(3000)` + 慢放 15s），
**跨月**（年历里点一个与当前月不同的月份）时复现：morph 的 p=0 帧里**日期整片不见**，
真实图层才有 —— 差 65 652 像素。

日志（`ym-end monthKey=202610 offset=846495.0 rest=846495.0`）说明流的偏移是对的，
于是把 morph 内部的量打出来，几何也全对（`source.y=0`、`bodyTop=306`、`rect.y≈9`）。
**问题出在我自己加的一行"优化"**：

```kotlin
val p = progress()
if (p <= 0.0001f) return@Canvas   // ← p = 0 这一帧整块网格什么都不画
```

`p = 0` 正是年→月的**最后一帧**，这一帧本该把整月网格按源矩形画满；提前返回让它变成空屏，
下一帧真实图层才把日期补上 —— 用户看到的"闪一下 + 位置偏移"就是它（日期与小标题同属
内容层，所以一起偏移）。morph 层本来就只在 `yearMorphing` 期间挂载，这个 guard 毫无必要。
**删掉**即可。

验证：
- 跨月：morph p=0 帧 vs 真实图层 **0 个像素差异**（修复前 65 652）；
- 同月：0（本来就对）；
- 普通速度录屏逐帧统计日历区墨迹量：**0 帧骤降**。

（R9 修的是另一件事 —— 交班后 `graphicsLayer alpha=0` 那一层要重录导致的 2~4 帧空白；
两条都是"某一帧内容不见了"，但成因与修法不同。）

## R9 · 年→月"最后闪一下"的真凶：`graphicsLayer { alpha = 0f }` 那一层被整层跳过

用户第三次报同一条："年视图切换到月视图还是在最后会闪一下。"
这次用 **`screenrecord` 录屏 + 逐帧分析**（不再靠 `screencap` 抓单帧，那抓不到 16ms 的
问题帧），在 600ms 的真实速度下抓到了：交班后有 **2~4 帧**，大标题与星期栏都在、
**整个日期区是空的**（既不是 morph 画的、也不是真实图层画的），随后日期又冒出来 —— 
这就是"闪一下"。

**判定性实验**：临时把连续流的 `contentAlpha` 固定成 `1f`（永不透明）→ 空白帧 **0**。
于是根因确定：`graphicsLayer { alpha = 0f }` 会让这一层**被整层跳过**（不再录制显示
列表），morph 结束把 alpha 翻回 1 时，重新录制要花几帧 —— 那几帧日期区就是空的。

**修法**：把"隐藏"从 `0f` 换成 `HIDDEN_ALPHA = 0.004f`（对比度约 1/255，
四舍五入后最多差 1 个灰阶，肉眼不可见），这一层始终在画，翻回 1 时是即时的。
验证（录屏逐帧统计日期区墨迹量）：**月→年 0 帧骤降、年→月 0 帧骤降**。

顺带按 iOS 对齐一处小标题差异：iOS 的 `MonthFlowLabel` **自己按日期算 `isCurrent`**
（今天所在月 = 加粗 + 主色），morph 与流用的是同一个视图；Android 的 `isCurrent` 是入参，
morph 里漏传 → 小标题在交班时会突然由灰变蓝。现在 `MonthFlowMorphSource.Label` 带上
`key`，morph 里按 `todayMonthKey` 传 `isCurrent`。

## R10 · 月↔周的小标题：**有意偏离 iOS**，改成"有位移 + 与行同步淡入"

用户先是选了"小标题是突然出现的，没有淡入"，随后自己复核了 iOS：

> "对了，我看了一下 iOS 的版本，iOS 版就是小标题一开始就在最终位置的。那你不要参考
> iOS 版了，这也是之前你说两个版本逻辑一致的原因。"

确实如此 —— iOS `MonthWeekMorphView` 把小标题钉在终点、只用 `1 - clamp01(progress*2.2)`
淡入，所以"逻辑一致"但观感就是"跳出来"。这次**有意不跟 iOS**：

| | 之前（= iOS） | 现在 |
| --- | --- | --- |
| 位置 | 钉在终点（screen y 恒定） | **锚到它那个月第一行**当前的 y（`morphRowY`，与行同一套公式）**再叠加 `LABEL_RISE = 24dp` 的落位位移** |
| 透明度 | `1 - clamp01(p * 2.2)`（很晚才淡入） | `1 - clamp01(p * 1.4)`（**与行同一条曲线**，标签和它的日期同时出现/消失） |

`p = 0` 时两项位移都归零 → `label.bottom - labelBandH`，与连续流的排版**逐像素一致**
（之前用"把 morph 停在 p=0"的办法验过 0 像素差，这条性质没有被破坏）。
实测（慢放逐帧截图）：小标题在 morph **中段**就已经淡入并在落位，不再等到最后才冒出来。

## R11 · 最终方案：**在两个月之间加真实距离**（`MONTH_GAP = 10dp`）

用户先后提了三条：① "稍微调高一点前一个月到小标题之间的间隔"；② "还需要再加大一点" +
"小标题也不要贴着分割线，你改了之后反而看着太挤了"；③ 最终给了正解：

> "我的意思是，你直接调整两个月之间的总距离不行吗？"

**对，前面两版都绕了**：把小标题往分割线挤（`LABEL_TUCK`，用户否掉）或把日期格内容整体
上移（`DAY_CONTENT_RISE`，会连周条一起改）—— 两者**都已回退**，日期格与分割线几何回到原样。
现在只在流的分块之间加一段真实距离：

```kotlin
cursor = bottom + CalendarLayout.MONTH_GAP   // MONTH_GAP = 10dp
```

小标题正好落在这段里，于是**上方和下方的空气同时成立**（实测，Pixel 10 Pro，截图逐像素）：

| | 原始 | 最终 |
| --- | --- | --- |
| 上个月农历墨迹底 → 小标题墨迹顶 | 6.7dp | **16.7dp** |
| 小标题墨迹底 → 分割线 | 4.7dp | **4.7dp**（没贴着） |
| 两个月之间的真实距离 | 0 | **10dp** |

连续性不受影响：`block.bottom`（最后一行底）与下一块的 `top` 之间隔着这一段；
可见块、静止偏移、`dominantBlockIndex` 全部按块顶算，自动带上间距。

测试跟着改语义（三条钉着"零间距"的老断言）：`rows inside a month are exactly one row apart`
（块内行距仍是 `rowH`，块之间 = `rowH + MONTH_GAP`）、
`the viewport at rest shows six rows or the next month's first row`、
`months are separated by the gap and the label sits above the divider`（新名字，同时钉住
"不贴分割线"）。

顺带修了一个**既有缓存缺陷**：`CalendarGrid.weeks` 的缓存写满是"满了就不再写入"，
于是后面所有月份都不命中，"同一月份返回同一实例"的约定就断了（被 `MonthFlowLayoutTest`
遍历大量月份后暴露）。改成满了先 `clear()` 再写。

### 小标题的位移动画：试了三版，最后用户拍板"直接出现就行"

（第三版是"与日期行同步淡入 `* 1.4` + 画到日期行上层"，用户回复："我的意思是，就让小标题
直接出现就行了。" → 已**全部撤销**，保持 iOS 行为：位置钉在终点、`* 2.2` 曲线、
小标题画在日期行之前。代码注释里写明了"不要再动这三条"，避免以后又被当成 bug 去改。）

### 附：早先两版的记录

用户先选"小标题是突然出现的，没有淡入"，于是做了（1）跟着自己那个月的第一行走、
（2）额外 24dp 落位位移 + 与行同步淡入。用户实测后说："小标题的问题没有修复。我觉得
干脆和 iOS 版一样，让小标题直接在最终位置算了。这样比跳变更好。"
→ **完全回到 iOS 行为**：位置钉在终点（screen y 恒定，只被裁剪边界扫过）、
`alpha = 1 - clamp01(progress * 2.2)`；`LABEL_RISE`、`morphRowY()`、
`MonthFlowMorphSource.Label.firstRowIndex` 全部删掉（不留死代码）。

证据：`docs/ui-audit/r11-1-label-gap-before-after.png`（上=原始 6.7dp，下=现在 16.7dp）。

### 单测抓到的两个真 bug（都已修）

1. **矩形插值方向反了**：`lerpRect(source, target, t)` 在 `t = 1`（月侧）给的是**目标** ——
   于是"p=0 时已经缩到卡片上"再反向长大，两个方向的起手帧全错。单测
   `year month grid morph relayouts the cells instead of squashing them` 钉住这条
   （端点必须分别等于源 / 目标矩形）。
2. **年侧行高不一致**：原来用 `rect.h / rowCount`，而年历里那张迷你月是**固定 6 行**
   排的（`cellH = grid.h / 6`）—— 收尾换回真年历时那张卡片会跳一下。现在行高在
   `target.h / 6` 与 `flowMetrics.cellH` 之间插值，两端都严丝合缝。

### 实测（AVD，`--ez slow-morph`，逐帧抓图）

- **月 → 年**：第一帧与屏幕逐像素一致 → 网格缩进「9月」卡片、年历同步淡入；
- **年 → 月**：网格从被点的卡片长出来、年历淡出，收尾正好接到真实月视图；
- **滚到半月位置再点年份**：第一帧仍与屏幕一致（不跳回整月）—— 源矩形取的是
  "屏幕上那一块网格的当前矩形"，靠 `FlowOffsetProbe` 把流的偏移漏给 morph 层；
- 截图：`docs/ui-audit/r7-1…r7-3`。

### 未做（按用户要求等确认）

**月 ↔ 周**的切换这一轮**没有动**（分割线那一条已在 R6 补 2 修过）。等月↔年确认之后再改。

### 已知遗留

- 日记页（阅读 / 编辑 / 多选 / 分享）**未开工**，差距清单见
  [`docs/android-design/07-ios-map-and-plan.md`](android-design/07-ios-map-and-plan.md) §3.1 的 P0-1…P0-3；
- 设计稿里日记编辑态的格式栏样式待定稿（用户 2026-09-26 反馈"还有点问题"）；
- 无障碍自定义动作：日历的「上/下月、上/下年」挂在**容器**上，日格本身没有
  「查看这一天的日记」动作（规范 §8 的这一条**有意延后**：日格的一次点击在月态是"进周态"、
  在周态才是"打开日记"，挂一个固定动作会和点击语义打架，等日记页那批一并处理）；
- 平板横屏的搜索筛选栏是 340dp、两组仍上下排列（规范 §1.3 原写的"左右并排"已按实现更正）。
