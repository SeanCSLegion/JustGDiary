# iOS 实现级 UI/交互规格 v3 —— App 外壳 · 搜索 · 足迹 · 设置

> **来源**：`/Users/cov/JustADiary` 当前 iOS 源码（SwiftUI，iPhone-only，iOS 26/27 Liquid Glass 时代 API）。
> 本文所有非显然结论都带 `file:line`（相对 iOS 仓库根目录），路径直接用 `JustDiary/...`。
> **一切以代码为准**：本文已逐行核对；与旧文档 `docs/ios-reference/footprint-search-settings-root.md`（记于 Components.swift=702 行 / SearchView=515 行 / AdaptiveLayout=184 行 / String Catalog=196 键的时代）冲突处，**以本文为准**，差异见 §8。
> **目标读者**：用 Jetpack Compose + Material 3 在 Android 上重建这四屏的工程师。文中给出 iOS 的**绝对数值**，Compose 侧按 dp 1:1 使用即可（iOS pt ≈ Android dp）。
> **环境基准**（文档中的实测数字都基于它）：iPhone 18 Pro，竖屏 `402×874pt`，横屏 `874×402pt`；竖屏安全区 `T62 L0 B34 R0`，横屏 `T0 L62 B20 R62`；横屏可用内容宽 `874-62-62 = 750pt`。

---

## 0. 文件清单（本文覆盖范围）

| 角色 | 文件 | 行数 |
|---|---|---|
| 外壳 / 浮条 | `JustDiary/Views/RootView.swift` | 169 |
| 全局状态 | `JustDiary/ViewModels/AppState.swift` | 9 |
| 深链意图 | `JustDiary/Services/LaunchIntent.swift` | 16 |
| App 入口 / URL scheme | `JustDiary/App/JustDiaryApp.swift` | 55 |
| 搜索屏 | `JustDiary/Views/Search/SearchView.swift` | 519 |
| 搜索 VM | `JustDiary/ViewModels/SearchViewModel.swift` | 312 |
| 足迹屏 | `JustDiary/Views/Footprint/FootprintView.swift` | 330 |
| 足迹 VM | `JustDiary/ViewModels/FootprintViewModel.swift` | 109 |
| 足迹聚合 | `JustDiary/Services/FootprintDataService.swift` | 137 |
| 设置屏 | `JustDiary/Views/Settings/SettingsView.swift` | 462 |
| 设置 VM | `JustDiary/ViewModels/SettingsViewModel.swift` | 182 |
| 通用组件 | `JustDiary/Views/Components/Components.swift` | 718 |
| 设计 token | `JustDiary/Views/Components/DesignSystem.swift` | 220 |
| Dynamic Type 管线 | `JustDiary/Views/Components/DynamicType.swift` | 73 |
| 版面判定 | `JustDiary/Views/Components/AdaptiveLayout.swift` | 254 |
| 颜色 token | `JustDiary/Services/Theme.swift` + `JustDiary/Resources/Assets.xcassets/*.colorset` | 69 |
| 文案 | `JustDiary/Services/L10n.swift` | 249 |
| 触觉 | `JustDiary/Services/Haptics.swift` | 10 |
| 设置模型 / 存储 | `JustDiary/Models/AppSettings.swift` | 115 |
| 高亮工具 | `JustDiary/Services/SearchUtil.swift` | 126 |
| 日期工具 | `JustDiary/Models/DateUtil.swift` | 93 |
| 文案表 | `JustDiary/Resources/Localizable.xcstrings` | 198 键，`en`(source) + `zh-Hans` |

**编译期依赖的 iOS 26/27 API（Android 必须自建等价物）**：`.glassEffect(_:in:)`、`.buttonStyle(.glass)`、`.buttonBorderShape(.capsule/.circle)`、`Tab(_:systemImage:value:)`、`.tabBarMinimizeBehavior(_:)`、`ContentUnavailableView`、`.onScrollGeometryChange`、Swift Charts `BarMark`。

---

## 1. App 外壳与导航

### 1.1 Tab 结构（顺序、图标、文案、值）

`RootView.body` 用系统 `TabView` + 新的 `Tab(_:systemImage:value:)` 构造（`RootView.swift:31-54`）。**顺序 = 枚举声明顺序**：

| # | Value (`AppTab`) | Label（运行时取 L10n） | key | zh-Hans | en | SF Symbol |
|---|---|---|---|---|---|---|
| 1 | `.home` | `L10n.str("index_title")` | `index_title` | 日记 | Diary | `house.fill` |
| 2 | `.footprint` | `L10n.str("footprint_title")` | `footprint_title` | 足迹 | Footprint | `figure.walk` |
| 3 | `.search` | `L10n.str("search_title")` | `search_title` | 搜索 | Search | `magnifyingglass` |
| 4 | `.settings` | `L10n.str("settings_title")` | `settings_title` | 设置 | Settings | `gearshape.fill` |

- `enum AppTab: Hashable { case home, footprint, search, settings }`（`RootView.swift:8-13`）。注释明确：`icon`/`label` 辅助方法与 `CaseIterable` 已删，标题与图标**内联**在 `Tab` 里。
- 默认选中 `.home`（`AppState.swift:5`）。
- 每个 tab 的内容都套 `.diaryBackground()`（`RootView.swift:37,41,48,53`）。
- 选中绑定：`activeTab` 是把 `appState.activeTab` 包成 `Binding`（`RootView.swift:20-22`），`TabView(selection:)` 只用它。

### 1.2 「选中玻璃胶囊」的真相

**代码里没有任何选中态样式**。全仓库 grep 无 `UITabBarAppearance`、无 `.tabViewStyle`、无 `.toolbar(.hidden, for: .tabBar)`、无 tab badge、无自定义 tab item。整个浮条是 **iOS 26/27 系统 Liquid Glass 浮动 tab bar**：

- 胶囊高亮、玻璃折射、选中/未选中配色由系统绘制；选中色由 `.tint(Theme.primary())` 注入（`RootView.swift:59`）→ 品牌蓝 `#2563EB`（`Theme.swift:4,6`）。
- 未选中项 = 系统 secondary label 色；无自定义形态。
- 无障碍标签由系统从 `Tab` 的 title 派生，无额外 `accessibilityLabel`。

> **Compose 复刻建议**：用 M3 `NavigationBar` + `NavigationBarItem`（4 项，等宽，底部），选中项 = `NavigationBarItemDefaults.colors(indicatorColor = primaryContainer)` 画出胶囊；图标用 Material Symbols 里语义最近的：home / footprints / search / settings。系统浮条在 iOS 是**浮起**的（两侧留白、圆角胶囊、贴屏幕底边），如需一致观感用 `Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 3.dp)` 自绘，而不是默认贴边的 NavigationBar。

### 1.3 浮条尺寸与滚动最小化

- `.tabBarMinimizeBehavior(.onScrollDown)`（`RootView.swift:55`）：**向下滚动时系统把浮条最小化**（收缩为一个小的玻璃胶囊），向上滚动恢复。这是外壳唯一的滚动联动，所有 tab 通用。
- 实测浮条尺寸（写死在 `AdaptiveLayout.swift:106-114` 的常量里，来自 UI 测试读 `app.tabBars` frame）：
  - 竖屏 `83pt`：18 Pro `(0,791,402,83)`；SE `(0,584,375,83)`。
  - 横屏 `64pt`：18 Pro `(0,338,874,64)`；SE `(0,311,667,64)`。
  - 两种形态**都紧贴屏幕底边**，且高度与机型 / home indicator 无关。
- 内容避让：`AdaptiveLayout.tabBarClearance = isPortrait ? 83 : 64`（`:122-124`），另有 `TabBarClearance` 组件在滚动内容尾部垫 120×字号倍率（见 §5）。

### 1.4 横屏时外壳的行为

`RootView.swift:56-58` 的注释是实测结论，**横屏对导航侧不做任何改动**：

- 系统在横屏仍把浮动 tab bar 放在**底部居中**（不上移到侧边、不变成 sidebar），占用 64pt。
- 因此横屏不需要 `NavigationRail` / 侧边栏；四屏都保留同一套底部导航。
- 宽度方向：横屏左右各有系统占位（`leading=62`、`trailing=62`），页面内容**已经落在安全区内**（原点 x=62），所以 `adaptivePagePadding()` 只加 16pt 左右边距，**绝不能再叠加安全区**（`AdaptiveLayout.swift:137-146` 的踩坑注释：第一版重复叠加导致横屏所有页面内容右移 62pt）。
- 各页横屏分支：首页左右分栏（不在本文范围）；足迹「统计 + 趋势|清单并排」；搜索「时间/地点两组条件并排一行」；设置**无横屏分支**（仍旧单列，卡片按 750-32=718pt 拉宽）。
- 旋转时 `AdaptiveLayoutReader` 通过 `onGeometryChange` 重算并下发（`AdaptiveLayout.swift:188-201`），不用 `GeometryReader` 包 `TabView`（会丢尺寸、清安全区）。

### 1.5 全局状态（AppState）与四 tab 的共享方式

`AppState`（`AppState.swift:3-9`，`@Observable`）只有 4 个字段，由 `RootView` 以 `@State` 持有（`RootView.swift:18`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `activeTab` | `AppTab` | 当前 tab，默认 `.home` |
| `presentEditor` | `Bool` | 是否呈现全屏编辑器 |
| `editorDayKey` | `String?` | 编辑器目标日（`nil` = 今天） |
| `uiTick` | `Int` | UI 刷新计数器（语言/主题/周起始等） |

**没有 EnvironmentObject**：AppState 只通过闭包向下传（`RootView.swift:33-36`、`44-47` 把 `openEditor` 传给 `HomeView` / `SearchView`）。

四屏真正共享的是这三条通道：

1. **`DiaryRepository.shared` 单例**（SQLite）：所有数据读写。
2. **`NotificationCenter` 广播**：
   - `.diaryVersionChanged`（`DiaryRepository.swift:7`，由 `bumpDiaryVersion()` 发，`:74-82`，主线程直发 / 否则 `DispatchQueue.main.async`）→ 首页 `HomeView.swift:70-72`、足迹 `FootprintView.swift:72-74`（`vm.reload()`）监听；编辑器关闭时也发一次（`RootView.swift:88-90`）。
   - `.uiTickChanged`（`:8`，`bumpUiTick()`，`:84-92`）→ `RootView.swift:138-140` 把 `appState.uiTick += 1`；`HomeView.swift:73-76`、`SettingsView.swift:23-25` 直接刷新。
3. **`SettingsStore`**（`UserDefaults(suiteName: "group.com.cov.justdiary")`，`AppSettings.swift:36-37`）：语言、主题、周起始、开始时间、提醒、自动定位、历史编辑。带进程内缓存 `LockedBox`（`:39`），`load()` 有值就返回缓存 —— 所以**改设置必须走 `save()`**，否则读到的还是旧值。

**uiTick 的双跳链路**（`RootView.swift:25-30,72`）：`bumpUiTick()` → 通知 → `appState.uiTick += 1` → `body` 里读 `settingsTick` → `.id("\(AppLanguage.current)#\(settingsTick)")` → **整棵树重建**。这条 `.id` 是语言/主题即时生效的唯一保证；代价见 §1.9 脆弱点。

### 1.6 全屏上下文（浮条如何消失）

- **编辑器**：`RootView.swift:86-102` 用 `.fullScreenCover(isPresented:)` 呈现 `DiaryPageView(dayKey:)`，并显式补注入 `diaryDynamicTypeSize`、`locale`、`adaptiveLayoutReader()`（`presentation` 不继承更内层的 environment，`:91-101` 注释）。`fullScreenCover` **整屏覆盖浮条**，编辑器自带返回/搜索/编辑/分享/图片/撤销/完成按钮（`DiaryPageView.swift:352-390`，均为 `PressableGlassIcon`）。
- 编辑器关闭回调 `onDismiss` 里 `bumpDiaryVersion()`（`RootView.swift:88-90`）→ 首页/足迹自动刷新。
- **图片预览**：`fullScreenCover(item:)`（`HomeView` 的 `CalendarPaneViews.swift:152`、`DayContentView.swift:49`、`DiaryPageView.swift:103`）。
- 除此之外**没有任何页面隐藏浮条**：搜索、足迹、设置、首页全部共享底部浮条；页面内滚动到内容末尾靠 `TabBarClearance` / 底部 padding 让位（§5）。
- 浮条「消失」的另外两种情况：① 系统 `onScrollDown` 最小化；② 键盘弹出时系统自行处理（搜索页在键盘上方仍可见浮条，靠 `scrollDismissesKeyboard(.immediately)` 收起键盘，`SearchView.swift:266`）。

### 1.7 深链：`justdiary://editor?dayKey=...`

入口在 `JustDiaryApp.swift:12-14, 18-27`：

```swift
.onOpenURL { url in handleURL(url) }
// handleURL: guard url.scheme == "justdiary"
//   url.host == "editor" → 取 queryItems 里第一个 name == "dayKey" 的值
//                           → LaunchIntent.openEditor(dayKey: dayKey)
//   其它 host（含 nil）    → LaunchIntent.openEditor()   // dayKey 为空 = 今天
```

`LaunchIntent` 只是两个静态变量（`LaunchIntent.swift:3-16`）：`target`（默认 `"home"`）、`dayKey`（默认 `""`）；`openEditor(dayKey:)` 置 `target="editor"`；`clear()` 复位。

**消费链路** `RootView.consumeLaunchIntent()`（`RootView.swift:149-157`）：

1. `guard LaunchIntent.target == "editor"`。
2. `dayKey` 为空 → `nil`（= 今天）。
3. 立刻 `LaunchIntent.clear()`（先清后开，避免重复触发）。
4. `DispatchQueue.main.asyncAfter(deadline: .now() + 0.15)` → 设 `appState.editorDayKey` / `appState.presentEditor = true`。**0.15s 延迟是硬编码的**（等场景稳定）。

调用点三处：`.task`（`RootView.swift:132`）、`UIApplication.didBecomeActiveNotification`（`:134-137`）、`scenePhase == .active`（`:141-146`）。

> **⚠️ 已知竞态**：App 已经在前台时 `onOpenURL` 不会伴随 `didBecomeActive`/`scenePhase` 变化，`consumeLaunchIntent()` 不会被调用 → **前台期间收到的深链要等到下一次激活（切后台再回来）才生效**，届时会「莫名其妙」弹出对应日期的编辑器。Android 侧应在 `onNewIntent` 里直接用 intent 驱动导航，不要复制这套「静态意图 + 激活时消费」。

UI 测试用的入口参数（同文件）：`-ui-test-state`（左上角 1×1、`opacity 0.02`、`accessibilityIdentifier("app.state")` 的探针，格式 `L:<语言>|T:<light|dark|nil>|tick:<n>`，`:73-85,160-168`）、`-ui-test-tab footprint|map|search|settings|其它→home`（`:106-115`）、`-ui-test-open-editor`（`:116-118`）、`-ui-test-open-day <key>`（可叠加 `-ui-test-reset-data` 先删该天，`:119-131`）。App 启动参数里还有 `-ui-test-reset-settings` / `-ui-test-no-autoloc`（`JustDiaryApp.swift:33-43`）。

### 1.8 呈现规则总表（sheet / overlay / alert / dialog / fullScreenCover）

| 类型 | 使用点 | 参数 | 说明 |
|---|---|---|---|
| `.sheet` | 搜索-自定义时间（`SearchView.swift:16-19`） | `presentationDetents([.medium])` | 半屏 |
| `.sheet` | 搜索-选择地点（`:20-23`） | `.large` | 接近全屏（列表长） |
| `.sheet` | 足迹-时间筛选（`FootprintView.swift:75-78`） | `.medium` | 与搜索时间弹窗同一套 UI |
| `.sheet` | 设置-开始时间（`SettingsView.swift:55-62`） | `.medium` | 24 小时滚轮 |
| `.sheet` | 设置-提醒时间（`:63-75`） | `.medium` | 时间滚轮 DatePicker |
| `.sheet` | 设置-导出分享（`:29-36`） | `.height(170)` + `ShareLink` + `.buttonStyle(.glassProminent)` + `padding(24)` | 系统分享面板 |
| `.sheet` | 设置-导入文件选择（`:37-44`） | 默认 detent，内容是 `DocumentPicker`（`UIDocumentPickerViewController(forOpeningContentTypes: [.data])`，单选，`:433-461`） | 取消 → `onPick(nil)` |
| `.confirmationDialog` | 设置-导入方式（`:45-54`） | `titleVisibility: .visible` | 标题「导入方式」；按钮：跳过已有日记 / 覆盖已有日记 / 取消(`role: .cancel`)。注释明确：**只保留给破坏性动作**，值选择一律用 `Menu`（`:138-141`） |
| `.alert` | 设置-结果提示（`:96`）、编辑器（`DiaryPageView.swift:138`） | `appAlert(item:)` 包装（`Components.swift:518-535`） | 见 §5.9 |
| `.overlay` | 设置-busy（`:76-95`） | `Color.black.opacity(0.25).ignoresSafeArea()` + 玻璃卡（`padding h32 v26`，`ProgressView(.large)` + 13pt 文案），`transition(.opacity)` | 导出/导入期间阻塞 |
| `.overlay(alignment: .bottom)` | 首页-toast（`HomeView.swift:45-64`） | 玻璃胶囊 toast，见 §5.10 | 唯一的 toast 形态 |
| `.overlay(alignment: .topLeading)` | 根-测试探针（`RootView.swift:73-85`） | 仅 `-ui-test-state` 时 | 不进产品 UI |
| `.fullScreenCover` | 编辑器（`RootView.swift:86-102`）、图片预览 | — | 覆盖浮条 |
| `Menu` | 设置页所有「值选择」行（`SettingsView.swift:142-152`） | `.menuOrder(.fixed)` | 系统玻璃菜单，选中项带 `checkmark` |

