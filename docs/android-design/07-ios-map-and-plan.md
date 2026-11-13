# 07 · iOS → Android 映射、差距与实施计划

> 这一篇回答三个问题：**iOS 的每一块对应 Android 的哪个文件**、**现在差多少**、**按什么顺序补**。
>
> 输入：`docs/ios-reference/v3-*.md`（iOS 侧逐条证据）· `docs/android-ui-inventory.md`（Android 现状盘点，含 `file:line`）
> · `docs/migration-progress.md`（R1–R3 已落地记录）

---

## 1. 文件映射表

| iOS 文件 | 行数 | Android 对应 | 状态 |
| --- | --- | --- | --- |
| `Views/RootView.swift` | 169 | `ui/root/{RootScreen,AppTab}.kt` | ✅ 完成 |
| `Views/Home/HomeView.swift` | 584 | `ui/home/HomeScreen.kt` + `HomeViewModel.kt` | ⚠️ 分栏左栏缺手势；分栏下未收回周/年态 |
| `Views/Home/CalendarLayout.swift` | 399 | `ui/home/CalendarLayout.kt` | ✅ 完成（14 个常量逐条对齐） |
| `Views/Home/CalendarGrids.swift` | 440 | `ui/home/CalendarDayGrid.kt` | ✅ 完成（未来日期用语义色，**有意偏离** v2 §5.2） |
| `Views/Home/CalendarPaneViews.swift` | 228 | `ui/home/CalendarGrid.kt` | ⚠️ 分栏紧凑行未接 |
| `Views/Home/DayContentView.swift` | 115 | `ui/home/HomeScreen.kt`（当日面板） | ⚠️ 缺卡片描边、内边距 14→12 |
| `Views/Home/MorphViews.swift` | 180 | `ui/home/HomeScreen.kt`（morph 层） | ✅ 完成 |
| `Views/Home/DragPagePager.swift` | 105 | `ui/home/HomeScreen.kt`（`pagerModifier`） | ⚠️ morph 中未禁用输入 |
| `Views/Diary/DiaryPageView.swift` | 683 | `ui/diary/{DiaryPageScreen,DiaryReadScreen}.kt` | ❌ 回调链断裂（见 §4） |
| `Views/Diary/RichTextView.swift` | 388 | `ui/diary/{EditorScreen,EditorTextArea,FontToolbar}.kt` | ⚠️ 段落菜单多一项、缺横滑 |
| `Views/Diary/RichTextEngine.swift` | 1335 | `ui/diary/{EditorModel,EditorSelection}.kt`（撤销/重做确认不做，`EditorHistory` 已删除） + `model/ContentPart.kt` | ✅ 完成（golden 字节级断言） |
| `Views/Diary/ReadTextView.swift` | 196 | `ui/diary/DiaryReadScreen.kt` | ⚠️ 正文列未限宽 |
| `Views/Diary/MediaViews.swift` | 187 | `ui/diary/DiaryReadScreen.kt`（图片） | ❌ 缺全屏预览 |
| `Views/Diary/ShareSheetView.swift` | 183 | `ui/diary/DiaryPageScreen.kt`（分享） | ❌ 无面板，直接拉起 Sharesheet |
| `Services/ImageShareService.swift` | 497 | `service/ImageShareRenderer.kt` | ⚠️ 写死 10 个颜色、时间写死 `HH:mm` |
| `Views/Search/SearchView.swift` | 519 | `ui/search/SearchScreen.kt` | ⚠️ 无自定义时间、地点用 AlertDialog |
| `Views/Footprint/FootprintView.swift` | 330 | `ui/footprint/FootprintScreen.kt` | ⚠️ 无自定义时间、趋势卡总渲染 |
| `Views/Settings/SettingsView.swift` | 462 | `ui/settings/SettingsScreen.kt` | ⚠️ 多列网格、开关行整行不可点 |
| `Views/Components/DesignSystem.swift` | 220 | `ui/theme/DesignTokens.kt` | ✅ 完成（R3 复查） |
| `Views/Components/DynamicType.swift` | — | `ui/theme/DesignTokens.kt`（`DynamicTypeMetrics`） | ✅ 完成（14 条单测） |
| `Views/Components/AdaptiveLayout.swift` | 254 | `ui/adaptive/AdaptiveLayout.kt` | ⚠️ 分栏阈值需从 700 → 548.5 |
| `Views/Components/Components.swift` | 718 | `ui/components/Components.kt` | ⚠️ 缺 7 个组件（见 `06-components-and-overlays.md`） |
| `Services/Theme.swift` | — | `ui/theme/{Color,Theme}.kt` | ✅ 完成 |

