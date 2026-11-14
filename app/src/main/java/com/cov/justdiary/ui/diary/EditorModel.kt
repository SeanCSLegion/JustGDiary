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
    val text: String = "", val blockStyle: String = ContentPartStyle.body, val align: String? = null, val spans: List<TextRun> = emptyList(), val marker: MarkerKind? = null, val done: Boolean = false, val imageSrc: String? = null,
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
         * （字号走 bodyMedium、底色由 `DiaryEditText.onDraw` 画）。
         */
        fun fromPartStyle(partStyle: String): EditorBlockStyle = when (partStyle) {
            ContentPartStyle.title -> Title
            ContentPartStyle.heading -> Heading
            else -> Body
        }
    }
}

/**
 * 编辑器内核的对外门面：常量 + 同名委托。
 *
 * 实现按职责拆在 [EditorPartsCodec]（行 ↔ `content_json`）、[EditorParagraphOps]
 * （段落/标记/回车规则）、[EditorInlineOps]（run 与行内样式）与 [EditorLineOps]
 * （行的增删拆合）；调用点仍然只认 `EditorModel`，行为与拆分前一致。
 */
object EditorModel {

    const val MARKER_BULLET = "• "
    const val MARKER_TODO_OPEN = "☐ "
    const val MARKER_TODO_DONE = "☑ "

    /** 对齐值（与 `content_json` 的 `align` 字段一致）。 */
    const val ALIGN_CENTER = "center"

    /** 空行 —— 编辑器初始状态。 */
    val EMPTY_LINE = EditorLine()

    /** 实现在 [EditorPartsCodec.linesToParts]。 */
    fun linesToParts(lines: List<EditorLine>): List<ContentPart> = EditorPartsCodec.linesToParts(lines)

    /** 实现在 [EditorPartsCodec.partsToLines]。 */
    fun partsToLines(parts: List<ContentPart>): List<EditorLine> = EditorPartsCodec.partsToLines(parts)

    /** 实现在 [EditorParagraphOps.applyBlockFormat]。 */
    fun applyBlockFormat(lines: List<EditorLine>, indices: IntRange, format: BlockFormat): List<EditorLine> = EditorParagraphOps.applyBlockFormat(lines, indices, format)

    /** 实现在 [EditorParagraphOps.toggleBlockFormat]。 */
    fun toggleBlockFormat(lines: List<EditorLine>, indices: IntRange, format: BlockFormat, cursorIndex: Int = indices.first): List<EditorLine> = EditorParagraphOps.toggleBlockFormat(lines, indices, format, cursorIndex)

    /** 实现在 [EditorParagraphOps.setBlockStyle]。 */
    fun setBlockStyle(lines: List<EditorLine>, indices: IntRange, style: String): List<EditorLine> = EditorParagraphOps.setBlockStyle(lines, indices, style)

    /** 实现在 [EditorParagraphOps.setAlignment]。 */
    fun setAlignment(lines: List<EditorLine>, indices: IntRange, center: Boolean): List<EditorLine> = EditorParagraphOps.setAlignment(lines, indices, center)

    /** 实现在 [EditorParagraphOps.centerAllowed]。 */
    fun centerAllowed(line: EditorLine): Boolean = EditorParagraphOps.centerAllowed(line)

    /** 实现在 [EditorParagraphOps.centerAllowed]。 */
    fun centerAllowed(lines: List<EditorLine>, indices: IntRange): Boolean = EditorParagraphOps.centerAllowed(lines, indices)

    /** 实现在 [EditorParagraphOps.toggleQuote]。 */
    fun toggleQuote(lines: List<EditorLine>, indices: IntRange): List<EditorLine> = EditorParagraphOps.toggleQuote(lines, indices)

    /** 实现在 [EditorParagraphOps.setMarkerOnRange]。 */
    fun setMarkerOnRange(lines: List<EditorLine>, indices: IntRange, kind: EditorLine.MarkerKind, cursorIndex: Int = indices.first): List<EditorLine> = EditorParagraphOps.setMarkerOnRange(lines, indices, kind, cursorIndex)

    /** 实现在 [EditorParagraphOps.toggleMarker]。 */
    fun toggleMarker(lines: List<EditorLine>, index: Int, kind: EditorLine.MarkerKind): List<EditorLine> = EditorParagraphOps.toggleMarker(lines, index, kind)

    /** 实现在 [EditorParagraphOps.repairAfterEnter]。 */
    fun repairAfterEnter(lines: List<EditorLine>, previous: List<EditorLine>): List<EditorLine> = EditorParagraphOps.repairAfterEnter(lines, previous)

