# 日记（Diary）功能 UI / 交互实现规格 v3

> **来源**：iOS 仓库 `/Users/cov/JustADiary`（工作树 = commit `0b07050` + 未提交的 `project.pbxproj`）。
> 本文只以 **代码** 为准；旧文档（`docs/editor-format-behaviors.md`、`docs/editor-typography.md`、
> `docs/design-system.md`、`docs/ios-reference/diary.md`）仅用于交叉验证，冲突时以代码为准，并在
> §8 的「与旧文档不一致的地方」列出。
>
> **引用格式**：`文件名:行号`，文件都在 `JustDiary/` 下（`Views/Diary/`、`ViewModels/`、`Services/`、`Models/`、
> `Views/Components/`）。行号对应上面的工作树版本。
>
> **目标读者**：用 Jetpack Compose + Material 3 重写该功能的 Android 工程师。

---

## 0. 术语与数据模型（读后面章节的前置）

| 概念 | 代码 | 说明 |
|---|---|---|
| 日记页 | `DiaryPageView` | 一天的日记，**同时承载阅读态与编辑态**（不是两个页面） |
| 条目 / 片段 | `EditBlock` | 一条带 `start_time_utc` / 地点 / `content_json` 的记录；由长按可多选删除 |
| 内容 | `ContentPart` | 段落级：`style` ∈ `title/heading/body/quote/list/todo/image` |
| 行内样式 | `TextRun` | `{text, bold?, italic?, strike?, underline?}`，**不存字号** |
| 承载格式 | `ContentDocument` | `{"v":2,"parts":[…]}`，`sortedKeys` + `withoutEscapingSlashes` |
| 编辑器 | `RichEditorController` | 包一个 `UITextView`，`NSAttributedString` 为唯一真相 |

`ContentPart` 的字段（`Models/ContentPart.swift:61-115`）：`style`、`text?`、`items?`、`done?`、`src?`、
`runs?`、`w?`、`h?`、`align?`（`align` 只有 `"center"` 或 `nil`，见 `RichTextEngine.swift:1272-1275`）。
规则：

- 段落样式（字号 / 行距 / 段前段后）**不落库**，只存 `style`；载入时按样式重新推导（`ContentPart.swift:117-145`）。
- `w`/`h` **只表达宽高比**，不是布局宽高（`RichTextEngine.swift:1077-1085`、`EditorSpacingTests.swift:337-343`）。
- v1 兼容：裸数组、`type` 键、`h1/h2/p/ul/img` 风格名、run 里的 `size`（读到即丢）——`ContentPart.swift:81-105`、
  `ContentPart.swift:132-140`。
- 空行不入库（`RichTextEngine.swift:1231`）；图片路径规范化为 `images/xxx.jpg`（`ContentPart.swift:209-215`）。

图片文件：`Documents/images/img_<epochMs>.jpg`（`DiaryViewModel.swift:542-557`），插入时按 2048px 降采样 + JPEG 0.85。

---

# 1. 页面结构

## 1.1 呈现方式与生命周期

- 日记页是 **fullScreenCover**（整屏盖住 TabView），由首页日历格 / 搜索结果 / URL scheme
  `justdiary://editor?dayKey=yyyy-MM-dd` / `LaunchIntent` 触发；dayKey 来自 `AppState.editorDayKey`
  （`Views/RootView.swift:86-102`、`App/JustDiaryApp.swift:17-27`、`Services/LaunchIntent.swift:7-10`）。
- cover 关闭时（`onDismiss`）会 `bumpDiaryVersion()`，让首页刷新（`Views/RootView.swift:88-90`）。
- cover 内部**显式重新注入** `\.diaryDynamicTypeSize`、`\.locale`、`\.adaptiveLayout`（`RootView.swift:97-101`）——
  presentation 不继承更内层的环境，这是动态字体在编辑页失效过的根因。
- 打开时 `.task` 执行：`vm.dayKey = dayKey; vm.onDismiss = { dismiss() }; await vm.load()`（`DiaryPageView.swift:58-62`）。
- `load()`（`DiaryViewModel.swift:57-69`）：`actualDayKey = dayKey ?? 今天`；清空 `hits/hitIndex`；
  **`startUtc = 当前毫秒时间戳`**；查询 `diary + blocks`；`isRead = true`。
- 两个外部通知：`.diaryVersionChanged` → 重新 `load()`（**会把 `isRead` 复位为 true、刷新 `startUtc`**，
  `DiaryPageView.swift:66-68`）；`.uiTickChanged` → `refreshSettings()`（`DiaryPageView.swift:69-71`）。

> 兼容性提醒：任何写库动作（保存、删条目、勾选待办）都会广播 `.diaryVersionChanged`，
> 于是整个日记页重新载入。Android 端如果沿用「编辑中页面会被数据库通知打断」的结构，
> 必须保证编辑态不会被这次重载清掉（iOS 现在的行为是：编辑中不会发生这些写操作，
> 只有阅读态勾选待办会触发）。

## 1.2 视图层级（ZStack，底部对齐）

```
ZStack(alignment: .bottom)                 // DiaryPageView.swift:26
├─ BlobBackground(dense: true)             // :27  页面背景（bg 色 + 3 个模糊色球）
├─ content                                 // :28  ScrollViewReader > ScrollView > VStack
├─ topBarOverlay                           // :33  悬浮顶栏（顶对齐，不在 ScrollView 里）
└─ if !vm.isRead { FontToolbar(...) }      // :34-51 格式栏（贴键盘上方）
```

- 整页 `.ignoresSafeArea(edges: .bottom)` + `.ignoresSafeArea(.keyboard, edges: .bottom)`
  （`DiaryPageView.swift:56-57`）：底部安全区与键盘避让**全部由页面自己负责**（顶栏让位靠内容 padding、
  键盘让位靠 `vm.keyboardHeight`），否则会和系统避让叠加出一份空白键盘高度。
- 背景 `BlobBackground(dense: true)`（`Views/Components/Components.swift:76-103`）：`Theme.bg()` 打底 +
  3 个 `Circle` 色球（300/250/210pt，blur 70/65/60，位置 `(-80,200)`、`(w+20,560)`、`(w-40,-30)`，`Components.swift:84-89`）。

## 1.3 悬浮顶栏（floating top bar）

结构与三态（`DiaryPageView.swift:350-397`）：

```
HStack(spacing: 8)                      // :351
├─ 返回  PressableGlassIcon("chevron.left")          // :352-355  a11y=返回/Back
├─ Spacer()                                          // :356
└─ 右侧按状态三选一
```

| 状态 | 右侧按钮（从左到右） | 代码 |
|---|---|---|
| **多选态** `selectMode` | `删除(N)` `GlassPrimaryButton(icon: "trash")` | `:357-362` |
| **阅读态** `isRead` | `magnifyingglass`（active = showSearch）、`square.and.pencil`（**仅当 `canEditToday`**）、`square.and.arrow.up` | `:363-380` |
| **编辑态** `!isRead` | `photo`（插入图片）、`arrow.counterclockwise`（放弃修改）、`checkmark`（active=true，保存） | `:381-394` |

- 按钮是 Liquid Glass 圆形按钮 `PressableGlassIcon`：**视觉直径 44pt**（HIG 最小点击目标），
  `.glass` 按钮样式会在标签外再补 4pt/边，所以标签盒取 `size - 8 = 36`（`Components.swift:311-343`）；
  图标 `diaryFont(17, weight: .medium)` + `.symbolRenderingMode(.hierarchical)`，active 时前景 `Theme.primary()`，
  且 `.symbolEffect(.bounce, value: active)`；点击一律 `Haptics.tap()`。
- 顶栏容器：`topBarOverlay` = 顶栏 + `.padding(.horizontal, 16)` + `.padding(.top, 4)` + `.padding(.bottom, 6)`
  （`DiaryPageView.swift:336-339`），随后：
  - 用 `onGeometryChange` 量自身高度，写进 `topBarHeight`（仅当 `height > 10` 且变化 > 0.5pt，`:340-342`）；
  - `.contentShape(Rectangle())` + `.onTapGesture { vm.dismissKeyboard() }`——**顶栏空白处是可点的收键盘区**
    （`:345-346`）；
  - `.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)` 钉在页面顶部（`:347`）。
- 顶栏**不在 ScrollView 里**（历史上挂在 `safeAreaInset`，键盘弹起时被一起滚出屏幕，返回按钮跑到 y=-51；
  `DiaryPageView.swift:29-32`、`docs/editor-format-behaviors.md:208`）。
- 内容顶端让位：`.padding(.top, topBarHeight + 10)`（`DiaryPageView.swift:278`）——顶栏高度是**实测值**，不写死。

> **注意（与需求描述不一致）**：当前实现**没有底部动作栏**。阅读/编辑/分享/搜索全部在顶栏，
> 编辑态唯一贴底的元素是浮在键盘上方的格式栏。Android 端若按 Material 3 落成 `TopAppBar`
> （阅读态 3 个 action，编辑态 3 个 action，多选态 1 个 destructive action）即可 1:1 对应。

## 1.4 Hero 卡（阅读态日期抬头）

`readHero`（`DiaryPageView.swift:442-492`）：

```
VStack(alignment: .leading, spacing: 6)                     // :443
├─ HStack(spacing: 8)                                       // :444
│  ├─ Text(L10n.formatDayKey(actualDayKey))                 // :445  20pt medium onSurface
│  └─ if canEditToday: "今日" 胶囊                           // :448-458
│       12pt medium 白字，padding h8/v3，Capsule(Theme.primary())，
│       shadow(Theme.glowColor(), radius 6, y 1)
└─ VStack(alignment: .leading, spacing: 4)                  // :460
   ├─ 有内容时：HStack(spacing: 8)                           // :462
   │  ├─ if showsTime: 首条 start_time（13pt, onSurfaceVariant） // :466-469
   │  └─ Text(L10n.fmt("read_hero_segments", blocks.count))  // :470  "N 个片段"
   ├─ if !first.locText.isEmpty: Label(locText, "location.fill") // :474-480
   │    13pt, onSurfaceVariant, lineLimit 2, 左对齐
   └─ 无内容时：Text(read_day_empty) =「这一天没有留下日记。」    // :482-484
.padding(14)                                                 // :488  ← 注意是 14，不是 Spacing.card(12)
.frame(maxWidth: .infinity, alignment: .leading)             // :489
.diaryCard(cornerRadius: Radius.card)                        // :490  r=20
.padding(.bottom, 2)                                         // :491
```

- 顶行日期文案由 `L10n.formatDayKey` → `dayTitle` → `date_day_title` 决定：
  zh `%1$@%2$ld日 %3$@`（例：`9月8日 周一`），en `%3$@, %1$@ %2$ld`（例：`Mon, Sep 8`）
  （`Services/L10n.swift:159-165`、`:194-197`）。
- `canEditToday = (actualDayKey == 今天)`（`DiaryViewModel.swift:51-53`）——非今天的日记**不显示**「今日」胶囊，
  顶栏也**不显示写日记按钮**。
- `showsTime = settings.autoTime`（`DiaryViewModel.swift:49`）：关闭「自动记录时间」时只是**不显示**时间；
  `start_time_utc` 仍照常写入（跨端契约，`Models/AppSettings.swift:4-11`）。

## 1.5 条目卡（每段日记一张卡）

`blockCard(_:index:)`（`DiaryPageView.swift:496-552`）：

```
VStack(alignment: .leading, spacing: 8)                      // :499
├─ HStack(alignment: .top, spacing: 8)                       // :500
│  ├─ 多选态：checkmark.circle.fill / circle，16pt，primary / onSurfaceVariant，padding(.top, 1)  // :501-506
│  ├─ VStack(alignment: .leading, spacing: 3)                // :507
│  │  ├─ if showsTime: 块时间（13pt bold, Theme.primary()）   // :509-513
│  │  └─ if !locText.isEmpty: Label(locText, "location.fill") // :514-520  12pt, lineLimit 2
│  ├─ Spacer()                                               // :522
│  └─ if editingIndex == index: GlassCountBadge("编辑中")      // :523-525  ⚠️ 见 §8 脆弱点
├─ .contentShape(Rectangle()).onTapGesture { handleBlockTap } // :527-530
└─ DiaryPartsView(parts:keyword:onToggleTodo:onImageTap:onTapText:) // :531-541
.padding(Spacing.card)  // 12                                // :543
.diaryCard(cornerRadius: Radius.card)  // r=20               // :544
.accessibilityElement(children: .contain) + id "diary.blockCard"  // :545-546
.onLongPressGesture(minimumDuration: 0.4) { enterSelect(id) } // :547-549
.accessibilityElement(children: .contain) + .isSelected 当被选中    // :550-551
```

- 卡片背景：`Color(.secondarySystemGroupedBackground)` + 描边 `outlineVariant` 0.45（0.5pt，增强对比度时
  0.55/1pt + 1.5pt）+ 阴影 `shadowColor` 0.14 / radius 8 / y 2（`Components.swift:135-160`）。
- 卡片之间：内容 VStack `spacing: 12`（`DiaryPageView.swift:248`）。
- 点击整卡（含正文）→ `handleBlockTap`（`:554-565`）：多选态切换选中（+Haptics）；否则 `enterEditBlock(index)`。

## 1.6 空状态

- **没有独立的空状态视图**（`GlassEmptyState` 未在日记页使用）。空的一天 = Hero 卡 + 元信息行替换为
  「这一天没有留下日记。」（`DiaryPageView.swift:482-484`），顶栏保留「写日记」入口（今天）。
- UI 测试正是靠顶栏的「写日记」按钮判断空日记页（`JustDiaryUITests/ShareSheetUITests.swift:37`、
  `EditorFlowUITests.swift:131-136`）。

## 1.7 编辑态卡片

`editorCard`（`DiaryPageView.swift:569-601`）：

```
VStack(alignment: .leading, spacing: 4)                       // :570
├─ VStack(alignment: .leading, spacing: 6)                    // :571
│  ├─ if showsTime: 时间胶囊 = Text(timeOf(startUtc))          // :573-582
│  │    13pt bold, Theme.primary()，padding h10/v5，Capsule(primaryContainer)
│  └─ locationRow                                             // :583  §3.5
└─ RichTextView(controller:placeholder:autoFocus:loadToken:loadParts:) // :585-589
     placeholder = editingIndex != nil ? "修改这段内容…" : "开始记录这一刻的想法…"
.padding(Spacing.card)  // 12                                 // :596  ← 与阅读卡同内边距
.diaryCard(cornerRadius: Radius.card, interactive: true)      // :597  r=20，按下更重的阴影
.accessibilityElement(children: .contain) + id "diary.editorCard"    // :599-600
```

- **没有 `minHeight`**：最小高度由 `RichTextView` 自己报（160pt × body 动态字号系数，见 §3.1）。
- 时间胶囊与地点胶囊都是「capsule + primaryContainer 底」，只在 `auto_time` / `auto_loc` 打开时出现。

## 1.8 内容滚动区（阅读态与编辑态共用）

`content`（`DiaryPageView.swift:245-332`）：

