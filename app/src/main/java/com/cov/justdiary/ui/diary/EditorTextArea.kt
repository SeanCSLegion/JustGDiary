package com.cov.justdiary.ui.diary

import android.text.Editable
import android.view.KeyEvent
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.widget.AppCompatEditText
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.ui.theme.EditorTypography

/**
 * 编辑器的文本宿主：
 *
 * ```
 * List<EditorLine>  ←→  Spannable  ←→  EditText
 * ```
 *
 * ## 为什么用 `EditText` 而不是纯 Compose 的 `BasicTextField`
 *
 * 方案 §5.2 的结论：Compose 的 `TextFieldState` **不支持 `AnnotatedString` 与局部样式**
 * （Google issue 389978748），而 `OutputTransformation` 只能改文本不能加样式。
 * `EditText` + `Spannable` 与 iOS 的 `UITextView` + `NSAttributedString` 结构对称，
 * 并且 IME（中文候选、手写）、选区、`StaticLayout` 换行、平台撤销都由系统保证。
 *
 * ## 文本的单一来源
 *
 * 用户输入 → `TextWatcher` → [EditorTextState.onUserText] → 模型；
 * 工具栏操作 → 模型 → [EditorTextState.apply] 递增 `revision` → 这里重建文本并恢复光标。
 * 用 [EditorTextState.suppressWatcher] 隔断回环，避免"重建文本又触发 watcher"。
 */