    /** 实现在 [EditorParagraphOps.inheritMarkersForNewLines]。 */
    fun inheritMarkersForNewLines(lines: List<EditorLine>, previous: List<EditorLine>): List<EditorLine> = EditorParagraphOps.inheritMarkersForNewLines(lines, previous)

    /** 实现在 [EditorParagraphOps.carryOverStylesWhenTyping]。 */
    fun carryOverStylesWhenTyping(lines: List<EditorLine>, previous: List<EditorLine>): List<EditorLine> = EditorParagraphOps.carryOverStylesWhenTyping(lines, previous)

    /** 实现在 [EditorParagraphOps.keepStylesForBlankLines]。 */
    fun keepStylesForBlankLines(lines: List<EditorLine>, previous: List<EditorLine>): List<EditorLine> = EditorParagraphOps.keepStylesForBlankLines(lines, previous)

    /** 实现在 [EditorParagraphOps.isBlankLine]。 */
    fun isBlankLine(line: EditorLine?): Boolean = EditorParagraphOps.isBlankLine(line)

    /** 实现在 [EditorParagraphOps.blocksNewline]。 */
    fun blocksNewline(lines: List<EditorLine>, index: Int): Boolean = EditorParagraphOps.blocksNewline(lines, index)

    /** 实现在 [EditorParagraphOps.endQuoteOnBlockedNewline]。 */
    fun endQuoteOnBlockedNewline(lines: List<EditorLine>, index: Int): List<EditorLine> = EditorParagraphOps.endQuoteOnBlockedNewline(lines, index)

    /** 实现在 [EditorInlineOps.applyInlineFlags]。 */
    fun applyInlineFlags(line: EditorLine, start: Int, end: Int, flags: InlineFlags): EditorLine = EditorInlineOps.applyInlineFlags(line, start, end, flags)

    /** 实现在 [EditorInlineOps.setTodoDone]。 */
    fun setTodoDone(lines: List<EditorLine>, index: Int, done: Boolean): List<EditorLine> = EditorInlineOps.setTodoDone(lines, index, done)

    /** 实现在 [EditorInlineOps.toggleInline]。 */
    fun toggleInline(line: EditorLine, start: Int, end: Int, flag: InlineFlag): EditorLine = EditorInlineOps.toggleInline(line, start, end, flag)

    /** 实现在 [EditorInlineOps.activeFlags]。 */
    fun activeFlags(line: EditorLine, start: Int, end: Int): InlineFlags = EditorInlineOps.activeFlags(line, start, end)

    /** 实现在 [EditorInlineOps.caretFlags]。 */
    fun caretFlags(line: EditorLine, offset: Int): InlineFlags = EditorInlineOps.caretFlags(line, offset)

    /** 实现在 [EditorInlineOps.flagsAt]。 */
    fun flagsAt(line: EditorLine, offset: Int): InlineFlags = EditorInlineOps.flagsAt(line, offset)

    /** 实现在 [EditorInlineOps.applyToLines]。 */
    fun applyToLines(lines: List<EditorLine>, indices: IntRange, transform: (EditorLine) -> EditorLine,): List<EditorLine> = EditorInlineOps.applyToLines(lines, indices, transform)

    /** 实现在 [EditorInlineOps.materializeRuns]。 */
    fun materializeRuns(line: EditorLine): List<TextRun> = EditorInlineOps.materializeRuns(line)

    /** 实现在 [EditorInlineOps.mergeRuns]。 */
    fun mergeRuns(runs: List<TextRun>): List<TextRun> = EditorInlineOps.mergeRuns(runs)

    /** 实现在 [EditorLineOps.splitLine]。 */
    fun splitLine(lines: List<EditorLine>, index: Int, offset: Int): List<EditorLine> = EditorLineOps.splitLine(lines, index, offset)

    /** 实现在 [EditorLineOps.insertLineAfter]。 */
    fun insertLineAfter(lines: List<EditorLine>, index: Int): List<EditorLine> = EditorLineOps.insertLineAfter(lines, index)

    /** 实现在 [EditorLineOps.mergeWithPrevious]。 */
    fun mergeWithPrevious(lines: List<EditorLine>, index: Int): List<EditorLine> = EditorLineOps.mergeWithPrevious(lines, index)

    /** 实现在 [EditorLineOps.insertImageLine]。 */
    fun insertImageLine(lines: List<EditorLine>, index: Int, src: String, width: Double? = null, height: Double? = null): List<EditorLine> = EditorLineOps.insertImageLine(lines, index, src, width, height)

    /** 实现在 [EditorLineOps.imageLineIndexAfterInsert]。 */
    fun imageLineIndexAfterInsert(lines: List<EditorLine>, caretIndex: Int): Int = EditorLineOps.imageLineIndexAfterInsert(lines, caretIndex)
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
