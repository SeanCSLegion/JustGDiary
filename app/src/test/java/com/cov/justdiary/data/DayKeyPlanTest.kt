package com.cov.justdiary.data

import com.cov.justdiary.model.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「新一天的开始时间」改动后的归属日重算计划（设计规范 `05-…` §3.4）。
 *
 * 这组断言守住的是设置页「先算再问再改」的前提：**预览与执行是同一个函数**。
 * 只要计划里 `movedBlocks == 0`，执行就不可能动到任何一条 `day_key`。
 */
class DayKeyPlanTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 把"某天某时刻"变成 `start_time_utc` 毫秒。 */
    private fun utcAt(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun keyOf(date: String, dayStartHour: Int, hour: Int, minute: Int = 0): String =
        DateUtil.dayKeyForUtc(utcAt(date, hour, minute), dayStartHour)

    @Test
    fun `no entry moves when the boundary does not cross any start time`() {
        // 4:00 与 3:00 之间没有任何片段的开始时间 → 0 篇会移动
        val rows = listOf(
            DayKeyRow(1, keyOf("2026-09-26", 4, 21, 40), utcAt("2026-09-26", 21, 40)),
            DayKeyRow(2, keyOf("2026-09-25", 4, 9, 15), utcAt("2026-09-25", 9, 15)),
        )
        val plan = planDayKeys(rows, 3)
        assertEquals(0, plan.movedBlocks)
        assertEquals(0, plan.conflicts)
    }

    @Test
    fun `entries between the two boundaries move to the previous day`() {
        // 21:40 在 start=4 时属于 9/26；把边界推到 22:00 之后属于 9/25
        val utc = utcAt("2026-09-26", 21, 40)
        val rows = listOf(DayKeyRow(1, keyOf("2026-09-26", 4, 21, 40), utc))
        val plan = planDayKeys(rows, 22)
        assertEquals(1, plan.movedBlocks)
        assertEquals("2026-09-25", plan.finalKeys[1])
    }

    @Test
    fun `already correct keys stay untouched`() {
        val utc = utcAt("2026-09-26", 10, 0)
        val rows = listOf(DayKeyRow(7, DateUtil.dayKeyForUtc(utc, 4), utc))
        val plan = planDayKeys(rows, 4)
        assertTrue(plan.movers.isEmpty())
        assertEquals(0, plan.conflicts)
    }

    @Test
    fun `rows without a start time keep their current key`() {
        // start_time_utc = 0（历史数据）时不参与移动，也不报冲突
        val rows = listOf(DayKeyRow(3, "2026-09-20", 0L))
        val plan = planDayKeys(rows, 22)
        assertEquals(0, plan.movedBlocks)
        assertEquals(0, plan.conflicts)
    }

    @Test
    fun `a key that is still occupied counts as a conflict and is skipped`() {
        // 两条记录互换归属日：1 要搬到 9/25（2 现在占着），而 2 并不打算离开 9/25 → 1 放弃
        val utcA = utcAt("2026-09-26", 21, 40) // start=4 → 9/26；start=22 → 9/25
        val utcB = utcAt("2026-09-25", 23, 30) // start=4 → 9/25；start=22 → 9/25（不动）
        val rows = listOf(
            DayKeyRow(1, DateUtil.dayKeyForUtc(utcA, 4), utcA),
            DayKeyRow(2, DateUtil.dayKeyForUtc(utcB, 4), utcB),
        )
        val plan = planDayKeys(rows, 22)
        assertEquals(1, plan.conflicts)
        assertEquals(0, plan.movedBlocks)
    }

    @Test
    fun `swapping neighbours can both move when each one vacates`() {
        // 1 从 9/26 搬到 9/25 的同一天，2 从 9/25 搬到 9/24 —— 互不挡路，两条都移动
        val utcA = utcAt("2026-09-26", 21, 40) // 4 → 9/26；22 → 9/25
        val utcB = utcAt("2026-09-25", 21, 40) // 4 → 9/25；22 → 9/24
        val rows = listOf(
            DayKeyRow(1, DateUtil.dayKeyForUtc(utcA, 4), utcA),
            DayKeyRow(2, DateUtil.dayKeyForUtc(utcB, 4), utcB),
        )
        val plan = planDayKeys(rows, 22)
        assertEquals(0, plan.conflicts)
        assertEquals(2, plan.movedBlocks)
        assertEquals("2026-09-25", plan.finalKeys[1])
        assertEquals("2026-09-24", plan.finalKeys[2])
    }
}
