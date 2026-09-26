package com.cov.justdiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自定义时间范围的输入解析。
 *
 * 这一层存在的理由：范围过滤是**字符串比较**，未归一化的输入会静默算出空区间
 * （界面只显示"没有找到匹配的日记"），所以"用户可能怎么写"必须在这里收干净。
 */
class DayKeyInputTest {

    @Test
    fun `already normalized input passes through`() {
        assertEquals("2026-09-01", DayKeyInput.normalize("2026-09-01"))
    }

    @Test
    fun `single digit month and day are padded`() {
        // 这是最容易踩的一个：`2026-9-1` 与 `2026-09-01` 的字典序不同，
        // 后者更大，于是 `day_key in "2026-9-1".."2026-09-01"` 是空区间
        assertEquals("2026-09-01", DayKeyInput.normalize("2026-9-1"))
        assertEquals("2026-01-05", DayKeyInput.normalize("2026-1-5"))
    }

    @Test
    fun `slash dot and compact forms are accepted`() {
        assertEquals("2026-09-01", DayKeyInput.normalize("2026/9/1"))
        assertEquals("2026-09-01", DayKeyInput.normalize("2026.09.01"))
        assertEquals("2026-09-01", DayKeyInput.normalize("20260901"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("2026-09-01", DayKeyInput.normalize("  2026-09-01  "))
    }

    @Test
    fun `dates that do not exist are rejected`() {
        assertNull(DayKeyInput.normalize("2026-02-30"))
        assertNull(DayKeyInput.normalize("2026-13-01"))
        assertNull(DayKeyInput.normalize("2026-00-10"))
        assertNull(DayKeyInput.normalize("2026-04-31"))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(DayKeyInput.normalize(""))
        assertNull(DayKeyInput.normalize("   "))
        assertNull(DayKeyInput.normalize("abc"))
        assertNull(DayKeyInput.normalize("2026-09"))
        assertNull(DayKeyInput.normalize("2026-09-01-01"))
        assertNull(DayKeyInput.normalize("202609"))
        // 年份超出可接受范围（明显是打错了）
        assertNull(DayKeyInput.normalize("1800-01-01"))
        assertNull(DayKeyInput.normalize("9999-01-01"))
    }

    @Test
    fun `a leap day is accepted only in a leap year`() {
        assertEquals("2024-02-29", DayKeyInput.normalize("2024-2-29"))
        assertNull(DayKeyInput.normalize("2026-02-29"))
    }

    @Test
    fun `normalizeRange swaps reversed endpoints instead of failing`() {
        // 用户把先后写反是很自然的手误，不该报错
        assertEquals("2026-09-01" to "2026-10-01", DayKeyInput.normalizeRange("2026-10-01", "2026-09-01"))
        // 同一天也算合法区间
        assertEquals("2026-09-01" to "2026-09-01", DayKeyInput.normalizeRange("2026-9-1", "2026-09-01"))
    }

    @Test
    fun `normalizeRange returns null when either endpoint is invalid`() {
        assertNull(DayKeyInput.normalizeRange("2026-09-01", "abc"))
        assertNull(DayKeyInput.normalizeRange("2026-02-30", "2026-09-01"))
    }

    /**
     * 归一化之后，字符串比较才等价于时间比较 —— 这是这一层要保证的核心性质。
     */
    @Test
    fun `normalized keys compare in chronological order`() {
        val raw = listOf("2026-9-1", "2026-10-01", "2026-09-30", "2026-1-1")
        val normalized = raw.map { DayKeyInput.normalize(it)!! }
        assertEquals(listOf("2026-09-01", "2026-10-01", "2026-09-30", "2026-01-01"), normalized)
        assertEquals(normalized.sorted(), listOf("2026-01-01", "2026-09-01", "2026-09-30", "2026-10-01"))
    }
}
