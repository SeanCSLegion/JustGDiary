package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.TextRun

/**
 * 富文本编辑器的**纯内核**：行模型 + 与 `content_json` 的双向转换 + 结构操作。
 *
 * ## 为什么要有这一层
 *
 * iOS 的 `RichTextEngine`（1,024 行）直接操作 `NSTextStorage`，段落样式挂在
 * 自定义 attributed 键上（`.diaryBlockStyle` / `.diaryDesignSize`），因此**编辑器行为
 * 无法脱离 UIKit 测试** —— 这正是方案 §5.4 里那 5 个"静默数据损坏"暗坑只在真机上才被发现的原因。
 *
 * Android 侧把状态拆成两层：
 *
 * ```
 * List<EditorLine>    ← 纯模型（本文件）：段落样式、对齐、行内 run、标记
 *        ↕ SpannableBridge（Android 适配层，M5 后半段）
 * Spannable           ← EditText 的真实文本（IME、选区、撤销由平台负责）
 * ```
 *
 * 于是"段落样式是否会被字号推断改写""列表/待办如何分合""倒序应用是否会错位"
 * 这些问题全部可以在 JVM 上断言。
 *
 * ## 行模型
 *
 * [EditorLine] 的 `text` **不含**标记符号：列表的 `• ` 与待办的 `☐ `/`☑ ` 由 [MarkerKind]
 * 与 [EditorLine.done] 表达。这与 iOS 一致 —— 那里标记是文本附件，而 `parts(from:)`
 * 会先把 `• `/`☐ `/`☑ ` 前缀剥掉再收集条目。
 */
data class EditorLine(
    val text: String = "",
    /** 段落样式：`title` / `heading` / `body` / `quote`。列表与待办由 [marker] 表达。 */
    val blockStyle: String = ContentPartStyle.body,
    /** `"center"` 或 null（与 `ContentPart.align` 同义）。 */
    val align: String? = null,
    /** 行内样式分段；空表示整行无行内样式。 */
    val spans: List<TextRun> = emptyList(),
    /** 列表或待办标记；null 表示普通段落。 */
    val marker: MarkerKind? = null,
    /** 仅待办有意义。 */
    val done: Boolean = false,
    /**
     * 图片行的图片键（`images/xxx.jpg`）；非图片行为 null。
     *
     * 图片在可编辑文本里用一个 [ImagePayloadSpan] 承载（对应 iOS 的 `PayloadAttachment`），
     * 因此 `src` 与宽高**能穿过 EditText 往返** —— 否则用户插入的图片会在下一次编辑时丢失。
     */
    val imageSrc: String? = null,
    val imageWidth: Double? = null,
    val imageHeight: Double? = null,
) {
    val isImage: Boolean get() = imageSrc != null
    val isListItem: Boolean get() = marker == MarkerKind.Bullet
    val isTodoItem: Boolean get() = marker == MarkerKind.Todo

    /** 含标记符号的显示文本（只用于阅读态与测试断言，不用于落库）。 */
    val displayText: String
        get() = when (marker) {
            MarkerKind.Bullet -> EditorModel.MARKER_BULLET + text
            MarkerKind.Todo -> (if (done) EditorModel.MARKER_TODO_DONE else EditorModel.MARKER_TODO_OPEN) + text
            null -> text
        }

    enum class MarkerKind { Bullet, Todo }
}

/** 行内样式开关。 */
enum class InlineFlag { Bold, Italic, Strike, Underline }

/** 一组行内样式的开关状态。 */
data class InlineFlags(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val underline: Boolean = false,
) {
    fun has(flag: InlineFlag): Boolean = when (flag) {
        InlineFlag.Bold -> bold
        InlineFlag.Italic -> italic
        InlineFlag.Strike -> strike
        InlineFlag.Underline -> underline
    }

    /** 把某一项设成指定值（其余不动）。 */
    fun with(flag: InlineFlag, value: Boolean): InlineFlags = when (flag) {
        InlineFlag.Bold -> copy(bold = value)
        InlineFlag.Italic -> copy(italic = value)
        InlineFlag.Strike -> copy(strike = value)
        InlineFlag.Underline -> copy(underline = value)
    }

    fun toggle(flag: InlineFlag): InlineFlags = when (flag) {
        InlineFlag.Bold -> copy(bold = !bold)
        InlineFlag.Italic -> copy(italic = !italic)
        InlineFlag.Strike -> copy(strike = !strike)
        InlineFlag.Underline -> copy(underline = !underline)
    }

    companion object {
        val NONE = InlineFlags()
    }
}

/**
 * 编辑器里的**段落格式六选一**（用户 2026-09-28 规则 2）。
 *
 * ```
 * 大标题 / 小标题 / 正文 / 引用 / 列表 / 待办     ← 任意时刻只有一个是"本行的格式"
 * ```
 *
 * 与 [EditorBlockStyle]（格式栏第一个按钮的菜单项，只有四项）区分开：
 * 这一层是**互斥规则本身**，菜单与三个按钮都落到它上面。
 */
enum class BlockFormat {
    Title,
    Heading,
    Body,
    Quote,
    List,
    Todo,
    ;

