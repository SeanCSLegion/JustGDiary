package com.cov.justdiary.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCalendarNavigationTest {

    @Test
    fun `step month moves one key and clamps at ends`() {
        val start = CalendarGrid.MONTH_KEYS.first()
        assertEquals(CalendarGrid.MONTH_KEYS[1], HomeCalendarNavigation.stepMonthKey(start, 1))
        assertNull(HomeCalendarNavigation.stepMonthKey(start, -1))
        val end = CalendarGrid.MONTH_KEYS.last()
        assertNull(HomeCalendarNavigation.stepMonthKey(end, 1))
    }

    @Test
    fun `step day crosses months`() {
        assertEquals("2026-11-01", HomeCalendarNavigation.stepDayKey("2026-10-31", 1))
        assertEquals("2026-10-31", HomeCalendarNavigation.stepDayKey("2026-11-01", -1))
        assertNull(HomeCalendarNavigation.stepDayKey("bad", 1))
    }

    @Test
    fun `carry selection clamps to target month length`() {
        assertEquals(
            "2026-02-28",
            HomeCalendarNavigation.carrySelectionKey("2026-01-31", 202602),
        )
        assertEquals(
            "2026-12-15",
            HomeCalendarNavigation.carrySelectionKey("2026-11-15", 202612),
        )
    }

    @Test
    fun `page week crosses month and reports target key`() {
        val (key, month) = HomeCalendarNavigation.pageWeek("2026-11-14", 1)!!
        assertEquals("2026-11-21", key)
        assertEquals(202611, month)

        val (nextKey, nextMonth) = HomeCalendarNavigation.pageWeek("2026-11-28", 1)!!
        assertEquals("2026-12-05", nextKey)
        assertEquals(202612, nextMonth)
    }

    @Test
    fun `top bar follows the dominant layer during morph`() {
        assertEquals(
            HomeViewMode.MONTH,
            HomeCalendarNavigation.topBarMode(HomeViewMode.WEEK, true, 0.2f),
        )
        assertEquals(
            HomeViewMode.WEEK,
            HomeCalendarNavigation.topBarMode(HomeViewMode.WEEK, true, 0.6f),
        )
        assertEquals(
            HomeViewMode.MONTH,
            HomeCalendarNavigation.topBarMode(HomeViewMode.MONTH, false, 0f),
        )
    }

    @Test
    fun `layer handoff predicates`() {
        assertTrue(HomeCalendarNavigation.showMonthFlow(HomeViewMode.MONTH, false, 0f))
        assertTrue(!HomeCalendarNavigation.showMonthFlow(HomeViewMode.MONTH, true, 0f))
        assertTrue(!HomeCalendarNavigation.showMonthFlow(HomeViewMode.MONTH, false, 0.1f))
        assertTrue(HomeCalendarNavigation.showWeekLayer(HomeViewMode.WEEK, 1f))
        assertTrue(!HomeCalendarNavigation.showWeekLayer(HomeViewMode.WEEK, 0.9f))
    }
}
