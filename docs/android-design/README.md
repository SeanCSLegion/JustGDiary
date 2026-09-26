# JustGDiary Android 设计规范（v3 · 权威版）

> **这一套文档是 Android 版界面的唯一权威规范。**
> 它取代 [`docs/design-spec.md`](../design-spec.md) 与 [`docs/ui-design-plan.md`](../ui-design-plan.md)（v2）中与之冲突的一切条款；
> 冲突处逐条列在本文件 §5「对 v2 的裁决」。**改界面之前先读这一篇。**
>
> 建立日期：2026-09-26 · 依据 iOS commit `0b07050`（一页时光 / JustADiary，iPhone-only，UI 已定稿）

> **实施状态（2026-09-26）**：**地基 + 首页 + 设置页 + 搜索页 + 足迹页已完成**，
> 见 [`docs/migration-progress.md`](../migration-progress.md) 的 R4 一节（5 个提交，逐屏一个）。
> **日记页尚未开工** —— 按计划等前四屏测试通过后再做；它的差距清单在
> [`07-ios-map-and-plan.md`](07-ios-map-and-plan.md) §3.1 的 P0-1…P0-3。
> 上机证据：`docs/ui-audit/r4/`（18 张，含平板横竖屏、深色、锁竖屏）。
> 参考设备：手机 Pixel 10 Pro（412 × 915 dp @2.625x）、平板 1280 × 800 dp / 800 × 1280 dp
> 设计稿：[`mockups/`](mockups)（9 块板面，含逐屏竖屏 / 横屏 / 深色）

---

## 一、一页结论

### 三条前提（本轮的拍板）

| # | 决定 | 含义 |
| --- | --- | --- |
| 1 | **视觉语言 = Google Material 3 / Material You** | 颜色走 `MaterialTheme.colorScheme` 语义角色，**动态取色默认开启**；形状 / 排版 / 海拔 / 动效全部落在 M3 官方令牌上。**不模仿 iOS 的 Liquid Glass。** |
| 2 | **信息层级、几何比例与交互结果 = 对齐 iOS** | 三态日历模型、morph 的起止形态、阅读列宽 = 编辑列宽、格式栏 9 键固定顺序、筛选条件随时可见可撤销……这些是**产品语义**，逐条复刻。 |
| 3 | **手机锁竖屏；横屏只在平板上出现** | `screenOrientation` 按最小宽度分流：`sw < 600dp` → `portrait`；`sw ≥ 600dp` → `fullSensor`。因此**横屏设计稿 = 平板设计稿**，手机没有横屏界面。 |

### 由此推出的五条硬性设计约束

1. **导航只有两种形态**：Compact（`< 600dp`）用 `NavigationBar` 80dp 贴底；Medium/Expanded（`≥ 600dp`）用 `NavigationRail` 96dp 贴左。两者指向同一组四个目的地，**不做第二套导航**。
2. **底栏参与布局，不是浮层。** iOS 的玻璃浮条是 overlay（`TabBarClearance` 手工让位），Android 的 `NavigationBar` 占真实高度 —— 所以 iOS 上「6 行月份最后一行被浮条盖住」这类问题在 Android 结构上不可能出现。**不要移植 `TabBarClearance` 那套数字。**
3. **一切短暂反馈用 Snackbar，一切二选一用 AlertDialog，一切参数编辑用 ModalBottomSheet。** 不引入 Toast、不引入自绘遮罩、不引入第三个弹层形态。
4. **48dp 是最小触控目标**（不是 iOS 的 44pt），正文对比度 ≥ 4.5:1、大字 ≥ 3:1；**不用 alpha 压文字**来表达"次要"或"未来"，一律换语义色。
5. **零第三方 UI 库。** 日历网格、迷你月历、柱状图、分享长图全部自绘；不引 `material-icons-extended`（图标按需内联 path）。

### 与 iOS 的三处**有意**分歧（不是 bug，不要再"修回去"）

| 项 | iOS | Android | 理由 |
| --- | --- | --- | --- |
| **阅读排版字号** | Apple HIG：正文 17 / 引用 15 | M3：正文 16 / 引用 14 | 各按自己的平台规范。`content_json` 只存**语义样式**，字号从不落库，`.jdiary` 仍双向互通。 |
| **顶栏与按钮** | 悬浮玻璃胶囊（`PressableGlassIcon`） | M3 `TopAppBar` 64dp + 标准 IconButton | Material 没有可折射的玻璃层；内容层用 surface container 阶梯分层。 |
| **日历缩放手势** | **没有**捏合缩放，月↔年只由顶栏胶囊触发 | 同样**不做**捏合 | 保持同一套手势结果；加了会和"上下滑翻月"抢手势，也会绕过 morph 锚点几何。 |