统一约定：**没有自定义 popover、没有自定义 dialog、没有 Snackbar**；确认类交互一律 `confirmationDialog` / `alert`，值选择一律 `Menu`，选择器一律半屏 `sheet`，阻塞类一律玻璃 overlay。

### 1.9 外壳的脆弱点（重建时请修正，不要照抄）

1. **`.id("\(AppLanguage.current)#\(settingsTick)")` 会重建整棵树**（`RootView.swift:72`）→ 切语言/主题/任意 `bumpUiTick` 后：四个 tab 的 `@State` VM 全部重建 → **搜索条件、搜索结果、滚动位置、足迹展开集合全部丢失**。Android 侧应改为只重组合并下发新 `Locale`/`ColorScheme`，不要销毁 ViewModel。
2. **深链在前台不生效**（§1.7）。
3. **`AppState` 靠 `@State` + 闭包传递**，tab 之间无法互相导航（例如「搜索→打开某天日记」走的是 `fullScreenCover` 而不是切 tab），因为 `openDiary` 回调只设 `editorDayKey`/`presentEditor`（`RootView.swift:44-47`）。
4. `.tint(Theme.primary())` 与 `.preferredColorScheme(AppConfigService.colorScheme)` 都在 `TabView` 上（`:59-60`）：主题切换依赖 `.id` 重建才彻底生效（`AppConfigService.applyAll()` 只清 canvas 缓存 + `bumpUiTick()`，`AppConfigService.swift:16-22`）。

---

## 2. 搜索页

### 2.1 结构（自上而下）

`SearchView.body = content`，`content` 是 `VStack(spacing: 0)`（`SearchView.swift:26-69`）：

```
VStack(spacing: 0)                        // :27
├─ header          .adaptivePagePadding() // :28-29  PageHeader「搜索」+ 右侧计数徽标
├─ [可隐藏] VStack(spacing: 0)            // :30-49  仅当 !hideTopControls
│   ├─ searchBar   .adaptivePagePadding() .padding(.top, 10)   // :32-34
│   └─ filterPanel .adaptivePagePadding() .padding(.top, 12)   // :35-37
│      （整块背景里塞了一个 GeometryReader 量高度 → topControlsHeight）// :39-47
│      transition: .opacity + .move(edge: .top)                 // :48
├─ [条件生效时] filterSummary .adaptivePagePadding() .padding(.top, 8) // :50-55
└─ resultsList                          // :56
.padding(.top, 12)                       // :58
.animation(.diaryStandard, value: hideTopControls)   // :59  easeInOut 0.3
.animation(.diaryQuick, value: vm.hasAnyCondition)   // :60  easeOut 0.22
.onChange(of: vm.keyword)   → vm.onKeywordChanged()  // :61-63
.onChange(of: searchFocused) { 若获得焦点且控件已隐藏 → 动画展开 } // :64-68
```

- 页面标题右侧徽标：`vm.total > 0` 时 `GlassCountBadge(text: L10n.fmt("search_result_count", vm.total), icon: "circle.fill")`（`:128-134`）；zh「找到 %d 条日记」/ en「%d entries found」。
- 两个 sheet 挂在 `content` 外层（`:16-23`）。
- `vm.openDiary = openDiary` 在 `onAppear` 注入（`:15`）。

### 2.2 搜索框：默认 / 聚焦 / 清除

`GlassSearchField`（组件定义 `Components.swift:547-597`）用法见 `SearchView.swift:136-155`：

| 状态 | 表现 |
|---|---|
| 默认（未聚焦） | 玻璃胶囊，高 `minHeight 46`、左右 `padding 14`；左侧 `magnifyingglass` 15pt `onSurfaceVariant`；TextField 占位「搜索日记内容…」（`search_placeholder`）15pt；文字非空时右侧出现 `xmark.circle.fill`（15pt，plain button，把输入清空） |
| 聚焦 | 胶囊右侧**追加**「取消」按钮（`search_cancel`，14pt medium，`Theme.primary()`），`transition(.opacity)`，`.animation(.easeOut(duration: 0.2), value: searchFocused)`（`:154`）；`submitLabel(.search)` |
| 提交（键盘 Search / 回车） | `onSubmit → vm.commitKeyword()`：trim → 输入清空 → 加入 `keywords`（去重）→ 立即搜索（`:140`，VM `:211-219`） |
| 清除输入（xmark） | `text = ""`（`Components.swift:577-586`）→ `onChange(of: vm.keyword)` → 250ms 防抖 → 重新搜索；**已提交的关键词不受影响** |

**没有「折叠态胶囊 → 展开态输入框」的二段式 morph**：输入框始终是整宽玻璃胶囊，只有「取消」按钮的出现/消失与整块顶部控件的自动隐藏（§2.3）。Android 侧若要简化，可保留「常驻输入框 + 聚焦显示取消」。

### 2.3 顶部控件自动隐藏（滚动联动）

`updateTopControls(_ geo:)`（`SearchView.swift:272-283`），由 `.onScrollGeometryChange` 在结果列表上触发（`:267-269`）：

```
若当前已隐藏：            hide = (contentOffset.y > 8)
若当前未隐藏：
   extra = topControlsHeight > 20 ? topControlsHeight : 170
   hide  = (y > 24) && (contentSize.height > containerSize.height + extra + 24)
变化时 withAnimation(.diaryStandard) 应用
```

- `topControlsHeight` 由 `GeometryReader` 在 `.onAppear` / `.onChange` 量取，且只在 `h > 20` 时更新（`:39-47`）。
- 隐藏的是「输入框 + 全部搜索条件面板」；**条件胶囊行不隐藏**。
- 反向：只要输入框获得焦点，立刻展开（`:64-68`）。
- 语义：只有当 `内容高 > 容器高 + 顶部控件高 + 24` 时才允许隐藏（即列表至少能滚出「让开顶部控件」的空间），否则永不隐藏，避免短列表上下抖动。

### 2.4 条件栏（`filterSummary`）——唯一的条件展示位

仅当 `vm.hasAnyCondition`（有关键词或有筛选）出现，`padding(.leading, 12)/.trailing, 4`、`frame(minHeight: 44)`、`diaryCard(cornerRadius: 20)`（`SearchView.swift:73-92`）。内容 = 横向滚动的一排胶囊 + 右侧一个固定按钮：

| 元素 | 组成 | 尺寸 | 点击行为 |
|---|---|---|---|
| 关键词胶囊（未提交） | `“<输入>”` + `xmark` | 文本 12pt medium、`xmark` 9pt semibold；`padding h10 v6`；capsule 填充 `Theme.primaryContainer()`；文字 `Theme.primary()` | `vm.clearKeywordInput()`（清输入框 + 重搜），并先收起键盘 `searchFocused = false`（`:96-103`） |
| 关键词胶囊（已提交，可多个） | `“<term>”` + `xmark` | 同上 | `vm.removeKeyword(term)`（从 `keywords` 移除 + 重搜） |
| 时间胶囊 | 本周 / 本月 / 今年 / 自定义（自定义带了日期时显示 `L10n.dateRange(from,to)`，即 `%1$@ – %2$@`） | 同上 | `vm.clearFilter(.time)` → `timeKind = .all`、清自定义日期（VM `:231-239`） |
| 地点胶囊 | 未记录地点 / `<国家> · 仅国家` / `<国家> · <省州>` | 同上 | `vm.clearFilter(.location)` → 复位 `LocFilter` |
| 清除全部 | `GlassIconButton(systemName: "xmark.circle.fill", size: 28, accessibilityLabel: "清除全部条件")` | 图标 14pt（`size` 参数只用于选字号：>36 → 16pt，否则 14pt；命中区固定 44×44），`.buttonStyle(.glass)` + 圆形边框 | 先收键盘，再 `vm.clearAll()` |

胶囊数据源 `vm.activeFilterItems`（VM `:99-138`），顺序固定：**未提交输入 → 已提交关键词（按加入顺序）→ 时间 → 地点**。

### 2.5 「全部搜索条件」面板

`filterPanel`（`SearchView.swift:157-175`）：外框 `padding(10)` + `diaryCard(Radius.card=20)`；内部按方向切换：

| 方向 | 布局 |
|---|---|
| 竖屏（`layout.isPortrait` = `height >= width`） | `VStack(alignment: .leading, spacing: 6)`：时间组在上、地点组在下（`:161-165`） |
| 横屏 | `HStack(alignment: .top, spacing: 16)`：两组并排，省约 60pt 高留给结果列表（`:166-171`，注释指向 `docs/adaptive-layout-plan.md §3.3`） |

**时间组**（`:177-196`）：组标题「时间范围」（`search_filter_time`，12pt `caption`，`onSurfaceVariant`）+ 横向 `ScrollView(showsIndicators: false)`，内部 `HStack(spacing: 6)`，并**额外 `padding(.vertical, 6)`** —— 注释说明：玻璃胶囊自带外沿高光与按压缩放，滚动视口贴边会把上下两条边裁掉（`:182-184`）。胶囊顺序与语义：

| 顺序 | 文案 key | zh / en | 行为 |
|---|---|---|---|
| 1 | `search_all_time` | 全部时间 / All time | `setTimeKind(.all)` |
| 2 | `search_time_week` | 本周 / This week | `setTimeKind(.thisWeek)` |
| 3 | `search_time_month` | 本月 / This month | `setTimeKind(.thisMonth)` |
| 4 | `search_time_year` | 今年 / This year | `setTimeKind(.thisYear)` |
| 5 | `vm.timeRangeLabel`（自定义 / 已选区间） | 自定义 / Custom 或 `%1$@ – %2$@` | **打开时间 sheet**（`vm.showTimeSheet = true`），不直接改条件（`:216-224`） |

选中态判定：`vm.timeKind == kind`（`GlassChip(active:)` 只把文字染成 `Theme.primary()`）。

**地点组**（`:198-214`）：组标题「地点」（`search_filter_loc`）+ 两个胶囊：

| 顺序 | 文案 | 选中判定 | 行为 |
|---|---|---|---|
| 1 | `search_all_loc` 全部地点 / All places | `locFilter.country == "" && region1 == "" && !noLoc` | 选中时点它 → `resetLocFilter()`；否则 `selectLocRow("","",false)` |
| 2 | `vm.locFilterLabel`（`选择地点…` / `未记录地点` / `<国家> · 仅国家` / `<国家> · <省州>`，VM `:71-76`） | `country != "" \|\| noLoc` | 已选中 → `resetLocFilter()`；未选中且为空 → **打开地点 sheet**；否则按行选择（`:226-239`） |

`vm.locFilterLabel` 的「选择地点…」= `search_loc_picker`，「仅国家」= `search_loc_country_only`，「未记录地点」= `search_loc_no_loc`。

### 2.6 时间范围预置与自定义区间

`TimeRangeKind.dayKeyRange(customFrom:customTo:)`（`SearchViewModel.swift:10-29`）→ 返回闭区间 dayKey 字符串对，直接进 SQL `d.day_key >= ? AND d.day_key <= ?`：

| kind | 范围 | 备注 |
|---|---|---|
| `.thisWeek` | `(dayKeyOf(weekFirst(today)), dayKeyOf(today))` | `weekFirst` **跟随设置的周起始**（`DateUtil.swift:74-77` 读 `SettingsStore.load().weekStart`） |
| `.thisMonth` | `(dayKeyOf(monthFirst(today)), dayKeyOf(today))` | 到今天为止 |
| `.thisYear` | `(YYYY-01-01, today)` | 年取自 `DateUtil.calendar` |
| `.custom` | `from = customFrom ?? startOfDay(today)`；`to = customTo ?? startOfDay(today)`；**若 from > to 自动交换** | 只取日期部分 |
| `.all` | `("0000-01-01", "9999-12-31")` | 哨兵值，不是 NULL 判断 |

**时间 sheet**（`SearchView.swift:350-394`，detent `.medium`）：

```
VStack(spacing: 16)
├─ GlassSheetHeader("自定义时间范围" = search_time_title) + 关闭「xmark」
├─ HStack(spacing: 8): 本周 / 本月 / 今年 三个 GlassChip（active = timeKind == kind）
├─ VStack(alignment:.leading, spacing:8): 标签「开始日期」(search_time_start, 12pt)
│    + DatePicker(displayedComponents: .date, style: .compact, tint: primary)
│      get: customFrom ?? DateUtil.monthFirst(Date())   ← 默认「本月 1 号」
│      set: customFrom = DateUtil.startOfDay($0)
├─ VStack(…): 标签「结束日期」(search_time_end) + DatePicker
│      get: customTo ?? Date()   set: startOfDay
└─ HStack(spacing: 12) .padding(.top, 12)
     ├─ GlassSecondaryButton("清除" search_time_clear, fullWidth) → clearTimeFilter()
     └─ GlassPrimaryButton("确定" search_time_apply, fullWidth) → applyCustomTime()
.padding(20).padding(.bottom, 8)
```

- 快捷胶囊点击 `applyQuickRange(kind)` → 设 kind、清自定义日期、关 sheet、重搜（VM `:179-185`）。
- 「清除」→ `timeKind = .all` + 清日期 + 关 sheet（VM `:187-193`）。
- 「确定」→ `timeKind = .custom` + 关 sheet（VM `:195-199`）；此时若 `customFrom/customTo` 为 nil，`activeFilterItems` 显示「自定义」，否则显示区间（VM `:115-120`）。
- **注意**：DatePicker 是 `.compact` 内联日期按钮，点开是系统日历浮层（不是滚轮）。

### 2.7 地点选择（独立屏 / sheet）

`locSheet`（`SearchView.swift:402-452`，detent `.large`），`onAppear` 触发 `vm.loadLocationOptions()`：

```
VStack(spacing: 10) .padding(20) .padding(.bottom, 8)
├─ GlassSheetHeader("选择地点" = search_loc_title) + 关闭
├─ 搜索框行：magnifyingglass(14pt, onSurfaceVariant) + TextField("搜索地点…" = search_loc_search, 15pt)
│    HStack(spacing: 8) .padding(.horizontal, 12) .frame(minHeight: 44)
│    .diaryCard(cornerRadius: Radius.field = 14, interactive: true)
│    .focused($locSearchFocused)  submitLabel(.search)  onSubmit→收键盘
├─ 说明文字「按日记数量排序」(search_loc_sort_hint, 12pt caption, 左对齐)
└─ ScrollView(showsIndicators: false) .scrollDismissesKeyboard(.immediately)
     VStack(spacing: 0)
     ├─ 行「全部地点」(country:"", region1:"", noLoc:false, count:nil)
     ├─ 仅当 vm.noLocCount > 0：行「未记录地点 (N)」(noLoc:true)
     ├─ ForEach(vm.filteredOptions)：行标签 = country（region1 非空时 " · region1"），右侧显示 count
     └─ 若 filteredOptions 为空且 !locLoading：Text("暂无地点数据" search_loc_empty, 13pt, padding top 40)
```

**行样式**（`:454-484`）：`HStack{ Text(15pt, active ? primary : onSurface, lineLimit 1); Spacer(); count?(12pt onSurfaceVariant); active?(checkmark 13pt semibold primary) }`，`padding(.horizontal, 12)`、`frame(minHeight: 46)`、背景 `RoundedRectangle(cornerRadius: Radius.badge = 12, style: .continuous)`，选中填 `Theme.glowColor()`（= 品牌蓝 15% 透明）否则 `.clear`；`.buttonStyle(.plain)`。

**点击行为** `selectLocRow(country:region1:noLoc:)`（VM `:172-177`）：`Haptics.tap()` → 覆盖 `locFilter` → **自动关闭 sheet** → 重搜。

**数据来源与排序**（`DiaryRepository.getLocationOptions(keyword:fromKey:toKey:)`，`DiaryRepository.swift:361-410`）：

- 两条 SQL：① `country+region1` 组合（两者都非空）② 只有 `country`（`region1 = ''`），都按 `COUNT(DISTINCT d.id)` 统计**日记篇数**（不是 block 数），并受**当前时间范围**限制。
- 合并后排序：`count` 降序，count 相同按 `(country+region1)` 升序（`:406`）。
- **硬上限 `.prefix(20)`**（`:407`）→ 最多 20 个地点选项。
- `noLocCount` 用 `noLocSql` 统计「该时间范围内完全没有地点信息的日记数」（`:22-26, 409-410`）。
- VM 侧的「搜索地点」是**纯客户端过滤**：`filteredOptions` 在已加载的 ≤20 项里做 `country/region1` 的 `lowercased().contains`（VM `:147-151`）—— 所以**搜索框搜不到榜外地点**（见 §2.13）。

### 2.8 搜索执行 / 状态机（`SearchViewModel`）

状态字段（`SearchViewModel.swift:47-69`）：

| 字段 | 默认 | 作用 |
|---|---|---|
| `keyword` | `""` | 输入框实时文本 |
| `keywords: [String]` | `[]` | 已提交关键词（多关键词 = 空格连接 = **AND** 语义） |
| `timeKind` | `.all` | 时间范围 |
| `customFrom/customTo` | `nil` | 自定义区间（只存日期 0 点） |
| `locFilter: LocFilter(country, region1, noLoc)` | 全空 | 地点 |
| `results: [SearchResultItem]` | `[]` | 已加载结果（分页累加） |
| `total` | `0` | SQL `COUNT(*)` 全量命中数（非已加载数） |
| `hasMore` | `false` | 是否还有下一页 |
| `searching` / `indexing` | `false` | 搜索中 / 索引未就绪 |
| `offset` | `0` | 已加载条数（分页游标） |

派生态：`effectiveKeywords`（= `keywords` + 去重后的 trim 输入，`:82-89`）、`combinedKeyword`（空格 join，`:91-93`）、`hasFilters`（时间非 all 或地点非空，`:78-80`）、`hasAnyCondition`（关键词或 filters，`:95-97`）。

