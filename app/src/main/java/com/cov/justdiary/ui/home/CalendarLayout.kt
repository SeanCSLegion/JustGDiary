package com.cov.justdiary.ui.home

import com.cov.justdiary.model.DateUtil
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 首页日历的全部布局数学。**逐条移植**自 iOS `Views/Home/CalendarLayout.swift`。
 *
 * 这一层刻意**不依赖 Compose**：它是纯数值计算，因此可以在 JVM 上精确断言
 * （方案 §6.4 与 `docs/migration-analysis/03-home-calendar-and-motion.md` §2 给出的
 * 那些数值就是这些断言的来源）。Compose 只负责把算出来的数字画出来。
 *
 * ## 与 iOS 的对应关系
 *
 * | 本文件 | iOS |
 * | --- | --- |
 * | 常量 | `CalendarLayout.swift:103-193` |
 * | [monthMetrics] / [weekMetrics] | `CalendarLayout.swift:149-165` |
 * | [monthPaneMetrics] | `CalendarPaneViews.swift:75-107` |
 * | [yearCardSize] / [yearCardRect] / [miniGridRect] | `CalendarLayout.swift:167-237` |
 * | [resolveDensity] | `CalendarLayout.swift:29-57` |
 * | [DayDraw] 的几何 | `CalendarGrids.swift:24-134` |
 * | [YearMonthMorph] / [MonthWeekMorph] | `MorphViews.swift` |
 * | [DragPagerMath] | `DragPagePager.swift:58-104` |
 */
object CalendarLayout {

    // ------------------------------------------------------------------ 常量

    /**
     * morph 时长（毫秒）＝ **M3 duration token `medium4` (400ms)**。
     *
     * 首轮取 600ms（= iOS 侧的 morphDuration），配合一条自定曲线把"感觉慢"压下去；
     * 现在按 Material 的令牌来：大面积"容器变换"用 `medium4` + `emphasized` 缓动，
     * 不需要靠拉长时长换顺滑。
     */
    const val MORPH_DURATION_MS = 600

    /** 减弱动态效果时的时长（线性）。 */
    const val REDUCED_MORPH_DURATION_MS = 180

    /**
     * **iOS 的 morph 曲线**：`CalendarLayout.morphSlideAnimation` =
     * `.timingCurve(0.2, 0.75, 0.3, 1.0, duration: morphDuration)`。
     *
     * 与之前误用的 M3 `emphasizedDecelerate (0.05, 0.7, 0.1, 1.0)` 差别很大，实测：
     * 走到"一半进度"所需的时间 —— iOS **15%** 时长，M3 只有 **6%**。
     * 用 M3 那条时开头一瞬就全到位、后面大半时间几乎不动，观感是"动画太快"，
     * 且所有后段淡入（小标题、年层）都被压成瞬间（用户："动画速率全都太快了，
     * 相较 iOS 版来说。也可能是动画曲线不一致的问题"）。
     */
    val IOS_MORPH_CURVE = floatArrayOf(0.2f, 0.75f, 0.3f, 1f)

    /**
     * 翻月 / 翻周 / 回到今天的时长（毫秒）。
     *
     * 两者都是 **M3 duration token**：`medium1 = 250`、`medium2 = 300`。
     */
    const val PAGE_SETTLE_DURATION_MS = 300

    /**
     * 竖屏：首页顶栏高度。
     *
     * **64dp 是 Material 3 `small top app bar` 的官方高度**（M3 "App bars" → small）。
     * 首轮取的 52dp 是自定值。
     */
    const val HEADER_H = 64f

    /** 竖屏：月历的大标题槽（**只在月态存在**）。 */
    const val BIG_TITLE_H = 72f

    /** 竖屏：星期栏与大标题槽。 */
    const val WEEKDAY_HEADER_H = 30f

    /** 横屏：紧凑的月标题与星期栏。 */
    const val COMPACT_MONTH_TITLE_H = 34f
    const val COMPACT_WEEKDAY_HEADER_H = 26f

    /**
     * 周条高度。
     *
     * ⚠️ 68 是**不够**的：周条里要放「选中圆的直径 + 农历一行」，而
     * `circleDiameter = min(cellW-3, contentH+14, cellH-3)`、`contentH = dayFont+4+lunarLineH`
     * —— 在 392dp 宽的手机上算出来约 48.5 + 15 ≈ 64dp，再加"有日记"的短横线就贴着 68 了。
     * 实测（真机 1080×2340@440dpi）：**中文的农历被下沿裁掉一截**（用户反馈的"遮挡"）。
     *
     * 80 = 内容所需 + 明确的余量，同时把 `circleDiameter` 的上限让开，不再被 `cellH-3` 夹住。
     */
    const val WEEK_STRIP_H = 80f
    const val DAY_TITLE_H = 40f

    /**
     * 年历：标题槽、左右边距、卡片间距、迷你月内缩与标题槽。
     *
     * [YEAR_TITLE_H] 与 iOS 一致取 62：页内要画出 32sp 的「2026年」大标题 + 右侧生肖。
     * 首轮曾把它压到 44（理由是"标题已经在顶栏里了"），但那样年历页没有大标题、
     * 视觉重心全空 —— 实测缺陷 D6 的一部分。
     */
    const val YEAR_TITLE_H = 62f
    const val YEAR_TOP_PAD = 8f
    const val YEAR_PAD = 16f
    const val YEAR_SPACING = 6f
    const val MINI_PAD = 6f
    const val MINI_TITLE_H = 17f
    const val MINI_LUNAR_FONT = 6f

    /**
     * 竖屏日历最大宽度（dp）。
     *
     * 700dp 的可用宽下 7 列每格 100dp，日号缩在中间、年份卡片会被拉成 224dp 的空盒子。
     * 640dp 对应每格 ~91dp，与 iPhone 的观感接近；分栏时改走 `masterWidth` 公式。
     */
    const val CALENDAR_MAX_WIDTH = 640f

