package com.cov.justdiary.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
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
 * 内容面用 M3 的 surface 层级 + 描边，**不用阴影**（阴影只留给真正浮起的元素：
 * 格式栏、Snackbar、BottomSheet、FAB）。
 *
 * 行为上仍与 iOS 对齐的部分：卡片在"增强对比度"下描边加粗（→ 1.5dp / 90%）。
 */
@Composable
fun DiaryCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.card),
    /**
     * 加在**内容层**（`Surface` 的 clickable 之内）的修饰符。
     *
     * 需要 `clearAndSetSemantics` 时**必须用它**，不能塞进 [modifier]：
     * `Surface(onClick)` 把自己的 `.clickable` 追加在调用方 modifier **之后**，
     * 而 Compose 是自内向外算语义、遇到 `shouldClearDescendantSemantics` 就重置 ——
     * 放在外层会把 clickable 的 onClick 与 Role 一起清掉，
     * 结果 TalkBack 只读得到描述、**按不动**（实测缺陷）。
     */
    contentModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val increased = LocalIncreasedContrast.current
    val shape = RoundedCornerShape(Radius.card)

    // 增强对比度：描边加深加粗（与 iOS 的规则一致）
    val border = if (increased) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
        ) {
            Box(modifier = Modifier.padding(contentPadding).then(contentModifier)) { content() }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
        ) {
            Box(modifier = Modifier.padding(contentPadding).then(contentModifier)) { content() }
        }
    }
}

/** 顶栏胶囊的两种变体（见设计规范 `03-home.md` §3）。 */
enum class PillVariant {
    /** 导航用：1dp `outlineVariant` 描边，中性。 */
    Outlined,

    /** 动作用：`surfaceContainerLow` 底 + 阴影，微微浮起。 */
    Elevated,
}

/**
 * 顶栏胶囊（M3 **assist chip**）。
 *
 * 首页顶部两枚：左侧是导航（月态读 `2026年` → 去年历；周态读 `九月` → 回月态），
 * 右侧是动作（`今天` → 回到今天）。视觉 40dp 高，触控目标由
 * `minimumInteractiveComponentSize` 撑到 48dp。
 */
@Composable
fun AssistPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    variant: PillVariant = PillVariant.Outlined,
) {
    val shape = CircleShape
    val colors = if (variant == PillVariant.Elevated) {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
        )
    } else {
        AssistChipDefaults.assistChipColors(
            containerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
    AssistChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier.heightIn(min = 40.dp),
        leadingIcon = leading?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        shape = shape,
        colors = colors,
        border = if (variant == PillVariant.Elevated) null else AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            borderWidth = 1.dp,
        ),
        elevation = if (variant == PillVariant.Elevated) {
            AssistChipDefaults.assistChipElevation(elevation = 1.dp)
        } else {
            null
        },
    )
}

/**
 * 可删除的条件胶囊（`InputChip`）。
 *
 * 用在搜索页与足迹页的"条件摘要行"：**每一条筛选都必须能单独撤销** ——
 * 这是 iOS 的条件栏唯一展示位职责，重建时不要退化成静态小块。
 */
@Composable
fun InputPill(
    text: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier,
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        },
        colors = InputChipDefaults.inputChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = null,
    )
}

/** 计数徽标（「找到 3 条日记」「1/3」）。 */
@Composable
fun CountBadge(
    text: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (leading != null) {
                Icon(leading, contentDescription = null, modifier = Modifier.size(12.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 骨架行。用于**首屏加载**（搜索正在执行、足迹首次载入）。
 *
 * 设计规范 `05-search-footprint-settings.md` §4：首屏用骨架行，
 * 翻页用底部小进度圈，**不要用整屏菊花**。
 */
@Composable
fun SkeletonRows(
    modifier: Modifier = Modifier,
    count: Int = 3,
    widthFractions: List<Float> = listOf(0.38f, 0.88f, 0.64f),
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.page),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(count) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                widthFractions.forEach { f ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }
            }
        }
    }
}

/**
 * 忙碌遮罩（导出 / 导入备份）。
 *
 * **不可取消**：导入中途取消会留下半写状态。因此这里没有关闭入口，
 * 只有 scrim + 居中的圆角卡片 + 不确定进度圈。
 */
@Composable
fun BusyOverlay(text: String, visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .semantics { paneTitle = text },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.panel),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(44.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
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
                // 页面标题 = M3 `headlineMedium`（28 / 36，设计规范 §2.2）。
                // 原来用的 `headlineLarge` 是 32sp —— 那是首页「九月 / 2026年」的高度，
                // 用在搜索/足迹/设置上会比设计稿重一档。
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
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
        // ⚠️ 上边距 12dp **写在这里**，调用方只给左右内边距。
        //
        // 原来卡片只有 4dp 的上下内边距、标题自己又没有任何上边距，于是标题紧贴卡片上沿 ——
        // 视觉上与卡片圆角"没有对齐"（用户反馈）。卡片圆角 16dp、标题左内边距 16dp，
        // 上边距也该是两位数才成比例。
        modifier = modifier.padding(top = 12.dp, bottom = 6.dp),
        // 12sp → 14sp：`labelMedium` 在设置卡里当"章节名"用偏小（用户反馈"太小"）
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 图标徽标：**40dp 圆** + `secondaryContainer` 底 + 20dp 图标（设计规范 `05-…` §3.1）。
 *
 * ⚠️ 必须保持正方形：放进 `Row` 时只用 `size()`，**不要**用 `fillMaxHeight()`，
 * 否则会被行高拉成长条（iOS 侧踩过这个坑）。
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    background: Color = MaterialTheme.colorScheme.secondaryContainer,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = background,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.5f),
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
    /** 图标与标题的着色（错误态传 `error`）。 */
    tint: Color? = null,
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
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
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