流程（`:263-307`）：

1. **无任何条件** → 直接清空 `results/total/hasMore` 并 return（**不改 `searching`、不改 `offset`、不递增 `currentSearchId`**）。
2. 有条件 → `searching = true`；`indexing = !searchIndexReady()`（`searchIndexReady()` 判定 `SettingsStore.searchIndexVersion >= 3`，`DiaryRepository.swift:14,303-305`）。
3. `await ensureSearchIndex()`（索引版本落后或 FTS 表缺失时重建，`:287-296`）。
4. 用 `currentSearchId` 做**最后一次写入者胜**的竞态保护（`:275-280, 292`）。
5. `await search(...)`：`reset ? 0 : offset`；`reset` 覆盖列表，否则 append。
6. `searching=false; indexing=false`。

触发点：关键词输入 250ms 防抖（`:153-160`，`Task.sleep(250_000_000)`，取消旧 task）、提交/移除关键词、任何筛选变更、`loadMore()`（`:246-248`）。

SQL（`DiaryRepository.runSearch`，`:878-950`）：

- 关键词：`terms = 空格切分`，FTS5 查询 = 每个词做 CJK 逐字切分后加引号 `"词1" "词2"`（AND）；同时保留 `d.search_text LIKE '%词%'` 兜底（`ESCAPE '\'`，`SearchUtil.escapeLikeTerm` 转义 `\ % _`）。FTS 不可用时 LIKE 生效。
- 地点：`noLoc == false && country != ""` → `EXISTS(... b.country = ? AND b.region1 = ?)`（**匹配该篇任意 block**）；`noLoc` → 该篇**没有任何**带地点信息的 block。
- 排序：`ORDER BY d.day_key DESC`（`:922`），分页 `LIMIT 200+1 OFFSET ?`；`hasMore = items.count > 200`，随后截断到 200（`:13, 915, 940-941`）。
- `total` 是同样 WHERE 条件下的 `COUNT(*)`。
- 片段：FTS 命中时用 `snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24)`（24 个 token 的窗口，`<hl>` 标注），再 `FtsSegment.desegment` 把逐字切分的 CJK 复原；FTS 无片段时用 `SearchUtil.buildFallbackSnippet(summary, keyword)`：取首个命中词**前后各 18 个字符**的窗口，两端越界加 `…`，并把所有词包成 `<hl>…</hl>`（`SearchUtil.swift:84-125`）。
- `db == nil` 时返回 `total = -1`（`DiaryRepository.swift:881`）→ 头部徽标不显示（`total > 0` 才显示）。

### 2.9 结果列表项组成与高亮规则

`resultsList`（`SearchView.swift:241-270`）：`ScrollView` + `LazyVStack(spacing: 10)`，整体 `.adaptivePagePadding()` + `.padding(.top, 10)`，末尾 `TabBarClearance()`。

列表项（`:316-346`）—— **注意：没有标题行**，日期胶囊 + 两行摘要就是全部内容：

```
HStack(spacing: 10)                                  // :317
├─ VStack(alignment: .leading, spacing: 6)           // :318
│   ├─ HStack(spacing: 6)  【日期胶囊】              // :319-330
│   │    Image("calendar") 11pt + Text(L10n.formatDayKey(dayKey)) 12pt medium
│   │    .padding(.horizontal, 8).padding(.vertical, 4)
│   │    Capsule().fill(Theme.primaryContainer())，文字/图标 Theme.primary()
│   └─ HighlightedText(snippet:summary:keyword: combinedKeyword) 13pt, onSurface, lineLimit 2
├─ Spacer()
├─ Text(L10n.timeOf(item.updatedUtc)) 12pt onSurfaceVariant      // 更新时间的「时分」
└─ Image("chevron.right") 12pt onSurfaceVariant
.padding(14) + .diaryCard(cornerRadius: Radius.card = 20)
.onTapGesture { Haptics.tap(); vm.openDiary?(item.dayKey) }      // :246-249
```

- 日期文案：`L10n.formatDayKey` → `date_day_title` = zh `%1$@%2$ld日 %3$@`（如「5月3日 周六」）/ en `%3$@, %1$@ %2$ld`；无法解析 dayKey 时原样显示。
- 右侧时间：`L10n.timeOf(updatedUtc)` 用**跟随设备 12/24 小时制 + App 语言**的格式（`L10n.swift:125-139`）。
- **整行可点但无按压反馈样式**（`.onTapGesture` 挂在卡片上，没有 `Button`，无高亮/涟漪）。
- `loadMore`：`hasMore` 时显示 `GlassPrimaryButton("加载更多" search_load_more, compact: true)` + `.padding(.vertical, 8)`（`:251-256`）。

**高亮规则**（`HighlightedText`，`:487-518`）：

1. 若 `snippet` 含 `<hl>` → 走 `SearchUtil.highlightSnippet(snippet)`：按 `<hl>…</hl>` 切成 `Seg(text:hit:)` 段（末尾未闭合的 `<hl>` 视作直到结尾都是 hit，`SearchUtil.swift:61-82`）。
2. 否则若关键词非空 → `SearchUtil.highlightSegments(summary, keyword:)`：把关键词按空格切词，**大小写不敏感**地在 `summary` 里找**所有**出现位置，排序 + 合并重叠区间，输出非命中/命中交替的段（`SearchUtil.swift:20-59`）。
3. 否则整段非命中。
4. 命中段样式：`foregroundColor = Theme.primary()`、`backgroundColor = Theme.primaryContainer()`、`font = .system(size: DynamicTypeMetrics.scaled(13, for: typeSize), weight: .medium)` —— 注释说明字号必须走 Dynamic Type，否则高亮词不随系统字号变大（`:509-513`）。

### 2.10 空态、加载态与「索引未就绪」

`emptyState`（`SearchView.swift:285-314`），渲染条件 **`vm.results.isEmpty && !vm.searching`**（`:257`），并且外面还套了一层 `.padding(.top, 60)`：

| 分支（按顺序） | 条件 | 内容 |
|---|---|---|
| A 加载/准备 | `vm.indexing \|\| vm.searching` | 大号 `ProgressView`（`.controlSize(.large)`，tint primary）+ 文案：`vm.searching` → 「正在搜索…」(`search_loading`)，否则「正在准备搜索索引…」(`search_preparing`)，字号 15 medium，`padding(.top, 60)` |
| B 有条件但无结果 | `vm.hasAnyCondition` | `ContentUnavailableView.search(text: vm.combinedKeyword)`（**系统组件，文案由 iOS 本地化，不在 String Catalog 里**；en 形如 “No Results for “keyword””）+ `GlassPrimaryButton("清除全部条件", compact)` → 收键盘 + `vm.clearAll()`；`padding(.top, 60)` |
| C 无条件（初始态） | else | `ContentUnavailableView { Label("搜索你的日记" search_start_title, systemImage: "magnifyingglass") } description: { Text("输入关键词，或选择时间与地点筛选" search_empty) }`；`padding(.top, 60)` |

> **⚠️ 实际观感 = 没有菊花、也没有「正在准备索引」**：分支 A 要求 `!vm.searching`（外层条件）**同时** `vm.indexing || vm.searching`（内层条件），而 `indexing` 为真的时刻 `searching` 必为真（VM `:266, 274`），两者互斥 → **分支 A 是死代码**。真实行为是：搜索进行中列表区**什么都不显示**（保留上一次的 `results`，首次搜索时为空白），结果到达后一次性刷新；底部 `emptyState` 的 `.padding(.top, 60)` 与外层重复，实际顶部空 120pt。
> Android 重建时请显式实现三态：`Loading`（骨架或 CircularProgress，覆盖列表区）、`Empty`（无条件 / 无结果两种文案）、`Content`；并保留「索引未就绪」提示（首次建索引可能耗时）。

### 2.11 清除筛选的所有入口（重建时逐个保留）

| 入口 | 位置 | 作用域 |
|---|---|---|
| 条件胶囊上的 `xmark` | `SearchView.swift:94-126` | 单条（关键词逐条 / 时间 / 地点） |
| 条件栏右侧 `xmark.circle.fill` | `:82-86` | `vm.clearAll()`：关键词、时间、地点全清（VM `:201-209`） |
| 空态里的「清除全部条件」按钮 | `:300-303` | 同 `clearAll()` |
| 时间面板里的「清除」 | `:383-385` | 只清时间（→ `.all` + 清自定义日期） |
| 点击已选中的「全部时间」/「全部地点」胶囊 | `:216-224, 226-239` | 只清对应维度 |
| 输入框的 `xmark.circle.fill` | `Components.swift:577-586` | 只清未提交输入 |

### 2.12 竖屏 / 横屏差异（搜索页）

| 项 | 竖屏（402×874） | 横屏（874×402） |
|---|---|---|
| 顶部控件块 | 输入框（top 10）+ 条件面板（top 12） | 相同 |
| 条件面板内部 | 时间组 / 地点组 **上下两行**，`VStack spacing 6` | **左右并排**，`HStack(alignment:.top, spacing:16)`，两组各 `frame(maxWidth:.infinity, alignment:.leading)`（`:160-172`） |
| 结果列表 | 剩余高度，LazyVStack 两项间距 10 | 同（屏幕高只有 402，条件区约 96pt + 标题 52 + 浮条 64 → 列表约 150–190pt，能显示约 1.5 项） |
| sheet | 时间 `.medium`、地点 `.large` | 同（系统横屏 sheet 仍居中/半屏） |
| 左右边距 | 16 / 16（`pagePadding`） | 16 / 16（安全区已由系统让开，**不要叠加**） |

### 2.13 搜索页的脆弱点 / 已知不一致

1. **加载态不可见**（§2.10）——首屏搜索会白屏到结果返回。
2. **清空条件可能被在途搜索「复活」**：无条件时 `doSearch` 提前 return，不递增 `currentSearchId`（VM `:265-272`），此前在途的搜索仍会通过 `currentSearchId == searchId` 校验并写回 `results` → 出现「没有任何条件却显示结果」；同一时刻 `searching` 仍为 true，空态也被抑制。
3. **地点搜索只在 Top-20 内**（`getLocationOptions` 的 `.prefix(20)` + 客户端过滤）。
4. **`filteredOptions` 的 `locLoading` 没有 loading UI**：`locLoading` 只用于抑制空态文案（`:437`），加载期间列表是空的。
5. **`SearchFilterItem.id` 可能撞车**：`id = "\(kind)-\(value)"`，同一关键词重复提交被 `keywords.contains` 挡住，但「未提交输入」与「已提交同名」不会同时出现（`activeFilterItems` 有去重判断 `:102`），逻辑上安全，但依赖了这一点。
6. `GlassIconButton(size: 28)` 的 `size` 参数只决定字号（>36 → 16pt），圆的大小恒为 44pt 命中区，视觉上偏大（`Components.swift:290-302`）。
7. 时间范围「本周」会跟随设置的周起始日（`DateUtil.weekFirst`），若用户在设置里改成周日起始，会对同一批数据得出不同的结果 —— 这是**期望行为**，但 Android 侧必须同步实现。
8. `total` 为 `-1`（DB 未就绪）时头部不显示徽标，等价于「没有结果计数」，没有错误提示。

---

## 3. 足迹页

### 3.1 结构（自上而下）

`FootprintView.body` 是 `VStack(spacing: 0)`（`FootprintView.swift:10-69`），`.padding(.top, 12)`（`:70`）：

```
VStack(spacing: 0)
├─ PageHeader("足迹" footprint_title)                          // :11-17
│     trailing = Text(L10n.fmt("footprint_summary", locatedCount, unlocated))
│                14pt(chip) onSurfaceVariant lineLimit 1
│     .padding(.horizontal, 16)     ← 注意：这里写死 16，没用 adaptivePagePadding()
├─ ScrollView(.horizontal, showsIndicators: false)              // :19-32
│     HStack(spacing: 8) { 年胶囊… + GlassActionChip(自定义) } .adaptivePagePadding()
└─ ScrollView(showsIndicators: false)                           // :34-68
      Group { 竖屏 or 横屏 两套布局 } .adaptivePagePadding() .padding(.top, 10)
      .padding(.bottom, max(24, layout.bottomInset + 44))       // :67
.task { await vm.load() }                                       // :71
.onReceive(.diaryVersionChanged) { Task { await vm.reload() } }  // :72-74
.sheet(isPresented: $showTimeFilter) { timeFilterSheet.presentationDetents([.medium]) } // :75-78
```

- 右上角摘要：`footprint_summary` = zh「已标注 %1$ld 处 · 未记录 %2$ld 篇」/ en「Located %1$ld · Unlocated %2$ld」；`locatedCount` = **当前筛选后**的已定位 block 数，`unlocated` = **全量、与筛选无关**的「完全没有地点信息的 block 数」（`FootprintViewModel.swift:82-84`）。
- 数据源：`DiaryRepository.allFootprintRows()`（`DiaryRepository.swift:330-358`）—— `edit_block JOIN diary`，按 `start_time_utc ASC`，一行 = 一个 block（含 country/region1/region2/region3/locText/locQuality/locPrecision/dayKey）。
- `vm.load()` 只在首次执行（`loaded` 守卫，`FootprintViewModel.swift:60-64`）；后续一律靠 `.diaryVersionChanged` → `reload()`。

### 3.2 年选择器 + 自定义时间筛选

横向 chip 条（`FootprintView.swift:19-32`，间距 8，可横滑）：

| 顺序 | 内容 | 选中判定 | 点击 |
|---|---|---|---|
| 1 | `GlassChip("全部" footprint_time_all, value:"all")` | `vm.timeKind == .all && vm.yearFilter == "all"` | `yearFilter = "all"`；`applyTimeKind(.all)`；`reload()` |
| 2..N | 每个有地点记录的年份（`vm.years`，字符串，**降序**：`yearSet.sorted(by: >)`，`FootprintViewModel.swift:86-90`；仅统计「有地点」的行，且**不受当前筛选影响**） | `vm.timeKind == .all && vm.yearFilter == <year>` | `yearFilter = <year>`；`applyTimeKind(.all)`；`reload()` |
| N+1 | `GlassActionChip(label: vm.timeRangeLabel, active: vm.timeKind == .custom)` | `vm.timeKind == .custom` | `Haptics.tap()`（组件内部还会再触发一次，见 §3.9）+ `showTimeFilter = true` |

- `vm.timeRangeLabel`：`timeKind == .custom` 且有起止日期 → `L10n.dateRange(from,to)`（`%1$@ – %2$@`）；否则「自定义」`footprint_time_custom`（`FootprintViewModel.swift:24-29`）。
- **自定义时间 sheet**（`:193-241`，detent `.medium`）与搜索页时间 sheet **完全同构**（同一个 `GlassSheetHeader` + 同一批 key）：标题 `search_time_title`（自定义时间范围）、三个快捷胶囊 本周/本月/今年（`search_time_week/month/year`，点击 = `applyTimeKind(kind)` + 关 sheet + `reload()`）、开始/结束 `DatePicker`（默认 `monthFirst(today)` / `today`，只取日期）、底部 `清除`(`search_time_clear`) / `确定`(`search_time_apply`)。区别：这里没有 `applyQuickRange`，`applyTimeKind` 会在 `kind != .custom` 时清空自定义日期（`FootprintViewModel.swift:33-39`）。
- **筛选叠加语义**（`FootprintViewModel.swift:66-95`）：先用 `yearFilter` 做 `dayKey.hasPrefix(year)`，再叠加 `timeKind` 的区间（`timeKind != .all` 时）。两者是**交集**。
  > ⚠️ 但年份胶囊的选中态要求 `timeKind == .all`：先用年份、再选自定义区间后，年份芯片会「看起来没选中」，实际两层过滤仍在生效。
- `FootprintDataService.hasPlace(row)`（`FootprintDataService.swift:47-50`）：`country`/`region1`/`region2`/`region3`/`locText` 任一非空即算「有地点」；**经纬度不参与**（旧地图版才需要坐标）。

### 3.3 统计卡（5 格）

`statsCard`（`:93-103`）：`HStack(spacing: 0)` + `.padding(.vertical, 14)` + `diaryCard(Radius.card)`，5 个等宽 `statCell`（`:105-121`）：

| 位置 | 文案 key | zh / en | 数据（`FootprintStats`） |
|---|---|---|---|
| 1 | `footprint_stat_provinces` | 省市 / Provinces | `provinces` = 去重 `country+\u{1}+region1` 集合大小 |
| 2 | `footprint_stat_cities` | 城市 / Cities | `cities` = 去重 `country+\u{1}region1+\u{1}region2` 集合大小 |
| 3 | `footprint_stat_countries` | 国家 / Countries | `countries` = 去重 `country` 集合大小 |
| 4 | `footprint_stat_blocks` | 片段 / Blocks | `blocks` = 已定位 block 计数 |
| 5 | `footprint_stat_days` | 天数 / Days | `days` = 去重 `day_key` 集合大小 |

单元格：`VStack(spacing: 3)`，上为数值 `TypeSize.statValue=18` semibold `onSurface`，下为标签 12pt `onSurfaceVariant`；两行都 `lineLimit(1)` + `minimumScaleFactor(0.6)`；`frame(maxWidth: .infinity)`。

### 3.4 年度趋势图（Swift Charts）

`trendCard(chartHeight:)`（`:125-155`）：

```
VStack(alignment: .leading, spacing: 10)          // 卡片内 spacing 10
├─ Text("年度记录天数" footprint_trend_title) 13pt(sectionTitle) medium onSurfaceVariant
├─ Chart(vm.yearly) { stat in
│     BarMark(x: .value("年份" footprint_trend_year, stat.year),
│               y: .value("天数" footprint_trend_days, stat.days))
│       .foregroundStyle(Theme.primary())          // 品牌蓝实心
│       .cornerRadius(Radius.bar = 4)              // 柱顶圆角 4
│   }
│   .chartYAxis { AxisMarks(position: .leading) { _ in AxisGridLine(); AxisValueLabel() } }
│   .frame(height: chartHeight ?? 132)             // 默认 132
│   .diaryFont(TypeSize.caption = 12)              // 轴标签字号跟随系统字号
│   .accessibilityLabel("年度记录天数")
.padding(14) .frame(maxWidth: .infinity, alignment: .leading) .diaryCard(Radius.card)
```