---

## 2. 常量映射（不重复列出，按目录查）

| 主题 | 在哪一篇 |
| --- | --- |
| 颜色 / 字号 / 形状 / 海拔 / 动效 / 无障碍 | [`01-design-system.md`](01-design-system.md) §1–§7 |
| 断点 / 导航 / 分栏 / insets / 返回栈 | [`02-adaptive-and-navigation.md`](02-adaptive-and-navigation.md) §2–§6 |
| 日历几何 / morph 端点 / 手势阈值 | [`03-home.md`](03-home.md) §2、§6、§7、§9 |
| 日记列宽 / 卡片 / 格式栏 / 长图 | [`04-diary.md`](04-diary.md) §6 |
| 搜索 / 足迹 / 设置的逐行规格 | [`05-search-footprint-settings.md`](05-search-footprint-settings.md) |
| 组件签名与弹层规范 | [`06-components-and-overlays.md`](06-components-and-overlays.md) |

---

## 3. 差距清单（按严重度）

### 3.1 P0 · 断链与严重缺陷（必须最先修）

| # | 问题 | 证据 | 修法 |
| --- | --- | --- | --- |
| **P0-1** | **阅读页「点片段进编辑 / 长按多选 / 删除」整条链未接线** —— `DiaryPageScreen.kt:108-154` 没传 6 个回调，`enterEditBlock` / `enterSelect` / `confirmDeleteSelected` 是**死代码** | `docs/android-ui-inventory.md` §2.3 | 按 `04-diary.md` §2.4 / §2.8 接通；补一条 instrumented 或 Robolectric 用例守住 |
| **P0-2** | **阅读页页内搜索完全失效** —— `showSearch` 只由无人调用的 `setSearchVisible` 置位，输入框恒空、无高亮无跳转 | 同上 | 按 `04-diary.md` §2.7 重做；计数与高亮用**同一套**算法 |
| **P0-3** | **分享没有面板** —— 点分享直接拉起系统 Sharesheet，看不到长图预览；`renderToFile` 失败也没有反馈 | `DiaryPageScreen.kt:81-89` | 按 `04-diary.md` §4 落 ModalBottomSheet |
| **P0-4** | **分栏左栏没有翻月手势**；且当前实现允许在分栏下停在周/年态 | `HomeScreen.kt:521-524` | 按 `03-home.md` §2.3 / §4.2：接上下滑翻月，并在进入分栏时无动画收回月态 |
| **P0-5** | **morph 进行中未禁用输入** —— `pagerModifier` 的 `pointerInput` key 未含 `morphing` | `HomeScreen.kt:426`、`:403` | 按 `03-home.md` §1 规则 2 修 |
| **P0-6** | **手机横屏未禁用**（当前 `≥700dp 且宽>高` 就分栏，手机横屏会进分栏） | `AdaptiveLayout.kt:54` | 按 `02-adaptive-and-navigation.md` §1 锁竖屏；分栏阈值改 548.5 |

### 3.2 P1 · 与 iOS 不一致（影响正确性 / 完整性）

