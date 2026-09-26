package com.cov.justdiary.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.cov.justdiary.model.AppSettings

/**
 * 系统「增强对比度 / 高对比文字」是否开启。
 *
 * iOS 侧对应 `@Environment(\.colorSchemeContrast)`：开启时卡片描边从 0.5dp/45%
 * 变成 1.5dp/90% 并加一圈外描边（`docs/animation-and-accessibility.md` §2.4）。
 */
val LocalIncreasedContrast = staticCompositionLocalOf { false }

/**
 * 系统「减弱动态效果 / 关闭动画」是否开启。
 *
 * iOS 侧对应 `@Environment(\.accessibilityReduceMotion)`：开启时两条 morph 从 400ms 的
 * 位移退化为 180ms 的**纯交叉淡入**（`docs/android-design/01-design-system.md` §6.1）。
 *
 * Android 没有与之等价的公开 API，判据取系统动画缩放：
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0`（开发者选项里"动画关闭"，
 * 也是无障碍设置里"移除动画"的落点）。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** 动态取色不可用（或用户关闭）时的回落色板：只覆写品牌相关的语义角色，其余用 M3 基线。 */
private val FallbackLightScheme = lightColorScheme(
    primary = BrandLightPrimary,
    onPrimary = BrandLightOnPrimary,
    primaryContainer = BrandLightPrimaryContainer,
    onPrimaryContainer = BrandLightOnPrimaryContainer,
)

private val FallbackDarkScheme = darkColorScheme(
    primary = BrandDarkPrimary,
    onPrimary = BrandDarkOnPrimary,
    primaryContainer = BrandDarkPrimaryContainer,
    onPrimaryContainer = BrandDarkOnPrimaryContainer,
)

/**
 * 应用主题。设计语言是 **Google Material 3**（已确认：不模仿 iOS 的 Liquid Glass，见方案 §7）。
 *
 * @param themeMode 应用内主题设置（`light` / `dark` / `system`），对应 iOS 的 `theme_mode`。
 * @param dynamicColor **Material You 动态取色，默认开启**（Q4 已确认）。开启且系统支持时
 *   取壁纸色；关闭或系统不支持时回落到以 [BrandSeed] 为准的品牌色板。
 *
 * 排版按当前系统字体缩放构造（[justDiaryTypography]），因此字号会按各档上限分别增长，
 * 且**默认字号下与设计完全一致**。
 */
@Composable
fun JustDiaryTheme(
    themeMode: String = AppSettings.THEME_SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    val darkTheme = isDarkTheme(themeMode)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> FallbackDarkScheme
        else -> FallbackLightScheme
    }

    CompositionLocalProvider(
        LocalIncreasedContrast provides isHighContrastEnabled(context),
        LocalReduceMotion provides isReduceMotionEnabled(context),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = justDiaryTypography(fontScale),
            content = content,
        )
    }
}

/** 系统是否关闭了动画（对应 iOS 的"减弱动态效果"）。取不到时按未开启处理。 */
fun isReduceMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** 系统动画缩放的比例值名（0 = 关闭动画）。 */
private const val ANIMATOR_DURATION_SCALE = "animator_duration_scale"

/**
 * 读取系统的"高对比文字"设置；取不到时按关闭处理。
 *
 * 公开 API `isHighContrastTextEnabled` 只在较新平台上存在，因此先试它、
 * 再回退读系统开关 `high_text_contrast_enabled` —— 两条路都用 `runCatching` 兜住，
 * 保证任何 API 级别上都不会因此崩溃。
 */
fun isHighContrastEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (runCatching { manager?.isHighContrastTextEnabled == true }.getOrDefault(false)) return true
    return runCatching {
        Settings.Secure.getInt(context.contentResolver, HIGH_CONTRAST_SETTING, 0) == 1
    }.getOrDefault(false)
}

/** 系统"高对比文字"开关的 secure setting 名（公开 API 不可用时的回退）。 */
private const val HIGH_CONTRAST_SETTING = "high_text_contrast_enabled"

/**
 * 主题设置 → 是否使用深色。纯函数，便于单测
 * （与 iOS `AppConfigService.colorScheme` 的三值映射等价）。
 *
 * 非法取值按"跟随系统"处理（与 `AppSettings` 的取值校验一致）。
 */
fun darkThemeFor(themeMode: String, systemInDark: Boolean): Boolean = when (themeMode) {
    AppSettings.THEME_LIGHT -> false
    AppSettings.THEME_DARK -> true
    else -> systemInDark
}

/** `@Composable` 包装：跟随系统时读系统设置。 */
@Composable
fun isDarkTheme(themeMode: String): Boolean = darkThemeFor(themeMode, isSystemInDarkTheme())