- **画的是什么**：每个「年」一根柱，y = 该年**去重后的记录天数**（`FootprintYearStat.days`，`FootprintDataService.buildYearly`，`:117-136`）。`FootprintYearStat` 还带 `places`（该年去重地点路径数），但**图上没用**。
- 数据按年份**升序**（`byYear.keys.sorted()`），x 轴是分类轴（字符串年份）。
- 轴：**只有 Y 轴被定制**（`position: .leading`，带网格线 + 数值标签）；X 轴用 Charts 默认（底部，类别标签 = 年份）。
- 颜色：柱 = `Theme.primary()`（`#2563EB`）；网格线/标签用系统默认（继承环境字号 → 12pt caption 缩放）。
- **没有任何点击/选择交互**：全仓库无 `chartXSelection` / `chartOverlay` / `chartGesture`（已 grep 确认）。点击柱子不会高亮、不会筛选、不会跳转。
- **显示条件**：`vm.yearly.count > 1`（`:40, 52`）—— 只有 0 或 1 个年份时**整张趋势卡不渲染**（不是空态，是直接不存在）。这与「清单」的空态不同。
- 空态：因为 `> 1` 才显示，趋势卡没有自己的空态。

### 3.5 地点清单：国家 → 省/州 → 城市 聚合树

`listCard(scrollable:)`（`:166-189`）：标题「地点清单」`footprint_list_title`（13pt medium onSurfaceVariant，`.padding(.bottom, 8)`）+ 内容：

- `vm.hasPlaces`（= `!vm.nodes.isEmpty`）为真 → `FootprintNodeList(nodes: vm.nodes, depth: 0, expanded: $vm.expanded)`；`scrollable == true`（横屏）时外面包一层 `ScrollView(showsIndicators: false)`。
- 否则 → `GlassEmptyState(systemImage: "mappin.slash", text: "还没有地点记录" footprint_empty)`。
- 卡片：`.padding(14)` + （横屏时 `maxHeight: .infinity`，顶部对齐）+ `diaryCard(Radius.card)`。

**树的构建规则**（`FootprintDataService.buildTree`，`:78-114`）：

- 每个 block 的路径 = `[country, region1, region2]` 里**非空**的部分，顺序固定；若三者都为空而 `locText` 非空 → 路径 = `[locText]`（`path(of:)`，`:53-60`）。所以：**直辖市/无省数据的国家会自然降级成更少的层级**，不是固定三层。
- 节点 `id` = 路径用 `\u{1}` 连接（稳定、可跨次刷新复用），`name` = 路径最后一段，`count` = **落在该节点及其所有子孙上的 block 数**（含自身，`:92`）。
- 同级排序：`count` 降序 → 相同则 `localizedStandardCompare` 升序（`:97-103`）。
- 树最多 3 层（depth 0/1/2），`isLeaf = children.isEmpty`。

**行渲染**（`FootprintNodeRow`，`:268-321`）：

| 元素 | 规格 |
|---|---|
| 图标 | 按 `depth`：0 → `globe.asia.australia`，1 → `map`，其余 → `mappin`；12pt medium；`frame(width: 16)`；颜色：叶子 `onSurfaceVariant`，非叶子 `Theme.primary()`（`:289-292, 323-329`） |
| 名称 | 15pt(rowTitle) `onSurface`，`lineLimit(1)` |
| 计数 | `"\(node.count)"` 12pt medium `onSurfaceVariant` + `monospacedDigit()`（等宽数字防跳动） |
| 展开箭头 | **仅非叶子**：`chevron.right` 12pt(caption) semibold `onSurfaceVariant`，`.rotationEffect(.degrees(isExpanded ? 90 : 0))` → 展开时朝下（`:302-307`） |
| 缩进 | `.padding(.leading, CGFloat(depth) * 16)` → 0 / 16 / 32pt（`:309`） |
| 行高 | `.padding(.vertical, 9)`（内容行高 ≈ 15pt 行高 + 18 ≈ 36pt） |
| 命中区 | `.contentShape(Rectangle())` 整行；`.buttonStyle(.plain)` |

**展开 / 收起**（`:281-286`）：

```swift
Button {
    guard !node.isLeaf else { return }        // 叶子：完全无响应（无触觉、无动画）
    Haptics.tap()
    withAnimation(.diaryQuick) {              // easeOut 0.22
        expanded.contains(id) ? expanded.remove(id) : expanded.insert(id)
    }
} label: { … }
```

- 状态是 `Set<String>`（节点 id），存在 VM 里（`FootprintViewModel.swift:20`）。
- 展开的子列表通过**递归具名类型**渲染（`FootprintNodeList` → `FootprintNodeRow` → 再 `FootprintNodeList(depth+1)`；注释说明 `@ViewBuilder` 函数不能自引用，`:254-266`）。
- **不存在「展开清单」按钮/文案**：全仓库无该字符串（已 grep 确认，String Catalog 里只有 `footprint_list_title`）。展开是**逐节点点击**行为。
- 筛选变化后会**剪枝**：`pruneExpanded()` 收集当前树里所有存活 id，与 `expanded` 求交集（`FootprintViewModel.swift:97-108`），避免集合无限增长与「展开到不存在的节点」。
- 无障碍：每行 `accessibilityIdentifier("footprint.node")`（供 UI 测试），展开时加 `.isSelected` trait（`:314-315`）。

### 3.6 空态

- **清单为空**（当前筛选下没有任何有地点的 block）→ `GlassEmptyState("mappin.slash", "还没有地点记录")`，居中、`padding(.top, 56)`、图标 26pt `onSurfaceVariant` 50% 透明、文字 15pt（`Components.swift:474-496`）。
- 注意：统计卡照常渲染（全 0 或部分为 0）；趋势卡在 `yearly.count > 1` 时不渲染 —— 所以「空页」= 统计卡 + 空态清单，中间没有趋势卡。
- 页面级**没有**独立的 loading 态：初次 `load()` 期间 `nodes` 为空 → 直接显示空态，随后数据到达再替换。

### 3.7 刷新时机

| 触发 | 行为 |
|---|---|
| 首次出现 | `.task { await vm.load() }`（`loaded` 守卫，只跑一次） |
| `.diaryVersionChanged`（写日记、删日记、导入、改开始时间后重算日期…） | `vm.reload()` |
| 年份/全部/自定义时间 chip | 改 `yearFilter` / `timeKind` 后手动 `Task { await vm.reload() }` |
| 时间 sheet 的快捷/清除/确定 | 同上 |
| 语言/主题（`uiTick` → `.id` 重建） | VM 重建 → `loaded=false` → `.task` 重新 `load()` |

### 3.8 竖屏 / 横屏差异（足迹页）

| 项 | 竖屏（402×874） | 横屏（874×402） |
|---|---|---|
| 主列 | `VStack(spacing: 12)`：统计卡 → 趋势卡（若 >1 年）→ 清单，单栏纵向（`:36-42`） | `VStack(spacing: 12)`：统计卡 → `HStack(alignment: .top, spacing: 12)`：**趋势卡（flex，占满剩余宽） + 清单（固定宽）**（`:43-62`） |
| 区块高度 | 自然高度 | `blockH = max(140, min(190, layout.size.height - 262))`；横屏 402 → `min(190, 140) = 140`（`402-262=140`）；该高度同时约束趋势卡与清单（`.frame(height: blockH)`，`:48,60`） |
| 图表高度 | 132（默认） | `max(72, blockH - 58)` = 82（`blockH=140`）；`:53` |
| 清单宽度 | 全宽 | `listPaneWidth = min(320, max(240, layout.contentWidth * 0.4))`；横屏 `contentWidth = 750` → `min(320, max(240,300)) = 300pt`（`:160-162`） |
| 清单滚动 | 跟随整页滚动（`scrollable: false`） | 卡片内独立滚动（`scrollable: true`，`maxHeight: .infinity`），否则长清单把卡片顶出屏幕（`:164-165, 174-180`） |
| 底部让位 | `.padding(.bottom, max(24, bottomInset + 44))`；竖屏 bottomInset=34 → **78** | 同式；横屏 bottomInset=20 → **64**（正好等于系统浮条高度，注释写在 `:66-67`） |
| 左右边距 | 16 / 16 | 16 / 16 |

> 横屏这套 `−262` / `−58` / `×0.4` 都是经验值（注释：标题+筛选+统计约占 200pt，浮条 64pt，其余留给内容），换机型/换字号后需要重新校准。Android 重建建议改成约束布局（`weight(1f)` + 固定宽 300dp 侧栏）而不是硬编码 262。

### 3.9 足迹页的脆弱点

1. `GlassActionChip` 内部已 `Haptics.tap()`（`Components.swift:448`），`FootprintView.swift:27` 又调一次 → 自定义时间 chip **双触觉**。
2. 年份 + 自定义区间可同时生效但年份 chip 不高亮（§3.2）。
3. 横屏 `blockH` 在极小高度下（如 402-262=140 已达下限 140）会把图表压到 82pt，柱子几乎不可读。
4. 趋势卡「>1 年才出现」意味着：单年用户永远看不到图表，也没有任何提示为什么没有图。
5. `unlocated` 与筛选无关（`rows.count - located.count`），但标题摘要把它和「已标注（筛选后）」并列 → 数字看起来不自洽（例如选了 2023 年时显示「已标注 12 处 · 未记录 340 篇」）。
6. 首次加载无 loading 态，会先闪一下空态。

---

## 4. 设置页

### 4.1 结构

`SettingsView.body`（`SettingsView.swift:7-97`）：`ScrollView(showsIndicators: false)` → `VStack(spacing: 12)`：

```
PageHeader("设置" settings_title)            // :101-103（无 trailing）
generalCard   通用                          // :231-256
rulesCard     日记规则                       // :276-317
reminderCard  提醒                          // :321-345
dataCard      数据                          // :349-367
aboutCard     关于                          // :371-377
TabBarClearance()                          // :16（底部让开浮条）
.adaptivePagePadding() .padding(.top, 12)   // :19-20
```

- 卡片 = `card { VStack(spacing: 0) { sectionTitle + rows + RowDivider } }.diaryCard(Radius.card)`（`:107-112`）。
- 分区标题 `sectionTitle`：13pt(sectionTitle) medium `onSurfaceVariant`，`.frame(maxWidth: .infinity, alignment: .leading)`，`padding(.horizontal, 16)`、`.padding(.top, 12)`、`.padding(.bottom, 0)`（`:114-122`）。
- 行间分隔线：`RowDivider(horizontalPadding: RowDivider.textInset)`，`textInset = 12 + 38 + 12 = 62pt`（与行内**文字**左对齐，而不是与图标对齐，`Components.swift:617-630`）。
- 刷新：`.task { await vm.refreshStatus() }`（定位/通知权限文案）、`.onReceive(.uiTickChanged) { vm.refreshSettings() }`、`.onReceive(didBecomeActive) { refreshStatus() }`（`:22-28`）。

### 4.2 三种行类型（尺寸 / 状态）

| 类型 | 结构 | 尺寸 | 交互 |
|---|---|---|---|
| `valueRow`（`:124-132`） | `Button { Haptics.tap(); action() } label: rowLabel`，`.buttonStyle(.plain)` | 行 `padding(.horizontal, 12)`、`frame(minHeight: 56)`、`contentShape(Rectangle())` | 整行可点 → 打开 sheet / 打开系统设置 / 无操作 |
| `menuRow`（`:142-152`） | `Menu { options } label: rowLabel`，`.menuOrder(.fixed)`，`.accessibilityValue(value)` | 同上 | 点行 → 系统玻璃菜单；每项 `option`（`:155-167`）：选中 = `Label(title, systemImage: "checkmark")`，未选中 = `Text(title)`；点击 `Haptics.tap()` + action，选中项带 `.isSelected` trait |
| `switchRow`（`:201-227`） | `HStack{ 徽标 + 文本 + Spacer(minLength:8) + Toggle("", isOn:).labelsHidden().tint(primary) }` | 同上（`.padding(.horizontal, 12)`、`minHeight 56`） | 拨动 → `onChange` → `Haptics.tap()` + 回调；`Toggle` 带 `accessibilityLabel(title)` |

`rowLabel` 内部（`:169-199`）：`HStack(spacing: 12)` → `GlassIconBadge(systemName:)`（38×38，圆角 12，`primaryContainer` 填充 + `glassBorder` 1pt 描边，图标 16pt）→ `VStack(alignment: .leading, spacing: 2)`{ 标题 15pt `onSurface`；副标题 13pt `onSurfaceVariant`（`lineLimit(2)`，`nil` 时不占位）} → `Spacer(minLength: 8)` → 值文本 15pt `onSurfaceVariant`（`lineLimit(1)`、`minimumScaleFactor(0.7)`、右对齐）→ `chevron.right` 15pt `onSurfaceVariant`。注释说明：值文本必须限行/缩放，否则放大系统字号后会把箭头挤出屏幕（`:184-185`）。

### 4.3 通用卡 `generalCard`（`settings_section_general` = 通用 / General）

| # | 图标 | 标题（key） | 副标题（key） | 控件 | 当前值 | 选项 / 变更后行为 |
|---|---|---|---|---|---|---|
| 1 | `globe` | 语言 / Language（`settings_language`） | 应用内显示语言 / Display language of the app（`settings_language_sub`） | `menuRow` | `langLabel`：`system`→跟随系统(`settings_lang_system`)、`zh`→**中文**、`en`→**English**（zh/en 两项是**硬编码字面量**，不随语言变化，`SettingsView.swift:266-272`） | 选项：跟随系统 / 中文 / English。→ `setLanguage(_)`：写设置 → `SettingsStore.load()`（AppLanguage 立即改变）→ `DiaryRepository.shared.bumpUiTick()`（→ 根视图 `.id` 重建，全 UI 换语言）→ `ReminderService.rearm()`（`SettingsViewModel.swift:46-52`） |
| — | — | `RowDivider(textInset=62)` | | | | |
| 2 | `paintpalette` | 主题 / Theme（`settings_theme`） | 浅色、深色或跟随系统 / Light, dark, or system（`settings_theme_sub`） | `menuRow` | `themeLabel`：跟随系统 / 浅色 / 深色（`settings_theme_system/light/dark`） | 选项：跟随系统 / 浅色 / 深色。→ `setTheme(_)`：写设置 → `AppConfigService.applyAll()`（`DayDraw.clearCache()` + `bumpUiTick()`）；实际配色由 `RootView.preferredColorScheme(AppConfigService.colorScheme)` 生效（`SettingsViewModel.swift:40-44`、`AppConfigService.swift:4-22`） |

### 4.4 日记规则卡 `rulesCard`（`settings_section_rules` = 日记规则 / Diary rules）

| # | 图标 | 标题（key） | 副标题（key） | 控件 | 当前值 | 变更后行为 |
|---|---|---|---|---|---|---|
| 1 | `sun.horizon` | 新一天的开始时间 / Start of a new day（`settings_day_start`） | 这个时间前算前一天 / Before this time = previous day（`settings_day_start_sub`） | `valueRow` → 打开小时滚轮 sheet（`.medium`） | `L10n.dayStartLabel(hour)`：0→「凌晨 0:00」(`time_label_midnight`)、1–5→「凌晨 %1$ld:00」(`time_label_dawn`)、6–11→「上午 %1$ld:00」(`time_label_morning`)、12→「中午 12:00」(`time_label_noon`)、13–17→「下午 %1$ld:00」(`time_label_afternoon`)、其余→「晚上 %1$ld:00」(`time_label_evening`)（`L10n.swift:228-237`） | sheet 内 `Picker(.wheel)` 列 0…23，每项文案 = `L10n.timeLabel(hour:h, minute:0)`（**跟随设备 12/24 小时制 + App 语言**），底部「保存」(`save`) → `applyDayStart()`：`save()` → `recomputeDayKeys(dayStartHour:)` 重算所有日记归属日 → `bumpDiaryVersion()` → **若冲突数 > 0** 弹 alert：标题「日期重算」(`settings_day_recalc_title`)、正文「有 %d 篇日记因规则变化保留了原归属日。」(`settings_day_recalc_msg`)（`SettingsViewModel.swift:114-125`） |
| — | — | `RowDivider` | | | | |
| 2 | `calendar` | 一周从哪一天开始 / Start of the week（`settings_week_start`） | 日历以周几开头 / Which day a week starts on（`settings_week_start_sub`） | `menuRow` | `weekStart == "sunday"` → 周日(`settings_week_sunday`) 否则 周一(`settings_week_monday`) | 选项：周一 / 周日。→ `setWeekStart(_)`：写设置 + `bumpUiTick()`（影响首页日历表头、搜索「本周」范围 `DateUtil.weekFirst`）（`SettingsViewModel.swift:54-58`） |
| — | — | `RowDivider` | | | | |
| 3 | `clock` | 自动记录时间 / Auto-record time（`settings_auto_time`） | 只影响是否显示 / Display only（`settings_auto_time_sub`） | `switchRow` | `settings.autoTime`，默认 **true**（`AppSettings.swift:11`） | `setAutoTime(_)`：写设置 + `bumpUiTick()`。注释强调：**这是纯显示开关**（编辑器时间胶囊/阅读页/首页/分享长图），`start_time_utc` 永远照常记录（`SettingsViewModel.swift:60-67`、`AppSettings.swift:5-11`） |
| — | — | `RowDivider` | | | | |
| 4 | `location` | 自动插入地点 / Auto-insert location（`settings_auto_loc`） | 需要位置信息权限 / Requires location permission（`settings_auto_loc_sub`） | `switchRow` | `settings.autoLoc`，默认 **true**（`AppSettings.swift:12`） | `setAutoLoc(_)`：写设置；**打开时**额外 `LocationService.shared.requestPermission()` + `DiaryRepository.backfillBlockRegions()`（回填历史 block 的国家/省市）（`SettingsViewModel.swift:69-76`） |
| 5 | `location.circle` | 位置权限 / Location permission（`settings_loc_permission`）**仅当 `autoLoc == true` 时出现** | 地点只存在本机 / Stays on this device only（`settings_loc_permission_sub`） | `valueRow` | `locStatusText`：已授权且精确 → 「精确」(`settings_loc_exact`)、已授权但模糊 → 「模糊」(`settings_loc_fuzzy`)、其它 → 「未授权」(`settings_loc_none`)（`SettingsViewModel.swift:25-34`） | `handleLocPermission()`：`notDetermined` → 请求权限；其它 → 打开系统设置 `UIApplication.openSettingsURLString`；随后延时 **800ms** 再 `refreshStatus()`（`:99-112`） |
| — | — | `RowDivider` | | | | |
| 6 | `pencil` | 允许修改 / 删除历史日记 / Edit past entries（`settings_history_edit`） | 历史日期只能改，不能新增 / Past days: edit or delete only（`settings_history_edit_sub`） | `switchRow` | `settings.allowHistoryEdit`，默认 **false**（`AppSettings.swift:13`） | `setAllowHistoryEdit(_)`：只写设置（`SettingsViewModel.swift:78-81`） |

