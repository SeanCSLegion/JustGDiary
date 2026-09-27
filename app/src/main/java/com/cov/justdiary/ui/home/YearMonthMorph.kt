package com.cov.justdiary.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.service.Lunar
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.TypeSize
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 年 ↔ 月 morph 层。**照着 iOS `YearMonthMorphView` 重写**（2026-09-27）。
 *
 * 在此之前 Android 的做法是"把连续月历流的整块（后来改成单块）做仿射变换" ——
 * `scaleX/scaleY` 会把日期文字压扁成涂抹，用户反馈"月视图切换年视图的动画也不流畅"。
 * iOS 的做法完全不同，也是这一版照抄的：
 *
 * ```
 * ┌ 年历页（scale 1.6 → 1、alpha 0 → 1，锚在源卡片中心）
 * ├ 本月网格：画在**插值出来的矩形**里，格子按 rowCount 行 × 7 列重排，
 * │           字号在 miniDayFont（≈9-14）与月格字号（20）之间插值 —— 日期始终清晰
 * ├ 大标题 + 星期栏：位置不动，按 `late` 淡入
 * ├ 下个月那一条（小标题 + 能看见的每一行）：年→月淡入 / 月→年迅速消失
 * └ 上个月露在视口里的那几行（本机特有，见下）
 * ```
 *
 * **与 iOS 的两处差异**（都是为了"从任意滚动位置进入都不跳"）：
 *  1. 源矩形取的是**屏幕上那一块网格的当前矩形**（`block.top − 滚动偏移`），不是"该月
 *     静止位置"的矩形 —— 用户滚到半月位置再点年份时，morph 的第一帧与屏幕上那一帧
 *     逐像素一致，不会先跳回整月；
 *  2. 因此还要多画一条"上个月露在视口里的那几行"（iOS 从静止位置开始，不会有这一段）。
 *
 * 整层是**纯绘制的过渡内容**：不进无障碍树、不接收点击。
 */