    /** 画农历所需的每行高度（规范 §5.3 第一档：`perRow ≥ 60` 才画农历）。 */
    const val LUNAR_ROW_HEIGHT = 60f

    /**
     * 每行最小高度：**低于它就放弃农历**（不是降级为周条）。
     *
     * 规范 §5.3 的两档阈值：`perRow ≥ 60` 画农历 / `≥ 38` 不画农历 / 否则降级为周条。
     * 这里的 44 是"带农历"的下沿（60 是**舒适**下沿，44 是硬下沿）；只隐藏农历的那一档
     * 用 [NO_LUNAR_ROW_HEIGHT]。
     */
    const val MINIMUM_ROW_HEIGHT = 44f

    /**
     * 不画农历时的每行最小高度（规范 §5.3 的第二档）。
     *
     * ⚠️ 原来这里与"带农历的下沿"共用 44，导致 `perRow ∈ [38, 44)` 被误判成
     * "连一行都放不下"、错误降级为周条。
     */
    const val NO_LUNAR_ROW_HEIGHT = 38f

    /** 月历固定 6 行。 */
    const val MONTH_ROWS = 6

    /**
     * 竖屏月格的行高（dp）。
     *
     * **这是本轮修正的核心常量。** iOS 侧的竖屏行高是**算出来的**
     * （`(screenH - 72 - 30) / 6`），在 iPhone 402×874 上得 ~102pt；搬到 Android
     * 的 426.7×952dp 上就变成 126dp —— 而格子内容只有 40dp 高，于是每行上下各空
     * 出 40dp 的空白，整屏看着像"日历被拉散"。
     *
     * 56dp 的取值理由：
     *  - Material 规范要求交互元素 ≥ 48dp，56dp 让选中圆的 40dp 直径有呼吸空间；
     *  - 加上日号（20sp）与农历（11sp）两行，56dp 刚好不挤；
     *  - 3 行内容（日号 + 农历 + 下划线）在 56dp 里垂直居中后视觉重心稳定。
     *
     * 需要更多行时不再拉高格子，而是**让日历区整体变高**（行数 × 行高）。
     */
    const val PORTRAIT_CELL_H = 56f

    /**
     * 月格行高的**安全上限**（dp）。
     *
     * 规范 §5.1：行高不封顶，`cellH = (区高 − 大标题槽 − 星期栏) / 6`。这个上限只在
     * 极端窗口比例下生效，防止一格高到 200dp 以上；**不是**给下方面板让位
     * （首轮把它当成"要留给面板的比例"，方向就错了 —— 面板只在周态与横屏分栏里出现）。
     */
    const val SANITY_MAX_CELL_H = 200f

    /**
     * 月格行高的下限（dp）。低于它就要走周条降级（横屏/矮窗口）。
     */
    const val MIN_CELL_H = 44f

    /**
     * 年历区的最小高度（dp）。
     *
     * 年历的那 12 张卡片是 3 列 × 4 行，每张卡片里还要放一个 6 行的迷你网格。
     * 卡片高度低于 ~70dp 时迷你网格的点就会挤在一起（首轮上机实测的"年历看不清"）。
     * 因此年历区不共用月历的 62% 上限 —— 它需要的垂直空间比月历多，
     * 而两者用的是**各自**的高度，morph 的两个端点分别与各自真实图层同源，收尾仍然不跳。
     */


    // 注：首轮这里有 ADJACENT_MONTH_ALPHA / FUTURE_ALPHA 两个"压透明度"的常量。
    // 相邻月现在**不画**，未来日期改用 M3 的 `onSurfaceVariant`（语义色层级）而不是
    // 透明度 —— 0.35 alpha 的对比度只有 2.19:1，Material / WCAG AA 都不允许。

    // ------------------------------------------------------------------ 基础工具

    fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

    fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * iOS `CalendarLayout.smoothstep`：**五次**平滑（`t³(6t² − 15t + 10)`）——
     * 注意不是常见那条三次的 `3t² − 2t³`，两端一阶、二阶导都为 0，起步/收尾更"黏"。
     *
     * 月↔周 morph 里"选中周中属于相邻月的那几天各走各的路线"用的就是它（iOS
     * `MonthWeekMorphView.adjacentShifts`）：`away = smoothstep(1 − progress)`。
     */
    fun smoothstep(x: Float): Float {
        val t = clamp01(x)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    fun clamp(value: Float, low: Float, high: Float): Float = max(low, min(high, value))

    /** 矩形（左上角 + 宽高），对应 iOS 的 `CGRect`。 */
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        val maxY: Float get() = y + h

        fun lerp(other: Rect, t: Float): Rect = Rect(
            x = CalendarLayout.lerp(x, other.x, t),
            y = CalendarLayout.lerp(y, other.y, t),
            w = CalendarLayout.lerp(w, other.w, t),
            h = CalendarLayout.lerp(h, other.h, t),
        )
    }

    fun lerpRect(a: Rect, b: Rect, t: Float): Rect = a.lerp(b, t)

    // ------------------------------------------------------------------ 度量

