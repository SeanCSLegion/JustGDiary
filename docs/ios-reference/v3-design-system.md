# 一页时光 / JustADiary — iOS 设计系统 + 自适应版面规范（v3，Android / Material 3 重建基线）

> **来源**：iOS 仓库 `/Users/cov/JustADiary`，SwiftUI，iPhone-only（iOS 27 SDK）。
> **用途**：让另一位工程师用 Jetpack Compose + Material 3 / Material You 重建同一套
> 信息层级、几何与交互意图。
> **取值原则**：**代码优先于文档**。本文所有数值均从源码读出并标注 `file:line`
> （路径相对于 iOS 仓库根）。与 `docs/design-system.md` 等旧文档冲突处，以本文为准
> 并已加 ⚠️ 标注。
> **读取范围**（全部完整读过）：`Views/Components/{DesignSystem,DynamicType,AdaptiveLayout,Components}.swift`、
> `Services/{Theme,Haptics}.swift`、`Views/RootView.swift`、`App/JustDiaryApp.swift`、
> `Resources/Assets.xcassets/**/Contents.json`（全部 14 个 colorset）、
> `Views/Home/{HomeView,CalendarLayout,CalendarPaneViews,CalendarGrids,DayContentView,DragPagePager}.swift`、
> `Views/Diary/{DiaryPageView,RichTextView,MediaViews,ShareSheetView}.swift`、
> `Views/{Search/SearchView,Footprint/FootprintView,Settings/SettingsView}.swift`、
> `ViewModels/DiaryViewModel.swift`、`Services/{AppConfigService,ImageShareService}.swift`，
> 以及 `docs/{design-system,adaptive-layout-plan,animation-and-accessibility,editor-typography}.md`。

---

## 1. 设计令牌

### 1.1 颜色令牌（Asset Catalog 动态色）

所有颜色定义在 `JustDiary/Resources/Assets.xcassets/<name>.colorset/Contents.json`，
由 `Theme`（`Services/Theme.swift:30-44`）以 `Color("<name>")` 暴露。
**全部为 `srgb` 色彩空间**，dark 值通过 `"appearances": [{"appearance":"luminosity","value":"dark"}]` 提供
（唯一的例外是 `AccentColor`，只有单值、没有 dark 变体）。

| 令牌 | Light（sRGB） | Dark（sRGB） | 语义角色 | 主要使用点 |
|---|---|---|---|---|
| `bg` | `#FAF9FD` (`0xFA/0xF9/0xFD`) | `#101318` (`0x10/0x13/0x18`) | 页面底色 | `BlobBackground` 最底层 `Components.swift:82`；`ShareSheetView` 背景 `ShareSheetView.swift:48`；`Theme.bg()` `Theme.swift:30` |
| `blobA` | `#93C5FD` @ **50.2%** | `#1E40AF` @ **50.2%** | 装饰色球 A（大、左侧） | `BlobBackground` `Components.swift:84,91` |
| `blobB` | `#BFDBFE` @ **60.0%** | `#1E3A8A` @ **54.9%** | 装饰色球 B（中、右下） | `Components.swift:86,93` |
| `blobC` | `#E0E7FF` @ **43.1%** | `#312E81` @ **43.1%** | 装饰色球 C（小、右上） | `Components.swift:88,95` |
| `flowLight` | `#FFFFFF` @ **80.0%** | `#FFFFFF` @ **34.9%** | 卡片流光高光 | `FlowLightOverlay` 下沿流光 `Components.swift:686`（**当前零实例化**，见 `docs/design-system.md` §八"保留"） |
| `glassBorder` | `#FFFFFF` @ **54.9%** | `#FFFFFF` @ **20.0%** | 玻璃/徽章 1pt 描边 | `GlassIconBadge` 描边 `Components.swift:653` |
| `glassDim` | `#FFFFFF` @ **45.1%** | `#262B36` @ **50.2%** | 玻璃按钮半透明垫层 | `GlassSecondaryButton` 胶囊底 `Components.swift:281` |
| `onSurface` | `#191C20` | `#E1E2E8` | 主文字 / 主前景 | 标题、正文、图标主色：`Components.swift:177,266,301,333,355,378,402,461,484,487,657`、`HomeView.swift:49,125`、`CalendarPaneViews.swift:162`、`SearchView.swift:333` 等 |
| `onSurfaceVariant` | `#44474F` | `#C5C6D0` | 次级文字 / 次级前景 | 副标题、meta、尾值、图标：`Components.swift:266,301,484,487,570,583`、`SettingsView.swift:179,188,194`、`SearchView.swift:181,200,339,342` 等 |
| `outlineVariant` | `#C5C6D0` | `#44474F` | 分隔线 / 细描边 | `RowDivider` `Components.swift:627`、`DiaryCard` 描边 `Components.swift:151`、格式栏分隔 `RichTextView.swift:319,334`、分栏竖线 `HomeView.swift:379`、日历行分隔线 `CalendarGrids.swift:208`、`ShareSheetView.swift:59` |
| `primaryContainer` | `#DBEAFE` | `#1E3A8A` | 品牌容器（徽章/胶囊底） | `GlassCountBadge` `Components.swift:435`、`GlassIconBadge` `Components.swift:649`、禁用态主按钮 `Components.swift:238`、时间/地点胶囊 `DiaryPageView.swift:580,680`、搜索条件胶囊 `SearchView.swift:119,329`、行高亮 `SearchView.swift` |
| `quoteBg` | `#E4EAF9` | `#2B3344` | 引用段底色 | 仅经 `Theme.quoteBgUIColor()`（`Theme.swift:44`）用于 TextKit：`RichTextEngine.swift:235,454,1204` |
| `shadowColor` | `#000000` @ **7.8%** | `#000000` @ **14.9%** | 阴影基色（再乘 per-site 系数） | `DiaryCard` `Components.swift:155`、toast `HomeView.swift:60`、格式栏 `RichTextView.swift:381`、分享预览 `ShareSheetView.swift:85` |
| `AccentColor` | `#2563EB` (`srgb 0.145/0.388/0.922` → 37,99,235) | 同值（**无 dark 变体**） | 系统强调色 | 未在代码中直接引用；与 `Theme.seed` 同值 |

**代码内计算的品牌色**（非 Asset）：

| 名称 | 值 | 定义 | 用途 |
|---|---|---|---|
| `Theme.seed` / `primary()` | `#2563EB` | `Theme.swift:4-6` | 全局 tint（`RootView.swift:59`）、选中圆、今天圆环、日期下划线、图表柱、主按钮实底、光标色 |
| `onPrimary()` | light `#FFFFFF`／dark `#191C20` | `Theme.swift:8-10` | 选中日期的数字色 `CalendarGrids.swift:102` |
| `glowColor()` | `#2563EB` @ 15% | `Theme.swift:14` | 主按钮辉光、搜索行高亮、首页"今天"徽章阴影 |
| `flowLightColor()` | `#2563EB` @ 40% | `Theme.swift:16` | 流光上沿 |
| `flowMaskColor()` | `#2563EB` @ 5% | `Theme.swift:18` | 流光卡片蒙版 |

**平台系统色**（SwiftUI/UIKit 语义色，Android 需自行等价）：

- `Color(.secondarySystemGroupedBackground)` — `diaryCard` 卡片填充 `Components.swift:145`（**卡片本体唯一来源**）。
- `UIColor.label` / `.secondaryLabel` / `.secondarySystemFill` / `.systemFill` — 只在 Asset 缺失时兜底（`Theme.swift:25,42,43,44`）。
- `Color.black.opacity(0.25)` — 设置页 busy 遮罩 `SettingsView.swift:79`。
- `Theme.onSurface().opacity(0.08)` @1pt — 分享预览描边 `ShareSheetView.swift:83`。
- `Theme.onSurface().opacity(0.5)` — 非本月日期数字 / 空态图标 `CalendarGrids.swift:111`、`Components.swift:484`、`DayContentView.swift:58`。
- `Theme.onSurfaceVariant().opacity(0.6)` — 周末星期名 `CalendarGrids.swift:16`。
- `Theme.onSurfaceVariant().opacity(0.7 / 0.8)` — 农历行 `CalendarGrids.swift:364`、`CalendarPaneViews.swift:170`。

> ⚠️ `onSurfaceVariant` 与 `outlineVariant` 互为**明暗互换**：light 下 `onSurfaceVariant=#44474F`、`outlineVariant=#C5C6D0`；dark 下完全对调。做 M3 映射时不要以为是笔误。

### 1.2 字体阶梯（Type Scale）

**所有字号都是"设计字号"（design size）**，绘制时必须过 `DynamicTypeMetrics.scaled(_:for:)`
（SwiftUI 侧 `.diaryFont(_:weight:)`，`DynamicType.swift:52`）。

| 令牌 | 设计字号 | 默认字重 | Apple 文本样式（自动推导） | 语义 | 使用点 |
|---|---|---|---|---|---|
| `TypeSize.display` | **32** | `.medium`（调用点给） | `.largeTitle`（34，最接近） | 首页日历月/年大标题 | `CalendarPaneViews.swift:31` 默认 `titleFont`；竖屏 `MonthBigTitle` |
| `TypeSize.pageTitle` | **24** | `.medium` | `.title2`（22） | `PageHeader` 页面标题 | `Components.swift:176`（首页 24 → `HomeView.swift:371` 横屏月标题） |
| `TypeSize.cardTitle` | **20** | `.medium` | `.title3`（20） | 卡片头（阅读页日期） | `DiaryPageView.swift:446` |
| `TypeSize.headerTitle` | **18** | `.medium` | `.body`（17） | 首页年月胶囊标题 | `HomeView.swift:119` |
| `TypeSize.statValue` | **18** | `.semibold` | `.body`（17） | 足迹 5 列统计数字 | `FootprintView.swift:108` |
| `TypeSize.sheetTitle` | **17** | `.medium` | `.body`（17） | Sheet 标题 | `Components.swift:354` |
| `TypeSize.rowTitle` | **15** | `.regular` | `.subheadline`（15） | 列表行主文字 | 16 处：`Components.swift:212,262,486,569,572`、`SettingsView.swift:174`、`SearchView.swift:293,461`、`DiaryPageView.swift:555`、`CalendarPaneViews.swift:161`、`DayContentView.swift:60`、`FootprintView.swift:294` |
| `TypeSize.rowValue` | **15** | `.regular` | `.subheadline` | 设置行尾值 | `SettingsView.swift:187`（含 chevron 193） |
| `TypeSize.chip` | **14** | `.regular`/`.medium` | `.footnote`（13） | 胶囊/chip 标签 | `Components.swift:212,377,401,457`、`SearchView.swift:147`、`FootprintView.swift:13` |
| `TypeSize.rowSub` | **13** | `.regular` | `.footnote`（13） | 设置/列表行第二行 | `SettingsView.swift:178,211` |
| `TypeSize.sectionTitle` | **13** | `.medium` | `.footnote`（13） | 卡内小节标题 | `SettingsView.swift:116`、`FootprintView.swift:128,169` |
| `TypeSize.meta` | **13** | `.regular`/`.bold` | `.footnote`（13） | 时间戳 / 地点行 | 14 处：`Components.swift`（toast 48）、`DiaryPageView.swift:467,471,476,483,511,575,673`、`SearchView.swift:332`、`SettingsView.swift:86`、`ShareSheetView.swift:102` |
| `TypeSize.badge` | **12** | `.medium`/`.regular` | `.caption1`（12） | 计数徽章 | 5 处：`Components.swift:427`、`SearchView.swift:110,323,467`、`FootprintView.swift:299` |
| `TypeSize.caption` | **12** | `.regular` | `.caption1`（12） | 最次级说明文字 | 18 处：`CalendarGrids.swift`（农历）、`CalendarPaneViews.swift:169`、`DayContentView.swift:96`、`DiaryPageView.swift:450,516,671`、`SearchView.swift:180,200,338,362,374,424`、`FootprintView.swift:115`、`RichTextView` 等 |

**未进令牌但被硬编码的字号**（Android 需要一并考虑）：

| 值 | 出处 | 含义 |
|---|---|---|
| 26 | `Components.swift:483`、`DayContentView.swift:57` | 空态 SF Symbol |
| 17 | `Components.swift:331`（`PressableGlassIcon` 图标） | 玻璃圆形按钮图标 |
| 16 | `Components.swift:300`（size>36 的图标按钮）、`Components.swift:643,657`（徽章图标）、`HomeView.swift:117`（返回 chevron）、`DiaryPageView.swift:504`（多选圆圈） | 图标字号 |
| 15 | `RichTextView.swift:242,280` | 格式栏按钮图标 |
| 14 | `Components.swift:300,586` | 图标按钮 / 搜索图标 |
| 13 | `Components.swift:209,259`（按钮内图标）、`SearchView.swift:472`（勾选） | 图标字号 |
| 12 | `Components.swift:209`（compact 图标）、`SearchView.swift:341`（chevron.right）、`FootprintView.swift:290`（节点图标） | 图标字号 |
| 11 | `Components.swift:454`、`SearchView.swift:321`（calendar 图标） | 图标字号 |
| 10 | `Components.swift:424` | 徽章内图标 |
| 9 | `SearchView.swift:113`、`RichTextView.swift:282` | 极小的 xmark / chevron.down |
| 1 | `RootView.swift:79`、`DiaryPageView.swift:114,124,132` | UI 测试探针文字（1pt、2% 不透明度） |

#### 1.2.1 Dynamic Type：按 Apple 文本样式缩放 + 每样式上限

实现：`DesignSystem.swift:107-219`、`DynamicType.swift`。

1. **设计字号 → Apple 文本样式**（`textStyle(for:)`，`DesignSystem.swift:143-156`），按"最接近的默认字号"匹配：

   | 设计字号区间 | Apple 样式 |
   |---|---|
   | `34...` | `.largeTitle` |
   | `28..<34` | `.title1` |
   | `22..<28` | `.title2` |
   | `20..<22` | `.title3` |
   | `17..<20` | `.body` |
   | `16..<17` | `.callout` |
   | `15..<16` | `.subheadline` |
   | `13..<15` | `.footnote` |
   | `12..<13` | `.caption1` |
   | 其余（<12） | `.caption2` |

