package com.cov.justdiary.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2：年历的两个小几何（iOS `64cbcc9` + `87e06f5`）。
 *
 * 1. 迷你月的选中 / 今天圆从 `0.42` 提到 `0.46`，直径至少占格宽 90%，相邻圆仍有缝；
 * 2. "有日记"标记挂在**日号行盒底**，不再挂在圆半径上 —— 改圆半径不会把标记带远。
 *
 * 纯数学层能钉住的部分都在这里；视觉收尾（年↔月 morph 逐帧）留给真机录屏。
 */
class CalendarYearGeometryTest {

    private val grid = CalendarLayout.miniGridRect(
        CalendarLayout.yearCardRect(1, 402f, 744f),
    )
    private val metrics = CalendarLayout.miniMetrics(grid)

    @Test
    fun `the mini month circle is at least ninety percent of the cell width`() {
        val radius = CalendarLayout.yearDayCircleRadius(metrics.cellW, metrics.cellH)
        val diameter = radius * 2f

        assertEquals(CalendarLayout.YEAR_DAY_CIRCLE_RADIUS_RATIO, 0.46f, 1e-6f)
        assertTrue(
            "直径应至少占格宽 90%（实际 ${diameter / metrics.cellW}）",
            diameter / metrics.cellW >= 0.90f,
        )
        assertTrue(
            "直径不能顶满格宽，相邻圆之间要留缝（实际 ${diameter / metrics.cellW}）",
            diameter / metrics.cellW <= 0.94f,
        )
        assertTrue(
            "相邻两圆之间应有正的缝",
            metrics.cellW - diameter > 0f,
        )
    }

    @Test
    fun `the diary mark hangs from the day number box bottom not the circle radius`() {
        val dayCenterY = 100f
        val textHeight = 28f
        val expected = dayCenterY + textHeight / 2f

        assertEquals(
            "标记顶端 = 日号盒底 + 0dp",
            expected,
            CalendarLayout.yearDiaryMarkTop(dayCenterY, textHeight),
            1e-6f,
        )

        // 圆半径怎么变，标记都不动（这正是从 `cy + 0.86r` 换过来的原因）
        val oldSmall = dayCenterY + CalendarLayout.yearDayCircleRadius(5f, 5f) * 0.86f
        val oldLarge = dayCenterY + CalendarLayout.yearDayCircleRadius(10f, 10f) * 0.86f
        assertTrue("旧写法会随半径移动：$oldSmall / $oldLarge", oldSmall != oldLarge)

        val newMark = CalendarLayout.yearDiaryMarkTop(dayCenterY, textHeight)
        assertEquals(
            "新写法与半径无关",
            newMark,
            CalendarLayout.yearDiaryMarkTop(dayCenterY, textHeight),
            1e-6f,
        )
    }

    @Test
    fun `the mark clears the glyph box and still stays inside the cell`() {
        val dayCenterY = metrics.cellH / 2f
        val textHeight = metrics.dayFont
        val markTop = CalendarLayout.yearDiaryMarkTop(dayCenterY, textHeight)

        assertTrue("标记在日号下方", markTop >= dayCenterY + textHeight / 2f - 1e-3f)
        assertTrue("标记不越出格底", markTop + 2f <= metrics.cellH + 1e-3f)
    }
}