| # | 问题 | 修法 |
| --- | --- | --- |
| P1-1 | 编辑器段落样式菜单含「引用」（iOS 只有大标题/小标题/正文） | 去掉菜单里的引用项（`FontToolbar.kt:100`） |
| P1-2 | 格式栏末尾多了「插入图片」（iOS 在顶栏） | 移到顶栏；格式栏恢复 9 项 |
| P1-3 | 格式栏放不下时不横滑（`FilterChip` 默认尺寸） | 按钮 40×40dp + `horizontalScroll` 兜底 |
| P1-4 | 阅读页正文列未限宽 660dp、未居中 | 按 `04-diary.md` §2.1 |
| P1-5 | 阅读页 hero 卡内边距 14dp、无描边 | 与条目卡统一 12dp |
| P1-6 | 缺图片全屏预览 | 按 `04-diary.md` §2.6 |
| P1-7 | 搜索缺「自定义时间范围」；地点选择器是 `AlertDialog` | 改 `ModalBottomSheet`（`05-…` §1.6） |
| P1-8 | 搜索条件摘要行的胶囊不可逐条删除、无「清除全部」 | 按 `05-…` §1.4 |
| P1-9 | 结果项日期胶囊放的是 `2026-09-20` 原文 | 用本地化日期（`9月20日 周日`） |
| P1-10 | 足迹缺「自定义时间」筛选 | 按 `05-…` §2.2 |
| P1-11 | 足迹趋势卡**总在渲染**（iOS 只在 ≥2 年份时出现） | 加 `yearly.size >= 2` 条件 |
| P1-12 | 设置页是多列网格（v2 要求单列；iOS 也是单列） | 手机 1 列；≥700dp 2 列；≥1000dp 3 列 |
| P1-13 | 设置开关行只有 `Switch` 可点 | 整行 `toggleable` + `contentDescription` |
| P1-14 | 设置行标题/副标题用 14/12（应为 16/13） | 按 `05-…` §3.1 |
| P1-15 | 设置「版本」行画了箭头但不可点 | 去掉箭头 |
| P1-16 | 「覆盖已有日记」没有破坏性配色 | 改 `error` 色 |
| P1-17 | 4 处写死底部留白 96/72dp | 改用 `innerPadding` + `navigationBarsPadding()` |
| P1-18 | 分享长图写死 10 个颜色 + 时间写死 `HH:mm` | 走 `colorScheme` + `DateFormats` |
| P1-19 | 分栏阈值 700 → **548.5**；主栏 280–440 → **260–440** | 按 `02-…` §2.1 |
| P1-20 | 周态缺两条分隔线（周条下、日期行下） | 按 `03-home.md` §5.1 |
| P1-21 | 年历「有日记」与「今天」互斥（应可共存） | 按 `03-home.md` §5.2（裁决 #8） |

### 3.3 P2 · 打磨项

| # | 问题 |
| --- | --- |
| P2-1 | `Spacing.hitTarget = 48dp` 定义了但几乎没人用；日格 44dp 起 |
| P2-2 | 日格缺「上一天 / 下一天」自定义无障碍动作（现在是挂在容器上的上/下月） |
| P2-3 | 结果行缺独立的时间文本（只在无障碍描述里） |
| P2-4 | 足迹计数用 14sp（应 12sp tabular） |
| P2-5 | 足迹空态未真正垂直居中 |
| P2-6 | 搜索页头计数是纯文本（应 `CountBadge`） |
| P2-7 | 搜索框未设 46dp 最小高；聚焦时缺「取消」 |
| P2-8 | 搜索缺滚动时收起顶部控件（**可选**，见 `05-…` §1.2 注） |
| P2-9 | 设置尾部取值固定 96dp + 省略号（应 `maxLines=1` + 缩放） |
| P2-10 | 相对日文案（今天/昨天/明天）在 UI 里已无使用点 → 首页胶囊要用上（`03-home.md` §3.3） |
| P2-11 | 阅读页缺顶栏返回按钮（现在只靠系统返回） |
| P2-12 | 图片圆角 12（R3 已统一）—— 确认与 `Radius.image` 常量一致 |

