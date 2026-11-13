# 04 · 日记（阅读 / 编辑 / 多选 / 分享）

> 日记页是**全屏路由**（不是导航栈里的一层），一个页面四种形态：阅读 / 编辑 / 多选 / 分享面板。
>
> 证据：`docs/ios-reference/v3-diary.md`（1399 行，含 `file:line`）· 设计稿 [`mockups/png/03-diary.png`](mockups/png)、
> [`07-extras.png`](mockups/png) · 代码：`ui/diary/**`

---

## 1. 呈现方式与路由

```
RootScreen
 ├─ 四个标签页（NavigationBar / Rail）
 └─ 全屏覆盖：DiaryPageScreen(dayKey, mode)
       ├─ 阅读态  ──点卡片──►  编辑态（编辑已有片段）
       ├─ 阅读态  ──点「写日记」/ 深链──►  编辑态（新建片段）
       ├─ 阅读态  ──长按卡片──►  多选态
       ├─ 阅读态  ──点分享──►  ShareSheet（ModalBottomSheet）
       └─ 编辑态  ──返回──►  「放弃修改？」──确认──► 阅读态
```

| 规则 | 说明 |
| --- | --- |
| 进入 | 首页当日卡片 / 首页「**继续写日记**」（**进阅读态**）/ 首页空态「写日记」（进编辑态）/ 搜索命中项 / 深链 `justdiary://editor?dayKey=` |
| 首页的两个入口 | ⚠️ 2026-09-28 用户返工：这一天**已经有内容**时，点「继续写日记」先去**阅读态**（先让用户看到写过的东西），要接着写再点顶栏的「写点什么」；只有**空态**的「写日记」直接进编辑态（没有内容可读）。这与 iOS 的 `openEditor`（面板按钮直接进编辑器）是**有意的偏离** |
| 关闭 | 返回键或顶栏返回；关闭时 `bumpDiaryVersion()` 让首页与足迹刷新 |
| 状态复位 | 数据变更通知**不得**把编辑态重置为阅读态（iOS 有个坑：`.diaryVersionChanged → load()` 会把 `isRead` 复位）。Android：编辑态忽略该通知，或只刷新 `startTimeUtc` 不碰 `isRead` |
| 设置 / 回前台的变化 | 设置保存一次（`SettingsStore.version` +1）与**每次回到前台**（`AppForeground.tick`）都要 `DiaryPageViewModel.refreshSettings()` 重算 `todayKey` —— 编辑态与待确认的保存**一律不动**。少了这一条，"用户在编辑页切后台待了一会儿"或"改了换日时刻"之后，保存时的跨日确认会**少问一次**，内容悄悄落到另一天 |
| 无未保存内容时 | 直接关闭，不弹确认 |
| 有未保存内容时 | 返回 → 拦截 → 「放弃修改？」（破坏性确认） |

---

## 2. 阅读态

### 2.1 结构

```
┌──────────────────────────────────────┐
│ ←            [搜索] [编辑] [分享]      │ 64   ← TopAppBar
├──────────────────────────────────────┤
│  ┌────────────────────────────────┐  │
│  │ 9月8日 周二  [今日]             │  │      ← Hero 卡（常驻）
│  │ 21:40   1 个片段                │  │
│  │ 📍 浦东新区 · 上海市 · 中国       │  │
│  └────────────────────────────────┘  │
│  ┌────────────────────────────────┐  │
│  │ 🕐 21:40                        │  │
│  │ 📍 浦东新区 · 上海市 · 中国       │  │      ← 条目卡（每段一张）
│  │ 字体样式自检                     │  │
│  │ 这一段是正文…                    │  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
        ← 列宽 min(660, 宽 − 32) 居中 →
```

### 2.2 顶栏

M3 `TopAppBar`（64dp），**钉在页面顶部，不进滚动区**。

| 形态 | 左侧 | 右侧动作 |
| --- | --- | --- |
| **阅读态** | 返回 | `search`（页内搜索，active 时高亮）· `edit` 写日记（**仅当天**）· `share` |
| **编辑态** | 返回 | `image` 插入图片 · `history` 放弃修改 · `check` 保存（**active：`primary` 前景**） |
| **多选态** | `close` 关闭 | `delete`（`error` 色），标题栏显示「已选择 N 项」 |

- 「写日记」只在 `actualDayKey == 今天` 时出现。非今天的日记**顶栏没有写入入口**，
  且编辑已有片段受 `allowHistoryEdit` 开关约束（关时历史日期只读）。
- 顶栏空白处**可点，用于收起键盘**（编辑态）。

### 2.3 Hero 卡（常驻，空的一天也在）

```
9月8日 周二  [今日]        ← 20sp / medium；「今日」胶囊 12sp + primary 底，仅当天
21:40   1 个片段           ← 13sp / onSurfaceVariant
📍 浦东新区 · 上海市 · 中国  ← 13sp，最多 2 行
```

- **空的一天**：元信息行替换为「这一天没有留下日记。」（**没有独立的空状态视图** —— 这是 iOS 的设计，
  它保证"这一天有没有内容"和"这一天是哪一天"永远在同一张卡里回答）。
- 「N 个片段」的 N 是当天的 `edit_block` 数量，N = 0 时不显示。
- `auto_time` 关闭时不显示首条时间（**仅显示层**，`start_time_utc` 照常入库）。

### 2.4 条目卡

```
┌─────────────────────────────────────┐
│ 🕐 21:40                            │ ← 13sp bold / primary（仅 auto_time 开）
│ 📍 浦东新区 · 上海市 · 中国           │ ← 12sp / onSurfaceVariant，最多 2 行
│                                     │
│ 字体样式自检                          │ ← 段落样式（见 §4.1）
│ 这一段是正文…                         │
└─────────────────────────────────────┘
  12dp 内边距 · 16dp 圆角 · surfaceContainerLow
```

- 卡片之间 12dp 间距；点击整卡（含正文）→ 进编辑态；**长按 400ms** → 进多选态。
- 卡片背景 `surfaceContainerLow`；**不用阴影**（iOS 的 `diaryCard` 用 `shadowColor @14%`，
  Android 换成 tonal elevation）。
- 高对比度开启时：卡片描边 0.5dp → **1.5dp `onSurfaceVariant`**。

### 2.5 段落与行内样式（阅读态 = 编辑态）

| 段落样式 | M3 角色 | 字号 / 行高 | 渲染 |
| --- | --- | --- | --- |
| `title` | `headlineMedium` | 28 / 36 | 普通 |
| `heading` | `titleLarge` | 22 / 28 | 普通 |
| `body` | `bodyLarge` | 16 / 24 | 普通 |
| `quote` | `bodyMedium` | 14 / 20 | 底 `surfaceContainerHigh`、圆角 4dp（M3 `extraSmall`）；文字内缩 **16dp**、纵向内边距 6dp；**左侧 4dp 竖条**（`outlineVariant`，距列左 6dp、上下各让 5dp、圆角 2dp）；右边再留 10dp（`QuoteTrailingPadding`，编辑态挂不上） |
| 列表 | 同 `body` | 16 / 24 | 18dp 标记槽 + 10dp 间距 → 正文起点 **28dp**；圆点 `onSurfaceVariant`、直径 **8dp**（平台 `BulletSpan` 的直径），图形在**首行行盒里垂直居中** |
| 待办（未完成） | 同 `body` | 16 / 24 | **18dp 空心勾选框**（圆角 2dp、描边 2dp `onSurfaceVariant`）+ 文字 |
| 待办（已完成） | 同 `body` | 16 / 24 | 18dp `primary` 实底 + `onPrimary` 对勾 + 文字删除线 + `onSurfaceVariant` |
| 居中 | 继承段落样式 | — | `textAlign = Center` |

- 行内样式：`bold` 700 / `italic` / `strikethrough` / `underline` 可叠加。
- **待办勾选框按 M3 `CheckboxTokens`**：容器 18dp、圆角 **2dp**、未选中 2dp `onSurfaceVariant` 描边、
  选中 `primary` 底 + `onPrimary` 对勾、图标 18dp。选中与未选都**只用形状 + 颜色**，不靠颜色单独承载。
