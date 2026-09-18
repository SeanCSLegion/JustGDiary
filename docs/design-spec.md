# JustGDiary Android 界面设计规范（Material 3）

日期：2026-09-18 · 环境：Android Studio 2026.1 / AGP 9.4.0 / Compose BOM 2026.09.00 /
Material 3 1.4.0 · 设备：Pixel 10 Pro AVD（1280 × 2856 @ 480dpi = 426.7 × 952dp，fontScale 1.0）

本文回答一件事：**iOS 版（JustADiary）的设计意图，在 Android 上应该长成什么样。**

改动界面之前先读这一篇。它同时是三条东西的合并记录：

1. iOS `docs/design-system.md` + `docs/adaptive-layout-plan.md` 里**与平台无关**的设计意图
   （信息层级、日期语义、日历三态、自适应判据、无障碍要求）；
2. 把这些意图**翻译成 Google 的做法**（Material 3 组件、Material 规范数值、Android 无障碍模型）；
3. 首轮迁移**逐字照搬 iOS 数值**导致的具体缺陷，以及每个缺陷的修法（§6）。

> **定位**：与 iOS 的界面**不追求像素一致**，只对齐这几件事——信息层级、日期/时间语义、
> 交互结果（点一天会发生什么）、动画的**意图**（月↔年、月↔周的方向感）。
> 具体数值一律走 Material 3。

---

## 一、首轮迁移（M0–M9）的问题清单

首轮在**没有设备**的前提下完成，几何全部由 JVM 单测守住。装上模拟器后暴露的是
「单测测不到的那一类」问题：**布局的可用空间假设**与**无障碍树**。

| # | 现象 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | 月历每行高 ~126dp，日号之间隔着整屏空白 | `CalendarLayout.portraitAreaHeight` 逐字照搬 iOS 的 `max(320, screenH - 64)`；iOS 竖屏 874pt 下 `cellH ≈ 102pt`，Android 952dp 下 `cellH = 126dp`，而格子内容只有 40dp 高 | §5.1：日历区高度改为**按内容派生并封顶**，剩余空间交给「选中日面板」 |
| 2 | 月历在 952dp 屏上占满整屏，选中日的日记内容无处可放 | 同上：iOS 靠「整屏 morph」把内容推到下一屏，Android 单屏内应直接给内容留位 | §5.1：竖屏是「月历 + 选中日面板」两段，面板占 `weight(1f)` |
| 3 | 日历在无障碍树里**完全不存在** | `MonthCanvas` / `WeekRowCanvas` / `YearCanvas` 全是 `Canvas` 手绘，一个可聚焦节点都没有；TalkBack 只能读到"2026年 九月"和一个按钮 | §5.2：日格改为**真实 Compose 组件**，每格一个可聚焦节点 |
| 4 | 点一天要**点两次**才进日记（第一次进周态、第二次才打开） | 照搬 iOS 的「点日期 → 整屏 morph 到周态」 | §5.3：点日期 = 选中该日 + 更新下方面板；周态整体移除 |
| 5 | 「今天」按钮在顶部右侧、年月是 `AssistChip`，层级语义都不对 | 照搬 iOS 的玻璃胶囊（iOS 用 `InfoCapsule`） | §5.4：M3 顶栏 + `TextButton` |
| 6 | 硬编码 `TODAY_LABEL`、年历标题 `monthValue + "月"` 等残留 | 迁移时漏了资源化 | §5.5：全部走 `strings.xml`（`values` + `values-zh-rCN`） |
| 7 | **横屏旋转直接崩屏** | `portraitAreaHeight` 的 `coerceIn` 区间在矮窗口里反转（`max 217 < min 240`），抛 `IllegalArgumentException` | §5.1：上下界全部由构造保证 `floor ≤ preferred ≤ available`，并加了 0/极矮窗口的回归断言 |
| 8 | 年历的迷你月日号互相叠住、看不清 | 年历照搬 iOS 的"迷你月画完整 6 行日号"，而 Android 的年历卡片只有 ~36dp 高 | §5.6：年历改为「月份名 + 记录圆点」，高度下限 470dp |
| 9 | 日格在无障碍树里**能读但不能点**（`clickable=false`），且一屏有 100+ 个碎片节点 | `clickable` 在里、`semantics(mergeDescendants = true)` 在外，生成两个节点：外层有描述无动作、内层有动作无描述 | §5.2：`clickable` + `clearAndSetSemantics`，每格一个 `Button` 节点 |
| 10 | **空日记页没有任何写入口** —— 进去只能看搜索框和分享 | `DiaryReadScreen` 有 `onWrite` 参数（默认 null），`DiaryPageScreen` **从未传**，等于把 iOS 的 `square.and.pencil` 按钮整条链丢了 | §6 备注：日记页接线 `onWrite = if (canEditToday) viewModel::enterWrite else null` |

