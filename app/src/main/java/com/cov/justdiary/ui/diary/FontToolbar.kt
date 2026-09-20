package com.cov.justdiary.ui.diary

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cov.justdiary.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing

/**
 * 编辑器格式栏。
 *
 * 对应 iOS 的 `FontToolbar`（`.glassEffect` 玻璃面板）—— Android 侧按 Material 3 做成
 * **色调面板 + `FilterChip` 组**，并按方案 §7.2 的映射把"悬浮操作面"的语义
 * 交给 M3 的 surface 层级表达，不做玻璃。
 *
 * 几个与 iOS 一致的行为：
 *  - 段落样式是**语义样式**（大标题 / 小标题 / 正文），没有任意磅值；
 *  - 引用单独一个按钮，且同时是"引用底色"的状态指示；
 *  - 列表与待办各自一个按钮，**只作用于当前行**；
 *  - 行内样式四个按钮显示当前生效状态（混合选区显示为未开启）；
 *  - 顺序固定为 段落样式 → 居中 → 列表 → 引用 → 待办 → B/I/S/U（方案 §7.4 / 缺陷 D11）。
 *
 * @param isCentered 当前段落是否居中（`content_json` 的 `align == "center"`）。
 *   居中与列表 / 引用 / 待办**互斥**：那三种块下本按钮置灰（见 [FontToolbar] 内注释）。
 * @param enabled 为 false 时整栏禁用 —— 由调用方在 **IME 组合期间**传 false
 *   （[FormatGate] 的规则：组合区里改 span 会被输入法覆盖）。
 */
@Composable
fun FontToolbar(
    activeBlock: EditorBlockStyle,
    flags: InlineFlags,
    isQuote: Boolean,
    isList: Boolean,
    isTodo: Boolean,
    isCentered: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onBlockStyle: (EditorBlockStyle) -> Unit = {},
    onToggleQuote: () -> Unit = {},
    onToggleList: () -> Unit = {},
    onToggleTodo: () -> Unit = {},
    onToggleAlign: () -> Unit = {},
    onToggleInline: (InlineFlag) -> Unit = {},
    onInsertImage: () -> Unit = {},
) {
    var styleMenuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.panel),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.card, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 段落样式菜单：大标题 / 小标题 / 正文
            Row {
                FilterChip(
                    selected = activeBlock != EditorBlockStyle.Body || styleMenuOpen,
                    onClick = { if (enabled) styleMenuOpen = true },
                    enabled = enabled,
                    label = { Text(blockStyleLabel(activeBlock)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = STYLE_LABEL) },
                    colors = toolbarChipColors(),
                )
                DropdownMenu(expanded = styleMenuOpen, onDismissRequest = { styleMenuOpen = false }) {
                    EditorBlockStyle.entries.forEach { style ->
                        DropdownMenuItem(
                            text = { Text(blockStyleLabel(style)) },
                            trailingIcon = {
                                if (style == activeBlock) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                            onClick = {
                                styleMenuOpen = false
                                onBlockStyle(style)
                            },
                        )
                    }
                }
            }

            // 居中对齐（方案 §7.4 / 缺陷 D11）：落库为 `content_json` 的 `align` 字段
            // （iOS 能写、Android 从前不能写，等于跨端能力缺一块）。
            // 与列表 / 引用 / 待办**互斥**，那些块下用 `enabled = false` 置灰而不是隐藏 ——
            // 隐藏会让右侧按钮左右位移，置灰能保持工具栏几何稳定。
            FilterChip(
                selected = isCentered,
                onClick = { if (enabled) onToggleAlign() },
                enabled = enabled && !isQuote && !isList && !isTodo,
                label = { Text(ALIGN_CENTER_LABEL) },
                // 不带图标：`material-icons-core` 里没有对齐类图标（`FormatAlignCenter`
                // 属于 extended，本工程刻意不引入）。工具栏的其它芯片都是"文字 + 可选图标"，
                // 这里只用文字，既不会误用"汉堡菜单"那类语义不符的图标，也和 引用 / 列表 / 待办
                // 的视觉重量一致（文字本身就是 contentDescription）。
                colors = toolbarChipColors(),
            )
            FilterChip(
                selected = isList,
                onClick = { if (enabled) onToggleList() },
                enabled = enabled,
                label = { Text(LIST_LABEL) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                colors = toolbarChipColors(),
            )
            FilterChip(
                selected = isQuote,
                onClick = { if (enabled) onToggleQuote() },
                enabled = enabled,
                label = { Text(QUOTE_LABEL) },
                colors = toolbarChipColors(),
            )
            FilterChip(
                selected = isTodo,
                onClick = { if (enabled) onToggleTodo() },
                enabled = enabled,
                label = { Text(TODO_LABEL) },
                leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                colors = toolbarChipColors(),
            )

            FilterChip(
                selected = flags.bold,
                onClick = { if (enabled) onToggleInline(InlineFlag.Bold) },
                enabled = enabled,
                label = { Text("B", fontWeight = FontWeight.Bold) },
                colors = toolbarChipColors(),
            )
            FilterChip(
                selected = flags.italic,
                onClick = { if (enabled) onToggleInline(InlineFlag.Italic) },
                enabled = enabled,
                label = { Text("I", fontStyle = FontStyle.Italic) },
                colors = toolbarChipColors(),
            )
            FilterChip(
                selected = flags.strike,
                onClick = { if (enabled) onToggleInline(InlineFlag.Strike) },
                enabled = enabled,
                label = { Text("S", textDecoration = TextDecoration.LineThrough) },
                colors = toolbarChipColors(),
            )
            FilterChip(
                selected = flags.underline,
                onClick = { if (enabled) onToggleInline(InlineFlag.Underline) },
                enabled = enabled,
                label = { Text("U", textDecoration = TextDecoration.Underline) },
                colors = toolbarChipColors(),
            )

            FilterChip(
                selected = false,
                onClick = { if (enabled) onInsertImage() },
                enabled = enabled,
                label = { Text(IMAGE_LABEL) },
                colors = toolbarChipColors(),
            )
        }
    }
}

/**
 * 格式栏统一的选中色：**`primary` 填充 + `onPrimary` 前景**（方案 §7.4「激活态用
 * `primary` 填充」）。抽成一个函数是为了让居中与既有按钮真正"长得一样"——
 * M3 `FilterChip` 默认选中色是 `secondaryContainer`，逐按钮覆写会漂移。
 */
@Composable
private fun toolbarChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun blockStyleLabel(style: EditorBlockStyle): String = when (style) {
    EditorBlockStyle.Title -> stringResource(R.string.editor_font_heading)
    EditorBlockStyle.Heading -> stringResource(R.string.editor_font_sub)
    EditorBlockStyle.Body -> stringResource(R.string.editor_font_body)
    EditorBlockStyle.Quote -> stringResource(R.string.editor_tool_quote)
}

private val STYLE_LABEL: String
    @Composable get() = stringResource(R.string.editor_tool_style)
private val QUOTE_LABEL: String
    @Composable get() = stringResource(R.string.editor_tool_quote)
private val LIST_LABEL: String
    @Composable get() = stringResource(R.string.editor_tool_list)
private val TODO_LABEL: String
    @Composable get() = stringResource(R.string.editor_tool_todo)
private val ALIGN_CENTER_LABEL: String
    @Composable get() = stringResource(R.string.editor_align_center)
private val IMAGE_LABEL: String
    @Composable get() = stringResource(R.string.a11y_insert_image)
