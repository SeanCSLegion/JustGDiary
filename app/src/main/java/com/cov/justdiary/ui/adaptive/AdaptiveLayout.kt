package com.cov.justdiary.ui.adaptive

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 版面判定。移植自 iOS `Views/Components/AdaptiveLayout.swift`。
 *
 * ## 只按可用尺寸判定，绝不按机型/方向
 *
 * iOS 的原注释说得很清楚：**只看当前可用宽高**，不看 `UIDevice.idiom`、不看 orientation、
 * 不读 `UIScreen.main` —— 折叠屏展开后仍是手机但宽高都变成 regular，任何按机型分支的代码
 * 都会错。Android 侧的对应做法就是只吃 `BoxWithConstraints`/`WindowSizeClass` 给的尺寸。
 *
 * ## 两组阈值（都有出处）
 *
 * | 用途 | 阈值 | 出处 |
 * | --- | --- | --- |
 * | 主导航形态 | `<600dp` 底栏 / `≥600dp` 侧栏 | Material 3 窗口尺寸类（决策 Q10） |
 * | 内容分栏与列数 | `<700` / `700–999` / `≥1000dp` | iOS `AdaptiveLayout` 阈值原样保留（决策 Q17） |
 * | 首页左右分栏 | 内容宽 `≥548.5dp` **且** 宽 > 高 | `splitsMasterDetail`（竖屏够宽也保持年/月/周三态 morph） |
 *
 * 548.5 = 2×16(页边距) + 16.5(栏间距 + 0.5 分隔线) + 260(主栏最窄) + 240(详情栏最窄)。
 * 这是 iOS 的当前值（v2 文档里的 700 是旧值，已作废，见设计规范 README 裁决 #7）。
 *
 * 全部是纯计算，因此可以在 JVM 上把临界值逐条断言（见 `AdaptiveLayoutTest`）。
 */