```
ScrollViewReader { proxy in
  ScrollView(showsIndicators: false) {
    VStack(spacing: 12) {
      if isRead {
        if showSearch { readSearchBar }        // :250-252
        readHero                                // :253
        ForEach(blocks.indices) { blockCard(...).id("block-\(id)") }   // :254-257
      } else {
        editorCard.id("editor-card")            // :259
      }
      if keyboardHeight > 0 { Color.clear.frame(height: keyboardHeight + 160).allowsHitTesting(false)
                              .onGeometryChange → revealCaret(false) }   // :264-272
      else { TabBarClearance(base: 140).allowsHitTesting(false) }        // :274
    }
    .padding(.top, topBarHeight + 10)           // :278
    .frame(maxWidth: layout.contentColumn(660)) // :284  ← 内容列限宽（§2.6）
    .adaptivePagePadding()                      // :285  左右各 16
    .frame(maxWidth: .infinity)                 // :286  居中
    .contentShape(Rectangle()).onTapGesture { vm.dismissKeyboard() }  // :289-290
    .animation(.diaryStandard, value: blocks.map(\.id))               // :291
  }
  .scrollDismissesKeyboard(.interactively)      // :294
  .scrollPosition($scrollPosition)              // :295
  .onScrollGeometryChange { contentOffset.y } → scrollOffsetY        // :296-298
  .onChange(of: vm.scrollTarget) → withAnimation(.diaryStandard) proxy.scrollTo(target, anchor: .center)  // :302-308
  .onChange(of: vm.isRead) { if !read { 0.3s 后 withAnimation(.diaryStandard) scrollTo("editor-card", anchor: .center) } } // :309-317
  .onChange(of: vm.keyboardHeight) { if !isRead, height > 0 { 0.1s 后 revealCaret(animated: true, retries: [0.15,0.3,0.5,0.75,1.0,1.4]) } } // :322-330
}
```

两个透明占位（键盘占位、`TabBarClearance`）都 `.allowsHitTesting(false)`，否则「点正文下方空白收键盘」
会被它们吃掉（`DiaryPageView.swift:262-263`、`:275`）。

`TabBarClearance(base: 140)`：高度 = `140 × multiplier(TypeSize.rowTitle = 15)`，即默认 140pt、
AX5 约 224pt（`Components.swift:605-615`）。

## 1.9 页内搜索栏（阅读态）

`readSearchBar`（`DiaryPageView.swift:401-415`）插在 Hero **上方**，仅 `showSearch` 时存在：

- `GlassSearchField`：magnifyingglass + TextField(submitLabel `.search`) + 非空时的 `xmark.circle.fill` 清除键
  + trailing 区；胶囊玻璃底、左右 padding 14、`minHeight 46`（`Components.swift:547-597`）。
- trailing（命中数 > 0 时）＝ `GlassCountBadge("当前序号/总数")` + 两个步进按钮
  （`chevron.up` = 上一个匹配、`chevron.down` = 下一个匹配）。
- 步进按钮**故意不用玻璃**（玻璃叠玻璃会糊成一团），是普通 `Button`：`diaryFont(13, .semibold)` +
  `onSurfaceVariant` + `minWidth/minHeight 44` + `contentShape(Circle())` + 独立 a11y label
  （`DiaryPageView.swift:417-438`）。
- 序号显示 `min(hitIndex + 1, hits.count)/hits.count`（`:406`）。

## 1.10 竖屏 / 横屏布局

| 项 | 竖屏 | 横屏 |
|---|---|---|
| 列数 | 单列 | **仍是单列**（日记页自己不分栏；首页在横屏才分「月历 + 选中日」两栏，那是 Home 功能） |
| 内容列宽 | `min(660, 屏宽 - 32)`，居中 | 同公式；宽屏时被 660 卡住，两侧留白，卡片居中 |
| 页面左右内边距 | 16（`AdaptiveLayout.pagePadding`） | 16；**不再叠加 safeArea.leading**（横屏原点已在安全区内，`AdaptiveLayout.swift:136-146`） |
| 顶栏 | 钉在页面顶部，`topBarHeight + 10` 给内容让位 | 同左；横屏可用高度本就很小（iPhone 18 Pro 402pt、SE 198pt），键盘弹起时顶栏**可能被系统推出屏幕**（实测返回按钮到过 y = −51），靠点空白/下拉收键盘把它拿回来（`DiaryPageView.swift:29-32`、`docs/editor-format-behaviors.md:208`） |
| 格式栏 | 键盘上方 8pt；键盘收起时贴 home indicator 上方（`safeAreaBottom + 12`），**横排一条** | 同样横排一条（曾竖排贴右，9 个按钮约 454pt > 402pt 可用高度被裁掉，已废弃） |
| 底部占位 | 键盘弹起：`keyboardHeight + 160`；否则 `TabBarClearance(140)` | 同左 |
| 键盘避让 | `vm.keyboardHeight`（窗口坐标交集，`Screen.keyboardObscuredHeight`） | 同左；**不能用 `frame.height`**（横屏该矩形可能是竖屏坐标系，SE 横屏实测 417pt > 整屏） |

**内容列宽公式（阅读 = 编辑，一致）**：
`contentColumn(660) = min(660, max(240, screenWidth - 2 × 16))`（`AdaptiveLayout.swift:132-134`），
外层再 `.frame(maxWidth: .infinity)` 居中。正文文本宽度 = 该列宽 − 卡片内边距 `Spacing.card × 2 = 24`
（阅读卡 `DiaryPageView.swift:543`、编辑卡 `:596`，两者都是 12；编辑器 `textContainerInset.left/right = 0`，
`RichTextView.swift:128`）。历史上编辑列 620、阅读列 660，进出编辑卡片会跳，现已统一为 660
（`DiaryPageView.swift:279-284`、`docs/editor-format-behaviors.md:206/253`）。

**光标不被遮挡规则（caret-not-hidden）**：`applyCaretReveal`（`DiaryPageView.swift:202-221`）

```
bottomLimit = formatBarTop - 8          // 格式栏上沿让开 8pt（键盘在格式栏下面，让开栏 = 让开键盘）
topLimit    = topBarHeight + 8          // 悬浮顶栏让开 8pt
caret = controller.caretRectInWindow()  // 光标在窗口坐标的矩形（仅第一响应者时非 nil）
delta = caret.maxY > bottomLimit ? caret.maxY - bottomLimit
      : caret.minY <  topLimit   ? caret.minY - topLimit : 0
if |delta| <= 1 → 不动
target = max(0, scrollOffsetY + delta)  // 只做「刚好露出来」，不居中
animated ? withAnimation(.diaryQuick) scrollPosition.scrollTo(y: target) : scrollPosition.scrollTo(y: target)
```

触发点（都必须遵守这条规则）：
1. 打字 / 移动光标：`formatTick` 变化 → `revealCaret(animated: false)`（`DiaryPageView.swift:87-89`）；
2. 格式栏出现 / 位置变化：`onGeometryChange(frame(in: .global))` → 记 `formatBarTop` + `revealCaret(false)`（`:45-48`）；
3. 键盘高度变化：延迟 0.1s 后 `revealCaret(animated: true, retries: [0.15,0.3,0.5,0.75,1.0,1.4])`（`:322-330`）；
4. 键盘占位块高度变化：`onGeometryChange` → `revealCaret(false)`（`:270-272`）。
   滚动用 SwiftUI 的 `ScrollPosition.scrollTo(y:)`，**不能直接改底层 `UIScrollView.contentOffset`**
   （下一次布局会覆盖回去，实测无效，`DiaryPageView.swift:20-22`）。

**键盘高度**取自 `Screen.keyboardObscuredHeight(screenFrame:)`：把 `keyboardFrameEndUserInfoKey` 的矩形
换算到窗口坐标后求交集高度（`Components.swift:63-71`）；动画曲线按 UIKit 的
`keyboardAnimationCurveUserInfoKey`（1/2/3 → easeIn/easeOut/linear，其它 → easeInOut），时长缺省 0.25s
（`DiaryPageView.swift:229-240`）。

## 1.11 格式栏（FontToolbar）

`RichTextView.swift:230-388`。**键盘上方、横排、按内容宽度居中、可横滑**（与 Apple 备忘录一致）：

```
ViewThatFits(in: .horizontal) {
  barRow                                             // 默认：一条放得下
  ScrollView(.horizontal, showsIndicators: false) { barRow }   // 放不下时退化为横滑
}
.padding(.horizontal, 12)                            // :367
.background { RoundedRectangle(cornerRadius: Radius.panel = 26, .continuous)
                .glassEffect(.regular.interactive(true), in: …)
                .shadow(Theme.shadowColor(), radius: 14, y: 4) }   // :372-382
```

- 位置由 `DiaryPageView.swift:34-51` 决定：
  `.padding(.bottom, vm.keyboardHeight > 0 ? vm.keyboardHeight + 8 : Screen.safeAreaBottom + 12)`
  + `.transition(.opacity)`（随 `withAnimation(.diaryStandard)` 淡入淡出）。
- 量的是**格式栏自己的上沿**（`frame(in: .global).minY`），且必须在加 `padding(.bottom,…)` **之前**量，
  否则量到被垫高的位置（`DiaryPageView.swift:43-48`）。
- 按钮点击：`Haptics.tap()` → 执行动作 → `onTap?()` = `vm.controller.textView?.becomeFirstResponder()`
  （把焦点还给编辑器；`DiaryPageView.swift:35-37`）——所以**点格式栏不会收键盘**。

---

# 2. 阅读态排版

阅读态与编辑态共用同一套 attributed-string 生成器（`PartsCodec`），因此**同一段文字在两种模式下逐像素一致**
（区别只有：阅读态切块、去掉结尾换行、去掉图片段落的段距，见 §2.7）。

## 2.1 语义段落样式（design size 阶梯）

字号取自 Apple iOS 字阶（`RichTextEngine.swift:5-18`、`:26-33`），**存的是 design size，绘制时再乘动态字号系数**：

| style | design size | 对应 text style / 动态字号上限 | 行距 `lineSpacing` | 段后 `paragraphSpacing` | 段前 `paragraphSpacingBefore` | 底色 |
|---|---|---|---|---|---|---|
| `title` 大标题 | **28** | `.title1` / ×1.40 | 28 × 0.13 = **3.64** | 28 × 0.15 = **4.2** | 28 × 0.35 = **9.8** | 无 |
| `heading` 小标题 | **22** | `.title2` / ×1.45 | 22 × 0.13 = **2.86** | 22 × 0.15 = **3.3** | 22 × 0.35 = **7.7** | 无 |
| `body` 正文 | **17** | `.body` / ×1.50 | 17 × 0.13 = **2.21** | **0** | **0** | 无 |
| `quote` 引用 | **15** | `.subheadline` / ×1.60 | 15 × 0.5 = **7.5** | 15 × 0.4 = **6.0** | 15 × 0.4 = **6.0** | `Theme.quoteBg` |
| `list` 列表 | 17（按 body 绘制） | ×1.50 | 2.21 | 0 | 0 | 无 |
| `todo` 待办 | 17（按 body 绘制） | ×1.50 | 2.21 | 0 | 0 | 无 |
| `image` 图片 | — | — | — | 见 §2.5 | 见 §2.5 | — |

- 代码：`designSize` `RichTextEngine.swift:26-33`；`lineSpacing` `:40-42`；`paragraphSpacing` `:52-59`；
  `paragraphSpacingBefore` `:64-71`；`list/todo/image` 用 body 属性 `RichTextEngine.swift:88-97`、`:1162`。
- **段前 > 段后**是刻意的：标题要离上文远、离下文近（`EditorSpacingTests.swift:143-154`、`:178-205`）。
- **行距 / 段距是 design 点，不随动态字号放大**（`typingAttributes(for:)` `RichTextEngine.swift:230-234`、
  `PartsCodec.paragraphStyle` `:1150-1157` 直接用 `designSize × 系数`，没有乘 multiplier）。
  只有字形大小随 `DynamicTypeSize` 放大 —— 复刻时照抄这一点，否则大字号下段落会被撑开。
- 文字颜色：`Theme.onSurface`（浅 `#191C20` / 深 `#E1E2E8`）；行内 `foregroundColor` 在 codec 里显式写入
  （`RichTextEngine.swift:1165`、`:1200`）。
- 引用底色 `Theme.quoteBg`（浅 `#E4EAF9` / 深 `#2B3344`，不透明；`Assets.xcassets/quoteBg.colorset`）作为
  `.backgroundColor` 属性铺在**整段（含换行）**上（`RichTextEngine.swift:1204`、`:453-455`）。
  注意：**没有左侧竖线**、没有内缩、没有圆角 —— iOS 侧引用就是一个整段浅色底（分享长图里的引用才有左侧色条，§4.3）。
- 引用对齐：切到 quote 时强制左对齐（`cancelCenter: true`，`RichTextEngine.swift:388`、`:458-460`）。

## 2.2 行内样式（bold / italic / strike / underline）

| 格式 | 实现 | 备注 |
|---|---|---|
| bold | `UIFontDescriptor.SymbolicTraits.traitBold` | 与段落字号相乘，保留段落样式 |
| italic | `.traitItalic` **+ 斜切矩阵 `c = 0.24`** | 中文字体（PingFang）没有真斜体，不加斜切几乎看不出斜（`RichTextEngine.swift:155-163`、`:319-328`） |
| strikethrough | `.strikethroughStyle = 1` | 0/1 值，无颜色参数（`RichTextEngine.swift:1202`） |
| underline | `.underlineStyle = 1` | 同上（`:1203`） |
| 对齐 | `.paragraphStyle.alignment = .center` | 只有居中一种；落库为 `align: "center"`（`:1272-1275`） |

- 待办已完成项：`strikethroughStyle = 1` + 前景色 `onSurface` 45% alpha（`RichTextEngine.swift:1167-1170`）。
- 选中/光标状态下的按钮高亮由 `activeStyles()` 决定：有选区时要求**整个选区**都带该 trait 才算 active
  （`RichTextEngine.swift:798-818`）。

## 2.3 列表 / 待办标记（marker）

- 标记不是文字，是 `NSTextAttachment`（`MarkerAttachment`，`RichTextEngine.swift:1028-1052`）：
  - `list` → SF Symbol `circle.fill`；`todo` → 未完成 `square`、已完成 `checkmark.square.fill`
    （`:1036-1042`）；
  - 字号 `13 × body 动态系数`，bounds `(x: 0, y: -2, w: 15×scale, h: 15×scale)`（`:1043-1049`）；
  - 着色：已完成 `Theme.primary`，否则 `onSurfaceVariant`（`:1046`）。
- **阅读态的待办标记是整行热区**：点击命中范围 = 从标记字符到该行行尾（`ReadTextView.swift:121-128`），
  点击 → `onToggleTodo`。列表项没有点击行为。
- 落库形式：`{"style":"list","items":["甲","乙"]}` / `{"style":"todo","items":[…],"done":[true,false]}`
  （`RichTextEngine.swift:1319-1334`，连续同类行会合并成一个 part）。
