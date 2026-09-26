# 06 · 组件库与弹层

> 这一篇是**动手前最后看的一页**：要用的组件在不在、签名是什么、弹层该用哪一种。
> 目标：**新增界面时不写新的基础组件**；确实需要新增时，加到这里并附上用例。
>
> 代码：`ui/components/Components.kt`、`ui/theme/*`

---

## 1. 组件清单（**已按实现核对**，2026-09-26）

> ⚠️ 这一节曾经列了 16 个**并不存在**的组件（`SectionCard` / `ListRow` / `FilterPill` /
> `DiarySearchBar` / `PrimaryButton` / `MonthGrid` / `YearGrid` / `TrendBarChart` / `PlaceTree` /
> `ShareLongImage` …），签名也与实现不符。照着那份清单去调，只会调到一个不存在的名字。
> 现在这一节是**代码里真实有的东西**，新增组件时请连同用例一起补进来。

### 1.1 `ui/components/Components.kt`（共享基础组件，10 个）

| 组件 | 签名要点 | 用途 / 注意 |
| --- | --- | --- |
| `DiaryCard` | `(modifier, onClick: (() -> Unit)? = null, contentPadding, contentModifier, content)` | **所有内容卡片**。`onClick != null` 时整卡可点并带涟漪。描边 1dp `outlineVariant`（增强对比度下 1.5dp `onSurfaceVariant`），**无阴影** |
| `PageHeader` | `(title, modifier, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null)` | 页面大标题。**三屏锚点必须一致**（左 16dp、标题顶 60dp）；放在滚动容器**外面**，别塞进 `LazyVerticalGrid` 的 item（内边距会叠） |
| `SectionTitle` | `(text, modifier)` | 卡片内的章节标题（「通用」「日记规则」…） |
| `IconBadge` | `(icon, size = 40.dp, contentDescription = null)` | 设置行左侧的圆形图标徽标（`secondaryContainer`） |
| `AssistPill` | `(text, onClick, leading: ImageVector? = null, variant: PillVariant, modifier)` | **顶栏胶囊** + 当日面板的「今天」。`variant = Outlined \| Elevated`。⚠️ M3 chip 的布局高由 `minimumInteractiveComponentSize` 定成 **48dp**，不要用 `height(40.dp)` 去压 |
| `InputPill` | `(text, onRemove: () -> Unit)` | 条件摘要行里的可删除芯片 |
| `CountBadge` | `(text, leading: ImageVector? = null)` | 「找到 3 条日记」「1/3」这类计数徽标 |
| `EmptyState` | `(icon, text, modifier)` | 统一空态（图标 + 一行说明） |
| `SkeletonRows` | `(count: Int = 3)` | 加载骨架 |
| `BusyOverlay` | `(text: String, visible: Boolean)` | 全屏忙碌遮罩（带 scrim、不可取消） |

**筛选芯片**用 M3 的 `FilterChip`，没有自己的包装；**按钮**直接用 M3 的
`Button` / `TextButton`，也没有包装层（曾经的 `PrimaryButton` 等一律不存在）。
**开关行**在 `SettingsScreen.kt` 里是私有的 `SwitchRow`（整行 `toggleable`，
内层 `Switch` 必须 `onCheckedChange = null`，否则 TalkBack 停两次）。

### 1.2 图标：`ui/components/AppIcons.kt`

不引 `material-icons-extended`（零第三方依赖的硬约束），缺的图标在这里
**内联 Material Symbols 的 path**，经 `materialIcon(name, path)` 构造。

现有：`CalendarMonth` `DirectionsWalk` `Language` `Palette` `DarkMode` `Schedule` `Place`
`MyLocation` `Edit` `Notifications` `Upload` `Download` `Info` `Globe`。
标准图标（`Icons.Filled.*`）直接使用，不要重复内联。

### 1.3 各页面私有的自绘组件

