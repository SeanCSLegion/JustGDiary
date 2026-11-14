package com.cov.justdiary.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cov.justdiary.service.DateFormats
import java.time.LocalDate

/**
 * 首页的 morph 覆盖层：年↔月、月↔周。
 *
 * 从 [HomeScreen] 拆出：这里只负责“按进度画哪一层”，状态机、冻结源与交班判据仍在
 * HomeScreen。两个层的参数都从调用方传入，避免在层内部再读组合状态。
 */
@Composable
internal fun HomeMorphLayers(
    yearMorphing: Boolean,
    weekMorphing: Boolean,
    weekSource: MonthFlowMorphSource?,
    yearProgress: () -> Float,
    weekProgress: () -> Float,
    yearZoomingIn: Boolean,
    layout: MonthFlowLayout,
    offsetDp: () -> Float,
    monthKey: Int,
    widthDp: Float,
    heightDp: Float,
    titleHeight: Float,
    weekdayHeight: Float,
    targetGrid: CalendarLayout.Rect,
    weekStart: String,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    titleText: (LocalDate) -> String,
    labelText: (LocalDate) -> String,
    year: Int,
    selectedMonth: Int,
    dateFormats: DateFormats?,
    onMonthClick: (Int) -> Unit,
    dayContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ④ 年 ↔ 月 morph 层：年历（自己一份）+ 本月网格重排进卡片。
    if (yearMorphing) {
        YearMonthMorph(
            progress = yearProgress,
            zoomingIn = yearZoomingIn,
            layout = layout,
            offsetDp = offsetDp,
            monthKey = monthKey,
            widthDp = widthDp,
            heightDp = heightDp,
            titleHeight = titleHeight,
            weekdayHeight = weekdayHeight,
            targetGrid = targetGrid,
            weekStart = weekStart,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            showLunar = showLunar,
            titleText = titleText,
            labelText = labelText,
            yearLayer = {
                YearLayer(
                    year = year,
                    selectedMonth = selectedMonth,
                    selectedKey = selectedKey,
                    todayKey = todayKey,
                    flags = flags,
                    weekStart = weekStart,
                    showLunar = showLunar,
                    yearHeight = heightDp,
                    yearWidth = widthDp,
                    yearProgress = yearProgress,
                    dateFormats = dateFormats,
                    onMonthClick = {},
                    // morph 层自己在同一个矩形里画这块网格 → 卡片不再画一遍
                    hiddenMonth = selectedMonth,
                )
            },
            modifier = modifier,
        )
    }

    // ⑤ 月↔周 morph 层：用冻结的源几何重画，两个方向共用一个源。
    // 撤下的条件是协程收尾（`weekMorphing` 复位），不是进度到端点；这样真实图层
    // 接手的第一帧被多盖几帧，避免“日期区整片空白一帧”。
    val source = weekSource
    if (weekMorphing && source != null) {
        MonthWeekMorph(
            progress = weekProgress,
            source = source,
            widthDp = widthDp,
            heightDp = heightDp,
            weekStart = weekStart,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            showLunar = showLunar,
            titleText = titleText,
            labelText = labelText,
            content = dayContent,
            modifier = modifier,
        )
    }
}