- **列表 / 待办是悬挂缩进**：换行后的第二行也从 28dp 起（编辑态 = `MarkerSpan` +
  `LeadingMarginSpan(0, 28dp)`，阅读态与预览 = 共享的 `MarkerRow`）。
- **段落间距 8dp**（`BlockGeometry.ParagraphGap`）：编辑态 / 阅读态 / 首页日面板预览**三处共用**，
  列表项间距也是 8dp（原先阅读态是 4dp）；行高用 M3 角色的官方值（见 `01-design-system.md` §2.4）。
- **三处共用同一份几何**（`ui/diary/BlockGeometry.kt`）—— 上面这张表就是"编辑时看到的"，
  编辑态与阅读态不再各画各的。

### 2.6 图片

| 项 | 规则 |
| --- | --- |
| 宽度 | **= 正文列宽**（`fillMaxWidth` + `aspectRatio`，不设最大高度）；**编辑态同样铺满**，库里存的 `w/h` **只当比例用**（`ImageDisplaySize` 不再按"自然宽度"封顶） |
| 圆角 | 12dp（`medium`） |
| 最小尺寸 | 高 ≥ 40dp（`ReadLayout.IMAGE_MIN_HEIGHT`）；宽恒为列宽 |
| 段距 | 上下各 10dp |
| 插入位置 | 光标处，作为独立段落（前后自动换行），居中 |
| 旋转屏幕 | **只按新列宽重新适配图片**，文本与光标位置不动 |
| 缺失文件 | 保留占位高度、不重排布局 |
| 点击 | 全屏预览（下滑 / 关闭退出；双击在「适宽 / 原始」间切换） |

> **"图片按列宽、旋转不动文本"是一条硬规则。** iOS 的 B20 缺陷就是横竖屏切换后图片不重排。

### 2.7 页内搜索（阅读态）

点顶栏 `search` → **悬浮在内容之上**（钉在顶栏下方）出现一条搜索栏：

```
[🔍 字体                       1/3  ⌃  ⌄]
```

- 命中计数 `当前序号 / 总数`；两个步进按钮（上一个 / 下一个）跳转并滚动到命中卡片。
- 命中文字高亮**分两级**（2026-09-27 用户反馈后补的，iOS 只有一级）：

  | | 底色 | 字色 |
  | --- | --- | --- |
  | **当前命中**（`当前序号` 指向的那一处） | `primary` | `onPrimary` |
  | 其余命中 | `primaryContainer` | `primary` |

  一级高亮时，同一张卡里有多处命中就看不出"现在看的是哪个"（点「下一个」页面滚了，但三处长得一模一样）。
- **计数与高亮必须用同一套算法**（iOS 是两套：跨 chunk 的命中只计数不高亮 —— 不要照抄）。
- 输入为空时只留输入框（计数与步进收起，文案不清除）。

> **2026-09-27 修正（用户实测反馈）**：搜索栏**不进滚动内容**，改为悬浮层（`DiarySearchBarOverlay`）。
> 本文件原先按 iOS `DiaryPageView.swift:250-252` 直译成"插在 Hero 上方"，但 iOS 里它跟着
> `ScrollView` 一起滚 —— 点「下一个匹配」跳到命中处以后，搜索栏连同计数与两个步进键都被顶出屏幕，
> 看不到"第几个 / 共几个"、也没法再点下一个，搜索就失去了意义。
> 现在的实现：列表在它下面滚动（`contentPadding.top` 让开 `52 + 8×2` dp），栏本身用页面底色铺满整宽 +
> `shadowElevation 2dp`，不透明所以不会在圆角周围露出底下的内容。

### 2.8 多选态

- 进入：长按任意条目卡 400ms。
- 顶栏换成「`close` + 已选择 N 项 + `delete`」。
- 卡片左侧出现 20dp 勾选圈；选中的卡加 **2dp `primary` 描边**。
- 点卡片 = 切换选中；点 `close` 或系统返回 = 退出多选。
- 点 `delete` → 「删除选中的 N 段日记？」（破坏性确认）→ 删除 → 回到阅读态。
- 删空当天的全部片段后，页面留在阅读态并显示空态（**不自动关闭路由**）。

---

## 3. 编辑态

> **进度（2026-09-27）**：§3.1–§3.7 已落地并在真机逐条实测（见
> [`../migration-progress.md`](../migration-progress.md) 的 **R28**）。三处**有意的平台化**：
> 1. **格式栏在布局里、不覆盖正文**（规范原话是"悬浮在键盘上方"）：它仍是 28dp 圆角 + 阴影的
>    悬浮面板，但占自己的高度 —— 于是 §3.5 那条 caret-not-hidden 的**结果**（光标永不被栏挡住）
>    由结构保证，不再需要四个触发点上的手工滚动；
> 2. **正文自己滚**（`EditText` 内部滚动，而不是外层 Compose 滚动 + 手算光标位置）：
>    键盘弹起时 Android 会把光标带进可见区；
> 3. **§3.2 的"空行只改接下来输入的样式"**由两层兜住：富文本层把段落属性挂在**终止换行**上
>    （`SPAN_INCLUSIVE_INCLUSIVE`），模型层在"空行开始输入"时把样式接过来
>    （`EditorModel.carryOverStylesWhenTyping`）。

### 3.1 编辑卡

```
┌─────────────────────────────────────┐
│ ( 🕐 21:40 )                        │ ← 时间胶囊：primaryContainer 底，13sp bold primary
│ 📍 浦东新区 · 上海市 · 中国           │ ← 地点胶囊（见 §3.2：整条只有**一枚**）
│                                     │
│ 开始记录这一刻的想法…|                 │ ← 提示语 = 输入框自己的 hint，与光标同一行
│ 这一段是正文…                         │
└─────────────────────────────────────┘
  12dp 内边距 · 16dp 圆角 · 与阅读卡同一内边距
```

- **时间胶囊**仅在 `auto_time` 打开时出现。文案 = 该片段的 `start_time_utc`，跟随系统 12/24 小时制。
- **输入区最小高度 = 160dp × 正文字号系数**（AX5 时约 240dp）。
- 占位文案：编辑已有片段 → 「修改这段内容…」；新建片段 → 「开始记录这一刻的想法…」。
- **占位文案是 `EditText` 自己的 `hint`，不是另起一块文本**（2026-09-28 用户返工）：

  | 项 | 规则 |
  | --- | --- |
  | 载体 | `EditText.hint`，`hintTextColor = onSurfaceVariant`，字号 = 正文 |
  | 位置 | 与光标**同一行同一列**（提示语就画在"文字将要出现的地方"） |
  | 光标 | 在输入区**顶部**（`gravity = TOP or START`），不垂直居中 |
  | 消失 | 一有内容（含只插了一张图）就消失 |

  > 原先提示语是一块单独的 `Text` 叠在输入区顶部，而 `EditText` 从宿主主题继承来的 gravity
  > 把第一行**垂直居中**了：真机实测提示语在顶部、光标在输入区正中间，两者相差 **65dp**，
  > 看上去就是"提示语单独占了一块空间"。

### 3.2 地点行（**一枚胶囊**，状态与动作是同一个元素）

> 本节 2026-09-28 按用户返工重写：原先是"左状态 + 右按钮"两块拼起来，与 iOS 不一致
> （iOS 从头到尾只有一个 `locationChipLabel`，见 `DiaryPageView.swift:610-682`）。

| 情形 | 胶囊文字 | 颜色 | 点击 |
| --- | --- | --- | --- |
| **新建片段**，尚未取到位置 | `位置获取中…` | `primary` | —（静态） |
| **新建片段**，还没拿到位置 | `重新获取位置` | `primary` | 取一次位置（未授权则先申请权限） |
| **有位置**（新建或编辑） | 该位置文本，单行省略 | `primary` | 打开精度菜单 |
| **编辑已有片段**，该片段无地点 | `未记录地点` | `onSurfaceVariant` | —（静态） |
| `auto_loc` 关且无位置 | 整行不显示 | — | — |

