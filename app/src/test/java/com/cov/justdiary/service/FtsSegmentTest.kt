package com.cov.justdiary.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CJK 切分的**语义锁定**测试。
 *
 * 这些样例是跨端的搜索契约：写入侧与查询侧共用 [FtsSegment.segment]，
 * 一旦有人"顺手"加上小写化、NFKC 归一或改区间，两端索引就会分叉 —— 因此逐条钉住。
 */
class FtsSegmentTest {

    @Test
    fun `cjk is the three documented ranges only`() {
        assertTrue("CJK 统一表意", FtsSegment.isCjk("深"))
        assertTrue("扩展 A", FtsSegment.isCjk("\u3400"))
        assertTrue("CJK 符号与标点 U+3002", FtsSegment.isCjk("。"))
        assertFalse("全角逗号 U+FF0C 不在区间内", FtsSegment.isCjk("，"))
        assertFalse("ASCII", FtsSegment.isCjk("a"))
        assertFalse("日文假名", FtsSegment.isCjk("あ"))
        assertFalse("韩文谚文", FtsSegment.isCjk("한"))
        assertFalse("空串", FtsSegment.isCjk(""))
    }

    @Test
    fun `spaces are inserted between adjacent cjk only`() {
        assertEquals("深 圳 湾", FtsSegment.segment("深圳湾"))
        assertEquals("中英边界不插空格", "abc深 圳", FtsSegment.segment("abc深圳"))
        assertEquals("中英边界不插空格", "深 圳abc", FtsSegment.segment("深圳abc"))
        assertEquals("全角逗号不算 CJK", "你 好，世 界", FtsSegment.segment("你好，世界"))
        assertEquals("句号属于 CJK 标点", "你 好 。 世 界", FtsSegment.segment("你好。世界"))
        assertEquals("ASCII 原样", "hello world", FtsSegment.segment("hello world"))
        assertEquals("", FtsSegment.segment(""))
    }

    @Test
    fun `segment is idempotent on already segmented cjk`() {
        // 已经插过空格的文本再切分不应产生双空格（空格不是 CJK）
        assertEquals("深 圳 湾", FtsSegment.segment("深 圳 湾"))
    }

    @Test
    fun `desegment removes only the injected cjk spaces`() {
        assertEquals("深圳湾", FtsSegment.desegment(FtsSegment.segment("深圳湾")))
        assertEquals("你好，世界", FtsSegment.desegment(FtsSegment.segment("你好，世界")))
        assertEquals("hello world", FtsSegment.desegment("hello world"))
        // 中英之间的空格保留
        assertEquals("深圳 abc", FtsSegment.desegment("深 圳 abc"))
        // ⚠️ desegment 无法区分"注入的空格"与"原本就在两个 CJK 之间的空格"，
        //    它一律去掉 —— iOS 同样如此。因此它**只能用于展示**，结果绝不能回写存储。
        assertEquals("深圳abc", FtsSegment.desegment("深 圳abc"))
    }

    @Test
    fun `desegment protects highlight tags`() {
        assertEquals("深圳<hl>湾</hl>", FtsSegment.desegment("深 圳 <hl>湾</hl>"))
        assertEquals("<hl>深圳</hl>", FtsSegment.desegment("<hl>深 圳</hl>"))
    }

    @Test
    fun `desegment handles combinations of marks as one character`() {
        // 字素簇：'深' + U+0301（组合尖音符）应被视为一个字符，
        // 因此它与后面的 '圳' 之间仍然插空格 —— 按 UTF-16 码元遍历会漏掉这一步。
        val combining = "深\u0301圳"
        assertEquals("深\u0301 圳", FtsSegment.segment(combining))
        assertEquals(combining, FtsSegment.desegment(FtsSegment.segment(combining)))
    }

    @Test
    fun `extended b ideographs are not cjk so they are not split`() {
        // 与 iOS 一致：U+20000 不在三个区间内 → 不插空格（这是刻意保留的缺口）
        val rare = String(Character.toChars(0x20000)) + "字"
        assertEquals(rare, FtsSegment.segment(rare))
    }
}
