package com.cov.justdiary.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.cov.justdiary.model.DateUtil
import java.time.LocalDate
import kotlin.math.max

/**
 * 年历 → 月流的 morph 层：把当前可见行冻结成源，再逐行交班。
 *
 * 从 [MonthFlow] 拆出；几何来源见 [MonthFlowGeometry]、画布见 [MonthFlowCanvas]。
 */

// --------------------------------------------------------------------------- morph 源构造

/** 把当前可见的行 / 小标题冻结成 morph 源（对应 iOS 的 `morphSource`）。 */
internal fun morphSource(
    layout: MonthFlowLayout,
    offsetDp: Float,
    tappedBlockIndex: Int,
    tappedRow: Int,
    titleHeight: Float,
    weekdayHeight: Float,
    viewportH: Float,
    heightDp: Float,
    labelBandH: Float,
    weekStart: String,
    density: Float,
    /**
     * 各块 / 各小标题的**实测**位置。
     *
     * ⚠️ 冻结源里的 y **必须**优先取它。按公式反算在 Compose 上会差（父层
     * `Modifier.offset { }` 的整像素取整、行高的 0.5dp 量化、启动期换过一次布局），
     * 实测能差到 ~200dp —— 用户报的"点下去先跳一下"（"跳上去"）就是它。
     * 实测值缺失（首帧还没放置完）时才退回公式。
     */
    geometry: FlowGeometryProbe? = null,
): MonthFlowMorphSource {
    val visible = layout.visibleBlocks(offsetDp, viewportH, margin = 0f)
    val topIndex = layout.dominantBlockIndex(offsetDp, viewportH)
    val topKey = layout.blocks.getOrNull(topIndex)?.key ?: 0
    val rows = ArrayList<MonthFlowMorphSource.Row>()
    val labels = ArrayList<MonthFlowMorphSource.Label>()
    var selected = 0
    visible.forEach { block ->
        val blockTopDp = geometry?.blockTopDp(block.index, density)
            ?: (block.top - offsetDp + titleHeight + weekdayHeight)
        labels.add(
            MonthFlowMorphSource.Label(
                monthFirst = block.monthFirst,
                key = block.key,
                // 实测的**顶边**；退回公式时也用"顶边"那个式子：
                //     `.offset(y = -(labelBandH + LABEL_TIGHT_GAP))`（见 `MonthFlowBlock`）
                top = geometry?.labelTopDp(block.key, density)
                    ?: (blockTopDp - (labelBandH + CalendarLayout.LABEL_TIGHT_GAP)),
            ),
        )
        CalendarGrid.displayedWeeks(block.monthFirst, weekStart).forEachIndexed { i, week ->
            if (block.index == tappedBlockIndex && i == tappedRow) selected = rows.size
            rows.add(
                MonthFlowMorphSource.Row(
                    week = week,
                    // 实测块顶 + 行号 × 行高：块内每一行是同一张 Canvas 按 `i × rowH` 画的，
                    // 所以块顶量准了，行位置就是准的。
                    top = blockTopDp + i * layout.rowH,
                    anchorMonth = block.monthFirst,
                    showDivider = !(block.index == 0 && i == 0),
                ),
            )
        }
    }
    return MonthFlowMorphSource(
        rows = rows,
        labels = labels,
        selectedIndex = selected.coerceIn(0, max(0, rows.size - 1)),
        topMonthKey = topKey,
        rowH = layout.rowH,
        viewportTop = titleHeight + weekdayHeight,
        height = heightDp,
    )
}

// --------------------------------------------------------------------------- 月 ↔ 周 morph

/**
 * 月↔周 morph：把**冻结下来的连续月历流**里选中那一行抬到星期栏下方、其余行移出屏幕，
 * 同时当日内容从下面顶上来。
 *
 * 行的位置不再由「第几行 × 行高」推出来，而是直接用冻结时的绝对 y —— 月视图改成连续
 * 滚动之后，按下那一刻屏幕上的行可能停在任意位置（半行、跨月），只有照抄这些数字，
 * 切换那一帧才不会跳。反方向（周→月）用同一份源、同一条进度曲线倒放，
 * 于是那一行会**回到它原来的位置**（用户：「从哪来到哪去」）。
 *
 * ⚠️ 这里**不在组合期读进度**：行的位置 / 透明度全在 `graphicsLayer` 的延迟 lambda 里
 * 算，所以 600ms 的 morph 期间不重组任何日期格（只让 layer 失效）。
 */