这些**故意不共享**：它们与所在页面的几何是绑死的，抽出来只会多一层参数搬运。

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| `CalendarDayGrid` | `ui/home/` | 月网格 + 周条共用；相邻月**永不绘制** |
| `CalendarWeekdayHeader` | `ui/home/` | 星期栏（极短文案，13sp） |
| `CalendarSection` / `YearLayer` / `YearCanvas` | `ui/home/` | 三态的日历主体；几何全部来自 `CalendarLayout` 的纯函数 |
| `HomeTopBar` | `ui/home/` | 顶栏两枚胶囊（外层私有） |
| `StatsCard` / `StatCell` / `TrendCard` / `YearBarChart` / `PlaceTreeCard` / `PlaceRow` | `ui/footprint/` | 统计 5 格、年度柱状图、三级地点树 |
| `DateRangeSheet` | `ui/components/DateRangeSheet.kt` | 自定义时间范围面板，**搜索页与足迹页共用同一个**（规范要求两处完全同构；曾经是两份近似拷贝，第二次改动必然分叉） |
| `LocationPickerSheet` | `ui/search/` | 地点选择面板（`ModalBottomSheet`） |
| `RangeDayField` / `DayKeyField` | `ui/*/` | 起止日期输入（⚠️ 纯文本、无校验，见 §5 待办） |

**几何纯函数**在 `ui/home/CalendarLayout.kt`（有 30+ 条单测）；
**图表度量**在各页面文件的常量区。

---

## 2. 弹层：只有三种

| 用途 | 组件 | 出现位置 | 关闭方式 |
| --- | --- | --- | --- |
| **短暂反馈** | `Snackbar` + `SnackbarHost` | 底栏之上 20dp | 自动 4s / 带「知道了」动作 / 新的顶掉旧的 |
| **二选一（含破坏性）** | `AlertDialog` | 居中 | 取消 / 确认 / 点 scrim（破坏性对话框**点 scrim 不关闭**） |
| **参数编辑** | `ModalBottomSheet` | 底部 | 下滑 / 点 scrim / 内部按钮 |

**不做**：Toast、自绘遮罩式弹窗、`Popup`、`Dialog`（裸）、第三套 sheet 形态。

### 2.1 Snackbar

| 项 | 规范 |
| --- | --- |
| 位置 | 屏幕底部，`navigationBarsPadding()` 之上 20dp；有底栏时**叠在底栏之上** |
| 宽度 | 左右各 16dp 内边距 |
| 内容 | 一行文案（≤ 2 行）+ 可选一个动作 |
| 动作 | 「知道了」/「撤销」/「重试」—— **动词开头** |
| 时长 | 有动作 6s / 无动作 4s |
| 用例 | 未来日期（「这一天还没到，不能写日记」）、分享失败、导出失败、导入失败 |

> iOS 用玻璃 toast（`showFutureToast`），而且**当前是死代码**（声明了从未调用）。
> Android 用 Snackbar，并**真的会弹**。

### 2.2 AlertDialog

| 项 | 规范 |
| --- | --- |
| 宽度 | 最小 280dp / 最大 560dp |
| 标题 | 24sp `headlineSmall`，一句话，破坏性动作带上数量（「删除选中的 2 段日记？」） |
| 正文 | 14sp `onSurfaceVariant`，说明**后果**（「删除后无法恢复。」） |
| 按钮 | 右下角，**横向排列**，顺序 = 取消 → 次要 → 主要（主要在最右） |
| 破坏性 | 主要按钮用 `error` 色（`TextButton`） |
| 单键 | 文案是「好」/「知道了」，**不是「取消」** |
| scrim | 破坏性对话框点 scrim **不关闭** |

现有对话框清单：

| 对话框 | 标题 | 按钮 |
| --- | --- | --- |
| 放弃修改 | 放弃修改？ | 取消 / **放弃**（error） |
| 删除多选 | 删除选中的 N 段日记？ | 取消 / **删除**（error） |
| 保存缺位置 | 仍然保存？ | 取消 / 仍然保存 |
| 改开始时间前确认 | 有 N 篇日记会改变归属日 | 取消 / 继续 |
| 导入方式 | 导入方式 | 取消 / 跳过已有日记 / **覆盖已有日记**（error） |
| 导入完成 / 失败 | 导入完成 / 导入失败 | 好 |
| 导出失败 | 导出失败 | 好 |

