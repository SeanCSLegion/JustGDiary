package com.cov.justdiary.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ripple
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cov.justdiary.ui.components.DiaryCard
import com.cov.justdiary.ui.components.IconBadge

/**
 * 设置页的行与卡片：菜单行、值行、开关行与统一样式的卡片容器。
 *
 * 从 [SettingsScreen] 拆出；只放可复用的行组件，业务状态留在主文件。
 */

// ---------------------------------------------------------------------- 行组件

/**
 * 卡片容器：内部行自己控制上下呼吸，因此卡片只留一点点内边距。
 *
 * 原来是 12dp —— 与行自身的 9dp 叠加后，末行文字下方会有 20dp 以上的空白，
 * 单行副标题时尤其明显（用户反馈"底部留白太多"）。现在 4dp，视觉上仍然有分隔感。
 */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    DiaryCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * 行之间的分隔线（规范 §3.1）：左缩进 **72dp**，与行的**文字**左对齐（不是与图标对齐）。
 *
 * 72 = 16(行内边距) + 40(图标徽标) + 16(间距)。
 */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 行高的两条档位（规范 §3.1）：带副标题 64dp、不带 56dp。 */
internal fun rowMinHeight(sub: String?): androidx.compose.ui.unit.Dp =
    // 这是**最小**高度，不是固定高度：副标题折成两行时行会自己长高，不会裁切。
    // 原来的 64/56 是按"副标题两行"定的，于是单行副标题的行上下各留 9dp，
    // 加上卡片自身的 12dp 内边距，末行下方会有 20dp 以上的空白（用户反馈"底部留白太多"）。
    // 现在收到 56/48：单行副标题正好是 24+2+20，2×5dp 呼吸；双行自然长到 ~76。
    if (sub != null) 56.dp else 48.dp

/**
 * 右侧的取值 [+ 尖角]。
 *
 * 取值**限宽但不写死**：原来是固定 96dp + 省略号，放大系统字号后长取值（「凌晨 4:00」）
 * 会被截成「凌晨 4…」。现在给到 150dp，配合 `maxLines = 1`；文案列本身 `weight(1f)`，
 * 因此再长也只会挤压自己、不会把尖角挤出屏幕（iOS 侧为这条专门加过 `minimumScaleFactor`）。
 *
 * `showChevron = false` 用于**不可点**的行（「版本」）—— iOS 那一行画了箭头却点了没反应，
 * 视觉上像能点，这里直接不画。
 */
@Composable
internal fun ValueTrailing(value: String, showChevron: Boolean = true) {
    if (value.isNotEmpty()) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 150.dp),
        )
    }
    if (showChevron) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 点一下打开菜单的行。 */
@Composable
internal fun MenuRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    value: String,
    options: List<MenuOption>,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .heightIn(min = rowMinHeight(sub))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = icon)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (sub != null) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ValueTrailing(value)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        option.onSelect()
                    },
                    trailingIcon = if (option.selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 点一下执行动作的行。 */
@Composable
internal fun ValueRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    value: String,
    onClick: () -> Unit = {},
    /** 「版本」这类行不可点，也不画箭头（规范 §3.5）。 */
    enabled: Boolean = true,
    actionLabel: String? = null,
    status: String = "",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = rowMinHeight(sub))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                if (actionLabel != null && status.isNotBlank()) "$title · $status" else title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (actionLabel != null) Modifier.semantics {
                    contentDescription = "$title ${value}，${actionLabel}"
                } else Modifier,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ValueTrailing(value, showChevron = enabled)
    }
}

/** 开关行。 */
@Composable
internal fun SwitchRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点（规范 §3.2）：只让开关本体可点是硬性体验问题，v2 已列为缺陷
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = rowMinHeight(sub))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // ⚠️ `onCheckedChange = null`：整行的 `toggleable` 已经负责切换。
        // 传了回调的话 Switch 自己也会挂一个 toggleable 语义节点，而 Compose 不会把一个
        // 独立可聚焦的子节点合并进父节点 → TalkBack 每行要停两次（实测反馈）。
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ---------------------------------------------------------------------- 对话框

internal data class MenuOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)