**教训（写进规范）**：iOS 的布局数学里凡是出现 `screenH`、`safeAreaBottom`、固定
`bottomClearance` 的地方，**都不能照搬**。Android 的可用空间由窗口尺寸类 + WindowInsets
决定，而且同一款系统在不同设备上的长宽比差别远大于 iPhone 家族。可移植的是**比例关系与
意图**，不是数值。同理，**任何 `coerceIn` 都必须证明区间不会反转** —— 横屏崩溃就是这条。

---

## 二、设计令牌

### 2.1 颜色：完全交给 Material 3 + 动态取色

| 项 | 做法 |
| --- | --- |
| 色板 | `MaterialTheme.colorScheme`；Android 12+ 且设置开启时用 `dynamicLight/DarkColorScheme`（Material You） |
| 品牌回落 | 动态取色不可用/关闭时，只覆写 `primary` 系列四个角色（`FallbackLightScheme`），其余取 M3 基线 |
| 语义用法 | 正文 `onSurface`；次要说明 `onSurfaceVariant`；选中/今天 `primary`；选中日号 `onPrimary`；卡片 `surfaceContainerLow`；分隔线 `outlineVariant` |
| 禁止 | 不写死十六进制；不用「玻璃/模糊」叠层（iOS 的 Liquid Glass 是平台特性，Android 不做等价物） |
| 高对比 | 系统「高对比文字」开启时，卡片描边由 0.5dp/45% 变 1.5dp/90%（`LocalIncreasedContrast`） |

**为什么不用 iOS 的固定品牌色**：iOS 侧品牌色刻意不跟随系统色调滑块；Android 的
Material You 是**用户显式选择的个性化**，关掉它反而违背平台约定。设置页给了开关。

### 2.2 字体：设计梯级 → M3 排版角色

字号梯级（`TypeSize`）与 M3 角色的映射见 `ui/theme/Type.kt`。要点：

- 每一处文字都必须落在梯级上（32/28/24/22/20/18/17/16/15/14/13/12/11），不拍脑袋取字号；
- 缩放走 `DynamicTypeMetrics`：`effectiveMultiplier = min(ceiling(style), max(1, fontScale))`。
  **fontScale = 1 时全部为 1**（默认外观与设计一致），放大时按档封顶，保证梯级**永不倒挂**；
- 日历格子内的文字走**半速**（`calendarSp`）：格子尺寸由屏幕决定，全速放大必然溢出；
- 固定高度一律改 `minHeight`；单行不可换行处用 `maxLines = 1` + `TextOverflow` 或
  自动缩小（日历大标题）。

### 2.3 间距与圆角

- 间距只用 `Spacing`（`page = 16dp` / `card = 12dp` / `rowIcon = 12dp` / `hitTarget = 44dp`）；
- 圆角：M3 组件用 `MaterialTheme.shapes`；**自绘**组件（日历格、柱状图、分享长图）用
  `Radius` 令牌，嵌套圆角走 `Radius.concentric(outer, inset)`；
