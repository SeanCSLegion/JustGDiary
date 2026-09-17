package com.cov.justdiary.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 度量令牌测试。数值全部逐字来自 iOS `Views/Components/DesignSystem.swift`，
 * 一旦有人"顺手改一下圆角"，这里会立刻失败。
 */
class DesignTokensTest {

    @Test
    fun `radius tokens match ios`() {
        assertEquals(20f, Radius.card.value, 1e-6f)
        assertEquals(26f, Radius.panel.value, 1e-6f)
        assertEquals(14f, Radius.field.value, 1e-6f)
        assertEquals(14f, Radius.image.value, 1e-6f)
        assertEquals(12f, Radius.badge.value, 1e-6f)
        assertEquals(4f, Radius.bar.value, 1e-6f)
        assertEquals(1f, Radius.hairline.value, 1e-6f)
    }

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
    fun `spacing tokens match ios`() {
        assertEquals(12f, Spacing.card.value, 1e-6f)
        assertEquals(12f, Spacing.rowIcon.value, 1e-6f)
        assertEquals(44f, Spacing.hitTarget.value, 1e-6f)
    }
}