2. **增长率上限**（`ceiling(for:)`，`DesignSystem.swift:124-136`）——这是**有意偏离 Apple 曲线**：

   | Apple 样式 | 上限 | 本项目落到该样式的设计字号 |
   |---|---|---|
   | `.largeTitle` | **1.35×** | 32 |
   | `.title1` | **1.40×** | 28 |
   | `.title2` / `.title3` | **1.45×** | 24 / 22 / 20 / 18 |
   | `.body` | **1.50×** | 17 |
   | `.callout` | **1.55×** | 16 |
   | `.subheadline` | **1.60×** | 15 |
   | `.footnote` | **1.65×** | 13 / 14 |
   | `.caption1` | **1.70×** | 12 |
   | 其余 | **1.75×** | 11 |

   小字号涨得多、大字号涨得少 —— 目的是让整条梯级在最大档（AX5）**保持单调**，
   不会出现"15pt 正文比 22pt 标题还大"的层级倒挂（`DesignSystem.swift:111-123`）。

3. **计算式**（`DesignSystem.swift:190-201`）：

   ```
   multiplier(size, category) = clamp( UIFontMetrics(style(size)).scaledValue(size, traits) / size,
                                       1.0 ... ceiling(style(size)) )
   scaled(size, category)     = size * multiplier(size, category)
   ```

   默认档 `.large` **短路返回 1.0**（`DesignSystem.swift:191`），因此默认外观与"固定字号"完全一致。
   `traits` 由 `UITraitCollection(preferredContentSizeCategory:)` 构造并按 rawValue 缓存（`DesignSystem.swift:158-187`）。
   12 档 `DynamicTypeSize` → `UIContentSizeCategory` 的完整映射见 `DesignSystem.swift:161-177`。

   ⚠️ `DesignSystem.swift:118-120` 注释里那张 "AX5 实际" 表（32→44.8、24→34.8 …）
   与当前代码的 ceiling 不一致（用 ceiling 反算应为 32→43.2、24→34.8 ✓、22→31.9 ✓、
   20→29.0 ✓、12→20.4 ✓、11→19.25）。**以 `ceiling(for:)` 的代码为准**，
   不要在 Android 侧照抄那张注释表。

4. **日历画布"半速"规则**（`calendarMultiplier(for:)`，`DesignSystem.swift:217-219`）：

   ```
   calendarMultiplier = 1 + ( multiplier(15, category) - 1 ) * 0.5
   ```

   即：以 **15pt（subheadline）的倍率**为基准，只取其**增量的一半**。
   理由：`Canvas` 画的日历格子尺寸由屏幕决定，日号走满速会被挤出格子。

   两个消费者：
   - `MonthCanvas` / `WeekRowCanvas`：`m.dayFont *= f; m.lunarFont *= f`（`CalendarGrids.swift:188-189, 273-274`）。
   - 星期栏文字：`.diaryCalendarFont(size)`（`DynamicType.swift:61-63`、`DynamicType.swift:39-48`）。

   > 注意：**只有**日号与农历、星期栏走半速。日历里的**月标题**走全速（`MonthPane.titleFont`
   > 是 `TypeSize.display`，`.diaryFont`）。`DayPane` 的日期标题行也走全速。

5. **UIKit 侧**：`PartsCodec` / `ReadTextView` 在 SwiftUI 外构建 `NSAttributedString`，
   只能接收显式 `DynamicTypeSize`（`DynamicType.swift:66-73`、`DesignSystem.swift:206-209`），
   由 `RootView` 下发（`RootView.swift:65`），且 `fullScreenCover` 内**必须再注入一次**
   （`RootView.swift:97`，因 presentation 不继承更内层的环境）。

#### 1.2.2 行高与段距

- **SwiftUI 界面文字不使用任何显式行高**：全仓库零处 `.lineSpacing()`、零处 `minimumLineHeight`。
  行高完全由 `Font.system(size:)` 的平台默认行盒给出（≈1.19–1.21 × 字号）。
  → Android 侧用 M3 各 type role 的默认 lineHeight 即可，不需要自定义。
- **富文本（编辑器 / 阅读器 / 引用）** 由 TextKit 段落样式给出，全部按**设计字号的比例**：

  | 样式 | 设计字号 (Apple) | `lineSpacing` | `paragraphSpacingBefore` | `paragraphSpacing` |
  |---|---|---|---|---|
  | `title` 大标题 | 28 (Title 1) | 0.13×28 = **3.64** | 0.35×28 = **9.8** | 0.15×28 = **4.2** |
  | `heading` 小标题 | 22 (Title 2) | 0.13×22 = **2.86** | 0.35×22 = **7.7** | 0.15×22 = **3.3** |
  | `body` 正文 | 17 (Body) | 0.13×17 = **2.21** | **0** | **0** |
  | `quote` 引用 | 15 (Subheadline) | **0.5×15 = 7.5** | 0.4×15 = **6.0** | 0.4×15 = **6.0** |
  | `list` / `todo` | 17（正文属性） | 2.21 | 0 | 0 |
  | `image` | — | — | **10.2** | **10.2** |

  代码：`RichTextEngine.swift:40-71`（`lineSpacing`/`paragraphSpacing`/`paragraphSpacingBefore`）、
  `RichTextEngine.swift:109-136`（`EditorDesignSize`）、
  `RichTextEngine.swift:1110-1117`（`imageParagraphStyle()`，`imageSpacing = body × 0.6 = 10.2`）。
  规则要点：**标题离上文远、离下文近**（前 0.35 / 后 0.15），引用上下对称。
  段间距是"前段 `paragraphSpacing` + 后段 `paragraphSpacingBefore`"**相加**（TextKit 语义）。
  这些值**不落库**，每次加载由块样式推导（`docs/editor-typography.md` §5.1）。

### 1.3 圆角令牌

定义：`DesignSystem.swift:13-34`。

| 令牌 | 值 (pt) | 语义 | 使用点 |
|---|---|---|---|
| `Radius.card` | **20** | 内容卡片、列表行、hero 面板 | `diaryCard` 默认值 `Components.swift:130`；13 处卡片调用；`FlowLightOverlay` 裁剪 `Components.swift:672,694` |
| `Radius.panel` | **26** | 悬浮控件面（编辑器格式栏、sheet、popover） | `RichTextView.swift:377,380`（格式栏玻璃面板）；`ImageShareService.B.cardRadius` 也是 26 |
| `Radius.field` | **14** | 输入框、卡内次级面 | 搜索地点输入 `SearchView.swift:422` |
| `Radius.image` | **14** | 卡内联图片 | `MediaViews.swift:32`、`ShareSheetView.swift:80,82`、`RichTextEngine.swift:1097`（`DiaryImageStore.rounded`）、`RichTextView` 表情 |
| `Radius.badge` | **12** | 图标徽章、小色块 | `GlassIconBadge` 默认 `Components.swift:635,648,652`；地点行高亮 `SearchView.swift:479` |
| `Radius.bar` | **4** | 图表柱、粗描边 | `Charts` `BarMark` `FootprintView.swift:137` |
| `Radius.hairline` | **1** | 2pt 高标记线的圆角 | 日历"有日记"下划线 `CalendarGrids.swift:131` |

**同心圆角公式**（Apple "rounded shape inside rounded shape" 规则）：

```swift
static func concentric(outer: CGFloat, inset: CGFloat) -> CGFloat { max(0, outer - inset) }
// DesignSystem.swift:31-33
```

即 **内圆角 = 外圆角 − 内缩量**；把内外设成同一个值会产生"尖角内衬"。
> 代码里目前**没有调用点**（grep 为零），但作为规则保留 —— Android 侧实现嵌套卡片/
> 内嵌输入框时按同一公式推导，不要各自拍值。

### 1.4 间距令牌

定义：`DesignSystem.swift:40-46`。

| 令牌 | 值 (pt) | 语义 | 使用点 |
|---|---|---|---|
| `Spacing.card` | **12** | 卡片内边距 / 卡片间纵向间距 | 卡片 padding：`DiaryPageView.swift:543,596`、`DayContentView.swift:112`；卡间距：`SettingsView.swift:9`、`SearchView.swift:243`(10)、`FootprintView.swift:38,49`；`RowDivider.textInset` 的基数 `Components.swift:621` |
| `Spacing.rowIcon` | **12** | 行内图标徽章与文字之间的间距 | `RowDivider.textInset = 12 + 38 + 12 = 62`（`Components.swift:621`）；`rowLabel`/`switchRow` 的 `HStack(spacing: 12)` `SettingsView.swift:170,202` |
| `Spacing.hitTarget` | **44** | Apple 最小点击目标 | `HomeView.swift:127`（年月胶囊 minHeight）；`DiaryPageView.swift:433`（条目内搜索步进按钮） |

**未进令牌、但反复出现的布局常数**（Android 需要对账）：

| 值 | 含义 | 出处 |
|---|---|---|
| **16** | 四屏统一页面水平边距 | `AdaptiveLayout.pagePadding` `AdaptiveLayout.swift:144-146`；`.adaptivePagePadding()` `AdaptiveLayout.swift:239-253` |
| **12** | 页面顶部留白 | `SettingsView.swift:20`、`SearchView.swift:58`、`FootprintView.swift:70`、`HomeView.swift:38` |
| **20** | 卡片**内容**水平内边距（阅读内容、日历标题行） | `DayContentView.swift:44`、`CalendarPaneViews.swift:184`、`HomeView.swift:570`、`CalendarGrids.swift:319,367` |
| **14** | 卡片 padding / 搜索框水平内边距 | 卡片：`DiaryPageView.swift:488`、`SearchView.swift:344`、`FootprintView.swift:152,186`；搜索框：`Components.swift:589` |
| **10** | 搜索筛选面板 padding、卡片间距 | `SearchView.swift:173`、`SearchView.swift:243` |
| **8** | 主按钮图标与文字间距 / 玻璃胶囊放进横滑时的上下余量 | `Components.swift:206`；`SearchView.swift:192,210`、格式栏 `RichTextView.swift:352` |
| **6** | 首页头 HStack 间距 / 胶囊内间距 | `HomeView.swift:110,115`、`Components.swift:206`(主按钮 HStack 6) |
| **4** | 格式栏按钮间距（**刻意**：6pt 时九个按钮 ~404pt 会超出 402pt 屏宽） | `RichTextView.swift:299-301,311` |
| **18** | 首页日记列表顶部留白 | `DayContentView.swift:45` |
| **10** | 首页日记卡片内 VStack 间距（信息行与正文之间） | `DayContentView.swift:83` |
| **96 / 140 / 120** | 首页日记滚动底部留白 / 编辑页 `TabBarClearance(base:140)` / `TabBarClearance` 默认 120 | `DayContentView.swift:15`、`DiaryPageView.swift:274`、`Components.swift:606` |
| **56** | 空态顶部留白 | `Components.swift:494`、`DayContentView.swift:69`、`SearchView.swift:259,296,305,312` |
| **220 / 190 / 140 / 132** | 分享面板渲染占位最小高 / 足迹横屏 blockH 上限 / blockH 下限 / 图表默认高 | `ShareSheetView.swift:105`、`FootprintView.swift:48,145` |
| **720** | 分享长图固定绘制宽度 | `ImageShareService.swift:24`、`ShareSheetView.swift:79` |
| **548.5** | 首页分栏阈值（见 §2） | `AdaptiveLayout.swift:62-63` |

### 1.5 阴影

**基色**：`Theme.shadowColor()` —— light `#000` @7.8%、dark `#000` @14.9%。
**每个使用点再乘一层系数**：

| 场景 | 不透明度系数 | 模糊半径 | Y 偏移 | 最终 alpha（light / dark） | 出处 |
|---|---|---|---|---|---|
| 内容卡片（静止） | ×0.14 | **8** | **2** | 1.09% / 2.09% | `Components.swift:155-157` |
| 内容卡片（`interactive: true`） | ×0.22 | **12** | **4** | 1.72% / 3.28% | `Components.swift:155-157` |
| 主按钮辉光 | 用 `glowColor()`（#2563EB@15%），不再乘系数 | **10**（compact **8**） | **3**（compact **2**） | 15% | `Components.swift:235` |
| 编辑器格式栏 | ×1.0 | **14** | **4** | 7.8% / 14.9% | `RichTextView.swift:381` |
| 首页未来日期 toast | ×1.0 | **12** | **4** | 7.8% / 14.9% | `HomeView.swift:60` |
| 分享预览图 | ×1.0 | **18** | **8** | 7.8% / 14.9% | `ShareSheetView.swift:85` |
| "今天"胶囊徽章 | 用 `glowColor()` | **6** | **1** | 15% | `DiaryPageView.swift:457` |

### 1.6 描边（Strokes）

| 场景 | 颜色 | 线宽 | 出处 |
|---|---|---|---|
| 内容卡片（常规对比度） | `outlineVariant` @ **45%** | **0.5** | `Components.swift:151-152` |
| 内容卡片（**增强对比度**开启） | 外圈 `onSurface` @ **55%** 1pt ＋ 内圈 `outlineVariant` @ **90%** **1.5pt** | 1 / 1.5 | `Components.swift:148,151-152` |
| 图标徽章 | `glassBorder` | **1** | `Components.swift:653` |
| 格式栏分隔线 | `outlineVariant` @ 50% | 1（`Divider().frame(height:20)`） | `RichTextView.swift:317-319,332-334` |
| 列表行分隔线 | `outlineVariant` @ 50%（`Divider().overlay`） | hairline | `Components.swift:626-627`、`ShareSheetView.swift:59` |
| 日历月格行分隔线 | `outlineVariant` @ **35%** × `dividerAlpha` | **1pt**（画在 `rowY-0.5`） | `CalendarGrids.swift:206-208`、`CalendarGrids.swift:285` |
| 分栏竖向分隔线 | `outlineVariant` @ **40%** | **0.5** | `HomeView.swift:378-381` |
| 分享预览图外框 | `onSurface` @ 8% | 1 | `ShareSheetView.swift:83` |
| 日历"今天"圆环 | `primary` @ `cellAlpha` | **1.5** | `CalendarGrids.swift:91-96` |
| 日历"有日记"下划线 | `primary`（选中时 `onPrimary`），尺寸 `min(20, cellW×0.5)` × **2pt**，圆角 `Radius.hairline` | — | `CalendarGrids.swift:127-133` |

### 1.7 模糊与玻璃

**A. 背景色球（`BlobBackground`，`Components.swift:76-103`）**
三层模糊圆叠加在 `bg` 上，整体 `.ignoresSafeArea()`。两种档位：

