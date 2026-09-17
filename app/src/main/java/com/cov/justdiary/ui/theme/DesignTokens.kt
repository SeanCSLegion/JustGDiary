package com.cov.justdiary.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计度量令牌。**数值逐字移植**自 iOS `Views/Components/DesignSystem.swift`。
 *
 * 与 iOS 的关系见 docs/android-migration-plan.md §7.2：Android 侧改用 Material 3 的形状与
 * 排版体系（`MaterialTheme.shapes` / `MaterialTheme.typography`），**不再把 iOS 的圆角
 * 令牌套到 M3 组件上**。这里保留 [Radius] 服务于**自绘组件**（日历网格、年度柱状图、
 * 分享长图）—— 那些地方沿用原布局数学，避免几何变化。
 */
object Radius {

    /** 内容卡片、列表行、hero 面板。 */
    val card: Dp = 20.dp

    /** 悬浮控件面：编辑器格式栏、sheet。 */
    val panel: Dp = 26.dp

    /** 输入框与卡片内的次级面。 */
    val field: Dp = 14.dp

    /** 卡片内联图片。 */
    val image: Dp = 14.dp

    /** 图标徽章与小色块。 */
    val badge: Dp = 12.dp

    /** 图表柱、进度条。 */
    val bar: Dp = 4.dp

    /** 2dp 高的标记线（日历"有日记"下划线取 1dp 圆角）。 */
    val hairline: Dp = 1.dp

    /**
     * 嵌套圆角：内圆角 = 外圆角 − 内缩量（下限 0）。
     *
     * Material 3 没有等价概念，因此原样保留为纯函数。直接把内外圆角设成同一个值
     * 会产生视觉上的"尖角内衬"。
     */
    fun concentric(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(0.dp)
}

/** 间距令牌（只收敛已被重复使用的值，不重新排版）。 */
object Spacing {
    val card: Dp = 12.dp

    /** 卡片图标徽章与文字之间的距离。 */
    val rowIcon: Dp = 12.dp

    /** 最小点击目标。 */
    val hitTarget: Dp = 44.dp

    /** 页面统一边距（iOS 侧十六处一致）。 */
    val page: Dp = 16.dp
}

/**
 * 命名语义字号（未缩放的**设计字号**）。数值与 iOS `TypeSize` 一一对应。
 *
 * 实际绘制字号由 [DynamicTypeMetrics.effectiveSp] 结合系统字体缩放算出，
 * **绝不使用本对象里的裸值直接绘制**（否则会忽略系统文字大小设置）。
 */
object TypeSize {
    /** 首页日历的月/年大标题。 */
    val display: Dp = 32.dp

    /** `PageHeader` 的页面标题。 */
    val pageTitle: Dp = 24.dp

    /** sheet 标题。 */
    val sheetTitle: Dp = 17.dp

    /** 卡片标题（阅读页的日期）。 */
    val cardTitle: Dp = 20.dp

    /** 紧凑头部胶囊里的标题（首页年月）。 */
    val headerTitle: Dp = 18.dp

    /** 足迹统计行里被强调的数字。 */
    val statValue: Dp = 18.dp

    /** 列表行/设置行的主文本。 */
    val rowTitle: Dp = 15.dp

    /** 设置行的说明第二行。 */
    val rowSub: Dp = 13.dp

    /** 设置行的尾部取值。 */
    val rowValue: Dp = 15.dp

    /** 卡片内的小节标题。 */
    val sectionTitle: Dp = 13.dp

    /** 芯片/胶囊标签。 */
    val chip: Dp = 14.dp

    /** 计数徽章。 */
    val badge: Dp = 12.dp

    /** 卡片标题下的时间与地点行。 */
    val meta: Dp = 13.dp

    /** 最次要的辅助文本。 */
    val caption: Dp = 12.dp

    // ---- 编辑器的语义段落梯级（title 28 / heading 22 / body 17 / quote 15）----

    val editorTitle: Dp = 28.dp
    val editorHeading: Dp = 22.dp
    val editorBody: Dp = 17.dp
    val editorQuote: Dp = 15.dp

    /** 编辑器四档梯级，从大到小（往返不变量测试用）。 */
    val editorLadder: List<Dp> = listOf(editorTitle, editorHeading, editorBody, editorQuote)