- 兼容解析：也接受字面量 `"• "` / `"☐ "` / `"☑ "` 前缀（导入内容用，`RichTextEngine.swift:1233-1240`）。
- 只有标记、后面没有文字的行**不入库**（用户正在输入的那一行，`:1250`）。

## 2.4 图片渲染（阅读态）

`DiaryImageView`（`MediaViews.swift:13-46`）：

- 宽高比来自 content_json 的 `w/h`（`:43` `aspectRatio(w/h, contentMode: .fit)`）；
  **宽度由列宽决定**，`w/h` 只当比例和取图提示（`:19-25`）。
- 图片按 `scaledToFit` 填满列宽，圆角 `Radius.image = 14`（`.continuous`）（`:31-32`）；
  文件缺失时用 `Color.clear` 占位，**保留槽位不重排**（`:38-41`）；
  a11y `.isImage`（`:44`）。
- 取图：`DiaryImageStore.image(for:maxPixel: max(displayW, displayH) × 3)`（`:28`），内部走
  ImageIO 降采样 + `NSCache`（200 张 / 150MB，`MediaStore.swift:47-68`）。
- 图片块上下各 `EditorDesignSize.imageSpacing = 17 × 0.6 = 10.2pt` padding（`MediaViews.swift:76`、
  `RichTextEngine.swift:122`）；图片是独立的 chunk，`VStack(spacing: 0)` 因此不会有额外间隙（`MediaViews.swift:64-68`）。
- 点击图片 → `onImageTap(src, h/w)` → `vm.previewImage = PreviewItem` → 全屏预览（`MediaViews.swift:34-37`、
  `DiaryPageView.swift:536-538`、`:103-105`）。
- 点击图片时 `Haptics.tap()`（`MediaViews.swift:35`）。

## 2.5 图片插入度量（编辑态/加载态，阅读态共用同一把尺子）

**插入新图**（`RichTextEngine.swift:836-863`）：

```
textWidth = tv.bounds.width - inset.left - inset.right        // inset 左右都是 0 → = 输入区宽度
            （bounds.width == 0 时兜底 343）                    // :845-846
maxW      = max(60, textWidth)                                 // :847   列宽即图宽
ratio     = image.size.height / max(1, image.size.width)       // :848
displayH  = max(40, maxW × ratio)                              // :849   最小高度 40pt
attachment.image   = DiaryImageStore.rounded(image, size: (maxW, displayH), radius: Radius.image = 14)  // :851
attachment.bounds  = (0, 0, maxW, displayH)                    // :852
paragraphStyle     = alignment .center + paragraphSpacingBefore/After = 10.2   // :856、:1112-1118
随后追加一个 "\n"（普通 body typing attributes），光标移到图片之后                // :858-861
```

**载入已有图片**（`PartsCodec.attributedString` `RichTextEngine.swift:1071-1100`）：

```
storedW = max(1, w ?? 300), storedH = max(1, h ?? 200)         // :1073-1074
fallbackW = max(60, Screen.width - 76)                         // :1075  仅当拿不到列宽时
maxW  = max(60, imageMaxWidth ?? fallbackW)                    // :1076
w = maxW                                                       // :1084
h = storedH × w / storedW                                      // :1085
radius = 14；段落样式只挂在附件字符上（结尾换行保持普通样式）      // :1088-1097
```

- **没有最大高度**：只有最小高度（编辑插入 40pt；分享长图 120pt）。竖图会按比例很长。
- 列宽变化（旋转 / 分屏）时 `refitImages(maxWidth:)` 只重排附件 bounds 与图片，文本与光标不动
  （`RichTextEngine.swift:912-944`），宽度仍是 `max(60, maxWidth)`（`:930`）——与插入、codec 完全同一把尺子，
  差别曾经是 24pt 的 bug 已修（`EditorFlowUITests.swift:375-407`）。
- 图片是独立 `ContentPart`；`parts(from:)` 遇到附件就产出一个 image part，并规范化 `src`（`RichTextEngine.swift:1283-1288`）。

## 2.6 阅读列宽 / 内边距 / 圆角

| 元素 | 值 | 依据 |
|---|---|---|
| 内容列上限 | `contentColumn(660)` = `min(660, max(240, 屏宽 − 32))` | `AdaptiveLayout.swift:132-134`、`DiaryPageView.swift:284` |
| 页面左右内边距 | 16（leading/trailing 分开取，横屏不叠加安全区） | `AdaptiveLayout.swift:144-146`、`:246-253` |
| 卡片内边距 | 12（`Spacing.card`） | `DiaryPageView.swift:543`、`:596` |
| 卡片圆角 | 20（`Radius.card`） | `DesignSystem.swift:15` |
| 卡片间距 | 12（VStack spacing） | `DiaryPageView.swift:248` |
| Hero 内边距 | **14**（唯一的例外） | `DiaryPageView.swift:488` |
| 正文容器内缩 | `textContainerInset = (top 2, left 0, bottom 2, right 0)`，`lineFragmentPadding = 0` | `MediaViews.swift:82`、`ReadTextView.swift:29-30` |
| 编辑器内缩 | `textContainerInset = (top 10, left 0, bottom 10, right 0)`，`lineFragmentPadding = 0` | `RichTextView.swift:128-129` |
| 内联图片圆角 | 14（`Radius.image`） | `DesignSystem.swift:22`、`MediaViews.swift:32`、`RichTextEngine.swift:851`/`:1088` |

**阅读与编辑同宽的完整推导**（Android 复刻时的验收条件）：

```
列宽 L = min(660, max(240, screenW − 32))
卡片宽 = L                       （两者同一个 VStack，同一个 frame(maxWidth:)）
文本宽 = L − 12×2 = L − 24       （阅读卡 & 编辑卡内边距都是 Spacing.card = 12；编辑器左右内缩 0）
图片宽 = 文本宽                   （阅读：aspectRatio 盒按列宽；编辑：insertImage/refitImages 用输入区宽度）
```

UI 测试逐方向断言「阅读输入区宽 == 编辑输入区宽」（`JustDiaryUITests/EditorFlowUITests.swift:261-303`）。

## 2.7 阅读态分块（chunk）与两条「减法」

`DiaryPartsView` 负责把 parts 切成 chunk（`MediaViews.swift:88-108`）：遇到 `image` 就单独成块，其余连续文本合成一块。

`PartsCodec.readerChunk`（`RichTextEngine.swift:1134-1148`）在阅读态必须做两件减法，否则会出现莫名空白：

1. **删掉结尾换行**：读块是独立 `UITextView`，`sizeThatFits` 会为结尾换行保留一整行（约 20pt），
   表现为「图片前 / 卡片底部多一块空白」（`:1139-1141`、`EditorSpacingTests.swift:275-284`）；
2. **纯图片块去掉段落样式**：图片的上下间距由 `DiaryPartsView` 的 padding 给，段落再算一遍会翻倍
   （`:1142-1146`、`EditorSpacingTests.swift:286-297`）。

文本段落的段距**保留**：chunk 内部的排版与编辑器逐像素一致（`EditorSpacingTests.swift:299-315`）。

## 2.8 关键颜色（Android 复刻用的实测值）

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `bg` | `#FAF9FD` | `#101318` | 页面背景 |
| `onSurface` | `#191C20` | `#E1E2E8` | 正文/标题文字 |
| `onSurfaceVariant` | `#44474F` | `#C5C6D0` | 时间/地点/次要文字 |
| `outlineVariant` | `#C5C6D0` | `#44474F` | 分隔线/卡片描边（常用 0.45~0.5 alpha） |
| `primary`（`Theme.seed`） | `#2563EB` | `#2563EB` | 时间文字、chip 文字、accent |
| `primaryContainer` | `#DBEAFE` | `#1E3A8A` | 时间/地点胶囊底、搜索命中底色 |
| `quoteBg` | `#E4EAF9` | `#2B3344` | 引用段落底色 |
| `shadowColor` | `black 7.8%` | `black 14.9%` | 卡片/格式栏阴影 |
| 卡片底 | `Color(.secondarySystemGroupedBackground)`（系统分组色） | 同左（自动深色） | 所有内容卡 |
| 高亮命中文字 | `primaryContainer` 底 + `primary` 字 | 同左 | 页内搜索命中 |

（`Services/Theme.swift:3-45`、`Resources/Assets.xcassets/*.colorset`）

搜索命中的处理：`ReadTextView.applyHighlight`（`ReadTextView.swift:143-168`）按空白切词、忽略大小写，
命中段加底色 + 前景色，并**按该 run 自己的段落样式重建字号**（保持动态字号曲线，不再写死 15pt）。

---

# 3. 编辑态

## 3.1 编辑器模型

**一个 `UITextView` 承载全部内容**（不是「每段一个 block 组件」）：

- `RichTextView`（UIViewRepresentable，`RichTextView.swift:90-222`）→ `PlaceholderTextView`
  （子类，自带 placeholder `UILabel`，`RichTextView.swift:4-88`）；
- `RichEditorController`（`@Observable`，`RichTextEngine.swift:213-997`）持有 `weak var textView`，
  所有格式动作都是「改 `textStorage` 的属性」或「改 `typingAttributes`」；
- 文本真相 = `tv.textStorage`（`NSAttributedString`）；落库时才由 `PartsCodec.parts(from:)` 反向解析成
  `[ContentPart]`（`RichTextEngine.swift:865-868`、`:1219-1317`）；
- 每个 run 上带两个**编辑器内部键**（不落库）：`.diaryDesignSize`（未缩放的 design size）与
  `.diaryBlockStyle`（段落样式名）（`RichTextEngine.swift:138-148`）。段落类型显式存储，**不再靠字号反推**
  （字号反推只作为导入内容的兜底，`:200-210`、`:1263-1271`）；
- `typingAttributes` = 「下一个输入字符的样式」，是行样式延续的唯一权威（`:227-246`）；
- 编辑动作统一走 `apply(_:)`：拷贝整份 storage → 改 → 整体替换 → **恢复原选区（按新长度 clamp）**
  （`:980-996`）。这是「选区不会在一次 toggle 后消失」的原因；
- 任何格式变化 / 光标移动都会 `formatTick += 1`（`:250-253`），驱动 SwiftUI 侧按钮高亮重算
  （`RichTextView.swift:386`、`DiaryPageView.swift:87-89`）。

UI 配置（`RichTextView.swift:122-154`）：背景透明、`isScrollEnabled = false`（高度自适应、外层 ScrollView 滚）、
`keyboardDismissMode = .interactive`、`alwaysBounceVertical = false`、无纵向滚动条、`textColor = onSurface`、
`tintColor = primary`。

**最小高度**：`160 × multiplier(17pt body)`，默认 160pt、AX5 约 240pt
（`RichTextView.swift:25-31`、`:44`、`:107-120`，测试 `EditorTypeSizeUITests.swift:152-159`）。

**placeholder**：`UILabel` 手绘，位于 `(inset.left, inset.top)`，宽 = 视图宽 − 左右 inset − 10，
高 = `ceil(font.lineHeight)`，字体与正文同字号同曲线（`RichTextView.swift:69-77`）；文本非空即隐藏（`:80-87`）。
文案：新建 =「开始记录这一刻的想法…」，编辑已有 =「修改这段内容…」（`DiaryPageView.swift:586-587`）。

**自动聚焦**：`autoFocus == true` 时 `makeUIView` 后 0.25s `becomeFirstResponder`（`RichTextView.swift:148-152`）；
`vm.autoFocusEditor` 在进入写/编辑时按 `autoFocusAllowed` 置位（`DiaryViewModel.swift:137`、`:158`、`:191-193`）。

## 3.2 作用域规则（段落样式 vs 字符样式）

这是整个编辑器最需要复刻的部分（`RichTextEngine.swift:369-379` 是设计说明）。

### 段落样式（title / heading / body / quote / 列表 / 待办 / 居中）

| 操作 | 作用域 | 细节 |
|---|---|---|
| 选区 | **选中的每一整段**（含终止换行） | `paragraphRanges(covering:)` 按行相交取（`:480-502`），倒序处理（插入/删除标记会移动后续 range，`:387`、`:594`） |
| 光标（有文字的行） | **光标所在的那一段，且只有这一段** | 空段返回行首零宽 range；**不再把上一行一起改**（老的 `location <= lineEnd` 判定在「回车后的行首」会命中上一行，`docs/editor-format-behaviors.md:195/218-228`） |
| 光标（空行） | **只改「接下来输入的样式」**（`typingAttributes`），已输入内容一个字都不动 | 文本类样式走 `notifyFormatChange()` 分支（`:391-393`、`:526-530`、`:724-726`） |
| 列表 / 待办标记 | `includeEmpty: true`，**空行也保留 range**（标记本身就是这一行的内容） | `:586-587`；开标记前先 `restyle(..., .body, cancelCenter: true)` 取消标题/引用/居中/另一种标记（`:602-606`） |
| 标记 toggle 方向 | 由**光标所在行**是否已有同类标记决定（选区只决定范围，不决定方向） | `:588-589` |

互斥关系（`:369-374`、`:504-509`）：

- `title/heading/body/quote` 与 `list/todo` **互斥**：加列表/待办会先取消文本样式；给列表/待办行套文本样式会
  **丢掉行首标记**（`:431-435`）；
- 居中与 `list/quote/todo` **互斥**：处于三类行上时格式栏「居中」按钮 disabled（`:310-315`），
  控制器里也再 guard 一次（`:508`）；切换引用时强制左对齐（`:388`、`:458-460`）。

回车（Return）行为（`RichTextView.swift:187-205` + `RichTextEngine.swift:641-683`）：

| 场景 | 结果 |
|---|---|
| 列表/待办项**有内容**时回车 | 消费这次按键：在光标处插入「换行 + 同种标记」，光标落在标记之后（`:664-672`） |
| 列表/待办项**空**时回车（即再按一次） | 消费按键，**去掉标记**结束列表，该行变成普通空行（`:656-661`、`:687-702`） |
| 空引用行回车 | 消费按键，**结束引用**（只重置 typing attributes，不改已输入内容，`:675-682`） |
| 居中的空行回车 | **直接忽略**（防止空居中行无限堆叠，`:567-571`、`RichTextView.swift:191-196`） |
| 其它 | 交给 UIKit 正常换行；段落属性会随换行延续（`PartsCodec` 把块属性挂在终止换行上，`:1211-1216`） |

### 字符样式（bold / italic / strike / underline）

| 操作 | 作用域 |
|---|---|
| 无选区（只有光标） | 只改 `typingAttributes`：**之后输入**的字符变样，已输入的字一个都不动（`:265-281`、`:338-347`） |
| 有选区 | 整个选区统一一个方向：方向 = 「选区里**每一个** run 都已经带该 trait / 样式」（全带 → 取消；否则 → 统一加上）。逐段各自取反会把混合选区变成补丁（`:286-297`、`:303-313`、`:349-367`） |
| 按钮高亮 | 有选区：要求整个选区都带才高亮（`:798-818`） |

## 3.3 格式栏按钮清单

**顺序固定，一行 9 项**（`RichTextView.swift:311-353`，spacing 4pt）：