    /** 落库时写进 `content_json` 的 `style`。列表/待办有自己的名字（它们由 `marker` 表达）。 */
    val partStyle: String
        get() = when (this) {
            Title -> ContentPartStyle.title
            Heading -> ContentPartStyle.heading
            Quote -> ContentPartStyle.quote
            Body, List, Todo -> ContentPartStyle.body
        }

    /**
     * 应用本格式时是否**连带取消居中**（规则 5）。
     *
     * 居中与列表 / 待办 / 引用互斥：启用这三类时自动取消居中，且之后再取消它们
     * **不恢复**原来的居中（那是有意的 —— 见 [EditorModel.applyBlockFormat]）。
     * 大标题 / 小标题 / 正文**不**动居中：居中的标题是合法组合（规则 4）。
     */
    val cancelsCenter: Boolean
        get() = this == Quote || this == List || this == Todo

    companion object {
        /** 这一行当前是什么格式（互斥判定与"方向"判定都用它）。 */
        fun ofLine(line: EditorLine): BlockFormat = when {
            line.marker == EditorLine.MarkerKind.Bullet -> List
            line.marker == EditorLine.MarkerKind.Todo -> Todo
            line.blockStyle == ContentPartStyle.title -> Title
            line.blockStyle == ContentPartStyle.heading -> Heading
            line.blockStyle == ContentPartStyle.quote -> Quote
            else -> Body
        }

        fun ofPartStyle(style: String): BlockFormat = when (style) {
            ContentPartStyle.title -> Title
            ContentPartStyle.heading -> Heading
            ContentPartStyle.quote -> Quote
            else -> Body
        }

        fun ofMarker(kind: EditorLine.MarkerKind): BlockFormat = when (kind) {
            EditorLine.MarkerKind.Bullet -> List
            EditorLine.MarkerKind.Todo -> Todo
        }
    }
}

/** 工具栏上"当前生效的段落样式"。 */
enum class EditorBlockStyle(val partStyle: String) {
    Title(ContentPartStyle.title),
    Heading(ContentPartStyle.heading),
    Body(ContentPartStyle.body),
    Quote(ContentPartStyle.quote),
    ;

    companion object {
        /**
         * 段落样式 → **格式栏第一个按钮（`T▾`）上显示的那一项**。
         *
         * ⚠️ **列表、待办、引用、图片与未知值都按正文**：
         * - 列表 / 待办 / 引用各自有**自己的按钮**，它们不该顺手把"段落样式"按钮点亮
         *   （用户 2026-09-28 实测反馈：*"引用样式启用的时候会改变正文按钮的样式"* ——
         *   引用一开，`T▾` 就变高亮、菜单里三项一个勾都没有，看起来像"正文丢了"）；
         * - 这也与 iOS `EditorBlockStyle(partStyle:)` 的口径一致：它们有自己的持久化名字，
         *   但绘制时用正文属性。
         *
         * 于是"引用行上段落样式菜单显示正文"是**有意**的：引用只改这一个按钮之外的东西
         * （字号走 bodyMedium、底色由 `QuoteBackgroundSpan` 画）。
         */
        fun fromPartStyle(partStyle: String): EditorBlockStyle = when (partStyle) {
            ContentPartStyle.title -> Title
            ContentPartStyle.heading -> Heading
            else -> Body
        }
    }
}

object EditorModel {

    const val MARKER_BULLET = "• "
    const val MARKER_TODO_OPEN = "☐ "
    const val MARKER_TODO_DONE = "☑ "

    /** 对齐值（与 `content_json` 的 `align` 字段一致）。 */
    const val ALIGN_CENTER = "center"

    /** 空行 —— 编辑器初始状态。 */
    val EMPTY_LINE = EditorLine()

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
                            align = if (line.align == ALIGN_CENTER) ALIGN_CENTER else null,
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
        if (lines.isEmpty()) lines.add(EMPTY_LINE)
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

    // ------------------------------------------------------------------ 段落级操作

