package com.cov.justdiary.service

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * P1-1：插图按**原分辨率 + q0.95**落盘，只保留方向（不带 GPS / 机型 / 时间）。
 *
 * 用 Robolectric 的 native graphics（真的 Skia + ImageDecoder），不是 mock 尺寸。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaStoreRobolectricTest {

    private lateinit var store: MediaStore

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        store = MediaStore(RuntimeEnvironment.getApplication())
        store.imagesDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        store.imagesDir.deleteRecursively()
    }

    private fun jpeg(width: Int, height: Int, color: Int = Color.RED): File {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "src_${width}x$height.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
        }
        bitmap.recycle()
        return file
    }

    /** Robolectric 的 `ImageDecoder` FileSource 不可用；ByteBuffer Source 走同一条 native 解码路径。 */
    private fun sourceOf(file: File): ImageDecoder.Source =
        ImageDecoder.createSource(ByteBuffer.wrap(file.readBytes()))

    private fun importedBitmap(src: String): Bitmap {
        val file = store.resolve(src)
        assertTrue("落盘文件不存在：$file", file.exists())
        return ImageDecoder.decodeBitmap(sourceOf(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    @Test
    fun `a 3000 by 2000 photo keeps its original pixels instead of being scaled to 2048`() {
        val src = store.importImageSource(sourceOf(jpeg(3000, 2000)))
        assertTrue("导入应当成功", src != null)

        val bitmap = importedBitmap(src!!)
        assertEquals("宽度必须保持原图", 3000, bitmap.width)
        assertEquals("高度必须保持原图", 2000, bitmap.height)
        bitmap.recycle()
        assertEquals("JPEG 质量与 iOS 5594a73 一致", 95, MediaStore.JPEG_QUALITY)
    }

    @Test
    fun `a small image is never upscaled`() {
        val src = store.importImageSource(sourceOf(jpeg(800, 600)))
        assertTrue(src != null)
        val bitmap = importedBitmap(src!!)
        assertEquals(800, bitmap.width)
        assertEquals(600, bitmap.height)
        bitmap.recycle()
    }

    @Test
    fun `exif orientation is baked into the pixels and gps is dropped`() {
        val source = jpeg(40, 20)
        val exif = ExifInterface(source.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        // 一个假的 GPS 标签：落盘后必须消失
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,12/1,0/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.saveAttributes()

        val src = store.importImageSource(sourceOf(source))
        assertTrue(src != null)
        val stored = store.resolve(src!!)

        // 方向烘进像素：40x20 旋转 90° → 20x40（输出 JPEG 不再依赖 orientation 标签）
        val bitmap = ImageDecoder.decodeBitmap(sourceOf(stored)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        assertEquals(20, bitmap.width)
        assertEquals(40, bitmap.height)
        bitmap.recycle()

        // 重新编码只写像素，元数据（含 GPS）不会跟着过去
        val storedExif = ExifInterface(stored.absolutePath)
        val latLong = FloatArray(2)
        assertTrue(
            "落盘 JPEG 不该保留 GPS",
            !storedExif.getLatLong(latLong),
        )
    }

    @Test
    fun `a failed import leaves no half written file`() {
        val broken = File(RuntimeEnvironment.getApplication().cacheDir, "broken.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val src = store.importImageSource(sourceOf(broken))
        assertNull("坏文件应当返回 null", src)
        assertTrue(
            "失败路径不能在 images/ 里留下文件",
            store.imagesDir.listFiles().isNullOrEmpty(),
        )
    }
}