| # | 图标 | a11y label（zh） | 动作 | active 判定 | 禁用 |
|---|---|---|---|---|---|
| 1 | `textformat.size` + `chevron.down`（宽 46） | 段落样式 | 菜单：大标题 / 小标题 / 正文（当前项打勾，`.menuOrder(.fixed)`） | `currentBlockStyle() != .body` | — |
| 2 | `text.aligncenter` | 居中 | `toggleCenter()` | `isCenterActive()` | 列表/引用/待办任一 active 时 disabled |
| — | `Divider`（高 20） | — | — | — | — |
| 3 | `list.bullet` | 列表 | `toggleList()` | `isListActive()` | — |
| 4 | `text.quote` | 引用 | `toggleQuote()` | `isQuoteActive()` | — |
| 5 | `checklist` | 待办 | `toggleTodo()` | `isTodoActive()` | — |
| — | `Divider`（高 20） | — | — | — | — |
| 6 | `bold` | 粗体 | `toggleBold()` | `activeStyles().bold` | — |
| 7 | `italic` | 斜体 | `toggleItalic()` | `.italic` | — |
| 8 | `strikethrough` | 删除线 | `toggleStrike()` | `.strike` | — |
| 9 | `underline` | 下划线 | `toggleUnderline()` | `.underline` | — |

- 按钮外观：图标 `diaryFont(15, .medium)`，`frame(minWidth: 34, minHeight: 40)`，`contentShape(Capsule())`，
  active = 白字 + `Theme.primary()` 胶囊底，非 active = `onSurface` 无底；disabled 时 `opacity 0.35`
  （`RichTextView.swift:234-255`）。a11y 上 active 会加 `.isSelected` trait（`:254`）。
- 样式菜单文案：大标题 / 小标题 / 正文（`editor_font_heading` / `editor_font_sub` / `editor_font_body`，
  `RichTextEngine.swift:99-106`），**引用不在菜单里**（它有自己的按钮，`RichTextEngine.swift:73-76`）。
- **竖屏与横屏的按钮列表完全相同、顺序相同**；差别只是可用宽度：默认字号下 9 个按钮约 360pt，
  402pt 的手机一条放得下；放不下（横屏窄机 / 大字号）时 `ViewThatFits` 退化为**横向滚动**的同一条
  （`RichTextView.swift:355-366`，测试 `EditorFlowUITests.swift:186-203`、`LandscapeLayoutUITests.swift:234-276`）。
- 每个按钮点击后都会把焦点还给编辑器（`becomeFirstResponder`，`DiaryPageView.swift:35-37`），
  所以连点排版不会收键盘。

## 3.4 图片插入流程（编辑态）

```
顶栏 photo 按钮 → showPhotoPicker = true                    // DiaryPageView.swift:382-385、（sheet :90-94）
→ PHPickerViewController：filter = .images, selectionLimit = 1,
  preferredAssetRepresentationMode = .current               // MediaViews.swift:111-122
→ 选中一张 → loadObject(ofClass: UIImage) → 主线程 onPicked  // MediaViews.swift:132-142
→ vm.insertImage(image)                                     // DiaryViewModel.swift:542-557
   ① downsample maxPixel 2048（ImageIO，失败则用原图）        // :543、MediaStore.swift:78-84
   ② jpegData(compressionQuality: 0.85)，失败退 pngData       // :544
   ③ 文件名 "img_<epochMs>.jpg"，写入 Documents/images/       // :545-550（DiaryRepository.imagesDir() :45-49）
   ④ controller.insertImage(processed, src: "images/img_<ms>.jpg")
→ 图片插在**光标处**，宽度 = 正文列宽，后面补一个换行，光标移到图片之后
                                                            // RichTextEngine.swift:836-863（§2.5）
```

- 失败（写文件异常）→ info 弹窗「插入图片失败」/「无法读取这张图片，请换一张重试。」（`DiaryViewModel.swift:552-556`）。
- 相册选择器的取消 = 系统 sheet 关闭，无副作用。
- 图片在地点胶囊上方、时间胶囊下方；插入的图片**不改变** `w/h` 的语义（存的是当时列宽与等比高度，
  但只当比例用）。

## 3.5 位置胶囊流程（仅新建片段取一次位置 / 编辑已有片段只能改精度）

`locationRow` 的分支（`DiaryPageView.swift:609-632`）：

| 场景 | 显示 | 可点行为 |
|---|---|---|
| **新建片段** + `auto_loc` 开 + 已定位 | 地点胶囊（primary 色） | 打开精度菜单（含「重新获取位置」） |
| **新建片段** + `auto_loc` 开 + 定位中 | 「位置获取中…」胶囊（primary 色，不可点） | — |
| **新建片段** + `auto_loc` 开 + 无位置 | 「重新获取位置」按钮（primary 色） | `vm.beginLocate()` |
| **新建片段** + `auto_loc` 关 | **整行不显示** | — |
| **编辑已有片段** + 该片段有地点（无论 `auto_loc`） | 地点胶囊 | 精度菜单（**没有**「重新获取位置」） |
| **编辑已有片段** + 该片段无地点 + `auto_loc` 开 | 「未记录地点」（灰，不可点） | — |
| **编辑已有片段** + 该片段无地点 + `auto_loc` 关 | **整行不显示** | — |

触发时机：

- 新建：`enterWrite()` 末尾 `beginLocateIfNeeded()` → `auto_loc && location == nil` 时 `beginLocate()`
  （`DiaryViewModel.swift:142`、`:436-439`）。
- 编辑已有：`enterEditBlock(index:)` 用**块的既有列**重建 `LocationSnapshot`（经纬度 + 地址文本 +
  `recordedPrecision` / `recordedText`），**不做任何定位请求**（`DiaryViewModel.swift:160-163`、`:519-538`）。
- 权限：未授权 → `requestPermission()`，等 600ms，仍未授权则 `locating = false`（回到「重新获取位置」）
  （`DiaryViewModel.swift:445-457`）。
- 定位成功后如果 `locText` 为空 → 视为「没有位置」（`location = nil`），避免存下一个永远补不上的空地点
  （`DiaryViewModel.swift:462-473`）。

**精度菜单**（`DiaryViewModel.swift:511-517` + `LocationService.swift:182-198`）：

- 已有片段（`recordedPrecision` 非空）：候选 = 〔记录时的等级（仅 exact/street 才保留，因为只有它的文本里
  可能有店名/街道）〕+ 〔region3 非空时的 district〕+ 〔region2 非空时的 city〕+ province；顺序固定。
- 新建片段（实时定位）：有权限且高精度 → `exact / street / district / city / province` 全量；
  否则 → `province / city / district`。
- 切换精度：`applyPrecision` 会重算 `locText`；若切回**记录时的等级**则**原样恢复记录的文本**
  （`recordedText`），否则用 placemark 或 region 重新拼（`DiaryViewModel.swift:475-491`）；
  文案 zh：精确地点 / 街道 / 区县 / 城市 / 省份（`L10n.swift:239-248`）。
- 菜单项前有 checkmark 表示当前等级。注意 `auto_loc` 只管「没有地点时」的提示与重试入口：
  **片段本来就有地点时，即使 `auto_loc` 关闭也照样显示地点胶囊并能改精度**（`DiaryPageView.swift:611-613`，
  只有 `location == nil` 的分支才看 `settings.autoLoc`）。

## 3.6 放弃修改

- 触发：顶栏 `arrow.counterclockwise`（`DiaryPageView.swift:386-389`）→ `confirmDiscardEditing()`
  （`DiaryViewModel.swift:250-257`）。**编辑器为空时不弹任何东西**（直接 return，`:251`）。
- 对话框（confirm 型：确认按钮在前、取消在后）：

| 字段 | zh | en |
|---|---|---|
| 标题 | 放弃修改？ | Discard changes? |
| 正文 | 未保存的内容将丢失，且无法恢复。 | Your unsaved changes will be lost and can’t be recovered. |
| 确认按钮 | 放弃 | Discard |
| 取消按钮 | 取消 | Cancel |

- 确认后 `discardEditing()`：`loadParts = editingOriginalParts; loadToken += 1; refreshTypingAttributes()`
  （`DiaryViewModel.swift:259-263`）—— 通过 loadToken 让 `updateUIView` 重新灌入进入编辑时的快照
  （`RichTextView.swift:166-173`）。**没有 undo 栈**，放弃 = 不可恢复。
- `editingOriginalParts` 在 `enterEditBlock` 时写为块的 parts、在 `enterWrite` 时清空
  （`DiaryViewModel.swift:131`、`:154`）——新建片段「放弃修改」的语义是「清空回初始空文档 + 重置 typing
  attributes」，不是回到上一段。

## 3.7 保存流程与自动时间

**保存触发点只有一个**：顶栏 `checkmark`（`DiaryPageView.swift:390-393` → `saveEditor()`）。

`saveEditor()` 判定树（`DiaryViewModel.swift:267-320`）：

```
parts = controller.currentParts()                       // 反向解析 textStorage → [ContentPart]
if parts.isEmpty:
    新建 且 本日无任何 block → onDismiss()（整页关闭，等于没写）
    否则                    → 动画回到阅读态（isRead = true, editingIndex = nil）
    return
contentJson = ContentFlatten.serializeContent(parts)     // {"v":2,"parts":[...]}

【编辑已有片段】editingIndex != nil:
    位置只取「精度/文本」：locText = location?.locText ?? block.locText
                          locPrecision = location?.locPrecision ?? block.locPrecision
    → updateBlockContent(blockId, contentJson, locText, locPrecision)
    → bumpDiaryVersion() → reloadAndShowRead()
    （错误 → 「保存失败」弹窗）

【新建片段】:
    blockDayKey = dayKeyForUtc(startUtc, settings.dayStartHour)
    ① 若 auto_loc 开 且 location == nil → confirm 弹窗：
         标题「未获取到位置」/ 正文「还没有获取到地点。保存后这条记录将无法再添加位置。」
         按钮：仍然保存 / 取消
         确认后**延迟 0.3s** 再继续（让弹窗先收干净，可能紧跟第二个弹窗）
    ② finishNewBlockSave(dayKey: blockDayKey, contentJson:)
         若 blockDayKey != 今天 → confirm 弹窗：
            标题「跨日编辑提示」/ 正文「本次内容将保存为 <显示日期> 的日记。保存并退出后，这篇日记将归档。」
            按钮：保存并退出 / 取消
         否则直接保存
    ③ saveByDayKey: 有当天 diary 就 addBlockToDiary，否则 addBlock(dayStartHour 参与算 day_key)
         → actualDayKey = dayKey → bumpDiaryVersion()
         → ReminderService.markTodayWritten()  // 打卡：标记今天已写
         → reloadAndShowRead()
         （错误 → 「保存失败」弹窗）
```

- `reloadAndShowRead()`（`DiaryViewModel.swift:377-390`）：重新查 blocks；`autoFocusEditor = false`；
  `withAnimation(.diaryStandard)` 复位 `isRead = true, editingIndex = nil, selectMode = false, selectedIds = []`。
  即**保存后停在阅读态、不关页**。
- **自动时间（auto-insert-time）**：`startUtc` 在 `load()` 时写为「打开日记页的那一刻」
  （`DiaryViewModel.swift:62`），进入**新建**编辑时**不刷新**（`enterWrite` 不碰 `startUtc`，`:121-143`）；
  编辑已有片段时 `startUtc = block.startTimeUtc`（`:159`）。因此新建片段保存的时间戳 = 打开当天日记页的时间，
  而不是点「写日记」的时间。保存后 `load()` 会再刷新为当时时刻。
- `settings.autoTime` 只影响**显示**（时间胶囊、阅读态块时间、Hero 开始时间、分享长图时间行）；
  `start_time_utc` 永远照常写入（`AppSettings.swift:4-11`、`DiaryViewModel.swift:46-49`）。

## 3.8 键盘收起的路径（「编辑页键盘收不起来」的最终行为）

三条路径 + 一个关键开关：

1. **点内容空白**：内容 VStack `.contentShape(Rectangle())` + `.onTapGesture { vm.dismissKeyboard() }`
   （`DiaryPageView.swift:289-290`）；两个透明占位都 `allowsHitTesting(false)`，否则手势落不到这层（`:262-263`、`:275`）。
2. **点顶栏空白**：`topBarOverlay` 同样挂 `contentShape + onTapGesture`（`:345-346`）；按钮自己的手势优先。
3. **下拉内容**：`.scrollDismissesKeyboard(.interactively)`（`:294`）+ 编辑器 `keyboardDismissMode = .interactive`
   （`RichTextView.swift:133`）。

`dismissKeyboard()` 做两件事（`DiaryViewModel.swift:195-202`）：

```swift
autoFocusEditor = false                                  // ← 关键
UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
```

- 为什么必须关 `autoFocusEditor`：SwiftUI 重建 `RichTextView` 时 `makeUIView` 会按它再喊一次
  `becomeFirstResponder`（延迟 0.25s），用户刚收起的键盘会被「叫回来」——表现就是**键盘收不起来**
  （`DiaryViewModel.swift:196-199`、`docs/editor-format-behaviors.md:207/254`）。
- **格式栏是有意的例外**：点格式栏按钮会 `becomeFirstResponder()` 把键盘叫回来（连续排版不被打断，
  `DiaryPageView.swift:35-37`、`docs/editor-format-behaviors.md:323-324`）。
- 阅读态页内搜索的 `TextField` 也走同一条「点空白收键盘」路径（`dismissKeyboard` 对任何 first responder 生效）。
- 顶栏按钮在横屏 + 键盘弹起时可能被系统推到屏幕外（已知限制，`docs/editor-format-behaviors.md:208`），
  所以「点空白 / 下拉收键盘」是拿回顶栏的唯一自然路径（UI 测试 `EditorFlowUITests.swift:207-254`）。

## 3.9 编辑态的其它状态变化

**宽度变化 / 旋转**（`RichTextView.swift:52-78`、`:156-175`、`RichTextEngine.swift:825-834`）：

- `PlaceholderTextView.layoutSubviews` 监听 `bounds.width`（> 40 且变化 > 1pt），推到下一轮 runloop 回调
  （布局期间不能改文本存储）；`updateUIView` 也会主动调一次 —— 因为「只有尺寸变化」时 SwiftUI 不保证再调
  `updateUIView`（`RichTextView.swift:52-56`）。
- `handleTextWidthChange` → 只重排图片（`refitImages`），**文本与光标原地不动**；首次测量不重排。
  旧实现会在宽度变化时重跑 `loadParts`，把用户中途输入的内容悄悄丢掉（`RichTextEngine.swift:820-834`）。

**动态字号变化**（设置里改文字大小 / 编辑页被 `.uiTickChanged` 刷新）：

- `updateUIView` 发现 `typeSize` 变了 → `applyTypeSize`（placeholder + 最小高度）+ `controller.reapplyTypeSize`
  （`RichTextView.swift:158-162`）；
- `reapplyTypeSize`：**先用旧的字号档解析出 parts（保证 design size 精确还原），再按新档重建**，
  光标位置 clamp 后保留（`RichTextEngine.swift:883-896`）。这是「保存不会把 17pt 正文写坏成标题」的关键。

