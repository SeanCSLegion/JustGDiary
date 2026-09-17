package com.cov.justdiary.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计度量令牌。
 *
 * 与 iOS 的关系见 docs/android-migration-plan.md §7.2：
 * Android 侧改用 Material 3 的形状与排版体系（`MaterialTheme.shapes` / `MaterialTheme.typography`），
 * **不再把 iOS 的圆角令牌套到 M3 组件上**。这里保留 [Radius] 只服务于**自绘组件**
 * （日历网格、年度柱状图、分享长图）—— 这些地方沿用原项目的布局数学，避免几何变化。
 */
object Radius {

    /** 内容卡片（自绘/分享长图使用）。 */
    val card: Dp = 20.dp

    /** 悬浮操作面（编辑器格式栏、分享图里的面板）。 */
    val panel: Dp = 26.dp

    /** 输入框、卡片内的次级面。 */
    val field: Dp = 14.dp

    /** 卡片内联图片。 */
    val image: Dp = 14.dp

    /** 图标徽章、小色块。 */
    val badge: Dp = 12.dp

    /** 图表柱、进度条。 */
    val bar: Dp = 4.dp

    /** 2dp 高的标记线（日历「有日记」下划线，宽度取 1dp 圆角）。 */
    val hairline: Dp = 1.dp

    /**
     * 嵌套圆角：内圆角 = 外圆角 − 内缩量（下限 0）。
     *
     * 这是原项目 `Radius.concentric(outer:inset:)` 的公式（`Views/Components/DesignSystem.swift`），
     * Material 3 没有等价概念，因此原样保留为纯函数。
     */
    fun concentric(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(0.dp)
}

/** 间距令牌（沿用原项目已被重复使用的值，不做重排）。 */
object Spacing {
    val card: Dp = 12.dp
    val rowIcon: Dp = 12.dp
    val hitTarget: Dp = 44.dp
    val page: Dp = 16.dp
}

/**
 * 设计字号（未缩放的语义字号，单位按 dp/pt 等价处理）。
 *
 * [DesignTypeSize.ceilingBySize] 给出"每个设计字号在最接近的系统文本样式下允许的最大缩放系数"，
 * 用于保证**字阶单调**（iOS 侧 `DynamicTypeMetrics.ceiling(for:)` 的同一目的）。
 * 注意方向：字号越小、上限越大（小字号的相对增幅更大），这样缩放后梯级才不会倒挂。
 *
 * ⚠️ M2 阶段必须从 `Views/Components/DesignSystem.swift:124-136` **逐字核对**本表后再启用；
 * 当前值是由 iOS 的 AX5 实测表反推的近似值，尚未逐条比对源码。
 */
object DesignTypeSize {

    val display: Dp = 32.dp
    val pageTitle: Dp = 24.dp
    val sheetTitle: Dp = 17.dp
    val cardTitle: Dp = 20.dp
    val headerTitle: Dp = 18.dp
    val statValue: Dp = 18.dp
    val rowTitle: Dp = 15.dp
    val rowSub: Dp = 13.dp
    val rowValue: Dp = 15.dp
    val sectionTitle: Dp = 13.dp
    val chip: Dp = 14.dp
    val badge: Dp = 12.dp
    val meta: Dp = 13.dp
    val caption: Dp = 12.dp

    /** 编辑器语义段落梯级（title / heading / body / quote）。 */
    val editorTitle: Dp = 28.dp
    val editorHeading: Dp = 22.dp
    val editorBody: Dp = 17.dp
    val editorQuote: Dp = 15.dp

    /** 编辑器的四档梯级，从大到小；[editorLadder] 用于往返不变量测试。 */
    val editorLadder: List<Dp> = listOf(editorTitle, editorHeading, editorBody, editorQuote)

    /**
     * 按设计字号给出缩放上限。数值越小、上限越高（Apple 的文本样式曲线本身如此，
     * 而我们的目标是**保持梯级单调**）。M2 逐字核对后本实现会被替换为查表 + 插值。
     */
    val ceilingBySize: Map<Dp, Float> = mapOf(
        32.dp to 1.40f,
        28.dp to 1.40f,
        24.dp to 1.45f,
        22.dp to 1.45f,
        20.dp to 1.45f,
        18.dp to 1.50f,
        17.dp to 1.50f,
        16.dp to 1.55f,
        15.dp to 1.60f,
        13.dp to 1.65f,
        12.dp to 1.70f,
        11.dp to 1.75f,
    )

    /**
     * 日历是唯一的例外：格子尺寸由屏幕决定，因此用**半速**缩放
     * （iOS 侧 `calendarMultiplier = 1 + (multiplier(for: 15) - 1) * 0.5`）。
     */
    fun calendarMultiplier(bodyMultiplier: Float): Float = 1f + (bodyMultiplier - 1f) * 0.5f
}

/**
 * 排版。
 *
 * M0 使用 Material 3 基线排版（`Typography()`）；把设计字号映射到 M3 的语义排版角色
 * （displaySmall / titleLarge / bodyLarge …）在 M2 阶段完成，届时本对象会被替换为显式映射，
 * 并叠加动态字体的逐档上限。
 */
val JustDiaryTypography = Typography()
