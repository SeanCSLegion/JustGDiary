# JustADiary 首页日历 / 动画 / 自适应版面 —— Android(Kotlin + Jetpack Compose) 迁移技术报告

> 只读分析了 `/Users/cov/JustADiary`（未做任何修改）。
> **范围**：仅 iPhone 竖屏 + iPhone 横屏。凡涉及 iPad / Mac 三栏的代码与文档，一律标注「本次跳过」。
> 参考基线：iPhone 18 Pro / iOS 27 模拟器；竖屏 402×874pt，横屏 874×402pt。
> 关键结论均附 `文件:行号`，常量均为源码实测值。

---

## 0. 结论摘要（先看这 10 条）

1. **首页竖屏是三态 morph 机（年↔月缩放、月↔周展开），横屏是两栏静态布局 + 单一「上下滑翻月」**。两条路径在 `HomeView.body` 第 31–41 行分叉：`layout.splitsMasterDetail` 为真走 `splitCalendarArea`，否则走 `header + calendarArea`。
2. **morph 不用 SwiftUI 的 matchedGeometryEffect，而是自己写 `Animatable`**：`YearMonthMorphView` / `MonthWeekMorphView` 各持一个 `progress: Double`，在 `animatableData.set` 里插值出一整套矩形与字号（`MorphViews.swift:15-25, 107-117`）。这决定了 Compose 的正确映射是「一个 progress 浮点 + 手写插值」，不是 `SharedTransitionLayout`。
3. **morph 时长 0.6s，曲线 `timingCurve(0.2, 0.75, 0.3, 1.0)`**；年↔月与月↔周共用同一条曲线（`CalendarLayout.swift:103-123`）。减弱动态效果降级为 `.linear(duration: 0.18)`（:117）。
4. **日历全部用 `Canvas` 手绘**（`CalendarGrids.swift:195, 280`），文字用 `GraphicsContext.resolve` + 自建 LRU 缓存（上限 24000，`CalendarGrids.swift:29-34`）。没有 `drawingGroup`、没有 `PhaseAnimator`、没有 `TimelineView`（后者只用在装饰性 `FlowLightOverlay`）。
5. **竖屏格子几何是定式**：标题槽 72 + 星期栏 30 + 6 行 `(areaH-102)/6`；横屏压缩为 34 + 26，且行数按 `displayedWeeks` 实算、格子高度按可用高度实算（`CalendarLayout.swift:125-156, 322-335`）。**「日号写死 20pt」是原横屏重叠的根因**，现已改为按格高推导（`CalendarPaneViews.swift:101-107`）。
6. **横屏 `paneH = max(220, 402-8-(bottomInset+44)) = 330`**；`density.resolve(330-60=270, rows=5|6, wantsLunar)` → 恒为 `.month(lunar: false)`（`HomeView.swift:327-341`）。**周条在 402pt 高的横屏上不会触发**，触发条件是 `paneH < 60 + 44×rows`（见 §2.5）。
7. **日记日期标记是「2pt 圆角下划线」不是圆点**：宽 `min(20, cellW*0.5)`、高 2、圆角 `Radius.hairline = 1`（`CalendarGrids.swift:127-134`）。今天＝1.5pt 描边圆环，选中＝实心 primary 圆，未来＝整体 alpha ×0.35。
8. **动态字体不是全局系数**：每个设计字号按「最接近的 Apple 文本样式」查 `UIFontMetrics`，再按样式封顶 1.35–1.75（`DesignSystem.swift:107-196`）。日历 Canvas 与星期栏用**半速**系数 `1 + (m(15)-1)/2`（`DesignSystem.swift:217-219`、`DynamicType.swift:39-63`）。Compose 无逐样式曲线，需重建一套「查表 + fontScale 夹紧」。
9. **玻璃是「控件层」专属策略**：内容卡片走 `diaryCard`（不透明分组背景 + 0.5pt 描边 + 阴影），控件走 `.glassEffect` / `.buttonStyle(.glass)`（`Components.swift:105-144` 与全仓 14 处 `.glassEffect`/`.buttonStyle(.glass)`）。这是 Compose 端最需要重新设计的部分——`Modifier.blur` 只模糊**自身内容**且 API<31 无效，背景模糊必须靠 haze 或 `GraphicsLayer` 录制。
10. **横屏那套 62pt 左侧避让在 Android 上不存在**：iOS 的 `safeArea.leading = 62` 来自「浮条变左侧竖排胶囊」，Android 用 `NavigationBar`/`NavigationRail` + `WindowInsets` 即可，`contentInset` 整套代码可以删掉。这是迁移中最大的「减法」。

---

## 1. 首页信息架构与交互清单

### 1.1 视图层级（竖屏）

```
HomeView (GeometryReader)
├─ header  (HomeView.swift:109-149)
│   ├─ 「‹ 年月/年/月」胶囊  → goBack()           [glassEffect at :131]
│   └─ InfoCapsule(相对日期)  → todayTapped()     [Components.swift:368-389]
└─ calendarArea(size:)  (HomeView.swift:413-458) —— ZStack(alignment: .top)
    ├─ yearLayer  (opacity: mode == .year ? 1 : 0)   DragPagePager(axis: .vertical)
    ├─ monthLayer (opacity: mode == .month ? 1 : 0)  DragPagePager(axis: .vertical)
    ├─ weekLayer  (opacity: mode == .week ? 1 : 0)   WeekdayHeader + DragPagePager(axis: .horizontal) + dayContentBlock
    ├─ YearMonthMorphView  (if ymMorph != nil)
    └─ MonthWeekMorphView  (if mwMorphMonth != nil)
```

- 三态由 `@State mode: CalendarMode`（`.year/.month/.week`，`HomeView.swift:14`）+ `zoom`（年↔月进度，:15）+ `expand`（月↔周进度，:17）驱动。
- **morph 期间三个底层 layer 完全不构建**（`if !morphing { ... }`，`HomeView.swift:423-433`），因为 `.opacity(0)` 的视图仍会每帧求值；这是 `docs/animation-and-accessibility.md:17-36` 记录的掉帧根因（每帧 55 个 Canvas，可见仅 13 个）。
- 横屏：`splitCalendarArea`（`HomeView.swift:322-399`）= `HStack{ MonthPane | 0.5pt 竖分隔线 | DayPane }`，**整块 header 隐藏**（`HomeView.swift:31-34`）。竖屏/横屏切换时 `onChange(of: layout.splitsMasterDetail)` → `settleToMonth()` 无动画收回年/周态（:67-71, 82-94）。

### 1.2 三种日历形态的切换方式

| 切换 | 触发 | 实现 | 起点状态 | 终点形态 |
|---|---|---|---|---|
| 月 → 年 | 竖屏点左上角年月胶囊（`home.header.back`，`HomeView.swift:111-138`）→ `goBack()` → `showYearPage()`（:252-269） | `withTransaction(disablesAnimations)` 先设 `ymMorph=(year, monthPage); mode=.year; zoom=0`，再 `withAnimation(zoomAnimation){ zoom=1 }` | `zoom=0`：整屏月历（72 标题 + 30 星期栏 + 6 行月格） | `zoom=1`：整屏年历（`YearPageView`：62 标题 + 4×3 迷你月卡片），**当前月卡片被 `hiddenMonth` 挖空**（`CalendarGrids.swift:377-379`） |
| 年 → 月 | 竖屏年历里点某个迷你月（`openMonthFromYear`，`HomeView.swift:178-201`） | 先设 `ymMorph; mode=.month; zoom=1`，再动画 `zoom=0` | `zoom=1`：年历（选中月卡片挖空，其迷你月标题浮在 `miniTitleRect`） | `zoom=0`：整屏月历，标题/星期栏/网格在固定槽位 |
| 月 → 周 | 竖屏点某个日期（`openDay`，`HomeView.swift:203-222`；经 `selectDay` :501-509） | 先设 `mwMorphMonth; mode=.week; expand=0`，再动画 `expand=1` | `expand=0`：月历 6 行 | `expand=1`：30pt 星期栏 + 68pt 周条（选中日所在那一周）+ 日期标题行 40pt + 日记内容区 |
| 周 → 月 | 竖屏再点左上角胶囊（`goBack()` case `.week`，`HomeView.swift:227-242`） | 先设 `mwMorphMonth; mode=.month; expand=1`，再动画 `expand=0` | 周态 | 月态 |
| 年 → 月（今天） | 年态点「今天」（`todayTapped()` :275-277） | `vm.resetToToday()` 后直接调 `openMonthFromYear(当月)` | 年态 | 月态 |
| 横屏：翻月 | 左栏**上下滑**（`DragPagePager(axis: .vertical)`，`HomeView.swift:353-360`） | `selectMonthPage(key)`（:402-411）把选中日带到新月的同一天，月末用 `range(of:.day)` 夹住 | — | 右栏 `DayPane` 同步 |
| 横屏：点日期 | 点左栏日期（`selectDay` :501-509） | 只 `vm.select(day)` + `.snappy(0.25)`，**不进入周视图** | — | 右栏换成该日日记 |

> **横屏不提供年份切换**：`header` 整块不构建，`showYearPage()` 在横屏不可达（`HomeView.swift:250-252` 注释、`docs/adaptive-layout-plan.md:17-18, 155, 172`）。

### 1.3 morph 起止状态与目标形态（精确）

**年↔月（`YearMonthMorphView`，`MorphViews.swift:5-93`）**
- `t = clamp01(1 - progress)`，`progress == zoom`。
- 网格矩形：`CL.lerp(miniGridRect(month), fullMonthGridRect, t)`（:30-32）。`fullMonthGridRect = (0, 72+30, w, h-102)`（`CalendarLayout.swift:239-244`）。
- 度量：`DayMetrics.lerp(miniMetrics, monthMetrics, t)`，再**强制** `cellW = grid.width/7`、`cellH = grid.height/6`（:36-38）。
- `lunarAlpha = showsLunar ? clamp01((t-0.5)/0.5) : 0`（:39）；`dividerAlpha = clamp01((t-0.55)/0.45)`（:40）。
- 月份大标题 + 星期栏整体 `opacity = clamp01((t-0.55)/0.45)`，且放在**固定高度 72 的 ZStack** 里，避免生长推动网格（:57-68）。
- 年历层：`yearOpacity = clamp01((progress-0.45)/0.55)`，`yearScale = 1 + (1-progress)*0.6`，`anchor = UnitPoint(card.midX/w, card.midY/h)`（:43-45, 55-56）——即**以选中月卡片中心为锚点缩放**。
- 源头迷你月标题：`opacity = clamp01((progress-0.7)/0.3)`（:46, 84-87）。
- 两侧必须共用 `miniTitleRect` / `miniMetrics` / `miniGridRect`，否则收尾会跳 1/3pt 或字号突变（:78-83、`CalendarLayout.swift:203-237`）。

