package com.cov.justdiary.ui.home

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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

    var monthKey by rememberSaveable { mutableStateOf(CalendarGrid.monthKeyOf(today)) }
    var selectedKey by rememberSaveable { mutableStateOf(todayKey) }
    var mode by rememberSaveable { mutableStateOf(HomeViewMode.MONTH) }

    // ⚠️ 两个进度条必须**从恢复出来的 mode 初始化**，不能固定从 0 起。
    //
    // `mode` 是 `rememberSaveable`（配置变更 / 进程重建后会恢复），而 `Animatable`
    // 不是 —— 于是"在年态下旋转屏幕再切深色"时会出现：mode 恢复成 YEAR，而
    // yearProgress 回到 0 → 年层 `alpha = yearOpacity = 0`，整屏年历消失，
    // 只剩下顶栏的「‹ 2026年」（实测缺陷 D2）。
    //
    // 规则：图层的**可见性由状态（mode）决定**，动画只负责过渡。
    // 从 mode 初始化等价于"恢复时直接贴到终值"，零帧闪烁，且不影响正常动画。
    val yearProgress = remember { Animatable(if (mode == HomeViewMode.YEAR) 1f else 0f) }
    val weekProgress = remember { Animatable(if (mode == HomeViewMode.WEEK) 1f else 0f) }
    val scope = rememberCoroutineScope()

    // ---- 年态「上下滑翻年」的跟手位移与收尾动画 ----
    //
    // ⚠️ 月态**不再用**这一套（它已经改成连续月历流，滚动位置与惯性都在流自己身上）。
    // 这里的位移只在**年层**的 `PagerViewport` 上起作用。
    // 两个值分开：拖动中直接改 `dragOffsetPx`（不启动协程），松手后才补动画。
    // ⚠️ **不要**在组合期把两个 state 加起来：那是组合期读 state，拖动时整屏每帧重组
    // （实测就是"不跟手、明显卡顿"）。读取放在 `graphicsLayer` 的延迟 lambda 里。
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var settled by remember { mutableStateOf(false) }
    val settleAnim = remember { Animatable(0f) }
    val dragNext by remember { derivedStateOf { dragOffsetPx + settleAnim.value < -1f } }
    val dragPrev by remember { derivedStateOf { dragOffsetPx + settleAnim.value > 1f } }
    fun pageOffset(): Float = dragOffsetPx + settleAnim.value

    // 「正在 morph」= 两条动画正在跑。**显式的布尔值**，不是"进度停在两端之间"。
    //
    // ⚠️ 判据从进度值改成标志位是这一轮的关键改动之一：进度值要在**组合期**读
    // （`yearProgress.value`），而那会让整个 HomeScreen 在 morph 的 600ms 里
    // 每帧重组一次（含当日面板、图片）。标志位只在动画的一头一尾各变一次。
    var weekMorphing by remember { mutableStateOf(mode == HomeViewMode.WEEK) }
    var yearMorphing by remember { mutableStateOf(mode == HomeViewMode.YEAR) }
    val morphing = weekMorphing || yearMorphing

    // 月↔周 morph 的**冻结源**：按下那一刻屏幕上真实的行几何。
    // 开合两个方向共用同一份（用户："从哪来到哪去"），关到月态才丢。
    var weekSource by remember { mutableStateOf<MonthFlowMorphSource?>(null) }
    // 年↔月 morph 的方向：true = 月→年（下个月那一条要淡出）、false = 年→月。
    var yearZoomingIn by remember { mutableStateOf(false) }

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
    // 后来按"Material 规范"换成了 `medium4 = 400ms` + `emphasized`（慢起步）——
    // 用户实测"400ms 太快了"，而且 `emphasized` 的慢起步会让开头一段几乎不动（iOS 的注释
    // 也专门讲过这一点）。两处都改回来：600ms 本身就是 M3 令牌（`long4`），
    // 而 `emphasizedDecelerate` 是 M3 里"起步带速度、尾部减速"的那条，意图与 iOS 一致。
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
                // ⚠️ 必须用 **iOS 那条** `timingCurve(0.2, 0.75, 0.3, 1.0)`
                //（`CalendarLayout.morphSlideAnimation`）。之前用的是 M3 的
                // `emphasizedDecelerate`：它在 **6% 时长**就走完一半路程（iOS 是 15%），
                // 于是"开头一瞬全到位、后面大半时间几乎不动" —— 观感就是"动画太快"，
                // 而且任何后段淡入（小标题、年层）都被压成瞬间。
                easing = CubicBezierEasing(
                    CalendarLayout.IOS_MORPH_CURVE[0],
                    CalendarLayout.IOS_MORPH_CURVE[1],
                    CalendarLayout.IOS_MORPH_CURVE[2],
                    CalendarLayout.IOS_MORPH_CURVE[3],
                ),
            )
        }
    }

    // 「跳到某个月」= 给连续月历流发一次显式请求（今天 / 年历点月 / 复位）。
    // token 让"连续两次跳到同一个月"也能被看到。
    var jumpToken by remember { mutableIntStateOf(0) }
    var monthJump by remember { mutableStateOf<MonthFlowJump?>(null) }
    fun jumpToMonth(key: Int, animated: Boolean) {
        jumpToken += 1
        monthJump = MonthFlowJump(key = key, animated = animated, token = jumpToken)
    }

    // 数据 / 设置变化 → 无动画回到「今天 + 月态」（规范 §1 规则 3）
    LaunchedEffect(dataVersion, settingsVersion) {
        if (dataVersion == 0 && settingsVersion == 0) return@LaunchedEffect   // 首帧不重置
        yearProgress.snapTo(0f)
        weekProgress.snapTo(0f)
        weekMorphing = false
        yearMorphing = false
        weekSource = null
        mode = HomeViewMode.MONTH
        selectedKey = todayKey
        monthKey = CalendarGrid.monthKeyOf(today)
        jumpToMonth(CalendarGrid.monthKeyOf(today), animated = false)
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
            while (weekMorphing || yearMorphing) {
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

    /** 上下滑翻月 / 无障碍动作：把连续月历流拨一个月（整月停下）。 */
    fun stepMonth(delta: Int) {
        val keys = CalendarGrid.MONTH_KEYS
        val target = keys.indexOf(monthKey) + delta
        if (target !in keys.indices) return
        monthKey = keys[target]
        jumpToMonth(keys[target], animated = true)
    }

    fun jumpYears(delta: Int) = stepMonth(delta * 12)

    /** 年态的翻年（手写分页器的收尾）：只换 `monthKey`，不碰流的滚动位置。 */
    fun pageMonth(delta: Int) {
        val keys = CalendarGrid.MONTH_KEYS
        val target = keys.indexOf(monthKey) + delta
        if (target !in keys.indices) return
        monthKey = keys[target]
    }

    /**
     * 分栏（平板横屏）滚动落定后：把选中日带到新月份的同一天，右栏才跟着一起走。
     *
     * 竖屏**不做**这件事 —— 翻月是纯浏览动作，不该悄悄改变"我要写哪一天"的目标
     * （副作用由顶栏胶囊的文案规则兜住，见规范 §3.3）。
     */
    fun carrySelectionInto(key: Int) {
        if (key == monthKey) return
        monthKey = key
        val first = CalendarGrid.monthFirstOfMonthKey(key)
        val day = selectedDate.dayOfMonth.coerceAtMost(first.lengthOfMonth())
        selectedKey = DateUtil.dayKeyOf(first.withDayOfMonth(day))
    }

    /**
     * 月↔周 morph 的两个方向（[expand] = true 进周态、false 回月态）。
     *
     * **两个方向共用同一份冻结源**（[weekSource]），于是选中那一行会精确地回到它
     * 原来的位置 —— 用户要的「从哪来到哪去」。周态里横向翻周**不会**重建源：
     * 与 iOS 一致（源是按下那一刻的快照，翻周只改当日内容）。
     */
    fun setWeekMode(expand: Boolean) {
        if (expand) {
            if (mode == HomeViewMode.WEEK || weekMorphing) return
        } else {
            if (mode != HomeViewMode.WEEK || weekMorphing) return
            // 周态回退：把「当前月」拉回选中日所在的月（与 iOS `goBack` 一致）。
            // ⚠️ **不动连续月历流的滚动位置** —— 它停在哪儿就留在哪儿（用户明确要求）。
            monthKey = CalendarGrid.monthKeyOf(selectedDate)
        }
        // ⚠️ `weekMorphing` 必须**同步**置位（不能放进 `scope.launch`）：图层的可见性由
        // `mode` + `morphing` 决定，两者若不在同一帧生效，中间就会有一帧"两层都不盖"
        // —— 表现为月↔周切换的第一帧直接跳过去（用户报的"完全是跳变"就是这一类）。
        weekMorphing = true
        mode = if (expand) HomeViewMode.WEEK else HomeViewMode.MONTH
        scope.launch {
            weekProgress.animateTo(if (expand) 1f else 0f, morphSpec)
            weekMorphing = false
            if (!expand) weekSource = null
        }
    }

    /** 周态下横向翻周：选中日跟着走一周（与 iOS `weekLayer` 的翻周一致）。 */
    fun pageWeek(delta: Int) {
        val next = selectedDate.plusDays(7L * delta)
        selectedKey = DateUtil.dayKeyOf(next)
        // 跨月时把月份也带过去（顶栏胶囊与年历源卡片都看它）
        monthKey = CalendarGrid.monthKeyOf(next)
    }

    /** 月态 → 年历（缩放 morph）。 */
    fun openYear() {
        if (mode == HomeViewMode.YEAR || morphing) return
        yearZoomingIn = true
        // 同上：标志位与 `mode` 必须同一帧生效
        yearMorphing = true
        mode = HomeViewMode.YEAR
        scope.launch {
            yearProgress.animateTo(1f, morphSpec)
            yearMorphing = false
        }
    }

    /** 年态 / 周态 → 月态。 */
    fun backToMonth() {
        when (mode) {
            HomeViewMode.WEEK -> setWeekMode(false)
            HomeViewMode.YEAR -> {
                if (yearMorphing) return
                yearZoomingIn = false
                yearMorphing = true
                mode = HomeViewMode.MONTH
                scope.launch {
                    yearProgress.animateTo(0f, morphSpec)
                    yearMorphing = false
                }
            }
            HomeViewMode.MONTH -> Unit
        }
    }

    /**
     * 年历里点某个月 → 缩放 morph 回月态。
     *
     * 连续月历流要在 morph **结束前**就停在那个月的静止位置，所以这里立刻把跳转请求
     * 发下去（morph 期间月视图是隐藏的，看不见这次跳 —— iOS 的 `jumpFlow(animated: false)`
     * 同一个道理）。用户要的「下一个月的日期会立即出现」由连续流本身承担：
     * 5 行的月份底下本来就露着下个月的小标题与第一行。
     */
    fun openMonthFromYear(month: Int) {
        if (yearMorphing) return
        val key = CalendarGrid.monthKey(year, month)
        monthKey = key
        // ⚠️ **不动选中日** —— 与 iOS 的 `HomeViewModel.selectMonth(_:)` 一致（它只写
        // `monthPage`）。这里曾经顺手把选中日设成该月 1 号，于是"从年历进月视图"会在
        // 1 号上冒出一个选中效果，而那一天根本没被选过（用户 2026-09-27 反馈）。
        // 选中日仍是原来那天：若它不在当前显示的月里，月视图就不该有任何高亮
        // （顶栏右胶囊会照实显示具体日期，见 `todayPillLabel`）。
        jumpToMonth(key, animated = false)
        yearZoomingIn = false
        // ⚠️ 这三行必须**同一帧**生效：`mode` 翻成月态、`yearMorphing` 置位、进度从 1 起。
        // 少一个就会闪一帧"完整的月视图"（用户报的年→月"完全是跳变"）。
        yearMorphing = true
        mode = HomeViewMode.MONTH
        scope.launch {
            yearProgress.animateTo(0f, morphSpec)
            yearMorphing = false
        }
    }

    /**
     * 回到今天（规范 §5.6）。
     *
     * **周态与月态都保持当前形态**，只有年态要退回月态 —— 与 iOS 一致
     * （`todayTapped`：year → 当月；month → `resetToToday`；week → `select(now)`）。
     */
    fun goToday() {
        monthKey = CalendarGrid.monthKeyOf(today)
        selectedKey = todayKey
        // 无条件发一次跳月请求：即使 `monthKey` 没变（用户只是在本月里滑动了一点），
        // 也要把流拨回本月起点 —— 语义就是"回到今天"（用户反馈过"今天按钮没用了"）。
        when (mode) {
            HomeViewMode.YEAR -> {
                jumpToMonth(CalendarGrid.monthKeyOf(today), animated = false)
                backToMonth()
            }
            HomeViewMode.MONTH -> jumpToMonth(CalendarGrid.monthKeyOf(today), animated = true)
            HomeViewMode.WEEK -> Unit
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
        val fontScale = LocalDensity.current.fontScale

        // 日历区 = 顶栏之外的全部可用高。月 / 周 / 年三态共用这一块，
        // 于是 morph 的两个端点天然同源（切换时不会有半屏的错位）。
        val topBarH = if (split) 0f else CalendarLayout.HEADER_H
        val bandH = maxOf(0f, availableH - topBarH)
        // 分栏（横屏）用紧凑的标题槽 / 星期栏：横屏只有 ~400dp 高，标题每多占 1dp
        // 就是从日期行里扣 1dp（与 iOS 的 `compactMonthTitleH` 同一组数）。
        val titleH = if (split) CalendarLayout.COMPACT_MONTH_TITLE_H else CalendarLayout.BIG_TITLE_H
        val weekdayH = if (split) {
            CalendarLayout.COMPACT_WEEKDAY_HEADER_H
        } else {
            CalendarLayout.WEEKDAY_HEADER_H
        }
        // 分栏的密度降级（规范 §5.8）：左栏高度稀缺，按每行可用高决定农历显隐。
        // 竖屏不走这条 —— 竖屏的农历显隐只由语言决定。
        val showLunarHere = if (split) {
            CalendarLayout.resolveDensity(bandH - titleH - weekdayH, 6, showLunar).showLunar
        } else {
            showLunar
        }
        // 行高：**与 iOS 同一条尺子** `(区高 − 标题槽 − 星期栏) / 6`（行高必须跨月恒定，
        // 否则滚过月份边界时格子会忽高忽低）。用户 2026-09-27 明确要求对齐 iOS ——
        // 附带好处是小标题那一行字有了 35dp 的留白（旧规则只有 19dp，会压到农历上）。
        val rowH = CalendarLayout.flowCellHeightFor(
            areaH = bandH,
            headerH = titleH + weekdayH,
            fontScale = fontScale,
            showLunar = showLunarHere,
        )
        val flowLayout = remember(weekStart, rowH) { MonthFlowLayout.of(weekStart, rowH) }
        val viewportH = maxOf(0f, bandH - titleH - weekdayH)

        // 年历的几何：年 morph 的终点是**当前月那张卡片的迷你网格**。
        // 两侧（年历页与 morph）必须用同一个函数算，否则收尾会跳。
        val yearMiniGrid = remember(monthFirst.monthValue, calendarW, bandH) {
            CalendarLayout.miniGridRect(
                CalendarLayout.yearCardRect(monthFirst.monthValue, calendarW, bandH),
            )
        }
        val monthTitle = monthTitleText(monthFirst, dateFormats, context)
        val titleText: (LocalDate) -> String = remember(dateFormats, context) {
            { month -> monthTitleText(month, dateFormats, context) }
        }
        val labelText: (LocalDate) -> String = remember(dateFormats, context) {
            { month -> monthCompactTitleText(month, dateFormats, context) }
        }
        val yearProgressReader: () -> Float = remember { { yearProgress.value } }
        val weekProgressReader: () -> Float = remember { { weekProgress.value } }
        // 连续流的滚动偏移（年↔月 morph 层要按"屏幕上那一块网格的真实位置"起手）
        val flowOffset = remember { FlowOffsetProbe() }
        // 连续月历流的初始滚动位置 = 当前月的**静止偏移**（该月第一行顶到视口顶部）。
        // `remember {}` 不带 key：只在第一次组合时取一次，之后滚动位置由流自己持有
        // —— 跨形态保持，正是用户要的「用户滑到哪，周视图返回的时候月视图就留在哪」。
        val initialFlowOffset = remember { flowLayout.restOffset(monthKey) ?: 0f }

        // 进入分栏（横屏 / 宽窗口）时归位到月态：横屏不提供年历入口，也没有周态
        // （规范 §5.8，与 iOS 的 `settleToMonth()` 等价）。
        LaunchedEffect(split) {
            if (split) {
                mode = HomeViewMode.MONTH
                yearProgress.snapTo(0f)
                weekProgress.snapTo(0f)
                yearMorphing = false
                weekMorphing = false
                weekSource = null
            }
        }

        // ---- 年态上下滑翻年：手写分页器（月态已经改成连续流，这套只剩年层在用）----
        val pagerGesture = if (mode != HomeViewMode.YEAR || morphing) {
            Modifier
        } else {
            Modifier.pointerInput(mode, rowH, split) {
                var accumulated = 0f
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        accumulated = 0f
                        scope.launch { settleAnim.stop() }
                        dragOffsetPx = 0f
                        tracker.resetTracking()
                    },
                    onDragCancel = {
                        accumulated = 0f
                        dragOffsetPx = 0f
                    },
                    onDragEnd = {
                        // 单位必须统一：`accumulated` 是 **dp**，而 `VelocityTracker` 给的是
                        // **px/s**（参考机 2.625×），所以把速度也换成 dp/s。
                        val velocity = tracker.calculateVelocity().y / densityScale
                        val predictedEnd =
                            accumulated + velocity * CalendarLayout.DragPagerMath.PROJECTION_SECONDS
                        val target = CalendarLayout.DragPagerMath.decide(accumulated, predictedEnd, true, true)
                        val pagePx = bandH * densityScale
                        // ⚠️ **方向必须留下来**（`stepOf`）：只用"要不要翻"会让两个方向都往前翻。
                        val step = CalendarLayout.stepOf(target)
                        val commitPx = -step * pagePx
                        settled = step != 0
                        scope.launch {
                            settleAnim.animateTo(
                                targetValue = commitPx - dragOffsetPx,
                                animationSpec = tween(
                                    durationMillis = if (settled) {
                                        CalendarLayout.DragPagerMath.PAGE_SETTLE_MS
                                    } else {
                                        CalendarLayout.DragPagerMath.PAGE_CANCEL_MS
                                    },
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                                ),
                            )
                            if (settled) pageMonth(step * 12)
                            dragOffsetPx = 0f
                            settleAnim.snapTo(0f)
                            settled = false
                        }
                        accumulated = 0f
                    },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        accumulated = CalendarLayout.DragPagerMath.clampOffset(
                            accumulated + delta / densityScale,
                            bandH,
                        )
                        dragOffsetPx = CalendarLayout.DragPagerMath.clampOffset(
                            accumulated * densityScale,
                            bandH * densityScale,
                        )
                    },
                )
            }
        }

        val a11yModifier = Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(a11yPrevMonth) { stepMonth(-1); true },
                CustomAccessibilityAction(a11yNextMonth) { stepMonth(1); true },
                CustomAccessibilityAction(a11yPrevYear) { jumpYears(-1); true },
                CustomAccessibilityAction(a11yNextYear) { jumpYears(1); true },
            )
        }

        /**
         * 月态点日期：**点一下就直接进周态**（与 iOS 一致）。
         *
         * 2026-09-26 曾改成"先选中、再点同一格才进周态"（当时怕误触），
         * 用户 2026-09-27 要求改回来："月视图去掉我之前说的先点击选中，再次点击跳转周视图。
         * 点击直接进入周视图就行了。"
         *
         * ⚠️ 仍然要显式判 `mode`：连续月历流**始终挂载**（滚动位置不能丢），
         * 周态 / 年态下它只是不透明度 0 —— 而**透明度 0 不阻止命中测试**。
         * 少了这一条，年历上没盖到卡片的地方点一下就会莫名进周态
         * （iOS 那边靠 `allowsHitTesting(false)` 挡，Compose 没有对应修饰符）。
         */
        val onFlowDayTap: (LocalDate, MonthFlowMorphSource) -> Unit = { date, source ->
            if (mode == HomeViewMode.MONTH && !morphing) {
                selectedKey = DateUtil.dayKeyOf(date)
                weekSource = source
                setWeekMode(true)
            }
        }

        /** 周态点日期：换选中；点已选中的那天打开日记（未来日期只给一条 Snackbar）。 */
        val onWeekDayClick: (LocalDate) -> Unit = { date ->
            val key = DateUtil.dayKeyOf(date)
            when {
                key != selectedKey -> selectedKey = key
                key > todayKey -> scope.launch {
                    snackbarHostState.showSnackbar(
                        message = futureToastText,
                        actionLabel = gotItLabel,
                    )
                }
                else -> onOpenDay(date)
            }
        }

        if (split) {
            // 横屏 / 宽窗口：左右双栏。左栏是**同一套连续月历流**（紧凑形态），
            // 右栏是选中日的内容（规范 §2.3）。
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(adaptive.masterWidth.dp)
                        .fillMaxHeight()
                        .then(a11yModifier),
                ) {
                    MonthFlow(
                        layout = flowLayout,
                        viewportH = viewportH,
                        widthDp = calendarW,
                        heightDp = bandH,
                        titleHeight = titleH,
                        weekdayHeight = weekdayH,
                        weekStart = weekStart,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        showLunar = showLunarHere,
                        initialOffsetDp = initialFlowOffset,
                        resetKey = flowLayout,
                        resetMonthKey = monthKey,
                        jump = monthJump,
                        enabled = true,
                        visible = true,
                        onDayTap = { date, _ -> selectedKey = DateUtil.dayKeyOf(date) },
                        // 翻月后把选中日带进新月份（右栏才跟着一起走）
                        onTopMonthChange = { key -> if (key != monthKey) monthKey = key },
                        onSettle = { key -> carrySelectionInto(key) },
                        compact = true,
                        titleText = titleText,
                        labelText = labelText,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
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
            // ⚠️ **iOS 的顶栏模型**：顶部只有一行**悬浮**胶囊（`HomeTopBar` 自身没有背景），
            // 日历区拿的是**整屏**高度，内容可以穿到胶囊底下。规范
            // `docs/android-design/02-adaptive-and-navigation.md` §3.4 写的就是这条
            //（"首页｜**无 `TopAppBar`**：只有一行 64dp 的胶囊行"）。
            //
            // 之前实现成了 `Column { 顶栏; 日历区 }`：顶栏**占位**、并把日历挤到下面，
            // 日历上沿再被 `clipToBounds()` 裁掉 —— 年↔月 morph 里年层放大后超出日区的
            // 那一条（第一排 1/2/3 月）就被切掉了（用户："有第一排日期有一点点遮挡"）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 唯一的裁剪边界 = 整屏：日历区自己不再裁，morph 层才能画到胶囊那一带。
                    .clipToBounds(),
            ) {
                // 日历区：位置与高度跟"顶栏占位"时**完全一致**（y = 顶栏高、高 = `bandH`），
                // 所以月/周/年三态与两个 morph 的几何一行都不用改。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = topBarH.dp)
                        .fillMaxWidth()
                        .height(bandH.dp)
                        .then(a11yModifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // ① 连续月历流：**始终挂载**（滚动位置是它自己的状态，卸载就丢）。
                    //    morph 期间与周/年态只把不透明度压到 0、撤下手势。
                    MonthFlow(
                        layout = flowLayout,
                        viewportH = viewportH,
                        widthDp = calendarW,
                        heightDp = bandH,
                        titleHeight = titleH,
                        weekdayHeight = weekdayH,
                        weekStart = weekStart,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        showLunar = showLunarHere,
                        initialOffsetDp = initialFlowOffset,
                        resetKey = flowLayout,
                        resetMonthKey = monthKey,
                        jump = monthJump,
                        enabled = mode == HomeViewMode.MONTH && !morphing,
                        // ⚠️ 可见性**不能**写成 `mode == MONTH && !morphing`：
                        // 年 morph 期间月层必须留在场上，与年层做**交叉淡入**
                        // （月层 = `1 − yearLayerOpacity(p)`，年层 = `yearLayerOpacity(p)`，
                        // 两条曲线在整段区间里和为 1）。写成上面那样的话，一进 morph 月层就
                        // 变透明，而年层要等 `p > 0.25` 才淡入 —— 中间那段**整屏空白**，
                        // 用户看到的就是"完全是跳变、没有过渡动画"。
                        // 周 morph 相反：那一行与当日内容由 morph 层自己画，月层必须让开，
                        // 否则会与 morph 层重影（两层画同一批日期）。
                        // ⚠️ 两个 morph 期间月流都**整体隐藏**：周 morph 由 `MonthWeekMorph`
                        // 重画那一行与当日内容；年 morph 由 `YearMonthMorph` 重画本月网格
                        // （后者画在年历**之上**，所以月流不能再自己画一遍）。
                        // 滚动位置不受影响 —— 隐藏 ≠ 卸载。
                        visible = mode == HomeViewMode.MONTH && !morphing,
                        onDayTap = onFlowDayTap,
                        onTopMonthChange = { key -> if (key != monthKey) monthKey = key },
                        onSettle = { key -> if (key != monthKey) monthKey = key },
                        offsetProbe = flowOffset,
                        titleText = titleText,
                        labelText = labelText,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    // ② 周层（真实图层）：morph 收尾之后才出现，位置与 morph 的终点逐像素一致。
                    if (mode == HomeViewMode.WEEK && !morphing) {
                        WeekLayer(
                            calendarW = calendarW,
                            weekStart = weekStart,
                            week = weekOf(
                                CalendarGrid.displayedWeeks(monthFirst, weekStart),
                                selectedKey,
                            ),
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            showLunar = showLunarHere,
                            dayBlocks = dayBlocks,
                            selectedDate = selectedDate,
                            dateFormats = dateFormats,
                            showTime = showTime,
                            imageLoader = imageLoader,
                            onDayClick = onWeekDayClick,
                            onPageWeek = ::pageWeek,
                            onOpenDay = { onOpenDay(selectedDate) },
                            onWrite = { onWrite(selectedDate) },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }

                    // ③ 年层（**年态静止**）：上下滑翻年的手势挂在它上面。
                    // morph 期间不画这一份 —— 那一段由 ④ 的 morph 层自己带一份年历，
                    // 两份同时在场会白画一遍 12 张迷你月。
                    if (mode == HomeViewMode.YEAR && !yearMorphing) {
                        Box(modifier = pagerGesture) {
                        PagerViewport(
                            bandH = bandH,
                            offsetPx = { pageOffset() },
                            showNext = dragNext,
                            showPrev = dragPrev,
                            page = { offsetYears ->
                                val neighbor = offsetYears != 0
                                YearLayer(
                                    year = year + offsetYears,
                                    selectedMonth = monthFirst.monthValue,
                                    selectedKey = selectedKey,
                                    todayKey = todayKey,
                                    flags = flags,
                                    weekStart = weekStart,
                                    showLunar = showLunarHere,
                                    yearHeight = bandH,
                                    yearWidth = calendarW,
                                    // 当前年跟着 morph；邻年恒为"年态已就位"
                                    yearProgress = if (neighbor) ({ 1f }) else yearProgressReader,
                                    dateFormats = dateFormats,
                                    onMonthClick = { month -> openMonthFromYear(month) },
                                )
                            },
                        )
                        }
                    }

                    // ④ 年 ↔ 月 morph 层：年历（自己一份）+ **本月网格重排进卡片**
                    //    （照着 iOS `YearMonthMorphView`：网格画在插值矩形里、字号跟着插值，
                    //      所以日期始终清晰；此前用整块仿射变换会把文字压扁）。
                    if (yearMorphing) {
                        YearMonthMorph(
                            progress = yearProgressReader,
                            zoomingIn = yearZoomingIn,
                            layout = flowLayout,
                            offsetDp = { flowOffset.value },
                            monthKey = monthKey,
                            widthDp = calendarW,
                            heightDp = bandH,
                            titleHeight = titleH,
                            weekdayHeight = weekdayH,
                            targetGrid = remember(yearMiniGrid, titleH, weekdayH) {
                                CalendarLayout.Rect(
                                    x = yearMiniGrid.x,
                                    y = yearMiniGrid.y - (titleH + weekdayH),
                                    w = yearMiniGrid.w,
                                    h = yearMiniGrid.h,
                                )
                            },
                            weekStart = weekStart,
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            showLunar = showLunarHere,
                            titleText = titleText,
                            labelText = labelText,
                            yearLayer = {
                                YearLayer(
                                    year = year,
                                    selectedMonth = monthFirst.monthValue,
                                    selectedKey = selectedKey,
                                    todayKey = todayKey,
                                    flags = flags,
                                    weekStart = weekStart,
                                    showLunar = showLunarHere,
                                    yearHeight = bandH,
                                    yearWidth = calendarW,
                                    yearProgress = yearProgressReader,
                                    dateFormats = dateFormats,
                                    onMonthClick = {},
                                    // morph 层自己在同一个矩形里画这块网格 → 卡片不再画一遍
                                    hiddenMonth = monthFirst.monthValue,
                                )
                            },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }

                    // ⑤ 月↔周 morph 层：用**冻结的源几何**重画，两个方向共用一个源。
                    val source = weekSource
                    if (weekMorphing && source != null) {
                        MonthWeekMorph(
                            progress = weekProgressReader,
                            source = source,
                            widthDp = calendarW,
                            heightDp = bandH,
                            weekStart = weekStart,
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            showLunar = showLunarHere,
                            titleText = titleText,
                            labelText = labelText,
                            content = {
                                DayContentBlock(
                                    blocks = dayBlocks,
                                    selectedDate = selectedDate,
                                    todayKey = todayKey,
                                    dateFormats = dateFormats,
                                    showTime = showTime,
                                    imageLoader = imageLoader,
                                    onOpenDay = { onOpenDay(selectedDate) },
                                    onWrite = { onWrite(selectedDate) },
                                    showLunar = showLunarHere,
                                )
                            },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }

                // 悬浮顶栏：**最后画** = 盖在日历之上；本身没有背景，年层放大后溢出的
                // 那一条会从胶囊周围（而不是"被顶栏下沿切断"）露出来。
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

/**
 * 年历层：年态（以及年 morph 期间）盖在连续月历流之上。
 *
 * 与月历一样是**整屏视图**（竖屏占满、分栏时占左栏），因此几何直接取日历区那一块
 * —— 两个端点分别与各自真实图层同源，收尾才不跳。
 *
 * `yearProgress` 传的是**读取器**而不是值：传值 = 组合期读 state = 动画每一帧都重组
 * 这一层并让 Canvas 重画（12 张迷你月、约 500 次文本测量）。传 lambda 之后，
 * alpha / scale 在 `graphicsLayer {}` 的延迟 lambda 里算 —— 那只让 layer 失效，
 * **Canvas 不需要重画**（平移 / 缩放 / 透明都是 layer 属性）。
 */
@Composable
private fun YearLayer(
    year: Int,
    selectedMonth: Int,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    weekStart: String,
    showLunar: Boolean,
    yearHeight: Float,
    yearWidth: Float,
    yearProgress: () -> Float,
    dateFormats: DateFormats?,
    onMonthClick: (Int) -> Unit,
    /** 年↔月 morph 期间不画这一张迷你月（morph 层正在同一矩形里画它，见 `YearCanvas`）。 */
    hiddenMonth: Int? = null,
) {
    val context = LocalContext.current
    // 与进度无关的那部分几何：算一次就够（它只跟尺寸和选中月有关）
    val card = remember(yearHeight, yearWidth, selectedMonth) {
        CalendarLayout.yearCardRect(selectedMonth, yearWidth, yearHeight)
    }
    YearCanvas(
        year = year,
        selectedMonth = selectedMonth,
        selectedKey = selectedKey,
        todayKey = todayKey,
        flags = flags,
        weekStart = weekStart,
        showLunar = showLunar,
        hiddenMonth = hiddenMonth,
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
                val p = yearProgress()
                // 年层的淡入曲线与月层的淡出曲线**互补**（月层用 `1 - yearOpacity`），
                // 两者在整段区间里和为 1，不会"双重曝光"、也不会"前 25% 什么都不见"。
                alpha = CalendarLayout.yearLayerOpacity(p)
                // 1.6 → 1：从"源卡片炸开"的起点略大一点，收尾正好与真实年历同尺寸。
                val k = 1f + (1f - p) * 0.6f
                scaleX = k
                scaleY = k
                // 缩放锚点 = **源月卡片（选中月那一张）的中心**，不是整层的中心。
                // 用层中心时年历是从屏幕正中"炸开"的，与用户点的那个月没关系；
                // 锚在源卡片上才是"从这张卡长出来"。（曾擅自把纵向锚点改成层顶想解决
                // 第一排被切的问题，用户指出"不要随便改锚点"——已还原，问题另行处理。）
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
 * 周层（真实图层）：morph **收尾之后**才出现。
 *
 * 排版必须与 [MonthWeekMorph] 的终态逐像素一致，否则 morph 结束换回真图层时会闪一下：
 * 星期栏 30 + 周条 80 + 分隔线 + 当日内容（起始 y = 110）。
 */
@Composable
private fun WeekLayer(
    calendarW: Float,
    weekStart: String,
    week: List<LocalDate>,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    dayBlocks: List<EditBlock>?,
    selectedDate: LocalDate,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: (String) -> ImageBitmap?,
    onDayClick: (LocalDate) -> Unit,
    onPageWeek: (Int) -> Unit,
    onOpenDay: () -> Unit,
    onWrite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = remember(calendarW, showLunar) {
        CalendarLayout.weekMetrics(calendarW, showLunar)
    }
    Column(modifier = modifier.width(calendarW.dp).fillMaxHeight()) {
        CalendarWeekdayHeader(weekStart = weekStart)
        WeekStrip(
            week = week,
            stripWidth = minOf(calendarW, WEEK_STRIP_MAX_WIDTH_DP),
            metrics = metrics,
            showLunar = showLunar,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            onDayClick = onDayClick,
            onPageWeek = onPageWeek,
        )
        DayContentBlock(
            blocks = dayBlocks,
            selectedDate = selectedDate,
            todayKey = todayKey,
            dateFormats = dateFormats,
            showTime = showTime,
            imageLoader = imageLoader,
            onOpenDay = onOpenDay,
            onWrite = onWrite,
            showLunar = showLunar,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 周态的当日内容块：分隔线 + 日期标题行 + 分隔线 + 日记内容。
 *
 * 对应 iOS 的 `dayContentBlock`：morph 层与真实周层**共用同一份**，于是 morph 收尾
 * 换回真图层时位置、内容、列宽全都不变。
 */
@Composable
private fun DayContentBlock(
    blocks: List<EditBlock>?,
    selectedDate: LocalDate,
    todayKey: String,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: (String) -> ImageBitmap?,
    onOpenDay: () -> Unit,
    onWrite: () -> Unit,
    showLunar: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DayPanel(
            blocks = blocks,
            selectedDate = selectedDate,
            todayKey = todayKey,
            dateFormats = dateFormats,
            showTime = showTime,
            imageLoader = imageLoader,
            onOpenDay = onOpenDay,
            onWrite = onWrite,
            onToday = null,
            showHeader = true,
            showLunar = showLunar,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

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
 * 周条：一行 7 天，横向拖动翻周。
 *
 * 对应 iOS `weekLayer` 的 `DragPagePager(axis: .horizontal)`：
 * 拖动时两页跟手（旧页淡出、新页淡入），松手按**位移或速度**决定去留，
 * 落定后把偏移清零（单位数组 `listOf(current)`，于是新页天然居中）。
 */
@Composable
private fun WeekStrip(
    week: List<LocalDate>,
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
            // 官方吸附：惯性 + 停在一周上（周条是**有边界**的翻页，吸附在这里是对的）
            flingBehavior = rememberSnapFlingBehavior(state),
            modifier = Modifier.width(stripWidth.dp),
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
