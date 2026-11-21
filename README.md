# JustGDiary

日记应用 **[JustADiary](../JustADiary)** 的 Android 版本。数据层与 iOS 版**严格互通**：同一套表名/列名/字符串取值/`content_json`/备份包格式，`.jdiary` 备份可以双向互导。

> **当前状态：M0–M9 的代码工作已完成，并已在模拟器上实测通过一轮界面返工**
> （当前功能/交互现状见 **[`docs/app-current-state.md`](docs/app-current-state.md)**；
> 详细的分里程碑记录见 [`docs/migration-progress.md`](docs/migration-progress.md)）。
> `./gradlew clean assembleDebug testDebugUnitTest` 通过，**652 个 JVM 单测**全绿，
> 另有 2 个脚本做跨端与 SQL 的独立校验。首页已按 Material 3 重新设计并在
> Pixel 10 Pro AVD 上验证（竖屏 / 横屏分栏 / 深色 / TalkBack 语义，
> 证据见 `docs/design-spec.md` §8）。
>
> ⚠️ **2026-09-20 复测发现界面与交互仍有多处缺陷**（含 2 条严重：搜索页永远停在
> "正在准备搜索索引…"、年历在"旋转后切深色"时整屏消失）。新的权威规范是
> [`docs/ui-design-plan.md`](docs/ui-design-plan.md)（界面设计方案 v2）：
> 它给出了逐屏规范、动效几何、缺陷清单（§2）与分五批的实施顺序（§12）。
> 下面这些"已验证"条目**只代表首轮迁移时的状态**，其中首页几何部分已被 v2 取代。
>
> ✅ **R2 已把 v2 的 P0–P5 落地**（见 [`docs/migration-progress.md`](docs/migration-progress.md) 的 R2 一节）：
> 首页恢复 iOS 的整屏三态 morph、月历只画当月日期、年历恢复 12 个迷你月历、
> 顶栏去掉自造的月/周按钮、上下滑翻月重新接线，并修掉 9 条缺陷（含 2 条严重）。
>
> ✍️ **2026-10-01：Android ← iOS 同步（R32）** —— 按 iOS 最近五轮更新同步：
> **编辑 / 阅读 / 首页预览三处显示统一**（一份 `BlockGeometry`：列表与待办有了悬挂缩进与
> M3 勾选框、引用块补上左侧竖条、段距三处一致、图片铺满列宽）、**分享位置精度**设置
> （分享图的地点只从地区列重建，精确地点与街道进不去）、记录精度按**权限 × 本次定位精度**
> 取上限并记住上一次手动选的档位、格式栏「段落样式」按钮不再被引用点亮、图片行禁用
> 「居中」、搜索页地点胶囊去掉装饰。清单见 [`docs/ios-sync-2026-10-01.md`](docs/ios-sync-2026-10-01.md)，
> 逐项记录见 [`docs/migration-progress.md`](docs/migration-progress.md) **R32**，
> 三处一致的截图在 `docs/ui-audit/r32/`。
>
> 🧱 **2026-11-14：P2-6 大文件拆分（R39）** —— `HomeScreen` / `MonthFlow` / `EditorModel` /
> `SettingsScreen` / `SpannableBridge` 五个 1000–2000 行核心文件按“纯函数 / 数据模型先出，
> Compose 组合后出”拆成 17 个文件；公开入口与行为不变，**652 个 JVM 单测仍全绿**。
> 明细见 [`docs/migration-progress.md`](docs/migration-progress.md) 的 **R39**。
>
> 🚀 **2026-11-14：P2 全部收口（R40）** —— 真机 MEIZU 21 复测：月→周 **98–101fps**、
> 周→月 **118fps**、新月份块首帧 **6.8ms**；TalkBack 焦点/语义、48dp 触控目标、
> 分享深色与图片行视觉走查全部完成（平板记录为历史验收），证据在 `docs/ui-audit/r40/`。
>
> ✨ **2026-11-14：P3 第一批（R41）** —— 阅读页分享面板新增“复制文字 / 分享文字”，
> 搜索页新增“最近搜索”（最多 10 条、可清除），编辑器图片入口支持一次选多张
> （最多 9 张、按顺序逐张成行）。**相机直拍按用户要求不做**。
>
> 🧱 **2026-11-14：HomeScreen 二拆完成（R43）** —— 顶栏 / 周层 / 年层 / 当日面板 /
> morph 覆盖层均已成文件；导航与图层交班判据抽成纯函数并补 6 条 JVM 断言。
> 主 `HomeScreen.kt` 只保留三态状态机与组合根（1146 行）。

