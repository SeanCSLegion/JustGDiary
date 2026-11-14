package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.EditBlock

/**
 * 阅读页「复制正文 / 分享文字」的纯文本渲染。
 *
 * 与分享长图同源：标题用 [title]（由调用方用 `DateFormats.dayTitle` 备好），
 * 每个片段之间空一行；列表 / 待办保留标记，引用加 `> `，图片以 `[图片]` 占位
 * （图片本身无法进入纯文本）。
 */
object DiaryText {

    fun format(
        title: String,
        blocks: List<EditBlock>,
        partsByBlock: Map<Long, List<ContentPart>>,
    ): String {
        val paragraphs = mutableListOf<String>()
        blocks.forEach { block ->
            val parts = partsByBlock[block.id]
                ?: ContentFlatten.parseContent(block.contentJson)
            parts.forEach { part ->
                paragraphs += paragraphsOf(part)
            }
        }
        return buildString {
            append(title.trim())
            if (paragraphs.isNotEmpty()) {
                append("\n\n")
                append(paragraphs.joinToString("\n\n"))
            }
        }.trim()
    }

    private fun paragraphsOf(part: ContentPart): List<String> = when (part.style) {
        ContentPartStyle.image -> listOf(IMAGE_PLACEHOLDER)
        ContentPartStyle.list -> part.items
            ?.map { "• ${it.trim()}" }
            ?.filter { it != "• " }
            ?: paragraphOf(part)
        ContentPartStyle.todo -> {
            val items = part.items.orEmpty()
            val done = part.done.orEmpty()
            if (items.isEmpty()) {
                paragraphOf(part)
            } else {
                items.mapIndexed { index, item ->
                    val mark = if (done.getOrNull(index) == true) "☑ " else "☐ "
                    mark + item.trim()
                }
            }
        }
        ContentPartStyle.quote -> paragraphOf(part).map { "> $it" }
        else -> paragraphOf(part)
    }

    private fun paragraphOf(part: ContentPart): List<String> {
        val text = ContentFlatten.flattenPart(part).trim()
        return if (text.isEmpty()) emptyList() else listOf(text)
    }

    private const val IMAGE_PLACEHOLDER = "[图片]"
}