**进入编辑的滚动**：`isRead` 变为 false 后 0.3s，`withAnimation(.diaryStandard)` 把 `"editor-card"`
滚到视口中心（`DiaryPageView.swift:309-317`）；随后键盘弹起时按 §1.10 的光标规则再修正。

---

# 4. 分享

## 4.1 入口：一步到「长图预览 + 系统分享动作」

- 入口只有顶栏的 `square.and.arrow.up`（**仅阅读态**，`DiaryPageView.swift:377-380`）。
- `vm.shareDiary()`（`DiaryViewModel.swift:561-596`）：

```
guard !showShareSheet                       // 面板已开则不重复渲染（否则预览会闪回进度圈）
shareBlocks = blocks.map { ShareBlock(time: startTimeUtc, loc: locText,
                                      parts: parseContentCached(contentJson)) }
isDark   = UITraitCollection.current.userInterfaceStyle == .dark
showTime = settings.autoTime
shareImage = nil; shareFileURL = nil; showShareSheet = true     // 先拉起面板（立刻有反馈）
Task.detached(priority: .userInitiated) {
    删除上一张分享图临时文件（面板早已收起，不留垃圾）
    image = ShareRenderer.render(dayKey:blocks:isDark:showTime:)
    fileURL = image.flatMap { writeShareFile($0, dayKey:) }     // JustDiary-<dayKey>.png
    MainActor: 面板还开着且有图 → 填 shareImage/shareFileURL
               渲染失败（image == nil）→ 关面板 + info 弹窗「分享失败」
}
```

- 面板：`.sheet(isPresented: $vm.showShareSheet)`，`.presentationDetents([.large])` +
  `.presentationDragIndicator(.visible)`（`DiaryPageView.swift:95-102`）。
- 渲染在后台线程（离屏 Core Graphics），面板先显示进度，避免「点了一两秒后才啪地弹出」。

## 4.2 分享面板内容（`ShareSheetView`）

```
GeometryReader { geo in
  if geo.size.width > geo.size.height {        // 横屏
      HStack(spacing: 0) {
          previewColumn
          if activityItem != nil { separator; activityArea.frame(width: min(430, geo.size.width * 0.55)) }
      }
  } else {                                     // 竖屏
      VStack(spacing: 0) {
          previewColumn
          if activityItem != nil { separator; activityArea.frame(height: activityHeight(geo.size.height)) }
      }
  }
}
.background(Theme.bg().ignoresSafeArea())
```

（`ShareSheetView.swift:24-49`）

| 区块 | 内容 | 依据 |
|---|---|---|
| 预览列 | `ScrollView(showsIndicators: false)`；图 `scaledToFit` + `frame(maxWidth: 720)` + 圆角 14 + 1pt 描边 `onSurface 8%` + shadow radius 18 / y 8 + padding h20 v18；a11y label「分享预览」，id `share.preview` | `:72-96` |
| 进度占位 | `ProgressView` + 「正在生成分享图…」（13pt, onSurfaceVariant），`VStack(spacing: 14)`，`minHeight 220`，id `share.rendering` | `:98-107` |
| 分隔线 | `Divider().overlay(outlineVariant.opacity(0.5))` | `:58-60` |
| 系统动作区 | 内嵌一个 `UIActivityViewController`（`UIViewControllerRepresentable`），右上角叠一块透明关闭热区 | `:62-68`、`:152-183` |
| 竖屏动作区高度 | `min(max(340, sheetH × 0.48), max(280, sheetH − 200))`（保证系统三行（条目行 + 应用行 + 动作行）约 330pt 能整行显示，预览至少留 200pt） | `:136-143` |
| 横屏动作区宽度 | `min(430, geo.width × 0.55)` | `:34` |
| 面板标题/关闭按钮 | **自己不画**：预览顶部就是日期，条目行就是文件名；关闭靠系统条目行右上角那个叉 | `:4-16` |

**系统动作区右上角关闭热区**（`systemCloseTarget`，`ShareSheetView.swift:111-134`）：
96×96 的透明 `Button` 盖在动作区 `topTrailing`，点击 = `Haptics.tap()` + `onClose()`（收起整个面板），
a11y label「取消」，id `share.close`。存在的原因：iOS 26/27 的分享面板由远程视图服务渲染，
系统那个叉点不动（自带 dismiss 打在不存在的 presentation controller 上），实测竖屏叉在
（距右 40、距顶 76）、横屏（距右 37、距顶 44），都落在这个 96×96 方框内且框内没有别的控件。

`UIActivityViewController` 的细节（`ShareSheetView.swift:51-56`、`:152-183`）：分享对象优先
**文件 URL**（这样系统条目行显示文件名 `2026-09-08.png` + 真实缩略图，并多出「存储到文件」这类动作），
拿不到文件时退回 `UIImage`；`completionWithItemsHandler` 里关闭整个面板；`sizeThatFits` 返回被提议尺寸
（缺省 320×360）。

## 4.3 长图版面（`ShareRenderer`，`Services/ImageShareService.swift`）

**画布**：宽固定 **720pt**，高按内容撑开；`UIGraphicsImageRendererFormat`：`scale = 2`、`opaque = true`
（`ImageShareService.swift:24`、`:150-164`）。渲染流程是「排版阶段产出绘制指令 → 绘制阶段只执行指令」，
保证量出来的行数和画出来的一致（`:9-22`、`:127-144`）。

**版面常量 `M`**（`:28-52`）：

| 常量 | 值 | 含义 |
|---|---|---|
| `width` | 720 | 画布宽 |
| `margin` | 56 | 左右页边距 |
| `content` | 608 | 内容宽 = 720 − 56×2 |
| `top` | 56 | 顶部留白 |
| `brandH` | 34 | 品牌行高 |
| `afterBrand` | 24 | 品牌行后间距 |
| `dateH` | 74 | 大日期行高 |
| `afterDate` | 12 | 日期后间距 |
| `metaH` | 34 | 起始时间行的量算行高 |
| `afterMeta` | 26 | meta 后间距 |
| `ruleWidth` / `ruleHeight` | 64 / 5 | 品牌色短线（圆角 2.5） |
| `afterRule` | 40 | 短线后间距 |
| `entryHeadH` | 38 | 每条记录的时间/地点行高 |
| `afterEntryHead` | 14 | 该行后间距 |
| `cardPadH` / `cardPadV` | 30 / 28 | 卡片内边距 |
| `cardRadius` | 26 | 卡片圆角 |
| `betweenEntries` | 30 | 条目之间 |
| `partGap` / `looseGap` | 20 / 26 | 段间距（标题/小标题用 looseGap，其余 partGap） |
| `beforeFooter` | 46 | 页脚前间距 |
| `footerH` | 30 | 页脚行高 |
| `bottom` | 48 | 底部留白 |

**字体 `F` / 行高 `LH`**（`:54-77`）：brand 26 semibold；year 22 medium；date **58 bold**；weekday 28 medium；
meta 25；time 27 semibold；loc 23；title 38 bold（行高 54）；heading 32 semibold（46）；body 29（48）；
quote 27（44）；item 29（46）；footer 22 medium（30）。文字统一 `minimumLineHeight = maximumLineHeight`
（`:359-367`），换行 `.byWordWrapping`。

**配色 `Palette`**（`:79-125`）：浅色 text `#15181D` / sub `#5B6472` / faint `#98A1B0` / accent `#2563EB` /
bg `#F8F9FC → #EDF0F7` / card `#FFFFFF` / cardBorder `black 6%` / cardShadow `black 8%` / quoteBg `#2563EB 7%`；
深色 text `#ECEDF1` / sub `#A7AEBB` / faint `#6E7683` / accent `#7C97FF` / bg `#0C0F14 → #131722` /
card `#1A1F29` / cardBorder `white 7%` / cardShadow `black 45%` / quoteBg `#7C97FF 14%`。
背景绘制（`:473-496`）：竖向线性渐变 + 两枚径向品牌晕染
（`(720×0.94, −80)` r=430 alpha 0.10 的 glowStrong；`(720×0.02, H×0.44)` r=380 alpha 0.06 的 glowSoft）。

**纵向结构**（`makePage`，`:168-275`）：

```
y = 56
1) 品牌行：accent 圆点 (margin+7, y+17) r=7；品牌名 = L10n "EntryAbility_label"（一页时光）
   矩形 (margin+26, y, content−140, 34)，26 semibold；年份 (L10n "date_year") 右对齐 (margin, y+5, content, 26)
   y += 34 + 24
2) 大日期 (L10n.dateOnly，如「9月8日」)：(margin, y, content, 74)，58 bold
   星期 (L10n.weekdayName)：(margin + dateWidth + 16, 基线对齐, max(40, content − dateWidth − 16), LH)
   y += 74 + 12
3) meta 行（仅 showTime 且首条 time > 0）：「<HH:mm> 开始写」= L10n "read_start_time"
   （**只有时间，没有地点**；auto_time 关则整块不画）  y += textH + 26
4) 品牌色短线 64×5 圆角 2.5                         y += 5 + 40
5) 每条记录（无内容且无时间且无地点则整条跳过）：
     条目之间 += 30
     时间/地点行：时间 (x=margin, w=160, h=38, 27 semibold, accent)
                 地点 (x = margin + timeWidth + 14, y+6, w 剩余, h 30, 23, sub，仅在剩余宽 > 60 时画)
                 y += 38 + 14
     卡片：先算内容高度再插卡片指令（卡片画在内容之下）：card = (margin, cardTop, 608, cardHeight)
           圆角 26、填充 card、描边 2pt（inset 1、半径 25）、阴影 offset(0,6) blur 26
     段间距：title/heading → 26，其余 → 20
6) 页脚：y += 46；细线 (margin, y, 608, 1) 圆角 0.5、faint 35%；
   y += 22；左「一页时光 · JustADiary」= "<EntryAbility_label> · JustADiary"（宽度 608×0.6，22 medium，faint）
          右 = dayKey（如 "2026-09-08"，右对齐，608×0.6）
   y += 30 + 48 → 页面总高
```

**每种 part 的绘制**（`appendPart`，`:286-333`）：

| style | 画法 |
|---|---|
| `title` | 38 bold / 行高 54 / text 色 |
| `heading` | 32 semibold / 行高 46 / text 色 |
| `body`（default 分支） | 29 / 行高 48 / text 色 |
| `quote` | 底色盒 `fillRounded(x, y, width, textH + 44)` 圆角 16 填 quoteBg；左侧色条 `(x, y+10, 5, boxH−20)` 圆角 2.5 用 accent；文字从 `x+26` 起、宽 `width − 46`，27 / 行高 44 |
| `list` | 每项：圆点 `(x+9, cursor + 23 − 3)` r=5.5 accent；文字从 `x+30` 起（markerW 30），29 / 46 |
| `todo` | 每项：30×30 复选框 `(x, cursor+8)` 圆角 8；已完成 = accent 填充 + 白色对勾（线宽 3.4，圆头圆角），未完成 = faint 2pt 描边；文字从 `x+48` 起，已完成用 sub 色；项间 16pt，返回值扣掉最后那 16 |
| `image` | 高 `max(120, width × ratio)`（ratio 取 `h/w`，缺失时读文件真实比例，再缺省 0.75）；圆角 20；按 aspect-fill 居中裁剪绘制；文件缺失时填 quoteBg 圆角块占位 |

**水印与文件名（硬性要求）**：

- 图片内两处品牌名：顶部品牌行（`一页时光`）与页脚 `一页时光 · JustADiary`；右下角是当天的 `dayKey`。
- 文件名：`JustDiary-<dayKey>.png`，写在**临时目录**（`FileManager.temporaryDirectory`），PNG（文字边缘不糊）、
  `.atomic`；同一天重复分享**覆盖同一个文件**，且下一次分享时删掉上一张（`DiaryViewModel.swift:598-615`、`:575-581`）。
  前缀是刻意的：系统分享面板直接拿文件名当标题，不加前缀容易和别人的 `2026-09-08.png` 撞名。

## 4.4 失败与边界

| 情况 | 表现 |
|---|---|
| 渲染返回 nil | 关闭面板 + info 弹窗「分享失败」/「日记图片生成失败，请稍后重试。」（`DiaryViewModel.swift:589-593`） |
| 文件写入失败 | 仍用 `UIImage` 分享（条目行退化为「图片」+ 占位图）（`DiaryViewModel.swift:584`、`ShareSheetView.swift:52-56`） |
| 空白的一天 | 只画品牌页眉 + 页脚，高度约 200~420pt，不画空卡片（`JustDiaryTests/ShareRendererTests.swift:74-79`） |
| `auto_time` 关闭 | 头部 meta 行与每条记录的时间行都不画，图明显变矮（`:64-71`） |
| 面板已开着再点分享 | 直接 return，不重复渲染（`DiaryViewModel.swift:562-563`） |
| 动作完成 / 取消 | `completionWithItemsHandler` 关闭整个面板（`ShareSheetView.swift:162-164`） |

---

# 5. 交互清单

## 5.1 点击 / 手势总表

### 阅读态（`isRead == true`，非多选）

| # | 手势 | 命中区 | 结果 | 依据 |
|---|---|---|---|---|
| R1 | tap | 顶栏 返回（44pt 玻璃圆） | `handleBack()`：非多选 → `onDismiss()` 关闭整页（fullScreenCover 收起） | `DiaryPageView.swift:352-355`、`DiaryViewModel.swift:204-230` |
| R2 | tap | 顶栏 搜索 | `withAnimation(.diaryQuick)` 切换 `showSearch`；关闭时同时清空 `searchText` 与 `hits` | `:364-370` |
| R3 | tap | 页内搜索输入框 | 弹出键盘；输入触发 150ms 防抖 → `computeHits()` | `:82-84`、`DiaryViewModel.swift:77-111` |
| R4 | tap | 搜索框内 `xmark.circle.fill`（文本非空时出现） | 清空 `searchText`（→ 防抖后清空命中） | `Components.swift:577-586` |
| R5 | tap | 命中步进 `chevron.up` / `chevron.down`（≥44pt） | `stepHit(∓1)`：`hitIndex` 循环回绕 → `scrollTarget = "block-<id>"` → `scrollTo(anchor: .center)`（`.diaryStandard`） | `DiaryPageView.swift:407-412`、`DiaryViewModel.swift:113-117`、`DiaryPageView.swift:302-308` |
| R6 | tap | 顶栏 写日记（**仅当天**） | `enterWrite()`：清空 `loadParts`/`editingOriginalParts`/`location`，`loadToken += 1`，`autoFocus`，`.diaryStandard` → 编辑态；随后按 `auto_loc` 触发定位。非当天时弹「无法新增编辑块」（但按钮本来就不显示） | `:371-376`、`DiaryViewModel.swift:121-143` |
| R7 | tap | 顶栏 分享 | `shareDiary()`（§4.1） | `:377-380` |
| R8 | tap | 条目卡的时间/地点抬头区 | `handleBlockTap` → `enterEditBlock(index)`（受历史日记权限拦截） | `:527-530`、`:554-565` |
| R9 | tap | 条目卡正文（单个 chunk 内） | 同上（`ReadTextView.onTapText`） | `MediaViews.swift:78-82`、`ReadTextView.swift:188` |
| R10 | tap | 待办行的任意位置（标记 → 行尾） | `toggleTodo(block, partIndex, itemIndex)`：翻转 `done[i]` → 写库 → `bumpDiaryVersion` → 整页重载。**多选态下同样生效** | `ReadTextView.swift:121-128`、`:178-182`、`DiaryViewModel.swift:413-432` |
| R11 | tap | 正文内图片 | `Haptics.tap()` + 全屏 `ImagePreviewView`（`previewImage = PreviewItem(src, h/w)`） | `MediaViews.swift:34-37`、`DiaryPageView.swift:536-538`、`:103-105` |
| R12 | long press ≥ 0.4s | 整张条目卡（含正文） | `enterSelect(block.id)`：`withAnimation(.diaryQuick)` 进入多选态并选中该条；历史日记未开权限时弹「只读内容」 | `DiaryPageView.swift:547-549`、`DiaryViewModel.swift:169-179` |
| R13 | tap | Hero 卡 | 无（没有手势） | `:442-492` |
| R14 | tap | 卡片之间的空白 / 正文下方的空白 | `vm.dismissKeyboard()`（收键盘） | `:289-290` |
| R15 | drag down | 内容区 | `scrollDismissesKeyboard(.interactively)` 交互式收键盘 | `:294` |
| R16 | swipe | 内容区 | 纵向滚动（无滚动条）；顶栏不随滚动移动 | `:247`、`:335-348` |