@Composable
fun EditorTextArea(
    state: EditorTextState,
    placeholder: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = EditorTypography.minimumInputHeight,
    bodyTextStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    /** 图片行的位图加载器；null 时用透明占位（`src` 仍会穿过往返，不会丢图）。 */
    imageProvider: ((String) -> android.graphics.Bitmap?)? = null,
    /** 段落样式 → 字号/行高/段距（px）；编辑器与阅读态共用同一把尺子。 */
    metricsFor: ((String) -> SpannableBridge.BlockMetrics?)? = null,
    /** 引用段的外观（底色/圆角/内边距/竖条）；由界面层从主题解析，与阅读态同参数。 */
    quoteStyle: SpannableBridge.QuoteStyle? = null,
    /** 列表 / 待办标记的外观（缩进/图形/配色）；同上。 */
    markerStyle: SpannableBridge.MarkerStyle? = null,
    /** 把内部的 `EditText` 交给宿主（点空白收键盘要用它）。 */
    onViewReady: (AppCompatEditText) -> Unit = {},
    /**
     * 进入编辑时自动聚焦（规范 §3.1：0.25s 后 `becomeFirstResponder`）。
     *
     * 只在**视图创建时**做一次：工具栏改样式不会重建视图，所以用户手动收起的键盘
     * 不会被"叫回来"（iOS 为此专门维护了 `autoFocusEditor` 标志位）。
     */
    autoFocus: Boolean = true,
) {
    val density = LocalDensity.current
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 空输入时的字号：提示语与开始输入后的正文逐像素一致（提示语是 `EditText` 自己的 hint，
    // 所以它天生就落在光标所在的位置上，不再是一块"单独占位"的文本）。
    val bodyFontSize = bodyTextStyle.fontSize.takeIf { it.isSp }?.value
    // 正文的排版（px）：提示语用它，末尾空行的兜底也用它。
    val bodyMetrics = metricsFor?.invoke(ContentPartStyle.body)

    // ---- 末尾空行：光标高度与水平位置的唯一来源 ----------------------------------
    //
    // 平台的 `Editor.updateCursorPosition`（`android-37.0/android/widget/Editor.java:2449-2464`）取的是
    // `layout.getLineTop(line)` / `getLineBottom(line)` 与 `layout.getPrimaryHorizontal(offset)`；
    // 而**段落 span 只能挂在字符上** —— 文档末尾那一行如果是空的，它一个字符都没有
    // （它的终止换行属于上一段的边界），于是它的行高与对齐只能由**视图级**的
    // `textSize` 与 `gravity` 决定（`TextView.getLayoutAlignment()` 就是把 gravity 翻成
    // `Layout.Alignment`，见 `TextView.java:10916-10929`）。
    //
    // 这正是用户规则 6（切大/小标题光标跟着变）与规则 4（空行光标居中/左对齐）的落点。
    // 代价说明：**每一段都显式挂了 `AlignmentSpan`**（见 `SpannableBridge.toSpannable`），
    // 所以这里的 gravity 只会影响末尾那一行。
    val tailMetrics = SpannableBridge.tailDefaults(state.lines, metricsFor)
    val tailCentered = SpannableBridge.tailCentered(state.lines)
    // 末尾那行是"空的引用行"时，它没有字符可挂 `QuoteBackgroundSpan` —— 由 EditText 自己画
    val tailQuote = SpannableBridge.tailQuoteStyle(state.lines, quoteStyle)

    // 提示语**永远是正文字号**（iOS 的 placeholder 用正文曲线画：`RichTextView.swift:69-77`），
    // 所以给它挂一个正文的排版 span —— 否则把空行改成大标题时 base 字号变大、提示语跟着变巨。
    val hintText: CharSequence = remember(placeholder, bodyMetrics, tailQuote) {
        android.text.SpannableString(placeholder).apply {
            if (bodyMetrics != null) {
                setSpan(
                    SpannableBridge.BlockMetricsSpan(
                        textSizePx = bodyMetrics.textSizePx,
                        lineHeightPx = bodyMetrics.lineHeightPx,
                        paragraphSpacingPx = 0,
                    ),
                    0,
                    length,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                )
            }
            // 空文档上先点了「引用」：提示语要跟着引用段的左边距走，
            // 否则它会贴在那块底的边上（正文有 `LeadingMarginSpan`，提示语是另一套 layout）
            if (tailQuote != null) {
                setSpan(
                    android.text.style.LeadingMarginSpan.Standard(tailQuote.textInsetPx),
                    0,
                    length,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                )
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        // 图片按可用宽度等比缩放（不再画成写死的正方形，否则非正方形图片会被拉伸）
        val imageWidthPx = with(density) { maxWidth.toPx().toInt() }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight),
            factory = { context ->
                DiaryEditText(context).apply {
                    isSingleLine = false
                    background = null
                    // 上下各留出引用底的纵向内边距：引用段的圆角底要画到行框外 6dp
                    // （`QuoteBackgroundSpan`），视图不留这点地方的话，**第一行**的引用底
                    // 会被视图上沿裁掉一条平边。左右仍是 0 —— 正文与光标贴边（真机实测 x=77）。
                    val quotePadV = with(density) { BlockGeometry.TextInset.toPx() }.toInt()
                    setPadding(0, quotePadV, 0, quotePadV)
                    // 编辑器自己滚（外层不再套 verticalScroll）：Android 会在视口变化
                    // （键盘弹起 / 换行）时把光标滚进可见区，这正是"光标不被挡"的平台做法。
                    setVerticalScrollBarEnabled(false)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    // 平台撤销（API 23+）与 IME 的自动大小写都保留默认行为
                    setText(
                        SpannableBridge.toSpannable(
                            state.lines,
                            imageProvider,
                            imageWidthPx,
                            metricsFor,
                            quoteStyle,
                            markerStyle,
                        ),
                    )
                    setSelection(text?.length ?: 0)
                    // 光标移动（不改文字）也要更新工具栏：`onSelectionChanged` 是
                    // `TextView` 的 protected 回调，只能靠子类转发。少了它，
                    // 工具栏的高亮与"接下来输入的样式"会停留在上一个位置（真机缺陷）。
                    onSelectionChangedListener = {
                        if (!state.suppressWatcher) {
                            val spanned = text
                            if (spanned != null) {
                                state.onSelectionChanged(currentSelection(spanned, selectionStart, selectionEnd))
                            }
                        }
                    }
                    // 规则 4：本行是空行时，这次回车不算数（判断要读模型，由 state 回答）。
                    // 按键这一路在这里就被吃掉；输入法直接 commitText("\n") 的那一路走
                    // `EditorTextState.onUserText` 的退回分支。
                    shouldBlockNewline = { state.blocksNewlineHere() }
                    onViewReady(this)
                    if (autoFocus) {
                        postDelayed(
                            {
                                requestFocus()
                                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                    as? android.view.inputmethod.InputMethodManager
                                // `SHOW_IMPLICIT`（= 0）自 API 34 起被标记废弃，语义就是"不加额外标志"
                                imm?.showSoftInput(this, 0)
                            },
                            250,
                        )
                    }
                    addTextChangedListener(
                        object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                // 插入区间：`count` 个字符从 `start` 开始（删除时 count = 0）
                                state.onTextReplaced(
                                    if (count > 0) start until (start + count) else IntRange.EMPTY,
                                )
                            }

                            override fun afterTextChanged(s: Editable?) {
                                if (state.suppressWatcher) return
                                val spanned = s ?: return
                                state.onUserText(spanned)
                                state.onSelectionChanged(currentSelection(spanned, selectionStart, selectionEnd))
                                state.updateComposition(spanned)
                                state.consumeInsertedRange()
                            }
                        },
                    )
                }
            },
            update = { view ->
                // 提示语与字号跟随主题 / 系统字号（每次组合都同步，代价可以忽略）
                view.hint = hintText
                view.setHintTextColor(placeholderColor.toArgb())
                // 末尾空行：base 字号 = 那一行的段落字号（规则 6），gravity = 那一行的对齐（规则 4）。
                // 两者都只在**真的变了**的时候才设：`setTextSize` / `setGravity` 都会让
                // TextView 丢掉 layout 重建一次（光标会闪），不能每次组合都无脑写。
                if (tailMetrics != null) {
                    val targetPx = tailMetrics.textSizePx.toFloat()
                    if (view.textSize != targetPx) {
                        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, targetPx)
                    }
                } else if (bodyFontSize != null) {
                    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, bodyFontSize)
                }
                val targetGravity = if (tailCentered) {
                    android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                } else {
                    android.view.Gravity.TOP or android.view.Gravity.START
                }
                if (view.gravity != targetGravity) view.gravity = targetGravity
                view.tailQuoteStyle = tailQuote
                // 回写条件 = "模型被工具栏改过"（版本号变了）**或**整个状态对象换了
                // （放弃修改 / 换了一个片段：`AndroidView` 的节点会被复用、`factory` 不会再跑，
                //  只比版本号会在两边都是 0 时判成"不用回写"，内容就回不去了 —— 真机缺陷）。
                val stamp = view.tag as? EditorViewStamp
                val needsRewrite = stamp == null ||
                    stamp.state !== state ||
                    stamp.revision != state.revision
                if (needsRewrite) {
                    view.tag = EditorViewStamp(state, state.revision)
                    val caret = EditorSelectionMap.flatOffset(
                        state.lines,
                        state.selection.lineIndex,
                        state.selection.start,
                    )
                    state.suppressWatcher = true
                    view.setText(
                        SpannableBridge.toSpannable(
                            state.lines,
                            imageProvider,
                            imageWidthPx,
                            metricsFor,
                            quoteStyle,
                            markerStyle,
                        ),
                    )
                    val safe = caret.coerceIn(0, view.text?.length ?: 0)
                    view.setSelection(safe)
                    state.suppressWatcher = false
                    // 工具栏改完模型：顺带把光标带进可见区（比如列表续项之后）
                    view.post { view.bringPointIntoView(view.selectionEnd) }
                }
            },
        )
    }
}

