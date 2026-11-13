package com.cov.justdiary.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
    // 整段动画复用同一张布局表（见 `MorphTextLayouts`）：日号/农历每帧都要画，
    // 但真正**需要重新排版**的只有"字号跳档"那几次，实测能把每帧 12ms 的测量降到 <2ms。
    val textLayouts = MorphTextLayouts.shared
    val blockIndex = remember(layout, monthKey) { layout.blockIndexForKey(monthKey) }
    if (blockIndex < 0) return
    val block = layout.blocks[blockIndex]
    val flowMetrics = remember(layout, widthDp, showLunar) {
        CalendarLayout.flowMetrics(widthDp, layout.rowH, showLunar)
    }
    val nextBlock = layout.blocks.getOrNull(blockIndex + 1)
    val prevBlock = layout.blocks.getOrNull(blockIndex - 1)
    val labelH = remember { CalendarLayout.flowLabelBandHeight(compact = false) }
    val labelBlockH = labelH + CalendarLayout.LABEL_TIGHT_GAP

    // 主题色在组合期取出（Canvas 的绘制 lambda 不是 @Composable）
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    /**
     * 网格随进度插值出来的矩形（源 = 屏幕上那一块月网格，目标 = 年历里那张迷你月）。
     *
     * ⚠️ 组合期**不读进度**：这个函数只在延迟 lambda（`draw` / `graphicsLayer`）里被调用。
     * 小标题那一层（真实 composable）也要用它，所以抽出来共用一份算式。
     *
     * ⚠️ 源矩形必须按**真实放置的取整口径**算：连续流里每个块的位置是
     * `Modifier.offset { IntOffset(0, ((block.top − off) × density).roundToInt()) }`，
     * 也就是**整像素**；内容层自己的 `offset(y = (标题槽 + 星期栏).dp)` 同样取整。
     * 少了这两处取整，morph 的第一帧与屏幕上的月视图会差 ~1px —— 整片日号描边
     * 会"重影"一下（录屏逐帧可测：整列最佳位移 1px）。
     */
    fun morphedGrid(p: Float): CalendarLayout.YearMonthGridMorph {
        val off = offsetDp()
        val densityScale = density.density
        val blockTopDp = (block.top - off).let { raw ->
            if (densityScale > 0f) {
                kotlin.math.round(raw * densityScale) / densityScale
            } else {
                raw
            }
        }
        val source = CalendarLayout.Rect(0f, blockTopDp, widthDp, block.rowCount * layout.rowH)
        return CalendarLayout.yearMonthGridMorph(
            progress = p,
            source = source,
            target = targetGrid,
            sourceMetrics = flowMetrics,
            showLunar = showLunar,
        )
    }

    val todayMonthKey = remember(todayKey) {
        DateUtil.parseDayKey(todayKey)?.let { CalendarGrid.monthKeyOf(it) } ?: 0
    }

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            // 纯视觉的过渡层：无障碍树里只保留真实图层，也不接收点击
            .clearAndSetSemantics { },
    ) {
        yearLayer()

        // ---- 大标题 + 星期栏：位置不动，只按 `late` 淡入 ----
        // ⚠️ 与月视图 / 月↔周 morph 共用**同一个 composable**（`MonthPinnedHeader`）。
        // 各写一份的代价实测过：`Text` 的盒子高度由 `LocalTextStyle` 的行高决定
        //（`bodyLarge` 的 `lineHeight`），写法稍差一点，交班那一帧整条标题就会上下跳。
        // 年 morph 不画底色（它底下是正在淡入的年历，压一条会切掉年历顶部）。
        MonthPinnedHeader(
            title = titleText(block.monthFirst),
            widthDp = widthDp,
            titleHeight = titleHeight,
            weekdayHeight = weekdayHeight,
            weekStart = weekStart,
            modifier = Modifier.graphicsLayer {
                alpha = CalendarLayout.clamp01(((1f - progress()) - 0.55f) / 0.45f)
            },
            drawBackground = false,
        )

        // ---- 小标题：**真实 composable**（与连续月历流里那个 `MonthFlowLabel` 同一个）
        //
        // 用户 2026-09-27："年视图切换到月视图的时候，小标题的高度有些偏高，所以在动画
        // 结束会跳一下。"
        //
        // 根因不是位置算式（两端其实已经对齐），而是**文字排版**：真实的 `MonthFlowLabel`
        // 是一个 `Text`，它的高度由 `LocalTextStyle` 的行高决定（`bodyLarge` 的
        // `lineHeight = 24sp`，字号只有 15sp）—— 盒子比字高，字在盒子里居中；
        // 而 morph 侧原来按 `TextStyle(fontSize = 15sp)` 量出来的是一个 ~21dp 的矮盒子。
        // 两个盒子**顶边对齐**、高度差 3dp ⇒ 字本身偏高约 1.5dp，收尾换回真图层时往下跳。
        // 现在两侧共用同一个 composable，排版天然一致。
        //
        // ⚠️ 位置**钉在最终位置**（月视图静止时那一处），**不跟网格走** ——
        // 用户 2026-09-27："年视图切换到月视图的时候，小标题其实是一开始就要在最终位置的，
        // 也就是小标题这个时候和其他月份日期一样不参与动画。"
        // 与 iOS 同构：`YearMonthMorphView` 里"下个月那一条"（小标题 + 第一行）就是按
        // `fullRect` 的**静止几何**画的，只有透明度在变；本月网格缩放的中间态会从它上面
        // 扫过去（网格画在它之后），所以看起来就是"网格长出来、小标题一直在那儿"。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((heightDp - titleHeight - weekdayHeight).dp)
                .offset(y = (titleHeight + weekdayHeight).dp)
                // 与真实月流的内容层同一条边界：顶上那块小标题（落在钉住层底下）本来就看不见
                .clipToBounds()
                // ⚠️ 透明度必须挂在**这一层（有确定尺寸）**上，不能挂到 `MonthFlowLabel`
                // 自己身上 —— 见下面"下个月那一条"那处的实测记录。
                .graphicsLayer {
                    val p = progress()
                    alpha = (1f - CalendarLayout.clamp01(p * 2.2f)) * (1f - p)
                },
        ) {
            // 本月小标题：位置钉在最终位置（实测块顶 − 一行字高），**与进度无关**；
            // 用 `offsetDp()` 而不是公式反算的块顶：冻结侧与真实渲染必须同源。
            MonthFlowLabel(
                cellW = CalendarLayout.monthCellW(widthDp),
                column = DateUtil.weekdayIndex(block.monthFirst, weekStart),
                text = labelText(block.monthFirst),
                isCurrent = block.key == todayMonthKey,
                modifier = Modifier.graphicsLayer {
                    translationY = (block.top - offsetDp() - labelBlockH) * density.density
                },
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            // ⚠️ 进度**在这里读**（draw 阶段）：只让绘制失效，不触发重组
            val p = progress()
            // ⚠️ **不要**在 `p = 0` 时提前返回。`p = 0` 正是年→月的**最后一帧**，
            // 这一帧必须把整月网格按源矩形画满 —— 少了它就是"日期整片消失一帧，
            // 下一帧真实图层才补上"（用户：年→月"最后有一个闪烁"，"闪烁之后日期位置、
            // 小标题也会有一定的偏移"）。morph 层只在 `yearMorphing` 期间挂载，
            // 不需要靠这个 guard 省性能。
            val off = offsetDp()
            val t = CalendarLayout.clamp01(1f - p)
            // ⚠️ 取整口径与真实排版一致：内容层的 `offset(y = (标题槽 + 星期栏).dp)`
            // 是**整像素**（`roundToPx`），不取整的话 morph 的网格会比真实月视图差 ~1px。
            val bodyTop = kotlin.math.round((titleHeight + weekdayHeight) * density.density)
            val grid = morphedGrid(p)
            val m = grid.metrics
            val gridTop = bodyTop + grid.rect.y * density.density
            val cellPx = m.cellW * density.density
            val rowPx = m.cellH * density.density

            // ---- 上个月露在视口里的那几行：不变换、快速淡出 ----
            //
            // ⚠️ **只有这一段**裁在钉住栏之下（内容区），与连续流的裁剪一致：
            // 少了它，上个月那几行会画到**大标题与星期栏上面**，收尾换回真实图层时
            // 又按内容区裁掉 —— 那些日期就"突然消失"（用户 2026-09-27 的原话）。
            //
            // ⚠️ **本月网格绝不能一起裁**：它在本月标题/星期栏**之上**（与 iOS
            // `YearMonthMorphView` 同序：网格是 ZStack 里最后画的那个），缩小过程中会往上
            // 走进标题带；裁在标题下沿的话，**第一排大数字的上半部分会被切掉一点**
            //（用户 2026-09-27："原本大的日期缩小的过程中，只有第一排的数字的上半部分
            //  会有一点遮挡，动画结束后又恢复完整"）。
            clipRect(
                left = 0f,
                top = bodyTop,
                right = size.width,
                bottom = size.height,
            ) {
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
                            textLayouts = textLayouts,
                            fontScale = fontScale,
                            primary = primary,
                            onPrimary = onPrimary,
                            onSurface = onSurface,
                            onSurfaceVariant = onSurfaceVariant,
                            dividerColor = dividerColor,
                            hasLunar = showLunar,
                            t = t,
                        )
                    }
                }
            }

            }   // clipRect：只包"上个月那几行"

            // ---- 本月网格（主角）：插值矩形 + 重排格子 ----
            // 不裁：它画在本月标题之上（与 iOS 同序），缩小过程中允许走进标题带。
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
                        textLayouts = textLayouts,
                        fontScale = fontScale,
                        primary = primary,
                        onPrimary = onPrimary,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        hasLunar = showLunar,
                        t = t,
                        originX = gridLeft,
                    )
                }
            }

            // ---- 本月小标题 ----
            // 画在**上面那一层真实 composable** 里（见 `MonthFlowLabel` 那一段的说明）：
            // 这里只保留网格，位置算式由 `morphedGrid` 两边共用。

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
                // ⚠️ 用**下一块自己的块顶**（`nextBlock.top`），不能用 `block.bottom` ——
                // 加了 `CalendarLayout.MONTH_GAP` 之后两者相差一个间隔，peek 会画高 10dp，
                // morph 收尾换回真实图层时"下个月的日期 + 小标题"就会跳一下
                // （用户 2026-09-27："下一个月的日期和小标题在最后会发生跳变，原因很可能是
                //   你没有把新的间隔放到动画位置计算中"）。
                val nextTop = bodyTop + (nextBlock.top - off) * density.density
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
                            textLayouts = textLayouts,
                            fontScale = fontScale,
                            primary = primary,
                            onPrimary = onPrimary,
                            onSurface = onSurface,
                            onSurfaceVariant = onSurfaceVariant,
                            dividerColor = dividerColor,
                            hasLunar = showLunar,
                            t = t,
                        )
                    }
                }
            }
        }


        // ---- 下个月那一条的**小标题**：画在网格**之后**（= 最上面）----
        //
        // ⚠️ 次序很关键，原来画在网格**之前**，于是整段动画里它都被正在长大的本月网格
        // 盖住 —— 只有最后一两帧网格让开时才露出来，看起来就是"收尾才蹦出来"。
        // iOS 的 `YearMonthMorphView` 把 `peek(...)`（小标题 + 下月第一行）放在 `ZStack`
        // 的**最后**，也就是网格之上；这一层照着改。
        //
        // 透明度仍用 iOS 那条曲线（`peekFadesIn`）：年→月 `clamp01((t − 0.55) / 0.45)`、
        // 月→年 `clamp01((t − 0.9) / 0.1)`；位置与本月小标题一样**钉死在最终位置**。
        if (nextBlock != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((heightDp - titleHeight - weekdayHeight).dp)
                    .offset(y = (titleHeight + weekdayHeight).dp)
                    // ⚠️ **实测（2026-09-27）**：把 `alpha` 挂在 `MonthFlowLabel` **自己**身上
                    // （`Modifier.graphicsLayer { alpha = 0.7f }`）时，整块标签**一个像素都不画**
                    // —— 连挂在同一层的纯色背景也不画；alpha = 1f 时又完全正常。位置
                    //（`translationY`）在同一层上是好的，所以不是"层没生效"，而是
                    // "层的尺寸来自 wrap-content 的子节点"时那块离屏缓冲画不出来。
                    // 结论：**透明度一律挂在外层这个有确定尺寸的 Box 上**，标签自己只做位移。
                    .graphicsLayer {
                        val p = progress()
                        val t = CalendarLayout.clamp01(1f - p)
                        alpha = if (zoomingIn) {
                            CalendarLayout.clamp01((t - 0.9f) / 0.1f)
                        } else {
                            CalendarLayout.clamp01((t - 0.55f) / 0.45f)
                        }
                    },
            ) {
                MonthFlowLabel(
                    cellW = CalendarLayout.monthCellW(widthDp),
                    column = DateUtil.weekdayIndex(nextBlock.monthFirst, weekStart),
                    text = labelText(nextBlock.monthFirst),
                    isCurrent = nextBlock.key == todayMonthKey,
                    modifier = Modifier.graphicsLayer {
                        translationY = (nextBlock.top - offsetDp() - labelBlockH) * density.density
                    },
                )
            }
        }
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
internal fun DrawScope.drawWeekRow(
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
    textLayouts: MorphTextLayouts,
    fontScale: Float,
    primary: Color,
    onPrimary: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    dividerColor: Color,
    hasLunar: Boolean = true,
    t: Float,
    /**
     * **相邻月那几格**画在哪个 y（px）。`null`（默认）= 不画它们。
     *
     * 只给"选中行"用：一条跨月的周在两个月里各有一行（各画自己那半个月），
     * 选中它时那一周要在周条里**合成一条**——所以相邻月那几天得从"自己那一行"的位置
     * 走到周条里（iOS `MonthWeekMorphView.adjacentShifts`）。其余行不传，
     * 因为它们的相邻月由**对面那一块自己的行**负责画，这里再画一遍就是叠字。
     */
    adjacentRowY: Float? = null,
    /**
     * `false` = **只画分割线、不画日期格**。
     *
     * 给"选中周的另一半"那一行用：它的日期由**选中行里那份**负责画
     *（那份会从这一行的位置走到周条里）。两边都画 = 同一天有两个动画，
     * 而且中段并不重合（用户 2026-09-27："有重复的日期动画，而且动画并不完全重合"）。
     */
    drawCells: Boolean = true,
) {
    if (alpha <= 0.01f) return
    val cellPx = metrics.cellW * density
    val rowPx = metrics.cellH * density
    if (showDivider) {
        // 分割线只跨**本月**那几列（相邻月那几格的分割线属于它们自己那一行）
        drawCellDividers(
            columns = inMonthColumns(week, anchorMonth),
            rowY = rowY,
            cellW = cellPx,
            alpha = metrics.dividerAlpha,
            color = dividerColor,
        )
    }
    if (!drawCells) return
    week.forEachIndexed { col, day ->
        val inMonth = inMonth(day, anchorMonth)
        if (!inMonth && adjacentRowY == null) return@forEachIndexed
        drawDayCell(
            day = day,
            col = col,
            rowY = if (inMonth) rowY else adjacentRowY!!,
            cellW = cellPx,
            cellH = rowPx,
            m = metrics,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            measurer = measurer,
            textLayouts = textLayouts,
            fontScale = fontScale,
            primary = primary,
            onPrimary = onPrimary,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            hasLunar = hasLunar,
            t = t,
            alpha = alpha,
        )
    }
}