**月↔周（`MonthWeekMorphView`，`MorphViews.swift:97-179`）**
- `selRow = weekRowIndex(of: selectedDate, in: month, ws)`（:121）；固定 6 行参与动画。
- 选中行：`y = lerp(titleH+headH+selRow*cellH, headH, progress)`，`metrics = lerp(monthMetrics, weekMetrics, progress)`（:157-160）。
- 选中行之前：`y = base - progress*(base+cellH)`；之后：`y = base + progress*(size.height - base)`；两者 `alpha = 1 - clamp01(progress*1.4)`（:161-167）。
- 大标题 `offset(y: -progress*titleH)`、`opacity = 1-clamp01(progress*2)`（:130-133）；星期栏 `offset(y: lerp(titleH, 0, progress))`（:134-136）。
- 内容区：`contentT = clamp01((progress-0.25)/0.75)`；`offset(y: stripBottom + (dayTitleH+20)*(1-contentT))`（`stripBottom = 30+68 = 98`），`opacity = clamp01((progress-0.3)/0.5)`（:127-128, 141-143）。

### 1.4 日期状态标识（全部由 `DayDraw.draw` 决定，`CalendarGrids.swift:50-135`）

| 状态 | 视觉 | 代码 |
|---|---|---|
| 选中日 | **实心** `Theme.primary()` 圆 | `:86-89` |
| 今天（未选中） | **1.5pt 描边** primary 圆环 | `:90-97` |
| 有日记 | **2pt 高圆角下划线**，宽 `min(20, cellW*0.5)`，圆角 1，y = `numY + dayFont/2 + 2`；颜色 primary（选中时 onPrimary） | `:127-134` |
| 未来日 | 整个 cell `alpha *= 0.35` | `:68-69` |
| 非本月日 | 仅当 `showAdjacent == true` 才画，文字色 `onSurface().opacity(0.5)`；`MonthCanvas(showAdjacent:false)` 时**直接跳过不画** | `:70-72, 107-112, 210-214` |
| 日号文字色 | 选中→`onPrimary`；今天→`primary`；本月→`onSurface`；非本月→`onSurface().opacity(0.5)` | `:99-113` |
| 农历行 | `textColor.opacity(isSelected ? 0.95 : 0.75)`，字号 `m.lunarFont` | `:121-123` |
| 行分隔线 | `outlineVariant().opacity(0.35 * dividerAlpha)`，`y = rowY - 0.5`，高 1，宽 `cellW*7`；**末尾整行不属于本月的填充行不画线** | `:206-209` |

选中圆直径：`circleD = min(cellW - 3, contentH + 14, cellH - 3)`，其中 `contentH = dayFont + 4 + lunarLineH`，`lunarLineH = lunarAlpha > 0.01 ? (lunarFont+5)*lunarAlpha : 0`（:24-26, 75-84）。竖屏 402pt 宽时 cellW=57.43、circleD≈54；横屏 cellW=49.29、circleD≈36.4。

### 1.5 翻月 / 翻年手势与边界

- **键空间**：`allMonthKeys` = 1900…2100 × 12 = **2412 个键**，键值 `year*100+month`（`CalendarLayout.swift:246-265`）；`allYears` = 1900…2100 = **201 项**（:267）；`allWeekKeys` 从 1900-01-01 所在周起、每 7 天一键，到 2101-01-01 止，按 `weekStart` 缓存（:287-307）。
- **边界**：到首页/末页时 `prevKey/nextKey == nil`，拖动只走橡皮筋，不回弹到越界页（`DragPagePager.swift:63-68`）。
- **翻月后选中日跟随**（横屏）：`selectMonthPage` 用 `min(日, 当月天数)` 夹住（`HomeView.swift:402-411`）。
- **竖屏翻周**：`weekLayer` 的 `onPageChange` 把「周起始差天数」加到 `selectedDate` 上再 reload（`HomeView.swift:520-526`）。

### 1.6 DragPagePager 的行为与阈值（`DragPagePager.swift:1-105`）

| 项 | 值 |
|---|---|
| 结构 | 只构建 `prev / current / next` 三页，`ZStack` + `offset`；**`disabled == true` 时除非 `offset != 0` 否则不构建邻页**（:22, 24-44） |
| 手势 | `DragGesture(minimumDistance: 8)`（:59） |
| 拖动进度 | `offset = min(pageSize, max(-pageSize, raw))` —— **夹在一页之内**，避免露白再回弹（:66-68） |
| 越界橡皮筋 | `rubber(x) = c*x/(x+c)`，`c = pageSize*0.25`（:101-104） |
| 翻页判定 | `goNext = nextKey != nil && (predictedEnd < -90 || (predictedEnd < 0 && translation < -60))`；`goPrev = prevKey != nil && (predictedEnd > 90 || (predictedEnd > 0 && translation > 60))`（:74-75）。**阈值 90pt（预测终点）/ 60pt（当前位移）** |
| 翻页动画 | `.snappy(duration: 0.3)`（:79, 94） |
| 收尾 | `offset = 0` 与 `onPageChange(target)` 必须在**同一个 `disablesAnimations` 事务**里，否则会先用旧 offset 渲染一帧新页造成末尾跳变（:82-91，`docs/adaptive-layout-plan.md:217-225`） |
| 外部改页 | `onChange(of: current)` 里无动画把 `offset` 清零，避免继承调用方的 `withAnimation` 多滑一次（:49-55） |

### 1.7 点击日期进入日记页的路径

日期点击 **不直接打开编辑器**，只切换选中态/进入周视图：

```
点日期 → selectDay(day)  (HomeView.swift:501-509)
        ├─ 横屏：vm.select(day) + reloadDayBlocks → 右栏 DayPane 刷新
        └─ 竖屏：openDay(day) → 月↔周 morph → reloadDayBlocks → weekLayer 下半部 DayContentView
        
点日记卡片（时间/地点行、正文、图片）→ openDiary(dayKey) = openEditor  (DayContentView.swift:92-102)
点「继续写 / 写日记」GlassPrimaryButton → openEditor(dayKey)  (DayContentView.swift:30-34, 59-61)
        ↓
HomeView.openEditor 闭包 → RootView: appState.editorDayKey = dayKey; appState.presentEditor = true  (RootView.swift:33-36)
        ↓
RootView.fullScreenCover → DiaryPageView(dayKey:)  (RootView.swift:87-103)
```

`DayContentView` 的 `showFutureToast` 参数（`DayContentView.swift:12`）**从未被调用**（`docs/design-system.md:267-268` 明确记录为死参数），所以「点未来日期弹 toast」这条路径实际不存在；`HomeView.showFutureDateToast`（`HomeView.swift:303-315`）与底部 toast overlay（:45-65）目前是接了但触发不了的死代码。

---

## 2. 日历布局数学（`CalendarLayout.swift`）

### 2.1 全部度量常量（确切值）

| 常量 | 值 | 位置 | 用途 |
|---|---|---|---|
| `morphDuration` | **0.6**（DEBUG 下 `SLOW_MORPH=1` 或 `-slow-morph` → **3.0**） | :103-109 | morph 时长 |
| `reducedMorphAnimation` | `.linear(duration: 0.18)` | :117 | 减弱动态效果 |
| `weekdayHeaderH` | **30** | :125 | 竖屏星期栏 |
| `bigTitleH` | **72** | :126 | 竖屏月大标题槽 |
| `compactMonthTitleH` | **34** | :131 | 横屏紧凑月标题 |
| `compactWeekdayHeaderH` | **26** | :132 | 横屏紧凑星期栏 |
| `weekStripH` | **68** | :133 | 周条行高 |
| `dayTitleH` | **40** | :134 | 周态日期标题行 |
| `yearTitleH` | **62** | :136 | 年历标题槽 |
| `yearPad` | **16** | :137 | 年历左右边距 |
| `yearSpacing` | **6** | :139 | 年历月卡片间距（原 10） |
| `miniPad` | **6** | :192 | 迷你月卡片内缩 |
| `miniTitleH` | **17** | :193 | 迷你月标题槽 |
| `miniLunarHidden` | **0** | :180 | 迷你月不显示农历 |
| `CalendarDensity.minimumRowHeight` | **44** | :21 | 每行最小高度 |

### 2.2 派生公式

```
monthCellW(width)                 = width / 7                                    (:141)
weekdayFontSize(cellW)            = clamp(cellW * 0.26, 11, 14)                  (:143)
monthCellH(areaH)                 = (areaH - 72 - 30) / 6                        (:145-147)   // 竖屏
monthMetrics(w, areaH, lunar)     = (cellW=w/7, cellH=monthCellH(areaH),
                                     dayFont=20, lunarFont=11,
                                     lunarAlpha=lunar?1:0, dividerAlpha=1)       (:149-156)
weekMetrics(w, lunar)             = (cellW=w/7, cellH=68, dayFont=20, lunarFont=11,
                                     lunarAlpha=lunar?1:0, dividerAlpha=0)       (:158-165)
yearCardSize(size)                = ((w-16*2-6*2)/3, (h-62-8-6*3)/4)             (:167-170)
miniDayFont(cellW, cellH)         = clamp(min(cellW*0.95, cellH*0.5), 9, 14)     (:176-178)
yearCardRect(month, size)         = x = 16 + col*(cardW+6); y = 62+8 + row*(cardH+6)
                                    col = (m-1)%3, row = (m-1)/3                  (:182-190)
miniGridRect(month, size)         = (card.minX+6, card.minY+6+17, cardW-12, cardH-12-17)  (:195-201)
miniTitleRect(month, size)        = (card.minX+6, card.minY+6, cardW-12, 17)      (:213-219)
miniTitleInCard                   = (6, 6)                                        (:222)
miniGridInCard                    = (6, 23)                                       (:223)
miniMetrics(size)                 = (cellW=gridW/7, cellH=gridH/6,
                                     dayFont=miniDayFont(...), lunarFont=6,
                                     lunarAlpha=0, dividerAlpha=0)                (:227-237)
fullMonthGridRect(size)           = (0, 72+30, w, h-102)                          (:239-244)
```