- **最小点击目标 48dp**（Material 规范）：日历格因此不小于 48dp；小于它的视觉元素
  （日号圆、图标）通过 `minimumInteractiveComponentSize` 或外层 padding 补足。

### 2.4 图标

首轮用 `material-icons-core` 里语义最近的图标。**不引入 `material-icons-extended`**
（35.7MB aar，`isMinifyEnabled = false` 下不划算）。映射集中在 `ui/root/AppTab.kt` 与
`ui/components/Components.kt` 各一处，将来换 Material Symbols 只改一个对象。

---

## 三、组件规范

| 场景 | M3 组件（唯一选择） |
| --- | --- |
| 页面标题 | `TopAppBar`（`SmallTopAppBar` / `CenterAlignedTopAppBar`）或自绘的 32dp 大标题 |
| 主行动（写日记） | `Button`（filled） |
| 次要行动 | `TextButton` / `OutlinedButton` |
| 图标行动 | `IconButton` / `FilledTonalIconButton`，`contentDescription` **必填** |
| 互斥取值（语言/主题/周起始/导出范围/位置精度） | `ExposedDropdownMenuBox` 或「行 + `DropdownMenu` + 勾选」；**不用** `AlertDialog` |
| 确认破坏性动作（放弃修改/删除/覆盖导入） | `AlertDialog` |
| 开关 | `Switch`（整行可点，`toggleable`） |
| 筛选/标签 | `FilterChip`（可多选）、`AssistChip`（触发动作） |
| 空态 | 图标（`onSurfaceVariant`）+ 一行说明 + 可选行动按钮，垂直居中 |
| 内容卡片 | `Card` + `CardDefaults.cardColors(containerColor = surfaceContainerLow)` |
| 底部导航 | `NavigationBar`（紧凑）/ `NavigationRail`（≥600dp） |
| 顶栏行动数量 | 主行动 1 个；其余收进 `DropdownMenu`（溢出菜单） |

**「选中/当前」的表示**：
- 日历：选中日 = `primary` 实心圆 + `onPrimary` 日号；今天 = `primary` 描边圆环；
  有日记 = 日号下方 2dp 圆角短横线（`Radius.hairline`）；
- 列表/筛选：`FilterChip` 的 `selected`；
- 导航：`NavigationBarItem` 的 `selected`。

**「有内容 / 无内容」**：不靠颜色单独承载（无障碍），一律同时有文案或形状差异。

---

## 四、自适应版面

### 4.1 判据只有一条：当前可用尺寸

不看机型、不看 `orientation`、不读 `Display`。判据来自 `BoxWithConstraints` 的
`maxWidth/maxHeight`（→ `AdaptiveLayout`），窗口尺寸类阈值取 Material 3 的 600dp：

| 形态 | 判据 | 主导航 | 首页 |
| --- | --- | --- | --- |
| 紧凑 | 宽 < 600dp | `NavigationBar`（底） | 单栏：月历 + 选中日面板 |
| 中等 / 扩展 | 宽 ≥ 600dp | `NavigationRail`（侧） | 宽 > 高且内容宽 ≥ 700dp 时左右分栏 |

### 4.2 首页两栏（横屏 / 宽窗口）

- 左栏日历：宽度 `clamp(内容宽 × 0.46, 280, 440)dp`；
- 右栏：日期标题行（含「今天」）+ 选中日内容，正文列**限宽 560dp** 并居中；
- 两栏之间 1dp `outlineVariant` 分隔线；
- 横屏**不提供年份入口**（与会话已确认的产品决策一致）：整块头部隐藏，只有上下滑切月。

### 4.3 系统占位

一律用 `WindowInsets.safeDrawing` / `safeContent`，不手写数值。`Scaffold` 的
`innerPadding` 必须消费（首轮导航栏高度就是这样漏掉的）。

---

## 五、首页规范（本轮重点）

### 5.1 竖屏结构

