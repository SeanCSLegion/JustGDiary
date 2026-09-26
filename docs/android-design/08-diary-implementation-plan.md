# 08 · 日记页实施计划

> **这一篇是"怎么改"的施工图**，不是"改成什么样"的规范。规范在 [`04-diary.md`](04-diary.md)。
> 日记页是全 app 最复杂的一块：四种形态（阅读 / 编辑 / 多选 / 分享）、
> 一条富文本往返链路、一条位置采集链路、一条分享长图链路。
> 先把施工顺序和不变量钉死，再动手。
>
> 现状依据：`docs/android-ui-inventory.md` §2.3 + 本轮复核（2026-09-26）。

---

## 1. 现状：三条断链（先看清楚再动手）

### 1.1 阅读页 → 编辑 / 多选 / 删除：整条链没接线

`DiaryReadScreen` 的签名里有 6 个参数，`DiaryPageScreen.kt:108-154` **一个都没传**：

| 参数 | 作用 | 现状 |
| --- | --- | --- |
| `onEditBlock: (Int) -> Unit` | 点片段 → 进编辑 | 用默认值 `{}` → **点了没反应** |
| `onLongPressBlock: (Int) -> Unit` | 长按 → 进多选 | 用默认值 → **长按没反应** |
| `selectMode: Boolean` | 多选态 | 恒 `false` → 勾选框永不出现 |
| `selectedIds: Set<Long>` | 选中集合 | 恒空 |
| `onToggleSelect: (Int) -> Unit` | 多选态点片段 | 用默认值 |
| `onDeleteSelected: () -> Unit` | 删除选中的 | 用默认值 |

`DiaryPageViewModel` 那边的 `enterEditBlock`(:226) / `enterSelect`(:250) /
`confirmDeleteSelected`(:267) 都写好了 —— 是**死代码**。

### 1.2 页内搜索：状态齐了，入口没人开

`DiaryPageViewModel.setSearchVisible(:462)` 是 `showSearch` 的**唯一**写入口，而全仓没有调用点。
于是：

- 阅读页永远 `showSearch = false` → 传给 `DiaryReadScreen` 的 `keyword` 恒为空串；
- 算出来的 `hits` / `hitIndex` / `scrollTargetBlockId` 全部没有消费者；
- 界面上也没有搜索图标 —— 用户没有开它的办法。

> `DiaryReadScreen` 内部确实用 `DiarySearch.findMatches(parts, keyword)` 算高亮，
> 所以**只要把 keyword 传进去、把入口接上，高亮就活了**。缺的是入口与回写。

### 1.3 分享：没有面板，也没有失败反馈

`DiaryPageScreen.kt:81-89` 收到 `shareImageFile` 之后**直接** `ShareIntents.sendImage(...)`：

- 看不到长图（规范 §4.2 要的是"一步到面板：预览 + 动作"）；
- 渲染失败时（`shareImageFile == null`）静默 —— 没有任何提示；
- 没有"保存到相册"这条动作。

### 1.4 另外两处结构问题

- **阅读页没有返回按钮**：`DiaryReadScreen` 的顶栏只有"多选计数 / 写日记 / 分享"，
  返回完全依赖系统返回键。规范 §2.2 要求顶栏左侧是 `←`。
- **`DiaryReadScreen` 的顶栏不在 `Scaffold` 里**，而是 `Column(verticalScroll)` 的**第一个子项** ——
  一滚就跟着滚走。规范 §2.2 / `02-adaptive-and-navigation.md` §3.4 要求顶栏钉在顶部。

---

## 2. 目标结构