    /**
     * 把 `[indices]` 上的段落格式**先取消、再应用**成 [format]。
     *
     * ## 用户 2026-09-28 规则 2 / 3 / 5 的落点
     *
     * - **六选一互斥**（规则 2）：大标题 / 小标题 / 正文 / 引用 / 列表 / 待办 是同一格属性，
     *   应用一个就自动取消另外的 —— 实现上就是"先把本行的段落格式清成正文，再写上目标"。
     *   这与 iOS 的 `restyle(..., .body, cancelCenter: true)` 顺序一致（那边也是先重置再套）。
     * - **本行已有内容也一样**（规则 3）：作用域是"本行"（有选区时是选中的每一行），
     *   已有内容不会被跳过 —— 它会被重新排版成新格式。
     * - **取消格式只取消当前行**（规则 3 后半）：本函数只动 [indices] 覆盖到的行，
     *   绝不回头改"以前已经应用过的"行。
     * - **居中的互斥方向**（规则 5）：引用 / 列表 / 待办会**顺手取消居中**
     *   （[BlockFormat.cancelsCenter]）；反过来的"取消这三类"**不恢复**居中。
     *
     * ## 两条必须守住的实现细节
     *
     * 1. ⚠️ 一律用 `copy`：段落格式只决定"这一段是什么"，**行内样式（B/I/S/U）与它无关**，
     *    必须原样跟着走（iOS `restyle`：*"preserving bold/italic traits"*）。真机缺陷
     *    （2026-09-28）："待办 + 四种行内样式"的行切一次小标题，四种样式全丢。
     * 2. ⚠️ 按区间整体重建，而不是逐下标就地修改：逐下标遍历时下标会整体错位
     *    （iOS 为此在 `toggleQuote` 里写 `ranges.reversed()`）。
     * 3. 图片行**不参与**段落格式：它没有文字，落库时是独立的 image 段
     *    （`linesToParts` 提前 return），改写它的 blockStyle 只会在往返时凭空造出一个样式。
     */
    fun applyBlockFormat(
        lines: List<EditorLine>,
        indices: IntRange,
        format: BlockFormat,
    ): List<EditorLine> {
        val range = normalize(indices, lines.size) ?: return lines
        val cancelCenter = format.cancelsCenter
        val middle = lines.subList(range.first, range.last + 1).map { line ->
            if (line.isImage) return@map line
            // ① 先取消：把本行的六选一格式清干净（正文 + 无标记 + 不勾选；引用/列表/待办还要清居中）
            // ② 再应用：写上目标格式
            line.copy(
                blockStyle = format.partStyle,
                marker = when (format) {
                    BlockFormat.List -> EditorLine.MarkerKind.Bullet
                    BlockFormat.Todo -> EditorLine.MarkerKind.Todo
                    else -> null
                },
                done = false,
                align = if (cancelCenter) null else line.align,
            )
        }
        return lines.take(range.first) + middle + lines.drop(range.last + 1)
    }

    /**
     * 切换 [format]：已经是它了 → 变回正文；否则 → 应用它（[applyBlockFormat]）。
     *
     * 方向（"已经是它了吗"）按 iOS 分两种取法：
     * - **引用**：区间内**全部**已是引用才算"已经是"（iOS `toggleQuote`）；
     * - **列表 / 待办**：只看**光标所在行**（iOS `toggleMarker`；选区只决定范围，不决定方向）。
     *
     * 大标题 / 小标题 / 正文**不做切换**：它们是段落样式菜单的项，选中即应用
     * （菜单里当前项带勾，再点一次不该把它变成正文）。
     */
    fun toggleBlockFormat(
        lines: List<EditorLine>,
        indices: IntRange,
        format: BlockFormat,
        cursorIndex: Int = indices.first,
    ): List<EditorLine> {
        val range = normalize(indices, lines.size) ?: return lines
        val already = when (format) {
            BlockFormat.Quote -> range.all { lines.getOrNull(it)?.blockStyle == ContentPartStyle.quote }
            BlockFormat.List -> lines.getOrNull(cursorIndex)?.marker == EditorLine.MarkerKind.Bullet
            BlockFormat.Todo -> lines.getOrNull(cursorIndex)?.marker == EditorLine.MarkerKind.Todo
            BlockFormat.Title, BlockFormat.Heading, BlockFormat.Body -> false
        }
        return applyBlockFormat(lines, range, if (already) BlockFormat.Body else format)
    }

    /**
     * 段落样式菜单（大标题 / 小标题 / 正文 / 引用）的入口 —— 只应用、不切换。
     *
     * 保留这个"按 partStyle 字符串"的签名，是因为落库契约里的样式名就是它
     * （`ContentPartStyle`）；互斥规则的唯一实现是 [applyBlockFormat]。
     */
    fun setBlockStyle(lines: List<EditorLine>, indices: IntRange, style: String): List<EditorLine> =
        applyBlockFormat(lines, indices, BlockFormat.ofPartStyle(style))

    private fun normalize(indices: IntRange, size: Int): IntRange? {
        if (size == 0) return null
        val first = indices.first.coerceIn(0, size - 1)
        val last = indices.last.coerceIn(0, size - 1)
        return if (first > last) last..first else first..last
    }

    /**
     * 设置/取消居中（段落级）。
     *
     * 规则 4：本行**有内容** → 整行居中/取消居中；本行**没有内容** → 改的是"光标的位置"
     * （空段落也带 `AlignmentSpan`，光标的 x 由 `Layout.getPrimaryHorizontal` 给出，
     * 居中段落里就落在行中央）。
     *
     * 规则 5：列表 / 引用 / 待办行上**不允许**居中 —— 与格式栏"任一行是那三类就把按钮置灰"
     * 完全对齐：**整段只要有**一行是那三类，这次居中就整体不生效（而不是"能居中的那几行先居中"，
     * 那种半吊子结果既不好解释、也会让"按钮灰着却改了东西"）。取消居中（`center = false`）
     * 永远允许。
     */
    fun setAlignment(lines: List<EditorLine>, indices: IntRange, center: Boolean): List<EditorLine> {
        val range = normalize(indices, lines.size) ?: return lines
        if (center && !centerAllowed(lines, range)) return lines
        return applyToLines(lines, range) { line ->
            line.copy(align = if (center) ALIGN_CENTER else null)
        }
    }

    /** 居中与引用/列表/待办互斥（规则 5）。 */
    fun centerAllowed(line: EditorLine): Boolean =
        line.marker == null && line.blockStyle != ContentPartStyle.quote