    /**
     * 一格的度量。字段与 iOS `DayMetrics` 一一对应。
     *
     * `lunarAlpha` / `dividerAlpha` 参与 morph 插值，因此是浮点而不是布尔。
     */
    data class DayMetrics(
        val cellW: Float,
        val cellH: Float,
        val dayFont: Float,
        val lunarFont: Float,
        val lunarAlpha: Float,
        val dividerAlpha: Float,
    ) {
        fun lerp(other: DayMetrics, t: Float): DayMetrics = DayMetrics(
            cellW = CalendarLayout.lerp(cellW, other.cellW, t),
            cellH = CalendarLayout.lerp(cellH, other.cellH, t),
            dayFont = CalendarLayout.lerp(dayFont, other.dayFont, t),
            lunarFont = CalendarLayout.lerp(lunarFont, other.lunarFont, t),
            lunarAlpha = CalendarLayout.lerp(lunarAlpha, other.lunarAlpha, t),
            dividerAlpha = CalendarLayout.lerp(dividerAlpha, other.dividerAlpha, t),
        )

        /**
         * 一整组内容的高度 = **日号 + 缝 + 标记 + 缝 + 农历**（顺序就是屏幕上从上到下的顺序）。
         *
         * ⚠️ 2026-09-27 改成"字号驱动"：以前用 `Text` 的盒子高（= 字号 × 主题行高，
         * 日号 27dp / 农历 24dp），两个盒子一叠比字形高出一大截，圆里的日号与农历
         * 就被行距撑得很开（用户："日号和农历似乎分得太开了，保留有日记标记的余量即可"）。
         */
        val contentH: Float
            get() = dayFont + GROUP_GAP + DIARY_MARK_ROW_H +
                (if (lunarAlpha > 0.01f) GROUP_GAP + lunarFont else 0f)

        /** 农历那一行的**行盒**高度（0 表示不画）。[lunarCenterY] 仍以它为基准。 */
        val lunarLineH: Float
            get() = if (lunarAlpha > 0.01f) (lunarFont + 5f) * lunarAlpha else 0f

        /** 选中圆的直径：比这一组再大一圈，但被格宽/格高夹住，相邻格不会相碰。 */
        val circleDiameter: Float get() = minOf(cellW - 3f, contentH + 14f, cellH - 3f)
    }

    /** 一格的宽度（`width / 7`）。 */
    fun monthCellW(width: Float): Float = width / 7f

    /** 星期栏的字号：`clamp(cellW * 0.26, 11, 14)`。 */
    fun weekdayFontSize(cellW: Float): Float = clamp(cellW * 0.26f, 11f, 14f)

    /**
     * 月态日历带的总高（含大标题槽 [BIG_TITLE_H] + 星期栏 [WEEKDAY_HEADER_H] + 网格）。
     *
     * 调用方传入的是"顶栏之外的全部可用高"（竖屏）或"分栏左栏的可用高"。月 / 年两态都是
     * **整屏视图**，日记预览只在周态与横屏分栏里出现，所以这里有多少给多少。
     */
    fun monthAreaHeight(availableH: Float): Float =
        availableH.coerceAtLeast(0f)

    /**
     * 月格的行高：**按固定 [MONTH_ROWS]（6）行等分，不封顶**。
     *
     * 这是规范 §5.1 的核心：iOS 的规则就是 `(区高 − 72 − 30) / 6`，行高**跨月恒定** ——
     * 6 行月正好铺满、5 行月正好留一行白，翻月时版面不会上下抖。
     *
     * 首轮把行高封顶在 96dp 的后果是：6 行只占 576dp，屏下方空出 270dp（实测缺陷 D5）。
     *
     * [SANITY_MAX_CELL_H] 只在极端窗口比例下生效，**不是**给下方面板让位。
     * 上下界恒定满足 `MIN_CELL_H < SANITY_MAX_CELL_H`，`coerceIn` 的区间永不反转。
     */
    fun cellHeightFor(
        areaH: Float,
        headerH: Float = BIG_TITLE_H + WEEKDAY_HEADER_H,
    ): Float = ((areaH - headerH) / MONTH_ROWS).coerceIn(MIN_CELL_H, SANITY_MAX_CELL_H)

    /**
     * 月格字号的上限（[monthMetrics] / [monthPaneMetrics] 与 mini 月都用这一组值）。
     * 上限而不是定值：格子够大时字号不再跟着长，否则一行只放得下两三个数字。
     */
    const val DAY_FONT_MAX = 20f
    const val LUNAR_FONT_MAX = 11f

    /** 日号与农历之间的空档要收掉多少 dp（圆底比数字本身高出一截）。 */
    const val LUNAR_TIGHTEN = 6f

    /** 一组内容里给"有日记"短横线留的余量（dp）：它是 2dp 线 + 1dp 间隙。 */
    const val DIARY_MARK_ROW_H = 3f

    /**
     * 日号与农历之间的呼吸缝（dp）。
     *
     * ⚠️ 2026-09-27 用户连提两次，最后定在这里："日号和农历似乎分得太开了，保留有日记的
     * 标记的余量即可" → 我一度收到 0（两行贴着），用户当场否掉："那你现在又太近了啊，
     * 没有保留有日记的标记的位置"。所以：**必须给标记留出可见的空位**，
     * 再加 2dp 呼吸缝。
     */
    const val GROUP_GAP = 2f

    /**
     * 一格「日号 + 农历 + 有日记标记」的自然高度。
     *
     * ⚠️ 2026-09-27：现在这个值直接当**行高**用（见 [flowCellHeightFor]），不再是
     * "下限"。真机实测一格内容是：日号盒子 27 + 农历盒子 24 + 标记 3 ≈ **54dp**，
     * 72 留出约 9dp/侧的呼吸 —— 与 iOS 那条 94pt 的行高尺子也基本吻合
     *（`byArea` 在 Pixel 10 Pro / MEIZU 21 这类机器上约 95~101dp，取 `min` 后行高就是 72）。
     */
    const val DAY_CELL_CONTENT_H = 72f

    /** 一格只有日号（无农历）时的自然高度：日号盒子 + 标记，同样按"整体居中"重新量的。 */
    const val DAY_CELL_NO_LUNAR_H = 44f

    /** 网格的**实际**高度（当月行数 × 行高）。 */
    fun monthGridHeight(rows: Int, cellH: Float): Float = max(0, rows) * cellH