---

## 二、文档地图

| 文件 | 内容 | 谁该读 |
| --- | --- | --- |
| [`01-design-system.md`](01-design-system.md) | 颜色角色、两套字号梯级的职责分界、形状与海拔、动效与触感令牌、无障碍基线 | 所有改 UI 的人；改 `ui/theme/` 之前必读 |
| [`02-adaptive-and-navigation.md`](02-adaptive-and-navigation.md) | 窗口尺寸类判据、导航形态、方向锁定、分栏规则、平板逐屏行为 | 改 `ui/adaptive/`、`ui/root/` 之前必读 |
| [`03-home.md`](03-home.md) | 首页三态（月 / 周 / 年）的几何、状态机、手势、morph 几何与动效 | 改 `ui/home/` 之前必读 |
| [`04-diary.md`](04-diary.md) | 日记阅读 / 编辑 / 多选 / 分享长图 | 改 `ui/diary/` 之前必读 |
| [`05-search-footprint-settings.md`](05-search-footprint-settings.md) | 搜索（含两组筛选与两个面板）、足迹（统计 / 趋势 / 三级树）、设置（五种卡与条件行） | 改 `ui/search/`、`ui/footprint/`、`ui/settings/` 之前必读 |
| [`06-components-and-overlays.md`](06-components-and-overlays.md) | 组件库清单（含函数签名）、弹层规范、空态 / 加载态 / 错误态的统一样式 | 新增任何 UI 之前必读 |
| [`07-ios-map-and-plan.md`](07-ios-map-and-plan.md) | iOS 文件/常量 → Android 文件/常量的**逐条映射表**、实施批次、验收清单、已知缺陷处置 | 排期与验收 |
| [`08-diary-implementation-plan.md`](08-diary-implementation-plan.md) | **日记页施工图**：三条断链的精确位置、6 步实施顺序、10 条不变量、最容易做错的 10 条 | 动日记页之前必读 |
| [`09-test-checklist.md`](09-test-checklist.md) | **前四屏验收清单**：逐条写清应该看到什么，照着走一遍即可判断有没有大问题 | 交给用户测试 |
| [`mockups/`](mockups) | 7 块设计板（HTML 源 + PNG 稿），逐屏竖屏 / 横屏 / 深色 + 交互与动效分解 | 对齐视觉时看这里 |

**iOS 侧的逐条证据**（含 `file:line`）在 `docs/ios-reference/`：

| 文件 | 覆盖 |
| --- | --- |
| `v3-home.md` | 首页状态机、几何、手势、morph、a11y |
| `v3-diary.md` | 日记页结构、排版、编辑器模型、格式栏、分享长图 |
| `v3-shell-search-footprint-settings.md` | 外壳导航、搜索、足迹、设置、通用组件、主题本地化 |
| `v3-design-system.md` | iOS 设计令牌全表、断点、动效/触感、M3 映射建议、9 处文档与代码不一致 |

**实施记录**在 [`docs/migration-progress.md`](../migration-progress.md)；**现状盘点**在 [`docs/android-ui-inventory.md`](../android-ui-inventory.md)。

---

## 三、设计稿索引

| 板面 | 内容 |
| --- | --- |
| [`mockups/png/02-home.png`](mockups/png) | 首页：月态（亮 / 深 / 空）、周态（满 / 空）、年态、平板横屏分栏、平板竖屏 |
| `03-diary.png` | 日记：阅读（满 / 空 / 页内搜索 / 深色）、编辑（键盘弹起 / 收起 / 深色）、分享面板、平板横屏 |
| `04-search.png` | 搜索：空态、结果态、加载态、无结果、两个 ModalBottomSheet、平板横屏；足迹：竖屏、空态、深色、平板横屏 |
| `05-settings.png` | 设置：竖屏、深色、平板两列；小时滚轮面板；四类对话框；多选态；未来日期 Snackbar |
| `06-design-system.png` | 亮/深 20 个颜色角色、动态取色四变体、排版梯级表、形状与海拔、组件速查、断点与导航三形态、动效表 |
| `07-extras.png` | 插图后的编辑卡、地点精度菜单、图片全屏预览、忙碌遮罩、语言下拉菜单 |
| `08-flows.png` | 三态状态机、两条 morph 的逐帧几何分解、手势地图、各页状态机与返回栈、组件状态矩阵 |

