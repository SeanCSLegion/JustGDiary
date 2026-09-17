package com.cov.justdiary.ui.diary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内联图片尺寸的规则测试。
 *
 * 规则来自 iOS `MediaViews.swift` 的 `DiaryImageView`：
 * `w = min(max(40, containerWidth), max(40, displayW))`、`h = displayH * w / displayW`。
 *
 * 原先 Android 侧给 `ImageSpan` 的是**写死的正方形边界**，非正方形图片会被拉伸变形 ——
 * 这几条断言就是防止它退回去。
 */
class ImageDisplaySizeTest {

    @Test
    fun `wide image is capped by the container width and keeps its ratio`() {
        // 4000×2000，容器 1000 → 宽 1000、高 500
        assertEquals(1000 to 500, ImageDisplaySize.fit(1000, 4000.0, 2000.0, 0, 0))
    }

    @Test
    fun `narrow image keeps its natural width instead of being stretched`() {
        // 200×100，容器 1000 → 仍是 200×100（不放大）
        assertEquals(200 to 100, ImageDisplaySize.fit(1000, 200.0, 100.0, 0, 0))
    }

    @Test
    fun `tall image keeps the ratio and may exceed the container height`() {
        // 1000×3000（竖构图），容器 1000 → 1000×3000：iOS 没有高度上限
        assertEquals(1000 to 3000, ImageDisplaySize.fit(1000, 1000.0, 3000.0, 0, 0))
    }

    @Test
    fun `tiny image is not shrunk below 40`() {
        // 10×10，容器 8 → 两侧都兜到 40 → 40×40
        assertEquals(40 to 40, ImageDisplaySize.fit(8, 10.0, 10.0, 0, 0))
    }

    @Test
    fun `unknown container width falls back to the natural size`() {
        assertEquals(640 to 480, ImageDisplaySize.fit(0, 640.0, 480.0, 0, 0))
    }

    @Test
    fun `missing stored size falls back to the bitmap size`() {
        assertEquals(320 to 160, ImageDisplaySize.fit(1000, null, null, 320, 160))
        // 只存了宽、没存高：高取位图的高
        assertEquals(1000 to 500, ImageDisplaySize.fit(1000, 4000.0, null, 4000, 2000))
    }

    @Test
    fun `a zero sized bitmap yields no size at all`() {
        assertEquals(0 to 0, ImageDisplaySize.fit(1000, null, null, 0, 0))
        assertEquals(0 to 0, ImageDisplaySize.fit(1000, 0.0, 0.0, 0, 0))
    }

    @Test
    fun `square images stay square`() {
        assertEquals(600 to 600, ImageDisplaySize.fit(1000, 600.0, 600.0, 0, 0))
    }
}