---

## 4. 实施批次

> 每一批结束后跑：`./gradlew clean assembleDebug testDebugUnitTest`（357 条）+ 两个校验脚本，并在 AVD / 平板上截图存档到 `docs/ui-audit/`。

### 批次 1 · 地基（不动界面也能验证）

1. `02-adaptive-and-navigation.md` §1：手机锁竖屏（`values/bools.xml` + `values-sw600dp/bools.xml` + `MainActivity`）
2. 分栏阈值 700 → **548.5**，主栏 260–440（`AdaptiveLayout.kt` + `AdaptiveLayoutTest` 补临界值）
3. `ui/theme/`：补 `LocalReduceMotion`；把 `Spacing.hitTarget` 真正用起来
4. 底部留白 4 处改为 `innerPadding` + `navigationBarsPadding()`
5. 补 `06-components-and-overlays.md` §1 里缺的 7 个组件（`AssistPill` / `InputPill` / `CountBadge` / `StatusSwitchRow` / `EmptyState` / `SkeletonRows` / `BusyOverlay`）

**验收**：`AdaptiveLayoutTest` 覆盖 599/600、699/700、999/1000、548.4/548.5、宽==高；手机转屏无效。

### 批次 2 · 首页收口

1. morph 中禁用输入（P0-5）
2. 分栏左栏接上下滑翻月；进入分栏时**收回年/周态**（P0-4）
3. 周态补两条分隔线（P1-20）
4. 年历：有日记与今天可共存（P1-21）
5. 顶栏胶囊改用 `AssistPill`；右胶囊文案规则（`03-home.md` §3.3）
6. 未来日期 Snackbar 真正接上

**验收**：`03-home.md` §11 全部条目；三态 morph 录屏与 [`mockups/png/08-flows.png`](mockups/png) 的逐帧分解对照；平板横屏**没有年份入口**且转入时自动回月态。

### 批次 3 · 日记页接通（最大一块）

1. **P0-1**：阅读页 → 编辑 / 多选 / 删除的回调链
2. **P0-2**：页内搜索重做（一套算法）
3. **P0-3**：分享 ModalBottomSheet + 长图预览
4. 顶栏三态（阅读 / 编辑 / 多选）与动作集重排；「插入图片」回顶栏（P1-1、P1-2）
5. 格式栏 9 项 + 横滑兜底（P1-3）
6. 正文列限宽 660 + 居中（P1-4、P1-5）
7. 图片全屏预览（P1-6）
8. 分享长图走 `colorScheme` + `DateFormats`（P1-18）

**验收**：`04-diary.md` §8 全部条目；一天内完成「新建 → 插图 → 编辑 → 保存 → 分享」全链路。

### 批次 4 · 搜索 / 足迹 / 设置

1. 搜索：自定义时间面板、地点 ModalBottomSheet、可逐条删除的条件摘要、本地化日期胶囊（P1-7～P1-9）
2. 足迹：自定义时间、趋势卡显示条件、计数字号、空态居中（P1-10、P1-11、P2-4、P2-5）
3. 设置：列数、行高与字号、开关行整行可点、版本行去箭头、覆盖按钮改 error 色（P1-12～P1-16）
4. 权限状态改枚举判定（`README.md` §6 缺陷 5）
5. 语言/主题切换改为 `CompositionLocal` 下发，**不重建整棵树**（缺陷 6）

**验收**：`05-…` §5 全部条目。

### 批次 5 · 打磨与无障碍

1. 日格 / 周条 / 年历的自定义无障碍动作（P2-2）
2. 搜索页头计数徽章、搜索框 46dp、聚焦「取消」（P2-6、P2-7）
3. 预测性返回（Q4）
4. 分享长图的时间格式与主题跟随
5. 全屏与深色实机复测；TalkBack 走查
6. 补齐 `docs/ui-audit/` 的新一轮截图