/** 从 `EditText` 的扁平选区换算出行内选区。 */
private fun currentSelection(text: Spanned, start: Int, end: Int): EditorSelection {
    val lines = SpannableBridge.fromSpannable(text)
    val a = EditorSelectionMap.locate(lines, start)
    val b = EditorSelectionMap.locate(lines, end)
    // 跨行选区保留"到哪一行"：段落级操作要覆盖选中的**每一整段**（规范 §3.2）
    return if (a.lineIndex == b.lineIndex) {
        EditorSelection(a.lineIndex, a.offsetInLine, b.offsetInLine)
    } else {
        EditorSelection(a.lineIndex, a.offsetInLine, b.offsetInLine, b.lineIndex)
    }
}

/**
 * `AndroidView` 上的回写标记：记住"上一次灌进去的是哪个状态对象、哪个版本"。
 *
 * 两个都要：同一状态内工具栏改模型是版本号变化；换状态对象（放弃修改 / 换片段）时
 * 版本号可能**恰好相同**，只看版本号就会漏掉回写。
 */
private class EditorViewStamp(val state: EditorTextState, val revision: Int)

/**
 * 带选区回调的 `EditText`。
 *
 * `TextView` 把"光标/选区变了"这件事只发给 protected 的 `onSelectionChanged`，
 * 没有公开监听器；而工具栏的高亮与"接下来输入的样式"都要跟着光标走，因此必须转发出来。
 *
 * **文本从顶部开始**（[gravity]）：`EditText` 从宿主主题继承来的 gravity 会把首行
 * 垂直居中，而输入区的最小高度是 160dp —— 真机实测：提示语画在输入区顶部、光标却停在
 * 正中间（两者相差 65dp），看起来就像"提示语单独占了一块地方"。
 */