```
DiaryPageScreen（全屏路由，持有 ViewModel）
└─ Scaffold(topBar = DiaryTopBar(...), snackbarHost = ...)      ← 顶栏钉住，不进滚动区
   ├─ 阅读态
   │   ├─ [showSearch] DiarySearchBar（读/写 keyword，上/下一个步进）
   │   ├─ DiaryHeroCard（日期 + 今日徽标 + 首行时间 + 「N 个片段」 + 地点）
   │   └─ DiaryBlockCard × N（点→编辑；长按 400ms→多选；多选态显示勾选圈 + 2dp 描边）
   ├─ 编辑态
   │   └─ EditorCard（时间胶囊 + 地点行 + EditorTextArea）
   ├─ 底部浮层（仅编辑态）
   │   └─ DiaryFormatBar（9 键，宽度预算见 04-diary.md §3.3）
   ├─ ModalBottomSheet（分享）
   └─ AlertDialog × 3（放弃修改 / 删除多选 / 保存缺位置）
```

`DiaryTopBar` 的三态（规范 §2.2）：

| 形态 | 左侧 | 右侧 |
| --- | --- | --- |
| 阅读 | `←` 返回 | `search`（active=showSearch）· `edit` 写日记（**仅当天**）· `share` |
| 编辑 | `←` 返回（拦截 → 放弃确认） | `image` 插图 · `history` 放弃 · `check` 保存（active） |
| 多选 | `close` 退出多选 | 标题「已选择 N 项」+ `delete`（`error` 色） |

---

## 3. 分步实施（每步都可编译、可单独验证）

> 每一步都保持"能跑、能看"，不要在一步里同时动状态机与像素。

### 步骤 1 · 顶栏与返回（最小可验证的改动）

1. 新增 `DiaryTopBar`，三态按上表；`DiaryPageScreen` 换成 `Scaffold(topBar = …)`。
2. `DiaryReadScreen` 去掉自己的顶部 Row 与 `Column(verticalScroll)` 的最外层滚动 ——
   它应该只负责**内容区**（hero + 卡片），滚动交给 `Scaffold` 的内容槽。
3. 返回：`onBack` → `viewModel.close()`（内部已有 `requestDismiss` 语义）。
   编辑态返回要**先弹「放弃修改？」**（`DiaryAlert` 已有该分支）。
4. 预测性返回：`PredictiveBackHandler` → 多选态先退多选，编辑态走确认，阅读态交还系统。

**验收**：三种形态的顶栏都对；返回键行为正确；滚动时顶栏不动。

### 步骤 2 · 接通三条断链（**只接线，不改样式**）

1. 6 个回调逐个传进 `DiaryReadScreen`（§1.1 的表）。
2. 搜索入口：阅读态顶栏的 `search` → `viewModel.setSearchVisible(!showSearch)`；
   搜索栏的 `onSearchChange` → `onSearchTextChange`、`onSearchStep` → `stepHit`。
3. 命中跳转：消费 `scrollTargetBlockId`，用 `LazyListState.animateScrollToItem` 滚到该卡
   （`DiaryReadScreen` 现在是 `verticalScroll`，要换成 `LazyColumn` 才能定位 —— 见步骤 4）。

**验收**：点卡片进编辑；长按进多选；多选能删；搜索能开、能高亮、能跳。

### 步骤 3 · 分享面板

1. 阅读态点 `share` → `viewModel.shareDiary(...)`（已有）。
2. `shareImageFile` 非空 → 开 `ModalBottomSheet`：标题「分享长图」+ 文件名/尺寸副标题 +
   长图预览（`Image` + `scaledToFit`，最大宽 200dp）+ 两个动作
   「分享…」（`ShareIntents.sendImage`）/「保存到相册」（写 `Pictures/JustGDiary`）。
3. 渲染中：面板先开、显示不确定进度 + 「正在生成分享图…」。
4. 失败：关面板 + 一条 Snackbar（复用首页那套 `SnackbarHostState`）。

**验收**：点分享 → 立刻看到面板；预览正确；两个动作都可用；失败有提示。

### 步骤 4 · 阅读区改 `LazyColumn`（为命中定位铺路）

