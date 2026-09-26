package com.cov.justdiary.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版面判定的临界值测试。
 *
 * 这些数字全部来自 iOS `AdaptiveLayout.swift` 与设计稿（竖屏 402×874、横屏 874×402），
 * 因此逐条钉死：档位边界、分栏条件、主栏公式、设置列数、以及"竖屏够宽也不分栏"这条
 * 最容易写错的规则（分栏必须**同时**满足宽度与横屏）。
 */
class AdaptiveLayoutTest {

    private fun layout(width: Float, height: Float, leading: Float = 0f, trailing: Float = 0f) =
        AdaptiveLayout(width = width, height = height, safeLeading = leading, safeTrailing = trailing)

    // ------------------------------------------------------------------ 导航形态

    @Test
    fun `nav form switches at 600dp`() {
        assertEquals(AdaptiveLayout.NavForm.Bar, layout(599f, 874f).navForm)
        assertEquals(AdaptiveLayout.NavForm.Bar, layout(600f - 0.01f, 874f).navForm)
        assertEquals(AdaptiveLayout.NavForm.Rail, layout(600f, 874f).navForm)
        assertEquals(AdaptiveLayout.NavForm.Rail, layout(750f, 382f).navForm)
    }

    @Test
    fun `portrait phone uses the bottom bar`() {
        assertEquals(AdaptiveLayout.NavForm.Bar, layout(402f, 874f).navForm)
    }

    // ------------------------------------------------------------------ 内容档位

    @Test
    fun `content tier thresholds are 700 and 1000`() {
        assertEquals(AdaptiveLayout.Tier.Compact, layout(699f, 874f).tier)
        assertEquals(AdaptiveLayout.Tier.Medium, layout(700f, 874f).tier)
        assertEquals(AdaptiveLayout.Tier.Medium, layout(999f, 874f).tier)
        assertEquals(AdaptiveLayout.Tier.Expanded, layout(1000f, 874f).tier)
    }

    /** 判档位用的是**窗口宽度**（与 iOS 一致：`AdaptiveLayout.size` 是界面尺寸）。 */
    @Test
    fun `tier is not reduced by safe areas`() {
        assertEquals(AdaptiveLayout.Tier.Medium, layout(750f, 382f, leading = 62f).tier)
    }

    // ------------------------------------------------------------------ 分栏

    @Test
    fun `landscape phone splits when content width is enough`() {
        // 874×402 横屏：内容宽 874（Android 侧没有 iOS 的左侧浮条占位）
        assertTrue(layout(874f, 402f).splitsMasterDetail)
    }

    @Test
    fun `portrait never splits even when wide enough`() {
        // 这是最容易写错的一条：宽度够了，但竖屏必须保持单栏（年/月/周三态 morph 不动）
        assertFalse(layout(800f, 1200f).splitsMasterDetail)
        assertFalse(layout(1000f, 1400f).splitsMasterDetail)
    }

    @Test
    fun `split needs 548_5dp of content width`() {
        // 548.5 = 2×16(页边距) + 16.5(栏间距+分隔线) + 260(主栏最窄) + 240(详情栏最窄)
        assertFalse(layout(548.4f, 400f).splitsMasterDetail)
        assertTrue(layout(548.5f, 400f).splitsMasterDetail)
    }

    @Test
    fun `side safe areas count against the content width`() {
        // 横屏有侧边系统占位时：874 - 62 = 812 ≥ 548.5 仍然分栏
        assertTrue(layout(874f, 402f, leading = 62f).splitsMasterDetail)
        // 扣完不够就不分栏
        assertFalse(layout(600f, 382f, leading = 62f).splitsMasterDetail)
        // 刚好卡在临界：600 - 51.5 = 548.5
        assertTrue(layout(600f, 382f, leading = 51.5f).splitsMasterDetail)
    }

