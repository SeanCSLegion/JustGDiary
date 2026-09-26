# 首页（日历 Tab）UI / 交互实现规格 v3

> **依据**：`/Users/cov/JustADiary` 最新 iOS 源码，commit `0b07050`（2026-09-26，iPhone-only）。
> **原则**：一切以代码为准，凡非显然结论都带 `文件:行号`。标注「推导」的数字是按代码公式计算的结果，不是代码里的字面量。
> **v3 相对 `home.md`（2026-09-20）的变化**：横屏分栏判据、底部让位、左右栏宽度全部重写；横屏年历入口取消；新增 `CalendarDensity` 高度降级。旧文档里的 `contentWidth >= 700`、`bottomInset + 44`、`calendarW = min(masterWidth, …)` 均已不存在——**不要沿用旧文档的数字**（详见附录 A）。

---

## 0. 文件与角色

| 文件 | 角色 |
|---|---|
| `JustDiary/Views/Home/HomeView.swift` | 首页根视图：状态机、顶栏、年/月/周三层、横屏分栏、morph 触发与编排 |
| `JustDiary/Views/Home/CalendarLayout.swift` | 模式枚举、密度模型、所有日历几何常量、周/月的日期计算与派生 |
| `JustDiary/Views/Home/CalendarGrids.swift` | `DayDraw`（Canvas 逐格画笔）、`MonthCanvas`、`WeekRowCanvas`、`MonthBigTitle`、`MiniMonthLabel`、`YearPageView` |
| `JustDiary/Views/Home/CalendarPaneViews.swift` | `MonthPane`（竖屏月态 + 横屏左栏共用）、`DayPane`（横屏右栏） |
| `JustDiary/Views/Home/DayContentView.swift` | 选中日面板：卡片列表 / 空态 / 继续写按钮（`DayContentView` + `DiaryBlockCard`） |
| `JustDiary/Views/Home/MorphViews.swift` | 两个 morph 视图：`YearMonthMorphView`、`MonthWeekMorphView` |
| `JustDiary/Views/Home/DragPagePager.swift` | 通用翻页容器（横/纵轴、橡皮筋、阈值） |
| `JustDiary/ViewModels/HomeViewModel.swift` | `selectedDate` / `monthPage` / `yearPage` / `flags` / `dayBlocks` / `settings` |
| `JustDiary/Views/Components/AdaptiveLayout.swift` | 唯一版面判据（分栏阈值、两栏宽度、浮条让位、页边距） |
| `JustDiary/Views/Components/DesignSystem.swift` | `Radius` / `Spacing` / `TypeSize` / `DynamicTypeMetrics` |
| `JustDiary/Views/Components/Components.swift` | `InfoCapsule`、`GlassPrimaryButton`、`diaryCard`、`BlobBackground`、`Haptics` 调用点 |
| `JustDiary/Services/Theme.swift` | 颜色 token（seed `#2563EB`、onPrimary、onSurface…） |
| `JustDiary/Services/L10n.swift` | 所有文案与日期格式（`date_year`、`index_today`…） |
| `JustDiary/Models/Lunar.swift` | 农历日/月/干支生肖串 |
| `JustDiary/Models/DateUtil.swift` | `dayKeyOf`、`monthFirst`、`weekdayIndex`、`relativeDays` |

首页被 `TabView` 的第 1 个 `Tab` 承载，外壳背景是 `BlobBackground()`（`RootView.swift:31-38`），底部是系统浮动 tab bar（横竖屏都贴屏幕底边居中，高度 64 / 83，见 §2.3.4）。

---

## 1. 屏幕与状态机

### 1.1 三个显示模式

```swift
enum CalendarMode { case year; case month; case week }   // CalendarLayout.swift:4-8
```

- 初始值 `.month`（`HomeView.swift:14`），`@State`，**不持久化**。
- 三层同时存在于 `ZStack(alignment: .top)`，只有当前态的 `opacity = 1` 且 `allowsHitTesting(true)`（`HomeView.swift:424-435`）。
- 两栏态（横屏分栏）**不经过三态 ZStack**：`layout.splitsMasterDetail == true` 时走 `splitCalendarArea`，模式恒为 `.month` 且无 morph（`HomeView.swift:31-41`）。

状态变量全集（`HomeView.swift:10-20`）：

| 变量 | 类型 | 含义 |
|---|---|---|
| `mode` | `CalendarMode` | 年 / 月 / 周 |
| `zoom` | `Double` | 年↔月 morph 进度，驱动 `YearMonthMorphView.progress`；`0` = 全屏月格，`1` = 年历 |
| `ymMorph` | `(year: Int, month: Date)?` | 非 nil 时渲染 `YearMonthMorphView`；同时 `morphing == true` |
| `expand` | `Double` | 月↔周 morph 进度；`0` = 月，`1` = 周 |
| `mwMorphMonth` | `Date?` | 非 nil 时渲染 `MonthWeekMorphView`（锚定在这个月） |
| `showFutureToast` / `futureToastTask` | `Bool` / `Task?` | 未来日期提示（**当前不可达**，见附录 A.3） |

`morphing = ymMorph != nil || mwMorphMonth != nil`（`HomeView.swift:157`）。morph 期间：三个基础层**整体不构建**（`HomeView.swift:425`），分页器 `disabled`，`goBack/selectDay/todayTapped` 因 `guard !morphing` 失效。

### 1.2 迁移表（触发 → 结果）

| 触发 | 代码路径 | 结果 |
|---|---|---|
| 启动 / `.task` | `HomeView.swift:66` | `await vm.loadInitial()`：拉 `flags`（月份 ±1 年的日记标记）与当天 blocks |
| 点顶栏左下胶囊（月态） | `goBack()` → `showYearPage()`（`HomeView.swift:224-248, 252-269`） | `zoom = 0` 无动画写入 → `mode = .year` → 动画 `zoom = 1`；`ymMorph = (monthPage.年, monthPage)` |
| 点顶栏胶囊（周态） | `goBack()`（`HomeView.swift:224-242`） | 「周 → 月」：`vm.selectMonth(monthFirst(selectedDate))`，`mode = .month`，`expand = 1` 无动画 → 动画 `expand = 0` |
| 点日期（竖屏） | `selectDay` → `openDay`（`HomeView.swift:203-222, 503-511`） | `vm.select(day)`（同时把 `monthPage` 设为该月）、`mwMorphMonth = monthPage`、`mode = .week`、`expand = 0` 无动画 → 动画 `expand = 1`；随后 `reloadDayBlocks()` |
| 点日期（横屏分栏） | `selectDay`（`HomeView.swift:503-507`） | 只 `vm.select(day)`（`.snappy(0.25)`）+ 重载右栏；**不进入周态** |
| 点年历里的迷你月 | `openMonthFromYear(_:)`（`HomeView.swift:178-201`） | `vm.selectMonth(该月)`（**不改 `selectedDate`**）、`ymMorph = (yearPage, month)`、`mode = .month`、`zoom = 1` 无动画 → 动画 `zoom = 0` |
| 点「今天」胶囊（月态） | `todayTapped`（`HomeView.swift:278-283`） | `.snappy(0.3)` 内 `vm.resetToToday()`（`selectedDate = now`、`monthPage = 本月`、`yearPage = 今年`）+ `reloadDayBlocks()` |
| 点「今天」胶囊（周态） | `HomeView.swift:284-289` | `.snappy(0.3)` 内 `vm.select(now)` + `reloadDayBlocks()`（留在周态） |
| 点「今天」胶囊（年态） | `HomeView.swift:275-277` | `vm.resetToToday()` 后立刻 `openMonthFromYear(当月的月号)` → morph 回今年当月 |
| 旋转进横屏分栏 | `.onChange(of: layout.splitsMasterDetail)`（`HomeView.swift:67-71`） | `settleToMonth()`：无动画清 `ymMorph/mwMorphMonth`、`zoom = 0`、`expand = 0`、`mode = .month`，再 `reloadDayBlocks()` |
| 横屏左栏上下滑翻月 | `selectMonthPage(_:)`（`HomeView.swift:353-360, 404-413`） | 把**选中日带到新月份的同一天**（超界取当月最后一天）→ `vm.select(target)` + 重载右栏 |
| 横屏右栏点「今天」 | `DayPane.onTodayTap = todayTapped`（`HomeView.swift:391`） | 同「月态」分支 |
| 竖屏月态上下滑翻月 | `monthLayer` 的 `onPageChange`（`HomeView.swift:485-487`） | `vm.selectMonth(新月)`——**只改 `monthPage`，不动 `selectedDate`**（月历上没有选中项，但周态的选中日仍是旧的） |
| 竖屏周态左右滑翻周 | `weekLayer` 的 `onPageChange`（`HomeView.swift:522-528`） | `delta = 新旧周首相差天数`（±7）→ `vm.select(selectedDate + delta)` + 重载 |
| 年态上下滑翻年 | `yearLayer` 的 `onPageChange`（`HomeView.swift:467-468`） | `vm.yearPage = 新年` |
| 收到 `.diaryVersionChanged` | `HomeView.swift:72-74` | 重新 `loadInitial()`（写完日记返回首页时刷新 flags 与当日内容） |
| 收到 `.uiTickChanged` | `HomeView.swift:75-78` | `vm.refreshSettings()` + `resetToToday()`：**回到今天 + 月态**（语言、主题、周起始日、`auto_time` 开关、备份导入都会发这个通知，`SettingsViewModel.swift:46-67,165`、`AppConfigService.swift:21`） |
| 旋转出分栏（横屏→竖屏） | **无处理** | 保持 `.month`（进分栏时已被 `settleToMonth` 归零），顶栏重新出现 |

### 1.3 跨启动记住什么

- **日历状态一律不持久化**：`HomeViewModel` 是每次创建的新对象，默认 `selectedDate = Date()`、`monthPage = 本月 1 日`、`yearPage = 今年`（`HomeViewModel.swift:6-8`），`mode = .month`、`zoom/expand = 0`。没有 `@SceneStorage`/`@AppStorage` 参与首页状态（全仓仅 `SettingsStore` 用 `UserDefaults`）。
- **影响首页显示设置的持久化项**（`UserDefaults(suiteName: "group.com.cov.justdiary")`，`AppSettings.swift:36-37`）：

| 设置 | 键 | 默认 | 对首页的影响 |
|---|---|---|---|
| 周起始日 | `week_start` | `"monday"` | 列顺序、周末着色、周条内容、`CalendarLayout` 缓存键 |
| 显示开始时间 | `auto_time` | `true` | 卡片时间行是否显示（数据照常记录） |
| 应用语言 | `app_language` | `"system"` | 决定 `AppLanguage.isZh` → 农历行/农历标签是否存在，以及所有文案 |
| 主题 | `theme_mode` | `"system"` | 颜色；切主题会清 `DayDraw` 文字缓存 |

- `AppLanguage.isZh`（`HomeView.swift:23`）同时控制：月格农历行（`showsLunar`）、周条农历行、周标题行右侧农历、年历生肖、右栏农历标签。
- `RootView` 对整棵树挂了 `.id("\(AppLanguage.current)#\(settingsTick)")`（`RootView.swift:72`），因此**任何 uiTick 变化都会重建 `HomeView`，`@State` 全部复位**（即使不看 `HomeView` 自己的 `.uiTickChanged` 处理，效果也是回到今天 + 月态）。

### 1.4 旋转

- 横屏（iPhone 横屏仍存在）：判据 `size.width > size.height && contentWidth >= 548.5`（`AdaptiveLayout.swift:75-77`）。iPhone 横屏一律满足（`SplitLayoutTests.swift:37-45`），因此**所有 iPhone 横屏都是两栏**。
- 竖屏（含 Duo 内屏这类「宽但不矮」）永远单栏（`AdaptiveLayout.swift:40, 75-77`）。
- 旋转进分栏：`settleToMonth()`（`HomeView.swift:67-71, 82-94`）——年态/周态/morph 全部无动画收起，`mode = .month`。旋转回竖屏不会恢复年态/周态。
- 分栏时**整块顶栏隐藏**（`HomeView.swift:31-41`）：没有年月胶囊、没有相对日期胶囊；「今天」移到右栏标题行（`CalendarPaneViews.swift:179-182`）。
- 横屏不提供年份入口：`home.yearChip` 已不存在（`LandscapeLayoutUITests.swift:61-66`）。

---

## 2. 几何与布局

### 2.1 唯一判据来源