### 2.3 ModalBottomSheet

| 项 | 规范 |
| --- | --- |
| 圆角 | 顶部 28dp（`extraLarge`） |
| 拖柄 | 32 × 4dp，`onSurfaceVariant @40%`，居中，距顶 8dp |
| 标题 | 16sp medium，距左 24dp |
| 内容内边距 | 左右 24dp |
| 底部动作 | 右对齐的文字按钮（清除 / 确定） |
| 展开状态 | 参数编辑用 `skipPartiallyExpanded = true`（一次展开到位）；分享面板用默认（可半展） |
| 关闭 | 下滑 / 点 scrim / 内部取消 |

现有 sheet 清单：

| Sheet | 内容 | 底部动作 |
| --- | --- | --- |
| 自定义时间范围 | 快捷胶囊 + 起止日期 | 清除 / 确定 |
| 选择地点 | 搜索框 + 地点清单 + 未记录地点 | 取消 / 确定 |
| 新一天的开始时间 | 小时选择器 | 保存 |
| 提醒时间 | 时间选择器 | 保存 |
| 分享长图 | 预览 + 「分享…」/「保存到相册」 | — |

### 2.4 下拉菜单

用 M3 `DropdownMenu` + `DropdownMenuItem`：当前项 `primary` 色 + 前导 `check` 20dp；非当前项 `onSurface` + 20dp 占位。
**锚在触发行尾**，不是屏幕中央。用例：语言、主题、周起始、导出范围、段落样式、地点显示精度。

---

## 3. 状态样式

| 状态 | 视觉 |
| --- | --- |
| **按下** | M3 状态层（`onSurface @12%`）；卡片整卡按下时不缩放、不变形 |
| **禁用** | `alpha 0.38` + 不响应点击（`enabled = false`） |
| **选中** | `secondaryContainer` 底 + `onSecondaryContainer` 前景（导航 / 芯片 / 格式栏）；列表项用 `primary` 描边或前导勾选 |
| **焦点（键盘）** | 2dp `primary` 外描边 + 2dp 内缩 |
| **加载中** | 骨架行 / 不确定进度圈；**不改变布局高度** |
| **空** | `EmptyState`（图标 + 标题 + 可选副标题 + 可选出路按钮） |
| **错误** | 内联：`error` 色文案 + 「重试」文字按钮；**不弹对话框**（除非是用户主动触发的动作失败） |

---

## 4. 文案规范

| 类别 | 规范 | 例 |
| --- | --- | --- |
| 按钮 | 动词开头，2–4 字 | 保存 / 放弃 / 删除 / 重新获取位置 |
| 破坏性按钮 | 用**具体动作**，不用「确定」 | 「删除」而不是「确定」 |
| 空态标题 | 陈述事实 | 「这一天还没有日记」 |
| 空态副标题 | 给下一步 | 「输入关键词，或选择时间与地点筛选」 |
| 错误 | 说清**发生了什么 + 怎么办** | 「备份文件无效或导入出错，请重试。」 |
| 计数 | 数字在前，量词在后 | 「找到 3 条日记」「1 个片段」「已选择 2 项」 |
| 时间 | 跟随系统 12/24 小时制 | 「晚上 9:00」/「9:00 PM」 |

- **不用「确定 / 取消」之外的第二套确认词**；破坏性一律用具体动词。
- 一切文案进 `strings.xml`，`values/`（英文）与 `values-zh-rCN/`（中文）**同时**更新。
  Lint 的 `MissingTranslation` 设为 error。
- ICU 复数用 `<plurals>`（「1 个片段」/「2 个片段」）。

---

## 5. 新增组件的门槛

在写一个新的 `@Composable` 之前，先回答：

1. 现有组件能不能通过参数覆盖？（改参数 > 加组件）
2. 新组件会不会出现第二个用例？（只有一个用例 → 就地实现，不要提取）
3. 它是不是在重复 M3 已有的东西？（M3 有就用 M3）
4. 它的形状 / 颜色 / 字号 / 时长能不能全部在 `01-design-system.md` 里找到出处？

四条都过了，再新增，并**同时更新本篇**。