`verticalScroll` 换成 `LazyColumn`，`items(blocks, key = { it.id })`。
hero 卡作为 `item {}` 放最前。这样 `scrollTargetBlockId` 才有落点。

⚠️ 换 `LazyColumn` 会改变"整页作为一条语义"的行为，`clearAndSetSemantics` 的写法要跟着复核。

### 步骤 5 · 排版与列宽对齐规范

- 正文列宽 `min(660, 宽 − 32)` 居中（阅读 = 编辑，同一公式）。
- 段落样式 → M3 角色（已在 `EditorTypography`）；引用底色 `surfaceContainerHigh`。
- 图片：宽 = 正文列宽、圆角 12dp、段距 10dp、**旋转只重排图片不动文本**。
- 卡片：`DiaryCard`（16dp 圆角 + `surfaceContainerLow` + 1dp 描边，无阴影）；
  hero 卡内边距 14dp、条目卡 12dp。

### 步骤 6 · 无障碍与打磨

- 条目卡：整卡一条语义 + `CustomAccessibilityAction`「编辑」「删除」；多选态 `isSelected`。
- 顶栏每个动作 `contentDescription`；格式栏按钮 active 加 `isSelected`。
- 待办勾选框可点（`Role.Checkbox` + 状态描述）。
- 大字号复测（1.3× / 2.0×）：正文不裁切、输入区最小高 × body 系数。

---

## 4. 不变量（改的时候不许破坏）

| # | 不变量 | 为什么 |
| --- | --- | --- |
| 1 | `content_json` 的字节**一个都不改** | 跨端互通（`app/src/test/resources/golden/` 有字节级断言） |
| 2 | 字号**从不落库**，只存语义样式 | 两端字体梯级不同（Apple HIG vs M3），落库就分叉 |
| 3 | `start_time_utc` **永远写入**，`auto_time` 只是显示开关 | `day_key` / `created_utc` / 排序都依赖它 |
| 4 | 只有**新建片段**才取位置；已有片段只能改精度 | 与 iOS 的 `canRelocate` 一致，也是产品决策 |
| 5 | 保存**不关闭**路由（回阅读态） | 用户要能看到刚写的内容 |
| 6 | 新建片段的时间戳 = **点「写日记」的时刻** | iOS 用的是"打开页面的时刻"，会让"看一眼再写"产生错位（有意偏离） |
| 7 | 编辑态**忽略**数据变更通知 | iOS 有个坑：写库通知会把 `isRead` 复位成阅读态，正在编辑的内容被踢掉 |
| 8 | 阅读列宽 = 编辑列宽 | 进出编辑卡片时文字不能左右跳 |
| 9 | 格式栏 9 键**固定顺序**，引用不在段落菜单里 | 与 iOS 逐项一致 |
| 10 | 「今日」徽标与「写日记」入口**只在当天**出现 | 历史日期只能改、不能新增 |

---

## 5. 下沉为纯函数 + 单测清单

按本项目的惯例：**凡是能写成纯函数的都不放进 Composable**。日记页新增/改动的部分里，
下面这些应该在 JVM 上断言（不依赖 Compose、不依赖 Android）：

| 目标 | 类型 | 断言点 |
| --- | --- | --- |
| `DiaryTopBarState.of(state)` | 纯函数：`State` → 顶栏三态 + 每个动作的 enabled/active | 阅读/编辑/多选各一条；「写日记」仅当天；多选态的删除键在空选时 disabled |
| `DiarySearch.findMatches` | 已有 | 补：跨段落命中（iOS 只计数不高亮）、大小写、CJK 分词 |
| `ShareLongImage` 的版面常量 | 纯数据 | 宽 720dp、页边距 56、卡圆角 26、内边距 30×28、引用左色条 5 |
| `DiaryPageViewModel` 的状态迁移 | Robolectric（已有同类） | 点卡片 → `isRead=false` 且 `editingIndex` 正确；长按 → `selectMode=true`；删除后回到阅读态且选中清空；编辑态收到 `refresh()` 不改变 `isRead` |