**（竖屏但非 morph 终点）`MonthPane` 自己的度量**（`CalendarPaneViews.swift:75-107`）：
```
dayFont(cellH)   = clamp(cellH * 0.34, 13, 20)
lunarFont(cellH) = clamp(cellH * 0.18, 9, 11)
```
402×874 竖屏实测：`areaH = max(320, 874-64) = 810` → `cellH = (810-102)/6 = 118` → `dayFont = 20`、`lunarFont = 11`，与 `CalendarLayout.monthMetrics` 一致（这正是 morph 收尾不跳的原因）；`contentH = 20+4+16 = 40` → `circleD = min(57.43-3, 54, 115) = 54`。

### 2.3 月历网格生成算法

```
weeks(inMonth:ws)  (CalendarLayout.swift:337-356)
  1. first = 当月 1 日；start = weekStart(of: first, ws)   // 回退 lead 天
  2. 固定生成 6 行：第 i 行 = start + 7i 天起连续 7 天
  3. 结果按 (year*100+month)*2 + (ws=="sunday"?1:0) 缓存（NSLock 保护）

displayedWeeks(inMonth:ws)  (:322-335)
  1. rows = weeks(...)   // 6 行
  2. 从尾部循环：若最后一行 7 天全不属于本月 → removeLast；至少保留 1 行
  // 这是横屏「只剩一行日期」bug 的修复（docs/adaptive-layout-plan.md:411-415）

weekStart(of:ws)  = date - weekdayIndex(date, ws) 天            (:269-272)
weekdayIndex      = ws=="sunday" ? weekday-1 : (weekday+5)%7      (DateUtil.swift:79-86)
weekRowIndex(of:in:ws) = (lead + day - 1) / 7, lead = weekdayIndex(当月1日)  (:364-368)
```

### 2.4 年历网格生成算法

4 行 × 3 列 = 12 张迷你月卡片（`CalendarGrids.swift:372-388`）。每张卡片是 `ZStack(alignment: .topLeading)`，内含：
- `MiniMonthLabel`（15pt，当月粗体 + primary 色）放在 `miniTitleInCard = (6,6)`，用 `frame(width:height:alignment:.leading)` + `offset`（**不能用 `.position`，会有 1/3pt 取整差**，`CalendarGrids.swift:405-413`）；
- `MonthCanvas(weeks: 固定 6 行, metrics: miniMetrics, showAdjacent: false)` 放在 `miniGridInCard = (6,23)`（:414-427）；
- 卡片外框用 `frame(width:height:alignment:.topLeading)`（:429-431）。

网格坐标 → 日期：`MiniMonthLabel` 的迷你日期字号按格宽推导 12–14pt，上限由 `cellH*0.5` 兜住（`CalendarLayout.swift:172-178`）。

### 2.5 横屏密度降级（唯一使用 `CalendarDensity.resolve` 的地方）

```
CalendarDensity.resolve(availableHeight, rows, wantsLunar)   (CalendarLayout.swift:29-42)
  perRow = round(availableHeight / rows)
  perRow >= 44 + 16 (=60)  →  .month(lunar: wantsLunar)
  perRow >= 44             →  .month(lunar: false)
  else                     →  .weekStrip
rows(monthWeeks:) = isWeekStrip ? 1 : monthWeeks              (:55-57)
```

横屏实测（`HomeView.swift:327-341`，iPhone 18 Pro 874×402）：
```
pagePad = 16, topPad = 8
bottomClearance = layout.bottomInset(20) + 44 = 64
paneH   = max(220, 402 - 8 - 64) = 330
headerH = 34 + 26 = 60
rows    = displayedWeeks(...).count  (5 或 6)
perRow  = 270 / rows  →  54 (5 行) 或 45 (6 行)
→ 恒为 .month(lunar: false)
cellH   = max(44, round(270 / rows)) = 54 或 45
```
**周条只在 `paneH - 60 < 44 × rows` 时触发**（402pt 高的横屏不满足；输入法弹起 / Duo 折半 / 更小高度才会）。
竖屏**不走这套判定**：`monthLayer` 直接传 `showsLunar ? .month(lunar:true) : .month(lunar:false)`（`HomeView.swift:493`），即**竖屏农历显隐只由语言决定，横屏才由高度决定**。

### 2.6 日期 → 格子坐标映射

- 绘制：`cx = (col + 0.5) * cellW`；内容垂直居中：`top = rowY + (cellH - contentH)/2`，`numY = top + dayFont/2`，`lunarY = top + dayFont + 4 + lunarLineH/2`（`CalendarGrids.swift:75-81`）。
- 命中测试（`MonthCanvas.dayAt(point:)`，:243-253）：`col = Int(x/cellW)`、`row = Int(y/cellH)`，越界或非本月（且 `showAdjacent == false`）返回 `nil`。`WeekRowCanvas` 只用 `col = Int(x/cellW)`（:295-300）。

---

## 3. morph 动画的技术细节

### 3.1 动画常量与曲线

| 用途 | 值 | 位置 |
|---|---|---|
| 年↔月 / 月↔周 morph | `Animation.timingCurve(0.2, 0.75, 0.3, 1.0, duration: 0.6)`（年↔月**别名**成同一条：`morphAnimation = morphSlideAnimation`） | `CalendarLayout.swift:113, 121-123` |
| 减弱动态效果 | `.linear(duration: 0.18)` | :117 |
| 调试慢放 | `SLOW_MORPH=1` 环境变量 **或** `-slow-morph` 启动参数 → 3.0s | :104-107 |
| 翻页 | `.snappy(duration: 0.3)` | `DragPagePager.swift:79, 94` |
| 横屏点日期 | `.snappy(duration: 0.25)` | `HomeView.swift:504` |
| 今天 | `.snappy(duration: 0.3)` | `HomeView.swift:280, 286` |
| toast | `.snappy(duration: 0.25)` + `.transition(.opacity + .move(edge:.bottom))` | `HomeView.swift:305-313, 62` |
| header 返回键显隐 | `.diaryStandard = .easeInOut(0.3)` | `HomeView.swift:138`、`Components.swift:8` |
| `diaryQuick` | `.easeOut(duration: 0.22)` | `Components.swift:7` |

> 关键历史结论（`docs/animation-and-accessibility.md:38-48`）：年↔月原用 `easeInOut`，**时间过 25% 只完成 13% 进度**；换成 `timingCurve(0.2,0.75,0.3,1.0)` 后同样 25% 时间完成 **65%**，两者节奏才一致。迁移到 Compose 时这条曲线必须原样保留（`CubicBezierEasing(0.2f, 0.75f, 0.3f, 1.0f)`）。

### 3.2 插值方式（没有 matchedGeometryEffect）

- 两个 morph 视图都声明 `: View, Animatable`，`animatableData` 是单个 `Double`（`MorphViews.swift:5-25, 97-117`）。
- 插值工具：`CL.lerp` 三个重载（CGFloat / Double / CGRect，`CalendarLayout.swift:65-79`）、`CL.clamp01`（:81）、`DayMetrics.lerp`（:92-99）。
- 参与插值的量：
  - **frame**：`grid = lerp(miniGridRect, fullMonthGridRect, t)`（`MorphViews.swift:32`），布局用 `frame(width:height:) + offset(x:y:)`（:76-77）。
  - **字号**：`DayMetrics.lerp` 对 `dayFont / lunarFont` 插值（`CalendarLayout.swift:92-99`），再被 `MonthCanvas` 的 `drawnMetrics` 乘上半速动态字体系数（`CalendarGrids.swift:185-191`）。
  - **opacity**：`lunarAlpha`、`dividerAlpha`、`yearOpacity`、`late`、`labelOpacity`、行 `alpha` 全是分段 `clamp01((t-a)/b)`。
  - **scale**：只有年历层 `scaleEffect(yearScale, anchor: anchorUnitPoint)`（`MorphViews.swift:45, 55`）。
  - **cornerRadius**：**没有**参与 morph（日历是方形网格）；圆角只出现在卡片/胶囊上。
  - **offset**：`MonthWeekMorphView` 的每行 y 与内容区 y 都是 `CL.lerp`（:142, 158-166）。
- 驱动方式：`withAnimation(anim)` 改 `@State zoom/expand`（`HomeView.swift:195-200, 215-220, 236-242, 263-268`），并用 `withTransaction(disablesAnimations)` 先把起点状态瞬间设好，再动画到终点——**避免起点也被动画**。
- `completion:` 回调里把 `ymMorph/mwMorphMonth` 置 nil，让真实图层接上（:197-200, 217-220, 239-242, 265-268）。

### 3.3 性能优化手段

| 手段 | 位置 | 说明 |
|---|---|---|
| morph 期间不构建底层 3 个 layer | `HomeView.swift:423-433` | 省掉每帧 42 个 Canvas |
| 禁用中的 pager 不构建邻页 | `DragPagePager.swift:22, 24, 44` | `showsNeighbours = !disabled \|\| offset != 0` |
| `Canvas` 手绘（不用 42 个子视图） | `CalendarGrids.swift:195, 280` | 一个月 = 1 个 Canvas |
| `GraphicsContext.ResolvedText` LRU 缓存 | `CalendarGrids.swift:28-34, 137-167` | 键 = (text, size, weight, color, isDark)，**字号量化到 0.5pt**；上限 **24000**；超限 `removeAll(keepingCapacity: true)`，并在 `-morph-log` 下记录 `textcache-evict-N` |
| 每页单独 `.clipped()` | `DragPagePager.swift:35`、`HomeView.swift:376, 457` | 防止页内容溢出到相邻页 |
| 缓存周数据（NSLock） | `CalendarLayout.swift:314-315, 339-354` | `weeksCache` |
| 缓存周键（按 weekStart） | `CalendarLayout.swift:287-307` | `weekKeysCache` |
| **未使用** drawingGroup | 全仓 grep 无命中 | 依赖 Canvas 本身已经离屏（`.clipped()` 会引入离屏） |
| **未使用** PhaseAnimator / matchedGeometryEffect / Namespace | 全仓无命中 | — |

### 3.4 `-slow-morph` 与 `-morph-log` 调试机制

