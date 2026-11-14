package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.TextRun

/**
 * 从 [EditorModel] 拆出的纯函数分组；对外仍从 [EditorModel] 调用。
 */
internal object EditorPartsCodec {

    // ------------------------------------------------------------------ 行 ↔ ContentPart

    /**
     * 行模型 → `content_json` 的段落。
     *
     * 与 iOS `PartsCodec.parts(from:)` 的分组规则一致：
     *  - **连续的列表项合并成一条 `list`**，连续的待办项合并成一条 `todo`（`done` 数组并行）；
     *  - 空白行直接丢弃；
     *  - 空文本的列表项仍会产生一个空条目（用户可以看到一个空的项目）；
     *  - 普通段落按 `blockStyle` 落库，`align` 只在为 `center` 时落库。
     */
    fun linesToParts(lines: List<EditorLine>): List<ContentPart> {
        val parts = mutableListOf<ContentPart>()
        for (line in lines) {
            if (line.imageSrc != null) {
                parts.add(
                    ContentPart(
                        style = ContentPartStyle.image,
                        src = line.imageSrc,
                        w = line.imageWidth,
                        h = line.imageHeight,
                    ),
                )
                continue
            }
            when (line.marker) {
                EditorLine.MarkerKind.Bullet -> appendItem(parts, line.text, done = null)
                EditorLine.MarkerKind.Todo -> appendItem(parts, line.text, done = line.done)
                null -> {
                    if (line.text.isBlank()) continue
                    parts.add(
                        ContentPart(
                            style = line.blockStyle,
                            runs = line.spans.ifEmpty { listOf(TextRun(text = line.text)) },
                            align = if (line.align == EditorModel.ALIGN_CENTER) EditorModel.ALIGN_CENTER else null,
                        ),
                    )
                }
            }
        }
        return parts
    }

    /**
     * `content_json` 的段落 → 行模型。
     *
     * 与 iOS `PartsCodec.attributedString(from:)` 一致：
     *  - 列表/待办的每个条目各自成为一行，带对应的标记；
     *  - 段落里若含有换行，**按行拆开**（导入的旧数据可能带 `\n`），第二行起继承同一块样式；
     *  - 图片段落会产生一个空文本的图片行占位（Android 侧图片作为独立附件行渲染）。
     */
    fun partsToLines(parts: List<ContentPart>): List<EditorLine> {
        val lines = mutableListOf<EditorLine>()
        for (part in parts) {
            when (part.style) {
                ContentPartStyle.list -> part.items.orEmpty().forEach { item ->
                    lines.add(EditorLine(text = item, marker = EditorLine.MarkerKind.Bullet))
                }

                ContentPartStyle.todo -> part.items.orEmpty().forEachIndexed { index, item ->
                    lines.add(
                        EditorLine(
                            text = item,
                            marker = EditorLine.MarkerKind.Todo,
                            done = part.done.orEmpty().getOrNull(index) == true,
                        ),
                    )
                }

                ContentPartStyle.image -> lines.add(
                    EditorLine(
                        text = "",
                        marker = null,
                        imageSrc = part.src,
                        imageWidth = part.w,
                        imageHeight = part.h,
                    ),
                )

                else -> {
                    val text = part.runs?.joinToString(separator = "") { it.text } ?: part.text.orEmpty()
                    // 段落里可能有换行（导入数据）：按行拆开，样式与对齐都继承
                    val segments = text.split('\n')
                    if (segments.size == 1) {
                        lines.add(
                            EditorLine(
                                text = text,
                                blockStyle = part.style,
                                align = part.align,
                                spans = part.runs.orEmpty(),
                            ),
                        )
                    } else {
                        segments.forEach { segment ->
                            lines.add(
                                EditorLine(
                                    text = segment,
                                    blockStyle = part.style,
                                    align = part.align,
                                    spans = emptyList(), // 跨行的 run 划分失去意义，按纯文本处理
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (lines.isEmpty()) lines.add(EditorModel.EMPTY_LINE)
        return lines
    }

    private fun appendItem(parts: MutableList<ContentPart>, item: String, done: Boolean?) {
        // **只有标记、后面没有文字的行不入库**（规范 §2.3 / iOS `parts(from:)`）：
        // 那是"用户还在这一行上"（刚按了标记、或回车留下的空条目），不是一个条目。
        if (item.isBlank()) return
        val last = parts.lastOrNull()
        if (done != null && last?.style == ContentPartStyle.todo) {
            parts[parts.size - 1] = last.copy(
                items = last.items.orEmpty() + item,
                done = last.done.orEmpty() + done,
            )
        } else if (done == null && last?.style == ContentPartStyle.list) {
            parts[parts.size - 1] = last.copy(items = last.items.orEmpty() + item)
        } else if (done == null) {
            parts.add(ContentPart(style = ContentPartStyle.list, items = listOf(item)))
        } else {
            parts.add(ContentPart(style = ContentPartStyle.todo, items = listOf(item), done = listOf(done)))
        }
    }
}
