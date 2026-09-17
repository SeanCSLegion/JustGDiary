package com.cov.justdiary.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 动态取色不可用（或用户关闭）时的回落色板：仅覆写品牌相关的语义角色，其余用 M3 基线。 */
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
 * 应用主题。
 *
 * 设计语言是 **Google Material 3**（已确认：不模仿 iOS 的 Liquid Glass，见方案 §7）。
 *
 * @param darkTheme 浅色/深色。M7 阶段接入设置项 `theme_mode`（light/dark/system）；
 *   应用内切换走 `AppCompatDelegate.setDefaultNightMode`，本次仅按系统取值。
 * @param dynamicColor **Material You 动态取色，默认开启**（Q4 已确认）。开启时取壁纸色；
 *   关闭或系统不支持时回落到以 [BrandSeed] 为准的品牌色板。
 */
@Composable
fun JustDiaryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> FallbackDarkScheme
        else -> FallbackLightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = JustDiaryTypography,
        content = content,
    )
}