`RootView` 只读一次几何：`.adaptiveLayoutReader()`（`RootView.swift:68`）→ `AdaptiveLayoutReader.resolve`（`AdaptiveLayout.swift:209-236`）。
**关键事实（代码注释里实测）**：`GeometryProxy.size` 是**扣掉安全区之后**的内容尺寸（横屏 874×402 读成 750×382，`AdaptiveLayout.swift:205-208`），所以 `AdaptiveLayout.size` 用 `scene.screen.bounds`（874×402），安全区由两者差值补出。18 Pro 实测安全区：竖屏 `T62 L0 B34 R0`、横屏 `T0 L62 B20 R62`（`docs/adaptive-layout-plan.md:180`）。

### 2.2 竖屏（非分栏）总容器

```
GeometryReader { geo in                       // HomeView.swift:28
  VStack(spacing: 0) {                        // :30
    header.padding(.horizontal,16).padding(.top,12)   // :36-39
    calendarArea(size: geo.size)              // :40
  }
}.ignoresSafeArea(edges: .bottom)             // :44
```

- `.ignoresSafeArea(edges: .bottom)` 只吃掉底部安全区：`geo.size = (屏宽 − leading − trailing) × (屏高 − 顶部安全区)`。
- 顶栏槽 = `padding(.top, 12)` + `frame(minHeight: 52)` = **64**（`HomeView.swift:38, 148`）。
- 日历区：`w = size.width`，`h = max(320, size.height − 64)`（`HomeView.swift:416-417`）——**那个 64 就是为了顶栏**；`h` 有 320pt 下限。
- 18 Pro 竖屏（402×874，顶部安全区 62）：`geo = 402 × 812` → **`h = 748`**、`w = 402`；网格起点（屏幕坐标）= 62 + 64 + 72 + 30 = **228**，`cellW = 57.43`，`cellH = (748−102)/6 = 107.67`。
  > **该结论与旧 `home.md` 的 `h = 810` / `cellH = 118` 不同**（旧稿误以为 `.ignoresSafeArea(.bottom)` 连顶部安全区也忽略）。用仓库里的真机截图 `docs/images/home-month.jpg`（520×1131px = 402×874pt，1.2935 px/pt）逐像素反推可验证：
  > 大标题「九月」字形中心 **162.0pt**（模型预测 62+64+36 = 162.0）、星期栏中心 **213.0pt**（预测 198+15 = 213）、第一行日号顶端 **264.4pt**（预测 261.8+字形留白）、四条行分隔线 **335.5 / 443.0 / 551.2 / 658.7pt**（预测 335.7 / 443.3 / 551.0 / 658.7）、系统浮条上沿 **790.9pt**（与实测 frame `y = 791` 一致）。若按 118pt 格高，第一条分隔线会在 346pt，与截图差 10.5pt。
- **竖屏 6 行月份的最后一整行会落在底部浮条之下（推导）**：`h = 748` 意味着网格纵向铺满到屏幕底边（屏幕 y 126 → 874），第 6 行占 766.3 → 874pt，其日号中心落在 800 → 840pt，而浮条实测占据 791 → 874pt（横跨 x 22 → 381pt）。5 行月份不会碰到浮条（网格止于 766.3pt，留 25pt 空隙）。Android 端要不要为 6 行月份额外让出浮条高度，需要产品决策。

三态在这块 `w × h` 区域内的垂直预算：

| 态 | 结构 | 高度 |
|---|---|---|
| 月 | `MonthPane`：大标题槽 + 星期栏 + 网格（`displayedWeeks` 行） | 72 + 30 + `cellH × rows`，`cellH = (h − 72 − 30)/6`（**永远除 6**） |
| 周 | 星期栏 + 周条 + 日内容块 | 30 + 68 + (h − 98) |
| 年 | 年标题块 + 4×3 迷你月 | 62 + 8 + 4×cardH + 3×6 |

`MonthPane` 内部（`CalendarPaneViews.swift:39-73`）：

```
VStack(spacing: 0) {
  titleRow                                  // 高 titleHeight（竖屏 72）
  WeekdayHeaderView(cellW: width/7)         // 高 weekdayHeight（竖屏 30）
  MonthCanvas(...).frame(height: rowH * weeks.count)
  Spacer(minLength: 0)                      // 5 行月份把第 6 行的高度留白
}.frame(width: width, height: areaH, alignment: .top).clipped()
```

- 网格起点 Y：`portraitGridOriginY = 72 + 30 = 102`（`CalendarPaneViews.swift:35-37`）。
- `rowH = density.isWeekStrip ? min(cellH, weekStripH) : cellH`（`:43`）——降级周条时把「铺满整块」算出的巨大 `cellH` 夹回 68。
- 列宽恒为 `width / 7`（`CalendarLayout.swift:171`）。

### 2.3 横屏分栏（`HomeView.splitCalendarArea`，`HomeView.swift:322-401`）

#### 2.3.1 判定与宽度分配（`AdaptiveLayout`）

| 量 | 公式 | 位置 |
|---|---|---|
| `contentWidth` | `size.width − max(0,leading) − max(0,trailing)` | `AdaptiveLayout.swift:35-37` |
| `splitsMasterDetail` | `size.width > size.height && contentWidth >= 548.5` | `:75-77` |
| `minSplitContentWidth` | `2×16 + (16 + 0.5) + 260 + 240 = 548.5` | `:62-63` |
| `splitColumns(containerWidth:)` | `available = cw − 32 − 16.5`；`preferred = min(440, max(260, round(cw × 0.46)))`；`master = min(preferred, max(260, available − 240))`；`detail = max(240, available − master)` | `:88-94` |
| 两栏之和恒等式 | `master + detail + 2×16 + 16.5 == 容器宽` | `SplitLayoutTests.swift:88-97` |

`splitCalendarArea` 把**几何内容宽**（已扣左右安全区）当容器宽传入：`splitColumns(containerWidth: size.width)`（`HomeView.swift:344`）。

| 设备横屏 | 容器宽 | master（月历） | detail（日记） |
|---|---|---|---|
| iPhone 18 Pro 874×402（安全区左右各 62） | 750 | **345** | **356.5** |
| iPhone SE 667×375（无安全区） | 667 | **307** | **311.5** |

#### 2.3.2 垂直预算

```swift
let topPad  = AdaptiveLayout.splitTopPadding                     // 8    (HomeView.swift:327)
let paneH   = layout.splitPaneHeight(containerHeight: size.height) // :331
let headerH = 34 + 26                                            // :333  compactMonthTitleH + compactWeekdayHeaderH
let weeks   = displayedWeeks(inMonth: vm.monthPage, ws: weekStart).count   // :334
let density = CalendarDensity.resolve(availableHeight: paneH - headerH, rows: weeks, wantsLunar: showsLunar)  // :335-337
let rows    = density.rows(monthWeeks: weeks)                    // :338
let cellH   = density.rowHeight(availableHeight: paneH - headerH, rows: rows)  // :339
```

- `splitPaneHeight = max(220, containerHeight − 8 − tabBarClearance)`（`AdaptiveLayout.swift:100-102`）。
- `tabBarClearance`：竖屏 83 / **横屏 64**（按方向取实测浮条高度，**不叠加** `bottomInset`；`AdaptiveLayout.swift:107-124`）。
- 18 Pro：`paneH = 402 − 8 − 64 = 330`；SE：`375 − 8 − 64 = 303`（`SplitLayoutTests.swift:114-121`）。
- 可用网格高：`available = paneH − 60` → 18 Pro 270，SE 243。
- 密度规则（`CalendarLayout.swift:44-72`）：`perRow = floor(available / rows)`；
  - `perRow >= 44 + 16 = 60` → `.month(lunar: wantsLunar)`（显示农历行）
  - `perRow >= 38` → `.month(lunar: false)`（隐藏农历行）
  - 否则 → `.weekStrip`（左栏退化成一行周条，横向翻周）
  - 行高 `cellH = max(minimumRowHeight, floor(available / rows))`，下限：带农历 44 / 不带农历 38 / 周条 38（`:21, :29, :32-47`）。
- **实测结论**：18 Pro 横屏 6 行 → `perRow = 45` → 无农历，`cellH = 45`；5 行 → 54，无农历。SE 横屏 6 行 → `perRow = 40` → 无农历，`cellH = 40`（这正是把 `dayRowHeight` 从 44 降到 38 的原因，否则 SE 横屏只剩一行）。
- 只有 `available / rows < 38` 才会降级周条（例如 `paneH = 240` 且 6 行）。

#### 2.3.3 水平结构与两栏内容

```swift
HStack(alignment: .top, spacing: 0) {                       // HomeView.swift:350
  DragPagePager(keys: allMonthKeys, current: monthKey, axis: .vertical, pageSize: paneH) {
    MonthPane(month:, weekStart:, selectedDate:, flags:,
              width: calendarW, areaH: paneH, cellH: cellH, density: density,
              titleHeight: 34, weekdayHeight: 26, titleFont: TypeSize.pageTitle /* 24 */,
              onTapDay: selectDay)
      .frame(width: calendarW, height: paneH)
  }.frame(width: calendarW, height: paneH).clipped()        // :353-376
  Rectangle().fill(outlineVariant 0.4).frame(width: 0.5, height: paneH)
      .padding(.horizontal, gutter/2)                       // :378-381  → 实占 8 + 0.5 + 8
  DayPane(blocks:dayKey:isFuture:openEditor:bottomInset: layout.bottomInset,
          maxColumnWidth: 560, headingHeight: 44, onTodayTap: todayTapped,
          showTime: vm.settings.autoTime)
      .frame(width: dayW, height: paneH).clipped()          // :384-395
}
.padding(.leading/.trailing, 16).padding(.top, 8)
.frame(width: size.width, height: size.height, alignment: .topLeading)   // :397-400
```

- **左栏**：`MonthPane`（与大标题 34 / 星期栏 26 / 24pt 标题字号 / 可能的周条降级），单页只画当前月，**不显示相邻月日期**（`showAdjacent: false`）。
- **右栏**：`DayPane` —— 44pt 标题行（日期 + 农历 + 右端「今天」胶囊）+ `DayContentView`，正文列宽上限 **560**（不是默认 660），滚动内容底 padding 24，外层再 `padding(.bottom, max(bottomInset, 24))`（`CalendarPaneViews.swift:126-151`）。
- 分隔线颜色 `Theme.outlineVariant().opacity(0.4)`，宽 0.5（`HomeView.swift:378-381`）。
- 页面左右各 16（`AdaptiveLayout.pagePadding`），顶部 8（`splitTopPadding`）；**不再重复避让安全区**（横屏安全区已体现在几何原点里，`AdaptiveLayout.swift:140-143`）。

#### 2.3.4 底部系统浮条

| 形态 | 浮条 frame（18 Pro 实测） | 让位方式 |
|---|---|---|
| 竖屏 | `(0, 791, 402, 83)` | 首页 `h = size.height − 64` 只让顶栏；日历纵向铺到屏幕底边（6 行月份的最后一行会落在浮条之下，见 §2.2） |
| 横屏 | `(0, 338, 874, 64)` | `splitPaneHeight` 显式减 64 |

SE 横屏 `(0, 311, 667, 64)`；两条都紧贴屏幕底边，高度与机型无关（`AdaptiveLayout.swift:106-124`，`docs/adaptive-layout-plan.md:79-82`）。
外壳 `TabView` 设了 `.tabBarMinimizeBehavior(.onScrollDown)`（`RootView.swift:55`）：竖屏向下滚日内容时系统浮条会缩小，但首页的让位量是常量，不随浮条高度变化。

### 2.4 宽度 / 高度断点汇总

| 断点 | 条件 | 结果 | 位置 |
|---|---|---|---|
| 分栏 | `宽 > 高 && contentWidth >= 548.5` | 左右双栏 | `AdaptiveLayout.swift:75-77` |
| 单栏 | 竖屏（含宽而不矮）或 `contentWidth < 548.5` | 年/月/周三态 morph | 同上 |
| 主栏宽度 | `clamp(round(cw×0.46), 260, 440)`，再受 `available − 240` 上夹 | 260…440 | `:88-94` |
| 详情栏下限 | 240 | | `:49` |
| 日历区高度下限 | 220（分栏）/ 320（单栏） | | `:101` / `HomeView.swift:417` |
| 月格密度 | `perRow >= 60` 农历 / `>= 38` 无农历 / 否则周条 | | `CalendarLayout.swift:64-71` |
| 正文列宽上限 | 竖屏无 `DayPane`；横屏 560；`DayPane` 默认 660 | | `HomeView.swift:389` / `CalendarPaneViews.swift:126` |
| 日历区总宽 | 单栏 = 全宽（无页边距）；横屏两栏 = 容器 − 32 − 16.5 | | `HomeView.swift:397-400` |

