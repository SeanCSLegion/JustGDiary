package com.cov.justdiary.ui.diary

import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.widget.AppCompatEditText
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
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        // 图片按可用宽度等比缩放（不再画成写死的正方形，否则非正方形图片会被拉伸）
        val imageWidthPx = with(density) { maxWidth.toPx().toInt() }
        if (state.lines.all { it.text.isEmpty() }) {
            Text(
                text = placeholder,
                modifier = Modifier.align(Alignment.TopStart),
                style = bodyTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight),
            factory = { context ->
                AppCompatEditText(context).apply {
                    isSingleLine = false
                    background = null
                    setPadding(0, 0, 0, 0)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    // 平台撤销（API 23+）与 IME 的自动大小写都保留默认行为
                    setText(SpannableBridge.toSpannable(state.lines, imageProvider, imageWidthPx))
                    setSelection(text?.length ?: 0)
                    addTextChangedListener(
                        object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                if (state.suppressWatcher) return
                                val spanned = s ?: return
                                state.onUserText(spanned)
                                state.onSelectionChanged(currentSelection(spanned, selectionStart, selectionEnd))
                                state.updateComposition(spanned)
                            }
                        },
                    )
                }
            },
            update = { view ->
                // 只有"模型被工具栏改过"时才回写文本，否则会打断用户正在进行的输入
                if (view.tag != state.revision) {
                    view.tag = state.revision
                    val caret = EditorSelectionMap.flatOffset(
                        state.lines,
                        state.selection.lineIndex,
                        state.selection.start,
                    )
                    state.suppressWatcher = true
                    view.setText(SpannableBridge.toSpannable(state.lines, imageProvider, imageWidthPx))
                    val safe = caret.coerceIn(0, view.text?.length ?: 0)
                    view.setSelection(safe)
                    state.suppressWatcher = false
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
    return if (a.lineIndex == b.lineIndex) {
        EditorSelection(a.lineIndex, a.offsetInLine, b.offsetInLine)
    } else {
        // 跨行选区：模型只支持行内，取起点所在行到行尾
        val line = lines.getOrNull(a.lineIndex)
        EditorSelection(a.lineIndex, a.offsetInLine, line?.text?.length ?: a.offsetInLine)
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
    fun onUserText(spanned: Spanned) {
        val parsed = SpannableBridge.fromSpannable(spanned)
        lines = EditorModel.inheritMarkersForNewLines(parsed, previous = lines)
        selection = selection.coerceTo(lines)
    }

    /** 工具栏操作：在模型上做变换，然后请 `EditText` 重建文本。 */
    fun apply(transform: (List<EditorLine>) -> List<EditorLine>) {
        // 组合进行中不做结构修改（与 FormatGate 同一条规则）
        if (composing) return
        lines = transform(lines).ifEmpty { listOf(EditorModel.EMPTY_LINE) }
        selection = selection.coerceTo(lines)
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
    }

    internal fun updateComposition(spanned: Spanned) {
        val range = SpannableBridge.compositionRange(spanned as android.text.Spannable)
        composing = FormatGate.hasComposition(range.first, range.last + 1)
    }

    /** 当前行。 */
    val currentLine: EditorLine get() = lines.getOrNull(selection.lineIndex) ?: EditorModel.EMPTY_LINE

    /** 工具栏用的行内选区（空选区时取光标前一个字符）。 */
    val inlineRange: Pair<Int, Int> get() = EditorSelectionMap.effectiveInlineRange(selection, lines)

    /** 当前生效的行内样式。 */
    val activeFlags: InlineFlags get() = activeFlagsOf(currentLine)
}

/** 工具栏可用的 `remember` 包装。 */
@Composable
fun rememberEditorTextState(initialLines: List<EditorLine>): EditorTextState =
    remember { EditorTextState(initialLines) }

internal fun EditorSelection.coerceTo(lines: List<EditorLine>): EditorSelection =
    EditorSelectionMap.normalize(this, lines)

internal fun activeFlagsOf(line: EditorLine): InlineFlags {
    val flags = EditorModel.materializeRuns(line)
    if (flags.isEmpty()) return InlineFlags.NONE
    // 光标处（行首）取第一个 run 的样式；这与 iOS 的 `activeStyles()` 读 typingAttributes 等价
    val run = flags.first()
    return InlineFlags(
        bold = run.bold == true,
        italic = run.italic == true,
        strike = run.strike == true,
        underline = run.underline == true,
    )
}