internal class DiaryEditText(context: android.content.Context) : AppCompatEditText(context) {

    init {
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
    }

    var onSelectionChangedListener: (() -> Unit)? = null

    /**
     * 这一次回车要不要**吃掉**（规则 4：本行是空行时不换行）。判断要读行模型，
     * 所以由 [EditorTextState] 回答。
     *
     * 软键盘的回车在多数输入法里是 `sendKeyEvent(KEYCODE_ENTER)`，最终落到
     * `dispatchKeyEvent`；少数输入法直接 `commitText("\n")`，那一路由
     * [EditorTextState.onUserText] 把换行退回去。
     */
    var shouldBlockNewline: (() -> Boolean)? = null

    /**
     * 末尾空行是引用时要画的那块底（[SpannableBridge.tailQuoteStyle]）。
     *
     * 前面每一段的引用底都由 `QuoteBackgroundSpan` 画 —— 但那个 span 得挂在字符上，
     * 而文档末尾的空行一个字符都没有。不补这一笔，用户在空行上点「引用」就看不到任何反馈
     * （R30 记的 F1 最典型的那一幕）。几何与 span 共用 [SpannableBridge.QuoteStyle.drawBox]。
     */
    var tailQuoteStyle: SpannableBridge.QuoteStyle? = null

    private val tailBoxPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
    }
    private val tailBoxPath = android.graphics.Path()
    private val tailBoxRadii = FloatArray(8)

    override fun onDraw(canvas: android.graphics.Canvas) {
        val style = tailQuoteStyle
        val l = layout
        if (style != null && l != null && l.lineCount > 0) {
            val line = l.lineCount - 1
            tailBoxPaint.color = style.backgroundColor
            canvas.save()
            // 与 TextView 自己画文本时同一套平移（gravity 是 TOP，所以没有额外的 voffset）
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            style.drawBox(
                canvas = canvas,
                paint = tailBoxPaint,
                path = tailBoxPath,
                radii = tailBoxRadii,
                left = 0f,
                top = l.getLineTop(line).toFloat(),
                right = l.width.toFloat(),
                bottom = l.getLineBottom(line, false).toFloat(),
                // 末尾这一行自成一块：两端都要圆（`drawBox` 会自己加上下内边距与竖条）
                first = true,
                last = true,
            )
            canvas.restore()
        }
        super.onDraw(canvas)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
            shouldBlockNewline?.invoke() == true
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChangedListener?.invoke()
    }
}

/**
 * 编辑器的可观察状态。
 *
 * 同时持有"模型"（[lines]）与"平台文本的版本号"（[revision]），
 * 并用 [suppressWatcher] 隔断"回写文本 → 触发 watcher → 又改模型"的回环。
 */
class EditorTextState(initialLines: List<EditorLine>) {

    var lines: List<EditorLine> by mutableStateOf(initialLines)
        private set

    var selection: EditorSelection by mutableStateOf(EditorSelection.NONE)
        private set

    /**
     * **待输入的行内样式**（对应 iOS 的 `typingAttributes`）。
     *
     * 规范 §3.2：光标（无选区）时点 B/I/S/U 只改"接下来输入的字符"，已输入的一个都不动；
     * 光标落到加粗的字后面时，接下来输入的自然也是加粗的。落地点在 [onUserText]：
     * 文本插入后对**插入区间**加 span。
     */
    var pendingFlags: InlineFlags by mutableStateOf(InlineFlags.NONE)
        private set

    /** 最近一次文本变更里被插入的区间（由 `TextWatcher.onTextChanged` 给出）。 */
    private var insertedRange: IntRange = IntRange.EMPTY

    internal fun onTextReplaced(range: IntRange) {
        insertedRange = range
    }