### 2.5 固定 vs 比例

| 固定 | 比例/派生 |
|---|---|
| 顶栏 12 + 52；浮条让位 64/83；分栏顶部留白 8；页边距 16；栏间距 16.5；标题槽 72/34；星期栏 30/26；周条 68；周标题行 40；年标题块 62；迷你月 `miniPad 6` / `miniTitleH 17` / 间距 6；日号/农历字号上下限；所有动画时长 | 格宽 `w/7`；月格格高 `(h−102)/6` 或按可用高分行；日号字号 `clamp(cellH×0.34, 13, 20)`；农历字号 `clamp(cellH×0.18, 9, 11)`；星期栏字号 `clamp(cellW×0.26, 11, 14)`；迷你月卡片 `((w−44)/3, (h−88)/4)`；迷你日号 `clamp(min(cellW×0.95, cellH×0.5), 9, 14)`；两栏宽度按容器宽比例 + 上下限 |

---

## 3. 三种态各自的视觉构成

### 3.0 共用：顶栏（只在竖屏 / 未分栏时构建）

`HStack(spacing: 10) { 返回胶囊; Spacer(); 相对日期胶囊 }.frame(minHeight: 52)`，外层 `.padding(.horizontal, 16).padding(.top, 12)`（`HomeView.swift:36-39, 109-149`）。

- **返回胶囊**（`HomeView.swift:111-138`）：玻璃 capsule（`.glassEffect(.regular.interactive(true))`），内容 `HStack(spacing: 6) { chevron.left(16pt semibold); Text(titleText, 18pt medium) }`，`.padding(.horizontal, 14)`，`frame(minHeight: 44)`（`Spacing.hitTarget`），`lineLimit(1)`、`minimumScaleFactor(0.7)`、`allowsTightening(true)`、`contentTransition(.opacity)`，颜色 `Theme.onSurface()`。identifier `home.header.back`。
  - `titleText`（`HomeView.swift:98-107`）：年态 = `date_year(yearPage)`（「2026年」/「2026」）；月态 = `date_year(monthPage.年)`（**只有年份，没有月份**）；周态 = `monthFull(monthPage)`（「九月」/「September」）。
  - 年态时 `opacity 0` + `allowsHitTesting(false)`（`:136-137`），`animation(.diaryStandard /* easeInOut 0.3 */, value: mode)`。
- **相对日期胶囊**：`InfoCapsule(text: relativeDayLabel(), action: todayTapped)`（`HomeView.swift:144`）——玻璃 capsule，`TypeSize.chip = 14` medium，`padding(.horizontal 12, .vertical 7)`，`minimumScaleFactor(0.8)`；文字见 §6.4。**竖屏这枚胶囊没有 accessibilityIdentifier**（横屏右栏那枚才有 `home.todayChip`）。

### 3.1 月态

由 `MonthPane`（`CalendarPaneViews.swift:8-114`）绘制，竖屏与横屏左栏共用同一套画笔。

1. **大标题槽**：`MonthBigTitle(month:height:fontSize:)`（`CalendarGrids.swift:305-322`）
   - 文本 `L10n.monthFull(month)` → zh「九月」，en「September」；`.diaryFont(32 /* TypeSize.display */, weight: .bold)`（横屏用 `TypeSize.pageTitle = 24`）；行宽占满、左对齐、`padding(.horizontal, 20)`、`lineLimit(1)`、`minimumScaleFactor(0.6)`、颜色 `Theme.onSurface()`。
   - 槽高：竖屏 `bigTitleH = 72`，横屏 `compactMonthTitleH = 34`；`.frame(height: titleHeight)` + identifier `home.monthTitle`（`:93-98`）。
   - 标题槽高度是 morph 的终点，**必须**与 `MonthWeekMorphView`/`YearMonthMorphView` 用的 72 一致（`CalendarPaneViews.swift:21-31` 的注释记录了历史 bug）。
2. **星期栏**：`WeekdayHeaderView`（`CalendarGrids.swift:3-21`）
   - 7 个等宽（`cellW = width/7`）文本，取自 `L10n.weekdayNames(weekStart:)` = `veryShortWeekdaySymbols` 按周起始日旋转（zh：一 二 三 四 五 六 日；en：M T W T F S S）。
   - 字号 `weekdayFontSize(cellW) = clamp(cellW×0.26, 11, 14)`（`CalendarLayout.swift:173`），用「阻尼 Dynamic Type」`diaryCalendarFont`（增长率减半，`DynamicType.swift:61-63`）。
   - 颜色：工作日 `Theme.onSurfaceVariant()`；周末 `Theme.onSurfaceVariant().opacity(0.6)`；周末判定 `weekStart == "sunday" ? (i==0||i==6) : (i>=5)`（`CalendarGrids.swift:13`）。
   - 无背景、无边框；槽高 30（横屏 26）。
3. **网格**：`MonthCanvas` 用 `Canvas` 一次性画完（`CalendarGrids.swift:193-227`）
   - 行数 = `displayedWeeks(inMonth:ws:)`：从 `weeks(inMonth:)` 固定的 6 行里**从尾部删掉「整行都不属于本月」的填充行**（`CalendarLayout.swift:352-365`）→ 4/5/6 行。月态 `showAdjacent: false`：相邻月的日期**既不画也不可点**（`CalendarGrids.swift:211-214, 249-251`）。
   - **行分隔线**：`i > 0` 且该行含本月日期时，画 `1pt` 高、颜色 `Theme.outlineVariant().opacity(0.35)` 的横线，位置 `y = i×cellH − 0.5`，横跨 `7×cellW`（`CalendarGrids.swift:206-209`）。**最后一行下方没有线**；被删掉的填充行也不会画线。
4. **单元格内容**（`DayDraw.draw`，`CalendarGrids.swift:50-135`），每格按「日号 + 农历」为一个整体垂直居中：

```
lineH    = lunarAlpha > 0.01 ? (lunarFont + 5) × lunarAlpha : 0      // :24-26
contentH = dayFont + 4 + lineH
top      = rowY + (cellH − contentH)/2        // 日号基线中心
numY     = top + dayFont/2
lunarY   = top + dayFont + 4 + lineH/2
cx       = (col + 0.5) × cellW
circleC  = top + contentH/2
circleD  = min(cellW − 3, contentH + 14, cellH − 3)                  // :84
```

   | 状态 | 画法 |
   |---|---|
   | **选中**（`dayKey == selectedKey`） | 实心圆：直径 `circleD`，填充 `Theme.primary()`（seed `#2563EB`），日号 `Theme.onPrimary()`（浅色白 / 深色 `#191C20`）、**semibold**，农历同色 `opacity 0.95` |
   | **今天**（未选中） | 圆环：同直径、`lineWidth 1.5`，描边 `Theme.primary()`；日号与农历 `Theme.primary()` |
   | 本月普通日 | 无底，日号 `Theme.onSurface()`（medium 字重），农历同色 `opacity 0.75` |
   | 非本月（仅周条/morph 出现） | 日号 `Theme.onSurface().opacity(0.5)`，整体再乘 `adjacentAlpha` |
   | **未来日**（`dayKey > todayKey`） | 整格（圆、日号、农历、下划线）alpha × **0.35**（`:68-69`） |
   | **有日记**（`flags.contains(dayKey)`） | 日号下方 2pt 高圆角条：宽 `min(20, cellW×0.5)`，`y = numY + dayFont/2 + 2`，圆角 `Radius.hairline = 1`，颜色选中时 `Theme.onPrimary()`、否则 `Theme.primary()`（`:127-134`）。**是下划线不是圆点。** |
   - 未来判定与「今天」都基于 `DateUtil.dayKeyOf`（本地日历的 `yyyy-MM-dd`）。
   - 5 行月份下方留白 = 1 × `cellH`（网格不拉伸）。
5. 颜色 token：`Theme.onSurface()` / `onSurfaceVariant()` / `outlineVariant()` 来自 Asset Catalog（`Theme.swift:31-40`），primary = `Color(hex: 0x2563EB)`（`Theme.swift:4-6`）。

### 3.2 周态

`weekLayer`（`HomeView.swift:513-546`）：

```
VStack(spacing: 0) {
  WeekdayHeaderView(weekStart:, cellW: w/7).frame(height: 30)          // :515-516
  DragPagePager(axis: .horizontal, pageSize: w) { WeekRowCanvas(...)   // :517-542
       .frame(height: 68) }
  dayContentBlock(w:, h:)                                              // :543
}.frame(width: w, height: h)
```

- 顶部**没有**大标题槽（月态那 72pt 被释放给内容）；当前月份只体现在顶栏胶囊文字里（「九月」）。
- **周条**：`WeekRowCanvas`，`weekMetrics = (cellW = w/7, cellH = 68, dayFont 20, lunarFont 11, lunarAlpha = showsLunar ? 1 : 0, dividerAlpha = 0)`（`CalendarLayout.swift:188-195`）。画法与月格完全相同（选中实心圆 / 今天圆环 / 未来 35% / 有日记下划线），但 `anchorMonth = nil`、`adjacentAlpha = 1`、`showDivider = false`（`HomeView.swift:531-539`）→ **跨月的 7 天外观一致**，只按「今天/选中/未来/有无日记」区分。
- `WeekRowCanvas` 自身**没有任何 accessibility 修饰**（`CalendarGrids.swift:256-303`）。
- **日内容块**（`dayContentBlock`，`HomeView.swift:548-583`）：

```
Divider()                                                              // 全宽系统分割线
HStack(spacing: 12) { 日期标题(15 semibold) ; Spacer() ; 农历(12, onSurfaceVariant×0.8) }
   .padding(.horizontal, 20).frame(height: 40 /* dayTitleH */)
Divider()
DayContentView(...)                                                    // 高 h − 98
```

  - 日期标题 = `L10n.weekHeaderTitle(selectedDate)`（zh「2026年9月26日 周六」），`lineLimit(1)`、`minimumScaleFactor(0.6)`、`allowsTightening(true)`；右侧农历 = `Lunar.fullLabel(selectedDate)`（zh「丙午年八月初四」），仅在 `AppLanguage.isZh` 时显示。
  - `stripBottom = 30 + 68 = 98`（`HomeView.swift:549`），内容块高度 `h − 98`。

### 3.3 年态

`yearLayer`（`HomeView.swift:462-477`）→ `DragPagePager(axis: .vertical, pageSize: h, keys: allYears /* 1900…2100 */)` → `YearPageView(year:selectedDate:flags:weekStart:containerSize:)`（`CalendarGrids.swift:340-440`）。

1. **年标题块**：高 `yearTitleH = 62`，内容底对齐（`.frame(height: 62, alignment: .bottom)`）：
   - 内层 `HStack(alignment: .center, spacing: 8)` 高 `62 − 8 = 54`，`padding(.horizontal, 20)`：
     - 左：`L10n.fmt("date_year", year)`（「2026年」），`TypeSize.display = 32` bold，颜色 `Theme.primary()`，`lineLimit(1)`、`minimumScaleFactor(0.6)`。
     - 右（仅中文）：`Lunar.yearZodiacLabel(6月1日)` → 干支 + 生肖 + 年（如「丙午马年」），`TypeSize.caption = 12`，`onSurfaceVariant().opacity(0.7)`。取 6 月 1 日作参考日，保证同一公历年标签稳定。
   - 底部 `Divider().padding(.horizontal, 20)`（`CalendarGrids.swift:369`）。
2. **迷你月矩阵**：4 行 × 3 列，行/列间距 `yearSpacing = 6`，整体 `padding(.top, 8)`、`padding(.horizontal, 16 /* yearPad */)`（`CalendarGrids.swift:372-390`）。
   - 卡片尺寸：`cardW = (w − 32 − 12)/3`，`cardH = (h − 62 − 8 − 18)/4`（`CalendarLayout.swift:197-200`）。
   - 卡片矩形：`x = 16 + col×(cardW+6)`，`y = 62 + 8 + row×(cardH+6)`，`col = (month−1)%3`，`row = (month−1)/3`（`:212-220`）。
   - 卡片**没有**背景/圆角/边框——只有标题与网格。点击整卡 → `onSelectMonth(month)`（`CalendarGrids.swift:382-383`）。