- **`-slow-morph` / `SLOW_MORPH=1`**：把 `morphDuration` 从 0.6 改成 3.0（`CalendarLayout.swift:103-109`）；因为 `morphSlideAnimation` 在每次访问时读 `morphDuration`，所以两条 morph 同时被放慢。README 与 `docs/animation-and-accessibility.md:73` 都记录了它。
- **`-morph-log`**：`MorphProgressLog`（`Services/MorphProgressLog.swift`，**整文件包在 `#if DEBUG` 里**）在内存里累积 `(systemUptime - start, 文本)`，每 25 条或遇到「非纯数字的 marker」时 flush 到 `Documents/morph.log`（制表符分隔）。写入点：
  - `HomeView.morphLog(marker)` 打 `ym-open-start/end`、`mw-open-start/end`、`ym-back-start/end`、`mw-back-start/end`（`HomeView.swift:169-176` 及调用点）；
  - `YearMonthMorphView.animatableData.set` 打 `"ym <progress>"`、`MonthWeekMorphView` 打 `"mw <progress>"`（`MorphViews.swift:19-24, 111-116`）；
  - `HomeViewModel.reloadDayBlocks` 打 `"reload"`（`HomeViewModel.swift:35-39`）；
  - `DayDraw` 缓存淘汰打 `"textcache-evict-N"`（`CalendarGrids.swift:157-161`）。
  - 消费方式：`MorphPerfUITests`（`JustDiaryUITests/MorphPerfUITests.swift:15`）用 `launchArguments = ["-morph-log"]` 驱动两次 morph，再由脚本把 `morph.log` 取出算逐帧间隔（`docs/animation-and-accessibility.md:9-13, 59-71`）。
- 另有 `-dump-chrome` 把真实安全区/档位写进 `Documents/chrome.log`（`AdaptiveLayout.swift:204-219`）——迁移后对应 Android 的 `WindowInsets` 日志。

---

## 4. 动态字体与无障碍适配

### 4.1 设计字号 → Apple 文本样式映射（`DesignSystem.swift:143-156`）

| 设计字号 | 文本样式 | 封顶倍率（`ceiling`，:124-136） | AX5 实际值 |
|---|---|---|---|
| ≥34 | `.largeTitle` | **1.35** | 32 → 44.8 |
| 28…<34 | `.title1` | **1.40** | 28 → 39.2 |
| 22…<28 | `.title2` | **1.45** | 24 → 34.8；22 → 31.9 |
| 20…<22 | `.title3` | **1.45** | 20 → 29 |
| 17…<20 | `.body` | **1.50** | 18 → 27；17 → 25.5 |
| 16…<17 | `.callout` | **1.55** | 16 → 24.8 |
| 15…<16 | `.subheadline` | **1.60** | 15 → 24 |
| 13…<15 | `.footnote` | **1.65** | 13 → 21.5 |
| 12…<13 | `.caption1` | **1.70** | 12 → 20.4 |
| <12 | `.caption2` | **1.75**（default） | 11 → 19.3 |

算法：`multiplier(size, typeSize) = min(ceiling(style), max(1, UIFontMetrics(forTextStyle: style).scaledValue(for: size, compatibleWith: traits) / size))`；`typeSize == .large` 时直接返回 1（默认档位零变化，:190-196）。`DynamicTypeSize` → `UIContentSizeCategory` 全 12 档映射在 :161-177，trait 按 category 缓存。

> 封顶是**有意的偏离**：Apple 原始曲线在 AX5 把 body 从 17pt 推到约 53pt 且比标题涨得更快，会让 15pt 正文大于 22pt 标题，产生层级倒挂（:107-123、`docs/design-system.md:47-61`）。封顶值的选择标准是**整条梯级保持单调**。

### 4.2 日历 Canvas 的「半速缩放」

```swift
calendarMultiplier(typeSize) = 1 + (multiplier(for: 15, typeSize:) - 1) * 0.5   // DesignSystem.swift:217-219
```
应用点：
- `MonthCanvas.drawnMetrics` / `WeekRowCanvas.drawnMetrics`：`dayFont *= f; lunarFont *= f`（`CalendarGrids.swift:185-191, 270-276`）；
- `.diaryCalendarFont(_:weight:)` 修饰符（`DynamicType.swift:39-63`）用于 `WeekdayHeaderView`（`CalendarGrids.swift:15`）；
- 原因：Canvas 文字无法自动跟随动态字体，且格子尺寸由屏幕决定，全速放大会把日号挤出格子（`docs/design-system.md:63-66`）。

### 4.3 减弱动态效果（reduceMotion）

- `HomeView` 读 `@Environment(\.accessibilityReduceMotion)`（:10），选择 `CalendarLayout.reducedMorphAnimation`（`.linear(0.18)`）替代 0.6s 曲线（:161-167）。**状态照常切换，只把位移压缩成快速交叉淡入**（`docs/animation-and-accessibility.md:109-113`）。
- `FlowLightOverlay`（装饰性流光，代码存在但零实例化）在 reduceMotion 时 `TimelineView(paused: true)`（`Components.swift:643-651`）。

### 4.4 增强对比度（darkerOutline）

`diaryCard` 读 `@Environment(\.colorSchemeContrast)`（`Components.swift:120, 126`）：

| | 普通 | 增强对比度 |
|---|---|---|
| 外描边（onSurface） | `opacity(0.0)`，1pt | **`opacity(0.55)`，1pt** |
| 内描边（outlineVariant） | `opacity(0.45)`，**0.5pt** | **`opacity(0.9)`，1.5pt** |
| 背景 | `Color(.secondarySystemGroupedBackground)` | 同左 |
| 阴影 | `shadowColor().opacity(interactive?0.22:0.14)`，radius `interactive?12:8`，y `interactive?4:2` | 同左 |

（`:131-141`，`docs/animation-and-accessibility.md:115-118`）

### 4.5 其它无障碍

- `MonthCanvas` 是一个整体 a11y 元素：label = `date_month_title(年, 月)`，value = 选中日，支持 `.accessibilityAdjustableAction`（增/减 1 天）+ 默认动作（点选中日），**但没有给可调节动作命名**（`CalendarGrids.swift:228-240`，`docs/design-system.md:228-232` 记录为待补缺口）。`onAdjacentDaySelected` 在 `MonthPane` 里没有传，所以竖屏这条可调节动作实际不可用。
- 年历每个迷你月显式暴露为 button，id `year.month.<N>`（`CalendarGrids.swift:432-438`），这是 UI 测试定位月份的唯一稳定手段。
- 固定高度一律用 `minHeight`（header 52、`PageHeader` 52、统一命中区 44）（`HomeView.swift:127, 148`、`Components.swift:171`、`DesignSystem.swift:45`）。
- 单行文本抗截断：`lineLimit(1) + minimumScaleFactor(0.6…0.9) + allowsTightening`。
- `TabBarClearance(base: 120)` 随字号放大：`base * multiplier(for: 15, ...)`（`Components.swift:582-592`）。

---

## 5. 设计令牌全量清单（`DesignSystem.swift` + `Theme.swift` + Assets）

### 5.1 Radius（`DesignSystem.swift:13-34`）

| 令牌 | 值 | 用途 |
|---|---|---|
| `Radius.card` | **20** | 内容卡片、列表行、hero 面板 |
| `Radius.panel` | **26** | 悬浮控件面：编辑器格式栏、sheet、popover |
| `Radius.field` | **14** | 输入框、卡片内次级面 |
| `Radius.image` | **14** | 卡片内联图片 |
| `Radius.badge` | **12** | 图标徽章、小色块 |
| `Radius.bar` | **4** | 图表柱、粗描边 |
| `Radius.hairline` | **1** | 2pt 高标记线（日历下划线） |

**concentric 嵌套圆角公式**：
```swift
static func concentric(outer: CGFloat, inset: CGFloat) -> CGFloat { max(0, outer - inset) }  // :31-33
```
即「内圆角 = 外圆角 − 内缩量」，不是内外同值（同值会产生视觉「尖角内衬」）。改造前项目里有 10 个互不相干的圆角（1/4/8/12/16/18/20/22/26/28）。

### 5.2 Spacing（`DesignSystem.swift:40-46`）

| 令牌 | 值 |
|---|---|
| `Spacing.card` | **12** |
| `Spacing.rowIcon` | **12** |
| `Spacing.hitTarget` | **44**（Apple 最小命中区） |
| `AdaptiveLayout.pagePadding`（等价令牌） | **16**（`AdaptiveLayout.swift:100-102`） |

> `Spacing.screen` / `cardGap` / `chip` 已在 2026-09-15 清理中删除（`docs/design-system.md:251`）。`RowDivider.textInset = 12 + 38 + 12 = 62`（`Components.swift:598`）。

### 5.3 TypeSize（`DesignSystem.swift:55-86`）

| 令牌 | 值 | 令牌 | 值 |
|---|---|---|---|
| `display` | **32** | `rowValue` | **15** |
| `pageTitle` | **24** | `sectionTitle` | **13** |
| `sheetTitle` | **17** | `chip` | **14** |
| `cardTitle` | **20** | `badge` | **12** |
| `headerTitle` | **18** | `meta` | **13** |
| `statValue` | **18** | `caption` | **12** |
| `rowTitle` | **15** | | |
| `rowSub` | **13** | | |

编辑器语义梯级（不落库、只影响绘制）：h1 **28** / h2 **22** / p **17** / quote **15**（`docs/design-system.md:87-95`）。

### 5.4 Animation 令牌（`Components.swift:6-9`）

| 令牌 | 值 |
|---|---|
| `Animation.diaryQuick` | `.easeOut(duration: 0.22)` |
| `Animation.diaryStandard` | `.easeInOut(duration: 0.3)` |

### 5.5 颜色令牌

**品牌与派生（`Theme.swift`）**

| 令牌 | 值 | 位置 |
|---|---|---|
| `seed`（primary） | `#2563EB` | Theme.swift:4 |
| `onPrimary` | light `#FFFFFF` / dark `#191C20` | :8-10 |
| `glowColor` | `seed @ 15%` | :14 |
| `flowLightColor` | `seed @ 40%` | :16 |
| `flowMaskColor` | `seed @ 5%` | :18 |
| `primary` | 即 `seed` | :6 |

**Asset 目录（light / dark，`Resources/Assets.xcassets`）**

| 令牌 | light | dark |
|---|---|---|
| `bg` | `#FAF9FD` | `#101318` |
| `onSurface` | `#191C20` | `#E1E2E8` |
| `onSurfaceVariant` | `#44474F` | `#C5C6D0` |
| `outlineVariant` | `#C5C6D0` | `#44474F` |
| `primaryContainer` | `#DBEAFE` | `#1E3A8A` |
| `glassDim` | `#FFFFFF @ 45.1%` | `#262B36 @ 50.2%` |
| `glassBorder` | `#FFFFFF @ 54.9%` | `#FFFFFF @ 20.0%` |
| `shadowColor` | `#000000 @ 7.8%` | `#000000 @ 14.9%` |
| `flowLight` | `#FFFFFF @ 80.0%` | `#FFFFFF @ 34.9%` |
| `blobA` | `#93C5FD @ 50.2%` | `#1E40AF @ 50.2%` |
| `blobB` | `#BFDBFE @ 60.0%` | `#1E3A8A @ 54.9%` |
| `blobC` | `#E0E7FF @ 43.1%` | `#312E81 @ 43.1%` |
| `quoteBg` | `#E4EAF9` | `#2B3344` |