    internal fun consumeInsertedRange() {
        insertedRange = IntRange.EMPTY
    }

    /** 是否正在 IME 组合中（组合期间禁用格式操作，见 [FormatGate]）。 */
    var composing: Boolean by mutableStateOf(false)
        private set

    /** 平台文本的版本号；工具栏改模型后递增，驱动 `EditText` 重建文本。 */
    var revision: Int by mutableIntStateOf(0)
        private set

    /** 内部：回写文本时置位，避免 watcher 把重建结果当成用户输入。 */
    internal var suppressWatcher: Boolean = false

    /**
     * 用户输入：采纳平台文本，并修补"回车打断列表"的情况。
     *
     * `EditText` 在列表行里按下回车只会插入 `\n`，新行没有任何标记前缀，
     * 于是列表会被拆成两条。这里用 [EditorModel.inheritMarkersForNewLines] 让新建的空行
     * 继承上一行的标记 —— 与 iOS 的标记附件在换行时被继承等价。
     */
    fun onUserText(spanned: Spannable) {
        // ⓪ 规则 4：本行是空行时这次回车不算数。按键那一路已经在 `DiaryEditText.dispatchKeyEvent`
        //    被吃掉，这里是**输入法直接 commitText("\n")** 的那一路 —— 把这次换行退回去：
        //    模型一个字节都不动，只递增版本号让 EditText 按旧模型重写一遍文本。
        if (isBlockedNewlineInsertion(spanned)) {
            // 空引用行上的这次回车同时"结束引用"（iOS）；模型变了的话 endQuoteIfNeeded 已经 +1
            endQuoteIfNeeded()
            revision += 1
            return
        }
        // ① 待输入的行内样式：落在**插入的那几个字符**上（组合中不动 —— 改 span 会打断候选）
        val range = insertedRange
        if (!composing && !range.isEmpty() && pendingFlags != InlineFlags.NONE) {
            applyPendingFlags(spanned, range)
        }
        // ② 解析 + 三步修补，顺序不能换：
        //    ③ 空行把上一版的段落格式接回来（末行没有字符可挂 span，不接就掉回正文）
        //    ④ 给新出现的空行补标记 / 段落格式（回车续项、标题引用延续）
        //    ⑤ 在空行上开始输入时把格式续上
        val parsed = SpannableBridge.fromSpannable(spanned)
        val repaired = EditorModel.carryOverStylesWhenTyping(
            EditorModel.repairAfterEnter(
                EditorModel.keepStylesForBlankLines(parsed, previous = lines),
                previous = lines,
            ),
            previous = lines,
        )
        lines = repaired
        selection = selection.coerceTo(lines)
        // ③ 修补改了模型就必须**回写平台文本**：新行的 `• `/`☐ ` 前缀与段落样式都挂在
        //    `Spannable` 上，不写回去用户看不到标记（真机缺陷：回车后续项没有项目符号）。
        //    只在真的改了东西时递增版本号，普通打字不会触发重写。
        if (repaired != parsed) revision += 1
    }

    /**
     * 这一次文本变更是不是"在该被吃掉的空行上行尾敲了一个回车"
     * （[EditorModel.blocksNewline]：居中的空行 / 空的引用行）。
     *
     * 行中回车（把一个段落切成两段）不在此列 —— 那不是"多出一个空行"。
     */
    private fun isBlockedNewlineInsertion(spanned: Spannable): Boolean {
        val range = insertedRange
        if (range.isEmpty() || range.last != range.first) return false
        if (range.first < 0 || range.first >= spanned.length) return false
        if (spanned[range.first] != '\n') return false
        if (!selection.isCollapsed) return false
        val index = selection.lineIndex
        val line = lines.getOrNull(index) ?: return false
        // 必须插在本行行尾（行内偏移 = 本行文字长度）
        if (selection.start != line.text.length) return false
        return EditorModel.blocksNewline(lines, index)
    }