> 第 4 行是本轮**最该补**的一条：§1.1 那三条断链的存在，正是因为状态迁移没有被测过。

---

## 6. 最容易做错的 10 条

1. **把 `isRead` 和 `editingIndex` 当成两个独立状态** —— 它们是同一个状态机的两面：
   `isRead = (editingIndex == null)` 只在"阅读/编辑"之间成立，多选是**第三个维度**（`selectMode`）。
2. **`verticalScroll` + `animateScrollToItem` 混用** —— 前者没有 item 概念，命中定位必须换 `LazyColumn`。
3. **编辑态被写库通知打断** —— 见不变量 7。
4. **格式栏折行** —— 单行 + 横滑（`04-diary.md` §3.3 的宽度预算）。
5. **键盘弹起后光标被挡** —— 四个触发点都要接（打字 / 格式栏位置变化 / 键盘高度变化 / 输入区高度变化）。
6. **点格式栏收键盘** —— 每个按钮点完要把焦点还给输入框。
7. **图片按 `fillMaxWidth` 但不跟列宽** —— 宽窗口下会撑破正文列。
8. **多选态删空当天全部片段后关闭路由** —— 应该停在阅读态显示空态。
9. **分享长图不跟随主题** —— 深色下渲染的必须是深色长图。
10. **把 `selectMode` 的 `clearAndSetSemantics` 漏掉** —— 多选态每张卡要读作"已选中/未选中"。

---

## 7. 验收清单（做完逐条打勾）

**顶栏与导航**
- [ ] 阅读态顶栏：`←` · 搜索 · 写日记（仅当天）· 分享
- [ ] 编辑态顶栏：`←`（拦截确认）· 插图 · 放弃 · 保存（active）
- [ ] 多选态顶栏：`close` · 「已选择 N 项」· 删除（error 色，空选禁用）
- [ ] 滚动时顶栏不动；键盘弹起时顶栏不被推出屏幕
- [ ] 系统返回：多选 → 阅读；编辑 → 确认；阅读 → 关闭路由

**三条断链**
- [ ] 点片段卡 → 进编辑，且载入的是**该片段**的内容
- [ ] 长按 400ms → 进多选，卡片出现勾选圈 + 2dp `primary` 描边
- [ ] 多选删除 → 破坏性确认 → 删除 → 回阅读态；删空后停在阅读态显示空态
- [ ] 点搜索图标 → 搜索栏出现；输入 → 命中高亮（`primaryContainer`）；上/下一个能跳转并滚到卡片
- [ ] 点分享 → **立刻**出现面板；预览正确；「分享…」与「保存到相册」都可用
- [ ] 分享渲染失败 → 关面板 + Snackbar

**排版**
- [ ] 阅读列宽 = 编辑列宽 = `min(660, 宽 − 32)` 居中
- [ ] 空的一天：hero 卡常驻 + 「这一天没有留下日记。」
- [ ] 历史日记：顶栏没有「写日记」
- [ ] 图片：宽 = 列宽、圆角 12dp、段距 10dp；旋转屏幕后图片重排、文本与光标不动
- [ ] 地点行：左状态右动作；编辑已有片段没有「重新获取位置」
- [ ] 「放弃修改？」在内容为空时不弹；插入图片后放弃要清理孤儿文件

**无障碍**
- [ ] TalkBack：条目卡读出内容 + 「编辑」「删除」自定义动作
- [ ] 待办勾选框可点、有状态描述
- [ ] 大字号（1.3× / 2.0×）：正文不裁切、格式栏不折行、光标不被挡

**回归**
- [ ] `./gradlew testDebugUnitTest` 全绿（含新增的状态迁移用例）
- [ ] 两个跨端校验脚本通过（`content_json` 与 `.jdiary` 未受影响）