    /**
     * 把给定的网格高度**均分**给若干行（不做任何夹取）。
     *
     * 横屏分栏的日历面板用这个：面板高度本身就是稀缺资源，必须铺满、不能留白，
     * 也没有"矮到要降级"的余地（[CalendarLayout.resolveDensity] 已经决定过要不要降级）。
     */
    fun fitCellHeight(gridH: Float, rows: Int): Float = gridH / rows.coerceAtLeast(1)

    /**
     * 年历区高度：整屏（除顶栏外的全部可用高），与月历一致。
     *
     * 早期版本给年历设了一个 470dp 的下限、并给它留出 `DAY_PANEL_MIN_H` ——
     * 那是"月态下方面板"还在时的遗留。现在日记预览只在**周态**与**横屏分栏**出现，
     * 年历也跟月历一样占满屏幕，12 张卡片随可用高均匀铺开（卡片高 = `yearCardSize` 公式）。
     */
    fun yearAreaHeight(availableH: Float): Float = availableH.coerceAtLeast(0f)

    /**
     * morph 起点/终点用的月格度量。
     *
     * iOS 在 `CalendarLayout.monthMetrics` 里把 `dayFont` 固定成 20、`lunarFont` 固定成 11；
     * 而真实图层（`MonthPane`）用 [monthPaneMetrics] 的 clamp 公式。两者在竖屏 402×874 下
     * **恰好相等**（20 / 11），这正是 morph 收尾不跳变的原因（`docs/adaptive-layout-plan.md:178-186`）。
     * 这里保留 [monthPaneMetrics] 的 clamp 版本作为**唯一实现**，因为它同时覆盖横屏的降级档
     * （横屏 `cellH` 45 → 15.3、54 → 18.36，与实测一致）。
     */
    fun monthMetrics(
        width: Float,
        areaH: Float,
        showLunar: Boolean,
        rows: Int = MONTH_ROWS,
        headerH: Float = BIG_TITLE_H + WEEKDAY_HEADER_H,
    ): DayMetrics = monthPaneMetrics(
        cellW = monthCellW(width),
        cellH = cellHeightFor(areaH, headerH),
        showLunar = showLunar,
    )

    /** 真实图层的月格度量：`dayFont = clamp(cellH*0.34, 13, 20)`、`lunarFont = clamp(cellH*0.18, 9, 11)`。 */
    fun monthPaneMetrics(cellW: Float, cellH: Float, showLunar: Boolean): DayMetrics = DayMetrics(
        cellW = cellW,
        cellH = cellH,
        dayFont = clamp(cellH * 0.34f, 13f, 20f),
        lunarFont = clamp(cellH * 0.18f, 9f, 11f),
        lunarAlpha = if (showLunar) 1f else 0f,
        dividerAlpha = 1f,
    )

    /**
     * 真实月格网格区的矩形：`(0, headerH, width, height - headerH)`。
     *
     * [headerH] 必须是调用方**实际**排出的高度（大标题槽 + 星期栏），否则年↔月 morph
     * 的两个端点对不齐（收尾会跳一下）。
     */
    fun fullMonthGridRect(width: Float, height: Float, headerH: Float = BIG_TITLE_H + WEEKDAY_HEADER_H): Rect =
        Rect(0f, headerH, width, height - headerH)

    /** 周条的度量：行高固定 68、无分隔线。 */
    fun weekMetrics(width: Float, showLunar: Boolean): DayMetrics = DayMetrics(
        cellW = monthCellW(width),
        cellH = WEEK_STRIP_H,
        dayFont = 20f,
        lunarFont = 11f,
        lunarAlpha = if (showLunar) 1f else 0f,
        dividerAlpha = 0f,
    )

    // ------------------------------------------------------- 连续月历流（MonthFlow）

    /**
     * 小标题与它下面那条分割线之间的间隙（「紧贴」的那个「紧」）。
     *
     * iOS `MonthFlowView.labelTightGap`：小标题底边 = 本月第一行顶 − 3dp，
     * 于是它整个落在**上个月最后一行底部的留白**里，不额外占高度。
     */
    const val LABEL_TIGHT_GAP = 3f

    /** 连续月历流里小标题的字号（竖屏 15 / 横屏紧凑 13）。 */
    fun flowLabelFontSize(compact: Boolean): Float = if (compact) 13f else 15f

    /**
     * 连续月历流里「月份之间那一带」的高度 = **小标题这一行字本身的高度**。
     *
     * 用户看过第一版（一个整行高 ≈ 94dp）说「间隔太多」，看过第二版（35dp）又说
     * 「还是太高，应该紧贴月份高度」，所以这里不再加任何上下留白：
     * 竖屏 15dp 字 → **21dp**；横屏紧凑 13dp 字 → **18dp**。
     * 上下看起来的空白来自日期行自身的垂直居中留白，不需要带子再让一份。
     */
    fun flowLabelBandHeight(compact: Boolean): Float =
        (flowLabelFontSize(compact) * 1.4f).roundToInt().toFloat()

    /**
     * **月与月之间的真实距离**（dp）。
     *
     * 用户 2026-09-27："我的意思是，你直接调整两个月之间的总距离不行吗？" —— 对，
     * 这才是正解：小标题上方那点空气本来就该由"两个月之间的距离"来给，而不是把小标题
     * 往分割线上挤（试过，用户否掉："你改了之后反而看着太挤了"），也不是去动日期格的
     * 排版（那会连周条一起改，得不偿失）。
     *
     * 连续性不受影响：块与块之间只是多出这一段，
     * `block.bottom`（最后一行底）与下一块的 `top` 之间隔着它，小标题正好落在这段里。
     */
    const val MONTH_GAP = 16f