### 4.5 提醒卡 `reminderCard`（`settings_section_remind` = 提醒 / Reminders）

| # | 图标 | 标题（key） | 副标题（key） | 控件 | 当前值 | 变更后行为 |
|---|---|---|---|---|---|---|
| 1 | `bell` | 写日记提醒 / Journal reminder（`settings_remind_enabled`） | 当天未写日记时提醒我 / Remind me when I haven't written today（`settings_remind_enabled_sub`） | `switchRow` | `settings.remindEnabled`，默认 **true**（`AppSettings.swift:17`） | 开 → 写设置 + `ReminderService.requestEnable()`；授权成功再 `ensureDailyReminder()`；最后 `refreshStatus()`。关 → `ReminderService.cancelAll()`（`SettingsViewModel.swift:83-97`） |
| 2 | `clock.badge` | 提醒时间 / Reminder time（`settings_remind_time`）**仅当提醒开启** | 每天提醒一次 / Once a day（`settings_remind_time_sub`） | `valueRow` → 时间滚轮 sheet（`.medium`） | `L10n.timeLabel(hour:minute:)`，默认 `21:00` → zh「晚上 9:00」/ en「9:00 PM」（`AppSettings.swift:18-19`） | sheet 用 `DatePicker(displayedComponents: .hourAndMinute, style: .wheel)`（**不是手搓双滚轮**，注释说明这样才会跟随语言与 12/24 小时制，`SettingsView.swift:409-430`），底部「保存」→ `applyRemindTime()`：`save()` + `ReminderService.rearm()` |
| 3 | `bell.badge` | 通知权限 / Notification permission（`settings_notif_permission`）**仅当提醒开启 且 `notifStatusText == 「未开启」`** | 关闭后无法收到提醒 / Reminders won't arrive when off（`settings_notif_permission_sub`） | `valueRow` | `notifStatusText`：`settings_notif_on`「已开启」/ `settings_notif_off`「未开启」（由 `ReminderService.notificationEnabled()` 判定） | `requestNotificationPermission()`：`requestEnable()` → `refreshStatus()`（`SettingsViewModel.swift:132-137`） |

> 「未开启」在代码里是**用本地化字符串做比较**（`SettingsView.swift:336`：`vm.notifStatusText == L10n.str("settings_notif_off")`）——语言切换后同一次刷新周期内可能不相等，属脆弱写法。Android 侧请用枚举而非文案比较。

### 4.6 数据卡 `dataCard`（`settings_section_data` = 数据 / Data）与危险区

| # | 图标 | 标题（key） | 副标题（key） | 控件 | 当前值 | 行为 |
|---|---|---|---|---|---|---|
| 1 | `square.and.arrow.up` | 导出全部日记 / Export all entries（`settings_export`） | 生成 .jdiary 备份包 / Generate a .jdiary backup（`settings_export_sub`） | `menuRow`（**value 传空字符串**，所以行尾只有一个 chevron） | `""` | 两个选项（都不带 checkmark，`selected: false`）：「仅日记数据」(`settings_export_data_only`) → `runExport(includeSettings: false)`；「日记 + 设置」(`settings_export_data_settings`) → `runExport(includeSettings: true)` |
| — | — | `RowDivider` | | | | |
| 2 | `square.and.arrow.down` | 导入备份 / Import backup（`settings_import`） | 从其他设备恢复日记 / Restore entries from another device（`settings_import_sub`） | `valueRow`（value 空串） | `""` | 打开系统文件选择器（`UIDocumentPickerViewController(forOpeningContentTypes: [.data])`，单选；取消 → 无后续）→ 收到 URL 后弹出**破坏性确认对话框** |

**导出流程**（`SettingsViewModel.swift:139-154` + `SettingsView.swift:29-36`）：

1. `busyText = "正在生成备份…"`(`settings_export_busy`) → 全屏 25% 黑遮罩 + 玻璃卡（大号菊花 + 13pt 文案）。
2. `BackupService.exportBackup(includeSettings:)` 成功 → `exportURL` 赋值 → 弹 `.sheet`：`ShareLink(item: url, preview: SharePreview(L10n.str("EntryAbility_label")))`，`.buttonStyle(.glassProminent)`、`padding(24)`、`presentationDetents([.height(170)])`。
   - ⚠️ `EntryAbility_label` 在**两种语言下都是「一页时光」**（zh 与 en 值相同，见字符串表），也就是英文环境下分享预览标题仍是中文 App 名 —— 这是从鸿蒙工程带过来的 key 名与文案。
3. 失败 → alert：标题「导出失败」(`settings_export_failed_title`)、正文「生成或保存备份时出错，请重试。」(`settings_export_failed_msg`)。

**导入流程**（`SettingsView.swift:37-54` + `SettingsViewModel.swift:156-181`）：

1. 选文件后 `vm.importURL = url; vm.importModeShowing = true`。
2. `confirmationDialog`：标题「导入方式」(`settings_import_mode_title`)，`titleVisibility: .visible`；动作按钮：
   - 「跳过已有日记」(`settings_import_skip`) → `runImport(mode: "skip")`
   - 「覆盖已有日记」(`settings_import_overwrite`) → `runImport(mode: "overwrite")`（**覆盖 = 破坏性**，但代码里**没有** `role: .destructive`，两个按钮同色）
   - 「取消」(`cancel`, `role: .cancel`)
3. `busyText = "正在导入备份…"`(`settings_import_busy`) → 遮罩 → 导入成功后：重新 `SettingsStore.load()`、`bumpDiaryVersion()`、`bumpUiTick()`，然后 alert：
   - 标题「导入完成」(`settings_import_success_title`)
   - 正文 `L10n.fmt("settings_import_success_msg", importedDays, skippedDays, overwrittenDays, importedBlocks, importedImages)` = zh「新增 %d 天，跳过 %d 天，覆盖 %d 天（共 %d 个编辑块、%d 张图片）。」/ en「Added %d days, skipped %d, overwritten %d (%d blocks, %d images).」
   - 若备份里带了设置：追加 `"\n" + "已同时恢复应用设置。"`(`settings_settings_restored`)
4. 失败 → 标题「导入失败」(`settings_import_failed_title`)、正文「备份文件无效或导入出错，请重试。」(`settings_import_failed_msg`)。

> **危险区范围**：设置页**没有**「清空全部日记 / 删除所有数据 / 重置设置」入口；唯一的破坏性路径就是导入时的「覆盖已有日记」，以及改「新一天的开始时间」触发的日期重算（可能改变既有日记的归属日）。

### 4.7 关于卡 `aboutCard`（`settings_section_about` = 关于 / About）

| # | 图标 | 标题（key） | 副标题 | 控件 | 当前值 | 行为 |
|---|---|---|---|---|---|---|
| 1 | `info.circle` | 版本 / Version（`settings_version`） | 无（`sub: nil`） | `valueRow`，action = `{}` | `SettingsStore.appVersion` = `CFBundleShortVersionString`，取不到兜底 `"1.0"`（`AppSettings.swift:90-92`） | **点了没有任何反应**（只有触觉与按压反馈）——但仍画了 `chevron.right`，视觉上「像能点」。重建时建议改成不可点行（无箭头）或去掉箭头 |

### 4.8 结果 alert 的呈现规则（`appAlert`）

`Components.swift:518-535`：

```swift
func appAlert(item: Binding<AppAlertItem?>) -> some View {
    alert(item.wrappedValue?.title ?? "", item: item) { current in
        if let secondary = current.secondaryLabel {
            Button(secondary) { current.primaryAction?() }
            Button(current.cancelLabel, role: .cancel) {}
        } else {
            Button(current.cancelLabel) { current.primaryAction?() }   // ← 单按钮时用「取消」当确认键
        }
    } message: { current in Text(current.message) }
}
```

- `AppAlertItem`（`:500-516`）：`title`、`message`、`cancelLabel`（默认 `L10n.str("cancel")` = 取消/Cancel）、可选 `secondaryLabel`、可选 `primaryAction`。
- 两个工厂：`AppAlertItem.confirm(title:message:confirmLabel:action:)`（把 `confirmLabel` 塞进 `secondaryLabel`，于是渲染成「确认 + 取消」两个按钮）、`AppAlertItem.info(title:message:action:)`（无 secondary）。
- 设置页的三处 alert（重算冲突、导出失败、导入成功/失败）全部走 `info` 形态 → **只有一个标着「取消」的按钮**，点它就是「确定」（回调为 nil，仅关闭）。Android 侧应换成「确定」文案。

### 4.9 竖屏 / 横屏（设置页）

- **没有横屏分支**：`SettingsView` 不读 `adaptiveLayout`，任何宽度都是单列、卡片通栏（横屏可用宽 750 − 32 = 718pt）。
- 两个 picker sheet 固定 `.medium`；导出分享 sheet 固定 `.height(170)`。
- 行高 56pt 在横屏只有 402pt 高的屏幕上一屏约显示 4 行；`ScrollView` + `TabBarClearance()` 保证最后一行不被浮条压住。
- 字号放大（Dynamic Type）：行高是 `minHeight`，可继续长高；值文本 `minimumScaleFactor(0.7)` 防止把箭头挤出。

### 4.10 设置页的脆弱点

1. `AppAlertItem.info` 的单按钮文案是「取消」而不是「确定」（§4.8）。
2. 通知权限行的显现条件用**本地化字符串比较**（§4.5）。
3. 导入对话框的「覆盖已有日记」没有 destructive role / 红色。
4. 「版本」行有 chevron 但点击无反应。
5. `menuRow` 的 `value` 对「导出/导入」传空串，仍会渲染一个空的 `Text("")`（占位 0 宽，无害但语义混乱）。
6. 语言切换依赖根视图 `.id` 重建 → 设置页自身的 sheet 状态、其它 tab 的 VM 状态都会丢（§1.9）。
7. 位置权限状态是**打开页面时/回到前台时**刷新 + 固定 800ms 延时猜测系统设置返回，没有真正的生命周期回调。

---

## 5. 通用组件（`JustDiary/Views/Components/Components.swift`）

> 通用约定：**内容区一律用不透明卡片**（`diaryCard`，系统 grouped background + 细描边 + 阴影），**控件层一律用 Liquid Glass**（chip / 图标按钮 / 搜索框 / 主次按钮）。`Components.swift:121-129` 的注释给出理由（WWDC26 session 8120：内容区没有可折射的东西，玻璃卡会读成「玻璃上的卡」）。Android 上「内容卡」= `Surface(color = surfaceContainerLow)`，「控件层」= `Surface(tonalElevation)` 或描边胶囊，不要滥用 blur。

### 5.1 动画常量

| 常量 | 值 | 位置 | 用途 |
|---|---|---|---|
| `Animation.diaryQuick` | `.easeOut(duration: 0.22)` | `Components.swift:7` | 展开/收起、条件行出现、chip 交互 |
| `Animation.diaryStandard` | `.easeInOut(duration: 0.3)` | `:8` | 顶部控件隐藏/展开 |

### 5.2 `PageHeader`（`:164-189`）

- 解剖：`HStack(alignment: .center, spacing: 12)`：标题（`TypeSize.pageTitle=24`，weight `.medium`，`onSurface`，`lineLimit(1)`，`minimumScaleFactor(0.7)`，`allowsTightening`）+ `Spacer(minLength: 8)` + `trailing()` 闭包（默认 `EmptyView`）。
- 高度：`.frame(minHeight: 52)`（注释：用 `height` 会在放大字号时裁掉标题）。
- 使用：搜索页（trailing = 计数徽标）、足迹页（trailing = 摘要文字）、设置页（无 trailing）。首页用自己的 header。
- Compose：`Row(verticalAlignment = CenterVertically)` + `Text(style = headlineSmall.copy(fontSize = 24.sp))`，`heightIn(min = 52.dp)`。

### 5.3 `GlassChip`（`:366-389`）

- 解剖：一个 `Text`，`TypeSize.chip=14`，`lineLimit(1)`、`minimumScaleFactor(0.9)`、`padding(.horizontal, 12)`、`padding(.vertical, 7)`、`contentShape(Capsule())`；`.buttonStyle(.glass)` + `.buttonBorderShape(.capsule)`。
- 状态：`active == true` → 文字 `Theme.primary()`；否则 `Theme.onSurface()`。**填充/描边全由系统玻璃样式提供**，没有自定义选中背景色。`accessibilityAddTraits(active ? .isSelected : [])`。
- 命中/视觉尺寸：`.glass` 样式会在标签外**每边再加 4pt**（`PressableGlassIcon` 注释 `:321-322` 明确），所以 14pt 文字 + 上下各 7pt ≈ 31pt 标签 → 视觉胶囊 ≈ **39pt 高**，比 44pt 最小命中区略小；放在横向 `ScrollView` 里必须留 `padding(.vertical, 6)`，否则上下高光被裁（`SearchView.swift:183-192`）。
- 使用：搜索页时间/地点筛选、快速区间；足迹页年份/全部；弹窗内快捷区间。
- Compose：`FilterChip`（`selected` 时 `labelColor = primary`，容器用 `surfaceContainerHigh`；若要玻璃感用 `Surface(shape = CircleShape, border = 1.dp white 20%)`），高度建议 40.dp。

### 5.4 `GlassActionChip`（`:440-470`）

- 与 `GlassChip` 的差异：可带前置图标（`systemImage`，11pt medium）、`HStack(spacing: 4)`、`padding(.horizontal, 10)/.vertical, 6)`、文字 `minimumScaleFactor(0.85)`。
- 状态：`active` → 文字 `primary`，否则 `onSurface`；同样是 `.glass` + capsule。
- 内部自带 `Haptics.tap()`（`:448`）；`FootprintView.swift:27` 又调一次 → 双触觉。
- 使用：足迹页「自定义」时间入口（`FootprintView.swift:25-29`）—— 这是全仓库唯一的使用点。

### 5.5 `InfoCapsule`（`:391-412`）与 `GlassCountBadge`（`:416-438`）

| 组件 | 解剖 | 尺寸 | 状态 | 使用 |
|---|---|---|---|---|
| `InfoCapsule` | 纯文字胶囊，`TypeSize.chip=14` + `.medium`，`onSurface`，`lineLimit(1)`、`minimumScaleFactor(0.8)`，`.glass` + capsule | `padding h12 v7` | 有 `action` 时可点（点击先 `Haptics.tap()`），`nil` 时也是 Button（无操作） | 首页「今天 / 相对日期」入口（`HomeView.swift:144`、`CalendarPaneViews.swift:180`） |
| `GlassCountBadge` | `HStack(spacing: 5)`：可选图标（10pt semibold）+ 文本（`TypeSize.badge=12` + `.medium`，`lineLimit(1)`、`minimumScaleFactor(0.8)`）；`Theme.primary()`；背景 `Capsule().fill(Theme.primaryContainer())` | `padding h10 v5` | 静态（不可点） | 搜索页结果计数（icon `circle.fill`，`SearchView.swift:131`）、编辑器搜索命中 `3/12`（`DiaryPageView.swift:406`）、编辑中标记（`:524`） |

### 5.6 按钮族：`GlassPrimaryButton` / `GlassSecondaryButton` / `GlassIconButton` / `PressableGlassIcon`

**`GlassPrimaryButton`**（`:193-243`）
- 解剖：`HStack(spacing: 6)`：可选图标（13pt semibold；`compact` 时 12pt）+ 标题（`TypeSize.rowTitle=15` medium；`compact` 时 `TypeSize.chip=14`）；`lineLimit(1)`、`minimumScaleFactor(0.85)`。
- 尺寸：`padding(.horizontal, compact ? 14 : 20)`、`padding(.vertical, compact ? 7 : 12)`；`fullWidth` → `frame(maxWidth: .infinity)`；`contentShape(Capsule())`。
- 背景（启用）：`Capsule().fill(Theme.primary())` + `.glassEffect(.regular.tint(Theme.primary()).interactive(true), in: Capsule())` + `shadow(color: Theme.glowColor(), radius: compact ? 8 : 10, y: compact ? 2 : 3)`。注释说明：**opaque 品牌填充是刻意的**，CTA 对比度优先（`:225-234`）。
- 禁用（`enabled == false`）：`Capsule().fill(Theme.primaryContainer()).opacity(0.6)`，文字 `white.opacity(0.55)`。
- 使用：搜索页「加载更多」「清除全部条件」、搜索/足迹弹窗「确定」、设置 picker sheet「保存」、首页「写日记」「继续写日记」。
- Compose：`Button(colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = onPrimary), shape = CircleShape, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp))`。

