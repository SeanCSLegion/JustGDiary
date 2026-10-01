package com.cov.justdiary.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.ui.components.AnchoredMenuItem
import com.cov.justdiary.ui.components.AppIcons
import com.cov.justdiary.ui.theme.Radius

/**
 * 编辑器格式栏（规范 `docs/android-design/04-diary.md` §3.3）。
 *
 * ```
 * [段落样式▾] [居中] │ [列表] [引用] [待办] │ [B] [I] [S] [U]      ← 顺序固定，与 iOS 逐项一致
 * ```
 *
 * | 项 | 尺寸 | 说明 |
 * | --- | --- | --- |
 * | 段落样式 | 46dp 宽（图标 18 + 下拉 14） | **菜单**：大标题 / 小标题 / 正文（当前项打勾）。带下拉指示是因为"只放一个图标"用户看不出它会展开 |
 * | 居中 | 40dp | 列表 / 引用 / 待办任一激活时**禁用**（互斥） |
 * | 设置分隔线 | 1 × 24dp | 三组之间 |
 * | 列表 / 引用 / 待办 | 40dp | 三者互斥；引用**不在**段落样式菜单里 |
 * | B / I / S / U | 40dp | 有选区时"整个选区都带"才高亮 |
 *
 * 外观（与 `mockups/assets/mockup.css` 的 `.formatbar` 同源）：`surfaceContainerHigh` +
 * 28dp 圆角 + 4dp 内边距 + shadow-2；按钮 40dp 圆；active = `secondaryContainer` 胶囊底 +
 * `onSecondaryContainer` 前景；disabled = `alpha 0.38`。
 *
 * **宽度预算**：46 + 40×8 + 分隔线 5×2 + 内边距 8 = **384dp**。412dp 手机的可用宽是 396dp，
 * 一行放得下；更窄或大字号时**横向滚动**，绝不折行（折行会把「下划线」挤到第二行、整条高度翻倍）。
 * 容器用"放得下就居中、放不下就从头排"（`horizontalScroll` + `widthIn(min = 可用宽)` + `Center`）。
 *
 * **触控目标**：按钮 40dp 宽 × **48dp 高**（栏高 56 = 4 + 48 + 4）。横向受上面那条宽度预算约束，
 * 因此没有取 Material 的 48dp 见方 —— 这是规范里"一行放得下"与"触控 48dp"两条同时要求时
 * 唯一自洽的取法（纵向补足，横向 40dp）。
 */
