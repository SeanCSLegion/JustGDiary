# JustGDiary Android 界面实现现状清单（inventory）

> **本文的用途**：给设计同学一份**与代码逐条对得上的现状底稿**，用来写新的权威界面设计文档。
> 本文只描述"代码里现在是什么样"，不做取舍、不提方案；每一条都带 `file:line`。
>
> **口径**
> - 代码基线：`/Users/cov/JustGDiary`，package `com.cov.justdiary`，分支 `main` 工作区（含 R1/R2/R3 之后的全部改动）。
> - `ui/**` 共 **32 个 Kotlin 文件、约 10,899 行**；本文覆盖 `ui/**`、`app/**`、`AndroidManifest.xml`、`res/values*/strings.xml`。
> - 所有"missing / partial / done"的判定都是**读代码**得到的；`docs/migration-progress.md` 里声称做完但代码里没接线的，本文一律按代码判定，并在 §7.2 单列。
> - 单测数字实测：`grep -c "@Test"` 全仓 **357** 条（与 R3 记录的 357 一致；README/design-spec/ui-design-plan 里的 352/355/356 都已过期，见 §6.5）。
> - 现有界面规范：[`docs/ui-design-plan.md`](ui-design-plan.md)（v2，权威）、[`docs/design-spec.md`](design-spec.md)（v1，部分作废，作废清单见 v2 附录 B）。

---

## 1. 文件与职责清单

### 1.1 入口与宿主

| 文件 | 行数 | 负责的界面 | 关键 composable / class |
| --- | --- | --- | --- |
| `app/MainActivity.kt` | 92 | 唯一 Activity；edge-to-edge；主题注入；启动序列 | `MainActivity:37`、`setContent` + `JustDiaryTheme(themeMode, dynamicColor)` `MainActivity.kt:62-75`、`onStart` 重排提醒 `:85` |
| `app/AppContainer.kt` | 60 | 手工 DI（无 Hilt）：repository / settings / mediaStore / backup / location / reminder / share renderer | `AppContainer:28` |
| `app/AppSettingsApplier.kt` | 172 | 设置变更落到平台（深色、语言、day_key 重算、地区回填、提醒重排）；返回 `SettingsApplyOutcome` | `bootstrap:66`、`onForeground:83`、`commit:94`、`appVersion:169` |
| `app/LaunchIntent.kt` | 49 | 深链 `justdiary://editor?dayKey=…` → `StateFlow`，由根界面消费 | `consume:31`、`SCHEME/HOST:44-45` |
| `app/DebugFlags.kt` | 57 | 调试开关 `-slow-morph`（3s 慢放）/`-morph-log`（帧率日志） | `resolve:38`、`consume:48`、`SLOW_MORPH_DURATION_MS:29` |
| `AndroidManifest.xml` | — | 单 Activity、深链 intent-filter、FileProvider（`${applicationId}.fileprovider`）、`localeConfig`、`windowSoftInputMode="adjustResize"` | 权限：COARSE/FINE_LOCATION、POST_NOTIFICATIONS、RECEIVE_BOOT_COMPLETED |

### 1.2 根导航

| 文件 | 行数 | 负责的界面 | 关键 composable / class |
| --- | --- | --- | --- |
| `ui/root/RootScreen.kt` | 263 | 根界面：4 标签导航（底栏/侧栏）、全屏日记页路由、深链、跨页数据版本 | `RootScreen:71`、`TabContent:179`（首页 `:194`、足迹 `:217`、搜索 `:235`、设置 `:261`） |
| `ui/root/AppTab.kt` | 26 | 4 个目的地与图标映射（**唯一**一处导航图标映射） | `AppTab:18`，图标 `Edit/Place/Search/Settings:22-25` |

### 1.3 首页（日历三态）

| 文件 | 行数 | 负责的界面 | 关键 composable / class |
| --- | --- | --- | --- |
| `ui/home/HomeScreen.kt` | 1291 | **首页全部**：三态状态机、morph、顶栏、当日面板、分栏 | `HomeViewMode:102`、`HomeScreen:127`、`YearLayer:603`、`CalendarSectionArgs:665`、`CalendarSection:711`、`WeekStrip:846`、`HomeTopBar:971`、`DayPanel:1065`、`DayEmptyState:1164`、`DayBlockCard:1199`、`DayPanelPart:1244` |
| `ui/home/CalendarLayout.kt` | 680 | 日历与 morph 的**全部纯数学**（不依赖 Compose，可 JVM 断言） | 常量 `:40-160`、`cellHeightFor:254`、`fitCellHeight:270`、`monthPaneMetrics:303`、`weekMetrics:322`、`yearCardRect:351`、`miniGridRect:377`、`resolveDensity:399`、`yearMonthMorph:495`、`monthWeekMorph:578`、`DragPagerMath:611` |
| `ui/home/CalendarDayGrid.kt` | 367 | 月格（**真实 Compose 组件**，逐格无障碍）+ 星期栏 | `CalendarDayGrid:72`、`CalendarDayCell:182`、`DiaryMark:309`、`CalendarWeekdayHeader:325`、`calendarGridHeight:366` |
| `ui/home/CalendarCanvas.kt` | 242 | 年历层：Canvas 画 12 个迷你月 + 12 个透明可聚焦卡片 | `YearCanvas:50`（绘制 `:93-209`、卡片层 `:211-237`） |
| `ui/home/CalendarGrid.kt` | 121 | 网格/键表生成（纯逻辑） | `weeks:48`、`displayedWeeks:67`、`MONTH_KEYS:36`、`weekKeys:87` |
| `ui/home/HomeViewModel.kt` | 152 | 首页状态（标记集合、选中日内容、周起始、auto_time） | `State:29`、`refresh:76`、`select:88`、`setVisibleYear:81`、`reloadDay:132` |

### 1.4 日记页（阅读 + 编辑 + 分享）

| 文件 | 行数 | 负责的界面 | 关键 composable / class |
| --- | --- | --- | --- |
| `ui/diary/DiaryPageScreen.kt` | 364 | 日记页宿主：`isRead` 二选一渲染；返回键；分享 Intent；9 类结果弹窗 | `DiaryPageScreen:45`、`editorLocationState:209`、`DiaryAlertDialog:230`、文案常量 `:315-364` |
| `ui/diary/DiaryPageViewModel.kt` | 629 | 读/写共用状态机：模式切换、保存/跨日、位置规则、页内搜索、分享 | `State:54`、`DiaryAlert:105`、`open:146`、`enterWrite:205`、`enterEditBlock:226`、`enterSelect:250`、`save:331`、`toggleTodo:443`、`setSearchVisible:462`、`beginLocate:525`、`shareDiary:562` |
| `ui/diary/DiaryReadScreen.kt` | 516 | 阅读页：顶栏动作、条目内搜索栏、hero 卡片、片段列表、段落渲染 | `DiaryReadScreen:88`、`ReadBlock:303`、`ReadPart:312`、`ParagraphText:391`、`highlightAnnotated:443`、`TodoBox:477` |
| `ui/diary/EditorScreen.kt` | 429 | 编辑页：顶栏、时间胶囊、位置行、正文宿主、格式栏、两个本地确认框 | `EditorScreen:66`、`ImportedImage:290`、`EditorSavePayload:293`、`LocationRow:305`、`buildPayload:382` |
| `ui/diary/EditorTextArea.kt` | 227 | 编辑正文宿主：`EditText` + `Spannable`；占位文案；模型/平台文本同步 | `EditorTextArea:48`、`EditorTextState:141`（`lines/selection/composing/revision`） |
| `ui/diary/FontToolbar.kt` | 226 | 格式栏：段落样式菜单 → 居中 → 列表 → 引用 → 待办 → B/I/S/U → 插入图片 | `FontToolbar:57`、`toolbarChipColors:200`、`blockStyleLabel:208` |
| `ui/diary/EditorModel.kt` | 716 | 编辑器**纯内核**：行模型、content_json 双向、段落/行内操作、IME 组合闸门 | `EditorLine:33`、`EditorBlockStyle:100`、`linesToParts:145`、`partsToLines:185`、`setBlockStyle:277`、`toggleInline:430`、`applyToLines:606`、`FormatGate:692` |
| `ui/diary/EditorSelection.kt` | 132 | 扁平偏移 ↔ 行内偏移换算（含标记前缀长度） | `EditorSelection:9`、`EditorSelectionMap.locate:55`、`flatOffset:78`、`effectiveInlineRange:115` |
| ~~`ui/diary/EditorHistory.kt`~~ | ~~78~~ | **已删除（2026-11-13，P1-6）**：撤销/重做确认不做，回滚只走「放弃修改」 | — |
| `ui/diary/SpannableBridge.kt` | 316 | `List<EditorLine>` ↔ `Spannable`（图片 span 承载 src/宽高做往返） | `toSpannable:58`、`fromSpannable:158`、`toSpannableFromParts:297`、`compositionRange:311` |
| `ui/diary/ReadLayout.kt` | 247 | 阅读渲染模型 + 内联图片尺寸 + 篇内搜索 | `ReadLayout:21`、`PartRender:76`、`renderModel:116`、`DiarySearch.findMatches:191` |
| `ui/diary/ImageDisplaySize.kt` | 53 | 内联图片显示尺寸（纯函数，编辑区用） | `fit:37` |

### 1.5 分享（无独立页面）

| 文件 | 行数 | 负责的界面 | 关键 composable / class |
| --- | --- | --- | --- |
| `data/ShareLayout.kt` | 304 | 分享长图**版式计算**（逐字对齐 iOS `ImageShareService`） | `ShareLayout:29`、`ShareBlock:104`、`LayoutRect:107`、常量 `:42+` |
| `service/ImageShareRenderer.kt` | 423 | 分享长图**自绘**（渐变底 + 两个光斑 + 时间轴 + 段落） | `render:49`、`renderToFile:119`、`drawBackground:144`、`drawOrb:158`、`timeLabel:356`、`Palette:370` |
| `service/ShareIntents.kt` | 52 | `ACTION_SEND` + FileProvider 分享 Intent | `shareImage:20`、`shareBackup:30`、`uriFor:46` |

### 1.6 足迹 / 搜索 / 设置

| 文件 | 行数 | 负责的界面 | 关键 composable / class |
| --- | --- | --- | --- |
| `ui/footprint/FootprintScreen.kt` | 484 | 足迹页：页头统计、年份芯片、5 格统计卡、年度柱状图、地点树 | `FootprintScreen:81`、`StatsCard:186`、`StatCell:202`、`TrendCard:227`、`YearBarChart:262`、`PlaceTreeCard:342`、`PlaceRow:366` |
| `ui/footprint/FootprintViewModel.kt` | 180 | 足迹状态：一次读库 + 内存筛选 + 展开集合 | `State:34`、`setYearFilter:79`、`setTimeKind:84`、`toggleExpandNode:107`、`placeOrder:166` |
| `data/FootprintViewState.kt` | 65 | 足迹视图状态装配（纯函数） | `FootprintViewState:14`、`FootprintViewStateBuilder.build:35` |
| `ui/search/SearchScreen.kt` | 699 | 搜索页：页头计数、搜索框、关键词芯片、时间/地点筛选、条件摘要、结果列表、地点选择器 | `SearchScreen:92`、`LocationPickerDialog:328`、`TimeFilterRow:463`、`LocationFilterRow:486`、`SearchResultRow:537`、`highlightSnippet:631` |
| `ui/search/SearchViewModel.kt` | 268 | 搜索状态：250ms 防抖、关键词提交、分页、索引状态、地点候选 | `State:37`、`onKeywordChange:65`、`commitKeyword:75`、`clearFilter:128`、`loadLocationOptions:150`、`runSearch:210` |
| `data/SearchFilters.kt` | 193 | 搜索纯逻辑：时间范围、条件摘要、分页合并 | `TimeRangeKind:10`、`SearchUiState:25`、`indexNotReady:46`、`dayKeyRange:80`、`activeFilterItems:120` |
| `ui/settings/SettingsScreen.kt` | 865 | 设置页：五张卡片 + 行组件 + 四个对话框 + 忙碌遮罩 | `SettingsScreen:107`、`SettingsBody:195`、`MenuRow:487`、`ValueRow:544`、`SwitchRow:582`、`HourPickerDialog:627`、`ReminderTimeDialog:676`、`ImportModeDialog:705`、`BusyOverlay:726`、`SettingsAlertDialog:765`、`SettingsIcons:839` |
| `ui/settings/SettingsViewModel.kt` | 360 | 设置状态：全部 setter → `AppSettingsApplier.commit`；权限事件通道；SAF 导出/导入 | `SettingsUiState:31`、`SettingsUiEvent:67`、`setTheme:149`…`applyDayStartHour:171`、`exportTo:250`、`stageImport:272`、`runImport:297` |

### 1.7 设计系统与共享组件