**`GlassSecondaryButton`**（`:245-285`）
- 解剖：同上但图标 13pt、标题 `rowTitle=15` medium、`onSurfaceVariant` 文字；尺寸固定 `padding h20 v12`（**没有 compact**）；背景 `Capsule().fill(Theme.glassDim())` + `.glassEffect(.regular.interactive(true))`。注释：`glassDim` 是半透明白，先铺一层能避免玻璃按钮在亮背景上「只剩一条看不见的描边」（`:274-279`）。
- 使用：搜索/足迹时间弹窗的「清除」。

**`GlassIconButton`**（`:287-309`）
- 解剖：`Image(systemName:)`，图标字号 `size > 36 ? 16 : 14`（semibold），颜色 `tint ?? Theme.onSurfaceVariant()`，`.frame(minWidth: 44, minHeight: 44)`，`contentShape(Circle())`；`.buttonStyle(.glass)` + `.buttonBorderShape(.circle)`；`accessibilityLabel(label ?? systemName)`。
- 参数 `size` 默认 32，**只影响字号**，圆形视觉尺寸恒为 44（命中区）。搜索页传 28 → 实际 14pt 图标 + 44pt 圆。
- 使用：`GlassSheetHeader` 的关闭按钮（`xmark`）、搜索页清除全部条件（`xmark.circle.fill`）。

**`PressableGlassIcon`**（`:311-343`）
- 解剖：图标 17pt **medium** + `.symbolRenderingMode(.hierarchical)`；颜色 `active ? Theme.primary() : Theme.onSurface()`；标签框 `max(28, size - 8)`（默认 size 44 → **36pt**，因为 `.glass` 样式每边再加 4pt，最终视觉圆 = 44pt）；`contentShape(Circle())`；`.glass` + circle；`.symbolEffect(.bounce, value: active)`；`active` 时加 `.isSelected` trait。
- 使用：编辑器顶栏（返回 / 搜索 / 编辑 / 分享 / 图片 / 撤销 / 完成）。
- Compose：`FilledTonalIconButton` / `IconButton(colors = …)` + `Modifier.size(44.dp)`；`symbolEffect(.bounce)` → 用 `animateFloatAsState` 做一次 scale 弹跳。

### 5.7 `GlassSheetHeader`（`:347-362`）

- 解剖：`HStack`{ 标题 `TypeSize.sheetTitle=17` medium `onSurface`（`lineLimit(1)`、`minimumScaleFactor(0.7)`）+ `Spacer()` + `GlassIconButton("xmark")` }。
- 使用：搜索时间弹窗、搜索地点弹窗、足迹时间弹窗、设置的两个 picker sheet —— **本项目所有自定义 sheet 的统一表头**。
- 弹窗内容统一 `padding(20)`（搜索/设置的 picker）+ 需要时 `padding(.bottom, 8)`。

### 5.8 列表行辅助：`RowDivider`（`:617-630`）与 `GlassIconBadge`（`:632-661`）

| 组件 | 规格 | 备注 |
|---|---|---|
| `RowDivider` | `Divider().overlay(Theme.outlineVariant().opacity(0.5)).padding(.horizontal, horizontalPadding)` | `static let textInset = Spacing.card(12) + 38 + Spacing.rowIcon(12) = 62`（`:621`）；设置页全部行间分隔线用 `textInset`，让分隔线从**文字**起点开始而不是图标下方（对齐 Settings.app） |
| `GlassIconBadge` | `ZStack`：`RoundedRectangle(cornerRadius: 12, style: .continuous)` 填充 `primaryContainer` + `glassBorder` 1pt 描边 + 图标 16pt `onSurface`；边长 `side = max(size, scaled(16) + 12)`（默认 `size = 38`，最小 38，字号放大时随字形增长，但**必须保持正方形**：注释说明用 `minWidth/minHeight` 会被 HStack 拉成长条） | 使用：设置页每一行的前置徽标 |

### 5.9 空态 / 卡片 / 弹窗提示

**`GlassEmptyState`**（`:474-496`）：`VStack(spacing: 12)`{ 图标（`diaryFont(26)`，`Theme.onSurfaceVariant().opacity(0.5)`）+ 文案（`TypeSize.rowTitle=15`，`onSurfaceVariant`，居中，可多行）+ 可选 `GlassPrimaryButton(compact: true)` }；`.frame(maxWidth: .infinity)` + `.padding(.top, 56)`。使用：足迹清单空态（`mappin.slash` + 「还没有地点记录」）。搜索页**不用**它，用系统 `ContentUnavailableView`。

**`diaryCard(cornerRadius:interactive:)`**（`:130-160`）——所有内容卡：
- 填充 `Color(.secondarySystemGroupedBackground)`（系统 grouped 背景，随亮暗自动）。
- 描边两层叠加（`:146-153`）：`onSurface` @ `0.0`（常规）/ `0.55`（高对比度，1pt）+ `outlineVariant` @ `0.45`（0.5pt）/ `0.9`（1.5pt）。即**常规下只有 0.5pt 的 `outlineVariant` 45% 细线**。
- 阴影：`Theme.shadowColor()`，`interactive ? 0.22 : 0.14`，`radius … ? 12 : 8`，`y … ? 4 : 2`。
- 圆角统一 `style: .continuous`（squircle）；`interactive: true` 目前只用于地点弹窗的搜索框（`SearchView.swift:422`）。

**`AppAlertItem` + `.appAlert(item:)`**（`:500-535`）：见 §4.8。视觉 = 系统 alert（Liquid Glass）；`title` 直接作为 alert 的标题参数，`message` 是正文；按钮顺序由系统决定。

**Toast（仅首页有）**（`HomeView.swift:45-64`）：`Text`(`TypeSize.meta=13`, `onSurface`) + `padding h16 v10` + `Capsule().fill(.clear).glassEffect(.regular, in: Capsule())` + `shadow(Theme.shadowColor(), radius: 12, y: 4)` + `padding(.bottom, 24)`，`transition(.opacity + .move(edge: .bottom))`，出现/消失动画 `.snappy(duration: 0.25)`，停留 2.5s，附带 `.sensoryFeedback(.warning, trigger:)`；文案「无法为未来的日期写日记。」(`index_future_toast`)。**四屏内没有其它 toast/snackbar**。

**`BlobBackground` / `.diaryBackground()`**（`:76-119`）：`ZStack`{ `Theme.bg()` + 三个大圆 blur 做氛围 }，`.ignoresSafeArea()`；非 dense 参数：280pt blur 60 @ (-60, 220)、240pt blur 60 @ (width+20, 640)、200pt blur 55 @ (width-30, -10)；`dense` 变体：300/70 @ (-80,200)、250/65 @ (w+20,560)、210/60 @ (w-40,-30)。四个 tab 根视图都套 `.diaryBackground()`。

**`FlowLightOverlay`**（`:665-705`）：卡片边缘流光（60fps `TimelineView`，4 秒循环，3pt 高光带，顶边 y=6、底边 y=height-6，第二道光透明度 ×0.7，播放 10 秒后 1 秒淡出暂停，尊重 `reduceMotion`，`allowsHitTesting(false)`）。**四屏未使用**（首页卡片用）。

### 5.10 `GlassSearchField`（`:547-597`）

见 §2.2。尺寸：`HStack(spacing: 8)`（放大镜 15pt `onSurfaceVariant` + `TextField` 15pt + 可选清除按钮 + `trailing()`），`padding(.horizontal, 14)`、`.frame(minHeight: 46)`，背景 `Capsule().fill(.clear).glassEffect(.regular.interactive(true), in: Capsule())`；`.submitLabel(.search)`。使用：搜索页（trailing = 取消按钮）、编辑器内查找（trailing = `GlassCountBadge` 命中计数，`DiaryPageView.swift:402-407`）。

### 5.11 `TabBarClearance`（`:599-615`）

- 解剖：`Color.clear.frame(height: base * DynamicTypeMetrics.multiplier(for: TypeSize.rowTitle, typeSize:))`，`base` 默认 **120**。
- 语义：滚动内容末尾垫高，避免最后一行被**浮动的系统 tab bar** 遮住；因为系统浮条会随字号长高，所以这里按 `rowTitle(15)` 的倍率缩放（默认字号 = 120pt）。
- 使用：搜索页 `LazyVStack` 末尾（`SearchView.swift:261`）、设置页 `VStack` 末尾（`SettingsView.swift:16`）、编辑器（`DiaryPageView.swift:274`，`base: 140`）。
- 足迹页**不用它**，改用 `.padding(.bottom, max(24, bottomInset + 44))`（`FootprintView.swift:67`）——同屏两种策略，重建时统一成一种即可。
- Compose：`Spacer(Modifier.height(120.dp))`（或用 `WindowInsets.navigationBars` + `Scaffold` 的 `contentWindowInsets` 统一处理，比 iOS 这套更干净）。

### 5.12 触觉

`Haptics.tap()`（`Haptics.swift:3-10`）：单例 `UIImpactFeedbackGenerator(style: .light)`，每次 `prepare()` + `impactOccurred()`。触发点：所有玻璃按钮（`GlassPrimaryButton:203`、`GlassSecondaryButton:253`、`GlassIconButton:296`、`PressableGlassIcon:327`、`GlassChip:373`、`InfoCapsule:397`、`GlassActionChip:448`）、设置行（`SettingsView.swift:126, 158, 221`）、搜索条件胶囊（`SearchView.swift:96`）、搜索地点行（VM `SearchViewModel.swift:173`）、足迹树展开（`FootprintView.swift:283`）、搜索结果项点击（`SearchView.swift:247`）。叶子节点与非交互文本无触觉。
Compose 对应：`LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress/TextHandleMove)`（Android 无 light impact 等价物，用 `LongPress` 或 `View.performHapticFeedback(CLOCK_TICK)`）。

---

## 6. 主题与本地化

### 6.1 颜色 token 全表（亮 / 暗）

`Theme`（`Theme.swift:3-45`）只提供三种来源：**代码里的 seed 色**、**seed 派生的透明度色**、**Asset Catalog 命名色**。下表 RGB 来自 `JustDiary/Resources/Assets.xcassets/*.colorset/Contents.json`（sRGB）：

| token | 访问器 | 亮色 | 暗色 |
|---|---|---|---|
| 品牌 seed / Accent | `Theme.primary()` / `AccentColor` | `#2563EB` (37,99,235) | 同左（**不随亮暗变化**） |
| onPrimary（主按钮文字） | `Theme.onPrimary()` | `white` | `#191C20` |
| primaryContainer（胶囊底、徽标底） | `Theme.primaryContainer()` | `#DBEAFE` | `#1E3A8A` |
| glow（主按钮阴影） | `Theme.glowColor()` | `primary @ 15%` | 同 |
| flowLightColor（流光） | `Theme.flowLightColor()` | `primary @ 40%` | 同 |
| flowMaskColor（流光遮罩） | `Theme.flowMaskColor()` | `primary @ 5%` | 同 |
| 页面底 | `Theme.bg()` | `#FAF9FD` | `#101318` |
| 主文字 | `Theme.onSurface()` | `#191C20` | `#E1E2E8` |
| 次文字 | `Theme.onSurfaceVariant()` | `#44474F` | `#C5C6D0` |
| 描边 | `Theme.outlineVariant()` | `#C5C6D0` | `#44474F` |
| 玻璃暗底（次按钮） | `Theme.glassDim()` | `#FFFFFF @ 45.1%` | `#262B36 @ 50.2%` |
| 玻璃描边（徽标） | `Theme.glassBorder()` | `#FFFFFF @ 54.9%` | `#FFFFFF @ 20%` |
| 流光高光 | `Theme.flowLight()` | `#FFFFFF @ 80%` | `#FFFFFF @ 34.9%` |
| 氛围球 A | `Theme.blobA()` | `#93C5FD @ 50.2%` | `#1E40AF @ 50.2%` |
| 氛围球 B | `Theme.blobB()` | `#BFDBFE @ 60%` | `#1E3A8A @ 54.9%` |
| 氛围球 C | `Theme.blobC()` | `#E0E7FF @ 43.1%` | `#312E81 @ 43.1%` |
| 阴影 | `Theme.shadowColor()` | `#000000 @ 7.8%` | `#000000 @ 14.9%` |
| 引用底（编辑器） | `quoteBg`（`Theme.quoteBgUIColor()`） | `#E4EAF9` | `#2B3344` |
| 卡片底 | 系统 `secondarySystemGroupedBackground` | 系统值 | 系统值 |

`Theme` 还有一组 UIKit 访问器（`primaryUIColor()`、`primaryContainerUIColor()`、`onSurfaceUIColor()`、`onSurfaceVariantUIColor()`、`quoteBgUIColor()`）供 Canvas / 富文本管线使用（`:20-44`）。

### 6.2 亮暗解析机制

1. **模式来源**：`AppConfigService.colorScheme`（`AppConfigService.swift:4-10`）读 `SettingsStore.load().themeMode`：`light → .light`、`dark → .dark`、其它 → `nil`（跟随系统）。
2. **应用方式**：`RootView` 上 `.preferredColorScheme(AppConfigService.colorScheme)`（`RootView.swift:60`）—— 整棵树（含 sheet、fullScreenCover）随之切换。
3. **颜色解析**：命名色由 Asset Catalog 的 `luminosity: dark` 变体解析；`Theme.primary()` 等代码色本身与亮暗无关（`onPrimary` 例外，用 `UIColor { trait in … }` 动态解析，`Theme.swift:8-10`）。
4. **切换代价**：`setTheme` 只做 `save()` + `applyAll()`（`DayDraw.clearCache()` + `bumpUiTick()`），真正重绘靠 `RootView` 的 `.id` 重建（`RootView.swift:69-72`）。
5. Compose 对应：`lightColorScheme(primary = Color(0xFF2563EB), primaryContainer = …, background = …, surface = …, onSurface = …, onSurfaceVariant = …, outlineVariant = …)` + 一套 `darkColorScheme`；`themeMode` → `isSystemInDarkTheme()` 分支。

### 6.3 语言切换机制

1. 设置项 `app_language` ∈ {`system`, `zh`, `en`}（`AppSettings.swift:15, 50, 56`）。
2. `AppLanguage.current`（`L10n.swift:4-19`）：`zh → "zh-Hans"`、`en → "en"`、`system → Locale.preferredLanguages.first`（默认 `zh-Hans`）。
3. `L10n.str(key)`（`:28-30`）：**不走 `String(localized:locale:)`**（注释解释：那个 API 会用 App 的 active language，忽略传入 locale，导致「语言只切一半」）。实现是直接读 `Bundle.main.url(forResource: "Localizable", withExtension: "strings", localization: <loc>)` 对应的 `.lproj` 表并用 `NSDictionary` 解析，**按 locale 缓存**在 `tables` 字典里（`:37-60`）。
4. `normalizedLocale`（`:67-81`）：先把系统语言（如 `zh-Hans-CN`、`en-CN`）归一到 bundle 里真实存在的本地化（`zh-Hans` / `en`）；匹配不到 → `en`。
5. 查找失败顺序：目标语言 → `en` → 返回 key 本身（`:37-43`）。
6. 生效路径：`SettingsViewModel.setLanguage` → `save()` → `SettingsStore.load()`（刷新进程内缓存）→ `bumpUiTick()` → `RootView.uiTick += 1` → `.id("\(AppLanguage.current)#\(tick)")` 重建整棵树 → 所有 `L10n.str` 重新求值；同时 `.environment(\.locale, AppLanguage.locale)`（`RootView.swift:66`）让系统组件（DatePicker、ContentUnavailableView、Charts 等）也换语言。
7. 与语言绑定的**非文案**行为：`AppLanguage.isZh` 决定首页是否显示农历（`HomeView.swift:25`）；日期/时间格式化器按 `AppLanguage.current` 缓存（`L10n.swift:88-139`）；时间格式的小时制跟随**设备**设置，AM/PM 与顺序跟随 App 语言（`:125-139`）。
8. Compose 对应：`AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(...))`（或 per-app language）+ `stringResource`；`Locale.preferredLanguages` ≈ `Resources.getSystem().configuration.locales`；注意 iOS 这里 `zh` 一律落到 `zh-Hans`（无繁体）。

### 6.4 String Catalog：包含哪些字符串

- 文件：`JustDiary/Resources/Localizable.xcstrings`，`version 1.0`，`sourceLanguage = "en"`，共 **198 个 key**，每种 key 提供 `en` + `zh-Hans` 两个 `stringUnit`（构建后落到 `en.lproj/Localizable.strings` 与 `zh-Hans.lproj/Localizable.strings`，`L10n` 读的就是编译产物）。
- **在 Catalog 里**：全部自定义 UI 文案、tab 标题、设置项标题/副标题/选项、4 种时间标签、日期格式模板（`date_day_title`、`date_day_only`、`date_range`、`index_week_head`）、分享预览标题 `EntryAbility_label`、通用按钮 `cancel`/`save`/`delete`/`discard`、无障碍标签（`a11y_back`、`a11y_share`、`a11y_insert_image`）。
- **不在 Catalog 里（系统提供）**：`ContentUnavailableView.search(text:)` 的「无结果」文案、DatePicker/滚轮/文件选择器/分享面板/alert 的按钮与标签、`TabView` 的浮条内部文案。
- **硬编码字面量**（刻意或遗漏）：设置页语言行的 `中文` / `English`（`SettingsView.swift:266-272`）、`SearchFilterItem` 里的中文引号 `“…”`（`SearchViewModel.swift:103, 106`）、`LaunchIntent` 的 `"home"`/`"editor"`、`-ui-test-*` 参数。
- 四屏用到的 key 全表：