@Composable
fun DiaryFormatBar(
    activeBlock: EditorBlockStyle,
    flags: InlineFlags,
    modifier: Modifier = Modifier,
    isQuote: Boolean = false,
    isList: Boolean = false,
    isTodo: Boolean = false,
    isImage: Boolean = false,
    isCentered: Boolean = false,
    enabled: Boolean = true,
    /**
     * 居中按钮能不能点（规则 5）。
     *
     * 默认按"光标那一行是列表 / 引用 / 待办 / 图片就置灰"推导；编辑器会显式传一个**按整个
     * 选区**算出来的值（选中一段引用 + 一行正文时，居中同样必须置灰 —— 只看光标那一行不够）。
     *
     * 图片行也要置灰：图片段的对齐由编解码固定，`align` 根本不落库，按钮在那里点了没用
     * （iOS `centerIsAllowed` 同样排除 image）。
     */
    centerEnabled: Boolean = enabled && !isList && !isQuote && !isTodo && !isImage,
    onBlockStyle: (EditorBlockStyle) -> Unit = {},
    onToggleCenter: () -> Unit = {},
    onToggleList: () -> Unit = {},
    onToggleQuote: () -> Unit = {},
    onToggleTodo: () -> Unit = {},
    onToggleInline: (InlineFlag) -> Unit = {},
    /**
     * 段落样式菜单是否开着（**由 `EditorScreen` 持有**）。
     *
     * 菜单要浮在格式栏**之上**，所以它画在编辑器的浮层里，而不是格式栏内部 ——
     * 状态因此提升到编辑器一层。
     */
    styleMenuOpen: Boolean = false,
    onStyleMenuChange: (Boolean) -> Unit = {},
) {
    val styleLabel = stringResource(R.string.editor_tool_style)
    val currentStyleLabel = blockStyleLabel(activeBlock)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.panel),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
    ) {
        BoxWithConstraints {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    // 放得下就居中（内容至少撑满可用宽），放不下就从头排并横滑
                    .widthIn(min = maxWidth)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlockStyleMenuButton(
                    activeBlock = activeBlock,
                    enabled = enabled,
                    label = styleLabel,
                    currentStyleLabel = currentStyleLabel,
                    open = styleMenuOpen,
                    onOpenChange = onStyleMenuChange,
                )
                FormatButton(
                    icon = AppIcons.AlignCenter,
                    label = stringResource(R.string.editor_align_center),
                    active = isCentered,
                    // 居中与列表 / 引用 / 待办互斥（规则 5）：那三类行上置灰，不允许点按。
                    // `enabled`（IME 组合中整栏禁用）是**与**关系：centerEnabled 只能再收紧。
                    // 控制器（`EditorModel.setAlignment`）里还会 guard 一次。
                    enabled = enabled && centerEnabled,
                    onClick = onToggleCenter,
                )
                Separator()
                FormatButton(
                    icon = AppIcons.BulletList,
                    label = stringResource(R.string.editor_tool_list),
                    active = isList,
                    enabled = enabled,
                    onClick = onToggleList,
                )
                FormatButton(
                    icon = AppIcons.Quote,
                    label = stringResource(R.string.editor_tool_quote),
                    active = isQuote,
                    enabled = enabled,
                    onClick = onToggleQuote,
                )
                FormatButton(
                    icon = AppIcons.Checklist,
                    label = stringResource(R.string.editor_tool_todo),
                    active = isTodo,
                    enabled = enabled,
                    onClick = onToggleTodo,
                )
                Separator()
                FormatButton(
                    icon = AppIcons.Bold,
                    label = stringResource(R.string.editor_tool_bold),
                    active = flags.bold,
                    enabled = enabled,
                    onClick = { onToggleInline(InlineFlag.Bold) },
                )
                FormatButton(
                    icon = AppIcons.Italic,
                    label = stringResource(R.string.editor_tool_italic),
                    active = flags.italic,
                    enabled = enabled,
                    onClick = { onToggleInline(InlineFlag.Italic) },
                )
                FormatButton(
                    icon = AppIcons.Strikethrough,
                    label = stringResource(R.string.editor_tool_strike),
                    active = flags.strike,
                    enabled = enabled,
                    onClick = { onToggleInline(InlineFlag.Strike) },
                )
                FormatButton(
                    icon = AppIcons.Underline,
                    label = stringResource(R.string.editor_tool_underline),
                    active = flags.underline,
                    enabled = enabled,
                    onClick = { onToggleInline(InlineFlag.Underline) },
                )
            }
        }
    }
}

