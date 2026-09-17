package com.cov.justdiary.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `content_json` 的**字节级**跨端兼容测试。
 *
 * 期望值不是手写的，而是由 **iOS 真实的 `ContentPart.swift` 编译执行后产出的 golden file**
 * （`app/src/test/resources/golden/` 下的 json，生成方式见 `docs/migration-progress.md`）。
 * 这样一旦跨端格式漂移（键序、转义、数字格式、`false` 是否落库、路径归一），测试立刻失败。
 */
class ContentFormatTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name.json")) {
            "缺少 golden file: /golden/$name.json"
        }.bufferedReader().use { it.readText() }.trimEnd('\n')

    /** 与 iOS `ContentFormatTests.storedFixture()` 逐字段一致。 */
    private fun storedFixture(): List<ContentPart> = listOf(
        ContentPart(style = ContentPartStyle.title, runs = listOf(TextRun(text = "标题"))),
        ContentPart(style = ContentPartStyle.heading, runs = listOf(TextRun(text = "小标题"))),
        ContentPart(
            style = ContentPartStyle.body,
            runs = listOf(TextRun(text = "正文"), TextRun(text = "加粗", bold = true)),
            align = "center",
        ),
        ContentPart(style = ContentPartStyle.quote, runs = listOf(TextRun(text = "引用", italic = true))),
        ContentPart(style = ContentPartStyle.list, items = listOf("甲", "乙")),
        ContentPart(style = ContentPartStyle.todo, items = listOf("做", "没做"), done = listOf(true, false)),
        ContentPart(style = ContentPartStyle.image, src = "images/a.jpg", w = 300.0, h = 200.0),
    )

    /** 与 iOS `ContentFormatTests.editorFixture()` 逐字段一致。 */
    private fun editorFixture(): List<ContentPart> = listOf(
        ContentPart(style = ContentPartStyle.title, runs = listOf(TextRun(text = "标题"))),
        ContentPart(style = ContentPartStyle.heading, runs = listOf(TextRun(text = "小标题"))),
        ContentPart(
            style = ContentPartStyle.body,
            runs = listOf(
                TextRun(text = "正文"),
                TextRun(text = "加粗", bold = true),
                TextRun(text = "斜体", italic = true),
                TextRun(text = "下划线", underline = true),
                TextRun(text = "删除线", strike = true),
            ),
            align = "center",
        ),
        ContentPart(style = ContentPartStyle.quote, runs = listOf(TextRun(text = "引用"))),
        ContentPart(style = ContentPartStyle.list, items = listOf("甲", "乙")),
        ContentPart(style = ContentPartStyle.todo, items = listOf("做", "没做"), done = listOf(true, false)),
    )

    /** 与 iOS `ContentFormatTests.testLegacyV1ContentIsUpgradedOnRead` 里的字面量一致。 */
    private val v1Content: String = """
        [{"type":"h1","runs":[{"text":"标题","size":22,"bold":false}]},
         {"type":"h2","runs":[{"text":"小标题","size":18}]},
         {"type":"p","runs":[{"text":"正文","size":15,"bold":true}],"align":"center"},
         {"type":"quote","text":"引用"},
         {"type":"ul","items":["甲"]},
         {"type":"todo","items":["做"],"done":[true]},
         {"type":"img","src":"a.jpg","w":10,"h":20}]
    """.trimIndent()

    // ---------------------------------------------------------------- 编码

    @Test
    fun `serialized content is byte-identical to the iOS encoder`() {
        assertEquals(
            "stored fixture 与 iOS 编码器输出不一致",
            golden("stored"),
            ContentFlatten.serializeContent(storedFixture()),
        )
        assertEquals(
            "editor fixture 与 iOS 编码器输出不一致",
            golden("editor"),
            ContentFlatten.serializeContent(editorFixture()),
        )
    }

    @Test
    fun `envelope key order and integral doubles match iOS`() {
        val json = ContentFlatten.serializeContent(storedFixture())
        assertTrue("信封必须是 {\"parts\":…,\"v\":2}（字母序）", json.startsWith("{\"parts\":["))
        assertTrue("版本号必须存在", json.endsWith("],\"v\":2}"))
        assertTrue("整数值的 w/h 不能写成 300.0", json.contains("\"w\":300"))
        assertTrue("整数值的 h 不能写成 200.0", json.contains("\"h\":200"))
        assertFalse("非 ASCII 不应被转义", json.contains("\\u"))
        assertFalse("斜杠不应被转义", json.contains("\\/"))
    }

    @Test
    fun `no font size and no legacy type key are written`() {
        val json = ContentFlatten.serializeContent(storedFixture())
        assertFalse("run 不能再带磅值: $json", json.contains("\"size\""))
        assertFalse("v1 才把块样式写在 type 里: $json", json.contains("\"type\""))
    }

    @Test
    fun `only set inline styles are written`() {
        val json = ContentFlatten.serializeContent(
            listOf(
                ContentPart(
                    style = ContentPartStyle.body,
                    runs = listOf(TextRun(text = "普通"), TextRun(text = "粗", bold = true)),
                ),
            ),
        )
        assertFalse("未开启的标志位不应写 false: $json", json.contains("\"bold\":false"))
        assertEquals("恰好一个 run 带 bold: $json", 2, json.split("\"bold\":true").size)
    }

    /** 直接构造 `bold = false` 也不能落库 —— 写出侧必须归一。 */
    @Test
    fun `explicit false flags are normalized away on write`() {
        val json = ContentFlatten.serializeContent(
            listOf(
                ContentPart(
                    style = ContentPartStyle.body,
                    runs = listOf(TextRun(text = "关掉的", bold = false, italic = false)),
                ),
            ),
        )
        assertFalse(json, json.contains("false"))
    }

    // ---------------------------------------------------------------- 解码

    @Test
    fun `v2 document round trips fixture exactly`() {
        val parts = storedFixture()
        assertEquals(parts, ContentFlatten.parseContent(ContentFlatten.serializeContent(parts)))
    }

    @Test
    fun `legacy v1 content is upgraded on read`() {
        val parts = ContentFlatten.parseContent(v1Content)

        assertEquals(
            listOf(
                ContentPartStyle.title, ContentPartStyle.heading, ContentPartStyle.body,
                ContentPartStyle.quote, ContentPartStyle.list, ContentPartStyle.todo,
                ContentPartStyle.image,
            ),
            parts.map { it.style },
        )
        assertEquals(listOf("标题"), parts[0].runs?.map { it.text })
        assertNull("v1 的 `false` 必须读作「未设置」", parts[0].runs?.first()?.bold)
        assertEquals(true, parts[2].runs?.first()?.bold)
        assertEquals("center", parts[2].align)
        assertEquals("解码本身不动图片路径", "a.jpg", parts[6].src)
    }

    @Test
    fun `v1 content is upgraded to v2 with the same bytes as iOS`() {
        assertEquals(golden("v1_upgraded"), ImagePathUtil.normalizeContent(v1Content))
    }

    @Test
    fun `empty and broken content decodes to nothing`() {
        for (raw in listOf("", "[]", "null", "{", "not json at all")) {
            assertEquals("输入：$raw", emptyList<ContentPart>(), ContentFlatten.parseContent(raw))
        }
    }

    @Test
    fun `unknown style decodes instead of throwing`() {
        val parts = ContentFlatten.parseContent("""{"parts":[{"style":"monospaced","text":"x"}],"v":2}""")
        assertEquals(1, parts.size)
        assertEquals("monospaced", parts[0].style)
    }

    @Test
    fun `todo done list keeps false elements`() {
        val part = ContentFlatten.parseContent(
            """{"parts":[{"style":"todo","items":["做","没做"],"done":[true,false]}],"v":2}""",
        ).single()
        assertEquals(listOf("做", "没做"), part.items)
        assertEquals(listOf(true, false), part.done)
    }

    @Test
    fun `missing style falls back to body`() {
        val part = ContentFlatten.parseContent("""{"parts":[{"text":"x"}],"v":2}""").single()
        assertEquals(ContentPartStyle.body, part.style)
    }

    // ---------------------------------------------------------------- 扁平化与图片路径

    @Test
    fun `flatten part prefers runs then text then items without separators`() {
        assertEquals(
            "文本拼接不带分隔符",
            "甲乙",
            ContentFlatten.flattenPart(ContentPart(style = ContentPartStyle.list, items = listOf("甲", "乙"))),
        )
        assertEquals(
            "runs 优先于 text",
            "run",
            ContentFlatten.flattenPart(
                ContentPart(style = ContentPartStyle.body, text = "text", runs = listOf(TextRun(text = "run"))),
            ),
        )
        assertEquals(
            "",
            ContentFlatten.flattenPart(ContentPart(style = ContentPartStyle.image)),
        )
    }

    @Test
    fun `flatten content concatenates parts without separators`() {
        val json = ContentFlatten.serializeContent(
            listOf(
                ContentPart(style = ContentPartStyle.body, text = "甲"),
                ContentPart(style = ContentPartStyle.body, text = "乙"),
            ),
        )
        assertEquals("甲乙", ContentFlatten.flattenContent(json))
    }

    @Test
    fun `image src keys are normalized to the images prefix`() {
        assertEquals("images/a.jpg", ImagePathUtil.normalizeSrcKey("a.jpg"))
        assertEquals("images/a.jpg", ImagePathUtil.normalizeSrcKey("images/a.jpg"))
        assertEquals("images/b.jpg", ImagePathUtil.normalizeSrcKey("nested/dir/b.jpg"))
        // 与 iOS 一致：收集引用时**不做**路径归一（解码本身不动 src），
        // 归一发生在写出路径（normalizeContent）里。
        assertEquals(
            listOf("a.jpg"),
            ImagePathUtil.collectImageSrcs("""{"parts":[{"style":"image","src":"a.jpg"}],"v":2}"""),
        )
        // 重写时才归一
        assertEquals(
            """{"parts":[{"src":"images/a.jpg","style":"image"}],"v":2}""",
            ImagePathUtil.normalizeContent("""{"parts":[{"style":"image","src":"a.jpg"}],"v":2}"""),
        )
    }
}