### 多选态（`selectMode == true`）

| # | 手势 | 结果 | 依据 |
|---|---|---|---|
| S1 | tap | 条目卡 → `Haptics.tap()` + 切换选中（`selectedIds` 增删）；卡片左侧 `circle` / `checkmark.circle.fill`（16pt）反映状态 | `DiaryPageView.swift:501-506`、`:554-563` |
| S2 | long press ≥ 0.4s | 再 `enterSelect(id)` → 追加选中（重复长按同一条会保持选中，不会取消） | `:547-549`、`DiaryViewModel.swift:169-179` |
| S3 | tap | 顶栏 `删除(N)` `GlassPrimaryButton` → `confirmDeleteSelected()` → confirm 弹窗 → `deleteBlocks` → 重载并退出多选态 | `:357-362`、`DiaryViewModel.swift:394-409`、`:377-390` |
| S4 | tap 顶栏 返回 | **退出多选态**（清空 `selectedIds`，`.diaryQuick`），页面不关 | `DiaryViewModel.swift:204-211` |
| S5 | tap 待办标记 / 图片 | 与阅读态相同（仍可勾选、仍可预览） | §5.1 R10/R11 |

### 编辑态（`isRead == false`）

| # | 手势 | 目标 | 结果 | 依据 |
|---|---|---|---|---|
| E1 | tap | 顶栏 返回 | 编辑器非空 → confirm「退出编辑」；确认则 `discardEditing() + showRead()`（**回到本页阅读态，不关页**）。编辑器为空 → 直接回阅读态，无弹窗 | `DiaryViewModel.swift:216-227`、`:234-240` |
| E2 | tap | 顶栏 插入图片 | `showPhotoPicker = true` → PHPicker sheet（单图）→ 插到光标处（§3.4） | `DiaryPageView.swift:382-385`、`:90-94` |
| E3 | tap | 顶栏 放弃修改 | 编辑器非空 → confirm「放弃修改？」；空则什么都不做 | `DiaryViewModel.swift:250-257` |
| E4 | tap | 顶栏 保存 | `saveEditor()`（§3.7） | `:390-393` |
| E5 | tap | 正文区 | 系统 UITextView 行为：放置光标 / 长按放大镜与选择手柄 / 长按弹系统菜单（拷贝、粘贴、查询…）/ 拖动选择 | `RichTextView.swift:122-154` |
| E6 | tap | 格式栏任一按钮 | `Haptics.tap()` → 格式动作 → `becomeFirstResponder()`（键盘保持/唤回） | `RichTextView.swift:234-255`、`DiaryPageView.swift:35-37` |
| E7 | tap | 格式栏 段落样式菜单 | 弹菜单：大标题 / 小标题 / 正文（当前项带 checkmark，顺序固定） | `RichTextView.swift:260-295` |
| E8 | tap | 地点胶囊 | 弹精度菜单（§3.5），当前等级带 checkmark，新建片段多一项「重新获取位置」 | `DiaryPageView.swift:636-666` |
| E9 | tap | 「重新获取位置」胶囊/按钮 | `beginLocate()`（请求权限或直接定位） | `:623-630`、`DiaryViewModel.swift:441-460` |
| E10 | tap | 内容空白 / 顶栏空白 | `dismissKeyboard()`（关 `autoFocusEditor` + 辞去 first responder） | `:289-290`、`:345-346`、`DiaryViewModel.swift:195-202` |
| E11 | drag down | 内容区 | 交互式收键盘 | `:294`、`RichTextView.swift:133` |
| E12 | Return 键 | 列表/待办项内 | 另起同种项；空项则结束该样式；空引用行结束引用；居中空行忽略换行（§3.2） | `RichTextView.swift:187-205`、`RichTextEngine.swift:641-683` |
| E13 | tap | 条目卡 | **不存在**（编辑态只渲染一张编辑卡） | `DiaryPageView.swift:258-260` |

### 图片全屏预览（`ImagePreviewView`，`.fullScreenCover`）

| # | 手势 | 目标 | 结果 | 依据 |
|---|---|---|---|---|
| P1 | tap | 黑色背景 | `dismiss()` | `MediaViews.swift:155-157` |
| P2 | drag | 图片上拖/下拖 | 背景透明度 `max(0, 1 − \|dy\|/600)`、图片缩放 `max(0.6, 1 − \|dy\|/800)`、跟随位移 | `MediaViews.swift:153`、`:162-163` |
| P3 | drag end | 位移 > 140pt 或预测位移 > 900pt | 关闭；否则 `withAnimation(.diaryQuick)` 弹回原位 | `:169-178` |
| P4 | VoiceOver 自定义动作 | — | `dismiss()`；视图带 `.isModal` | `:182-185` |

### 分享面板

| # | 手势 | 目标 | 结果 | 依据 |
|---|---|---|---|---|
| SH1 | tap | 预览（`share.preview`） | 无（只是预览，不放大） | `ShareSheetView.swift:72-96` |
| SH2 | tap | 系统条目行右上角的叉（盖 96×96 透明热区） | `Haptics.tap()` + 关闭面板，回到日记页 | `ShareSheetView.swift:111-134` |
| SH3 | 下拉 | 面板（`presentationDragIndicator(.visible)`） | 关闭面板 | `DiaryPageView.swift:100-101` |
| SH4 | tap | 系统动作（拷贝 / 存储图像 / AirDrop / 存储到文件…） | 系统行为；完成后关闭整个面板 | `ShareSheetView.swift:158-167` |

## 5.2 对话框 / 面板 / 文案（精确文案）

通用规则（`Components.swift:500-535`）：`AppAlertItem.confirm` → 两个按钮，顺序为 **[确认文案]（default）、[取消]（cancel role）**；
`AppAlertItem.info` → **一个按钮，标签是「取消」**，点了执行 `primaryAction`（⚠️ 见 §8）。没有 toast
（全 App 只有首页的「未来日期」提示是 toast）。

| # | 触发 | 类型 | 标题 | 正文 | 按钮（zh） | en |
|---|---|---|---|---|---|---|
| D1 | 顶栏 返回（编辑态、编辑器非空） | confirm | 退出编辑 | 内容尚未保存，退出前需要处理。 | 放弃 / 取消 | Leave editor · You have unsaved content. · Discard/Cancel |
| D2 | 顶栏 放弃修改（编辑器非空） | confirm | 放弃修改？ | 未保存的内容将丢失，且无法恢复。 | 放弃 / 取消 | Discard changes? · Your unsaved changes will be lost and can’t be recovered. |
| D3 | 保存（新建、`auto_loc` 开、无位置） | confirm | 未获取到位置 | 还没有获取到地点。保存后这条记录将无法再添加位置。 | 仍然保存 / 取消 | Location unavailable · No location yet. Once saved, you can't add one to this entry. |
| D4 | 保存（新建、`start_utc` 落在别的 day_key） | confirm | 跨日编辑提示 | 本次内容将保存为 <日期> 的日记。保存并退出后，这篇日记将归档。 | 保存并退出 / 取消 | Crossing day boundary · This content will be saved to %@. It will be archived after you save and exit. |
| D5 | 保存失败（DB 异常） | info | 保存失败 | 日记保存失败，请稍后重试。 | 取消 | Save failed · Couldn't save your entry. Please try again. |
| D6 | 多选态 删除(N) | confirm | 删除编辑块 | 将删除选中的 %d 个编辑块，删除后不可恢复。 | 删除 / 取消 | Delete blocks · Delete the selected %d blocks? This cannot be undone. |
| D7 | 插入图片写文件失败 | info | 插入图片失败 | 无法读取这张图片，请换一张重试。 | 取消 | Insert image failed · Couldn't load this image. Please try another one. |
| D8 | 分享渲染失败 | info | 分享失败 | 日记图片生成失败，请稍后重试。 | 取消 | Share failed · Couldn't generate the image. Please try again later. |
| D9 | 点「写日记」（非当天，正常情况下按钮不显示） | info | 无法新增编辑块 | 历史日记只能修改原有编辑块，不能新增内容，以免时间线错乱。 | 取消 | Cannot add blocks · Past entries can only be edited in place… |
| D10 | 点已有片段 / 长按（非当天且未开「允许修改历史日记」） | info | 只读内容 | 开启「允许补写 / 修改历史日记」后，可修改已有内容。 | 取消 | Read-only content · Enable “Edit past entries” to modify existing content. |

面板/弹层：

| 弹层 | 呈现 | 文案 / 内容 | 依据 |
|---|---|---|---|
| 相册选择器 | `.sheet`（系统 PHPicker，单图） | 系统 UI | `DiaryPageView.swift:90-94`、`MediaViews.swift:111-144` |
| 分享面板 | `.sheet` + `.presentationDetents([.large])` + drag indicator | 预览 + 系统动作；进度文案「正在生成分享图…」 | `:95-102`、`ShareSheetView.swift:98-107` |
| 图片预览 | `.fullScreenCover(item:)` | 无文字 | `:103-105` |
| 段落样式菜单 | SwiftUI `Menu`（系统 popover/action sheet 形态） | 大标题 / 小标题 / 正文 | `RichTextView.swift:260-295` |
| 地点精度菜单 | 同上 | 精确地点 / 街道 / 区县 / 城市 / 省份（+ 重新获取位置） | `DiaryPageView.swift:636-666` |
| 系统文本菜单 | UIKit 默认 | 拷贝 / 粘贴 / 查询 / 替换… | 系统 |

## 5.3 导航与转场

| 从 | 动作 | 到 | 动画 |
|---|---|---|---|
| 首页日历格 / 搜索结果 / URL scheme / LaunchIntent | 打开日记 | 日记页（fullScreenCover，整屏） | 系统 cover 转场 |
| 日记页 阅读态 | 顶栏 返回 | 关闭 cover，回到触发页 | 系统 cover 转场；关闭后 `bumpDiaryVersion()` |
| 日记页 阅读态 | 点条目卡 / 写日记 | **本页**编辑态（不推新页面） | `withAnimation(.diaryStandard)`（0.3s easeInOut） |
| 日记页 编辑态 | 顶栏 返回（确认放弃）/ 保存完成 | **本页**阅读态 | `.diaryStandard` |
| 日记页 编辑态 | 保存且内容为空且当天无任何片段 | 关闭整页（`onDismiss`） | 系统 cover |
| 日记页 | 点图片 | 图片预览全屏 cover（`ImagePreviewView`） | 系统 cover；关闭后回日记页 |
| 日记页 阅读态 | 长按卡片 | 本页多选态 | `.diaryQuick`（0.22s easeOut） |
| 日记页 | 顶栏 分享 | 分享 sheet（`.large`） | 系统 sheet |
| 日记页 | `.diaryVersionChanged` | 本页 `load()`：**回到阅读态**、刷新 blocks/`startUtc` | 无动画（状态直接替换；blocks 变化时有 `.diaryStandard` 的隐式动画，`:291`） |

编辑态进入后 0.3s 会把编辑卡滚到视口中央（`:309-317`），随后键盘弹起由光标规则接管（§1.10）。

## 5.4 副作用（写库 / 通知 / 提醒）

| 动作 | 数据库 | 通知 | 其它 |
|---|---|---|---|
| 保存（新建 / 编辑） | `addBlock` / `addBlockToDiary` / `updateBlockContent` | `bumpDiaryVersion()` | 新建当天记录时 `ReminderService.markTodayWritten()`（打卡） |
| 勾选待办 | `updateBlockContent(blockId, contentJson)`（只改 `done`） | `bumpDiaryVersion()` | 触发本页重载 |
| 删除片段 | `deleteBlocks([id…])` | `bumpDiaryVersion()` | 退出多选态 |
| 插入图片 | **不写库**（只写文件 `Documents/images/img_<ms>.jpg`，随保存才落 `content_json`） | — | 放弃修改不会删文件（孤儿文件由仓库的清理逻辑处理） |
| 分享 | 写临时文件 `JustDiary-<dayKey>.png` | — | 上次的文件在下次分享时删除 |
| 关闭页面 | — | `bumpDiaryVersion()`（cover 的 `onDismiss`） | 首页/搜索刷新 |

---

# 6. 无障碍与动态字体

## 6.1 可访问性标签 / trait / 自定义动作（实测清单）

