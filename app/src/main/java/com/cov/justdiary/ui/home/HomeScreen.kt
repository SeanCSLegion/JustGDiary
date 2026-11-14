package com.cov.justdiary.ui.home

import androidx.compose.ui.semantics.semantics
import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.app.DebugFlags
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.Log
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 当前屏幕刷新率（Hz）；取不到返回 0。`-morph-log` 用它确认"面板是不是被限帧"。 */
private fun screenRefreshRate(context: android.content.Context): Float =
    runCatching { context.display.refreshRate }.getOrDefault(0f)

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
     * 「写日记」（**空态**那一颗）的去向：直接进编辑器（对应 iOS 的 `openEditor(dayKey)`）。
     *
     * ⚠️「继续写日记」（这一天已经有内容）**不走这里**，它走 [onOpenDay] 进阅读态
     * （2026-09-28 用户返工）。不传时退回 [onOpenDay]（阅读页），保证旧调用点行为不变。
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
    imageLoader: suspend (String) -> ImageBitmap? = { null },
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

    // P0-5：`today` 可能因为换日时刻/回前台而变化。只有“用户当前选中的正好是旧的今天”时才
    // 跟着切到新的今天；如果他在看别的日期，不要把他拽回来。
    var lastTodayKey by rememberSaveable { mutableStateOf(todayKey) }
    LaunchedEffect(todayKey) {
        if (todayKey != lastTodayKey && selectedKey == lastTodayKey) {
            selectedKey = todayKey
            monthKey = CalendarGrid.monthKeyOf(today)
        }
        lastTodayKey = todayKey
    }

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

    /** 触发点（点击 / 返回键）的墙钟时刻：`-morph-log` 用它量"按下列动画第一帧"的延迟。 */
    var lastTriggerNs by remember { mutableStateOf(0L) }
    fun markTrigger(label: String) {
        if (!DebugFlags.morphLog) return
        lastTriggerNs = System.nanoTime()
        Log.app.i("触发 %s @ %d".format(label, lastTriggerNs / 1_000_000))
    }

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

    /**
     * 跑一次 morph 动画；`-morph-log` 下顺带打一行**真实墙钟耗时**。
     *
     * `animateTo` 是挂起函数，返回即代表动画跑完 —— 因此「返回时刻 − 触发时刻」就是这条
     * 动画真正占用的时间，与组合 / 重组无关（这是排查"动画是不是被改短了"的唯一可靠口径）。
     */
    suspend fun runMorph(
        name: String,
        progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
        target: Float,
    ) {
        if (!DebugFlags.morphLog) {
            progress.animateTo(target, morphSpec)
            return
        }
        val t0 = System.nanoTime()
        progress.animateTo(target, morphSpec)
        val t1 = System.nanoTime()
        Log.app.i(
            "动画 %s：%d ms（触发→动画开始 %d ms，触发→动画结束 %d ms）"
                .format(
                    name,
                    (t1 - t0) / 1_000_000,
                    (t0 - lastTriggerNs) / 1_000_000,
                    (t1 - lastTriggerNs) / 1_000_000,
                ),
        )
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

    val context = LocalContext.current

    // morph 帧率日志（`-morph-log`）
    if (DebugFlags.morphLog) {
        LaunchedEffect(morphing) {
            if (!morphing) return@LaunchedEffect
            val start = System.nanoTime()
            val trigger = lastTriggerNs
            Log.app.i("屏幕刷新率 %.1f Hz".format(screenRefreshRate(context)))
            Log.app.i(
                "morph 开始：距触发 %d ms（含重组）spec=%s"
                    .format((start - trigger) / 1_000_000, morphSpec),
            )
            // ⚠️ 判据必须直接读**快照状态对象**，不能读组合期捕获的那个布尔值：
            // 后者在动画结束的那次重组里才更新，循环会晚一帧退出。
            var frames = 0
            var maxGap = 0L
            var prev = start
            var firstFrameNs = 0L
            val gaps = ArrayList<String>()
            while (weekMorphing || yearMorphing) {
                withFrameNanos { now ->
                    frames++
                    if (frames == 1) firstFrameNs = now
                    val gap = (now - prev) / 1_000_000
                    if (gap > maxGap) maxGap = gap
                    prev = now
                    gaps.add("%d:+%d".format(frames, gap))
                }
            }
            val end = System.nanoTime()
            val elapsedMs = (end - start) / 1_000_000
            val average = if (frames > 0) elapsedMs.toDouble() / frames else 0.0
            Log.app.i(
                "morph 结束：%d 帧 / %d ms（平均 %.2f ms/帧 = %.1f fps，最长帧 %d ms）"
                    .format(frames, elapsedMs, average, 1000.0 / average, maxGap),
            )
            Log.app.i("morph 逐帧间隔：" + gaps.joinToString(" "))
            Log.app.i(
                "morph 关键点：触发→首帧 %d ms，首帧→结束 %d ms，总墙钟 %d ms"
                    .format(
                        (firstFrameNs - trigger) / 1_000_000,
                        (end - firstFrameNs) / 1_000_000,
                        (end - trigger) / 1_000_000,
                    ),
            )
        }
    }

    // 未来日期用 Snackbar 反馈（规范 §6.3）。iOS 侧的同类提示是"声明了却从未调用"的死代码，
    // 而且挂在底部 overlay 上只留 24dp —— 真触发会压住浮条。这里用 M3 的 SnackbarHost。
    val snackbarHostState = remember { SnackbarHostState() }
    val futureToastText = stringResource(R.string.index_future_toast)
    val gotItLabel = stringResource(R.string.action_got_it)

    /** 上下滑翻月 / 无障碍动作：把连续月历流拨一个月（整月停下）。 */
    fun stepMonth(delta: Int) {
        val target = HomeCalendarNavigation.stepMonthKey(monthKey, delta) ?: return
        monthKey = target
        jumpToMonth(target, animated = true)
    }

    fun jumpYears(delta: Int) = stepMonth(delta * 12)

    /**
     * 无障碍动作「前一天 / 后一天」（P1-3）。
     *
     * 选中日前后移动；跨月时月份标题与连续流一起过去（与 `carrySelectionInto` 不同，
     * 这里**不动**当月的日号 —— 用户明确点了"后一天"，就选那一天）。
     */
    fun stepDay(delta: Int) {
        val nextKey = HomeCalendarNavigation.stepDayKey(selectedKey, delta) ?: return
        selectedKey = nextKey
        val nextMonth = HomeCalendarNavigation.monthKeyOfDay(nextKey) ?: return
        if (nextMonth != monthKey) {
            monthKey = nextMonth
            jumpToMonth(nextMonth, animated = true)
        }
    }

    /** 年态的翻年（手写分页器的收尾）：只换 `monthKey`，不碰流的滚动位置。 */
    fun pageMonth(delta: Int) {
        monthKey = HomeCalendarNavigation.pageMonthKey(monthKey, delta) ?: return
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
        selectedKey = HomeCalendarNavigation.carrySelectionKey(selectedKey, key) ?: return
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
        if (DebugFlags.morphLog) markTrigger(if (expand) "enterWeek" else "exitWeek")
        weekMorphing = true
        mode = if (expand) HomeViewMode.WEEK else HomeViewMode.MONTH
        scope.launch {
            runMorph(if (expand) "月→周" else "周→月", weekProgress, if (expand) 1f else 0f)
            weekMorphing = false
            if (!expand) weekSource = null
        }
    }

    /** 周态下横向翻周：选中日跟着走一周（与 iOS `weekLayer` 的翻周一致）。 */
    fun pageWeek(delta: Int) {
        // ⚠️ **必须现读 `selectedKey`**，不能用组合期的 `selectedDate` 值。
        // 周条的翻页手势跑在 `pointerInput` 的协程里，那个块只在**第一次组合**时启动，
        // 捕获的是当时那个 `pageWeek` 闭包 —— 里面固化了当时的 `selectedDate`。
        // 于是每次翻周都算"进入周态那一天 + 7"，第二下之后永远原地踏步
        //（用户 2026-09-27："左右切换不能一直切换下去，只能切换一次似乎"；
        //  日志实测第二次调用：selectedKey 已是 2026-09-22，而 selectedDate 还是 2026-09-15）。
        val (nextKey, nextMonth) = HomeCalendarNavigation.pageWeek(selectedKey, delta) ?: return
        selectedKey = nextKey
        // 跨月时把月份也带过去（顶栏胶囊与年历源卡片都看它）
        monthKey = nextMonth
    }

    /** 月态 → 年历（缩放 morph）。 */
    fun openYear() {
        if (mode == HomeViewMode.YEAR || morphing) return
        markTrigger("openYear")
        yearZoomingIn = true
        // 同上：标志位与 `mode` 必须同一帧生效
        yearMorphing = true
        mode = HomeViewMode.YEAR
        scope.launch {
            runMorph("月→年", yearProgress, 1f)
            yearMorphing = false
        }
    }

    /** 年态 / 周态 → 月态。 */
    fun backToMonth() {
        when (mode) {
            HomeViewMode.WEEK -> {
                markTrigger("backToMonth-week")
                setWeekMode(false)
            }
            HomeViewMode.YEAR -> {
                if (yearMorphing) return
                markTrigger("backToMonth-year")
                yearZoomingIn = false
                yearMorphing = true
                mode = HomeViewMode.MONTH
                scope.launch {
                    runMorph("年→月", yearProgress, 0f)
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
        markTrigger("openMonthFromYear")
        val key = HomeCalendarNavigation.monthKeyFor(year, month)
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
            runMorph("年→月(点月)", yearProgress, 0f)
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
    val a11yPrevDay = stringResource(R.string.a11y_prev_day)
    val a11yNextDay = stringResource(R.string.a11y_next_day)

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
        // ⚠️ 行高先量化到 0.5dp 再建版面：窗口 resize / 分屏拖动 / 系统字号动画期间
        // `rowH` 是**逐帧连续变化**的，直接当 key 会让 `MonthFlowLayout.of` 每帧重建
        // 2412 个块（每次都是 2412 次 `LocalDate.of` + 列表分配）。量化后最多每 0.5dp 重建一次。
        val rowHKey = kotlin.math.round(rowH * 2f) / 2f
        val flowLayout = remember(weekStart, rowHKey) { MonthFlowLayout.of(weekStart, rowHKey) }
        val viewportH = maxOf(0f, bandH - titleH - weekdayH)

        // 年历的几何：年 morph 的终点是**当前月那张卡片的迷你网格**。
        // 两侧（年历页与 morph）必须用同一个函数算，否则收尾会跳。
        val yearMiniGrid = remember(monthFirst.monthValue, calendarW, bandH) {
            CalendarLayout.miniGridRect(
                CalendarLayout.yearCardRect(monthFirst.monthValue, calendarW, bandH),
            )
        }
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
        // ⚠️ 初始偏移**只在第一次组合时取一次**（`remember {}` 不带 key）：之后滚动位置由
        // 流自己持有 —— 跨形态保持，正是"用户滑到哪，周视图返回时月视图就留在哪"。
        // ⚠️ 启动时会**组合两次**：第一帧的视口还没量准（`boxWithConstraints` 首帧给出的
        // 高度与最终不同），`rowH` 会从 ~101dp 变成 95dp，于是 `flowLayout` 实例变了、
        // `MonthFlow` 里 `LaunchedEffect(resetKey, layout)` 会把滚动拨回"当前月的静止位置"。
        // 真机实测这一次拨动是可见的（先看到旧偏移那一段、再跳到本月）。
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
                CustomAccessibilityAction(a11yPrevDay) { stepDay(-1); true },
                CustomAccessibilityAction(a11yNextDay) { stepDay(1); true },
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
                    .fillMaxSize(),
                // ⚠️ **这里不能加 `clipToBounds()`**：`padding(Scaffold 的 innerPadding)`
                // 把这一层压到状态栏下沿，一旦裁剪，年层放大后超出日区的那一条就切在那里
                // （用户："月到年的第一排还是有被切的问题"）。不裁的话它可以一直画到窗口
                // 顶部、从状态栏底下穿过去 —— 与 iOS 的 `hFull` 模型一致。
                // 日历区自己该裁的地方（连续流的内容层）另有 clip，不受影响。
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
                        // ⚠️ 按**进度**交班，不按 `morphing` 标志位（理由见下面周层那一处）。
                        // 月态 + 周进度回到 0 ⇒ 真实月流接手；年 morph 期间一律不画
                        // （那一段由 `YearMonthMorph` 重画本月网格）。
                        visible = HomeCalendarNavigation.showMonthFlow(
                            mode = mode,
                            yearMorphing = yearMorphing,
                            weekProgress = weekProgress.value,
                        ),
                        onDayTap = onFlowDayTap,
                        onTopMonthChange = { key -> if (key != monthKey) monthKey = key },
                        onSettle = { key -> if (key != monthKey) monthKey = key },
                        offsetProbe = flowOffset,
                        titleText = titleText,
                        labelText = labelText,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    // ② 周层（真实图层）：morph 收尾之后才出现，位置与 morph 的终点逐像素一致。
                    // ⚠️ 交班判据必须是**进度**，不能是 `!morphing`。
                    //
                    // `weekMorphing` 要到协程收尾（`runMorph` 返回之后）才复位，而进度在那之前
                    // 就已经到 1 了 —— 这一段时间里 **morph 层和真实周层同时可见**，两块都画
                    // 那 7 个日号，只是位置上差几个像素，看起来就是"好多日号重叠起来了"
                    //（用户 2026-09-27 截图实锤）。morph 层到终点即让位，两者永不同时在场上。
                    // ⚠️ 前半句 `mode == WEEK` **不能省**：我一度写成 `mode != WEEK || …`，
                    // 结果月视图与年视图下都常驻了一层周条（用户 2026-09-27 当场发现）。
                    // 真实周层只在**周态**存在；月/年态下这一层必须完全不在场上。
                    //
                    // ⚠️ **必须带 `weekProgress.value >= 1f`**，否则交班会漏一帧：
                    // morph 层在 `p >= 1` 撤掉，而 `morphing` 要到协程收尾才变 false ——
                    // 中间那几帧**两层都不在场**，画面就是"跳一下/闪一下"
                    //（用户 2026-09-27："切换时候的动画最后都有跳动闪现"）。
                    // 带上进度条件后两边严格互补：任意时刻恰好一层在画。
                    //
                    // ⚠️ 反过来，**不能**再带 `!morphing`：那样"真层挂上"要等协程收尾，
                    // 与 morph 层的撤下条件（同上）错开 1~2 帧 —— 实测录屏里那一帧
                    // 日期区是**整片空的**（当日内容那一大块首次组合要花一帧以上），
                    // 下一帧才补上，观感就是"最后闪一下"。现在真层在 p 到端点那一帧就挂上，
                    // 而 morph 层继续在它**上面**多盖几帧（见 morph 层的条件）。
                    val weekSettled = HomeCalendarNavigation.showWeekLayer(
                        mode = mode,
                        weekProgress = weekProgress.value,
                    )
                    if (weekSettled) {
                        WeekLayer(
                            calendarW = calendarW,
                            weekStart = weekStart,
                            // ⚠️ 周条显示的必须是**选中日所在的那一周**，不能"在当前月的
                            // 周列表里找、找不到就退回第一周"：连续流里可以点到露出来的
                            // 下个月那几天（比如流停在 9 月、点到 10 月 1 日），那时在
                            // 9 月的周列表里根本找不到它 —— 旧写法会退回 9 月第一周，
                            // 表现为"动画正确、动画结束后周视图突然跳到另一周"
                            // （用户 2026-09-27 报的"有概率"就是这个，取决于点哪一天）。
                            // iOS 用 `CalendarLayout.weekKey(selectedDate)`，同一条语义。
                            // `List` 在 Compose 里按**实例**比较，每次重组新建会让
                            // `WeekLayer → WeekStrip → 3×CalendarDayGrid` 全部重跑
                            //（21 格 × 农历 + 字符串），所以这里必须 remember。
                            week = remember(selectedKey, weekStart) {
                                CalendarGrid.weekOf(selectedKey, weekStart)
                            },
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
                    //
                    // ⚠️ **不要改成"常驻"**（2026-09-27 试过，回退了）：
                    // 月态下这一层是透明的，但透明**不阻止命中测试** —— `YearCanvas` 的 12 个
                    // 迷你月卡片各自是一个铺满日历区的 `Modifier.clickable`，它们会在月流
                    // **之上**把指针事件接走，于是月态**上下拖动完全失效**（真机实测：
                    // 显式 `input motionevent` 拖 800px，整屏差异 0.000）。而且在年态翻年时，
                    // 这一层会跟着 `graphicsLayer` 的放大一起参与绘制，底部会露残留。
                    // 想省"年历首次排版"的开销，只能另找不参与命中测试的预热方式。
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

                    HomeMorphLayers(
                        yearMorphing = yearMorphing,
                        weekMorphing = weekMorphing,
                        weekSource = weekSource,
                        yearProgress = yearProgressReader,
                        weekProgress = weekProgressReader,
                        yearZoomingIn = yearZoomingIn,
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
                        year = year,
                        selectedMonth = monthFirst.monthValue,
                        dateFormats = dateFormats,
                        onMonthClick = { month -> openMonthFromYear(month) },
                        dayContent = {
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

                // 悬浮顶栏：**最后画** = 盖在日历之上；本身没有背景，年层放大后溢出的
                // 那一条会从胶囊周围（而不是"被顶栏下沿切断"）露出来。
                // ⚠️ 顶栏的**文案也必须跟着图层交班时机换**，不能跟着 `mode` 立刻换。
                //
                // 用户 2026-09-27："闪现的问题是顶栏的缘故，闪现是顶栏突然闪现出来。"
                // 原来 `mode` 在**点下去那一瞬间**就改了，于是左胶囊的文案立刻从月态的
                // "2026年" 变成周态的 "‹ 九月" —— 而此时 morph 层里的月标题还在往上滑、
                // 月视图那一层也还在场上，顶栏就"凭空冒出来"了。
                //
                // 现在：周 morph 进行期间，顶栏**暂时按月态的文案显示**（与屏幕上占主
                // 导的那一层一致），等 morph 收尾、真实周层接手时再换文案。
                // 判据：**屏幕上哪一层占主导**（morph 过半即算目标形态占主导）。
                //  · 月→周（p 从 0 涨到 1）：p < 0.5 时月层主导 ⇒ 文案仍是月态；
                //  · 周→月（p 从 1 降到 0）：p >= 0.5 时周层主导 ⇒ 文案仍是周态。
                val topBarMode = HomeCalendarNavigation.topBarMode(
                    mode = mode,
                    weekMorphing = weekMorphing,
                    weekProgress = weekProgress.value,
                )
                HomeTopBar(
                    // 月态只放年份（月份由下面 72dp 的 32sp 大标题承担）；周态放月份名 = 回月历
                    leftLabel = homeTitle(topBarMode, year, monthFirst, dateFormats, context),
                    showLeft = topBarMode != HomeViewMode.YEAR,
                    todayLabel = todayPillLabel(selectedDate, today, monthFirst, dateFormats, context),
                    // morph 期间禁点：此时真实图层还没接手，点它会让状态与画面不一致
                    onLeftClick = {
                        // 月态 → 年历；周态 → 回月态；年态不可点（整枚隐藏）
                        if (!weekMorphing && !yearMorphing) {
                            if (mode == HomeViewMode.MONTH) openYear() else backToMonth()
                        }
                    },
                    onToday = ::goToday,
                    mode = topBarMode,
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