- 胶囊的样子照 iOS `locationChipLabel`：`location.fill` 12dp + 文本 13sp、间距 4dp、
  内边距 10×5dp、整枚刷 `primaryContainer` 底。
- **没有单独的「显示精度」文字按钮**：点胶囊本身就是打开精度菜单。
- 精度菜单（浮层，不新开窗口 → 不收键盘）：各档精度；**新片段**在末尾多一条分隔线 +
  「重新获取位置」（iOS `canRelocate`，`DiaryPageView.swift:472-480`），已保存的片段没有这一条。
- **位置只在新建片段时获取一次**。保存之后该片段不再支持"重新获取"，只能调显示精度。
- 无地址视为无地点（`locText` 为空即无地点）。
- **各档精度的地址文案**：新取到的位置走 `LocationResolver.textFromPlacemark`，已有片段从地区列重建
  走 `textFromRegion`。其中「区县」一级**优先取 `subLocality`**（中国的「南山区」在这一栏），
  **没有才退到** `subAdministrativeArea`（美国是 county）—— 此前只看后者，中国常常只显示到市，
  与"按地区列重建"的文案对不上（iOS 同处已修）。
- 保存时若 `auto_loc` 打开且仍无位置 → **先弹「未获取到位置」**（标题 `editor_location_retry`、
  正文 `editor_location_missing_msg`、确认键「仍然保存」`editor_save_anyway`），确认后
  **延迟 0.3s** 再走跨日判定（iOS `DiaryViewModel.swift:251-264`）；保存后这个片段再也补不上位置。
- **取不到位置的三条兜底**（2026-09-28 真机返工，`LocationService`）：

  | 手段 | 说明 |
  | --- | --- |
  | 新鲜缓存点 | 2 分钟内的系统缓存点直接用（iOS `liveUpdates()` 首帧给的就是它） |
  | 两条来源赛跑 | GMS 融合定位 ∥ 平台 `LocationManager`（每个可用 provider 一个请求），谁先回来用谁 |
  | 过期缓存兜底 | 实时取点失败时，用 30 分钟内的缓存点；再旧就宁可没有位置 |
  | 反地理编码重试 | 4 次（400ms / 900ms / 1800ms 退避）：真机上系统会**中途重启厂商定位服务**，正在跑的 `getFromLocation` 会拿到 `DeadObjectException`（服务重启约 500ms，刚起来的 geocoder 头几秒还会返回空地址） |
  | 回前台补一次 | 新建片段 + `auto_loc` 开 + 还没位置 + 没有正在跑的请求 → 回到前台时再取一次 |

### 3.3 格式栏

**贴键盘上方 8dp 的一条悬浮 Surface**（键盘收起时落到手势条上方 24dp）。

```
┌──────────────────────────────────────────────┐
│ [段落样式] [居中] │ [列表] [引用] [待办] │ [B] [I] [S] [U] │
└──────────────────────────────────────────────┘
   56dp 高 · 28dp 圆角 · surfaceContainerHigh · shadow-2
```

**按钮顺序固定，与 iOS 逐项一致**（9 项 + 2 条分隔线）：

| # | 图标（Material Symbols） | 动作 | active 判定 | 禁用条件 |
| --- | --- | --- | --- | --- |
| 1 | `format_size` + 下拉箭头（宽 46dp） | 段落样式菜单：**大标题 / 小标题 / 正文** | **只认大标题 / 小标题**（`EditorBlockStyle.lightsStyleMenu`）——引用、列表、待办、图片与正文都不点亮 | — |
| 2 | `format_align_center` | 居中开关 | 段落居中 | 列表 / 引用 / 待办 / **图片行**任一激活时禁用 |
| — | 分隔线（20dp 高） | — | — | — |
| 3 | `format_list_bulleted` | 列表开关 | 是列表 | — |
| 4 | `format_quote` | 引用开关 | 是引用 | — |
| 5 | `checklist` | 待办开关 | 是待办 | — |
| — | 分隔线 | — | — | — |
| 6 | `format_bold` | 粗体 | 选区/光标处有粗体 | — |
| 7 | `format_italic` | 斜体 | 同上 | — |
| 8 | `strikethrough` | 删除线 | 同上 | — |
| 9 | `format_underline` | 下划线 | 同上 | — |

- 尺寸：按钮 40 × 40dp（**触控目标 48dp**）；图标 20dp；active = `secondaryContainer` 胶囊底 + `onSecondaryContainer` 前景；disabled = `alpha 0.38` + 不响应点击。
- **引用有自己的按钮，不在段落样式菜单里**（菜单只有三项）。
- **第 1 个按钮是「菜单」而不是「开关」，必须带下拉指示**：宽 **46dp**，图标 18dp + `expand_more` 14dp。
  只放一个 `format_size` 图标时用户看不出它会展开展开菜单（iOS 用的也是
  `textformat.size` + `chevron.down`）。

### 格式栏的宽度预算（决定"一行放得下"还是"要横滑"）

| 元素 | 宽度 |
| --- | --- |
| 段落样式（菜单按钮） | 46dp × 1 |
| 其余 8 个按钮 | 40dp × 8 = 320dp |
| 2 条分隔线 | 1dp + 两侧各 2dp = 5dp × 2 = 10dp |
| 格式栏自身左右内边距 | 4dp × 2 = 8dp |
| **合计** | **384dp** |

- 412dp 手机：可用宽 = 412 − 2×8（外层内边距）= **396dp ≥ 384** → 一行放得下。
- ≤ 360dp 的窄机：放不下时**横向滚动**，**绝不换行** —— 折行会让「下划线」掉到第二行、
  整条高度翻倍，还会顶掉输入区的可视高度。
- 容器用"放得下就居中、放不下就从头排"（Compose 里是
  `horizontalScroll` + 内容 `widthIn(min = 可用宽)` 后 `wrapContentWidth(CenterHorizontally)`，
  或简单点：`BoxWithConstraints` 判断后决定 `Arrangement`）。**不要**无条件居中 ——
  溢出时会把两端的按钮都裁掉一半。
- **点任意按钮后焦点回到输入框**（`focusRequester.requestFocus()`）—— 连点排版不会收键盘。
- 竖屏与横屏的按钮列表**完全相同、顺序相同**（iOS 曾把横屏改成竖排贴右，9 个按钮 454dp > 402dp 可用高度被裁掉，已废弃）。

#### 按钮功能规则（用户 2026-09-28 逐条定稿，**已实现**）

> 用户原话："现在开始改日记页编辑模式的样式栏按钮功能。这块需要对齐 iOS 版的按钮功能……
> 现在我提出几点，你做好记录，设计稿里面的内容可能不太正确或者不够完整，**以我说的为主，
> 我没说到的以 iOS 版为主**。"下面六条就是对这六句话的落地口径，实现全在
> `EditorModel`（纯逻辑）+ `SpannableBridge`（span）+ `EditorTextArea`（光标），
> 逐条有单测（`EditorModelTest` 的 `rule 1..6` 段、`EditorStateTest`、`EditorCursorLayoutTest`）。

