package com.cov.justdiary.ui.diary

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 内联图片的显示尺寸。
 *
 * 规则：**铺满正文列宽，按原始宽高比推高度**（移植自 iOS `MediaViews.swift` 的
 * `DiaryImageView`：「Fills the column, keeping the picture's aspect ratio … The stored
 * pair is only the aspect ratio here — the layout decides the width」）。
 *
 * 库里存的 `w/h` 因此**只当比例用**，不再当"自然宽度"：
 * 早先编辑态按 `min(可用宽, 自然宽)` 显示，于是窄图在编辑时居中小小一张、点进阅读态
 * 却被拉满整列 —— 同一张图在两种模式下两个样子（R30 记的那条不一致，本次收掉）。
 *
 * ## 为什么单独抽出来
 *
 * 原先 Android 侧给 `ImageSpan` 的边界是**写死的正方形**（64×64 / 占位尺寸），
 * 于是任何非正方形图片都会被拉伸变形。这里把它变成纯函数，真机与单测共用同一套规则。
 */
object ImageDisplaySize {

    /**
     * 计算显示尺寸（像素）。
     *
     * @param containerWidthPx 可用宽度（正文列宽）；`<= 0` 表示还不知道容器宽度，
     *   这时退到自然尺寸（首帧、或纯逻辑测试）
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

        val width = if (containerWidthPx > 0) containerWidthPx.toDouble() else naturalWidth
        val height = max(1.0, naturalHeight * width / naturalWidth)
        return width.roundToInt() to height.roundToInt()
    }
}