| 档位 | 球 | 直径 | 模糊半径 | 位置（x, y） |
|---|---|---|---|---|
| 默认 | blobA | **280** | **60** | `(-60, 220)` |
| | blobB | **240** | **60** | `(geo.width + 20, 640)` |
| | blobC | **200** | **55** | `(geo.width − 30, −10)` |
| `dense: true`（仅编辑页 `DiaryPageView.swift:27`） | blobA | **300** | **70** | `(-80, 200)` |
| | blobB | **250** | **65** | `(geo.width + 20, 560)` |
| | blobC | **210** | **60** | `(geo.width − 40, −30)` |

`diaryBackground()`（`Components.swift:105-119`）把默认档挂成整页背景，四个 tab 都用
（`RootView.swift:37,41,48,53`）。

**B. Liquid Glass 使用边界**（`docs/design-system.md` §5 + `Components.swift:121-133`）
**内容区不用玻璃，玻璃只属于"浮在内容之上的控件层"**：

| 用玻璃（控件层） | 用不透明卡片（内容层） |
|---|---|
| `GlassPrimaryButton` `Components.swift:234` | 所有 `diaryCard`（13 处调用点） |
| `GlassSecondaryButton` `Components.swift:282` | — |
| `GlassIconButton`（`.buttonStyle(.glass)`）`Components.swift:305` | — |
| `PressableGlassIcon`（`.buttonStyle(.glass)`）`Components.swift:337` | — |
| `GlassChip` `Components.swift:385` | — |
| `InfoCapsule` `Components.swift:409` | — |
| `GlassActionChip` `Components.swift:466` | — |
| `GlassSearchField` `Components.swift:594` | — |
| 编辑器格式栏 `RichTextView.swift:379` | — |
| 首页年月胶囊 `HomeView.swift:131`、未来日期 toast `HomeView.swift:58` | — |

三条硬规则（都踩过坑）：
1. **不在玻璃上叠玻璃**：条目内搜索的"上/下一个匹配"原是玻璃里的玻璃按钮，
   现改为普通 `Button`（`DiaryPageView.swift:417-438`）。
2. **主按钮的品牌色实底是有意保留的**（`Components.swift:225-235`）：只留 tint 会让主 CTA
   明显变弱；玻璃在这里提供高光与边缘处理。
3. **玻璃胶囊放进横向滚动必须留上下余量**：`.padding(.vertical, 6)`（搜索筛选行
   `SearchView.swift:192,210`）或 `8`（格式栏 `RichTextView.swift:352`）。
   否则 `.buttonStyle(.glass)` 自带的外沿高光会被滚动视口裁掉。

**C. `FlowLightOverlay`（`Components.swift:665-705`）** —— **当前零实例化**，是"做出来但没接上的设计组件"。
参数留档：4 秒一个循环；上下各一条 3pt 高的线性渐变条（`[.clear, flowLight, .clear]`，
下沿用 `flowLight` @ 70%）；上沿 y=6，下沿 y = height − 6；alpha 三角波 `max(0, 1 − |cycle − 0.5| × 4)`；
60fps `TimelineView`；卡片蒙版 `flowMaskColor`；启动 **10 秒后**用 1.0s easeInOut 暂停；
`reduceMotion` 时 `TimelineView(paused:)` 直接暂停。

---

## 2. Layout & 自适应

### 2.1 唯一的判据：`AdaptiveLayout`（`AdaptiveLayout.swift`）

**设计约束（代码注释 `AdaptiveLayout.swift:3-15`）**：只看**当前可用宽度/高度**，
**不看 `UIDevice.idiom`、不看 orientation、不读 `UIScreen.main`**。
理由：iPhone Duo 展开后仍是 iPhone 但宽高都是 regular，任何按机型分支的代码在它上面都会错。
左右安全区**分别**读取（横屏时 `leading` 常非 0）。

| 属性 | 公式 | 行 |
|---|---|---|
| `bottomInset` | `max(0, safeArea.bottom)` | `AdaptiveLayout.swift:27` |
| `contentWidth` | `max(0, size.width − max(0, safeArea.leading) − max(0, safeArea.trailing))` | `AdaptiveLayout.swift:35-37` |
| `isPortrait` | `size.height >= size.width` | `AdaptiveLayout.swift:40` |
| `leadingPagePadding` / `trailingPagePadding` | 恒为 **16**，**不含安全区** | `AdaptiveLayout.swift:145-146` |
| `contentColumn(maxWidth:)` | `min(maxWidth, max(240, size.width − 2×16))`，默认 `maxWidth = 660` | `AdaptiveLayout.swift:132-134` |
| `tabBarClearance` | `isPortrait ? 83 : 64` | `AdaptiveLayout.swift:122-124` |
| `defaultValue` | `size = 390×844`，`safeArea = .zero`（仅预览兜底） | `AdaptiveLayout.swift:150-153` |

### 2.2 布局类（"断点"）与阈值

手机端**只有两个版面类**（没有 widthSizeClass 分档表，全部由下面这一条判据派生）：

| 类名（概念） | 判定条件 | 首页表现 |
|---|---|---|
| **单栏（竖屏 / 宽但不矮）** | `size.height >= size.width`（`isPortrait`）**或** `contentWidth < 548.5` | 年 / 月 / 周**三态 morph**，单栏 |
| **分栏（横屏且两栏放得下）** | `size.width > size.height` **且** `contentWidth >= 548.5` | 左月历 + 右选中日 |

```swift
var splitsMasterDetail: Bool {
    size.width > size.height && contentWidth >= Self.minSplitContentWidth
}   // AdaptiveLayout.swift:75-77
```

**分栏阈值 548.5pt 的构成**（`AdaptiveLayout.swift:45-63`）：

| 常量 | 值 | 行 |
|---|---|---|
| `minMasterWidth`（月历主栏最窄） | **260**（7 列每格 ~37pt，日期才点得准） | `:45` |
| `maxMasterWidth`（主栏最宽） | **440**（再宽只是拉大格子） | `:47` |
| `minDetailWidth`（详情栏最窄） | **240**（再窄正文每行只折 3–4 字） | `:49` |
| `splitGutter`（两栏之间空隙，分隔线左右各一半） | **16** | `:51` |
| `splitSeparatorWidth` | `splitGutter + 0.5` = **16.5** | `:53` |
| `splitTopPadding`（分栏区块距页顶留白） | **8** | `:55` |
| `pagePadding` | **16** | `:144` |
| **`minSplitContentWidth`** | `2×16 + 16.5 + 260 + 240` = **548.5** | `:62-63` |

> ⚠️ **旧实现要求 `contentWidth >= 700`，已被取代**。700 把 iPhone SE 横屏（667pt，无安全区）
> 挡在门外，导致"同是横屏，18 Pro 分栏、SE 只是把竖屏版面横向拉长"。
> 现在**任何横屏 iPhone 都能拿到左右双列**（`AdaptiveLayout.swift:70-74`）。

**分栏两栏宽度分配**（`splitColumns(containerWidth:)`，`AdaptiveLayout.swift:88-94`）：

```swift
let available = max(0, containerWidth - 2*16 - 16.5)
let preferred = min(440, max(260, round(containerWidth * 0.46)))
let master    = min(preferred, max(260, available - 240))
return (master, max(240, available - master))
```

**传容器宽，不要传 `contentWidth`** —— 这里算的是实际排版，必须与真正可用的容器对齐。
实测结果（已用脚本按公式复算，两栏之和 + 页边距 + 分隔线**正好等于容器宽**）：

| 设备 / 形态 | 容器宽 | 可用内容宽 | 主栏 | 详情栏 | 和 + 32 + 16.5 |
|---|---|---|---|---|---|
| iPhone 18 Pro 横屏 | 874 | 750（−62 −62） | **402.0** | **423.5** | 874.0 ✓ |
| iPhone SE 横屏 | 667 | 667（无安全区） | **307.0** | **311.5** | 667.0 ✓ |
| 阈值边界 | 548.5 | 548.5 | **260.0** | **240.0** | 548.5 ✓ |
| 分屏 / 折叠态 | 600 | 600 | 276.0 | 275.5 | 600.0 ✓ |

> ⚠️ `docs/adaptive-layout-plan.md:64` 写"18 Pro 横屏 左 345 / 右 356.5"——
> 那是**设计稿阶段**用 750（内容宽）×0.46 算的；代码传的是**容器宽 874**，
> 实际是 **402 / 423.5**。以代码为准。

**分栏两栏高度**（`splitPaneHeight(containerHeight:)`，`AdaptiveLayout.swift:100-102`）：

```swift
max(220, containerHeight - splitTopPadding - tabBarClearance)
```

- 18 Pro 横屏（高 402）：`402 − 8 − 64` = **330**
- SE 横屏（高 375）：`375 − 8 − 64` = **303**
- 下限 **220** 是兜底：到那一步日历密度会自己降级成周条，不会把日期压扁。

### 2.3 底部系统浮条（tab bar）避让

`AdaptiveLayout.swift:104-124`。**浮条高度只与方向有关，与机型无关**（UI 测试实测
`app.tabBars` frame）：

| 方向 | 常量 | 实测 | 按钮高 |
|---|---|---|---|
| 竖屏 | `tabBarHeightPortrait = 83` | 18 Pro `(0, 791, 402, 83)`；SE `(0, 584, 375, 83)` | 54pt |
| 横屏 | `tabBarHeightLandscape = 64` | 18 Pro `(0, 338, 874, 64)`；**SE `(0, 311, 667, 64)`** | 36pt |

两条都**紧贴屏幕底边**，所以让出的高度就是浮条自身高度。

> ⚠️ **不要写成 `bottomInset + 44`**（旧写法）：它在有 home indicator 的机型上
> （横屏 `bottomInset = 20`）碰巧等于 64，所以 18 Pro 上看着是对的；
> 但 SE 横屏 `bottomInset = 0`，只让出 44pt，最后一行日期会被浮条压住 20pt。

导航形态用**系统默认的底部浮条**（`TabView` 不加额外样式，`RootView.swift:31-55`），
且 `.tabBarMinimizeBehavior(.onScrollDown)`（`RootView.swift:55`）。
**不引入第二套导航**，也不在 `TabView` 里套 `NavigationSplitView`（会与页内主从结构套成两层）。

### 2.4 安全区与键盘 inset 的消费方式

**几何读取只发生一次**：`RootView` 上的 `.adaptiveLayoutReader()`（`RootView.swift:68`），
以及 `fullScreenCover` 内的**第二次注入**（`RootView.swift:101`，因 presentation 不继承环境）。
内部用 `onGeometryChange` 而非包 `GeometryReader`（后者会让 `TabView` 失去尺寸并把安全区清零，
`AdaptiveLayout.swift:172-174`）。

**`resolve(_:)` 的关键推导**（`AdaptiveLayout.swift:209-236`）：

1. `GeometryProxy.size` 是**扣掉安全区之后**的内容尺寸（横屏 874×402 会读成 750×382），
   拿它判档位会偏小 → 因此界面尺寸取 **scene 的 `screen.bounds`**，
   并把"内容尺寸 → 界面尺寸"的差值当作安全区。
2. 差值多于左右安全区之和时（夹着导航条之类的条带），补到 `trailing`；
   纵向同理补到 `bottom`（`:228-234`）。
3. 读不到场景（预览）时退回"内容尺寸 + 0 安全区"，只影响预览。

**消费规则**：

- **页面内容已经在安全区里**（横屏几何原点就是 x = 62），页面**只加自己的 16pt 页边距**
  （`.adaptivePagePadding()`，`AdaptiveLayout.swift:239-253`）。
  **绝不能再加一次 `safeArea.leading`** —— 第一版这样做导致横屏所有页面内容右移 62pt
  （页头在 78、卡片在 140），左半屏白掉一条（`AdaptiveLayout.swift:140-143`）。
  `contentWidth` **只用于"可用内容宽度"这类计算**，不要叠进页边距。
- **底部**：内容 `.ignoresSafeArea(edges: .bottom)` 时（`HomeView.swift:44`），
  底部让位取 `tabBarClearance`；未忽略时用 `bottomInset`。
  `DayPane` 用 `max(bottomInset, 24)`（`CalendarPaneViews.swift:150`）。
- **键盘**：
  - 编辑器**自己做键盘避让**：`.ignoresSafeArea(.keyboard, edges: .bottom)`（`DiaryPageView.swift:57`）
    ＋ 内容末尾补一块 `keyboardHeight + 160` 的占位（`DiaryPageView.swift:265`），
    **不再让系统避让一次**（两套叠加会多出一份键盘高度的空白）。
  - 键盘高度必须换算到窗口坐标求交集：`Screen.keyboardObscuredHeight(screenFrame:)`
    （`Components.swift:63-71`）。**不能取 `frame.height`** —— 横屏时那套坐标可能是竖屏朝向的
    （SE 横屏实测 `(0, 250, 375, 417)`，比整块屏还高）。
  - 键盘动画曲线映射：`curveValue 1→.easeIn / 2→.easeOut / 3→.linear / 默认→.easeInOut`，
    时长取 `keyboardAnimationDurationUserInfoKey`，默认 0.25s（`DiaryPageView.swift:229-240`）。
  - 格式栏位置：键盘弹起时贴**键盘上方 8pt**；收起时贴 **Home Indicator 上方 `Screen.safeAreaBottom + 12`**
    （`DiaryPageView.swift:49`）。
  - 光标可见性：`revealCaret` 以格式栏**上沿**为下界（`formatBarTop − 8`）、
    顶栏高度为上界（`topBarHeight + 8`）（`DiaryPageView.swift:202-221`）。

**`Screen` 工具（`Components.swift:13-72`）**：`size` 取
当前前台场景的 `keyWindow.bounds.size`（跟着运行时改尺寸走），
场景不存在时兜底 `390×844`；`safeAreaBottom` 取 `keyWindow.safeAreaInsets.bottom`。
`onGeometryChange` 侧必须比较**只影响版面的部分**（`LayoutGeometry`，`AdaptiveLayout.swift:183-186`），
避免滚动时每帧刷新。

### 2.5 编辑器正文列宽