    /**
     * 规则 4 / D10：**此刻**按回车要不要吃掉（按键路径用；判断依据与输入法路径同源）。
     *
     * 会吃掉的两类（用户 2026-09-28 定稿，与 iOS 逐条对齐）：
     * - **居中的空行**（不让堆空行）；**不居中的空行不受任何限制**；
     * - **空的引用行** —— 顺手**结束引用**（iOS："空引用行回车 → 消费按键 + 结束引用"）。
     *   这一步会改模型，所以这里顺带递增版本号，让 EditText 按新模型回写。
     *
     * 有选区时回车是"用换行替换选区"，不属于"空行上多敲一个回车"，照常放行。
     */
    fun blocksNewlineHere(): Boolean {
        if (!selection.isCollapsed) return false
        if (!EditorModel.blocksNewline(lines, selection.lineIndex)) return false
        endQuoteIfNeeded()
        return true
    }

    /** 空引用行上被吃掉的那次回车 = 结束引用（模型真的变了才请求回写）。 */
    private fun endQuoteIfNeeded() {
        val ended = EditorModel.endQuoteOnBlockedNewline(lines, selection.lineIndex)
        if (ended !== lines) {
            lines = ended
            revision += 1
        }
    }

    /**
     * 把待输入样式写进**平台文本**（直接对 Spannable 加 span，不改文本：
     * 改文本会再触发一次 watcher，也会打断输入法）。
     */
    private fun applyPendingFlags(spanned: Spannable, range: IntRange) {
        val start = range.first.coerceIn(0, spanned.length)
        val end = (range.last + 1).coerceIn(start, spanned.length)
        if (start >= end) return
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        if (pendingFlags.bold || pendingFlags.italic) {
            val style = when {
                pendingFlags.bold && pendingFlags.italic -> android.graphics.Typeface.BOLD_ITALIC
                pendingFlags.bold -> android.graphics.Typeface.BOLD
                else -> android.graphics.Typeface.ITALIC
            }
            spanned.setSpan(android.text.style.StyleSpan(style), start, end, flag)
        }
        if (pendingFlags.strike) {
            spanned.setSpan(android.text.style.StrikethroughSpan(), start, end, flag)
        }
        if (pendingFlags.underline) {
            spanned.setSpan(android.text.style.UnderlineSpan(), start, end, flag)
        }
    }

    /**
     * 工具条的 B/I/S/U：
     * - **有选区** → 整个选区统一一个方向（全带 → 取消，否则 → 加上，规范 §3.2）；
     * - **无选区** → 只改 [pendingFlags]，已输入的字一个都不动。
     */
    fun toggleInline(flag: InlineFlag) {
        if (composing) return
        if (selection.isCollapsed) {
            // 无选区：只改"接下来输入的样式"，已输入的字一个都不动
            pendingFlags = pendingFlags.toggle(flag)
            return
        }
        // 有选区：整个选区统一一个方向（全带 → 取消，否则 → 加上）
        val enable = !selectionFlags().has(flag)
        val covered = EditorSelectionMap.coveredRanges(selection, lines)
        apply { current ->
            current.mapIndexed { index, item ->
                val range = covered.firstOrNull { it.first == index }?.second
                if (range == null || range.first >= range.last) {
                    item
                } else {
                    EditorModel.toggleInline(item, range.first, range.last, flag)
                }
            }
        }
        pendingFlags = pendingFlags.with(flag, enable)
    }

    /** 整个选区是否**都带**某一项样式（混合选区按"未开启"处理，与 iOS `activeStyles()` 一致）。 */
    fun selectionFlags(): InlineFlags {
        if (selection.isCollapsed) return pendingFlags
        var result: InlineFlags? = null
        for ((index, range) in EditorSelectionMap.coveredRanges(selection, lines)) {
            if (range.first >= range.last) continue
            val line = lines.getOrNull(index) ?: continue
            val flags = EditorModel.activeFlags(line, range.first, range.last)
            result = if (result == null) {
                flags
            } else {
                InlineFlags(
                    bold = result.bold && flags.bold,
                    italic = result.italic && flags.italic,
                    strike = result.strike && flags.strike,
                    underline = result.underline && flags.underline,
                )
            }
        }
        return result ?: InlineFlags.NONE
    }

    /** 工具栏操作：在模型上做变换，然后请 `EditText` 重建文本。 */
    fun apply(transform: (List<EditorLine>) -> List<EditorLine>) {
        // 组合进行中不做结构修改（与 FormatGate 同一条规则）
        if (composing) return
        lines = transform(lines).ifEmpty { listOf(EditorModel.EMPTY_LINE) }
        selection = selection.coerceTo(lines)
        revision += 1
    }

