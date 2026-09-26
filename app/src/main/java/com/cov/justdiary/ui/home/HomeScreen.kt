package com.cov.justdiary.ui.home

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.cov.justdiary.R
import com.cov.justdiary.app.DebugFlags
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.Log
import com.cov.justdiary.service.Lunar
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.components.AppIcons
import com.cov.justdiary.ui.components.AssistPill
import com.cov.justdiary.ui.components.PillVariant
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.LocalReduceMotion
import com.cov.justdiary.ui.theme.Spacing
import com.cov.justdiary.ui.theme.TypeSize
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * 首页的三种形态。对应 iOS `HomeView` 的 `CalendarMode`。
 *
 * 三态之间的 morph 与 iOS 同一套数学（[CalendarLayout.monthWeekMorph] /
 * [CalendarLayout.yearMonthMorph]），只是端点几何跟着 §5.1 的新区高走。
 */
enum class HomeViewMode { MONTH, WEEK, YEAR }

/**
 * 首页日历。对应 iOS `Views/Home/HomeView.swift`，规范见
 * [`docs/android-design/03-home.md`](../../../../../docs/android-design/03-home.md)。
 *
 * ## 三态状态机（规范 §1）
 *
 * | 迁移 | 触发 | 动画 |
 * | --- | --- | --- |
 * | 月 → 周 | **点任意日期** | 滑动（`weekProgress 0→1`） |
 * | 周 → 月 | 点顶栏左胶囊 `‹ 九月` | 滑动（`weekProgress 1→0`） |
 * | 月 → 年 | 点顶栏左胶囊 `‹ 2026年` | 缩放（`yearProgress 0→1`） |
 * | 年 → 月 | 点任意迷你月 | 缩放（`yearProgress 1→0`） |
 *
 * 三条必须守住的规则（规范 §1）：
 *  1. **可见性由 `mode` 决定，不由动画值决定** —— 两个 `Animatable` 从恢复出来的 `mode`
 *     初始化，等价于"恢复时直接贴到终值"，否则"年态下旋转 + 切深色"会让年历整屏消失（缺陷 D2）。
 *  2. **morph 进行中禁止一切输入** —— 分页手势整条撤下，返回 / 点日期 / 今天三者都 guard。
 *  3. **状态不持久化语义**：冷启动恒为「今天 + 月态」；进入分栏时无动画收回月态。
 *
 * ## 平板分栏（规范 §2.3 / §4.2）
 *
 * `splitsMasterDetail`（内容宽 ≥ 548.5dp 且宽 > 高）时左右分栏：
 * 左栏月历（仅上下滑翻月 + 点日期）、右栏选中日。**分栏下没有顶栏、没有周态、没有年态**
 * —— 与 iOS 的 `settleToMonth()` 一致。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    flags: Set<String> = emptySet(),
    weekStart: String = DateUtil.WEEK_START_MONDAY,
    showLunar: Boolean = true,
    dateFormats: DateFormats? = null,
    onOpenDay: (LocalDate) -> Unit = {},
    /**
     * 「写日记 / 继续写日记」的去向：**直接进编辑器**（对应 iOS 的 `openEditor(dayKey)`）。
     *
     * 不传时退回 [onOpenDay]（阅读页），保证旧调用点行为不变。
     */
    onWrite: (LocalDate) -> Unit = onOpenDay,
    /** 当前选中日的片段（当日面板用；`null` = 这一天还没有日记）。 */
    dayBlocks: List<EditBlock>? = null,
    /**
     * `auto_time` 设置：是否显示片段的开始时间。
     *
     * 关闭时只显示地点（与 iOS 的 `vm.showsTime` 同一语义）；数据层照旧记录。
     */
    showTime: Boolean = true,
    /** 年份变化 → 让上层按年份区间重新读日记标记。 */
    onYearChange: (Int) -> Unit = {},
    /** 选中日变化 → 让上层读这一天的内容。 */
    onSelectDay: (String) -> Unit = {},
    /** 内联图片加载器（接 `MediaStore`）。 */
    imageLoader: (String) -> ImageBitmap? = { null },
    /**
     * 数据版本（导入备份、别处改过日记）。
     *
     * 变化时**重置回「今天 + 月态」**（规范 §1 规则 3）。
     * 原来 `monthKey/selectedKey/mode` 是 `rememberSaveable` 且没有任何重置路径 ——
     * 切主题会重建 Activity，`rememberSaveable` 反倒把"年态"恢复回来。
     */
    dataVersion: Int = 0,
    /** 设置版本（语言 / 主题 / 周起始 / `auto_time`）。变化时同样重置。 */
    settingsVersion: Int = 0,
) {
    val todayKey = DateUtil.dayKeyOf(today)
    // 「上下滑翻月」的**跟手位移**（px）与**收尾动画**。
    // 两个分开：拖动中直接改 `dragOffsetPx`（不启动协程），松手后才用 `settleAnim` 补动画。
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var settled by remember { mutableStateOf(false) }
    val settleAnim = remember { Animatable(0f) }
    // ⚠️ **不要**在这里把两个 state 加起来。
    //
    // `val pageOffsetPx = dragOffsetPx + settleAnim.value` 是**组合期读 state** ——
    // 拖动时每一帧都会让整个 HomeScreen 重组（含当日面板、图片），实测就是"不跟手、
    // 明显卡顿"。正确做法是让 `graphicsLayer { }` 那个 **deferred lambda** 自己去读：
    // 那样变化只让对应的 layer 失效，跳过组合与布局两个阶段。
    //
    // 唯一需要参与组合的是"要不要组合邻页"，而它只在**跨过零点**时才变 ——
    // 所以用 `derivedStateOf` 把它收敛成布尔值，避免每帧重组。
    val dragNext by remember { derivedStateOf { dragOffsetPx + settleAnim.value < -1f } }
    val dragPrev by remember { derivedStateOf { dragOffsetPx + settleAnim.value > 1f } }
    fun pageOffset(): Float = dragOffsetPx + settleAnim.value

    var monthKey by rememberSaveable { mutableStateOf(CalendarGrid.monthKeyOf(today)) }
    // 月列表的滚动状态：初始落在当前月（进程重建后也回到同一月）
    val monthListState = rememberLazyListState(
        initialFirstVisibleItemIndex = CalendarGrid.MONTH_KEYS
            .indexOf(CalendarGrid.monthKeyOf(today))
            .coerceAtLeast(0),
    )
    var selectedKey by rememberSaveable { mutableStateOf(todayKey) }
    var mode by rememberSaveable { mutableStateOf(HomeViewMode.MONTH) }

    // ⚠️ 两个进度条必须**从恢复出来的 mode 初始化**，不能固定从 0 起。
    //
    // `mode` 是 `rememberSaveable`（配置变更 / 进程重建后会恢复），而 `Animatable`
    // 不是 —— 于是"在年态下旋转屏幕再切深色"时会出现：mode 恢复成 YEAR，而
    // yearProgress 回到 0 → 年层 `alpha = yearOpacity = 0`，整屏年历消失，
    // 只剩下顶栏的「‹ 2026年」（实测缺陷，见方案 §2 D2）。
    //
    // 规则：图层的**可见性由状态（mode）决定**，动画只负责过渡。
    // 从 mode 初始化等价于"恢复时直接贴到终值"，零帧闪烁，且不影响正常动画。
    val yearProgress = remember { Animatable(if (mode == HomeViewMode.YEAR) 1f else 0f) }
    val weekProgress = remember { Animatable(if (mode == HomeViewMode.WEEK) 1f else 0f) }
    val scope = rememberCoroutineScope()
    // 「正在 morph」= 进度**停在两个端点之间**。
    //
    // ⚠️ 不能写成 `yearProgress.value > 0.01f`：年态的静止值就是 1.0，
    // 那个写法会让年态**永远**被当成"morph 中"，于是
    // ① 翻页手势被整条撤下（年态上下滑毫无反应 —— 实测确认，这是个存在已久的 bug），
    // ② 与「morph 期间撤下手势」的本意（规范 §1 规则 2）也不符。
    val morphing = (yearProgress.value > 0.01f && yearProgress.value < 0.99f) ||
        (weekProgress.value > 0.01f && weekProgress.value < 0.99f)
    // 「减弱动态效果」：系统关掉动画时两条 morph 退化为 180ms 纯交叉淡入（规范 §6.1）。
    // 判据同时认 `LocalReduceMotion`（设置里的"移除动画"）与
    // `ValueAnimator.areAnimatorsEnabled()`（开发者选项 / 无障碍），任一为真即降级。
    val systemReduceMotion = LocalReduceMotion.current
    val animatorsEnabled = remember(systemReduceMotion) {
        if (systemReduceMotion) {
            false
        } else {
            runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
        }
    }
    // morph 的动画规格 —— **Material 3 的官方令牌**：
    //   时长 `long4 = 600ms`、缓动 `easing.emphasized.decelerate = cubic-bezier(0.05, 0.7, 0.1, 1)`。
    //
    // ⚠️ **这里改回过一次**：首轮用的是 iOS 的 600ms + `timingCurve(0.2, 0.75, 0.3, 1.0)`，
    // 后来我按"Material 规范"换成了 `medium4 = 400ms` + `emphasized`（慢起步）——
    // 用户实测"400ms 太快了"，而且 `emphasized` 的慢起步会让开头一段几乎不动（iOS 的注释
    // 也专门讲过这一点）。两处都改回来：
    //   · **600ms 本身就是 M3 令牌**（`long4`），M3 也明确说"越大越复杂的过渡用越长的时长"，
    //     全屏多元素 morph 正属此类；
    //   · `emphasizedDecelerate` 是 M3 令牌里"起步带速度、尾部减速"的那条，
    //     与 iOS 选 `(0.2, 0.75, 0.3, 1.0)` 的意图一致。
    // （`MaterialTheme.motionScheme` 在 material3 1.4.0 里还是 internal，取不到，
    //   所以按官方文档/令牌实现的数值显式构造，数值与 `MotionTokens` 一致。）
    // 首轮用的是 iOS 的 `timingCurve(0.2, 0.75, 0.3, 1.0)` + 600ms，不属于 Material。
    val morphSpec = remember(animatorsEnabled, DebugFlags.slowMorph) {
        when {
            // 「减弱动态效果」优先于一切调试开关：线性、短时长（Android 无障碍要求）
            !animatorsEnabled -> tween<Float>(
                durationMillis = CalendarLayout.REDUCED_MORPH_DURATION_MS,
                easing = LinearEasing,
            )
            // 调试慢放：同样给线性，便于逐帧核对几何
            DebugFlags.slowMorph -> tween<Float>(
                durationMillis = DebugFlags.SLOW_MORPH_DURATION_MS,
                easing = LinearEasing,
            )
            else -> tween<Float>(
                durationMillis = CalendarLayout.MORPH_DURATION_MS,
                easing = CubicBezierEasing(
                    CalendarLayout.M3_EMPHASIZED_DECELERATE[0],
                    CalendarLayout.M3_EMPHASIZED_DECELERATE[1],
                    CalendarLayout.M3_EMPHASIZED_DECELERATE[2],
                    CalendarLayout.M3_EMPHASIZED_DECELERATE[3],
                ),
            )
        }
    }

    // 数据 / 设置变化 → 无动画回到「今天 + 月态」（规范 §1 规则 3）
    LaunchedEffect(dataVersion, settingsVersion) {
        if (dataVersion == 0 && settingsVersion == 0) return@LaunchedEffect   // 首帧不重置
        yearProgress.snapTo(0f)
        weekProgress.snapTo(0f)
        mode = HomeViewMode.MONTH
        selectedKey = todayKey
        monthKey = CalendarGrid.monthKeyOf(today)
    }

    // 月份 ↔ 滚动位置**双向同步**：
    // - `monthKey` 被外部改（点「今天」、年历点某月）→ 列表滚到那一项；
    // - 列表滚动**停稳**（含 fling 结束）→ 把 `monthKey` 落到那一项。
    // 两边判等后再写，不会互相触发成环。
    LaunchedEffect(monthKey) {
        val target = CalendarGrid.MONTH_KEYS.indexOf(monthKey)
        if (target >= 0 && monthListState.firstVisibleItemIndex != target) {
            monthListState.scrollToItem(target)
        }
    }
    LaunchedEffect(monthListState) {
        snapshotFlow { monthListState.isScrollInProgress to monthListState.firstVisibleItemIndex }
            .collect { (scrolling, index) ->
                if (scrolling) return@collect
                // 停稳后**吸附到整月**：慢拖（松手时几乎没有速度）不会触发 fling，
                // 于是可能停在"上个月的尾巴还露一点"的位置。这里补一次动画，
                // 让静止态永远是"一整个月"（规范 §5.1：一屏一个月）。
                // 兜底吸附：慢拖没有 fling，框架不会自动吸 —— 这里补一次动画。
                //
                // ⚠️ 判据是"**占据视口中心的那一项**"，不是"第一个可见项"。
                // 原来总吸回第一个可见项的起点，于是拖了几乎一整月也会被拉回来 ——
                // 用户："吸附的力度有些不对劲，似乎是太会吸了。"
                // 改成按中心判定后，拖过一半就顺势进到下一月，力度自然。
                if (monthListState.firstVisibleItemScrollOffset != 0) {
                    val info = monthListState.layoutInfo
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    val target = info.visibleItemsInfo.firstOrNull {
                        center >= it.offset && center < it.offset + it.size
                    } ?: info.visibleItemsInfo.firstOrNull()
                    if (target != null) monthListState.animateScrollToItem(target.index)
                }
                val key = CalendarGrid.MONTH_KEYS.getOrNull(index) ?: return@collect
                if (key != monthKey) monthKey = key
            }
    }

    // 选中日与年份变化上报给上层（与 iOS 的同名回调等价）
    LaunchedEffect(selectedKey) { onSelectDay(selectedKey) }
    val monthFirst = remember(monthKey) { CalendarGrid.monthFirstOfMonthKey(monthKey) }
    val year = monthFirst.year
    LaunchedEffect(year) { onYearChange(year) }
    val selectedDate = remember(selectedKey) { DateUtil.parseDayKey(selectedKey) ?: monthFirst }

    // morph 帧率日志（`-morph-log`）
    if (DebugFlags.morphLog) {
        LaunchedEffect(morphing) {
            if (!morphing) return@LaunchedEffect
            var frames = 0
            val start = System.nanoTime()
            while (yearProgress.value > 0.01f || weekProgress.value > 0.01f) {
                withFrameNanos { frames++ }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val average = if (frames > 0) elapsedMs.toDouble() / frames else 0.0
            Log.app.d("morph: %d 帧 / %d ms（平均 %.1f ms/帧）".format(frames, elapsedMs, average))
        }
    }

    val context = LocalContext.current
    // 未来日期用 Snackbar 反馈（规范 §6.3）。iOS 侧的同类提示是"声明了却从未调用"的死代码，
    // 而且挂在底部 overlay 上只留 24dp —— 真触发会压住浮条。这里用 M3 的 SnackbarHost。
    val snackbarHostState = remember { SnackbarHostState() }
    val futureToastText = stringResource(R.string.index_future_toast)
    val gotItLabel = stringResource(R.string.action_got_it)

    /**
     * 翻月（规范 §6.2）。
     *
     * **竖屏不动选中日**：月态里选中日本来就不可见，翻月是纯浏览动作，
     * 不该悄悄改变"我要写哪一天"的目标（副作用由顶栏胶囊的文案规则兜住，见 §3.3）。
     *
     * **分栏（平板横屏）要把选中日带到新月份的同一天**：右栏常驻显示选中日的内容，
     * 不带走的话右栏会显示"另一个月的某天"，与左栏脱节。月末按当月天数夹住。
     */
    fun pageMonth(delta: Int, carrySelection: Boolean = false) {
        val keys = CalendarGrid.MONTH_KEYS
        val index = keys.indexOf(monthKey)
        val target = index + delta
        if (target !in keys.indices) return
        monthKey = keys[target]
        if (!carrySelection) return
        val first = CalendarGrid.monthFirstOfMonthKey(keys[target])
        val day = (DateUtil.parseDayKey(selectedKey) ?: today).dayOfMonth
        val clamped = if (day < first.lengthOfMonth()) day else first.lengthOfMonth()
        selectedKey = DateUtil.dayKeyOf(first.withDayOfMonth(clamped))
    }

    fun jumpYear(delta: Int) = pageMonth(delta * 12)

    /** 月 ↔ 周 morph（选中行上移到周条位置）。 */
    fun setWeekMode(expand: Boolean) {
        if ((weekProgress.value > 0.99f) == expand) return
        mode = if (expand) HomeViewMode.WEEK else HomeViewMode.MONTH
        scope.launch { weekProgress.animateTo(if (expand) 1f else 0f, morphSpec) }
    }

    /** 周态下横向翻周：选中日跟着走一周（与 iOS `weekLayer` 的翻周一致）。 */
    fun pageWeek(delta: Int) {
        val next = selectedDate.plusDays(7L * delta)
        selectedKey = DateUtil.dayKeyOf(next)
        // 跨月时把月份也带过去，否则月↔周 morph 的起点会错位
        monthKey = CalendarGrid.monthKeyOf(next)
    }

    /** 月态 → 年历（缩放 morph）。 */
    fun openYear() {
        if (mode == HomeViewMode.YEAR) return
        mode = HomeViewMode.YEAR
        scope.launch { yearProgress.animateTo(1f, morphSpec) }
    }

    /** 周态 / 年态 → 月态。周态回退时把月份拉回选中日所在的月（与 iOS 的 `goBack` 一致）。 */
    fun backToMonth() {
        if (mode == HomeViewMode.WEEK) {
            monthKey = CalendarGrid.monthKeyOf(selectedDate)
            setWeekMode(false)
            // 用户："周视图切换到月视图……要让选中日的月份的 1 号在月视图顶部"。
            // 拨到该项的**起点**（偏移 0）—— 配合上面的 `contentPadding`，
            // 起点位置正是"标签被盖住、分割线贴住栏底"。
            scope.launch {
                monthListState.scrollToItem(CalendarGrid.MONTH_KEYS.indexOf(monthKey))
            }
            return
        }
        if (mode == HomeViewMode.YEAR) {
            mode = HomeViewMode.MONTH
            scope.launch { yearProgress.animateTo(0f, morphSpec) }
        }
    }

    /** 年历里点某个月 → 缩放 morph 回月态。 */
    fun openMonthFromYear(month: Int) {
        monthKey = CalendarGrid.monthKey(year, month)
        selectedKey = DateUtil.dayKeyOf(LocalDate.of(year, month, 1))
        mode = HomeViewMode.MONTH
        scope.launch { yearProgress.animateTo(0f, morphSpec) }
    }

    /**
     * 回到今天（规范 §5.6）。
     *
     * **周态与月态都保持当前形态**，只有年态要退回月态 —— 与 iOS 一致
     * （`todayTapped`：year → 当月；month → `resetToToday`；week → `select(now)`）。
     * 首轮一律 `mode = MONTH`，于是在周态点"今天"会被踢回月态。
     */
    fun goToday() {
        monthKey = CalendarGrid.monthKeyOf(today)
        selectedKey = todayKey
        // ⚠️ 除了改 `monthKey`，还要**直接**把列表拨回当月开头。
        //
        // 只靠 `LaunchedEffect(monthKey)` 不够：如果用户只是"在当前月里往下滑了一点"，
        // `monthKey` 根本没变（还是当月）→ effect 不重跑 → 点「今天」纹丝不动
        // （用户反馈"今天按钮没用了"）。这里无条件拨回，语义就是"回到今天"。
        scope.launch {
            monthListState.scrollToItem(CalendarGrid.MONTH_KEYS.indexOf(monthKey))
        }
        when (mode) {
            HomeViewMode.YEAR -> {
                mode = HomeViewMode.MONTH
                scope.launch { yearProgress.snapTo(0f) }
            }
            HomeViewMode.WEEK -> Unit
            HomeViewMode.MONTH -> scope.launch { yearProgress.snapTo(0f) }
        }
    }

    // 预测性返回（规范 §6 / 设计规范 Q4）：
    //   年态 / 周态 → 回月态（**不退出应用**）；月态 → 交还系统。
    // iOS 的年态只有"点迷你月"一条退路，Android 用户会先按系统返回键。
    // ⚠️ 必须把 progress flow **消费掉**。activity-compose 1.13.0 在这条协程结束后会执行
    // `check(completed) { "You must collect the progress flow" }`（PredictiveBackHandler.kt:232），
    // 不 collect 时手势永远不 complete → onCompletion 不触发 → 抛 IllegalStateException，
    // 表现为"周态 / 年态按返回键直接闪退"。
    // 这里不做跟手动画（三态 morph 由 morphSpec 驱动），所以只 collect 不消费事件。
    PredictiveBackHandler(enabled = mode != HomeViewMode.MONTH) { progress ->
        progress.collect { }
        backToMonth()
    }

    // 无障碍动作的标签要在组合期取好（`semantics` 的 lambda 不是 @Composable）
    val a11yPrevMonth = stringResource(R.string.a11y_prev_month)
    val a11yNextMonth = stringResource(R.string.a11y_next_month)
    val a11yPrevYear = stringResource(R.string.a11y_prev_year)
    val a11yNextYear = stringResource(R.string.a11y_next_year)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableH = maxHeight.value
        val availableW = maxWidth.value
        val adaptive = LocalAdaptiveLayout.current
        val split = adaptive.splitsMasterDetail
        // 竖屏 / 平板竖屏：日历限宽 640dp 居中（规范 §2.2）。不限宽时平板竖屏一格会有 100dp 宽，
        // 日号缩在中间、年份卡片更是被拉成 224dp 的空盒子。分栏时用主栏宽度公式。
        val calendarW = if (split) {
            adaptive.masterWidth
        } else {
            minOf(availableW, CalendarLayout.CALENDAR_MAX_WIDTH)
        }
        val densityScale = LocalDensity.current.density

        // 当月**实际**行数（4…6）：`displayedWeeks` 从尾部删掉"整行都不属于本月"的行。
        // 与 `weeks()` 的固定 6 行不同 —— 只画真正含当月日期的行（规范 §5.1 / §5.2），
        // 于是 5 行月的最后一行是留白，而不是一排相邻月的日期。
        val displayed = remember(monthFirst, weekStart) {
            CalendarGrid.displayedWeeks(monthFirst, weekStart)
        }
        val rows = displayed.size

        // 月态日历带 = 顶栏之外的全部可用高；行高按**固定 6 行**等分、不封顶
        // （规范 §5.1：行高跨月恒定 ⇒ 翻月不抖；6 行月铺满、5 行月留一行白）。
        val topBarH = if (split) 0f else CalendarLayout.HEADER_H
        val monthAreaH = maxOf(0f, availableH - topBarH)
        // 分栏（横屏）的密度降级（规范 §5.8）：左栏高度稀缺，按每行可用高决定农历显隐 ——
        // `perRow ≥ 60` 显示农历、`≥ 44` 隐藏、否则退成周条。竖屏不走这条（农历只由语言决定）。
        val splitHeaderH = CalendarLayout.COMPACT_MONTH_TITLE_H + CalendarLayout.COMPACT_WEEKDAY_HEADER_H
        val density = remember(monthAreaH, rows, showLunar, split) {
            if (split) {
                CalendarLayout.resolveDensity(monthAreaH - splitHeaderH, rows, showLunar)
            } else {
                CalendarLayout.Density(CalendarLayout.DensityKind.Month, showLunar)
            }
        }
        val showLunarHere = density.showLunar
        // 分栏下**真的降级为周条**：可用高低于 `NO_LUNAR_ROW_HEIGHT × 行数` 时，
        // 6 行月格每行只剩不到 38dp —— 日号与选中圆会挤在一起。
        // 原来 `resolveDensity` 算出了 `WeekStrip` 却只读 `.showLunar`，等于没降级（规范 §5.3）。
        val weekStripOnly = split && density.kind == CalendarLayout.DensityKind.WeekStrip
        val shownRows = if (weekStripOnly) 1 else rows
        // 行高 = **把整页均分给当月实际行数**（5 行月就 5 等分，6 行月就 6 等分）。
        //
        // ⚠️ 原来竖屏走 `cellHeightFor`（固定除以 6），于是 5 行的月份在页内留一整行空白，
        // 而"页高"又跟着内容变（`monthPageH = 标题 + 星期栏 + 行数 × cellH`）——
        // **相邻月的行数不同 ⇒ 两页高度不同 ⇒ 行数多的那一页会被行数少的那一页的
        // 高度裁掉**，表现为"邻页只露出一两行"（用户实测）。
        //
        // 按用户建议统一成"**每页都是同一个整页高度**、行数少就把行拉高"：
        // 页高恒定 = 不会互相裁切，也不会留空白行。
        val headerH = if (split) splitHeaderH else {
            CalendarLayout.BIG_TITLE_H + CalendarLayout.WEEKDAY_HEADER_H
        }
        // 行高**由列宽推出**（不是把整页均分给行数）：见 `dayCellHeightFor` 的说明 ——
        // 均分会让 5 行月的行距达到列距的两倍多，用户反馈"上下间隔太大"。
        val cellH = CalendarLayout.dayCellHeightFor(
            calendarW = calendarW,
            fontScale = LocalDensity.current.fontScale,
            showLunar = showLunarHere,
        )
            .let { if (weekStripOnly) it.coerceAtMost(CalendarLayout.WEEK_STRIP_H) else it }
        val gridH = CalendarLayout.monthGridHeight(shownRows, cellH)
        val metrics = remember(calendarW, cellH, showLunarHere) {
            CalendarLayout.monthPaneMetrics(
                cellW = CalendarLayout.monthCellW(calendarW),
                cellH = cellH,
                showLunar = showLunarHere,
            )
        }

        // ---- 月 ↔ 周 morph（规范 §5.5.1）----
        val weekMetrics = remember(calendarW, showLunarHere) {
            CalendarLayout.weekMetrics(calendarW, showLunarHere)
        }
        val selectedWeekIndex = remember(displayed, selectedKey) {
            displayed.indexOfFirst { row -> row.any { DateUtil.dayKeyOf(it) == selectedKey } }
                .coerceAtLeast(0)
        }
        // 降级为周条时只保留选中日所在那一行（规范 §5.3：「只画选中日所在的那一行」）
        val visibleRows = if (weekStripOnly) listOf(displayed[selectedWeekIndex]) else displayed
        val p = weekProgress.value
        val morphed = remember(p, weekMetrics, metrics, rows, selectedWeekIndex) {
            CalendarLayout.monthWeekMorph(
                progress = p,
                baseRowY = cellH * selectedWeekIndex,
                monthHeight = cellH * rows,
                monthMetrics = metrics,
                weekMetrics = weekMetrics,
                // 落点 = 网格容器的局部 y=0：容器自己会被上移 BIG_TITLE_H·p，
                // 于是选中行落在**绝对** y = 星期栏底部，正是真实周条的位置。
                stripTopY = 0f,
            )
        }
        // 每一行的**绝对**网格局部 y（构造逻辑抽到 CalendarLayout 里，便于单测钉住"p=0 =
        // 自然排布"这条不变量）。
        val rowOffsets = CalendarLayout.monthWeekRowOffsets(
            state = morphed,
            rows = rows,
            selectedWeekIndex = selectedWeekIndex,
            cellH = cellH,
        )
        // 各行**不淡出**：动效是纯粹的整体位移（用户："选中日期行和其上日期整体向上移动……
        // 而其下的日期，则向下移动直到屏幕外"）。淡出会让它看起来像"消失"而不是"移走"。
        val rowAlphas = FloatArray(rows) { 1f }
        // 选中行在 morph 中**缩小**到周条尺寸（其余行保持月格尺寸后移出）。
        // 只改度量不改行高会让"选中行上移"看起来像整行平移，收尾时和周条对不上。
        val rowMetrics = remember(p, metrics, weekMetrics, rows, selectedWeekIndex) {
            if (p <= 0.01f) {
                null
            } else {
                Array(rows) { row ->
                    if (row == selectedWeekIndex) metrics.lerp(weekMetrics, p) else metrics
                }
            }
        }
        val weekMode = p > 0.01f
        val fullBandH = maxOf(0f, availableH - topBarH)
        // 周态的日历带 = 星期栏 30 + 周条 68（大标题槽已经移出屏幕）
        val weekBandH = CalendarLayout.WEEKDAY_HEADER_H + CalendarLayout.WEEK_STRIP_H
        val bandH = CalendarLayout.lerp(fullBandH, weekBandH, p)
        val yearHeight = if (split) availableH else fullBandH
        val monthTitle = monthTitleText(monthFirst, dateFormats, context)

        // 进入分栏（横屏 / 宽窗口）时归位到月态：横屏不提供年历入口，也没有周态
        // （规范 §5.8，与 iOS 的 `settleToMonth()` 等价）。
        LaunchedEffect(split) {
            if (split) {
                mode = HomeViewMode.MONTH
                yearProgress.snapTo(0f)
                weekProgress.snapTo(0f)
            }
        }

        // 上下滑翻月 / 翻年（规范 §6）：阈值与橡皮筋复用 `DragPagerMath`。
        //
        // 可拖范围 = **一整页**。
        //
        // 取一半时（上一版）手指滑过约 4cm 内容就开始减速、随后停住 ——
        // 用户的感觉就是"顿挫、不完全跟手"。月份/年份是**无边界**的列表，
        // 没有任何理由限制拖动距离：iOS 也是让页面 1:1 跟着手指走，
        // 只在"松手"时才按阈值决定去留。
        // 这里给到整页；超出整页才交给 `rubberBand` 收尾。
        val dragRangeDp = bandH
        // 月层的**页高 = 内容实际高度**（大标题槽 + 星期栏 + 网格），不是整个日历带。
        //
        // ⚠️ 用整带高度当页高，5 行的月份（如九月）就会在页内留一整行空白，
        // 而下一页从"带高"之后才开始 —— 拖动时两页之间出现一大块**空洞**，
        // 看起来像"内容明显不全"（用户反馈）。改成按内容高度分页之后两页是**紧挨着**的；
        // 静置时多出来的那点高度落在页外（在当日面板之上），观感与原来完全一样。
        // 每一页都是**同一个整页高度**（= 日历带）：行数少时行被拉高填满，
        // 于是任意两个相邻月的高度都相同，拖动时不会互相裁切。
        val monthPageH = monthAreaH

        //
        // **morph 进行中整条手势撤下**（规范 §1 规则 2）。放在 `then` 里而不是在
        // 回调里 guard：撤下之后系统会把进行中的拖动当作取消，不会留下半截位移。
        // ⚠️ 手势要挂在**日历区的容器**上，不能挂在月层上：年态下月层是不组合的
        // （morph 收尾后整层撤下），挂在月层上会让年态**完全没有翻页手势** —— 实测回归。
        // 周态没有这条手势（规范 §6），morph 中撤下（规范 §1 规则 2）。
        // 月态不再需要自绘手势（交给 `LazyColumn` 的滚动）；周态本来就没有；只有**年态**保留。
        val pagerGesture = if (mode != HomeViewMode.YEAR || morphing) {
            Modifier
        } else {
            Modifier.pointerInput(mode, rows, cellH, split) {
                var accumulated = 0f
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        accumulated = 0f
                        // 上一次的收尾动画还在跑就先掐掉，否则位移会叠在动画上
                        scope.launch { settleAnim.stop() }
                        dragOffsetPx = 0f
                        tracker.resetTracking()
                    },
                    onDragCancel = {
                        accumulated = 0f
                        dragOffsetPx = 0f
                    },
                    onDragEnd = {
                        // ⚠️ 单位必须统一：`accumulated` 是 **dp**（下面除了 `densityScale`），
                        // 而 `VelocityTracker` 给的是 **px/s** —— 直接相加会让甩动阈值
                        // 随屏幕密度变化（参考机 2.625×）。这里把速度也换成 dp/s。
                        val velocity = tracker.calculateVelocity().y / densityScale
                        val predictedEnd =
                            accumulated + velocity * CalendarLayout.DragPagerMath.PROJECTION_SECONDS
                        val target = CalendarLayout.DragPagerMath.decide(accumulated, predictedEnd, true, true)
                        // 收尾：把已拖出的位移**动画补完到一页**（提交）或**弹回 0**（放弃），
                        // 动画结束后才真正换月 —— 于是换月那一帧的画面与动画终点完全一致，
                        // 不会出现"啪"地跳一下。
                        val pagePx = bandH * densityScale
                        // ⚠️ **方向必须留下来**。这里曾经只留了"要不要翻"（`settled: Boolean`），
                        // 把 `Next / Previous` 收敛成了一个布尔值，于是无论往哪边拖都走
                        // `pageMonth(+1)` / `jumpYear(+1)` —— **两个方向都往前翻**
                        // （实测：从一月往下拖得到二月，往上拖得到三月）。
                        val step = CalendarLayout.stepOf(target)
                        val commitPx = -step * pagePx
                        settled = step != 0
                        scope.launch {
                            settleAnim.animateTo(
                                targetValue = commitPx - dragOffsetPx,
                                animationSpec = tween(
                                    durationMillis = if (settled) CalendarLayout.DragPagerMath.PAGE_SETTLE_MS else CalendarLayout.DragPagerMath.PAGE_CANCEL_MS,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                ),
                            )
                            if (settled) {
                                // 分栏翻月带走选中日（右栏跟着走），竖屏不带走
                                if (mode == HomeViewMode.YEAR) jumpYear(step) else pageMonth(step, split)
                            }
                            // 换月后当前页就是刚才的邻页，把位移归零（同一帧内完成，看不出跳）
                            dragOffsetPx = 0f
                            settleAnim.snapTo(0f)
                            settled = false
                        }
                        accumulated = 0f
                    },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        // ⚠️ 夹的是**手势的可拖范围**，不是"一格的宽度"。
                        //
                        // 原来这里传的是 `cellH`（月格的行高，约 100dp）—— 于是手指滑过
                        // 100dp 之后内容就**不再跟手**了，而屏幕上还有 700 多 dp 的空间：
                        // 用户感觉"滑了没反应 / 不跟手"（月态与年态都一样，实测反馈）。
                        // 现在按日历带高度的一半来夹，`rubberBand` 再接管超出部分。
                        accumulated = CalendarLayout.DragPagerMath.clampOffset(
                            accumulated + delta / densityScale,
                            dragRangeDp,
                        )
                        // **跟手**：位移直接作用到日历层（规范 §6.1）。
                        // 方向：向上拖（`accumulated` 为负）→ 内容上移 → 下一个月从下方进来，
                        // 与 `decide()` 里"向上拖 = Next"一致（注意**不要**再取负号，
                        // 取了负号就变成"手往上、内容往下"，实测就是反的）。
                        // 超过一页的部分加橡皮筋阻尼，松手后回弹（规范 §9）。
                        val raw = accumulated * densityScale
                        val pagePx = dragRangeDp * densityScale
                        dragOffsetPx = if (kotlin.math.abs(raw) <= pagePx) {
                            raw
                        } else {
                            val sign = if (raw < 0f) -1f else 1f
                            sign * (pagePx + CalendarLayout.DragPagerMath.rubberBand(
                                kotlin.math.abs(raw) - pagePx,
                                pagePx,
                            ))
                        }
                    },
                )
            }
        }
        // 分栏 / 周态用的"固定尺寸 + 可选手势"修饰符
        val pagerModifier = Modifier
            .fillMaxWidth()
            .height(bandH.dp)
            .clipToBounds()
            .then(pagerGesture)

        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 分栏（横屏/宽窗口）时头部隐藏：可用高只有 ~400dp，头部会吃掉日历（与 iOS 一致）
            if (!split) {
                HomeTopBar(
                    // 月态只放年份（月份由下面 72dp 的 32sp 大标题承担）；周态放月份名 = 回月历
                    leftLabel = homeTitle(mode, year, monthFirst, dateFormats, context),
                    showLeft = mode != HomeViewMode.YEAR,
                    todayLabel = todayPillLabel(selectedDate, today, monthFirst, dateFormats, context),
                    onLeftClick = {
                        // 月态 → 年历；周态 → 回月态；年态不可点（整枚隐藏）
                        if (mode == HomeViewMode.MONTH) openYear() else backToMonth()
                    },
                    onToday = ::goToday,
                    mode = mode,
                )
            }

            val onDayClick: (LocalDate) -> Unit = { date ->
                val key = DateUtil.dayKeyOf(date)
                when {
                    // 月态：**先选中，再点同一格才进周态**（用户 2026-09-26 要求）。
                    //
                    // 原来是"点任意一天直接 morph 进周态"—— 用户反馈那样太容易误进：
                    // 想换一天看看，结果整屏切走了。现在两步走：
                    //   第一次点 → 只改选中（停留月态，当日面板/胶囊跟着变）
                    //   再点已选中的那天 → 进周态
                    !weekMode && key != selectedKey -> selectedKey = key
                    !weekMode -> setWeekMode(true)
                    // 周态：点另一天 = 换选中
                    key != selectedKey -> selectedKey = key
                    // 未来日期：只给一条 Snackbar，不打开日记（规范 §6.3）
                    // 未来日期：一条带「知道了」动作的 Snackbar（规范 §6.3 / 06 §2.1）
                    key > todayKey -> scope.launch {
                        snackbarHostState.showSnackbar(
                            message = futureToastText,
                            actionLabel = gotItLabel,
                        )
                    }
                    else -> onOpenDay(date)
                }
            }
            val calendarArgs = CalendarSectionArgs(
                // 降级为周条时只画选中日那一行（行位移/度量数组也要跟着切，否则长度对不上）
                displayed = visibleRows,
                // 降级时不需要逐行位移（只有一行，没有 morph 的余地）
                rowOffsetsOverride = if (weekStripOnly) FloatArray(1) else null,
                rowAlphasOverride = if (weekStripOnly) floatArrayOf(1f) else null,
                rowMetricsOverride = if (weekStripOnly) arrayOf(metrics) else null,
                monthFirst = monthFirst,
                metrics = metrics,
                cellHeight = cellH,
                calendarW = calendarW,
                weekStart = weekStart,
                showWeekdayHeader = mode != HomeViewMode.YEAR,
                selectedKey = selectedKey,
                todayKey = todayKey,
                flags = flags,
                showLunar = showLunarHere,
                weekMetrics = weekMetrics,
                weekProgress = p,
                rowOffsets = rowOffsets,
                rowAlphas = rowAlphas,
                rowMetrics = rowMetrics,
                selectedWeekIndex = selectedWeekIndex,
                fadeForYear = yearProgress.value,
                onDayClick = onDayClick,
                onPageMonth = { delta -> pageMonth(delta) },
                onPageWeek = ::pageWeek,
                a11yPrevMonth = a11yPrevMonth,
                a11yNextMonth = a11yNextMonth,
                a11yPrevYear = a11yPrevYear,
                a11yNextYear = a11yNextYear,
                pageMonth = { delta -> pageMonth(delta) },
                jumpYear = ::jumpYear,
                // 大标题槽只在竖屏存在；分栏时高度太紧（规范 §5.8）
                showBigTitle = !split,
                monthTitleLabel = monthTitle,
            )

        // 邻月页的 args：只换 `displayed` / `monthFirst`，morph 相关字段归零
        // （翻月拖动只可能发生在月态，此时 weekProgress 恒为 0）。
        /**
         * 任意一个月的 `CalendarSectionArgs`。
         *
         * [withMorph] 只在"当前月"那一项为真 —— 月↔周 morph 是**当月项内部**的事，
         * 其余月份的 `weekProgress` 必须是 0，否则它们会跟着一起上移。
         */
        fun monthArgsAt(key: Int, withMorph: Boolean): CalendarSectionArgs {
            val first = CalendarGrid.monthFirstOfMonthKey(key)
            val weeks = CalendarGrid.displayedWeeks(first, weekStart)
            // ⚠️ **恒定紧凑**：不再随 morph 进度切换形态。
            //
            // 原来紧凑块 34dp、完整块 102dp，判据又是连续量 `p` 的函数 ——
            // 于是 p 每跨过一次判据，那一块的高度就突跳 68dp。
            // 30fps 抽帧拍到的正是那一帧（月→周第 66 帧：大标题消失、网格上跳）。
            //
            // 现在**所有月份块一律紧凑**，高度永远一致：
            //   · 既没有"停稳后闪一下"（块高不随 monthKey 变），
            //   · 也没有 morph 中的瞬切（块高不随 p 变）。
            // 大标题与星期栏**只由顶部钉住层提供一份**，并连续跟随 p 折叠/淡出。
            val compact = true
            return calendarArgs.copy(
                displayed = weeks,
                monthFirst = first,
                weekProgress = if (withMorph) calendarArgs.weekProgress else 0f,
                rowOffsets = if (withMorph) calendarArgs.rowOffsets else FloatArray(weeks.size),
                rowAlphas = if (withMorph) calendarArgs.rowAlphas else FloatArray(weeks.size) { 1f },
                rowMetrics = if (withMorph) calendarArgs.rowMetrics else null,
                selectedWeekIndex = if (withMorph) calendarArgs.selectedWeekIndex else -1,
                monthTitleLabel = monthCompactTitleText(first, dateFormats, context),
                compactMonth = compact,
                titleIndentDp = monthsTitleIndent(weeks, calendarArgs.calendarW),
                showBigTitle = !compact && calendarArgs.showBigTitle,
                fadeForYear = if (withMorph) calendarArgs.fadeForYear else 0f,
            )
        }

            if (split) {
                // 横屏 / 宽窗口：左右双栏。左栏只有月态（分栏下没有年份入口，也没有周态）。
                // ⚠️ 三块必须在 **Row** 里（规范 §2.3）：放进 Column 会被按高度分配，
                // 表现为"日历占满左半边、右栏整块消失"。
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 左栏：上下滑翻月（翻月时把选中日带到新月份的同一天，右栏才跟着走）。
                // **宽度取 `adaptive.masterWidth`（clamp(内容宽×0.46, 260, 440)）而不是
                // `weight(1f)`** —— 两个 weight(1f) 是 50/50，1280dp 平板上左栏会占 591dp，
                // 日历只有 440dp、右边 151dp 死区，分隔线也落在屏幕正中（规范 §2.3）。
                Box(
                    modifier = Modifier
                        .width(adaptive.masterWidth.dp)
                        .fillMaxHeight()
                        .then(pagerModifier),
                ) {
                    CalendarSection(args = calendarArgs)
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                DayPanel(
                    blocks = dayBlocks,
                    selectedDate = selectedDate,
                    todayKey = todayKey,
                    dateFormats = dateFormats,
                    showTime = showTime,
                    imageLoader = imageLoader,
                    onOpenDay = { onOpenDay(selectedDate) },
                    onWrite = { onWrite(selectedDate) },
                    onToday = ::goToday,
                    showHeader = true,
                    showLunar = showLunarHere,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                }
            } else {
                when (mode) {
                    // 周态：周条 / 分隔线 / 日期标题行 / 分隔线 / 当日内容（规范 §5.1）
                    HomeViewMode.WEEK -> Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(
                            modifier = pagerModifier,
                            contentAlignment = Alignment.TopCenter,
                        ) { CalendarSection(args = calendarArgs) }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        DayPanel(
                            blocks = dayBlocks,
                            selectedDate = selectedDate,
                            todayKey = todayKey,
                            dateFormats = dateFormats,
                            showTime = showTime,
                            imageLoader = imageLoader,
                            onOpenDay = { onOpenDay(selectedDate) },
                            onWrite = { onWrite(selectedDate) },
                            onToday = null,
                            showHeader = true,
                            showLunar = showLunarHere,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }

                    // 月态 / 年态：**两层同时存在**（规范 §7.2）
                    //
                    // ⚠️ 原来这里是 `when (mode)` 两个互斥分支，而 `openYear()` 会立刻把
                    // `mode` 翻成 YEAR —— 于是 morph 前段只有年层在场，而年层的
                    // `yearOpacity = clamp01((p-0.45)/0.55)` 在 p<0.45 时是 0，
                    // **日历区整屏空白约 180ms**（反向约 220ms）。
                    //
                    // 现在两层都组合：月层按 `1 - p` 淡出并朝「源月卡片中心」等比缩小，
                    // 年层按它自己的曲线淡入并放大 —— 两者在整段区间里都是可见的。
                    // 用**等比**缩小而不是逐格重排：等比不会让字形变形，
                    // 而 400ms 内逐格重排的收益看不出来（规范 §7.2 的几何插值在此降级为
                    // 「整层缩放 + 交叉淡入」，刻度见 03-home.md）。
                    HomeViewMode.MONTH, HomeViewMode.YEAR -> {
                        val yp = yearProgress.value
                        // 拖动方向决定要组合哪一张邻页：向上拖（负位移）出「下一个月」。
                        // 没在拖的时候（|位移| < 1px）一张邻页都不组合 —— 月历是自绘的，
                        // 常驻三张会让每帧多画两遍。
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .then(pagerGesture),
                        ) {
                            // 月层：morph 结束后（年态且 p=1）完全不画，省一次整屏组合
                            if (!(mode == HomeViewMode.YEAR && yp >= 1f)) {
                                val src = CalendarLayout.yearCardRect(
                                    monthFirst.monthValue,
                                    calendarW,
                                    yearHeight,
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(bandH.dp)
                                        .clipToBounds()
                                        .graphicsLayer {
                                            // ⚠️ **与年层的淡入互补**，不能各用各的曲线。
                                            //
                                            // 原来月层是 `1 - yp`（全程线性），而年层是
                                            // `clamp01((yp - 0.25) / 0.75)`（前 25% 完全不出现）——
                                            // 两者不同步，中间出现"双重曝光"，观感就是"没对齐"
                                            // （用户："月视图到年视图的动画速率还需调一下，动画没有对齐"）。
                                            // 取同一个 `yearOpacity` 的补，两条曲线在整段区间内**和为 1**。
                                            // 唯一来源在 CalendarLayout.yearLayerOpacity（年层也用同一条）
                                            alpha = 1f - CalendarLayout.yearLayerOpacity(yp)
                                            // 1 → 0.28：接近迷你月卡片相对月网格的尺度
                                            val k = CalendarLayout.lerp(1f, 0.28f, yp)
                                            scaleX = k
                                            scaleY = k
                                            transformOrigin = TransformOrigin(
                                                pivotFractionX = ((src.x + src.w / 2f) / calendarW)
                                                    .coerceIn(0f, 1f),
                                                pivotFractionY = ((src.y + src.h / 2f) / yearHeight)
                                                    .coerceIn(0f, 1f),
                                            )
                                        },
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    // 月态 = **一张连续可滚的月列表**（用户提议，2026-09-26）：
                                    // 一个月一项、项间空一行、滚动是无级的，松手由官方
                                    // `rememberSnapFlingBehavior` 吸附到最近的月份。
                                    // 手写的分页器（PagerViewport / 位移 / 橡皮筋 / 收尾动画）就此删除。
                                    // **吸附**（用户 2026-09-26 决定加回来）。
                                    //
                                    // 起因：无级滑动可以停在"上个月还露一条边"的位置，
                                    // 而 `monthKey` 只在停稳后更新 —— 于是"滚到别的月的日期上直接点"
                                    // 时 morph 会拿上个月的行号去驱动这个月的块，上移错误的一行
                                    // （见 03-home.md §8.9.2）。用户取舍："退而求其次，把吸附加上去，
                                    // 这样每次都会是默认位置了。"
                                    //
                                    // ⚠️ 只靠 `flingBehavior` 不够：**慢拖（松手几乎没有速度）
                                    // 不会触发 fling**，仍会停在月中。所以下面还有一道
                                    // "停稳后若不在项起点就拨回去"的兜底。
                                    LazyColumn(
                                        state = monthListState,
                                        flingBehavior = rememberSnapFlingBehavior(monthListState),
                                        userScrollEnabled = !morphing,
                                        // 顶部内边距 = **钉住栏高度 − 紧凑标签行高度**。
                                        //
                                        // 这样"滚到某一项、偏移 0"时（= 静置位置）：
                                        //   · 该月的紧凑标签正好落在钉住栏**之下被盖住**
                                        //     —— 用户："不要显示日期的数字月份"（顶栏已经写了月名）；
                                        //   · 该月首行上方那条分割线正好落在**钉住栏的底边**
                                        //     —— 用户："日期上的分割线位置就是顶部固定栏的底部"。
                                        // 也就是列表内容从"标签被盖住、分割线贴住栏底"开始。
                                        contentPadding = PaddingValues(
                                            top = (CalendarLayout.BIG_TITLE_H +
                                                CalendarLayout.WEEKDAY_HEADER_H -
                                                CalendarLayout.COMPACT_MONTH_TITLE_H).dp,
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        items(
                                            count = CalendarGrid.MONTH_KEYS.size,
                                            key = { CalendarGrid.MONTH_KEYS[it] },
                                        ) { index ->
                                            val key = CalendarGrid.MONTH_KEYS[index]
                                            CalendarSection(
                                                args = monthArgsAt(key, withMorph = key == monthKey),
                                            )
                                            // 月与月之间**不再另加间隔**：紧凑月标签占的那一整行
                                            // 就是间隔（用户要求"每个月之间的分割线要跟日期间的
                                            // 间隔线一样"）。
                                        }
                                    }
                                }
                                    // === 顶部**钉住层**（用户给的 iOS 参考版面）===
                                    //
                                    // 大标题 + 星期栏只有这一份，固定在顶部、**不随滚动移动**；
                                    // 下面的日期在它下面无级滑动。声明在列表**之后**（画在上面），
                                    // 且必须带 `surface` 底色 —— 否则滚上来的日期会从标题底下透出来。
                                    //
                                    // morph 期间隐藏（`p < 0.01f`）：那时大标题要淡出、
                                    // 星期栏要跟着周条落位，由块内那一份接管（几何与原来完全一致）。
                                    // ⚠️ **不再用 `if (p < 0.01f)` 开关** —— 那是阶跃：
                                    // p 一跨过 0.01，整层突然出现/消失。现在它**始终在场**，
                                    // 只在内部连续跟随 p：大标题高度 72 -> 0、透明度 1 -> 0；
                                    // 星期栏始终可见（它就是 morph 的终点位置）。
                                    // 这样 p 从 0 到 1 的每一帧都只有连续变化。
                                    run {
                                        // 标题跟着滚动**连续换字**：取"刚露出钉住层下沿的那一项"。
                                        //
                                        // 用 `firstVisibleItemIndex` 不行 —— 上一月还露着一条边时它仍是上一月，
                                        // 于是标题要等到吸附完成才换（参考图里是滑动过程中就换掉的）。
                                        val topKey by remember {
                                            derivedStateOf {
                                                val info = monthListState.layoutInfo
                                                val probe = info.viewportStartOffset +
                                                    (CalendarLayout.BIG_TITLE_H +
                                                        CalendarLayout.WEEKDAY_HEADER_H) * 2.75f + 1f
                                                val item = info.visibleItemsInfo.firstOrNull {
                                                    probe >= it.offset && probe < it.offset + it.size
                                                } ?: info.visibleItemsInfo.firstOrNull()
                                                item?.let { CalendarGrid.MONTH_KEYS.getOrNull(it.index) }
                                            }
                                        }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                // ⚠️ 这个系数必须加在**整层**上（大标题 + 星期栏），
                                                // 不能只加大标题 —— 只加标题时，返回过程中星期栏
                                                // 从 yp=1 就跟着整层淡入，而标题要等 yp<0.25 才出现，
                                                // 两者对不上（用户实测："年视图切换月视图的时候，
                                                // 顶栏的星期直接就出现了，应该和大月份一起出现才对"）。
                                                .graphicsLayer {
                                                    alpha = 1f -
                                                        CalendarLayout.clamp01(yp * 4f)
                                                }
                                                .background(MaterialTheme.colorScheme.surface)
                                                .align(Alignment.TopCenter),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(calendarW.dp)
                                                    // 高度随 p **折叠**到 0（连续），不是开关
                                                    .height(
                                                        CalendarLayout.lerp(
                                                            CalendarLayout.BIG_TITLE_H,
                                                            0f,
                                                            p,
                                                        ).dp,
                                                    ),
                                                contentAlignment = Alignment.CenterStart,
                                            ) {
                                                Text(
                                                    text = topKey?.let {
                                                        monthTitleText(
                                                            CalendarGrid.monthFirstOfMonthKey(it),
                                                            dateFormats,
                                                            context,
                                                        )
                                                    } ?: monthTitle,
                                                    fontSize = DynamicTypeMetrics.effectiveSp(
                                                        TypeSize.display,
                                                        LocalDensity.current.fontScale,
                                                    ),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier
                                                        .padding(horizontal = 20.dp)
                                                        // 与高度折叠同步淡出（连续）
                                                        .graphicsLayer {
                                                            // 周 morph：标题按 p 淡出（与高度折叠同步）
                                                            alpha = 1f - 2f * p
                                                        },
                                                )
                                            }
                                            Box(modifier = Modifier.width(calendarW.dp)) {
                                                CalendarWeekdayHeader(weekStart = weekStart)
                                            }
                                        }
                                    }

                            }
                            // 年层：与月层同一套"跟手 + 邻页"结构。
                            //
                            // ⚠️⚠️ 这一层**必须按需组合**，不能常驻。两个实测缺陷都出在这里：
                            //
                            // 1. `graphicsLayer { alpha = 0f }` **不阻止命中测试** ——
                            //    常驻的年历画布盖在月历之上（同层 Box 里声明在后），
                            //    于是月态下每一次点击都被年历吃掉，并按**年历卡片的矩形**去分发：
                            //    用户看到的现象是"点日期没反应 / 点到了年视图的日期位置"，
                            //    也就永远进不了周态。
                            // 2. 拖动时 `PagerViewport` 会组合邻页，而邻年页传的是
                            //    `yearProgress = 1f`（不透明）—— 于是**月态下一拖就把一整屏年历
                            //    滑进来了**（用户报的"跳出年视图，然后又切回月态"）。
                            //
                            // 判据与月层对称：年态，或者 morph 已经启动（p > 0）。
                            if (mode == HomeViewMode.YEAR || yp > 0.01f) {
                            PagerViewport(
                                bandH = bandH,
                                offsetPx = { pageOffset() },
                                showNext = dragNext,
                                showPrev = dragPrev,
                                page = { offsetYears ->
                                    val neighbor = offsetYears != 0
                                    YearLayer(
                                        args = calendarArgs,
                                        year = year + offsetYears,
                                        monthFirst = monthFirst.plusYears(offsetYears.toLong()),
                                        monthTitleLabel = monthTitle,
                                        yearHeight = yearHeight,
                                        yearWidth = calendarW,
                                        monthGridH = gridH,
                                        // 当前年跟着 morph；邻年恒为"年态已就位"
                                        yearProgress = if (neighbor) ({ 1f }) else ({ yearProgress.value }),
                                        dateFormats = dateFormats,
                                        onMonthClick = { month -> openMonthFromYear(month) },
                                    )
                                },
                            )
                            }
                        }
                    }
                }
            }
        }
            // Snackbar 落在内容之上、底栏之上（底栏由 RootScreen 的 Scaffold 占位，不重叠）
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