| # | 规则（用户原话要点） | 实现口径 | 落点 |
| --- | --- | --- | --- |
| 1 | 粗体 / 斜体 / 删除线 / 下划线**可以同时应用** | 四种行内样式是**叠加**的：`StyleSpan` 只承载"字重+字形"一个 Typeface（粗+斜合并成一个 `BOLD_ITALIC`，与 Google `SpanStyle(fontWeight, fontStyle)` 同义），删除线与下划线是两个独立的 `CharacterStyle`，因此四种可以同时挂在一段文字上 | `SpannableBridge.applyInlineSpans`、`EditorTextState.applyPendingFlags` |
| 2 | 大小标题 / 正文 / 列表 / 待办 / 引用**互斥**，"**先取消其他样式再启用选中的**" | 六选一（`BlockFormat`）：应用任何一项都先**把本行的段落格式清回正文**（标记、引用、标题一起清），再写上目标。顺序与 iOS `restyle(..., .body, cancelCenter:)` 一致 | `EditorModel.applyBlockFormat` |
| 3 | 大小标题 / 正文的范围是"选中之后输入的内容"；列表 / 引用 / 待办是**一整行**且**回车之后也不取消**；本行已有内容时**先取消本行格式再应用**；取消格式只取消**当前行**，不回头改以前应用过的行 | ①作用域 = 光标所在行（有选区时是选中的每一行）；②空行上应用 = 记在模型里（"待输入样式"），开始输入时由 `carryOverStylesWhenTyping` 续上；③回车后新行继承同一格式（标记 / 引用 / 标题 / 居中全继承）；④取消只作用于传入的行区间 | `EditorModel.applyBlockFormat` / `repairAfterEnter` / `carryOverStylesWhenTyping` |
| 4 | 居中：有内容 → 整行居中；空行 → **光标居中**；取消时同理。**居中的空行上再按回车不执行** | 空行的居中就是段落的 `AlignmentSpan`（光标 x = `Layout.getPrimaryHorizontal`）；**末尾空行**没有字符可挂 span，改由视图级 `gravity` 表达。回车拦截 = "本行是空行 **且本行居中**"（用户 2026-09-28 定稿：*"对齐 iOS 版，居中的时候不让有空行。但是如果不是居中的时候，是不做限制的"*）；**空的引用行**也吃回车并顺手结束引用（iOS 同）。⚠️ **居中不随换行延续**：居中的作用域就是"本行"，回车后的新行是左对齐（见下面的用词说明） | `EditorModel.setAlignment` / `blocksNewline` / `endQuoteOnBlockedNewline`、`SpannableBridge.tailCentered`、`DiaryEditText.dispatchKeyEvent` + `EditorTextState.onUserText` |
| 5 | 居中与列表 / 待办 / 引用**互斥**：启用那三类时自动取消居中，且**选中状态下居中按钮置灰**；取消那三类**不恢复**居中 | `BlockFormat.cancelsCenter`（引用 / 列表 / 待办为真）在应用时一并清 `align`；格式栏的 `centerEnabled` 由**整个选区**算出（任何一行是那三类就置灰），`EditorModel.setAlignment` 里再 guard 一次；取消时不写回 `align` | `EditorModel.applyBlockFormat` / `centerAllowed`、`DiaryFormatBar.centerEnabled` |
| 6 | 切大 / 小标题 / 正文时**光标大小跟着变**；列表 / 引用 / 待办按各自的字号 | 光标高度 = 光标那一行的行高（`Editor.updateCursorPosition`）。**有字符的行**由该行的 `BlockMetricsSpan` 决定（列表 / 待办按正文画）；**末尾空行**只能由视图级 `textSize` 给出 —— 所以每一段都显式挂 `AlignmentSpan`，让视图级 `gravity` 只影响末尾那一行 | `SpannableBridge.toSpannable` / `tailDefaults`、`EditorTextArea` 的 `update` |

### 样式呈现：与 Google 官方（Compose / Material 3）的逐项对照

用户 2026-09-28："同时，对于样式，你需要参考谷歌官方的 SDK 和示例。"
下表把编辑器的每一种"样式呈现"对到官方口径上 —— **语义取官方的做法，载体取平台等价物**
（编辑器的正文是 `EditText` + `Spannable`，不是 Compose 的 `AnnotatedString`，见 `docs/research-compose-rich-text-apis.md` §2.4/§2.5）。

| 呈现项 | Google 官方口径 | 本项目（平台等价物） |
| --- | --- | --- |
| 加粗 + 斜体 | `SpanStyle(fontWeight = Bold, fontStyle = Italic)` —— **一个 span 同时带字重与字形**，不是两个互斥属性 | `SpannableBridge.applyInlineSpans` 把 run 写成**一个** `StyleSpan(Typeface.BOLD_ITALIC)`（粗+斜合并），因此四种行内样式可以任意叠加（规则 1） |
| 删除线 + 下划线 | `TextDecoration.combine(listOf(LineThrough, Underline))` | 两个独立的 `StrikethroughSpan` / `UnderlineSpan` 共存（平台把两者画成独立的 Paint 标志），语义与 `combine` 等价 |
| 居中 | `ParagraphStyle(textAlign = TextAlign.Center)` | 段落的 `AlignmentSpan.Standard(ALIGN_CENTER)`；**左对齐也显式挂 `ALIGN_NORMAL`**（把视图级 gravity 让给末尾空行，见规则 6） |
| 字号 / 行高 | `SpanStyle(fontSize = …)` + `ParagraphStyle(lineHeight = …)`；字号取 M3 排版角色 | `BlockMetricsSpan`（`MetricAffectingSpan` + `LineHeightSpan.WithDensity`），字号取 `EditorTypography.textStyleFor` 的 M3 角色 |
| 段落样式 → 角色 | M3 Typography：`headlineMedium` / `titleLarge` / `bodyLarge` / `bodyMedium` | 与阅读态**同一张表**（§2.5：编辑 = 阅读） |
| 激活态按钮 | M3 `StandardIconButtonTokens`：未选中图标 `onSurfaceVariant`、选中图标 `primary`（无底）；`FilledTonalIconButtonTokens`：未选中 `secondaryContainer` 底 + `onSecondaryContainer` 图标，选中 `secondary` 底 + `onSecondary` 图标（源码：`material3-android-1.4.0-sources.jar` 的 `tokens/*IconButton*.kt`） | 取**混合**：未选中 = 官方标准型的 `onSurfaceVariant`（无底），选中 = 官方 tonal 型的 `secondaryContainer` + `onSecondaryContainer` 胶囊。⚠️ 与任一款官方 token 都不是逐字一致，这是设计稿 `.formatbar` 与 iOS（iOS 是 `primary` 底 + 白字）之间的折中 —— 若要严格对齐官方 / iOS，把选中的胶囊底换成 `primary` + `onPrimary` 即可（一处常量） |
| 禁用态 | M3 禁用 = 前景 `onSurface` @ **38%**（`DisabledOpacity = 0.38f`，同上源码） | `FormatBarMetrics.DisabledAlpha = 0.38f` ✓ |
| 触控目标 | M3 图标按钮 48dp | 40dp 宽 × 48dp 高：宽度受 §3.3 的"9 键一行放得下"预算约束，纵向补足到 48dp（有意的取法，见 §3.3 注） |
| 提示语 | 占位文案与正文同字号 | `EditText.hint` 挂一个**正文字号**的 `BlockMetricsSpan`：切大标题时 base 字号会为了光标变大，但提示语必须保持正文大小（iOS `RichTextView` 的 placeholder 也用正文曲线画） |

三条容易被误读的细节，都在代码注释里写死了：
- **空行判定**（规则 4）：**只有居中的空行**会被吃掉回车，**不居中的空行完全不受限制**
  （用户 2026-09-28 原话："居中的时候不让有空行。但是如果不是居中的时候，是不做限制的"）——
  想按几个空行就按几个。**空的引用行**另有一条：吃掉回车并**结束引用**
  （用户 2026-10-01 定稿："如果引用状态没有输入内容的话，就取消本次引用，光标位置重置"）。
  带标记的空条目（`• `）**不是空行**：回车在那里的含义是"结束列表"（iOS 同）。