    /** 整段区间是否**每一行**都允许居中（格式栏据此置灰居中按钮）。 */
    fun centerAllowed(lines: List<EditorLine>, indices: IntRange): Boolean =
        normalize(indices, lines.size)?.all { centerAllowed(lines[it]) } ?: false

    /** 切换 [indices] 区间上的引用样式（规则 2：先取消本行其它格式，再套引用）。 */
    fun toggleQuote(lines: List<EditorLine>, indices: IntRange): List<EditorLine> =
        toggleBlockFormat(lines, indices, BlockFormat.Quote)

    /**
     * 切换列表 / 待办标记（多行选区版）。对应 iOS `toggleMarker`：
     * 开启前先把该行重置为正文（取消标题/引用/居中/另一种标记），再次点击则取消。
     */
    fun setMarkerOnRange(
        lines: List<EditorLine>,
        indices: IntRange,
        kind: EditorLine.MarkerKind,
        /** 光标所在行；**toggle 方向只看这一行**（选区只决定范围）。 */
        cursorIndex: Int = indices.first,
    ): List<EditorLine> = toggleBlockFormat(lines, indices, BlockFormat.ofMarker(kind), cursorIndex)

    /** 切换光标所在那一行的列表 / 待办标记。 */
    fun toggleMarker(lines: List<EditorLine>, index: Int, kind: EditorLine.MarkerKind): List<EditorLine> =
        toggleBlockFormat(lines, index..index, BlockFormat.ofMarker(kind), index)