/**
 * 年历层：盖在月历之上，透明度与缩放来自插值（对应 iOS 的 `yearLayer` + `YearMonthMorphView`）。
 *
 * 与月历一样是**整屏视图**（竖屏占满、分栏时占左栏），因此 morph 的终点取
 * [yearHeight] 而不是旧的固定下限 —— 两个端点分别与各自真实图层同源，收尾才不跳。
 */
@Composable
private fun YearLayer(
    args: CalendarSectionArgs,
    year: Int,
    monthFirst: LocalDate,
    monthTitleLabel: String,
    yearHeight: Float,
    yearWidth: Float,
    /** 月层网格的**实际**高度（行数 × 行高），morph 的月端点。 */
    monthGridH: Float,
    /**
     * morph 进度的**读取器**，不是值。
     *
     * 传值 = 组合期读 state = 动画每一帧都重组 `YearLayer` 并让这只 Canvas 重画
     * （12 张迷你月、约 500 次文本测量）。传 lambda 之后，alpha/scale 在
     * `graphicsLayer { }` 的 deferred lambda 里算 —— 那只让 layer 失效，
     * **Canvas 不需要重画**（平移/缩放/透明都是 layer 属性）。
     */
    yearProgress: () -> Float,
    dateFormats: DateFormats?,
    onMonthClick: (Int) -> Unit,

) {
    val context = LocalContext.current
    // 与进度无关的那部分几何：算一次就够（它只跟尺寸和选中月有关）
    val miniGrid = remember(yearHeight, monthGridH, yearWidth, monthFirst) {
        CalendarLayout.miniGridRect(
            CalendarLayout.yearCardRect(monthFirst.monthValue, yearWidth, yearHeight),
        )
    }
    val fullGrid = remember(yearHeight, monthGridH, yearWidth) {
        CalendarLayout.fullMonthGridRect(
            yearWidth,
            CalendarLayout.BIG_TITLE_H + CalendarLayout.WEEKDAY_HEADER_H + monthGridH,
            CalendarLayout.BIG_TITLE_H + CalendarLayout.WEEKDAY_HEADER_H,
        )
    }
    fun morphAt(progress: Float) = CalendarLayout.yearMonthMorph(
            progress = progress,
            // **源卡片 = 当前选中月的那一张**：年↔月 morph 从它自己的迷你网格长出来。
            // 首轮固定用 1 月那张卡，于是 9 月放大时动画从左上角飞过来（观感"动画不对"）。
            miniGrid = miniGrid,
            // 网格区从「大标题槽 + 星期栏」之下开始，高 = 行数 × 行高。
            // 这里必须用**封顶/派生之后**的真实网格高，否则收尾时格子尺寸对不上（会跳）。
            fullGrid = fullGrid,
            showLunar = args.showLunar,
        )
    YearCanvas(
        year = year,
        selectedMonth = monthFirst.monthValue,
        todayKey = args.todayKey,
        flags = args.flags,
        weekStart = args.weekStart,
        yearTitle = dateFormats?.year(year) ?: context.getString(R.string.date_year, year),
        zodiacLabel = dateFormats?.yearZodiac(year).orEmpty(),
        modifier = Modifier
            // ⚠️ 必须与月历用**同一个限宽**：原来这里是 `fillMaxWidth()`，只有月历层被限到
            // `calendarW`。平板竖屏下年历会拿到 ~703dp，卡片被拉成 ~220dp 的空盒，
            // 而且年历与月历横向错位 ~31dp —— morph 的锚点也就对不上了（规范 §2.2/§5.2）。
            .width(yearWidth.dp)
            .height(yearHeight.dp)
            .graphicsLayer {
                // 进度在 layer 里读（延迟）：动画每一帧只让 layer 失效，Canvas 不重画
                val morph = morphAt(yearProgress())
                alpha = morph.yearOpacity
                scaleX = morph.yearScale
                scaleY = morph.yearScale
                // 缩放锚点 = **源月卡片（选中月那一张）的中心**，不是整层的中心。
                // 用层中心时年历是从屏幕正中"炸开"的，与用户点的那个月没关系；
                // 锚在源卡片上才是"从这张卡长出来"（规范 §7.2 的 morph 起点）。
                val card = CalendarLayout.yearCardRect(
                    monthFirst.monthValue,
                    yearWidth,
                    yearHeight,
                )
                transformOrigin = TransformOrigin(
                    pivotFractionX = ((card.x + card.w / 2f) / yearWidth).coerceIn(0f, 1f),
                    pivotFractionY = ((card.y + card.h / 2f) / yearHeight).coerceIn(0f, 1f),
                )
            },
        monthLabel = { date ->
            dateFormats?.monthName(date)
                ?: context.getString(R.string.date_month_short, date.monthValue)
        },
        onMonthClick = onMonthClick,
    )
}