@Composable
fun MonthWeekMorph(
    progress: () -> Float,
    source: MonthFlowMorphSource,
    widthDp: Float,
    heightDp: Float,
    weekStart: String,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    titleText: (LocalDate) -> String,
    labelText: (LocalDate) -> String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    // ============================================================================
    // **分层结构与 iOS `MonthWeekMorphView` 逐条对应**（2026-09-27 第二次重写）
    //
    //   ZStack:                                   （→ Compose 里的 Box 叠层）
    //     ① 大标题        .offset(y: −p × titleH).opacity(1 − 2p)   ← 真实 composable
    //     ② 星期栏        .offset(y: lerp(titleH, 0, p))            ← 真实 composable
    //     ③ 行 + 小标题    裁在 clipTop（= 星期栏底边）以下
    //     ④ 当日内容      挂在选中行下面，淡入
    //
    // ⚠️ 上一版把 ①② 也画进 ③ 那张 Canvas、而且跟着一起裁在 `clipTop` 以下 —— 而
    // `clipTop` 恰好等于星期栏的**下沿**，于是大标题与星期栏在整段动画里**一次都没画
    // 出来**，收尾换回真图层时才一起冒出来。用户的原话："顶栏的绘制有问题，导致最后会
    // 突然闪现出来"、"让月视图下的星期栏也一并向上，移动到周视图的星期栏位置，正好是
    // 重合的"。iOS 里 ①② 本来就在裁剪容器**之外**，这一版照着改回来。
    //
    // 现在 ①② 用**与月视图钉住层同一个 composable**（`MonthPinnedHeader`），整条按
    // `p × titleH` 上移：`p = 0` 与月视图逐像素一致，`p = 1` 星期栏正好落在周视图的
    // 位置上（y = 0），两个方向都不跳。
    // ============================================================================
    val density = LocalDensity.current
    val densityF = density.density
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer(cacheSize = 2048)
    val textLayouts = MorphTextLayouts.shared

    val rowH = source.rowH
    val headH = CalendarLayout.WEEKDAY_HEADER_H
    val titleH = source.viewportTop - headH
    val mMetrics = remember(widthDp, rowH, showLunar) {
        CalendarLayout.flowMetrics(widthDp, rowH, showLunar)
    }
    val wMetrics = remember(widthDp, showLunar) {
        CalendarLayout.weekMetrics(widthDp, showLunar)
    }
    val cellW = CalendarLayout.monthCellW(widthDp)

    val scheme = MaterialTheme.colorScheme
    val todayMonthKey = remember(todayKey) {
        DateUtil.parseDayKey(todayKey)?.let { CalendarGrid.monthKeyOf(it) } ?: 0
    }
    val title = remember(source.topMonthKey, titleText) {
        titleText(CalendarGrid.monthFirstOfMonthKey(source.topMonthKey))
    }
    // 选中行在冻结坐标里的顶：其余行的"让位"、小标题的位移、以及**当日内容挂在哪**
    // 都以它为界（对应 iOS 的 `selectedTop` / `selectedY`）。
    val selectedTop = source.selectedRow?.top ?: source.viewportTop

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            // 与 iOS 那个 `.clipped()` 同：钉住头上移时不许画到顶栏（胶囊那一行）上去。
            // 它同时兜住了"往上滑出的小标题"：越过日历区上沿就往回裁掉。
            .clipToBounds()
            .clearAndSetSemantics { },
    ) {
        // ---- ③ 各行让位（裁在星期栏底边以下） ----
        //
        // P2-3：**非选中行不再每帧重录 Canvas**，而是各自挂一个 `graphicsLayer`：
        // 行的内容（日期格 / 农历 / 分割线）是冻结的，只在首帧录一次；600ms 里变化的
        // 只有 translationY 与 alpha，交给 RenderNode 合成。选中行要按进度插值字号/行高，
        // 必须每帧重画，单独留在下面那张 Canvas 里。
        val selectedIndex = source.selectedIndex
        val selectedWeekFirst = source.selectedRow?.week?.first()
        val selectedAnchor = source.selectedRow?.anchorMonth
        val adjacentIndex = source.rows.indexOfFirst {
            it.anchorMonth != selectedAnchor && it.week.first() == selectedWeekFirst
        }
        val adjacentRow = source.rows.getOrNull(adjacentIndex)

        fun rowYAt(p: Float, index: Int): Float {
            val row = source.rows[index]
            return if (index == selectedIndex) {
                CalendarLayout.lerp(row.top, headH, p)
            } else if (row.top < selectedTop) {
                row.top - p * (row.top + rowH)
            } else {
                row.top + p * (heightDp - row.top)
            }
        }

        fun rowAlphaAt(p: Float, index: Int): Float =
            if (index == selectedIndex) 1f else 1f - CalendarLayout.clamp01(p * 1.4f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 裁切窗顶边随进度上移；只在绘制阶段读进度，不触发重组。
                .drawWithContent {
                    val clipTop = CalendarLayout.lerp(source.viewportTop, headH, progress()) * densityF
                    clipRect(top = clipTop, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            source.rows.forEachIndexed { i, row ->
                if (i == selectedIndex) {
                    // 选中行：字号 / 行高 / 相邻月位置都跟着进度变，保留每帧重画。
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val p = progress()
                        val rowY = rowYAt(p, i)
                        val rowMetrics = mMetrics.lerp(wMetrics, p)
                        var adjacentY: Float? = null
                        if (adjacentRow != null) {
                            val away = CalendarLayout.smoothstep(1f - p)
                            adjacentY = rowY + (adjacentRow.top - selectedTop) * away
                        }
                        drawWeekRow(
                            week = row.week,
                            anchorMonth = row.anchorMonth,
                            rowY = rowY * densityF,
                            metrics = rowMetrics,
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            alpha = 1f,
                            showDivider = row.showDivider,
                            measurer = measurer,
                            textLayouts = textLayouts,
                            fontScale = fontScale,
                            primary = scheme.primary,
                            onPrimary = scheme.onPrimary,
                            onSurface = scheme.onSurface,
                            onSurfaceVariant = scheme.onSurfaceVariant,
                            dividerColor = scheme.outlineVariant,
                            hasLunar = showLunar,
                            t = 1f,
                            adjacentRowY = adjacentY?.let { it * densityF },
                            // 选中周"另一半"那一行只画分割线、不画日期（见原注释）。
                            drawCells = i != adjacentIndex,
                        )
                    }
                } else {
                    // 静态行：内容只录一次，位置 / 透明度走 layer。
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowH.dp)
                            .graphicsLayer {
                                val p = progress()
                                translationY = rowYAt(p, i) * densityF
                                alpha = rowAlphaAt(p, i)
                            },
                    ) {
                        drawWeekRow(
                            week = row.week,
                            anchorMonth = row.anchorMonth,
                            rowY = 0f,
                            metrics = mMetrics,
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            alpha = 1f,
                            showDivider = row.showDivider,
                            measurer = measurer,
                            textLayouts = textLayouts,
                            fontScale = fontScale,
                            primary = scheme.primary,
                            onPrimary = scheme.onPrimary,
                            onSurface = scheme.onSurface,
                            onSurfaceVariant = scheme.onSurfaceVariant,
                            dividerColor = scheme.outlineVariant,
                            hasLunar = showLunar,
                            t = 1f,
                            // 相邻月那几格只归选中行；其余行与旧实现一致，不画。
                            drawCells = i != adjacentIndex,
                        )
                    }
                }
            }
        }


        // ---- ③b 小标题：**真实 composable**（与月视图 `MonthFlowBlock` 同一份排版，
        //      所以交班那一帧逐像素一致），位置用冻结时的实测值（见 `FlowGeometryProbe`）。
        //
        // 用户 2026-09-27："月到周的时候，小标题不动"。原来它位置钉死、只淡出，
        // 而它上下的日期都在移动 —— 看着就像"少动了一个元素"。
        // 现在按**与日期行同一条规则**位移（它是本月的第一行上方那一块，同属"让位"）：
        //   · 小标题在选中周之上 ⇒ 向上移出；
        //   · 在选中周之下 ⇒ 向下移出。
        // 往上滑出屏幕的那些由钉住层的不透明底色 + 根节点裁切兜住（与月视图同一条边界）。
        //
        // ⚠️ **透明度挂在这一层（尺寸确定的 Box）上，不能挂到 `MonthFlowLabel` 自己身上**：
        // 实测（2026-09-27）外层挂 `alpha < 1` 时那一块会被画成**完全空白**（连纯色背景
        // 都不画），alpha = 1f 才正常；位置（`translationY`）挂在自己身上是好的。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = CalendarLayout.clamp01((1f - progress()) * 2f) },
        ) {
            source.labels.forEach { label ->
                MonthFlowLabel(
                    cellW = cellW,
                    column = DateUtil.weekdayIndex(label.monthFirst, weekStart),
                    text = labelText(label.monthFirst),
                    compact = compact,
                    isCurrent = label.key == todayMonthKey,
                    modifier = Modifier.graphicsLayer {
                        val p = progress()
                        translationY = if (label.top < selectedTop) {
                            label.top - p * (label.top + rowH)
                        } else {
                            label.top + p * (heightDp - label.top)
                        } * densityF
                    },
                )
            }
        }

        // ---- ①② 钉住层：与月视图同一个 composable，整条按 `p × titleH` 上移 ----
        MonthPinnedHeader(
            title = title,
            widthDp = widthDp,
            titleHeight = titleH,
            weekdayHeight = headH,
            weekStart = weekStart,
            titleAlpha = { 1f - CalendarLayout.clamp01(progress() * 2f) },
            modifier = Modifier.graphicsLayer {
                translationY = -progress() * titleH * densityF
            },
        )

        // ---- ④ 当日内容（日期栏 + 日记预览）：真实 composable，**挂在选中行下面**
        //
        // 与 iOS 的 `contentOffset = selectedY + weekStripH` 同一条：
        // 它**不是**按固定曲线滑，而是始终吊在选中行底下 —— 正方向（月→周）"从下面升上来"，
        // 反方向（周→月）"跟着行一起沉下去"。写成固定 y 的代价实测过：周→月时选中行要往下
        // 走 400+dp 回到它那个月，而内容钉在原地不动 —— 日期标题行会被那一行**穿过去**
        //（用户 2026-09-27："日记预览和日期栏也是有动画的，现在缺失了"）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val selectedY = CalendarLayout.lerp(selectedTop, headH, progress())
                    translationY = (selectedY + CalendarLayout.WEEK_STRIP_H) * densityF
                    alpha = CalendarLayout.clamp01((progress() - 0.3f) / 0.5f)
                },
        ) {
            content()
        }
    }
}