    /**
     * 回车之后的修补（规范 §3.4 的四种回车行为）。
     *
     * `EditText` 只会插入一个 `\n`，标记与段落样式都不会自己延续；这里按 iOS 的
     * `shouldChangeTextIn` 规则 + 用户 2026-09-28 规则 3 把它补齐：
     *
     * | 场景 | 结果 |
     * | --- | --- |
     * | 列表 / 待办项**有内容**时回车 | 新行继承同种标记（继续加一项），待办默认未勾选 |
     * | 列表 / 待办项**空**时回车 | **结束列表**：上一行去掉标记，新行是普通空行（iOS 同） |
     * | 大 / 小标题、引用回车 | **新行继承同一样式** —— 规则 3 的"回车之后也不取消格式，直到用户取消格式" |
     * | 居中的正文回车 | 新行**不继承居中**（居中只作用于"本行"，见下面的 ⚠️） |
     * | 正文回车 | 新行还是正文（= 没有样式要延续，行为与以前一致） |
     *
     * 三种"新行"都只继承**上一行**的状态，因此只对"新出现、且内容为空"的行生效 ——
     * 粘贴 / 删除 / 普通输入都不受影响。
     *
     * ## 与 iOS 的一处**有意差异**（D10）
     *
     * iOS 在**空引用行**上回车是"消费按键 + 结束引用"（`RichTextEngine.swift:675-682`：
     * *"空引用行回车 → 结束引用"*）。用户规则 3 说得更直接：**"回车之后也不取消格式，
     * 直到用户取消格式"** —— 引用只能由「引用」按钮取消，回车必须延续。这里按用户的口径实现；
     * "怎么退出引用"的答案因此变成"再点一次引用按钮"（或选别的段落格式）。
     *
     * 居中空行的回车在**更早一步**就被 [blocksNewline] 吃掉了（规则 4），走不到这里；
     * 万一走到（例如粘贴带进来的换行），也是"新行继承居中"，与 iOS 的
     * "段落属性随换行延续"一致。
     */
    fun repairAfterEnter(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> {
        if (lines.size <= previous.size) return lines
        val result = lines.toMutableList()
        for (index in result.indices) {
            val line = result[index]
            if (line.text.isNotEmpty() || line.marker != null || index == 0) continue
            val above = result[index - 1]
            when {
                // 空条目再回车 → 结束列表：上一行的标记去掉，这一行保持普通空行
                above.marker != null && above.text.isEmpty() ->
                    result[index - 1] = above.copy(marker = null, done = false)

                // 有条目的列表 / 待办 → 继续加一项
                above.marker != null ->
                    result[index] = line.copy(marker = above.marker, done = false)

                // 非标记行 → **块样式跟着换行延续**（规则 3：标题 / 引用"回车之后也不取消格式"）。
                //
                // iOS 是靠"段落属性挂在终止换行上"天然延续的；Android 这边
                // `Spannable` 的块 span 虽然也覆盖终止换行，但新插入的 `\n` 会让**旧 span**
                // 向后长一格，新行的行首反而没有 span 起点 —— 解析回来就是正文。
                // 所以这里在模型层显式补上（随后 [EditorTextState] 会按模型回写平台文本）。
                // 上一行是正文时不改任何东西，因此不必特判。
                //
                // ⚠️ **居中不在这里延续**（`align` 不带下去）。iOS 的段落属性确实会随换行延续，
                // 但用户 2026-09-28 的规则 4 把居中的作用域写成"本行"，且真机实测反馈
                // （"居中的作用范围不对劲……取消的时候没有真的取消，不知道是不是和换行有关系"）——
                // 就是这一条：居中一旦被换行继承，取消时只取消光标那一行，上面几行还留着居中，
                // 看起来像"没取消掉"。所以居中的延续只在"用户显式点居中"时发生。
                else ->
                    result[index] = line.copy(blockStyle = above.blockStyle)
            }
        }
        return result
    }

    /** 兼容旧名字（D9 的继承规则已经并入 [repairAfterEnter]）。 */
    fun inheritMarkersForNewLines(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> = repairAfterEnter(lines, previous)

    /**
     * "在空行上开始输入"时，把**段落样式与对齐**续上（规范 §3.2 的空行规则）。
     *
     * 富文本里空行没有字符可以挂样式：末行的空段连终止换行都没有，于是从 `EditText`
     * 解析回来的新行会退化成正文。这里按上一版的状态把它接过来 ——
     * 用户在空行上点了「大标题」再输入，得到的必须是标题。
     *
     * 只在"上一版是空行、这一版有字、且解析结果没有任何段落样式"时生效，
     * 因此不会覆盖用户粘贴进来的样式。
     */
    fun carryOverStylesWhenTyping(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> = lines.mapIndexed { index, line ->
        val before = previous.getOrNull(index)
        val startedTyping = before != null && before.text.isEmpty() && line.text.isNotEmpty()
        if (startedTyping && line.blockStyle == ContentPartStyle.body && line.align == null) {
            line.copy(blockStyle = before.blockStyle, align = before.align)
        } else {
            line
        }
    }

    /**
     * **空行**（没有文字、没有标记）的段落格式，从上一版接过来 —— [carryOverStylesWhenTyping] 的另一半。
     *
     * ## 为什么必须有这一条
     *
     * 段落 span 只能挂在字符上。文档**末行**如果是空的，它一个字符都没有（终止换行算上一段的边界），
     * 从 `EditText` 解析回来必然退化成正文 —— 于是两件本该成立的事会失效：
     *
     * 1. **把一行的文字删空**（规则 3 的"直到用户取消格式"；iOS 的 `typingAttributes`
     *    在删除之后也不重置）：删光大标题再输入，应该还是大标题；
     * 2. **空行本来就是"待输入样式"的载体**：在空行上选了大标题 / 引用之后再敲回车，
     *    这一行还是大标题 / 引用（规则 3："回车之后也不取消格式"）—— 少了这一条，
     *    回车会把这一行悄悄打回正文。
     *
     * 只在这一版解析出来是**正文且不居中**时生效，所以不会覆盖用户敲进去的或粘贴进来的样式；
     * 非空行、标记行、图片行一律不碰。**每一步文本变更后都跑**（而不是只在行数变化时），
     * 因为"样式丢了"这件事与行数变化无关。
     */
    fun keepStylesForBlankLines(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> = lines.mapIndexed { index, line ->
        val before = previous.getOrNull(index) ?: return@mapIndexed line
        if (line.isImage || line.marker != null || line.text.isNotEmpty()) return@mapIndexed line
        if (line.blockStyle == ContentPartStyle.body && line.align == null) {
            line.copy(blockStyle = before.blockStyle, align = before.align)
        } else {
            line
        }
    }

    // ------------------------------------------------------------------ 回车与空行（规则 4）

    /**
     * 是不是一行"什么内容都没有"的空行。
     *
     * **带标记的空条目（`• `）不算**：标记本身就是这一行的内容，用户还要在上面打字，
     * 回车在那里的含义是"结束列表"（[repairAfterEnter]，iOS 同）。
     */
    fun isBlankLine(line: EditorLine?): Boolean =
        line != null && line.text.isEmpty() && line.marker == null && line.imageSrc == null

    /**
     * 这一次回车要不要**吃掉**（用户 2026-09-28 定稿，与 iOS 逐条对齐）。
     *
     * | 条件 | 出处 |
     * | --- | --- |
     * | 本行是空行，且**本行居中** | iOS `shouldBlockNewline`（`RichTextView.swift:191-196`、`RichTextEngine.swift:567-571`）：*"居中的空行回车 → 直接忽略，防止空居中行无限堆叠"* —— 用户原话"**居中的时候不让有空行**" |
     * | 本行是空行，且**本行是引用** | iOS：*"空引用行回车 → 消费按键，结束引用"* —— 用户原话"**按照 iOS 的结束引用**"（见 [endQuoteOnBlockedNewline]） |
     *
     * ⚠️ **不居中的空行不受任何限制**（用户原话："但是如果不是居中的时候，是不做限制的"）：
     * 想按几个空行就按几个空行。早先按"本行空 && 上一行也空"实现过一版，已按这句推翻。
     */
    fun blocksNewline(lines: List<EditorLine>, index: Int): Boolean {
        val line = lines.getOrNull(index) ?: return false
        if (!isBlankLine(line)) return false
        return line.align == ALIGN_CENTER || line.blockStyle == ContentPartStyle.quote
    }

    /**
     * 被吃掉的那次回车**顺手要做的事**：空引用行上的回车 = iOS 的"结束引用"
     * （只重置这一行的段落样式，不动已输入的内容 —— 这一行本来就是空的）。
     *
     * 返回**同一个列表实例**表示不用回写平台文本；返回新列表表示模型变了。
     */
    fun endQuoteOnBlockedNewline(lines: List<EditorLine>, index: Int): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        if (!isBlankLine(line) || line.blockStyle != ContentPartStyle.quote) return lines
        return lines.toMutableList().also { it[index] = line.copy(blockStyle = ContentPartStyle.body) }
    }

    /**
     * 把一组行内样式**整体**应用到 `[start, end)`（不做取反）。
     *
     * 这是"待输入样式"（typing attributes）落地的动作：用户在有光标、无选区时点了 B，
     * 之后输入的字符应当加粗 —— 由调用方在文本插入后对**插入区间**调用本函数。
     */
    fun applyInlineFlags(
        line: EditorLine,
        start: Int,
        end: Int,
        flags: InlineFlags,
    ): EditorLine {
        val text = line.text
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from == to || text.isEmpty()) return line

        val runs = materializeRuns(line)
        val rebuilt = mutableListOf<TextRun>()
        var cursor = 0
        for (run in runs) {
            val runStart = cursor
            val runEnd = cursor + run.text.length
            cursor = runEnd
            if (runEnd <= from || runStart >= to) {
                rebuilt.add(run)
                continue
            }
            if (runStart < from) rebuilt.add(run.copy(text = run.text.substring(0, from - runStart)))
            val covered = run.text.substring((from - runStart).coerceAtLeast(0), minOf(to, runEnd) - runStart)
            rebuilt.add(
                run.copy(
                    text = covered,
                    bold = flags.bold || run.bold == true,
                    italic = flags.italic || run.italic == true,
                    strike = flags.strike || run.strike == true,
                    underline = flags.underline || run.underline == true,
                ),
            )
            if (runEnd > to) rebuilt.add(run.copy(text = run.text.substring(to - runStart)))
        }
        return line.copy(spans = mergeRuns(rebuilt))
    }

    /** 勾选/取消待办项。 */
    fun setTodoDone(lines: List<EditorLine>, index: Int, done: Boolean): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        if (!line.isTodoItem) return lines
        return lines.toMutableList().also { it[index] = line.copy(done = done) }
    }

    // ------------------------------------------------------------------ 行内样式

    /**
     * 在整行（或给定区间）上切换行内样式。
     *
     * 与 iOS 一致：
     *  - 已开启则关闭、未开启则开启；**是否"已开启"按区间内是否全部开启判定**（混合选区 → 开启）；
     *  - 关闭后的 run 只保留仍然开启的标记 —— 落库时不写 `false`（由 `ContentFlatten` 统一保证）。
     *
     * @param start inclusive 字符偏移
     * @param end exclusive 字符偏移；`start == end` 时不做任何修改（空选区由调用方记为"待输入样式"）
     */
    fun toggleInline(
        line: EditorLine,
        start: Int,
        end: Int,
        flag: InlineFlag,
    ): EditorLine {
        val text = line.text
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from == to) return line

        val current = activeFlags(line, from, to)
        val enable = !current.has(flag)

        val runs = materializeRuns(line)
        val rebuilt = mutableListOf<TextRun>()
        var cursor = 0
        for (run in runs) {
            val runStart = cursor
            val runEnd = cursor + run.text.length
            cursor = runEnd
            if (runEnd <= from || runStart >= to) {
                rebuilt.add(run)
                continue
            }
            // 与选区相交：先补上左侧未覆盖的部分
            if (runStart < from) {
                rebuilt.add(run.copy(text = run.text.substring(0, from - runStart)))
            }
            val covered = run.text.substring((from - runStart).coerceAtLeast(0), (minOf(to, runEnd) - runStart))
            rebuilt.add(applyFlag(run.copy(text = covered), flag, enable))
            if (runEnd > to) {
                rebuilt.add(run.copy(text = run.text.substring(to - runStart)))
            }
        }
        return line.copy(spans = mergeRuns(rebuilt), text = text)
    }

