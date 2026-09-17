package com.cov.justdiary.ui.diary

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 内联图片的显示尺寸。移植自 iOS `MediaViews.swift` 的 `DiaryImageView`：
 *
 * ```swift
 * let w = min(max(40, geo.size.width), max(40, displayW))
 * let fitH = max(1, displayH * w / max(displayW, 1))
 * ```
 *
 * 也就是：**按图片的自然宽度显示，但不超过可用宽度，也不小于 40**；高度按原始宽高比推出来。
 * iOS 没有"最大高度"上限 —— 竖向长图就是会很高，与照片本身一致。
 *
 * ## 为什么单独抽出来
 *
 * 原先 Android 侧给 `ImageSpan` 的边界是**写死的正方形**（64×64 / 占位尺寸），
 * 于是任何非正方形图片都会被拉伸变形。这里把它变成纯函数，真机与单测共用同一套规则。
 */
object ImageDisplaySize {

    /** 最小显示宽度（与 iOS 的 `max(40, …)` 一致）。 */
    const val MIN_WIDTH: Double = 40.0

    /**
     * 计算显示尺寸（像素）。
     *
     * @param containerWidthPx 可用宽度；`<= 0` 表示还不知道容器宽度（返回自然尺寸）
     * @param storedWidth 库里存的原始宽（`content_json` 的 `w`，可能为空）
     * @param storedHeight 库里存的原始高
     * @param fallbackWidth 位图实际宽（`storedWidth` 缺失时用）
     * @param fallbackHeight 位图实际高
     */
    fun fit(
        containerWidthPx: Int,
        storedWidth: Double?,
        storedHeight: Double?,
        fallbackWidth: Int,
        fallbackHeight: Int,
    ): Pair<Int, Int> {
        val naturalWidth = storedWidth?.takeIf { it > 0 } ?: fallbackWidth.toDouble()
        val naturalHeight = storedHeight?.takeIf { it > 0 } ?: fallbackHeight.toDouble()
        if (naturalWidth <= 0 || naturalHeight <= 0) return 0 to 0

        val available = if (containerWidthPx > 0) containerWidthPx.toDouble() else naturalWidth
        val width = min(max(MIN_WIDTH, available), max(MIN_WIDTH, naturalWidth))
        val height = max(1.0, naturalHeight * width / max(naturalWidth, 1.0))
        return width.roundToInt() to height.roundToInt()
    }
}