3. **迷你月内部**（`miniMonth(month:)`，`CalendarGrids.swift:395-439`）：
   - 标题：`MiniMonthLabel`，`L10n.monthName(month)`（zh「1月」…「12月」；en「Jan」…），15pt，**当月且当年**时 `.bold` + `Theme.primary()`，否则 `.semibold` + `Theme.onSurface()`；`lineLimit(1)`、`minimumScaleFactor(0.7)`；`minimumScaleFactor(0.7)`。矩形 = 卡片内缩 `miniPad = 6`、高 `miniTitleH = 17`。
   - 网格：`MonthCanvas`，矩形 = 卡片内缩 `(6, 6+17)`、尺寸 `(cardW−12, cardH−12−17)`。用 `CalendarLayout.miniMetrics(in:)`（`CalendarLayout.swift:257-267`）：
     - `cellW = gridW/7`，`cellH = gridH/6`（**永远 6 行**，不裁填充行），
     - `dayFont = clamp(min(cellW×0.95, cellH×0.5), 9, 14)`（`:206-208`），
     - `lunarFont = 6` 但 `lunarAlpha = 0` → **不画农历**；`dividerAlpha = 0` → **不画行分隔线**；
     - `showAdjacent: false` → 只画本月日期；选中圆 / 今天圆环 / 未来 35% / 有日记下划线**照旧生效**（下划线宽度 `min(20, cellW×0.5)`，18 Pro 竖屏约 7.7pt）。
     - `onTapDay: nil`、`allowsHitTesting(false)` → 迷你网格不响应点击。
   - 标题与网格都用「显式矩形 + `offset`」摆放，外层 `.frame(width: cardW, height: cardH, alignment: .topLeading)`——这是为了与 morph 里的同一张迷你月像素级对齐（`CalendarGrids.swift:405-431`）。
4. 年态下顶栏胶囊隐藏；离开年态只有两条路：点某个迷你月（morph 进该月）或上下滑换年。

### 3.4 日内容面板（`DayContentView`）

`HomeView` 里有两处宿主：竖屏周态的 `dayContentBlock`（整宽、无 `maxColumnWidth`、`bottomPadding` 默认 96）和横屏右栏 `DayPane`（`maxColumnWidth 560`、`bottomPadding 24`、额外 `padding(.bottom, max(bottomInset,24))`）。

- `ScrollView(showsIndicators: false)`（`DayContentView.swift:22`）→ `VStack(alignment: .leading, spacing: 16)`，`.padding(.horizontal, 20).padding(.top, 18).padding(.bottom, bottomPadding)`（`:23, 44-46`）。
- **有内容**：`ForEach(blocks)` → `DiaryBlockCard`；若 `dayKey == 今天`，末尾追加整宽主按钮 `GlassPrimaryButton(继续写日记)`（`:33-39`）。
- **空态**（`:54-70`）：`VStack(spacing: 12)`，`.padding(.top, 56)`，`frame(maxWidth: .infinity)`：
  - 图标 26pt、`onSurfaceVariant().opacity(0.5)`：未来 → `moon.stars`，否则 `square.and.pencil`；
  - 文案 `TypeSize.rowTitle = 15`、`onSurfaceVariant()`：未来 → `index_future_empty`「这一天还没有到来。」；否则 → `index_day_empty`「这一天没有留下日记。」
  - 仅「非未来且是今天」时给 `GlassPrimaryButton(写日记)`（`:62-66`）。
- **卡片 `DiaryBlockCard`**（`:73-114`）：
  - 头部信息行（有地点或有开始时间且 `showTime` 时才有）：`VStack(spacing: 4)`，`Label(L10n.timeOf(block.startTimeUtc), systemImage: "clock")` 与 `Label(block.locText, systemImage: "location.fill")`（后者 `lineLimit(2)`、左对齐）；`TypeSize.caption = 12`、`onSurfaceVariant()`；整块 `.contentShape(Rectangle())` + `onTapGesture` → 触觉 + 打开编辑器（`:85-104`）。
  - 正文：`DiaryPartsView(parts: ContentFlatten.parseContentCached(block.contentJson), onImageTap:, onTapText: 打开编辑器)`（`:105-110`）。
    - 分段规则：`image` 段落单独成块（图片前/后自带 `EditorDesignSize.imageSpacing = body×0.6 = 10.2pt` 的上下留白），连续的文本段落合成一个 `ReadTextView`（`MediaViews.swift:62-108`）；`VStack` 自身 `spacing: 0`。
    - 段落样式（`RichTextEngine.swift:26-107`）：title 28 / heading 22 / body 17 / quote 15；行距 `designSize×0.13`（quote 为 `×0.5`）；段前距 title/heading `×0.35`、quote `×0.4`、body 0；段后距 title/heading `×0.15`、quote `×0.4`、body 0；quote 有 `Theme.quoteBgUIColor()` 背景（`RichTextEngine.swift:235`）；`list` 用 `circle.fill` 圆点、`todo` 用 `square` / `checkmark.square.fill`（完成态删除线 + 45% 前景），标记符 15×15pt（`RichTextEngine.swift:1033-1049`）；图片按列宽铺满、圆角 `Radius.image = 14`、居中。
  - 卡片外观：`.padding(Spacing.card = 12)` + `.diaryCard(cornerRadius: Radius.card = 20)` = `secondarySystemGroupedBackground` + `outlineVariant 0.45` 的 0.5pt 描边 + `shadowColor 0.14 / radius 8 / y 2`（`Components.swift:130-160`）。
- 点图片 → `ImagePreviewView` 全屏预览（`DayContentView.swift:49-51`、`MediaViews.swift:146-187`）。

### 3.5 背景

`TabView` 各页统一 `.diaryBackground()` = `Theme.bg()` + 三个模糊圆斑 `BlobBackground`（`Components.swift:76-119`）：280pt `blobA` @(-60,220) blur 60、240pt `blobB` @(w+20,640) blur 60、200pt `blobC` @(w−30,−10) blur 55。首页自身不加卡片背景，日历直接画在斑点上。

---

## 4. 交互与手势

### 4.1 手势总表

| 手势 | 生效范围 | 阈值/判定 | 结果 |
|---|---|---|---|
| 纵向拖拽（≥8pt） | 月态分页器（竖屏）、年态分页器、横屏左栏分页器 | 见 §4.3 | 换月 / 换年（横屏换月同时移动选中日） |
| 横向拖拽（≥8pt） | **仅周态**分页器 | 同上 | 换周（选中日 ±7 天） |
| 单击日期 | `MonthCanvas` 的 `SpatialTapGesture(count: 1)`（`CalendarGrids.swift:223-227`）、`WeekRowCanvas`（`:294-301`） | 命中列 = `Int(x/cellW)`，行 = `Int(y/cellH)`；月态下相邻月日期返回 nil | 竖屏：进周态 morph；横屏：只切右栏选中日 |
| 单击「今天」/ 相对日期胶囊 | 顶栏（竖屏）/ 右栏标题行（横屏） | — | 见 §1.2「今天」三行 |
| 单击左上角胶囊 | 竖屏顶栏 | 年态不可点（`opacity 0` + 关闭命中） | 月态 → 年历 morph；周态 → 回月态 morph |
| 单击迷你月卡片 | 年态 | `contentShape(Rectangle())` + `onTapGesture` | 回该月（zoom morph） |
| 单击卡片文字/时间/地点 | 日内容面板 | — | 打开该日编辑器（`openEditor(dayKey)`） |
| 单击图片 | 日内容面板 | — | 全屏图片预览 |
| 单击「写日记 / 继续写日记」 | 日内容面板 | — | 打开编辑器 |
| **捏合缩放** | —— | **不存在** | 全仓 `Magnif*` 无任何命中：月↔年**没有**捏合手势，只由胶囊点击触发 |
| **长按** | —— | **不存在** | 首页无 `onLongPressGesture`（唯一一处在编辑器 `DiaryPageView.swift:547`） |

### 4.2 横屏上下滑翻月（`LayoutPagePager` + `selectMonthPage`）

- 左栏分页器 `axis: .vertical`、`pageSize: paneH`（`HomeView.swift:353-357`），与竖屏月态同方向（上滑 = 下一个月）。
- `onPageChange` = `selectMonthPage(key)`（`HomeView.swift:404-413`）：新月份里取**选中日的同一个日号**（超出当月天数则取当月最后一天）→ `vm.select(target)`（同时把 `monthPage` 设为该月）→ 重载右栏。这样右栏永远与左栏的选中项一致（`LandscapeLayoutUITests.swift:126-142` 守住这条）。
- 竖屏月态翻月**不做**这件事：`vm.selectMonth` 只改 `monthPage`（`HomeViewModel.swift:47-49`），选中日与右栏/周态内容留在原月份——**这是竖屏与横屏的重要差异**。

### 4.3 分页器阈值与橡皮筋（`DragPagePager.swift`）

```
DragGesture(minimumDistance: 8)                                   // :59
onChanged: t = axis == .vertical ? translation.height : translation.width
           t > 0 且没有上一页 → raw = rubber(t)
           t < 0 且没有下一页 → raw = -rubber(-t)
           offset = min(pageSize, max(-pageSize, raw))            // :62-68
onEnded:   p = predictedEndTranslation（同轴分量）
           goNext = nextKey != nil && (p < -90 || (p < 0 && t < -60))   // :74
           goPrev = prevKey != nil && (p >  90 || (p > 0 && t >  60))   // :75
           翻页动画 .snappy(duration: 0.3)，位移到 ±pageSize              // :79-81
           completion 里用**同一个无动画事务**同时把 offset 归零并 onPageChange // :82-92
           未过阈值 → .snappy(0.3) 回弹到 0                          // :94-96
rubber(x) = c·x/(x+c)，c = pageSize × 0.25                         // :101-104
```

- 每页最多偏移一页（`±pageSize` 夹紧），越界方向用橡皮筋。
- 外部改 `current`（点「今天」、morph 收尾）时 `offset` 在**无动画事务**里归零，避免多一次滑动（`:49-55`）。
- 边界：`allMonthKeys` = 1900-01 … 2100-12（`CalendarLayout.swift:289-295`，共 2412 页）；`allYears` = 1900…2100（`:297`）；`allWeekKeys` = 覆盖 1900-01-01 所在周（按周起始日向前补齐）到 2101-01-01 之前的每一周（`:319-337`，按 weekStart 缓存）。到边界后对应方向 `prevKey/nextKey == nil` → 只有橡皮筋，不翻页。
- 分页器为了性能**只在需要时构建邻页**：`showsNeighbours = !disabled || offset != 0`（`:22`），morph 期间（`disabled = true`）邻页不构建。

### 4.4 点击「今天」的三种结果

| 当前态 | 动画 | 数据 |
|---|---|---|
| 月 | `.snappy(duration: 0.3)` | `vm.resetToToday()`（selectedDate/monthPage/yearPage 一起回今天）+ `reloadDayBlocks()` |
| 周 | `.snappy(duration: 0.3)` | `vm.select(Date())` + `reloadDayBlocks()`（仍是周态） |
| 年 | 无（先无动画置 `zoom = 1` 再动画到 0） | `vm.resetToToday()` 然后 `openMonthFromYear(当前月)` |

### 4.5 手势冲突与命中细节

- 月格点击用 `SpatialTapGesture`，与分页器 `DragGesture(minimumDistance: 8)` 共存：位移 < 8pt 视为点击。
- 命中判定用「坐标 / 格尺寸」取整，**不是**逐格视图（`CalendarGrids.swift:243-253, 294-301`）；月态下落在相邻月日期的格子上会返回 nil（点空白无反应，UI 测试因此要瞄准第一行：`MorphPerfUITests.swift:44-46`）。
- 周态周条内跨月的日期**可以点**（`anchorMonth = nil` → 一律视为本月）。
- 横屏两栏互相独立：左栏上下滑翻月、右栏上下滑滚正文，不会互相抢手势（分属不同子树）。

---

## 5. 动画 / morph

### 5.1 曲线与时长（`CalendarLayout.swift:133-153`）