@Composable
fun YearMonthMorph(
    /** 进度**读取器**（不是值）：网格每帧都要重画，必须在 draw 阶段读。 */
    progress: () -> Float,
    /** true = 月→年（下个月那一条迅速消失）；false = 年→月（它跟着淡入）。 */
    zoomingIn: Boolean,
    layout: MonthFlowLayout,
    /** 当前滚动偏移的**读取器**（年→月时流会被无动画地拨到目标月，所以要读实时值）。 */
    offsetDp: () -> Float,
    monthKey: Int,
    widthDp: Float,
    heightDp: Float,
    titleHeight: Float,
    weekdayHeight: Float,
    /** 目标：年历里那张迷你月的**网格**矩形（内容坐标，已减掉标题槽 + 星期栏）。 */
    targetGrid: CalendarLayout.Rect,
    weekStart: String,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    titleText: (LocalDate) -> String,
    labelText: (LocalDate) -> String,
    /** 年历页（由调用方给：只有一份实现，alpha / scale 由它自己按进度算）。 */
    yearLayer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer(cacheSize = MORPH_TEXT_CACHE)
    val blockIndex = remember(layout, monthKey) { layout.blockIndexForKey(monthKey) }
    if (blockIndex < 0) return
    val block = layout.blocks[blockIndex]
    val flowMetrics = remember(layout, widthDp, showLunar) {
        CalendarLayout.flowMetrics(widthDp, layout.rowH, showLunar)
    }
    val nextBlock = layout.blocks.getOrNull(blockIndex + 1)
    val prevBlock = layout.blocks.getOrNull(blockIndex - 1)
    val cellW = CalendarLayout.monthCellW(widthDp)
    val labelH = remember { CalendarLayout.flowLabelBandHeight(compact = false) }
    val labelBlockH = labelH + CalendarLayout.LABEL_TIGHT_GAP

    // 主题色在组合期取出（Canvas 的绘制 lambda 不是 @Composable）
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    val titleStyle = remember(fontScale, onSurface) {
        TextStyle(
            fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.display, fontScale),
            fontWeight = FontWeight.Bold,
            color = onSurface,
        )
    }
    // ⚠️ morph 画的格子必须与**真实日期格逐像素一致**，否则交班（morph ↔ 真实图层）
    // 那一帧整片日期会跳一下 —— 用户说的"最后会闪一下"就是它。
    //
    // 真实格子（`CalendarDayCell`）的竖向布局是：
    //   Column[ 圆(circleDiameter) , 农历那一行 Text , 有日记的标记(1+2dp) ]
    //   在格子里**居中**，再整体 `+LUNAR_TIGHTEN/2`、农历自己 `-LUNAR_TIGHTEN`。
    // 所以农历 Text 的**盒子高度**（不是字号）决定一切 —— 用同一个 `LocalTextStyle`
    // 量一次，再按字号比例缩放（字号在 morph 里是插值的）。
    val baseTextStyle = LocalTextStyle.current
    val lunarBoxH = remember(measurer, baseTextStyle, fontScale, showLunar) {
        if (!showLunar) {
            0f
        } else {
            val style = baseTextStyle.copy(
                fontSize = DynamicTypeMetrics.calendarSp(
                    flowMetrics.lunarFont.dp,
                    fontScale,
                ),
            )
            measurer.measure(LUNAR_PROBE, style).size.height / density.density
        }
    }

    val labelStyle = remember(fontScale, primary, onSurfaceVariant) {
        TextStyle(
            fontSize = DynamicTypeMetrics.calendarSp(
                CalendarLayout.flowLabelFontSize(compact = false).dp,
                fontScale,
            ),
            fontWeight = FontWeight.SemiBold,
            color = onSurfaceVariant,
        )
    }

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            // 纯视觉的过渡层：无障碍树里只保留真实图层，也不接收点击
            .clearAndSetSemantics { },
    ) {
        yearLayer()

        Canvas(modifier = Modifier.fillMaxSize()) {
            // ⚠️ 进度**在这里读**（draw 阶段）：只让绘制失效，不触发重组
            val p = progress()
            if (p <= 0.0001f) return@Canvas
            val off = offsetDp()
            val t = CalendarLayout.clamp01(1f - p)
            val bodyTop = (titleHeight + weekdayHeight) * density.density
            val blockTop = block.top - off
            val source = CalendarLayout.Rect(0f, blockTop, widthDp, block.rowCount * layout.rowH)
            val grid = CalendarLayout.yearMonthGridMorph(
                progress = p,
                source = source,
                target = targetGrid,
                rowCount = block.rowCount,
                sourceMetrics = flowMetrics,
                showLunar = showLunar,
            )
            val m = grid.metrics
            val gridTop = bodyTop + grid.rect.y * density.density
            val cellPx = m.cellW * density.density
            val rowPx = m.cellH * density.density

            // ⚠️ **一切日期都裁在钉住栏之下**（内容区），与连续流的裁剪逐像素一致。
            //
            // 少了这一层裁剪：上个月露在视口里的那几行会画到**大标题与星期栏上面**，
            // 而动画收尾换回真实图层时，流是按内容区裁的 —— 那些日期就"突然消失"
            // （用户 2026-09-27 的原话）。年→月时更明显：目标月被无动画地拨到静止位置，
            // 上个月整块都在内容区之上，本来就一行都不该画。
            clipRect(
                left = 0f,
                top = bodyTop,
                right = size.width,
                bottom = size.height,
            ) {

            // ---- 上个月露在视口里的那几行：不变换、快速淡出 ----
            val neighborAlpha = 1f - CalendarLayout.clamp01(p * 3f)
            // 上个月那几行只在"源矩形之上确实还露着"时才画：年→月时目标月已经被拨到
            // 静止位置，上个月整块都在内容区之上 —— 一行都不该画（用户：
            // "特别是前一个月的日期，你现在前一个月的日期也有动画"）。
            val prevBottom = prevBlock?.let { bodyTop + (it.bottom - off) * density.density } ?: 0f
            if (prevBlock != null && neighborAlpha > 0.01f && prevBottom > bodyTop + 0.5f) {
                CalendarGrid.displayedWeeks(prevBlock.monthFirst, weekStart).forEachIndexed { i, week ->
                    val rowY = bodyTop + (prevBlock.top + i * layout.rowH - off) * density.density
                    if (rowY + layout.rowH * density.density > bodyTop - 1f && rowY < size.height) {
                        drawWeekRow(
                            week = week,
                            anchorMonth = prevBlock.monthFirst,
                            rowY = rowY,
                            metrics = flowMetrics,
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            alpha = neighborAlpha,
                            showDivider = !(prevBlock.index == 0 && i == 0),
                            measurer = measurer,
                            fontScale = fontScale,
                            primary = primary,
                            onPrimary = onPrimary,
                            onSurface = onSurface,
                            onSurfaceVariant = onSurfaceVariant,
                            dividerColor = dividerColor,
                            lunarBoxHDp = lunarBoxHDpFor(flowMetrics.lunarFont, lunarBoxH),
                            t = t,
                        )
                    }
                }
            }

            // ---- 本月网格（主角）：插值矩形 + 重排格子 ----
            // ⚠️ 网格的**左边**必须一路传下去：格子与分割线都是以"网格左上角"为原点画的，
            // 少了这个偏移，网格会永远贴在最左边（只有宽度在缩）—— 用户报的
            // "计算位置比实际位置偏左"就是它。
            val gridLeft = grid.rect.x * density.density
            CalendarGrid.displayedWeeks(block.monthFirst, weekStart).forEachIndexed { row, week ->
                val rowY = gridTop + row * rowPx
                // 分割线：本块第一行不画（整条流的第一块），其余都画 —— 与连续流一致
                if (row > 0 || block.index != 0) {
                    drawCellDividers(
                        columns = inMonthColumns(week, block.monthFirst),
                        rowY = rowY,
                        cellW = cellPx,
                        alpha = m.dividerAlpha,
                        color = dividerColor,
                        originX = gridLeft,
                    )
                }
                week.forEachIndexed { col, day ->
                    if (!inMonth(day, block.monthFirst)) return@forEachIndexed
                    drawDayCell(
                        day = day,
                        col = col,
                        rowY = rowY,
                        cellW = cellPx,
                        cellH = rowPx,
                        m = m,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        measurer = measurer,
                        fontScale = fontScale,
                        primary = primary,
                        onPrimary = onPrimary,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        lunarBoxHDp = lunarBoxHDpFor(m.lunarFont, lunarBoxH),
                        t = t,
                        originX = gridLeft,
                    )
                }
            }

            // 本月小标题：跟着网格一起缩、一起淡（它在静止位置本来就藏在钉住层后面）
            val labelAlpha = (1f - CalendarLayout.clamp01(p * 2.2f)) * t
            if (labelAlpha > 0.01f) {
                val text = measurer.measure(labelText(block.monthFirst), labelStyle)
                drawText(
                    textLayoutResult = text,
                    topLeft = Offset(
                        x = gridLeft +
                            DateUtil.weekdayIndex(block.monthFirst, weekStart) * cellPx +
                            (cellPx - text.size.width) / 2f,
                        y = gridTop - labelBlockH * density.density,
                    ),
                    alpha = labelAlpha,
                )
            }

            // ---- 下个月那一条（小标题 + 能看见的每一行）----
            // 方向不同、处理也不同（与 iOS 的 `peekFadesIn` 一致）：
            //  · 年 → 月：它是**要出现**的内容，跟着这一段动画淡入；
            //  · 月 → 年：它是**要离开**的内容，一开场就迅速消失。
            val peekAlpha = if (zoomingIn) {
                CalendarLayout.clamp01((t - 0.9f) / 0.1f)
            } else {
                CalendarLayout.clamp01((t - 0.55f) / 0.45f)
            }
            if (nextBlock != null && peekAlpha > 0.01f) {
                val nextTop = bodyTop + (block.bottom - off) * density.density
                CalendarGrid.displayedWeeks(nextBlock.monthFirst, weekStart).forEachIndexed { i, week ->
                    val rowY = nextTop + i * layout.rowH * density.density
                    if (rowY < size.height - 0.5f) {
                        drawWeekRow(
                            week = week,
                            anchorMonth = nextBlock.monthFirst,
                            rowY = rowY,
                            metrics = flowMetrics,
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            alpha = peekAlpha,
                            showDivider = true,
                            measurer = measurer,
                            fontScale = fontScale,
                            primary = primary,
                            onPrimary = onPrimary,
                            onSurface = onSurface,
                            onSurfaceVariant = onSurfaceVariant,
                            dividerColor = dividerColor,
                            lunarBoxHDp = lunarBoxHDpFor(flowMetrics.lunarFont, lunarBoxH),
                            t = t,
                        )
                    }
                }
                val text = measurer.measure(labelText(nextBlock.monthFirst), labelStyle)
                drawText(
                    textLayoutResult = text,
                    topLeft = Offset(
                        x = DateUtil.weekdayIndex(nextBlock.monthFirst, weekStart) * cellW * density.density +
                            (cellW * density.density - text.size.width) / 2f,
                        y = nextTop - labelBlockH * density.density,
                    ),
                    alpha = peekAlpha,
                )
            }

            }   // clipRect：内容区（钉住栏之下）
        }

        // ---- 大标题 + 星期栏：位置不动，只按 `late` 淡入 ----
        MonthMorphHeader(
            title = titleText(block.monthFirst),
            widthDp = widthDp,
            titleHeight = titleHeight,
            weekdayHeight = weekdayHeight,
            weekStart = weekStart,
            alpha = remember { { CalendarLayout.clamp01(((1f - progress()) - 0.55f) / 0.45f) } },
            titleStyle = titleStyle,
        )
    }
}