- **引用在编辑态的外观**（原 F1，**已实现**）：一块圆角底 + **左侧竖条** + `LeadingMarginSpan`
  做文字内缩，参数与阅读态、首页预览逐项相同（`BlockGeometry`：`surfaceContainerHigh` /
  4dp 圆角 / 6dp 纵向 / **文字内缩 16dp** / 竖条 4dp 宽 + 距列左 6dp + 上下各让 5dp +
  圆角 2dp / `outlineVariant`）。三个坑记在这里：
  ① **底由 `DiaryEditText.onDraw` 在 `super.onDraw` 之前画**（所有引用段都走这一条，不再是
  `LineBackgroundSpan`）：平台的 `Editor.onDraw` 顺序是"**先画光标、再画行背景 span**"
  （`android-37.0/android/widget/Editor.java:2143-2154`），底挂在 span 上就会把光标整个盖住 ——
  真机实测（2026-10-01）：引用段里打上字之后，光标在 8 张连拍里一张都找不到，而正文 / 大标题行
  同样的操作 3~4 张能找到。顺带修掉了"底复用同一个 `Paint`、第二帧起整块变成竖条那种深灰"。
  ② **末尾空行没有字符可挂 span**：`Layout.getParagraphSpans` 对 `start == end && start > 0`
  的空段落直接返回空数组（`text/Layout.java:3489`），所以文档里还有别的行时，末尾那个空的引用行
  要**补一个终止换行**（`SpannableBridge.PhantomLineSpan`，解析时再丢掉）才挂得上内缩 ——
  不补就是"光标停在列最左边、打第一个字才往右跳 16dp"（用户两次报的就是这个）。
  空文档（模型只有一行）不补：那时文本必须保持为空，提示语才显示得出来，
  而落在位置 0 的零长度 span 平台是认的。
  ③ **段后距不能算进底**（它是烘进 `LineHeightSpan` descent 里的），必须在末行减掉，
  否则底会往下多出一大截、看起来像把下一行也包进来了（用户实测："引用的作用范围现在也不对"）。
  唯一的差异：`Spannable` 没有右边距 span，阅读态与预览右边还有 10dp
  （`QuoteTrailingPadding`），编辑态挂不上 —— 这是三处唯一对不齐的地方。
- **光标高度**：光标高度 = 光标那一行的**行盒高度**。行盒不再少 1px（`ascent` 取整、余数给
  `descent`），因此与 M3 角色的行高逐行对齐。R31 记的"段落最后一行的光标比行高多 8dp"
  **不再是遗留偏差**：段距现在只落在段与段之间那一行（文末最后一段不加），那一行多出来的一截
  就是它下面真正要画的段距本身；它随字号成比例变化，因此规则 6 成立。

### 3.3b 段落 span 的粘性（真机缺陷，2026-09-28）

段落级 span（块样式 / 对齐 / 字号行高 / 引用底）的右端**含本行的终止换行**，
也就是终点正好落在**下一行的行首**。因此它们统一用 **`SPAN_INCLUSIVE_EXCLUSIVE`**
（起点包含、终点不包含，见 `SpannableBridge.ParagraphSpanFlags`）：

- 两头 `INCLUSIVE` 时，用户在下一行行首敲的第一个字符会被**上一段吞掉** ——
  屏幕上那一行还带着上一段的居中 / 字号，而模型里它明明是正文
  （解析按"span 起点 == 行首"判定，所以模型一直是对的）。表现就是
  "看着还居中、其实已经不是居中、取消也取消不掉"；
- 起点仍要包含：在行首插入文字应当留在这段里。

### 3.4 编辑器模型

代码分四层，改动时按这条线找位置：

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 纯模型 | `EditorModel.kt` | 行模型、`content_json` 双向、六选一互斥、作用域、回车与空行规则（**无 Android 依赖，全部可 JVM 断言**） |
| 平台桥 | `SpannableBridge.kt` | `EditorLine` ↔ `Spannable`（块样式 span / 排版 span / 对齐 span / 行内样式 span / 图片 span） |
| 状态机 | `EditorTextArea.kt` 的 `EditorTextState` | 选区、"待输入样式"、回车修补、版本号回写；`DiaryEditText` 只负责按键拦截 |
| **按钮接线** | `EditorFormatActions.kt` | 格式栏九个按钮 → 模型的**唯一**接线点（作用域与方向都在这里定，`EditorFormatActionsTest` 逐个按钮驱动） |

之所以把接线单独成一层：语义在 `EditorModel` 里已经钉死，但"每个按钮传哪一段区间、用不用光标行定方向"
一样会写错，而这段接线原来散在 `EditorScreen` 的六个 lambda 里、只能靠真机验。

| 规则 | 说明 |
| --- | --- |
| **段落格式六选一** | 大标题 / 小标题 / 正文 / 引用 / 列表 / 待办 = `BlockFormat`，同一格属性。应用 = **先取消本行的格式、再应用目标**（`applyBlockFormat`）；方向（开 / 关）由光标那一行决定（引用按整段是否已是引用，与 iOS `toggleQuote` 同） |
| **段落样式作用域** | 光标/选区**覆盖到的整段**。空行只改"后续输入的属性"，不动已有文字 |
| **字符样式作用域** | 有选区 → 整段统一取反（全有则全无）；无选区 → 只影响后续输入；四种样式**可叠加** |
| **段落级改动不得动行内样式** | 换段落样式 / 加去列表 / 勾选待办 / 居中，都**只改"这一段是什么"**：加粗、斜体、删除线、下划线原样跟着走（iOS `restyle`：*preserving bold/italic traits*）。真机缺陷（2026-09-28）：一条"待办 + 四种行内样式"的行切一次小标题，四种样式全丢 —— 原因是重建 `EditorLine` 时只 copy 了 `text`，`spans` 掉在了地上 |
| **互斥** | 居中 与 列表/引用/待办 互斥（按钮置灰，且按**整个选区**判定）；列表/引用/待办三者与三种文本样式互斥。居中的拒绝是**整段生效或不生效**：选区里只要有**一行**是那三类，`setAlignment` 整体不动（不留半吊子结果） |
| **方向 · 列表/待办** | 只看**光标那一行**；有选区时看选区的**起始行**（Android 只给 `getSelectionStart/End`，不暴露焦点在哪一端；iOS 的 `selectedRange.location` 同样是起始端，两边口径一致）。**引用**不同：区间内**全部**已是引用才取消（iOS `toggleQuote`） |
| **回车 · 列表** | 续下一项；在**空列表项**上回车 → 结束列表，回到正文 |
| **回车 · 标题** | 非标记行的回车：**新行继承同样的块样式**（大标题 / 小标题）；**居中不继承**（居中的作用域是"本行"）—— 规则 3 的"回车之后也不取消格式，直到用户取消格式"，**空行也一样**（先在空行上选「大标题」再回车，新行还是大标题） |
| **回车 · 引用** | **跟随 iOS**（2026-11-12 用户确认，R36）：本行**有内容** → 平台正常换行，**新行继续引用**；本行**是空的** → 这次回车被吃掉、连新行都不产生，本行直接变回正文（光标因此回到列最左边）。于是"退出引用"要在一个空引用行上再按一次回车；落库形态可以是 `quote,quote,body`。iOS `RichTextEngine.handleReturn` 是这条口径的来源，Android 以后继续以 iOS 为源 |
| **回车 · 空行** | 本行是空行且**居中** → **吃掉这次回车**（居中的空行不堆叠，规则 4）；本行是空行且**是引用** → 吃掉 + 结束引用（上一行）。按键与输入法两条路都拦（`dispatchKeyEvent` + `onUserText` 退回） |
| **回车 · 段首**（光标在行首） | 拆分成"上面一个空行 + 原来那段被顶下去"。平台会把新插入的换行并进段落 span（字号 / 对齐 / 内缩一起漏到空行上），所以**模型层纠正归属、平台层按模型回写一次**：空行——引用给**正文**（用户 2026-10-01："理论上没有引用的空行"）、大标题 / 小标题**保留**（规则 3）、**居中一律不留**（规则 4）；被顶下去的那一行把样式与对齐**接回来**（否则在标题行首回车会把标题降级成正文，落库就错）。见 `repairSplitAtParagraphStart` + `SpannableBridge.hasLeakedParagraphSpan` |
| **空行的段落格式** | 段落 span 挂不到空行上（**末行**连终止换行都没有），所以模型层每步都做一次"空行把上一版的格式接回来"（`keepStylesForBlankLines`）：删空一行不掉格式、空行上的待输入样式也不会被别处的编辑抹掉 |
| **光标字号与 x** | 光标高度 = 光标那一行的行高：有字符的行由该行 `BlockMetricsSpan` 给（列表/待办按正文）；**末尾空行**由视图级 `textSize` 给（`tailDefaults`），因此每段都显式挂 `AlignmentSpan`，视图级 `gravity` 只影响末尾那一行。光标的 x 由段落 `LeadingMarginSpan` 给 —— 末尾那个**空的引用行**靠"补一个终止换行"（`PhantomLineSpan`）才拿得到内缩，否则会停在列最左边（详见 §3.2 的三条细节） |
| **字号反解** | `content_json` 只存语义样式；反解必须**精确匹配**，否则正文会被写成小标题 |
| **一行一张图** | 图片**永远独占一行**：光标行是空行 → 图片落在这一行（不再多留一张空行）；否则插在光标行**下面**新开的一行。插图后光标落到图片**下面**那一行（没有就补一个空行） |
| **图片居中** | 图片段一律居中（`AlignmentSpan.ALIGN_CENTER`，对应 iOS `PartsCodec.imageParagraphStyle()`：*"centred … never hangs off the left edge of a wide column"*）。这是**渲染规则**，`content_json` 里图片段**不写** `align`（iOS 也不写） |
| **图片宽度** | 编辑器也**铺满正文列宽**：`ImageDisplaySize.fit` 只按 `w/h` 推高度，不再按"自然宽度"封顶 —— 与阅读态的 `fillMaxWidth + aspectRatio` 同一条规则（图片段仍一律居中） |
| **自动时间** | 新建片段的时间戳 = **点「写日记」的那一刻**（iOS 是打开页面的时刻 —— 不要照抄） |
| **无 undo 栈** | 「放弃修改」不可恢复。插入图片后放弃会留下孤儿文件 → Android 要顺手清理 |