| 名称 | 定义 | 用途 |
|---|---|---|
| `morphDuration` | `0.6`（DEBUG 下 `SLOW_MORPH=1` 或 `-slow-morph` → `3.0`） | 月↔周与年↔月的时长 |
| `morphSlideAnimation` | `.timingCurve(0.2, 0.75, 0.3, 1.0, duration: morphDuration)` | 起步带速度、尾部减速、无回弹 |
| `morphAnimation` | `== morphSlideAnimation`（年↔月与月↔周**共用同一条曲线**，此前是 `easeInOut`，起步太慢） | 年↔月 |
| `reducedMorphAnimation` | `.linear(duration: 0.18)` | 减弱动态效果时替代上面两条（几何位移被压成一次快速交叉） |
| `.snappy(duration: 0.3)` | 系统 snappy | 分页器翻页/回弹、点「今天」（月/周） |
| `.snappy(duration: 0.25)` | 系统 snappy | 横屏点日期改选中、未来 toast 出现/消失 |
| `.diaryStandard` | `.easeInOut(duration: 0.3)`（`Components.swift:8`） | 顶栏胶囊 opacity 随 `mode` 变化 |

`HomeView` 只通过两个计算属性选曲线：`zoomAnimation`（`:161-163`）与 `slideAnimation`（`:165-167`），二者都按 `accessibilityReduceMotion` 在 `reducedMorphAnimation` 与各自默认曲线之间切换。

### 5.2 年 ↔ 月 zoom morph（`YearMonthMorphView`，`MorphViews.swift:5-93`）

驱动方式：`HomeView` 先把目标状态**无动画**写入（`withTransaction(disablesAnimations = true)`），再用 `withAnimation(zoomAnimation) { zoom = ... }` 动画进度值；视图 `Animatable` 用 `animatableData` 把每帧进度喂进 body（`:15-25`）。

- 进年历：`zoom: 0 → 1`；回月历：`zoom: 1 → 0`。内部统一取 `t = clamp01(1 − progress)`（`:28`）：`t = 1` 表示「全屏月格」，`t = 0` 表示「年历」。
- 图层（ZStack，`alignment: .topLeading`）：
  1. **年历页**（`YearPageView`，`hiddenMonth: 当月` 用 `Color.clear` 占位，避免与飞行中的月格重叠）：
     `opacity = clamp01((progress − 0.45)/0.55)`（`:44`），`scaleEffect(1 + (1 − progress)×0.6, anchor:)`（`:45`）→ 年历从 **1.6 倍**收到 1 倍；
     `anchor = UnitPoint(card.midX/size.width, card.midY/size.height)`（`:42-43`）——**锚点就是源月份迷你卡片的中心**，缩放围绕它进行。
  2. **月格**：矩形 `grid = lerp(miniGridRect(month), fullMonthGridRect, t)`（`:30-32`）——从该月在年历里的迷你网格**线性插值**到全屏月网格；
     `metrics = DayMetrics.lerp(miniMetrics, monthMetrics, t)` 后强制 `cellW = grid.width/7`、`cellH = grid.height/6`（`:34-38`）；
     `lunarAlpha = showsLunar ? clamp01((t − 0.5)/0.5) : 0`（`:39`，进度过半才淡入农历）；
     `dividerAlpha = clamp01((t − 0.55)/0.45)`（`:40`）；
     `MonthCanvas(weeks: weeks(inMonth:) /* 固定 6 行 */, showAdjacent: false, onTapDay: nil).frame(grid).offset(grid.minX, grid.minY)`（`:69-77`）。
  3. **大标题槽 + 星期栏**：固定 72 与 30 的槽位（**尺寸不随动画变化**，否则下方网格会被推动），整体 `opacity = late = clamp01((t − 0.55)/0.45)`（`:41, 57-68`）。
  4. **源月份的迷你月标题**：绝对矩形 `miniTitleRect(month:)` + `frame(alignment: .leading)` + `offset`，`opacity = clamp01((progress − 0.7)/0.3)`（`:46, 83-87`）——与年历页里的标题用**同一个矩形和同一种写法**，避免 morph 收尾时月份数字挪 1px。
- 整体 `.clipped()` + `.allowsHitTesting(false)`（`:90-91`）：morph 期间不可交互。

### 5.3 月 ↔ 周 expand morph（`MonthWeekMorphView`，`MorphViews.swift:97-180`）

驱动：`expand: 0 → 1`（进周）/ `1 → 0`（回月），同样先无动画置初值再动画。

- 常量：`titleH = 72`，`headH = 30`，`cellH = monthCellH(areaH:) = (h − 102)/6`，`stripBottom = 30 + 68 = 98`，`contentT = clamp01((progress − 0.25)/0.75)`（`:120-128`）。
- 图层：
  1. **大标题**：`offset(y: −progress×72)`、`opacity = 1 − clamp01(progress×2)`（`:130-133`）→ 前 50% 就完全淡出并上移。
  2. **星期栏**：`offset(y: lerp(72, 0, progress))`（`:134-136`）→ 从标题下方升到顶部，**这一步是「月→周」的骨架动作**。
  3. **6 行日期**（`ForEach(0..<6)`，注意 morph 用固定 6 行，不是 `displayedWeeks`）：
     - 选中行 `i == selRow`：`y = lerp(72 + 30 + i×cellH, 30, progress)`，`metrics = lerp(monthMetrics, weekMetrics, progress)`，`alpha = 1`（`:157-160`）→ 这一行升到星期栏正下方并变成 68pt 高的周条；
     - 选中行**上方**的行：`y = base − progress×(base + cellH)`（向上飞出），`alpha = 1 − clamp01(progress×1.4)`（`:161-163`）→ 60% 进度就消失；
     - 选中行**下方**的行：`y = base + progress×(h − base)`（向下飞出），同一条 alpha 曲线（`:164-166`）；
     - `adjacentAlpha = progress`（`:175`）→ 跨月日期从透明淡入；`showDivider = i > 0`（`:173`）；每行用 `WeekRowCanvas(...).frame(height: metrics.cellH).offset(y:)`，行内容按 `metrics.cellH` 收缩。
  4. **日内容块**（闭包 `content()`，即 `HomeView.dayContentBlock`）：`offset(y: stripBottom + (dayTitleH + 20)×(1 − contentT))` = 98 + 60×(1 − contentT)，`opacity = clamp01((progress − 0.3)/0.5)`（`:141-143`）→ 从下方 60pt 处滑入，进度 0.8 后完全显现。
  - 整体 `.clipped()`、`.allowsHitTesting(false)`。
- `HomeView.openDay` 与 `goBack` 的周/月分支都用 `slideAnimation`；完成后清空 `mwMorphMonth`（`:215-220, 237-242`）。

### 5.4 触觉反馈

| 时机 | 调用 | 位置 |
|---|---|---|
| 点日期（竖屏进周态） | `Haptics.tap()`（`UIImpactFeedbackGenerator(style: .light)` + `prepare()`） | `HomeView.swift:205` |
| 横屏点日期 | `Haptics.tap()` | `:505` |
| 点左上胶囊（进年历 / 回月 / 从年历选月） | `Haptics.tap()` | `:112, 180` |
| 点「今天」（月/周态） | `Haptics.tap()` | `:279, 285` |
| 周条里点日期 | `Haptics.tap()` | `:536` |
| 「今天」胶囊、`InfoCapsule`、玻璃按钮自身 | 组件内部再调一次 `Haptics.tap()` | `Components.swift:397, 203` |
| 未来日期 toast（不可达） | `.sensoryFeedback(.warning, trigger: showFutureToast)` | `HomeView.swift:63` |
| 点卡片文字 / 图片 / 时间地点 | `Haptics.tap()` | `DayContentView.swift:36, 101, 108`；`MediaViews.swift:35` |

注意：顶栏「今天」是 `Haptics.tap()` 被调用两次（`HomeView.swift:279` + `InfoCapsule` 内部 `Components.swift:397`），实际会有两下轻微撞击。

### 5.5 减弱动态效果（`accessibilityReduceMotion`）

- 两个 morph 仍然执行，但曲线换成 `.linear(duration: 0.18)`（`HomeView.swift:161-167` + `CalendarLayout.swift:145-147`），几何位移被压缩成一次很快的过渡。
- 分页器翻页（`.snappy(0.3)`）、点「今天」、横屏点日期**不受**该开关影响（没有对应处理）。
- 首页没有别的减弱动态效果分支：`DayDraw` 与日历画笔不读该开关，翻页/「今天」/横屏选日也不读。

### 5.6 morph 期间的正确性/性能约定

- morph 时三个基础层**完全不构建**（`HomeView.swift:425`），否则隐藏的年层会白建 3×12 个月画布。
- 分页器在 `disabled` 时不建邻页（`DragPagePager.swift:22`）。
- `DayDraw` 有 `ResolvedText` 缓存，键 = (文本, 半磅量化字号, 字重, 颜色, 暗色)，上限 24000 条（`CalendarGrids.swift:28-42, 139, 151-163`）；主题切换时 `DayDraw.clearCache()`（`AppConfigService.swift:20-21`）。
- `#if DEBUG` 的 `-morph-log` 会把进度写进 `MorphProgressLog`（`HomeView.swift:169-176`、`MorphViews.swift:18-24, 110-116`），仅测试用。

---

## 6. 栏内文案与格式

### 6.1 首页用到的全部字符串

| key | zh-Hans | en | 出现位置 |
|---|---|---|---|
| `date_year` | `%1$ld年` | `%1$ld` | 顶栏标题（月/年态）、年历大标题 |
| `date_month_title` | `%1$ld年 %2$@` | `%2$@ %1$ld` | 月格的无障碍 label（含空格） |
| `date_day_title` | `%1$@%2$ld日 %3$@` | `%3$@, %1$@ %2$ld` | 无障碍 value；`formatDayKey` → 右栏标题 |
| `date_day_only` | `%1$@%2$ld日` | `%1$@ %2$ld` | 其它页面的日期区间 |
| `date_range` | `%1$@ – %2$@` | 同 | 非首页 |
| `index_today` | 今天 | Today | 相对日期胶囊、右栏「今天」胶囊 |
| `index_yesterday` | 昨天 | Yesterday | 相对日期胶囊 |
| `index_tomorrow` | 明天 | Tomorrow | 相对日期胶囊 |
| `index_days_ago` | `%1$ld天前` | `%1$ld days ago` | 相对日期胶囊 |
| `index_days_later` | `%1$ld天后` | `in %1$ld days` | 相对日期胶囊 |
| `index_week_head` | `%1$ld年%2$@%3$ld日 %4$@` | `%4$@, %2$@ %3$ld, %1$ld` | 周态日内容块标题行 |
| `index_write` | 写日记 | Write | 空态按钮 |
| `index_continue_write` | 继续写日记 | Continue writing | 今天有内容时的整宽按钮 |
| `index_day_empty` | 这一天没有留下日记。 | No entry for this day. | 空态（过去/今天） |
| `index_future_empty` | 这一天还没有到来。 | This day hasn't arrived yet. | 空态（未来） |
| `index_future_toast` | 无法为未来的日期写日记。 | You can't write a diary for a future date. | 未来提示 toast（**当前不可达**） |
| （无 key）月份名 | `monthFull` = `DateFormatter("MMMM")` → 「九月」 | 「September」 | 月态大标题、周态顶栏、迷你月 a11y label |
| （无 key）短月名 | `monthName` = `shortMonthSymbols` → 「9月」 | 「Sep」 | 迷你月标题、周态标题行、月格 a11y |
| （无 key）极短星期 | `veryShortWeekdaySymbols` → 「一…日」 | 「M…S」 | 星期栏 7 个标签 |
| （无 key）短星期 | `shortWeekdaySymbols` → 「周六」 | 「Sat」 | 周态标题行、「今天」标题的日期串 |
| （无 key）农历 | `Lunar.fullLabel` → 「丙午年八月初四」；`dayLabel` → 「初一」/「八月」 | 仅中文显示 | 周态标题行右侧、右栏标题行 |

**「N 个片段」不在首页**：`read_hero_segments`（`%1$ld 个片段`）只出现在日记阅读页 `DiaryPageView.swift:470`，`footprint_stat_blocks`（`片段`）只出现在足迹页 `FootprintView.swift:98`。首页没有任何「片段计数」文案。

### 6.2 日期 / 时间格式规则

