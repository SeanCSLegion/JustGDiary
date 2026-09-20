package com.cov.justdiary.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cov.justdiary.ui.theme.LocalIncreasedContrast
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing
import com.cov.justdiary.ui.theme.TypeSize

/**
 * 共享 UI 原语。与 iOS `Views/Components/Components.swift` 同构：一个文件放多个小组件。
 *
 * ## 设计语言
 *
 * 这些组件**按 Material 3 实现**（已确认：不模仿 iOS 的 Liquid Glass，见方案 §7）：
 * 内容面用 M3 的 surface 层级 + 描边 + elevation，而不是半透明玻璃。
 * 只有**自绘组件**（日历、图表、分享长图）才沿用原项目的度量令牌。
 *
 * 行为上仍与 iOS 对齐的部分：卡片在"增强对比度"下描边加粗（0.5dp/45% → 1.5dp/90% + 外描边）。
 */
@Composable
fun DiaryCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.card),
    content: @Composable () -> Unit,
) {
    val increased = LocalIncreasedContrast.current
    val shape = RoundedCornerShape(Radius.card)

    // 增强对比度：内描边加深加粗，并加一圈外描边（与 iOS 的规则一致）
    val border = if (increased) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
            tonalElevation = 2.dp,
            shadowElevation = 3.dp,
        ) {
            Box(modifier = Modifier.padding(contentPadding)) { content() }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
        ) {
            Box(modifier = Modifier.padding(contentPadding)) { content() }
        }
    }
}

/**
 * 页面标题（`TypeSize.pageTitle`）。
 *
 * 高度用 `heightIn(min = ...)` 而不是固定高度：放大字号时标题行要能长高（iOS 侧的
 * 溢出处理原则，见 `docs/design-system.md` §6）。
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.hitTarget)
            .padding(horizontal = Spacing.page, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 卡片内的小节标题（`TypeSize.sectionTitle`）。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 图标徽章：38dp 圆角方块 + 品牌色底 + 图标。
 *
 * ⚠️ 必须保持正方形：放进 `Row` 时只用 `size()`，**不要**用 `fillMaxHeight()`，
 * 否则会被行高拉成长条（iOS 侧踩过这个坑，见 `docs/design-system.md` §6）。
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    size: androidx.compose.ui.unit.Dp = 38.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(Radius.badge),
        color = background,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * 空状态：图标 + 文案（+ 可选副文案与行动按钮）。
 *
 * 对应 iOS `GlassEmptyState`（图标 26pt + 文案 15pt + 可选 compact 按钮）。
 * 行动按钮是规范 §9.5 要求的：**有条件但无结果**时必须给一个「清除全部条件」的出口，
 * 否则用户只能一个个点掉胶囊才能回到空态。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.page),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * 内容最大宽度约束（阅读 ≤660dp、编辑 ≤620dp、卡片流每列 ≤480dp）。
 *
 * 手机竖屏/横屏用不到，但一旦接宽屏（M8 之后）这三条是硬约束
 * （见方案 §6.1 引用的 `docs/adaptive-layout-plan.md`）。
 */
object ContentWidth {
    val reading = 660.dp
    val editing = 620.dp
    val cardColumn = 480.dp
}
