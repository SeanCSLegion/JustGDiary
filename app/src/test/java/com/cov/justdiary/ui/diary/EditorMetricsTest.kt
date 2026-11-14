package com.cov.justdiary.ui.diary

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.StyleSpan
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 编辑态的排版数据必须**逐项**与阅读态的 `TextStyle` 对齐：字号、行高、字距、字重。
 *
 * 前两项早就有测试（行盒 / 段距），后两项是 2026-11-14 用户反馈"编辑模式排版和阅读模式
 * 不一致"的直接根因：编辑态只把字号与行高传进 `Spannable`，字距是 0、小标题字重是 400，
 * 于是同一段文字在两边**换行位置不同**（正文 0.5sp、引用 0.25sp 的 M3 字距被丢了）。
 *
 * 这里既测"换算"（[blockMetricsOf]），也测"落到平台笔上"（[BlockMetricsSpan]）：
 * 只测前者的话，`SpannableEncoder` 忘了把新字段传下去时测试仍然是绿的（实际上就漏过一次）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorMetricsTest {

    private val density = Density(density = 3f, fontScale = 1f)

    @Test
    fun `body metrics carry the M3 letter spacing and weight`() {
        val metrics = blockMetricsOf(Typography().bodyLarge, density, paragraphSpacingPx = 24)

        assertEquals(48, metrics.textSizePx)
        assertEquals(72, metrics.lineHeightPx)
        assertEquals(24, metrics.paragraphSpacingPx)
        // bodyLarge：0.5sp @ 16sp = 0.03125em
        assertEquals(0.03125f, metrics.letterSpacingEm, 1e-6f)
        assertEquals(400, metrics.fontWeight)
    }

    @Test
    fun `heading metrics carry its 500 weight`() {
        val heading = Typography().titleLarge.copy(fontWeight = FontWeight.Medium)
        val metrics = blockMetricsOf(heading, density, paragraphSpacingPx = 24)

        assertEquals(500, metrics.fontWeight)
        // titleLarge 的 letterSpacing 是 0；不能把正文的字距错套到标题上
        assertEquals(0f, metrics.letterSpacingEm, 1e-6f)
    }

    @Test
    fun `block metrics span applies letter spacing and weight to the paint`() {
        val paint = TextPaint().apply { textSize = 12f }
        BlockMetricsSpan(
            textSizePx = 48,
            lineHeightPx = 72,
            paragraphSpacingPx = 24,
            letterSpacingEm = 0.03125f,
            fontWeight = 500,
        ).updateMeasureState(paint)

        assertEquals(48f, paint.textSize, 1e-3f)
        assertEquals(0.03125f, paint.letterSpacing, 1e-6f)
        assertEquals(500, paint.typeface.weight)
    }

    @Test
    fun `paragraph weight does not erase an inline bold or italic run`() {
        val bold = TextPaint().apply { textSize = 48f }
        StyleSpan(Typeface.BOLD).updateMeasureState(bold)
        BlockMetricsSpan(48, 72, 24, fontWeight = 500).updateMeasureState(bold)
        assertTrue("行内粗体必须压过段落字重", bold.typeface.isBold)

        val italic = TextPaint().apply { textSize = 48f }
        StyleSpan(Typeface.ITALIC).updateMeasureState(italic)
        BlockMetricsSpan(48, 72, 24, fontWeight = 500).updateMeasureState(italic)
        assertTrue("段落字重不能抹掉行内斜体", italic.typeface.isItalic)
        assertEquals("小标题 + 斜体仍应是 500 字重", 500, italic.typeface.weight)
    }
}
