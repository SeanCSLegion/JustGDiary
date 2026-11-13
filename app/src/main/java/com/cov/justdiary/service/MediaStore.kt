package com.cov.justdiary.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import com.cov.justdiary.model.ImagePathUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片的落盘与解码。对应 iOS `Services/MediaStore.swift` + `DiaryImageStore`。
 *
 * ## 常量照搬 iOS（跨端共享图片、备份互通都依赖这些值）
 *
 * | 用途 | 参数 | iOS 出处 |
 * | --- | --- | --- |
 * | 插入图片时的落盘 | **原始像素尺寸**、JPEG **q=0.95**、只保留方向 | `5594a73`（2026-10 之后） |
 * | 内存不足时的兜底 | 最长边 **2048**、q=0.95，仅 OOM 时回退 | 本仓库（Android 内存约束） |
 * | 渲染时降采样 | `max(w, h) × 3` | `MediaViews.swift:25` |
 * | 预览降采样 | 最长边 **1600** | `MediaViews.swift:151` |
 * | 文件名 | `img_<epochMillis>.jpg` | `DiaryViewModel.swift:492` |
 * | 内存缓存 | 200 项 / 150 MB | `MediaStore.swift:53-54` |
 *
 * P2-1：Android 侧缓存上限收窄到 96 MB，并区分槽位尺寸（缩略图 480 / 正文 1600），
 * 避免"40dp 的缩略图也按 1600px 解码"。
 *
 * ## 与 iOS 的实现差异
 *
 * iOS 用 `CGImageSourceCreateThumbnailAtIndex` + `kCGImageSourceCreateThumbnailWithTransform`
 * 一步拿到"已按 EXIF 旋转 + 已降采样"的图。Android 侧用 **`ImageDecoder`**（API 28+，
 * minSdk 33 满足）：它同样**自动应用 EXIF 方向**并支持 `setTargetSize` 降采样，
 * 因此不需要额外引入 `androidx.exifinterface`。
 *
 * 缓存只按**字节数**限制（`LruCache` 只有一个维度）；iOS 的 200 项上限在 Android 侧
 * 由 96 MB 的字节上限近似覆盖（P2-1 从 150 MB 收窄）。显示链路按槽位解码
 * （缩略图 480 / 正文 1600），因此同样内存下能装更多页。
 */