### 3.5 键盘与光标

- 窗口：`android:windowSoftInputMode="adjustResize"`；编辑容器 `Modifier.imePadding()`。
- **格式栏位置**：`padding(bottom = if (imeVisible) 8.dp else 24.dp)`，浮在内容之上。
- **光标不被遮挡（caret-not-hidden）**：

```
bottomLimit = 格式栏上沿 − 8dp
topLimit    = 顶栏下沿 + 8dp
delta = if (caret.bottom > bottomLimit) caret.bottom − bottomLimit
        else if (caret.top < topLimit)  caret.top − topLimit
        else 0
if (|delta| > 1) scrollBy(delta)      // 只做"刚好露出来"，不居中
```

触发点：打字 / 移动光标、格式栏出现或位置变化、键盘高度变化（延迟 + 重试）、输入区高度变化。
**四个触发点都要接** —— 少一个就会出现"光标被键盘挡住"。

### 3.6 「放弃修改？」

| 项 | 内容 |
| --- | --- |
| 标题 | 放弃修改？ |
| 正文 | 这段内容还没有保存，放弃后无法恢复。 |
| 左键 | 取消（`TextButton`） |
| 右键 | **放弃**（`TextButton`，`error` 色） |

**新建片段且内容为空时直接关闭，不弹确认。**

### 3.6b 保存前的两个确认弹窗（D3 / D4）

顺序固定，**先 D3 后 D4**（iOS `saveEditor` 同一顺序）：

| # | 触发 | 标题 / 正文 | 确认键 |
| --- | --- | --- | --- |
| D3 | 新建片段 + `auto_loc` 开 + 仍无位置 | 「未获取到位置」/「还没有获取到地点。保存后这条记录将无法再添加位置。」 | **仍然保存** / 取消 |
| D4 | 内容的 `start_time_utc` 算出来的 `day_key` ≠ "今天" | 「跨日编辑提示」/「本次内容将保存为 %s 的日记。保存并退出后，这篇日记将归档。」 | **保存并退出** / 取消 |

- 两个弹窗共用同一份**待写入项**（内容 / 起始时刻 / 位置快照，在点保存那一刻冻结）：
  弹窗期间编辑器还活着，但用户在这期间改的字、调的精度都不进入这一次写入。
- 「取消」= 放弃这次保存（清掉待写入项），**不离开编辑态**。
- D4 确认后落库到那一天，页面跟着**切到那一天**的阅读态 —— 否则用户眼前还是原来那一天，
  而刚写的内容在别处。
- **"今天"的算法**：`DateUtil.dayKeyForUtc(now, dayStartHour)`。设置里改「新一天的开始时间」
  或用户在编辑页切后台再回来时都会重算（见 §1 的"状态复位"一行）。

### 3.7 保存

保存 = 写入 `edit_block`（`content_json` / `start_time_utc` / 地点字段）→ `bumpDiaryVersion()` → 回到阅读态。

- 保存**不关闭路由**（回到阅读态，用户可以看到刚写的内容）。
- 保存后 `start_time_utc` **不再变动**（编辑已有片段不会刷新时间戳）。
- 图片文件先落盘再写库；写库失败要删掉刚落盘的图片。

---

## 4. 分享

### 4.1 入口与流程（一步到位）

点顶栏 `share` → **立刻**弹出 ModalBottomSheet（`skipPartiallyExpanded = true`），
面板内先显示进度，后台线程渲染长图，渲染完替换成预览。

```
顶栏 share
  → renderShareBlocks()
  → ModalBottomSheet（预览列显示"正在生成分享图…"）
  → 后台渲染（离屏 Canvas，2x）
  → 写入 cacheDir/share/JustDiary-<dayKey>.png
  → 预览就位 + 「分享…」/「保存到相册」可用
  → 渲染失败：关面板 + 一条 Snackbar「分享失败」
```

### 4.2 面板内容

| 区块 | 内容 |
| --- | --- |
| 标题 | 「分享长图」 |
| 副标题 | `JustDiary-2026-09-08.png · 1160 × 1680` |
| 预览 | 长图 `scaledToFit`，最大宽 200dp（竖屏）/ 随面板高自适应；12dp 圆角 + 1dp `outlineVariant` 描边 |
| 动作 | 「分享…」（filled）→ 系统 Sharesheet（`ACTION_SEND`，`image/png`，走 `FileProvider`）<br>「保存到相册」（tonal）→ 写入 `Pictures/JustGDiary` |
| 渲染中 | 不确定进度圈 + 「正在生成分享图…」，区块最小高 220dp |

- **面板自己不画关闭按钮** —— 下滑手势或点 scrim 关闭（M3 默认）。
- 分享对象优先**文件 URI**（这样系统面板显示文件名与真实缩略图）。
- 重复分享覆盖同名文件；面板关闭后删除上一次的临时文件（`cacheDir/share/`）。
- **一次性提示（「已保存到相册」/「保存失败」）钉在页面顶部**，不是底部：面板是 M3
  `ModalBottomSheet`（独立窗口，盖住整屏），底部的 Snackbar 会被它整个遮住 —— 真机实测
  "提示根本不出现"。同类提示一律走这个位置。
- 「分享…」按钮**由用户点击触发**系统 Sharesheet（Android 的系统面板是独立 UI，不能内嵌，
  所以不能像 iOS 那样"面板一出来就在下面挂着"）。

### 4.3 长图版面（**2026-09 重做版**，对齐 iOS `ShareRenderer`）

> iOS 在 2026-09 把这一块整个重做过：旧版是「三个大色球 + 满屏渐变 + 一条悬空的蓝色时间轴」，
> 背景花花绿绿把正文对比度拉低、大日期下面又重复一次首条时间、页脚几乎看不见。
> **Android 这一版是照新版复刻的**（`data/ShareLayout.kt` 只算版式并产出绘制指令，
> `service/ImageShareRenderer.kt` 只执行指令）。