**外壳 / 通用**

| key | zh-Hans | en |
|---|---|---|
| `index_title` | 日记 | Diary |
| `footprint_title` | 足迹 | Footprint |
| `search_title` | 搜索 | Search |
| `settings_title` | 设置 | Settings |
| `cancel` | 取消 | Cancel |
| `save` | 保存 | Save |
| `EntryAbility_label` | 一页时光 | 一页时光 |

**搜索页**

| key | zh-Hans | en |
|---|---|---|
| `search_placeholder` | 搜索日记内容… | Search diary content… |
| `search_cancel` | 取消 | Cancel |
| `search_clear_all` | 清除全部条件 | Clear all filters |
| `search_result_count` | 找到 %d 条日记 | %d entries found |
| `search_filter_time` | 时间范围 | Time range |
| `search_filter_loc` | 地点 | Place |
| `search_all_time` | 全部时间 | All time |
| `search_time_week` | 本周 | This week |
| `search_time_month` | 本月 | This month |
| `search_time_year` | 今年 | This year |
| `search_custom` | 自定义 | Custom |
| `search_all_loc` | 全部地点 | All places |
| `search_loc_picker` | 选择地点… | Pick a place… |
| `search_loc_no_loc` | 未记录地点 | No place recorded |
| `search_loc_country_only` | 仅国家 | Country only |
| `search_load_more` | 加载更多 | Load more |
| `search_loading` | 正在搜索… | Searching… |
| `search_preparing` | 正在准备搜索索引… | Preparing search index… |
| `search_start_title` | 搜索你的日记 | Search your diary |
| `search_empty` | 输入关键词，或选择时间与地点筛选 | Type a keyword or filter by time and place |
| `search_time_title` | 自定义时间范围 | Custom time range |
| `search_time_start` | 开始日期 | Start date |
| `search_time_end` | 结束日期 | End date |
| `search_time_clear` | 清除 | Clear |
| `search_time_apply` | 确定 | Apply |
| `search_loc_title` | 选择地点 | Choose a location |
| `search_loc_search` | 搜索地点… | Search places… |
| `search_loc_sort_hint` | 按日记数量排序 | Sorted by entries count |
| `search_loc_empty` | 暂无地点数据 | No location data yet |
| `date_day_title` | `%1$@%2$ld日 %3$@` | `%3$@, %1$@ %2$ld` |
| `date_range` | `%1$@ – %2$@` | `%1$@ – %2$@` |

**足迹页**

| key | zh-Hans | en |
|---|---|---|
| `footprint_summary` | 已标注 %1$ld 处 · 未记录 %2$ld 篇 | Located %1$ld · Unlocated %2$ld |
| `footprint_time_all` | 全部 | All |
| `footprint_time_custom` | 自定义 | Custom |
| `footprint_stat_provinces` | 省市 | Provinces |
| `footprint_stat_cities` | 城市 | Cities |
| `footprint_stat_countries` | 国家 | Countries |
| `footprint_stat_blocks` | 片段 | Blocks |
| `footprint_stat_days` | 天数 | Days |
| `footprint_trend_title` | 年度记录天数 | Diary days per year |
| `footprint_trend_year` | 年份 | Year |
| `footprint_trend_days` | 天数 | Days |
| `footprint_list_title` | 地点清单 | Places |
| `footprint_empty` | 还没有地点记录 | No places recorded yet |

（足迹的时间弹窗复用 `search_time_title/start/end/clear/apply/week/month/year`。）

**设置页**

| key | zh-Hans | en |
|---|---|---|
| `settings_section_general` | 通用 | General |
| `settings_language` | 语言 | Language |
| `settings_language_sub` | 应用内显示语言 | Display language of the app |
| `settings_lang_system` | 跟随系统 | Follow system |
| `settings_lang_zh` | 中文 | 中文 |
| `settings_lang_en` | English | English |
| `settings_theme` | 主题 | Theme |
| `settings_theme_sub` | 浅色、深色或跟随系统 | Light, dark, or system |
| `settings_theme_system` | 跟随系统 | Follow system |
| `settings_theme_light` | 浅色 | Light |
| `settings_theme_dark` | 深色 | Dark |
| `settings_section_rules` | 日记规则 | Diary rules |
| `settings_day_start` | 新一天的开始时间 | Start of a new day |
| `settings_day_start_sub` | 这个时间前算前一天 | Before this time = previous day |
| `settings_day_recalc_title` | 日期重算 | Recompute dates |
| `settings_day_recalc_msg` | 有 %d 篇日记因规则变化保留了原归属日。 | %d entries kept their original date due to the rule change. |
| `settings_week_start` | 一周从哪一天开始 | Start of the week |
| `settings_week_start_sub` | 日历以周几开头 | Which day a week starts on |
| `settings_week_monday` | 周一 | Monday |
| `settings_week_sunday` | 周日 | Sunday |
| `settings_auto_time` | 自动记录时间 | Auto-record time |
| `settings_auto_time_sub` | 只影响是否显示 | Display only |
| `settings_auto_loc` | 自动插入地点 | Auto-insert location |
| `settings_auto_loc_sub` | 需要位置信息权限 | Requires location permission |
| `settings_loc_permission` | 位置权限 | Location permission |
| `settings_loc_permission_sub` | 地点只存在本机 | Stays on this device only |
| `settings_loc_exact` | 精确 | Precise |
| `settings_loc_fuzzy` | 模糊 | Approximate |
| `settings_loc_none` | 未授权 | Not granted |
| `settings_history_edit` | 允许修改 / 删除历史日记 | Edit past entries |
| `settings_history_edit_sub` | 历史日期只能改，不能新增 | Past days: edit or delete only |
| `settings_section_remind` | 提醒 | Reminders |
| `settings_remind_enabled` | 写日记提醒 | Journal reminder |
| `settings_remind_enabled_sub` | 当天未写日记时提醒我 | Remind me when I haven't written today |
| `settings_remind_time` | 提醒时间 | Reminder time |
| `settings_remind_time_sub` | 每天提醒一次 | Once a day |
| `settings_notif_permission` | 通知权限 | Notification permission |
| `settings_notif_permission_sub` | 关闭后无法收到提醒 | Reminders won't arrive when off |
| `settings_notif_on` | 已开启 | On |
| `settings_notif_off` | 未开启 | Off |
| `settings_section_data` | 数据 | Data |
| `settings_export` | 导出全部日记 | Export all entries |
| `settings_export_sub` | 生成 .jdiary 备份包 | Generate a .jdiary backup |
| `settings_export_data_only` | 仅日记数据 | Entries only |
| `settings_export_data_settings` | 日记 + 设置 | Entries + Settings |
| `settings_export_busy` | 正在生成备份… | Generating backup… |
| `settings_export_failed_title` | 导出失败 | Export failed |
| `settings_export_failed_msg` | 生成或保存备份时出错，请重试。 | Something went wrong while generating or saving the backup. Please try again. |
| `settings_import` | 导入备份 | Import backup |
| `settings_import_sub` | 从其他设备恢复日记 | Restore entries from another device |
| `settings_import_busy` | 正在导入备份… | Importing backup… |
| `settings_import_mode_title` | 导入方式 | Import mode |
| `settings_import_skip` | 跳过已有日记 | Skip existing |
| `settings_import_overwrite` | 覆盖已有日记 | Overwrite existing |
| `settings_import_success_title` | 导入完成 | Import complete |
| `settings_import_success_msg` | 新增 %d 天，跳过 %d 天，覆盖 %d 天（共 %d 个编辑块、%d 张图片）。 | Added %d days, skipped %d, overwritten %d (%d blocks, %d images). |
| `settings_settings_restored` | 已同时恢复应用设置。 | App settings were restored as well. |
| `settings_import_failed_title` | 导入失败 | Import failed |
| `settings_import_failed_msg` | 备份文件无效或导入出错，请重试。 | The backup file is invalid or the import failed. Please try again. |
| `settings_section_about` | 关于 | About |
| `settings_version` | 版本 | Version |
| `time_label_midnight` | 凌晨 0:00 | 12:00 AM |
| `time_label_dawn` | 凌晨 %1$ld:00 | %1$ld:00 AM |
| `time_label_morning` | 上午 %1$ld:00 | %1$ld:00 AM |
| `time_label_noon` | 中午 12:00 | 12:00 PM |
| `time_label_afternoon` | 下午 %1$ld:00 | %1$ld:00 PM |
| `time_label_evening` | 晚上 %1$ld:00 | %1$ld:00 PM |

---

## 7. 数值速查表（magic numbers）

> 格式：**值** — 含义 — `file:line`。所有字号都会再乘 Dynamic Type 倍率（§7.9），下表是**默认字号档**的值。

### 7.1 圆角 `Radius`（`DesignSystem.swift:13-33`）

| 值 | 名称 | 用途 | 位置 |
|---|---|---|---|
| 20 | `Radius.card` | 所有内容卡、结果行、列表行、统计卡、趋势卡、清单卡、条件栏、busy 卡 | `DesignSystem.swift:15` |
| 26 | `Radius.panel` | 浮动控制面板 / sheet（**本四屏未直接使用**，编辑器工具栏用） | `:17` |
| 14 | `Radius.field` | 输入类内嵌面。**代码里的唯一实际用例**：地点弹窗的搜索框（`SearchView.swift:422`）。文档注释里还写了「高亮地点行 / 设置 busy 卡」，但代码中它们分别用 `Radius.badge=12`（`SearchView.swift:479`）与 `Radius.card=20`（`SettingsView.swift:91`）—— 注释与实现不一致 | `DesignSystem.swift:19-20` |
| 14 | `Radius.image` | 卡片内嵌图片 | `:22` |
| 12 | `Radius.badge` | 图标徽标、**地点弹窗选中行高亮** | `:24`；用例 `SearchView.swift:479` |
| 4 | `Radius.bar` | 图表柱顶圆角 | `:26`；用例 `FootprintView.swift:137` |
| 1 | `Radius.hairline` | 2pt 细标（首页日历） | `:28` |
| `max(0, outer-inset)` | `Radius.concentric` | 嵌套圆角规则 | `:31-33` |
| `.continuous` | squircle | 所有 `RoundedRectangle` 的 style | 例 `Components.swift:144` |

### 7.2 间距 `Spacing`（`DesignSystem.swift:40-46`）+ 页面边距

| 值 | 含义 | 位置 |
|---|---|---|
| 12 | `Spacing.card`（卡片之间）/ 设置页卡片间距 / 页脚图标与文字 | `:41`；`SettingsView.swift:9` |
| 12 | `Spacing.rowIcon`（徽标与文字） | `:43`；`SettingsView.swift:170, 202` |
| 44 | `Spacing.hitTarget`（最小命中区） | `:45` |
| 16 | `AdaptiveLayout.pagePadding`（四屏统一左右边距，`adaptivePagePadding()`） | `AdaptiveLayout.swift:144-146, 241-253` |
| 6 | 设置页行内「标题/副标题」间距 | `SettingsView.swift:172, 204` |
| 2 | 同上（VStack spacing） | `SettingsView.swift:172, 204` |
| 8 | `Spacer(minLength:)` 行内兜底 | `SettingsView.swift:183, 215` |

### 7.3 字号 `TypeSize`（`DesignSystem.swift:55-86`）

| 值 | 名称 | 本四屏用例 |
|---|---|---|
| 32 | `display` | （首页月/年标题） |
| 24 | `pageTitle` | 四屏页头标题 |
| 20 | `cardTitle` | （阅读页日期） |
| 18 | `headerTitle` | （首页年月胶囊） |
| 18 | `statValue` | 足迹统计数值 |
| 17 | `sheetTitle` | 所有弹窗标题 |
| 15 | `rowTitle` | 设置行标题、设置行尾值、结果摘要（`HighlightedText` 基础）、空态文案、地点行、树行名、搜索框文本、`Spacer` 文本 |
| 15 | `rowValue` | 设置行尾值 / chevron 字号 |
| 14 | `chip` | chip、`InfoCapsule`、主按钮 compact、搜索取消按钮、足迹摘要 |
| 13 | `rowSub` | 设置行副标题 |
| 13 | `meta` | 搜索结果摘要、busy 文案、toast |
| 13 | `sectionTitle` | 卡片分区标题、趋势图标题、清单标题 |
| 12 | `badge` | 计数徽标、日期胶囊文字、条件胶囊文字、树节点计数 |
| 12 | `caption` | 组标签（时间范围/地点）、行尾时间、地点弹窗说明、图表轴、统计标签 |
| 硬编码 11 | — | 结果项日历图标（`SearchView.swift:321`） |
| 硬编码 12 | — | 结果项 chevron（`:341`）、树图标（`FootprintView.swift:290`） |
| 硬编码 13 | — | 条件胶囊的 `xmark` → 实为 9（`SearchView.swift:113`）；地点行 checkmark 13（`:472`） |
| 硬编码 9 | — | 条件胶囊 `xmark`（`SearchView.swift:113`） |
| 硬编码 10 | — | 徽标图标（`Components.swift:424`） |
| 硬编码 14/16 | — | `GlassIconButton` 图标（`:300`） |
| 硬编码 17 | — | `PressableGlassIcon` 图标（`:331`） |
| 硬编码 16 | — | `GlassIconBadge` 图标（`:656`） |
| 硬编码 26 | — | 空态图标（`:482`） |

### 7.4 组件尺寸 / 内边距

| 值 | 含义 | 位置 |
|---|---|---|
| 52 | `PageHeader` 最小高度 | `Components.swift:187` |
| 0.7 | 页头标题最小缩放 | `:179` |
| 20 / 14 | 主按钮左右内边距（常规 / compact） | `:217` |
| 12 / 7 | 主按钮上下内边距（常规 / compact） | `:218` |
| 13 / 12 | 主按钮图标字号（常规 / compact） | `:209` |
| 15 / 14 | 主按钮文字字号（`rowTitle` / `chip`） | `:212` |
| 10 / 8 | 主按钮阴影半径（常规 / compact） | `:235` |
| 3 / 2 | 主按钮阴影 y 偏移（常规 / compact） | `:235` |
| 0.85 | 主/次按钮文字最小缩放 | `:214, 264` |
| 0.6 | 主按钮禁用填充透明度 | `:239` |
| 0.55 | 主按钮禁用文字透明度 | `:216` |
| 20 / 12 | 次按钮左右 / 上下内边距（无 compact） | `:267-268` |
| 44 × 44 | `GlassIconButton` / `PressableGlassIcon` 命中区 | `:302, 334` |
| `size - 8`（≥28） | `PressableGlassIcon` 标签边长（默认 36） | `:323` |
| +4pt/边 | `.glass` button style 追加的内边距（解释视觉尺寸） | 注释 `:320-322` |
| 12 / 7 | `GlassChip` / `InfoCapsule` 内边距 | `:381-382, 405-406` |
| 10 / 6 | `GlassActionChip` 内边距 | `:462-463` |
| 10 / 5 | `GlassCountBadge` 内边距 | `:432-433` |
| 5 | 徽标内图标间距 | `:421` |
| 4 | `GlassActionChip` 图标间距 | `:451` |
| 38 | `GlassIconBadge` 边长（圆角 12，图标 16） | `:634-635, 656` |
| 46 | `GlassSearchField` 最小高度 / 搜索框左边距 14 | `:589-590` |
| 56 | 设置行最小高度 | `SettingsView.swift:197, 226` |
| 12 | 设置行左右内边距 | `:196, 225` |
| 62 | `RowDivider.textInset` = 12+38+12 | `Components.swift:621` |
| 0.5 / 0.45 | 卡片描边宽度 / `outlineVariant` 透明度 | `:151-152` |
| 1 / 0.55 + 1.5 / 0.9 | 高对比度下的描边（`onSurface` / `outlineVariant`） | `:148-152` |
| 0.14 / 0.22 | 卡片阴影透明度（常规 / interactive） | `:155-156` |
| 8 / 12 | 卡片阴影半径（常规 / interactive） | `:156` |
| 2 / 4 | 卡片阴影 y（常规 / interactive） | `:157` |
| 56 | 空态顶部内边距；图标 26pt + 50% 透明；文字间距 12 | `:481-494` |
| 120 | `TabBarClearance` 基准高度（× rowTitle 倍率） | `:606-613` |
| 140 | 编辑器 `TabBarClearance(base: 140)` | `DiaryPageView.swift:274` |
| 16 / 10 / 24 | toast 左右 / 上下内边距、底部外边距；阴影 radius 12 / y 4 | `HomeView.swift:49-56` |

### 7.5 外壳 / 版面阈值

| 值 | 含义 | 位置 |
|---|---|---|
| 4 | tab 数量与顺序：日记 / 足迹 / 搜索 / 设置 | `RootView.swift:31-54` |
| `figure.walk`, `magnifyingglass`, `house.fill`, `gearshape.fill` | tab SF Symbols | `:32,39,43,50` |
| `.onScrollDown` | 浮条滚动最小化策略 | `:55` |
| 83 / 64 | 浮条高度（竖屏 / 横屏） | `AdaptiveLayout.swift:113-114` |
| `isPortrait = height >= width` | 版面判据（**不看机型/朝向 API**） | `:40` |
| `contentWidth = width - leading - trailing` | 可用于分栏/百分比计算的宽度 | `:35-37` |
| `bottomInset = max(0, safeArea.bottom)` | 底部安全区 | `:27` |
| 390 × 844 | `Screen` 兜底尺寸（无场景时） | `Components.swift:31` |
| 548.5 | 首页分栏最小内容宽 = 2×16 + 16.5 + 260 + 240 | `AdaptiveLayout.swift:62-63` |
| 0.15s | 深链消费延迟 | `RootView.swift:153` |
| 170 | `topControlsHeight` 兜底估值 | `SearchView.swift:278` |

### 7.6 搜索相关