class MediaStore(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 图片目录：`filesDir/images`（iOS 是 `Documents/images`）。 */
    val imagesDir: File get() = File(appContext.filesDir, ImagePathUtil.IMAGE_DIR_NAME)

    private val cache = object : LruCache<String, Bitmap>(CACHE_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // ------------------------------------------------------------------ 读

    /**
     * 异步取图：调用方在组合期只能走这一条（P2-1）。
     *
     * 真正的解码由 [image] 在 IO 上做；命中缓存时也会切回调用方上下文，
     * 但 Compose 的 `produceState` 不关心这一点。
     */
    suspend fun imageAsync(src: String, maxPixel: Int = PREVIEW_MAX_PIXEL): Bitmap? =
        withContext(Dispatchers.IO) { image(src, maxPixel) }

    /** 取一张已降采样的图；命中内存缓存则直接返回。 */
    fun image(src: String, maxPixel: Int = PREVIEW_MAX_PIXEL): Bitmap? {
        val file = resolve(src)
        if (!file.exists()) return null

        val key = "$src@$maxPixel"
        cache.get(key)?.let { return it }

        val bitmap = decodeFile(file, maxPixel) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** 解码一个文件并按 [maxPixel] 降采样（自动应用 EXIF 方向）。 */
    fun decodeFile(file: File, maxPixel: Int): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            if (longest > maxPixel && longest > 0) {
                val scale = maxPixel.toFloat() / longest
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }.onFailure { Log.editor.w("解码图片失败：${file.name}（${it.message}）") }.getOrNull()

    // ------------------------------------------------------------------ 写

    /**
     * 把用户选中的图片导入应用私有目录，返回 `content_json` 里使用的键
     * （`images/img_<ms>.jpg`）。失败返回 null。
     *
     * P1-1（2026-11-12 确认）：**原分辨率 + q0.95，只保留方向**。Android 侧显示链路
     * 仍然按目标尺寸降采样（`image(src, maxPixel)`），因此排版与常驻内存不变。
     *
     * 大图内存兜底：全分辨率解码约 `4 bytes/px`（16MP ≈ 64MB，48MP ≈ 192MB）。
     * 先试全尺寸；只有真的 `OutOfMemoryError` 才回退到 [OOM_FALLBACK_MAX_PIXEL]，
     * 不把 2048 重新加回"存储侧"。
     */
    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        importImageSource(ImageDecoder.createSource(appContext.contentResolver, uri))
    }

    /**
     * 与 [importImage] 同一条路径，但直接吃 [ImageDecoder.Source]（Robolectric 测试用）。
     *
     * 落盘字节只包含**解码后的像素**：`ImageDecoder` 已经按 EXIF 把方向烘进像素，
     * 重新 `compress` 又只写图像数据，因此输出 JPEG 里不会带 GPS / 机型 / 时间。
     */
    internal fun importImageSource(source: ImageDecoder.Source): String? {
        val bitmap = decodeForInsert(source) ?: return null
        val name = "img_${System.currentTimeMillis()}.jpg"
        return try {
            imagesDir.mkdirs()
            val target = File(imagesDir, name)
            FileOutputStream(target).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    error("compress 返回 false")
                }
            }
            ImagePathUtil.IMAGE_DIR_PREFIX + name
        } catch (error: Throwable) {
            Log.editor.e("写入图片失败：$name", error)
            // 失败路径不留下半张文件（压缩/写盘中途都可能抛）。
            runCatching { File(imagesDir, name).delete() }
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** 全分辨率解码；OOM 时退回 [OOM_FALLBACK_MAX_PIXEL] 再试一次。 */
    private fun decodeForInsert(source: ImageDecoder.Source): Bitmap? {
        return try {
            decode(source, maxPixel = null)
        } catch (oom: OutOfMemoryError) {
            Log.editor.w("原图解码内存不足，回退 ${OOM_FALLBACK_MAX_PIXEL}px：${oom.message}")
            runCatching { decode(source, OOM_FALLBACK_MAX_PIXEL) }
                .onFailure { Log.editor.w("回退解码仍失败：${it.message}") }
                .getOrNull()
        } catch (error: Throwable) {
            Log.editor.e("读取所选图片失败", error)
            null
        }
    }

    private fun decode(source: ImageDecoder.Source, maxPixel: Int?): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 必须显式要软件位图：`Bitmap.compress` 不能读硬件位图，而且全尺寸硬件位图
            // 在部分驱动上会直接把内存压力留给 GPU。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (maxPixel != null) {
                val width = info.size.width
                val height = info.size.height
                val longest = max(width, height)
                if (longest > maxPixel && longest > 0) {
                    val scale = maxPixel.toFloat() / longest
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }

    // ------------------------------------------------------------------ 缓存与路径

    fun resolve(src: String): File =
        File(imagesDir, ImagePathUtil.normalizeSrcKey(src).removePrefix(ImagePathUtil.IMAGE_DIR_PREFIX))

    /** 单个 src 的全部缓存条目失效（覆盖导入后同一个 src 可能指向不同内容）。 */
    fun invalidate(src: String) {
        val prefix = "$src@"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
    }

    fun invalidateAll() = cache.evictAll()

    /**
     * 低内存回调：直接清空图片缓存。
     *
     * 由 `JustDiaryApplication.onTrimMemory` 调用；不在这里做"部分淘汰"——
     * 图片都能从磁盘重新解码，清空是最简单也最不会留尾巴的策略。
     */
    fun trimMemory() = cache.evictAll()

    companion object {
        /** 插入图片的 JPEG 质量：与 iOS `5594a73` 一致（原分辨率 + q0.95）。 */
        const val JPEG_QUALITY: Int = 95

        /** 只在该尺寸解码抛 OOM 时使用；不参与正常落盘。 */
        const val OOM_FALLBACK_MAX_PIXEL: Int = 2048

        /** 正文 / 编辑器 / 阅读态图片。 */
        const val PREVIEW_MAX_PIXEL: Int = 1600

        /** 首页当日面板等缩略图槽位。 */
        const val THUMBNAIL_MAX_PIXEL: Int = 480

        /** 96 MB：低于 iOS 的 150 MB，配合"按槽位尺寸解码"控制常驻内存。 */
        const val CACHE_MAX_BYTES: Int = 96 * 1024 * 1024

        /** 渲染降采样：`max(storedW, storedH) * 3`（iOS `MediaViews.swift:25`）。 */
        fun renderMaxPixel(storedWidth: Double, storedHeight: Double): Int =
            (max(storedWidth, storedHeight) * 3).roundToInt().coerceAtLeast(1)
    }
}