| 项 | 规则 | 出处 |
|---|---|---|
| 列宽上限 | **660pt**，阅读态与编辑态**同宽** | `DiaryPageView.swift:284`（`layout.contentColumn(660)`） |
| 生效形式 | `min(660, max(240, size.width − 32))`；**页边距加在这个 frame 外面** | `AdaptiveLayout.swift:132-134`、`DiaryPageView.swift:284-285` |
| 窄屏实测列宽 | 402→**370**；375→**343**；667→**635**；750/874→**660** | 按公式复算 |
| 卡片内边距 | `Spacing.card` = **12**，编辑区不再自己左右内缩 | `DiaryPageView.swift:596` |
| 原因 | 原来编辑态 620 / 阅读态 660，横屏进出编辑整张卡片会跳；且编辑区自带 12pt 内缩后，SE 上 `343 − 24 = 319` 的提议宽度会被 `UITextView` 的 340pt 兜底撑大、正文与图片横向溢出 | `docs/adaptive-layout-plan.md:242-246` |
| 输入区最小高 | `160 × multiplier(17, category)`（默认 160，AX5 约 240） | `RichTextView.swift:29` |
| 图片排版 | 按**列宽**等比放大并**居中**；存储的 `w/h` 只作比例与取图分辨率上限 | `RichTextEngine.swift:1076-1102`、`MediaViews.swift:20-45` |
| 格式栏 | 横竖屏都是**键盘上方的横排玻璃条**，**按内容宽度居中**（`ViewThatFits`：装不下才退化成横滑），不拉满整行 | `RichTextView.swift:355-366` |

> **不做正文双列**：正文分两列会破坏阅读顺序（Apple 备忘录/图书/News 也是单栏限宽）。
> 图片与地图**可以**与正文并排（那是"并排的介质"，不是"并排的文字"）。
> 见 `docs/adaptive-layout-plan.md` §6。

### 2.6 首页分栏（split columns）

`HomeView.splitCalendarArea(size:)`，`HomeView.swift:322-401`：

```
HStack(alignment: .top, spacing: 0) {
  MonthPane  .frame(width: master, height: paneH).clipped()   // 上下滑翻月
  Rectangle(outlineVariant@40%).frame(width: 0.5, height: paneH).padding(.horizontal, gutter/2)
  DayPane    .frame(width: detail, height: paneH).clipped()
}
.padding(.leading/.trailing, 16).padding(.top, 8)
.frame(width: size.width, height: size.height, alignment: .topLeading)
```

| 项 | 规则 | 出处 |
|---|---|---|
| 头部 | 分栏时**整块头部隐藏**（年份入口取消，横屏只剩"上下滑切月"） | `HomeView.swift:31-34` |
| 「今天」 | 移到**右栏标题行** | `CalendarPaneViews.swift:179-182` |
| 标题槽 / 星期栏 | 横屏紧凑 **34 / 26**（竖屏 **72 / 30**） | `CalendarLayout.swift:155-162`、`HomeView.swift:333` |
| 右栏正文列 | `maxColumnWidth: 560`，居中 | `HomeView.swift:389`、`CalendarPaneViews.swift:147-148` |
| 右栏标题行高 | **44**（竖屏 `dayTitleH = 40`） | `HomeView.swift:390`、`CalendarLayout.swift:164` |
| 翻月 | 垂直 `DragPagePager`，`pageSize: paneH`；翻月后**选中日跟着进入新月份的同一天**（月末按当月天数夹住） | `HomeView.swift:353-359, 404-413` |
| 点日期 | 分栏时只更新选中日（**不进入周视图**），动画 `.snappy(duration: 0.25)` | `HomeView.swift:502-511` |
| 旋转进入分栏 | `settleToMonth()`：**无动画**收掉年/周态与 morph | `HomeView.swift:67-94` |

**竖屏（单栏）侧的关键几何**：

| 项 | 值 | 出处 |
|---|---|---|
| 头部 | `‹ 年月` 玻璃胶囊（`minHeight: 44`）＋「今天」`InfoCapsule`；整行 `minHeight: 52` | `HomeView.swift:109-149` |
| 日历区可用高 | `max(320, size.height − 64)` | `HomeView.swift:417` |
| 大标题槽 / 星期栏 | **72 / 30** | `CalendarLayout.swift:155-156` |
| 月历每格高 | `(areaH − 72 − 30) / 6` | `CalendarLayout.swift:175-177` |
| 周条高 / 日期标题行高 | **68 / 40** | `CalendarLayout.swift:163-164` |
| 星期名字号 | `min(max(cellW × 0.26, 11), 14)` | `CalendarLayout.swift:173` |
| 年历卡片 | 3 列：`(w − 32 − 12) / 3`；4 行：`(h − 62 − 8 − 18) / 4`；年标题 62；卡内边距 6；`miniTitleH` 17；`yearSpacing` 6 | `CalendarLayout.swift:166-231` |
| 迷你日期字号 | `min(14, max(9, min(cellW × 0.95, cellH × 0.5)))` | `CalendarLayout.swift:206-208` |
| 迷你月农历 | 恒隐藏（`miniLunarHidden = 0`，`lunarFont: 6`） | `CalendarLayout.swift:210, 264-265` |

### 2.7 日历密度降级（横屏高度不足时）

`CalendarDensity`，`CalendarLayout.swift:10-88`。**竖屏不走这条**（竖屏用 `mode` 三态 morph），
横屏只有 `month` 一种交互，放不下时自动降级。

| 档位 | 判定（`resolve(availableHeight:rows:wantsLunar:)`） | 每行至少 | 每行实际 |
|---|---|---|---|
| `.month(lunar: true)` | `floor(availableHeight / rows) >= 44 + 16 = 60` | `lunarRowHeight = 44` | `max(44, floor(avail/rows))` |
| `.month(lunar: false)` | 否则 `floor(availableHeight / rows) >= 38` | `dayRowHeight = 38` | `max(38, floor(avail/rows))` |
| `.weekStrip` | 否则 | 38 | 1 行，`min(cellH, 68)` |

`rows` **必须**传 `CalendarLayout.displayedWeeks(...).count`（去掉末尾"整周都不属于本月"的填充行），
不要传固定的 6 —— 否则"5 行放得下"会被误判成"6 行放不下"，整块错误降级成周条
（`CalendarLayout.swift:347-365`，这正是第一版横屏只剩一行日期的原因）。
两处 `floor` 必须一致（`:61-64` 与 `:44-47`），否则会出现"判定说放得下、实际每行高出 1pt"。

**为什么下限是 38 而不是 44**：44 是"20pt 日号 **+ 11pt 农历** + 选中圆"的高度；
隐藏农历那一档本来就不画农历，六行只需 ~40pt 一格。若坚持 44，
**SE 横屏（375pt 高）会被降级成周条**，左右分栏的左栏只剩一行日期，
横屏首页等于没有月历（`CalendarLayout.swift:23-29`）。

**日历字体随格高推导**（`CalendarPaneViews.swift:101-107`）：
`dayFont = min(20, max(13, cellH × 0.34))`，`lunarFont = min(11, max(9, cellH × 0.18))`；
农历行高 `(lunarFont + 5) × lunarAlpha`（`CalendarGrids.swift:24-26`）。
> ⚠️ `CalendarLayout.monthMetrics` 里写死 `dayFont: 20 / lunarFont: 11`
> （`CalendarLayout.swift:179-186`）只用于**竖屏**路径；横屏与 `MonthPane` 走上面的推导值。

日历格子内的视觉编码（`DayDraw.draw`，`CalendarGrids.swift:50-135`）：

| 状态 | 表现 |
|---|---|
| 选中日 | 实心圆，直径 `min(cellW − 3, contentH + 14, cellH − 3)`，`primary` @ `cellAlpha`；数字用 `onPrimary`、**semibold** |
| 今天（未选中） | **1.5pt** 圆环，同上直径；数字 `primary` |
| 本月普通日 | 数字 `onSurface`，medium |
| 非本月日 | 数字 `onSurface` **@ 50%**；`cellAlpha ×= adjacentAlpha` |
| **未来日** | **`cellAlpha ×= 0.35`**（整格变淡） |
| 有日记 | 数字下方 2pt 圆角下划线，宽 `min(20, cellW × 0.5)`，`primary`（选中时 `onPrimary`），y = `numY + dayFont/2 + 2` |
| 农历 | `lunarFont`，颜色 = 数字色 **@ 75%**（选中时 95%） |

### 2.8 小屏 vs 大屏横屏行为对照（iPhone SE vs 18 Pro）

| 项 | iPhone SE 横屏 667×375 | iPhone 18 Pro 横屏 874×402 |
|---|---|---|
| `safeAreaInsets` | **全 0**（无刘海）→ `contentWidth = 667` | `T0 L62 B20 R62` → `contentWidth = 750` |
| 是否分栏 | **是**（667 ≥ 548.5） | **是**（750 ≥ 548.5） |
| 主栏 / 详情栏 | **307 / 311.5** | **402 / 423.5** |
| `paneH` | `375 − 8 − 64` = **303** | `402 − 8 − 64` = **330** |
| 日历区扣标题/星期栏 | `303 − 34 − 26` = **243** | `330 − 60` = **270** |
| 6 行时每格 | `floor(243/6)` = **40** → 满足 38 → **`.month(lunar: false)`**（隐藏农历） | `floor(270/6)` = **45** → 45 ≥ 60？否 → 也走 **`.month(lunar: false)`**；5 行时 `floor(270/5)=54`，仍 < 60，**同样是隐藏农历** |
| 日号字号 | `min(20, max(13, 40×0.34))` = **13.6** | `min(20, max(13, 45×0.34))` = **15.3** |
| 底部让位 | `tabBarClearance = 64`（`bottomInset = 0`，**不能**用 `bottomInset + 44`） | `tabBarClearance = 64`（`bottomInset = 20`，旧式 `20+44` 恰好也对，但别这么写） |
| 内容左边界 | `0 + 16` = **16** | `62 + 16` = **78** |
| 分栏竖线位置 | x ≈ **339.5** | x ≈ **439** |

**两档在横屏都拿得到左右双列**（这正是把阈值从 700 改成 548.5 的目的）；
差异只体现在格子高度、是否画农历与日号字号。
若可用高度继续掉到 6 × 38 以下（键盘弹起、Duo 折成一半），才降级为**周条**。

**其它页面的横屏行为**（都不分栏，只调整排布）：

| 页面 | 竖屏 | 横屏 | 出处 |
|---|---|---|---|
| 首页 | 年/月/周三态 morph，单栏 | **左右分栏** | `HomeView.swift:31` |
| 足迹 | 统计 → 趋势 → 清单，单栏纵向 | **「趋势图 \| 地点清单」并排**；清单宽 `min(320, max(240, contentWidth × 0.4))`；两块高 `max(140, min(190, screenH − 262))`；图表高 `max(72, blockH − 58)`；清单内部滚动 | `FootprintView.swift:36-62, 160-162` |
| 搜索 | 时间/地点上下两行 | **条件压成一行**（`HStack(spacing:16)`，省约 60pt）；结果仍是**单栏纵向列表** | `SearchView.swift:160-172` |
| 设置 | 单列卡片 | **单列卡片**（共用；设置无主从关系，不做左右两页） | `SettingsView.swift:8-21` |
| 日记页 | 列宽 ≤660 | 列宽 ≤660（同宽）；格式栏仍是横排玻璃条 | `DiaryPageView.swift:284` |
| 分享面板 | 上下（预览 + 系统动作），动作区高 `min(max(340, h×0.48), max(280, h−200))` | **左右**（预览在左，动作区在右，宽 `min(430, w × 0.55)`） | `ShareSheetView.swift:28-44, 141-143` |

---

## 3. 动效与触感

### 3.1 动画令牌

| 令牌 / 场景 | 曲线与时长 | 定义 | 用途 |
|---|---|---|---|
| `Animation.diaryQuick` | `.easeOut(duration: **0.22**)` | `Components.swift:7` | 进/出多选态（`DiaryViewModel.swift:175,206`）、搜索步进、`selectMode` 复位、首页滚动定位（`DiaryPageView.swift:217`）、足迹节点展开（`FootprintView.swift:284`）、阅读页搜索栏开合（`DiaryPageView.swift:366`） |
| `Animation.diaryStandard` | `.easeInOut(duration: **0.3**)` | `Components.swift:8` | 阅读 ↔ 编辑切换（`DiaryViewModel.swift:138,164,236,384`）、首页头部返回按钮显隐（`HomeView.swift:138`）、搜索头部收起与条件栏（`SearchView.swift:59,66`）、日记块列表变化（`DiaryPageView.swift:291`）、滚动到目标块（`DiaryPageView.swift:304,312`） |
| `CalendarLayout.morphAnimation` | `=` `morphSlideAnimation` | `CalendarLayout.swift:143` | 年 ↔ 月缩放 morph |
| `CalendarLayout.morphSlideAnimation` | `.timingCurve(**0.2, 0.75, 0.3, 1.0**, duration: **0.6**)` | `CalendarLayout.swift:151-153` | 年↔月**与**月↔周**共用同一条曲线** |
| `CalendarLayout.morphDuration` | **0.6s**（DEBUG 下 `-slow-morph` 或 `SLOW_MORPH=1` → **3.0s**） | `CalendarLayout.swift:133-139` | 同上 |
| `CalendarLayout.reducedMorphAnimation` | `.linear(duration: **0.18**)` | `CalendarLayout.swift:147` | **减弱动态效果**开启时的 morph 替代 |
| 翻页收尾 / 回到今天 / 未来日期 toast | `.snappy(duration: **0.3** / **0.25**)` | `DragPagePager.swift:79,94`；`HomeView.swift:280,286`（0.3）、`305,311,506`（0.25） | 翻页、选中日切换 |
| 流光淡出 | `.easeInOut(duration: **1.0**)` | `Components.swift:699` | `FlowLightOverlay` 启动 10s 后暂停 |
| 搜索栏焦点显隐 | `.easeOut(duration: **0.2**)` | `SearchView.swift:154` | 搜索框「取消」按钮 |
| 键盘动画 | 取系统 `keyboardAnimationCurve/Duration`（默认 `easeInOut`, **0.25s**） | `DiaryPageView.swift:229-240` | 键盘高度变化 |

> **为什么年↔月不用 `easeInOut`**（`CalendarLayout.swift:140-142`、`docs/animation-and-accessibility.md` §1.2）：
> `easeInOut` 从零速度起步，时间过 25% 时只完成约 **13%** 进度，观感上比月↔周"慢半拍"；
> `timingCurve(0.2, 0.75, 0.3, 1.0)` 在同样 25% 时间点已完成约 **65%**。
> 实测帧率从 56–57fps / p90 26.6ms 提升到 **58fps / p90 16.9–17.0ms**。

### 3.2 过渡（Transitions）