重建全部 PNG：

```bash
cd docs/android-design/mockups && bash build.sh          # 全量
bash build.sh 02-home                                     # 单块
```

> 渲染走 headless Chrome + CDP（`shoot.mjs`）：先量页面真实尺寸，再按 `clip` + 2x 截图。
> **不要改回 `--screenshot --window-size`** —— 超过约 4000 CSS px 高的板面会被 Chrome 降采样并填成灰色。

---

## 四、术语与硬约束

### 术语

| 词 | 含义 |
| --- | --- |
| **三态** | 首页的 `month` / `week` / `year` 三个显示态，同一屏内的整屏 morph |
| **morph** | 三态之间的连续过渡（月↔周 = 滑动，月↔年 = 缩放） |
| **结构单元** | 一条日记 = 一个 `edit_block`（片段）；一天可以有多个片段 |
| **设计字号 / 角色字号** | 前者是本项目的内容层梯级（编辑器段落、日历）；后者是 `MaterialTheme.typography` 的 15 个官方角色 |
| **窗口尺寸类** | Material 3 的 Compact / Medium / Expanded（本项目阈值 600 / 840dp） |

### 硬约束（改代码时不许碰）

| 项 | 约束 |
| --- | --- |
| 包名 | `com.cov.justdiary`，debug **不加** `applicationIdSuffix` |
| SDK | minSdk **33** / targetSdk 36 / compileSdk 37 |
| 第三方 UI 库 | **零**。含不引 `material-icons-extended` |
| 数据库 | `SQLiteOpenHelper` + 裸 SQL，**不用 Room**（表结构要与 iOS 逐字一致） |
| 跨端互通 | 表名 / 列名 / 字符串取值 / `content_json` 字节 / zip 布局 / `settingsJson` 的 10 个键，一律不改 |
| 字号 | **从不落库**。`content_json` 只存语义样式（`title` / `heading` / `body` / `quote`）与行内标志 |
| `start_time_utc` | 即使「自动记录时间」关闭也**照常写入**（`day_key` / `created_utc` / 排序依赖它）。该开关是**纯显示开关** |
| `region1` | 「记录新位置」与「回填历史」两条路径规则**故意不同**，不要统一 |
| 导航 | 必须是 M3 `NavigationBar` / `NavigationRail`，不自绘 |
| 构建 | 本机无系统 JDK：`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` |

---

## 五、对 v2（`ui-design-plan.md`）的裁决

v2 有 **8 处自相矛盾**（不同章节给出不同数值），代码一律跟了 §3.x。本规范给出唯一值，**以后不再讨论**：

| # | 争点 | v2 的两处说法 | **v3 裁决** | 依据 |
| --- | --- | --- | --- | --- |
| 1 | morph 时长 | 600ms（§5）vs 400ms（§3.7） | **400ms + `easing.emphasized`** | M3 `medium4`；R3 已落地并有单测 |
| 2 | 编辑器正文字号 | 17sp（§6.3）vs 16sp（§3.2） | **16sp（`bodyLarge`）** | M3 官方角色；R3 已改并钉了测试 |
| 3 | 卡片圆角 | 12 vs 14 | **16dp（`large`）** | M3 形状梯级；`Radius.card = 16` |
| 4 | 卡片圆角（备选） | 16 vs 20 | 同 #3 → **16dp** | `20` 是 `largeIncreased`，只在需要更"软"的容器上用；本项目不用 |
| 5 | 未来日期表现 | alpha 压暗 vs 语义色 | **语义色 `onSurfaceVariant`**（不用 alpha） | iOS 是整格 `alpha × 0.35`，但那样对比度只有 2.19:1（R3 实测），违反 WCAG AA 与 §1 的铁律；而且我们不画周末列配色，不存在"混淆"问题 |
| 6 | 正文列宽 | 560 vs 660 | **660**（阅读 = 编辑，同一公式 `min(660, 宽 − 32)`） | iOS `AdaptiveLayout.contentColumn(660)`；进出编辑卡片不跳 |
| 7 | 分栏是否含三态 | §5 含 vs §12 不含 | **分栏只有月态**：无周态、**无年态**（严格对齐 iOS 的 `settleToMonth()`）。切进分栏时无动画收回月态 | iOS 横屏 `settleToMonth()`；见 `02-adaptive-and-navigation.md` §4.2 |
| 8 | 年历标记 | 「有日记」与「今天」互斥 vs 可共存 | **可共存**：今天 = 圆环，有日记 = 字重加粗 + 主色；选中 = 实心圆（优先级最高） | iOS `CalendarGrids.swift` 的三个独立开关 |

