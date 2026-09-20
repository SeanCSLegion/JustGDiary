# JustGDiary 界面设计方案（v2）

日期：2026-09-20 · 参考设备：Pixel 10 Pro AVD（1280 × 2856 @480dpi = **426.7 × 952 dp**）
iOS 对照：iPhone 18 Pro（402 × 874 pt，iOS 27）

> **实施状态：P0–P5 已全部落地**（见 [`docs/migration-progress.md`](migration-progress.md) 的 R2 一节，
> 上机证据见 [`docs/ui-audit/r2/`](ui-audit/r2)）。`./gradlew clean assembleDebug testDebugUnitTest`
> 通过，355 条 JVM 单测全绿；跨端 SQL 与 `.jdiary` 互通校验也通过。
>
> 本文是 Android 版界面的**唯一权威规范**。它取代 `docs/design-spec.md` 中与之冲突的条款
> （冲突处逐条列在附录 B）。改界面之前先读这一篇。
>
> iOS 侧的逐条数值（含 `file:line`）见 `docs/ios-reference/`；本方案只写**Android 应该做成什么**。

---

## 〇、一页结论

已确认的三条前提（本轮拍板）：

| # | 决定 | 含义 |
| --- | --- | --- |
| 1 | **视觉语言 = Material 3 + 动态取色** | 不模仿 iOS 的 Liquid Glass。颜色走 `MaterialTheme.colorScheme`，动态取色保留并默认开启。 |
| 2 | **版式与交互 = 对齐 iOS** | 信息层级、几何比例、三态模型、手势结果、动画意图，全部按 iOS 复刻；不照搬的是**数值来源**（窗口尺寸类 + WindowInsets）与**视觉样式**。 |
| 3 | **首页 = 整屏三态 morph** | 恢复 iOS 的月／周／年三态与它们之间的 morph。放弃首轮"月历 + 下方面板"的单屏改法。 |

由此推出四条会改动现有代码的结论：

1. **月历只画当月日期**。相邻月留空，不画、不可点、不进无障碍树。现在画了（`CalendarDayGrid.kt:79` 默认 `showAdjacent = true`），与 iOS 的 `showAdjacent: false` 相反。
2. **年历恢复 12 个迷你月历**。首轮改成"月份名 + 记录点"的理由是"卡片只有 ~36dp 高"；实测卡片是 **383 × 503 px ≈ 128 × 168 dp**，这个理由已经不成立。而且没有迷你月历，年↔月 morph 就**没有落点**——这正是"年历动画不对"的根因。
3. **点日期 = 整屏 morph 进周态**。现在点日期只换下方面板、不换屏（`HomeScreen.kt:106`），morph 只能靠顶栏那个图标触发。
4. **删掉顶栏那个日历图标按钮**。iOS 没有它；它在月／周两态下图标完全一样，看不出含义，和"今天"并排会被读成第二个日历入口。回月态的入口改回 iOS 的做法：周态时顶栏左侧变成 `‹ 九月`。

外加 7 条**已复现**的缺陷（§2），其中两条是严重级别。

---

## 一、设计原则

1. **可移植的是意图，不是数值。** iOS 布局数学里凡是出现 `screenH`、`safeAreaBottom`、固定 `bottomClearance` 的地方一律不许照搬。Android 的可用空间由窗口尺寸 + WindowInsets 决定，长宽比跨度远大于 iPhone 家族。要移植的是**比例关系、层级、状态语义、动画方向感**。
2. **日期与时间是产品的骨架。** 三态、选中、今天、有日记、农历、未来，每一条都要有**非颜色**的表达（形状、文字、下划线），且三态一致。
3. **动画的终点就是真实图层。** morph 的每一个端点必须取自真实排版用的同一个矩形／同一组常量。任何"再算一遍"都会在收尾时跳一下。
4. **可见性由状态决定，不由动画值决定。** 一个图层"在不在"必须能只从 `mode` 推出来；动画只负责过渡。违反这条就是 §2 的 D2。
5. **任何 `coerceIn` 都要证明区间不会反转。** 横屏崩溃就是这么来的。
6. **零第三方 UI 库。** 柱状图、日历网格、分享长图自绘。

---

## 二、现状实测：问题清单

全部在 Pixel 10 Pro AVD 上实测（截图 + `uiautomator dump`），或由代码可直接判定。
证据截图存在 [`docs/ui-audit/`](ui-audit)（文件名以 `BUG-` 开头的三条即严重缺陷的现场）。

### 2.1 严重（必改）

| # | 现象 | 证据 | 根因 | 修法 |
| --- | --- | --- | --- | --- |
| **D1** | **搜索页永远停在"正在准备搜索索引…"，永远看不到空态** | 空库、未输入关键词时截图即此态 | `SearchFilters.kt:38` `indexNotReady = total < 0`；`SearchViewModel.kt:217-220` 在"无关键词且无筛选"时把 `total` 显式写成 `-1` 并 early-return → 永久判定为"索引未就绪" | 空查询分支写 `total = 0`（或把 `indexNotReady` 与"是否有条件"解耦，只在真正重建索引时置位）；空态按 §9.5 三选一 |
| **D2** | **年历整屏消失**：在年态下旋转 → 转回 → 切深色，12 张卡片全部不见，只剩顶栏"‹ 2026年" | 已复现两次；`dump` 里 12 个 `content-desc="1月"…` 的 Button 全部消失 | 年层的存在感由动画值 `yearProgress`（`HomeScreen.kt:142`）驱动，而它没有随配置变更恢复；`mode=year` 被恢复，`yearProgress` 回到 0 → `yearOpacity = 0` 永久为 0 | 年层可见性改由 `mode` 决定；`yearProgress` 只做过渡，恢复时 `snapTo` 到终值 |
| **D3** | **"自动插入时间"开关实际无效**（两端） | iOS 侧：`autoTime` 只有 `SettingsViewModel.swift:61` 的写与 `SettingsView.swift:296` 的绑，**编辑器完全不消费** → 纯死设置。Android 侧：`EditorScreen.kt:345` 消费了，但语义是"关闭时把 `startTimeUtc` 换成保存时刻"——两者只差几秒，**用户看不出任何区别** | 上游 iOS 侧是死设置；Android 迁移时自作主张实现了一个不可感知的语义 | **两端统一为"显示开关"**（见 §7.3）：开＝记录并显示开始时间；关＝**不显示**（编辑器时间胶囊、阅读页、首页当日面板、分享长图全部不显示）。`start_time_utc` **照旧记录** —— `day_key` / `created_utc` / 排序都依赖它，写 0 会让记录掉到 1970，破坏 `.jdiary` 互通。iOS 侧已同样修改并通过 `xcodebuild` 构建 |

### 2.2 显示 / 一致性