/**
 * 日历区的全部入参。
 *
 * 打成一个包是因为三态（月 / 周 / 年）共用同一份几何与回调，逐项传参会写出两份
 * 三十行的调用点，容易漏项 —— 首轮就是那样漏掉了 `showLunar` 与 `onPageWeek`。
 */
private data class CalendarSectionArgs(
    val displayed: List<List<LocalDate>>,
    val monthFirst: LocalDate,
    val metrics: CalendarLayout.DayMetrics,
    val cellHeight: Float,
    val calendarW: Float,
    val weekStart: String,
    val showWeekdayHeader: Boolean,
    val selectedKey: String,
    val todayKey: String,
    val flags: Set<String>,
    val showLunar: Boolean,
    val weekMetrics: CalendarLayout.DayMetrics,
    val weekProgress: Float,
    val rowOffsets: FloatArray,
    val rowAlphas: FloatArray,
    /** 非空时覆盖 [rowOffsets]（周条降级下只有一行）。 */
    val rowOffsetsOverride: FloatArray? = null,
    val rowAlphasOverride: FloatArray? = null,
    val rowMetricsOverride: Array<CalendarLayout.DayMetrics>? = null,
    /** 月↔周 morph：每一行的度量（选中行在 morph 中缩到周条尺寸）。 */
    val rowMetrics: Array<CalendarLayout.DayMetrics>?,
    val selectedWeekIndex: Int,
    val fadeForYear: Float,
    val onDayClick: (LocalDate) -> Unit,
    val onPageMonth: (Int) -> Unit,
    val onPageWeek: (Int) -> Unit,
    val a11yPrevMonth: String,
    val a11yNextMonth: String,
    val a11yPrevYear: String,
    val a11yNextYear: String,
    val pageMonth: (Int) -> Unit,
    val jumpYear: (Int) -> Unit,
    /** 是否画 72dp 的大标题槽（竖屏 true；分栏时为省高度画 false）。 */
    val showBigTitle: Boolean,
    /**
     * **非当前月**的紧凑形态：只留一个小的内联月标签，不画星期栏。
     * 参考 iOS 的版面（用户 2026-09-26 给的截图）：大标题与星期栏只有顶部那一份。
     */
    val compactMonth: Boolean = false,
    /**
     * 紧凑月标签的**横向缩进**（dp）：对齐到"1 号所在的那一列"。
     *
     * 用户反馈："日期的月份，非顶栏，显示位置不对，你现在还是在左上角，
     * 而不是对应月 1 月的上方。" —— 参考图里「1月」是压在 1 号那一列上的。
     */
    val titleIndentDp: Float = 0f,
    /** 大标题文案（`九月`）。 */
    val monthTitleLabel: String,
)