/** morph 层里的大标题 + 星期栏（钉在顶部、位置不动，只淡入淡出）。 */
@Composable
private fun MonthMorphHeader(
    title: String,
    widthDp: Float,
    titleHeight: Float,
    weekdayHeight: Float,
    weekStart: String,
    alpha: () -> Float,
    titleStyle: TextStyle,
) {
    Column(
        modifier = Modifier
            .width(widthDp.dp)
            .graphicsLayer { this.alpha = alpha() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(titleHeight.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 20.dp),
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CalendarWeekdayHeader(
            weekStart = weekStart,
            modifier = Modifier.height(weekdayHeight.dp),
        )
    }
}

// --------------------------------------------------------------------------- 绘制

/** 这一行里属于锚定月份的列（分割线只跨这些格）。 */
private fun inMonthColumns(week: List<LocalDate>, anchorMonth: LocalDate): List<Int> =
    week.indices.filter { inMonth(week[it], anchorMonth) }

private fun inMonth(day: LocalDate, anchorMonth: LocalDate): Boolean =
    day.year == anchorMonth.year && day.monthValue == anchorMonth.monthValue

/**
 * 画一整行（只画属于 [anchorMonth] 的格）。
 *
 * 几何与配色与 `CalendarDayGrid`（真实组件版）**同源**：日号、农历、选中圆、今天圆环、
 * 有日记的短横线、按格的分割线 —— 只有"点击"是缺的（morph 层不接收点击）。
 * 单位：`rowY` / `cellW` 等几何量都是 **px**，绘制时不再换算。
 */
private fun DrawScope.drawWeekRow(
    week: List<LocalDate>,
    anchorMonth: LocalDate,
    rowY: Float,
    metrics: CalendarLayout.DayMetrics,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    alpha: Float,
    showDivider: Boolean,
    measurer: TextMeasurer,
    fontScale: Float,
    primary: Color,
    onPrimary: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    dividerColor: Color,
    lunarBoxHDp: Float,
    t: Float,
) {
    if (alpha <= 0.01f) return
    val cellPx = metrics.cellW * density
    val rowPx = metrics.cellH * density
    if (showDivider) {
        drawCellDividers(
            columns = inMonthColumns(week, anchorMonth),
            rowY = rowY,
            cellW = cellPx,
            alpha = metrics.dividerAlpha,
            color = dividerColor,
        )
    }
    week.forEachIndexed { col, day ->
        if (!inMonth(day, anchorMonth)) return@forEachIndexed
        drawDayCell(
            day = day,
            col = col,
            rowY = rowY,
            cellW = cellPx,
            cellH = rowPx,
            m = metrics,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            measurer = measurer,
            fontScale = fontScale,
            primary = primary,
            onPrimary = onPrimary,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            lunarBoxHDp = lunarBoxHDp,
            t = t,
            alpha = alpha,
        )
    }
}

/** 按格的分割线：只画"这一行真正有日期的那几列"（与 `CalendarDayGrid` 同一条规则）。 */
private fun DrawScope.drawCellDividers(
    columns: List<Int>,
    rowY: Float,
    cellW: Float,
    alpha: Float,
    color: Color,
    /** 网格左边缘的 x（px）。相邻月那两条 peek 传 0（它们铺满整宽）。 */
    originX: Float = 0f,
) {
    if (columns.isEmpty() || alpha <= 0.01f) return
    val thickness = 1.dp.toPx()
    columns.forEach { col ->
        drawRect(
            color = color.copy(alpha = 0.35f * alpha),
            topLeft = Offset(originX + col * cellW, rowY),
            size = Size(cellW, thickness),
        )
    }
}

/** 一个日期格。`rowY` / `cellW` / `cellH` 都是 px；[m] 里的量仍是 dp（几何函数吃 dp）。 */
private fun DrawScope.drawDayCell(
    day: LocalDate,
    col: Int,
    rowY: Float,
    cellW: Float,
    cellH: Float,
    m: CalendarLayout.DayMetrics,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    measurer: TextMeasurer,
    fontScale: Float,
    primary: Color,
    onPrimary: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    /** 农历那一行 Text 的盒子高度（dp，与真实格子同一个 `LocalTextStyle` 量出来的）。 */
    lunarBoxHDp: Float,
    /** `1` = 月侧端点（月格模型）、`0` = 年侧端点（年历迷你月模型）。 */
    t: Float,
    alpha: Float = 1f,
    /** 网格左边缘的 x（px）。 */
    originX: Float = 0f,
) {
    if (alpha <= 0.01f) return
    val key = DateUtil.dayKeyOf(day)
    val isSelected = key == selectedKey
    val isToday = key == todayKey
    val isFuture = key > todayKey

    // 几何：把 px 的 rowY / 格宽格高换回 dp，复用 CalendarLayout 的纯函数
    val rowYDp = rowY / density
    val metrics = m.copy(cellW = cellW / density, cellH = cellH / density)
    // 两边端点是**两套不同的排版模型**，必须按 `t` 插值，否则交班那一帧会跳：
    //  · 月侧（t=1）：`CalendarDayCell` —— Column[圆, 农历, 标记] 居中 + 3dp，农历 −6dp；
    //  · 年侧（t=0）：`YearCanvas` 的迷你月 —— 日号**直接居中在格子里**，
    //    圆半径 = `min(格宽, 格高) × 0.42`，标记挂在圆下方 0.86×半径处，选中/有日记加粗。
    val layout = cellLayout(rowYDp, metrics, lunarBoxHDp)
    val miniBlend = CalendarLayout.clamp01(1f - t)
    val cellCenterY = (rowYDp + metrics.cellH / 2f) * density
    val cx = originX + CalendarLayout.cellCenterX(col, metrics.cellW) * density
    val circleCy = CalendarLayout.lerp(layout.circleCenterY * density, cellCenterY, miniBlend)
    val miniRadius = minOf(metrics.cellW, metrics.cellH) * 0.42f * density
    val radius = CalendarLayout.lerp(metrics.circleDiameter / 2f * density, miniRadius, miniBlend)

    when {
        isSelected -> drawCircle(
            color = primary.copy(alpha = alpha),
            radius = radius,
            center = Offset(cx, circleCy),
        )
        isToday -> drawCircle(
            color = primary.copy(alpha = alpha),
            radius = (radius - CalendarLayout.TODAY_RING_WIDTH / 2f * density).coerceAtLeast(0f),
            center = Offset(cx, circleCy),
            style = Stroke(width = CalendarLayout.TODAY_RING_WIDTH * density),
        )
    }

    val textColor = when {
        isSelected -> onPrimary
        isToday -> primary
        isFuture -> onSurfaceVariant
        else -> onSurface
    }
    // 字重：月格模型一律常重；年历迷你月把"选中 / 有日记"加粗 —— 同样按 `t` 插值。
    val weight = if ((isSelected || flags.contains(key)) && miniBlend > 0.01f) {
        FontWeight(CalendarLayout.lerp(400f, 700f, miniBlend).roundToInt().coerceIn(1, 1000))
    } else {
        FontWeight.Normal
    }
    val num = measurer.measure(
        day.dayOfMonth.toString(),
        TextStyle(
            fontSize = DynamicTypeMetrics.calendarSp(metrics.dayFont.dp, fontScale),
            color = textColor,
            fontWeight = weight,
        ),
    )
    drawText(
        textLayoutResult = num,
        topLeft = Offset(
            x = cx - num.size.width / 2f,
            y = circleCy - num.size.height / 2f,
        ),
        alpha = alpha,
    )
    if (metrics.lunarAlpha > 0.01f) {
        val lunar = measurer.measure(
            Lunar.dayLabelOf(day),
            TextStyle(
                fontSize = DynamicTypeMetrics.calendarSp(metrics.lunarFont.dp, fontScale),
                color = textColor.copy(alpha = 0.75f),
            ),
        )
        drawText(
            textLayoutResult = lunar,
            topLeft = Offset(
                x = cx - lunar.size.width / 2f,
                y = layout.lunarCenterY * density - lunar.size.height / 2f,
            ),
            alpha = alpha * metrics.lunarAlpha,
        )
    }
    if (flags.contains(key)) {
        val markW = minOf(20f, metrics.cellW * 0.5f)
        val miniMarkTop = cellCenterY + miniRadius * 0.86f
        val markTop = CalendarLayout.lerp(layout.markTop * density, miniMarkTop, miniBlend)
        drawRoundRect(
            color = (if (isSelected) onPrimary else primary).copy(alpha = alpha),
            topLeft = Offset(cx - markW / 2f * density, markTop),
            size = Size(markW * density, 2f * density),
            cornerRadius = CornerRadius(1f * density),
        )
    }
}

/**
 * 一个日期格里"圆 / 农历 / 标记"的竖向位置（dp，**相对本行顶部**）。
 *
 * 逐条对应 `CalendarDayCell` 的布局：
 * ```
 * Column(居中 + offset(+LUNAR_TIGHTEN/2)) {
 *     圆(circleDiameter)   // 日号在圆里居中
 *     农历 Text            // offset(-LUNAR_TIGHTEN)
 *     标记(1dp padding + 2dp)
 * }
 * ```
 * ⚠️ 这里**不能**用 `CalendarLayout.contentTop / lunarCenterY`（那是 iOS `DayDraw` 的
 * 公式，Android 的真实格子是组件布局，两者差 4~11dp）—— 差这点，morph 交班那一帧
 * 整片日期就会跳一下。
 */
private data class CellLayout(
    val circleCenterY: Float,
    val lunarCenterY: Float,
    val markTop: Float,
)

private fun cellLayout(rowY: Float, m: CalendarLayout.DayMetrics, lunarBoxHDp: Float): CellLayout {
    val markH = 3f                                   // 1dp padding + 2dp 短横线
    val columnH = m.circleDiameter + lunarBoxHDp + markH
    val top = rowY + (m.cellH - columnH) / 2f + CalendarLayout.LUNAR_TIGHTEN / 2f
    return CellLayout(
        circleCenterY = top + m.circleDiameter / 2f,
        lunarCenterY = top + m.circleDiameter + lunarBoxHDp / 2f - CalendarLayout.LUNAR_TIGHTEN,
        markTop = top + m.circleDiameter + lunarBoxHDp + 1f,
    )
}

/** 农历盒子高度按字号比例缩放（字号在 morph 里是插值的）。 */
private fun lunarBoxHDpFor(lunarFont: Float, flowLunarBoxH: Float): Float =
    if (flowLunarBoxH <= 0f) 0f else flowLunarBoxH * (lunarFont / FLOW_LUNAR_FONT_REF)

/** morph 层的文本测量缓存（字号每帧都在变，缓存要够大）。 */
private const val MORPH_TEXT_CACHE = 256

/** 量农历盒子高度用的探针文本（两个汉字，和真实内容同宽同高）。 */
private const val LUNAR_PROBE = "初一"

/** 流里农历字号的参考值（`CalendarLayout.flowMetrics` 的 `lunarFont`，竖屏是 11）。 */
private const val FLOW_LUNAR_FONT_REF = 11f