| 文件 | 行数 | 负责的界面 | 关键 composable / class |
| --- | --- | --- | --- |
| `ui/theme/Theme.kt` | 112 | `JustDiaryTheme`、动态取色、回落色板、高对比 CompositionLocal | `LocalIncreasedContrast:26`、`FallbackLight/DarkScheme:29/36`、`JustDiaryTheme:54`、`isHighContrastEnabled:87`、`darkThemeFor:104` |
| `ui/theme/Color.kt` | 30 | 品牌种子色 + 8 个回落角色 | `BrandSeed = #2563EB:13`、`:22-30` |
| `ui/theme/Type.kt` | 112 | M3 排版（官方基线 + 逐档封顶缩放）+ 编辑器段落映射 | `justDiaryTypography:33`、`JustDiaryTypography:70`、`EditorTypography.textStyleFor:95`、`minimumInputHeight:111` |
| `ui/theme/DesignTokens.kt` | 293 | `Radius` / `Spacing` / `TypeSize` / `DynamicTypeMetrics` | `Radius:16`、`Spacing:58`、`TypeSize:98`、`DynamicTypeMetrics:191` |
| `ui/components/Components.kt` | 231 | 5 个共享原语 + 正文列限宽常量 | `DiaryCard:44`、`PageHeader:95`、`SectionTitle:129`、`IconBadge:145`、`EmptyState:177`、`ContentWidth:227` |
| `ui/adaptive/AdaptiveLayout.kt` | 190 | 版面判定（纯数据 + CompositionLocal + Host） | `AdaptiveLayout:36`、`splitsMasterDetail:54`、`masterWidth:63`、`navForm:67`、`tier:70`、`settingsColumns:77`、`LocalAdaptiveLayout:149`、`rememberAdaptiveLayout:158`、`AdaptiveLayoutHost:182` |

### 1.8 资源

| 文件 | 行数 | 内容 |
| --- | --- | --- |
| `res/values/strings.xml` | 220 | **190** 个 `<string>`（英文），无 `<plurals>`/`<string-array>` |
| `res/values-zh-rCN/strings.xml` | 209 | **189** 个 `<string>`；与英文**只差 `app_name`**（键集合完全一致） |
| `res/values/date_formats.xml` / `values-zh-rCN/` | 37 / 26 | 日期时间**语义槽位** 14 个 key（与 iOS `Localizable.xcstrings` 同名） |
| `res/values/colors.xml`、`values-night/colors.xml`、`values/themes.xml` | 11 / 4 / 8 | 只提供窗口底与启动图标色；`Theme.JustDiary` = `Theme.AppCompat.DayNight.NoActionBar`（AppCompat 深色/语言机制 + Compose 配色） |
| `res/xml/locales_config.xml` | 7 | `en` + `zh-CN` |
| `res/xml/file_paths.xml` | 11 | FileProvider 路径（分享长图、备份包） |

---

## 2. 逐屏现状

### 2.1 根导航（`ui/root/RootScreen.kt`）

**做了什么**

- 4 个标签，顺序固定：日记 / 足迹 / 搜索 / 设置（`AppTab.kt:22-25`），图标 `Edit / Place / Search / Settings`。
- 形态切换：`adaptive.navForm == Rail`（宽 ≥ 600dp）走 `NavigationRail` + `VerticalDivider` + 右内容区（`:111-138`）；否则 `Scaffold(bottomBar = NavigationBar)`（`:140-167`）。
- 几何**只读一次**：`BoxWithConstraints` → `rememberAdaptiveLayout(maxWidth, maxHeight)`（`:107-108`），经 `LocalAdaptiveLayout` 下发；`Scaffold` 的 `innerPadding` 被消费（`:163`）。
- 日记页是**全屏覆盖**：`openDayKey != null` 时提前 return，走 `AdaptiveLayoutHost` + `DiaryPageScreen`（`:95-105`），没有导航栈。
- 跨页刷新：`dataVersion` 计数器（`:85`）+ 各页 `LaunchedEffect(dataVersion)`（`:197`、`:221`）—— 对应 iOS 的 `.diaryVersionChanged`。首页另监听设置版本（`:199-200`）。
- 深链：`LaunchIntent.dayKey` → `openDayKey`（`:88-93`）。
- 每个标签页的 ViewModel 在**首次打开**时创建并长期存活（`:195`、`:218`、`:236`）；`SettingsScreen` 与 `MainActivity` 共用同一个 `SettingsViewModel` 实例（`MainActivity.kt:46-48` + `SettingsScreen.kt:113`）。

**没做的**

- 顶部没有全局 `TopAppBar`（符合 v2 §4「每个标签页各自持有」）：首页用自绘 `HomeTopBar`，其余三屏用 `PageHeader`。
- 底部导航**没有**内层多级导航（如日记列表 → 日记详情）：只有"打开一天"的全屏覆盖。

### 2.2 首页（`ui/home/HomeScreen.kt`）

**状态机**：`HomeViewMode { MONTH, WEEK, YEAR }`（`:102`），`mode` 为 `rememberSaveable`（`:159`）；`yearProgress` / `weekProgress` 两个 `Animatable` **从恢复出来的 mode 初始化**（`:170-171`，D2 的修法）；`morphing = yearProgress > 0.01f || weekProgress > 0.01f`（`:173`）；动画规格 `morphSpec`（`:182-204`）：默认 400ms + `cubic-bezier(0.2,0,0,1)`，`Animator` 关闭时 180ms 线性，`DebugFlags.slowMorph` 时 3000ms 线性。

**月态**（`:571-573`）：`pagerModifier` 包住 `CalendarSection`。版面 = 顶栏 64 + 大标题槽 72 + 星期栏 30 + `rows` 行网格；`rows = CalendarGrid.displayedWeeks(...).size`（`:325-328`，4…6 行）；`cellH` 由 `CalendarLayout.cellHeightFor(areaH)` 得到（按**固定 6 行**等分、不封顶，`CalendarLayout.kt:254-259`）。顶栏（`:460-468`）：左 `▾ 2026年`（整块可点 → 年历）+ 右 `今天`。

**周态**（`:550-568`）：`CalendarSection` + `DayPanel(weight 1f)`。点日期触发**整屏 morph 进周态**（`:471-484`）：`selectedKey = key; setWeekMode(true)`；morph 期间选中行度量插值 `rowMetrics`（`:394-402`），行位移用**绝对 y**（`:382-388`），落点 `stripTopY = 0f`（`:378`）。周条可左右拖拽翻周（`WeekStrip:846`，阈值 60dp，`:942`），翻月/翻年用上下拖拽（`:422-455`，`DragPagerMath`）。

**年态**（`:576-589`）：`YearLayer` → `YearCanvas`，绘制 62dp 页内大标题 + 干支生肖（`CalendarCanvas.kt:99-127`）+ 12 张迷你月（日号 + 今天高亮 + 有日记下划线 / 今天圆点，`:155-208`），12 张卡片各为一个可聚焦节点（`:211-237`）。点卡片 → `openMonthFromYear`（缩放 morph，源卡片 = **当前选中月**那张，`:620-624`）。

**分栏（横屏 / 宽窗口）**（`:517-546`）：`Row` 里放 左栏 `CalendarSection`（`weight 1f`）+ `VerticalDivider`（1dp `outlineVariant@40%`）+ 右栏 `DayPanel`；判据 `LocalAdaptiveLayout.current.splitsMasterDetail`（宽 ≥700dp 且宽 > 高）；顶栏在分栏时**隐藏**（`:459`）；进入分栏时强制归位到月态（`:413-419`）；分栏走 `fitCellHeight` 密铺 + `resolveDensity` 三档降级（`:336-350`）。

**当日面板**（`DayPanel:1065`）：标题行（日期 `titleSmall` + 农历 `labelSmall` + 可选 `今天` 按钮，`:1087-1122`）；内容区 `verticalScroll` + 560dp 限宽（`:1132-1136`）；有片段 → `DayBlockCard` 列表 + 今天显示「继续写日记」（`:1148-1152`）；无片段 → `DayEmptyState`（月/铅笔 emoji + 文案 + 仅今天给「写日记」，`:1164-1195`）。`DayBlockCard` 显示 `时间 · 地点`（`auto_time` 关时只留地点，`:1206-1215`）+ 只读段落（`:1244-1287`），整卡可点 → 阅读页（`:1219`）。

**stub / missing（首页）**

| 项 | 现状 | 证据 |
| --- | --- | --- |
| 分栏左栏**没有翻月手势** | `pagerModifier` 只挂在非分栏分支；分栏分支的 `Box` 无 `pointerInput` | `HomeScreen.kt:521-524`（对比 `:571`、`:551`） |
| 分栏下点日期 → 左栏塌成周条，**无法回月态** | `onDayClick` 无条件 `setWeekMode(true)`；回月态只由（已隐藏的）顶栏标题触发 | `:476-479`、`:459`、`:268-278` |
| 分栏下没有周态/年态 | `mode` 被强制为 `MONTH`（三态只在竖屏完整存在） | `:413-419`、`:548-590` |
| `CALENDAR_BAND_TOP = 52f` 遗留常量（顶栏已是 64dp）、`Icons.Filled.DateRange` 未使用导入 | 死代码 | `:936`、`:36` |
| 首页内联图片**点按无预览** | `DayPanelPart` 的 `Image` 无 `clickable` | `:1246-1258` |
| 未来日期可点（保持 iOS 行为） | 点未来日期 → 进周态 + 未来空态 | `:1083`、`:1154` |
| `HomeViewModel.onMonthChanged:95`、`resetToToday:107` | **无人调用**（界面自己管 monthKey/selectedKey） | §7.3 |

### 2.3 日记阅读页（`ui/diary/DiaryReadScreen.kt` + `DiaryPageScreen.kt`）

**结构（自上而下，全部在一个 `verticalScroll` 的 Column 里）**

1. 顶栏动作行：仅右对齐的动作 —— 多选计数 + 删除（`selectMode` 时）、`写日记`（仅今天）、`分享`（`:119-146`）。**没有返回按钮**（用系统返回，`DiaryPageScreen.kt:91-93` `BackHandler`）。
2. 条目内搜索栏：`OutlinedTextField`（placeholder「搜索日记内容…」）+ 清除 + 计数 `i/n` + 上一个/下一个 `IconButton`（`:149-184`）。
3. hero 卡片（**常驻**，空态也画 —— D8 的修法）：日期（20sp medium，`TypeSize.cardTitle`）+ 今天胶囊（`primary` 底）+ 元信息行（首片段 startLine · 片段数，`:194-249`）。
4. 片段列表：每块为 `Column` + `combinedClickable`（点击 / 长按）+ 元信息行 + 段落（`:252-296`）。
5. 底部 `Box(padding bottom = 96.dp)`（`:298`）。

**段落渲染**（`ReadPart:312`）三形态：`Paragraph`（引用走 `surfaceContainerHighest` 圆角卡，其余 `Text`，对齐取 `align == center`；样式→角色走 `EditorTypography.textStyleFor`）、`Items`（列表 `•`、待办 `TodoBox` 可点切换）、`Image`（宽 `ReadLayout.imageDisplayWidth`，缺失时按存储宽高比占位 + 「图片加载失败」文案）。

**状态与渲染的断链（重要）**

- `DiaryPageScreen` 只传了 `dayTitle / blocks / keyword / currentMatch / onSearchChange / onSearchStep / onToggleTodo / onShare / imageLoader / onWrite`（`:108-154`），**没有传** `onEditBlock / onLongPressBlock / selectMode / selectedIds / onToggleSelect / onDeleteSelected` —— 它们全部落在默认空实现上（`DiaryReadScreen.kt:100-110`）。后果：
  - 点片段**不进编辑**（`DiaryPageViewModel.enterEditBlock:226` 从未被调用）；
  - 长按**不进多选**（`enterSelect:250`、`toggleSelection:259`、`confirmDeleteSelected:267` 全部未被调用）；
  - `DiaryAlert.ReadOnly` 与 `DiaryAlert.DeleteConfirm` **不可达**。
- 页内搜索**实际失效**：`keyword = if (state.showSearch) state.searchText else ""`（`DiaryPageScreen.kt:123`），而 `showSearch` 初值 `false` 且只由 `setSearchVisible`（`DiaryPageViewModel.kt:462`）置位 —— 后者**无人调用**。于是搜索框是受控且恒为空，输入不显示、无高亮、无 `i/n`、无跳转（`scrollTargetBlockId` / `consumeScrollTarget` 亦无人用）。

**阅读页缺失项**：内联图片点按 → 全屏预览（`ReadPart` 的 Image 分支无点击，`:353-384`）；只读提示条（`AlertDialog` 实现，无 Snackbar）；顶栏缺少 v2 §6.1 图里的 `←` 与搜索图标。

### 2.4 日记编辑页（`ui/diary/EditorScreen.kt`）