/**
 * 翻页视口：把「跟手位移 + 邻页补齐」这套结构收在一处，月层与年层共用。
 *
 * [page] 的入参是相对偏移：`0` 当前页、`+1` 下一页（下方）、`-1` 上一页（上方）。
 * 邻页只在真正拖动时组合（`showNext` / `showPrev`）—— 日历与年历都是自绘的，
 * 常驻三页会让每帧多画两遍。
 *
 * 邻页与本页之间正好隔一个 [bandH]，于是"本页滑出屏幕"的同时"邻页滑进来"，中间不露白。
 */
@Composable
private fun PagerViewport(
    bandH: Float,
    /**
     * 位移的**读取器**而不是值。
     *
     * 传值意味着调用方在组合期读了 state，于是拖动每一帧都要重组整屏；
     * 传 lambda 之后 `graphicsLayer` 的 deferred lambda 自己去读，
     * 位移变化只让 layer 失效（跳过组合与布局）。
     */
    offsetPx: () -> Float,
    showNext: Boolean,
    showPrev: Boolean,
    page: @Composable (Int) -> Unit,
) {
    val densityScale = LocalDensity.current.density
    val pagePx = bandH * densityScale
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(bandH.dp)
            .clipToBounds(),
        // ⚠️ 每一页都要**自己居中**：日历与年历都限宽 `min(640, 内容宽)`，
        // 而这里的 Box 是整宽的 —— 少了 `TopCenter`，宽屏（平板竖屏）上日历会贴左边，
        // 右边空出一大块（实测回归：包了这一层之后丢的）。
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bandH.dp)
                .graphicsLayer { translationY = offsetPx() },
            contentAlignment = Alignment.TopCenter,
        ) {
            if (showNext) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bandH.dp)
                        .graphicsLayer { translationY = pagePx },
                    contentAlignment = Alignment.TopCenter,
                ) { page(1) }
            }
            if (showPrev) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bandH.dp)
                        .graphicsLayer { translationY = -pagePx },
                    contentAlignment = Alignment.TopCenter,
                ) { page(-1) }
            }
            page(0)
        }
    }
}

