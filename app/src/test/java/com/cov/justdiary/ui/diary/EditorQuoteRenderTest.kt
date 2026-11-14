package com.cov.justdiary.ui.diary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.util.TypedValue
import android.view.View
import com.cov.justdiary.model.ContentPartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 引用段**画出来**长什么样（R30 记的 F1：编辑态点「引用」正文外观逐像素不变）。
 *
 * 这一条只能看像素，所以测试真的**把真实的 `DiaryEditText` 画进 `Bitmap` 再探针**
 * （Robolectric 的 native graphics 走的是真的 Skia，画出来的就是设备上看到的东西）。
 * 探的是四件事：
 *
 * 1. **有没有底**：引用行中间那块像素必须是底色，正文行的同一位置必须是白的；
 * 2. **纵向内边距**：行框上下各多出 6dp 的底，再往外就是白的；
 * 3. **多行是一个整块**：折行的引用在行与行之间**不留缝**（中间行不重复扩边）；
 * 4. **底不能盖住光标**：底画在 `super.onDraw` 之前、文本里一个 `LineBackgroundSpan` 都没有
 *    —— 平台的 `Editor` 是"先画光标、再画行背景 span"（`Editor.java:2143-2154`），
 *    底挂在 span 上就会把光标整个盖住（真机实测：引用段里打上字之后，
 *    光标在 8 张连拍里一张都找不到，而正文 / 大标题行同样的操作 3/8 张能找到）。
 *
 * 另外钉住"横向内边距是左侧缩进"这个平台限制（`Spannable` 没有右边距 span，见
 * [QuoteStyle] 的说明）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorQuoteRenderTest {

    private val boxColor = Color.rgb(0xC0, 0x20, 0x20)
    private val style = QuoteStyle(
        backgroundColor = boxColor,
        cornerRadiusPx = 4f,
        textInsetPx = 10,
        paddingVerticalPx = 6,
    )
    private val metrics = BlockMetrics(textSizePx = 40, lineHeightPx = 50, paragraphSpacingPx = 0)

    private val width = 400
    private val height = 600

    /** 画好的视图与它的位图（探针用视图坐标 = 位图坐标）。 */
    private class Rendered(val view: DiaryEditText, val bitmap: Bitmap) {
        val layout get() = view.layout
        fun pixel(x: Int, y: Int) = bitmap.getPixel(x, y)

        /** layout 坐标 → 视图坐标：视图上内边距就是引用底的纵向内边距。 */
        fun v(y: Int) = y + view.extendedPaddingTop
        fun mid(line: Int) = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
    }

    /**
     * 按 `EditorTextArea` 的真实接法建视图并画进 Bitmap：
     * 文本 → `SpannableBridge.toSpannable`，引用样式 → 视图级字段，末尾空行的字号 → 视图级 `textSize`。
     */
    private fun render(
        lines: List<EditorLine>,
        quoteStyle: QuoteStyle? = style,
        metricsFor: ((String) -> BlockMetrics?)? = { metrics },
    ): Rendered {
        val view = DiaryEditText(RuntimeEnvironment.getApplication())
        view.quoteStyle = quoteStyle
        view.tailQuote = SpannableBridge.tailQuoteStyle(lines, quoteStyle) != null
        view.setPadding(0, style.paddingVerticalPx, 0, style.paddingVerticalPx)
        view.setText(
            SpannableBridge.toSpannable(
                lines,
                imageProvider = null,
                containerWidthPx = width,
                metricsFor = metricsFor,
                quoteStyle = quoteStyle,
            ),
        )
        // 末尾空行（一个字符都没有）的字号只能走视图级 —— 与 `EditorTextArea.update` 同一条规则
        SpannableBridge.tailDefaults(lines, metricsFor)?.let {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSizePx.toFloat())
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        return Rendered(view, bitmap)
    }

    private fun isBox(color: Int) =
        Color.red(color) > 150 && Color.green(color) < 120 && Color.blue(color) < 120

    @Test
    fun `a quote paragraph gets a background box and a body paragraph does not`() {
        val r = render(listOf(EditorLine(text = "bbbb"), EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)))

        // 正文那一行：同一列必须是白的（没有底）
        assertTrue("正文行不该有底", !isBox(r.pixel(width / 2, r.v(r.layout.getLineTop(0) + 4))))

        // 引用那一行：行的正中间必须是底色
        val quoteLine = r.layout.getLineForOffset(r.layout.getLineStart(1) + 1)
        assertTrue(
            "引用行该有底（实际 ${Integer.toHexString(r.pixel(width / 2, r.v(r.mid(quoteLine))))}）",
            isBox(r.pixel(width / 2, r.v(r.mid(quoteLine)))),
        )
    }

    @Test
    fun `the box extends six pixels above and below the quote line and then stops`() {
        val r = render(listOf(EditorLine(text = "bbbb"), EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)))
        val line = r.layout.getLineForOffset(r.layout.getLineStart(1))
        val top = r.layout.getLineTop(line)
        val bottom = r.layout.getLineBottom(line, /* includeLineSpacing = */ false)

        assertTrue("行框里侧 2px 处是底", isBox(r.pixel(width / 2, r.v(top + 2))))
        assertTrue("行框上方 3px（在 6px 内边距里）还是底", isBox(r.pixel(width / 2, r.v(top - 3))))
        assertTrue("行框下方 3px 还是底", isBox(r.pixel(width / 2, r.v(bottom + 3))))
        // 再往外（>6px）应当是白的
        assertTrue("上方再往外（>6px）应当是白的", !isBox(r.pixel(width / 2, r.v(top - 9))))
    }

    @Test
    fun `a wrapped quote is one continuous box with rounded outer corners only`() {
        val long = "引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用引用"
        val r = render(listOf(EditorLine(text = long, blockStyle = ContentPartStyle.quote)))
        assertTrue("这一段应当折成多行（实际 ${r.layout.lineCount} 行）", r.layout.lineCount >= 2)

        // 行与行之间不能有白缝
        val firstBottom = r.layout.getLineBottom(0, /* includeLineSpacing = */ false)
        for (y in (firstBottom - 2)..(firstBottom + 2)) {
            assertTrue("折行处第 $y 行像素断了", isBox(r.pixel(width / 2, r.v(y))))
        }
        // 整块底**上沿**是圆的：底的最上一行像素里，最左那一点不该是实心（被圆角切掉）
        val boxTop = r.v(r.layout.getLineTop(0) - style.paddingVerticalPx)
        assertTrue("上沿正中是底的实心", isBox(r.pixel(width / 2, boxTop + 1)))
        assertTrue("左上角要圆掉", !isBox(r.pixel(0, boxTop)))
    }

    @Test
    fun `the quote text is inset from the left by the horizontal padding`() {
        val r = render(listOf(EditorLine(text = "MMMM", blockStyle = ContentPartStyle.quote)))
        val band = r.mid(0)

        // 找这一行里第一个"既不是底、也不是白"的像素 —— 那就是文字
        var firstText = -1
        for (x in 0 until width) {
            val c = r.pixel(x, r.v(band))
            if (!isBox(c) && c != Color.WHITE) {
                firstText = x
                break
            }
        }
        assertTrue("引用行里应当找得到文字像素", firstText >= 0)
        assertTrue(
            "文字要从 ${style.textInsetPx} px 之后开始（实际 $firstText）",
            firstText >= style.textInsetPx,
        )
    }

    /**
     * 文本层里**不能**再有 `LineBackgroundSpan`：平台先画光标、再画行背景 span
     * （`Editor.onDraw` → `drawCursor` 在前、`drawLayout` 在后），底挂在 span 上就等于
     * 把光标盖掉 —— 引用段里打上字之后光标就再也看不见了（用户 2026-10-01 报的"光标位置不对"）。
     */
    @Test
    fun `no line background span is set so the box can never cover the cursor`() {
        val spanned = SpannableBridge.toSpannable(
            listOf(EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)),
            imageProvider = null,
            containerWidthPx = width,
            metricsFor = { metrics },
            quoteStyle = style,
        )
        assertEquals(
            "引用底必须由视图自己画（画在光标之下），不能挂在文本层",
            0,
            spanned.getSpans(0, spanned.length, android.text.style.LineBackgroundSpan::class.java).size,
        )
    }

    /**
     * 末尾那个**空的引用行**也要有行首内缩 —— 否则光标停在列的最左边
     * （用户 2026-10-01 报的"引用时光标位置不对"），一打字又往右跳 16dp。
     *
     * 手法：模型有两行以上时给它补一个终止换行（[PhantomLineSpan]），
     * 它因此是一段"有字符的段落"，`LeadingMarginSpan` 照常生效。
     */
    @Test
    fun `an empty trailing quote line carries the leading margin and a phantom terminator`() {
        val lines = listOf(EditorLine(text = "上一行"), EditorLine(text = "", blockStyle = ContentPartStyle.quote))
        val spanned = SpannableBridge.toSpannable(
            lines,
            imageProvider = null,
            containerWidthPx = width,
            metricsFor = { null },
            quoteStyle = style,
        )
        // 补位换行：文本比模型多一个空段落，靠标记认出来
        assertEquals("补位换行要在文本末端留下标记", "上一行\n\n", spanned.toString())

        val margins = spanned.getSpans(0, spanned.length, android.text.style.LeadingMarginSpan::class.java)
        assertEquals("空引用行上应当正好有一个行首内缩 span", 1, margins.size)
        val span = margins.first()
        assertEquals(
            "内缩 span 要挂在那一段上（零长度在 start>0 时平台不认，见 Layout.getParagraphSpans）",
            1,
            spanned.getSpanEnd(span) - spanned.getSpanStart(span),
        )
        assertEquals("内缩值 = 引用的文字内缩", style.textInsetPx, span.getLeadingMargin(true))
        // 解析回来还是两行（补位行被丢掉，模型不多也不少；行内 run 会被规范化，只比文本与样式）
        assertEquals(
            listOf("上一行" to ContentPartStyle.body, "" to ContentPartStyle.quote),
            SpannableBridge.fromSpannable(spanned).map { it.text to it.blockStyle },
        )

        // 正文的末尾空行不该多挂这一个（它不需要内缩）
        val body = SpannableBridge.toSpannable(
            listOf(EditorLine(text = "上一行"), EditorLine(text = "")),
            imageProvider = null,
            containerWidthPx = width,
            metricsFor = { null },
            quoteStyle = style,
        )
        assertEquals("正文的末尾空行不该有内缩", 0, body.getSpans(0, body.length, android.text.style.LeadingMarginSpan::class.java).size)
        assertEquals("上一行\n", body.toString())
    }

    /** 空文档（模型只有一行）上点「引用」：走零长度 span 那条老路，提示语也得还在。 */
    @Test
    fun `a single empty quote line keeps the empty text so the placeholder survives`() {
        val lines = listOf(EditorLine(text = "", blockStyle = ContentPartStyle.quote))
        val spanned = SpannableBridge.toSpannable(
            lines,
            imageProvider = null,
            containerWidthPx = width,
            metricsFor = { null },
            quoteStyle = style,
        )
        assertEquals("文本必须还是空的（否则提示语不显示）", "", spanned.toString())
        val margins = spanned.getSpans(0, spanned.length, android.text.style.LeadingMarginSpan::class.java)
        assertEquals("空文档上的空引用行靠零长度 span 撑内缩", 1, margins.size)
        assertEquals(0, spanned.getSpanEnd(margins.first()) - spanned.getSpanStart(margins.first()))
        assertEquals(style.textInsetPx, margins.first().getLeadingMargin(true))
        // 空文本解析回来只剩"一个空行"（空文本本来就没有字符可挂 span，样式靠 keepStylesForBlankLines 续）
        assertEquals(listOf(""), SpannableBridge.fromSpannable(spanned).map { it.text })
    }

    /**
     * P1-5：空引用行与"打一个字之后"的引用底必须占**同一组行**。
     *
     * iOS 用两条用例守这一条（空着时的底 / 打一个字之后的底）；Android 原来只断言了
     * "顶部有底、下面没有"。这里逐行扫中心像素，直接比较 top / bottom。
     */
    @Test
    fun `an empty quote line and a typed quote line have the same box rows`() {
        fun boxRows(lines: List<EditorLine>): IntRange {
            val r = render(lines)
            val rows = (0 until height).filter { isBox(r.pixel(width / 2, it)) }
            assertTrue("没画出引用底：$lines", rows.isNotEmpty())
            return rows.first()..rows.last()
        }

        val empty = boxRows(
            listOf(EditorLine(text = "bbbb"), EditorLine(text = "", blockStyle = ContentPartStyle.quote)),
        )
        val typed = boxRows(
            listOf(EditorLine(text = "bbbb"), EditorLine(text = "引", blockStyle = ContentPartStyle.quote)),
        )
        assertEquals("引用底的上沿", empty.first, typed.first)
        assertEquals("引用底的下沿", empty.last, typed.last)
    }

    @Test
    fun `without a quote style nothing is drawn`() {
        val r = render(
            listOf(EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)),
            quoteStyle = null,
        )
        assertEquals("没给引用样式时一个底都不该画", Color.WHITE, r.pixel(width / 2, r.v(r.mid(0))))
    }

    /**
     * **空文档**上点「引用」：那一行一个字符都没有，段落 span 挂不上，
     * 由 `DiaryEditText.onDraw` 自己画（F1 里用户最容易撞上的那一幕）。
     */
    @Test
    fun `an empty single quote line is painted by the view itself`() {
        val r = render(listOf(EditorLine(text = "", blockStyle = ContentPartStyle.quote)))
        assertTrue("tailQuote 要打开（模型只有一行）", r.view.tailQuote)

        assertTrue(
            "末尾空行的引用底要画出来（视图级兜底），实际 ${Integer.toHexString(r.pixel(width / 2, 30))}",
            isBox(r.pixel(width / 2, 30)),
        )
        assertTrue("底只在顶部那条带里，下方仍是白的", !isBox(r.pixel(width / 2, 300)))
    }

    /** 末尾不是空的引用行时，视图一个像素都不该画（避免和段落那一路画两遍）。 */
    @Test
    fun `the view paints nothing when the trailing line is not an empty quote`() {
        val r = render(listOf(EditorLine(text = "正文")))
        assertTrue("tailQuote 要关着", !r.view.tailQuote)
        assertTrue("正文行不该有任何引用底", !isBox(r.pixel(width / 2, 30)))
    }

    /**
     * 段后距**不能**算进引用底：它是烘进 `LineHeightSpan` 的 descent 里的，
     * `getLineBottom` 拿到的值含这一段，不减掉就会"引用底比文字多出一大截"，
     * 看起来像把下一行也包进来了（用户 2026-09-28 实测："引用的作用范围现在也不对"）。
     *
     * 不去算具体的行高（各家字体的度量不一样），直接比**同一段内容在段后距 0 / 24 下的底高**：
     * 底高必须一样 —— 段后距只该影响行与行的间距，不该把底撑高。
     */
    @Test
    fun `the paragraph spacing does not stretch the box`() {
        fun boxHeight(spacing: Int): Int {
            val quoteStyle = style.copy(paragraphSpacingPx = spacing)
            val r = render(
                listOf(EditorLine(text = "bbbb"), EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)),
                quoteStyle = quoteStyle,
                metricsFor = { metrics.copy(paragraphSpacingPx = spacing) },
            )
            val rows = (0 until height).filter { isBox(r.pixel(width / 2, it)) }
            assertTrue("没画出引用底", rows.isNotEmpty())
            return rows.last() - rows.first() + 1
        }

        val withoutSpacing = boxHeight(0)
        val withSpacing = boxHeight(24)
        assertEquals("段后距把引用底撑高了", withoutSpacing, withSpacing)
    }

    /** 补位换行里那个 `\n` 不能被解析成"多出来的一行"：往返之后模型一致。 */
    @Test
    fun `the phantom terminator never leaks into the parsed model`() {
        val lines = listOf(
            EditorLine(text = "正文"),
            EditorLine(text = "引用", blockStyle = ContentPartStyle.quote),
            EditorLine(text = "", blockStyle = ContentPartStyle.quote),
        )
        val spanned = SpannableBridge.toSpannable(lines, metricsFor = { metrics }, quoteStyle = style)
        assertTrue("补位换行挂在文本末端", SpannableBridge.isPhantomLine(spanned as Spanned))
        assertEquals(
            lines.map { it.text to it.blockStyle },
            SpannableBridge.fromSpannable(spanned).map { it.text to it.blockStyle },
        )
    }
}
