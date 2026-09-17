# JustGDiary

日记应用 **[JustADiary](../JustADiary)** 的 Android 版本。数据层与 iOS 版**严格互通**：同一套表名/列名/字符串取值/`content_json`/备份包格式，`.jdiary` 备份可以双向互导。

> **当前状态：M0–M9 的代码工作已完成**（详细的分里程碑记录见 [`docs/migration-progress.md`](docs/migration-progress.md)）。
> `./gradlew clean assembleDebug testDebugUnitTest` 通过，**352 个 JVM 单测**全绿，
> 另有 2 个脚本做跨端与 SQL 的独立校验。**尚未在真机/模拟器上安装运行过**（本机无设备与 AVD，
> 见文末"已验证 / 未验证"）。

## 关键约定

| 项目 | 约定 |
| --- | --- |
| 应用包名（applicationId） | `com.cov.justdiary` |
| 与 iOS 版的关系 | 包名与 iOS 版**完全一致**；debug 也**不加** `applicationIdSuffix` |
| minSdk / targetSdk / compileSdk | 33 / 36 / 37 |
| 设计语言 | **Material 3 + 动态取色（Material You）+ 原生自适应导航**；不模仿 iOS 的 Liquid Glass，界面按 Google 规范重新设计，只对齐功能、交互与动画 |
| 第三方 UI 库 | **零**（不引入任何玻璃/模糊库；柱状图等自绘） |
| 默认分支 | `main` |

## 技术栈

| 项 | 版本 / 说明 |
| --- | --- |
| Kotlin | 2.4.20（**AGP 9 内置 Kotlin 支持**，不应用 `org.jetbrains.kotlin.android`） |
| AGP / Gradle | 9.4.0 / 9.7.1（wrapper 走华为镜像 + sha256 校验） |
| Compose | BOM 2026.09.00，material3 1.4.0 |
| JDK | 17（本机用 Android Studio 自带的 JBR 25 编译，见下） |
| 数据库 | `SQLiteOpenHelper` + **裸 SQL**（不用 Room：表结构要与 iOS 逐字一致的契约） |
| 后台任务 | WorkManager（每日提醒）；无精确闹钟权限 |
| 定位 | 运行时探测 GMS：有 Play services 走 `FusedLocationProviderClient`，否则退原生 `LocationManager` |
| 单测 | JUnit4 + **Robolectric**（数据层与备份链路的运行时验证） |

## 构建与验证

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # 本机无系统 JDK

./gradlew clean assembleDebug testDebugUnitTest   # 编译 + 352 个 JVM 单测（含 15 个 Robolectric 用例）

# 两个独立校验脚本
python3 tools/verify_sqlite_schema.py    # 从 Kotlin 源码抽出 DDL，用宿主 sqlite3 真跑一遍（18 条断言）
bash    tools/verify_backup_interop.sh   # 用 iOS 真实的 ZipArchive.swift 读 Android 写出的 .jdiary（15 条断言）
```

> 第一次跑 Robolectric 需要下载 `android-all-instrumented`（约 100MB）。
> `app/build.gradle.kts` 已把 `robolectric.dependency.repo.url` 指到阿里云镜像
> （直连 `repo1.maven.org` 在本机网络下会被 TLS 重置）。

## 目录结构

```
JustGDiary/
├── app/src/main/java/com/cov/justdiary/
│   ├── app/          # Application、AppContainer（手工 DI）、MainActivity、LaunchIntent、DebugFlags
│   ├── model/        # 纯模型：AppSettings、DiaryModels、DateUtil、ContentPart（content_json 编解码）
│   ├── data/         # DiaryRepository（~1000 行）、SearchSql、FootprintAggregator、ShareLayout、db/
│   ├── service/      # 平台服务：Location、Reminder、Backup、ZipArchive、MediaStore、FtsSegment …
│   ├── ui/           # adaptive / home / diary / footprint / search / settings / root / components / theme
│   └── ...
├── app/src/test/     # JVM 单测（含 golden file 与 Robolectric 运行时验证）
├── docs/             # 迁移方案、进度记录、逐页源码分析报告
└── tools/            # 两个独立校验脚本（SQL schema、跨端备份互通）
```

## 架构上的两条主线

1. **纯逻辑下沉，平台细节注入**。日历数学、分享长图版式、编辑模型、足迹聚合、搜索筛选、
   提醒排期、设置副作用、版面判定，全部是不依赖 Android/Compose 的纯函数或纯数据，
   因此可以在 JVM 上逐条断言（这也是 352 个测试里绝大多数能存在的原因）。
   平台相关的部分（`Geocoder`、`Spannable`、`WorkManager`、SAF、Canvas 绘制）放在 `service/` 与外层 `ui/`。
2. **跨端契约冻结**。表名/列名/字符串取值/`content_json` 字节/zip 布局/`settingsJson` 的 10 个键
   都不允许"顺手改干净"：`.jdiary` 必须双向互通。为此专门用真实 iOS 源码编译出的产物当测试夹具
   （`app/src/test/resources/golden/`），并保留了两处看起来很别扭、但**故意**与 iOS 一致的差异
   （例如 `region1` 在"记录新位置"与"回填历史"两条路径上规则不同）。

## 已验证 / 未验证

**已验证**（都有可复现的命令）

- 编译：`clean assembleDebug` 通过；
- 单元测试：352 条全绿，覆盖日历/morph 数学、编辑模型、`content_json` 与 iOS 的字节级一致、
  跨端 zip 布局、搜索筛选、足迹聚合、提醒排期、设置副作用、版面判定的临界值、内联图片尺寸规则；
- **运行时**（Robolectric）：真开一次 SQLite 跑完 v0→v1→v3 迁移链、写入/读回/编辑/删除/搜索、
  `day_key` 重算幂等、`EditorLine` ↔ `Spannable` 往返不丢图、导出→导入的 SKIP/OVERWRITE 两种模式；
- 跨端：用 iOS 真实的 `ZipArchive.swift` 读 Android 写出的 `.jdiary`（15 条断言）。

**未验证**（需要设备，按已确认的验收口径本轮不做）

- 安装、启动、真机交互；
- morph 动画的帧率实测（调试开关 `-slow-morph` / `-morph-log` 已就位，等有设备时直接可用）；
- TalkBack 走查（`a11y_*` 文案与日历的"上/下个月、上/下一年"自定义动作已接）；
- 设置页行图标目前取 `material-icons-core` 里语义最近的图标 —— 换成真正的 Material Symbols
  需要 `material-icons-extended`（35.7MB aar，`isMinifyEnabled = false` 下不划算），
  已集中在一处映射里，将来替换只改一个对象。

## 文档

| 文件 | 内容 |
| --- | --- |
| [`docs/android-migration-plan.md`](docs/android-migration-plan.md) | 迁移方案（含所有已确认的决策与理由、逐页迁移清单、风险清单） |
| [`docs/migration-progress.md`](docs/migration-progress.md) | 逐里程碑的交付物、验证证据、与 iOS 的差异（含**有意不复制**的 iOS 缺陷清单） |
| [`docs/migration-analysis/`](docs/migration-analysis) | 数据层 / 编辑器 / 首页日历 / 服务与平台的逐行源码分析报告 |

## 环境要求

- Android Studio（本机 2026.1）
- JDK 17+（`app/build.gradle.kts` 的 `sourceCompatibility`/`jvmTarget` 为 17）
- Android SDK：`local.properties` 里的 `sdk.dir`（该文件**不入库**）
