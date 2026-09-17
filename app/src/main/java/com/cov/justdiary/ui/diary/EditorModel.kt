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

/** 工具栏上"当前生效的段落样式"。 */
enum class EditorBlockStyle(val partStyle: String) {
    Title(ContentPartStyle.title),
    Heading(ContentPartStyle.heading),
    Body(ContentPartStyle.body),
    Quote(ContentPartStyle.quote),
    ;

    companion object {
        /**
         * 段落样式 → 编辑样式。**列表、待办、图片与未知值都按正文渲染**
         * （与 iOS `EditorBlockStyle(partStyle:)` 一致：它们有自己的持久化名字，
         * 但绘制时用正文属性）。
         */
        fun fromPartStyle(partStyle: String): EditorBlockStyle = when (partStyle) {
            ContentPartStyle.title -> Title
            ContentPartStyle.heading -> Heading
            ContentPartStyle.quote -> Quote
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
     * 给 `[indices]` 覆盖的区间设置段落样式。
     *
     * ## 两个必须守住的点
     *
     * 1. **先把标记行"摊平"**：列表/待办行没有段落样式（它们由标记表达），
     *    设为标题/引用时要把标记去掉；**一条含 N 个条目的列表会摊成 N 个段落**
     *    （因为存储里每个条目本来就是独立的一行）。否则会出现"带项目符号的大标题"。
     * 2. **按区间整体重建，而不是逐下标就地修改**：摊平会改变行数，
     *    逐下标遍历时后面的下标会整体错位 —— iOS 为此在 `toggleQuote` 里显式
     *    `ranges.reversed()`，但"倒序"只对**不改变行数**的操作成立。这里改成
     *    先把区间切出来、映射完再拼回，从根上避免下标漂移。
     *    `EditorModelTest` 有一条对照测试：按 iOS 那种逐下标正序改会得到错误结果。
     */
    fun setBlockStyle(lines: List<EditorLine>, indices: IntRange, style: String): List<EditorLine> {
        val range = normalize(indices, lines.size) ?: return lines
        val middle = lines.subList(range.first, range.last + 1).flatMap { line ->
            when (line.marker) {
                null -> listOf(line.copy(blockStyle = style))
                else -> lineItems(line).map { item ->
                    EditorLine(text = item.text, blockStyle = style, align = line.align)
                }
            }
        }
        return lines.take(range.first) + middle + lines.drop(range.last + 1)
    }

    /**
     * 把区间内的所有段落/条目合并成**一条**列表或待办（对应"选中几行 → 点列表按钮"）。
     *
     * 与 iOS 的差异：iOS 的列表按钮只作用于光标所在的那一行（`toggleMarker` 在行首
     * 插入一个标记附件），因此没有"多行合并成一条列表"的路径。这里补上，
     * 因为多行选区在 Android 上同样存在，而落库格式要求连续条目合并成一条。
     */
    fun setMarkerOnRange(
        lines: List<EditorLine>,
        indices: IntRange,
        kind: EditorLine.MarkerKind,
    ): List<EditorLine> {
        val range = normalize(indices, lines.size) ?: return lines
        val gathered = lines.subList(range.first, range.last + 1).flatMap { lineItems(it) }
        if (gathered.isEmpty()) return lines
        // 区间内全部已是同种标记 → 取消（摊平回正文段落）
        val source = lines.subList(range.first, range.last + 1)
        val allMarked = source.all { it.marker == kind }
        val middle = if (allMarked) {
            // 取消标记 → 摊平回正文段落
            gathered.map { EditorLine(text = it.text, blockStyle = ContentPartStyle.body) }
        } else {
            // 开启标记 → 每条一行（linesToParts 会把连续同种标记合并成一条 list/todo part）
            gathered.map { item ->
                EditorLine(
                    text = item.text,
                    marker = kind,
                    done = if (kind == EditorLine.MarkerKind.Todo) item.done else false,
                )
            }
        }
        return lines.take(range.first) + middle + lines.drop(range.last + 1)
    }

    /** 一行承载的条目（普通段落 = 1 条；列表/待办 = N 条）。 */
    private fun lineItems(line: EditorLine): List<EditorLine> = when (line.marker) {
        null -> listOf(EditorLine(text = line.text, blockStyle = line.blockStyle, align = line.align, spans = line.spans))
        else -> {
            // 编辑器里列表/待办的每个条目本来就是独立的一行；
            // 只有从 content_json 载入时才会出现"一行多条目"（这里按 text 兜底）
            listOf(line)
        }
    }

    private fun normalize(indices: IntRange, size: Int): IntRange? {
        if (size == 0) return null
        val first = indices.first.coerceIn(0, size - 1)
        val last = indices.last.coerceIn(0, size - 1)
        return if (first > last) last..first else first..last
    }

    /** 设置/取消居中（段落级）。 */
    fun setAlignment(lines: List<EditorLine>, indices: IntRange, center: Boolean): List<EditorLine> =
        applyToLines(lines, indices) { line ->
            line.copy(align = if (center) ALIGN_CENTER else null)
        }

    /**
     * 切换 [indices] 区间上的引用样式。
     *
     * 与 iOS `toggleQuote` 一致：只有区间内**全部**已是引用时才取消，否则全部设为引用。
     */
    fun toggleQuote(lines: List<EditorLine>, indices: IntRange): List<EditorLine> {
        val allQuote = indices.all { lines.getOrNull(it)?.blockStyle == ContentPartStyle.quote }
        val target = if (allQuote) ContentPartStyle.body else ContentPartStyle.quote
        return setBlockStyle(lines, indices, target)
    }

    /**
     * 切换列表 / 待办标记。对应 iOS `toggleMarker`：
     *
     * - **开启前先把该行重置为正文**（取消标题/引用/居中/另一种标记）；
     * - 再次点击同一标记则取消，恢复为正文段落。
     */
    fun toggleMarker(lines: List<EditorLine>, index: Int, kind: EditorLine.MarkerKind): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        // iOS 的列表/待办按钮只作用于光标所在行（toggleMarker 在行首插入一个标记附件）
        val toggledOff = line.marker == kind
        val updated = if (toggledOff) {
            line.copy(marker = null, done = false, blockStyle = ContentPartStyle.body)
        } else {
            line.copy(
                marker = kind,
                done = if (kind == EditorLine.MarkerKind.Todo) line.done else false,
                // 开启标记前重置块样式与居中（与 iOS 的 restyle(to: .body, cancelCenter: true) 一致）
                blockStyle = ContentPartStyle.body,
                align = null,
            )
        }
        return lines.toMutableList().also { it[index] = updated }
    }

    /**
     * 让**新建的空行**继承上一行的标记。
     *
     * `EditText` 在列表/待办行里按回车只插入 `\n`，新行没有标记前缀，列表会被拆成两条。
     * iOS 用文本附件，换行时附件不会自动继承 —— 但它的标记检测是"行首附件"，
     * 于是新行也不带标记；实际使用中用户按回车是想**继续加一项**，
     * 所以这里显式继承（有意修正，记作 D9）。
     *
     * 只对"上一版里不存在、且内容为空"的行生效，因此不会影响正常的粘贴/删除。
     */
    fun inheritMarkersForNewLines(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> {
        if (lines.size <= previous.size) return lines
        return lines.mapIndexed { index, line ->
            if (line.text.isEmpty() && line.marker == null && index > 0) {
                val above = lines[index - 1]
                if (above.marker != null) {
                    line.copy(marker = above.marker, done = false)
                } else {
                    line
                }
            } else {
                line
            }
        }
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
     */
    fun insertImageLine(
        lines: List<EditorLine>,
        index: Int,
        src: String,
        width: Double? = null,
        height: Double? = null,
    ): List<EditorLine> = lines.toMutableList().also {
        it.add(
            (index + 1).coerceIn(0, lines.size),
            EditorLine(imageSrc = src, imageWidth = width, imageHeight = height),
        )
    }

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
