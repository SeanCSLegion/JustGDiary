# 02 · 自适应与导航

> 这一篇定义"**什么尺寸下变成什么样子**"。所有判据都是**纯函数**，临界值必须能在 JVM 上逐条断言。
>
> 参照：iOS `Views/Components/AdaptiveLayout.swift`（逐条数值见 `docs/ios-reference/v3-design-system.md` §2）
> · Material 3 窗口尺寸类 · 设计稿 [`mockups/png/06-design-system.png`](mockups/png) 第 5 节

---

## 1. 方向锁定：手机只有竖屏

**需求**：手机不启用横屏模式；横屏界面只在平板上出现。

### 1.1 实现

```
app/src/main/res/values/bools.xml            <bool name="is_tablet">false</bool>
app/src/main/res/values-sw600dp/bools.xml    <bool name="is_tablet">true</bool>
```

```xml
<activity android:name=".app.MainActivity"
    android:screenOrientation="unspecified"   <!-- 由代码决定 -->
    ... />
```

```kotlin
// MainActivity.onCreate
val isTablet = resources.getBoolean(R.bool.is_tablet)
requestedOrientation = if (isTablet) {
    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
} else {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT   // 含倒置？→ 用 PORTRAIT，不倒置
}
```

### 1.2 判据是 `sw600dp`，不是 `sw` 的当前值

`sw600dp` 读的是**最小宽度**（屏幕较短边），因此**不随旋转变化** —— 平板横竖屏都是 tablet，
手机横竖屏都是 phone。**不要**用 `LocalConfiguration.screenWidthDp` 判"是不是平板"：
折叠屏展开后 `screenWidthDp` 会跳到 700+，但它仍然是手机形态，锁横屏的体验会很怪。

> 折叠屏的中间态（600–840dp 且短边 < 600dp）按 `sw600dp` 判为 phone → 锁竖屏。
> 这是**有意**的：折叠屏内屏竖屏可用宽度已经足够，不需要横屏；而锁竖屏能保证三态 morph 的几何稳定。

### 1.3 与 `AdventureLayout` 的关系

方向锁定只决定"系统会不会旋转"。**版面仍然只看当前可用宽高**（§2），不看机型、不看方向、不读 `UIScreen`。
iOS 的原注释已经把理由写清楚了：折叠屏展开后仍是手机但宽高都变成 regular，任何按机型分支的代码都会错。

---

## 2. 版面判定：一个判据，一处读取

```kotlin
data class AdaptiveLayout(
    val width: Float, val height: Float,
    val safeLeading: Float, val safeTrailing: Float,
    val safeTop: Float, val safeBottom: Float,
) {
    val contentWidth: Float get() = max(0f, width - max(0f, safeLeading) - max(0f, safeTrailing))
    val navForm: NavForm   get() = if (width >= 600f) NavForm.Rail else NavForm.Bar
    val tier: Tier         get() = when {
        width <  700f -> Tier.Compact
        width < 1000f -> Tier.Medium
        else          -> Tier.Expanded
    }
    val splitsMasterDetail: Boolean get() = contentWidth >= 548.5f && width > height
    ...
}
```

**唯一来源**：`RootScreen` 读一次几何 → `CompositionLocalProvider(LocalAdaptiveLayout provides …)`。
**页面内部不许再读屏幕尺寸**（不许出现第二个 `BoxWithConstraints`，除非是像日记页那样的全屏路由宿主）。
全屏路由（日记页 / 编辑器）自带一个 `AdaptiveLayoutHost`，规则同样成立。

### 2.1 三组阈值（都有出处）

| 用途 | 阈值 | 出处 |
| --- | --- | --- |
| **导航形态** | `< 600dp` 底栏 / `≥ 600dp` 侧栏 | Material 3 窗口尺寸类 |
| **内容档位 / 设置列数 / 分栏并排** | `< 700` / `700–999` / `≥ 1000dp` | iOS `AdaptiveLayout` 阈值原样保留 |
| **首页与足迹是否左右分栏** | 内容宽 `≥ 548.5dp` **且** 宽 > 高 | iOS `splitsMasterDetail`（**v3 更新**：v2 写的 700dp 是旧值） |

> **548.5 是怎么来的**：`2 × 16(页边距) + 16.5(栏间距 + 0.5 分隔线) + 260(主栏最窄) + 240(详情栏最窄)`。

> **"必须同时宽 > 高"的意义**：平板竖屏（800 × 1280）虽然宽度足够，但保持单栏 ——
> 这样竖屏的年/月/周三态 morph 交互在任何设备上都一样。这条与 iOS 完全一致。

### 2.2 分栏的主栏宽度

```kotlin
val masterWidth: Float get() =
    min(440f, max(260f, (contentWidth * 0.46f).roundToInt().toFloat()))
// 且保证 detailWidth = contentWidth - masterWidth - 2*padding - gutter >= 240
```

- 实测：1280dp 平板横屏（减 96dp 侧栏 → 内容 1184）→ 主栏 440（封顶）、详情栏 ≈ 692。
- 主栏 260dp 时 7 列每格 ≈ 37dp —— 这是可点下限，所以 260 是硬底。