| 值 | 含义 | 位置 |
|---|---|---|
| 250ms | 关键词输入防抖 | `SearchViewModel.swift:156` |
| 200 | 单页结果上限 `searchLimit` | `DiaryRepository.swift:13` |
| 3 | 搜索索引版本 `searchIndexVersion` | `:14` |
| 24 | FTS `snippet(..., 24)` 的 token 窗口 | `:910` |
| 18 | 兜底片段前后字符数（±18） | `SearchUtil.swift:98-99` |
| 20 | 地点选项硬上限 `.prefix(20)` | `DiaryRepository.swift:407` |
| 0.2s | 搜索框「取消」出现动画 | `SearchView.swift:154` |
| 10 / 12 / 8 / 12 / 60 | 搜索页顶部/内部间距：`content.top 12`、`searchBar.top 10`、`filterPanel.top 12`、`filterSummary.top 8`、空态 `.top 60`（**外层再加 60**） | `:58, 34, 37, 53, 259` |
| 10 | `filterPanel` 内边距；结果 `LazyVStack` 项间距；结果项 `HStack` 间距 | `:173, 243, 317` |
| 6 | chip 行间距 / 条件栏内胶囊间距 / 结果项内 VStack 间距 / 日期胶囊内图文间距 | `:185, 204, 76, 318, 320` |
| 4 | 条件胶囊「文字 + xmark」间距 | `:108` |
| 6 | chip 行 `padding(.vertical, 6)`（防裁切） | `:192, 210` |
| 44 | 条件栏最小高度；`GlassIconButton` 命中区 | `:90` |
| 12 / 4 | 条件栏左 / 右内边距 | `:88-89` |
| 10 / 6 | 条件胶囊内边距 | `:116-117` |
| 8 / 4 | 日期胶囊内边距 | `:326-327` |
| 14 | 结果项内边距 | `:344` |
| 8 | 「加载更多」上下外边距 | `:255` |
| 46 / 12 | 地点行最小高 / 左右内边距 | `:476-477` |
| 40 | 地点弹窗「暂无地点数据」顶部内边距 | `:441` |
| 20 / 8 | 弹窗内边距 / 底部附加内边距 | `:392-393, 447-448` |
| 16 / 8 / 8 / 12 | 弹窗内间距：主 VStack 16、快捷胶囊 8、标签 8、底部按钮 12 | `:351, 355, 360, 382` |
| 10 | 地点弹窗主 VStack 间距 | `:403` |
| 8 / 24 / 24 / 170 | 顶部隐藏判定：`y>8`（保持隐藏）、`y>24` 且 `contentSize > container + extra + 24`、`extra` 兜底 170 | `:272-283` |

### 7.7 足迹相关

| 值 | 含义 | 位置 |
|---|---|---|
| 16 | 页头左右内边距（写死，非 `adaptivePagePadding`） | `FootprintView.swift:17` |
| 8 | 年份 chip 间距 | `:20` |
| 12 / 10 | 卡片间间距 / 滚动内容顶部内边距 | `:38,49,51,65` |
| `max(24, bottomInset + 44)` | 底部让位（竖屏 78 / 横屏 64） | `:67` |
| 14 | 统计卡上下内边距；趋势卡 / 清单卡内边距 | `:101, 152, 186` |
| 3 | 统计单元格内间距 | `:106` |
| 0.6 | 统计值 / 标签最小缩放 | `:111, 118` |
| 132 | 趋势图默认高度 | `:145` |
| 10 | 趋势卡内 VStack 间距 | `:126` |
| 4 | 柱顶圆角（`Radius.bar`） | `:137` |
| > 1 | 趋势卡显示条件：`vm.yearly.count > 1` | `:40, 52` |
| 16 | 树每层缩进 | `:309` |
| 9 | 树行上下内边距 | `:310` |
| 16 | 树图标容器宽度 | `:292` |
| 8 | 清单标题底部内边距；树行 `Spacer(minLength:)` | `:171, 297` |
| 90° | 展开箭头旋转角 | `:306` |
| 140 / 190 / 262 | 横屏区块高 `max(140, min(190, H - 262))` | `:48` |
| 58 | 横屏图表高 `max(72, blockH - 58)` | `:53` |
| 0.4 / 240 / 320 | 横屏清单宽 `min(320, max(240, contentWidth × 0.4))` | `:161` |
| 12 | 横屏 HStack 间距 | `:51` |

### 7.8 设置相关

| 值 | 含义 | 位置 |
|---|---|---|
| 12 | 卡片间距 / 页面顶部内边距 | `SettingsView.swift:9, 20` |
| 16 / 12 / 0 | 分区标题左右 / 上 / 下内边距 | `:119-121` |
| 56 | 行最小高度 | `:197, 226` |
| 0.7 | 行尾值最小缩放 | `:190` |
| 2 | 行内标题/副标题间距 | `:172, 204` |
| 20 | picker sheet 内边距 | `:406, 429` |
| 16 | picker sheet 内 VStack 间距 | `:390, 416` |
| 170 | 导出分享 sheet 高度 | `:34` |
| 24 | 导出分享面板内边距 | `:33` |
| 0.25 | busy 遮罩黑色透明度 | `:79` |
| 32 / 26 | busy 卡左右 / 上下内边距；内部间距 14 | `:81, 89-90` |
| 800ms | 位置权限返回后的刷新延时 | `SettingsViewModel.swift:109` |
| 4 / true / true / false / "system" / "system" / "monday" / true / 21 / 0 | 设置默认值：`dayStartHour` / `autoTime` / `autoLoc` / `allowHistoryEdit` / `themeMode` / `appLanguage` / `weekStart` / `remindEnabled` / `remindHour` / `remindMinute` | `AppSettings.swift:4-19` |
| `group.com.cov.justdiary` | UserDefaults suite | `AppSettings.swift:36` |
| `day_start_hour` `auto_time` `auto_loc` `allow_history_edit` `theme_mode` `app_language` `week_start` `remind_enabled` `remind_hour` `remind_minute` | 持久化 key | `AppSettings.swift:21-32, 65-74` |
| `search_index_version` / `content_format_version` | 另两个非偏好型 key | `:77-88` |

### 7.9 Dynamic Type（字号倍率）

- `diaryFont(size)` = `.system(size: size × multiplier)`，倍率由 `UIFontMetrics(forTextStyle:)` 按最接近的 Apple 文本样式计算并**封顶**（`DynamicType.swift:29-37, 52-54`；`DesignSystem.swift:190-201`）。默认档（`.large`）倍率恒为 1（`:191`）。
- 字号 → 文本样式映射（`DesignSystem.swift:143-156`）：≥34 `largeTitle`；28–33 `title1`；22–27 `title2`；20–21 `title3`；17–19 `body`；16 `callout`；15 `subheadline`；13–14 `footnote`；12 `caption1`；其余 `caption2`。
- 倍率上限（`:124-136`）：`largeTitle 1.35`、`title1 1.40`、`title2/title3 1.45`、`body 1.50`、`callout 1.55`、`subheadline 1.60`、`footnote 1.65`、`caption1 1.70`、默认 1.75。
- AX5 实测结果（注释 `:119-120`）：32→44.8、28→39.2、24→34.8、22→31.9、20→29、18→27、17→25.5、16→24.8、15→24、13→21.5、12→20.4、11→19.3。
- 半速倍率（日历专用）：`1 + (multiplier(15) - 1) × 0.5`（`:217-219`）。
- 根视图把系统 `dynamicTypeSize` 通过 `\.diaryDynamicTypeSize` 下发（`RootView.swift:65`）；`fullScreenCover` 里必须**重新注入**（`:97`）。
- Compose 对应：`MaterialTheme.typography` 用 sp 会自动跟随系统字号；若要不完全等同的封顶行为，可读 `LocalDensity.current.fontScale` 做 clamp。

---

## 附录 A. 与旧文档的差异 · 重建检查清单 · 缺陷清单

### A.1 相对 `docs/ios-reference/footprint-search-settings-root.md` 需要更新的点（以本次代码为准）

| # | 项目 | 旧文档 | 本次核对（代码事实） |
|---|---|---|---|
| 1 | 行数漂移 | Components.swift 702 / SearchView 515 / AdaptiveLayout 184 / SettingsViewModel 178 / xcstrings 196 键 | **718 / 519 / 254 / 182 / 198**（`wc -l` 实测） |
| 2 | `Radius.field` 的用途 | 按 `DesignSystem.swift` 注释写「search containers、highlighted location row、settings busy card」 | 代码里 `field` 只用于地点弹窗搜索框（`SearchView.swift:422`）；高亮地点行用 `Radius.badge=12`（`:479`），busy 卡用 `Radius.card=20`（`SettingsView.swift:91`） |
| 3 | 统一页边距 | 未记录 `leadingPagePadding` / `trailingPagePadding` / `pagePadding` | `AdaptiveLayout.swift:137-146`：`pagePadding = 16`，`adaptivePagePadding()` 只加 16，**绝不能再叠加安全区**（否则横屏所有内容右移 62pt） |
| 4 | 浮条让位常量 | 0 次提及 `tabBarClearance` | `AdaptiveLayout.swift:106-124`：竖屏 83 / 横屏 64，来自 UI 测试实测 frame；`bottomInset + 44` 的旧写法在 SE 横屏会少让 20pt |
| 5 | 地点选项上限 | 0 次提及 `prefix(20)` | `DiaryRepository.swift:407`：最多 20 项，且弹窗内的搜索是**客户端过滤这 20 项** |
| 6 | 多关键词 | 0 次提及 | `SearchViewModel.keywords: [String]`（`:48`）+ `combinedKeyword` 空格连接 → FTS 侧是 **AND** 语义；条件栏每个关键词一个可删除胶囊（`:105-107`） |
| 7 | 搜索加载态 | 记录了「正在准备搜索索引」文案 | 该分支**实际不可达**（见 §2.10 死代码分析）：真实行为是搜索中列表空白 |
| 8 | 深链 | — | 前台收到 deep link 不会立即消费（§1.7 的竞态） |

### A.2 Android 重建检查清单（逐条对应本文的交互）

**外壳**
1. 4 个 tab、顺序与图标语义务必一致（日记/足迹/搜索/设置）；默认选中「日记」。
2. 底部导航悬浮（圆角 + 两侧留白 + 贴底），向下滚动最小化、向上恢复。
3. 横屏不换导航形态（仍底部居中），内容左右各 16dp 边距，不叠加安全区。
4. 内容末尾统一让位（120dp 或 Scaffold insets），避免最后一行被浮条遮住。
5. 编辑器用全屏路由（覆盖底部导航），关闭后刷新首页/足迹。
6. 深链 `justdiary://editor?dayKey=YYYY-MM-DD`：`dayKey` 缺省 = 今天；**用 intent 直接导航**，不要复制「静态意图 + 激活时消费」。

**搜索**
7. 常驻玻璃搜索框（46dp 高）+ 聚焦出现「取消」+ 非空出现清除按钮；提交把词变成可删除胶囊（多词 AND）。
8. 250ms 防抖实时搜索；200 条一页 + 「加载更多」；结果按 day_key 倒序。
9. 时间 5 个 chip（全部时间/本周/本月/今年/自定义）+ 半屏 sheet（快捷区间 + 起止日期 + 清除/确定）；自定义区间可自动交换起止。
10. 地点 chip（全部地点/当前选择）+ 大高度 sheet（搜索框、按篇数排序、Top20、未记录地点计数、选中高亮 + checkmark、点击即关闭并生效）。
11. 条件栏：每个条件可单独 ×，右侧「清除全部条件」；条件栏只在有条件时出现。
12. 顶部控件滚动自动隐藏/聚焦自动展开（阈值见 §2.3）。
13. 结果项 = 日期胶囊（primaryContainer + primary 文字）+ 两行高亮摘要 + 右侧更新时间 + chevron；高亮 = primary 文字 + primaryContainer 底色 + medium 字重。
14. 三态空态：无条件（引导）/ 有条件无结果（系统「无结果」+ 清除全部按钮）/ 加载中（**必须显式实现**，见缺陷 1）。
15. 页头右侧计数徽标（`total > 0` 才显示）。

**足迹**
16. 年份 chip 条（全部 + 各年份倒序）+ 自定义时间 chip（active 高亮）+ 半屏时间 sheet。
17. 5 格统计卡（省市/城市/国家/片段/天数）；页头右侧「已标注 N 处 · 未记录 M 篇」。
18. 趋势柱状图：x = 年份升序、y = 该年记录天数、品牌蓝、柱顶 4dp 圆角、仅 Y 轴在左侧带网格线、默认高 132dp；**仅当 ≥2 年时显示**；无点击交互。
19. 三级树：country → region1 → region2（缺级自动降级）；节点 id = 路径；计数含子孙；同级按计数降序再按名称；图标按层级 globe/map/pin；缩进 16dp/级；非叶子才显示可旋转 chevron；点击展开/收起（0.22s）。
20. 筛选变化后剪枝已展开集合；清单为空时显示空态。
21. 横屏：统计在上，趋势图（flex）与清单（≈300dp 固定宽、内部滚动）并排，区块高受容器高约束。

**设置**
22. 6 张卡：通用 / 日记规则 / 提醒 / 数据 / 关于（+ 页头）；卡片间距 12dp、行高 ≥56dp、图标徽标 38dp、分隔线左缩进 62dp。
23. 语言（跟随系统/中文/English）、主题（跟随系统/浅色/深色）→ **Menu 形式的值选择**，不是对话框。
24. 开始时间（0–23 滚轮，文案随时区/12-24 小时制，保存后重算归属日并在有冲突时提示）、周起始（周一/周日）、自动记录时间（纯显示开关）、自动插入地点（开时请求权限 + 回填历史）、位置权限（跳系统设置）、历史编辑开关。
25. 提醒：总开关（开时请求授权并排程，关时取消全部）、提醒时间（12/24 小时制滚轮）、通知权限行（仅未授权时出现）。
26. 数据：导出（仅日记 / 日记+设置 → 生成 `.jdiary` → 分享面板）、导入（文件选择 → **确认对话框**：跳过 / 覆盖 / 取消 → 阻塞遮罩 → 结果提示含统计数字与「已恢复设置」）。
27. 结果提示用「确定」按钮（不要学 iOS 这边的「取消」）；覆盖已有日记要有明确的破坏性语义。
28. 竖屏/横屏同构，单列，滚动到底部有让位。

### A.3 缺陷 / 脆弱点总清单（建议在 Android 侧修正）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | 搜索加载态不可见（空态分支 A 是死代码，外层 `!vm.searching` 与内层 `vm.searching` 互斥） | `SearchView.swift:257, 287-296`；`SearchViewModel.swift:266, 274, 305-306` | 显式 Loading 态（骨架/进度条）覆盖结果区 |
| 2 | 清空全部条件后，在途搜索仍会写回结果（无条件分支不递增 `currentSearchId`） | `SearchViewModel.swift:265-272, 275-280, 292` | 用一个单调递增的 requestId + 条件版本号，无条件时也作废在途请求 |
| 3 | 前台收到深链不生效，会延迟到下次激活 | `RootView.swift:132, 136, 144, 149-157`；`JustDiaryApp.swift:12-14` | intent 直达导航 |
| 4 | Info 类 alert 的确认按钮文案是「取消」 | `Components.swift:523-534`；`SettingsView.swift:96` | 单按钮用「确定」 |
| 5 | 通知权限行的显示条件用本地化文案比较 | `SettingsView.swift:336` | 用布尔/枚举状态 |
| 6 | 「版本」行有 chevron 但无行为 | `SettingsView.swift:374-375` | 去掉箭头或改为可复制/可点开关于页 |
| 7 | 语言/主题切换靠 `.id` 重建整棵树 → 所有 VM 状态与滚动位置丢失 | `RootView.swift:69-72` | 只更新 Locale/ColorScheme |
| 8 | 地点搜索只在 Top-20 内 | `DiaryRepository.swift:406-407`；`SearchViewModel.swift:250-261, 147-151` | 服务端（DB）按关键词查询，或取消硬上限 |
| 9 | 足迹「已标注（筛选后）」与「未记录（全量）」口径不一致 | `FootprintViewModel.swift:82-84` | 两个数字用同一口径，或明确标注 |
| 10 | 足迹 年份 chip + 自定义区间可叠加但年份 chip 不高亮 | `FootprintView.swift:84`；`FootprintViewModel.swift:71-77` | 明确互斥或同时显示两个条件 |
| 11 | 双击触觉：`GlassActionChip` 内部 + 调用点各一次 | `Components.swift:448`；`FootprintView.swift:27` | 触觉只在组件内部触发一次 |
| 12 | `GlassIconButton(size:)` 参数只改字号不改圆尺寸 | `Components.swift:290, 300-302` | 拆分「图标字号」与「按钮尺寸」两个参数 |
| 13 | 导入「覆盖已有日记」无 destructive 语义 | `SettingsView.swift:52` | 用 error/destructive 配色 |
| 14 | 空态顶部实际留白 120pt（内外各 60） | `SearchView.swift:259, 296/305/312` | 保留一层 |
| 15 | `TabBarClearance`（搜索/设置）与 `bottomInset+44`（足迹）两套让位策略 | `SearchView.swift:261`；`SettingsView.swift:16`；`FootprintView.swift:67` | 统一用 Scaffold insets / 单一 Spacer |
| 16 | 首次进入足迹先闪空态（无 loading） | `FootprintView.swift:71, 173-184` | 加 Loading 态 |
| 17 | `Radius.field` 文档注释与实现不一致 | `DesignSystem.swift:19-20` vs `SearchView.swift:479` | 以代码为准 |
| 18 | 分享预览标题 `EntryAbility_label` 在中英文下都是「一页时光」 | `Localizable.xcstrings`（zh=en=一页时光）；`SettingsView.swift:31` | Android 用应用名资源，随语言变化 |



