package com.cov.justdiary.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计令牌的纯 JVM 测试（不依赖 Android 框架，毫秒级）。
 *
 * 守住两类**不变量**（对应 iOS 侧的 `JustDiaryTests/ContentFormatTests` 与
 * `AdaptiveLayoutTests` 的同类断言）：
 *  1. 嵌套圆角公式；
 *  2. 缩放上限必须让字阶保持单调 —— 这是 iOS 侧踩过的坑（Apple 的原始曲线在 AX5
 *     会把正文推到比标题还大，导致层级倒挂）。
 */
class DesignTokensTest {

    @Test
    fun `concentric radius subtracts the inset`() {
        assertEquals(16.dp, Radius.concentric(Radius.card, 4.dp))
        assertEquals(Radius.field, Radius.concentric(Radius.card, 6.dp))
        assertEquals(0.dp, Radius.concentric(Radius.card, Radius.card))
    }

    @Test
    fun `concentric radius never goes negative`() {
        assertEquals(0.dp, Radius.concentric(Radius.field, Radius.card))
        assertEquals(0.dp, Radius.concentric(0.dp, 8.dp))
    }

    @Test
    fun `editor ladder is strictly decreasing`() {
        val ladder = DesignTypeSize.editorLadder
        assertEquals(4, ladder.size)
        for (i in 0 until ladder.size - 1) {
            assertTrue(
                "编辑器字阶必须严格递减：${ladder[i]} -> ${ladder[i + 1]}",
                ladder[i].value > ladder[i + 1].value,
            )
        }
    }

    @Test
    fun `calendar uses half speed scaling`() {
        // 半速：正文系数 1.6 时，日历系数应为 1.3
        assertEquals(1.3f, DesignTypeSize.calendarMultiplier(1.6f), 1e-6f)
        // 默认字号（系数 1）下必须恰好为 1，保证默认外观不偏移
        assertEquals(1f, DesignTypeSize.calendarMultiplier(1f), 1e-6f)
    }

    @Test
    fun `scaling ceilings keep the type ladder monotonic`() {
        val sizes = DesignTypeSize.ceilingBySize.keys.sortedByDescending { it.value }
        assertTrue("上限表至少要覆盖 4 个字号", sizes.size >= 4)

        var previousEffective = Float.MAX_VALUE
        for (size in sizes) {
            val ceiling = DesignTypeSize.ceilingBySize.getValue(size)
            assertTrue("上限必须 >= 1：$size -> $ceiling", ceiling >= 1f)
            val effective = size.value * ceiling
            assertTrue(
                "缩放后字阶发生倒挂：$size * $ceiling = $effective 不小于上一级 $previousEffective",
                effective < previousEffective,
            )
            previousEffective = effective
        }
    }
}