/**
 * 日历区：星期栏 + （月格网格 或 周条）。
 *
 * 竖屏是整宽的一段，横屏分栏时只占左侧 [CalendarSectionArgs.calendarW]。
 * **高度由调用方给出的容器决定**，因此下面的「选中日」面板一定拿得到剩余空间。
 *
 * 月 ↔ 周 morph 期间月层的行位移/淡出由 [CalendarSectionArgs.rowOffsets] /
 * [CalendarSectionArgs.rowAlphas] 驱动，周条按 [CalendarSectionArgs.weekProgress] 淡入，
 * 两者在同一个区间里交叉 —— 这正是 iOS `calendarArea` 里月层与 morph 层同时存在的那一段。
 */
@Composable
private fun CalendarSection(args: CalendarSectionArgs) {
    val density = LocalDensity.current.density
    val fontScale = LocalDensity.current.fontScale
    val p = args.weekProgress
    // 月↔周 morph（规范 §5.5.1）：大标题槽上移淡出、星期栏上移、网格上移。
    // 三者的位移量都是 -BIG_TITLE_H·p，于是 p=1 时星期栏落在 y=0、网格容器落在 y=30，
    // 正好是周条的真实位置 —— 这就是"动画终点即真实图层"。
    // 竖屏：72dp 的 32sp 大标题；分栏：34dp 的 24sp 紧凑标题（规范 §5.8，与 iOS 的
    // `compactMonthTitleH` 一致 —— 横屏高度稀缺，但完全不画标题会让左栏失去锚点）。
    val bigTitleH = when {
        // 紧凑月（非当前月）的标签**占一整行的高度**（= 行高）：
        // 用户要求"月份与日期之间的间隔要和日期间的间隔一样"，而月份之间的那条线
        // 也要和行分隔线一样 —— 于是标签行就当成"一行"来排。
        // 用户："月份的高度是紧贴字体显示的，不是和日期一样高的。" —— 标签行只按字号撑开。
        //
        // ⚠️ 试过再把高度压到 0、用 `offset` 把标签画进首行留白（想让它更贴近 1 号），
        // 结果**整块标签消失**（0 高度父级下文字量成 0 高，加 `wrapContentHeight(unbounded)`
        // 也没救回来），已回退。标签与 1 号之间那点空隙留着下一轮用别的办法处理。
        args.compactMonth -> CalendarLayout.COMPACT_MONTH_TITLE_H
        args.showBigTitle -> CalendarLayout.BIG_TITLE_H
        else -> CalendarLayout.COMPACT_MONTH_TITLE_H
    }
    // 行位移量 = **完整标题高（BIG_TITLE_H）× p**，不是 `bigTitleH`（紧凑时 34）× p。
    //
    // 因为块虽然恒为紧凑形态（标签 34dp），**它上方还有钉住层的大标题（72dp）**，
    // 而那个标题会随 p 折叠到 0。校验两端：
    //   p=0：网格容器绝对 y = 内边距 68 + 标签 34 − 0 = **102** = 钉住栏高（72+30）✓ 正是静置位置；
    //   p=1：容器绝对 y = 68 + 34 − 72 = **30** = 折叠后钉住栏的高度 ✓ 周条正好落在星期栏之下 ✓
    // 两端都对，且随 p **线性**变化 ⇒ 中间没有任何一帧跳变。
    val shiftY = CalendarLayout.BIG_TITLE_H * p
    val titleOpacity = 1f - CalendarLayout.clamp01(p * 2f)

    Column(
        modifier = Modifier
            .width(args.calendarW.dp)
            // ⚠️ 必须 `unbounded`：周态的色带只有 98dp（星期栏 30 + 周条 68），
            // 而月层天然要 72 + 30 + 网格 ≈ 640dp。不加这一条时 Column 会被父约束压缩，
            // 最后一个子项（网格容器）分到 0 高度 —— 周条**完全不渲染**，实测就是这个现象。
            // 正确做法是让月层保持自然高度、由外层 band 负责裁剪。
            .wrapContentHeight(unbounded = true, align = Alignment.Top)
            // 年月切换的无障碍入口（iOS 侧没有标签，Android 一并补上）
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(args.a11yPrevMonth) { args.pageMonth(-1); true },
                    CustomAccessibilityAction(args.a11yNextMonth) { args.pageMonth(1); true },
                    CustomAccessibilityAction(args.a11yPrevYear) { args.jumpYear(-1); true },
                    CustomAccessibilityAction(args.a11yNextYear) { args.jumpYear(1); true },
                )
            },
    ) {
        // 大标题槽（月态与分栏都有，只是高度不同）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bigTitleH.dp)
                .graphicsLayer {
                    translationY = -bigTitleH * p * density
                    alpha = titleOpacity
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                // 月份开始前的那条分割线**不在这一层画** —— 交给 `CalendarDayGrid`
                // 的 `dividerAboveFirst`：它知道首行哪些列真正有日期，
                // 于是线只跨那段（用户："分割线范围到日期为限，而不是一整行"）。
                // 32sp 大标题（规范 §5.2）：iOS 的视觉重心就在这一行，
                // 首轮把它并进顶栏，于是整块版面又扁又空（实测缺陷 D5）。
                // 标签宽度要**量出来**才能做溢出保护：11 月 1 号落在最后一列时，
                // "列缩进 + 标签宽度"会超出日历宽度，标签就被切到屏幕外（用户实测）。
                var labelWidthPx by remember { mutableStateOf(0) }
                val labelIndent = if (!args.compactMonth) {
                    args.titleIndentDp
                } else {
                    val avail = args.calendarW - labelWidthPx / LocalDensity.current.density - 16f
                    args.titleIndentDp.coerceAtMost(avail.coerceAtLeast(0f))
                }
                Text(
                    text = args.monthTitleLabel,
                    onTextLayout = { labelWidthPx = it.size.width },
                    fontSize = when {
                        args.compactMonth ->
                            DynamicTypeMetrics.effectiveSp(TypeSize.statValue, fontScale)
                        args.showBigTitle ->
                            DynamicTypeMetrics.effectiveSp(TypeSize.display, fontScale)
                        else ->
                            DynamicTypeMetrics.effectiveSp(TypeSize.pageTitle, fontScale)
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = if (args.showBigTitle) 20.dp else 16.dp)
                        // 紧凑月标签对齐到 1 号那一列；当前月（钉住层那份）缩进为 0。
                        // 已经过溢出保护：右边缘不会超出日历宽度。
                        .offset(x = labelIndent.dp),
                )
        }
        Box(modifier = Modifier.graphicsLayer { translationY = -shiftY * density }) {
            // 紧凑月（非当前月）**不画星期栏** —— 星期栏在顶部只有一份（钉住层里的那条）。
            // 这里仍然占住同样的高度，块与块之间的间距才稳定。
            // 紧凑月**完全不占**星期栏的高度：那 30dp 正是"月份与日期之间间隔太大"的来源。
            if (!args.compactMonth) {
                CalendarWeekdayHeader(weekStart = args.weekStart, compact = !args.showBigTitle)
            }
        }
        // 高度在「月态网格」与「周条」之间插值：用 `if` 二选一会让容器瞬间跳掉一整块。
        // 不加 clipToBounds —— 周条在 morph 前段位于容器上边界之外，裁掉会露出空白；
        // 真正的裁剪交给外面那一层 band（让行的移出被裁掉，而周条仍在屏幕内）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    CalendarLayout.lerp(
                        calendarGridHeight(args.displayed.size, args.cellHeight).value,
                        CalendarLayout.WEEK_STRIP_H + bigTitleH,
                        p,
                    ).dp,
                )
                .graphicsLayer { translationY = -shiftY * density },
        ) {
            if (p > 0.01f) {
                WeekStrip(
                    week = weekOf(args.displayed, args.selectedKey),
                    weekIndex = args.selectedWeekIndex,
                    // 用比内容区更陡的曲线：返回时早退，避免残影
                    progress = CalendarLayout.weekStripAlpha(p),
                    stripWidth = minOf(args.calendarW, WEEK_STRIP_MAX_WIDTH_DP),
                    metrics = args.weekMetrics,
                    showLunar = args.showLunar,
                    selectedKey = args.selectedKey,
                    todayKey = args.todayKey,
                    flags = args.flags,
                    onDayClick = args.onDayClick,
                    onPageWeek = args.onPageWeek,
                    // 周条**就位**（不再从上方滑入 —— 那是用户否掉的"展开"观感）。
                    // 它固定在容器局部 y=0（= 选中行的落点），只随 p 淡入：
                    // 选中行**自己走上来**与它重合，整个过渡因此只有一段位移。
                    modifier = Modifier.offset(y = 0.dp),
                )
            }
            if (p < 0.99f) {
                CalendarDayGrid(
                    rows = args.displayed,
                    anchorMonth = args.monthFirst,
                    metrics = args.metrics,
                    selectedKey = args.selectedKey,
                    todayKey = args.todayKey,
                    flags = args.flags,
                    onDayClick = args.onDayClick,
                    modifier = Modifier.graphicsLayer {
                        // 年历 morph 期间月层淡出；月↔周期间由行级 alpha 负责
                        alpha = 1f - fadeProgress(args.fadeForYear)
                    },
                    // 规范 §5.2：**只画当月日期**，相邻月留空
                    showAdjacent = false,
                    showLunar = args.showLunar,
                    showDividers = true,
                    // 每个月（除列表首月）在首行上方也画一条 —— 即"月份开始之前"的那条
                    dividerAboveFirst = args.compactMonth,
                    rowOffsets = (args.rowOffsetsOverride ?: args.rowOffsets).takeIf { p > 0.01f },
                    rowAlphas = if (p > 0.01f) {
                        val base = args.rowAlphasOverride ?: args.rowAlphas
                        FloatArray(base.size) { base[it] * (1f - p) }
                    } else {
                        null
                    },
                    rowMetrics = args.rowMetricsOverride ?: args.rowMetrics,
                )
            }
        }
    }
}

