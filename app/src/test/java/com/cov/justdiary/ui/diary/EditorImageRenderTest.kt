package com.cov.justdiary.ui.diary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.StaticLayout
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 编辑态的图片行：**12dp 圆角 + 上下 10dp 留白**，与阅读态的 `InlineImage` 同一套几何。
 *
 * 阅读态是一张 Compose `Image`，这两项由布局直接给；编辑态是 `ImageSpan`，平台只把
 * drawable 塞进行里 —— 早先它把图片画成直角、且贴着上下文字（用户反馈"图片排版不一致"）。
 * 这里用真实排版 + 真实绘制探针，量的是"画出来"的结果，不是算出来的期望值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorImageRenderTest {

    private val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
    private fun px(value: Float): Int = (value * density).toInt()

    private val imageColor = Color.rgb(0x20, 0x60, 0xC0)
    private val verticalPadding = px(10f)
    private val cornerRadius = px(12f)
    private val imageWidth = 300
    private val imageHeight = 150

    private fun render(): Bitmap {
        val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(imageColor)
        val bodyMetrics = BlockMetrics(
            textSizePx = px(16f),
            lineHeightPx = px(24f),
            paragraphSpacingPx = px(8f),
        )
        val spanned = SpannableBridge.toSpannable(
            lines = listOf(
                EditorLine(
                    imageSrc = "images/test.jpg",
                    imageWidth = imageWidth.toDouble(),
                    imageHeight = imageHeight.toDouble(),
                ),
            ),
            imageProvider = { bitmap },
            containerWidthPx = imageWidth,
            metricsFor = { bodyMetrics },
            imageStyle = ImageStyle(
                verticalPaddingPx = verticalPadding,
                cornerRadiusPx = cornerRadius.toFloat(),
                placeholderColor = Color.GRAY,
            ),
        )
        val paint = TextPaint().apply { textSize = px(16f).toFloat() }
        val layout = StaticLayout.Builder
            .obtain(spanned, 0, spanned.length, paint, imageWidth)
            .build()

        val field = Bitmap.createBitmap(imageWidth, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(field)
        canvas.drawColor(Color.WHITE)
        layout.draw(canvas)

        // 图片本身 + 上下各 10dp（最后一行没有段距）就是这一行的总高
        assertEquals(imageHeight + verticalPadding * 2, layout.height)
        return field
    }

    @Test
    fun `the image row keeps ten dp above and below the picture`() {
        val field = render()
        val rows = (0 until field.height).filter { isImage(field.getPixel(imageWidth / 2, it)) }
        assertEquals(verticalPadding, rows.first())
        assertEquals(verticalPadding + imageHeight - 1, rows.last())
        assertTrue("上方留白必须是背景色", !isImage(field.getPixel(imageWidth / 2, verticalPadding - 1)))
        assertTrue(
            "下方留白必须是背景色",
            !isImage(field.getPixel(imageWidth / 2, verticalPadding + imageHeight)),
        )
    }

    @Test
    fun `the image corners are rounded like the reader's`() {
        val field = render()
        val top = verticalPadding
        val middle = verticalPadding + imageHeight / 2
        assertTrue("图片正中必须是图片色", isImage(field.getPixel(imageWidth / 2, middle)))
        assertFalse("左上角要被圆角切掉", isImage(field.getPixel(0, top)))
        assertFalse("右上角要被圆角切掉", isImage(field.getPixel(imageWidth - 1, top)))
        assertTrue("竖直中线的左沿仍应是图片色", isImage(field.getPixel(1, middle)))
    }

    private fun isImage(color: Int): Boolean =
        kotlin.math.abs(Color.red(color) - Color.red(imageColor)) <= 12 &&
            kotlin.math.abs(Color.green(color) - Color.green(imageColor)) <= 12 &&
            kotlin.math.abs(Color.blue(color) - Color.blue(imageColor)) <= 12
}