**结构**

1. 顶栏：`←`（有改动时弹「放弃修改？」）/ 日期标题 / `放弃修改` / `保存`（`:140-174`）。**`插入图片` 不在顶栏**，它是格式栏最后一枚芯片（`FontToolbar.kt:183-189`）。
2. 时间胶囊：`auto_time` 开且文案非空时显示（`primaryContainer` 底 + `primary` 字 + 圆角 50%，`:178-189`）。
3. 位置行 `LocationRow:305`：左 = **状态**（`定位中…` `primary` / 未记录地点 / 地名），右 = **动作**（`重新获取位置` `AssistChip`，仅新建片段）+ 精度档位文案与「切换精度」按钮（`:316-361`）；`Hidden` 状态整行不渲染（`:311`）。
4. 正文：`EditorTextArea`（`weight(1f)` + `verticalScroll` + 16dp 边距，`:200-209`）；宿主是 `AppCompatEditText`（`EditorTextArea.kt:76`），placeholder 是叠在上层的 `Text`（`:62-69`），最小高 = `EditorTypography.minimumInputHeight = 160dp`（`Type.kt:111`）。
5. 格式栏 `FontToolbar`：`Surface(surfaceContainerHigh, Radius.panel, elevation 3)` + 横向滚动 `FilterChip` 组（`:212-249`）；`enabled = !state.composing`（IME 组合期间禁用，`:221`）；顺序：段落样式菜单 → 居中 → 列表 → 引用 → 待办 → B → I → S → U → 插入图片；选中态统一 `primary`/`onPrimary`（`FontToolbar.kt:200-205`）；居中与列表/引用/待办互斥（置灰不隐藏，`:122`）。
6. 两个本地 `AlertDialog`：放弃修改确认（`:253-268`）、保存时无位置确认（`:271-286`）。

**保存链路**：`onSave(EditorSavePayload)` → `DiaryPageViewModel.save:331`（编辑已有片段 → `updateBlockContent`；新建 → 跨日判定 → `insertBlock`；`start_time_utc` **永远**记录编辑器打开时刻，`EditorScreen.kt:393-396`）。

**stub / missing（编辑页）**

| 项 | 现状 | 证据 |
| --- | --- | --- |
| 段落样式菜单**含「引用」**（4 项） | v2 §7.4 要求只给「大标题 / 小标题 / 正文」 | `FontToolbar.kt:100` + `EditorBlockStyle:100-105` |
| 格式栏横屏不竖排贴右 | 始终是横向滚动一行 | `FontToolbar.kt:83-88` |
| 按钮无显式 `minWidth 34dp × minHeight 40dp` | 用 `FilterChip` 默认尺寸 | `FontToolbar.kt:91-189` |
| ~~`EditorHistory` 已构造但撤销/重做无入口~~ | **已删除（P1-6）**；脏标记只看 `lines != initialLines` | — |
| `DiaryPageViewModel.confirmDiscard:299` 未被调用 | 「放弃修改」实际走编辑器本地弹窗 → `onDiscard` → `close()` | `EditorScreen.kt:155-157`、`:146` |

### 2.5 分享（**没有独立页面**）

- 入口：阅读页顶栏的分享 `IconButton`（`DiaryReadScreen.kt:142-144`）→ `DiaryPageViewModel.shareDiary:562`：把当天片段转成 `ShareLayout.ShareBlock`（time / location / parts）→ `ImageShareRenderer.renderToFile`（`:576-584`）→ `state.shareImageFile`。
- 出口：宿主 `DiaryPageScreen` 的 `LaunchedEffect(state.shareImageFile)` 直接 `startActivity(ShareIntents.shareImage(...))`（`:81-89`），系统分享面板，**没有预览页、没有分享设置**。
- 失败 → `DiaryAlert.ShareFailed` 弹窗；空日记 → 直接 `ShareFailed`（`DiaryPageViewModel.kt:571-574`）。
- 版式是 iOS 的忠实移植：720pt 宽、时间轴、日期 62、起始行 28、地点 24 等（`ShareLayout.kt:42-70`），自绘背景为渐变 + **两个装饰光斑**（`ImageShareRenderer.kt:144-166`），配色是写死的十六进制（`Palette.light/dark:370-408`）。
- 分享图里每个片段的时间用 `SimpleDateFormat("HH:mm")`（`ImageShareRenderer.kt:356-359`），**不跟随应用语言与设备 12/24 小时制**；`auto_time` 关闭时只影响顶部起始行（`DiaryPageScreen.kt:139-143`），逐片段时间照画（`:92`）。

### 2.6 足迹（`ui/footprint/FootprintScreen.kt`）

- 页头 `PageHeader`：标题 + 「已标注 N 处 · 未记录 M 篇」（`:94-97`，文案 `footprint_summary`）。
- 年份芯片行：`全部` + `state.yearOptions`（**没有「自定义」**，`:107-118`）。
- `locatedCount == 0` → `EmptyState`（`:121-128`）。
- 竖屏：`LazyColumn`（统计卡 → 趋势卡 → 地点树卡，`:155-180`）；分栏（`landscape == true`，即 `splitsMasterDetail`）：`StatsCard` → `Row[TrendCard(weight 1f, 高度 footprintBlockHeight) | PlaceTreeCard(宽 footprintListWidth)]`（`:130-153`）。
- 统计卡：5 格（省市 / 城市 / 国家 / 片段 / 天数），**每格合并为一个语义节点**读作「省市 7」（`:202-210`）。
- 趋势卡：`SectionTitle` + 自绘 `YearBarChart`（Y 轴 0/中值/最大值 + 网格线 + 柱 `Radius.bar` + 柱顶数值 + 年份标签）；整图一个 `contentDescription`（`:280-286`）。
- 地点树：递归行，缩进 16dp/级（`:390`），`IconBadge` 32dp + 名称 + 计数 + 展开箭头；非叶行 `clickable(role = Button)` + `clearAndSetSemantics`「名称 计数」+ `stateDescription` 展开/收起（`:386-399`）。

**stub / missing（足迹）**

| 项 | 现状 | 证据 |
| --- | --- | --- |
| **时间筛选整组没有 UI** | `setTimeKind:84`、`clearTimeFilter:94`、`applyCustomRange:100` 三个方法无人调用；`TimeRangeKind.Custom` 无入口 | §7.3 |
| 叶子节点点击无效 | `PlaceRow` 的 `onDayClick` 只往下透传，从未挂 `clickable` | `FootprintScreen.kt:366-442`（尤其 `:378-387`、`:437`） |
| 趋势卡常驻 | v2 §八要求「只在年份数 ≥ 2 时出现」；现在 `yearly.isEmpty()` 时显示「暂无趋势」文案 | `:227-252` |
| 空态不是垂直居中 | `EmptyState(weight(1f, fill = false))` | `:121-128` |
| 底部 96dp 写死 | v2 §4 要求用 `innerPadding` | `:160` |

### 2.7 搜索（`ui/search/SearchScreen.kt`）

- 页头 `PageHeader`：标题 + `state.total < 0` 时不显示计数（`:140-144`）。
- 搜索框：`OutlinedTextField` + 前置放大镜 + 有词时清除按钮；`imeAction = Search`；`semantics { contentDescription = placeholder }`（`:147-176`，注释说明 Compose 1.12 无 search 档 `Role`）。
- 关键词芯片行：已提交词 → `InputChip`（点整枚 = 删除）+ 右侧「清除全部条件」（`:179-198`）。
- 时间/地点两组 `FilterChip`：宽屏（`filtersSideBySide`）左右并排，否则上下堆叠（`:201-237`）。时间四档 = 全部 / 本周 / 本月 / 今年（`timeRangeOptions:653-658`）；地点 = 全部地点 / 未记录地点 / 选择地点 / 已选（R32 起「选择地点」胶囊**不带装饰图钉、
   文案也不带省略号**，只有「已选中」带对勾）。
- 生效条件摘要：`filterItems` 渲染成**静态**小圆角块（`surfaceContainerHighest` + `labelSmall`），**没有 ✕、没有逐条移除、末尾没有「清除全部条件」**（`:240-263`）。
- 结果区四态（`:265-321`）：① `indexNotReady` → 转圈 + 「正在准备搜索索引…」；② 无条件 → 「搜索你的日记」+ 提示；③ 有条件无结果 → 「没有找到相关日记」+ 清除全部按钮；④ 结果 `LazyColumn` + 「加载更多」。
- 结果行：日期胶囊（`primaryContainer`/`onPrimaryContainer`，显示的是 `result.dayKey` 原文而不是本地化日期）+ 摘要 2 行 + 高亮片段 2 行 + 右 chevron；整行 `heightIn(48dp)` + `clickable(role = Button)` + `clearAndSetSemantics` 描述「日期 · 摘要 · 时刻」（`:537-623`）。
- 地点选择器：`LocationPickerDialog:328`（**`AlertDialog`**）内嵌本地搜索框、排序提示、加载中、`全部地点` / `未记录地点(N)` / 国家·地区(N) 列表，选中项打勾。

**stub / missing（搜索）**

| 项 | 现状 | 证据 |
| --- | --- | --- |
| **自定义时间范围无 UI** | `TimeRangeKind.Custom` 存在、`applyCustomRange:97` 存在，但界面上没有日期范围选择器 | §7.3 |
| 条件摘要不可逐条移除 | `searchViewModel.clearFilter:128` 无人调用；`clearTimeFilter/clearKeywordInput` 同样无人调用 | §7.3 |
| 互斥取值用 `AlertDialog` 而非 `ModalBottomSheet` | 全仓没有 `ModalBottomSheet` | `SearchScreen.kt:349`；v2 §9.3 |
| 搜索框最小高 46dp 未设 | 用 `OutlinedTextField` 默认（56dp） | `:147-176` |
| 滚动时不收起搜索框/筛选面板 | 结果列表在下方独立 `LazyColumn`，头部固定 | `:294-320` |
| 「清除全部条件」只在无条件结果时出现 | 关键词芯片行里另有一个，但摘要行没有 | `:196`、`:289` |

### 2.8 设置（`ui/settings/SettingsScreen.kt`）

- 结构：`LazyVerticalGrid`，列数 `LocalAdaptiveLayout.current.settingsColumns` = 1 / 2 / 3（`<680` / `≥680` / `≥1000`，`:206-209`）；页头跨全列（`:221-223`）；五张卡：**通用 / 日记规则 / 提醒 / 数据 / 关于**。
- 通用：语言（跟随系统 / 中文 / English，取值显示母语原文 `:818-823`）、主题（跟随系统 / 浅色 / 深色）、动态取色 `Switch`。
- 日记规则：新一天的开始时间（`AlertDialog` + 24 小时列表，文案走 `DateFormats.dayStartLabel`）、一周从哪一天开始（菜单）、自动插入时间 `Switch`、自动插入地点 `Switch`、位置权限行（**仅 `autoLoc` 开时出现**，`:311-320`）、允许修改/删除历史日记 `Switch`。
- 提醒：提醒开关；开时出现提醒时间行（M3 `TimePicker` 对话框，跟随设备 12/24 制 `:686`）；开着且未授权时出现通知权限行（`:356-365`）。
- 数据：导出（菜单：仅数据 / 含设置 → SAF `CreateDocument`，MIME `application/octet-stream`，文件名 `JustDiary_yyyyMMdd_HHmmss.jdiary`）、导入（SAF `OpenDocument` → 先拷进缓存再弹「跳过 / 覆盖」确认框）。
- 关于：只有一行版本号（`state.appVersion`，`:404-410`）。
- 行组件：`MenuRow`（行 + `DropdownMenu` + 勾选）、`ValueRow`（行 + 动作）、`SwitchRow`（行 + `Switch`）；行高 `minHeight 56dp`，`IconBadge` 38dp，分隔线从 62dp 起（`:456-461`）；尾部取值固定 `width(96.dp)` + `maxLines = 1` + 省略号（`:465-483`）。
- 忙碌遮罩 `BusyOverlay:726`：全屏 `Box` + `clickable(indication = null)` 吞点击 + `surfaceContainerHigh` 卡片 + 进度条 + 文案。
- 权限：`SettingsUiEvent` 通道 → `rememberLauncherForActivityResult`（定位多权限 / 通知单权限 / 跳系统设置），`SettingsScreen.kt:116-159`。

**stub / missing（设置）**

| 项 | 现状 | 证据 |
| --- | --- | --- |
| **多列布局**（2/3 列） | v2 §10 明确「单列卡片，竖屏与宽窗口共用，不做左右两页」 | `SettingsScreen.kt:206-209`、`AdaptiveLayout.kt:77-81` |
| `Switch` 行**整行不可点** | 只有 `Switch` 本身可点；v2 §10 要求整行 `toggleable` 且 `contentDescription = 标题` | `SwitchRow:582-615`（全仓无 `toggleable`） |
| 尾部取值不做自动缩小 | 固定 96dp + 省略号，长文案会截断 | `:465-483` |
| 分组标题压在卡片内顶部 | 用 `padding(horizontal = 16.dp, vertical = 0.dp)` 覆写 `SectionTitle` 的 6dp | `:228-231` |
| 「关于」卡片只有版本行 | 无图标、无版权/开源说明 | `:398-412` |