    /** 区间内**全部**开启才算开启（混合选区视为未开启，与多数编辑器的行为一致）。 */
    fun activeFlags(line: EditorLine, start: Int, end: Int): InlineFlags {
        val text = line.text
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from == to) {
            // 空选区：取光标处的样式（用于工具栏高亮）
            val at = from.coerceAtMost((text.length - 1).coerceAtLeast(0))
            return flagsAt(line, at)
        }
        var result = InlineFlags(bold = true, italic = true, strike = true, underline = true)
        for (offset in from until to) {
            val flags = flagsAt(line, offset)
            result = InlineFlags(
                bold = result.bold && flags.bold,
                italic = result.italic && flags.italic,
                strike = result.strike && flags.strike,
                underline = result.underline && flags.underline,
            )
        }
        return result
    }

    /**
     * **光标处**的样式（按钮高亮与"接下来输入的样式"都用它）。
     *
     * 规则与 UIKit 的 `typingAttributes` 一致：取光标**前一个字符**的样式
     * （在行首则取后一个）—— 于是"在加粗的字后面接着打字仍然是加粗的"。
     */
    fun caretFlags(line: EditorLine, offset: Int): InlineFlags {
        val text = line.text
        if (text.isEmpty()) return InlineFlags.NONE
        val at = offset.coerceIn(0, text.length)
        val probe = if (at == 0) 0 else at - 1
        return flagsAt(line, probe)
    }

    /** 某个字符位置的行内样式。 */
    fun flagsAt(line: EditorLine, offset: Int): InlineFlags {
        var cursor = 0
        for (run in materializeRuns(line)) {
            val end = cursor + run.text.length
            if (offset < end) {
                return InlineFlags(
                    bold = run.bold == true,
                    italic = run.italic == true,
                    strike = run.strike == true,
                    underline = run.underline == true,
                )
            }
            cursor = end
        }
        return InlineFlags.NONE
    }

    // ------------------------------------------------------------------ 行的增删（Enter / Backspace）

    /**
     * 在行的 [offset] 处拆成两行（Enter）。样式、对齐、标记与行内样式都继承给新行。
     *
     * 与 iOS 的差异（有意修正 D8）：iOS 在"空段落 + 居中"时**直接吞掉换行**
     * （`shouldBlockNewline`），用户无法在居中段落后面另起一行。这里允许换行，
     * 只是新行继承居中 —— 吞掉按键属于"按键无响应"，比多一个空行更难理解。
     */
    fun splitLine(lines: List<EditorLine>, index: Int, offset: Int): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        val at = offset.coerceIn(0, line.text.length)
        val left = line.copy(text = line.text.substring(0, at), spans = sliceRuns(line, 0, at))
        val right = line.copy(
            text = line.text.substring(at),
            spans = sliceRuns(line, at, line.text.length),
            // 待办的新行默认未勾选（与 iOS 的新待办项一致）
            done = false,
        )
        return lines.toMutableList().also {
            it[index] = left
            it.add(index + 1, right)
        }
    }

    /** 在第 [index] 行之后插入一个空行（Enter 到末尾、或列表里新增一项）。 */
    fun insertLineAfter(lines: List<EditorLine>, index: Int): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        val newLine = EditorLine(
            blockStyle = line.blockStyle,
            align = line.align,
            marker = line.marker,
            done = false,
        )
        return lines.toMutableList().also { it.add(index + 1, newLine) }
    }

    /**
     * 把第 [index] 行并入上一行（Backspace 在行首）。
     *
     * 列表/待办里删除一个空条目 = 退出列表（合并后的行不再带标记），
     * 这样"空项目符号按退格"就能回到正文 —— 与 iOS 上用户的实际预期一致。
     */
    fun mergeWithPrevious(lines: List<EditorLine>, index: Int): List<EditorLine> {
        if (index <= 0 || index >= lines.size) return lines
        val previous = lines[index - 1]
        val current = lines[index]
        val merged = if (current.text.isEmpty() && current.marker == null) {
            previous
        } else {
            previous.copy(
                text = previous.text + current.text,
                spans = mergeRuns(materializeRuns(previous) + materializeRuns(current)),
            )
        }
        return lines.toMutableList().also {
            it[index - 1] = merged
            it.removeAt(index)
        }
    }

    // ------------------------------------------------------------------ 图片

    /**
     * 图片在编辑器里的表示。
     *
     * Android 侧把图片作为**独立一行**的附件渲染（iOS 是正文里的文本附件）。
     * 落库时由 [linesToParts] 转成 `ContentPartStyle.image` —— 注意图片行不参与列表分组。
     *
     * ## 一行一张图
     *
     * 图片**永远独占一行**（这就是"每一行只允许放一张图片"的落点）：
     * - 光标所在行是**空行**（没有文字、没有标记）→ 图片就落在这张空行上，
     *   不额外留一行空白（iOS 是在光标处原地插入附件，语义相同）；
     * - 其它情况 → 图片插在光标行**下面**新开的一行，正文一个字都不动。
     *
     * 返回值里图片行的下标由 [EditorModel.imageLineIndexAfterInsert] 给出（插图后要把光标放到它下面）。
     */
    fun insertImageLine(
        lines: List<EditorLine>,
        index: Int,
        src: String,
        width: Double? = null,
        height: Double? = null,
    ): List<EditorLine> {
        val image = EditorLine(imageSrc = src, imageWidth = width, imageHeight = height)
        val target = index.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val current = lines.getOrNull(target)
        return lines.toMutableList().also { result ->
            if (current != null && isBlankEditableLine(current)) {
                result[target] = image
            } else {
                result.add((target + 1).coerceIn(0, lines.size), image)
            }
        }
    }

    /** 插图之后，图片行落在第几行（光标要去它下面那一行）。 */
    fun imageLineIndexAfterInsert(lines: List<EditorLine>, caretIndex: Int): Int {
        if (lines.isEmpty()) return 0
        val target = caretIndex.coerceIn(0, lines.size - 1)
        val current = lines.getOrNull(target)
        return if (current != null && isBlankEditableLine(current)) target else target + 1
    }

    /**
     * 是不是一张"什么内容都没有"的可编辑行。
     *
     * 图片行不算（不能在图片行上再压一张图）、带标记的空条目不替换（用户可能正要输入条目）。
     */
    private fun isBlankEditableLine(line: EditorLine): Boolean =
        line.imageSrc == null && line.marker == null && line.text.isEmpty()

    // ------------------------------------------------------------------ 倒序应用（T3）

    /**
     * 对 [indices] 里的行应用 [transform]，**从后往前**执行。
     *
     * ## 为什么必须倒序（方案 §5.4 的 T3）
     *
     * iOS 在 `toggleQuote` 里写的是 `for range in ranges.reversed()`。原因不是习惯：
     * 段落级操作会改变后续段落的位置/形态（例如把列表行摊平成多行、或把多行合并成一条列表），
     * 正序遍历时前面刚改过的行会让后面的下标整体错位，最终改错行。
     *
     * 这里的实现按 `indices.last` → `indices.first` 逆序调用 [transform]，
     * 每次都用**最新**的列表，因此即使 [transform] 改变了行数也不会错位。
     * `EditorModelTest` 里有一条对照测试：正序实现会得到错误结果。
     */
    fun applyToLines(
        lines: List<EditorLine>,
        indices: IntRange,
        transform: (EditorLine) -> EditorLine,
    ): List<EditorLine> {
        var result = lines
        for (index in indices.last downTo indices.first) {
            val line = result.getOrNull(index) ?: continue
            result = result.toMutableList().also { it[index] = transform(line) }
        }
        return result
    }

    // ------------------------------------------------------------------ run 工具

    /** 把一行摊平成 run 序列；空 spans 时用整行文本构造一个无样式 run。 */
    fun materializeRuns(line: EditorLine): List<TextRun> =
        if (line.spans.isEmpty()) {
            if (line.text.isEmpty()) emptyList() else listOf(TextRun(text = line.text))
        } else {
            val joined = line.spans.joinToString(separator = "") { it.text }
            // 存储里的 run 划分与文本不一致时（外部改过文本），以文本为准重建
            if (joined == line.text) line.spans else listOf(TextRun(text = line.text))
        }

    /** 合并相邻的同样式 run，并丢弃空 run（落库前的规范化）。 */
    fun mergeRuns(runs: List<TextRun>): List<TextRun> {
        val merged = mutableListOf<TextRun>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && sameInlineStyle(last, run)) {
                merged[merged.size - 1] = last.copy(text = last.text + run.text)
            } else {
                merged.add(run)
            }
        }
        return merged
    }

    private fun sameInlineStyle(a: TextRun, b: TextRun): Boolean =
        (a.bold == true) == (b.bold == true) &&
            (a.italic == true) == (b.italic == true) &&
            (a.strike == true) == (b.strike == true) &&
            (a.underline == true) == (b.underline == true)

    private fun applyFlag(run: TextRun, flag: InlineFlag, enable: Boolean): TextRun = when (flag) {
        InlineFlag.Bold -> run.copy(bold = if (enable) true else null)
        InlineFlag.Italic -> run.copy(italic = if (enable) true else null)
        InlineFlag.Strike -> run.copy(strike = if (enable) true else null)
        InlineFlag.Underline -> run.copy(underline = if (enable) true else null)
    }

    /** 取 [from, to) 区间的 run 切片（[splitLine] 用）。 */
    private fun sliceRuns(line: EditorLine, from: Int, to: Int): List<TextRun> {
        if (from >= to) return emptyList()
        val result = mutableListOf<TextRun>()
        var cursor = 0
        for (run in materializeRuns(line)) {
            val runStart = cursor
            val runEnd = cursor + run.text.length
            cursor = runEnd
            if (runEnd <= from || runStart >= to) continue
            val startInRun = (from - runStart).coerceAtLeast(0)
            val endInRun = (minOf(to, runEnd) - runStart).coerceAtMost(run.text.length)
            if (startInRun < endInRun) {
                result.add(run.copy(text = run.text.substring(startInRun, endInRun)))
            }
        }
        return mergeRuns(result)
    }
}