- **dayKey**：`String(format: "%04d-%02d-%02d", y, m, d)`，用 `DateUtil.calendar`（Gregorian、`Locale.current`、当前时区，`DateUtil.swift:4-14`）。首页所有「今天/未来/有日记」比较都是字符串比较 `dayKey > todayKey`（`HomeView.swift:386, 575`）。
- **周态标题行**：`L10n.weekHeaderTitle` = `fmt("index_week_head", 年, 短月名, 日, 短星期)` → zh「2026年9月26日 周六」（注意 `%2$@` 已经是「9月」，再接「26日」）；en「Sat, Sep 26, 2026」。
- **右栏标题**：`L10n.formatDayKey(dayKey)` = `dayTitle` = `fmt("date_day_title", 短月名, 日, 短星期)` → zh「9月26日 周六」；en「Sat, Sep 26」。
- **卡片时间行**：`L10n.timeOf(startTimeUtc)`，用「app 语言 + 设备 12/24 小时制」的组合：小时循环骨架 `j` 取自 `Locale.current`（尊重系统「24 小时制」），AM/PM 符号取自 app 语言（`L10n.swift:116-139`）。仅当 `AppSettings.autoTime == true` 且 `startTimeUtc > 0` 时显示。
- **相对日**：`L10n.relativeDayLabel(from: selectedDate, to: Date())` 用**自然日**差（`startOfDay` 相减，`DateUtil.swift:87-92`）：0 → 今天；−1 → 昨天；+1 → 明天；< −1 → `N天前`；> 1 → `N天后`（`L10n.swift:209-218`）。
- 所有格式化器按 locale 缓存，语言切换立即生效（`L10n.swift:83-114`）。

### 6.3 农历行规则（`Lunar.swift:19-44`）

| 场景 | 规则 |
|---|---|
| 月格 / 周条农历行 | `Lunar.dayLabel(date)`：农历日为 1 时显示月名（`闰` 前缀 + 正月…腊月），否则显示日名（初一…三十） |
| 是否显示 | 仅当 `AppLanguage.isZh`（`HomeView.swift:23`）且密度允许（竖屏恒 true；横屏 `perRow >= 60` 才 true，实测两种横屏 iPhone 都不显示） |
| 年历迷你月 | **永不显示**（`lunarAlpha = 0`，`lunarFont` 仅 6） |
| 迷你月/年标题生肖 | `yearZodiacLabel(该年 6 月 1 日)` = 干支 + 生肖 + 「年」（「丙午马年」），仅中文，仅年历标题行 |
| 周标题行 / 右栏标题 | `Lunar.fullLabel` = 干支年 + 闰月 + 月名 + 日名（「丙午年八月初四」），仅中文 |
| 农历实现 | `Calendar(identifier: .chinese)`、当前时区；干支 `stems[(y−1)%10] + branches[(y−1)%12]` |

### 6.4 相对日胶囊（顶栏右侧 / 横屏右栏「今天」）

- 竖屏顶栏：`InfoCapsule(text: relativeDayLabel(), action: todayTapped)`——文案随选中日变化（今天/昨天/明天/N天前/N天后），点它就是「回到今天」。
- 横屏右栏：固定文案 `index_today`「今天」，位置在标题行最右侧（`CalendarPaneViews.swift:179-182`）。
- 视觉：玻璃 capsule、14pt medium、`padding(12/7)`、`minimumScaleFactor(0.8)`、强调前最小 44×44 命中区（按钮自身没有最小尺寸，但行高 44/52 保证可点）。

### 6.5 日内容面板构成（竖屏周态 / 横屏右栏）

1. （竖屏）全宽 `Divider()`；
2. 标题行 40pt / 横屏 44pt：`日期串`（15pt semibold）+ `Spacer` + （中文）`农历 fullLabel`（12pt，`onSurfaceVariant×0.8`）+（横屏）「今天」胶囊；
3. （竖屏）全宽 `Divider()`；
4. `ScrollView`：18pt 顶留白 → 卡片（间距 16）→ 今天的「继续写日记」按钮 → 96（竖屏）/ 24（横屏）底留白；
5. 空态：图标 + 一行文案（+ 今天才有的「写日记」按钮），顶部再留 56pt。

---

## 7. 无障碍

### 7.1 月格（`MonthCanvas`，`CalendarGrids.swift:228-240`）

- `.accessibilityElement(children: .ignore)`：整块网格是**一个**元素（7×N 个日期不可单独聚焦）。
- `label` = `date_month_title` → 「2026年 9月」；`value` = `formatDayKey(selectedDate)` → 「9月26日 周六」。
- `.accessibilityAdjustableAction`：上/下滑动 ±1 天，回调 `onAdjacentDaySelected`——但 `MonthPane` 与年历迷你月**都没有传**这个闭包（`CalendarPaneViews.swift:59-65`、`CalendarGrids.swift:414-423`），因此该动作实际是空操作。
- `.accessibilityAction`（默认动作）：`onTapDay?(selectedDate)`。竖屏月态里 `onTapDay = openDay` → **VoiceOver 双击网格会进入周态**；横屏里 `onTapDay = selectDay` → 只改选中日。迷你月里 `onTapDay = nil` → 无操作。
- 结果：分页器同时存在 3 页，于是**同名元素最多 3 份**（UI 测试注释 `LandscapeLayoutUITests.swift:44-52`、`MorphPerfUITests.swift:31-39` 都提到这一点）。

### 7.2 周条（`WeekRowCanvas`）

- **零无障碍修饰**（`CalendarGrids.swift:256-303`）：Canvas 没有 `accessibilityElement`，因此 VoiceOver 在周态**读不到任何日期**，也无法选中日期。这是当前实现最大的无障碍缺口。

### 7.3 年历迷你月（`CalendarGrids.swift:435-438`）

- `.accessibilityElement(children: .ignore)` + `.accessibilityAddTraits(.isButton)` + `label = monthFull(该月)`（「九月」）+ `identifier = "year.month.N"`（N = 1…12）→ 12 个可聚焦按钮，可确定性定位（UI 测试用 `year.month.1`）。
- 年历大标题与生肖是普通 `Text`，各自独立可读。
- 年态下三层里的月/周层虽然 `opacity 0`，但仍被构建；顶层年层 `opacity 1`，实际 VoiceOver 顺序以真实可见元素为准。

### 7.4 顶栏

- 返回胶囊 = `Button` + identifier `home.header.back`，label 为 title 文本（「2026年」/「九月」），无自定义 hint；年态 `opacity 0` + `allowsHitTesting(false)`。
- 「今天」胶囊 = `Button`（`InfoCapsule`），label「今天/昨天/…」；**竖屏没有 identifier**，横屏右栏那枚是 `home.todayChip`。
- 顶栏整体没有 `.accessibilityElement(children: .contain)` 之类的分组。

### 7.5 分页器

- `DragPagePager` 没有任何无障碍修饰（`DragPagePager.swift` 全文），也不是可调元素：VoiceOver **无法**翻月/翻年/翻周，只能靠「今天」或胶囊跳转。

### 7.6 日内容面板

- 卡片头部的时间/地点是 `Label`（可读），整块带 `onTapGesture` → 会成为可点元素（「打开编辑器」）。
- 正文 `ReadTextView` 用 `UITextView`（`isEditable = false`、`isSelectable = false`、无自定义 a11y），UIKit 默认把整块文本当成一个文本元素；`todo` 勾选/图片点击是文本附件上的手势，没有对应的无障碍动作。
- 空态图标/文案是普通元素；按钮 `GlassPrimaryButton` 的 label 即标题文案；`GlassIconButton`/`PressableGlassIcon` 有显式 `accessibilityLabel` 兜底（首页未用到）。

### 7.7 identifier 清单（UI 测试契约）

| identifier | 位置 |
|---|---|
| `home.header.back` | 顶栏返回胶囊（`HomeView.swift:135`） |
| `home.monthTitle` | 月态大标题（`CalendarPaneViews.swift:96`）与两个 morph 里的同名字段（`MorphViews.swift:62, 131`） |
| `home.dayHeading` | 周态/右栏标题行日期（`CalendarPaneViews.swift:166`） |
| `home.todayChip` | 横屏右栏「今天」（`CalendarPaneViews.swift:181`）；竖屏无 |
| `year.month.N` | 年历迷你月（`CalendarGrids.swift:438`） |
| `app.state` | 测试探针（`RootView.swift:83`，非首页） |

### 7.8 Dynamic Type 与减弱动态效果

- 日历 Canvas 里画不出的文字（日号、农历）用 `DynamicTypeMetrics.calendarMultiplier = 1 + (multiplier(15) − 1)×0.5`（减半增长，`DesignSystem.swift:211-219`）乘在 `dayFont/lunarFont` 上（`CalendarGrids.swift:185-191, 270-276`），保证日号不会撑出格子。
- 星期栏用 `diaryCalendarFont`（同样减半，`DynamicType.swift:39-63`）；其余文本用 `diaryFont`（按 Apple 曲线 + 每档上限，`DesignSystem.swift:107-201`；AX5 时 32→44.8、15→24、12→20.4）。
- 顶栏、标题行用 `minHeight`（而非 `height`）+ `minimumScaleFactor`，保证大字级不被压扁/裁切（`HomeView.swift:146-148`、`Components.swift:184-188`、`CalendarPaneViews.swift:163-165`）。
- 减弱动态效果：见 §5.5（只有 morph 曲线被替换）。

---

## 8. 数值速查表

### 8.1 `HomeView.swift`

| 值 / 表达式 | 位置 | 含义 |
|---|---|---|
| `.month` | `:14` | 初始模式 |
| `zoom = 0`、`expand = 0` | `:15, 17` | 两个 morph 进度的初值（0 = 常态） |
| `16` / `12` | `:37-38` | 顶栏水平内边距 / 顶部留白 |
| `Capsule().glassEffect(.regular.interactive(true))` | `:131` | 返回胶囊材质 |
| `opacity(mode == .year ? 0 : 1)` + `allowsHitTesting(mode != .year)` | `:136-137` | 年态隐藏返回胶囊 |
| `HStack(spacing: 10)` | `:110` | 顶栏两项间距 |
| `chevron.left` 16pt semibold | `:116-117` | 返回图标 |
| `TypeSize.headerTitle = 18` | `:119` | 胶囊标题字号 |
| `minimumScaleFactor(0.7)` | `:121` | 胶囊标题最小缩放 |
| `padding(.horizontal, 14)` | `:126` | 胶囊内水平留白 |
| `minHeight: Spacing.hitTarget = 44` | `:127` | 胶囊最小高度 |
| `frame(minHeight: 52)` | `:148` | 顶栏最小高度（+12 = 64，正好等于 `calendarArea` 减掉的高度） |
| `showFutureToast` 停留 `2.5s`、`.snappy(0.25)`、`padding(.bottom, 24)`、`shadow(radius: 12, y: 4)`、`padding(h 16, v 10)`、`.sensoryFeedback(.warning)` | `:46-64, 305-313` | 未来 toast 视觉与时长（**不可达**） |
| `.snappy(duration: 0.3)` | `:280, 286` | 点「今天」（月/周态） |
| `.snappy(duration: 0.25)` | `:305, 311, 506` | toast、横屏点日期 |
| `splitTopPadding` / `pagePadding` | `:327, 397-398` | 横屏顶部 8 / 左右 16 |
| `paneH = splitPaneHeight(size.height)` | `:331` | 两栏共同高度（18 Pro 330 / SE 303） |
| `headerH = 34 + 26 = 60` | `:333` | 横屏紧凑标题槽 + 星期栏 |
| `density.resolve(available: paneH−60, rows: weeks, wantsLunar: showsLunar)` | `:335-337` | 横屏密度（决定农历行与是否降级周条） |
| `cellH = density.rowHeight(...)` | `:339` | 横屏每行高（18 Pro 45/54，SE 40/48） |
| `master/detail = layout.splitColumns(size.width)` | `:344-346` | 两栏宽度（345/356.5 或 307/311.5） |
| `Rectangle().frame(width: 0.5).fill(outlineVariant×0.4)` + `padding(.horizontal, gutter/2 = 8)` | `:378-381` | 栏间分隔线（实占 16.5） |
| `maxColumnWidth: 560` | `:389` | 右栏正文列宽上限 |
| `headingHeight: 44` | `:390` | 右栏标题行高（放下「今天」胶囊） |
| `h = max(320, size.height − 64)` | `:417` | 单栏日历区高度与下限 |
| `weekdayHeaderH = 30` / `weekStripH = 68` | `:516, 540-542` | 周态两段高度 |
| `cellW = w / 7` | `:515` | 星期栏列宽 |
| `stripBottom = 30 + 68 = 98` | `:549` | 日内容块起点 |
| `HStack(spacing: 12)` | `:553` | 日内容块标题行间距 |
| `TypeSize.rowTitle = 15` semibold / `minimumScaleFactor(0.6)` | `:555-561` | 日期标题字号与最小缩放 |
| `TypeSize.caption = 12`、`onSurfaceVariant×0.8` | `:565-566` | 农历标签与颜色 |
| `padding(.horizontal, 20)` / `frame(height: dayTitleH = 40)` | `:570-571` | 日内容块标题行 |
| `.frame(height: h − 98)` | `:582` | 日内容滚动区高度 |