| # | 现象 | 证据 | 修法 |
| --- | --- | --- | --- |
| **D4** | 月历画相邻月日期（`8月31日`、`10月1日…`），iOS 不画 | `CalendarDayGrid.kt:79` `showAdjacent = true`；iOS `showAdjacent: false` | §5.2：相邻月格留空 |
| **D5** | 月态下方留约 **270dp** 空白带 | 实测日格 `[0,414][182,702]`（288px = 96dp，被 `MAX_CELL_H` 封顶）；6 行到 y=2142，导航栏从 y≈2620 起 | §5.1：恢复 72dp 大标题槽，`cellH = gridAreaH / 6` 不封顶 |
| **D6** | 年历 12 张卡片各只有一个月名 + 一个点，卡片 128 × 168dp 里几乎全空 | 截图；`CalendarCanvas.kt:120` 注释记录了这个取舍 | §5.4：恢复迷你月历 |
| **D7** | 顶栏多一个日历图标按钮，iOS 无此物；月／周两态的图标完全一样 | `dump`：`[974,204][1046,276] desc='周视图'/'月视图'`，图标不变 | §5.7：删除；回月态改用顶栏左侧 `‹ 九月` |
| **D8** | 阅读页在"这一天没有日记"时**整屏空白**（只有顶栏和一条搜索框） | 实测截图 | §6.2：hero 卡片常驻，空态文案写在 hero 里（iOS 同） |
| **D9** | 横屏右栏标题重复"今天"：`9月20日 周日 丙午年八月初十 **今天**` 后面还有一枚 `今天` 按钮 | 实测截图 | §5.8：相对日文案（今天／昨天／明天）只在**没有**「今天」按钮时出现；分栏时右栏标题行只留 `今天` 按钮 |
| **D10** | 编辑页位置行文案重复：左侧标签 `重新获取位置` + 右侧按钮 `重新获取位置` | `dump`：`[144,404]` 与 `[932,402]` 同文案 | §7.3：左标签是**状态**（定位中…／未记录地点／地名），右按钮才是动作 |
| **D11** | 编辑格式栏缺"居中对齐" | `dump` 滚动后按钮序列：段落样式 / 引用 / 列表 / 待办 / B / I / S / U / 插入图片 | §7.4：补对齐；`content_json` 的 `align` 字段 iOS 能写，Android 不能写等于跨端编辑能力缺一块 |
| **D12** | 日格行分隔线在"整行都不是当月"时也画 | `CalendarDayGrid.kt:97` 的条件被 `showAdjacent=true` 短路 | §5.2：该行没有当月日期就不画分隔线 |
| **D13** | **上下滑翻月完全失效** | 手势只写在 `MonthPager` 里，而 `MonthPager` 是**死代码**（`CalendarSection` 从不调用它），月格上没有任何 `pointerInput` | §5.6：把手势接到真实图层上（月态 / 年态共用） |

### 2.3 属于"有意偏离"，本方案保留

| 项 | 为什么保留 |
| --- | --- |
| 日格是真实 Compose 组件（iOS 是整块 Canvas + 单一语义节点） | Android 上这是**改进**：TalkBack 能逐格读。不退回 Canvas。 |
| 动态取色（Material You） | 本轮已确认。iOS 的固定品牌色不作为 Android 默认。 |
| 导航 `NavigationBar` / `NavigationRail` | 平台约定；iOS 的系统浮条是 iOS 的等价物。 |
| 不引入 `material-icons-extended` | 35.7MB aar，`isMinifyEnabled = false` 下不划算；图标映射集中在一处。 |

---

## 三、设计令牌

### 3.1 颜色

| 项 | 做法 |
| --- | --- |
| 色板 | `MaterialTheme.colorScheme`；Android 12+ 且设置开启时 `dynamicLight/DarkColorScheme`（默认开启） |
| 回落 | 关闭或不支持时只覆写 `primary` 四角色（`FallbackLightScheme` / `FallbackDarkScheme`），其余取 M3 基线 |
| 正文 / 次要 | `onSurface` / `onSurfaceVariant` |
| 选中 / 今天 / 有日记标记 | `primary` |
| 选中日号 | `onPrimary` |
| 内容卡片 | `surfaceContainerLow` |
| 分隔线 | `outlineVariant`（行分隔线 `alpha = 0.35f`，卡片描边 `0.45f`） |
| 引用块底色 | `primaryContainer`（iOS 用 `quoteBg`；Android 用 M3 语义色，观感一致） |
| 时间胶囊 / 计数徽章 | `primaryContainer` 底 + `primary` 字 |
| 禁止 | 不写死十六进制；不做"玻璃/模糊"叠层 |
| 高对比 | 系统"高对比文字"开启时卡片描边 0.5dp/45% → 1.5dp/90%（`LocalIncreasedContrast`） |

**"有内容 / 无内容"不许只靠颜色承载**：一律同时有形状或文字差异（下划线、圆环、stateDescription）。

**对比度（硬性）**：正文 ≥ 4.5:1、大字（≥18.66sp 粗体或 ≥24sp）≥ 3:1（WCAG AA，Material 的
"Accessibility → color and contrast" 直接引用它）。**不用 `alpha` 压文字表达"次要"** ——
M3 的强调层级用语义色（`onSurface` / `onSurfaceVariant`）表达。实测反例：未来日期原先
`onSurface @35%`，浅色下只有 **2.19:1**；周末列 `onSurfaceVariant @60%` 只有 **3.16:1**
（而星期栏是 11sp 小字，需要 4.5:1）。现已全部改为语义色。

### 3.2 字体梯级

**两层字号，职责分开**：

1. **M3 组件的 15 个排版角色 = Material 官方字号**。`MaterialTheme.typography`
   直接取 `Typography()` 基线（displayLarge 57 / headlineLarge 32 / headlineMedium 28 /
   headlineSmall 24 / titleLarge 22 / titleMedium 16 / titleSmall 14 / bodyLarge 16 /
   bodyMedium 14 / bodySmall 12 / labelLarge 14 / labelMedium 12 / labelSmall 11 …），
   只把本项目的**逐档封顶动态字体**套上去。顶栏标题、对话框、`OutlinedTextField`、
   按钮、`NavigationBarItem`、芯片因此都是官方尺寸。
2. **自绘内容用 [TypeSize] 梯级**（日历格子、柱状图、分享长图、编辑器段落）。
   编辑器段落的 28 / 22 / 17 / 15 是**跨端阅读体验的契约**（与 iOS 的段落语义一一对应），
   刻意保留 Apple 的梯级；它们落在 M3 角色上的**最近邻**是
   headlineMedium / titleLarge / bodyLarge / bodyMedium。

> **首轮的错误**：把自定梯级直接挂到 M3 的 15 个角色上，于是 `titleLarge = 18`（官方 22）、
> `bodyLarge = 17`（官方 16）、`headlineSmall = 20`（官方 24）—— **所有 M3 组件都不是官方字号**。
> 现已拆开。

缩放走 `DynamicTypeMetrics`：`multiplier = min(ceiling(style), max(1, fontScale))`，
**`fontScale = 1` 时全部为 1**（默认外观与官方基线逐字一致），放大时按档封顶保证梯级永不倒挂。

日历内文字走**半速**（`calendarSp`）：格子尺寸由屏幕决定，全速放大必然溢出。

固定高度一律改 `minHeight`；单行不可换行处 `maxLines = 1` + `TextOverflow` 或自动缩小。

### 3.3 间距 / 圆角 / 触控目标

- 间距只用 `Spacing`：`page = 16` / `card = 12` / `rowIcon = 12`；
- 圆角**全部落在 M3 形状梯级上**。material3 1.4.0（M3 Expressive）的完整梯级是
  `none 0` / `extraSmall 4` / `small 8` / `medium 12` / `large 16` / `largeIncreased 20` /
  `extraLarge 28` / `extraLargeIncreased 32` / `extraExtraLarge 48` / `full`。M3 组件本来就走 `MaterialTheme.shapes`；**自绘**组件（日历格、柱状图、
  分享长图）用 `Radius` 令牌，取值同样对齐这条梯级 ——
  `card = 16`(large) / `panel = 28`(extraLarge) / `field = 12`(medium) / `image = 12`(medium) /
  `badge = 12`(medium) / `bar = 4`(extraSmall) / `hairline = 1`(自绘 2dp 标记线)；
  嵌套圆角走 `Radius.concentric(outer, inset)`；
- **最小触控目标 48dp —— Material 的硬性要求**：`Spacing.hitTarget = 48dp`，日格不小于它；
  更小的视觉元素（选中圆、图标）靠外层 padding 或
  `LocalMinimumInteractiveComponentSize` 补足。