/**
 * IME 组合区闸门（方案 §5.4 的 **T4**）。
 *
 * iOS 侧**完全没有**处理 marked text（全仓无 `markedTextRange`），因为 UIKit 会在
 * 组合期间丢弃自定义 attributed 键 —— 那是"输入第二个字符起样式丢失"的根因。
 * Android 上同一问题以 **composition range** 的形式出现：
 *
 * 中文/日文输入法在候选未上屏时，`Spannable` 的这段文本是"组合中"的，
 * 此时改它的 span 会被输入法覆盖，甚至导致候选词错乱。
 *
 * 因此规则是：**格式操作跳过组合区**，只在组合结束后应用。
 * 这是净新增的工作量，不是移植量。
 */
object FormatGate {

    /**
     * 该格式操作是否可以立即应用到 `[selectionStart, selectionEnd)`。
     *
     * @param composingStart 组合区起点，-1 表示无组合
     * @param composingEnd 组合区终点，-1 表示无组合
     */
    fun canApplyFormat(
        selectionStart: Int,
        selectionEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ): Boolean {
        if (composingStart < 0 || composingEnd <= composingStart) return true
        val selStart = minOf(selectionStart, selectionEnd)
        val selEnd = maxOf(selectionStart, selectionEnd)
        // 选区完全落在组合区之外才允许
        return selEnd <= composingStart || selStart >= composingEnd
    }

    /** 组合区是否为空（无组合）。 */
    fun hasComposition(composingStart: Int, composingEnd: Int): Boolean =
        composingStart >= 0 && composingEnd > composingStart
}