**内容卡背景**：`Color(.secondarySystemGroupedBackground)`（系统分组背景，`Components.swift:129`）——Compose 侧对应 `MaterialTheme.colorScheme.surfaceContainer`/`surface` 需按主题映射。

---

## 6. Liquid Glass 控件用法清单（含视觉参数）

### 6.1 使用边界（设计原则）

- **内容区不用 Liquid Glass**（WWDC26 session 8120）：内容卡片一律走 `diaryCard`，因为「玻璃下面没有可折射的内容」（`Components.swift:105-116`、`docs/design-system.md:176-180`）。
- 玻璃只属于**控件层**：浮在内容之上的 chip / 图标按钮 / 搜索框 / 主行动按钮 / toast / 键盘上的格式栏。
- **不在玻璃上叠玻璃**：读日记内的搜索栏「上一个/下一个」已从 `GlassIconButton` 改成普通按钮（`docs/design-system.md:198-201`）。

### 6.2 全仓 `.glassEffect` / `.buttonStyle(.glass)` / `.glassProminent` 出现位置

| 文件:行 | 形式 | 形状 / 参数 |
|---|---|---|
| `Views/Home/HomeView.swift:58` | `.glassEffect(.regular, in: Capsule())` | 未来日期 toast；`Capsule().fill(.clear)` + `shadow(shadowColor, radius 12, y 4)`，`padding(h16,v10)`，底部 24（:45-63） |
| `Views/Home/HomeView.swift:131` | `.glassEffect(.regular.interactive(true), in: Capsule())` | header 返回/年月胶囊；`padding(h14)`，`frame(minHeight: 44)`，`minHeight: 52`（:127-148） |
| `Views/Components/Components.swift:218` | `.glassEffect(.regular.tint(Theme.primary()).interactive(true), in: Capsule())` | `GlassPrimaryButton`；底下**有意保留** `Capsule().fill(Theme.primary())` 实底 + `shadow(glowColor, radius 10 / 8, y 3 / 2)`；文字白色，`padding(h20/v12)` 或紧凑 `h14/v7`（:200-224） |
| `Views/Components/Components.swift:266` | `.glassEffect(.regular.interactive(true), in: Capsule())` | `GlassSecondaryButton`；底下 `Capsule().fill(Theme.glassDim())`（半透明白 45%）+ `padding(h20/v12)`（:257-267） |
| `Views/Components/Components.swift:289` | `.buttonStyle(.glass)` + `.buttonBorderShape(.circle)` | `GlassIconButton`（size 32，glyph 14/16pt，`frame(minWidth/minHeight: 44)`，`contentShape(Circle())`） |
| `Views/Components/Components.swift:314` | `.buttonStyle(.glass)` + `.buttonBorderShape(.circle)` + `.symbolEffect(.bounce)` | `PressableGlassIcon`（size 40，glyph 16pt，hierarchical 渲染，`active` 时 primary 色 + isSelected trait） |
| `Views/Components/Components.swift:362` | `.buttonStyle(.glass)` + `.buttonBorderShape(.capsule)` | `GlassChip`（chip 14pt，`padding(h12/v7)`） |
| `Views/Components/Components.swift:386` | `.buttonStyle(.glass)` + `.buttonBorderShape(.capsule)` | `InfoCapsule`（chip 14pt medium，`padding(h12/v7)`） |
| `Views/Components/Components.swift:443` | `.buttonStyle(.glass)` + `.buttonBorderShape(.capsule)` | `GlassActionChip`（含 11pt 图标，`spacing 4`，`padding(h10/v6)`） |
| `Views/Components/Components.swift:571` | `.glassEffect(.regular.interactive(true), in: Capsule())` | `GlassSearchField`；`Capsule().fill(.clear)`，`padding(h14)`，`frame(minHeight: 46)` |
| `Views/Diary/RichTextView.swift:328` | `.glassEffect(.regular.interactive(true), in: RoundedRectangle(cornerRadius: Radius.panel=26, style: .continuous))` | 编辑器格式栏；`fill(.clear)` + `shadow(shadowColor, radius 14, y 4)`；外 padding 横 12 竖 8（竖排时 6 / 2） |
| `Views/Diary/DiaryPageView.swift:56` | `.buttonStyle(.glassProminent)` | 分享 sheet 里的 `ShareLink`；`padding(24)` + `.presentationDetents([.height(170)])` |
| `Views/Settings/SettingsView.swift:39` | `.buttonStyle(.glassProminent)` | 导出结果的分享 `ShareLink`（同样 `padding(24)`、`height(170)`） |

> `.buttonStyle(.glass)` / `.glassProminent` 这类**系统按钮样式**由 iOS 27 提供玻璃底 + 高光 + 按压反馈；源码里没有自定义模糊半径/描边数值——**它们不是可参数化的**，迁移到 Material 3 时必须用自定义实现补齐视觉（见 §9.4）。

### 6.3 `diaryCard(cornerRadius:interactive:)` —— 内容卡片具体实现

```swift
// Components.swift:114-144
func diaryCard(cornerRadius: CGFloat = Radius.card /* 20 */, interactive: Bool = false) -> some View
→ RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
    .fill(Color(.secondarySystemGroupedBackground))
    .overlay {
        RoundedRectangle(cornerRadius:, style: .continuous)
            .stroke(Theme.onSurface().opacity(increased ? 0.55 : 0.0), lineWidth: 1)      // 外描边，默认不可见
            .overlay {
                RoundedRectangle(cornerRadius:, style: .continuous)
                    .stroke(Theme.outlineVariant().opacity(increased ? 0.9 : 0.45),
                            lineWidth: increased ? 1.5 : 0.5)                          // 内描边
            }
    }
    .shadow(color: Theme.shadowColor().opacity(interactive ? 0.22 : 0.14),
            radius: interactive ? 12 : 8,
            y: interactive ? 4 : 2)
```
`RoundedRectangle(style: .continuous)` = **Apple 连续曲率（squircle）**，不是圆弧角——Compose 的 `RoundedCornerShape` 是二次/圆弧角，视觉上会有差异（§9.3 第 16 条）。

首页用到的调用点：`DayContentView.swift:105`（`DiaryBlockCard`，`padding(Spacing.card=12)`，`VStack(spacing: 10)`）。

---

## 7. 共享组件清单（`Components.swift`）

| 组件 | 行号 | 职责 | 参数 | 视觉规格 |
|---|---|---|---|---|
| `Animation.diaryQuick/diaryStandard` | 6-9 | 全局动画令牌 | — | easeOut 0.22 / easeInOut 0.3 |
| `Screen.size/height/width/safeAreaBottom` | 13-56 | 从 key window / scene 读尺寸与底部安全区（**不要用于版面判定**，见 `AdaptiveLayout`） | — | 兜底 `CGSize(390, 844)` |
| `BlobBackground(dense:)` | 60-87 | 三团模糊圆作为页面背景 | `dense: Bool = false` | 圆 300/250/210 或 280/240/200，`blur(radius: 70/65/60 或 60/60/55)`，颜色 blobA/B/C |
| `diaryBackground()` | 89-103 | 把 `BlobBackground` 铺到 `TabView` 的每个 tab 上 | — | `ignoresSafeArea()` |
| `diaryCard(cornerRadius:interactive:)` | 105-144 | **内容卡片**唯一入口 | cornerRadius=20, interactive=false | 见 §6.3 |
| `PageHeader(title:trailing:)` | 148-173 | 页头（pageTitle 24pt medium + 右侧自定义） | generic `Trailing` | `frame(minHeight: 52)`，`spacing 12`，`minimumScaleFactor(0.7)` |
| `GlassPrimaryButton(title:icon:fullWidth:compact:enabled:action:)` | 177-227 | **主行动**（品牌色实底 + 玻璃高光） | 5 个 | Capsule + primary fill + glassEffect(tint:primary,interactive) + shadow(glow, 10/8, y3/2)；禁用时 primaryContainer@0.6、文字白 55% |
| `GlassSecondaryButton(title:icon:fullWidth:action:)` | 229-269 | 次级行动 | 4 个 | Capsule + glassDim fill + glassEffect(regular,interactive) |
| `GlassIconButton(systemName:size:tint:accessibilityLabel:action:)` | 271-293 | 圆形玻璃图标按钮（sheet 关闭等） | size=32 | `.buttonStyle(.glass)` + circle，glyph 14（size>36 → 16），命中区 44 |
| `PressableGlassIcon(systemName:size:active:accessibilityLabel:action:)` | 295-320 | 圆形玻璃图标按钮（**编辑器工具栏**，带选中态与弹跳） | size=40 | 同上 + `symbolEffect(.bounce, value: active)`；active → primary 色 + `.isSelected`；hierarchical 渲染 |
| `GlassSheetHeader(title:onClose:)` | 324-339 | sheet 标题行 + 关闭按钮 | — | sheetTitle 17pt medium + `GlassIconButton("xmark")` |
| `GlassChip(label:active:action:)` | 343-366 | 可选中玻璃胶囊（筛选） | active | chip 14pt，`h12/v7`，active → primary 色 + isSelected |
| `InfoCapsule(text:action:)` | 368-389 | 玻璃信息胶囊（首页「今天/相对日期」） | 可选 action | chip 14pt medium，`h12/v7`，`minimumScaleFactor(0.8)` |
| `GlassCountBadge(text:icon:)` | 393-415 | **不是玻璃**：primaryContainer 实心胶囊计数徽章 | icon 可选 | badge 12pt medium（图标 10pt），`h10/v5`，`spacing 5`，`foregroundStyle(primary)` |
| `GlassActionChip(label:systemImage:active:action:)` | 417-447 | 带图标的玻璃动作胶囊（时间范围等） | 4 个 | chip 14pt + 11pt 图标，`spacing 4`，`h10/v6` |
| `GlassEmptyState(systemImage:text:actionTitle:action:)` | 451-473 | 空态（图标 26pt + 文案 + 可选紧凑主按钮） | 4 个 | `VStack(spacing:12)`，图标 onSurfaceVariant@0.5，`padding(.top, 56)` |
| `AppAlertItem` + `appAlert(item:)` | 477-512 | 统一 alert 模型与呈现 | title/message/cancelLabel/secondaryLabel/primaryAction；`confirm` / `info` 两个工厂 | 走系统 item-based `alert`，按钮顺序与玻璃呈现由系统决定 |
| `GlassSearchField(text:placeholder:focus:onSubmit:trailing:)` | 524-574 | 玻璃搜索框 | generic `Content` | 见 §6.2；`minHeight 46`，`h14`，`magnifyingglass` + `xmark.circle.fill` 清除 |
| `TabBarClearance(base:)` | 582-592 | 底部为悬浮 tab bar 预留（**随字号放大**） | base=120 | `base * multiplier(for: 15, ...)` |
| `RowDivider(horizontalPadding:)` | 594-607 | 设置/列表分隔线 | — | `Divider().overlay(outlineVariant@0.5)`；`textInset = 12+38+12 = 62` |
| `GlassIconBadge(systemName:size:cornerRadius:)` | 609-638 | 圆角方形图标徽章（**不是玻璃**） | size=38, radius=12 | primaryContainer fill + glassBorder 1pt 描边；**边长随字号放大但保持正方形**：`max(size, scaled(16)+12)` |
| `FlowLightOverlay` | 642-682 | 装饰性流光（**零实例化**，保留为设计组件） | — | `TimelineView(.animation(minimumInterval: 1/60))`，4s 周期，两条 3pt 渐变线，`reduceMotion` 暂停，10s 后暂停 |
| `applyFocus(_:)` | 686-694 | 可选 `FocusState` 绑定的条件修饰符 | — | fileprivate |