```
┌──────────────────────────────────────────┐
│ ▾ 2026年9月                       今天   │  顶栏 52dp（整块可点 → 年历）
├──────────────────────────────────────────┤
│  一  二  三  四  五  六  日               │  星期栏 30dp
│  …月历网格：每行 56dp、5 或 6 行…          │   ≤ 366dp
├──────────────────────────────────────────┤
│  9月15日 周二 · 八月初四 · 今天            │  日期标题 40dp
│  ┌────────────────────────────────────┐  │
│  │ 选中日的日记内容（可滚动）           │  │  weight(1f)
│  │ …                 [写日记 / 继续写] │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

**区高的唯一公式**（`CalendarLayout.portraitAreaHeight`）：

```
content   = 星期栏 30 + 行数 × 56
panelMin  = min(200, 可用高 × 0.4)      // 矮窗口里面板下限收缩
ceiling   = 可用高 − panelMin
preferred = min(content, ceiling)
floor     = min(240 × 0.6, preferred)   // 不得凭空抬高行数少的月份
区高       = preferred.coerceIn(floor, 可用高)
```

`floor ≤ preferred ≤ 可用高` 由构造保证，**区间永不反转**（首轮横屏崩溃就出在这里）。

- 日历区用一个**固定高度**的容器占位（竖屏 `Modifier.height(52 + 区高)`），
  **不能用 `fillMaxSize`**：那样下方面板会被摊到屏幕底部，年历层的第 4 行还会压住当日标题；
- 选中日面板占剩下的全部高度（`weight(1f)`），内部 `verticalScroll`；
- 可用高 < 520dp（横屏/矮窗口）时走 §4.2 的两栏，右栏承载面板。

### 5.2 日格（无障碍的关键）

**日格必须是真实 Compose 组件**，不能是 Canvas 里的画笔。每格：

- 尺寸：宽 `1/7`，高 56dp（≥ 48dp 最小点击目标）；
- 内容自上而下：选中/今天圆底（直径 `cellHeight − 16dp`，夹在 28…48dp）→ 日号
  （`clamp(cellH × 0.34, 13, 20)` sp，半速缩放）→ 农历（`clamp(cellH × 0.18, 9, 11)` sp）
  → 有日记下划线（2dp）；
- `Modifier.clearAndSetSemantics` 至少给出：
  - `contentDescription` = 完整日期（如「2026年9月15日 周二」）；
  - `stateDescription` = 「今天」「已选中」「有日记/无日记」的组合；
  - 点击动作由 `clickable` 提供（`role = Role.Button`）；
- `selected` 语义属性用于「今天/选中」；
- 相邻月的日期降到 55% 透明度，未来日期降到 35%，**两者都仍可点**。

**实测证据**：`adb shell uiautomator dump` 里能找到全部 31 个日格节点，每个带
`content-desc="9月15日 周二"`（首轮这里是 0 个）。

### 5.3 交互（与 iOS 的差异，全部有意）

| 动作 | iOS | Android（本规范） |
| --- | --- | --- |
| 点一个日期 | 整屏 morph 到周态 | **选中该日**，下方面板换成这天的内容；不换屏 |
| 打开某天的日记 | 周态里再点一次 | 点面板里的「写日记 / 继续写日记」，或**再点一次已选中的日期** |
| 上下滑 | 月历翻月 | 同（保留：阈值 90/60、橡皮筋 0.25、投影 0.2s） |
| 左右滑 | 横屏翻周 | **未实现**（见下方"有意不做"） |
| 点顶栏年月 | 进年历（缩放 morph） | 同（保留 morph，几何与各自真实图层同源） |
| 点「今天」 | 回今天并重载 | 同 |
| 未来的日期 | toast「这一天还没有到来」 | 空态文案已表达同一件事（不再弹 toast） |

**有意不做：周态整屏 morph。** 首轮把 iOS 的"点日期 → 整屏 morph 到周条 + 下方日记"
逐字搬了过来。在 Android 上它同时是「交互要与平台一致」和「信息重复」两个问题：

1. iOS 那次 morph 是**反馈**（告诉用户"我切到周了"）；Android 的点日期本来就该直接
   显示这天，多一次转场反而让第一次点击"看起来没反应"（实测用户会连点两次）；
2. 周条能提供的信息（这一周的 7 天）在下方的「选中日面板」里已经完整存在，
   而面板还多了"今天的日记内容 + 写入口"。

所以周态整体移除，`HomeViewMode` 只剩 `MONTH` / `YEAR`。日历的三态几何数学
（`monthWeekMorph`）仍保留在 `CalendarLayout` 并由 `CalendarMorphTest` 守着 ——
它是一条成熟的能力，重新需要时直接接上。

**为什么改点日期**：与「点日期 = 看这天」的平台共识一致；可发现性优先于与 iOS 的形似。

### 5.4 顶栏

- 左侧：`▾`（月态，`ArrowDropDown`）+ `2026年9月`（`titleLarge`）；整块 → 年历；
- 年态：`‹`（`AutoMirrored.KeyboardArrowLeft`）+ `2026年`；整块 → 回月历；
- 右侧：`今天`（`TextButton`，回到今天并重载）；
- 顶栏高度 `minHeight = 52dp`（大字号下不裁切）；
- 分栏（横屏/宽窗口）时**整块顶栏隐藏**：只有 400dp 高，顶栏会吃掉日历（与会话已确认的
  "横屏不提供年份入口"一致）。

### 5.5 文案

全部走资源，`values`（英文）与 `values-zh-rCN`（简体中文）必须同时给：

| key | 中文 | 英文 |
| --- | --- | --- |
| `index_today` | 今天 | Today |
| `index_year_view` | 年历 | Year |
| `index_month_view` | 月历 | Month |
| `index_week_view` | 周历 | Week |
| `index_future_toast` | 这一天还没有到来 | This day hasn't arrived yet |
| `a11y_month_grid` | %1$s 的月历 | %1$s calendar |
| `index_selected` | 已选中 | Selected |

日期/时间文案统一由 `DateFormats` 生成（跟随应用语言与设备 12/24 小时制），
**不允许**再出现 `monthValue + "月"` 这类硬编码。

### 5.6 年历

年历**不沿用** iOS 的"12 张卡片各画一个完整迷你月"。首轮上机实测：年历卡片在
Android 竖屏上只有 ~36dp 高（`(区高 − 62 − 8 − 18) / 4`），塞 6 行日号必然互相叠住，
读不出任何信息。

改成 **`月份名 + 记录圆点`**：

- 卡片：3 列 × 4 行，`yearCardSize` 公式不变，但 `YEAR_TITLE_H` 由 iOS 的 62 调到 44
  （年历标题已经在 M3 顶栏里，页内不再重复），省下的高度全给卡片；
- 年历区有**自己的下限** `YEAR_MIN_AREA_H = 470dp`（不共用月历的 62% 上限）：
  这是"卡片高度足够画清 6 行圆点"的最小值；
- 迷你网格按 `miniGridRect` 画 **6 行圆点**：有记录的日期一个 `primary` 点，
  今天一个更大的实心点；没有记录的日期不画；
- 整张卡片是一个可点区域（`contentDescription` = 月份名，`stateDescription` = 有/无记录），
  叠在 Canvas 之上、与绘制用同一组 `yearCardRect` 比例 —— 画在哪、点在哪、读在哪三者同源。

**为什么年历仍是 Canvas**：月格的**每一天**都是用户要点的目标，必须逐格暴露给 TalkBack；
年历的 12 张卡片各自是**一个整体点击目标**，迷你网格是装饰性内容，逐个朗读只会让导航变冗长。

因为年历与月历各用各的区高，`yearMonthMorph` 的两个端点分别取自各自的真实图层
（`miniGridRect(yearCardRect(1, w, yearAreaH))` 与 `fullMonthGridRect(w, monthAreaH, 30)`），
所以 morph 收尾仍然不跳。

---

## 六、其余四屏的现状与待改

| 屏 | 现状（模拟器实测） | 待改 |
| --- | --- | --- |
| 足迹 | 版面正确（标题 + 统计 + 筛选 + 柱状图 + 清单），空态规范 | 柱状图缺无障碍描述；横屏分栏时正文列需限宽 |
| 搜索 | 版面正确（搜索框 + 两组 `FilterChip` + 结果） | 搜索框在无焦点时缺 `SearchBar` 语义；结果项点击目标需 ≥48dp |
| 设置 | 版面正确（分组卡片 + 图标徽章 + 尾部取值 + `Switch`），取值用菜单不用行动表 | 「位置权限」行尾值右对齐截断风险；分组标题层级再压一档 |
| 日记页 | M5 已完成（语义样式 + 格式栏 + 内联图片）。**本轮修掉一个断链**：`onWrite` 从未接线，空日记页没有写入口（见 §1 第 10 条） | 格式栏在键盘弹起时的位置需实测；只读态的提示条走 `Snackbar` |

这四屏本轮只做**规范核对**，不改版面结构。

---

## 七、无障碍（验收口径）

- 日历：TalkBack 逐格可读（§5.2），有「上个月 / 下个月 / 上一年 / 下一年」自定义动作；
- 所有 `IconButton` 有 `contentDescription`；装饰性图标显式 `contentDescription = null`；
- 触控目标 ≥ 48dp（日历格、列表行、筛选芯片）；
- 「减弱动态效果」（`ValueAnimator.areAnimatorsEnabled() == false`）时 morph 降到 180ms 线性；
- 不使用「仅靠颜色」传达状态。

## 八、验证方式

| 层面 | 手段 |
| --- | --- |
| 纯数学 | JVM 单测（`CalendarLayoutTest` / `CalendarMorphTest` / `AdaptiveLayoutTest`） |
| 版面 | 模拟器实测：`adb shell uiautomator dump` 读真实 bounds 断言 |
| 无障碍 | `uiautomator dump` 里必须能找到 `7 × 行数` 个日格节点（实测 31 个） |
| 回归 | `./gradlew testDebugUnitTest`（356 条，含全部原有的跨端兼容性断言） |

**本轮实测记录**（Pixel 10 Pro AVD，1280 × 2856 @ 480dpi = 426.7 × 952dp，fontScale 1.0）：

| 场景 | 结果 |
| --- | --- |
| 竖屏首页 | 月历 6 行 × 56dp = 336dp，日格内容垂直居中，无空白带 |
| 无障碍 | 31 个日格节点，`content-desc` 形如 `9月15日 周二`，`today`/`selected` 状态齐全 |
| 点日期 | 下方面板标题从 `9月18日` 变为 `9月8日 周二 丙午年七月廿七` |
| 再点已选中日期 | 进入该日日记页 |
| 上下滑 | 九月 → 十月，选中日跟到 10 月 9 日（`丙午年九月初九`） |
| 今天 | 回到 2026 年 9 月 18 日 |
| 年历 | 12 张卡片全部可聚焦；9 月卡片带今天的点；`‹ 2026年` |
| 无障碍 | 35 个日格全部是 `Button + clickable="true"`，整屏 100 个节点（改前 100+ 碎片且不可点） |
| 写日记链路 | 面板「写日记」→ 编辑器 → 保存 → 阅读页显示 `8:23 开始写 / ABC Test123` → 返回首页，面板显示该片段 + 「继续写日记」，日格状态变成「有日记」 |
| 横屏 | 左月历 6 行（约 30dp/行）+ 右侧当日面板分栏；导航切侧栏；**不再崩溃** |
| 深色 | 全部语义色跟随 M3 深色板，无写死颜色 |