    /**
     * 竖屏连续月历流的行高：**与 iOS 同一条尺子** —— `(区高 − 标题槽 − 星期栏) / 6`。
     *
     * 用户 2026-09-27："行高也按照 iOS 的 94pt 来设置，这方面对齐 iOS。"
     * 在 iPhone 402×874 上这个式子得 94pt；本机（Pixel 10 Pro，426.7×952dp）得 ~105dp。
     *
     * ⚠️ 这条规则**取代**了 §8.9 那条"由列宽推出（列距 × 1.2）"：
     *  - 一屏正好 6 行（= 一个月）⇒ 与 iOS 的观感一致；
     *  - 一行底部的留白 = (行高 − 内容高) / 2 ≈ 35dp，**放得下** 21dp 的小标题 +
     *    3dp 间隙 ⇒ 小标题与上个月最后一行之间自然留出约 11dp（用户要的"稍微隔开一点"）；
     *    旧规则（73dp）只有 19dp 留白，小标题会压到前一行的农历上。
     *
     * [fontScale] 那一项只是**兜底**：系统字号调大时内容会变高，行高至少要放得下
     * "日号 + 农历"（下限 72dp，与 [DAY_CELL_CONTENT_H] 同源）。
     */
    fun flowCellHeightFor(
        areaH: Float,
        headerH: Float = BIG_TITLE_H + WEEKDAY_HEADER_H,
        fontScale: Float = 1f,
        showLunar: Boolean = true,
    ): Float {
        val byContent =
            (if (showLunar) DAY_CELL_CONTENT_H else DAY_CELL_NO_LUNAR_H) * max(1f, fontScale)
        // ⚠️ 2026-09-27：**行高改成跟内容走**，不再取 `max(视口均分, 内容)`。
        // 用户："现在行高也要一并缩小了" —— 选中圆改成"三样整体居中"、圆不再被内容撑大
        //（真机实测圆直径是被**格宽**夹住的：cellW 56.1 ⇒ 圆 53.1），一格里真正的内容
        // 只有 ~54dp；再按"视口 6 等分"给到 ~101dp，一行就多出 ~47dp 的纯留白，
        // 一个月凭空高出一大截。现在按内容给行高，一屏能踏实放下 6 行还富余一些。
        // `areaH` 只作为**上限**：视口比内容还矮时才压缩（横屏左栏那种情况）。
        val byArea = (areaH - headerH) / MONTH_ROWS
        return minOf(byContent, byArea).coerceIn(MIN_CELL_H, SANITY_MAX_CELL_H)
    }

    /**
     * 一格里「内容之上」的留白：`(行高 − 内容高) / 2`。
     *
     * 小标题（[flowLabelBandHeight] + [LABEL_TIGHT_GAP]）就借用这段留白 ——
     * `MonthFlowLayoutTest` 用它守住"小标题不压到上个月的日期上"。
     */
    fun cellContentTopPadding(metrics: DayMetrics): Float = (metrics.cellH - metrics.contentH) / 2f

    /**
     * 连续月历流单行的绘制参数。
     *
     * 字号跟着行高走：竖屏行高 ~94dp 时仍是设计里的 20/11；横屏左栏一格只有 40dp，
     * 20dp 日号 + 11dp 农历会上下叠在一起（横屏左栏一直是按格高推导字号）。
     */
    fun flowMetrics(width: Float, rowH: Float, showLunar: Boolean): DayMetrics =
        monthPaneMetrics(cellW = monthCellW(width), cellH = rowH, showLunar = showLunar)

    /** 迷你月（年历）的日号字号：`clamp(min(cellW*0.95, cellH*0.5), 9, 14)`。 */
    fun miniDayFont(cellW: Float, cellH: Float): Float = clamp(min(cellW * 0.95f, cellH * 0.5f), 9f, 14f)

    /**
     * 年历迷你月的选中 / 今天圆半径比例（P1-2，2026-11-13）。
     *
     * 旧值 `0.42` 让年历圆恒占格宽 84%，而月 / 周 / 流约 90.5% —— iOS 用户报的
     * "年历圆偏小"在 Android 同样存在。`0.46` 的直径 = 格宽 92%，与 iOS 93% 看齐，
     * 相邻圆之间仍留 8% 的缝。**两处同改**：`CalendarCanvas` 与 `YearMonthMorph` 都调这里。
     */
    const val YEAR_DAY_CIRCLE_RADIUS_RATIO = 0.46f

    /** 年历圆半径（dp）。竖直方向也受格高限制，避免碰到上下两行。 */
    fun yearDayCircleRadius(cellW: Float, cellH: Float): Float =
        min(cellW, cellH) * YEAR_DAY_CIRCLE_RADIUS_RATIO

    /**
     * 年历"有日记"标记的顶边 y（px）。
     *
     * P1-2：标记不再挂在圆半径上（那样放大圆会把标记一起带远），而是挂在**日号文本盒底**
     * 下方 0dp 处。`dayTextHeight` 是实际测得的文本行盒高；年历 gap = 0。
     */
    fun yearDiaryMarkTop(dayCenterY: Float, dayTextHeight: Float): Float =
        dayCenterY + dayTextHeight / 2f

    /** 迷你月度量：不显示农历、不画分隔线。 */
    fun miniMetrics(grid: Rect): DayMetrics = DayMetrics(
        cellW = grid.w / 7f,
        cellH = grid.h / MONTH_ROWS,
        dayFont = miniDayFont(grid.w / 7f, grid.h / MONTH_ROWS),
        lunarFont = MINI_LUNAR_FONT,
        lunarAlpha = 0f,
        dividerAlpha = 0f,
    )

    /**
     * 年历底部安全边距。
     *
     * 卡片高度本来就是按可用高**推导**的（12 张正好铺满），所以屏幕比例一变，
     * 最后一行就会**紧贴内容区底边**、看起来压住底部导航栏
     * （用户："最底下的三个月的背景会和底栏有重叠。这个是不同手机屏幕高度、比例不同导致的"）。
     * 这里留出一段固定余量，让任何比例下末行都不贴边。
     */
    const val YEAR_BOTTOM_PAD = 14f