### 2.9 其他弹层 / 浮层清单

| 弹层 | 类型 | 位置 |
| --- | --- | --- |
| 日记页 9 类结果/确认弹窗（历史不可新增、只读、退出确认、放弃确认、跨日、保存失败、删除确认、插图失败、分享失败） | `AlertDialog` | `DiaryPageScreen.kt:230-313` |
| 编辑器「放弃修改」确认 / 「保存但无位置」确认 | `AlertDialog`（本地 state） | `EditorScreen.kt:253-286` |
| 地点选择器 | `AlertDialog` | `SearchScreen.kt:328-427` |
| 开始时间小时列表 | `AlertDialog` + `LazyColumn` | `SettingsScreen.kt:627-671` |
| 提醒时刻 | `AlertDialog` + M3 `TimePicker` | `SettingsScreen.kt:676-701` |
| 导入方式（跳过 / 覆盖 / 取消） | `AlertDialog` | `SettingsScreen.kt:705-722` |
| 导出/导入进行中 | 自绘遮罩（非 M3 组件） | `SettingsScreen.kt:726-761` |
| 设置结果提示 | `AlertDialog` | `SettingsScreen.kt:765-806` |
| 段落样式菜单 / 设置取值菜单 | `DropdownMenu` | `FontToolbar.kt:99-112`、`SettingsScreen.kt:523-538` |

全仓**没有** `ModalBottomSheet`、`Snackbar`、`Toast`（`grep` 为空）。

---

## 3. 设计系统现状

### 3.1 颜色与主题（`ui/theme/Theme.kt`、`Color.kt`）

- 入口：`JustDiaryTheme(themeMode: String = AppSettings.THEME_SYSTEM, dynamicColor: Boolean = true)`（`Theme.kt:54-58`），由 `MainActivity` 用设置态驱动（`MainActivity.kt:63-67`）。
- 动态取色：`dynamicColor && SDK_INT >= S` → `dynamicDark/LightColorScheme(context)`（`:64-65`）；否则回落 `FallbackDark/LightScheme`（`:29-41`），**只覆写 4 个角色**（`primary` / `onPrimary` / `primaryContainer` / `onPrimaryContainer`），其余取 M3 基线；种子色 `BrandSeed = #2563EB`（`Color.kt:13`）。
- 深色判定：`darkThemeFor(themeMode, systemInDark)` 纯函数（`:104-108`），非法值按「跟随系统」。
- 高对比：`LocalIncreasedContrast`（`:26`）由 `isHighContrastEnabled(context)` 提供（`:87-93`，先试 `AccessibilityManager.isHighContrastTextEnabled`，回退读 secure setting）；唯一消费者是 `DiaryCard` 的描边（`Components.kt:50-58`：0.5dp/45% ↔ 1.5dp/90%）。
- **`MaterialTheme.shapes` 没有被赋值** —— 主题只传 `colorScheme` + `typography`（`:72-76`），所以 M3 组件走库默认形状；自绘/半自绘组件用 `RoundedCornerShape(Radius.*)`（13 处）。
- 页面底色：`MainActivity` 的 `Surface(color = colorScheme.background)`（`MainActivity.kt:68-71`）。

### 3.2 排版（`ui/theme/Type.kt`、`DesignTokens.kt`）

**两层字号，职责分开**（R3 的结论）：

1. **M3 的 15 个角色** = `Typography()` 官方基线 + 本项目逐档封顶缩放，`fontScale = 1` 时与官方逐字一致（`Type.kt:33-63`）。
2. **内容层 `TypeSize`**（`DesignTokens.kt:98-164`）：`display 32`、`pageTitle 24`、`sheetTitle 17`、`cardTitle 20`、`headerTitle 18`、`statValue 18`、`rowTitle 15`、`rowSub 13`、`rowValue 15`、`sectionTitle 13`、`chip 14`、`badge 12`、`meta 13`、`caption 12`、以及编辑器四档 `editorTitle 28` / `editorHeading 22` / `editorBody 16` / `editorQuote 14`。

**动态字体**：`DynamicTypeMetrics`（`DesignTokens.kt:191-279`）：

- `appleTextStyle(designSize)` 把设计字号映射到最接近的 Apple 文本样式（`:197-208`）；
- `ceiling(style)` 逐档上限 1.35…1.75（`:211-221`）；
- `effectiveMultiplier = min(ceiling, max(1, fontScale))`（`:231-234`）—— **`fontScale = 1` 时全部为 1**，梯级永不倒挂；
- `effectiveSp(designSize, fontScale)`（`:242-246`）先除掉 Compose 的自动缩放；`effectiveSpForSize`（`:275-278`）供 M3 角色使用；
- 日历专用**半速**：`calendarMultiplier`（`:253-256`）、`calendarSp`（`:259-262`）。

**编辑器段落映射**（`Type.kt:91-112`，唯一映射点）：`title → headlineMedium 28/36`、`heading → titleLarge 22/28`、`body → bodyLarge 16/24`、`quote → bodyMedium 14/20`；`designSizeForStyle`（`:103-108`）供布局计算；`minimumInputHeight = 160dp`（`:111`）。

### 3.3 形状 / 间距 / 触控（`DesignTokens.kt`）

- `Radius`（**对齐 M3 形状梯级**，`:16-55`）：`card 16`(large) / `panel 28`(extraLarge) / `field 12` / `image 12` / `badge 12`(medium) / `bar 4`(extraSmall) / `hairline 1`；`concentric(outer, inset) = (outer - inset).coerceAtLeast(0)`（`:54`）。
- `Spacing`（`:58-75`）：`card 12` / `rowIcon 12` / `hitTarget 48` / `page 16`。
- 触控目标真实性：日格 ≥ `MIN_CELL_H 44`（`CalendarLayout.kt:146`）且在竖屏由 `cellHeightFor` 拉到 ≥44；结果行 `heightIn(min = 48.dp)`（`SearchScreen.kt:565`）；设置行 `heightIn(min = 56.dp)`（`SettingsScreen.kt:500`）；芯片用 M3 默认 48dp 交互尺寸。

### 3.4 动效（`CalendarLayout.kt` + `HomeScreen.kt`）

| 令牌 | 值 | 位置 |
| --- | --- | --- |
| morph 时长 | **400ms**（M3 `medium4`） | `CalendarLayout.kt:40` |
| morph 缓动 | `cubic-bezier(0.2, 0, 0, 1)`（M3 `emphasized`） | `CalendarLayout.kt:54` |
| 减弱动态效果 | **180ms 线性**（`ValueAnimator.areAnimatorsEnabled()`） | `CalendarLayout.kt:46`、`HomeScreen.kt:185-188` |
| 调试慢放 | 3000ms 线性（`-slow-morph`） | `CalendarLayout.kt:43`、`DebugFlags.kt:29` |
| 翻月 / 翻周 / 回今天 | 300ms（medium2）/ 250ms（medium1） | `CalendarLayout.kt:68-69`、`HomeScreen.kt:865` |
| 翻页数学 | `minimumDragDistance 8`、橡皮筋 `c = pageSize × 0.25`、阈值 `predictedEnd 90` / `translation 60`、投影 0.2s（**本层唯一近似**） | `CalendarLayout.kt:611-668` |
| morph 内部插值 | 月↔周：行位移 / 行度量 / 大标题 / 内容区；年↔月：网格矩形重分格、字号、农历与分隔线 alpha、年层 opacity 与 scale 1+0.6 | `CalendarLayout.kt:578-606`、`:495-534` |
| 帧率日志 | `-morph-log` 打「N 帧 / M ms / 平均」 | `HomeScreen.kt:214-226` |

### 3.5 共享组件（`ui/components/Components.kt`，函数签名）

```kotlin
@Composable fun DiaryCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
                          contentPadding: PaddingValues = PaddingValues(Spacing.card),
                          content: @Composable () -> Unit)                        // :44
@Composable fun PageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null,
                           trailing: (@Composable () -> Unit)? = null)            // :95
@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier)          // :129
@Composable fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, contentDescription: String? = null,
                          tint: Color = onPrimaryContainer, background: Color = primaryContainer,
                          size: Dp = 38.dp)                                          // :145
@Composable fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier,
                           contentDescription: String? = null, hint: String? = null,
                           actionLabel: String? = null, onAction: (() -> Unit)? = null) // :177
object ContentWidth { val reading = 660.dp; val editing = 620.dp; val cardColumn = 480.dp } // :227
```

`DiaryCard`：`Surface` + `surfaceContainerLow` + 描边（0.45f `outlineVariant`，高对比时 1.5dp/0.90f）+ `Radius.card`；可点版本 `tonalElevation 2 / shadowElevation 3`，否则 `1 / 1`（`:54-85`）。
`PageHeader` 用 `MaterialTheme.typography.headlineLarge`（`:112`）—— 注意它**不**使用 `TypeSize.pageTitle`。
`EmptyState` 图标 40dp、正文 `bodyMedium`、可选 `bodySmall` 提示与 `Button` 动作（`:193-217`）。

### 3.6 自适应（`ui/adaptive/AdaptiveLayout.kt`）

**判定只吃可用尺寸**，不读机型/方向/`Display`（`:17-24`）。

| 能力 | 规则 | 位置 |
| --- | --- | --- |
| 主导航 | `width >= 600dp` → `Rail`，否则 `Bar` | `:67`、`:115` |
| 内容档位 `tier` | `<700` Compact / `<1000` Medium / `≥1000` Expanded | `:70-74`、`:118-119` |
| 设置列数 | `<680` 1 列 / `<1000` 2 列 / `≥1000` 3 列 | `:77-81`、`:122` |
| **首页/足迹分栏** | `contentWidth >= 700` **且** `width > height` | `:54`、`:125` |
| 主栏宽 | `clamp(contentWidth × 0.46, 280, 440)` | `:63-64`、`:127-129` |
| 筛选并排 | 同 `splitsMasterDetail` | `:84` |
| 趋势图高 | 132dp / 200dp（分栏时） | `:87`、`:136-137` |
| 足迹清单宽 / 块高 | `min(320, max(240, contentWidth × 0.4))` / `max(140, min(190, height − 262))` | `:94-95`、`:102-103` |
| 正文列宽 | `contentColumn(maxWidth = 660)`：`min(maxWidth, max(240, width − 32))` | `:106-107`、`:132-134` |
| 安全区 | `safeLeading/Trailing/Top/Bottom` 由 `WindowInsets.safeDrawing` 解析；`contentWidth` 扣左右 | `:46`、`:158-173` |

**手机横屏**：**启用**，走"宽 ≥600dp → 侧栏 + 宽 ≥700dp 且宽 > 高 → 首页分栏"两档；**没有**独立的平板/折叠屏版面（没有 ListDetail 骨架、没有三分栏）。`AdaptiveLayoutHost`（`:182-189`）给全屏路由（日记页）自带 `BoxWithConstraints`。
`contentColumn` 与 `tier` 在生产代码里**无调用点**（只被 `AdaptiveLayoutTest` 断言）：`tier` 仅用于语义分组，`contentColumn` 尚未接线。

### 3.7 图标

- 只有 `material-icons-core`（`app/build.gradle.kts` 的 `libs.androidx.compose.material.icons.core`），**刻意不引 `material-icons-extended`**（理由：35.7MB aar + `isMinifyEnabled = false`）。
- 两处集中映射：`ui/root/AppTab.kt:22-25`（导航四图标）、`ui/settings/SettingsScreen.kt:839-855`（15 个设置行图标，语义最近者）。
- 由于缺扩展包，界面上出现"文字芯片"替代图标的地方：格式栏的**居中对齐**（`FontToolbar.kt:119-129`，注释解释了为什么没有图标）、`B/I/S/U`（`:154-181`）。

### 3.8 字符串与文案约定