> **首轮的形状与触控目标是 iOS 的数值**：`card 20` / `panel 26` / `field 14` / `image 14` /
> `hitTarget 44pt` —— 没有一个是 M3 梯级上的值，44dp 更是低于 Material 的下限。
> 视觉层既然走 Material，自绘组件也必须落在同一条梯级上，否则会出现
> "M3 卡片 12、日历 20"的错位。

- **状态层（涟漪）是硬性要求**：可交互元素必须有触摸反馈。首轮日历格与年历卡片写的是
  `indication = null`，**完全没有反馈**。现在日格给"以日号圆为直径的圆形涟漪"
  （同 M3 date picker 的圆形状态层），年历卡片给默认有界涟漪。
  **例外**：导出/导入的遮罩层（scrim）用 `indication = null` 是**正确**的 —— 它只负责吞掉点击。

### 3.4 动效令牌

**全部取 Material 3 的动效令牌**（数值与 material3 1.4.0 的 `MotionTokens` 逐字一致）：

| 令牌 | 值 | M3 令牌名 | 用途 |
| --- | --- | --- | --- |
| `MORPH_DURATION_MS` | **400** | `duration.medium4` | 月↔周、年↔月 morph（大面积容器变换） |
| `M3_EMPHASIZED` | `(0.2, 0, 0, 1)` | `easing.emphasized` | morph |
| `M3_EMPHASIZED_DECELERATE` | `(0.05, 0.7, 0.1, 1)` | `easing.emphasized.decelerate` | "进入"方向的大面积过渡 |
| `PAGE_SETTLE_DURATION_MS` | **300** | `duration.medium2` | 翻月 / 翻周 / 回到今天 |
| `QUICK_SETTLE_DURATION_MS` | **250** | `duration.medium1` | 分栏下点日期、toast |
| `REDUCED_MORPH_DURATION_MS` | **180** + 线性 | —（Android 无障碍约定） | 系统"减弱动态效果"开启时 |

> **首轮用的是 iOS 的曲线**：600ms + `timingCurve(0.2, 0.75, 0.3, 1.0)`。那是 Apple 的数值，
> 不属于 Material；按 Google 的令牌改成 `medium4 + easing.emphasized` 之后**不需要靠拉长时长
> 换顺滑**，所以时长从 600 降到 400。
> （`MaterialTheme.motionScheme` 在 material3 1.4.0 里仍是 `internal`，取不到，
> 因此按官方令牌值显式构造 `CubicBezierEasing`。）
| 弹簧（可选） | `snappy(0.3)` 的 Compose 等价 | 优先用 `tween` 保持与纯数学层一致 |

调试开关保留：`-slow-morph`（慢放，肉眼核对几何）、`-morph-log`（帧率日志）。

### 3.5 图标

沿用 `material-icons-core` 里语义最近的图标，映射集中在 `ui/root/AppTab.kt` 与
`ui/components/Components.kt` 各一处。所有 `IconButton` 必须有 `contentDescription`；
装饰性图标显式 `contentDescription = null`。

### 3.6 背景与层次

不用 iOS 的蓝色光晕背景（那是 iOS 品牌视觉的一部分，本方案不沿用）。
Material 3 的层次靠 `surface` / `surfaceContainerLow` / 阴影与描边表达：

- 页面底 = `colorScheme.surface`（`Scaffold` 默认）；
- 内容卡片 = `surfaceContainerLow` + 0.5dp `outlineVariant@45%` 描边；
- 日历整块**不套卡片**（与 iOS 一致：日历是版面本身，不是卡片）。

### 3.7 Material 3 合规核对表

