package com.cov.justdiary.ui.diary

import android.text.Layout
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
 * **光标几何**的运行时回归（用户 2026-09-28 规则 4 与规则 6）。
 *
 * 这两条规则以前只能靠真机截图肉眼判断，这里把它们钉在**平台真正的算式**上：
 * `Editor.updateCursorPosition`（`android-37.0/android/widget/Editor.java:2449-2464`）取的是
 *
 * ```
 * top/bottom = layout.getLineTop(line) / getLineBottom(line, includeLineSpacing = false)
 * x          = layout.getPrimaryHorizontal(offset, clamped)
 * ```
 *
 * 于是"光标的字号对不对""光标有没有居中"就等于问 `StaticLayout`：
 * 光标那一行有多高、光标那个 offset 的 x 是多少。Robolectric 跑的是**真的**文本排版
 * （native graphics：真实的字体度量），所以这些断言不是模拟。
 *
 * 覆盖的三块：
 * 1. 空行（**非**末尾行）靠自己的段落 span 撑高与居中 —— `SpannableBridge` 负责；
 * 2. **末尾空行**一个字符都没有，挂不上 span，只能靠视图级 `textSize` / `gravity`
 *    （[SpannableBridge.tailDefaults] / [SpannableBridge.tailCentered]）；
 * 3. 每一段都显式挂 `AlignmentSpan`，所以视图级 gravity 只影响末尾那一行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorCursorLayoutTest {

    private val body = SpannableBridge.BlockMetrics(textSizePx = 48, lineHeightPx = 60, paragraphSpacingPx = 0)
    private val heading = SpannableBridge.BlockMetrics(textSizePx = 66, lineHeightPx = 84, paragraphSpacingPx = 0)
    private val quote = SpannableBridge.BlockMetrics(textSizePx = 42, lineHeightPx = 54, paragraphSpacingPx = 0)

    private fun metrics(style: String): SpannableBridge.BlockMetrics = when (style) {
        ContentPartStyle.title, ContentPartStyle.heading -> heading
        ContentPartStyle.quote -> quote
        else -> body
    }

    /** 按编辑器的真实接法建 layout：末尾空行的视图级字号/gravity 由桥接层的两个函数给出。 */
    private fun layoutOf(
        lines: List<EditorLine>,
        width: Int = 3000,
        metricsOverride: SpannableBridge.BlockMetrics? = null,
        quoteStyle: SpannableBridge.QuoteStyle? = null,
    ): StaticLayout {
        val spanned = SpannableBridge.toSpannable(
            lines,
            imageProvider = null,
            containerWidthPx = width,
            metricsFor = { metricsOverride ?: metrics(it) },
            quoteStyle = quoteStyle,
        )
        val tail = SpannableBridge.tailDefaults(lines, ::metrics)
        val paint = TextPaint().apply { textSize = (tail?.textSizePx ?: body.textSizePx).toFloat() }
        return StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width)
            .setAlignment(
                if (SpannableBridge.tailCentered(lines)) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL,
            )
            .build()
    }

    private fun lineOf(layout: StaticLayout, offset: Int): Int = layout.getLineForOffset(offset)

    private fun heightOf(layout: StaticLayout, line: Int): Int =
        layout.getLineBottom(line, /* includeLineSpacing = */ false) - layout.getLineTop(line)

    // ------------------------------------------------------------------ 规则 6：光标字号跟着段落样式

    @Test
    fun `an empty line that is not last gets its own line height`() {
        // "标题 / 空行 / 正文"：中间那一行是空行，但它有自己的终止换行 → 段落 span 撑得住
        val lines = listOf(
            EditorLine(text = "标题", blockStyle = ContentPartStyle.title),
            EditorLine(text = "", blockStyle = ContentPartStyle.title),
            EditorLine(text = "正文"),
        )
        val layout = layoutOf(lines)
        // 空行在文本里的偏移 = "标题\n" 之后
        val emptyLine = lineOf(layout, "标题\n".length)
        assertTrue(
            "空行也该是标题那么高（实际 ${heightOf(layout, emptyLine)}，正文 $body.lineHeightPx）",
            heightOf(layout, emptyLine) > body.lineHeightPx * 1.2f,
        )
        assertEquals(
            "空行的高度 = 它自己的段落样式（标题行同高）",
            heightOf(layout, 0),
            heightOf(layout, emptyLine),
        )
    }

    @Test
    fun `the trailing empty line follows the view level text size`() {
        // 大标题：末尾空行（一个字符都没有）只能靠视图级 textSize
        val titleTail = listOf(EditorLine(text = "标题", blockStyle = ContentPartStyle.title), EditorLine(text = "", blockStyle = ContentPartStyle.title))
        val titleLayout = layoutOf(titleTail)
        val titleLine = lineOf(titleLayout, titleLayout.text.length)
        assertTrue(
            "大标题的空行光标要有大标题那么高（实际 ${heightOf(titleLayout, titleLine)}，正文 $body.lineHeightPx）",
            heightOf(titleLayout, titleLine) > body.lineHeightPx * 1.2f,
        )

        // 正文：同一位置的光标必须是正文那么高
        val bodyTail = listOf(EditorLine(text = "标题", blockStyle = ContentPartStyle.title), EditorLine(text = ""))
        val bodyLayout = layoutOf(bodyTail)
        val bodyLine = lineOf(bodyLayout, bodyLayout.text.length)
        assertTrue(
            "切回正文之后光标要变回正文那么高（实际 ${heightOf(bodyLayout, bodyLine)}）",
            heightOf(bodyLayout, bodyLine) < heightOf(titleLayout, titleLine),
        )
    }

    @Test
    fun `the trailing empty line follows the block style of list quote and todo`() {
        // 引用是 bodyMedium（比正文小一号）：它的空行光标也要小一号
        val quoteTail = listOf(EditorLine(text = "引用", blockStyle = ContentPartStyle.quote), EditorLine(text = "", blockStyle = ContentPartStyle.quote))
        val quoteLayout = layoutOf(quoteTail)
        val quoteLine = lineOf(quoteLayout, quoteLayout.text.length)
        assertTrue(
            "引用行空行的光标应该比正文小（实际 ${heightOf(quoteLayout, quoteLine)}）",
            heightOf(quoteLayout, quoteLine) < body.lineHeightPx,
        )

        // 列表 / 待办的标记行按正文画（`effectiveBlockStyle`），所以光标是正文高度
        val listTail = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet), EditorLine(text = "", marker = EditorLine.MarkerKind.Bullet))
        val listLayout = layoutOf(listTail)
        val listLine = lineOf(listLayout, listLayout.text.length)
        assertEquals(
            "标记行不是空行：它自带 `• ` 两个字符，不靠视图级兜底",
            null,
            SpannableBridge.tailDefaults(listTail, ::metrics),
        )
        assertTrue(
            "列表空行的光标 = 正文那么高（标记行一律按正文画，实际 ${heightOf(listLayout, listLine)}）",
            heightOf(listLayout, listLine) >= body.lineHeightPx - 2,
        )
    }

    // ------------------------------------------------------------------ 规则 4：光标居中 / 左对齐

    @Test
    fun `centering an empty non last line puts the caret in the middle`() {
        val lines = listOf(
            EditorLine(text = "", align = EditorModel.ALIGN_CENTER),
            EditorLine(text = "正文"),
        )
        val layout = layoutOf(lines)
        val caret = layout.getPrimaryHorizontal(0)
        assertEquals("空行居中 → 光标的 x 落在行中央", layout.width / 2f, caret, 1f)
    }

    @Test
    fun `centering the trailing empty line puts the caret in the middle`() {
        val lines = listOf(
            EditorLine(text = "正文"),
            EditorLine(text = "", align = EditorModel.ALIGN_CENTER),
        )
        val layout = layoutOf(lines)
        assertEquals("末尾空行居中 → 视图级 gravity 生效", true, SpannableBridge.tailCentered(lines))
        assertEquals("光标的 x 落在行中央", layout.width / 2f, layout.getPrimaryHorizontal(layout.text.length), 1f)

        // 取消居中 → 回到行首（左对齐）
        val left = lines.map { it.copy(align = null) }
        val leftLayout = layoutOf(left)
        assertEquals("取消居中 → 光标回到左对齐", 0f, leftLayout.getPrimaryHorizontal(leftLayout.text.length), 1f)
    }

    /** 视图级 gravity 只影响末尾空行：正文段不能被它带着一起居中。 */
    @Test
    fun `the view level alignment never leaks into other paragraphs`() {
        val lines = listOf(
            EditorLine(text = "普通正文"),
            EditorLine(text = "", align = EditorModel.ALIGN_CENTER),
        )
        val layout = layoutOf(lines)
        assertEquals("第一段仍然左对齐", 0f, layout.getPrimaryHorizontal(0), 1f)
        assertEquals("末尾空行居中", layout.width / 2f, layout.getPrimaryHorizontal(layout.text.length), 1f)
    }

    // ------------------------------------------------------------------ 引用段的**光标 x**（用户 2026-10-01 报的"光标位置不对"）

    private val inset = 48
    private val quoteStyle = SpannableBridge.QuoteStyle(
        backgroundColor = 0,
        cornerRadiusPx = 0f,
        textInsetPx = inset,
        paddingVerticalPx = 0,
    )

    /** 光标在 (行, 行内偏移) 处的 x —— 平台的 `Editor.updateCursorPosition` 取的就是这个值。 */
    private fun caretX(layout: StaticLayout, lines: List<EditorLine>, line: Int, offset: Int): Float =
        layout.getPrimaryHorizontal(EditorSelectionMap.flatOffset(lines, line, offset))

    /**
     * **空的引用行**也要把光标放在引用的文字起点上。
     *
     * 平台对"文档末尾那个空段落"是不认段落 span 的
     * （`Layout.getParagraphSpans`：`start == end && start > 0` 直接返回空数组，`text/Layout.java:3489`），
     * 所以文档里有别的行时，末尾那个空的引用行必须补一个终止换行（[SpannableBridge.PhantomLineSpan]）
     * 才挂得上 `LeadingMarginSpan` —— 真机实测：不补就是 x=77（列最左边），补了才是 120（内缩处）。
     */
    @Test
    fun `an empty trailing quote line keeps the caret at the quote inset`() {
        val lines = listOf(EditorLine(text = "上一行"), EditorLine(text = "", blockStyle = ContentPartStyle.quote))
        val layout = layoutOf(lines, quoteStyle = quoteStyle)
        assertEquals(
            "末尾的空引用行：光标要落在内缩处",
            inset.toFloat(),
            caretX(layout, lines, 1, 0),
            1f,
        )

        // 对照：末尾是空的**正文**行时不能有内缩（光标贴列左边）
        val bodyTail = listOf(EditorLine(text = "上一行"), EditorLine(text = ""))
        val bodyLayout = layoutOf(bodyTail, quoteStyle = quoteStyle)
        assertEquals("末尾的空正文行：光标贴列左边", 0f, caretX(bodyLayout, bodyTail, 1, 0), 1f)
    }

    /**
     * **不同格式混排**时每一行的光标都要落在**这一行自己的**文字起点上
     * （用户 2026-10-01："特别是你注意不同格式混合的时候，光标位置的异常"）。
     *
     * 一行一段、段段不同（引用 / 正文 / 空的引用行 / 列表），逐行量光标的 x。
     */
    @Test
    fun `every line in a mixed format document puts the caret on its own text start`() {
        val lines = listOf(
            EditorLine(text = "引用", blockStyle = ContentPartStyle.quote),
            EditorLine(text = "正文"),
            EditorLine(text = "", blockStyle = ContentPartStyle.quote),
            EditorLine(text = "条目", marker = EditorLine.MarkerKind.Bullet),
        )
        val layout = layoutOf(lines, quoteStyle = quoteStyle)

        assertEquals("第 1 行是引用 → 内缩处", inset.toFloat(), caretX(layout, lines, 0, 0), 1f)
        assertEquals("第 2 行是正文 → 列左边", 0f, caretX(layout, lines, 1, 0), 1f)
        assertEquals("第 3 行是空的引用行 → 内缩处", inset.toFloat(), caretX(layout, lines, 2, 0), 1f)
        // 列表：标记槽的宽度来自 MarkerStyle，这里不给 markerStyle → 标记是两个字面字符，
        // 光标在"条目"之后 = 标记 + 文字宽度，只要 > 0 就说明没有被引用段的边距串味
        assertTrue(
            "第 4 行是列表 → 光标在标记 + 文字之后（实际 ${caretX(layout, lines, 3, 2)}）",
            caretX(layout, lines, 3, 2) > caretX(layout, lines, 1, 0),
        )
    }
}