---

## 8. 自适应版面规则（仅手机竖屏 / 横屏）

### 8.1 唯一判据：`AdaptiveLayout`（`AdaptiveLayout.swift:19-123`）

```
size     = 场景 screen.bounds（不是 GeometryProxy.size，因为后者已扣掉安全区）
safeArea = 四边分别读取（横屏 leading/trailing 常不相等）

tier:                       width >= 1000 → .wide；>= 700 → .medium；else .compact      (:30-34)
contentInset/trailingInset  = max(0, safeArea.leading/trailing)                          (:39-40)
bottomInset                 = max(0, safeArea.bottom)                                    (:46)
contentWidth                = max(0, width - contentInset - trailingInset)                (:54)

splitsMasterDetail = contentWidth >= 700 && width > height                               (:61-63)   ← 首页两栏
isPortrait         = height >= width                                                     (:66)
splitsDashboard    = contentWidth >= 900                                                 (:68)   ← 足迹（本次跳过细节）
splitsSearch       = contentWidth >= 800                                                 (:70)   ← 搜索（本次跳过细节）
masterWidth        = min(440, max(280, round(contentWidth * 0.46)))                       (:76-78)
cardColumns        = width >= 1000 ? 3 : width >= 680 ? 2 : 1                             (:81-85)
contentColumn(max) = min(max, max(240, width - 80))                                       (:88-90)

pagePadding = 16（leading == trailing == 16，**绝不再加 contentInset**）                   (:100-102)
calendarRows = max(1, min(6, Int(availableCalendarHeight / 44)))                          (:108-110)
availableCalendarHeight = max(80, height - bottomInset - 20)                              (:113-115)
defaultValue = size (390, 844), safeArea .zero                                            (:119-122)
```

**手机实测值**

| | 竖屏 402×874 | 横屏 874×402 |
|---|---|---|
| `size`（screen.bounds） | 402×874 | 874×402 |
| `safeArea` | T62 L0 B34 R0 | T0 **L62** B20 R62 |
| `contentWidth` | 402 | **750** |
| `tier` | compact | medium |
| `splitsMasterDetail` | **false**（402<700） | **true**（750≥700 且宽>高） |
| `masterWidth` | 280（未被使用） | **345**（= round(750×0.46)） |
| `pagePadding` | 16 | **16（不是 16+62）** |
| 内容左边界 | 0+16 = 16 | 62+16 = **78** |

### 8.2 横屏左右分栏的比例与安全区处理要点

- 分隔：`pagePad = 16`、`topPad = 8`、`gutter = 16`、0.5pt 竖分隔线（`outlineVariant@0.4`）（`HomeView.swift:327-346, 378-381`）。
- 左栏宽：
  ```
  calendarW = min(layout.masterWidth /*345*/, max(260, size.width - 32 - 260))
            = min(345, max(260, 458)) = 345
  dayW      = max(240, size.width - 32 - 345 - 16 - 0.5) = 356.5   // iPhone 18 Pro 横屏
  ```
  （`HomeView.swift:343-347`）
- 右栏 `DayPane` 内部再限宽：`maxColumnWidth = 560`（:389），标题行高 **44**（放「今天」胶囊），`DayContentView.bottomPadding = 24`（`CalendarPaneViews.swift:126-147`）。
- **安全区只避让一次**：`GeometryProxy` 的原点已经在安全区内（横屏 x=62），`HomeView` 与 `MonthPane` **都不能**再减 `safeArea.leading`；第一版重复避让把日历推到 x≈222，左半屏白掉（`HomeView.swift:323-326`、`CalendarPaneViews.swift:13-15`、`AdaptiveLayout.swift:92-99`、`docs/adaptive-layout-plan.md:206-209, 420-425`）。
- **底部给系统浮条让位**：`bottomClearance = layout.bottomInset + 44`（横屏实测浮条 `y 338–402`、高 64），两栏高度 `paneH = max(220, size.height - topPad - bottomClearance)`（`HomeView.swift:329-332`、`docs/adaptive-layout-plan.md:190-215`）。
- 两栏都是**固定宽度**（`.frame(width:)` + `.clipped()`），点日期不会带动布局（`docs/adaptive-layout-plan.md:404-406`，由 `LandscapeLayoutUITests.testTappingDayKeepsPaneWidthsStable` 守住）。
- `AdaptiveLayout.resolve`（:179-202）：用 `UIWindowScene.screen.bounds` 当界面尺寸，把「内容尺寸→界面尺寸」的差值补进对应边的 inset（`insets.trailing = max(trailing, dw - leading)`）——这是为了不依赖 `UIScreen.main`（Duo / iPad 多窗口）。

### 8.3 「本次跳过」的部分

- iPad / Mac 三栏版面（`docs/adaptive-layout-plan.md` §3、`wide-*.png`）、`NavigationSplitView` 与侧边栏形态、Duo 折痕 `reservedRegion` 预留、`cardColumns` 2/3 列——**本次跳过**。
- 横屏 62pt 左侧避让**只对 iOS 成立**；Android 没有「浮条变左侧竖排胶囊」这件事（见 §9.2）。

---

## 9. Android / Compose 落地映射表

判定口径：**自然迁移** = Compose 原生直接支持；**需第三方库** = 原生缺失或能力不足，给候选库；**需重新设计** = Compose 无对应能力，需替代方案。

### 9.1 总表

