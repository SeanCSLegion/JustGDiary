package com.cov.justdiary.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态字体度量的测试。
 *
 * 上限表是**逐字**从 iOS `DesignSystem.swift:124-136` 抄来的，这份测试守住两件事：
 *
 * 1. 在大字号下 Android 复现 iOS 文档里记的那张 AX5 实测表
 *    （`DesignSystem.swift:113-119` 的注释）—— 说明移植没有抄错；
 * 2. 缩放后**字阶永不倒挂**（`size × 上限` 严格递减），这正是上限存在的理由。
 */
class DynamicTypeMetricsTest {

    @Test
    fun `text style mapping matches the ios table`() {
        assertEquals(AppleTextStyle.LargeTitle, DynamicTypeMetrics.appleTextStyle(34f))
        assertEquals(AppleTextStyle.Title1, DynamicTypeMetrics.appleTextStyle(32f))
        assertEquals(AppleTextStyle.Title1, DynamicTypeMetrics.appleTextStyle(28f))
        assertEquals(AppleTextStyle.Title2, DynamicTypeMetrics.appleTextStyle(22f))
        assertEquals(AppleTextStyle.Title3, DynamicTypeMetrics.appleTextStyle(20f))
        assertEquals(AppleTextStyle.Body, DynamicTypeMetrics.appleTextStyle(17f))
        assertEquals(AppleTextStyle.Callout, DynamicTypeMetrics.appleTextStyle(16f))
        assertEquals(AppleTextStyle.Subheadline, DynamicTypeMetrics.appleTextStyle(15f))
        assertEquals(AppleTextStyle.Footnote, DynamicTypeMetrics.appleTextStyle(13f))
        assertEquals(AppleTextStyle.Footnote, DynamicTypeMetrics.appleTextStyle(14f))
        assertEquals(AppleTextStyle.Caption1, DynamicTypeMetrics.appleTextStyle(12f))
        assertEquals(AppleTextStyle.Caption2, DynamicTypeMetrics.appleTextStyle(11f))
    }

    @Test
    fun `ceilings match the ios switch verbatim`() {
        assertEquals(1.35f, DynamicTypeMetrics.ceiling(AppleTextStyle.LargeTitle), 1e-6f)
        assertEquals(1.40f, DynamicTypeMetrics.ceiling(AppleTextStyle.Title1), 1e-6f)
        assertEquals(1.45f, DynamicTypeMetrics.ceiling(AppleTextStyle.Title2), 1e-6f)
        assertEquals(1.45f, DynamicTypeMetrics.ceiling(AppleTextStyle.Title3), 1e-6f)
        assertEquals(1.50f, DynamicTypeMetrics.ceiling(AppleTextStyle.Body), 1e-6f)
        assertEquals(1.55f, DynamicTypeMetrics.ceiling(AppleTextStyle.Callout), 1e-6f)
        assertEquals(1.60f, DynamicTypeMetrics.ceiling(AppleTextStyle.Subheadline), 1e-6f)
        assertEquals(1.65f, DynamicTypeMetrics.ceiling(AppleTextStyle.Footnote), 1e-6f)
        assertEquals(1.70f, DynamicTypeMetrics.ceiling(AppleTextStyle.Caption1), 1e-6f)
        assertEquals(1.75f, DynamicTypeMetrics.ceiling(AppleTextStyle.Caption2), 1e-6f)
    }

    @Test
    fun `default font scale leaves every design size untouched`() {
        for (size in TypeSize.ladder) {
            assertEquals(
                "默认字号下 $size 必须原样返回",
                1f,
                DynamicTypeMetrics.effectiveMultiplier(size.value, 1f),
                1e-6f,
            )
        }
    }

    /** 大字号下复现 iOS 注释里记录的 AX5 实测表。 */
    @Test
    fun `large font scale reproduces the documented ax5 table`() {
        val expected = mapOf(
            32f to 44.8f,
            28f to 39.2f,
            24f to 34.8f,
            22f to 31.9f,
            20f to 29.0f,
            18f to 27.0f,
            17f to 25.5f,
            16f to 24.8f,
            15f to 24.0f,
            13f to 21.45f,
            12f to 20.4f,
            11f to 19.25f,
        )
        for ((size, scaled) in expected) {
            val actual = size * DynamicTypeMetrics.effectiveMultiplier(size, fontScale = 2f)
            assertEquals("设计字号 $size 在大字号下的结果", scaled, actual, 0.05f)
        }
    }

