package com.cov.justdiary.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Material 3 排版：把本项目的**设计字号梯级**映射到 M3 的语义排版角色。
 *
 * 为什么不是直接用 `Typography()` 的基线值：本项目有一套既定的设计字号
 * （32 / 28 / 24 / 22 / 20 / 18 / 17 / 16 / 15 / 14 / 13 / 12 / 11，见 [TypeSize]），
 * 屏幕上每一处文字都应该取其中之一，而不是各自拍脑袋。把它们挂到 M3 的角色上之后，
 * 组件里写 `MaterialTheme.typography.titleMedium` 就等于拿到设计字号 20，
 * 同时仍然处在 M3 的排版体系内。
 *
 * 字号通过 [DynamicTypeMetrics.effectiveSp] 解析（含逐档上限），因此**默认字号下
 * 每个角色都恰好等于设计字号**，放大字号时才按各档上限分别增长。
 *
 * `lineHeight` 一律留给平台默认：编辑器的段落行距（正文 0.13×、引用 0.5×）是**绘制规则**，
 * 在 M5 的编辑器渲染里单独处理（与 iOS 一致），不混进通用排版。
 */
fun justDiaryTypography(fontScale: Float): Typography {
    fun style(designSize: Dp, weight: FontWeight) = TextStyle(
        fontSize = DynamicTypeMetrics.effectiveSp(designSize, fontScale),
        fontWeight = weight,
        lineHeight = TextUnit.Unspecified,
    )

    return Typography(
        // 大字号：日历的月/年标题、编辑器大标题
        displayLarge = style(TypeSize.display, FontWeight.Normal),
        displayMedium = style(TypeSize.editorTitle, FontWeight.Bold),
        displaySmall = style(TypeSize.pageTitle, FontWeight.Bold),
        // 标题
        headlineLarge = style(TypeSize.pageTitle, FontWeight.SemiBold),
        headlineMedium = style(TypeSize.editorHeading, FontWeight.SemiBold),
        headlineSmall = style(TypeSize.cardTitle, FontWeight.SemiBold),
        // 小标题与行内主文本
        titleLarge = style(TypeSize.headerTitle, FontWeight.SemiBold),
        titleMedium = style(TypeSize.sheetTitle, FontWeight.Medium),
        titleSmall = style(TypeSize.rowTitle, FontWeight.Medium),
        // 正文
        bodyLarge = style(TypeSize.editorBody, FontWeight.Normal),
        bodyMedium = style(TypeSize.rowTitle, FontWeight.Normal),
        bodySmall = style(TypeSize.rowSub, FontWeight.Normal),
        // 标签、芯片、徽章
        labelLarge = style(TypeSize.chip, FontWeight.Medium),
        labelMedium = style(TypeSize.badge, FontWeight.Medium),
        labelSmall = style(TypeSize.caption, FontWeight.Normal),
    )
}

/**
 * 预览与测试用的默认排版（fontScale = 1）。
 *
 * 运行时的排版由 [justDiaryTypography] 按当前系统字体缩放构造，见 `JustDiaryTheme`。
 */
val JustDiaryTypography: Typography = justDiaryTypography(fontScale = 1f)

/** 编辑器段落样式 → 设计字号（与 `ContentPartStyle` 的语义一一对应）。 */
object EditorTypography {
    fun designSizeForStyle(style: String): Dp = when (style) {
        "title" -> TypeSize.editorTitle
        "heading" -> TypeSize.editorHeading
        "quote" -> TypeSize.editorQuote
        else -> TypeSize.editorBody
    }

    /**
     * 段落行距比例（`RichTextEngine.swift:40-42`）：引用 0.5×，其余 0.13×。
     * 只影响绘制、不落库 —— 读取时由块类型重新推导（与 iOS 一致）。
     */
    fun lineSpacingRatio(style: String): Float = if (style == "quote") 0.5f else 0.13f

    /**
     * 段后距比例（`RichTextEngine.swift:46-53`）：大标题 0.4、小标题 0.3、引用 0.4、正文 0。
     */
    fun paragraphSpacingRatio(style: String): Float = when (style) {
        "title" -> 0.4f
        "heading" -> 0.3f
        "quote" -> 0.4f
        else -> 0f
    }

    /** 编辑区最小高度（E4：默认 160dp，随正文字号缩放）。 */
    val minimumInputHeight: Dp = 160.dp
}
