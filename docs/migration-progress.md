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
按已确认的决策 Q8，本轮不做安装运行验证，因此与 M3 一起做一次运行验证时优先补这三层。

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

### M6 起（下一轮）

按方案 §12 进入 **M6 足迹 + 搜索**：`FootprintDataService` 三级聚合（纯集合运算）、
自绘年度柱状图、地点清单树；FTS 搜索的 SQL 已在 M1 完成，这里接 ViewModel 与搜索页 UI
（关键词胶囊、时间/地点筛选、高亮、分页）。