    /** 年历卡片尺寸：`((w - 16*2 - 6*2)/3, (h - 62 - 8 - 6*3 - 底部余量)/4)`。 */
    fun yearCardSize(width: Float, height: Float): Pair<Float, Float> = Pair(
        (width - YEAR_PAD * 2 - YEAR_SPACING * 2) / 3f,
        (height - YEAR_TITLE_H - YEAR_TOP_PAD - YEAR_SPACING * 3 - YEAR_BOTTOM_PAD) / 4f,
    )

    /** 第 `month`（1..12）张迷你月卡片的位置。 */
    fun yearCardRect(month: Int, width: Float, height: Float): Rect {
        val (cardW, cardH) = yearCardSize(width, height)
        val col = (month - 1) % 3
        val row = (month - 1) / 3
        return Rect(
            x = YEAR_PAD + col * (cardW + YEAR_SPACING),
            y = YEAR_TITLE_H + YEAR_TOP_PAD + row * (cardH + YEAR_SPACING),
            w = cardW,
            h = cardH,
        )
    }

    /** 迷你月标题在卡片内的位置（`miniTitleInCard = (6, 6)`）。 */
    fun miniTitleRect(card: Rect): Rect = Rect(
        x = card.x + MINI_PAD,
        y = card.y + MINI_PAD,
        w = card.w - MINI_PAD * 2,
        h = MINI_TITLE_H,
    )

    /**
     * 迷你月网格在卡片内的位置（`miniGridInCard = (6, 23)`）。
     *
     * 行数固定 [MONTH_ROWS]（6）—— 与 [yearMonthMorph] 的分格行数一致，
     * 这样 morph 的两端行数相同，收尾不会跳。
     */
    fun miniGridRect(card: Rect): Rect = Rect(
        x = card.x + MINI_PAD,
        y = card.y + MINI_PAD + MINI_TITLE_H,
        w = card.w - MINI_PAD * 2,
        h = card.h - MINI_PAD * 2 - MINI_TITLE_H,
    )

    /** 真实月格的完整网格区（旧签名，保留给旧调用点；新代码用带 `headerH` 的那个）。 */
    fun fullMonthGridRect(width: Float, height: Float): Rect =
        Rect(0f, BIG_TITLE_H + WEEKDAY_HEADER_H, width, height - BIG_TITLE_H - WEEKDAY_HEADER_H)

    // ------------------------------------------------------------------ 密度降级

    enum class DensityKind { Month, WeekStrip }

    data class Density(val kind: DensityKind, val showLunar: Boolean)

    /**
     * 横屏的密度判定（竖屏**不走**这里 —— 竖屏的农历显隐只由语言决定）。
     *
     * `perRow >= 60 → 月格(含农历)`、`>= 44 → 月格(隐藏农历)`、否则 `周条`。
     */
    fun resolveDensity(availableHeight: Float, rows: Int, wantsLunar: Boolean): Density {
        val safeRows = max(1, rows)
        // **向下取整**（与 `rowHeight` 保持一致）：向上取整会出现"判定说放得下、
        // 实际每行高出 1dp"，网格于是比日历区高、最后一行被裁掉半行
        val perRow = floor(availableHeight / safeRows).toInt()
        return when {
            // ≥ 60：够画农历
            perRow >= LUNAR_ROW_HEIGHT.toInt() -> Density(DensityKind.Month, wantsLunar)
            // ≥ 38：只放日号与选中圆
            perRow >= NO_LUNAR_ROW_HEIGHT.toInt() -> Density(DensityKind.Month, false)
            // 再低就只剩一行可以看：降级为周条（只画选中日所在那一行）
            else -> Density(DensityKind.WeekStrip, false)
        }
    }

    // ------------------------------------------------------------------ 绘制几何（DayDraw）

    fun cellCenterX(col: Int, cellW: Float): Float = (col + 0.5f) * cellW

    /** 一格内容垂直居中后的顶部 y。 */
    fun contentTop(rowY: Float, metrics: DayMetrics): Float =
        rowY + (metrics.cellH - metrics.contentH) / 2f

    /** 日号文字的中心 y。 */
    fun dayNumberCenterY(top: Float, metrics: DayMetrics): Float = top + metrics.dayFont / 2f

    /** 农历文字的中心 y。 */
    fun lunarCenterY(top: Float, metrics: DayMetrics): Float =
        top + metrics.dayFont + 4f + metrics.lunarLineH / 2f

    /** 选中圆的中心 y。 */
    fun circleCenterY(top: Float, metrics: DayMetrics): Float = top + metrics.contentH / 2f

    /** "有日记"下划线的宽度与位置（`w = min(20, cellW*0.5)`，高 2，圆角 1）。 */
    data class DiaryMark(val width: Float, val y: Float, val height: Float, val cornerRadius: Float)

    fun diaryMark(top: Float, metrics: DayMetrics): DiaryMark = DiaryMark(
        width = min(20f, metrics.cellW * 0.5f),
        y = dayNumberCenterY(top, metrics) + metrics.dayFont / 2f + 2f,
        height = 2f,
        cornerRadius = 1f,
    )

    /** 今天圆环的线宽（`CalendarGrids.swift:92`）。 */
    const val TODAY_RING_WIDTH = 1.5f