> 另外 v2 §2 的 13 条缺陷（D1–D13）**全部仍然有效**，处置见 [`07-ios-map-and-plan.md`](07-ios-map-and-plan.md) §5。
> 其中 D3（`auto_time` 语义）已由两端统一为"显示开关"；D1/D2 已在 R2 修掉。

---

## 六、iOS 侧新暴露、Android 必须避开的 12 条缺陷

分析 iOS 最新代码时发现的问题（详细证据见 `docs/ios-reference/v3-*.md`）。**重建时不要照抄**：

| # | iOS 现状 | Android 怎么做 |
| --- | --- | --- |
| 1 | 搜索的加载分支是死代码：搜索进行中列表**空白、没有菊花** | 加 `LinearProgressIndicator` + 骨架行（设计稿 `04-search.png` 第 3 格） |
| 2 | 清空全部条件时不递增 `currentSearchId`，在途搜索结果仍会写回 | 清空条件必须使在途请求失效（版本号 / `Job.cancel`） |
| 3 | 前台收到深链不生效，要等下次激活才消费 → 莫名弹出编辑器 | 深链在 `onNewIntent` 立即消费 |
| 4 | 单按钮 info 弹窗的确认键标签是「取消」 | 一律用「好」/「知道了」 |
| 5 | 通知权限状态用**本地化字符串**比较 | 用枚举，不用文案比较 |
| 6 | 切语言/主题用 `.id()` 重建整棵树 → 丢失搜索条件、足迹展开态、滚动位置 | 用 `CompositionLocal` 下发语言与主题，**不重建整棵树** |
| 7 | 地点选择硬上限 20 项 + 客户端过滤 → 榜外地点搜不到 | 走 SQL `LIKE` 查询，不截断 |
| 8 | 改「新一天开始时间」会重算归属日，只在有冲突时才提示 | 改动前先算冲突数，**有冲突必须先确认**再改 |
| 9 | 设置页没有"清空数据 / 重置"，唯一破坏性是导入覆盖且**没有破坏性配色** | 「覆盖已有日记」用 `error` 色（设计稿 `05-settings.png`）；是否补"清空数据"见 §7 |
| 10 | 竖屏翻月不改选中日 → 顶栏胶囊可能显示与当前月无关的"今天" | 翻月保留选中日，但**胶囊文案此时改显具体日期**（见 `03-home.md` §3.3） |
| 11 | 分页器同时构建 3 页 → 同名无障碍元素最多 3 份 | 用 `Pager` 的 `beyondViewportPageCount = 0`，a11y 只暴露当前页 |
| 12 | 未来日期 toast 是死代码；未来日的空态也不提供写入按钮 | 未来日期**必须**给 Snackbar 反馈（设计稿 `05-settings.png` 第 6 格） |

---

## 七、待产品决策（暂定，实现时按"暂定"执行）

| # | 问题 | 暂定做法 |
| --- | --- | --- |
| Q1 | 设置页是否补「清空全部日记」入口 | **暂不补**（与 iOS 一致，降低数据风险）。若补，必须：二次确认 + 输入「删除」二字确认 + 先自动导出一份备份 |
| Q2 | 平板横屏是否保留周态 / 年态 | **都不保留**（裁决 #7，2026-09-26 已拍板）。左栏月态 + 右栏当日内容；要看年视图请转回竖屏 |
| Q3 | 平板竖屏（800 × 1280）是否也分栏 | **不分栏**（`宽 > 高` 才分栏，与 iOS 同）；日历限宽 640dp 居中 |
| Q4 | 是否加 `PredictiveBack`（Android 13+ 预测性返回） | **加**。返回目标：年态 → 月态 → 退出；编辑态 → 拦截并弹「放弃修改？」 |
| Q5 | 分享长图的深色版 | **跟随当前主题**（iOS 同）。分享出去与屏幕上看到的一致 |