> ♿ **2026-11-21：首页日历滚动与 TalkBack（R46）** —— 连续月历流保留 ±3 个月预挂载，
> 但逐日无障碍节点只为视口内的行创建，避免 TalkBack 焦点遍历到当前屏幕外的预热日期；
> 无障碍关闭时仍使用 Canvas 绘制。模拟器验证与性能观察见 [`docs/migration-progress.md`](docs/migration-progress.md) 的 **R46**。
>
> ✍️ **2026-09-28：日记页第三轮返工（R30，真机 MEIZU 21 / Android 16）** ——
> 首页「继续写日记」改为**先进阅读态**；定位可靠性重做（**新鲜缓存点 → 两条来源赛跑 →
> 过期缓存兜底** + 反地理编码重试 + 回前台补取 + 编辑器内申请权限），
> 地点行改成 iOS 那样**一枚胶囊**（不再有单独的「重新获取位置」按钮）；
> 插图**自动居中 / 一行一张 / 光标移到下一行**；提示语改成**输入框自己的 `hint`**
> （原先与光标差 65dp）；并修掉「换段落样式会把 B/I/S/U 一起抹掉」。
> 格式栏按钮的待返工清单记在 [`docs/android-design/04-diary.md`](docs/android-design/04-diary.md) §3.3。
>
> ✍️ **2026-09-27：日记页·编辑态完成并在真机实测通过**（[`docs/migration-progress.md`](docs/migration-progress.md) **R28**）：
> 9 键格式栏（段落样式菜单 / 居中 / 列表 / 引用 / 待办 / B I S U）、段落样式与行内样式的**作用域规则**、
> 「待输入样式」（空选区点 B 之后输入的字才是粗的）、回车续项/结束列表、放弃修改回滚、保存回阅读态；
> 编辑器与阅读态**共用同一套字号/行高/段距**。
>
> ✍️ **2026-09-27：日记页·阅读态完成并在真机（MEIZU 21 / Android 16）实测通过**
> （逐条目击与截图证据见 [`docs/migration-progress.md`](docs/migration-progress.md) 的 **R27** 一节）：
> 顶栏三态、Hero 卡与空态、六种段落 + 四种行内样式、图片与全屏预览、**悬浮的页内搜索**（计数 / 高亮 /
> 跳转 / 循环）、长按多选与删除确认；编辑态与分享面板是下一轮。
> 富文本编辑器的底座调研（Google 官方 API 与样例的实测结论）见
> [`docs/research-compose-rich-text-apis.md`](docs/research-compose-rich-text-apis.md)。
>
> 📐 **2026-09-26：Android 设计规范 v3 已定稿** ——
> **[`docs/architecture.md`](docs/architecture.md) 与 [`docs/screens/`](docs/screens/) 是当前架构与逐屏规范**
> （取代 `docs/design-spec.md` 与 `docs/ui-design-plan.md` v2；v2 的 8 处自相矛盾已在
> [README §5](docs/android-design/README.md) 逐条裁决）。
> 依据是 iOS 最新代码（commit `0b07050`）的逐行分析，IOS 侧证据在 `docs/ios-reference/v3-*.md`。
> 三条前提：**视觉 = Material 3 / Material You**、**层级与交互对齐 iOS**、
> **手机竖屏与横屏均支持，包含折叠屏窗口变化；不维护平板专属布局**。
> 架构、各页面功能/交互说明见 **[`docs/architecture.md`](docs/architecture.md)**；横屏与折叠屏约定见 [`docs/landscape-and-foldables.md`](docs/landscape-and-foldables.md)。逐屏设计稿见 **[`docs/android-design/mockups/`](docs/android-design/mockups/index.html)**；
> 差距清单与实施批次见 [`docs/android-design/07-ios-map-and-plan.md`](docs/android-design/07-ios-map-and-plan.md)。