- 全部 UI 文案走 `stringResource`；两个 locale 键集合**完全一致**（除 `app_name`），共 190/189 键。
- 分组（按 key 前缀，英文键数）：`settings_* 64`、`editor_* 34`、`search_* 22`、`a11y_* 14`、`index_* 14`、`footprint_* 12`、`read_* 7`、`loc_* 6`、`reminder_* 4`、`tab_* 4`、`share_* 2`、单词键 `app_name/cancel/delete/discard/ok/save` 各 1。
- **日期时间文案不允许硬编码**：统一走 `service/DateFormats.kt`（由 `container.settings.load().appLanguage` 装配，`RootScreen.kt:189-191`、`SettingsScreen.kt:161-163`、`SearchViewModel.kt:183-189`）；`res/values/date_formats.xml` 是它的**语义槽位**（14 个 key，与 iOS `Localizable.xcstrings` 同名）。
- 组件里的文案用 `private val XXX_LABEL: String @Composable get() = stringResource(...)` 模式（如 `DiaryReadScreen.kt:499-516`、`SearchScreen.kt:660-699`、`SettingsScreen.kt:810+`），因为这个写法允许在非 Composable 的 `semantics {}` lambda 里先取好文本。
- **11 个键已无人使用**（设计时可清理或复用）：`settings_notif_on`、`index_year_view`、`index_month_view`、`index_week_view`、`index_future_toast`、`index_day_heading`、`index_toggle_week`、`index_toggle_month`、`a11y_month_grid`、`a11y_scroll_to_today`（`app_name` 由 Manifest 使用）。
- 例外（未走资源/未走 DateFormats 的文案）：首页空态用 emoji `🌙` / `✏️`（`HomeScreen.kt:1179`）、阅读页 bullet `"•"` 与待办 `☑/☐`（`DiaryReadScreen.kt:337`、`EditorModel.kt:124-126`）、分享图时间 `"HH:mm"`（`ImageShareRenderer.kt:357`）、设置页语言名 `"中文"/"English"` 刻意不翻译（`SettingsScreen.kt:818-823`）。

---

## 4. 与 `docs/ui-design-plan.md` 的差距

判定口径：**done** = 代码里有且行为与 v2 一致；**partial** = 有但缺项/取值不符；**missing** = 代码里找不到。

### 4.1 v2 §3 设计令牌

| v2 条款 | 判定 | 证据 |
| --- | --- | --- |
| §3.1 色板走 `MaterialTheme.colorScheme` + 动态取色默认开、回落只覆写 primary 四角色 | **done** | `Theme.kt:29-41`、`:63-69` |
| §3.1 不写死十六进制 | **partial**：UI 层零写死，但**分享长图**写死 10 个颜色（`Palette.light/dark`） | `ImageShareRenderer.kt:370-408` |
| §3.1 高对比 → 卡片描边 1.5dp/90% | **done**（仅 `DiaryCard` 消费） | `Components.kt:50-58` |
| §3.1 不用 alpha 压文字 | **done**（日号/周末列都改语义色；只剩描边与分隔线的 alpha） | `CalendarDayGrid.kt:214-222`、`:353-356` |
| §3.2 M3 15 角色 = 官方字号 | **done**（`Typography()` 基线 + 封顶缩放） | `Type.kt:33-63` |
| §3.2 自绘内容用 `TypeSize`；编辑器 28/22/**16**/**14** | **done** | `DesignTokens.kt:144-153`、`Type.kt:95-100` |
| §3.2 `fontScale = 1` 全为 1；日历半速 | **done** | `DesignTokens.kt:231-234`、`:253-262` |
| §3.2 固定高度改 `minHeight` | **done**（顶栏 `heightIn`、设置行 `heightIn`；`FitCellHeight` 例外见 §5.1） | `HomeScreen.kt:981`、`SettingsScreen.kt:500` |
| §3.3 `Spacing` = page16/card12/rowIcon12 | **done** | `DesignTokens.kt:58-75` |
| §3.3 圆角全落 M3 梯级（16/28/12/12/12/4） | **done** | `DesignTokens.kt:16-46` + `DesignTokensTest.kt:19-28` |
| §3.3 最小触控 48dp | **partial**：`Spacing.hitTarget = 48` 但**几乎没有组件用它**；日格靠 `MIN_CELL_H 44`，结果行/设置行分别写 48/56 | `DesignTokens.kt:71`；`CalendarLayout.kt:146` |
| §3.3 可交互元素必须有状态层 | **done**（日格圆形涟漪 `radius = circleDiameter/2`；年历卡片有界涟漪；遮罩 `indication = null`） | `CalendarDayGrid.kt:235-241`、`CalendarCanvas.kt:223-228`、`SettingsScreen.kt:730-733` |
| §3.4 morph 400ms + M3 emphasized；翻页 300/250；减弱 180 线性 | **done** | `CalendarLayout.kt:40-69`、`HomeScreen.kt:182-204` |
| §3.5 图标集中两处映射 | **done** | `AppTab.kt`、`SettingsScreen.kt:839` |
| §3.6 页面底 = surface、卡片 = surfaceContainerLow + 0.5dp 描边、日历不套卡片 | **done** | `Components.kt:57`、`HomeScreen.kt:571-573` |

### 4.2 v2 §4 信息架构

| v2 条款 | 判定 | 证据 |
| --- | --- | --- |
| 4 标签、顺序、图标、`NavigationBar`/`NavigationRail` 按 600dp | **done** | `AppTab.kt:22-25`、`RootScreen.kt:109-152` |
| 顶栏各标签页自持 | **done**（首页自绘 + 其余 `PageHeader`） | `HomeScreen.kt:971`、`FootprintScreen.kt:94`、`SearchScreen.kt:140`、`SettingsScreen.kt:222` |
| 系统占位统一 safeDrawing / `Scaffold.innerPadding` 必须消费 | **partial**：`innerPadding` 消费了，但 4 处又写死底部留白（搜索 96 / 足迹 96 / 阅读 96 / 设置 72+16） | `RootScreen.kt:163`；`SearchScreen.kt:300`、`FootprintScreen.kt:160`、`DiaryReadScreen.kt:298`、`SettingsScreen.kt:216` |
| 内容列表底部不写死 96dp | **missing**（同上 4 处） | 同上一行 |

### 4.3 v2 §5 首页

| v2 条款 | 判定 | 证据 |
| --- | --- | --- |
| §5.1 顶栏 64 + 大标题槽 72 + 星期栏 30 + `cellH = gridAreaH / 6` 不封顶 | **done**（`SANITY_MAX_CELL_H 200` 只在极端比例生效） | `CalendarLayout.kt:77`、`:80`、`:83`、`:254-259` |
| §5.1 月态无预览、周态有预览、年态无预览 | **done** | `HomeScreen.kt:548-590` |
| §5.1 `cellH` 跨月恒定（不按当月行数等分） | **done**（`rows` 只决定画几行） | `CalendarLayout.kt:259`、`HomeScreen.kt:325-351` |
| §5.2 只画当月日期、相邻月留空且不进无障碍树 | **done**（`showAdjacent = false` 且空 `Box` 不带语义） | `CalendarDayGrid.kt:87`、`:142-146` |
| §5.2 行分隔线只在该行含当月日期时画 | **done** | `CalendarDayGrid.kt:115-124` |
| §5.2 日格：圆底 → 日号 → 农历 → 有日记下划线；字号 `clamp(cellH×0.34,13,20)` / `clamp(cellH×0.18,9,11)`；圆底 `min(cellW−3, 内容高+14, cellH−3)` | **done** | `CalendarLayout.kt:303-310`、`:221`；`CalendarDayGrid.kt:256-303` |
| §5.2 今天 = 1.5dp 描边圆环；选中 = 实心圆 + `onPrimary` 半粗；有日记 = 2dp 圆角短横线 | **done** | `CalendarDayGrid.kt:264-289`、`:300-316`、`CalendarLayout.kt:442` |
| §5.2 **未来日期 alpha 0.35** | **不适用**：R3 已改为语义色 `onSurfaceVariant`（v2 自身 §3.1 也禁止 alpha） | `CalendarDayGrid.kt:217-222` |
| §5.3 周态 7 段结构（顶栏 / 星期栏 / 周条 68 / 分隔线 / 日期行 40 / 分隔线 / 内容 weight） | **partial**：缺"周条下分隔线"与"日期行下分隔线"；日期行高用 `heightIn(min = 40dp)` | `HomeScreen.kt:1086-1122`、`CalendarLayout.kt:91` |
| §5.3 日记卡片 = `surfaceContainerLow` + 描边 + 12dp 内边距，元信息行/正文可点进日记页 | **done**（卡片用 `Card` 默认形状而非 `Radius.card`，视觉近似） | `HomeScreen.kt:1216-1223` |
| §5.4 12 个迷你月历（日号 + 有日记下划线 + 今天点） | **done**，但**有日记与今天是互斥分支**（`when { hasDiary -> …; isToday -> … }`），同一天不会既画线又画点 | `CalendarCanvas.kt:169-207`（`:193-205`） |
| §5.4 年月标题行 62 + 生肖 | **done** | `CalendarLayout.kt:100`、`CalendarCanvas.kt:99-127` |
| §5.4 卡片不描边不填色 | **done** | `CalendarCanvas.kt:93-208` |
| §5.4 卡片节点 `contentDescription = 月份名` + `stateDescription` | **done** | `CalendarCanvas.kt:230-234` |
| §5.5 morph 端点取自真实图层 | **done** | `HomeScreen.kt:369-379`、`:617-633` |
| §5.6 手势表：点日期月→周；点顶栏年月→年；点迷你卡→月；上下滑翻月/翻年；左右滑翻周；今天 | **done**（**分栏下例外**，见 §2.2） | `HomeScreen.kt:440-443`、`:471-484`、`:874-905`、`:295-306` |
| §5.6 morph 进行中忽略新输入 | **partial**：`pagerModifier` 的 `pointerInput` key 未含 `morphing`；`weekMode` 判定只用于着色，未禁用 pager | `HomeScreen.kt:426`、`:403` |
| §5.7 顶栏三态（`▾ 2026年` / `‹ 九月` / `‹ 2026年`）+ 今天；删除日历图标 | **done** | `HomeScreen.kt:459-469`、`:971-1017`、`homeTitle:1025-1037` |
| §5.8 分栏判据 ≥700dp 且宽 > 高；左栏 `clamp(46%,280,440)`；正文列限宽；密度降级三档 | **partial**：判据/宽度/降级 done，正文列限宽用的是 **560dp**（v2 §5.8 写 560，§6.1 写 660，代码取 560） | `HomeScreen.kt:1135`、`AdaptiveLayout.kt:54-64`、`CalendarLayout.kt:399-407` |
| §5.8 左栏"只有上下滑翻月" | **missing**（左栏无手势，见 §2.2） | `HomeScreen.kt:521-524` |
| §5.8 右栏标题行只留「今天」按钮（D9） | **done** | `HomeScreen.kt:1111-1119` |
| §5.9 三个实现坑（unbounded / Row 分栏 / `fillMaxHeight`） | **done**（代码注释逐条标注） | `HomeScreen.kt:731-735`、`:519-521`、`CalendarDayGrid.kt:228-231` |
| §5.10 日格无障碍：完整日期 + 状态 + `selected` + 自定义动作（上一天/下一天 等 4 条） | **partial**：日期/状态/`selected` done；自定义动作是「上个月/下个月/上一年/下一年」挂在**整个日历容器**上，不是"上一天/下一天"挂在日格上 | `CalendarDayGrid.kt:249-253`、`HomeScreen.kt:737-744` |
| §5.10 空白格不进无障碍树；星期栏不合并；触控 ≥48dp | **done**（触控目标实际 44dp 起，见 §4.1） | `CalendarDayGrid.kt:144`、`:325-363` |

### 4.4 v2 §6 阅读页

| v2 条款 | 判定 | 证据 |
| --- | --- | --- |
| §6.1 顶栏 4 个动作（返回 / 搜索 / 写日记 / 分享），图标 + `contentDescription` | **partial**：返回按钮**没有**（用系统返回）；搜索是常驻输入框而非图标 | `DiaryReadScreen.kt:119-184`、`DiaryPageScreen.kt:91-93` |
| §6.1 hero 卡片（日期 20sp + 元信息 13sp，含开始时间与地点两行） | **partial**：日期 20sp medium done；元信息合成**一行**（startLine · 片段数），地点不再单列 | `DiaryReadScreen.kt:211-247` |
| §6.1 卡片 `surfaceContainerLow` + 描边 + 12dp 内边距 + `Radius.card` | **partial**：容器/形状 done，内边距是 **14dp**，无描边 | `DiaryReadScreen.kt:198-205` |
| §6.1 正文列限宽 660dp 居中 | **partial**：只有 hero 标题限宽 660dp；正文列没有限宽，也没有居中 | `DiaryReadScreen.kt:216`、`:431-438` |
| §6.1 底部预留导航栏高度（不写死） | **missing**（写死 96dp） | `DiaryReadScreen.kt:298` |
| §6.2 hero 常驻、空态文案写进 hero、可写时给按钮 | **done**（空态另外还画了一个图标 + 文案 + 按钮的块） | `DiaryReadScreen.kt:194-249`；`HomeScreen.kt:1164-1195` |
| §6.3 段落样式 28/22/17/15 + 行距表 | **与 R3 冲突**：代码按 M3 的 28/22/**16**/**14**、行高用角色官方值（v2 §3.2/§3.7 已改，§6.3 表未同步） | `Type.kt:95-100` |
| §6.3 内联图片 `Radius.image(14)`、点按进全屏预览 | **partial**：圆角是 **12**（R3 改的）；**全屏预览 missing** | `Radius.image:37`；`DiaryReadScreen.kt:353-384` |
| §6.4 页内搜索：框最小高 46dp；命中计数徽章 `primaryContainer` 胶囊；上一个/下一个是普通按钮；防抖 150ms | **partial + 功能失效**：防抖 150ms done（`DiaryPageViewModel.kt:623`）；框高未设、计数是纯文本 `labelMedium`、无徽章；且整条链路因 `showSearch` 恒 false 而失效 | §2.3；`DiaryReadScreen.kt:156-183` |