    @Test
    fun `multiplier never exceeds the ceiling and never shrinks text`() {
        for (size in TypeSize.ladder) {
            val ceiling = DynamicTypeMetrics.ceilingForSize(size.value)
            for (fontScale in listOf(0.85f, 1f, 1.3f, 1.6f, 2f, 3f)) {
                val multiplier = DynamicTypeMetrics.effectiveMultiplier(size.value, fontScale)
                assertTrue("$size @ $fontScale 不应超过上限", multiplier <= ceiling + 1e-6f)
                assertTrue("$size @ $fontScale 不应缩小", multiplier >= 1f - 1e-6f)
            }
        }
    }

    @Test
    fun `ladder stays monotonic at every font scale`() {
        for (fontScale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f, 2.5f)) {
            var previous = Float.MAX_VALUE
            for (size in TypeSize.ladder) {
                val scaled = size.value * DynamicTypeMetrics.effectiveMultiplier(size.value, fontScale)
                assertTrue(
                    "fontScale=$fontScale 时字阶倒挂：$size → $scaled 不小于上一级 $previous",
                    scaled < previous,
                )
                previous = scaled
            }
        }
    }

    @Test
    fun `effective sp cancels the automatic compose font scaling`() {
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            for (size in TypeSize.ladder) {
                val sp = DynamicTypeMetrics.effectiveSp(size, fontScale).value
                // Compose 会对 sp 自动乘 fontScale，最终绘制值应为 designSize × multiplier
                assertEquals(
                    "fontScale=$fontScale, size=$size",
                    size.value * DynamicTypeMetrics.effectiveMultiplier(size.value, fontScale),
                    sp * fontScale,
                    1e-3f,
                )
            }
        }
    }

    @Test
    fun `calendar grows at half rate`() {
        assertEquals(1f, DynamicTypeMetrics.calendarMultiplier(1f), 1e-6f)
        // 大字号下日历用 15pt 档的半速：1 + (1.6 - 1) * 0.5 = 1.3
        assertEquals(1.3f, DynamicTypeMetrics.calendarMultiplier(2f), 1e-6f)
        // 半速必须小于全速
        for (fontScale in listOf(1.2f, 1.5f, 2f)) {
            assertTrue(
                DynamicTypeMetrics.calendarMultiplier(fontScale) <
                    DynamicTypeMetrics.effectiveMultiplier(15f, fontScale),
            )
        }
    }

    @Test
    fun `invalid font scale falls back to one`() {
        assertEquals(1f, DynamicTypeMetrics.effectiveMultiplier(17f, 0f), 1e-6f)
        assertEquals(1f, DynamicTypeMetrics.effectiveMultiplier(17f, -3f), 1e-6f)
        assertEquals(1f, DynamicTypeMetrics.effectiveMultiplier(17f, Float.NaN), 1e-6f)
        assertEquals(1f, DynamicTypeMetrics.effectiveMultiplier(17f, Float.POSITIVE_INFINITY), 1e-6f)
    }

    @Test
    fun `editor ladder ceilings are the ones quoted in the plan`() {
        // 28 → Title1 1.40；22 → Title2 1.45；16 → Callout 1.55；14 → Footnote 1.65
        assertEquals(1.40f, DynamicTypeMetrics.ceilingForSize(TypeSize.editorTitle.value), 1e-6f)
        assertEquals(1.45f, DynamicTypeMetrics.ceilingForSize(TypeSize.editorHeading.value), 1e-6f)
        assertEquals(1.55f, DynamicTypeMetrics.ceilingForSize(TypeSize.editorBody.value), 1e-6f)
        assertEquals(1.65f, DynamicTypeMetrics.ceilingForSize(TypeSize.editorQuote.value), 1e-6f)
    }

    @Test
    fun `type size tokens follow the platform scales`() {
        assertEquals(32f, TypeSize.display.value, 1e-6f)
        assertEquals(24f, TypeSize.pageTitle.value, 1e-6f)
        assertEquals(17f, TypeSize.sheetTitle.value, 1e-6f)
        assertEquals(20f, TypeSize.cardTitle.value, 1e-6f)
        assertEquals(18f, TypeSize.headerTitle.value, 1e-6f)
        assertEquals(15f, TypeSize.rowTitle.value, 1e-6f)
        assertEquals(13f, TypeSize.rowSub.value, 1e-6f)
        assertEquals(14f, TypeSize.chip.value, 1e-6f)
        assertEquals(12f, TypeSize.badge.value, 1e-6f)
        // 编辑器段落梯级 = M3 角色：headlineMedium / titleLarge / bodyLarge / bodyMedium
        // （iOS 侧是 Apple HIG 的 Title1 28 / Title2 22 / Body 17 / Subheadline 15 —— 两端有意不同）
        assertEquals(28f, TypeSize.editorTitle.value, 1e-6f)
        assertEquals(22f, TypeSize.editorHeading.value, 1e-6f)
        assertEquals(listOf(28f, 22f, 16f, 14f), TypeSize.editorLadder.map { it.value })
    }

    @Test
    fun `m3 component roles keep the official type scale at fontScale 1`() {
        // 官方基线（material3 1.4.0 的 Typography() 默认值）。
        // 这条守住"顶栏 / 对话框 / 输入框 / 按钮用的是 Google 的字号，不是本项目自定的梯级"。
        assertEquals(28f, JustDiaryTypography.headlineMedium.fontSize.value, 1e-3f)
        assertEquals(24f, JustDiaryTypography.headlineSmall.fontSize.value, 1e-3f)
        assertEquals(22f, JustDiaryTypography.titleLarge.fontSize.value, 1e-3f)
        assertEquals(16f, JustDiaryTypography.titleMedium.fontSize.value, 1e-3f)
        assertEquals(14f, JustDiaryTypography.titleSmall.fontSize.value, 1e-3f)
        assertEquals(16f, JustDiaryTypography.bodyLarge.fontSize.value, 1e-3f)
        assertEquals(14f, JustDiaryTypography.bodyMedium.fontSize.value, 1e-3f)
        assertEquals(12f, JustDiaryTypography.bodySmall.fontSize.value, 1e-3f)
        assertEquals(14f, JustDiaryTypography.labelLarge.fontSize.value, 1e-3f)
        assertEquals(12f, JustDiaryTypography.labelMedium.fontSize.value, 1e-3f)
        assertEquals(11f, JustDiaryTypography.labelSmall.fontSize.value, 1e-3f)
        // 行高也用官方值（不再自算比例）
        assertEquals(24f, JustDiaryTypography.bodyLarge.lineHeight.value, 1e-3f)
        assertEquals(28f, JustDiaryTypography.titleLarge.lineHeight.value, 1e-3f)
    }

    @Test
    fun `editor paragraph ladder equals the material role sizes`() {
        assertEquals(TypeSize.editorTitle.value, JustDiaryTypography.headlineMedium.fontSize.value, 1e-3f)
        assertEquals(TypeSize.editorHeading.value, JustDiaryTypography.titleLarge.fontSize.value, 1e-3f)
        assertEquals(TypeSize.editorBody.value, JustDiaryTypography.bodyLarge.fontSize.value, 1e-3f)
        assertEquals(TypeSize.editorQuote.value, JustDiaryTypography.bodyMedium.fontSize.value, 1e-3f)
    }

    @Test
    fun `editor paragraph ladder maps onto material roles`() {
        // 行高不再自算比例，直接用 M3 角色的官方 lineHeight（见 EditorTypography 注释）；
        // 这里守住"样式 → 设计字号"的一一对应，角色映射在 EditorTypography.textStyleFor。
        assertEquals(TypeSize.editorTitle, EditorTypography.designSizeForStyle("title"))
        assertEquals(TypeSize.editorHeading, EditorTypography.designSizeForStyle("heading"))
        assertEquals(TypeSize.editorBody, EditorTypography.designSizeForStyle("body"))
        assertEquals(TypeSize.editorQuote, EditorTypography.designSizeForStyle("quote"))
        // 未知样式兜底成正文（不能默默变成大标题）
        assertEquals(TypeSize.editorBody, EditorTypography.designSizeForStyle("whatever"))
    }
}