## 关键约定

当前对齐版本：Android **1.1 (2)**，与 iOS 一致，版本来源见 [`version.properties`](version.properties)。


| 项目 | 约定 |
| --- | --- |
| 应用包名（applicationId） | `com.cov.justdiary` |
| 与 iOS 版的关系 | 包名与 iOS 版**完全一致**；debug 也**不加** `applicationIdSuffix` |
| minSdk / targetSdk / compileSdk | 33 / 36 / 37 |
| 设计语言 | **Material 3 + 动态取色（Material You）+ 底部 NavigationBar**；不模仿 iOS 的 Liquid Glass，界面按 Google 规范重新设计，只对齐功能、交互与动画（详见 [`docs/android-design/01-design-system.md`](docs/android-design/01-design-system.md)） |
| 方向 | 手机竖屏与横屏均支持；布局按窗口宽高与安全区自适应，不按设备类别分支 |
| 第三方 UI 库 | **零**（不引入任何玻璃/模糊库；柱状图等自绘） |
| 默认分支 | `main` |

## 技术栈

| 项 | 版本 / 说明 |
| --- | --- |
| Kotlin | 2.4.20（**AGP 9 内置 Kotlin 支持**，不应用 `org.jetbrains.kotlin.android`） |
| AGP / Gradle | 9.5.1 / 9.7.1（wrapper 走华为镜像 + sha256 校验） |
| Compose | BOM 2026.09.00，material3 1.4.0 |
| JDK | 17（本机用 Android Studio 自带的 JBR 25 编译，见下） |
| 数据库 | `SQLiteOpenHelper` + **裸 SQL**（不用 Room：表结构要与 iOS 逐字一致的契约） |
| 后台任务 | WorkManager（每日提醒）；无精确闹钟权限 |
| 定位 | 运行时探测 GMS：有 Play services 走 `FusedLocationProviderClient`，否则退原生 `LocationManager` |
| 单测 | JUnit4 + **Robolectric**（数据层与备份链路的运行时验证） |

## 构建与验证

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # 本机无系统 JDK

./gradlew clean assembleDebug testDebugUnitTest   # 编译 + 652 个 JVM 单测（含 29 个 Robolectric 类 / 198 条运行时用例）
./gradlew :app:connectedDebugAndroidTest          # 7 条 instrumented（需已启动模拟器/真机）
./gradlew :app:assembleRelease                    # 签名配置见 docs/release.md；R8 + 资源压缩
python3 tools/verify_release_apk.py app/build/outputs/apk/release/app-release.apk  # 验签 / 包名 / 版本