### 4.5 v2 §7 编辑页

| v2 条款 | 判定 | 证据 |
| --- | --- | --- |
| §7.1 结构（顶栏 / 时间胶囊 / 位置行 / 正文 / 格式栏贴键盘上方 8dp） | **partial**：`imePadding` 让整列贴键盘，格式栏自带 8dp 边距；顶栏的"插入图片"不在（在格式栏） | `EditorScreen.kt:134-138`、`:222-224`；`FontToolbar.kt:183` |
| §7.2 顶栏左 `←`（未保存弹确认）+ 右三动作 | **partial**：左 `←` done；右只有「放弃修改」「保存」，`插入图片` 移到格式栏 | `EditorScreen.kt:146-173` |
| §7.3 时间胶囊（`auto_time` 开时显示；关时不显示） | **done**（编辑器 / 阅读页 hero / 首页面板 / 分享起始行四处一致） | `EditorScreen.kt:178-189`；`DiaryPageScreen.kt:115-119`、`:139-143`；`HomeScreen.kt:1206-1215` |
| §7.3 位置行：左状态（`定位中…` primary / 未记录地点 / 地名），右动作 `重新获取位置`（仅新建） | **done** | `EditorScreen.kt:313-347` |
| §7.4 格式栏顺序 段落样式 → 居中 → 列表 → 引用 → 待办 → B I S U | **done**（末尾多了「插入图片」） | `FontToolbar.kt:90-189` |
| §7.4 补齐居中对齐；列表/引用/待办下禁用对齐 | **done**（置灰） | `FontToolbar.kt:119-129` |
| §7.4 段落样式菜单**只给**大标题/小标题/正文 | **missing**（菜单含引用，共 4 项） | `FontToolbar.kt:100`、`EditorBlockStyle:100-105` |
| §7.4 格式栏横屏竖排贴右侧；按钮 `minWidth 34 × minHeight 40`；激活态 `primary` 填充 | **partial**：激活态 done；竖排 **missing**；尺寸用 `FilterChip` 默认值 | `FontToolbar.kt:76-88`、`:200-205` |
| §7.5 字号不落库、编辑器持设计字号、原样保存字节级不变 | **done**（有 golden 与单测守住） | `DesignTokens.kt:91-93`；`app/src/test/resources/golden/` |

### 4.6 v2 §8 足迹

| v2 条款 | 判定 | 证据 |
| --- | --- | --- |
| 标题行 `足迹` 24sp + 右侧统计 | **done** | `FootprintScreen.kt:94-97`、`Components.kt:95-125` |
| 年份筛选 `FilterChip` 横向滚动：全部 + 各年份 + **自定义**（时间范围选择器） | **partial**：全部 + 年份 done；**自定义 missing**，时间筛选整组无 UI | `FootprintScreen.kt:107-118`；§7.3 |
| 统计卡 5 等分、整格一个语义节点 | **done** | `FootprintScreen.kt:186-223` |
| 趋势卡只在年份数 ≥ 2 时出现；柱 `Radius.bar`；Y 轴 12sp；补无障碍描述 | **partial**：柱/轴/无障碍 done；**总在渲染** | `FootprintScreen.kt:227-252`、`:280-337` |
| 地点清单树形、缩进 16dp/级、叶子无 chevron、计数 12sp tabular | **partial**：树/缩进/无 chevron done；计数是 `labelLarge`（14sp），非 12sp tabular | `FootprintScreen.kt:390`、`:414-418` |
| 空态：图标 + `还没有地点记录`，垂直居中 | **partial**（`weight(1f, fill=false)`，未真正居中） | `:121-128` |
| 宽窗口：趋势图与地点清单并排（图表 flex、清单 240–320dp） | **done** | `:130-153`、`AdaptiveLayout.kt:94-95` |

### 4.7 v2 §9 搜索

| v2 条款 | 判定 | 证据 |
| --- | --- | --- |
| §9.1 结构：页头 + 计数徽章 | **done**（计数是 `PageHeader.subtitle` 纯文本，不是徽章） | `SearchScreen.kt:140-144` |
| §9.2 搜索框最小高 46dp；聚焦时右侧出现「取消」；语义正确；防抖 250ms；回车提交为芯片 | **partial**：防抖 250ms + `imeAction.Search` + `contentDescription` done；**没有「取消」按钮**（只有清除）、未设 46dp 最小高 | `SearchScreen.kt:147-176`、`SearchViewModel.kt:262` |
| §9.3 时间与地点两组 `FilterChip`；宽窗口并排；互斥取值用 `ModalBottomSheet` | **partial**：芯片与并排 done；**没有自定义时间范围**；地点选择器用 `AlertDialog` 而非 `ModalBottomSheet` | `:201-237`、`:349` |
| §9.4 条件汇总行：每条 `primaryContainer` 胶囊 + `✕`（可单独移除）+ 最右「清除全部条件」；无条件时整行不显示 | **partial**：行会显示/隐藏 done，但胶囊是**静态** `surfaceContainerHighest` 小块、无 `✕`、无「清除全部」 | `:240-263` |
| §9.5 三档空态；`indexNotReady` 与 `total` 解耦 | **done** | `:265-292`、`SearchFilters.kt:36-47`、`SearchViewModel.kt:217-224` |
| §9.6 结果行：日期胶囊 + 摘要（命中高亮）+ 时间 + chevron；整行 ≥48dp；Button 语义 | **partial**：全部 done，但日期胶囊里放的是 **`dayKey` 原文**（`2026-09-20`）而不是本地化日期；结果行**没有独立的右侧时间文本**（时间只在无障碍描述里） | `:587-599`、`:546-559` |
| §9.6 底部「加载更多」；向下滚动收起搜索框与筛选面板 | **partial**：加载更多 done；滚动收起 **missing** | `:312-319` |

### 4.8 v2 §10 设置

| v2 条款 | 判定 | 证据 |
| --- | --- | --- |
| **单列卡片**，竖屏与宽窗口共用 | **missing**（1/2/3 列网格） | `SettingsScreen.kt:206-209` |
| 分组标题 13sp medium `onSurfaceVariant`，压在卡片内顶部 | **done**（`SectionTitle` = `labelMedium`） | `Components.kt:129-136` |
| 行：图标徽章 38×38（正方形）+ 标题 15sp + 副标题 13sp + 尾部取值 15sp + chevron | **partial**：徽章/尾部/chevron done；标题副标题用的是 **`bodyMedium 14` / `bodySmall 12`**，不是 15/13 | `SettingsScreen.kt:510-517`、`:565-573` |
| 行高 `minHeight 56dp`；分隔线从文字起点起（缩进 62dp） | **done** | `:500`、`:456-461` |
| 互斥取值用「行 + `DropdownMenu` + 勾选」，不用 `AlertDialog` | **done** | `:487-540` |
| 破坏性确认用 `AlertDialog` | **done** | `ImportModeDialog:705`、`SettingsAlertDialog:765` |
| 开关：`Switch`、**整行 `toggleable`**、`contentDescription = 标题` | **missing**（只有 `Switch` 可点，无 `toggleable`、无 `contentDescription`） | `:582-615` |
| 尾部取值 `maxLines = 1` + 自动缩小 | **partial**（固定 96dp + 省略号） | `:465-483` |
| 时间选择：小时轮盘用应用自己的时间文案；提醒时间用 `TimePicker` | **done** | `:627-701`、`Formats.dayStartLabel` |
| 忙碌遮罩半透明 + 进度 + 说明，遮罩之上不可点 | **done** | `:726-761` |
| 「关于」分组 | **partial**（只有版本号一行） | `:398-412` |

### 4.9 v2 §11 无障碍

| 条款 | 判定 | 证据 |
| --- | --- | --- |
| 日历逐格可读、空白格不可聚焦 | **done** | `CalendarDayGrid.kt:249-253`、`:144` |
| 所有 `IconButton` 有 `contentDescription`；装饰性显式 `null` | **done**（逐处检查：`DiaryReadScreen` 的删除/写/分享/清除/上一个/下一个都有；`SearchScreen` 清除与 chip 的 ✕ 有；`HomeScreen` 的 `▾/‹` 为 `null` 且标题块整体有语义） | `DiaryReadScreen.kt:134-144`、`:165-182`；`HomeScreen.kt:996-1002` |
| 触控目标 ≥48dp | **partial**（日格 44dp 起；见 §4.1） | `CalendarLayout.kt:146` |
| 减弱动态效果 → 180ms 线性 | **done** | `HomeScreen.kt:185-188` |
| 统计数字/图表/日历状态不只靠颜色 | **done** | `FootprintScreen.kt:208-210`、`:285`；`CalendarDayGrid.kt:242-252` |
| 语言切换后所有文案跟随（含日期/农历/相对日）；12/24 制跟随设备 | **partial**：除分享图时间（写死 `HH:mm`）外均走 `DateFormats`；相对日文案（今天/昨天/明天）在 UI 里**已无使用点** | `ImageShareRenderer.kt:356-359`；`date_formats.xml` 的 `index_yesterday/tomorrow` 未被引用 |

### 4.10 代码做了、但 v2 未授权的

| 项 | 说明 | 位置 |
| --- | --- | --- |
| **设置页多列网格**（2/3 列） | v2 §10 明确要求单列；`AdaptiveLayout.settingsColumns` 无 v2 依据（来自 iOS） | `SettingsScreen.kt:206-209`、`AdaptiveLayout.kt:76-81` |
| **格式栏末尾的「插入图片」芯片** | v2 §7.2 把插入图片放在顶栏；代码把它做成第 10 枚芯片 | `FontToolbar.kt:183-189` |
| **分享长图的两个装饰光斑 + 写死配色** | v2 §3.6 说"不用 iOS 的蓝色光晕背景"、§3.1 说"不写死十六进制"；分享海报沿用了 iOS 的视觉（v2 未对海报单独授权或豁免） | `ImageShareRenderer.kt:144-166`、`:370-408` |
| **首页 3 态之外的"分栏月态密铺"** | 分栏下用 `fitCellHeight` 均分（v2 §5.8 只说"密度降级三档"，未定义"均分铺满"） | `HomeScreen.kt:345-350`、`CalendarLayout.kt:270` |
| **`EmptyState` 的第三行 `hint`** | v2 §9.5 空态只描述了标题；代码额外给一行提示 | `Components.kt:206-214`、`SearchScreen.kt:278-283` |
| **首页空态用 emoji 图标**（`🌙` / `✏️`） | v2 只说"图标 + 文案"，emoji 不是 M3 图标做法 | `HomeScreen.kt:1172-1182` |
| **编辑页「保存但无位置」二次确认** | v2 §7 未提；来自 iOS 的 `LocationRules` 规则 | `EditorScreen.kt:271-286`、`LocationRules.needsSaveConfirmation` |
| **日记页 9 类 `AlertDialog`** | v2 只规定"破坏性确认用 AlertDialog"；代码把只读提示、保存失败、分享失败等**全部**用 AlertDialog 呈现 | `DiaryPageScreen.kt:230-313` |

### 4.11 v2 自己内部相互矛盾、设计文档必须先裁决的地方

| # | 矛盾 | 位置 | 代码跟随的是 |
| --- | --- | --- | --- |
| C1 | morph 时长/曲线：§3.4 表 = **400ms + `emphasized(0.2,0,0,1)`**，§5.5.1/§5.5.2 标题 = **600ms + `timingCurve(0.2,0.75,0.3,1.0)`** | `ui-design-plan.md:175` vs `:407`、`:422` | §3.4（400ms + M3 曲线，`CalendarLayout.kt:40-54`） |
| C2 | 正文/引用字号：§3.2/§3.7 = **16 / 14**（M3），§6.3 表 = **17 / 15**（Apple） | `:127`、`:216` vs `:554-556` | §3.7（16/14，`Type.kt:95-100`） |
| C3 | 内联图片圆角：§3.3 = **12**（medium），§6.3 = **14** | `:153` vs `:561` | §3.3（12，`Radius.image`） |
| C4 | 日记卡片圆角：§3.3 = **16**（large），§5.3 图示 = **20** | `:152` vs `:373` | §3.3（16，`Radius.card`） |
| C5 | 未来日期：§3.1/§3.7 = **语义色、禁止 alpha**，§5.2 表 = **alpha 0.35** | `:109-112`、`:220` vs `:351` | §3.1（`onSurfaceVariant`） |
| C6 | 阅读正文列限宽：§5.8 = **560dp**，§6.1 = **660dp** | `:479` vs `:540` | 首页面板用 560（`HomeScreen.kt:1135`），阅读页只对 hero 标题用 660（`DiaryReadScreen.kt:216`） |
| C7 | 分栏左栏是否含三态：§5.8 表说"**三态都在左栏**"，同段又说"**不提供年历入口**" | `:477` | 只有月态（`HomeScreen.kt:413-419`） |
| C8 | 年历「有日记」与「今天」的标记：§5.4 要求"下划线 + 今天圆点"（可并存） | `:394` | 代码是 `when` 互斥（`CalendarCanvas.kt:193-205`） |

