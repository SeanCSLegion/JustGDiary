package com.cov.justdiary.ui.diary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.cov.justdiary.model.ContentPartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **编辑态的段落几何**：标记槽 / 悬挂缩进 / 勾选框 / 段距。
 *
 * 这些数全部来自 [BlockGeometry]，阅读态与首页预览用同一批 dp 常量 ——
 * 所以这里量的既是"编辑态长什么样"，也是三个渲染面共同的尺子。
 *
 * 用真实排版 + 真实绘制（Robolectric NATIVE）：`StaticLayout` 排一遍、画成位图后按像素探针，
 * 因此"缩进对不对""圆点画在哪""行高有没有被标记撑高"都是量出来的，不是算出来的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorMarkerRenderTest {

    private val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
    private fun px(value: Float): Int = (value * density).toInt()

    private val metrics = SpannableBridge.BlockMetrics(
        textSizePx = px(16f),
        lineHeightPx = px(24f),
        paragraphSpacingPx = px(8f),
    )

    // 探针色必须是**文字抗锯齿不可能产生**的饱和色：正文是黑字白底，任何灰度都会被
    // 当成"墨迹"（`EditorQuoteRenderTest` 也踩过同一个坑）。文字因此一律不参与判定。
    private val bulletColor = Color.rgb(0xC0, 0x20, 0x20)
    private val fillColor = Color.rgb(0x00, 0x60, 0xC0)
    private val checkColor = Color.rgb(0xFF, 0xD0, 0x00)

    private val marker = SpannableBridge.MarkerStyle(
        indentPx = px(BlockGeometry.MarkerIndent.value),
        centerXPx = px(BlockGeometry.MarkerSlot.value) / 2f,
        bulletDiameterPx = px(BlockGeometry.BulletSize.value).toFloat(),
        checkboxSizePx = px(BlockGeometry.CheckboxSize.value).toFloat(),
        checkboxRadiusPx = px(BlockGeometry.CheckboxRadius.value).toFloat(),
        checkboxBorderPx = px(BlockGeometry.CheckboxBorder.value).toFloat(),
        outlineColor = bulletColor,
        fillColor = fillColor,
        checkColor = checkColor,
        doneTextColor = bulletColor,
    )

    private val width = 300

    private fun layoutOf(lines: List<EditorLine>, width: Int = this.width): StaticLayout {
        val spanned = SpannableBridge.toSpannable(
            lines = lines,
            containerWidthPx = width,
            metricsFor = { metrics },
            markerStyle = marker,
        )
        val paint = TextPaint().apply { textSize = metrics.textSizePx.toFloat() }
        return StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width).build()
    }

    private fun render(layout: StaticLayout): Bitmap {
        val pad = px(BlockGeometry.TextInset.value)
        val bitmap = Bitmap.createBitmap(width, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate(0f, pad.toFloat())
        layout.draw(canvas)
        return bitmap
    }

    /**
     * 引用底（含左侧竖条）现在由**视图**在 `super.onDraw` 之前画（画在光标之下，见
     * `DiaryEditText.onDraw`），所以这一条要经过真实的 `DiaryEditText`，不能再只画 `StaticLayout`。
     */
    private fun renderQuoteView(lines: List<EditorLine>, quoteStyle: SpannableBridge.QuoteStyle): Bitmap {
        val pad = px(BlockGeometry.TextInset.value)
        val view = DiaryEditText(RuntimeEnvironment.getApplication())
        view.quoteStyle = quoteStyle
        view.tailQuote = false
        view.setPadding(0, pad, 0, pad)
        view.setText(
            SpannableBridge.toSpannable(
                lines = lines,
                containerWidthPx = width,
                metricsFor = { metrics },
                quoteStyle = quoteStyle,
            ),
        )
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, 400)
        val bitmap = Bitmap.createBitmap(width, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        return bitmap
    }

    private fun pixel(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getPixel(x, y)

    /** 位图里所有"接近某个颜色"的像素（抗锯齿会掺一点底色，所以按通道差判定）。 */
    private fun pixelsNear(bitmap: Bitmap, color: Int, tolerance: Int = 40): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val p = pixel(bitmap, x, y)
                if (kotlin.math.abs(Color.red(p) - Color.red(color)) <= tolerance &&
                    kotlin.math.abs(Color.green(p) - Color.green(color)) <= tolerance &&
                    kotlin.math.abs(Color.blue(p) - Color.blue(color)) <= tolerance
                ) {
                    out.add(x to y)
                }
            }
        }
        return out
    }

    private val longText = "列表项的文字长到必须折行才能看出第二行从哪里开始"

    @Test
    fun `a wrapped item hangs at the marker indent`() {
        val layout = layoutOf(listOf(EditorLine(text = longText, marker = EditorLine.MarkerKind.Bullet)))
        assertTrue("这段文字要能折行（否则这条用例白测）", layout.lineCount >= 2)

        val secondLineStart = layout.getLineStart(1)
        val x = layout.getPrimaryHorizontal(secondLineStart)
        assertEquals(
            "换行后的第二行要从标记缩进 ${BlockGeometry.MarkerIndent} 起（悬挂缩进）",
            px(BlockGeometry.MarkerIndent.value).toFloat(),
            x,
            1.5f,
        )
    }

    @Test
    fun `the bullet is drawn inside the marker slot`() {
        val layout = layoutOf(listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet)))
        val bitmap = render(layout)
        val dots = pixelsNear(bitmap, bulletColor)
        assertTrue("应当画出一个圆点", dots.isNotEmpty())

        val left = dots.minOf { it.first }
        val right = dots.maxOf { it.first }
        val slot = px(BlockGeometry.MarkerSlot.value)
        assertTrue("圆点整体要落在标记槽内（实测 $left..$right，槽宽 $slot）", left >= 0 && right <= slot)
        val center = (left + right) / 2
        assertEquals("圆点在槽里居中", (slot / 2).toDouble(), center.toDouble(), 2.0)
    }

    @Test
    fun `the marker never grows the line box`() {
        val withMarker = layoutOf(listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet)))
        val withoutMarker = layoutOf(listOf(EditorLine(text = "甲")))
        assertEquals(
            "标记自己占的行高必须与正文一致（否则列表行的行距会比正文大）",
            withoutMarker.getLineBottom(0) - withoutMarker.getLineTop(0),
            withMarker.getLineBottom(0) - withMarker.getLineTop(0),
        )
    }

    @Test
    fun `an open todo draws an outline box of the M3 size`() {
        val layout = layoutOf(listOf(EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo)))
        val bitmap = render(layout)
        val outline = pixelsNear(bitmap, bulletColor)
        assertTrue("未完成的勾选框要有描边", outline.isNotEmpty())

        val size = px(BlockGeometry.CheckboxSize.value)
        val boxWidth = outline.maxOf { it.first } - outline.minOf { it.first } + 1
        assertTrue("勾选框宽度应当就是 ${BlockGeometry.CheckboxSize}（实测 $boxWidth）", boxWidth <= size && boxWidth >= size - 2)
        assertTrue("勾选框不能跑出标记槽", outline.maxOf { it.first } <= px(BlockGeometry.MarkerSlot.value))
    }

    @Test
    fun `a done todo fills the box and draws the check`() {
        val layout = layoutOf(
            listOf(EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo, done = true)),
        )
        val bitmap = render(layout)
        assertTrue("完成的勾选框要有实底", pixelsNear(bitmap, fillColor).size >= 20)
        assertTrue("完成的勾选框要有对勾", pixelsNear(bitmap, checkColor, tolerance = 30).isNotEmpty())
    }

    /**
     * 完成态的删除线**只能改绘制**：勾一下待办不该把整行写成"手动删除线"
     * （否则回读时 `run.strike = true` 会落进 `content_json`，取消勾选也退不回来）。
     */
    @Test
    fun `the done decoration never leaks into the model`() {
        val line = EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo, done = true)
        val spanned = SpannableBridge.toSpannable(
            lines = listOf(line),
            containerWidthPx = width,
            metricsFor = { metrics },
            markerStyle = marker,
        )
        val parsed = SpannableBridge.fromSpannable(spanned).first()
        assertTrue("完成态仍然是待办", parsed.isTodoItem && parsed.done)
        assertEquals("勾选状态不该变成行内删除线", emptyList<Any>(), parsed.spans.filter { it.strike == true })
    }

    @Test
    fun `the paragraph gap sits between paragraphs but not after the last one`() {
        val one = layoutOf(listOf(EditorLine(text = "甲")))
        val two = layoutOf(listOf(EditorLine(text = "甲"), EditorLine(text = "乙")))
        val gap = px(BlockGeometry.ParagraphGap.value)
        val lineHeight = metrics.lineHeightPx

        assertEquals("单独一段：行高就是行高，文末不多出一段距离", lineHeight, one.height)
        assertEquals("两段之间恰好 = 行高 + 段距", lineHeight * 2 + gap, two.height)
    }

    @Test
    fun `a quote line indents its text by the shared inset`() {
        val quoteStyle = SpannableBridge.QuoteStyle(
            backgroundColor = Color.rgb(0xEE, 0xEE, 0xEE),
            cornerRadiusPx = px(BlockGeometry.QuoteRadius.value).toFloat(),
            textInsetPx = px(BlockGeometry.QuoteTextInset.value),
            paddingVerticalPx = px(BlockGeometry.QuotePadding.value),
        )
        val spanned = SpannableBridge.toSpannable(
            lines = listOf(EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)),
            containerWidthPx = width,
            metricsFor = { metrics },
            quoteStyle = quoteStyle,
        )
        val paint = TextPaint().apply { textSize = metrics.textSizePx.toFloat() }
        val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width).build()
        assertEquals(
            "引用正文要缩进 ${BlockGeometry.QuoteTextInset}（竖条在里面）",
            px(BlockGeometry.QuoteTextInset.value).toFloat(),
            layout.getPrimaryHorizontal(0),
            1.5f,
        )
        // 与正文对照：没有引用样式时贴边
        val plain = layoutOf(listOf(EditorLine(text = "引用")))
        assertNotEquals(plain.getPrimaryHorizontal(0), layout.getPrimaryHorizontal(0))
    }

    @Test
    fun `the quote bar is drawn inside the block near the left edge`() {
        val barColor = Color.rgb(0x10, 0x80, 0x30)
        val quoteStyle = SpannableBridge.QuoteStyle(
            backgroundColor = Color.rgb(0xEE, 0xEE, 0xEE),
            cornerRadiusPx = px(BlockGeometry.QuoteRadius.value).toFloat(),
            textInsetPx = px(BlockGeometry.QuoteTextInset.value),
            paddingVerticalPx = px(BlockGeometry.QuotePadding.value),
            barLeadingPx = px(BlockGeometry.QuoteBarLeading.value).toFloat(),
            barWidthPx = px(BlockGeometry.QuoteBarWidth.value).toFloat(),
            barInsetPx = px(BlockGeometry.QuoteBarInset.value).toFloat(),
            barRadiusPx = px(BlockGeometry.QuoteBarRadius.value).toFloat(),
            barColor = barColor,
        )
        val bitmap = renderQuoteView(
            listOf(EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)),
            quoteStyle,
        )

        val bar = pixelsNear(bitmap, barColor)
        assertTrue("引用块要有左侧竖条", bar.isNotEmpty())
        val leading = px(BlockGeometry.QuoteBarLeading.value)
        val barWidth = px(BlockGeometry.QuoteBarWidth.value)
        assertTrue(
            "竖条要落在块内左侧（实测 ${bar.minOf { it.first }}..${bar.maxOf { it.first }}，" +
                "期望 $leading..${leading + barWidth}）",
            bar.minOf { it.first } >= leading - 1 && bar.maxOf { it.first } <= leading + barWidth,
        )
    }

    /** 缩进必须真的把文字推到槽后面，而不是只把标记画窄了。 */
    @Test
    fun `the first line of an item starts after the marker glyph`() {
        val layout = layoutOf(listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet)))
        val x = layout.getPrimaryHorizontal(SpannableBridge.MARKER_LENGTH)
        assertEquals(
            "正文从 ${BlockGeometry.MarkerIndent} 起（标记画布宽度 = 槽 + 间距）",
            px(BlockGeometry.MarkerIndent.value).toFloat(),
            x,
            1.5f,
        )
        assertTrue("这一行不能被折行", layout.lineCount == 1)
        assertEquals(Layout.Alignment.ALIGN_NORMAL, layout.getParagraphAlignment(0))
    }
}