| 场景 | 过渡 | 出处 |
|---|---|---|
| 首页未来日期 toast | `.opacity.combined(with: .move(edge: .bottom))` | `HomeView.swift:62` |
| 搜索头控件收起 | `.opacity.combined(with: .move(edge: .top))` | `SearchView.swift:48,54` |
| 搜索「取消」按钮 | `.opacity` | `SearchView.swift:151` |
| 编辑器格式栏 | `.opacity` | `DiaryPageView.swift:50` |
| 设置页 busy 遮罩 | `.opacity` | `SettingsView.swift:93` |

### 3.3 手势与拖拽几何

`DragPagePager`（`DragPagePager.swift`）：

| 项 | 值 | 行 |
|---|---|---|
| 最小拖动距离 | **8pt** | `:59` |
| 翻页判定 | 预测位移 `p < −90`（下一页）/ `p > 90`（上一页）；或 `p<0 && t<−60` / `p>0 && t>60` | `:74-75` |
| 拖动偏移夹取 | `min(pageSize, max(−pageSize, raw))` —— 越过一页继续拖会露出空白再回弹 | `:68` |
| 边界橡皮筋 | `c = pageSize × 0.25`；`rubber(x) = c·x / (x + c)` | `:101-104` |
| **翻页收尾不变量** | `current` 与 `offset = 0` 必须在**同一个无动画事务**里改 | `:82-91` |
| 页内容裁剪 | 每页单独 `.clipped()` | `:35` |

### 3.4 减弱动态效果（Reduce Motion）

全仓库仅 **2 处**读取 `@Environment(\.accessibilityReduceMotion)`：

| 位置 | 行为 | 行 |
|---|---|---|
| `HomeView` | `zoomAnimation` / `slideAnimation` 换成 `CalendarLayout.reducedMorphAnimation`（0.18s 线性）—— **状态照常切换，但没有横扫屏幕的位移** | `HomeView.swift:10, 161-167` |
| `FlowLightOverlay` | `TimelineView(..., paused: paused \|\| reduceMotion)` —— 直接暂停 | `Components.swift:666, 674` |

其余动效（`.snappy`、`.diaryQuick`、`.diaryStandard`）**未接减弱动态效果**。

### 3.5 触感调用点（Haptics）

`Haptics` 只有**一个** API（`Haptics.swift:3-10`）：

```swift
private static let tapGenerator = UIImpactFeedbackGenerator(style: .light)
static func tap() { tapGenerator.prepare(); tapGenerator.impactOccurred() }
```

即：**UIImpactFeedbackGenerator(.light)**，每次调用前 `prepare()`。
> `docs/design-system.md:301` 记录 `Haptics.medium` 已被删除；**没有** selection / notification 反馈。

**全部 34 个 `Haptics.tap()` 调用点**（按交互归类）：

| 交互 | 文件:行 |
|---|---|
| 主按钮 `GlassPrimaryButton`（所有主 CTA） | `Components.swift:203` |
| 次按钮 `GlassSecondaryButton` | `Components.swift:253` |
| 玻璃圆形图标按钮 `GlassIconButton` | `Components.swift:296` |
| 可按压玻璃圆 `PressableGlassIcon` | `Components.swift:327` |
| 玻璃胶囊 chip `GlassChip` | `Components.swift:373` |
| 信息胶囊 `InfoCapsule` | `Components.swift:397` |
| 动作 chip `GlassActionChip` | `Components.swift:448` |
| 首页头部返回 / 年月胶囊 | `HomeView.swift:112` |
| 年历点月份进月视图 | `HomeView.swift:180` |
| 点日期进周视图（竖屏） | `HomeView.swift:205` |
| 「今天」（月态 / 周态） | `HomeView.swift:279, 285` |
| 分栏下点日期 | `HomeView.swift:505` |
| 周条点日期 | `HomeView.swift:536` |
| 首页日记卡信息行 / 正文点击进阅读 | `DayContentView.swift:35, 101, 108` |
| 「继续写」 | `DayContentView.swift`（`Haptics` 由 `GlassPrimaryButton` 内部触发） |
| 阅读页顶栏：分享 / 编辑 / 删除选中 | `DiaryPageView.swift:360`（删除） |
| 条目内搜索步进（上/下一个匹配） | `DiaryPageView.swift:427` |
| 多选态切换某个块 | `DiaryPageView.swift:556` |
| 地点「重新获取」 | `DiaryPageView.swift:624` |
| 地点精度菜单选项 | `DiaryPageView.swift:640` |
| 地点「重新获取位置」（菜单内） | `DiaryPageView.swift:653` |
| 图片点击放大 | `MediaViews.swift:35` |
| 格式栏每个按钮 | `RichTextView.swift:237` |
| 格式栏样式菜单选项 | `RichTextView.swift:266` |
| 分享面板关闭 | `ShareSheetView.swift:124` |
| 足迹「自定义时间」chip | `FootprintView.swift:27` |
| 足迹树节点展开 / 折叠 | `FootprintView.swift:283` |
| 搜索条件胶囊点掉 | `SearchView.swift:96` |
| 搜索结果项点击打开日记 | `SearchView.swift:247` |
| 设置行（取值行） | `SettingsView.swift:126` |
| 设置菜单选项 | `SettingsView.swift:157` |
| 设置开关 Toggle | `SettingsView.swift:221` |

**唯一的非 tap 触感**：`sensoryFeedback(.warning, trigger: showFutureToast)`
（`HomeView.swift:63`）—— 将来日期 toast 出现时给一次 warning 反馈。

---

## 4. 无障碍基线

### 4.1 Dynamic Type

- **全量接入**：所有界面文字走 `.diaryFont(_:weight:)`（`DynamicType.swift:52-54`），
  UIKit 侧走 `UIFont.diary(_:weight:typeSize:)`（`DynamicType.swift:69-72`）。
- **默认档不变**：`.large` 档 `multiplier` 直接返回 1.0（`DesignSystem.swift:191`），
  所以默认外观与"固定字号"版本完全一致。
- **每样式上限**（见 §1.2.1）保证梯级在 AX5 仍然单调（不会出现 15pt 正文大于 22pt 标题）。
- **日历半速**（见 §1.2.1 第 4 点）。
- **溢出策略**（`docs/design-system.md` §6）：
  - 固定高度 → **`minHeight`**：`PageHeader`（`Components.swift:187`，52pt）、
    首页头部（`HomeView.swift:148`，52pt）、设置行（`SettingsView.swift:197,226`，56pt）、
    搜索筛选摘要行（`SearchView.swift:90`，44pt）、位置选择行（`SearchView.swift:421,477`，44/46pt）、
    周标题行、**编辑器输入区**（`RichTextView.swift:29`，`160 × multiplier(17)`）。
  - 不能换行的单行 → `lineLimit(1)` + `minimumScaleFactor` + `allowsTightening`：
    页面标题 0.7（`Components.swift:178-180`）、首页胶囊 0.7（`HomeView.swift:121-122`）、
    设置尾值 0.7（`SettingsView.swift:190`）、统计 5 列 0.6（`FootprintView.swift:111,118`）、
    日历大标题 / 日期行 0.6（`CalendarPaneViews.swift:164`、`HomeView.swift:560`）、
    农历 0.75（`CalendarPaneViews.swift:175`）、chip 0.85–0.9（`Components.swift:380,459`）、
    主/次按钮 0.85（`Components.swift:214,264`）。
  - 图标徽章按字号放大但**必须保持正方形**：`side = max(38, scaled(16) + 12)`，
    且用 `frame(width:height:)` 而非 `minWidth/minHeight`（后者在 `HStack` 里会被行高拉成长条）
    （`Components.swift:642-644, 659`）。
  - 底部浮条留白随字号放大：`TabBarClearance`（`Components.swift:605-615`），
    `height = base × multiplier(15)`，默认 `base = 120`；编辑页 `base = 140`。
  - 高亮片段字号也必须跟随（曾写死 13pt）：`SearchView.swift:492,512`。
  - 图表坐标轴字号也跟随：`FootprintView.swift:149`。

### 4.2 增强对比度（Increase Contrast）

**只有 1 处**读取 `@Environment(\.colorSchemeContrast)`：`diaryCard`（`Components.swift:136, 142`）。

| | 常规 | `increased` |
|---|---|---|
| 外描边 | **不画**（`onSurface` @ 0%，1pt） | `onSurface` @ **55%**，**1pt** |
| 内描边 | `outlineVariant` @ **45%**，**0.5pt** | `outlineVariant` @ **90%**，**1.5pt** |
| 阴影 | 不变（`shadowColor` × 0.14/0.22） | 不变 |

其余组件（玻璃按钮、chip、日历画布）**未接增强对比度**。

### 4.3 VoiceOver：标签 / 特征（traits）策略

**约定**：
1. **有文字的控件用它自己的文字**，不额外加 `accessibilityLabel`（SwiftUI 默认已读）。
2. **纯图标按钮必须补 label**，绝不能让系统读出符号名。所有图标按钮都走
   `GlassIconButton(systemName:accessibilityLabel:)` / `PressableGlassIcon(...)` /
   `FormatToolbar.btn(_:accessibilityLabel:)` 的显式参数：
   `Components.swift:291,307,317,340`、`DiaryPageView.swift:353,365,373,378,383,387,391`、
   `RichTextView.swift:253`（九个格式按钮 313/320/324/328/335/339/343/347 + 样式菜单 293）。
   > 反面案例已在注释里留档（`DiaryPageView.swift:417-423`）：条目内搜索的步进按钮
   > 原来只有 `.glass` 样式、没有 label，VoiceOver 读出"chevron.up"。
3. **选中态用 `.isSelected` trait**，不用改 label：
   `GlassChip`（`Components.swift:387`）、`GlassActionChip`（`:468`）、
   `PressableGlassIcon`（`:341`）、日记块卡（`DiaryPageView.swift:551`）、
   足迹树节点（`FootprintView.swift:315`）、设置菜单选项（`SettingsView.swift:166`）、
   格式栏按钮（`RichTextView.swift:254`）。
4. **图片加 `.isImage` trait**：`MediaViews.swift:44`（`DiaryImageView`）。
5. **容器语义**：日记块卡与编辑卡用 `.accessibilityElement(children: .contain)`
   （`DiaryPageView.swift:545,550,599`），格式栏作为容器暴露自己的 frame
   （`RichTextView.swift:370-371`，`editor.formatBar`），便于 UI 测试。
6. **菜单类控件用 `.accessibilityValue` 报当前值**：
   设置行（`SettingsView.swift:151`）、地点菜单（`DiaryPageView.swift:664-665`）、
   格式栏样式菜单（`RichTextView.swift:293-294`）。开关用 `.accessibilityLabel(title)`
   （`SettingsView.swift:219`）。
7. **可点掉的搜索胶囊**：`.accessibilityLabel(item.label)` +
   `.accessibilityHint("清除全部条件")`（`SearchView.swift:124-125`）。
8. **Canvas 绘制的日历**必须自己暴露（SwiftUI 无法代劳）：
   - `MonthCanvas`：`.accessibilityElement(children: .ignore)`＋
     label = "2026年9月"（`date_month_title`）＋ value = 选中日（`formatDayKey`）＋
     `.accessibilityAdjustableAction`（上下滑切换日期，`increment` 后一天 / `decrement` 前一天）＋
     默认动作 = 点选中日（`CalendarGrids.swift:228-240`）。
   - 年历的 12 个迷你月：`.accessibilityElement(children: .ignore)` +
     `.accessibilityAddTraits(.isButton)` + label = 月名 + `id = "year.month.<n>"`
     （`CalendarGrids.swift:435-438`）。
   > **已知缺口**（`docs/design-system.md` §七.1）：`accessibilityAdjustableAction`
   > **没有动作标签**（`a11y_next_day` / `a11y_prev_day` 两条文案已被当无用键删除）。
   > Android 侧建议补上 `CustomAccessibilityAction("后一天"/"前一天")`。
9. **状态探针**（仅 UI 测试，1pt / 2% 不透明 / `allowsHitTesting(false)`）：
   `app.state`（`RootView.swift:77-84`）、`editor.state` / `editor.caret` / `editor.image`
   （`DiaryPageView.swift:106-137`）。

### 4.4 最小点击目标

| 场景 | 尺寸 | 出处 |
|---|---|---|
| Apple HIG 最小值（令牌） | **44 × 44** | `Spacing.hitTarget` `DesignSystem.swift:45` |
| 首页年月胶囊 | `minHeight: 44`（整行 `minHeight: 52`） | `HomeView.swift:127,148` |
| 阅读页条目内搜索步进按钮 | `minWidth/minHeight: 44` | `DiaryPageView.swift:433` |
| `GlassIconButton` | `minWidth/minHeight: 44`（默认 `size = 32` 只影响图标字号） | `Components.swift:302` |
| `PressableGlassIcon` | 可见圆默认 `size = 44`；标签盒 `max(28, size − 8)`，因 `.glass` 样式在标签外再补 4pt/边 | `Components.swift:314-323` |
| 设置行 / 开关行 | `minHeight: 56` | `SettingsView.swift:197,226` |
| 地点选择行 | `minHeight: 46` | `SearchView.swift:477` |
| 地点搜索框 | `minHeight: 44` | `SearchView.swift:421` |
| 搜索筛选摘要行 | `minHeight: 44` | `SearchView.swift:90` |
| 搜索框 `GlassSearchField` | `minHeight: 46` | `Components.swift:590` |
| 格式栏按钮 | `minWidth: 34, minHeight: 40`；样式菜单 `minWidth: 46` | `RichTextView.swift:244,285` |
| 分享面板的"看得见的叉"点击区 | 96 × 96 透明区（压住系统远程视图上的关闭按钮） | `docs/design-system.md:252` |
| 日历单格 | 由布局保证：竖屏 `(areaH−102)/6`；横屏下限 38（见 §2.7） | `CalendarLayout.swift:29,175-177` |
| 足迹树行 | `.padding(.vertical, 9)`（行内容自然高度 ~36–40） | `FootprintView.swift:310` |

### 4.5 与无障碍相关的设置项

