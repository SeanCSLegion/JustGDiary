package com.cov.justdiary.ui.diary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内联图片尺寸的规则测试。
 *
 * 规则来自 iOS `MediaViews.swift` 的 `DiaryImageView`：**铺满正文列宽、按比例推高度**
 * （库里存的 `w/h` 只当比例用）。这条同时是"编辑态与阅读态同一张图"的保证 ——
 * 阅读态是 `fillMaxWidth + aspectRatio`，编辑态此前按自然宽度居中，两边不一样。
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
    fun `narrow image fills the column instead of staying small`() {
        // 200×100，容器 1000 → 铺满 1000，高按比例 500（与阅读态的 fillMaxWidth 一致）
        assertEquals(1000 to 500, ImageDisplaySize.fit(1000, 200.0, 100.0, 0, 0))
    }

    @Test
    fun `tall image keeps the ratio and may exceed the container height`() {
        // 1000×3000（竖构图），容器 1000 → 1000×3000：iOS 没有高度上限
        assertEquals(1000 to 3000, ImageDisplaySize.fit(1000, 1000.0, 3000.0, 0, 0))
    }

    @Test
    fun `a very narrow column still wins over the natural width`() {
        // 10×10，容器 8 → 8×8（"布局决定宽度"这一条没有例外）
        assertEquals(8 to 8, ImageDisplaySize.fit(8, 10.0, 10.0, 0, 0))
    }

    @Test
    fun `unknown container width falls back to the natural size`() {
        assertEquals(640 to 480, ImageDisplaySize.fit(0, 640.0, 480.0, 0, 0))
    }

    @Test
    fun `missing stored size falls back to the bitmap size`() {
        // 位图 320×160、容器 1000 → 铺满 1000×500（比例取自位图）
        assertEquals(1000 to 500, ImageDisplaySize.fit(1000, null, null, 320, 160))
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
        // 600×600 的方图、容器 1000 → 1000×1000（铺满之后仍然是正方形）
        assertEquals(1000 to 1000, ImageDisplaySize.fit(1000, 600.0, 600.0, 0, 0))
        // 容器比图小 → 按容器缩，比例不变
        assertEquals(300 to 300, ImageDisplaySize.fit(300, 600.0, 600.0, 0, 0))
    }
}
