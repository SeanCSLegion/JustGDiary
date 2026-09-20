package com.cov.justdiary.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Material 3 排版。
 *
 * ## 为什么是"官方基线 + 本项目缩放"而不是"把自定梯级挂上去"
 *
 * 首轮把本项目的设计字号梯级（32/28/24/22/20/18/17/16/15/14/13/12/11）
 * 直接挂到 M3 的 15 个角色上，结果是 `titleLarge = 18`（官方 22）、
 * `bodyLarge = 17`（官方 16）、`headlineSmall = 20`（官方 24）……
 * 于是**所有 M3 组件都不是官方字号**：顶栏标题、对话框、`OutlinedTextField`、
 * 按钮、`NavigationBarItem` 全部偏离。
 *
 * 现在：15 个角色**保持 Material 官方字号**（`Typography()` 基线），只把本项目的
 * **逐档封顶动态字体**套上去 —— `fontScale = 1` 时与 Google 的基线逐字一致，
 * 放大时按各档上限增长、梯级不倒挂。
 *
 * 本项目的自定梯级 [TypeSize] 仍然服务于**内容层**：编辑器段落（28/22/17/15 是
 * 跨端阅读体验的契约）、日历格子、自绘文本。两者职责分开，不再互相污染。
 *
 * `lineHeight` 保持 M3 的角色行高；编辑器的段落行距（正文 0.13×、引用 0.5×）
 * 是**绘制规则**，在编辑器渲染里单独处理（与 iOS 一致），不混进通用排版。
 */
fun justDiaryTypography(fontScale: Float): Typography {
    val base = Typography()

    /** 保留 M3 官方的字号与字重，只把**字号**按本项目的逐档上限重算。 */
    fun TextStyle.scaledRole(): TextStyle = copy(
        fontSize = DynamicTypeMetrics.effectiveSpForSize(fontSize.value, fontScale),
        lineHeight = if (lineHeight == TextUnit.Unspecified) {
            TextUnit.Unspecified
        } else {
            DynamicTypeMetrics.effectiveSpForSize(lineHeight.value, fontScale)
        },
    )

    return Typography(
        displayLarge = base.displayLarge.scaledRole(),
        displayMedium = base.displayMedium.scaledRole(),
        displaySmall = base.displaySmall.scaledRole(),
        headlineLarge = base.headlineLarge.scaledRole(),
        headlineMedium = base.headlineMedium.scaledRole(),
        headlineSmall = base.headlineSmall.scaledRole(),
        titleLarge = base.titleLarge.scaledRole(),
        titleMedium = base.titleMedium.scaledRole(),
        titleSmall = base.titleSmall.scaledRole(),
        bodyLarge = base.bodyLarge.scaledRole(),
        bodyMedium = base.bodyMedium.scaledRole(),
        bodySmall = base.bodySmall.scaledRole(),
        labelLarge = base.labelLarge.scaledRole(),
        labelMedium = base.labelMedium.scaledRole(),
        labelSmall = base.labelSmall.scaledRole(),
    )
}

/**
 * 预览与测试用的默认排版（fontScale = 1）。
 *
 * 运行时的排版由 [justDiaryTypography] 按当前系统字体缩放构造，见 `JustDiaryTheme`。
 */
val JustDiaryTypography: Typography = justDiaryTypography(fontScale = 1f)

/**
 * 编辑器段落的**内容层排版**：段落样式 → Material 3 的排版角色（规范 §3.2、§6.3）。
 *
 * Android 侧按 **Material 3** 来（iOS 侧按 Apple HIG，两端有意不同，见 [TypeSize] 的注释）：
 *
 * | 段落样式 | M3 角色 | 字号 / 行高 |
 * | --- | --- | --- |
 * | `title`（大标题） | `headlineMedium` | 28 / 36 |
 * | `heading`（小标题） | `titleLarge` | 22 / 28 |
 * | `body`（正文） | `bodyLarge` | 16 / 24 |
 * | `quote`（引用） | `bodyMedium` | 14 / 20 |
 *
 * **行高直接用角色的官方值**，不再自己算比例：首轮沿用了 iOS 的
 * `lineSpacing = 0.13 × 字号`（Apple 的 `lineSpacing` 是"额外"行距，不是行高），
 * 在 Compose 里被近似成 `fontSize × 1.13`，既不是 Apple 的语义也不是 Material 的。
 *
 * 段后距 Material **没有规范**（M3 不定义富文本段距），由阅读区的
 * `Arrangement.spacedBy` 统一给节奏，不在这一层编造数值。
 */
object EditorTypography {

    /** 段落样式 → M3 排版角色。**这是样式与字号的唯一映射点。** */
    @Composable
    fun textStyleFor(style: String): TextStyle = when (style) {
        "title" -> MaterialTheme.typography.headlineMedium
        "heading" -> MaterialTheme.typography.titleLarge
        "quote" -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodyLarge
    }

    /** 段落样式对应的设计字号（自绘 / 布局计算用；与 [textStyleFor] 的角色一一对应）。 */
    fun designSizeForStyle(style: String): Dp = when (style) {
        "title" -> TypeSize.editorTitle
        "heading" -> TypeSize.editorHeading
        "quote" -> TypeSize.editorQuote
        else -> TypeSize.editorBody
    }

    /** 编辑区最小高度（E4：默认 160dp，随正文字号缩放）。 */
    val minimumInputHeight: Dp = 160.dp
}