# 三个独立校验脚本
python3 tools/verify_sqlite_schema.py    # 从 Kotlin 源码抽出 DDL，用宿主 sqlite3 真跑一遍（18 条断言）
bash    tools/verify_backup_interop.sh   # 用 iOS 真实的 ZipArchive.swift 读 Android 写出的 .jdiary（15 条断言）
python3 tools/verify_docs.py             # 测试计数 / P1 收口 / release 配置的静态校验
```

CI：`.github/workflows/android.yml`（单测 + 校验脚本 + API 35 模拟器 instrumented）。

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
   因此可以在 JVM 上逐条断言（这也是 652 个测试里绝大多数能存在的原因）。
   平台相关的部分（`Geocoder`、`Spannable`、`WorkManager`、SAF、Canvas 绘制）放在 `service/` 与外层 `ui/`。
2. **跨端契约冻结**。表名/列名/字符串取值/`content_json` 字节/zip 布局/`settingsJson` 的 12 个键
   都不允许"顺手改干净"：`.jdiary` 必须双向互通。为此专门用真实 iOS 源码编译出的产物当测试夹具
   （`app/src/test/resources/golden/`），并保留了两处看起来很别扭、但**故意**与 iOS 一致的差异
   （例如 `region1` 在"记录新位置"与"回填历史"两条路径上规则不同）。

## 已验证 / 未验证

**已验证**（都有可复现的命令）

- 编译：`clean assembleDebug` 通过；
- 单元测试：652 条全绿，覆盖日历/morph 数学、编辑模型与编辑器状态机、`content_json` 与 iOS 的字节级一致、
  跨端 zip 布局、搜索筛选、足迹聚合、提醒排期、设置副作用、版面判定的临界值、内联图片尺寸规则；
- **运行时**（Robolectric）：真开一次 SQLite 跑完 v0→v1→v3 迁移链、写入/读回/编辑/删除/搜索、
  `day_key` 重算幂等、`EditorLine` ↔ `Spannable` 往返不丢图、导出→导入的 SKIP/OVERWRITE 两种模式；
- 跨端：用 iOS 真实的 `ZipArchive.swift` 读 Android 写出的 `.jdiary`（15 条断言）。

**已在模拟器上实测**（Pixel 10 Pro AVD，1280 × 2856 @ 480dpi）

- 安装、启动、竖屏 / 横屏 / 深色三种形态的首页版面；
- 日历无障碍：`uiautomator dump` 里 31 个日格节点，`content-desc` 为完整日期，
  `today`/`selected` 状态与 `onClick` 标签齐全；
- 交互：点日期换当日内容、再点已选中日期进日记、上下滑翻月、年历 / 今天 /
  横屏分栏。逐条证据见 [`docs/design-spec.md`](docs/design-spec.md) §8。

**仍未验证**

- morph 动画的**帧率实测**（调试开关 `-slow-morph` / `-morph-log` 已就位）；
- TalkBack **真人走查**（语义已实测存在，但朗读顺序与提示语尚未逐条听）；
- 其余三屏（足迹 / 搜索 / 设置）的横屏与深色只做了目视核对，没有逐条断言；
- 设置页行图标目前取 `material-icons-core` 里语义最近的图标 —— 换成真正的 Material Symbols
  需要 `material-icons-extended`（35.7MB aar，`isMinifyEnabled = false` 下不划算），
  已集中在一处映射里，将来替换只改一个对象。

## 文档

| 文件 | 内容 |
| --- | --- |
| [`docs/app-current-state.md`](docs/app-current-state.md) | **当前应用现状稿（用户可见行为的唯一事实来源）** —— 按界面分类的功能/布局/交互/状态/限制，随每次行为变更更新 |
| [`docs/ui-design-plan.md`](docs/ui-design-plan.md) | 界面设计方案 v2（历史设计意图；与现状稿冲突时以现状稿为准） |
| [`docs/design-spec.md`](docs/design-spec.md) | 首轮界面规范与上机缺陷清单（**部分条款已被 v2 取代**，见 v2 附录 B） |
| [`docs/ios-reference/`](docs/ios-reference) | iOS 侧逐条数值提取（含 `file:line`），供对照实现 |
| [`docs/android-migration-plan.md`](docs/android-migration-plan.md) | 迁移方案（含所有已确认的决策与理由、逐页迁移清单、风险清单） |
| [`docs/migration-progress.md`](docs/migration-progress.md) | 逐里程碑的交付物、验证证据、与 iOS 的差异（含**有意不复制**的 iOS 缺陷清单） |
| [`docs/migration-analysis/`](docs/migration-analysis) | 数据层 / 编辑器 / 首页日历 / 服务与平台的逐行源码分析报告 |

## 环境要求

- Android Studio（本机 2026.1）
- JDK 17+（`app/build.gradle.kts` 的 `sourceCompatibility`/`jvmTarget` 为 17）
- Android SDK：`local.properties` 里的 `sdk.dir`（该文件**不入库**）