| 设置 | 值域 | 默认 | 影响 | 出处 |
|---|---|---|---|---|
| 主题 `themeMode` | `system` / `light` / `dark` | `system` | `RootView.preferredColorScheme`；改变即重建整棵树 | `AppConfigService.swift:4-10`、`RootView.swift:60` |
| 语言 `appLanguage` | `system` / `zh` / `en` | `system` | `\.locale` + 整树 `.id` 重建；也决定**是否显示农历**（`AppLanguage.isZh`） | `RootView.swift:66,72`；`HomeView.swift:23` |
| 周起始 `weekStart` | `monday` / `sunday` | `monday` | 日历列序、周末着色（`i>=5` 或 `i==0\|\|i==6`） | `SettingsView.swift:285-292`、`CalendarGrids.swift:15` |
| 新一天开始时间 `dayStartHour` | 0–23（单轮 Picker，标签用应用自己的时间文案） | — | 日记归属日 | `SettingsView.swift:388-407` |
| 自动记录开始时间 `autoTime` | Bool | — | **仅显示层**：关闭时隐藏时间行，`start_time_utc` 照常记录 | `SettingsView.swift:294-297`、`DiaryPageView.swift:465-469` |
| 自动记录地点 `autoLoc` | Bool | — | 新建片段才会查地点；已有片段保持原值 | `SettingsView.swift:299-302` |
| 允许修改历史日期 `allowHistoryEdit` | Bool | — | 关闭时非今天的块不可编辑，弹 `editor_readonly_title/msg` | `SettingsView.swift:312-315`、`DiaryViewModel.swift` |
| 提醒 `remindEnabled` / `remindHour` / `remindMinute` | Bool / 0–23 / 0–59 | — | 本地通知；`DatePicker(.hourAndMinute, .wheel)` | `SettingsView.swift:321-345, 414-430` |
| 系统侧：文字大小 | 12 档 | `.large` | 见 §1.2.1 / §4.1 | `RootView.swift:17,65` |
| 系统侧：减弱动态效果 | — | — | 见 §3.4（**仅 2 处**接入） | `HomeView.swift:10`、`Components.swift:666` |
| 系统侧：增强对比度 | — | — | 见 §4.2（**仅 `diaryCard`**） | `Components.swift:136` |
| 系统侧：Liquid Glass 色调 | — | — | **不受影响**：App 自己的品牌色是刻意固定的 | `docs/animation-and-accessibility.md` §2.5 |

**未接入的无障碍能力**：`differentiateWithoutColor`（全仓库零引用）、
`accessibilityVoiceOverEnabled`（零引用）、Reduce Transparency（零引用）、
Button Shapes（零引用）、`accessibilityHidden` 也未使用。

---

## 5. 与 Material 3 的映射建议

### 5.1 颜色角色映射

| iOS 令牌 | Light | Dark | M3 建议角色 | 说明 / 风险 |
|---|---|---|---|---|
| `bg` | `#FAF9FD` | `#101318` | **`surface`**（或 `surfaceContainerLowest`） | 语义就是"页面底"。注意它有极淡的紫调，不是纯中性灰 |
| `Color(.secondarySystemGroupedBackground)`（卡片） | 系统色 | 系统色 | **`surfaceContainerLow`** （浅色 ≈ `#FFFFFF`/`#F7F2FA`；深色 ≈ `#1D1B20` 一档） | ⚠️ **没有 Asset 值**，是平台动态色。Android 需按 `surface` + tonal elevation 自行推一档；不要用纯 `surface`，否则卡片与背景分不开 |
| `onSurface` | `#191C20` | `#E1E2E8` | **`onSurface`** | 1:1 |
| `onSurfaceVariant` | `#44474F` | `#C5C6D0` | **`onSurfaceVariant`** | 1:1 |
| `outlineVariant` | `#C5C6D0` | `#44474F` | **`outlineVariant`** | 1:1。⚠️ 与上一行**明暗互换**，M3 里两者也恰好是这个关系，可以放心 |
| `primary`（`Theme.seed`） | `#2563EB` | `#2563EB`（**不变**） | **`primary`** | ⚠️ **最大冲突**：M3 的 `primary` 是 tonal palette 的 tone-40/80，随 seed 变化；这里品牌蓝**明暗共用一值**，且**刻意不跟随系统 Liquid Glass 色调**。若走完整 Material You 动态取色，`primary` 会被重算 → **建议把品牌蓝固定为 `primary`，只在 secondary/tertiary/surface 上用动态色**，或直接用静态 M3 配色 |
| `primaryContainer` | `#DBEAFE` | `#1E3A8A` | **`primaryContainer`** | 1:1，连明暗取值都符合 M3 的 tone-90/tone-30 直觉 |
| `onPrimary` | `#FFFFFF` | `#191C20` | **`onPrimary`** | 1:1 |
| `quoteBg` | `#E4EAF9` | `#2B3344` | **`surfaceContainerHigh`**（或 `secondaryContainer` 的低饱和近似） | ⚠️ 无干净等价。M3 没有"引用块底色"角色；`surfaceContainerHighest` 太灰、`secondaryContainer` 太有色。建议自定义 `quoteContainer` |
| `glassBorder` | `#FFFFFF` @54.9% | `#FFFFFF` @20% | **`outline`（低 alpha 变体）/ 自定义 glass token** | ⚠️ **无等价**。M3 没有玻璃层概念 |
| `glassDim` | `#FFFFFF` @45.1% | `#262B36` @50.2% | **自定义 glass token** | ⚠️ 同上 |
| `blobA/B/C` | 见 §1.1 | 见 §1.1 | **无对应** | ⚠️ **M3 没有装饰性背景色球**。建议保留为自定义 token，用 `Canvas`/`Modifier.blur` 实现（Compose: `Modifier.blur(60.dp)` + `drawBehind`） |
| `flowLight` | `#FFFFFF` @80% | `#FFFFFF` @34.9% | **无对应** | ⚠️ 流光效果无 M3 等价物；且**当前零实例化**，Android 可暂不实现 |
| `shadowColor` | `#000` @7.8% | `#000` @14.9% | **`shadow`** | ⚠️ **理念冲突**：M3 主张用 tonal elevation（表面提亮）而非投影表达层级。iOS 这里是 **阴影 + hairline 描边** 组合。建议 Android 保留阴影（`Modifier.shadow`）以维持 iOS 观感，但把 alpha 换算过来（见 §1.5 的 per-site 系数） |
| `AccentColor` | `#2563EB` | 同值 | `primary`（不必单独设） | 与 `Theme.seed` 同值，冗余 |

**派生色（`Theme` 内计算）映射**：

| iOS | 值 | M3 建议 |
|---|---|---|
| `glowColor()` | `primary` @15% | `primary.copy(alpha = 0.15f)` —— 自定义，无 M3 角色 |
| `flowLightColor()` | `primary` @40% | 同上，无等价 |
| `flowMaskColor()` | `primary` @5% | 同上，无等价 |
| `onSurface().opacity(0.5)` | 非本月日 / 空态图标 | M3 无标准；建议 `onSurface.copy(alpha=0.5f)` 而非 `onSurfaceVariant`（保留"淡化"而非"换色"语义） |
| `onSurfaceVariant().opacity(0.6)` | 周末星期名 | `onSurfaceVariant.copy(alpha=0.6f)` |
| `Color.black.opacity(0.25)` | busy 遮罩 | **`scrim`**（M3 标准 scrim 是黑色，alpha 由组件决定；M3 常用 32%） |

### 5.2 字体角色映射

| iOS 设计字号 | Apple 样式 | M3 type role | M3 默认 size/lineHeight | 差异 |
|---|---|---|---|---|
| **32** `display` | largeTitle | **`displaySmall`**（36sp）或 `headlineLarge`（32sp） | 32/40 | ✅ `headlineLarge` 尺寸完全吻合；但 M3 `headlineLarge` 字重是 W400，iOS 这里用 `.medium` |
| **28**（编辑器 title） | Title 1 | **`headlineMedium`** | 28/36 | ✅ **精确匹配**（两端已确认，见 `docs/design-system.md` §3.1） |
| **24** `pageTitle` | title2 | **`headlineSmall`** | 24/32 | ✅ 精确匹配 |
| **22**（编辑器 heading） | Title 2 | **`titleLarge`** | 22/28 | ✅ **精确匹配** |
| **20** `cardTitle` | title3 | **`titleLarge`**（22）偏低 或自定义 20 | 22/28 | ⚠️ M3 在 20sp 处有空洞（titleLarge=22、titleMedium=16）。建议自定义 20sp token |
| **18** `headerTitle`/`statValue` | body | **`titleMedium`**（16）偏高 或自定义 18 | 16/24 | ⚠️ 同样有空洞 |
| **17**（编辑器 body） | Body | **`bodyLarge`** | 16/24 | ⚠️ **1sp 差**：iOS 17 → M3 16。**这是有意接受的差异**（`docs/design-system.md` §3.1 已确认两端阅读排版有意不同），**不要**把字号写进存储来"对齐" |
| **17** `sheetTitle` | body | **`titleMedium`**（16/24） | 16/24 | ⚠️ 同上 |
| **15** `rowTitle`/`rowValue` | subheadline | **`bodyMedium`**（14/20） | 14/20 | ⚠️ 1sp 差 |
| **15**（编辑器 quote） | Subheadline | **`bodyMedium`** | 14/20 | ⚠️ 1sp 差，已确认接受 |
| **14** `chip` | footnote | **`labelLarge`**（14/20） | 14/20 | ✅ 精确匹配（chip 用 label role 也更符合 M3 语义） |
| **13** `rowSub`/`sectionTitle`/`meta` | footnote | **`bodySmall`**（12/16）或自定义 13 | 12/16 | ⚠️ 1sp 差；`labelMedium` 是 12/16 |
| **12** `badge`/`caption` | caption1 | **`labelMedium`**（12/16） | 12/16 | ✅ 精确匹配（badge 用 label role 正确） |
| **11**（日历农历） | caption2 | **`labelSmall`**（11/16） | 11/16 | ✅ 精确匹配 |

**字重映射**：iOS `.regular` → M3 `Normal`；`.medium` → `Medium`；`.semibold` → `SemiBold`；`.bold` → `Bold`。
M3 各 type role 自带默认字重（display/headline **W400**、title **W500**、body/label **W400**），
**iOS 侧的 `.medium` 标题需要显式覆盖**，否则标题会偏细。

**行高**：iOS 界面文字**不设显式行高**，用平台默认（≈1.19–1.21×字号）；
M3 各 role 的 lineHeight/字号比 ≈ 1.33–1.5，**明显更松**。
→ 若追求 iOS 观感，Android 侧应把 `lineHeightStyle`/`lineHeight` 收到 ≈1.2×；
若追求 M3 规范则可保持默认。**这是个必须显式决策的点。**

**Dynamic Type → Android 字体缩放**：

| iOS 机制 | Android 等价 | 注意 |
|---|---|---|
| 12 档 `DynamicTypeSize` | `fontScale`（系统设置"字体大小"，通常 0.85–2.0） | 档位不是线性对应 |
| **每样式不同上限**（1.35–1.75） | **自定义**：`min(rawScale, ceiling[role])` | ⚠️ **M3 无等价**。Android 默认对所有 sp 线性放大，会复现 iOS 已经修掉的"层级倒挂"问题。**必须移植 `ceiling` 表** |
| 日历半速（`1 + (m−1)×0.5`） | 自定义：日历文字用 `fontSize = design * (1 + (scale−1)*0.5)`，并**绕过 `sp`**（用 `dp` 或自行缩放），否则会被系统再放大一次 | ⚠️ **M3 无等价，必须手写** |
| 默认档 `.large` 返回 1.0 | `fontScale == 1.0` 时不动 | 保证默认外观一致 |
| `.minimumScaleFactor` 单行压缩 | Compose `Text(maxLines=1)` 会**省略号截断**，不会自动缩字。需用 `BasicText` + `TextMeasurer` 自行缩字，或接受截断 | ⚠️ **行为差异明显**：iOS 宁可轻微缩小也不截断 |
| `minHeight` 替代固定高度 | 用 `Modifier.heightIn(min = ...)`，不要 `height(...)` | 直接对应 |

### 5.3 形状（Shape）映射

| iOS 令牌 | 值 | M3 shape token | 说明 |
|---|---|---|---|
| `Radius.card` | 20 | **`shapeCornerLarge`（16dp）** 或 `largeIncreased`（20dp，M3 Expressive） | ✅ Expressive 的 `largeIncreased = 20dp` 精确匹配。经典 M3 的 `large = 16dp` 偏小 |
| `Radius.panel` | 26 | **`extraLarge`（28dp）** 或 `extraLargeIncreased`（32） | ⚠️ M3 在 26dp 无 token。建议自定义 |
| `Radius.field` | 14 | **`medium`（12dp）** 或自定义 14 | ⚠️ 2dp 差 |
| `Radius.image` | 14 | **`medium`（12dp）** 或自定义 14 | 同上 |
| `Radius.badge` | 12 | **`medium`（12dp）** | ✅ 精确匹配 |
| `Radius.bar` | 4 | **`extraSmall`（4dp）** | ✅ 精确匹配 |
| `Radius.hairline` | 1 | 自定义（或 `RoundedCornerShape(1.dp)`） | M3 无 |
| **胶囊**（按钮/chip/搜索框） | `Capsule()` | **`CircleShape` / `RoundedCornerShape(50%)`** | M3 的 `Button` 已经默认全圆角，chip 用 `FilterChip` |
| `Radius.concentric(outer:inset:)` | `max(0, outer − inset)` | **无 M3 等价** | ⚠️ **M3 没有"同心圆角"规则**。Material 的嵌套容器通常用**同一个 shape token**（M3 规范示意里内外同值）。若要复刻 iOS 观感，需手写 `max(0, outer − inset)` 并在设计系统里强制 |

**已废弃的圆角**：改造前项目有 10 个互不相干的圆角（1/4/8/12/16/18/20/22/26/28），
相邻卡片可能差 2pt 却毫无理由。Android 侧**不要**引入新圆角值。

### 5.4 动效（Motion）映射

