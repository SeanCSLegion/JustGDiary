package com.cov.justdiary.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.cov.justdiary.data.ShareLayout
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 分享长图的位图渲染。对应 iOS `Services/ImageShareService.swift` 的绘制部分。
 *
 * **版式**全部来自 [ShareLayout] 的纯函数（因此"常量是否对齐 iOS"由 JVM 单测守住），
 * 这里只负责把算好的坐标画出来。iOS 手写逐字符测量，这里用 `Paint.measureText`
 * 通过 [ShareLayout.CharWidthProvider] 注入 —— 换行位置在真机上与 iOS 基本一致。
 *
 * 与 iOS 的三处有意修正见 [ShareLayout] 的类注释（D5 地点行按实际行数前进、
 * D6 标题按实际行数、D7 量宽抽象化）。
 */
class ImageShareRenderer(
    context: Context,
    private val mediaStore: MediaStore,
) {

    private val appContext: Context = context.applicationContext
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 用 `Paint` 精确量宽（对应 iOS 的逐字符 `boundingRect`）。 */
    private val widthProvider = ShareLayout.CharWidthProvider { ch, fontSize ->
        paint.textSize = fontSize.toFloat()
        paint.measureText(ch.toString()).toDouble()
    }

    /**
     * 渲染一天的分享长图。全部 block 为空时返回 null。
     *
     * @param isDark 走深色调色板（与分享那一刻的界面一致）。
     */
    fun render(
        dateText: String,
        startLine: String,
        blocks: List<ShareLayout.ShareBlock>,
        isDark: Boolean,
    ): Bitmap? {
        if (blocks.isEmpty()) return null

        val layout = ShareLayout.layout(dateText, startLine, blocks, widthProvider)
        val height = max(1, layout.height.toInt())
        val bitmap = Bitmap.createBitmap(ShareLayout.WIDTH.toInt(), height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = if (isDark) Palette.dark() else Palette.light()

        drawBackground(canvas, palette, height.toFloat())

        // 日期（62 bold）
        drawText(canvas, dateText, ShareLayout.BLOCK_LEFT.toFloat(), ShareLayout.DATE_Y.toFloat(),
            ShareLayout.DATE_FONT, true, palette.text)

        // 起始行（28，行高 40，可换行）
        drawWrapped(
            canvas, startLine, ShareLayout.BLOCK_LEFT.toFloat(), layout.startLineY.toFloat(),
            ShareLayout.CONTENT_WIDTH, ShareLayout.START_LINE_FONT, ShareLayout.START_LINE_HEIGHT, palette.sub,
        )

        var y = (layout.startLineY + layout.startLineHeight).toFloat()
        drawHairline(canvas, y, palette)

        val timelineTop = y + ShareLayout.HAIRLINE_ADVANCE.toFloat()
        // 时间轴：从内容区顶部延伸到底部（页脚之上留 20）
        drawTimeline(
            canvas,
            from = timelineTop,
            to = layout.height.toFloat() - ShareLayout.FOOTER_H.toFloat() - 20f,
            palette = palette,
        )

        blocks.forEachIndexed { blockIndex, block ->
            y += ShareLayout.TIME_ADVANCE.toFloat() + ShareLayout.TIME_GAP.toFloat()
            // 节点画在时间行的垂直中部
            drawNode(canvas, ShareLayout.TIMELINE_LINE_X.toFloat(), y - ShareLayout.TIME_GAP.toFloat() - 10f, palette)
            drawText(
                canvas, timeLabel(block.time),
                ShareLayout.BLOCK_LEFT.toFloat(),
                y - ShareLayout.TIME_ADVANCE.toFloat() - ShareLayout.TIME_GAP.toFloat() + ShareLayout.TIME_ADVANCE.toFloat(),
                ShareLayout.TIME_FONT, true, palette.accentA,
            )

            val locationLines = ShareLayout.wrappedLines(
                block.location, ShareLayout.LOCATION_FONT, ShareLayout.CONTENT_WIDTH, widthProvider,
            )
            drawWrapped(
                canvas, block.location, ShareLayout.BLOCK_LEFT.toFloat(), y,
                ShareLayout.CONTENT_WIDTH, ShareLayout.LOCATION_FONT, ShareLayout.LOCATION_LINE_HEIGHT, palette.sub,
            )
            y += max(1, locationLines.size) * ShareLayout.LOCATION_LINE_HEIGHT.toFloat()

            block.parts.forEachIndexed { partIndex, part ->
                drawPart(canvas, part, y, palette)
                y += layout.partRects[blockIndex][partIndex].height.toFloat()
            }
            y += ShareLayout.BLOCK_SPACING.toFloat()
        }

        drawFooter(canvas, layout.height.toFloat() - ShareLayout.FOOTER_H.toFloat(), palette)
        return bitmap
    }

    /** 渲染并写入缓存目录，返回可直接分享的 PNG 文件。 */
    fun renderToFile(
        dayKey: String,
        dateText: String,
        startLine: String,
        blocks: List<ShareLayout.ShareBlock>,
        isDark: Boolean,
    ): File? {
        val bitmap = render(dateText, startLine, blocks, isDark) ?: return null
        val dir = File(appContext.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, "JustDiary_${dayKey.replace("-", "")}.png")
        return try {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("compress 返回 false")
            }
            file
        } catch (error: Throwable) {
            Log.backup.e("写入分享图失败", error)
            null
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------ 绘制

    private fun drawBackground(canvas: Canvas, palette: Palette, height: Float) {
        val gradient = LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(palette.bgTop, palette.bgMid, palette.bgBottom),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, ShareLayout.WIDTH.toFloat(), height, Paint().apply { shader = gradient })

        // 两个装饰光斑（对应 iOS 的 drawOrb）
        drawOrb(canvas, 450f, height + 60f, 500f, palette.accentC)
        drawOrb(canvas, 120f, -80f, 380f, palette.accentB)
    }

    private fun drawOrb(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val shader = RadialGradient(
            x, y, r,
            intArrayOf(withAlpha(color, 0.45f), withAlpha(color, 0f)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    }

    private fun drawHairline(canvas: Canvas, y: Float, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val gradient = LinearGradient(
            ShareLayout.BLOCK_LEFT.toFloat(), y, (ShareLayout.WIDTH - ShareLayout.SIDE).toFloat(), y,
            intArrayOf(palette.accentA, withAlpha(palette.accentB, 0f)),
            null,
            Shader.TileMode.CLAMP,
        )
        paint.shader = gradient
        paint.strokeWidth = 1f
        canvas.drawLine(ShareLayout.BLOCK_LEFT.toFloat(), y, (ShareLayout.WIDTH - ShareLayout.SIDE).toFloat(), y, paint)
    }

    private fun drawTimeline(canvas: Canvas, from: Float, to: Float, palette: Palette) {
        if (to <= from) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, from, 0f, to,
            intArrayOf(withAlpha(palette.accentA, 0.55f), withAlpha(palette.accentB, 0.15f)),
            null,
            Shader.TileMode.CLAMP,
        )
        val half = ShareLayout.TIMELINE_LINE_W.toFloat() / 2f
        canvas.drawRect(ShareLayout.TIMELINE_LINE_X.toFloat() - half, from, ShareLayout.TIMELINE_LINE_X.toFloat() + half, to, paint)
    }

    private fun drawNode(canvas: Canvas, x: Float, y: Float, palette: Palette) {
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.accentA, 0.18f) }
        canvas.drawCircle(x, y, 18f, halo)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = withAlpha(palette.accentA, 0.6f)
        }
        canvas.drawCircle(x, y, 15f, ring)
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentA }
        canvas.drawCircle(x, y, 5f, core)
    }

    private fun drawPart(canvas: Canvas, part: ContentPart, y: Float, palette: Palette) {
        val metrics = ShareLayout.metricsFor(part.style)
        val text = com.cov.justdiary.model.ContentFlatten.flattenPart(part)
        val left = ShareLayout.BLOCK_LEFT.toFloat()

        when (part.style) {
            ContentPartStyle.title -> drawWrapped(
                canvas, text, left, y, ShareLayout.CONTENT_WIDTH, metrics.fontSize, metrics.lineHeight, palette.text, bold = true,
            )

            ContentPartStyle.heading -> drawWrapped(
                canvas, text, left, y, ShareLayout.CONTENT_WIDTH, metrics.fontSize, metrics.lineHeight, palette.text, semibold = true,
            )

            ContentPartStyle.quote -> {
                val rect = ShareLayout.partRect(part, y.toDouble(), widthProvider)
                val box = RectF(
                    left,
                    y,
                    (left + ShareLayout.CONTENT_WIDTH).toFloat(),
                    (y + rect.height).toFloat(),
                )
                canvas.drawRoundRect(box, QUOTE_RADIUS, QUOTE_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.quoteBg })
                drawWrapped(
                    canvas, text, left + ShareLayout.QUOTE_INSET.toFloat(), y + INK_PADDING,
                    ShareLayout.CONTENT_WIDTH - ShareLayout.QUOTE_INSET, metrics.fontSize, metrics.lineHeight, palette.text,
                )
            }

            ContentPartStyle.list -> part.items.orEmpty().forEachIndexed { index, item ->
                val lineY = y + index * metrics.lineHeight.toFloat() + INK_PADDING
                canvas.drawCircle(left + 6f, lineY + metrics.fontSize.toFloat() / 3f, 5.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentA })
                drawWrapped(
                    canvas, item, left + 22f, lineY, ShareLayout.CONTENT_WIDTH - 22, metrics.fontSize, metrics.lineHeight, palette.text,
                )
            }

            ContentPartStyle.todo -> part.items.orEmpty().forEachIndexed { index, item ->
                val lineY = y + index * metrics.lineHeight.toFloat() + INK_PADDING
                val done = part.done.orEmpty().getOrNull(index) == true
                drawTodoBox(canvas, left, lineY + 4f, done, palette)
                drawWrapped(
                    canvas, item, left + 40f, lineY, ShareLayout.CONTENT_WIDTH - 40, metrics.fontSize, metrics.lineHeight, palette.text,
                )
            }

            ContentPartStyle.image -> {
                val rect = ShareLayout.partRect(part, y.toDouble(), widthProvider)
                val box = RectF(
                    left,
                    y,
                    (left + ShareLayout.CONTENT_WIDTH).toFloat(),
                    (y + rect.height).toFloat(),
                )
                val bitmap = part.src?.let { mediaStore.image(it)?.asImageBitmapOrNull() }
                if (bitmap != null) {
                    val path = Path().apply { addRoundRect(box, CARD_RADIUS, CARD_RADIUS, Path.Direction.CW) }
                    canvas.save()
                    canvas.clipPath(path)
                    canvas.drawBitmap(bitmap, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
                    canvas.restore()
                } else {
                    canvas.drawRoundRect(box, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.quoteBg })
                    drawText(canvas, IMAGE_PLACEHOLDER, left + 22f, y + (rect.height / 2).toFloat(), 30.0, false, palette.sub)
                }
            }

            else -> drawWrapped(
                canvas, text, left, y, ShareLayout.CONTENT_WIDTH, metrics.fontSize, metrics.lineHeight, palette.text,
            )
        }
    }

    private fun drawTodoBox(canvas: Canvas, x: Float, y: Float, done: Boolean, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (done) {
            paint.shader = LinearGradient(
                x, y, x + 28f, y + 28f,
                intArrayOf(palette.accentA, palette.accentB),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(RectF(x, y, x + 28f, y + 28f), 8f, 8f, paint)
            // 对勾
            val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
                color = 0xFFFFFFFF.toInt()
            }
            canvas.drawLine(x + 7f, y + 15f, x + 12f, y + 20f, check)
            canvas.drawLine(x + 12f, y + 20f, x + 21f, y + 9f, check)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = palette.sub
            canvas.drawRoundRect(RectF(x, y, x + 28f, y + 28f), 8f, 8f, paint)
        }
    }

    private fun drawFooter(canvas: Canvas, y: Float, palette: Palette) {
        val text = FOOTER_TEXT
        paint.textSize = 24f
        paint.color = palette.faint
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val width = paint.measureText(text)
        canvas.drawText(text, (ShareLayout.WIDTH / 2).toFloat() - width / 2f, y + 24f, paint)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        fontSize: Double,
        bold: Boolean,
        color: Int,
    ) {
        paint.textSize = fontSize.toFloat()
        paint.color = color
        paint.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        canvas.drawText(text, x, y + fontSize.toFloat(), paint)
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Double,
        fontSize: Double,
        lineHeight: Double,
        color: Int,
        bold: Boolean = false,
        semibold: Boolean = false,
    ) {
        if (text.isEmpty()) return
        val lines = ShareLayout.wrappedLines(text, fontSize, maxWidth, widthProvider)
        paint.textSize = fontSize.toFloat()
        paint.color = color
        paint.typeface = Typeface.create(
            Typeface.DEFAULT,
            if (bold) Typeface.BOLD else if (semibold) Typeface.BOLD else Typeface.NORMAL,
        )
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, x, y + index * lineHeight.toFloat() + fontSize.toFloat(), paint)
        }
    }

    private fun timeLabel(utcMillis: Long): String {
        val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return formatter.format(java.util.Date(utcMillis))
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** `MediaStore.image` 返回 [Bitmap]；这里做一次空安全转换。 */
    private fun Bitmap.asImageBitmapOrNull(): Bitmap? = this

    /** 分享图的调色板（逐字对齐 iOS `ImageShareService.swift:31-45`）。 */
    data class Palette(
        val text: Int,
        val sub: Int,
        val faint: Int,
        val bgTop: Int,
        val bgMid: Int,
        val bgBottom: Int,
        val accentA: Int,
        val accentB: Int,
        val accentC: Int,
        val quoteBg: Int,
    ) {
        companion object {
            fun light(): Palette = Palette(
                text = 0xFF191C20.toInt(),
                sub = 0xFF44474F.toInt(),
                faint = (0xFF191C20.toInt() and 0x00FFFFFF) or ((0.38f * 255).toInt() shl 24),
                bgTop = 0xFFFBFBFE.toInt(),
                bgMid = 0xFFF3F6FD.toInt(),
                bgBottom = 0xFFF7F2FF.toInt(),
                accentA = 0xFF6E8BFF.toInt(),
                accentB = 0xFF9B6BFF.toInt(),
                accentC = 0xFF3FB6FF.toInt(),
                quoteBg = 0xFFE4EAF9.toInt(),
            )

            fun dark(): Palette = Palette(
                text = 0xFFE1E2E8.toInt(),
                sub = 0xFFC5C6D0.toInt(),
                faint = (0xFFE1E2E8.toInt() and 0x00FFFFFF) or ((0.38f * 255).toInt() shl 24),
                bgTop = 0xFF101318.toInt(),
                bgMid = 0xFF131826.toInt(),
                bgBottom = 0xFF131826.toInt(),
                accentA = 0xFF4A7DFF.toInt(),
                accentB = 0xFFA56BFF.toInt(),
                accentC = 0xFF38BDF8.toInt(),
                quoteBg = 0xFF2B3344.toInt(),
            )
        }
    }

    companion object {
        const val SHARE_DIR = "share"
        const val IMAGE_PLACEHOLDER = "图片"
        const val FOOTER_TEXT = "· 一页时光 ·"

        /** 引用框圆角：`Radius.concentric(card 20, inset 4) = 16`。 */
        const val QUOTE_RADIUS = 16f

        /** 段落内文字相对框顶的额外留白（对应 iOS 的墨迹留白）。 */
        const val INK_PADDING = 8f
        const val CARD_RADIUS = 20f
    }
}