    /** 全部设计字号，从大到小。 */
    val ladder: List<Dp> = listOf(
        display, editorTitle, pageTitle, editorHeading, cardTitle,
        headerTitle, statValue, editorBody, 16.dp, rowTitle, editorQuote,
        chip, rowSub, rowValue, sectionTitle, meta, badge, caption, 11.dp,
    ).distinct().sortedDescending()
}

/**
 * 动态字体的缩放上限与解析。**逐字移植**自 iOS `DesignSystem.swift:107-220`
 * （`DynamicTypeMetrics`）。
 *
 * ## iOS 侧的做法与原因
 *
 * iOS 把每个设计字号归到**最接近的 Apple 文本样式**，再用 `UIFontMetrics` 取该样式的
 * 缩放值，最后按样式封顶。封顶是**有意的偏离**：Apple 的原始曲线在 AX5 会把 body 从
 * 17pt 推到约 53pt，而且涨得**比标题样式更快**，套到本项目的字号梯级上会出现层级倒挂
 * （15pt 正文比 22pt 标题还大）。上限对"最需要放大的小字号"给得最高，对本来就大的字号
 * 给得最低，从而在保留 Apple 曲线形状的同时让整条梯级保持单调。
 *
 * ## Android 的等价物
 *
 * Android 只有一个全局 `fontScale`，没有"每个文本样式一条曲线"。因此这里的规则是：
 *
 * ```
 * effectiveMultiplier(size, fontScale) = min(ceiling(size), max(1, fontScale))
 * ```
 *
 * - `fontScale = 1`（默认）→ 全部为 1，**默认外观与设计完全一致**；
 * - `fontScale` 取到 2.0 时，正好复现 iOS 在 AX5 的实测表
 *   （32→44.8、17→25.5、15→24 …），因为那些值就是各档的上限；
 * - 由于每档上限不同且 `min` 单调，缩放后梯级**永不倒挂**（由单测守住）。
 */
object DynamicTypeMetrics {

    /**
     * 各设计字号对应的 **Apple 文本样式**，按"最接近的默认字号"匹配
     * （`DesignSystem.swift:150-162`）。匹配默认字号意味着默认档下每个度量都返回原值。
     */
    fun appleTextStyle(designSize: Float): AppleTextStyle = when {
        designSize >= 34f -> AppleTextStyle.LargeTitle
        designSize >= 28f -> AppleTextStyle.Title1
        designSize >= 22f -> AppleTextStyle.Title2
        designSize >= 20f -> AppleTextStyle.Title3
        designSize >= 17f -> AppleTextStyle.Body
        designSize >= 16f -> AppleTextStyle.Callout
        designSize >= 15f -> AppleTextStyle.Subheadline
        designSize >= 13f -> AppleTextStyle.Footnote
        designSize >= 12f -> AppleTextStyle.Caption1
        else -> AppleTextStyle.Caption2
    }

    /** 各 Apple 文本样式的增长上限（`DesignSystem.swift:124-136`，逐字）。 */
    fun ceiling(style: AppleTextStyle): Float = when (style) {
        AppleTextStyle.LargeTitle -> 1.35f
        AppleTextStyle.Title1 -> 1.40f
        AppleTextStyle.Title2, AppleTextStyle.Title3 -> 1.45f
        AppleTextStyle.Body -> 1.50f
        AppleTextStyle.Callout -> 1.55f
        AppleTextStyle.Subheadline -> 1.60f
        AppleTextStyle.Footnote -> 1.65f
        AppleTextStyle.Caption1 -> 1.70f
        AppleTextStyle.Caption2 -> 1.75f
    }

    /** 设计字号对应的增长上限。 */
    fun ceilingForSize(designSize: Float): Float = ceiling(appleTextStyle(designSize))

    /**
     * 该设计字号在当前系统字体缩放下的**有效倍率**。
     *
     * 见类注释里的公式；[fontScale] 必须是正的，异常值按 1 处理。
     */
    fun effectiveMultiplier(designSize: Float, fontScale: Float): Float {
        val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        return minOf(ceilingForSize(designSize), maxOf(1f, scale))
    }

    /**
     * 直接可用于绘制的 `sp`。
     *
     * Compose 会对 `sp` **自动乘一次 fontScale**，所以这里先把系统缩放除掉，
     * 让最终绘制值恰好等于 `designSize × min(ceiling, fontScale)`。
     */
    fun effectiveSp(designSize: Dp, fontScale: Float): TextUnit {
        val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        val multiplier = effectiveMultiplier(designSize.value, scale)
        return (designSize.value * multiplier / scale).sp
    }

    /**
     * 日历专用：**半速**缩放（`DesignSystem.swift:214-219`）。
     *
     * 日历格子尺寸由屏幕决定，日号不能跟随完整曲线，否则会挤出格子。
     */
    fun calendarMultiplier(fontScale: Float): Float {
        val bodyLike = effectiveMultiplier(15f, fontScale)
        return 1f + (bodyLike - 1f) * 0.5f
    }

    /** 半速的 `sp`（同样先除掉 Compose 的自动缩放）。 */
    fun calendarSp(designSize: Dp, fontScale: Float): TextUnit {
        val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        return (designSize.value * calendarMultiplier(scale) / scale).sp
    }
}

/** iOS `UIFont.TextStyle` 里本设计用到的子集。 */
enum class AppleTextStyle {
    LargeTitle,
    Title1,
    Title2,
    Title3,
    Body,
    Callout,
    Subheadline,
    Footnote,
    Caption1,
    Caption2,
}