---

## 3. 导航

### 3.1 形态

| 尺寸类 | 导航 | 尺寸 | 位置 |
| --- | --- | --- | --- |
| Compact（手机） | `NavigationBar` | 80dp | 贴底，**参与布局**（不是浮层） |
| Medium（平板竖屏 / 折叠展开） | `NavigationRail` | 96dp | 贴左，含标签 |
| Expanded（平板横屏） | `NavigationRail` | 96dp | 贴左，含标签 |

**四个目的地、顺序固定**：日记 / 足迹 / 搜索 / 设置。默认落地在「日记」。

**不引入 `NavigationDrawer`**：只有 4 个目的地，抽屉会把内容挤窄、还要多一次点击。
M3 允许 Expanded 用 rail，这里就用 rail，保持三种尺寸下的肌肉记忆一致。

### 3.2 不加 FAB

M3 建议 rail 顶部放一枚 FAB。**这里不加**：

> 新建日记的入口是"日历上的一天" —— **日期语义先于"写"这个动作**。
> 一枚全局 FAB 会绕开日期（用户得先想"我要写哪天的"，而不是"今天"）。

写日记的入口保持三条：① 首页周态当日面板的「写日记 / 继续写今天的日记」；
② 日记阅读页顶栏的「写日记」（仅当天）；③ 深链 `justdiary://editor?dayKey=`。

### 3.3 底栏消失的时机

**底栏/侧栏永远在。** 只有两种例外：

1. **全屏路由**（日记阅读 / 编辑 / 图片预览）盖住整个窗口 —— 与 iOS 的 `fullScreenCover` 一致；
2. 图片全屏预览是沉浸式（隐藏系统栏）。

> iOS 的浮条有 `.tabBarMinimizeBehavior(.onScrollDown)`（滚动时收起）。Android 的 `NavigationBar` **不做**自动收起：
> M3 没有对应规范，收起/展开会造成列表跳动。

### 3.4 顶栏

| 屏 | 顶栏 |
| --- | --- |
| 首页 | **无 `TopAppBar`**：只有一行 64dp 的"胶囊行"（左侧导航胶囊 + 右侧「今天」胶囊），**不随滚动收起**（首页不滚动） |
| 搜索 / 足迹 / 设置 | 页头大标题 28/24sp（**不用 `LargeTopAppBar`**，因为这三个页面需要标题常驻可见：搜索页滚动时顶部控件会收起，标题若也跟着走会失去定位感） |
| 日记阅读 / 编辑 | `TopAppBar` 64dp，**钉在页面顶部不进滚动区** |

> iOS 曾经把日记页顶栏挂在 `safeAreaInset` 上，键盘弹起时被一起滚出屏幕（返回按钮跑到 y = -51）。
> Android 用 `Scaffold` 的 `topBar` 槽位，结构上不可能发生。**横屏时也不要让顶栏滚出**。

---

## 4. 逐屏的平板行为

| 屏 | Compact（手机竖屏） | Medium（平板竖屏 800×1280） | Expanded（平板横屏 1280×800） |
| --- | --- | --- | --- |
| **首页** | 三态 morph，日历铺满宽度 | 三态 morph，日历**限宽 640dp 居中** | **左右分栏**：左 = 月历（紧凑标题 34 + 星期栏 26，上下滑翻月），右 = 选中日内容（标题行 44dp，右侧「今天」，正文限宽 560dp）。**无顶栏、无周态、无年态** |
| **首页 · 年态** | 整屏 3 × 4 迷你月历 | 同左，限宽居中 | **不存在**：分栏时强制收回月态（对齐 iOS，见 §4.2） |
| **首页 · 周态** | 星期栏 + 周条 + 当日内容 | 同左 | **不存在**：右栏已经常驻当日内容（裁决 #7） |
| **日记阅读 / 编辑** | 单列，列宽 = `min(660, 宽 − 32)` | 单列，列宽被 660 卡住居中 | 同左（**日记页横屏不分栏** —— 分栏是首页的功能） |
| **搜索** | 单列：搜索框 → 条件面板 → 结果 | 同左 | **筛选左栏（340dp）+ 结果右栏** |
| **足迹** | 统计 → 趋势 → 清单，顺次向下 | 同左 | 统计整行在上；下方**趋势图（flex）\| 地点清单（固定 300dp，内部滚动）**并排 |
| **设置** | 单列 5 张卡 | **2 列** | **3 列** |

### 4.1 分栏下点日期 ≠ 进周态

分栏时点日期**只更新选中日**（右栏跟着换），**不触发 morph** —— 因为当日内容已经常驻可见，
再 morph 一次是纯粹的干扰。进分栏时若正处于年态或周态，**无动画地收回月态**。

```
onChange(splitsMasterDetail) { split -> if (split) settleToMonth() /* 无动画，清 morph，回月态 */ }
```

### 4.2 分栏下没有年态、没有周态（严格对齐 iOS）

