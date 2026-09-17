package com.cov.justdiary.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 品牌色。
 *
 * `BrandSeed` 对齐 iOS 的 `Theme.seed`（`Services/Theme.swift:4`，#2563EB），
 * 在 Android 侧同时承担两个角色（见 docs/android-migration-plan.md §7.5）：
 *  1. Material You 动态取色的**种子色**（Android 12+ 实际取壁纸颜色，本值不参与）；
 *  2. 动态取色不可用、或用户在设置里关闭时的**回落色板**。
 */
val BrandSeed = Color(0xFF2563EB)

// ---------------------------------------------------------------------------
// 回落色板：品牌蓝的 M3 tonal 取值。
// 说明：这些值是按 Material 3 的色调规则（tone 40/90/10 用于浅色，80/30/90 用于深色）
// 为 #2563EB 选定的近似值；若后续要"从种子精确生成整套 scheme"，可接入
// material-color-utilities 并在 M2 阶段替换本文件中的常量。
// ---------------------------------------------------------------------------

internal val BrandLightPrimary = Color(0xFF2563EB)
internal val BrandLightOnPrimary = Color(0xFFFFFFFF)
internal val BrandLightPrimaryContainer = Color(0xFFDBEAFE)
internal val BrandLightOnPrimaryContainer = Color(0xFF001A41)

internal val BrandDarkPrimary = Color(0xFFAAC7FF)
internal val BrandDarkOnPrimary = Color(0xFF002E69)
internal val BrandDarkPrimaryContainer = Color(0xFF00458F)
internal val BrandDarkOnPrimaryContainer = Color(0xFFD6E3FF)
