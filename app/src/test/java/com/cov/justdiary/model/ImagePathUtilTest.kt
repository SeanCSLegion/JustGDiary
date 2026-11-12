package com.cov.justdiary.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0-0：图片键的归一与安全边界。
 *
 * 这些用例直接钉住“任何输入都只能落到 `images/<一个安全文件名>`”，
 * 不允许 `..` / 绝对路径 / 目录分隔符穿过。
 */
class ImagePathUtilTest {

    @Test
    fun `normalization always keeps only the basename`() {
        assertEquals("images/a.jpg", ImagePathUtil.normalizeSrcKey("a.jpg"))
        assertEquals("images/a.jpg", ImagePathUtil.normalizeSrcKey("images/a.jpg"))
        assertEquals("images/a.jpg", ImagePathUtil.normalizeSrcKey("nested/a.jpg"))
        assertEquals("images/a.jpg", ImagePathUtil.normalizeSrcKey("images/../../a.jpg"))
        assertEquals("images/${ImagePathUtil.INVALID_IMAGE_NAME}", ImagePathUtil.normalizeSrcKey("images/.."))
    }

    @Test
    fun `invalid stored sources are rejected, not repaired`() {
        assertTrue(ImagePathUtil.isValidStoredSrc("images/a.jpg"))
        assertTrue(ImagePathUtil.isValidStoredSrc("images/img_1.png"))

        assertFalse(ImagePathUtil.isValidStoredSrc("a.jpg"))
        assertFalse(ImagePathUtil.isValidStoredSrc("images/../a.jpg"))
        assertFalse(ImagePathUtil.isValidStoredSrc("images/../../databases/just_diary.db"))
        assertFalse(ImagePathUtil.isValidStoredSrc("images//a.jpg"))
        assertFalse(ImagePathUtil.isValidStoredSrc("images/"))
        assertFalse(ImagePathUtil.isValidStoredSrc("images/."))
        assertFalse(ImagePathUtil.isValidStoredSrc("images/.."))
        assertFalse(ImagePathUtil.isValidStoredSrc("images/a\\b.jpg"))
        assertFalse(ImagePathUtil.isValidStoredSrc("images/\u0000.jpg"))
    }

    @Test
    fun `import accepts a bare old-style filename but not traversal`() {
        assertTrue(ImagePathUtil.isValidImportSrc("a.jpg"))
        assertTrue(ImagePathUtil.isValidImportSrc("images/a.jpg"))
        assertFalse(ImagePathUtil.isValidImportSrc("../a.jpg"))
        assertFalse(ImagePathUtil.isValidImportSrc("images/../a.jpg"))
        assertFalse(ImagePathUtil.isValidImportSrc("a/b.jpg"))
        assertFalse(ImagePathUtil.isValidImportSrc("/a.jpg"))
    }

    @Test
    fun `safe file names reject path separators and dot segments`() {
        assertTrue(ImagePathUtil.isSafeImageFileName("img_1.jpg"))
        assertFalse(ImagePathUtil.isSafeImageFileName(""))
        assertFalse(ImagePathUtil.isSafeImageFileName("."))
        assertFalse(ImagePathUtil.isSafeImageFileName(".."))
        assertFalse(ImagePathUtil.isSafeImageFileName("../x"))
        assertFalse(ImagePathUtil.isSafeImageFileName("a/b"))
        assertFalse(ImagePathUtil.isSafeImageFileName("a\\b"))
        assertFalse(ImagePathUtil.isSafeImageFileName(".hidden.jpg"))
    }

    @Test
    fun `inside check rejects traversal and absolute targets`() {
        val root = File(System.getProperty("java.io.tmpdir"), "image-root-${System.nanoTime()}")
        root.mkdirs()
        try {
            assertTrue(ImagePathUtil.isInside(root, File(root, "a.jpg")))
            assertFalse(ImagePathUtil.isInside(root, File(root, "../a.jpg")))
            assertFalse(ImagePathUtil.isInside(root, File("/tmp/a.jpg")))
        } finally {
            root.deleteRecursively()
        }
    }
}