    @Test
    fun `tablet portrait never splits even at 800dp wide`() {
        // 平板竖屏 800×1280：宽度远超阈值，但宽 < 高 → 保持单栏（与 iOS 一致）
        assertFalse(layout(800f, 1280f).splitsMasterDetail)
        // 平板横屏 1280×800（已扣侧栏 96dp → 内容 1184）→ 分栏
        assertTrue(layout(1184f, 800f).splitsMasterDetail)
    }

    @Test
    fun `master width matches the iOS value at 750dp content width`() {
        // iOS：0.46 × 750 = 345 —— 与设计稿的横屏左栏宽度一致
        assertEquals(345f, layout(750f, 382f).masterWidth, 0.001f)
    }

    @Test
    fun `master width is clamped to 260 through 440`() {
        assertEquals(260f, layout(402f, 874f).masterWidth, 0.001f)
        assertEquals(440f, layout(1400f, 800f).masterWidth, 0.001f)
    }

    @Test
    fun `master width leaves at least 240dp for the detail column at the split threshold`() {
        // 分栏下沿：内容 548.5 → 主栏 clamp(252.31, 260, 440) = 260，
        // 详情栏 = 548.5 - 260 - 32(页边距) - 16.5(间距) = 240
        val l = layout(548.5f, 400f)
        val detail = l.contentWidth - l.masterWidth - 32f - 16.5f
        assertEquals(240f, detail, 0.01f)
    }

    // ------------------------------------------------------------------ 设置列数

    @Test
    fun `settings columns are 1 2 3 across the tiers`() {
        assertEquals(1, layout(699f, 874f).settingsColumns)
        assertEquals(2, layout(700f, 874f).settingsColumns)
        assertEquals(2, layout(999f, 874f).settingsColumns)
        assertEquals(3, layout(1000f, 874f).settingsColumns)
    }

    @Test
    fun `portrait phone settings keep a single column`() {
        assertEquals(1, layout(402f, 874f).settingsColumns)
    }

    // ------------------------------------------------------------------ 页面级数值

    @Test
    fun `footprint list width follows the iOS formula`() {
        // 874 → max(240, 349.6) = 349.6 → min(320, …) = 320
        assertEquals(320f, layout(874f, 402f).footprintListWidth, 0.001f)
        // 700 → 0.4 × 700 = 280
        assertEquals(280f, layout(700f, 400f).footprintListWidth, 0.001f)
    }

    @Test
    fun `footprint block height is clamped to 140 through 190`() {
        assertEquals(140f, layout(874f, 382f).footprintBlockHeight, 0.001f)
        assertEquals(190f, layout(874f, 700f).footprintBlockHeight, 0.001f)
        assertEquals(190f, layout(874f, 452f).footprintBlockHeight, 0.001f)
    }

    @Test
    fun `trend height follows the split flag`() {
        assertEquals(AdaptiveLayout.TREND_HEIGHT_PHONE, layout(402f, 874f).trendHeight, 0.001f)
        assertEquals(AdaptiveLayout.TREND_HEIGHT_WIDE, layout(874f, 402f).trendHeight, 0.001f)
    }

    @Test
    fun `content column is capped but never below the minimum`() {
        // 宽屏限宽：660 上限，否则一行 100+ 字
        assertEquals(660f, layout(1400f, 800f).contentColumn(), 0.001f)
        // 窄屏不能因为限宽反而缩一圈（竖屏 402 - 32 = 370）
        assertEquals(370f, layout(402f, 874f).contentColumn(), 0.001f)
        // 极窄：兜到 240 下沿
        assertEquals(240f, layout(200f, 800f).contentColumn(), 0.001f)
        // 编辑列上限可以单独指定
        assertEquals(620f, layout(1400f, 800f).contentColumn(AdaptiveLayout.EDITING_MAX_WIDTH), 0.001f)
    }

    @Test
    fun `filters sit side by side only in the split layout`() {
        assertFalse(layout(402f, 874f).filtersSideBySide)
        assertTrue(layout(874f, 402f).filtersSideBySide)
    }
}