**画布**：宽固定 **720px**（不是 1440 —— iOS 的 2× 是 `UIGraphicsImageRendererFormat.scale`，
截图落到像素上就是 720 宽），高按内容撑开。

**版面常量**（逐项对齐 `ImageShareService.swift:28-52`）：

| 常量 | 值 | | 常量 | 值 |
| --- | --- | --- | --- | --- |
| `margin` | 56 | | `cardPadH` / `cardPadV` | 30 / 28 |
| `content` | 608 | | `cardRadius` | 26 |
| `top` | 56 | | `betweenEntries` | 30 |
| `brandH` / `afterBrand` | 34 / 24 | | `partGap` / `looseGap` | 20 / 26 |
| `dateH` / `afterDate` | 74 / 12 | | `beforeFooter` | 46 |
| `metaH` / `afterMeta` | 34 / 26 | | `footerH` / `bottom` | 30 / 48 |
| `ruleWidth` / `ruleHeight` | 64 / 5 | | `entryHeadH` / `afterEntryHead` | 38 / 14 |

**字体**：brand 26 semibold · year 22 · date **58 bold** · weekday 28 · meta 25 · time 27 semibold ·
loc 23 · title 38 bold（行高 54）· heading 32 semibold（46）· body 29（48）· quote 27（44）·
item 29（46）· footer 22（30）。

**配色**（`Palette`，与 `Theme.seed` #2563EB 同源）：

| | 浅色 | 深色 |
| --- | --- | --- |
| text / sub / faint | `#15181D` / `#5B6472` / `#98A1B0` | `#ECEDF1` / `#A7AEBB` / `#6E7683` |
| accent | `#2563EB` | `#7C97FF` |
| 背景 | `#F8F9FC → #EDF0F7` | `#0C0F14 → #131722` |
| 卡片 | `#FFFFFF`，描边 6% 黑，阴影 8% 黑 | `#1A1F29`，描边 7% 白，阴影 45% 黑 |
| 引用底 | `#2563EB` 7% | `#7C97FF` 14% |

背景 = 竖向线性渐变 + **两枚极淡的品牌晕染**（`(720×0.94, −80)` r430 α0.10；`(720×0.02, H×0.44)`
r380 α0.06）—— 只让纯色背景有点呼吸感，不参与构图。

**纵向结构**：

```
y = 56
1) 品牌行：accent 圆点 (56+7, y+17) r7；「一页时光」26 semibold (56+26, y, 468, 34)
                                              年份 22 右对齐 (56, y+5, 608, 26)
   y += 34 + 24
2) 大日期（`date_day_only`：9月8日）58 bold (56, y, 608, 74)
   星期（`weekdayName`：周日）28 与日期**基线对齐**，x = 56 + 日期宽 + 16
   y += 74 + 12
3) 起始时间行（`read_start_time`：8:40 开始写，**只有时间、没有地点**）25
   auto_time 关闭 → 整行不画（只推进 26）        y += 文本高 + 26
4) 品牌色短线 64 × 5 圆角 2.5                    y += 5 + 40
5) 每条记录（无内容且无时间且无地点 → 整条跳过）：
     条目之间 += 30
     时间 / 地点行：时间 (56, y, 160, 38) 27 semibold accent
                   地点 (56 + 时间宽 + 14, y+6, 剩余宽, 30) 23 sub（剩余宽 ≤ 60 时不画）
     卡片：**卡片本体画在这段内容之前**（先卡后面），圆角 26、填充 card、
           描边 2pt（inset 1、半径 25）、阴影 offset(0,6) blur 26
     段间距：title/heading → 26，其余 → 20
6) 页脚：y += 46；细线 (56, y, 608, 1) faint 35%；y += 22
   左「一页时光 · JustADiary」22 medium faint（宽 608×0.6）
   右 dayKey（2026-09-08）右对齐                             y += 30 + 48
```

**分享图上的地点文案**（`LocationResolver.shareText`）：**只从地区列（`country` / `region1..3`）重建**，
`loc_text` 不参与；实际级别 = `min(记录精度, 设置里的「分享位置精度」上限)`，**只能更粗**；
上限只有 隐藏 / 区县 / 城市 / 省份 —— **精确地点与街道在任何设置下都进不了分享图**，
重建为空就不画地点行（不退回原文）。见 `05-search-footprint-settings.md` §3.4。

**每种段落的绘制**：

| style | 画法 |
| --- | --- |
| `title` / `heading` / `body` | 38 bold/54 · 32 semibold/46 · 29/48，text 色 |
| `quote` | 底色盒圆角 16（quoteBg）+ 左侧 5px accent 色条（上下各内缩 10）；文字从 `x+26` 起、宽 `width−46`，27/44 |
| `list` | 每项：accent 圆点 r5.5 `(x+9, cursor + 23 − 3)`；文字从 `x+30` 起，29/46 |
| `todo` | 每项：30×30 复选框（圆角 8）；已完成 = accent 填充 + **白色对勾**（线宽 3.4、圆头圆角），未完成 = faint 2pt 描边；文字从 `x+48` 起，**已完成项用 sub 色**；项间 16 |
| `image` | 高 `max(120, 内宽 × ratio)`；圆角 20；按 aspect-fill 居中裁剪；文件缺失时填 quoteBg 占位 |

**水印与文件名**：「一页时光」出现在顶部品牌行、页脚「一页时光 · JustADiary」；右下角是当天
`dayKey`。文件名 `JustDiary-<dayKey>.png`，写在 `cacheDir/share/`，同一天覆盖同名文件。

---

## 5. 无障碍

| 元素 | 语义 |
| --- | --- |
| Hero 卡 | 「2026年9月8日 周二，21:40，1 个片段，中国上海市浦东新区」 |
| 条目卡 | 整卡一个元素 + `CustomAccessibilityAction`「编辑」「删除」；`isSelected` 在多选态下置位 |
| 顶栏动作 | 「搜索」「写日记」「分享」「插入图片」「放弃修改」「保存」「删除」——全部要有 `contentDescription` |
| 格式栏按钮 | 每个都有 label，active 加 `isSelected` trait |
| 待办勾选框 | 「验证标题层级，已完成/未完成」，可点切换 |
| 图片 | 「图片，宽 660 高 420」+「双击全屏查看」 |
| 动态字体 | 编辑器字号按 §2.3 的逐档封顶；最大档下输入区最小高 × body 系数 |
| 触控目标 | 全部 ≥ 48dp（iOS 是 44pt） |

---

## 6. 数值速查表

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `TOPBAR_H` | 64 | M3 small top app bar |
| 正文列宽 | `min(660, 宽 − 32)` | **阅读 = 编辑** |
| 卡片内边距 | 12dp（阅读与编辑**相同**） | iOS `Spacing.card` |
| 卡片圆角 | 16dp | `large` |
| 卡片间距 | 12dp | |
| Hero 卡内边距 | 14dp | iOS 是 14，不是 12 |
| Hero 日期 | 20sp medium | `titleLarge` |
| Hero 元信息 | 13sp `onSurfaceVariant` | |
| 条目卡时间 | 13sp bold `primary` | |
| 条目卡地点 | 12sp `onSurfaceVariant` | |
| 图片 | 宽 = 列宽（编辑态同），圆角 12dp，高 ≥ 40dp，段距 10dp | 存的 `w/h` 只当比例 |
| 段距 | 8dp（`BlockGeometry.ParagraphGap`） | 编辑 / 阅读 / 首页预览三处共用；文末最后一段不加 |
| 列表 / 待办缩进 | 标记槽 18dp + 间距 10dp = **正文起点 28dp** | 悬挂缩进；圆点 8dp，勾选框 18dp / 圆角 2dp（M3） |
| 引用 | 文字内缩 16dp、圆角 4dp、竖条 4dp、纵向 6dp | 右边距 10dp 只有阅读态与预览挂得上 |
| 格式栏 | 高 56dp，圆角 28dp，按钮 40 × 40dp（触控 48dp） | |
| 格式栏图标 | 20dp | |
| 输入区最小高 | `160dp × body 系数` | AX5 ≈ 240dp |
| 多选长按 | 400ms | |
| 选中卡描边 | 2dp `primary` | |
| 页内搜索栏 | 高 52dp，**悬浮**（钉在顶栏下方，不随内容滚动） | |
| 分享长图宽 | 720dp @2x | |
| 分享面板预览最大宽 | 200dp（竖屏） | |

