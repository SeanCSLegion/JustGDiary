package com.cov.justdiary.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **不吃键盘的轻量菜单** —— 编辑器段落样式菜单与地点精度菜单都用它。
 *
 * ## 为什么不能直接用 M3 的 `DropdownMenu`
 *
 * `DropdownMenu` 底层是 `Popup(focusable = true)`：它自带一个**可聚焦窗口**，
 * 窗口一拿到焦点，正在编辑的 `EditText` 就丢了焦点，系统随即收起输入法；菜单选完
 * 再把焦点还回去、键盘又弹回来 —— 真机上表现为**键盘闪一下**（iOS 的 `Menu` 不抢焦点，
 * 所以没有这一下）。
 *
 * **换任何"另起一个窗口"的做法都躲不掉**（真机逐条试过）：
 * - `Popup(focusable = false)`：窗口根本收不到触摸，菜单项点不动；
 * - `Dialog`（含 `FLAG_NOT_FOCUSABLE` / `FLAG_ALT_FOCUSABLE_IM`）：能点，但**前台窗口一换，
 *   输入法就被系统收起**，键盘照样消失。
 *
 * Android 的输入法跟的是"焦点窗口"，只要前台换窗口就会收 —— 这是平台行为，不是组件的 bug。
 *
 * ## 这里的做法：**在同一个窗口里画浮层**
 *
 * 不新开窗口，而是在编辑器自己的组合树上加一层 `Box` 叠在最上面。因为窗口没换，
 * 输入法全程不动：**键盘不闪、光标不丢、选区不变**。
 *
 * 用法（宿主负责 z 序，把这个调用放在最后）：
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     ...编辑器的内容...
 *     AnchoredMenu(expanded, onDismiss, alignment = Alignment.BottomStart) { ...菜单项... }
 * }
 * ```
 *
 * 关闭手势：铺一层不可聚焦的透明遮罩（同一个窗口里，所以遮罩不会吃掉菜单项自己的点击 ——
 * 菜单排在遮罩之后），点它 / 按返回键都关。
 */
@Composable
fun AnchoredMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 菜单面板在整个浮层里的对齐方式（贴底菜单用 `BottomStart`）。 */
    alignment: Alignment = Alignment.BottomStart,
    width: Dp = MenuDefaults.Width,
    /** 菜单面板相对浮层边缘的留白。 */
    padding: Dp = MenuDefaults.Gap,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：给"点空白关菜单"一个目标。**不可聚焦**，所以点它不会让 EditText 丢焦点；
        // 它排在菜单面板之前，因此不会挡住菜单项自己的点击。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Surface(
            modifier = Modifier
                .align(alignment)
                .padding(padding)
                .widthIn(min = width, max = MenuDefaults.MaxWidth),
            shape = RoundedCornerShape(MenuDefaults.CornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = MenuDefaults.Elevation,
            tonalElevation = MenuDefaults.Elevation,
        ) {
            Column(
                modifier = Modifier.padding(vertical = MenuDefaults.VerticalPadding),
                content = content,
            )
        }
    }
}

/** 菜单里的一行：48dp 高，选中项打勾 + 主色文字。 */
@Composable
fun AnchoredMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MenuDefaults.ItemHeight)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = MenuDefaults.ItemHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MenuDefaults.ItemGap),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (selected && trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                modifier = Modifier.size(MenuDefaults.IconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(modifier = Modifier.size(MenuDefaults.IconSize))
        }
    }
}

/**
 * **弹出位置**在编辑器里的推荐值（编辑器是一个 `Column`：编辑卡 + 格式栏，居中、列宽 ≤660dp）。
 *
 * 菜单从**格式栏**长出来，所以贴在格式栏正上方；横向与格式栏左对齐。
 * 这一个常量同时被段落样式菜单（在格式栏里）与地点精度菜单（在编辑卡里）使用 ——
 * 两者的浮层都铺满整个编辑器，因此用同一组对齐 + 留白就能落在同一个位置。
 */
object MenuDefaults {
    /** 菜单宽度（M3 菜单最小 112dp，这里给三项标签留够）。 */
    val Width: Dp = 168.dp
    val MaxWidth: Dp = 280.dp

    /** 列表项高 48dp（M3 `MenuTokens.ListItemContainerHeight`）。 */
    val ItemHeight: Dp = 48.dp
    val ItemHorizontalPadding: Dp = 16.dp
    val ItemGap: Dp = 12.dp
    val IconSize: Dp = 18.dp

    /** 上下内边距 8dp（M3 菜单容器）。 */
    val VerticalPadding: Dp = 8.dp

    /** 圆角 4dp（M3 `MenuTokens.ContainerShape`）。 */
    val CornerRadius: Dp = 4.dp

    /** 阴影 2dp。 */
    val Elevation: Dp = 2.dp

    /** 菜单与格式栏 / 地点行之间的留白。 */
    val Gap: Dp = 8.dp
}