| 元素 | label（zh / en） | trait / id | 依据 |
|---|---|---|---|
| 顶栏 返回 | 返回 / Back | 44pt `.glass` 圆按钮 | `DiaryPageView.swift:352-355`、`Components.swift:340` |
| 顶栏 搜索 | 搜索 / Search | active 时 `.isSelected` + symbol bounce | `:364-370`、`Components.swift:339-341` |
| 顶栏 写日记 | 写日记 / Write | 仅当天出现 | `:371-376` |
| 顶栏 分享 | 分享 / Share | — | `:377-380` |
| 顶栏 插入图片 | 插入图片 / Insert image | — | `:382-385` |
| 顶栏 放弃修改 | 放弃修改 / Discard Changes | — | `:386-389` |
| 顶栏 保存 | 保存 / Save | active=true（白色胶囊底）→ `.isSelected` | `:390-393`、`:234-255` |
| 顶栏 删除(N) | `删除(%d)` / `Delete (%d)`（含 `trash` 图标） | `GlassPrimaryButton` | `:357-362` |
| 搜索框 | placeholder「在当前日记中查找…」 | `TextField`，submitLabel `.search` | `:401-405`、`Components.swift:571-576` |
| 命中步进 | 上一个匹配 / 下一个匹配 | 普通 Button，`minWidth/minHeight 44` | `:407-412`、`:424-438` |
| 命中计数 | `n/总数` | `GlassCountBadge` | `:406`、`Components.swift:416-438` |
| 条目卡 | — | `.accessibilityElement(children: .contain)` + id `diary.blockCard`；选中时 `.isSelected` | `:545-551` |
| 条目卡 多选圆圈 | — | 纯 `Image`（无独立 label） | `:501-506` |
| 「编辑中」徽标 | 编辑中 / Editing | `GlassCountBadge` | `:523-525` |
| 正文内图片 | 无 label，仅 `.isImage` | 依赖图片所在文本 | `MediaViews.swift:44` |
| 编辑卡 | — | id `diary.editorCard` | `:599-600` |
| 格式栏容器 | — | `.accessibilityElement(children: .contain)` + id `editor.formatBar` | `RichTextView.swift:370-371` |
| 格式栏按钮 | 段落样式 / 居中 / 列表 / 引用 / 待办 / 粗体 / 斜体 / 删除线 / 下划线 | active 时 `.isSelected`；「段落样式」另有 `accessibilityValue` = 当前样式名（大标题/小标题/正文） | `RichTextView.swift:234-255`、`:293-294` |
| 地点胶囊 | label「选择地点」+ value = 当前地址文本 | `Menu` | `DiaryPageView.swift:663-665` |
| 分享预览 | 分享预览 / Share preview | id `share.preview` | `ShareSheetView.swift:88-89` |
| 分享进度 | — | id `share.rendering` | `:106` |
| 分享关闭热区 | 取消 / Cancel | id `share.close`，96×96 透明 | `:122-134` |
| 图片预览 | — | `.isModal` + `accessibilityAction { dismiss() }` | `MediaViews.swift:182-185` |
| 弹窗 | 标题 + 正文（见 §5.2） | 系统 alert，`AppAlertItem` | `Components.swift:518-535` |

**缺口（Android 端应主动补齐，不要照抄）**：

- 待办勾选没有独立 a11y 元素/自定义动作 —— 它靠「整行 tap 命中区」实现，VoiceOver 下无法明确操作
  （`ReadTextView.swift:178-182`）；
- 图片只有 `.isImage`，没有 label，也没有「打开大图」的 action（`MediaViews.swift:44`）；
- 条目卡与卡片内容都是 `children: .contain`，VoiceOver 会把卡片当容器遍历。

## 6.2 动态字体（Dynamic Type）

机制（`Views/Components/DynamicType.swift:1-73` + `DesignSystem.swift:88-220`）：

- `RootView` 通过 `@Environment(\.dynamicTypeSize)` 读系统档位，注入 `\.diaryDynamicTypeSize`
  （`RootView.swift:65`），并**在 fullScreenCover 里再注入一次**（`:97`）——presentation 不继承内层环境。
- `.diaryFont(size, weight)` 把 design size 解析成 `DynamicTypeMetrics.scaled(size, for: typeSize)`
  = `size × multiplier`；multiplier 用 `UIFontMetrics(forTextStyle: 该尺寸最接近的 Apple 样式)`
  缩放到目标 content-size category，再**按样式上限 clamp**，并保证 `≥ 1`；默认档（`.large`）恒等于原值。
- 编辑器的 UIKit 字体走 `EditorFont.font` → `DynamicTypeMetrics.font`（同一套）；`PartsCodec` 也用它，
  所以阅读态、编辑态以及其它页面的文字都在同一条曲线上；**唯一的例外是分享长图** ——
  它是离屏 Core Graphics 绘制，字号写死（`ImageShareService.swift:54-68`），不随系统字号变化。

**上限表**（`DesignSystem.swift:124-136`，按 text style）：

| text style | 对应 design size（本功能用到的） | 上限 | AX5 实得 |
|---|---|---|---|
| `.title1` | 28（大标题） | ×1.40 | 39.2 |
| `.title2` | 22（小标题） | ×1.45 | 31.9 |
| `.title3` | 20（Hero 日期 `TypeSize.cardTitle`） | ×1.45 | 29.0 |
| `.body` | 17（正文 / 列表 / 待办 / 编辑器最小高度系数） | ×1.50 | 25.5 |
| `.subheadline` | 15（引用 / `TypeSize.rowTitle`） | ×1.60 | 24.0 |
| `.footnote` | 13（`TypeSize.meta`：时间、地点、segments） | ×1.65 | 21.5 |
| `.caption1` | 12（`TypeSize.caption` / `TypeSize.badge`：今日胶囊、编辑中） | ×1.70 | 20.4 |

**会跟着字号长大的东西**：所有 `.diaryFont` 文本；编辑器字体（design size → 曲线）；placeholder（手动同步，
`RichTextView.swift:25-31`）；编辑器最小高度 `160 × multiplier(17)`（默认 160 → AX5 约 240，
`RichTextView.swift:29`、`:44`）；列表/待办标记（`13 × scale` 与 `15 × scale` 的 bounds，
`RichTextEngine.swift:1043-1049`）；`TabBarClearance`（`140 × multiplier(15)`，`Components.swift:605-615`）；
`GlassIconBadge`（未在本功能用到）。

**不会跟着字号长大的东西**（复刻时注意）：段落 `lineSpacing` / `paragraphSpacing` / `paragraphSpacingBefore`
（design 点，`RichTextEngine.swift:40-71`）；图片上下 10.2pt（`EditorDesignSize.imageSpacing`）；
所有卡片内边距、圆角、按钮尺寸（`PressableGlassIcon` 直径固定 44、格式栏按钮固定 34×40）。

**上限的意义**：Apple 原始曲线在 AX5 会把 17pt 拉到约 53pt，而这里 clamp 到 1.50（≈25.5pt），
保证 28/22/17/15 的层级在任何档位都保持单调（`DesignSystem.swift:107-123`）。
UI 测试用 `-UIPreferredContentSizeCategoryName UICTContentSizeCategoryAccessibilityXXXL` 驱动
（`EditorTypeSizeUITests.swift:39-53`、`:152-159`）。

**保存安全性**（跨字号来回编辑不失真）：字号只由段落的 `style` 决定；从 textStorage 反解时先用
`.diaryDesignSize`，缺失时**在正向映射里精确匹配**（误差 < 0.01），最后才用除法兜底 —— 近似反解会在
每次保存后向上漂移，直到正文越过分级阈值被写成小标题（`RichTextEngine.swift:187-210`、
`EditorTypeSizeUITests.swift:97-147` 连续保存 3 轮验证）。

---

# 7. 数值速查表（含 `file:line`）

> 路径省略 `JustDiary/` 前缀。所有数值均为**代码实测值**，不是设计稿推测。

## 7.1 设计令牌（Views/Components/DesignSystem.swift、Views/Components/Components.swift）

| 常量 | 值 | 位置 | 含义 |
|---|---|---|---|
| `Radius.card` | 20 | Views/Components/DesignSystem.swift:15 | 内容卡、Hero 卡、编辑卡圆角 |
| `Radius.panel` | 26 | :17 | 格式栏 / 浮层圆角 |
| `Radius.field` | 14 | :20 | 输入框、内嵌子表面 |
| `Radius.image` | 14 | :22 | 内联图片圆角（阅读 + 编辑器附件都烘进图里） |
| `Radius.badge` / `bar` / `hairline` | 12 / 4 / 1 | :24-28 | 徽标 / 粗描边 / 2pt 标记 |
| `Spacing.card` | 12 | :41 | 卡片内边距（阅读卡与编辑卡**都用它**） |
| `Spacing.rowIcon` | 12 | :43 | 图标与文字间距 |
| `Spacing.hitTarget` | 44 | :45 | 最小点击目标（搜索步进按钮使用） |
| `TypeSize.cardTitle` | 20 | :63 | 阅读态 Hero 日期 |
| `TypeSize.meta` | 13 | :83 | 时间 / 地点 / 片段数 |
| `TypeSize.caption` | 12 | :85 | 「今日」胶囊、地点图标 |
| `TypeSize.badge` | 12 | :81 | 计数徽标文字 |
| `TypeSize.rowTitle` | 15 | :69 | 搜索框文字、`TabBarClearance` 的缩放基准 |
| `TypeSize.chip` | 14 | :79 | 胶囊标签 |
| `Animation.diaryQuick` | `.easeOut(0.22)` | Components.swift:7 | 多选态、搜索栏、预览回弹、光标滚动动画 |
| `Animation.diaryStandard` | `.easeInOut(0.3)` | :8 | 进出编辑、保存、块列表变化、搜索跳转 |

## 7.2 日记页（Views/Diary/DiaryPageView.swift）

| 数值 | 位置 | 含义 |
|---|---|---|
| 顶栏容器 padding：水平 16 / 上 4 / 下 6 | :337-339 | 悬浮顶栏外边距 |
| 顶栏 HStack spacing 8、水平 padding 4 | :351、:396 | 顶栏内部 |
| `topBarHeight` 采样门槛：> 10 且变化 > 0.5 | :341 | 防止噪声抖动 |
| 内容顶部让位 `topBarHeight + 10` | :278 | 悬浮顶栏让位（替代 `safeAreaInset`） |
| 内容 VStack spacing 12 | :248 | 卡片间距 |
| 内容列 `contentColumn(660)` | :284 | 正文列上限 660 |
| 键盘占位高度 `keyboardHeight + 160` | :265 | 让键盘 + 底部余量 |
| `TabBarClearance(base: 140)` | :274 | 键盘收起时底部占位（× 动态系数） |
| 光标让位边距 ±8（底部 `formatBarTop − 8`、顶部 `topBarHeight + 8`） | :206-207 | caret-not-hidden |
| 光标滚动死区 `\|delta\| > 1` | :214 | 避免抖动 |
| 键盘弹起后首次延迟 0.1s，重试 [0.15, 0.3, 0.5, 0.75, 1.0, 1.4] | :324-328 | 布局分几帧稳定 |
| 键盘动画默认时长 0.25s | :229 | 缺省 curve → easeInOut |
| 进入编辑后 0.3s 把编辑卡滚到中央 | :311-313 | 进编辑的定位 |
| 格式栏底部 padding：`keyboardHeight + 8` / `safeAreaBottom + 12` | :49 | 键盘上方 8pt / home indicator 上方 12pt |
| 卡片长按阈值 0.4s | :547 | 进入多选态 |
| Hero 卡 padding 14、底额外 2、圆角 20 | :488-491 | Hero 卡 |
| Hero 顶行 spacing 8 / 副信息 spacing 4 | :444、:460 | Hero 内部 |
| 「今日」胶囊：12pt medium、padding h8/v3、shadow r6 y1 | :449-457 | 今日标记 |
| 条目卡内 VStack spacing 8；抬头 HStack spacing 8；时间/地点 VStack spacing 3 | :499-507 | 条目卡 |
| 多选圆圈 16pt，`padding(.top, 1)` | :502-506 | 多选指示 |
| 时间胶囊 padding h10/v5；地点胶囊 HStack spacing 4、padding h10/v5 | :577-581、:669-681 | 编辑态胶囊 |
| 搜索命中序号 `min(hitIndex+1, count)/count` | :406 | 计数徽标 |
| 搜索输入防抖 150ms | DiaryViewModel.swift:81 | 页内搜索 |
| 页面背景色球：300/250/210pt，blur 70/65/60 | Components.swift:84-89 | `BlobBackground(dense: true)` |

## 7.3 编辑器（Views/Diary/RichTextView.swift、RichTextEngine.swift）

| 数值 | 位置 | 含义 |
|---|---|---|
| 最小高度 `160 × multiplier(17)` | RichTextView.swift:29、:44 | 输入区最小高度（默认 160） |
| `textContainerInset` = (top 10, left 0, bottom 10, right 0) | :128 | 编辑器内缩（左右为 0，保证与阅读等宽） |
| `lineFragmentPadding = 0` | :129 | 文本左边界与卡片内边距对齐 |
| intrinsic 兜底宽度 340 | :47 | 仅在拿不到被提议宽度时（已被 `sizeThatFits` 覆盖） |
| placeholder 宽 = 视图宽 − 左右 inset − 10；高 = `ceil(lineHeight)` | :74-77 | 手绘 placeholder |
| 自动聚焦延迟 0.25s | :148-152 | `autoFocus` |
| 宽度回调门槛：> 40 且变化 > 1 | :62 | 旋转 / 分屏重排触发 |
| 格式栏按钮：图标 15pt medium、`minWidth 34`、`minHeight 40`、spacing 4 | :242-244、:311 | 9 个按钮默认约 360pt，402pt 屏一条放得下 |
| 格式栏样式菜单按钮 `minWidth 46`、chevron 9pt、HStack spacing 3 | :278-289 | 段落样式入口 |
| 格式栏分隔条高 20、`outlineVariant` 0.5 | :317-319、:332-334 | 三组之间 |
| 格式栏 `padding(.vertical, 8)` / `padding(.horizontal, 12)` | :352、:367 | 内外边距 |
| 格式栏背景：圆角 26 + shadow r14 y4 | :377-381 | 玻璃面板 |
| disabled 按钮 `opacity 0.35` | :252 | 居中按钮在列表/引用/待办行上 |
| design size：28 / 22 / 17 / 15 | RichTextEngine.swift:26-33 | 段落字号阶梯 |
| lineSpacing：quote 0.5×、其它 0.13× | :40-42 | 3.64 / 2.86 / 2.21 / 7.5 |
| paragraphSpacing：title/heading 0.15×、quote 0.4×、body 0 | :52-59 | 4.2 / 3.3 / 6.0 / 0 |
| paragraphSpacingBefore：title/heading 0.35×、quote 0.4×、body 0 | :64-71 | 9.8 / 7.7 / 6.0 / 0 |
| `imageSpacing = 17 × 0.6 = 10.2` | :122 | 图片上下留白（设计点，不随字号放大） |
| 斜体斜切矩阵 `c = 0.24` | :160-161、:323 | CJK 斜体可见性 |
| 字号反解精确匹配容差 0.01 | :206 | 防止正文被写成标题 |
| 列表/待办标记：`pointSize 13 × scale`，bounds `(0, −2, 15×scale, 15×scale)` | :1043-1049 | 标记尺寸 |
| 图片插入：`maxW = max(60, 文本列宽)`、`displayH = max(40, maxW×ratio)`、圆角 14、兜底列宽 343 | :845-852 | 插入度量 |
| 图片载入兜底：stored w/h = 300/200，`fallbackW = max(60, Screen.width − 76)`、`maxW = max(60, imageMaxWidth ?? fallbackW)` | :1073-1076 | codec 度量 |
| 图片重排 `w = max(60, maxWidth)`，`h = storedH×w/storedW` | :930-931 | 旋转重排 |
| 取图 `maxPixel = max(storedW, storedH) × 3` | :933、MediaViews.swift:28 | 内存降采样 |
| 阅读 chunk：删结尾换行；纯图片块去段落样式 | :1139-1146 | 阅读态两条减法 |
| 阅读正文容器内缩 (top 2, left 0, bottom 2, right 0) | MediaViews.swift:82 | 阅读 chunk |
| 段落终止换行携带块属性（保证再回车沿用同样式） | :1211-1216 | 段落延续 |
| 空行不入库 | :1231 | 落库规则 |
| 已完成待办：删除线 + `onSurface` 45% alpha | :1167-1170 | 已完成样式 |

