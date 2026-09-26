# 01 · 设计系统

> 这一篇定义 Android 版的**视觉与度量令牌**。凡是在代码里写下一个颜色、一个字号、一个圆角、一段时长，
> 都必须能在这篇里找到出处。找不到 → 先补文档再写代码。
>
> 参照：iOS `Views/Components/{DesignSystem,DynamicType,Components}.swift`、`Services/Theme.swift`
> （逐条数值见 `docs/ios-reference/v3-design-system.md`）· Material 3（[m3.material.io](https://m3.material.io)）
> · `androidx.compose.material3:material3:1.4.0` 的令牌实现。
> 设计稿：[`mockups/png/06-design-system.png`](mockups/png)

---

## 1. 颜色

### 1.1 铁律

1. **代码里不出现十六进制颜色**（`ui/theme/Color.kt` 的回落色板除外）。一律写 `MaterialTheme.colorScheme.xxx`。
2. **不用 alpha 压文字来表达"次要"**。`onSurface.copy(alpha = 0.35f)` 这类写法一律换成语义色
   （`onSurfaceVariant`），否则对比度会掉到 3:1 以下（R3 实测过：`onSurface @35%` = **2.19:1**）。
3. **不用颜色单独承载信息**。今天 / 选中 / 有日记 / 未来 四个状态各自都有**非颜色**的表达（圆环 / 实心圆 / 下划线 / 整格压暗）。

### 1.2 角色用法表

| 角色 | 用在哪 | 不要用在哪 |
| --- | --- | --- |
| `primary` | 主按钮底、选中日实心圆、今天圆环、柱状图柱体、Switch 轨道（开）、链接式文字按钮、`checkmark` 保存图标 | 大面积背景 |
| `onPrimary` | 主按钮文字、选中日格内的日号与农历 | — |
| `primaryContainer` | 时间胶囊、计数徽标、搜索命中高亮、`今日` 徽标、（深色下的）主按钮 | 正文背景 |
| `onPrimaryContainer` | 以上容器内的文字 | — |
| `secondaryContainer` | **导航选中指示器**、设置行图标徽标、格式栏 active 按钮底 | 主行动 |
| `tertiaryContainer` | 待办勾选框（选中）、次要强调 | — |
| `error` | 破坏性动作（删除 / 覆盖 / 放弃）、错误文案 | 普通强调 |
| `surface` | 窗口底色（`Scaffold` 的 containerColor） | 卡片 |
| `surfaceContainerLowest` | Dialog、BottomSheet 内容底、输入框底 | 页面底 |
| `surfaceContainerLow` | **内容卡片**（日记卡、设置卡、Hero 卡、统计卡） | 页面底 |
| `surfaceContainer` | **底部导航栏**、顶栏（`TopAppBar` 默认） | 卡片 |
| `surfaceContainerHigh` | **引用块底**、悬浮格式栏、搜索框、DropdownMenu | 页面底 |
| `surfaceContainerHighest` | Switch 轨道（关） | — |
| `onSurface` | 正文、标题、日期数字 | 次要说明 |
| `onSurfaceVariant` | 次要说明、图标、农历、轴标签、分隔说明 | 正文 |
| `outline` | 输入框描边、Switch 描边、OutlinedButton 描边 | 分隔线 |
| `outlineVariant` | **分隔线**、卡片描边、网格线 | 文字 |
| `inverseSurface` / `inverseOnSurface` | Snackbar 底 / 文字 | — |
| `inversePrimary` | Snackbar 上的动作文字 | — |

### 1.3 动态取色（Material You）

- **默认开启**（`JustDiaryTheme(dynamicColor = true)`）。Android 12+ 取壁纸色，`secondary` / `tertiary` / `surface` 家族全部跟着走。
- 关闭或系统不支持时回落到品牌色板：`BrandSeed = #2563EB`（与 iOS `Theme.seed` **同值**）。
- **只有 `primary` / `secondary` / `tertiary` 及其容器参与动态取色**；`error` 与 `surface` 家族由 M3 基线固定，保证破坏性语义与层级在任何壁纸下都稳定。
- 组件里**永远不要**假设"主色是蓝色"。凡是需要与背景拉开对比的地方都用语义角色，不要写 `if (light) ... else ...`。

### 1.4 深浅色与对比度

- 主题三值：`light` / `dark` / `system`（`AppSettings.theme_mode`）。`darkThemeFor()` 是纯函数，有单测。
- 系统「高对比文字」开启时（`LocalIncreasedContrast`）：卡片描边 `0.5dp / outlineVariant` → **`1.5dp / onSurfaceVariant @90%`**，并额外加一圈外描边。
  （iOS 侧只有 `diaryCard` 接入了增强对比度；Android 保持同一范围，不要扩大到全局。）
- 对比度下限：**正文 ≥ 4.5:1，≥ 18sp 或粗体大字 ≥ 3:1**。新增任何"浅色文字"前先算一遍。

---

## 2. 排版

### 2.1 两套梯级，职责分开（这是最容易搞错的一条）

```
┌─ MaterialTheme.typography（15 个官方角色）── 所有 M3 组件吃这一套
│    顶栏标题 / 对话框 / 输入框 / 按钮 / 导航标签 / 列表行 / 菜单
│    ★ 字号 = Google 官方值，一个字都不改
│
└─ TypeSize（本项目的内容层梯级）──────────── 只服务自绘与富文本
     编辑器段落（28/22/16/14）、日历日号与农历、分享长图、柱状图轴标签
     ★ 逐档封顶的动态字体由 DynamicTypeMetrics 解析
```

**为什么**：首轮把本项目梯级挂到 M3 角色上，结果 `titleLarge = 18`（官方 22）、`bodyLarge = 17`（官方 16）、
`headlineSmall = 20`（官方 24）——**顶栏、对话框、输入框、按钮、标签页全部不是官方尺寸**。
现在由 `app/src/test/**/DesignTokensTest.kt` 的两条断言钉住：
① `MaterialTheme.typography` 的 11 个角色 == M3 官方字号；② 编辑器梯级 == M3 角色字号。

### 2.2 内容层的字号梯级

| 语义 | 设计字号 | M3 角色 | 行高 | 动态字体上限档 |
| --- | --- | --- | --- | --- |
| 页面大标题（`九月` / `2026年`） | 32 | 自绘 | — | Title1 → 1.40 |
| 页面标题（`搜索` / `足迹` / `设置`） | 24 | `headlineSmall` | 32 | Title3 → 1.45 |
| 编辑器 · 大标题 | 28 | `headlineMedium` | 36 | Title1 → 1.40 |
| 编辑器 · 小标题 | 22 | `titleLarge` | 28 | Title2 → 1.45 |
| 编辑器 · 正文 | 16 | `bodyLarge` | 24 | Body → 1.50 |
| 编辑器 · 引用 | 14 | `bodyMedium` | 20 | Subheadline → 1.60 |
| 卡片标题（阅读页日期） | 20 | `titleLarge` | 28 | Title3 → 1.45 |
| 紧凑头部胶囊标题 | 18 | `titleMedium` | 24 | Body → 1.50 |
| 统计数字 | 18 | `titleMedium` | 24 | Body → 1.50 |
| 列表行主文本 | 16 | `bodyLarge` | 24 | Body → 1.50 |
| 列表行副文本 | 13 | `bodyMedium` | 20 | Footnote → 1.65 |
| 章节标题 | 13 | `titleSmall` | 20 | Footnote → 1.65 |
| 芯片 / 胶囊标签 | 14 | `labelLarge` | 20 | Subheadline → 1.60 |
| 元信息（时间 / 地点） | 13 | `bodyMedium` | 20 | Footnote → 1.65 |
| 最次要（农历 / 轴标签） | 12 | `labelMedium` | 16 | Caption1 → 1.70 |
| 日历日号 | `min(20, max(13, cellH × 0.34))` | 自绘 | — | 半速 |
| 日历农历 | `min(11, max(9, cellH × 0.18))` | 自绘 | — | 半速 |

> `min(20, max(13, cellH × 0.34))` 是**必须**的：横屏/分栏把格子压到 40dp 时，
> 写死 20 会让日号和农历重叠（iOS 曾踩过这个坑）。

### 2.3 动态字体：逐档封顶，绝不是全局线性

Android 只有一个 `fontScale`，直接乘会复现 iOS 早已修掉的层级倒挂（AX5 下 15pt 正文涨得比 22pt 标题还快）。

```
effectiveMultiplier(designSize, fontScale) = min(ceiling(designSize), max(1, fontScale))
effectiveSp(designSize, fontScale)         = (designSize × effectiveMultiplier / fontScale).sp
```

- `ceiling` 按"最接近的 Apple 文本样式"分档：LargeTitle 1.35 / Title1 1.40 / Title2·Title3 1.45 /
  Body 1.50 / Callout 1.55 / Subheadline 1.60 / Footnote 1.65 / Caption1 1.70 / Caption2 1.75。
- **先除掉 `fontScale` 再交给 Compose** —— 因为 Compose 会对 `sp` 自动乘一次。
- `fontScale = 1` 时结果与设计值**逐字一致**（默认外观不变）。
- **日历专用半速**：`calendarMultiplier = 1 + (bodyLike − 1) × 0.5`。格子尺寸由屏幕决定，日号不能跟满曲线。
- 单测守住：`DynamicTypeMetricsTest`（14 条）断言"任何 `fontScale` 下梯级不倒挂"。

### 2.4 段落间距 / 行距

- 编辑器段落**行高直接用 M3 角色的官方值**（36 / 28 / 24 / 20）。
  **不要**照搬 iOS 的 `lineSpacing = 0.13 × 字号` —— Apple 的 `lineSpacing` 是"额外行距"，不是行高，
  在 Compose 里被近似成 `fontSize × 1.13`，既不是 Apple 的语义也不是 Material 的。
- 段后距 M3 **没有规范**（M3 不定义富文本段距）。由阅读区的 `Arrangement.spacedBy(8.dp)` 统一给节奏，
  不在排版层编造数值。
- 阅读态与编辑态共用同一套 `PartsCodec` 生成器 → **同一段文字在两种模式下逐像素一致**。

---

## 3. 形状

全部落在 M3 Expressive 梯级上。**不再出现 20 / 26 / 14 这些非梯级值**。

| 令牌 | 值 | 用在 |
| --- | --- | --- |
| `extraSmall` | 4dp | Snackbar、引用块底、柱状图柱顶 |
| `small` | 8dp | FilterChip、待办勾选框 |
| `medium` | 12dp | 输入框、卡片内联图片、次级面、Dialog 内的列表 |
| `large` | **16dp** | **内容卡片**（日记卡、设置卡、Hero 卡、统计卡、年历迷你月卡） |
| `extraLarge` | 28dp | ModalBottomSheet、AlertDialog、悬浮格式栏、FAB、搜索栏 |
| `full` | 999dp | 日期胶囊、chip、Switch、导航指示器、Badge |

### 3.1 嵌套圆角

```kotlin
/** 内圆角 = 外圆角 − 内缩量（下限 0）。M3 没有等价概念，原样保留为纯函数。 */
fun concentric(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(0.dp)
```

直接把内外圆角设成同一个值会产生视觉上的"尖角内衬"。用在：卡片里的图片、卡片里的嵌套面板。

### 3.2 日历的几何例外（M3 无对应，保留 iOS 原值）

- `cellH = (日历区高 − 大标题槽 72 − 星期栏 30) / 6`，**恒除 6**。
  5 行的月份在下方留白 → **翻月时格子尺寸不变、不会跳**。
- 正文列宽 `min(660, 宽 − 32)`，**阅读列宽 = 编辑列宽**。

---

## 4. 海拔与分层

M3 用 **tonal elevation**（surface container 阶梯）而不是投影。层级靠底色深浅表达：

```
surface                     页面底
  └ surfaceContainerLow     内容卡片（最常用）
      └ surfaceContainerHigh  卡片内的次级面：引用块、搜索框、格式栏、菜单
          └ surfaceContainerHighest  Switch 轨道（关）
surfaceContainerLowest      Dialog / BottomSheet / 输入框（比页面"更亮"）
surfaceContainer           底部导航栏、顶栏
```

**只有真正浮起的元素才带阴影**（`Modifier.shadow`）：FAB、Snackbar、悬浮格式栏、BottomSheet、DropdownMenu。
内容卡片**不用阴影**（iOS 的 `diaryCard` 带 `shadowColor @14% / radius 8 / y 2`，Android 换成 tonal elevation）。

| 层级 | 组件 | 阴影 |
| --- | --- | --- |
| 0 | 页面、卡片、列表行 | 无 |
| 1 | 搜索栏（悬浮态）、FAB（small） | `shadow-1` |
| 2 | 格式栏、FAB、DropdownMenu | `shadow-2` |
| 3 | BottomSheet、Dialog、Snackbar | `shadow-3` |

---

## 5. 图标

- **Material Symbols**，24dp 网格，`viewBox="0 0 24 24"`。
- 顶栏 / 导航图标 24dp；列表行图标 20dp；卡片内元信息图标 14–16dp；FAB 24dp。
- **不引 `material-icons-extended`**（体积换不来价值）。需要的图标按需内联 path —— 本设计稿用到的 50 个图标
  已经全部列在 `mockups/assets/ui.js` 的 `ICONS` 表里，可以直接抄。
- 导航栏四个目的地（与 iOS 的语义对应）：

| 目的地 | iOS 图标 | Android 图标 | 说明 |
| --- | --- | --- | --- |
| 日记 | `house.fill` | `calendar_month` | **有意不同**：这一屏的主体是日历，"房子"在 4 标签导航里会和"首页"混淆 |
| 足迹 | `figure.walk` | `directions_walk` | 同义 |
| 搜索 | `magnifyingglass` | `search` | 同义 |
| 设置 | `gearshape.fill` | `settings` | 同义 |

- 图标**不单独承载语义** —— 每个图标按钮都要有 `contentDescription`（无文字按钮）或可见标签。

---

## 6. 动效与触感

### 6.1 时长与缓动

| 场景 | 时长 | 缓动 | 令牌 |
| --- | --- | --- | --- |
| 月↔周 morph（滑动） | 400ms | `easing.emphasized` = CubicBezier(0.2, 0, 0, 1) | `medium4` |
| 月↔年 morph（缩放） | 400ms | 同上 | 两条 morph **共用**同一曲线（iOS 同） |
| 翻月 / 翻年 / 翻周 | 300ms | `easing.emphasizedDecelerate` | `medium2` |
| 展开 / 收起列表、内容淡入 | 250ms | `easing.standard` | `medium1` |
| 状态层涟漪 | 由 M3 组件自带 | — | — |
| **减弱动态效果**开启 | 180ms | `easing.linear`（**只做交叉淡入，不做位移**） | — |

> 减弱动效的判定：`Settings.Global.ANIMATOR_DURATION_SCALE == 0f` 或
> `AccessibilityManager` 的相应状态；封装成 `LocalReduceMotion`，与 `LocalIncreasedContrast` 并列。

### 6.2 触感

| 交互 | Android API | iOS 对照 |
| --- | --- | --- |
| 点日期 / 点胶囊 / 点格式按钮 / 拨开关 | `HapticFeedbackType.TextHandleMove` | `UIImpactFeedbackGenerator(.light)` |
| 长按进入多选 | `HapticFeedbackType.LongPress` | 同左 |
| 未来日期 / 操作被拒 | `HapticFeedbackType.Reject` | `.sensoryFeedback(.warning)` |
| 面板展开 / 收起 | 不给触感 | — |

**一次交互只给一次触感**，不叠加；`Switch` 自身已有系统反馈，不要再补。

### 6.3 禁则

- **不做捏合缩放**（见 `README.md` 一·有意分歧）。
- 不用 `AnimatedVisibility` 的默认滑入做整屏切换 —— 三态只有一个 morph 通道。
- 不在滚动里叠加视差 / 缩放 / 模糊。
- 不给列表项加"进入时的错落淡入"（日记条数可能上百）。

---

## 7. 无障碍基线

| 项 | 要求 |
| --- | --- |
| 触控目标 | **≥ 48dp**（`Spacing.hitTarget`），日历格 ≥ 40dp 且整格可点 |
| 状态层 | 所有可交互元素必须有触摸反馈。**首轮日历格与年历卡片 `indication = null`，完全没有反馈 —— 这是硬性违规，已修** |
| 文字对比度 | 正文 ≥ 4.5:1；大字 ≥ 3:1；**新代码不许用 alpha 压文字** |
| `contentDescription` | 每个图标按钮、每个自绘元素；日历格读作「9月8日 周二，农历廿七，有日记，未选中」 |
| 自绘元素 | 日历网格、迷你月历、柱状图必须自己提供语义（`Modifier.semantics { }`），否则 TalkBack 读不到 |
| 分页器 | `beyondViewportPageCount = 0`，避免同名元素出现 2–3 份 |
| 键盘导航 | 顶栏动作、列表行、卡片都要可用 Tab / 方向键到达且有可见焦点 |
| 动态字体 | 见 §2.3；最大档下**不许裁切**，长文本用 `maxLines` + 缩放或换行，**不要用省略号截断关键信息** |
| 预测性返回 | Android 13+ 接入 `PredictiveBackHandler`（见 `02-adaptive-and-navigation.md` §6） |

---

## 8. 改动检查清单

改 `ui/theme/` 或任何组件前后，逐条自查：

- [ ] 没有新增十六进制颜色；没有 `Color(0x...)` 出现在 `ui/theme/` 之外
- [ ] 没有用 `alpha` 压文字来表达次要
- [ ] 新字号要么来自 `MaterialTheme.typography`，要么在 §2.2 表里有行
- [ ] 新圆角在 §3 梯级上
- [ ] 层级用 surface container 表达，没有随手加阴影
- [ ] 所有新交互有 ≥ 48dp 触控目标 + 触摸反馈
- [ ] 新增的自绘元素有 `contentDescription`
- [ ] 动画时长取自 §6.1，且接了减弱动效
- [ ] `./gradlew testDebugUnitTest` 里 `DesignTokensTest` / `DynamicTypeMetricsTest` 仍全绿
