package com.cov.justdiary.ui.home

import com.cov.justdiary.model.DateUtil
import java.time.LocalDate
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

    /** morph 时长（毫秒）。 */
    const val MORPH_DURATION_MS = 600

    /** `-slow-morph` / `SLOW_MORPH=1` 下的调试慢放时长。 */
    const val SLOW_MORPH_DURATION_MS = 3000

    /** 减弱动态效果时的时长（线性）。 */
    const val REDUCED_MORPH_DURATION_MS = 180

    /**
     * morph 的曲线控制点 `timingCurve(0.2, 0.75, 0.3, 1.0)`。
     *
     * 为什么不是 `easeInOut`：实测同样 0.6s，`easeInOut` 在时间过 25% 时只走了 13% 进度，
     * 而这条曲线走 65% —— 这就是当初"感觉慢"的根因（`docs/animation-and-accessibility.md:38-48`）。
     */
    val MORPH_EASING = floatArrayOf(0.2f, 0.75f, 0.3f, 1.0f)

    /** 翻月 / 今天是 `.snappy(duration: 0.3)`；横屏点日期与 toast 是 0.25。 */
    const val PAGE_SETTLE_DURATION_MS = 300
    const val QUICK_SETTLE_DURATION_MS = 250

    /** 竖屏：星期栏与大标题槽。 */
    const val WEEKDAY_HEADER_H = 30f
    const val BIG_TITLE_H = 72f

    /** 横屏：紧凑的月标题与星期栏。 */
    const val COMPACT_MONTH_TITLE_H = 34f
    const val COMPACT_WEEKDAY_HEADER_H = 26f

    /** 周条行高与周态日期标题行。 */
    const val WEEK_STRIP_H = 68f
    const val DAY_TITLE_H = 40f

    /** 年历：标题槽、左右边距、卡片间距、迷你月内缩与标题槽。 */
    const val YEAR_TITLE_H = 62f
    const val YEAR_TOP_PAD = 8f
    const val YEAR_PAD = 16f
    const val YEAR_SPACING = 6f
    const val MINI_PAD = 6f
    const val MINI_TITLE_H = 17f
    const val MINI_LUNAR_FONT = 6f

    /** 每行最小高度（低于它就要降级为周条）。 */
    const val MINIMUM_ROW_HEIGHT = 44f

    /** 月历固定 6 行。 */
    const val MONTH_ROWS = 6

    /** 竖屏日历区的高度下限与底部为系统导航让出的高度。 */
    const val PORTRAIT_MIN_AREA_H = 320f
    const val PORTRAIT_BOTTOM_CLEARANCE = 64f

    /** 未选中的相邻月日期的不透明度。 */
    const val ADJACENT_MONTH_ALPHA = 0.35f

    /** 未来日期的整体不透明度系数。 */
    const val FUTURE_ALPHA = 0.35f

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

    /** 竖屏月格行高：`(areaH - 大标题 72 - 星期栏 30) / 6`。 */
    fun monthCellH(areaH: Float, rows: Int = MONTH_ROWS): Float =
        (areaH - BIG_TITLE_H - WEEKDAY_HEADER_H) / rows

    /** 竖屏日历区可用高度：`max(320, screenH - 64)`。 */
    fun portraitAreaHeight(screenH: Float): Float = max(PORTRAIT_MIN_AREA_H, screenH - PORTRAIT_BOTTOM_CLEARANCE)

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
        cellH = (areaH - headerH) / rows,
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

    /** 年历卡片尺寸：`((w - 16*2 - 6*2)/3, (h - 62 - 8 - 6*3)/4)`。 */
    fun yearCardSize(width: Float, height: Float): Pair<Float, Float> = Pair(
        (width - YEAR_PAD * 2 - YEAR_SPACING * 2) / 3f,
        (height - YEAR_TITLE_H - YEAR_TOP_PAD - YEAR_SPACING * 3) / 4f,
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

    /** 迷你月网格在卡片内的位置（`miniGridInCard = (6, 23)`）。 */
    fun miniGridRect(card: Rect): Rect = Rect(
        x = card.x + MINI_PAD,
        y = card.y + MINI_PAD + MINI_TITLE_H,
        w = card.w - MINI_PAD * 2,
        h = card.h - MINI_PAD * 2 - MINI_TITLE_H,
    )

    /** 真实月格的完整网格区：`(0, 102, w, h - 102)`。 */
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
        val perRow = (availableHeight / safeRows).roundToInt()
        return when {
            perRow >= (MINIMUM_ROW_HEIGHT + 16f).toInt() -> Density(DensityKind.Month, wantsLunar)
            perRow >= MINIMUM_ROW_HEIGHT.toInt() -> Density(DensityKind.Month, false)
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
            yearOpacity = clamp01((p - 0.45f) / 0.55f),
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
        /** 上方各行的 y 与透明度。 */
        val rowAboveY: Float,
        val rowAboveAlpha: Float,
        /** 下方各行的 y 与透明度。 */
        val rowBelowY: Float,
        val rowBelowAlpha: Float,
        /** 大标题的位移与透明度。 */
        val titleOffsetY: Float,
        val titleOpacity: Float,
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
     */
    fun monthWeekMorph(
        progress: Float,
        baseRowY: Float,
        monthHeight: Float,
        monthMetrics: DayMetrics,
        weekMetrics: DayMetrics,
        stripBottom: Float = 98f,
        dayTitleH: Float = DAY_TITLE_H,
    ): MonthWeekMorphState {
        val p = clamp01(progress)
        val rowAboveAlpha = 1f - clamp01(p * 1.4f)
        val contentT = clamp01((p - 0.25f) / 0.75f)

        return MonthWeekMorphState(
            selectedRowY = lerp(baseRowY, WEEKDAY_HEADER_H, p),
            metrics = monthMetrics.lerp(weekMetrics, p),
            selectedRowAlpha = 1f,
            rowAboveY = baseRowY - p * (baseRowY + monthMetrics.cellH),
            rowAboveAlpha = rowAboveAlpha,
            rowBelowY = baseRowY + p * (monthHeight - baseRowY),
            rowBelowAlpha = rowAboveAlpha,
            titleOffsetY = -p * BIG_TITLE_H,
            titleOpacity = 1f - clamp01(p * 2f),
            contentT = contentT,
            contentOffsetY = stripBottom + (dayTitleH + 20f) * (1f - contentT),
            contentAlpha = clamp01((p - 0.3f) / 0.5f),
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

    // ------------------------------------------------------------------ 日期 → 行号

    /** 某一天在月格中的行号（`CalendarLayout.swift:364-368`）。 */
    fun weekRowIndex(date: LocalDate, monthFirst: LocalDate, weekStart: String): Int {
        val lead = DateUtil.weekdayIndex(monthFirst, weekStart)
        return (lead + date.dayOfMonth - 1) / 7
    }

    /** 相邻月份的日期在锚定月份网格里的额外透明度（与 iOS 的 `adjacentAlpha` 默认值一致）。 */
    fun adjacentAlpha(showAdjacent: Boolean): Float = if (showAdjacent) 1f else ADJACENT_MONTH_ALPHA
}