| iOS | 时长/曲线 | M3 motion token | 说明 |
|---|---|---|---|
| `diaryQuick` | `.easeOut(0.22)` | **`MotionTokens.DurationShort2`（200ms）+ `EasingEmphasizedDecelerate`** | ⚠️ M3 强调曲线是 cubic-bezier(0.05, 0.7, 0.1, 1.0)，比 easeOut 起步更快 |
| `diaryStandard` | `.easeInOut(0.3)` | **`DurationMedium2`（300ms）+ `EasingStandard`**（cubic-bezier(0.2, 0, 0, 1)） | 时长精确匹配；曲线 M3 更"material" |
| `morphSlideAnimation` | `timingCurve(0.2, 0.75, 0.3, 1.0, 0.6)` | **`DurationLong2`（500ms）～ 自定义 600ms + `EasingEmphasized`** | ⚠️ **M3 无 600ms token**（`DurationLong4 = 600ms` 在 Expressive 中存在）。曲线 `(0.2,0.75,0.3,1.0)` **无 M3 等价**，建议原样移植 |
| `reducedMorphAnimation` | `.linear(0.18)` | **`DurationShort3`（150ms）+ `LinearEasing`** | 对应 Android "移除动画"无障碍设置（`Settings.Global.TRANSITION_ANIMATION_SCALE == 0` / `ValueAnimator.areAnimatorsEnabled() == false`） |
| `.snappy(0.3)` / `.snappy(0.25)` | 苹果弹簧 | **`MotionScheme.standard().spatial()`** 或 `spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)` | ⚠️ Compose 弹簧参数需实测调 |
| `sensoryFeedback(.warning)` | — | **`HapticFeedbackType.LongPress` + `View.performHapticFeedback`** 或 `Vibrator`（`VibrationEffect.createPredefined(EFFECT_CLICK)`） | ⚠️ Android 无"warning"语义触感 |
| `UIImpactFeedbackGenerator(.light)` | — | **`HapticFeedbackType.TextHandleMove` / `LongPress`**，或 `VibrationEffect.EFFECT_TICK`（API 29+） | ⚠️ **Android 触感**：`HapticFeedbackType` 只有 `LongPress` / `TextHandleMove` 等少数几种，没有"light impact"的精确对应。建议统一用 `EFFECT_TICK` 或 `EFFECT_CLICK` |
| 玻璃按压缩放（`.buttonStyle(.glass)` 自带） | — | **`Modifier.scale` + `InteractionSource`**，`MotionTokens` | ⚠️ M3 的 ripple 与 iOS 的"玻璃按压缩放"观感完全不同。**必须显式决策**：保留 ripple（更 M3）还是复刻缩放（更像 iOS） |

### 5.5 玻璃 / 阴影 / 层级映射

| iOS 机制 | M3 建议 | 风险 |
|---|---|---|
| Liquid Glass（`.glassEffect`, `.buttonStyle(.glass)`） | **无等价**。可选：`Surface` + `tonalElevation`，或 `Modifier.blur` 自绘，或 Haze 类库 | ⚠️ **最大观感差异点**。M3 的层级语言是 **tonal elevation（表面按高度提亮/染色）**，玻璃是 **折射 + 高光 + 背景透出**。玻璃出现在 11 处（见 §1.7B），全部是"浮在内容之上的控件层" |
| `diaryCard`（不透明底 + hairline + 阴影） | `Card` / `Surface(shape, color = surfaceContainerLow, border = BorderStroke(0.5.dp, outlineVariant.copy(0.45f)))` + `Modifier.shadow(8.dp)` | ✅ 这套可以直接映射；**关键是 M3 `Card` 默认无边框、无阴影（用 tonal elevation），需要显式加**，否则卡片轮廓会消失 |
| **内容区不用玻璃 / 控件层才用玻璃** | 建议保留这条分工：内容 = `Surface`/`Card`；浮层控件 = 玻璃或 `Surface(tonalElevation)` | 这是全局一致性规则，不是单个组件的选择 |
| 增强对比度描边（0.5pt/45% → 1.5pt/90% + 外描边） | 无 M3 等价；用 `LocalAccessibilityManager` / `Settings.Global.HIGH_TEXT_CONTRAST` 判断，自行加粗边框 | ⚠️ Android 没有统一的"增强对比度"开关回调；需自行读系统设置 |
| `shadowColor` per-site alpha 系数（0.14/0.22） | `Modifier.shadow(elevation, shape, ambientColor, spotColor)`；Compose 的阴影 alpha 由 elevation 与平台绘制决定，**不能直接指定 alpha** | ⚠️ **Compose 的 `shadow` 不接 alpha**。要精确复刻需自绘（`drawBehind` + `Paint.setShadowLayer`）或用 `graphicsLayer` + 自定义 |

### 5.6 无干净 M3 等价物的清单（重要）

1. **Liquid Glass 全部 11 个使用点**（§1.7B）—— M3 层级语言完全不同。
2. **装饰性背景色球 `blobA/B/C`**（尺寸/模糊/位置见 §1.7A）—— M3 无背景装饰概念。
3. **`FlowLightOverlay` 流光** —— 无等价；且当前零实例化。
4. **`Radius.concentric(outer:inset:)`** —— M3 规范里嵌套容器用同一 shape token。
5. **每样式 Dynamic Type 上限 `ceiling`（1.35–1.75）** —— Android `fontScale` 是全局线性。
6. **日历"半速"缩放** —— 必须绕过 sp 自行缩放。
7. **`minimumScaleFactor` 单行压缩** —— Compose `Text` 只有省略/裁剪。
8. **`shadowColor` 的 per-site alpha 系数** —— Compose `shadow` 不接 alpha。
9. **增强对比度的三段式描边** —— 无系统回调。
10. **`sensoryFeedback(.warning)`** —— Android 无不带警告语义的触感常量。
11. **`UIImpactFeedbackGenerator(.light)` 的力度/时长** —— `HapticFeedbackType` 无对应粒度。
12. **年份入口的整屏 morph（年 ↔ 月 ↔ 周三态）** —— Compose 需要 `Animatable` + `Canvas` 手写插值；
    且 iOS 的 morph 有两处**必须共用同一矩形/度量**的强约束（`CalendarLayout.miniTitleRect`
    `CalendarLayout.swift:233-253`、`miniMetrics` `:255-267`），否则收尾会出现 1/3pt 的跳变。

---

## 6. 数值速查表

> 路径均相对 iOS 仓库根 `/Users/cov/JustADiary`。

### 6.1 圆角 / 间距 / 字体

| 常量 | 值 | `file:line` | 含义 |
|---|---|---|---|
| `Radius.card` | 20 | `Views/Components/DesignSystem.swift:15` | 内容卡片、列表行、hero 面板 |
| `Radius.panel` | 26 | `DesignSystem.swift:17` | 悬浮控件面（格式栏、sheet、popover） |
| `Radius.field` | 14 | `DesignSystem.swift:20` | 输入框、卡内次级面 |
| `Radius.image` | 14 | `DesignSystem.swift:22` | 卡内联图片 |
| `Radius.badge` | 12 | `DesignSystem.swift:24` | 图标徽章、小色块 |
| `Radius.bar` | 4 | `DesignSystem.swift:26` | 图表柱、粗描边 |
| `Radius.hairline` | 1 | `DesignSystem.swift:28` | 2pt 标记线的圆角 |
| `Radius.concentric(outer:inset:)` | `max(0, outer−inset)` | `DesignSystem.swift:31-33` | 嵌套圆角规则 |
| `Spacing.card` | 12 | `DesignSystem.swift:41` | 卡片内边距 / 卡间距 |
| `Spacing.rowIcon` | 12 | `DesignSystem.swift:43` | 图标徽章与文字间距 |
| `Spacing.hitTarget` | 44 | `DesignSystem.swift:45` | Apple 最小点击目标 |
| `TypeSize.display` | 32 | `DesignSystem.swift:57` | 首页日历月/年大标题 |
| `TypeSize.pageTitle` | 24 | `DesignSystem.swift:59` | 页面标题 |
| `TypeSize.sheetTitle` | 17 | `DesignSystem.swift:61` | Sheet 标题 |
| `TypeSize.cardTitle` | 20 | `DesignSystem.swift:63` | 卡片头 |
| `TypeSize.headerTitle` | 18 | `DesignSystem.swift:65` | 紧凑头部胶囊标题 |
| `TypeSize.statValue` | 18 | `DesignSystem.swift:67` | 足迹统计数字 |
| `TypeSize.rowTitle` | 15 | `DesignSystem.swift:69` | 列表/设置行主文字 |
| `TypeSize.rowSub` | 13 | `DesignSystem.swift:73` | 设置行第二行 |
| `TypeSize.rowValue` | 15 | `DesignSystem.swift:75` | 设置行尾值 |
| `TypeSize.sectionTitle` | 13 | `DesignSystem.swift:77` | 卡内小节标题 |
| `TypeSize.chip` | 14 | `DesignSystem.swift:79` | 胶囊/chip 标签 |
| `TypeSize.badge` | 12 | `DesignSystem.swift:81` | 计数徽章 |
| `TypeSize.meta` | 13 | `DesignSystem.swift:83` | 时间戳 / 地点行 |
| `TypeSize.caption` | 12 | `DesignSystem.swift:85` | 最次级说明 |
| `ceiling(.largeTitle)` | 1.35 | `DesignSystem.swift:126` | 32pt 的放大上限 |
| `ceiling(.title1)` | 1.40 | `DesignSystem.swift:127` | 28pt |
| `ceiling(.title2/.title3)` | 1.45 | `DesignSystem.swift:128` | 24/22/20/18pt |
| `ceiling(.body)` | 1.50 | `DesignSystem.swift:129` | 17pt |
| `ceiling(.callout)` | 1.55 | `DesignSystem.swift:130` | 16pt |
| `ceiling(.subheadline)` | 1.60 | `DesignSystem.swift:131` | 15pt |
| `ceiling(.footnote)` | 1.65 | `DesignSystem.swift:132` | 13/14pt |
| `ceiling(.caption1)` | 1.70 | `DesignSystem.swift:133` | 12pt |
| `ceiling(default)` | 1.75 | `DesignSystem.swift:134` | 其余（11pt） |
| `textStyle(for:)` 分档 | 34/28/22/20/17/16/15/13/12 | `DesignSystem.swift:143-156` | 设计字号 → Apple 样式 |
| `multiplier` 短路 | `.large` → 1.0 | `DesignSystem.swift:191` | 默认档不缩放 |
| `calendarMultiplier` | `1 + (m₁₅−1)×0.5` | `DesignSystem.swift:217-219` | 日历半速 |
| `EditorBlockStyle.designSize` | 28 / 22 / 17 / 15 | `Views/Diary/RichTextEngine.swift:26-33` | 编辑器语义梯级 |
| `.lineSpacing` | quote `0.5×`，其余 `0.13×` | `RichTextEngine.swift:40-42` | 段内换行行距 |
| `.paragraphSpacing` | title/heading `0.15×`，quote `0.4×`，body `0` | `RichTextEngine.swift:52-59` | 段后距 |
| `.paragraphSpacingBefore` | title/heading `0.35×`，quote `0.4×`，body `0` | `RichTextEngine.swift:64-71` | 段前距 |
| `EditorDesignSize.imageSpacing` | `body × 0.6` = 10.2 | `RichTextEngine.swift:122` | 图片上下留白 |
| `blockStyle(for:)` 阈值 | `(28+22)/2=25`, `(22+17)/2=19.5` | `RichTextEngine.swift:131-135` | 仅导入文档用的兜底 |
| 图片/待办图标 | 13pt × `multiplier(17)`；bounds 15×15×scale，y=−2 | `RichTextEngine.swift:1043-1047` | 待办附件符号 |

### 6.2 自适应版面

| 常量 | 值 | `file:line` | 含义 |
|---|---|---|---|
| `minMasterWidth` | 260 | `AdaptiveLayout.swift:45` | 主栏（月历）最窄 |
| `maxMasterWidth` | 440 | `AdaptiveLayout.swift:47` | 主栏最宽 |
| `minDetailWidth` | 240 | `AdaptiveLayout.swift:49` | 详情栏最窄 |
| `splitGutter` | 16 | `AdaptiveLayout.swift:51` | 两栏空隙 |
| `splitSeparatorWidth` | 16.5 | `AdaptiveLayout.swift:53` | 空隙 + 0.5pt 分隔线 |
| `splitTopPadding` | 8 | `AdaptiveLayout.swift:55` | 分栏区距页顶 |
| `minSplitContentWidth` | **548.5** | `AdaptiveLayout.swift:62-63` | 分栏阈值 |
| `pagePadding` | 16 | `AdaptiveLayout.swift:144` | 页面水平内边距（不含安全区） |
| `contentColumn` 默认上限 | 660 | `AdaptiveLayout.swift:132` | 正文列宽上限 |
| `contentColumn` 下限 | 240 | `AdaptiveLayout.swift:133` | 正文列宽下限 |
| `tabBarHeightPortrait` | 83 | `AdaptiveLayout.swift:113` | 竖屏浮条高 |
| `tabBarHeightLandscape` | 64 | `AdaptiveLayout.swift:114` | 横屏浮条高 |
| `defaultValue` size | 390×844 | `AdaptiveLayout.swift:151` | 几何兜底 |
| `splitPaneHeight` 下限 | 220 | `AdaptiveLayout.swift:101` | 分栏两栏高度下限 |
| `splitColumns` 主栏比例 | `round(cw × 0.46)` | `AdaptiveLayout.swift:91` | 主栏首选取值 |
| `Screen` 兜底尺寸 | 390×844 | `Components.swift:31` | 无场景时 |
| 键盘动画默认时长 | 0.25 | `DiaryPageView.swift:229` | 系统未给时 |
| 顶栏让位 padding | `topBarHeight + 10` | `DiaryPageView.swift:278` | 内容顶部留白 |
| 键盘占位 | `keyboardHeight + 160` | `DiaryPageView.swift:265` | 内容末尾补高 |
| 格式栏键盘间距 | 键盘上方 **8** | `DiaryPageView.swift:49` | 弹起时 |
| 格式栏安全区间距 | `safeAreaBottom + 12` | `DiaryPageView.swift:49` | 收起时 |
| 光标上界 / 下界余量 | 8 / 8 | `DiaryPageView.swift:206-207` | `revealCaret` |

### 6.3 日历

