package com.cov.justdiary.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.TypeSize
import java.time.LocalDate

/**
 * 年历页：3 列 × 4 行的迷你月，**每张卡片是一个独立的可点/可聚焦区域**。
 *
 * ## 为什么年历仍然用 Canvas 画，月格却改成了真实组件
 *
 * 见 `CalendarDayGrid.kt` 的类注释。一句话：月格的**每一天**都是用户要点的目标，
 * 必须逐格暴露给 TalkBack；年历的 12 张卡片各自是**一个整体点击目标**，迷你月里的
 * 日号（9–14sp）属于装饰性内容，逐个朗读只会让导航变得冗长。
 *
 * 所以这里的做法是：Canvas 负责画，位置由 [CalendarLayout.yearCardRect] 的**比例**决定；
 * 在其上按同一组比例叠 12 个透明但可聚焦的卡片层 —— 画在哪、点在哪、读在哪三者同源。
 */
@Composable
fun YearCanvas(
    year: Int,
    selectedMonth: Int,
    todayKey: String,
    flags: Set<String>,
    modifier: Modifier = Modifier,
    weekStart: String = DateUtil.WEEK_START_MONDAY,
    showLunar: Boolean = false,
    onMonthClick: ((Int) -> Unit)? = null,
    /**
     * 迷你月的标题文案（**必传**）。
     *
     * Canvas 的绘制 lambda 不是 `@Composable` 上下文，所以文案必须由调用方在组合期准备好。
     * 原先这里写死 `monthValue + "月"`，切到英文后年历仍显示中文 —— 现在没有默认值，
     * 调用方必须给出跟随语言的文案（见 `HomeScreen` 的 `DateFormats.monthName`）。
     */
    /** 页内大标题（`2026年`，32sp bold primary）。 */
    yearTitle: String = "",
    /** 大标题右侧的干支生肖（`丙午马年`；非中文时为空字符串）。 */
    zodiacLabel: String = "",
    monthLabel: (LocalDate) -> String,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)

    // Canvas 的绘制 lambda 不是 @Composable 上下文，主题色必须在这里先取出
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val hasDiaryLabel = stringResource(R.string.index_has_diary)
    val noDiaryLabel = stringResource(R.string.index_no_diary)
    // 哪几个月有日记：只算一次（12 张卡片共用一个集合）
    val monthsWithDiary = remember(flags, year) {
        flags.mapNotNull { DateUtil.parseDayKey(it) }
            .filter { it.year == year }
            .map { it.monthValue }
            .toSet()
    }

    BoxWithConstraints(modifier = modifier) {
        val canvasW = maxWidth.value
        val canvasH = maxHeight.value

        Canvas(modifier = Modifier.fillMaxSize()) {
            val widthDp = size.width / density.density
            val heightDp = size.height / density.density

            // ---- 页内大标题（规范 §5.4）：32sp bold primary + 右侧干支生肖 ----
            // 只在中文下画生肖；英文用户看干支没有意义（与 iOS 一致）。
            if (yearTitle.isNotEmpty()) {
                val titleStyle = TextStyle(
                    fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.display, density.fontScale),
                    fontWeight = FontWeight.Bold,
                    color = primary,
                )
                val titleText = measurer.measure(yearTitle, titleStyle)
                drawText(
                    textLayoutResult = titleText,
                    topLeft = Offset(
                        CalendarLayout.YEAR_PAD.dp.toPx(),
                        (CalendarLayout.YEAR_TITLE_H.dp.toPx() - titleText.size.height) / 2f,
                    ),
                )
                if (zodiacLabel.isNotEmpty()) {
                    val zodiacStyle = TextStyle(
                        fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.caption, density.fontScale),
                        color = onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    val zodiacText = measurer.measure(zodiacLabel, zodiacStyle)
                    drawText(
                        textLayoutResult = zodiacText,
                        topLeft = Offset(
                            size.width - CalendarLayout.YEAR_PAD.dp.toPx() - zodiacText.size.width,
                            (CalendarLayout.YEAR_TITLE_H.dp.toPx() - zodiacText.size.height) / 2f,
                        ),
                    )
                }
            }

            val monthTitleStyle = TextStyle(
                fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.rowTitle, density.fontScale),
                fontWeight = FontWeight.SemiBold,
            )

            for (month in 1..12) {
                val card = CalendarLayout.yearCardRect(month, widthDp, heightDp)
                val title = CalendarLayout.miniTitleRect(card)
                val grid = CalendarLayout.miniGridRect(card)

                val monthFirst = LocalDate.of(year, month, 1)
                val isCurrentMonth = month == selectedMonth
                val titleColor = if (isCurrentMonth) primary else onSurfaceVariant

                val titleText = measurer.measure(
                    monthLabel(monthFirst),
                    monthTitleStyle.copy(color = titleColor),
                )
                drawText(
                    textLayoutResult = titleText,
                    topLeft = Offset(
                        title.x.dp.toPx(),
                        title.y.dp.toPx() + (title.h.dp.toPx() - titleText.size.height) / 2f,
                    ),
                )

                // ---- 迷你月：6 行 × 7 列的**日号**（规范 §5.4）----
                //
                // 首轮这里画的是"有记录的日期一个圆点"，理由是"卡片只有 ~50dp 高"。
                // 实测卡片是 128 × 168dp（383 × 503px），这个前提早就不成立 ——
                // 没有日号，迷你月读不出"这个月是几号星期几"，而且年↔月 morph
                // 也就没有落点（只能退化成空白缩放，这正是"年历动画不对"的根因）。
                val cellW = grid.w.dp.toPx() / 7f
                val cellH = grid.h.dp.toPx() / CalendarLayout.MONTH_ROWS
                val dayFont = CalendarLayout.miniDayFont(grid.w / 7f, grid.h / CalendarLayout.MONTH_ROWS)
                val dayStyle = TextStyle(
                    fontSize = DynamicTypeMetrics.calendarSp(dayFont.dp, density.fontScale),
                )
                val rows = CalendarGrid.displayedWeeks(monthFirst, weekStart)

                rows.forEachIndexed { row, week ->
                    week.forEachIndexed { col, date ->
                        if (date.year != year || date.monthValue != month) return@forEachIndexed
                        val key = DateUtil.dayKeyOf(date)
                        val isToday = key == todayKey
                        val hasDiary = flags.contains(key)

                        val style = dayStyle.copy(
                            color = if (isToday) primary else onSurface,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        )
                        val layout = measurer.measure(date.dayOfMonth.toString(), style)
                        val cx = grid.x.dp.toPx() + (col + 0.5f) * cellW
                        val cy = grid.y.dp.toPx() + (row + 0.5f) * cellH
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                cx - layout.size.width / 2f,
                                cy - layout.size.height / 2f,
                            ),
                        )
                        // 有日记：日号下方 2dp 短横线；今天：同位置的实心小圆点
                        val markW = minOf(9f, cellW * 0.5f).dp.toPx()
                        val markY = cy + layout.size.height / 2f + 1.dp.toPx()
                        when {
                            hasDiary -> drawRoundRect(
                                color = primary,
                                topLeft = Offset(cx - markW / 2f, markY),
                                size = androidx.compose.ui.geometry.Size(markW, 1.4f.dp.toPx()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.7f.dp.toPx()),
                            )
                            isToday -> drawCircle(
                                color = primary,
                                radius = 1.6f.dp.toPx(),
                                center = Offset(cx, markY + 0.7f.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }

        if (onMonthClick != null && canvasW > 0f && canvasH > 0f) {
            for (month in 1..12) {
                val card = CalendarLayout.yearCardRect(month, canvasW, canvasH)
                val monthName = monthLabel(LocalDate.of(year, month, 1))
                val interaction = remember(month) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .offset(x = card.x.dp, y = card.y.dp)
                        .fillMaxWidth(card.w / canvasW)
                        .fillMaxHeight(card.h / canvasH)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClick = { onMonthClick(month) },
                        )
                        .background(Color.Transparent)
                        .semantics(mergeDescendants = true) {
                            contentDescription = monthName
                            stateDescription =
                                if (month in monthsWithDiary) hasDiaryLabel else noDiaryLabel
                        },
                )
            }
        }
    }
}

/** 文本测量缓存的容量（对应 iOS 的 24000 条 + 0.5pt 量化）。 */
private const val TEXT_CACHE_SIZE = 512
