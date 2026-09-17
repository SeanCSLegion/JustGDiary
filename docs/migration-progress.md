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

## M1 —— 数据层（进行中）

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
| `data/DiaryRepository.kt` | 读 / 写 / 搜索 / 索引重建 / 日期重算 / 地区回填 / 图片清理；单并发调度器 + `BEGIN IMMEDIATE` |
| `app/src/test/resources/golden/*.json` | **真实 iOS 编码器产出的 golden file** |
| `tools/verify_sqlite_schema.py` | 用宿主 SQLite 真执行 schema 与查询的验证脚本（18 项断言全通过） |

### 验证证据

```
./gradlew testDebugUnitTest assembleDebug   →  BUILD SUCCESSFUL
  ContentFormatTest   15 tests  （含与 iOS golden file 的字节级比对）
  DateUtilTest        14 tests
  SearchSqlTest       13 tests
  FtsSegmentTest       7 tests
  DesignTokensTest     5 tests
  TOTAL: 54 tests, 0 failed

python3 tools/verify_sqlite_schema.py       →  18 项断言全部 PASS
  schema 表名/列名/索引名与 iOS 一致；FTS5 + CJK 切分可子串命中；snippet 产出 <hl>；
  不分词则查不到（证明切分必要）；搜索/总数/足迹/未记录判定/day_key 两阶段重算 均可用
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

### 尚未完成（下一轮）

1. **备份 / 导入（`BackupService` + zip）**：`.jdiary` 的流式导出与导入、
   `manifest.json`、`settingsJson` 的 DTO、`VACUUM INTO` 快照、整天 skip/overwrite 合并；
2. **媒体存储**：`BitmapFactory` 降采样（最长边 2048 / JPEG 85）、`img_<ms>.jpg` 命名、内存 + 磁盘缓存；
3. **DB 执行层测试**：目前 schema 与查询已用宿主 SQLite 验证，但 `SQLiteOpenHelper`、
   参数绑定、游标映射这三层还没有自动化测试（本机无 AVD；候选方案是 Robolectric 或
   一台真机，届时与 M3 一起做一次运行验证）。