---

## 7. 与 iOS 的差异（逐条，都是有意）

| # | 项 | iOS | Android | 理由 |
| --- | --- | --- | --- | --- |
| 1 | 顶栏 | 悬浮玻璃圆按钮（44pt 视觉 + 4pt 外扩） | `TopAppBar` 64dp + 标准 IconButton | Material 没有玻璃层 |
| 2 | 正文 / 引用字号 | 17 / 15（Apple HIG） | 16 / 14（M3 `bodyLarge` / `bodyMedium`） | 各按平台规范；字号不落库，互通不受影响 |
| 3 | 待办勾选框 | `checkmark.square.fill` | 18dp 方形 + 对勾，**圆角 2dp / 描边 2dp**（M3 `CheckboxTokens`），选中 `primary` 底 + `onPrimary` 勾 | Material Symbols 的 `check_box` 形态 |
| 4 | 分享面板下半部 | 内嵌 `UIActivityViewController` | 两个动作按钮 → 系统 Sharesheet | Android 的 Sharesheet 是独立系统 UI，不能内嵌 |
| 5 | 分享面板的关闭 | 靠 96 × 96 透明热区盖住系统那个"点不动"的叉 | M3 ModalBottomSheet 自带下滑 / scrim 关闭 | 结构上不需要 workaround |
| 5b | 段落样式 / 精度菜单的宿主 | SwiftUI `Menu`（系统 popover，不抢焦点） | **同窗口浮层**（`AnchoredMenu`），不是 `DropdownMenu` | Android 上任何"另起窗口"的做法都会让系统收掉输入法：`DropdownMenu`（可聚焦弹窗）**键盘闪一下**；`Popup(focusable=false)` 收不到触摸；`Dialog` 能点但照样收键盘。同窗口浮层三条都躲开了 |
| 5c | 图片插入后的光标 | 附件之后紧跟一个 `\n`，`selectedRange` 落在其后 | 同一个结果：图片独立成行 + 光标移到**它下面那一行**（没有就补一个空行） | 早先把图片插在光标行之后、光标留在原行，"插图后接着打字"会打在图片上面 |
| 6 | 新建片段时间戳 | 打开页面的时刻 | **点「写日记」的时刻** | 前者会让"打开看了一眼又回去写"产生错位 |
| 7 | 页内搜索的计数 / 高亮 | 两套算法（跨 chunk 命中只计数不高亮） | **一套算法**（`DiarySearch.findMatches` 同时喂计数与高亮） | 一致性 |
| 7b | 页内搜索栏位置 | 插在 Hero 卡上方，**跟着内容一起滚** | **悬浮在内容之上**（钉在顶栏下方，不随滚动） | 跟着滚会让「下一个匹配」把搜索栏顶出屏幕，计数与步进键都够不着 —— 用户实测反馈 |
| 7c | 命中高亮 | 所有命中**同一个样式** | **两级**：当前命中 `primary` 实底反转字，其余 `primaryContainer` | 一级高亮时看不出"现在看的是哪一处"，尤其在多命中同卡时 —— 用户实测反馈 |
| 8 | 「编辑中」徽标 | 在当前状态机下不可达（死代码） | 不实现 | 死 UI 不迁移 |
| 9 | 单键弹窗按钮 | 文案是「取消」 | 「好」 | 文案正确性 |
| 10 | 插入图片后放弃 | 留下孤儿文件 | 顺手清理 | 数据卫生 |
| 11b | **居中随换行延续** | iOS 的段落属性会随换行延续（`PartsCodec` 把块属性挂在终止换行上），居中也一样 | 居中**只作用于本行**，回车后的新行左对齐 | 用户规则 4 把居中的作用域写成"本行"，且真机实测反馈"居中的作用范围不对劲……取消的时候没有真的取消，不知道是不是和换行有关系"——正是延续造成的（取消只取消光标那一行，上面几行还留着居中） |
| 11 | **引用里回车** | 空的引用行：消费按键 + 结束引用（`RichTextEngine.swift:675-682`）；**有内容的引用行：换行之后引用延续**（`typingAttributes` 还带着引用属性） | **与 iOS 相同**：空引用行"消费按键 + 结束引用"，有内容的引用行"换行 + 新行继续引用"（2026-11-12 用户确认"一律跟随 iOS"，R36 已把 R33 的反向分歧改回） | 两端统一；Android 以 iOS 为源 |
| 12 | **段落几何的依据** | 按 Apple 的比例：行高 1.25 / 1.32 / 1.50 / 1.60、段前距 0.55 / 0.50 / 0.60 × 字号 | 一份 `BlockGeometry`：行高用 **M3 角色的官方值**（36 / 28 / 24 / 20），段距落在 4dp 栅格上取 **8dp**，标记槽 18 + 间距 10 = 正文起点 28dp —— 编辑 / 阅读 / 首页预览三处共用 | 照 Google / Material 的规范；M3 不定义富文本段距，只能自定一档并在三处共用 |
| 13 | **标记图形的垂直位置** | 基线之上 0.32em（只有 TextKit 表达得准） | **首行行盒里垂直居中**（编辑态 `(top + bottom) / 2`，Compose 侧 `(行高 − 图形高) / 2`） | 一条与平台无关、三个渲染面都能落地的规则 |

---

## 8. 验收清单

- [ ] 空的一天：Hero 卡常驻 + 「这一天没有留下日记。」+ 顶栏保留写入入口（**不是整屏空白**，v2 的 D8）
- [ ] 非今天：顶栏没有「写日记」，历史编辑受 `allowHistoryEdit` 约束
- [ ] 阅读 / 编辑**列宽完全一致**，进出编辑卡片时文字不左右跳
- [ ] 格式栏 9 个按钮顺序与 §3.3 表一致；引用**不在**段落样式菜单里；含「居中」
- [ ] 点格式栏按钮**不收键盘**；格式栏放不下时横向滚动
- [ ] 光标永远不被键盘或格式栏挡住（本实现由"格式栏不覆盖正文 + `EditText` 自己滚"结构性保证）
- [ ] 插图后：图片**独占一行**（光标行是空行就落在这一行）、整段**居中**、光标在图片**下面**那一行；旋转屏幕后图片重排、文本与光标不动
- [ ] 地点行是**一枚胶囊**（状态与动作同一个元素，2026-09-28 返工）：定位中 / 重新获取位置 / 地址 / 未记录地点；编辑已有片段没有获取入口
- [ ] 提示语是输入框的 `hint`（与光标同一行），不是另起一块文本；光标在输入区**顶部**
- [ ] 换段落样式 / 加去列表 / 勾选待办 / 居中，**都不改动 B/I/S/U**
- [ ] 长按 400ms 进多选；删除有破坏性确认；删空后停在阅读态
- [ ] 页内搜索栏**悬浮**在内容之上：连点「下一个匹配」时它不被顶出屏幕（§2.7 的修正）
- [ ] **当前命中**与其余命中**两种高亮**：点「下一个」时被选中的那一处换色（深色下是亮底深字 / 浅色下是品牌色实底白字）
- [ ] 分享一步弹出面板；渲染失败给 Snackbar；长图文件名 `JustDiary-<dayKey>.png`
- [ ] 段落样式菜单打开时**键盘不闪**（输入法全程不动），点一项即生效并关闭
- [ ] 插图后光标在图片**下面**那一行；图片与上下文的间距与普通段落一致
- [ ] 「放弃修改？」在内容为空时不弹
- [ ] TalkBack 能读出条目卡并给出「编辑 / 删除」自定义动作
- [ ] 最大字体档下正文不裁切、输入区够高