切进分栏时**无动画地收回月态**：

```
onChange(splitsMasterDetail) { split -> if (split) settleToMonth() /* 清 morph、回月态、不播动画 */ }
```

- **年态不可达**：分栏时顶栏整条隐藏，年份入口（那枚 `‹ 2026年` 胶囊）随之消失。
  要看年视图请把平板转回竖屏（竖屏三态 morph 完整保留）。
- **周态不可达**：右栏已经常驻当日内容，再 morph 一次是纯干扰。
- 转回竖屏时**不恢复**年/周态 —— 回到月态。

> 这一条已由产品确认（2026-09-26），与 iOS 的 `settleToMonth()` 完全一致。
> 左栏保留的交互只有两种：**上下滑翻月** 与 **点日期**。

### 4.3 分栏下的左右分工

- **左栏**（月历）负责**时间导航**：上下滑翻月、点日期选日、点左栏标题行进/退年历。
- **右栏**（当日）负责**内容**：日期标题行 44dp（日期 + 农历 + 右侧「今天」）、分隔线、片段卡片列表。
- 左右栏之间是 **1dp `outlineVariant` 分隔线**，两侧各 16dp 间距（合计 16.5dp）。
- **划分判据（Android 与 iOS 不同）**：iOS 的「今天」只在右栏标题行；Android 的**顶栏整条在分栏时隐藏**，
  「今天」只在右栏标题行 —— 严格一处，不重复。

---

## 5. 窗口 Insets

| Insets | 用法 |
| --- | --- |
| `WindowInsets.safeDrawing` | 唯一的 inset 来源。由 `rememberAdaptiveLayout()` 一次性解析成 `AdaptiveLayout` 的安全区字段 |
| `WindowInsets.systemBars` | 只在沉浸式页面（图片预览）用 |
| `WindowInsets.ime` | **只用 `adjustResize` + `Modifier.imePadding()`**，不要手动算键盘高度 |

**规则**：

1. **页边距恒 16dp，且不含安全区。** 在 edge-to-edge 下，`Scaffold` 的 `innerPadding` 已经把安全区算进去了；
   在安全区原点之上再加一次 16dp 就会"重复避让"（iOS 横屏曾因此把日历推到 x ≈ 222，左半屏白白空着）。
2. **不要移植 `TabBarClearance` / `bottomInset + 44` 这类 iOS 数字。** Android 的底栏在布局流里。
3. 键盘：`android:windowSoftInputMode="adjustResize"` + 需要避让的容器加 `Modifier.imePadding()`。
   **不要**去读 `WindowInsets.ime` 的高度自己算（横屏下这个矩形可能是另一个坐标系的值）。

---

## 6. 返回栈与预测性返回

| 场景 | 返回行为 |
| --- | --- |
| 首页 · 年态 | 返回 → 月态（**不退出应用**）。用 `PredictiveBackHandler(enabled = mode == year)` 拦截 |
| 首页 · 周态 | 返回 → 月态 |
| 首页 · 月态 | 返回 → 交还系统（退出应用 / 回到桌面） |
| 日记阅读（全屏路由） | 返回 → 关闭路由，同时 `bumpDiaryVersion()` |
| 日记编辑 | 返回 → **拦截**，弹「放弃修改？」（破坏性确认） |
| 日记多选 | 返回 → 退出多选（回到阅读态），不关闭路由 |
| 任一 ModalBottomSheet | 返回 → 关闭面板（M3 自带） |
| 搜索结果态 | 返回 → 清空关键词与筛选（如有），否则交还系统 |
| 足迹展开态 | 返回 → 收起展开（如有），否则交还系统 |
| 深链进入编辑器 | 返回 → 回到「日记」标签的月态（不是回到桌面） |

**深链必须在 `onNewIntent` 立即消费**（iOS 有个缺陷：前台收到深链要等下次激活才生效，
用户会莫名看到编辑器弹出来）。

---

## 7. 验收清单

- [ ] 手机（`sw < 600dp`）在系统设置里打开"自动旋转"后，App **仍然只竖屏**
- [ ] 平板（`sw ≥ 600dp`）四个方向都能转，且转完不崩、不丢状态（**年/周态在横屏收回月态是预期行为**）
- [ ] `AdaptiveLayoutTest` 覆盖：599/600、699/700、999/1000、548.4/548.5、宽 == 高 五个临界
- [ ] 三种尺寸下四个目的地都能到达，导航不重叠内容
- [ ] 平板横屏：左栏滑月 → 右栏跟着换选中日；点日期 → 只换右栏，不 morph
- [ ] 平板横屏：**没有年份入口**，从竖屏年态转过来会自动收回月态；左栏只能上下滑翻月
- [ ] 平板竖屏：三态 morph 与手机完全一致
- [ ] 设置页 1 / 2 / 3 列在 600 / 700 / 1000dp 处正确切换
- [ ] 键盘弹起时输入框与光标可见；顶栏不滚出屏幕
- [ ] 预测性返回在年态 / 编辑态 / 多选态各自正确拦截