---

## 5. 可复用的钩子

> 新设计文档可以按这一节写"改哪里"，不必重新发明入口。

### 5.1 主题令牌（改外观的最小面）

| 想改什么 | 改哪里 |
| --- | --- |
| 品牌主色 / 回落色板 | `ui/theme/Color.kt:13`（种子）+ `:22-30`（8 个角色） |
| 动态取色开关与回落逻辑 | `ui/theme/Theme.kt:63-69` |
| 深色判定 | `ui/theme/Theme.kt:104-108` |
| **M3 组件圆角** | 目前是库默认；要统一改需在 `MaterialTheme(...)` 加 `shapes = ...`（`Theme.kt:72-76`，现在**没有**这一项） |
| 自绘组件圆角 | `ui/theme/DesignTokens.kt:16-55`（`Radius`，有单测钉住 M3 梯级） |
| 间距 | `ui/theme/DesignTokens.kt:58-75`（`Spacing`；`hitTarget` 目前几乎无人使用） |
| 字号 | M3 组件 → `Type.kt:33-63`；内容层 → `DesignTokens.kt:98-164`（`TypeSize`）；编辑器四档 → `Type.kt:95-108` |
| 缩放曲线/上限 | `DesignTokens.kt:191-279`（`DynamicTypeMetrics`，14 条单测） |
| 高对比行为 | `Theme.kt:26`、`Components.kt:50-58` |
| 动效时长/缓动 | `ui/home/CalendarLayout.kt:40-69`、`HomeScreen.kt:182-204` |
| 页面/卡片底色 | `MainActivity.kt:68-71`、`Components.kt:65-81` |

### 5.2 组件 API（可直接复用的签名）

见 §3.5（`DiaryCard` / `PageHeader` / `SectionTitle` / `IconBadge` / `EmptyState` / `ContentWidth`）。
组件库目前**只有这 5 个**——设计文档若新增"列表行""徽章""芯片组""区块标题"等通用件，需要明确定义在哪个文件、与现有 5 个的关系。

### 5.3 自适应 API

`AdaptiveLayoutHost { }` 包住全屏路由（`AdaptiveLayout.kt:182`）；页面内读 `LocalAdaptiveLayout.current`（`:149`），**不要自己读屏幕尺寸**（这是项目里的硬约定，见 `:145-148`）。
可用属性：`contentWidth`、`splitsMasterDetail`、`masterWidth`、`navForm`、`tier`、`settingsColumns`、`filtersSideBySide`、`trendHeight`、`footprintListWidth`、`footprintBlockHeight`、`contentColumn(maxWidth)`。阈值常量集中在 `:113-142`。

### 5.4 ViewModel 状态面（新设计必须逐字段映射）

**`HomeViewModel.State`**（`ui/home/HomeViewModel.kt:29-42`）
`today: LocalDate` / `monthKey: Int` / `selectedKey: String` / `year: Int` / `flags: Set<String>`（有日记的日期）/ `dayBlocks: List<EditBlock>?`（null = 这一天没日记）/ `selectedDiary: DiaryRecord?` / `weekStart: String` / `autoTime: Boolean`

**`DiaryPageViewModel.State`**（`ui/diary/DiaryPageViewModel.kt:54-87`，派生属性 `:88-101`）
`dayKey` / `actualDayKey` / `blocks: List<EditBlock>` / `isRead` / `editingIndex: Int?` / `selectMode` / `selectedIds: Set<Long>` / `location: LocationSnapshot?` / `locating` / `startTimeUtc: Long` / `settings: AppSettings` / `loadParts: List<ContentPart>` / `loadToken: Int` / `alert: DiaryAlert?` / `requestDismiss` / `searchText` / `showSearch` / `hits: List<SearchHit>` / `hitIndex` / `scrollTargetBlockId: Long?` / `shareImageFile: File?` / `todayKey` / `pendingSave: Pair<String, Long>?`
派生：`canEditToday` / `isEditingExistingBlock` / `canRelocate` / `locationText` / `precisionOptions`
枚举 `DiaryAlert`：`HistoryNoAdd` / `ReadOnly` / `ExitConfirm` / `DiscardConfirm` / `CrossDay(dayKey)` / `SaveFailed` / `DeleteConfirm(count)` / `ImageInsertFailed` / `ShareFailed`

**`EditorTextState`**（`ui/diary/EditorTextArea.kt:141-206`）
`lines: List<EditorLine>` / `selection: EditorSelection` / `composing: Boolean` / `revision: Int`；派生 `currentLine` / `inlineRange` / `activeFlags`；方法 `onUserText` / `apply` / `replace`
`EditorSavePayload`（`EditorScreen.kt:293-302`）：`parts` / `locText?` / `locPrecision?` / `startTimeUtc`
`ReadBlock`（`DiaryReadScreen.kt:303-309`）：`id` / `startTimeUtc` / `startLine` / `parts`

**`SearchViewModel.State`**（`ui/search/SearchViewModel.kt:37-57`）
`ui: SearchUiState` / `filterItems: List<SearchFilterItem>` / `locOptions: List<LocOption>` / `noLocCount: Int` / `locSearch: String` / `locLoading: Boolean` / `indexing: Boolean`
**`SearchUiState`**（`data/SearchFilters.kt:25-44`）
`keyword` / `keywords: List<String>` / `timeKind: TimeRangeKind` / `customRange: DayKeyRange?` / `locFilter: LocFilter` / `offset` / `items: List<SearchResultItem>` / `hasMore` / `total: Int`（-1 = 还没查过）/ `loading` / `indexing`
派生：`indexNotReady`（只等于 `indexing`，D1 的修法）/ `isEmpty` / `hasAnyCondition`
`SearchFilterItem(kind, label)`，`Kind = Keyword | Time | Location`（`:13-15`）

**`FootprintViewModel.State`**（`ui/footprint/FootprintViewModel.kt:34-46`）
`rows` / `yearFilter: String` / `timeKind: TimeRangeKind` / `customFrom` / `customTo` / `viewState: FootprintViewState` / `expanded: Set<String>` / `loaded`；派生 `hasPlaces`
**`FootprintViewState`**（`data/FootprintViewState.kt:14-24`）：`stats` / `nodes: List<FootprintNode>` / `yearly: List<FootprintYearStat>` / `locatedCount` / `unlocatedCount` / `yearOptions: List<String>`

**`SettingsUiState`**（`ui/settings/SettingsViewModel.kt:31-45`）
`settings: AppSettings` / `locationPermission: LocationPermissionState`（NONE/FUZZY/EXACT）/ `notificationsGranted` / `busy: BusyKind?`（EXPORT/IMPORT）/ `alert: SettingsAlert?` / `importModePromptVisible` / `pendingImport: File?` / `appVersion: String`
`SettingsAlert`：`DayKeysRecomputed(conflicts)` / `ExportFailed` / `ImportFailed` / `ImportSucceeded(stats)`
`SettingsUiEvent`：`RequestLocationPermission` / `RequestNotificationPermission` / `OpenAppSettings`

**`AdaptiveLayout`**（`ui/adaptive/AdaptiveLayout.kt:36-142`）：`width/height/safeLeading/safeTrailing/safeTop/safeBottom` + 上面 §5.3 的派生值。

### 5.5 几何纯函数（首页改版只动这一层 + 两个渲染文件）

`CalendarLayout` 可直接断言、无 Compose 依赖：常量（`:40-160`）、`cellHeightFor` / `fitCellHeight` / `monthGridHeight` / `monthAreaHeight` / `yearAreaHeight`、`monthPaneMetrics` / `weekMetrics` / `miniMetrics` / `DayMetrics.circleDiameter`、`yearCardRect` / `miniGridRect` / `miniTitleRect` / `yearCardSize` / `fullMonthGridRect`、`resolveDensity`、`yearMonthMorph` / `monthWeekMorph`、`DragPagerMath`、`weekRowIndex` / `dayAt` / `weekDayAt`。
渲染侧只有两个文件：`CalendarDayGrid.kt`（月格 + 星期栏，真实组件）与 `CalendarCanvas.kt`（年历，Canvas + 透明卡片层）。

### 5.6 资源约定

- 新文案 → `res/values/strings.xml` **与** `res/values-zh-rCN/strings.xml` **同时**加（键集合必须保持相等，除 `app_name`）。
- 前缀约定：`tab_` / `index_`（首页）/ `read_`（阅读页）/ `editor_`（编辑页）/ `search_` / `footprint_` / `settings_` / `loc_` / `reminder_` / `share_` / `a11y_`（无障碍描述）。
- 日期时间**不要自己拼**：加到 `res/values/date_formats.xml`（+ zh-rCN），并由 `DateFormats` 暴露；语义槽位与 iOS 同名。
- 资源在非 Composable 上下文（`semantics {}`、`Canvas` 绘制、ViewModel）里用时：前者用 `private val X: String @Composable get()` 模式；后者由宿主注入字符串（如 `SearchViewModel.apply` 的 `noLocLabel`，`SearchViewModel.kt:191`）。

---

## 6. 测试与验证手段

### 6.1 单测（`app/src/test`，32 个文件，**357** 个 `@Test`）

**覆盖 UI/几何逻辑的（JVM 纯逻辑，无需设备）**

| 文件 | @Test | 覆盖什么 | 代表用例 |
| --- | --- | --- | --- |
| `ui/home/CalendarLayoutTest.kt` | 13 | 顶栏/行高/区高/年历区高/横屏密度/`coerceIn` 不反转/星期字号/`dayAt`·`weekDayAt` 命中 | `cell height fills the screen and is never capped for the panel`、`month area fills the screen and never inverts`、`constants match the ios table` |
| `ui/home/CalendarMorphTest.kt` | 13 | morph 两端不变量（月端点=真实网格、年端点=迷你网格）、7×6 重分格、年层 opacity/scale、行号 | `year month morph ends exactly at the mini month grid`、`month week morph ends at the week strip layout` |
| `ui/home/CalendarGridTest.kt` | 8 | 固定 6 行、尾部裁剪、2412 月键/201 年/10488 周键 | — |
| `ui/adaptive/AdaptiveLayoutTest.kt` | 17 | 600dp 切导航、700/1000 档位、安全区扣减、竖屏不分栏、`masterWidth` 夹取 | `nav form switches at 600dp`、`portrait never splits even when wide enough` |
| `ui/theme/DesignTokensTest.kt` | 4 | `Radius` 落在 M3 梯级、`concentric`、`Spacing.hitTarget = 48` | `radius tokens sit on the material 3 shape scale` |
| `ui/theme/DynamicTypeMetricsTest.kt` | 14 | Apple 样式映射、上限表、`fontScale=1` 不变、AX5 表、梯级单调、`effectiveSp` 抵消 Compose 缩放、日历半速 | `ladder stays monotonic at every font scale` |
| `ui/theme/ThemeModeTest.kt` | 3 | 主题三值 → 深色（纯函数） | — |
| `ui/diary/EditorModelTest.kt` | **40** | 行↔`content_json`、列表/待办合并、段落样式摊平、倒序应用、行内 run、拆行/合并、图片行 | — |
| `ui/diary/EditorSelectionTest.kt` | 9 | 扁平偏移↔行内偏移（含标记前缀） | — |
| ~~`ui/diary/EditorHistoryTest.kt`~~ | ~~8~~ | **已删除（P1-6）** | — |
| `ui/diary/ReadLayoutTest.kt` | 15 | 渲染模型三种形态、图片宽度/高度/解码目标像素 | `image display width is capped by both the margin and the design maximum` |
| `ui/diary/ImageDisplaySizeTest.kt` | 8 | 编辑区内联图片尺寸规则 | — |
| `data/ShareLayoutTest.kt` | 13 | 分享长图常量与 iOS 逐项一致、换行、引用高度、图片高度 | `canvas constants match the ios renderer` |
| `data/FootprintAggregatorTest.kt` | 15 | 已定位判定、路径层级、统计去重、树计数、排序注入 | — |
| `data/SearchFiltersTest.kt` | 18 | 时间范围四档+自定义、条件摘要、关键词芯片、分页合并、**索引状态与 total 解耦** | `custom range is sorted so the order of picking does not matter` |
| `service/DateFormatsTest.kt` | 13 | 日期时间语义槽位（跟随语言与 12/24 制） | — |
| `data/SearchSqlTest.kt` / `model/ContentFormatTest.kt` / `service/LunarTest.kt` / `service/LocationRulesTest.kt` / `service/FtsSegmentTest.kt` 等 | 其余 | 数据层/跨端契约/位置规则（非 UI，但 UI 依赖其输出） | — |