| # | 项目 | iOS 位置 | 判定 | Compose 方案 / 候选库 | 风险 |
|---|---|---|---|---|---|
| 1 | 日历网格 Canvas 绘制 | `CalendarGrids.swift:195, 280` | **自然迁移** | `Canvas`/`Modifier.drawBehind` + `rememberTextMeasurer()` + `drawText` | 文字排版 API 不如 `GraphicsContext.ResolvedText` 顺手 |
| 2 | 命中测试 `dayAt(point:)` | `CalendarGrids.swift:243-253` | **自然迁移** | `Modifier.pointerInput { detectTapGestures { offset -> … } }` | 注意 px/dp 换算（见 §9.3） |
| 3 | ResolvedText LRU（24000，半磅量化） | `CalendarGrids.swift:28-34, 137-167` | **需重新设计** | `TextMeasurer(cacheSize = N)` 默认缓存很小 → 自建 `LruCache<TextKey, TextLayoutResult>` | 直接用默认缓存会在 morph 中反复重排 |
| 4 | morph（单个 progress 手写插值） | `MorphViews.swift:5-25, 97-117` | **自然迁移** | `Animatable`/`animateFloatAsState` + `CubicBezierEasing(0.2f,0.75f,0.3f,1f)` + `tween(600)` | 无风险；**不要**用 `SharedTransitionLayout`（见 §9.4） |
| 5 | `withTransaction(disablesAnimations)` 设起点 | `HomeView.swift:186-193` | **自然迁移** | 用一个不可动画的 `mutableStateOf` 起点 + 单独 `Animatable` 目标 | 需保证 setState 不在同一帧被动画捕获 |
| 6 | 分段 `clamp01` opacity/alpha | `MorphViews.swift:39-46, 128-143` | **自然迁移** | 纯 Kotlin 数学 | — |
| 7 | `scaleEffect(anchor: UnitPoint)` 年历缩放 | `MorphViews.swift:43-56` | **自然迁移** | `Modifier.graphicsLayer { scaleX/scaleY; transformOrigin = TransformOrigin(px, py) }` | `TransformOrigin` 用分数坐标，可直接对应 |
| 8 | 月/周 `offset(y:)` 逐行位移 | `MorphViews.swift:137-179` | **自然迁移** | `Modifier.offset` 或直接放在 `Layout`/`drawBehind` 里 | — |
| 9 | `DragPagePager` + 阈值/橡皮筋 | `DragPagePager.swift:58-104` | **自然迁移** | `VerticalPager`/`HorizontalPager` + `rememberPagerState` + `PagerDefaults.flingBehavior(snapPositionalThreshold=…)` + `beyondViewportPageCount` | Pager 的判定模型（速度+位置阈值）与 90/60pt 不同，若要求 1:1 手感需自写 `draggable` |
| 10 | 「不构建邻页」的性能约束 | `DragPagePager.swift:22-44` | **自然迁移** | `beyondViewportPageCount = 0` | 默认 0，但 `beyondBoundsPageCount` 也会预载 |
| 11 | 内容卡片 `diaryCard` | `Components.swift:114-144` | **自然迁移（圆角除外）** | `Surface(shape = RoundedCornerShape(20.dp), color = surfaceContainer, border = BorderStroke(0.5.dp, …))` + `Modifier.shadow` | 圆角曲率不同（§9.2 #16）；阴影无 y 偏移与颜色 → 需 `drawBehind` + `Paint` |
| 12 | `.glassEffect` / `.buttonStyle(.glass)` / `.glassProminent`（14 处，§6.2） | 全仓 | **需第三方库 + 需重新设计** | 背景模糊用 **haze**（`dev.chrisbanes.haze:haze`）或 `rememberGraphicsLayer()` 自制；高光/描边/按压反馈要自画 | API<31 无真实模糊（§9.5） |
| 13 | `BlobBackground` 的 `blur(60–70)` 圆 | `Components.swift:60-87` | **需重新设计** | API≥31 可用 `Modifier.blur`；<31 退回 `Brush.radialGradient` 叠加，或预渲染 PNG | 视觉退化，需设计确认 |
| 14 | 动态字体（逐样式曲线 + 封顶） | `DesignSystem.swift:107-219` | **需重新设计** | 读 `LocalDensity.fontScale`，自建「字号→倍率」查表 + 封顶；用 `CompositionLocalProvider(LocalDensity provides Density(density, fontScale = capped))` 局部夹紧 | Android 只有单一 fontScale，没有 AX1–AX5 与逐样式曲线；需重新定标（§9.6） |
| 15 | 日历半速缩放 `1+(m(15)-1)/2` | `DesignSystem.swift:217-219` | **自然迁移** | 公式原样照搬，作用于 Canvas 字号 | — |
| 16 | `.continuous` 连续曲率圆角 | `Components.swift:128` 等 | **需重新设计** | Compose 无 squircle；近似：`RoundedCornerShape` + 更小半径，或自绘 `Path` 三次贝塞尔 | 观感差异在大圆角（26dp 面板）最明显 |
| 17 | `minimumScaleFactor` / `allowsTightening` | `HomeView.swift:121, 558` 等 | **需第三方库/新版 API** | Compose 1.8 `BasicText(autoSize = TextAutoSize.StepBased(...))`；旧版本用 `onTextLayout` 循环缩字号 | `autoSize` 较新，需确认 BOM 版本 |
| 18 | `TabView` + `.sidebarAdaptable` | `RootView.swift:31-59` | **需重新设计** | 手机两向都用 `NavigationBar`（镜像 iOS 行为）；若要按 Material 规范做横屏则 `NavigationRail`/`NavigationSuiteScaffold` | 横屏用 NavigationBar 会占底部 80dp，需重算日历高度 |
| 19 | `WindowInsets` 与安全区 | `AdaptiveLayout.swift:157-202` | **自然迁移** | `WindowInsets.systemBars` / `displayCutout` / `safeDrawing`；`Modifier.windowInsetsPadding` | **横屏 62pt 那套可整体删除** |
| 20 | 分栏判定 700/900/800、masterWidth 46% | `AdaptiveLayout.swift:61-90` | **自然迁移** | `BoxWithConstraints` + `WindowSizeClass`/`currentWindowAdaptiveInfo()` | Android 横屏手机宽度 ~800–950dp，阈值仍成立 |
| 21 | 编辑器全屏呈现 | `RootView.swift:87-103` | **自然迁移** | 独立 NavHost destination 或 `ModalBottomSheet`/`Dialog(usePlatformDefaultWidth=false)` | `fullScreenCover` 的环境注入问题（`diaryDynamicTypeSize`）在 Compose 里由 `CompositionLocal` 天然继承，反而更简单 |
| 22 | 可调节无障碍动作（增/减 1 天） | `CalendarGrids.swift:233-240` | **自然迁移** | `Modifier.semantics { customActions = listOf(CustomAccessibilityAction("后一天"){…}) }` + `progressBarRangeInfo`/`setProgress` | Compose 需显式命名（正好补上 iOS 的缺口） |
| 23 | 触觉反馈 `Haptics.tap()` / `.sensoryFeedback(.warning)` | `HomeView.swift:63, 112` 等 | **需重新设计** | `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`；更细用 `LocalView.current.performHapticFeedback(HapticFeedbackConstants.REJECT/CONFIRM)` | `HapticFeedbackType` 只有 LongPress/TextHandleMove 两种 |
| 24 | 日期 / 周起始算法 | `DateUtil.swift:69-86`、`CalendarLayout.swift:337-368` | **自然迁移** | `java.time` + `WeekFields.of(DayOfWeek.MONDAY or SUNDAY, 1)` + `LocalDate.with(TemporalAdjusters.previousOrSame(…))` | 需 `coreLibraryDesugaring`（minSdk<26） |
| 25 | SF Symbols 图标 | 全仓 | **需第三方库/资源迁移** | Material Symbols 或自带 vector drawable；`symbolEffect(.bounce)` 无对应 | 图标语义需逐个重选 |
| 26 | `MorphProgressLog` 调试 | `Services/MorphProgressLog.swift` | **自然迁移** | `Log.d` / 写 `filesDir/morph.log`；`-slow-morph` 用 `BuildConfig.DEBUG` + Intent extra | — |
| 27 | `RowDivider.textInset = 62`、`TabBarClearance` 随字号 | `Components.swift:594-607, 582-592` | **自然迁移** | 直接按 sp 计算高度 | — |

### 9.2 三个必须先决定的架构问题

1. **横屏要不要沿用 iOS 的「底部 NavigationBar」**。iOS 横屏实测浮条在底部居中（`docs/adaptive-layout-plan.md:99-103`），Android 的 Material 规范在横屏用 `NavigationRail`。若照抄 iOS，日历高度要按「底部 80dp」重算；若改 Rail，则 §8.2 的左右分栏比例要全部重算（横向空间少 80dp）。**建议：手机横屏用 `NavigationRail` 才是 Android 的正确形态，但那样 `paneH` 与 `MasterWidth` 都需要重算**——这是本报告唯一要求产品/设计决策的点。
2. **minSdk**。`Modifier.blur` 真正生效要 API 31；haze 的降级方案在 API<31 是半透明色块而非模糊。如果目标 minSdk ≥ 31，玻璃可高度还原；若 minSdk ≤ 30，需要接受降级或改用「不透明控件 + 轻描边 + 阴影」的替代设计。
3. **Compose BOM 版本**。`rememberGraphicsLayer`（1.7）、`TextMeasurer` 的 `cacheSize`、`TextAutoSize`（1.8）、`SharedTransitionLayout`（1.7）都挑版本。

### 9.3 特别分析：日历 Canvas → Compose Canvas 的可行性

**结论：完全可行，是本次迁移最稳的一块。**

- `MonthCanvas` 一个类画整个月（6 行 × 7 列 + 圆 + 文字 + 下划线 + 分隔线），Compose 用 `Canvas(Modifier) { drawRect/drawCircle/drawRoundRect/drawText }` 一对一翻译。
- 文字：`val measurer = rememberTextMeasurer(cacheSize = …)`；`measurer.measure(AnnotatedString("$day"), style = TextStyle(fontSize = dayFontSp, fontWeight = …))` → `drawText(layoutResult, topLeft = Offset(cx - w/2, numY - h/2))`（注意 Compose `drawText` 以左上角为锚，需要减半宽高；iOS 用的是 `anchor: .center`）。
- 缓存：iOS 把 `(text, size 半磅量化, weight, color, isDark)` 当键缓存 `ResolvedText`。Compose 对应缓存 `TextLayoutResult`；`TextMeasurer` 自带 LRU 但**默认容量很小**，morph 中字号每半磅一个新 key 会打爆它 → 必须 `cacheSize` 调大或自建带淘汰的缓存（对应 iOS 的 24000 上限与 `keepingCapacity`）。
- 命中测试：`detectTapGestures { pos -> col = (pos.x / cellWpx).toInt(); row = (pos.y / cellHpx).toInt() }`，`cellW/cellH` 统一用 **px** 计算（`with(density){ dp.toPx() }`），避免 dp/sp 混算。iOS 的 `SpatialTapGesture` 直接给局部坐标，Compose 同样给局部坐标，等价。
- 与 iOS 的差异点：Compose `Canvas` 里 `drawText` 每次都要 `TextLayoutResult`（测量），iOS 的 `context.resolve` 也测量，代价同阶；`Path` 描边用 `Stroke(width, cap, join)`；圆角下划线用 `drawRoundRect(cornerRadius = CornerRadius(1.dp.toPx()))`。
- 性能上，Compose 的 `Canvas` 也是单节点绘制，不会产生 42 个 composable；`Modifier.drawWithCache` 可进一步缓存 `Path` 对象。

### 9.4 特别分析：morph → Compose 动画 API 的对应与限制

| iOS 能力 | Compose 对应 | 结论 |
|---|---|---|
| `struct V: View, Animatable { var progress: Double }` | `animateFloatAsState(targetValue, animationSpec = tween(600, easing = CubicBezierEasing(0.2f,0.75f,0.3f,1f)))` 或 `Animatable(0f).animateTo(1f, spec)` | **逐项等价**。推荐 `Animatable`（可 `snapTo` 起点 + 可 `stop`），与 `withTransaction(disablesAnimations)` 的「瞬间设起点」一一对应 |
| 多值插值（rect + metrics + 4 个 alpha） | 全部由同一个 `progress` 纯函数推导（`lerp`），**不要**用 `updateTransition` 分多路——分路会各自插值、无法保证同帧一致 | 推荐单一 progress |
| `UnitPoint` 锚点缩放 | `graphicsLayer { scaleX/Y; transformOrigin = TransformOrigin(card.center.x / size.width, card.center.y / size.height) }` | 等价 |
| `.opacity(x)` | `Modifier.alpha(x)` 或 `graphicsLayer { alpha }` | 等价 |
| `withAnimation { } completion: { }` | `LaunchedEffect`/`Animatable.animateTo` 后置语句，或 `Animatable` 的挂起返回 | 等价（顺序更清晰） |
| 减弱动态效果 | 见 §9.7 | 等价 |
| **`SharedTransitionLayout` + `Modifier.sharedElement`** | **不建议使用** | 它是「两个不同 composable 之间的共享元素转场」，需要 `AnimatedVisibilityScope`，而且默认做的是「位置/尺寸插值 + 交叉淡入」；本项目的 morph 是**同一个 Canvas 连续重绘**（字号、农历行 alpha、行位移全部连续变化，年历层还带锚点缩放），用 `sharedElement` 反而做不出「网格连续变形」。`SharedTransitionLayout` 只能作为「年历页 ↔ 月历页」这种整体转场时的补充，核心仍需 progress 手绘 |
| `PhaseAnimator` / `KeyframeAnimator` | 可用但不需要：morph 是单段缓动，不是多阶段 | 不用 |

**关键限制**：Compose 的 `animateFloatAsState` 每帧会触发**该 composable 的重组**（不是只重绘）。如果 morph 视图里包含 12 个迷你月 Canvas，重组成本会明显高于 iOS 的 `Animatable`（iOS 那侧也是重建 body，但 SwiftUI 的 diff 粒度不同）。**优化建议**：把 `progress` 用 `graphicsLayer`/`drawBehind`/自定义 `Layout` 的 lambda 读取（`Modifier.drawBehind { val p = progressState.value }`，读取发生在**绘制阶段**而非组合阶段），或把整个 morph 放进一个 `Canvas` 里画，从而把每帧成本压到「重绘」而不是「重组」。这是 Compose 端最重要的实现细节。

