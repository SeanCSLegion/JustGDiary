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

    /** `-slow-morph` / `SLOW_MORPH=1` 下的调试慢放时长。 */
    const val SLOW_MORPH_DURATION_MS = 3000

    /** 减弱动态效果时的时长（线性）。 */
    const val REDUCED_MORPH_DURATION_MS = 180

    /**
     * **M3 `easing.emphasized`** = `cubic-bezier(0.2, 0, 0, 1)`。
     *
     * 数值取自 material3 1.4.0 的官方令牌实现（`MotionTokens.EasingEmphasizedCubicBezier`）；
     * 首轮用的 `timingCurve(0.2, 0.75, 0.3, 1.0)` 是 iOS 的曲线，不属于 Material。
     */
    val M3_EMPHASIZED = floatArrayOf(0.2f, 0f, 0f, 1f)

    /**
     * **M3 `easing.emphasized.decelerate`** = `cubic-bezier(0.05, 0.7, 0.1, 1)`。
     *
     * 用于"进入"方向的大面积过渡（`MotionTokens.EasingEmphasizedDecelerateCubicBezier`）。
     */
    val M3_EMPHASIZED_DECELERATE = floatArrayOf(0.05f, 0.7f, 0.1f, 1f)

    /**
     * 翻月 / 翻周 / 回到今天的时长（毫秒）。
     *
     * 两者都是 **M3 duration token**：`medium1 = 250`、`medium2 = 300`。
     */
    const val PAGE_SETTLE_DURATION_MS = 300
    const val QUICK_SETTLE_DURATION_MS = 250

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

    /** 周条行高与周态日期标题行。 */
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
     * 竖屏 / 平板竖屏的日历最大宽度（dp）。
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
     * ⚠️ 原来这里与 [MINIMUM_ROW_HEIGHT] 共用 44，导致 `perRow ∈ [38, 44)` 被误判成
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

    /** 竖屏日历区的绝对下限（低于它就没有可用的月历了）。 */
    const val PORTRAIT_MIN_AREA_H = 240f

    /**
     * 月格行高的**安全上限**（dp）。
     *
     * 规范 §5.1：行高不封顶，`cellH = (区高 − 大标题槽 − 星期栏) / 6`。这个上限只在
     * 平板竖屏这类极端比例上生效，防止一格高到 200dp 以上；**不是**给下方面板让位
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

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    fun clamp(value: Float, low: Float, high: Float): Float = max(low, min(high, value))

    /** 矩形（左上角 + 宽高），对应 iOS 的 `CGRect`。 */
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        val maxX: Float get() = x + w
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

        /** 农历行的高度（0 表示不画）。 */
        val lunarLineH: Float
            get() = if (lunarAlpha > 0.01f) (lunarFont + 5f) * lunarAlpha else 0f

        /** 一格内容的整体高度（日号 + 间距 + 农历行）。 */
        val contentH: Float get() = dayFont + 4f + lunarLineH

        /** 选中圆的直径：比内容再大一圈，但被格宽/格高夹住，相邻格不会相碰。 */
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
    fun monthAreaHeight(availableH: Float, rows: Int = MONTH_ROWS): Float =
        availableH.coerceAtLeast(0f)

    /** 旧名保留（旧调用点仍可用），语义等同 [monthAreaHeight]。 */
    fun portraitAreaHeight(availableH: Float, rows: Int = MONTH_ROWS): Float =
        monthAreaHeight(availableH, rows)

    /**
     * 月格的行高：**按固定 [MONTH_ROWS]（6）行等分，不封顶**。
     *
     * 这是规范 §5.1 的核心：iOS 的规则就是 `(区高 − 72 − 30) / 6`，行高**跨月恒定** ——
     * 6 行月正好铺满、5 行月正好留一行白，翻月时版面不会上下抖。
     *
     * 首轮把行高封顶在 96dp 的后果是：6 行只占 576dp，屏下方空出 270dp（实测缺陷 D5）。
     *
     * [SANITY_MAX_CELL_H] 只在平板竖屏这类极端比例上生效，**不是**给下方面板让位。
     * 上下界恒定满足 `MIN_CELL_H < SANITY_MAX_CELL_H`，`coerceIn` 的区间永不反转。
     */
    fun cellHeightFor(
        areaH: Float,
        rows: Int = MONTH_ROWS,
        headerH: Float = BIG_TITLE_H + WEEKDAY_HEADER_H,
        preferred: Float = PORTRAIT_CELL_H,
    ): Float = ((areaH - headerH) / MONTH_ROWS).coerceIn(MIN_CELL_H, SANITY_MAX_CELL_H)

    /**
     * 竖屏行高：**由列宽推出**，让"上下间隔"与"左右间隔"接近。
     *
     * 用户真机反馈："目前的日期上下间隔太大了，参考左右间隔来设置上下间隔。"
     * 原因是行高被"把整页均分给行数"算成了 114dp（5 行月），而列宽只有 ~51dp ——
     * 行距是列距的两倍多，看着就散。
     *
     * 1.2 这个系数：设计稿的 `PORTRAIT_CELL_H = 56` 对应 51dp 列宽（≈1.1），
     * iOS 参考图约 1.34，取中间偏设计稿。
     */
    const val CELL_H_PER_COL_W = 1.2f

    /** 月格字号的上限（`monthMetrics` 里的 `clamp` 用的是同一组值）。 */
    const val DAY_FONT_MAX = 20f
    const val LUNAR_FONT_MAX = 11f

    /**
     * 内联月份标签往首行留白里下探多少 dp（标签行高度为 0，靠这个偏移落在 1 号上方）。
     */
    const val COMPACT_LABEL_DROP = 6f

    /** 日号与农历之间的空档要收掉多少 dp（圆底比数字本身高出一截）。 */
    const val LUNAR_TIGHTEN = 6f

    /** 一格"日号 + 农历"的自然高度（`CalendarDayCell` 注释实测 ≈ 70dp，留 2dp 余量）。 */
    const val DAY_CELL_CONTENT_H = 72f

    /** 一格只有日号（无农历）时的自然高度：以选中圆直径为据。 */
    const val DAY_CELL_NO_LUNAR_H = 48f

    /**
     * 竖屏行高 = **列宽**与**文字实际高度**两者取大。
     *
     * 只按列宽会**裁掉农历**：字号是从 `cellH` 推出来的（`dayFont = cellH×0.34` 上限 20、
     * `lunarFont = cellH×0.18` 上限 11），之后还要乘系统字号缩放 ——
     * 用户把系统字号调大时，列宽推出的行高装不下"日期 + 农历"（真机实测农历被切一半）。
     * 所以这里按上限字号 × 缩放系数估一个内容高度，取较大者。
     */
    fun dayCellHeightFor(calendarW: Float, fontScale: Float = 1f, showLunar: Boolean = true): Float {
        val byWidth = calendarW / 7f * CELL_H_PER_COL_W
        // 内容下限：`CalendarDayCell` 的注释写明"日号 + 农历 ≈ 70dp"，
        // 取 72dp 留一点余量。**低于它会裁掉农历**（真机实测：61dp 时农历被切一半）。
        // 有农历时用 72，无农历（纯日号）时用圆直径那档就够。
        val natural = if (showLunar) DAY_CELL_CONTENT_H else DAY_CELL_NO_LUNAR_H
        val byContent = natural * max(1f, fontScale)
        return max(byWidth, byContent).coerceIn(MIN_CELL_H, SANITY_MAX_CELL_H)
    }

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
        cellH = cellHeightFor(areaH, rows, headerH),
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

    /** 迷你月（年历）的日号字号：`clamp(min(cellW*0.95, cellH*0.5), 9, 14)`。 */
    fun miniDayFont(cellW: Float, cellH: Float): Float = clamp(min(cellW * 0.95f, cellH * 0.5f), 9f, 14f)

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

    /** 横屏日历区的高度：`max(220, screenH - topPad - bottomClearance)`。 */
    fun landscapePaneHeight(screenH: Float, topPad: Float, bottomClearance: Float): Float =
        max(220f, screenH - topPad - bottomClearance)

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

    // ------------------------------------------------------------------ morph：月 ↔ 周

    /**
     * 月 ↔ 周 morph 在进度 `progress`（0 = 月历、1 = 周态）时的插值量。
     *
     * 对应 `MorphViews.MonthWeekMorphView`：选中行上移到周条位置，上下两行向两侧移出，
     * 大标题上移淡出，内容区上移淡入。
     */
    data class MonthWeekMorphState(
        /** 选中行在当前进度下的 y。 */
        val selectedRowY: Float,
        /** 选中行的度量（在月格与周条之间插值）。 */
        val metrics: DayMetrics,
        /** 选中行的透明度。 */
        val selectedRowAlpha: Float,
        /**
         * 两侧的**整体位移量**（用户 2026-09-26 明确描述的动效模型）。
         *
         * 选中行及其**上方**各行一起上移 [upShift]（相对间距**不变**），
         * 直到选中行到达周条位置；选中行**下方**各行一起下移 [downShift] 直到移出屏幕。
         *
         * 原来是"上方各行向选中行压缩并淡出"，那会让观感变成
         * "先上移压缩、再展开周视图"两段动画 —— 用户否掉了那个模型。
         */
        val upShift: Float,
        val downShift: Float,
        /** 大标题的位移与透明度。 */
        val titleOffsetY: Float,
        val titleOpacity: Float,
        /** 周条自身的透明度（比内容区更陡，避免返回时留残影）。 */
        val stripAlpha: Float,
        /** 内容区的进度、位移与透明度。 */
        val contentT: Float,
        val contentOffsetY: Float,
        val contentAlpha: Float,
    )

    /**
     * @param baseRowY 选中行在月格里的基准 y（= row * cellH）
     * @param monthHeight 月格区域的总高（= rows * cellH）
     * @param monthMetrics 月格度量
     * @param weekMetrics 周条度量
     * @param stripBottom 周条下方内容区的目标 y（iOS 用 98）
     * @param dayTitleH 周态日期标题行高度（40），内容区上移量含它 + 20
     * @param stripTopY 选中行在**自己容器里的落点**。
     *   iOS 的周条容器在星期栏**之后**，所以落点是 `WEEKDAY_HEADER_H`（30）；
     *   Android 的周条容器紧跟在（会淡出的）星期栏下方，落点是 **0** ——
     *   用 30 会让选中行在动画中段从星期栏文字上穿过去（实测"日号叠在周一上"）。
     */
    /**
     * 周条自身的透明度：比内容区**更陡**（p 到 0.55 左右就退干净）。
     *
     * 用户实测："周视图切换到月视图的时候，周视图的消失慢了点，能看到动画残影。"
     */
    fun weekStripAlpha(progress: Float): Float = clamp01(progress * 1.8f)

    fun monthWeekMorph(
        progress: Float,
        baseRowY: Float,
        monthHeight: Float,
        monthMetrics: DayMetrics,
        weekMetrics: DayMetrics,
        stripBottom: Float = 98f,
        dayTitleH: Float = DAY_TITLE_H,
        stripTopY: Float = WEEKDAY_HEADER_H,
    ): MonthWeekMorphState {
        val p = clamp01(progress)
        val contentT = clamp01((p - 0.25f) / 0.75f)

        return MonthWeekMorphState(
            selectedRowY = lerp(baseRowY, stripTopY, p),
            metrics = monthMetrics.lerp(weekMetrics, p),
            selectedRowAlpha = 1f,
            // 上移量：选中行从 `baseRowY` 走到 `stripTopY`；上方各行**同步同量**上移
            // （所以它们之间的相对间距完全不变 -> 观感是"整块往上走"）。
            upShift = (baseRowY - stripTopY) * p,
            // 下移量：下方第一行从 `baseRowY + cellH` 走到 `monthHeight + cellH`，即刚好移出底部。
            downShift = (monthHeight - baseRowY) * p,
            titleOffsetY = -p * BIG_TITLE_H,
            titleOpacity = 1f - clamp01(p * 2f),
            // 周条的淡入淡出比内容区**更陡**：返回时它必须在 p 还比较大时就退干净，
            // 否则会留下"残影"（用户实测："周视图的消失慢了点，能看到动画残影"）。
            stripAlpha = weekStripAlpha(p),
            contentT = contentT,
            contentOffsetY = stripBottom + (dayTitleH + 20f) * (1f - contentT),
            // 当日面板：返回时也要**早退**（原来 p 降到 0.3 才开始淡出，尾巴拖得长，
            // 与周条一起构成"残影"）。阈值抬到 0.5，返回时前 20% 就退干净。
            contentAlpha = clamp01((p - 0.5f) / 0.3f),
        )
    }

    /**
     * 每一行的**绝对**网格局部 y（[CalendarDayGrid] 的 `rowOffsets` 用的就是它）。
     *
     * 抽成纯函数是为了可测：**p = 0 时它必须逐行等于自然排布 `row × cellH`**，
     * 否则行位移一挂上就会整块跳一格（这个 bug 就是这么漏出去的）。
     */
    fun monthWeekRowOffsets(
        state: MonthWeekMorphState,
        rows: Int,
        selectedWeekIndex: Int,
        cellH: Float,
    ): FloatArray = FloatArray(rows) { row ->
        val natural = row * cellH
        // 选中行与它上方的行：**同一个位移量**（整体上移，相对间距不变）
        // 选中行下方的行：整体下移
        if (row <= selectedWeekIndex) natural - state.upShift else natural + state.downShift
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