**运行时（Robolectric，仅 2 个文件，均**不**覆盖 UI）**：`data/DiaryRepositoryRobolectricTest.kt`、`data/BackupServiceRobolectricTest.kt`。
**没有 `app/src/androidTest`、没有 screenshotTest**（`app/src/` 下只有 `main` 与 `test`）；`app/build.gradle.kts` 里配了 `androidTestImplementation` 与 `debugImplementation(compose.ui.test.manifest)`，但没有对应源码目录。
**golden 文件**：`app/src/test/resources/golden/{editor,stored,v1_upgraded}.json`（`content_json` 字节级跨端契约）。

### 6.2 `tools/` 下的三个脚本（都**不**覆盖 UI）

| 工具 | 作用 |
| --- | --- |
| `tools/verify_sqlite_schema.py` | 从 Kotlin 源码抽 DDL，用宿主 `sqlite3` 真跑一遍（表名/列名/顺序与 iOS 一致） |
| `tools/verify_backup_interop.sh` | 用 iOS 真实的 `ZipArchive.swift` 读 Android 写出的 `.jdiary` |
| `tools/cross_end_extract.swift` | 上一个脚本的辅助：从 iOS 源码里提取/复刻读写逻辑 |

### 6.3 构建与运行

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # 本机无系统 JDK
cd /Users/cov/JustGDiary
./gradlew clean assembleDebug testDebugUnitTest     # 编译 + 357 个 JVM 单测
python3 tools/verify_sqlite_schema.py               # 跨端 SQL 校验
bash    tools/verify_backup_interop.sh              # 跨端备份互通校验
```

- JDK 要求 17（`sourceCompatibility`/`jvmTarget = 17`，`app/build.gradle.kts`），本机用 Android Studio 自带 JBR（README.md:50）。
- SDK：`compileSdk 37` / `targetSdk 36` / `minSdk 33`；`applicationId = com.cov.justdiary`（**debug 也不加 `applicationIdSuffix`**）。
- Compose：BOM 2026.09.00 + material3 1.4.0 + `material-icons-core`（`gradle/libs.versions.toml`，声明在 `app/build.gradle.kts` 的 `dependencies`）；UI 第三方库**零**。
- Robolectric 首次运行会下 `android-all-instrumented`（~100MB），仓库已指向阿里云镜像（`app/build.gradle.kts` 的 `testOptions`）。
- 深链手工测试：`justdiary://editor?dayKey=YYYY-MM-DD` 或 `justdiary://editor/YYYY-MM-DD`（`LaunchIntent.kt:31-38`）。
- 调试 morph：`adb shell am start -n com.cov.justdiary/.app.MainActivity --ez slow-morph true --ez morph-log true`（`DebugFlags.kt:9-11`）。

### 6.4 上机验证与证据目录

- 参考设备：Pixel 10 Pro AVD，1280 × 2856 @480dpi = **426.7 × 952dp**，API 37。
- 版面/无障碍断言手段：`adb shell uiautomator dump`（读真实 bounds 与 `content-desc`/`stateDescription`）。
- 截图留档：`docs/ui-audit/`（首轮 + 3 张 `BUG-*` 现场）与 `docs/ui-audit/r2/`（R2 逐屏：月/周/年 × 浅/深、横屏分栏、编辑页、阅读页 hero、搜索空态，共 12 张 + `README.md`）。
- 尚无自动化 UI 测试（无 Espresso/Compose UI test 源码），上述断言都是**人工执行 + 留档**。
- `docs/android-design/mockups/` 目前**只有** `assets/mockup.css`，`png/` 为空 —— 设计稿尚未落库。

### 6.5 文档里的数字漂移（写新文档前先对齐口径）

| 文档 | 声称的单测数 | 位置 |
| --- | --- | --- |
| README.md | 355（正文另有一处 352） | `README.md:8`、`:52` |
| docs/design-spec.md | 356 | `design-spec.md:339` |
| docs/ui-design-plan.md | 355 | `ui-design-plan.md:784` |
| docs/migration-progress.md（R3） | 357 | `migration-progress.md:1051` |
| **实测（`@Test` 计数）** | **357** | — |

同理，`ui-design-plan.md:6` 声称"P0–P5 已全部落地"，与 §4 的 20 余条 partial/missing **不一致**——这是新设计文档必须先解决的口径问题。

---

## 7. 风险与硬约束

### 7.1 绝对不许动的（跨端契约与工程约束）

| 约束 | 内容 | 位置 |
| --- | --- | --- |
| **包名** | `com.cov.justdiary`，debug 也**不加** `applicationIdSuffix`（与 iOS 一致，深链与 FileProvider authority 都依赖它） | `app/build.gradle.kts`；`README.md:27-28` |
| **数据契约** | 表名/列名/字符串取值/`content_json` 字节/zip 布局/`settingsJson` 的 10 个键；`.jdiary` 双向互通 | `README.md:85-88`；golden 夹具 |
| **`start_time_utc` 照旧记录** | `auto_time` 只是"显示开关"；写 0 或改写会让 `day_key`/`created_utc`/排序错乱、记录掉到 1970 | `EditorScreen.kt:393-396` |
| **字号不落库** | `content_json` 只存语义样式（`title`/`heading`/`body`/`quote`）与行内标志；两端各按自己的规范推导渲染字号 | `Type.kt:91-100`；`.jdiary` 互通不受影响 |
| **`region1` 的两条路径故意不同** | 「记录新位置」与「回填历史」规则不一致，是**故意**与 iOS 一致，不是 bug | `README.md:87-88`；`LocationRules` |
| **minSdk 33 / targetSdk 36 / compileSdk 37** | 33 是 `setApplicationLocales` 走平台 `LocaleManager` 的下限（`AppSettingsApplier.kt:151-157`） | `app/build.gradle.kts` |
| **零第三方 UI 库** | 不引玻璃/模糊库、不引图表库（柱状图自绘）、不引 `material-icons-extended`（35.7MB） | `README.md:31`；`FontToolbar.kt:124-127` |
| **不用 Room** | 裸 SQL，表结构必须与 iOS 逐字一致 | `README.md:42` |
| **导航形态** | `NavigationBar`（<600dp）/`NavigationRail`（≥600dp）是平台约定，不照抄 iOS 的浮条 | `ui-design-plan.md:83` |
| **两端排版有意不同** | iOS 按 Apple HIG、Android 按 Material 3；不许把某一端"修"成另一端 | `migration-progress.md:1012-1041` |

### 7.2 已知缺陷 / 断链（**本清单新发现，文档里没有记录**）

| # | 严重度 | 现象 | 根因（file:line） |
| --- | --- | --- | --- |
| **U1** | **高** | **阅读页的"点片段进编辑"整条链断了**：点片段、长按多选、删除选中的入口全部无效；`ReadOnly` / `DeleteConfirm` 两个弹窗不可达 | `DiaryPageScreen.kt:108-154` 未传 `onEditBlock`/`onLongPressBlock`/`selectMode`/`selectedIds`/`onToggleSelect`/`onDeleteSelected`；`DiaryReadScreen.kt:100-110` 全是空默认值；`DiaryPageViewModel.enterEditBlock:226`/`enterSelect:250`/`confirmDeleteSelected:267` 无调用点 |
| **U2** | **高** | **阅读页条目内搜索完全失效**：输入框是受控且恒为空、无高亮、无 `i/n`、无跳转 | `DiaryPageScreen.kt:123` 用 `state.showSearch` 门控，而 `showSearch` 只由 `DiaryPageViewModel.setSearchVisible:462` 置位，该方法**无人调用**；`scrollTargetBlockId`/`consumeScrollTarget:488` 同样无人用 |
| **U3** | 中 | **横屏分栏左栏没有翻月手势**（`pagerModifier` 只挂在非分栏分支） | `HomeScreen.kt:521-524`（对比 `:551`、`:571`） |
| **U4** | 中 | **横屏点日期后左栏永久变成周条**：顶栏隐藏 → 没有回月态入口，且分栏的"归位"只在 `split` 变化时触发 | `HomeScreen.kt:476-479`、`:459`、`:413-419`、`:268-278` |
| **U5** | 中 | 阅读页/首页的内联图片**点按无任何反应**（无全屏预览） | `DiaryReadScreen.kt:353-384`、`HomeScreen.kt:1246-1258` |
| **U6** | 中 | 足迹地点树点击**无反应**（`onDayClick` 从未挂到行上） | `FootprintScreen.kt:366-442` |
| **U7** | 中 | 分享长图每个片段的时间写死 `HH:mm`，**不跟随应用语言/设备 12/24 制**；且 `auto_time` 关闭时逐片段时间仍会画出来 | `ImageShareRenderer.kt:356-359`、`:92` |
| **U8** | 低 | 4 处写死底部留白（96dp / 72dp），在底部导航模式下与 `innerPadding` 叠加 | `SearchScreen.kt:300`、`FootprintScreen.kt:160`、`DiaryReadScreen.kt:298`、`SettingsScreen.kt:216` |
| **U9** | 低 | 设置页 `Switch` 行整行不可点（违反 v2 §10 与 Material 的整行切换约定） | `SettingsScreen.kt:582-615` |
| **U10** | 低 | 搜索结果行的日期胶囊显示 `dayKey` 原文（`2026-09-20`），未本地化；结果行没有可见的时间文本 | `SearchScreen.kt:587-599` |
| **U11** | 低 | 年历同一天不会同时显示"有日记下划线"和"今天圆点"（`when` 互斥） | `CalendarCanvas.kt:193-205` |

### 7.3 死代码 / 未接线 API 清单（新设计要么接上、要么删掉）

**ViewModel 方法（有实现、无调用点）**

| 类 | 方法 |
| --- | --- |
| `HomeViewModel` | `onMonthChanged:95`、`resetToToday:107` |
| `DiaryPageViewModel` | `enterEditBlock:226`、`enterSelect:250`、`toggleSelection:259`、`confirmDeleteSelected:267`、`confirmDiscard:299`、`setSearchVisible:462`、`consumeScrollTarget:488` |
| `SearchViewModel` | `clearKeywordInput:87`、`applyCustomRange:97`、`clearTimeFilter:102`、`clearFilter:128` |
| `FootprintViewModel` | `setTimeKind:84`、`clearTimeFilter:94`、`applyCustomRange:100`、`toggle:115` |

**其他未接线的实现**

- ~~`EditorHistory`（撤销/重做栈，78 行 + 8 条单测）~~ —— 已确认不做并在 P1-6 删除；回滚只走「放弃修改」。
- `SpannableBridge.toSpannableFromParts:297`、`toParts:301`（已有 `toSpannable`/`fromSpannable` 路径）。
- `ReadLayout.highlight:168`、`DiarySearch.nextIndex:220` / `previousIndex:228`、`EditorSelectionMap.targetLineRange:109`。
- `AdaptiveLayout.contentColumn:106`、`AdaptiveLayout.tier:70`（仅被单测断言）。
- `CalendarLayout.landscapePaneHeight:410`（主代码与单测都无引用）、`CalendarLayout.monthMetrics:290` / `yearAreaHeight:279` / `portraitAreaHeight:240`（保留给旧调用点/单测）。
- `HomeScreen.kt:36` 未使用的 `Icons.Filled.DateRange` 导入、`:936` 过期的 `CALENDAR_BAND_TOP = 52f`。
- 10 个未使用的字符串资源（见 §3.8）。

### 7.4 无法从代码判定、需要上机或设计裁决的

1. **帧率**：morph 的实际帧率只有 `-morph-log` 能测（`HomeScreen.kt:214-226`），README 的"仍未验证"清单里仍挂着。
2. **TalkBack 真人走查**：语义节点已实测存在，但朗读顺序/提示语未逐条听（`README.md:112`）。
3. **分享长图的版式归属**：它是"跨端契约"还是"Android 可自由设计"？目前代码按 iOS 逐字移植 + 写死配色（§4.10），新的设计文档必须明确裁决。
4. **`docs/android-design/mockups/` 为空**：设计稿尚未入库，`png/` 目录只有空壳。
5. **v2 §4.11 列出的 8 处自相矛盾**（C1–C8）：新文档必须逐条拍板，否则代码里那 8 个"跟随 §3.x"的实现会被当成 bug 反复"修"。