    /**
     * 命中测试：点 (x, y) 落在第几行第几列。
     *
     * 越界或（在 `showAdjacent == false` 时）不属于锚定月份就返回 null
     * （`CalendarGrids.swift:243-253`）。
     */
    fun dayAt(
        x: Float,
        y: Float,
        metrics: DayMetrics,
        weeks: List<List<LocalDate>>,
        anchorMonth: LocalDate?,
        showAdjacent: Boolean,
    ): LocalDate? {
        if (metrics.cellW <= 0f || metrics.cellH <= 0f) return null
        val col = (x / metrics.cellW).toInt()
        val row = (y / metrics.cellH).toInt()
        if (col !in 0..6 || row < 0 || row >= weeks.size) return null
        val date = weeks[row].getOrNull(col) ?: return null
        if (!showAdjacent && anchorMonth != null) {
            if (date.year != anchorMonth.year || date.monthValue != anchorMonth.monthValue) return null
        }
        return date
    }

    /** 周条只需要列（`CalendarGrids.swift:295-300`）。 */
    fun weekDayAt(x: Float, metrics: DayMetrics, week: List<LocalDate>): LocalDate? {
        if (metrics.cellW <= 0f) return null
        val col = (x / metrics.cellW).toInt()
        if (col !in 0..6) return null
        return week.getOrNull(col)
    }

    // ------------------------------------------------------------------ morph：年 ↔ 月

    /**
     * 年 ↔ 月 morph 在进度 `progress`（**0 = 月历起点、1 = 年历终点**）时的全部插值量。
     *
     * 对应 `MorphViews.YearMonthMorphView`：`t = 1 - progress` 之后所有量都是 `t` 的纯函数。
     */
    data class YearMonthMorphState(
        val t: Float,
        val grid: Rect,
        val metrics: DayMetrics,
        val lunarAlpha: Float,
        val dividerAlpha: Float,
        val yearOpacity: Float,
        val yearScale: Float,
        val sourceMiniTitleOpacity: Float,
    )

    /**
     * 年层在月↔年 morph 中的不透明度（**唯一来源**）。
     *
     * 月层用 `1 - 它` 作为自己的不透明度 —— 两条曲线在整段区间内**和为 1**，
     * 不会出现"前 25% 都没出现"或"双重曝光"的错位。
     * （用户："月视图到年视图的动画速率还需调一下，动画没有对齐。"）
     */
    fun yearLayerOpacity(progress: Float): Float = clamp01((clamp01(progress) - 0.25f) / 0.75f)

    fun yearMonthMorph(
        progress: Float,
        miniGrid: Rect,
        fullGrid: Rect,
        showLunar: Boolean,
    ): YearMonthMorphState {
        val p = clamp01(progress)
        val t = clamp01(1f - p)

        // 网格先插值，再**强制**按 7 列 6 行重新分格 —— 这样格子尺寸始终自洽
        val grid = lerpRect(miniGrid, fullGrid, t)
        val cellW = grid.w / 7f
        val cellH = grid.h / MONTH_ROWS

        val lunarAlpha = if (showLunar) clamp01((t - 0.5f) / 0.5f) else 0f
        val dividerAlpha = clamp01((t - 0.55f) / 0.45f)

        return YearMonthMorphState(
            t = t,
            grid = grid,
            metrics = DayMetrics(
                cellW = cellW,
                cellH = cellH,
                // 字号在两个端点之间插值：迷你月很小，月格按 cellH 推导
                dayFont = lerp(
                    miniDayFont(cellW, cellH),
                    monthPaneMetrics(cellW, cellH, showLunar).dayFont,
                    t,
                ),
                lunarFont = lerp(MINI_LUNAR_FONT, monthPaneMetrics(cellW, cellH, showLunar).lunarFont, t),
                lunarAlpha = lunarAlpha,
                dividerAlpha = dividerAlpha,
            ),
            lunarAlpha = lunarAlpha,
            dividerAlpha = dividerAlpha,
            // 从 p=0.25 就开始淡入（原来 0.45）：月层的淡出是 1-p，两者必须在整段区间里
            // 至少有一层可见，否则 morph 前段会露出空白
            yearOpacity = yearLayerOpacity(p),
            yearScale = 1f + (1f - p) * 0.6f,
            sourceMiniTitleOpacity = clamp01((p - 0.7f) / 0.3f),
        )
    }

    // ------------------------------------------------- morph：年 ↔ 月（网格重排）

    /** 年↔月 morph 里"那一块月网格"的插值结果。 */
    data class YearMonthGridMorph(
        /** 1 = 月侧（源），0 = 年侧（目标）。 */
        val t: Float,
        val rect: Rect,
        val metrics: DayMetrics,
        val lunarAlpha: Float,
        val dividerAlpha: Float,
    )