| 常量 | 值 | `file:line` | 含义 |
|---|---|---|---|
| `lunarRowHeight` | 44 | `Views/Home/CalendarLayout.swift:21` | 带农历行的最小行高 |
| `dayRowHeight` | 38 | `CalendarLayout.swift:29` | 无农历行的最小行高 |
| 农历档判定阈值 | `lunarRowHeight + 16` = 60 | `CalendarLayout.swift:65` | 每行 ≥60 才画农历 |
| `morphDuration` | 0.6（DEBUG 3.0） | `CalendarLayout.swift:133-139` | morph 时长 |
| `reducedMorphAnimation` | `.linear(0.18)` | `CalendarLayout.swift:147` | 减弱动态效果 |
| `morphSlideAnimation` | `timingCurve(0.2,0.75,0.3,1.0, 0.6)` | `CalendarLayout.swift:151-153` | 共用曲线 |
| `weekdayHeaderH` | 30 | `CalendarLayout.swift:155` | 竖屏星期栏 |
| `bigTitleH` | 72 | `CalendarLayout.swift:156` | 竖屏大标题槽 |
| `compactMonthTitleH` | 34 | `CalendarLayout.swift:161` | 横屏标题槽 |
| `compactWeekdayHeaderH` | 26 | `CalendarLayout.swift:162` | 横屏星期栏 |
| `weekStripH` | 68 | `CalendarLayout.swift:163` | 周条高 |
| `dayTitleH` | 40 | `CalendarLayout.swift:164` | 竖屏日期标题行 |
| `yearTitleH` | 62 | `CalendarLayout.swift:166` | 年历标题高 |
| `yearPad` | 16 | `CalendarLayout.swift:167` | 年历页边距 |
| `yearSpacing` | 6 | `CalendarLayout.swift:169` | 迷你月间距 |
| `weekdayFontSize` | `min(max(cellW×0.26,11),14)` | `CalendarLayout.swift:173` | 星期名字号 |
| `monthCellH` | `(areaH − 72 − 30)/6` | `CalendarLayout.swift:175-177` | 竖屏格高 |
| `miniPad` / `miniTitleH` | 6 / 17 | `CalendarLayout.swift:222-223` | 迷你月内缩 / 标题高 |
| `miniDayFont` | `min(14, max(9, min(cellW×0.95, cellH×0.5)))` | `CalendarLayout.swift:206-208` | 迷你日期字号 |
| `miniMetrics` lunarFont / alpha | 6 / 0 | `CalendarLayout.swift:264-265` | 迷你月不画农历 |
| `monthCellW` | `width/7` | `CalendarLayout.swift:171` | 单格宽 |
| `displayedWeeks` 去尾 | while 循环删末尾整周非本月的行 | `CalendarLayout.swift:352-365` | 密度判定的行数来源 |
| `allMonthKeys` / `allYears` | 1900…2100 | `CalendarLayout.swift:289-297` | 翻页键域 |
| `dayFont(for:)` | `min(20, max(13, cellH×0.34))` | `Views/Home/CalendarPaneViews.swift:101-103` | 日号字号 |
| `lunarFont(for:)` | `min(11, max(9, cellH×0.18))` | `CalendarPaneViews.swift:105-107` | 农历字号 |
| 密网格 `dayFont`/`lunarFont` | 20 / 11 | `CalendarLayout.swift:179-186` | **仅竖屏**月历度量 |
| 未来日期淡化 | `×0.35` | `Views/Home/CalendarGrids.swift:69` | 未来日整格 alpha |
| 非本月淡化 | `×adjacentAlpha`（月历内 `showAdjacent=false` 直接跳过） | `CalendarGrids.swift:70-72, 211-214` | — |
| 选中圆直径 | `min(cellW−3, contentH+14, cellH−3)` | `CalendarGrids.swift:84` | contentH = dayFont+4+农历行高 |
| 今天圆环线宽 | 1.5 | `CalendarGrids.swift:91` | — |
| 非本月数字色 | `onSurface` @50% | `CalendarGrids.swift:111` | — |
| 农历色 | 数字色 @75%（选中 95%） | `CalendarGrids.swift:122` | — |
| 有日记下划线 | `min(20, cellW×0.5)` × 2pt，圆角 1 | `CalendarGrids.swift:128-131` | y = numY + dayFont/2 + 2 |
| 行分隔线 | `outlineVariant` @35% × dividerAlpha，1pt | `CalendarGrids.swift:208` | 画在 `rowY − 0.5` |
| 周末星期名 | `onSurfaceVariant` @60% | `CalendarGrids.swift:16` | 索引 `i>=5`（周一起）或 `i==0‖i==6`（周日起） |
| 文字缓存上限 | 24000 | `CalendarGrids.swift:34` | ResolvedText 高水位 |
| 字号量化 | `(size×2).rounded()/2` | `CalendarGrids.swift:139` | 半磅量化做 cache key |
| 月标题字体 | `TypeSize.pageTitle`(24) / 竖屏 `TypeSize.display`(32) | `HomeView.swift:371`、`CalendarPaneViews.swift:31` | 横屏 / 竖屏 |

### 6.4 动效 / 手势 / 组件几何

| 常量 | 值 | `file:line` | 含义 |
|---|---|---|---|
| `Animation.diaryQuick` | `.easeOut(0.22)` | `Views/Components/Components.swift:7` | 快速状态切换 |
| `Animation.diaryStandard` | `.easeInOut(0.3)` | `Components.swift:8` | 标准状态切换 |
| `PageHeader` minHeight | 52 | `Components.swift:187` | 页头最小高 |
| 主按钮 padding | h `20`（compact `14`）/ v `12`（compact `7`）；HStack 间距 6 | `Components.swift:206,217-218` | — |
| 次按钮 padding | h 20 / v 12 | `Components.swift:267-268` | — |
| `GlassIconButton` 图标 | `size>36 ? 16 : 14`，semibold | `Components.swift:300` | 默认 `size = 32` |
| `PressableGlassIcon` | `size = 44`，label `max(28, size−8)`，图标 17 medium | `Components.swift:314,323,331` | `.glass` 在标签外补 4pt/边 |
| `GlassChip` padding | h 12 / v 7；字号 `TypeSize.chip` | `Components.swift:381-382` | — |
| `InfoCapsule` padding | h 12 / v 7；`.medium` 字重 | `Components.swift:401-406` | — |
| `GlassCountBadge` padding | h 10 / v 5；图标 10 | `Components.swift:424,432-433` | — |
| `GlassActionChip` padding | h 10 / v 6；图标 11 | `Components.swift:454,462-463` | — |
| `GlassEmptyState` | VStack 间距 12；图标 26；上留白 56 | `Components.swift:481-494` | 图标 `onSurfaceVariant` @50% |
| `GlassSearchField` | HStack 间距 8；h padding 14；`minHeight 46` | `Components.swift:567,589-590` | 玻璃胶囊 |
| `TabBarClearance` | `base × multiplier(15)`，默认 base 120 | `Components.swift:606,612` | 编辑页 base 140 |
| `RowDivider.textInset` | `12 + 38 + 12 = 62` | `Components.swift:621` | 对齐行文字而非图标 |
| 行分隔线 | `outlineVariant` @50% | `Components.swift:627` | — |
| `GlassIconBadge` | 默认 38，`max(38, scaled(16)+12)`；圆角 12；描边 1pt `glassBorder` | `Components.swift:634-644,653` | 必须保持正方形 |
| 卡片阴影（静止/交互） | 8/12 半径，2/4 偏移，alpha 系数 0.14/0.22 | `Components.swift:155-157` | — |
| 卡片描边（常规/增强） | 0.5pt @45% / 1.5pt @90% + 外 1pt @55% | `Components.swift:148-152` | — |
| 色球（默认档） | 280/240/200 直径，60/60/55 模糊 | `Components.swift:91-96` | — |
| 色球（dense 档） | 300/250/210 直径，70/65/60 模糊 | `Components.swift:84-89` | 仅编辑页 |
| `FlowLightOverlay` 循环 | 4.0s；条高 3pt；上 y=6，下 y=h−6；下沿 alpha ×0.7；10s 后暂停 | `Components.swift:672-702` | 零实例化 |
| `DragPagePager` 最小拖动 | 8pt | `Views/Home/DragPagePager.swift:59` | — |
| `DragPagePager` 翻页阈值 | `±90` 预测 / `±60` 实际 | `DragPagePager.swift:74-75` | — |
| `DragPagePager` 橡皮筋 | `c = pageSize × 0.25` | `DragPagePager.swift:101-104` | — |
| `DragPagePager` 收尾动画 | `.snappy(0.3)` | `DragPagePager.swift:79,94` | — |
| 首页 toast 停留 | 2.5s | `HomeView.swift:309` | 进出 `.snappy(0.25)` |
| 首页 toast 阴影 | 半径 12 / y 4；底部 padding 24 | `HomeView.swift:60-61` | — |
| 日历区可用高（竖屏） | `max(320, h − 64)` | `HomeView.swift:417` | — |
| 分栏右栏标题行高 | 44 | `HomeView.swift:390` | — |
| 分栏右栏正文列 | 560 | `HomeView.swift:389` | — |
| 日历转横屏收态 | `settleToMonth()` 无动画 | `HomeView.swift:67-94` | — |
| 足迹横屏 blockH | `max(140, min(190, screenH − 262))` | `Views/Footprint/FootprintView.swift:48` | — |
| 足迹横屏图表高 | `max(72, blockH − 58)` | `FootprintView.swift:53` | — |
| 足迹清单栏宽 | `min(320, max(240, contentWidth × 0.4))` | `FootprintView.swift:160-162` | — |
| 足迹统计卡 padding | v 14 | `FootprintView.swift:101` | 5 列等分 |
| 足迹树行 | leading `depth×16`；v 9；图标宽 16 | `FootprintView.swift:309-310,292` | — |
| 搜索筛选面板 | padding 10；两组间距 6 / 16（横屏）；横滑内 v padding 6 | `SearchView.swift:162,167,173,192,210` | — |
| 搜索结果行 | padding 14；摘要 `lineLimit(2)` | `SearchView.swift:334,344` | — |
| 搜索头部收起阈值 | 已收起时 `y > 8`；未收起时 `y > 24` 且内容超出 `extra(=控件高,默认170) + 24` | `SearchView.swift:275-280` | — |
| 设置行 | v padding h12；`minHeight 56`；图标与文字间距 12 | `SettingsView.swift:196-197,226` | — |
| 设置小节标题 | h padding 16；top 12 | `SettingsView.swift:119-121` | — |
| busy 遮罩 | `black @0.25`；卡片 padding h32 / v26 | `SettingsView.swift:79,89-90` | — |
| 分享面板横屏动作区宽 | `min(430, w × 0.55)` | `ShareSheetView.swift:34` | — |
| 分享面板动作区高（竖屏） | `min(max(340, h×0.48), max(280, h−200))` | `ShareSheetView.swift:141-143` | — |
| 分享预览 | `maxWidth 720`；padding h20 / v18；阴影 18/8 | `ShareSheetView.swift:79,86-87,85` | — |
| 分享面板 sheet | `.presentationDetents([.large])` + drag indicator | `DiaryPageView.swift:100-101` | — |
| 编辑器顶栏 | h padding 16；top 4；bottom 6；内部 HStack 间距 8；h padding 4 | `DiaryPageView.swift:337-339,351,396` | — |
| 编辑器内容 VStack | 间距 12；顶部 `topBarHeight + 10` | `DiaryPageView.swift:248,278` | — |
| 编辑卡片 | padding 12；内部 VStack 间距 4 / 6 | `DiaryPageView.swift:570-571,596` | — |
| 输入区最小高 | `160 × multiplier(17)`，`.rounded()` | `Views/Diary/RichTextView.swift:26,29` | 默认 160，AX5 ≈240 |
| 格式栏按钮 | `minWidth 34 / minHeight 40`；样式菜单 `minWidth 46`；图标 15 medium | `RichTextView.swift:242-244,280-285` | — |
| 格式栏容器 | h padding 12；v padding 8；按钮间距 4；分隔线高 20 | `RichTextView.swift:311,352,367` | 九个按钮 ~360pt |
| 格式栏面板 | 圆角 26；阴影 14/4 | `RichTextView.swift:377,381` | — |
| 阅读 hero | padding 14；VStack 间距 6 / 4 | `DiaryPageView.swift:443,460,488` | — |
| 日记块卡 | padding 12；VStack 间距 8；内部间距 3 | `DiaryPageView.swift:499,507,543` | — |
| 长按进多选 | `minimumDuration: 0.4` | `DiaryPageView.swift:547` | — |
| 阅读时间胶囊 | h 10 / v 5；字重 bold；色 `primary` on `primaryContainer` | `DiaryPageView.swift:511,576-580` | — |
| 地点胶囊 | h 10 / v 5；图标 `caption`(12) | `DiaryPageView.swift:671,677-678` | — |
| 首页日记列表 | VStack 间距 16；h padding 20；top 18；bottom 96 | `Views/Home/DayContentView.swift:23,44-46,15` | — |
| 日记块卡（首页） | padding 12；VStack 间距 10；信息行间距 4 | `DayContentView.swift:83,86,112` | — |
| 图片上下留白 | 10.2（编辑器 TextKit + 阅读区 padding） | `RichTextEngine.swift:122`、`MediaViews.swift:76` | — |
| 阅读块 `textContainerInset` | top 2 / left 0 / bottom 2 / right 0 | `MediaViews.swift:82` | — |
| 分享长图 | 宽 720；边距 56；卡圆角 26；卡内 padding h30/v28；条目间距 30 | `Services/ImageShareService.swift:24,29,45,43-44,46` | 独立版面 |

---

## 附：文档与代码不一致处一览（改 Android 前必读）

| # | 文档说法 | 代码实际 | 处理 |
|---|---|---|---|
| 1 | `docs/adaptive-layout-plan.md:64` "18 Pro 横屏 左 345 / 右 356.5" | 传**容器宽** 874 → `round(874×0.46)=402`，得 **402 / 423.5** | **以代码为准**（§2.2 表） |
| 2 | 文档 "分栏阈值 `contentWidth >= 700`" | 已是 `>= 548.5` | 以代码为准 |
| 3 | `DesignSystem.swift:118-120` 注释的 AX5 表（32→44.8） | 按当前 `ceiling` 应为 **32→43.2**；其余 24/22/20/12/11 与注释一致 | 以 `ceiling(for:)` 代码为准 |
| 4 | `docs/animation-and-accessibility.md:102` "缩放上限 1.45×" | 已是**每样式不同**（1.35–1.75） | 以 `ceiling(for:)` 为准 |
| 5 | `docs/animation-and-accessibility.md:96` 提到 `\.diaryTypeScale` 环境值 | 已改名为 `\.diaryDynamicTypeSize`，且下发的是 **category** 而非预乘系数 | 以 `DynamicType.swift:17-27` 为准 |
| 6 | `docs/design-system.md:357` "横屏 `contentWidth >= 548.5`" | 正确（与代码一致） | — |
| 7 | `docs/design-system.md:374` "横屏浮条 64pt" | 正确 | — |
| 8 | `CalendarLayout.swift:179-186` 的 `monthMetrics` 写死 dayFont 20 | 只用于**竖屏**；横屏与 `MonthPane` 走 `dayFont(for: cellH)` 推导 | 见 §2.7 |
| 9 | `docs/design-system.md:301` 提到已删 `Haptics.medium` | 正确，现在只有 `tap()` | — |