/** 按格的分割线：只画"这一行真正有日期的那几列"（与 `CalendarDayGrid` 同一条规则）。 */
internal fun DrawScope.drawCellDividers(
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
internal fun DrawScope.drawDayCell(
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
    textLayouts: MorphTextLayouts,
    fontScale: Float,
    primary: Color,
    onPrimary: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    /**
     * 这一格要不要画农历。
     *
     * ⚠️ 排版**完全由字号驱动**（见下面的说明），所以调用方不必再量"文字盒子高度"传进来 ——
     * 以前那两个 `lunarBoxHDp` / `dayBoxHDp` 实际上只被当布尔用。
     */
    hasLunar: Boolean = true,
    /** `1` = 月侧端点（月格模型）、`0` = 年侧端点（年历迷你月模型）。 */
    t: Float,
    /**
     * `true` = **日号靠左、农历靠右右对齐**（用户 2026-09-27："把农历放右边，右对齐"）。
     *
     * 只改横向落点：日号仍在左边那份的垂直中心上（即圆心/组心），农历贴右边线。
     * `false`（默认）= 日号居中、农历在它下方（月视图与周条的既有排布）。
     */
    lunarRightAligned: Boolean = false,
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
    //    圆半径 = `min(格宽, 格高) × 0.46`，标记挂在日号行盒底，选中/有日记加粗。
    // ---- 排版（**直接算在这里**，不经中间层）----
    //
    // 用户 2026-09-27 拍板：选中圆里装「日号 + 有日记标记 + 农历」，三样作为**一组**居中；
    // 英文环境没有农历 ⇒ 自然退化成日号+标记居中。
    //
    // 单位约定（与原实现一致）：`m.cellW / cellH / dayFont / lunarFont` 是 **dp**，
    // 而 `rowY / cellW / cellH` 这几个入参是 **px**；`layout.*` 一律是 **dp**。
    // ⚠️ **排版完全由字号驱动，不再用 `Text` 的盒子高度**。
    //
    // 用户 2026-09-27："月视图下圆里日号和农历似乎分的太开了，保留有日记标记的余量即可"。
    // 根因：`Text` 的盒子高 = 字号 × 主题行高（日号 27dp / 农历 24dp），而字形本身只有
    // 字号那么高（20 / 11）—— 两个盒子一叠，中间凭空多出十几 dp 的"行距"。
    // 现在整组高度 = `日号字号 + 农历字号 + 标记余量`，两个字形各自居中在自己的那份里。
    val dayGlyph = metrics.dayFont
    val lunarGlyph = if (hasLunar && metrics.lunarAlpha > 0.01f) metrics.lunarFont else 0f
    // 两行之间留出 `GROUP_GAP`：那一小段就是"有日记"短横线的位置
    //（不给它留位，短横线会压在农历上；用户 2026-09-27："没有保留有日记的标记的位置"）。
    // ⚠️ 顺序是「日号 → 有日记标记 → 农历」（用户 2026-09-27 实测指出：
    // "有日记标记是在农历下面。但是，应该是在日号下面，农历上面"）。
    val gap = CalendarLayout.GROUP_GAP
    val groupH = dayGlyph + gap + MARK_H + gap + lunarGlyph
    // 整组在行内**严格居中**（不加 `LUNAR_TIGHTEN/2` 那类整体偏移 —— 用户实测指出
    // 它会让"选中圆不在分割线正中"）。
    val groupTopDp = rowY / density + (metrics.cellH - groupH) / 2f
    val dayCenterDp = groupTopDp + dayGlyph / 2f
    val markTopDp = groupTopDp + dayGlyph + gap
    val lunarCenterDp = groupTopDp + dayGlyph + gap + MARK_H + gap + lunarGlyph / 2f
    // 月侧：圆心 = 整组中心；圆直径（[CalendarLayout.monthPaneMetrics] 那边）也按
    // "整组高 + 14" 撑开，于是**月视图与周条的圆同尺寸、同排布**。
    // 年侧：圆心 = 格心（迷你月里日号就是全部内容）。
    val monthCircleCy = groupTopDp + groupH / 2f
    val miniCircleCy = rowYDp + metrics.cellH / 2f
    val miniBlend = CalendarLayout.clamp01(1f - t)
    val cx = originX + CalendarLayout.cellCenterX(col, metrics.cellW) * density
    val circleCy = CalendarLayout.lerp(monthCircleCy * density, miniCircleCy * density, miniBlend)
    // 日号跟着**同一族**的排版走（月侧 = 组心、年侧 = 格心），morph 时与圆同步
    val dayCy = CalendarLayout.lerp(dayCenterDp * density, miniCircleCy * density, miniBlend)
    val miniRadius = CalendarLayout.yearDayCircleRadius(metrics.cellW, metrics.cellH) * density
    val radius = CalendarLayout.lerp(metrics.circleDiameter / 2f * density, miniRadius, miniBlend)

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
    // 日号先量出来：年历标记的 y 要挂在**日号行盒底**，而不是圆半径上（P1-2）。
    // ⚠️ 字号必须量化再测量（见下面的说明）。
    val num = textLayouts.layout(
        measurer = measurer,
        text = day.dayOfMonth.toString(),
        fontSize = DynamicTypeMetrics.calendarSp(quantizeFont(metrics.dayFont).dp, fontScale),
        fontWeight = weight,
        color = textColor,
    )

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

    // ⚠️ 字号**必须量化**再测量：`TextMeasurer` 的缓存键里含 `TextStyle`，而 morph 期间
    // `metrics.dayFont` 是逐帧连续插值的 —— 不量化就每帧全 miss，每格每帧都要重新整形
    //（31 格 × 2 次 ≈ 60 次/帧，实测占 2~5ms）。量化到 0.5dp 后同一档字号复用同一条缓存，
    // 视觉上最多差 0.5dp（iOS 自己也是按 0.5pt 量化的）。
    // 日号的横向中心：默认格心；"农历靠右"模式下挪到偏左（0.30 格宽）
    // 左边给日号：`0.24 格宽` —— 与右边农历之间留出约 4dp 的缝（56dp 格宽下实测刚好不撞）
    val dayX = if (lunarRightAligned) originX + metrics.cellW * 0.24f * density else cx
    drawText(
        textLayoutResult = num,
        topLeft = Offset(
            x = dayX - num.size.width / 2f,
            y = dayCy - num.size.height / 2f,
        ),
        alpha = alpha,
    )
    if (metrics.lunarAlpha > 0.01f) {
        // ⚠️ 颜色里**不要**带 `metrics.lunarAlpha`：它是逐帧连续值，带进去等于每帧都是新键、
        // 缓存完全失效（这正是原来每帧 12ms 测量的根因）。alpha 交给 `drawText` 的参数。
        val lunar = textLayouts.layout(
            measurer = measurer,
            text = Lunar.dayLabelCached(day),
            fontSize = DynamicTypeMetrics.calendarSp(quantizeFont(metrics.lunarFont).dp, fontScale),
            color = textColor,
        )
        // "农历靠右"模式：贴右内边距右对齐，且与日号同一水平线（都在组心上）
        val lunarX = if (lunarRightAligned) {
            originX + (metrics.cellW - CELL_SIDE_PAD) * density - lunar.size.width
        } else {
            cx - lunar.size.width / 2f
        }
        val lunarY = if (lunarRightAligned) {
            dayCy - lunar.size.height / 2f
        } else {
            lunarCenterDp * density - lunar.size.height / 2f
        }
        drawText(
            textLayoutResult = lunar,
            topLeft = Offset(x = lunarX, y = lunarY),
            alpha = alpha * metrics.lunarAlpha,
        )
    }
    if (flags.contains(key)) {
        val markW = minOf(20f, metrics.cellW * 0.5f)
        val miniMarkTop = CalendarLayout.yearDiaryMarkTop(dayCy, num.size.height.toFloat())
        val markTop = CalendarLayout.lerp(markTopDp * density, miniMarkTop, miniBlend)
        drawRoundRect(
            color = (if (isSelected) onPrimary else primary).copy(alpha = alpha),
            topLeft = Offset(dayX - markW / 2f * density, markTop),
            size = Size(markW * density, 2f * density),
            cornerRadius = CornerRadius(1f * density),
        )
    }
}

/** 流里农历字号的参考值（`CalendarLayout.flowMetrics` 的 `lunarFont`，竖屏是 11）。 */
private const val FLOW_LUNAR_FONT_REF = 11f

/**
 * morph 期间的文本测量缓存条数。
 *
 * 字号量化到 0.5dp 之后，键的规模是「日号 31 × 字号档 ~12 × 字重 2 × 颜色 2」≈ 1500，
 * 再加农历与标题 —— 2048 才够一轮完整动画不淘汰。
 */
private const val MORPH_TEXT_CACHE = 2048

/**
 * 把字号量化到 0.5dp。
 *
 * `TextMeasurer` 的缓存键含字号，而 morph 期间字号是逐帧连续插值的 —— 不量化就每帧全 miss。
 */
private fun quantizeFont(size: Float): Float = kotlin.math.round(size * 2f) / 2f

/** 一组内容里给"有日记"短横线留的高度：2dp 线 + 1dp 间隙。 */
private const val MARK_H = 3f

/** "农历靠右"模式里离格子右边线的内边距（dp）。 */
private const val CELL_SIDE_PAD = 4f