data class AdaptiveLayout(
    val width: Float,
    val height: Float,
    val safeLeading: Float = 0f,
    val safeTrailing: Float = 0f,
    val safeTop: Float = 0f,
    val safeBottom: Float = 0f,
) {

    /** 可用于放内容、且不会被系统占位吃掉的宽度（横屏左右安全区分别扣）。 */
    val contentWidth: Float get() = max(0f, width - max(0f, safeLeading) - max(0f, safeTrailing))

    /**
     * 首页（以及足迹）是否左右分栏。
     *
     * **必须同时**满足"内容宽够"与"宽 > 高"：竖屏即使够宽也保持单栏，
     * 竖屏的年/月/周三态 morph 交互完全不动（与 iOS 一致）。
     */
    val splitsMasterDetail: Boolean get() = contentWidth >= SPLIT_MIN_CONTENT_WIDTH && width > height

    /**
     * 主栏（月历）宽度。
     *
     * 7 列每格至少 ~37dp 才好点，因此取"内容宽的 46%"并夹在 260…440 之间 ——
     * 更宽只是把格子拉大，不会让内容更好读。iOS 在 750dp 内容宽下得到 345dp，
     * 与设计稿一致；Android 内容宽更大时按同一公式放大（平板横屏到 440 封顶）。
     */
    val masterWidth: Float
        get() = min(MASTER_MAX_WIDTH, max(MASTER_MIN_WIDTH, (contentWidth * MASTER_WIDTH_RATIO).roundToInt().toFloat()))

    /** 主导航形态：紧凑用底栏，中等/扩展用侧栏（M3 窗口尺寸类）。 */
    val navForm: NavForm get() = if (width >= RAIL_MIN_WIDTH) NavForm.Rail else NavForm.Bar

    /** 内容档位（设置列数、筛选压行、并排布局都用它）。 */
    val tier: Tier get() = when {
        width < WIDE_MIN_WIDTH -> Tier.Compact
        width < EXTRA_WIDE_MIN_WIDTH -> Tier.Medium
        else -> Tier.Expanded
    }

    /** 设置页列数：1 列（<680dp）→ 2 列（≥680dp）→ 3 列（≥1000dp）。 */
    val settingsColumns: Int get() = when {
        width < SETTINGS_TWO_COLUMN_MIN_WIDTH -> 1
        width < EXTRA_WIDE_MIN_WIDTH -> 2
        else -> 3
    }

    /** 搜索页的两组筛选条件是否左右并排（横屏省约 60dp 高度）。 */
    val filtersSideBySide: Boolean get() = splitsMasterDetail

    /** 足迹饼图/柱状图高度：竖屏 132dp，宽屏 200dp。 */
    val trendHeight: Float get() = if (splitsMasterDetail) TREND_HEIGHT_WIDE else TREND_HEIGHT_PHONE

    /**
     * 足迹"趋势图 | 地点清单"并排时，清单栏的宽度。
     *
     * iOS：`min(320, max(240, contentWidth × 0.4))`。
     */
    val footprintListWidth: Float
        get() = min(320f, max(240f, contentWidth * 0.4f))

    /**
     * 足迹并排时趋势图块的可用高度。
     *
     * iOS：`max(140, min(190, screenH − 262))`。
     */
    val footprintBlockHeight: Float
        get() = max(140f, min(190f, height - FOOTPRINT_BLOCK_HEIGHT_RESERVE))

    /** 正文列宽上限（阅读 660 / 编辑 620），宽屏必须限宽，否则一行 100+ 字。 */
    fun contentColumn(maxWidth: Float = READING_MAX_WIDTH): Float =
        min(maxWidth, max(MIN_CONTENT_COLUMN, width - 2 * PAGE_PADDING))

    enum class NavForm { Bar, Rail }

    enum class Tier { Compact, Medium, Expanded }

    companion object {
        /** 侧栏的最小宽度（M3 窗口尺寸类的中等档下沿）。 */
        const val RAIL_MIN_WIDTH = 600f

        /** 内容档位阈值（iOS 原样保留）。 */
        const val WIDE_MIN_WIDTH = 700f
        const val EXTRA_WIDE_MIN_WIDTH = 1000f

        /** 设置页两列的下沿（与 iOS 的 `columnCount` 一致）。 */
        const val SETTINGS_TWO_COLUMN_MIN_WIDTH = 680f

        /** 首页/足迹分栏的最小内容宽度（iOS 当前值；v2 的 700 已作废）。 */
        const val SPLIT_MIN_CONTENT_WIDTH = 548.5f

        const val MASTER_WIDTH_RATIO = 0.46f
        const val MASTER_MIN_WIDTH = 260f
        const val MASTER_MAX_WIDTH = 440f

        const val PAGE_PADDING = 16f
        const val READING_MAX_WIDTH = 660f
        const val EDITING_MAX_WIDTH = 620f
        const val MIN_CONTENT_COLUMN = 240f

        const val TREND_HEIGHT_PHONE = 132f
        const val TREND_HEIGHT_WIDE = 200f
        const val FOOTPRINT_BLOCK_HEIGHT_RESERVE = 262f

        /** 预览/测试的兜底值：还没有读到几何时用（真实布局由 CompositionLocal 下发）。 */
        val Default = AdaptiveLayout(width = 390f, height = 844f)
    }
}

/**
 * 当前版面。由 `RootScreen` 读一次几何后下发，**页面内部不要再各自读屏幕尺寸** ——
 * 这正是 iOS 原注释里反复强调的那条：一个判据、一处读取。
 */
val LocalAdaptiveLayout = staticCompositionLocalOf { AdaptiveLayout.Default }

/**
 * 从当前几何与系统安全区解析出 [AdaptiveLayout]。
 *
 * 只读一次：调用方（`RootScreen`）在 `BoxWithConstraints` 里拿到可用宽高后传进来，
 * 页面内部不要再自己读尺寸。
 */
@Composable
fun rememberAdaptiveLayout(width: Float, height: Float): AdaptiveLayout {
    val insets = WindowInsets.safeDrawing
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // 上下只有 density，左右还要 layoutDirection（横屏时左右安全区常常不等）
    fun dp(px: Int): Float = with(density) { px.toDp().value }

    return AdaptiveLayout(
        width = width,
        height = height,
        safeLeading = dp(insets.getLeft(density, layoutDirection)),
        safeTrailing = dp(insets.getRight(density, layoutDirection)),
        safeTop = dp(insets.getTop(density)),
        safeBottom = dp(insets.getBottom(density)),
    )
}

/**
 * 把当前几何解析成 [AdaptiveLayout] 并下发。
 *
 * 全屏路由（日记页）没有外层 `Scaffold` 的 `innerPadding`，因此它自带一个
 * `BoxWithConstraints`：这样"页面内部不读屏幕尺寸"这条规则在任何入口都成立。
 */
@Composable
fun AdaptiveLayoutHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val adaptive = rememberAdaptiveLayout(maxWidth.value, maxHeight.value)
        CompositionLocalProvider(LocalAdaptiveLayout provides adaptive) { content() }
    }
}