## 7.4 ViewModel / 服务（ViewModels/DiaryViewModel.swift、Services/*）

| 数值 | 位置 | 含义 |
|---|---|---|
| 图片降采样 `maxPixel 2048`、JPEG `quality 0.85` | DiaryViewModel.swift:543-544 | 插入图片压缩 |
| 图片文件名 `img_<epochMs>.jpg`，目录 `Documents/images/` | :545-550、Data/DiaryRepository.swift:45-49 | 落盘规则 |
| 跨日弹窗确认后的延迟 0.3s | :313 | 让第一个弹窗收干净 |
| 定位权限等待 600ms | :448 | 请求权限后的重试窗口 |
| 搜索防抖 150ms | :81 | 页内搜索 |
| 分享失败 → 关面板 + info 弹窗 | :589-593 | 失败路径 |
| 分享文件名 `JustDiary-<dayKey>.png`，临时目录、PNG、atomic | :604-610 | 文件名规则 |
| 分享图取色 `UITraitCollection.current.userInterfaceStyle` | :568 | 深色判定 |
| 图片缓存 200 张 / 150MB | Services/MediaStore.swift:53-54 | `NSCache` |
| 阅读取图 `maxPixel 1600`（预览）/ `max(w,h)×3`（内联） | MediaViews.swift:158、:28 | 取图精度 |
| 内容解析缓存 300 条 | Services/MediaStore.swift:13 | `ContentPartCache` |

## 7.5 分享面板（Views/Diary/ShareSheetView.swift）

| 数值 | 位置 | 含义 |
|---|---|---|
| 横屏判据 `geo.width > geo.height` → HStack | :28-36 | 布局切换 |
| 横屏动作区宽 `min(430, geo.width × 0.55)` | :34 | 右栏 |
| 竖屏动作区高 `min(max(340, H × 0.48), max(280, H − 200))` | :141-143 | 系统动作区 |
| 预览图 `frame(maxWidth: 720)`、圆角 14、描边 1pt `onSurface 8%`、shadow r18 y8 | :78-85 | 预览卡 |
| 预览 padding 水平 20 / 垂直 18 | :86-87 | 预览外边距 |
| 进度占位：VStack spacing 14、`minHeight 220` | :99-105 | 生成中 |
| 关闭热区 96×96（竖屏叉在距右 40 / 距顶 76，横屏 37 / 44） | :122-124、:113-121 | 远程视图叉的替代点击区 |
| `sizeThatFits` 兜底 (320, 360) | :174-178 | 内嵌 activity VC |

## 7.6 长图渲染（Services/ImageShareService.swift）

| 数值 | 位置 | 含义 |
|---|---|---|
| 画布宽 720、`scale 2`、`opaque = true` | :24、:154-156 | 输出位图 |
| margin 56 / top 56 / bottom 48 | :30、:32、:51 | 页面留白 |
| content = 608 | :31 | 内容宽 |
| brandH 34 / afterBrand 24 | :33-34 | 品牌行 |
| dateH 74 / afterDate 12 | :35-36 | 大日期行 |
| metaH 34 / afterMeta 26 | :37-38 | 起始时间行 |
| ruleWidth 64 / ruleHeight 5 / afterRule 40 | :39-41 | 品牌色短线 |
| entryHeadH 38 / afterEntryHead 14 | :42-43 | 条目时间行 |
| cardPadH 30 / cardPadV 28 / cardRadius 26 | :44-46 | 卡片 |
| betweenEntries 30 / partGap 20 / looseGap 26 | :47-49 | 段间距 |
| beforeFooter 46 / footerH 30 | :50-51 | 页脚 |
| 字体：brand 26 semibold / year 22 medium / date 58 bold / weekday 28 medium / meta 25 / time 27 semibold / loc 23 / title 38 bold / heading 32 semibold / body 29 / quote 27 / item 29 / footer 22 medium | :55-67 | 字号 |
| 行高：title 54 / heading 46 / body 48 / quote 44 / item 46 / footer 30 | :71-76 | 行高（min=max） |
| 品牌点 radius 7、位置 (margin+7, y+17) | :175 | 品牌行圆点 |
| 星期 x = margin + dateWidth + 16，宽 `max(40, content − dateWidth − 16)` | :194-196 | 基线对齐 |
| 引用：padV 22、文字 x+26、文字宽 width−46、盒圆角 16、左侧条 5×（boxH−20）圆角 2.5、条内缩 10 | :301-311 | 引用块 |
| 列表：圆点 r5.5 于 (x+9, cursor+23−3)，markerW 30 | :338-344 | 列表项 |
| 待办：复选框 30×30（y+8，圆角 8）、对勾线宽 3.4、markerW 48、项间 16 | :338-354、:451-469 | 待办项 |
| 图片：`h = max(120, width × ratio)`、圆角 20、aspect-fill | :321-326、:382-394、:438-450 | 图片块 |
| 页脚细线 608×1 圆角 0.5、faint 35%；左右各占 60% 宽 | :263-271 | 页脚 |
| 卡片阴影 offset(0,6) blur 26、描边 2pt（inset 1、半径 25） | :417-425 | 卡片 |
| 背景渐变 + 两枚径向晕染：(0.94W, −80) r430 α0.10、(0.02W, 0.44H) r380 α0.06 | :473-496 | 背景 |

---

# 8. 脆弱点、与旧文档的差异、测试钩子

## 8.1 意外点 / 脆弱点（复刻时先读这一节）

1. **没有底部动作栏**：阅读 / 编辑 / 分享 / 搜索全在悬浮顶栏；编辑态唯一贴底的是格式栏。
   任何「底部 read/edit/share/search 四按钮」的描述都来自旧版设计，不适用于当前代码。
2. **info 弹窗只有一个按钮，标签是「取消」**（`Components.swift:528-530`）：保存失败 / 插入图片失败 /
   分享失败 / 只读内容 / 无法新增编辑块，用户看到的都是「取消」。这是通用组件的现状，Android 端建议
   改成「知道了」，但要意识到 iOS 就是这样。
3. **「编辑中」徽标（`GlassCountBadge`）在当前状态机下不可达**：`blockCard` 只在 `isRead` 时渲染，
   而 `editingIndex != nil` 时 `isRead` 恒为 false（`DiaryPageView.swift:523-525`、`DiaryViewModel.swift:145-167`）。
4. **`.diaryVersionChanged` 会重载整页并把 `isRead` 复位为 true**（`DiaryPageView.swift:66-68`、
   `DiaryViewModel.swift:57-69`）。目前只有阅读态会写库（勾待办），所以看不出问题；一旦 Android 端
   在编辑态触发写库（例如自动保存），必须避免用同一路径重载，否则编辑内容与光标会丢。
   重载还会重置 `startUtc = now` 并清空搜索命中。
5. **新建片段的时间戳 = 打开日记页的时刻**，不是点「写日记」的时刻（`load()` 写 `startUtc`，
   `enterWrite()` 不刷新）。跨日弹窗判定用的就是这个时间。
6. **卡片的内边距（12pt 边框带）点击不会进入编辑**：只有抬头区（时间/地点那行，`:527-530`）和正文
   chunk（`ReadTextView` 的 tap）会触发 `handleBlockTap`；点在卡片 padding 上会落到页面级
   `dismissKeyboard`（`:289-290`）。
7. **段落行距/段距不随动态字号放大**（`RichTextEngine.swift:40-71` 是 design 点）。大字号下段落看起来更紧，
   这是当前实现的现状而非 bug 修复目标。
8. **阅读态搜索：命中计数与高亮是两套算法**。计数来自 ViewModel 按 part 扫描（`DiaryViewModel.swift:87-111`），
   高亮来自每个 chunk 自己的字符串扫描（`ReadTextView.swift:143-168`）——跨图片/跨 chunk 的关键词**不会**被高亮，
   但会计入计数。
9. **搜索跳转只滚到「卡片」**（`scrollTarget = "block-<id>"`，anchor `.center`），不定位到卡片内的具体段落。
10. **图片没有最大高度**：只有最小高度（编辑器插入 40pt、长图 120pt）与最小宽度 60pt。竖长图会占很高。
11. **插入图片后「放弃修改」不会删掉刚写入 `Documents/images/` 的文件**（`DiaryViewModel.swift:542-557`
    先落盘再插入），会成为孤儿文件（仓库另有清理逻辑）。
12. **分享面板的关闭依赖 96×96 透明热区**覆盖系统远程视图画的叉（`ShareSheetView.swift:111-134`）：
    系统改版后位置会漂移，属于已知脆弱点。
13. **编辑器没有自建 undo/redo**：只有 UIKit 的 shake-to-undo；「放弃修改」是唯一的回退手段且不可恢复
    （`DiaryViewModel.swift:244-249` 的注释明确说明）。
14. **多选态下待办仍可勾选、图片仍可预览**（`DiaryPageView.swift:531-541` 没有 selectMode 分支），
    与「多选时点击 = 选择」的心智不完全一致。
15. **`start_time_utc` 永远写入**，`auto_time` 只是显示开关（`AppSettings.swift:4-11`）——跨端契约，别改成 0。
16. **`-ui-test-*` 启动参数在生产构建里也会被解析**（`DiaryPageView.swift:73-81`、`:106-137`、
    `JustDiaryApp.swift:33-41`），只是没有对应参数时不生效。

## 8.2 与旧文档不一致的地方（以代码为准）

| 旧文档说法 | 代码实际 |
|---|---|
| `docs/editor-format-behaviors.md:124` 「有选区时字符样式高亮只看选区首字符（B8）」 | 已修：要求**整个选区**都带该 trait 才高亮（`RichTextEngine.swift:798-818`；同文档 `:203` 也记为已修） |
| 同文档按钮总表 `:111` / `:113` 的「换行后」列写「**不延续**（回到正文）」 | 与同文档 R3（`:66-73`）及代码不一致：项内回车**另起一项、延续标记**（`RichTextEngine.swift:664-672`），只有**空项**再回车才结束样式（`:656-661`） |
| `docs/design-system.md` 等提到「阅读列 660 / 编辑列 620」 | 已统一：两种模式都是 `contentColumn(660)`（`DiaryPageView.swift:279-284`，B18 已修） |
| 「分享入口先弹只有『分享』按钮的小菜单，再出系统面板」 | 一步直达：预览 + 系统动作同屏（`ShareSheetView.swift:4-16`，测试 `ShareSheetUITests.swift:55-84`） |
| 「格式栏横屏竖排贴正文右侧」 | 已废弃，横竖屏都是横排一条（`RichTextView.swift:224-229`） |
| `docs/ios-reference/diary.md`（v1）中的底部动作栏 / 三步分享 / 字号写死 | 均已被本文档取代 |

## 8.3 测试钩子（Android instrumentation 可 1:1 对照）

启动参数（`ProcessInfo.processInfo.arguments`）：

| 参数 | 作用 | 位置 |
|---|---|---|
| `-ui-test-open-day <yyyy-MM-dd>` | 直接打开某天的日记页 | RootView.swift:119-131 |
| `-ui-test-reset-data` | 打开前清掉该天的 diary | RootView.swift:122-128 |
| `-ui-test-reset-settings` | 设置恢复默认 | JustDiaryApp.swift:33-35 |
| `-ui-test-no-autoloc` | `auto_loc = false`（保存不再弹「未获取到位置」） | JustDiaryApp.swift:36-41 |
| `-ui-test-no-location` | `currentLocation()` 直接返回 nil | LocationService.swift:34-40 |
| `-ui-test-no-autofocus` | 进入编辑不自动弹键盘 | DiaryViewModel.swift:191-193 |
| `-ui-test-insert-image` | 进编辑 0.35s 后自动插一张 600×300 的测试图 | DiaryPageView.swift:73-81、:148-157 |
| `-ui-test-editor-state` | 暴露探针 `editor.state`：`"<styles>\|<text>"` 或 `"read:<styles>"` | DiaryPageView.swift:129-136、:166-181 |
| `-ui-test-editor-caret` | 暴露探针 `editor.caret`：`"minY,maxY"`（窗口坐标） | :111-119、:159-164 |
| `-ui-test-editor-image` | 暴露探针 `editor.image`：`"宽,高"` 或 `"none"` | :120-128、:141-146 |
| `-ui-test-open-editor` | 直接打开今天的编辑页 | RootView.swift:116-118 |
| `-ui-test-tab <home/footprint/search/settings>` | 启动到指定 tab | RootView.swift:106-115 |
| `-ui-test-state` | 暴露 `app.state`（语言/主题） | RootView.swift:73-85 |

其它入口：URL scheme `justdiary://editor?dayKey=yyyy-MM-dd`（`App/JustDiaryApp.swift:17-27`）。

## 8.4 Android（Compose + M3）落地对照

| iOS | Compose / M3 建议 |
|---|---|
| 悬浮顶栏（玻璃圆按钮，44pt） | `TopAppBar` + `IconButton`（40dp 触控区，`IconButtonDefaults`）；`title` 留空，`navigationIcon` = 返回，`actions` = 搜索/编辑/分享 或 图片/放弃/保存 或 删除(N) |
| 多选态 | 顶栏换成 `TopAppBar` + 删除 `TextButton`（destructive），卡片左侧 `Checkbox`/`Icon` |
| Hero 卡 | `Card`（`RoundedCornerShape(20.dp)`，`CardDefaults.cardColors(containerColor = surfaceContainer)`）+ `Text` 20sp medium |
| 条目卡 | `Card` + `Column`（12dp padding，8dp 间距） |
| 格式栏 | `BottomAppBar`（`Modifier.imePadding()`）或自定义 `Surface`（26dp 圆角）+ `Row(horizontalScroll)`，9 个 `IconButton` |
| 富文本编辑 | `BasicTextField` + `AnnotatedString`/`SpanStyle` + `ParagraphStyle`；段落样式靠「行首标记 + 行样式表」自行维护（Compose 没有 `typingAttributes`，需要 `TextFieldValue` 的 selection + 当前样式状态机） |
| 阅读分块 | `Column` + 逐 part 渲染（`Text` 用 `buildAnnotatedString`；图片 `AsyncImage`/`Image`；待办 `Row` + `Checkbox`，整行 `clickable`） |
| 待办勾选 | `Checkbox` + `Modifier.clickable` 整行触发，并补 a11y（iOS 缺这点） |
| 图片 | `Image(Modifier.fillMaxWidth().aspectRatio(w/h).clip(RoundedCornerShape(14.dp)))`；缺失时 `Spacer` 保位 |
| 长图分享 | `Bitmap` + `Canvas`（同样 720×2 倍图）；系统分享用 `Intent.ACTION_SEND` + `FileProvider` 的 `content://` URI，文件名保持 `JustDiary-<dayKey>.png` |
| 对话框 | `AlertDialog`（confirm：确认 + 取消；info：单按钮） |
| 动态字体 | `sp` + `fontScale`，但要复刻 iOS 的**每档上限**（§6.2 表），否则大字号下 28/22/17 的层级会倒挂 |