### 9.5 特别分析：玻璃 / 模糊

| | iOS | Compose |
|---|---|---|
| API | `.glassEffect(.regular.interactive(true), in: Capsule())`（iOS 26/27 系统材质，自动折射背景 + 高光 + 交互缩放） | 无 |
| `Modifier.blur(radius)` | — | 使用 `RenderEffect.createBlurEffect`，**仅 API ≥ 31 生效；API ≤ 30 完全无效（no-op）**；且**模糊的是该 composable 自身绘制的内容**，不是它背后的内容 |
| 背景（backdrop）模糊 | — | 必须把「背景内容」录制进 `GraphicsLayer`，再对该 layer 应用 `renderEffect`：`val gl = rememberGraphicsLayer(); Modifier.drawWithContent { /* 背景 */ }; 前景 Modifier.drawWithContent { gl.record { this@drawWithContent.drawContent() }; gl.renderEffect = BlurEffect(r, r); drawLayer(gl) }`（Compose 1.7+ `rememberGraphicsLayer` / `BlurEffect`） |
| 现成库 | — | **haze（`dev.chrisbanes.haze:haze`）**：提供 `Modifier.hazeSource(state)` + `Modifier.hazeEffect(state, style = HazeMaterials.ultraThin())`，API≥31 走 `RenderEffect` 真模糊，API<31 退化为半透明 scrim + 噪点。**推荐作为首选**，理由是它正是上述 `GraphicsLayer` 录制方案的成熟封装，且支持圆角形状、tint、blurEnabled 开关 |
| 描边/高光 | 系统材质自带 | 自画：1dp（iOS 0.5–1pt）`glassBorder` 半透明白描边 + 顶部线性渐变高光 + `shadow` |
| 按压反馈 | 系统 `interactive(true)` 自动缩放/变亮 | 自画：`interactionSource` + `animateFloatAsState` 的 scale 0.97 / elevation 变化 |
| 版本/性能注意 | — | 真模糊是 GPU 昂贵操作；haze 官方建议**同屏少量**（首页只有 header 胶囊 + toast = 2 处，可接受）。避免给每个日期格子加玻璃 |

**视觉参数映射表（可直接落到 Compose）**

| iOS 元素 | 形状/圆角 | 填充 | 描边 | 阴影 |
|---|---|---|---|---|
| header 年月胶囊 | Capsule（`h14`, minHeight 44） | 无（纯玻璃） | 玻璃自带 | 无 |
| 「今天」`InfoCapsule` | Capsule（`h12/v7`） | 无 | 玻璃自带 | 无 |
| 未来 toast | Capsule（`h16/v10`） | 无 | 玻璃自带 | `shadowColor` r12 y4 |
| `GlassPrimaryButton` | Capsule（`h20/v12`） | **primary 实底**（有意） | 玻璃自带 + 高光 | `glowColor @15%` r10 y3（紧凑 r8 y2） |
| `GlassSecondaryButton` | Capsule（`h20/v12`） | **glassDim**（L 白45.1%/D #262B36 50.2%） | 玻璃自带 | 无 |
| `GlassSearchField` | Capsule（`h14`, minHeight 46） | 无 | 玻璃自带 | 无 |
| 编辑器格式栏 | **RoundedRect 26**（连续曲率） | 无 | 玻璃自带 | `shadowColor` r14 y4 |
| `GlassIconBadge`（非玻璃） | **RoundedRect 12** | `primaryContainer` | `glassBorder` 1pt | 无 |
| `diaryCard`（非玻璃） | **RoundedRect 20 连续曲率** | `secondarySystemGroupedBackground` | `outlineVariant@0.45` 0.5pt | `shadowColor@0.14` r8 y2（interactive: 0.22 / r12 / y4） |

### 9.6 特别分析：动态字体

- **Android 只有一个标量 `fontScale`**（`LocalDensity.current.fontScale`，系统设置里通常是 0.85–2.0 的少量档位），没有 iOS 的 12 档 content size category，也没有「每个文本样式一条曲线」。
- **可行做法**：
  1. 在 Application/Activity 层把系统 `fontScale` 读出来，用一个与 iOS 14 个 `TypeSize` 角色对应的**查表函数** `multiplierFor(role, fontScale)` 复刻 iOS 的「小字号涨得多、大字号涨得少」形状（可用 `DesignSystem.swift:119-120` 那张 AX5 表反推一条分段线性/幂函数曲线）。
  2. 每个角色封顶值照搬 `ceiling(for:)`：1.35 / 1.40 / 1.45 / 1.45 / 1.50 / 1.55 / 1.60 / 1.65 / 1.70 / 1.75。
  3. **全局夹紧**（最简单）：`CompositionLocalProvider(LocalDensity provides Density(density, fontScale = min(fontScale, 1.5f)))` —— 保持 `density` 不变，只夹 `fontScale`，dp 布局不受影响。缺点：所有角色同一个上限。
  4. **逐角色精确**（推荐）：在 `MaterialTheme.typography` 里为每个角色写入**已解析的绝对值**，并在该子树内把 `fontScale` 覆写为 1f，避免 sp 二次放大；即 `fontSize = resolvedSp.sp` 配合 `LocalDensity(fontScale = 1f)`。
  5. **Canvas 文字**：`TextMeasurer` 用 `Density(density, fontScale = 1f)` 构造，字号自己算 `designSize * calendarMultiplier`，与 iOS 的 `drawnMetrics` 完全对应（半速公式照搬）。
- **坑**：
  - 覆写 `LocalDensity` 会同时影响 `Em` 单位、`TextUnit` 推导与 `TextStyle.lineHeight`，要做局部最小覆写；
  - 不要用 `(px / density).sp` 这类换算——sp 会被 fontScale 再乘一次；
  - Android 系统最大 fontScale（2.0）比 iOS AX5（≈3.1×）小，所以「封顶」在 Android 上更容易触发，反而更容易保持布局不破。

### 9.7 特别分析：减弱动态效果 / 无障碍设置读取

| iOS | Android 读取方式 | 用法 |
|---|---|---|
| `@Environment(\.accessibilityReduceMotion)` | **`ValueAnimator.areAnimatorsEnabled()`**（API 26+，官方推荐：系统「动画时长缩放 = 关闭」时返回 false）；或读 `Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)`，`== 0f` 即关闭 | 为真时把 morph 换成 `tween(180, easing = LinearEasing)` / 直接 `snapTo(1f)` + 交叉淡入，与 `CalendarLayout.reducedMorphAnimation`（`.linear(0.18)`）对应 |
| 同上 | Compose 内部还有 `MotionDurationScale` / `LocalMotionDurationScale`（`scaleFactor`），`animate*AsState` 会据此缩放时长，缩放为 0 时直接跳变 | 可作为「不写额外代码也尊重系统设置」的兜底，但显式降级分支仍要自己写 |
| `@Environment(\.colorSchemeContrast)`（增强对比度） | `AccessibilityManager.isHighTextContrastEnabled()`（需在目标 API level 上确认可用性；Android 14 另有 `Settings.Global.HIGH_TEXT_CONTRAST_ENABLED`） | 为真时把 `diaryCard` 描边从 0.5dp/45% 改为 1.5dp/90% + 加一条 1dp 外描边（对应 `Components.swift:131-137`） |
| `FlowLightOverlay` 的 `TimelineView(paused: reduceMotion)` | 同一开关 | 关闭无限循环动画；Compose 里对应 `rememberInfiniteTransition` 的 `infiniteRepeatable` 需手动停 |
| 其它相关 | `Settings.Global.TRANSITION_ANIMATION_SCALE` / `WINDOW_ANIMATION_SCALE`（API 30 起部分只读语义有变化）、`AccessibilityManager.isTouchExplorationEnabled`、`LocalAccessibilityManager.current` | 读取 `Settings.Global` **不需要权限**（写入才需要 `WRITE_SECURE_SETTINGS`） |

---

## 10. 附：手机竖屏 / 横屏数值推演（可直接当 Compose 单元测试断言）

**竖屏 402×874（几何 402×874，安全区 T62 L0 B34 R0）**

```
h（日历区）= max(320, 874 - 64) = 810
cellW      = 402 / 7 = 57.43
cellH      = (810 - 72 - 30) / 6 = 118.0
dayFont    = clamp(118*0.34, 13, 20) = 20
lunarFont  = clamp(118*0.18, 9, 11) = 11
contentH   = 20 + 4 + (11+5) = 40
circleD    = min(57.43-3, 40+14, 118-3) = 54
weekdayFont= clamp(57.43*0.26, 11, 14) = 14
网格起点   = (0, 72+30) = (0, 102)
5 行月网格实际占用 = 590；6 行 = 708（剩余为 Spacer）
```

**横屏 874×402（几何 750×402，安全区 T0 L62 B20 R62）**

```
paneH      = max(220, 402 - 8 - (20+44)) = 330
headerH    = 34 + 26 = 60
perRow     = 270 / rows
rows 5 → perRow 54, cellH 54；rows 6 → perRow 45, cellH 45
density    = .month(lunar: false)（perRow ≥ 44 且 < 60）
calendarW  = 345
dayW       = 750 - 32 - 345 - 16 - 0.5 = 356.5
cellW      = 345 / 7 = 49.29
dayFont    = clamp(45*0.34=15.3 或 54*0.34=18.36, 13, 20) → 15.3 / 18.36
lunarAlpha = 0 → contentH = dayFont + 4
circleD    = min(49.29-3, dayFont+4+14, cellH-3) → 34.3（6 行）/ 36.4（5 行）
内容左边界 = 62 + 16 = 78（几何原点已在安全区内）
```

**边界阈值（建议写成测试）**
- 竖屏/横屏分叉：`contentWidth >= 700 && width > height`；横屏 750 ≥ 700 ✓，竖屏 402 ✗。
- 密度降级到周条：`paneH - 60 < 44 × rows`（402pt 高时不触发）。
- 翻页：预测位移 > 90pt 或（同向且）位移 > 60pt；拖动夹在 `±pageSize` 内；橡皮筋 `0.25*pageSize`。
- Canvas 缓存：24000 条上限。

---

### 附注：本次跳过的内容
iPad / Mac 三栏版面（`docs/adaptive-layout-plan.md` §3 的 `wide-*`、`NavigationSplitView` 侧边栏、`cardColumns` 2/3 列、Duo 折痕 `reservedRegion` 预留、足迹 900pt / 搜索 800pt 分栏）——**本次跳过**，仅保留其对 `AdaptiveLayout` 的接口影响。