    /**
     * 年↔月 morph 的**网格插值**：源矩形（屏幕上那一块月网格）→ 目标矩形（年历里那张迷你月
     * 的网格），格子按 [rowCount] 行 × 7 列**重新排布**（不是把整层压扁）。
     *
     * 这是从 iOS `YearMonthMorphView` 学来的关键一条：iOS 把 `MonthCanvas` 画在插值出来的
     * `grid` 矩形里，字号在 `miniDayFont`（≈9-14pt）与月格字号（20pt）之间插值 ——
     * 所以"月份缩进卡片"的过程中**日期始终清晰**，而不是被拉成一片涂抹。
     * Android 之前用整块仿射变换（`scaleX/scaleY`），文字会被压扁，用户反馈
     * "月视图切换年视图的动画也不流畅"。
     *
     * @param source 源矩形（**内容坐标 dp**）：`(0, 本块顶 − 滚动偏移, 宽, 行数 × 行高)`
     * @param target 目标矩形（**内容坐标 dp**）：年历里那张迷你月的网格矩形
     * @param rowCount 本块要画的行数（`displayedWeekCount`，4…6）
     */
    fun yearMonthGridMorph(
        progress: Float,
        source: Rect,
        target: Rect,
        sourceMetrics: DayMetrics,
        showLunar: Boolean,
    ): YearMonthGridMorph {
        val t = clamp01(1f - clamp01(progress))
        // ⚠️ 方向：`t = 1` 是**月侧**，所以要 `lerp(目标, 源, t)` —— 写成
        // `lerpRect(source, target, t)` 会得到"p=0 时已经缩到卡片上"的反向动画
        // （单测 `year month grid morph relayouts...` 就是钉这一条的）。
        val rect = lerpRect(target, source, t)
        val cellW = rect.w / 7f
        // ⚠️ 行高**不能**用 `rect.h / rowCount`：年历里那张迷你月是按**固定 6 行**排的
        // （`YearCanvas`：`cellH = grid.h / 6`，只画 displayedWeeks 行），所以年侧的
        // 行高必须是 `target.h / 6`，否则 morph 收尾换回真年历时那张卡片会跳一下。
        val cellH = lerp(target.h / MONTH_ROWS, sourceMetrics.cellH, t)
        val metrics = DayMetrics(
            cellW = cellW,
            cellH = cellH,
            // 字号在两个端点之间插值（t = 1 是月侧、t = 0 是迷你月）
            dayFont = lerp(miniDayFont(cellW, cellH), sourceMetrics.dayFont, t),
            lunarFont = lerp(MINI_LUNAR_FONT, sourceMetrics.lunarFont, t),
            lunarAlpha = if (showLunar) clamp01((t - 0.5f) / 0.5f) else 0f,
            dividerAlpha = clamp01((t - 0.55f) / 0.45f),
        )
        return YearMonthGridMorph(
            t = t,
            rect = rect,
            metrics = metrics,
            lunarAlpha = metrics.lunarAlpha,
            dividerAlpha = metrics.dividerAlpha,
        )
    }

    // ------------------------------------------------------------------ 翻页

    /** 翻月/翻周的拖动数学（`DragPagePager.swift:58-104`）。 */
    object DragPagerMath {

        /** 手势的最小识别距离。 */
        const val MINIMUM_DRAG_DISTANCE = 8f

        /** 越界橡皮筋的强度：`c = pageSize * 0.25`。 */
        const val RUBBER_BAND_RATIO = 0.25f

        /** 翻页判定的**预测终点**阈值。 */
        const val PREDICTED_THRESHOLD = 90f

        /** 翻页判定的**当前位移**阈值。 */
        const val TRANSLATION_THRESHOLD = 60f

        /** 提交翻页时的收尾动画时长（规范 §9：与 morph 同一套 `medium` 量级）。 */
        const val PAGE_SETTLE_MS = 220

        /** 放弃翻页、弹回原位时的时长（比提交略快，回弹要"轻"）。 */
        const val PAGE_CANCEL_MS = 180

        /**
         * 把松手时的速度投影成"预测终点"所用的时间常数（秒）。
         *
         * iOS 用 SwiftUI 的 `predictedEndTranslation`（UIKit 的减速度投影）。
         * Compose 的 `detectVerticalDragGestures` 不直接给这个值，因此这里用
         * `predictedEnd = translation + velocity * PROJECTION_SECONDS` 近似；
         * 0.2s 与 UIKit 常用投影量级一致。**这是本层唯一一处近似**，
         * 阈值本身（90 / 60）与 iOS 完全相同。
         */
        const val PROJECTION_SECONDS = 0.2f

        /** 把原始位移夹在一页之内，避免露出空白后再回弹。 */
        fun clampOffset(raw: Float, pageSize: Float): Float = min(pageSize, max(-pageSize, raw))

        /** 越界位移的橡皮筋阻尼。 */
        fun rubberBand(overflow: Float, pageSize: Float): Float {
            val c = pageSize * RUBBER_BAND_RATIO
            if (c <= 0f) return overflow
            return c * overflow / (overflow + c)
        }

        /**
         * 松手后翻到哪一页。
         *
         * `predictedEnd` 是系统预测的终点位移（对应用户的甩动速度）。
         * 需要当前页有对应的邻页才允许翻（[hasNext] / [hasPrev]）。
         */
        fun decide(
            translation: Float,
            predictedEnd: Float,
            hasNext: Boolean,
            hasPrev: Boolean,
        ): PageTarget {
            val goNext = hasNext &&
                (predictedEnd < -PREDICTED_THRESHOLD || (predictedEnd < 0f && translation < -TRANSLATION_THRESHOLD))
            if (goNext) return PageTarget.Next

            val goPrev = hasPrev &&
                (predictedEnd > PREDICTED_THRESHOLD || (predictedEnd > 0f && translation > TRANSLATION_THRESHOLD))
            if (goPrev) return PageTarget.Previous

            return PageTarget.Stay
        }
    }

    enum class PageTarget { Previous, Stay, Next }

    /**
     * 翻页目标 → **带符号的步长**（`Next = +1` / `Previous = -1` / `Stay = 0`）。
     *
     * 这个函数存在的唯一理由是防止一类真实发生过的回归：收尾动画那边一度只用
     * `settled: Boolean` 表示"要不要翻"，把 `Next / Previous` 收敛成了一个布尔值 ——
     * 于是**无论往哪边拖都往前翻**（实测：从一月往下拖得到二月，往上拖得到三月）。
     * 方向必须一路带着走到 `pageMonth` / `jumpYear`，所以把它固化成纯函数并加断言。
     */
    fun stepOf(target: PageTarget): Int = when (target) {
        PageTarget.Next -> 1
        PageTarget.Previous -> -1
        PageTarget.Stay -> 0
    }

    // ------------------------------------------------------------------ 日期 → 行号

    /** 某一天在月格中的行号（`CalendarLayout.swift:364-368`）。 */
    fun weekRowIndex(date: LocalDate, monthFirst: LocalDate, weekStart: String): Int {
        val lead = DateUtil.weekdayIndex(monthFirst, weekStart)
        return (lead + date.dayOfMonth - 1) / 7
    }

}