### 8.2 `CalendarLayout.swift`

| 值 | 位置 | 含义 |
|---|---|---|
| `morphDuration = 0.6`（DEBUG 慢速 3.0） | `:133-139` | 两个 morph 的时长 |
| `.timingCurve(0.2, 0.75, 0.3, 1.0, duration:)` | `:151-153` | morph 曲线（无回弹） |
| `.linear(duration: 0.18)` | `:147` | 减弱动态效果下的 morph 曲线 |
| `lunarRowHeight = 44` | `:21` | 带农历行的最小行高 |
| `dayRowHeight = 38` | `:29` | 不带农历行的最小行高（从 44 降到 38，否则 SE 横屏退化成周条） |
| `rowHeight = max(min, floor(available/rows))` | `:44-47` | 行高分配（向下取整，绝不溢出） |
| `perRow >= 44 + 16 = 60` → 显示农历 | `:64-66` | 密度上限判据 |
| `perRow >= 38` → 无农历 | `:68-70` | 密度中档判据 |
| 否则 `.weekStrip`，`rows = 1` | `:71, 85-87` | 周条降级 |
| `weekdayHeaderH = 30` | `:155` | 竖屏星期栏高 |
| `bigTitleH = 72` | `:156` | 竖屏月标题槽高 |
| `compactMonthTitleH = 34` | `:161` | 横屏月标题槽高 |
| `compactWeekdayHeaderH = 26` | `:162` | 横屏星期栏高 |
| `weekStripH = 68` | `:163` | 周条高度（竖屏与横屏一致） |
| `dayTitleH = 40` | `:164` | 周态日内容标题行高 |
| `yearTitleH = 62` | `:166` | 年历标题块高 |
| `yearPad = 16` | `:167` | 年历左右内边距 |
| `yearSpacing = 6` | `:169` | 迷你月行/列间距 |
| `monthCellW = width / 7` | `:171` | 格宽 |
| `weekdayFontSize = clamp(cellW×0.26, 11, 14)` | `:173` | 星期栏字号 |
| `monthCellH = (areaH − 72 − 30) / 6` | `:175-177` | 月格格高（**恒除 6**） |
| `monthMetrics = (cellW, cellH, dayFont 20, lunarFont 11, lunarAlpha, dividerAlpha 1)` | `:179-186` | 月态/morph 终点的画笔参数 |
| `weekMetrics = (cellW, 68, 20, 11, lunarAlpha, dividerAlpha 0)` | `:188-195` | 周条画笔参数（无分隔线） |
| `yearCardSize = ((w − 32 − 12)/3, (h − 62 − 8 − 18)/4)` | `:197-200` | 迷你月卡片尺寸 |
| `miniDayFont = clamp(min(cellW×0.95, cellH×0.5), 9, 14)` | `:206-208` | 迷你日号字号（比旧的 11pt 大，且随格宽收缩） |
| `miniLunarHidden = 0` | `:210` | 迷你月农历 alpha 恒 0 |
| `yearCardRect = (16 + col×(cardW+6), 62 + 8 + row×(cardH+6))` | `:212-220` | 迷你月卡片绝对矩形 |
| `miniPad = 6` / `miniTitleH = 17` | `:222-223` | 卡片内边距 / 标题高 |
| `miniGridRect = card + (6, 6+17), (cardW−12, cardH−12−17)` | `:225-231` | 迷你网格矩形 |
| `miniTitleRect = card + (6, 6), (cardW−12, 17)` | `:243-249` | 迷你标题绝对矩形（年历页与 morph 必须共用） |
| `miniTitleInCard = (6,6)` / `miniGridInCard = (6, 6+17)` | `:252-253` | 卡片内偏移（年历页 ZStack 是卡片本身） |
| `miniMetrics = (gridW/7, gridH/6, miniDayFont, 6, alpha 0, dividerAlpha 0)` | `:257-267` | 迷你月画笔参数 |
| `fullMonthGridRect = (0, 72+30, w, h−102)` | `:269-274` | 全屏月网格矩形（morph 终点） |
| `monthKey = year×100 + month` | `:276-279` | 月分页键 |
| `allMonthKeys = 1900-01 … 2100-12`（2412 项） | `:289-295` | 月分页数据 |
| `allYears = 1900…2100` | `:297` | 年分页数据 |
| `allWeekKeys`：1900-01-01 所在周起，每 7 天，至 2101-01-01 | `:319-337` | 周分页键（按 weekStart 缓存） |
| `displayedWeeks`：从 6 行尾部删掉「整行不含本月」的行 | `:352-365` | 实际绘制行数（4/5/6） |
| `weeks(inMonth:)`：固定 6 行、行首 = 本月 1 日所在周首 | `:367-386` | morph 与年历用 |
| `weekRowIndex = (lead + day − 1)/7` | `:394-398` | 选中日所在行（周条模式用） |

### 8.3 `CalendarGrids.swift`

| 值 | 位置 | 含义 |
|---|---|---|
| `isWeekend = weekStart=="sunday" ? (i==0‖i==6) : (i>=5)` | `:13` | 周末着色判定 |
| 周末 `onSurfaceVariant×0.6` | `:16` | 周末标签颜色 |
| `lunarLineH = (lunarFont + 5) × lunarAlpha`（α≤0.01 时为 0） | `:24-26` | 农历行占高 |
| 文字缓存上限 `24000` 条 | `:34` | `ResolvedText` 缓存水位 |
| 字号半磅量化 `(size×2).rounded()/2` | `:139` | 缓存键（morph 时字号连续变化） |
| 未来日 `alpha × 0.35` | `:68-69` | 未来日期整体压暗 |
| `contentH = dayFont + 4 + lineH` | `:76` | 日号+农历整体高 |
| `top = rowY + (cellH − contentH)/2` | `:77` | 垂直居中 |
| `numY = top + dayFont/2`；`lunarY = top + dayFont + 4 + lineH/2` | `:78-79` | 日号/农历中心 |
| `cx = (col + 0.5) × cellW`；`circleC = top + contentH/2` | `:80-81` | 圆心 |
| `circleD = min(cellW − 3, contentH + 14, cellH − 3)` | `:84` | 选中圆/今天环直径 |
| 今天环 `lineWidth = 1.5` | `:91` | 圆环粗细 |
| 非本月日 `onSurface×0.5` | `:111` | 相邻月日期颜色（仅周条/morph 可见） |
| 农历 `opacity 0.75`（选中 0.95） | `:122` | 农历行透明度 |
| 有日记下划线：`w = min(20, cellW×0.5)`、高 `2`、`y = numY + dayFont/2 + 2`、圆角 `1` | `:127-134` | 「有日记」标记 |
| 行分隔线：`y = i×cellH − 0.5`、高 `1`、`outlineVariant×0.35`、`i > 0` | `:206-209` | 月格行线 |
| `MonthBigTitle`：`padding(.horizontal, 20)`、`minimumScaleFactor(0.6)`、bold、默认 32pt | `:313-320` | 月大标题 |
| `MiniMonthLabel`：15pt、当月 bold + primary、否则 semibold + onSurface、`minimumScaleFactor(0.7)` | `:332-336` | 迷你月标题 |
| `YearPageView` 标题 `HStack(spacing 8)`、`padding(.horizontal, 20)`、`frame(height: 62−8)` | `:353-368` | 年标题行 |
| 年历网格 `.padding(.top, 8)`、`padding(.horizontal, 16)`、`spacing 6`、4×3 | `:372-390` | 迷你月矩阵 |
| 迷你月 `hiddenMonth` → `Color.clear` 占位 | `:377-379` | morph 时挖空源月 |
| 迷你月外层 `frame(card, alignment: .topLeading)` | `:431` | 防止 ZStack 居中把内容推下 7.5pt |

### 8.4 `CalendarPaneViews.swift` / `DayContentView.swift`

| 值 | 位置 | 含义 |
|---|---|---|
| `portraitTitleHeight = bigTitleH = 72` / `portraitWeekdayHeight = 30` | `CalendarPaneViews.swift:35-36` | morph 终点（默认值必须引用这两个常量） |
| `portraitGridOriginY = 102` | `:37` | 竖屏网格起点 |
| `rowH = isWeekStrip ? min(cellH, 68) : cellH` | `:43` | 周条降级时夹住行高 |
| `dayFont = clamp(cellH×0.34, 13, 20)` | `:101-103` | 日号字号随格高（横屏 40pt 格 → 13.6） |
| `lunarFont = clamp(cellH×0.18, 9, 11)` | `:105-107` | 农历字号随格高 |
| `maxColumnWidth = 660`（默认）/ 560（首页横屏实传） | `:126` / `HomeView.swift:389` | 正文列宽上限 |
| `headingHeight = dayTitleH = 40`（默认）/ 44（横屏实传） | `:128` / `HomeView.swift:390` | 标题行高 |
| `bottomPadding = 24`、`.padding(.bottom, max(bottomInset, 24))` | `:145, 150` | 右栏底部让位 |
| `HStack(spacing: 10)`、`Spacer(minLength: 8)`、`padding(.horizontal, 20)` | `:159, 178, 184` | 右栏标题行 |
| `minimumScaleFactor(0.75)`（农历） | `:175` | 窄栏里缩而不截断 |
| `bottomPadding = 96`（竖屏）/ 24（横屏） | `DayContentView.swift:15` / `CalendarPaneViews.swift:145` | 滚动区底部留白 |
| `VStack(spacing: 16)`、`padding(h 20, top 18)` | `:23, 44-46` | 卡片列表布局 |
| 空态图标 26pt、`padding(.top, 56)`、`VStack(spacing: 12)` | `:55-69` | 空态 |
| 卡片 `.padding(Spacing.card = 12)` + `Radius.card = 20` | `:112-113` | 卡片内边距与圆角 |
| 信息行 `VStack(spacing: 4)`、`TypeSize.caption = 12`、地点 `lineLimit(2)` | `:86-97` | 时间/地点行 |
| 阅读器 `textContainerInset (2, 0, 2, 0)`、图片上下 `10.2pt` | `MediaViews.swift:76, 82` | 段落节奏 |

### 8.5 `MorphViews.swift`

| 值 | 位置 | 含义 |
|---|---|---|
| `t = clamp01(1 − progress)` | `:28` | 年↔月：1 = 全屏月格，0 = 年历 |
| `grid = lerp(miniGridRect, fullMonthGridRect, t)` | `:30-32` | 月格矩形插值 |
| `metrics = lerp(miniMetrics, monthMetrics, t)`，`cellW = grid.w/7`、`cellH = grid.h/6` | `:34-38` | 画笔参数插值 |
| `lunarAlpha = clamp01((t − 0.5)/0.5)` | `:39` | 农历行半程后淡入 |
| `dividerAlpha = clamp01((t − 0.55)/0.45)` | `:40` | 行分隔线后半程淡入 |
| `late = clamp01((t − 0.55)/0.45)` | `:41` | 标题槽+星期栏不透明度 |
| `anchor = UnitPoint(card.midX/w, card.midY/h)` | `:42-43` | 年历缩放锚点 = 源月卡片中心 |
| `yearOpacity = clamp01((progress − 0.45)/0.55)` | `:44` | 年历页淡入 |
| `yearScale = 1 + (1 − progress)×0.6` | `:45` | 年历从 1.6× 收到 1× |
| `labelOpacity = clamp01((progress − 0.7)/0.3)` | `:46` | 源月迷你标题最后出现 |
| 标题槽固定 `bigTitleH = 72` / 星期栏 `weekdayHeaderH = 30` | `:64-66` | 尺寸固定，避免推动网格 |
| `contentT = clamp01((progress − 0.25)/0.75)` | `:128` | 月↔周日内容进度 |
| 大标题 `offset(y: −progress×72)`、`opacity = 1 − clamp01(progress×2)` | `:132-133` | 标题上移并在 50% 前消失 |
| 星期栏 `offset(y: lerp(72, 0, progress))` | `:136` | 升到顶部 |
| 选中行 `y = lerp(72+30+i×cellH, 30, progress)` + `metrics = lerp(monthMetrics, weekMetrics, progress)` | `:157-160` | 选中行展开成周条 |
| 上方行 `y = base − progress×(base + cellH)`；下方行 `y = base + progress×(h − base)` | `:161-166` | 上下飞出 |
| 非选中行 `alpha = 1 − clamp01(progress×1.4)` | `:163, 166` | 60% 进度隐没 |
| `adjacentAlpha = progress`；`showDivider = i > 0` | `:173-175` | 跨月日期淡入、保留行线 |
| 日内容 `offset(y: 98 + (40+20)×(1 − contentT))`、`opacity = clamp01((progress − 0.3)/0.5)` | `:142-143` | 从下方 60pt 滑入 |
| 两个 morph 都 `.clipped()` + `.allowsHitTesting(false)` | `:90-91, 146-147` | morph 期间不响应点击 |