/**
 * 周条：一行 7 天，横向拖动翻周。
 *
 * 对应 iOS `weekLayer` 的 `DragPagePager(axis: .horizontal)`：
 * 拖动时两页跟手（旧页淡出、新页淡入），松手按**位移或速度**决定去留，
 * 落定后把偏移清零（单位数组 `listOf(current)`，于是新页天然居中）。
 */
@Composable
private fun WeekStrip(
    week: List<LocalDate>,
    weekIndex: Int,
    /** 月↔周 morph 进度：进来时从 0 → 1 淡入。 */
    progress: Float,
    /** 周条的可用宽度（dp，已限宽并居中）。 */
    stripWidth: Float,
    metrics: CalendarLayout.DayMetrics,
    showLunar: Boolean,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    onDayClick: (LocalDate) -> Unit,
    onPageWeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 与月态同一个教训：**只组合当前一周，横向拖出去的部分就是空白**。
    //
    // 现在用"**居中的窗口 + 框架滚动**"：一次放 [WINDOW] 周，当前周固定在正中间，
    // 左右各有邻周；滑动由 `LazyRow` + 官方吸附负责，停稳后回调 `onPageWeek`，
    // 再把窗口**无动画地**拨回中间（内容同时被父层换成新的当前周）。
    val state = rememberLazyListState(initialFirstVisibleItemIndex = WEEK_WINDOW_CENTER)
    val density = LocalDensity.current
    val gridLabel = weekDescription(week)

    // **外部换了周 → 把窗口拨回正中**。
    //
    // 少了这一条时：点「今天」（或点别的日期）只会换掉 `week` 的内容，
    // 而 `LazyRow` 的滚动位置停在用户上次滑到的地方 —— 于是周条纹丝不动，
    // 看起来就是"「今天」按钮没用了"（用户实测反馈）。
    LaunchedEffect(week.firstOrNull()) {
        if (state.firstVisibleItemIndex != WEEK_WINDOW_CENTER ||
            state.firstVisibleItemScrollOffset != 0
        ) {
            state.scrollToItem(WEEK_WINDOW_CENTER)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemIndex }
            .collect { (scrolling, index) ->
                if (scrolling) return@collect
                if (state.firstVisibleItemScrollOffset != 0) state.animateScrollToItem(index)
                val moved = index - WEEK_WINDOW_CENTER
                if (moved != 0) {
                    onPageWeek(moved)
                    // 新的一周成为"当前周"之后它就在窗口正中，拨回去即可继续滑
                    state.scrollToItem(WEEK_WINDOW_CENTER)
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CalendarLayout.WEEK_STRIP_H.dp),
        contentAlignment = Alignment.Center,
    ) {
        LazyRow(
            state = state,
            // 官方吸附：惯性 + 停在一周上
            flingBehavior = rememberSnapFlingBehavior(state),
            modifier = Modifier
                .width(stripWidth.dp)
                .graphicsLayer { alpha = progress },
        ) {
            items(count = WEEK_WINDOW, key = { it }) { i ->
                val days = remember(week, i) { week.map { it.plusWeeks((i - WEEK_WINDOW_CENTER).toLong()) } }
                Box(modifier = Modifier.width(stripWidth.dp)) {
                    CalendarDayGrid(
                        rows = listOf(days),
                        anchorMonth = days.first(),
                        metrics = metrics,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        onDayClick = onDayClick,
                        showAdjacent = true,
                        showLunar = showLunar,
                        showDividers = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                // 只有**当前周**是无障碍节点：左右邻周是纯视觉的过渡内容
                                if (i == WEEK_WINDOW_CENTER) {
                                    Modifier.semantics { contentDescription = gridLabel }
                                } else {
                                    Modifier.clearAndSetSemantics { }
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** 周条窗口的周数（奇数，当前周居中）：左右各留 3 周，足够跟手且不会一次组合太多。 */
private const val WEEK_WINDOW = 7
private const val WEEK_WINDOW_CENTER = WEEK_WINDOW / 2

/**
 * 紧凑月标签的横向缩进：对齐到"1 号所在的那一列"。
 * 用户："你现在还是在左上角，而不是对应月 1 月的上方。"
 */
private fun monthsTitleIndent(weeks: List<List<LocalDate>>, calendarW: Float): Float {
    val col = weeks.firstOrNull()?.indexOfFirst { it.dayOfMonth == 1 }?.coerceAtLeast(0) ?: 0
    return col * CalendarLayout.monthCellW(calendarW)
}

/** 顶栏的高度（与 [HomeTopBar] 的 `heightIn(min = 52.dp)` 对应）。 */
private val CALENDAR_BAND_TOP = 52f

/** 周条在宽窗口里的宽度上限（与当日面板的正文列同一个量级）。 */
private const val WEEK_STRIP_MAX_WIDTH_DP = 560f

/** 横向拖动翻周的判定阈值（dp）。 */
private const val WEEK_PAGE_THRESHOLD = 60f

/** 周条形态下取选中日所在的那一周（找不到就退回第一行）。 */
private fun weekOf(weeks: List<List<LocalDate>>, selectedKey: String): List<LocalDate> =
    weeks.firstOrNull { row -> row.any { DateUtil.dayKeyOf(it) == selectedKey } } ?: weeks.first()

/** 周条的无障碍描述：`2026年9月14日 周一 那一周`。 */
@Composable
private fun weekDescription(week: List<LocalDate>): String {
    val first = week.firstOrNull() ?: return ""
    return stringResource(R.string.a11y_week_grid, DateFormats.dayTitleFor(first))
}

// ---------------------------------------------------------------------- 顶栏

/**
 * 首页顶栏（规范 §3）：两枚 M3 **assist chip**。
 *
 * | 位置 | 变体 | 月态 | 周态 | 年态 |
 * | --- | --- | --- | --- | --- |
 * | 左 | `Outlined` + 前导 `chevron_left` | `2026年`（→ 年历） | `九月`（→ 回月态） | **整枚隐藏** |
 * | 右 | `Elevated` + 前导 `calendar_today` | 相对日 / 具体日期（→ 回到今天） | 同左 | 同左 |
 *
 * **这里没有"月↔周"按钮**：iOS 没有那个入口，而且它的图标在月/周两态下完全一样，
 * 摆在「今天」旁边只会被读成第二个日历入口（缺陷 D7）。
 *
 * 年态隐藏左胶囊时必须另留退路：**系统返回键**（`PredictiveBackHandler`）与**点迷你月**
 * —— iOS 只有后者，Android 用户满屏找不到返回会先按系统返回键。
 */
@Composable
private fun HomeTopBar(
    leftLabel: String,
    showLeft: Boolean,
    todayLabel: String,
    onLeftClick: () -> Unit,
    onToday: () -> Unit,
    mode: HomeViewMode = HomeViewMode.MONTH,
) {
    // 左胶囊的无障碍文案要**跟着动作走**：月态点它是进年历，周态才是回月历
    val a11yLeftLabel = stringResource(
        if (showLeft && mode == HomeViewMode.MONTH) R.string.a11y_back_to_year else R.string.a11y_back_to_month,
    )
    val a11yTodayLabel = stringResource(R.string.a11y_scroll_to_today)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CalendarLayout.HEADER_H.dp)
            // 8dp + 48dp 胶囊 + 8dp = 64dp = HEADER_H。
            // M3 的 chip 布局高是 48dp（`minimumInteractiveComponentSize`，触控目标硬要求），
            // 所以这里不能再按"视觉 40dp"去配 12dp 上边距，否则整条会超到 72dp。
            .padding(horizontal = Spacing.page, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeft) {
            AssistPill(
                text = leftLabel,
                onClick = onLeftClick,
                leading = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                variant = PillVariant.Outlined,
                modifier = Modifier.semantics { contentDescription = a11yLeftLabel },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // 不带前导图标（用户反馈：不要日历图标）。可见文案会在
        // 今天 / 昨天 / 3 天后 / 9月26日 之间变，因此用 stateDescription 承载它，
        // contentDescription 固定为「回到今天」，TalkBack 的语义才稳定。
        AssistPill(
            text = todayLabel,
            onClick = onToday,
            // **与左胶囊同款**（用户反馈："今天按钮的样式似乎和年份月份的按钮样式
            // 不太一样，统一一下"）。同一根顶栏上两枚胶囊用两种填充是没道理的 ——
            // `Elevated` 的浅底+阴影让它看起来比左胶囊"高一级"，但两者只是
            // "导航"与"回到今天"的区别，没有主次。
            variant = PillVariant.Outlined,
            modifier = Modifier.semantics {
                contentDescription = a11yTodayLabel
                stateDescription = todayLabel
            },
        )
    }
}

/**
 * 右胶囊的文案（规范 §3.3，**Android 的一处改进**）。
 *
 * ```
 * 选中日属于当前显示的月  → 相对日（今天 / 昨天 / 明天 / 3 天前 …）
 * 否则                    → 具体日期（9月26日）
 * ```
 *
 * iOS 竖屏翻月不改选中日，于是可能出现"屏幕上是 12 月、胶囊却写着『今天』"的误导。
 * Android 保留"翻月不改选中日"（翻月是纯浏览，不该改变写入目标），但让胶囊在两者
 * 脱节时说实话。
 */
@Composable
private fun todayPillLabel(
    selectedDate: LocalDate,
    today: LocalDate,
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String {
    val sameMonth = selectedDate.year == monthFirst.year &&
        selectedDate.monthValue == monthFirst.monthValue
    if (!sameMonth) {
        return formats?.dateOnly(selectedDate)
            ?: context.getString(R.string.date_day_only, selectedDate.monthValue, selectedDate.dayOfMonth)
    }
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, selectedDate).toInt()
    return when {
        days == 0 -> stringResource(R.string.index_today)
        days == -1 -> stringResource(R.string.index_yesterday)
        days == 1 -> stringResource(R.string.index_tomorrow)
        days < -1 -> stringResource(R.string.index_days_ago, -days)
        else -> stringResource(R.string.index_days_later, days)
    }
}

/**
 * 顶栏左侧胶囊的文案。
 *
 * 月态只放**年份**（规范 §3）：月份由下面 72dp 的 32sp 大标题承担 —— 首轮把
 * "2026年 九月"整串塞进顶栏，结果既没有大标题、顶栏又比 iOS 重（缺陷 D5 的一部分）。
 */
private fun homeTitle(
    mode: HomeViewMode,
    year: Int,
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = when (mode) {
    HomeViewMode.YEAR, HomeViewMode.MONTH ->
        formats?.year(year) ?: context.getString(R.string.date_year, year)
    // 周态左侧是"返回月历"，文案用月份名（与 iOS 的 `titleText` 一致）
    HomeViewMode.WEEK -> formats?.monthFull(monthFirst)
        ?: context.getString(R.string.date_month_short, monthFirst.monthValue)
}

/** 月历大标题的文案（`九月` / `September`）。 */
/**
 * 月历里**内联**的月份标签：中文数字（`11月`）、英文缩写（`Sep`）。
 *
 * 直接用 [`DateFormats.monthName`]（= `TextStyle.SHORT`）—— 它本来就是这个用途
 * （`date_day_only` / `date_day_title` 也用它），`DateFormatsTest` 里已经断言了
 * `9月` / `Sep` 两种输出，不需要另加一个函数。
 * 固定区（钉住的大标题）用 [monthTitleText]，即 `monthFull`（`十一月` / `September`）。
 */
private fun monthCompactTitleText(
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = formats?.monthName(monthFirst)
    ?: context.getString(R.string.date_month_short, monthFirst.monthValue)

private fun monthTitleText(
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = formats?.monthFull(monthFirst)
    ?: context.getString(R.string.date_month_short, monthFirst.monthValue)

// ---------------------------------------------------------------------- 日历

/** 年历 morph 期间月层的不透明度曲线（对应 iOS `1 - clamp01((p - 0.45) / 0.55)`）。 */
private fun fadeProgress(progress: Float): Float =
    CalendarLayout.clamp01((progress - 0.45f) / 0.55f)

// ---------------------------------------------------------------------- 当日面板

/**
 * 竖屏下方的「选中日」面板 / 横屏右栏。对应 iOS `Views/Home/DayContentView.swift`
 * 与 `CalendarPaneViews.swift` 的 `DayPane`。
 *
 * 与 iOS 相同的三条业务规则：
 *  1. 只有**今天**才有写入口；未来的日期显示"还没有到来"；
 *  2. 有内容时底部给「继续写日记」，空态给「写日记」；
 *  3. 标题行显示完整日期 + 农历。
 */
@Composable
private fun DayPanel(
    blocks: List<EditBlock>?,
    selectedDate: LocalDate,
    todayKey: String,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: (String) -> ImageBitmap?,
    /** 点日记卡片 → 进编辑器（iOS 的 `openEditor`）。 */
    onOpenDay: () -> Unit,
    /** 「写日记 / 继续写日记」按钮 → 进编辑器。 */
    onWrite: () -> Unit,
    onToday: (() -> Unit)?,
    showHeader: Boolean,
    showLunar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dayKey = DateUtil.dayKeyOf(selectedDate)
    val isToday = dayKey == todayKey
    val isFuture = dayKey > todayKey
    val hasBlocks = !blocks.isNullOrEmpty()

    Column(modifier = modifier) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 设计值 40dp（分栏 44dp），但分栏右栏标题行要放一枚「今天」胶囊，
                    // M3 chip 的布局高是 48dp —— 取 min 48 让行高由它决定（规范 §2.3 的 44dp
                    // 是 iOS 的 44pt 触控目标，Android 的平台要求是 48dp）。
                    .heightIn(min = Spacing.hitTarget)
                    .padding(horizontal = Spacing.page),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = dateFormats?.dayTitle(selectedDate)
                        ?: DateFormats.dayTitleFor(selectedDate),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (showLunar) {
                    Text(
                        text = lunarLabelOf(selectedDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // 这里**不画**相对日文案（今天 / 昨天 / 明天）：
                // iOS 的当日标题行只有「日期 + 农历」，"今天"是横屏右栏标题行里那枚**按钮**。
                // 首轮两处都画，于是横屏标题行出现两个"今天"（实测缺陷 D9）。
                Spacer(modifier = Modifier.weight(1f))
                // 分栏时「今天」只在这一处（右栏标题行），竖屏周态不重复画（规范 §3.4）
                if (onToday != null) {
                    AssistPill(
                        text = stringResource(R.string.index_today),
                        onClick = onToday,
                        variant = PillVariant.Outlined,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 正文列限宽（规范 §4.2）：宽窗口里不限宽会把一行拉成 100+ 字
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasBlocks) {
                    blocks.orEmpty().forEach { block ->
                        DayBlockCard(
                            block = block,
                            dateFormats = dateFormats,
                            showTime = showTime,
                            imageLoader = imageLoader,
                            onOpenDay = onOpenDay,
                        )
                    }
                    if (isToday) {
                        Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.index_continue_write))
                        }
                    }
                } else {
                    DayEmptyState(isFuture = isFuture, isToday = isToday, onWrite = onWrite)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** 空态：图标 + 一行说明 +（仅今天）写入口。垂直居中，不靠"顶到 56dp"的硬编码。 */
@Composable
private fun DayEmptyState(isFuture: Boolean, isToday: Boolean, onWrite: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Material 图标而不是 emoji：emoji 的字形随厂商变化，也不是设计语言的一部分
            Icon(
                imageVector = if (isFuture) AppIcons.DarkMode else AppIcons.Edit,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                if (isFuture) R.string.index_future_empty else R.string.index_day_empty,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (isToday) {
            Button(onClick = onWrite) { Text(stringResource(R.string.index_write)) }
        }
    }
}

/** 一天的日记片段卡片（只读渲染；编辑与勾选都在日记页里）。 */
@Composable
private fun DayBlockCard(
    block: EditBlock,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: (String) -> ImageBitmap?,
    onOpenDay: () -> Unit,
) {
    // `auto_time` 关闭时不显示开始时间，只保留地点（与 iOS 的 `showsTime` 一致）
    val startLine = buildString {
        if (showTime && block.startTimeUtc > 0) {
            append(dateFormats?.timeOf(Instant.ofEpochMilli(block.startTimeUtc)).orEmpty())
        }
        if (block.locText.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(block.locText)
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDay),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (startLine.isNotEmpty()) {
                Text(
                    text = startLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ContentFlatten.parseContent(block.contentJson).forEach { part ->
                DayPanelPart(part = part, imageLoader = imageLoader)
            }
        }
    }
}

/** 面板里的一个段落（只读）。 */
@Composable
private fun DayPanelPart(part: ContentPart, imageLoader: (String) -> ImageBitmap?) {
    when {
        part.src != null -> {
            val bitmap = imageLoader(part.src)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        !part.items.isNullOrEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            part.items.orEmpty().forEachIndexed { index, item ->
                val done = part.done?.getOrNull(index) == true
                val bullet = when {
                    part.style != ContentPartStyle.todo -> "• "
                    done -> "☑ "
                    else -> "☐ "
                }
                Text(
                    text = bullet + item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
            }
        }

        else -> Text(
            text = ContentFlatten.flattenPart(part),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 该日期的农历全称（干支年 + 闰月 + 日），如「丙午年八月初四」。 */
private fun lunarLabelOf(date: LocalDate): String =
    Lunar.fullLabel(Lunar.ChineseCalendarProvider.lunarDateOf(date, java.time.ZoneId.systemDefault()))
