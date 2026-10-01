package com.cov.justdiary.ui.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选区换算测试。
 *
 * 这一层容易错的地方有三处，逐条钉住：标记前缀占位、行间的 `\n`、以及越界夹住。
 * 换算出错的表现是"光标一进列表就整体偏移一个字符"或"加粗加错位置"，都属于难查的 bug。
 */
class EditorSelectionTest {

    private val plain = listOf(
        EditorLine(text = "第一行"),
        EditorLine(text = "第二行"),
        EditorLine(text = "第三行"),
    )

    private val withMarkers = listOf(
        EditorLine(text = "正文"),
        EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
        EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo, done = true),
    )

    @Test
    fun `total length counts markers and newlines`() {
        // "第一行"(3) + \n + "第二行"(3) + \n + "第三行"(3) = 11
        assertEquals(11, EditorSelectionMap.totalLength(plain))
        // "正文"(2) + \n + "• 甲"(3) + \n + "☑ 做"(3) = 10
        assertEquals(10, EditorSelectionMap.totalLength(withMarkers))
        assertEquals(0, EditorSelectionMap.totalLength(emptyList()))
    }

    @Test
    fun `flat offsets map to line and in line offsets`() {
        assertEquals(LinePosition(0, 0), EditorSelectionMap.locate(plain, 0))
        assertEquals(LinePosition(0, 3), EditorSelectionMap.locate(plain, 3))
        // 第 4 个字符是第二行开头（跳过了 \n）
        assertEquals(LinePosition(1, 0), EditorSelectionMap.locate(plain, 4))
        assertEquals(LinePosition(1, 2), EditorSelectionMap.locate(plain, 6))
        assertEquals(LinePosition(2, 0), EditorSelectionMap.locate(plain, 8))
        assertEquals(LinePosition(2, 3), EditorSelectionMap.locate(plain, 11))
    }

    @Test
    fun `marker prefixes are excluded from the in line offset`() {
        // "正文" 占 0..2，\n 在 2；列表行从 3 开始，前缀 "• " 占 3..5
        assertEquals(LinePosition(1, 0), EditorSelectionMap.locate(withMarkers, 3))
        assertEquals("光标停在前缀里时按行首处理", LinePosition(1, 0), EditorSelectionMap.locate(withMarkers, 4))
        assertEquals("前缀之后才是第 0 个正文字符", LinePosition(1, 0), EditorSelectionMap.locate(withMarkers, 5))
        assertEquals(LinePosition(1, 1), EditorSelectionMap.locate(withMarkers, 6))
    }

    @Test
    fun `locate and flat offset are inverse`() {
        for (lines in listOf(plain, withMarkers)) {
            for (index in lines.indices) {
                for (offset in 0..lines[index].text.length) {
                    val flat = EditorSelectionMap.flatOffset(lines, index, offset)
                    val back = EditorSelectionMap.locate(lines, flat)
                    assertEquals("行 $index 偏移 $offset", LinePosition(index, offset), back)
                }
            }
        }
    }

    @Test
    fun `out of range offsets are clamped instead of throwing`() {
        assertEquals(LinePosition(0, 0), EditorSelectionMap.locate(plain, -5))
        assertEquals(LinePosition(2, 3), EditorSelectionMap.locate(plain, 999))
        assertEquals(0, EditorSelectionMap.flatOffset(plain, -3, -3))
        assertEquals(EditorSelectionMap.totalLength(plain), EditorSelectionMap.flatOffset(plain, 99, 99))
        assertEquals(LinePosition(0, 0), EditorSelectionMap.locate(emptyList(), 10))
    }

    @Test
    fun `selection normalization swaps and clamps`() {
        assertEquals(EditorSelection(0, 1, 2), EditorSelectionMap.normalize(EditorSelection(0, 1, 2), plain))
        // 反向选区要交换
        assertEquals(EditorSelection(0, 1, 2), EditorSelectionMap.normalize(EditorSelection(0, 2, 1), plain))
        // 越界要夹住
        assertEquals(EditorSelection(0, 0, 3), EditorSelectionMap.normalize(EditorSelection(0, -1, 99), plain))
        // 行号越界
        assertEquals(EditorSelection(2, 0, 0), EditorSelectionMap.normalize(EditorSelection(99, 0, 0), plain))
        assertEquals(EditorSelection.NONE, EditorSelectionMap.normalize(EditorSelection(0, 1, 2), emptyList()))
    }

    @Test
    fun `collapsed selection is detected`() {
        assertTrue(EditorSelection(1, 2, 2).isCollapsed)
        assertTrue(!EditorSelection(1, 0, 2).isCollapsed)
    }

    @Test
    fun `line range covers every paragraph the selection touches`() {
        // 规范 §3.2：段落级操作用"选中的每一整段"
        assertEquals(1..1, EditorSelection(1, 2, 2).lineRange)
        assertEquals(0..2, EditorSelection(0, 1, 3, endLineIndex = 2).lineRange)
    }

    @Test
    fun `covered ranges split a multi line selection per line`() {
        val lines = listOf(
            EditorLine(text = "第一行"),
            EditorLine(text = "第二行"),
            EditorLine(text = "第三行"),
        )
        // 从第 0 行第 1 个字符 选到 第 2 行第 2 个字符
        val selection = EditorSelection(0, 1, 2, endLineIndex = 2)
        assertEquals(
            listOf(0 to (1..3), 1 to (0..3), 2 to (0..2)),
            EditorSelectionMap.coveredRanges(selection, lines),
        )
    }

    @Test
    fun `toolbar operations cover every paragraph the selection touches`() {
        // 段落级按钮（标题 / 列表 / 引用 / 待办 / 居中）作用在选区覆盖到的**每一整段**上；
        // 光标（无选区）时就是它所在的那一行（用户 2026-09-28 规则 3）
        assertEquals(0..0, EditorSelection(0, 0, 0).lineRange)
        assertEquals(0..2, EditorSelection(0, 0, 3, endLineIndex = 2).lineRange)
    }
}
