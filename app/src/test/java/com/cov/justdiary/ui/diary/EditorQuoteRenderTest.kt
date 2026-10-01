package com.cov.justdiary.ui.diary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.StaticLayout
import android.text.TextPaint
import com.cov.justdiary.model.ContentPartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 引用段**画出来**长什么样（R30 记的 F1：编辑态点「引用」正文外观逐像素不变）。
 *
 * 这一条只能看像素，所以测试真的**把 `StaticLayout` 画进 `Bitmap` 再探针**：
 * Robolectric 的 native graphics 走的是真的 Skia，画出来的就是设备上看到的东西。
 * 探的是三件事：
 *
 * 1. **有没有底**：引用行中间那块像素必须是底色，正文行的同一位置必须是白的；
 * 2. **纵向内边距**：行框上下各多出 6dp 的底，再往外就是白的；
 * 3. **多行是一个整块**：折行的引用在行与行之间**不留缝**（中间行不重复扩边）。
 *
 * 另外钉住"横向内边距是左侧缩进"这个平台限制（`Spannable` 没有右边距 span，见
 * [SpannableBridge.QuoteStyle] 的说明）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorQuoteRenderTest {

    private val boxColor = Color.rgb(0xC0, 0x20, 0x20)
    private val style = SpannableBridge.QuoteStyle(
        backgroundColor = boxColor,
        cornerRadiusPx = 4f,
        textInsetPx = 10,
        paddingVerticalPx = 6,
    )
    private val metrics = SpannableBridge.BlockMetrics(textSizePx = 40, lineHeightPx = 50, paragraphSpacingPx = 0)

    private val width = 400

    private fun layoutOf(lines: List<EditorLine>): StaticLayout {
        val spanned = SpannableBridge.toSpannable(
            lines,
            imageProvider = null,
            containerWidthPx = width,
            metricsFor = { metrics },
            quoteStyle = style,
        )
        val paint = TextPaint().apply { textSize = metrics.textSizePx.toFloat() }
        return StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width).build()
    }

    /**
     * 把 layout 画进 Bitmap —— **按 EditText 的真实画法**：视图上下各留出
     * [SpannableBridge.QuoteStyle.paddingVerticalPx] 的内边距，引用底的上下 6dp 才有地方画
     * （`EditorTextArea` 里就是 `setPadding(0, padV, 0, padV)`，否则第一行的引用底会被视图上沿裁掉）。
     */
    private fun render(layout: StaticLayout): Bitmap {
        val pad = style.paddingVerticalPx
        val bitmap = Bitmap.createBitmap(width, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate(0f, pad.toFloat())
        layout.draw(canvas)
        return bitmap
    }

    /** 探针坐标：bitmap 坐标（= 视图坐标）。 */
    private fun pixel(bitmap: Bitmap, x: Int, y: Int) = bitmap.getPixel(x, y)

    /** layout 坐标 → bitmap 坐标。 */
    private fun v(y: Int) = y + style.paddingVerticalPx

    private fun isBox(color: Int) =
        Color.red(color) > 150 && Color.green(color) < 120 && Color.blue(color) < 120

    @Test
    fun `a quote paragraph gets a background box and a body paragraph does not`() {
        val layout = layoutOf(listOf(EditorLine(text = "bbbb"), EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)))
        val bitmap = render(layout)

        // 正文那一行：同一列必须是白的（没有底）
        assertTrue("正文行不该有底", !isBox(pixel(bitmap, width / 2, v(layout.getLineTop(0) + 4))))

        // 引用那一行：行的正中间必须是底色
        val quoteLine = layout.getLineForOffset(layout.getLineStart(1) + 1)
        val quoteMid = (layout.getLineTop(quoteLine) + layout.getLineBottom(quoteLine)) / 2
        assertTrue(
            "引用行该有底（实际 ${Integer.toHexString(pixel(bitmap, width / 2, v(quoteMid)))}）",
            isBox(pixel(bitmap, width / 2, v(quoteMid))),
        )
    }

    @Test
    fun `the box extends six pixels above and below the quote line and then stops`() {
        val layout = layoutOf(listOf(EditorLine(text = "bbbb"), EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)))
        val bitmap = render(layout)
        val line = layout.getLineForOffset(layout.getLineStart(1))
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line, /* includeLineSpacing = */ false)

        assertTrue("行框里侧 2px 处是底", isBox(pixel(bitmap, width / 2, v(top + 2))))
        assertTrue("行框上方 3px（在 6px 内边距里）还是底", isBox(pixel(bitmap, width / 2, v(top - 3))))
        assertTrue("行框下方 3px 还是底", isBox(pixel(bitmap, width / 2, v(bottom + 3))))
        // 再往外（>6px）应当是白的。探上方：引用是文档最后一行时，下方已经在 bitmap 之外了
        assertTrue("上方再往外（>6px）应当是白的", !isBox(pixel(bitmap, width / 2, v(top - 9))))
    }

    @Test
    fun `a wrapped quote is one continuous box with rounded outer corners only`() {
        val long = "引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用"
        val layout = layoutOf(listOf(EditorLine(text = long, blockStyle = ContentPartStyle.quote)))
        assertTrue("这一段应当折成多行（实际 ${layout.lineCount} 行）", layout.lineCount >= 2)
        val bitmap = render(layout)

        // 行与行之间不能有白缝
        val firstBottom = layout.getLineBottom(0, /* includeLineSpacing = */ false)
        for (y in (firstBottom - 2)..(firstBottom + 2)) {
            assertTrue("折行处第 $y 行像素断了", isBox(pixel(bitmap, width / 2, v(y))))
        }
        // 整块底**上沿**是圆的：底的最上一行像素里，最左那一点不该是实心（被圆角切掉）
        val boxTop = v(layout.getLineTop(0) - style.paddingVerticalPx)
        assertTrue("上沿正中是底的实心", isBox(pixel(bitmap, width / 2, boxTop + 1)))
        assertTrue("左上角要圆掉", !isBox(pixel(bitmap, 0, boxTop)))
    }

    @Test
    fun `the quote text is inset from the left by the horizontal padding`() {
        val layout = layoutOf(listOf(EditorLine(text = "MMMM", blockStyle = ContentPartStyle.quote)))
        val bitmap = render(layout)
        val line = layout.getLineForOffset(0)
        val band = ((layout.getLineTop(line) + layout.getLineBottom(line)) / 2)

        // 找这一行里第一个"既不是底、也不是白"的像素 —— 那就是文字
        var firstText = -1
        for (x in 0 until width) {
            val c = pixel(bitmap, x, v(band))
            if (!isBox(c) && c != Color.WHITE) {
                firstText = x
                break
            }
        }
        assertTrue("引用行里应当找得到文字像素", firstText >= 0)
        assertTrue(
            "文字要从 $style.textInsetPx px 之后开始（实际 $firstText）",
            firstText >= style.textInsetPx,
        )
    }

    @Test
    fun `without a quote style nothing is drawn`() {
        val spanned = SpannableBridge.toSpannable(
            listOf(EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)),
            imageProvider = null,
            containerWidthPx = width,
            metricsFor = { metrics },
            quoteStyle = null,
        )
        val paint = TextPaint().apply { textSize = metrics.textSizePx.toFloat() }
        val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width).build()
        val bitmap = render(layout)

        val mid = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2
        assertEquals("没给引用样式时一个底都不该画", Color.WHITE, pixel(bitmap, width / 2, mid))
    }

    /**
     * **末尾空行**的引用底：那一行一个字符都没有，`QuoteBackgroundSpan` 挂不上，
     * 由 `DiaryEditText.onDraw` 自己画（F1 里用户最容易撞上的那一幕：在空行上点「引用」）。
     * 这里直接驱动真实 `EditText`：量好 → 布局 → 画进 Bitmap → 探针。
     */
    @Test
    fun `an empty trailing quote line is painted by the view itself`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val view = DiaryEditText(context)
        val tail = EditorLine(text = "", blockStyle = ContentPartStyle.quote)
        view.tailQuoteStyle = style
        view.setPadding(0, style.paddingVerticalPx, 0, style.paddingVerticalPx)
        view.setText(
            SpannableBridge.toSpannable(
                listOf(tail),
                imageProvider = null,
                containerWidthPx = width,
                metricsFor = { metrics },
                quoteStyle = style,
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

        assertTrue(
            "末尾空行的引用底要画出来（视图级兜底），实际 ${Integer.toHexString(pixel(bitmap, width / 2, 30))}",
            isBox(pixel(bitmap, width / 2, 30)),
        )
        assertTrue("底只在顶部那条带里，下方仍是白的", !isBox(pixel(bitmap, width / 2, 300)))
    }

    /** 末尾不是空的引用行时，视图一个像素都不该画（避免和 span 画两遍）。 */
    @Test
    fun `the view paints nothing when the trailing line is not an empty quote`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val view = DiaryEditText(context)
        view.tailQuoteStyle = null
        view.setText(
            SpannableBridge.toSpannable(
                listOf(EditorLine(text = "正文")),
                imageProvider = null,
                containerWidthPx = width,
                metricsFor = { metrics },
                quoteStyle = style,
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

        assertTrue("正文行不该有任何引用底", !isBox(pixel(bitmap, width / 2, 30)))
    }

    /**
     * 段后距**不能**算进引用底：它是烘进 `LineHeightSpan` 的 descent 里的，
     * `drawBackground` 拿到的 `bottom` 含这一段，不减掉就会"引用底比文字多出一大截"，
     * 看起来像把下一行也包进来了（用户 2026-09-28 实测："引用的作用范围现在也不对"）。
     *
     * 不去算具体的行高（各家字体的度量不一样），直接比**同一段内容在段后距 0 / 24 下的底高**：
     * 底高必须一样 —— 段后距只该影响行与行的间距，不该把底撑高。
     */
    @Test
    fun `the paragraph spacing does not stretch the box`() {
        fun boxHeight(spacing: Int): Int {
            val quoteStyle = style.copy(paragraphSpacingPx = spacing)
            val spanned = SpannableBridge.toSpannable(
                listOf(EditorLine(text = "bbbb"), EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)),
                imageProvider = null,
                containerWidthPx = width,
                metricsFor = { metrics.copy(paragraphSpacingPx = spacing) },
                quoteStyle = quoteStyle,
            )
            val paint = TextPaint().apply { textSize = metrics.textSizePx.toFloat() }
            val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width).build()
            val pad = quoteStyle.paddingVerticalPx
            val bitmap = Bitmap.createBitmap(width, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.translate(0f, pad.toFloat())
            layout.draw(canvas)
            val rows = (0 until bitmap.height).filter { isBox(pixel(bitmap, width / 2, it)) }
            assertTrue("没画出引用底", rows.isNotEmpty())
            return rows.last() - rows.first() + 1
        }

        val withoutSpacing = boxHeight(0)
        val withSpacing = boxHeight(24)
        assertEquals("段后距把引用底撑高了", withoutSpacing, withSpacing)
    }
}
