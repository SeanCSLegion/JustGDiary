# 06 · 组件库与弹层

> 这一篇是**动手前最后看的一页**：要用的组件在不在、签名是什么、弹层该用哪一种。
> 目标：**新增界面时不写新的基础组件**；确实需要新增时，加到这里并附上用例。
>
> 代码：`ui/components/Components.kt`、`ui/theme/*`

---

## 1. 组件清单

### 1.1 容器

| 组件 | 签名 | 用途 |
| --- | --- | --- |
| `DiaryCard` | `(modifier, shape = Shapes.large, containerColor = surfaceContainerLow, onClick: (() -> Unit)? = null, content)` | **所有内容卡片**。`onClick != null` 时整卡可点并带涟漪 |
| `SectionCard` | `(title: String, modifier, content)` | 带章节标题的设置卡（通用 / 日记规则 / …） |
| `ListRow` | `(icon: ImageVector?, title: String, subtitle: String?, value: String?, trailing: ListRowTrailing, onClick: (() -> Unit)?)` | 设置行 / 结果行。`ListRowTrailing = None \| Chevron \| Switch(onToggle) \| Custom(slot)` |
| `RowDivider` | `(insetStart: Dp = 72.dp)` | 卡片内的行分隔线 |
| `GroupedCardGroup` | `(modifier, content)` | 一列设置卡的容器（处理卡间距与列布局） |

### 1.2 控件

| 组件 | 签名 | 说明 |
| --- | --- | --- |
| `IconBadge` | `(icon: ImageVector, size: Dp = 40.dp, container = secondaryContainer)` | 设置行 / 列表行左侧的圆形图标徽标 |
| `AssistPill` | `(text: String, leading: ImageVector?, variant: PillVariant, onClick)` | **顶栏胶囊**。`variant = Outlined \| Elevated`（首页左 / 右两枚） |
| `FilterPill` | `(text: String, selected: Boolean, leading: ImageVector?, onClick)` | 筛选芯片（搜索时间/地点、足迹年份） |
| `InputPill` | `(text: String, onRemove: () -> Unit)` | 条件摘要行里的可删除芯片 |
| `CountBadge` | `(text: String, icon: ImageVector? = null)` | 「找到 3 条日记」「1/3」这类计数徽标 |
| `DiarySearchBar` | `(value, onValueChange, onClear, onCancel, focused, placeholder)` | 搜索页 / 日记页内搜索共用的输入条 |
| `PrimaryButton` / `TonalButton` / `OutlinedTextButton` / `TextActionButton` | `(text, onClick, enabled, modifier)` | 直接包 M3 的 `Button` 变体，统一最小宽度与高度 |
| `StatusSwitchRow` | `(icon, title, subtitle, checked, onCheckedChange)` | **整行可点**的开关行（新代码用这个，不要直接用 `ListRow` 拼） |
| `EmptyState` | `(icon: ImageVector, title: String, subtitle: String?, action: (@Composable () -> Unit)?)` | 统一空态 |
| `SkeletonRows` | `(count: Int = 3)` | 加载骨架 |
| `BusyOverlay` | `(text: String, visible: Boolean)` | 全屏忙碌遮罩（不可取消） |
| `SegmentedToggle` | `(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit)` | 仅在需要 2–3 选一且不需要展开菜单时使用（当前无用例） |

### 1.3 日历 / 图表（自绘）

| 组件 | 签名 | 说明 |
| --- | --- | --- |
| `MonthGrid` | `(year, month, weekStart, selectedDate, today, hasEntry: (LocalDate) -> Boolean, cellH: Dp, showLunar: Boolean, onDayClick)` | 月态网格 + 周条共用；`showAdjacent` **不存在这个参数** —— 相邻月永不绘制 |
| `MonthBigTitle` | `(month: YearMonth, height: Dp, fontSize: TextUnit)` | 大标题槽；**morph 的终点**，几何必须与真实排版一致 |
| `MiniMonthCard` | `(year, month, hasEntry, today, selected, onClick)` | 年态的迷你月历卡片 |
| `YearGrid` | `(year, weekStart, hasEntry, today, selectedDate, onMonthClick)` | 3 × 4 迷你月历 |
| `WeekStrip` | `(weekStartDate, selectedDate, hasEntry, onDayClick)` | 周态的一行日期 |
| `TrendBarChart` | `(data: List<YearStat>, height: Dp, modifier)` | 足迹柱状图；**无点击交互** |
| `PlaceTree` | `(nodes: List<PlaceNode>, expanded: Set<String>, onToggle, depth = 0)` | 足迹三级树 |
| `ShareLongImage` | `(dayKey, blocks, isDark, showTime) -> File` | 分享长图渲染（离屏 Canvas） |

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
