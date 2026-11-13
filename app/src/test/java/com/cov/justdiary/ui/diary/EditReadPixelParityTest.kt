package com.cov.justdiary.ui.diary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.ui.theme.EditorTypography
import com.cov.justdiary.ui.theme.JustDiaryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * “编辑 vs 阅读逐像素”的第一条基线（P2-6b）。
 *
 * 只取**正文单段、无行内样式、无标记**这个最小公共子集：同一个字符串分别走
 * `SpannableBridge`（编辑态 `DiaryEditText`）和 `DiaryReadScreen` 的 Compose 正文
 * （阅读态），把两边都画进 Bitmap 后逐像素比较。
 *
 * 这条故意从最小子集开始：列表 / 待办 / 引用的标记与背景是已知的“编辑态只是字面
 * 标记、阅读态是组件”的差异，不能塞进同一条用例里假装一致。后续再逐步扩大范围。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditReadPixelParityTest {

    @get:Rule
    val rule = createComposeRule()

    private val paragraph = "Hello diary"

    private fun editorBitmap(
        width: Int,
        metrics: SpannableBridge.BlockMetrics,
        backgroundColor: Int,
        textColor: Int,
    ): Bitmap {
        val view = DiaryEditText(org.robolectric.RuntimeEnvironment.getApplication()).apply {
            background = null
            setPadding(0, 0, 0, 0)
            setIncludeFontPadding(false)
            setTextColor(textColor)
            setText(
                SpannableBridge.toSpannable(
                    lines = listOf(EditorLine(text = paragraph)),
                    imageProvider = null,
                    containerWidthPx = width,
                    metricsFor = { metrics },
                    quoteStyle = null,
                ),
            )
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = maxOf(1, view.measuredHeight)
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply { drawColor(backgroundColor) }
            view.draw(Canvas(bitmap))
        }
    }

    @Test
    fun `body paragraph renders the same pixels in editor and reader`() {
        var bodyMetrics: SpannableBridge.BlockMetrics? = null
        var surfaceArgb = Color.WHITE
        var textArgb = Color.BLACK

        rule.setContent {
            JustDiaryTheme(themeMode = "light", dynamicColor = false) {
                surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
                textArgb = MaterialTheme.colorScheme.onSurface.toArgb()
                val density = LocalDensity.current
                val bodyStyle = EditorTypography.textStyleFor(ContentPartStyle.body)
                bodyMetrics = with(density) {
                    SpannableBridge.BlockMetrics(
                        textSizePx = bodyStyle.fontSize.toPx().toInt(),
                        lineHeightPx = bodyStyle.lineHeight.toPx().toInt(),
                        paragraphSpacingPx = 0,
                    )
                }
                Surface(
                    modifier = Modifier.size(width = 411.dp, height = 400.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DiaryReadScreen(
                            dayTitle = "Nov 13",
                            isToday = false,
                            blocks = listOf(
                                ReadBlock(
                                    id = 1L,
                                    startTimeUtc = 0L,
                                    timeLabel = "",
                                    locText = "",
                                    parts = listOf(ContentPart(style = ContentPartStyle.body, text = paragraph)),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        val read = rule.onNodeWithText(paragraph, useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val metrics = requireNotNull(bodyMetrics)
        val editor = editorBitmap(
            width = read.width,
            metrics = metrics,
            backgroundColor = surfaceArgb,
            textColor = textArgb,
        )

        println("edit/read sizes: read=${read.width}x${read.height} editor=${editor.width}x${editor.height}")
        // 行盒高度必须一致（差 1px 允许，取整方向可能不同）。
        assertTrue(
            "阅读/编辑行盒高度差过大：read=${read.height} editor=${editor.height}",
            kotlin.math.abs(read.height - editor.height) <= 1,
        )

        // 两个渲染器的基线取整方向可能差 1px；在 ±1px 内找最小差异，再按比例断言。
        var bestDifferent = Int.MAX_VALUE
        var bestCounted = 1
        var bestShift = 0
        for (shift in -1..1) {
            var different = 0
            var counted = 0
            for (y in 0 until read.height) {
                val ey = y + shift
                if (ey !in 0 until editor.height) continue
                for (x in 0 until minOf(read.width, editor.width)) {
                    counted++
                    if (!close(read.getPixel(x, y), editor.getPixel(x, ey))) different++
                }
            }
            if (different < bestDifferent) {
                bestDifferent = different
                bestCounted = counted.coerceAtLeast(1)
                bestShift = shift
            }
        }
        val ratio = bestDifferent.toDouble() / bestCounted
        println(
            "edit/read body parity: read=${read.width}x${read.height} " +
                "editor=${editor.width}x${editor.height} shift=$bestShift " +
                "diff=$bestDifferent/$bestCounted (${ratio})",
        )
        // 两个渲染器的字形抗锯齿 / M3 letterSpacing 处理仍有已知小差异；正文基线、
        // 字号或行盒若错位会远超这个阈值。后续把 letterSpacing 也统一后可继续收紧。
        assertTrue("正文编辑/阅读像素差异过大：$ratio", ratio <= 0.08)
    }

    private fun close(a: Int, b: Int): Boolean {
        val da = kotlin.math.abs(Color.alpha(a) - Color.alpha(b))
        val dr = kotlin.math.abs(Color.red(a) - Color.red(b))
        val dg = kotlin.math.abs(Color.green(a) - Color.green(b))
        val db = kotlin.math.abs(Color.blue(a) - Color.blue(b))
        return maxOf(da, dr, dg, db) <= 8
    }
}