    /**
     * 插入一张图片，并把光标放到**图片之后的新行**上。
     *
     * 对应 iOS `RichTextEngine.insertImage`：那边插入的是"附件 + 一个 `\n`"，然后把
     * `selectedRange` 设到附件之后 —— 效果就是**光标落在图片下面那一行**，接着输入的字
     * 出现在图片之后而不是之前（真机实测：早先的实现把图片插在光标行之后、光标却留在原行，
     * 于是"插图之后继续打字"会打在图片上面）。
     *
     * 图片行本身由 [EditorModel.insertImageLine] 建（它是"独立一行"的附件 → 一行一张图）。
     */
    fun insertImage(src: String, width: Double?, height: Double?) {
        if (composing) return
        val index = selection.lineIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val imageLine = EditorModel.imageLineIndexAfterInsert(lines, index)
        val next = EditorModel.insertImageLine(lines, index, src, width, height)
        // 光标要去图片**下面**那一行。没有就补一个空行 ——
        // 这样"插图后直接打字"永远打在图片下面（与 iOS 一致）。
        val caretLine = imageLine + 1
        val withCaret = if (caretLine <= next.lastIndex) {
            next
        } else {
            next + EditorModel.EMPTY_LINE
        }
        lines = withCaret.ifEmpty { listOf(EditorModel.EMPTY_LINE) }
        val caret = caretLine.coerceIn(0, lines.lastIndex)
        selection = EditorSelection(caret, 0, 0)
        pendingFlags = EditorModel.caretFlags(currentLine, selection.start)
        revision += 1
    }

    /** 直接替换整个模型（载入已有片段 / 撤销）。 */
    fun replace(newLines: List<EditorLine>, caret: EditorSelection = selection) {
        lines = newLines.ifEmpty { listOf(EditorModel.EMPTY_LINE) }
        selection = caret.coerceTo(lines)
        revision += 1
    }

    internal fun onSelectionChanged(next: EditorSelection) {
        selection = next.coerceTo(lines)
        // 光标落到哪里，"接下来输入的样式"就跟到哪里（iOS 的 typingAttributes 同义）
        if (selection.isCollapsed) {
            pendingFlags = EditorModel.caretFlags(currentLine, selection.start)
        }
    }

    /** 测试用：直接指定"是否在组合中"（生产路径由 [updateComposition] 从 Spannable 读）。 */
    internal fun setComposingForTest(value: Boolean) {
        composing = value
    }

    internal fun updateComposition(spanned: Spanned) {
        val range = SpannableBridge.compositionRange(spanned as android.text.Spannable)
        composing = FormatGate.hasComposition(range.first, range.last + 1)
    }

    /** 当前行。 */
    val currentLine: EditorLine get() = lines.getOrNull(selection.lineIndex) ?: EditorModel.EMPTY_LINE

    /**
     * 工具条高亮的"当前行内样式"：
     * - 有选区 → 整个选区**都带**才算（混合选区不高亮）；
     * - 无选区 → 用 [pendingFlags]（光标处的样式）。
     */
    val activeFlags: InlineFlags get() = selectionFlags()

    /** 工具条段落级操作覆盖的整段范围。 */
    val lineRange: IntRange get() = selection.lineRange

    /**
     * 格式栏：光标所在行的段落格式（六选一）。按钮高亮都用它，
     * 于是"列表行上段落样式菜单显示正文"这类规则只有一处实现。
     */
    val currentFormat: BlockFormat get() = BlockFormat.ofLine(currentLine)

    /**
     * 格式栏：居中按钮此刻能不能点（规则 5）。
     *
     * 判据是**整个选区**：选中的行里只要有一行是列表/引用/待办，居中就置灰 ——
     * "启用列表、待办、引用格式的时候……把居中按钮置灰，不允许用户点按"。
     */
    val centerEnabled: Boolean get() = EditorModel.centerAllowed(lines, lineRange)
}

/** 工具栏可用的 `remember` 包装。 */
@Composable
fun rememberEditorTextState(initialLines: List<EditorLine>): EditorTextState =
    remember { EditorTextState(initialLines) }

internal fun EditorSelection.coerceTo(lines: List<EditorLine>): EditorSelection =
    EditorSelectionMap.normalize(this, lines)