规范：以 Google 官方的 Material 3 为准（[m3.material.io](https://m3.material.io) 与
`androidx.compose.material3` 1.4.0 的令牌实现）。逐项核对结果：

| # | 项目 | Google 官方要求 | 本项目现状 | 判定 |
| --- | --- | --- | --- | --- |
| 1 | 最小触控目标 | **48dp**（[Accessibility](https://m3.material.io/foundations/accessible-design/accessibility-basics)） | `Spacing.hitTarget = 48dp` | ✅ 本轮修正（原 44dp 是 iOS HIG） |
| 2 | 形状梯级 | `extraSmall 4 / small 8 / medium 12 / large 16 / largeIncreased 20 / extraLarge 28 / extraLargeIncreased 32 / extraExtraLarge 48 / full`（[Shape](https://m3.material.io/styles/shape/shape-scale-tokens)） | `Radius`：`card 16` / `panel 28` / `field 12` / `image 12` / `badge 12` / `bar 4` | ✅ 本轮修正。**精确说明**：原来的 `card 20` 其实**是**官方令牌（`largeIncreased`，Expressive 新增）；但 `panel 26`、`field 14`、`image 14` **不在梯级上**。现在统一到 `large / extraLarge / medium`，与 M3 的卡片族取同一档 |
| 3 | M3 组件字号 | 15 个角色的官方 type scale（[Type scale](https://m3.material.io/styles/typography/type-scale-tokens)）。**注意 M3 Expressive 修订过部分角色**，且每个角色还有 `Emphasized` 变体 | `MaterialTheme.typography` = **`Typography()` 基线**（直接取库里的官方值，因此自动跟随 Expressive 的修订）+ 本项目的逐档封顶缩放 | ✅ 本轮修正。原先把自定梯级挂到角色上，于是 `titleLarge = 18`（官方 22）、`bodyLarge = 17`（官方 16）、`headlineSmall = 20`（官方 24）—— 顶栏 / 对话框 / 输入框 / 按钮全都不是官方尺寸 |
| 4 | 自绘内容字号 | 不受 type scale 约束，但应有据可依 | `TypeSize` 梯级；编辑器段落 28/22/17/15 是**跨端阅读契约**，最近邻为 headlineMedium / titleLarge / bodyLarge / bodyMedium | ⚠️ 有意保留（内容层，见 §3.2） |
| 5 | Small top app bar | 高度 **64dp**（[Top app bar](https://m3.material.io/components/top-app-bar/specs)） | `CalendarLayout.HEADER_H = 64f` | ✅ 本轮修正（原 52dp 是自定值） |
| 6 | 动效时长 / 缓动 | `duration.medium4 = 400ms`、`easing.emphasized = (0.2, 0, 0, 1)`（[Easing & duration](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs)） | morph 400ms + emphasized；翻页 300ms(medium2) / 250ms(medium1) | ✅ 本轮修正（原 600ms + iOS 曲线） |
| 7 | 状态层 / 涟漪 | 可交互元素必须有触摸反馈（[States](https://m3.material.io/foundations/interaction/states/state-layers)） | 日格 = 圆形涟漪；年历卡片 = 有界涟漪；遮罩层显式无反馈（正确） | ✅ 本轮修正（原日格/年历 `indication = null`，完全无反馈） |
| 8 | 文字对比度 | 正文 ≥ 4.5:1、大字 ≥ 3:1（[Accessibility](https://m3.material.io/foundations/accessible-design/accessibility-basics)） | 全部改用语义色，不用 alpha 压文字 | ✅ 本轮修正（原未来日期 2.19:1、周末列 3.16:1） |
| 9 | 不用颜色单独承载信息 | 必须同时有形状/文字/语义 | 有日记 = 下划线；今天 = 圆环；选中 = 实心圆；状态进 `stateDescription` | ✅ |
| 10 | 底部导航 | 3–5 个目的地；`NavigationBar` 紧凑 / `NavigationRail` ≥ 600dp | 4 个；600dp 切 `NavigationRail` | ✅ |
| 11 | 窗口尺寸类 | compact < 600 / medium 600–839 / expanded ≥ 840（[Window size classes](https://m3.material.io/foundations/layout/applying-layout/window-size-classes)） | 导航按 600dp 切；首页分栏按 **700dp 且宽 > 高**（比 M3 的 840dp 更早分栏，因为日历 + 正文列各需最小宽度） | ⚠️ 有意偏离，理由见 §5.8 |
| 12 | 表面层级 | 用 `surfaceContainer*` 色调层级，而不是阴影（[Elevation](https://m3.material.io/styles/elevation/overview)） | 页面 `surface`、卡片 `surfaceContainerLow`、遮罩内 `surfaceContainerHigh` | ✅ |
| 13 | 颜色角色 | 用语义角色、不写死十六进制 | `MaterialTheme.colorScheme` + 动态取色，零写死 | ✅ |
| 14 | 动态取色 | Material You 默认开启（Android 12+） | `dynamicLight/DarkColorScheme`，设置里可关 | ✅ |
| 15 | 无障碍语义 | 交互元素有 `contentDescription`；装饰性图标为 `null`；状态可读 | 日格逐格可读、结果行带 Button 特质、装饰图标显式 null | ✅ |
| 16 | 减弱动效 | 尊重系统"减弱动态效果" | 180ms 线性（morph）/ 不做 morph | ✅ |

**判定汇总**：16 项里 **13 项 ✅**、**3 项 ⚠️ 有意偏离**（自绘内容字号、分栏阈值、以及第 4 项
的编辑器段落梯级）。三项都写在方案里、有理由、可复核 —— 这是"按 Google 规范"与
"与 iOS 版功能/阅读体验对齐"两条约束的**已知交点**，不是遗漏。

### 3.8 仍然偏离、需要产品决策的点

1. **编辑器段落梯级 28/22/17/15** 保留 Apple 的数值（跨端阅读体验一致）。若要完全按 M3，
   应改为 headlineMedium 28 / titleLarge 22 / bodyLarge **16** / bodyMedium **14**
   —— 那会让 Android 与 iOS 的阅读版式出现可见差异。**默认不改**。
2. **首页分栏阈值 700dp**（M3 建议 840dp 起分栏）：日历 7 列至少要 280dp、正文列至少 320dp，
   相加 ≈ 700dp，手机横屏的可用宽 750dp 正好落进分栏一侧。按 840dp 的话手机横屏永远不会分栏，
   与 iOS 版行为不一致。
3. **周起始日、农历、干支生肖**：Material 没有对应规范（属于内容/区域设置），按 iOS 版与
   应用语言处理。

---

## 四、信息架构与导航

| 项 | 规范 |
| --- | --- |
| 标签 | 4 个，顺序固定：**日记 / 足迹 / 搜索 / 设置** |
| 图标 | 日记 `Edit`、足迹 `Place`、搜索 `Search`、设置 `Settings` |
| 紧凑（宽 < 600dp） | `NavigationBar` 置底，`NavigationBarItem.selected` 表达当前页 |
| 中等 / 扩展（宽 ≥ 600dp） | `NavigationRail` 置侧 |
| 顶栏 | 每个标签页各自持有；不共用全局 `TopAppBar` |
| 系统占位 | 一律 `WindowInsets.safeDrawing` / `safeContent`；`Scaffold` 的 `innerPadding` **必须消费** |
| 页面底距 | 内容列表底部预留导航栏高度（`innerPadding`），不要写死 96dp |

---

## 五、首页规范（本轮重点）

### 5.1 三态与几何

三种形态由同一个状态机驱动，**互斥且完整**：

| 形态 | 版面 | 日记内容 |
| --- | --- | --- |
| **月态** | 顶栏 64 + 大标题槽 72 + 星期栏 30 + 固定 6 行网格 | 无 |
| **周态** | 顶栏 64 + 星期栏 30 + 周条 68 + 日期标题行 40 + 内容区 | 有（占剩余全部高度） |
| **年态** | 顶栏 64 + 年份标题行 62 + 3 × 4 迷你月卡片 | 无 |

**几何公式**（`CalendarLayout`）：

```
headerH      = 64                       // M3 small top app bar 的官方高度
areaH        = 可用高 − headerH          // 可用高来自 WindowInsets + BoxWithConstraints
bigTitleH    = 72                       // 只在月态存在
weekdayH     = 30
gridAreaH    = areaH − bigTitleH − weekdayH
cellH        = gridAreaH / 6            // ★ 不封顶，与 iOS 同构
rows         = 当月实际周数(4…6)         // 只画 rows 行；剩余 1 行留白是设计而非缺陷
weekStripH   = 68
dayTitleH    = 40
yearTitleH   = 62
yearCardW    = (内容宽 − 2×16 − 2×gutter 6) / 3
yearCardH    = (areaH − yearTitleH − 2×8 − 3×6) / 4
```

参考设备（426.7 × 952dp，状态栏 24dp，顶栏 64dp）落值：

| 量 | 值 |
| --- | --- |
| `areaH` | 952 − 24 − 64 = 864 |
| `gridAreaH` | 864 − 72 − 30 = 762 |
| **`cellH`** | **127** |
| 6 行网格高 | 762（**正好铺满**，无空白带） |
| 5 行网格高 / 留白 | 635 / 127 |
| `yearCardW × yearCardH` | 127.6 × 192 |
| 内容宽（分栏时右栏） | 见 §5.8 |

> **为什么 `cellH` 不封顶**：首轮封顶 96dp 的后果就是 §2 的 D5 —— 6 行只占 576dp，
> 屏下方空出 270dp。iOS 的规则是 `(区高 − 72 − 30) / 6`，行高**跨月恒定**，
> 6 行月正好铺满、5 行月正好留一行白。留白是可预期的"呼吸"，而不是"没排满"。
>
> **为什么不按当月实际行数等分**（`cellH = gridAreaH / rows`）：那样 5 行月是 155dp、
> 6 行月是 129dp，**翻月时整块版面会上下抖一下**，而且同一格日号在不同月份的位置不一致。
> 恒定行高换来的稳定性比"铺满"更重要 —— 这是 iOS 的选择，本方案沿用。

```
竖屏 · 月态（6 行月，铺满）          竖屏 · 周态（周条 + 内容）
┌──────────────────────────┐        ┌──────────────────────────┐
│ ▾ 2026年          今天    │        │ ‹ 九月            今天    │  ← 64
├──────────────────────────┤        ├──────────────────────────┤
│ 九月                      │  ← 72  │  一  二  三 …  六  日     │  ← 30
│  一  二  三  四  五  六  日│  ← 30  ├──────────────────────────┤
│  1   2   3   4   5   6   7│        │ 14  15  16  17 [18] 19 20│  ← 68
│  8  …                    │        ├──────────────────────────┤
│  …                        │        │ 9月18日 周五 · 八月初八   │  ← 40
│  …（共 6 行 × 129dp）     │        ├──────────────────────────┤
│  …                        │        │  ▌日记卡片 / 空态 + 写日记│
│  …                        │        │  （占剩余全部高度）        │
└──────────────────────────┘        └──────────────────────────┘
```

### 5.2 月态

**只画当月日期。** 相邻月的位置**留空**：不绘制、不可点、不进无障碍树。
这不是"淡显"，是"没有"——iOS 就是这么做的（`showAdjacent: false`）。

- 星期栏：7 列，文案跟随「一周从哪一天开始」设置；周末列用 `onSurfaceVariant`（其余 `onSurface`）；
- 首行第一列不一定是 1 号，之前的格子为空白；
- **行分隔线**：1dp `outlineVariant @35%`，画在第 2 行起的行上边界；**该行含至少一个当月日期时才画**（D12）；
- **不画竖线**。

日格（每格一个真实 Compose 组件）：

| 项 | 规范 |
| --- | --- |
| 尺寸 | 宽 `1/7`，高 `cellH`（≥ 48dp） |
| 内容（自上而下） | 选中/今天圆底 → 日号 → 农历 → "有日记"下划线 |
| 日号字号 | `clamp(cellH × 0.34, 13, 20)` sp，**半速缩放** |
| 农历字号 | `clamp(cellH × 0.18, 9, 11)` sp，半速缩放；**仅应用语言为中文时显示该行** |
| 圆底直径 | `min(cellW − 3, 内容高 + 14, cellH − 3)` |
| 状态 · 普通 | 日号 `onSurface` |
| 状态 · 今天（未选中） | 1.5dp `primary` 描边圆环；日号与农历 `primary` |
| 状态 · 选中 | `primary` 实心圆 + `onPrimary` 日号（`FontWeight.SemiBold`） |
| 状态 · 有日记 | 日号下方 2dp 圆角短横线，宽 `min(20, cellW × 0.5)`，`primary`（选中时 `onPrimary`） |
| 状态 · 未来 | 整个日格 `alpha = 0.35`（**仍可点**，点了只选中并进周态） |
| 农历文案 | 初一…三十；初一显示月名（正月…腊月，闰月带"闰"） |

### 5.3 周态

自上而下：

1. 顶栏 64：左 `‹ 九月`（点它 = morph 回月态）；右 `今天`；
2. 星期栏 30（与月态同一个组件、同一位置，位置由 morph 插值决定）；
3. 周条 68：7 格，选中日靠 `primary` 实心圆；**不画行分隔线**；
4. 分隔线（1dp `outlineVariant@35%`）；
5. 日期标题行 40：`9月18日 周五`（15sp semibold）+ `Spacer` + `丙午年八月初八`（12sp `onSurfaceVariant` 80%）；
6. 分隔线；
7. 内容区（`weight(1f)`，**高度必须由 weight 独占**，见 §5.9 的坑）：
   - 有日记：卡片列表（见下）；
   - 无日记：空态（图标 + 文案 + **「写日记」按钮**，只有当天可写时出现按钮）。

> **不要给内容区套 `fillMaxSize` 的 Row + `weight(1f)`**：高度被覆盖后内容会被摊到屏幕底部，表现为"下方面板全空"。

日记卡片（列表项，间距 16dp，左右页边距 16dp）：

```
┌────────────────────────────────────┐  Radius.card(20)
│ 🕐 07:20   📍 浙江省杭州市西湖区·北山街 │  12sp onSurfaceVariant
│ 清晨的湖边                          │  大标题 28sp
│ 六点半就醒了，天还没大亮。…          │  正文 17sp
│ ┃雾散得比想象中快。                  │  引用 15sp，primaryContainer 底
└────────────────────────────────────┘
```

- 卡片 = `surfaceContainerLow` + 0.5dp 描边 + 12dp 内边距；
- 点卡片的**元信息行或正文** → 进日记页；
- 点正文里的内联图片 → 全屏预览（点按或下滑 >140dp 关闭）。

### 5.4 年态

**恢复 iOS 的 12 个迷你月历**（这是 D6 的修法，也是年月 morph 的落点）：

- 年份标题行 62：`2026年`（32sp bold，`primary`）+ 右侧生肖 `丙午马年`（12sp `onSurfaceVariant@70%`，仅中文）；
- 3 列 × 4 行，卡片间距 6dp，外边距 16dp；
- **每张卡片 = 一个整体点击目标**（点它 = 缩放 morph 进该月），卡片内部：
  - 月份名（迷你标题，15sp semibold；当前月用 `primary` 加粗）；
  - 迷你月网格 6 行 × 7 列：日号字号 `clamp(cellW × 0.95, 9, 14)`，**无农历**；
  - 有日记的日期：日号下方 2dp 下划线（`primary`）；今天：实心小圆点；
  - 相邻月不画；
- 卡片本身**不描边、不填色**（iOS 也不填）——年历是"12 张小日历"，不是 12 张卡片；
- 无障碍：整张卡片一个节点，`contentDescription = 月份名`，`stateDescription = 有记录 / 无记录`；迷你日号是装饰性内容，不逐个朗读。

> **为什么年历仍可用 Canvas 画日号**：月格里**每一天**都是点击目标，必须逐格暴露；
> 年历的 12 张卡片各自只有**一个**点击目标，迷你网格是装饰。两者要求不同，实现可以不同。
> 但绘制用的矩形、命中用的矩形、语义用的矩形必须**同源**（同一组 `yearCardRect`）。

### 5.5 morph 规范

统一记法：`p` = 动画进度（0 → 1），morph 容器坐标原点 = **日历区顶部（顶栏之下）**。

#### 5.5.1 月 ↔ 周（600ms，`timingCurve(0.2, 0.75, 0.3, 1.0)`）

| 量 | 插值 |
| --- | --- |
| 大标题槽 | `y = −72 × p`，`alpha = 1 − clamp01(2p)` |
| 星期栏 | `y = lerp(72, 0, p)` |
| 选中行 | `top = lerp(102 + row × cellH, 30, p)` |
| 选中行度量 | 月格 → 周条：`cellH` 由 `cellH` 插值到 68，字号跟着 `lerp` |
| 上方行 | `y = (102 + row × cellH) − p × (row × cellH + cellH)`，`alpha = 1 − clamp01(1.4p)` |
| 下方行 | `y = (102 + row × cellH) + p × (rows × cellH − row × cellH)`，`alpha` 同上 |
| 内容区 | `contentT = clamp01((p − 0.25) / 0.75)`；`y = 138 + 60 × (1 − contentT)`；`alpha = clamp01((p − 0.3) / 0.5)` |

**不变量**：`p = 1` 时选中行的位置与高度必须**等于真实周条图层**的位置与高度（`30` / `68`），
`p = 0` 时必须等于真实月态网格（`102 + row × cellH` / `cellH`）。任何一处再从别处算一遍，收尾就会跳。

#### 5.5.2 年 ↔ 月（600ms，同曲线）

`t = 1 − p`（`t = 0` 是年历端点、`= 1` 是月历端点）：

| 量 | 插值 |
| --- | --- |
| 网格矩形 | `lerp(miniGridRect(源卡片), fullMonthGridRect, t)`，再按 7 列 × 6 行重新分格 |
| 日号字号 | `lerp(miniDayFont, monthDayFont, t)` |
| 农历行 alpha | `clamp01((t − 0.5) / 0.5)` |
| 行分隔线 alpha | `clamp01((t − 0.55) / 0.45)` |
| 年层 | `opacity = clamp01((p − 0.45) / 0.55)`；`scale = 1 + (1 − p) × 0.6`，锚点 = 源卡片中心 |
| 源卡片月份标题 | `alpha = clamp01((p − 0.7) / 0.3)` |

**不变量**：`fullMonthGridRect` 必须取自**行高封顶/派生之后**的真实网格矩形；用"未派生的区高"算会让终点 `cellH` 对不上，收尾跳一下（首轮踩过）。

### 5.6 手势与命中

| 动作 | 结果 |
| --- | --- |
| 点顶栏年月（月态） | 缩放 morph 进年历 |
| 点顶栏 `‹ 九月`（周态） | morph 回月态 |
| 点顶栏 `‹ 2026年`（年态） | 回月态 |
| 点当日格子（月态） | **整屏 morph 进周态**（选中该日） |
| 点当日格子（周态） | 选中该日并重载内容 |
| 点空白格（非当月） | 无动作（格子不存在） |
| 点迷你月卡片（年态） | 缩放 morph 进该月 |
| 上下滑（月态） | 翻月；选中日**日期不变**（不是"改到月初"） |
| 上下滑（年态） | 翻年 |
| 左右滑（周态） | 翻周（±7 天） |
| 点 `今天` | 回到今天：年态 → 当月；月态 → 选中今天；周态 → 选中今天，均重载 |
| 未来的日期 | 可选中、可进周态；内容区显示未来空态（iOS 的 toast 在代码里从未被调用，Android 不引入 toast） |
| morph 进行中 | 忽略新输入（pager 禁用、基础层不构建） |

翻页数学沿用 iOS：`minimumDragDistance = 8`，橡皮筋 `rubber(x) = c·x/(x+c)`、`c = pageSize × 0.25`，
提交阈值 `predictedEnd < −90` 或 `translation < −60`；
**翻页收尾必须把"换页"与"偏移清零"放进同一个无动画事务**，否则会先渲染一帧新页再滑回来，观感是"末尾跳变"。

### 5.7 顶栏

| 形态 | 左 | 右 |
| --- | --- | --- |
| 月态 | `▾ 2026年`（`titleLarge`，整块可点 → 年历） | `今天`（`TextButton`） |
| 周态 | `‹ 九月`（整块可点 → 回月态） | `今天` |
| 年态 | `‹ 2026年`（整块可点 → 回月态） | `今天` |

- 高度 `minHeight = 64dp`（**M3 `small top app bar` 的官方高度**；首轮取的是自定的 52dp）；
- **删掉日历图标按钮**（D7）。回月态的入口是"周态顶栏左侧"，不是第二个图标；
- 分栏（横屏/宽窗口）时整块顶栏隐藏（见 §5.8）。

### 5.8 横屏 / 宽窗口分栏

判据只有一条：**宽 ≥ 700dp 且 宽 > 高** → 左右分栏。不看机型、不看 `orientation`。

| 项 | 规范 |
| --- | --- |
| 左栏（日历） | 宽 `clamp(内容宽 × 0.46, 280, 440)`；三态都在左栏；**不提供年历入口**，只有上下滑翻月 |
| 分隔线 | 1dp `outlineVariant@40%` |
| 右栏（选中日） | 占剩余宽；正文列**限宽 560dp** 并居中 |
| 标题行 | 44dp：`9月20日 周日` + 农历 + `Spacer` + `今天` 按钮 |
| 面板高 | 左右两栏等高，都不压到底部导航 |
| 密度降级 | `perRow = 可用高 / rows`：`≥ 60` → 显示农历；`≥ 44` → 不显示农历；否则 → 周条（1 行，横向翻周） |

**D9 的修法**：右栏标题行里**只保留 `今天` 按钮**；相对日文案（今天／昨天／明天）只在没有该按钮的位置出现。
左栏日历**不显示**顶栏与大标题（高度太紧），只留 34dp 月份标题 + 26dp 星期栏。

### 5.9 实现上踩过的坑（写进规范）

1. **不能给日历带固定高度却让容器 `fillMaxSize`** —— 容器与内容脱节，年层的第 4 行卡片会压住下方内容；
2. **不能用 `fillMaxSize` 的 Row 装 `weight(1f)` 的预览** —— 预览会被摊到屏幕底部；
3. **任何 `coerceIn` 都要证明区间不反转**：矮窗口里 `max(217) < min(240)` 会抛异常（横屏崩溃就是这么来的）；
4. **可见性不要挂在动画值上**（D2）。
5. **月层的高度必须"无约束"地量，由外层色带裁剪**。周态的色带只有 98dp（星期栏 30 + 周条 68），
   而月层天然要 `72 + 30 + 网格 ≈ 640dp`。用普通 `Column` 时它会被父约束压缩，**最后一个子项
   （网格容器）分到 0 高度 → 周条完全不渲染**（本轮重写时踩到，实测整条周条消失）。
   正确写法：月层 `Modifier.wrapContentHeight(unbounded = true, align = Alignment.Top)`，
   外层色带 `clipToBounds()` 负责裁掉移出的行。
6. **分栏的三块必须在 `Row` 里**。放进 `Column` 会被按**高度**分配：表现为"日历占满左半边、
   右栏整块消失"（本轮重写时踩到）。
7. **日格要 `fillMaxHeight()`**。不写时 `Box` 只包住内容（日号 + 农历 ≈ 70dp），
   于是内容贴在行顶、下面空一截，分隔线看起来离内容很远（实测 `dump` 里日格 210px vs 行距 324px）。

### 5.10 无障碍

- 月/周态的日格：**每格一个可聚焦节点**，`role = Role.Button`：
  - `contentDescription` = 完整日期（`2026年9月15日 周二`）；
  - `stateDescription` = `今天` / `已选中` / `有日记` / `无日记` 的组合；
  - `selected` 语义属性用于"今天/选中"；
  - 自定义动作：`上一天` / `下一天`；
- 年态：每张迷你月卡片一个节点（`contentDescription` = 月份名）；
- 空白格**不进无障碍树**（`clearAndSetSemantics {}`）；
- 星期栏是普通文本，不合并；
- 触控目标 ≥ 48dp；不使用"仅靠颜色"的状态表达。

---

## 六、日记阅读页

### 6.1 结构（竖屏）

```
┌──────────────────────────────────────┐
│ ←           🕐搜索   ✎写日记   ⤴分享   │  ← 64，图标动作 + contentDescription
├──────────────────────────────────────┤
│ 9月20日 周日  [今天]                   │  ← hero：日期 20sp medium
│ 🕐 08:23 开始写 · 2 个片段             │     13sp onSurfaceVariant
│ 📍 浙江省杭州市西湖区 · 北山街           │     13sp onSurfaceVariant
├──────────────────────────────────────┤
│ ┌──────────────────────────────────┐ │
│ │ 08:23  📍 北山街                  │ │  ← 时间 13sp bold primary
│ │ 清晨的湖边                        │ │  ← 大标题 28sp
│ │ 六点半就醒了…                     │ │  ← 正文 17sp
│ │ ┃雾散得比想象中快。                │ │  ← 引用 15sp，primaryContainer 底
│ └──────────────────────────────────┘ │
│  …（卡片间距 12dp）                    │
└──────────────────────────────────────┘
```

- 卡片 = `surfaceContainerLow` + 0.5dp 描边 + 12dp 内边距 + `Radius.card`；
- 正文列**限宽 660dp** 居中（竖屏就是"屏宽 − 左右各 16"）；
- 底部预留导航栏高度（`innerPadding`），不写死数值。

### 6.2 空态（D8 的修法）

英雄卡片**常驻**，即使当天没有内容。空态文案写进 hero（"这一天没有留下日记。"），
**不给整屏空白**——现在的实现只画了顶栏和一条搜索框。可写时在 hero 下方给「写日记」按钮。

### 6.3 正文样式（与 iOS 一致，跨端可读性契约）

| 段落样式 | 字号 | lineSpacing | paragraphSpacing |
| --- | --- | --- | --- |
| 大标题 | 28 | 0.13 × 字号 | 0.4 × 字号 |
| 小标题 | 22 | 0.13 × 字号 | 0.3 × 字号 |
| 正文 | 17 | 0.13 × 字号 | 0 |
| 引用 | 15 | 0.5 × 字号 | 0.4 × 字号，`primaryContainer` 底，强制左对齐 |
| 列表 / 待办 | 17 | 0.13 × 字号 | 0 |

行内：粗体 / 斜体 / 删除线 / 下划线；对齐取 `content_json` 的 `align`。
**图片与地图可以与正文并排** —— 那是"并排的介质"，不是"并排的文字"。

内联图片：宽 `min(max(40, 容器宽), 存储宽)`，等比；`Radius.image(14)`；点按进全屏预览。

### 6.4 页内搜索

搜索框在内容顶部（滚动区第一行），最小高 46dp；命中计数徽章 `i/n` 用
`primaryContainer` 胶囊；上一个/下一个匹配是**普通按钮**（不要"玻璃上叠玻璃"），
`contentDescription` = `上一个匹配` / `下一个匹配`。防抖 150ms。

---

## 七、日记编辑页

### 7.1 结构

```
┌──────────────────────────────────────┐
│ ←                 🖼插入图片  放弃修改  保存 │  ← 64
├──────────────────────────────────────┤
│ ┌──────────────────────────────────┐ │
│ │ [08:23]                          │ │  ← 时间胶囊：13sp bold primary
│ │ 📍 定位中…  /  北山街  ⌄           │ │  ← 状态标签 + 动作
│ │                                   │ │
│ │ 开始记录这一刻的想法…              │ │  ← 正文 17sp，minHeight 160 × 倍率
│ └──────────────────────────────────┘ │
│  （键盘弹起时格式栏贴在键盘上方 8dp）    │
├──────────────────────────────────────┤
│ [正文⌄] │ 居中 │ 列表 │ 引用 │ 待办 │ B I S U │
└──────────────────────────────────────┘
```

### 7.2 顶栏

左 `←`（有未保存内容时弹确认"放弃修改？"）；右三个动作：
`插入图片`（相册单张）、`放弃修改`、`保存`。横屏/宽窗口时动作收到一行里。

### 7.3 位置行与时间（D10、D3 的修法）

- **时间胶囊**：`自动插入时间`开启时，打开编辑器即插入当前时间的胶囊（`primaryContainer` 底 + `primary` 字，13sp bold）；关闭时显示"添加时间"动作按钮。这是 D3——现在这个开关完全没被消费。
- **位置行**：**左边是状态、右边是动作，两者不许同文案**（D10）：
  - 状态：`定位中…`（`primary`）/ 无位置时 `未记录地点`（`onSurfaceVariant`）/ 已有位置时显示地名；
  - 动作：`重新获取位置`（只在新建片段时出现）。

### 7.4 格式栏

顺序固定：`段落样式菜单` → `居中对齐` → `列表` → `引用` → `待办` → `B` `I` `S` `U`。

- 补齐 `居中对齐`（D11）：`content_json` 的 `align` iOS 能写，Android 不能写等于跨端能力缺一块；列表/引用/待办下禁用对齐（互斥）；
- 段落样式菜单只给 `大标题 / 小标题 / 正文`（引用由独立按钮切换，与 iOS 一致）；
- 位置：键盘弹起时贴键盘上方 8dp；收起时贴底部安全区 + 12dp；**不要写死 84dp**；
- 横屏/宽窗口：格式栏竖排贴右侧；
- 每个按钮 `minWidth 34dp × minHeight 40dp`，激活态用 `primary` 填充。

### 7.5 编辑不变量（不许破坏）

- 字号**不落库**；落库的是块类型（`style`）与行内标志；字号每次加载由样式重新推导；
- 编辑器内部持有的必须是**设计字号**（28/22/17/15），不是绘制字号；
- 打开已有片段再原样保存，必须字节级不变（现有单测守住，不许放宽）。

---

## 八、足迹

```
足迹                                   已标注 14 处 · 未记录 3 篇   ← 24sp 标题 + 14sp
[全部] [2026] [2025] [2024] [自定义]                          ← FilterChip 横向滚动
┌──────────────────────────────────────────────────────────┐
│ 7     9     4      14      13                            │  ← 18sp semibold / 12sp
│ 省市  城市  国家   片段    天数                            │
└──────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────┐
│ 年度记录天数                                              │  ← 13sp onSurfaceVariant
│  6 ┤        █                                            │
│  3 ┤  █     █     █                                      │
│  0 ┼──┴─────┴─────┴──                                    │
│     2024  2025  2026                                     │
└──────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────┐
│ 地点清单                                                  │
│  🌏 中国                                          9  ›    │
│    🗺 浙江省                                       5  ›    │
│      📍 杭州市                                     3      │
└──────────────────────────────────────────────────────────┘
```

| 项 | 规范 |
| --- | --- |
| 标题行 | `足迹` 24sp + 右侧一句统计（14sp `onSurfaceVariant`） |
| 年份筛选 | `FilterChip` 横向滚动；`全部` + 各年份 + `自定义`（开时间范围选择器） |
| 统计卡 | 5 等分，数值 18sp semibold / 标签 12sp；**整格合并成一个语义节点**（"省市 7"），不要拆成两个 Text |
| 趋势卡 | 只在年份数 ≥ 2 时出现；柱 `Radius.bar(4)`，`primary`；Y 轴刻度 12sp；**补无障碍描述**（"年度记录天数"） |
| 地点清单 | 树形：国家 → 省 → 市；深度缩进 16dp/级；叶子节点无 chevron；计数 12sp tabular |
| 空态 | 图标 + `还没有地点记录`，垂直居中 |
| 宽窗口 | 趋势图与地点清单**并排**（图表 flex、清单固定 240–320dp），不做左右主从分栏 |

---

## 九、搜索

### 9.1 结构

```
搜索                          [找到 3 条日记]     ← 24sp 标题 + 计数徽章
┌────────────────────────────────────────────┐
│ 🔍 搜索日记内容…                    取消    │  ← 最小高 46dp
└────────────────────────────────────────────┘
┌────────────────────────────────────────────┐
│ 时间范围                                    │
│ [全部时间] [本周] [本月] [今年]              │
│ 地点                                        │
│ [全部地点] [未记录地点] [选择地点…]          │
└────────────────────────────────────────────┘
[关键词 ✕] [本周 ✕] [中国 ✕]              ✕清除全部   ← 条件汇总行（最小高 44dp）
┌────────────────────────────────────────────┐
│ [9月20日]  六点半就醒了，天还没大亮…   08:23 ›│
└────────────────────────────────────────────┘
```

### 9.2 搜索框

`OutlinedTextField` 或 `SearchBar`，最小高 46dp；获得焦点时右侧出现 `取消`；
`SearchBar` 语义必须正确（现在无焦点时缺语义）。防抖 250ms，回车**提交为关键词芯片**（不是直接搜）。

### 9.3 筛选

时间与地点两组，各用 `FilterChip`；宽窗口时两组**并排**（省下约 60dp 竖高）。
互斥取值（自定义时间范围、地点选择）用 `ModalBottomSheet`，**不用 `AlertDialog`**。

### 9.4 条件汇总行

按顺序：[实时关键词] [已提交关键词…] [时间] [地点]，每个是一条 `primaryContainer` 胶囊 + `✕`，
点了单独移除；最右是 `清除全部条件`。没有条件时整行不显示。

### 9.5 空态 / 加载态（D1 的修法）

| 情形 | 显示 |
| --- | --- |
| 正在重建索引（**真的在跑**） | 进度指示 + `正在准备搜索索引…` |
| 有条件但无结果 | `没有找到相关日记` + `清除全部条件` 按钮 |
| 无关键词无条件 | 图标 + `搜索你的日记` + `输入关键词，或选择时间与地点筛选` |

**关键**：`indexNotReady` 不能等价于 `total < 0`。空查询时 `total` 必须是 0，
否则永远落进第一行（D1，实测就是这样）。

### 9.6 结果行

日期胶囊（`primaryContainer`，12sp）+ 摘要 13sp（命中词 `primary` + `primaryContainer` 底）+ 右侧时间 12sp + chevron；
整行 ≥ 48dp，**给 Button 语义**（现在是 `onTapGesture`，没有按钮特质）。
点击进该日日记页；底部"加载更多"；向下滚动收起搜索框与筛选面板。

---

## 十、设置

单列卡片，竖屏与宽窗口**共用**（设置项之间没有主从关系，不做左右两页）。

```
设置
┌─ 通用 ────────────────────────────────┐
│ [🌐] 语言                   跟随系统 › │
│      应用内显示语言                     │
│ [🎨] 主题                   跟随系统 › │
│      切换浅色、深色或跟随系统            │
│ [❤] 动态取色                    [ ●]  │
│      跟随壁纸取色（Material You）       │
└───────────────────────────────────────┘
┌─ 日记规则 ─────────────────────────────┐
│ [🌅] 新一天的开始时间         凌晨 4:00 ›│
│ [📅] 一周从哪一天开始            周一 › │
│ [🕐] 自动插入时间                [ ●]  │  ← 当前是死开关（D3）
│ [📍] 自动插入地点                [ ●]  │
│ [🔒] 位置权限                  未授权 › │
│ [✏] 允许修改 / 删除历史日记        [  ] │
└───────────────────────────────────────┘
┌─ 提醒 ─ 数据 ─ 关于 ───────────────────┐
```

| 项 | 规范 |
| --- | --- |
| 分组标题 | 13sp medium `onSurfaceVariant`，压在卡片内顶部 |
| 行 | 图标徽章 38 × 38（`primaryContainer` 底、`Radius.badge`，**必须保持正方形**）+ 标题 15sp + 副标题 13sp + 尾部取值 15sp + chevron |
| 行高 | `minHeight 56dp`；分隔线从文字起点开始（缩进 62dp） |
| 互斥取值 | 「行 + `DropdownMenu` + 勾选」，**不用 `AlertDialog`** |
| 破坏性确认（放弃修改、删除、覆盖导入） | `AlertDialog` |
| 开关 | `Switch`，整行 `toggleable`，`contentDescription` = 标题 |
| 尾部取值 | `maxLines = 1` + 自动缩小；**右对齐截断风险要实测** |
| 时间选择 | 小时轮盘用应用自己的时间文案（跟随 12/24 小时制）；提醒时间用 `TimePicker` |
| 忙碌遮罩 | 导出/导入时半透明遮罩 + 进度 + 说明，`Scrim` 之上不可点 |

---

## 十一、无障碍验收口径

- 日历：TalkBack 逐格可读（§5.10），空白格不可聚焦；
- 所有 `IconButton` 有 `contentDescription`；装饰性图标显式 `null`；
- 触控目标 ≥ 48dp（日格、列表行、筛选芯片、设置行）；
- "减弱动态效果"（`ValueAnimator.areAnimatorsEnabled() == false`）时 morph 降到 180ms 线性；
- 统计数字、图表、日历状态**不只靠颜色**表达；
- 语言切换后所有文案（含日期、农历、相对日）跟随应用语言，设备 12/24 小时制跟随设备。

---

## 十二、实施顺序

每一批结束都必须能**单独上机验证**，不许把两批混在一起提交。

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| **P0 止血** | D1（搜索永久 loading）、D2（年历消失） | 两个缺陷各自有复现步骤与修复后的截图 |
| **P1 首页几何与月态** | 恢复 72dp 大标题槽；`cellH = gridAreaH / 6` 不封顶；只画当月日期；行分隔线条件；删除顶栏图标；周态顶栏改 `‹ 九月` | 6 行月铺满、5 行月留一行白；相邻月无日期；竖屏/横屏/深色三种形态截图 |
| **P2 年态** | 恢复 12 个迷你月历（含日号、下划线、今天点）；年份标题行 62dp | 年历截图与 iOS 对照；12 张卡片逐张可聚焦 |
| **P3 morph** | 月↔周按 §5.5.1 重做（点日期触发）；年↔月按 §5.5.2 重做（端点取自真实图层）；`stripTopY` 落点校正 | 录屏逐帧核对两端不跳；`-morph-log` 帧率；减弱动效下降级 |
| **P4 其余四屏** | D8 阅读页 hero 空态、D9 横屏标题重复、D10 位置行、D11 对齐按钮；搜索空态三选一 + 结果行 Button 语义 + 搜索框语义；足迹统计卡语义合并、趋势图无障碍、地点行"名称+计数" | 逐屏截图 + `uiautomator dump` 断言（实测：结果行 `Button` + `9月20日 · DB G2 · 10:17`；月态 30 个日格节点） |
| **P5 自适应** | 分栏判据（≥700dp 且宽>高）、左栏 `clamp(46%, 280, 440)`、正文列限宽、密度降级三档 | 426.7×952 / 952×426.7 / 320dp 三档无重叠无截断 |

---

## 十三、验收方式

| 层面 | 手段 |
| --- | --- |
| 纯数学 | JVM 单测：`CalendarLayoutTest` / `CalendarMorphTest` / `AdaptiveLayoutTest`，覆盖行高派生、morph 两端不变量、`coerceIn` 区间不反转 |
| 版面 | 模拟器实测：`adb shell uiautomator dump` 读真实 bounds 断言 |
| 无障碍 | `dump` 里必须能找到 `7 × 当月行数` 个日格节点；空白格必须为 0 个 |
| 回归 | `./gradlew testDebugUnitTest`（**355** 条，含全部跨端兼容性断言）不许减少 |
| 跨端 | `python3 tools/verify_sqlite_schema.py` + `bash tools/verify_backup_interop.sh` |
| 视觉 | Pixel 10 Pro AVD：竖屏 / 横屏 / 深色 / 大字号（fontScale 1.3、2.0）四种，逐屏截图留档 |

---

## 附录 A：iOS 参考文档索引

| 文件 | 内容 |
| --- | --- |
| `docs/ios-reference/home.md` | iOS 首页逐条数值（含 `file:line`） |
| `docs/ios-reference/diary.md` | iOS 阅读/编辑页逐条数值 |
| `docs/ios-reference/footprint-search-settings-root.md` | iOS 足迹/搜索/设置/导航逐条数值 |
| `../JustADiary/docs/design-system.md` | iOS 设计令牌、动态字体、日期格式 |
| `../JustADiary/docs/adaptive-layout-plan.md` | iOS 自适应版面与设计稿说明 |
| `../JustADiary/docs/design/landscape/screens/ph-*.png` | iOS 设计稿（竖屏三态 + 横屏） |

> 冲突时以 **Swift 源码** 为准，其次 `docs/*.md`，最后设计稿
> （已知设计稿与代码有 10 处出入，其中横屏"年份胶囊"、竖屏"今日圆环"等已由代码否定）。

## 附录 B：本方案取代的旧规范

`docs/design-spec.md` 的下列条款**作废**，以本方案为准：

| 旧条款 | 旧写法 | 现写法 |
| --- | --- | --- |
| §5.2 | 相邻月日期降到 55% 透明度，仍可点 | **不画、不可点、不进无障碍树**（§5.2） |
| §5.3 | 顶栏补一个显式的月↔周按钮 | **删除该按钮**，回月态走周态顶栏左侧 `‹ 九月`（§5.7） |
| §5.3 表 | 点一个日期（月态）= 整屏 morph | 保留（本轮由代码落实，见 §5.6） |
| §5.6 | 年历改为"月份名 + 记录圆点" | **恢复 12 个迷你月历**（§5.4） |
| §5.1 | 竖屏月态整屏，行高封顶 96dp | 行高**不封顶**，`cellH = gridAreaH / 6`（§5.1） |
| §5.4 | 左侧 `▾ + 2026年9月` | 顶栏只放 `▾ 2026年`；`九月` 回到 72dp 大标题槽（§5.7、§5.1） |

旧文档的 §1「首轮迁移问题清单」作为历史记录保留。