---

## 5. 缺陷处置表

### 5.1 v2 的 D1–D13

| # | 状态 | 说明 |
| --- | --- | --- |
| D1 搜索页永远"正在准备索引" | ✅ R2 已修 | 空查询写 `total = 0` |
| D2 年历整屏消失 | ✅ R2 已修 | 可见性改由 `mode` 决定 + `snapTo` 终值 |
| D3 `auto_time` 是死设置 | ✅ 两端统一为"显示开关" | `start_time_utc` 照常入库 |
| D4 月历画相邻月 | ✅ 已修（`showAdjacent = false`） | |
| D5 月态下方 270dp 空白 | ✅ 已修（大标题槽 72 + 恒除 6） | 空白是**有意**的（翻月不跳） |
| D6 年历卡片几乎全空 | ✅ 已修（恢复 12 个迷你月历） | |
| D7 顶栏多一个日历图标按钮 | ✅ 已修（删除） | |
| D8 阅读页空态整屏空白 | ✅ 已修（hero 常驻） | |
| D9 横屏右栏标题重复"今天" | ✅ 已修 | |
| D10 编辑页位置行文案重复 | ✅ 已修 | |
| D11 格式栏缺"居中对齐" | ✅ 已修 | |
| D12 空行也画分隔线 | ✅ 已修 | |
| D13 上下滑翻月失效 | ⚠️ 竖屏已修，**分栏左栏仍缺** → 见 P0-4 | |

### 5.2 iOS 侧新暴露的 12 条（详见 `README.md` §6）

其中与 Android 实现直接相关的 5 条：**搜索加载态**（P0 相关）、**撤销条件使在途请求失效**、
**深链立即消费**、**语言/主题不重建整棵树**、**地点选择不截断**。

### 5.3 本轮新发现

| # | 问题 | 严重度 | 处置 |
| --- | --- | --- | --- |
| N1 | 阅读页 → 编辑 / 多选 / 删除回调链断裂 | **严重** | P0-1 |
| N2 | 阅读页页内搜索失效 | **严重** | P0-2 |
| N3 | 分享缺面板与失败反馈 | 高 | P0-3 |
| N4 | 分栏左栏无手势；分栏下未强制收回周/年态 | 高 | P0-4 |
| N5 | morph 中未禁用输入 | 中 | P0-5 |
| N6 | 手机横屏仍可分栏 | 高（与新需求冲突） | P0-6 |
| N7 | `recomputeDayKeys` 的冲突判定恒不成立（两端共有），真撞车时会留下 `tmp#N` 归属日 | **严重（数据损坏）** | **已在设置页那批修掉**：按真实唯一约束判断，并抽出纯函数 `planDayKeys` + 6 条单测 |

---

## 6. 验收方式

| 手段 | 覆盖 |
| --- | --- |
| `./gradlew clean assembleDebug testDebugUnitTest` | 357 条 JVM 单测（含 15 条 Robolectric） |
| `CalendarLayoutTest` / `CalendarMorphTest` | 日历几何与 morph 端点（26 条） |
| `AdaptiveLayoutTest` | 版面判定的 5 组临界值（17 条） |
| `DesignTokensTest` / `DynamicTypeMetricsTest` | M3 角色字号、形状梯级、48dp、逐档封顶不倒挂（18 条） |
| `tools/verify_sqlite_schema.py` / `tools/verify_backup_interop.sh` | 跨端契约（改动 UI 也必须保持通过） |
| AVD 截图 + `uiautomator dump` | 版面、深色、TalkBack 语义 |
| 平板（`sw600dp`）实机 / AVD | 横竖屏、三种尺寸类、分栏 |

**新增 UI 逻辑必须配套单测**：凡是"能写成纯函数"的（几何、切换规则、文案选择、筛选叠加），
一律下沉到不依赖 Compose 的地方并加断言 —— 这是本项目 357 条测试能存在的原因。