/** 段落样式菜单：宽 46dp，图标 18dp + 下拉指示 14dp。 */
@Composable
private fun BlockStyleMenuButton(
    activeBlock: EditorBlockStyle,
    enabled: Boolean,
    label: String,
    currentStyleLabel: String,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
) {
    val menuBackground = MaterialTheme.colorScheme.secondaryContainer
    // 非聚焦浮层收不到返回键的 window 事件，菜单开着时由这里接管：先关菜单，不退出编辑态。
    androidx.activity.compose.BackHandler(enabled = open) { onOpenChange(false) }

    Row(
        modifier = Modifier
            .width(FormatBarMetrics.MenuWidth)
            .height(FormatBarMetrics.TouchHeight)
            .clip(CircleShape)
            .background(if (activeBlock.lightsStyleMenu) menuBackground else Color.Transparent)
            .alpha(if (enabled) 1f else FormatBarMetrics.DisabledAlpha)
            .clickable(enabled = enabled, onClickLabel = label) { onOpenChange(!open) }
            .semantics {
                role = Role.Button
                contentDescription = label
                // 当前段落样式作为 value 读出来（TalkBack：「段落样式，大标题」）
                stateDescription = currentStyleLabel
                selected = activeBlock.lightsStyleMenu
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.FormatSize,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (activeBlock.lightsStyleMenu) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Icon(
            imageVector = AppIcons.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (activeBlock.lightsStyleMenu) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 段落样式按钮**点亮**的判据：只认大标题 / 小标题。
 *
 * 引用虽然也"不是正文"，但它**不在**这个菜单里（有自己的按钮）。早先用
 * `activeBlock != Body` 判定，于是点「引用」会把段落样式按钮一起点亮 ——
 * iOS B21 的同一处 bug（那边改成"只认 Title / Heading"并把点亮挂到无障碍的
 * `isSelected` 上）。
 *
 * ⚠️ 注意只影响**按钮自己**：菜单面板里哪一项打勾仍按 `style == activeBlock` 算，
 * 引用行因此"一项都不打勾"，这是对的。
 */
private val EditorBlockStyle.lightsStyleMenu: Boolean
    get() = this == EditorBlockStyle.Title || this == EditorBlockStyle.Heading

/** 单个格式按钮：40dp 圆 + 48dp 高触控区；active 用 `secondaryContainer` 胶囊底。 */
@Composable
private fun FormatButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val activeTint = MaterialTheme.colorScheme.onSecondaryContainer
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .width(FormatBarMetrics.ButtonWidth)
            .height(FormatBarMetrics.TouchHeight)
            .alpha(if (enabled) 1f else FormatBarMetrics.DisabledAlpha)
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .semantics {
                role = Role.Button
                contentDescription = label
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(FormatBarMetrics.ButtonWidth)
                .clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(FormatBarMetrics.IconSize),
                tint = if (active) activeTint else idleTint,
            )
        }
    }
}

/** 组之间的分隔线：1 × 24dp，两侧各 2dp。 */
@Composable
private fun Separator() {
    Spacer(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 格式栏的几何常量（同时是"一行放得下"那张宽度预算表）。 */
object FormatBarMetrics {
    val ButtonWidth: Dp = 40.dp

    /** 段落样式是菜单，比普通按钮宽 6dp（图标 + 下拉指示）。 */
    val MenuWidth: Dp = 46.dp

    /** 触控高度取 48dp：栏高 = 4 + 48 + 4 = 56dp（规范 §3.3）。 */
    val TouchHeight: Dp = 48.dp

    val IconSize: Dp = 20.dp

    const val DisabledAlpha = 0.38f

    /** 一行放得下所需的总宽：46 + 40×8 + 5×2 + 8 = 384dp。 */
    val TotalWidth: Dp = MenuWidth + ButtonWidth * 8 + 5.dp * 2 + 8.dp
}

@Composable
private fun blockStyleLabel(style: EditorBlockStyle): String = when (style) {
    EditorBlockStyle.Title -> stringResource(R.string.editor_font_heading)
    EditorBlockStyle.Heading -> stringResource(R.string.editor_font_sub)
    EditorBlockStyle.Body -> stringResource(R.string.editor_font_body)
    EditorBlockStyle.Quote -> stringResource(R.string.editor_tool_quote)
}

/**
 * 段落样式菜单的面板（三项：大标题 / 小标题 / 正文）。**引用不在里面** —— 它有自己的按钮。
 *
 * 由 `EditorScreen` 的浮层调用（见 [com.cov.justdiary.ui.components.AnchoredMenu]）：
 * 菜单浮在格式栏之上、且不新开窗口，所以打开它**不会收键盘**。
 */
@Composable
fun ParagraphStyleMenu(
    activeBlock: EditorBlockStyle,
    onSelect: (EditorBlockStyle) -> Unit,
) {
    listOf(EditorBlockStyle.Title, EditorBlockStyle.Heading, EditorBlockStyle.Body).forEach { style ->
        AnchoredMenuItem(
            label = blockStyleLabel(style),
            selected = style == activeBlock,
            trailingIcon = Icons.Filled.Check,
            onClick = { onSelect(style) },
        )
    }
}
