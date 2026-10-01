package com.cov.justdiary.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.cov.justdiary.data.ShareLayout
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 分享长图的位图渲染。对应 iOS `Services/ImageShareService.swift` 的绘制部分。
 *
 * **版式**全部来自 [ShareLayout] 的纯函数（因此"常量是否对齐 iOS"由 JVM 单测守住），
 * 这里只负责把算好的指令画出来 —— iOS 的「排版阶段产出指令 → 绘制阶段只执行指令」
 * （`ImageShareService.swift:9-22`）在这里是同一条结构，因此不会出现"量出来 3 行、
 * 画出来 4 行"的错位。
 *
 * ## 量宽走 `Paint`（对应 iOS 的 `NSString.boundingRect`）
 *
 * [ShareLayout.FontMetrics] 的真机实现就是下面的 [paintMetrics]：`measureText` 量宽，
 * `Paint.FontMetrics` 给行盒与基线偏移。测试用 `InkRatioFontMetrics` 近似。
 */
class ImageShareRenderer(
    context: Context,
    private val mediaStore: MediaStore,
) {

    private val appContext: Context = context.applicationContext
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** 画位图单独一支：要开双线性过滤，且不参与实心填充的状态复用。 */
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    /** 真机量宽：`Paint.measureText`（字体随主题的 sans-serif，与界面同族）。 */
    private val paintMetrics = object : ShareLayout.FontMetrics {
        override fun width(text: String, fontSize: Double): Double {
            paint.textSize = fontSize.toFloat()
            paint.typeface = typefaceFor(fontSize, bold = false)
            return paint.measureText(text).toDouble()
        }

        override fun descent(fontSize: Double): Double {
            paint.textSize = fontSize.toFloat()
            return paint.fontMetrics.descent.toDouble()
        }

        override fun lineHeightOf(fontSize: Double): Double {
            paint.textSize = fontSize.toFloat()
            val fm = paint.fontMetrics
            return (fm.descent - fm.ascent).toDouble()
        }
    }

    /**
     * 字体：iOS 用系统字体的 semibold / bold / regular 三档。
     *
     * Android 侧统一用 `sans-serif`（Roboto）的对应字重 —— 与界面同一族，
     * 但**不再引用 Compose 的 `FontWeight`**：这一层是纯 `Canvas` 绘制，
     * 不应该为了一个字重把 Compose 拉进来。
     */
    private fun typefaceFor(fontSize: Double, bold: Boolean): Typeface = if (bold) {
        Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    } else {
        Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    /**
     * 渲染一天的分享长图。全部 block 为空时返回 null。
     *
     * @param isDark 走深色调色板（与分享那一刻的界面一致）。
     * @param showTime `auto_time` 关闭时隐藏头部起始行与每条记录的时间。
     */
    fun render(
        texts: ShareLayout.ShareTexts,
        blocks: List<ShareLayout.ShareBlock>,
        isDark: Boolean,
        showTime: Boolean = true,
        timeLabel: (Long) -> String = { "" },
    ): Bitmap? {
        if (blocks.isEmpty()) return null

        val palette = if (isDark) ShareLayout.Palette.dark() else ShareLayout.Palette.light()
        val page = ShareLayout.makePage(
            texts = texts,
            blocks = blocks,
            showTime = showTime,
            palette = palette,
            metrics = paintMetrics,
            imageSize = { src -> mediaStore.image(src)?.let { it.width to it.height } },
            timeLabel = timeLabel,
        )
        val height = max(1, page.height.toInt())
        val bitmap = Bitmap.createBitmap(ShareLayout.WIDTH.toInt(), height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas, height.toFloat(), palette)
        page.ops.forEach { draw(canvas, it, palette) }
        return bitmap
    }

    /** 渲染并写入缓存目录，返回可直接分享的 PNG 文件。 */
    fun renderToFile(
        dayKey: String,
        texts: ShareLayout.ShareTexts,
        blocks: List<ShareLayout.ShareBlock>,
        isDark: Boolean,
        showTime: Boolean = true,
        timeLabel: (Long) -> String = { "" },
    ): File? {
        val bitmap = render(texts, blocks, isDark, showTime, timeLabel) ?: return null
        val dir = File(appContext.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, fileNameOf(dayKey))
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

    /**
     * 分享图的文件名：`JustDiary-<dayKey>.png`（规范 §4.3 与验收清单钉住的这条）。
     *
     * 前缀是刻意的：系统分享面板直接拿文件名当标题，不带前缀容易和别人的
     * `2026-09-08.png` 撞名（iOS `ImageShareService` 同一考虑）。
     */
    fun fileNameOf(dayKey: String): String = "JustDiary-$dayKey.png"

    /**
     * 清掉上一次的分享图（**在面板关闭后**调，规范 §4.2 最后一条）。
     *
     * 分享图都在 `cacheDir/share/`，同名会被覆盖，但"先分享 A 天、再分享 B 天"
     * 会留下 A 天那一张。
     */
    fun clearShareDir() {
        runCatching {
            File(appContext.cacheDir, SHARE_DIR).listFiles()?.forEach { it.delete() }
        }.onFailure { Log.backup.w("清理分享图失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 绘制

    private fun draw(canvas: Canvas, op: ShareLayout.Op, palette: ShareLayout.Palette) {
        when (op) {
            is ShareLayout.Op.FillRounded -> {
                fill.color = blend(op.color, op.alpha)
                canvas.drawRoundRect(op.rect.toRectF(), op.radius.toFloat(), op.radius.toFloat(), fill)
            }

            is ShareLayout.Op.Card -> {
                // 阴影：iOS 是 offset(0, 6) / blur 26 / cardShadow
                fill.setShadowLayer(26f, 0f, 6f, blend(op.shadow, palette.cardShadowAlpha))
                fill.color = op.fill.toInt()
                canvas.drawRoundRect(op.rect.toRectF(), op.radius.toFloat(), op.radius.toFloat(), fill)
                fill.clearShadowLayer()
                // 描边 2pt，inset 1、半径 -1
                stroke.color = blend(op.border, palette.cardBorderAlpha)
                stroke.strokeWidth = 2f
                val inset = op.rect.toRectF().also { it.inset(1f, 1f) }
                canvas.drawRoundRect(inset, (op.radius - 1).toFloat(), (op.radius - 1).toFloat(), stroke)
            }

            is ShareLayout.Op.Text -> drawText(canvas, op)

            is ShareLayout.Op.Bar -> {
                fill.color = blend(op.color, op.alpha)
                canvas.drawRoundRect(op.rect.toRectF(), op.radius.toFloat(), op.radius.toFloat(), fill)
            }

            is ShareLayout.Op.Dot -> {
                fill.color = op.color.toInt()
                canvas.drawCircle(op.cx.toFloat(), op.cy.toFloat(), op.radius.toFloat(), fill)
            }

            is ShareLayout.Op.Image -> drawImage(canvas, op)

            is ShareLayout.Op.Checkbox -> drawCheckbox(canvas, op, palette)
        }
    }

    /**
     * 一段文本：**逐行自己画**，每行的基线按 `lineHeight` 等距排。
     *
     * 不用 `StaticLayout`：iOS 那边是 `minimumLineHeight = maximumLineHeight` 的等距行盒，
     * 这里用同一套算法才能保证"量出来的行数 = 画出来的行数"，也不受换行策略差异影响。
     */
    private fun drawText(canvas: Canvas, op: ShareLayout.Op.Text) {
        val lines = ShareLayout.wrappedLines(op.text, op.font, op.rect.width, paintMetrics)
        if (lines.isEmpty()) return
        paint.color = blend(op.color, op.alpha)
        paint.textSize = op.font.toFloat()
        paint.typeface = typefaceFor(op.font, op.bold)
        val fm = paint.fontMetrics
        lines.forEachIndexed { index, line ->
            val baseline = op.rect.y + op.lineHeight * index + (op.lineHeight + fm.descent)
            val x = if (op.align == ShareLayout.Align.Right) {
                op.rect.right - paint.measureText(line)
            } else {
                op.rect.x
            }
            canvas.drawText(line, x.toFloat(), baseline.toFloat(), paint)
        }
    }

    private fun drawImage(canvas: Canvas, op: ShareLayout.Op.Image) {
        val bitmap = mediaStore.image(op.src)?.softwareCopy()
        val rect = op.rect.toRectF()
        val path = Path().apply {
            addRoundRect(rect, op.radius.toFloat(), op.radius.toFloat(), Path.Direction.CW)
        }
        if (bitmap == null) {
            // 文件缺失：填一块占位色（iOS `draw` 的 `.image` 分支同此）
            fill.color = blend(op.fallbackColor, op.fallbackAlpha)
            canvas.drawPath(path, fill)
            return
        }
        canvas.save()
        canvas.clipPath(path)
        // aspect-fill 居中裁剪（iOS 先算 scale 再居中绘制）
        val scale = max(rect.width() / max(1, bitmap.width), rect.height() / max(1, bitmap.height))
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val dst = RectF(
            rect.centerX() - drawW / 2,
            rect.centerY() - drawH / 2,
            rect.centerX() + drawW / 2,
            rect.centerY() + drawH / 2,
        )
        canvas.drawBitmap(bitmap, null, dst, imagePaint)
        canvas.restore()
    }

    private fun drawCheckbox(canvas: Canvas, op: ShareLayout.Op.Checkbox, palette: ShareLayout.Palette) {
        val rect = op.rect.toRectF()
        val radius = ShareLayout.CHECKBOX_RADIUS.toFloat()
        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        if (op.checked) {
            fill.color = op.fill.toInt()
            canvas.drawPath(path, fill)
            // 对勾：线宽 3.4 / 圆头圆角（iOS 的三个点与线宽逐字相同）
            val check = Path().apply {
                moveTo(rect.left + 8, rect.centerY() + 1)
                lineTo(rect.left + 13, rect.bottom - 8)
                lineTo(rect.right - 7, rect.top + 8)
            }
            stroke.color = op.mark.toInt()
            stroke.strokeWidth = 3.4f
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(check, stroke)
        } else {
            stroke.color = blend(op.border, palette.faintLineAlpha)
            stroke.strokeWidth = 2f
            canvas.drawPath(path, stroke)
        }
    }

    /**
     * 背景：两段竖向渐变 + 两枚径向品牌晕染（iOS `drawBackground`）。
     *
     * **不是**旧版那种"三个大色球 + 满屏渐变"：晕染只让纯色背景有点呼吸感，不参与构图。
     */
    private fun drawBackground(canvas: Canvas, height: Float, palette: ShareLayout.Palette) {
        val gradient = LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(palette.bgTop.toInt(), palette.bgBottom.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        fill.shader = gradient
        canvas.drawRect(0f, 0f, ShareLayout.WIDTH.toFloat(), height, fill)
        fill.shader = null

        drawGlow(
            canvas,
            cx = ShareLayout.WIDTH * ShareLayout.GLOW_A_X_RATIO,
            cy = ShareLayout.GLOW_A_Y,
            radius = ShareLayout.GLOW_A_RADIUS,
            color = palette.glowStrong,
            alpha = ShareLayout.GLOW_A_ALPHA,
        )
        drawGlow(
            canvas,
            cx = ShareLayout.WIDTH * ShareLayout.GLOW_B_X_RATIO,
            cy = height * ShareLayout.GLOW_B_Y_RATIO,
            radius = ShareLayout.GLOW_B_RADIUS,
            color = palette.glowSoft,
            alpha = ShareLayout.GLOW_B_ALPHA,
        )
    }

    private fun drawGlow(canvas: Canvas, cx: Double, cy: Double, radius: Double, color: Long, alpha: Double) {
        val shader = RadialGradient(
            cx.toFloat(), cy.toFloat(), radius.toFloat(),
            intArrayOf(blend(color, alpha), blend(color, 0.0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        fill.shader = shader
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius.toFloat(), fill)
        fill.shader = null
    }

    /** iOS 的 `withAlphaComponent`：把 0..1 的 alpha 写进颜色的高 8 位。 */
    private fun blend(color: Long, alpha: Double): Int {
        val a = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        return ((color and 0x00FFFFFFL).toInt()) or (a shl 24)
    }

    /**
     * 分享长图是**软件画布**（`Bitmap` + `Canvas`），而 `MediaStore.image()` 交给界面的是
     * `ImageDecoder` 解码出来的 **HARDWARE bitmap** —— 直接 `drawBitmap` 会抛
     * `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`。
     *
     * 真机实测（2026-09-28）：**只要这一天里有图片**，点分享就是「分享失败：日记图片生成失败」
     * （`JustDiary.backup: 渲染分享图失败：Software rendering doesn't support hardware bitmaps`）。
     * 这里按需拷一份 `ARGB_8888` 的软件位图；`HARDWARE` 之外的位图原样返回，不多花一次拷贝。
     */
    private fun Bitmap.softwareCopy(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) {
            copy(Bitmap.Config.ARGB_8888, false)
        } else {
            this
        }

    private fun ShareLayout.Rect.toRectF(): RectF =
        RectF(x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        const val SHARE_DIR = "share"

        /** 品牌名（`EntryAbility_label`，与 `app_name` 同值）。 */
        const val BRAND_NAME = "一页时光"
    }
}
