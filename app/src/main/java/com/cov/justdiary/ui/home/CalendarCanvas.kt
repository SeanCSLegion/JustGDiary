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
    monthLabel: (LocalDate) -> String,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)

    // Canvas 的绘制 lambda 不是 @Composable 上下文，主题色必须在这里先取出
    val primary = MaterialTheme.colorScheme.primary
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

            val titleStyle = TextStyle(
                fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.rowTitle, density.fontScale),
                fontWeight = FontWeight.SemiBold,
            )
            val dayStyle = TextStyle(
                fontSize = DynamicTypeMetrics.calendarSp(TypeSize.caption, density.fontScale),
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
                    titleStyle.copy(color = titleColor),
                )
                drawText(
                    textLayoutResult = titleText,
                    topLeft = Offset(
                        title.x.dp.toPx(),
                        title.y.dp.toPx() + (title.h.dp.toPx() - titleText.size.height) / 2f,
                    ),
                )

                // 迷你月用「圆点」而不是日号：年历卡片只有 ~50dp 高，6 行日号在这个尺寸下
                // 必然互相叠住（首轮上机实测）。圆点在同尺寸下清晰可读，而且年历真正要回答的
                // 问题只有两个 —— 「这个月有记录吗」「今天是哪一格」。
                val cellW = grid.w.dp.toPx() / 7f
                val cellH = grid.h.dp.toPx() / CalendarLayout.MONTH_ROWS
                val radius = (minOf(cellW, cellH) * 0.26f).coerceAtLeast(1.2f)
                val weeks = CalendarGrid.weeks(monthFirst, weekStart)

                weeks.forEachIndexed { row, week ->
                    week.forEachIndexed { col, date ->
                        val inMonth = date.year == year && date.monthValue == month
                        if (!inMonth) return@forEachIndexed
                        val key = DateUtil.dayKeyOf(date)
                        val isToday = key == todayKey
                        val hasDiary = flags.contains(key)
                        if (!isToday && !hasDiary) return@forEachIndexed

                        val cx = grid.x.dp.toPx() + (col + 0.5f) * cellW
                        val cy = grid.y.dp.toPx() + (row + 0.5f) * cellH
                        if (isToday) {
                            // 今天：实心圆 + 一圈留白，保证在任何底色上都看得见
                            drawCircle(
                                color = primary,
                                radius = radius * 1.7f,
                                center = Offset(cx, cy),
                            )
                        } else {
                            drawCircle(
                                color = primary.copy(alpha = 0.55f),
                                radius = radius,
                                center = Offset(cx, cy),
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
