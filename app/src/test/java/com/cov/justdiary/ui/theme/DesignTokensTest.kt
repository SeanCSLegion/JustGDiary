package com.cov.justdiary.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 度量令牌测试。
 *
 * 形状与触控目标的取值**对齐 Material 3**（`ShapeKeyTokens` 的
 * `extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28`，以及
 * Material 的 48dp 最小触控目标）；首轮那套数字是从 iOS `DesignSystem.swift`
 * 逐字搬来的（20 / 26 / 14 / 44），不是 Google 的梯级，已改。
 * 有人"顺手改一下圆角"时这里会立刻失败。
 */
class DesignTokensTest {

    @Test
    fun `radius tokens sit on the material 3 shape scale`() {
        // M3 large / extraLarge / medium / medium
        assertEquals(16f, Radius.card.value, 1e-6f)
        assertEquals(28f, Radius.panel.value, 1e-6f)
        assertEquals(12f, Radius.field.value, 1e-6f)
        assertEquals(12f, Radius.image.value, 1e-6f)
        assertEquals(12f, Radius.badge.value, 1e-6f)
        assertEquals(4f, Radius.bar.value, 1e-6f)
        assertEquals(1f, Radius.hairline.value, 1e-6f)
    }

    @Test
    fun `concentric radius subtracts the inset`() {
        assertEquals(12.dp, Radius.concentric(Radius.card, 4.dp))
        assertEquals(Radius.field, Radius.concentric(Radius.card, 4.dp))
        assertEquals(0.dp, Radius.concentric(Radius.card, Radius.card))
    }

    @Test
    fun `concentric radius never goes negative`() {
        assertEquals(0.dp, Radius.concentric(Radius.field, Radius.card))
        assertEquals(0.dp, Radius.concentric(0.dp, 8.dp))
    }

    @Test
    fun `spacing tokens follow material 3`() {
        assertEquals(12f, Spacing.card.value, 1e-6f)
        assertEquals(12f, Spacing.rowIcon.value, 1e-6f)
        // Material 的硬性最小触控目标（iOS HIG 是 44pt）
        assertEquals(48f, Spacing.hitTarget.value, 1e-6f)
    }
}