### 8.6 `DragPagePager.swift`

| 值 | 位置 | 含义 |
|---|---|---|
| `showsNeighbours = !disabled ‖ offset != 0` | `:22` | 邻页懒构建 |
| `showsIndicators` 无关；`.clipped()` 每页单独裁剪 | `:34-35` | 防溢出 |
| `onChange(of: current)` 无动画归零 offset | `:49-55` | 避免多一次滑动 |
| `DragGesture(minimumDistance: 8)` | `:59` | 与点击手势的分界 |
| `offset` 夹在 `±pageSize` | `:68` | 一页之内 |
| 翻页判据 `p < −90 ‖ (p < 0 && t < −60)` / `p > 90 ‖ (p > 0 && t > 60)` | `:74-75` | 速度优先，位移兜底 |
| 翻页动画 `.snappy(duration: 0.3)`；收尾同事务改 `offset` + `current` | `:79-92` | 无跳变衔接 |
| 回弹 `.snappy(duration: 0.3)` | `:94-96` | 未过阈值 |
| `rubber(x) = c·x/(x+c)`，`c = pageSize×0.25` | `:101-104` | 边界橡皮筋 |

### 8.7 `AdaptiveLayout.swift`

| 值 | 位置 | 含义 |
|---|---|---|
| `bottomInset = max(0, safeArea.bottom)` | `:27` | 底部安全区（横屏 20 / SE 横屏 0） |
| `contentWidth = w − max(0,leading) − max(0,trailing)` | `:35-37` | 可用内容宽（18 Pro 横屏 750） |
| `isPortrait = size.height >= size.width` | `:40` | 竖屏判定（决定浮条让位高度） |
| `minMasterWidth = 260` / `maxMasterWidth = 440` | `:45, 47` | 主栏上下限（每格 ≥ 37pt） |
| `minDetailWidth = 240` | `:49` | 详情栏下限 |
| `splitGutter = 16` | `:51` | 栏间距（分隔线左右各 8） |
| `splitSeparatorWidth = 16.5` | `:53` | 栏间距 + 细线 |
| `splitTopPadding = 8` | `:55` | 分栏距顶留白 |
| `minSplitContentWidth = 548.5` | `:62-63` | 分栏阈值 |
| `splitsMasterDetail = 宽>高 && contentWidth ≥ 548.5` | `:75-77` | 唯一分栏判据 |
| `splitColumns`：46% 夹在 260…440，再给详情栏留 240 | `:88-94` | 两栏宽度 |
| `splitPaneHeight = max(220, h − 8 − tabBarClearance)` | `:100-102` | 两栏高度（下限 220） |
| `tabBarHeightPortrait = 83` / `tabBarHeightLandscape = 64` | `:113-114` | 底部浮条实测高度（按方向取，不叠加 inset） |
| `pagePadding = 16` | `:144` | 四屏统一页边距（不再叠加安全区） |

### 8.8 `DesignSystem.swift` / `Components.swift` / `Theme.swift`

| 值 | 位置 | 含义 |
|---|---|---|
| `Radius.card = 20`、`panel = 26`、`field = 14`、`image = 14`、`badge = 12`、`bar = 4`、`hairline = 1` | `DesignSystem.swift:13-34` | 圆角标尺（首页用 card / image / hairline） |
| `Spacing.card = 12`、`rowIcon = 12`、`hitTarget = 44` | `:40-46` | 间距标尺（首页用 card / hitTarget） |
| `TypeSize`：display 32 / pageTitle 24 / sheetTitle 17 / cardTitle 20 / headerTitle 18 / statValue 18 / rowTitle 15 / rowSub 13 / rowValue 15 / sectionTitle 13 / chip 14 / badge 12 / meta 13 / caption 12 | `:55-86` | 字号标尺 |
| Dynamic Type 上限：largeTitle 1.35 / title1 1.40 / title2·3 1.45 / body 1.50 / callout 1.55 / subheadline 1.60 / footnote 1.65 / caption1 1.70 / 其它 1.75 | `:124-136` | 每档最大放大倍数 |
| 字号→textStyle 阈值：≥34 / 28–34 / 22–28 / 20–22 / 17–20 / 16–17 / 15–16 / 13–15 / 12–13 / <12 | `:143-156` | 用 Apple 曲线而不是统一倍数 |
| `calendarMultiplier = 1 + (multiplier(15) − 1)×0.5` | `:217-219` | 日历 Canvas 文字减半增长 |
| `.diaryStandard = easeInOut(0.3)`、`.diaryQuick = easeOut(0.22)` | `Components.swift:7-8` | 通用动画 |
| `InfoCapsule`：chip 14 medium、`padding(12/7)`、`minimumScaleFactor 0.8`、玻璃 capsule | `Components.swift:391-412` | 「今天」胶囊 |
| `GlassPrimaryButton`：rowTitle 15 medium、`padding(h 20, v 12)`、`primary` 填充 + 玻璃 + `glowColor` 阴影 `radius 10, y 3`、禁用态 `primaryContainer×0.6` | `Components.swift:193-243` | 「写日记 / 继续写日记」 |
| `diaryCard`：`secondarySystemGroupedBackground` + `outlineVariant 0.45` 的 0.5pt 描边 + `shadowColor 0.14 / radius 8 / y 2`（对比度增强时 0.55/0.9、1.5pt） | `Components.swift:130-160` | 内容卡片材质 |
| `BlobBackground` 三个斑：280/240/200pt，blur 60/60/55 | `Components.swift:90-97` | 页面背景 |
| `Theme.seed = #2563EB`；`onPrimary` = 白 / 暗色 `#191C20` | `Theme.swift:4-10` | 主色与选中日文字色 |
| `DayDraw` 用色：primary（选中圆/今天环/下划线）、onPrimary（选中文字）、onSurface（本月）、onSurface×0.5（相邻月）、outlineVariant×0.35（行线） | `Theme.swift:31-40` + `CalendarGrids.swift:86-134` | 日历配色 |

---

## 附录 A. 与旧文档 / 直觉不一致的地方（重建时必须知道）

1. **没有捏合缩放，也没有长按**。月↔年是「点左上角胶囊」触发的 `zoom` 进度动画（`HomeView.swift:252-269`）；`zoom` 是 `Double`，阈值只有内部的 `0.45 / 0.55 / 0.7` 淡入淡出阈值（`MorphViews.swift:39-46`），没有手势阈值可谈。全仓无 `MagnificationGesture` / `MagnifyGesture`（`git log -S` 也查不到）。
2. **横屏没有年份入口**（`home.yearChip` 已删除）：分栏时整块顶栏隐藏，`settleToMonth()` 保证不会停在年/周态（`HomeView.swift:31-41, 67-71`）。
3. **未来日期 toast 是死代码**：`DayContentView.showFutureToast` 声明了但从未调用（`DayContentView.swift:12`；横屏 `DayPane` 传的是空闭包 `CalendarPaneViews.swift:144`），空态对未来日只显示「这一天还没有到来。」且不提供写入按钮，因此 `showFutureDateToast()` / `showFutureToast` 永不可达。toast 还挂在 `.overlay(alignment: .bottom)` 上、只留 24pt——真触发会压在系统浮条区域。
4. **月格不是「圆点」而是下划线**：`min(20, cellW×0.5)` 宽、2pt 高，位于日号正下方 2pt（`CalendarGrids.swift:127-134`）。
5. **竖屏翻月不移动选中日**（只换 `monthPage`），横屏翻月会移动（`selectMonthPage`）。横屏两栏因此始终自洽，竖屏则可能出现「选中日不在当前月」的状态——这是设计使然，Android 端若照抄需明确取舍。
6. **年历里选月不改 `selectedDate`**：`openMonthFromYear` 只 `vm.selectMonth`（`HomeView.swift:189`），所以相对日期胶囊仍按旧选中日显示。
7. **月格格高恒按 6 行计算**：4/5 行的月份在下方留白，不会把行撑高（`CalendarLayout.swift:175-177`）。
8. **密度降级只在横屏可能出现**：竖屏 `density` 硬编码 `.month(lunar: showsLunar)`（`HomeView.swift:495`），高度不足时也不会变周条（只有 `h` 的 320pt 下限兜底）。
9. **无障碍缺口**：整块月网格是一个元素（无法逐日聚焦）、可调动作因未传回调而无效、周条完全没有无障碍、分页器没有翻页动作（`CalendarGrids.swift:228-240, 256-303`、`DragPagePager.swift`）。另因分页器同时构建 3 页，同名元素最多 3 份。
10. **顶栏「今天」在竖屏没有 identifier**（只有横屏右栏那枚有 `home.todayChip`）。
11. **点「今天」会连发两次轻触觉**（`HomeView.swift:279` + `InfoCapsule` 内部 `Components.swift:397`）。
12. **任何显示类设置变化都会把首页重置到今天 + 月态**（`.uiTickChanged` → `resetToToday()`，且 `RootView` 的 `.id` 会重建整棵树）。
13. 旧 `home.md` 的横屏数字 `bottomInset + 44`、`contentWidth >= 700`、`calendarW = min(masterWidth, max(260, size.width − 32 − 260))` 与竖屏 `areaH = 810 / cellH = 118` **均已过时**；现行公式见 §2.3 与 §2.2。
14. **竖屏日历纵向铺到屏幕底边**：6 行月份的最后一行（日号中心 ≈ 800–840pt）落在系统浮条（791–874pt）之下，5 行月份才有 25pt 空隙；截图逐像素反推的完整证据见 §2.2。
15. **点「今天」时月份是瞬移而不是滑动**：`DragPagePager` 在 `current` 外部变化时用无动画事务把 `offset` 归零（`DragPagePager.swift:49-55`），所以 `resetToToday()` 的 `.snappy(0.3)` 只作用于右栏/内容，月历本身直接跳页。

## 附录 B. Android / Compose 落地提示（对应关系）

| iOS | Compose 对应 |
|---|---|
| 三态 ZStack + opacity/allowsHitTesting | 单 `Box` + `when(mode)`（**不要**真叠三层；三层同建只为了 morph 期间的交叉淡出，Compose 可用 `AnimatedContent` 或自绘进度） |
| `Canvas` + `DayDraw` | `Canvas` / `drawIntoCanvas` 自绘同一份几何（别用 7×6 个 `Box`，横屏 morph 靠逐格插值） |
| `DragPagePager` | `HorizontalPager`/`VerticalPager`（`PagerState`），阈值语义需自行实现或接受 Compose 默认的 50% 位移阈值 |
| `DayMetrics` / `CL.lerp` | `@Immutable data class` + `lerp`（Compose 有 `androidx.compose.ui.geometry.lerp`） |
| `glassEffect` / `InfoCapsule` | M3 `AssistChip` / `Surface(shape = CircleShape, tonalElevation, blur)` |
| `L10n` + `Localizable.xcstrings` | 同名 key 的 `strings.xml`（zh/en 两份），农历需引入 lunar 库（`Lunar.dayLabel` 规则见 §6.3） |
| `CalendarDensity` | 把 §2.3.2 的三档判据原样移植（44/38/60 三个数字 + 向下取整） |
| 分栏判据 | `BoxWithConstraints`：`maxWidth > maxHeight && contentWidth >= 548.5.dp`；两栏宽度公式 `splitColumns`（46%，260…440，保 240） |
| 浮条让位 | 横屏 64dp / 竖屏 83dp 的实测常量（不要试图从 insets 推导） |
