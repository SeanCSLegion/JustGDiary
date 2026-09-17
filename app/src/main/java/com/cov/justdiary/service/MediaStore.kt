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
 * | 插入图片时降采样 | 最长边 **2048**、JPEG **q=0.85** | `DiaryViewModel.swift:489-490` |
 * | 渲染时降采样 | `max(w, h) × 3` | `MediaViews.swift:25` |
 * | 预览降采样 | 最长边 **1600** | `MediaViews.swift:151` |
 * | 文件名 | `img_<epochMillis>.jpg` | `DiaryViewModel.swift:492` |
 * | 内存缓存 | 200 项 / 150 MB | `MediaStore.swift:53-54` |
 *
 * ## 与 iOS 的实现差异
 *
 * iOS 用 `CGImageSourceCreateThumbnailAtIndex` + `kCGImageSourceCreateThumbnailWithTransform`
 * 一步拿到"已按 EXIF 旋转 + 已降采样"的图。Android 侧用 **`ImageDecoder`**（API 28+，
 * minSdk 33 满足）：它同样**自动应用 EXIF 方向**并支持 `setTargetSize` 降采样，
 * 因此不需要额外引入 `androidx.exifinterface`。
 *
 * 缓存只按**字节数**限制（`LruCache` 只有一个维度）；iOS 的 200 项上限在这里
 * 由 150 MB 的字节上限近似覆盖 —— 单张图按 2048 边长算约 16 MB，200 项上限
 * 实际上不会被触及。
 */
class MediaStore(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 图片目录：`filesDir/images`（iOS 是 `Documents/images`）。 */
    val imagesDir: File get() = File(appContext.filesDir, ImagePathUtil.IMAGE_DIR_NAME)

    private val cache = object : LruCache<String, Bitmap>(CACHE_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // ------------------------------------------------------------------ 读

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
     */
    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val longest = max(width, height)
                if (longest > INSERT_MAX_PIXEL && longest > 0) {
                    val scale = INSERT_MAX_PIXEL.toFloat() / longest
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }.onFailure { Log.editor.w("读取所选图片失败：${it.message}") }.getOrNull() ?: return@withContext null

        val name = "img_${System.currentTimeMillis()}.jpg"
        return@withContext try {
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
            null
        } finally {
            bitmap.recycle()
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

    companion object {
        const val INSERT_MAX_PIXEL: Int = 2048
        const val JPEG_QUALITY: Int = 85
        const val PREVIEW_MAX_PIXEL: Int = 1600

        /** iOS: `totalCostLimit = 150 * 1024 * 1024`。 */
        const val CACHE_MAX_BYTES: Int = 150 * 1024 * 1024

        /** 渲染降采样：`max(storedW, storedH) * 3`（iOS `MediaViews.swift:25`）。 */
        fun renderMaxPixel(storedWidth: Double, storedHeight: Double): Int =
            (max(storedWidth, storedHeight) * 3).roundToInt().coerceAtLeast(1)
    }
}
