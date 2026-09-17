package com.cov.justdiary.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * zip 读写测试。
 *
 * 备份包是**跨端契约**：Android 写出的包要能被 iOS 的自实现 `ZipArchive` 读、
 * iOS 写出的包要能被这里读。本测试覆盖字节层面的正确性（CRC、长度、store/deflate、
 * 流式落盘）；跨端那一半由 `tools/verify_backup_interop.sh` 用 iOS 真实代码验证。
 */
class ZipArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `bytes and file entries round trip`() {
        val source = temp.newFile("payload.bin").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val zipFile = File(temp.root, "out.jdiary")

        ZipArchive.create(
            zipFile,
            listOf(
                ZipArchive.Source.Bytes("manifest.json", """{"v":1}""".toByteArray()),
                ZipArchive.Source.FileSource("db/just_diary.db", source),
                ZipArchive.Source.Bytes("images/a.jpg", "fake-jpeg".toByteArray()),
            ),
        )

        ZipArchive.open(zipFile).use { reader ->
            assertEquals(
                setOf("manifest.json", "db/just_diary.db", "images/a.jpg"),
                reader.entryNames,
            )
            assertEquals("""{"v":1}""", reader.readBytes("manifest.json")!!.decodeToString())
            assertArrayEquals(source.readBytes(), reader.readBytes("db/just_diary.db"))
            assertTrue(reader.contains("images/a.jpg"))

            val extracted = File(temp.root, "extracted.db")
            assertTrue(reader.extractTo("db/just_diary.db", extracted))
            assertArrayEquals(source.readBytes(), extracted.readBytes())
        }
    }

    @Test
    fun `missing entries are reported without throwing`() {
        val zipFile = File(temp.root, "out.jdiary")
        ZipArchive.create(zipFile, listOf(ZipArchive.Source.Bytes("a.txt", "a".toByteArray())))

        ZipArchive.open(zipFile).use { reader ->
            assertNull(reader.readBytes("nope.txt"))
            assertFalse(reader.extractTo("nope.txt", File(temp.root, "nope.txt")))
            assertFalse(reader.contains("nope.txt"))
        }
    }

    @Test
    fun `empty file round trips`() {
        val zipFile = File(temp.root, "empty.jdiary")
        ZipArchive.create(zipFile, listOf(ZipArchive.Source.Bytes("empty.bin", ByteArray(0))))

        ZipArchive.open(zipFile).use { reader ->
            assertEquals(0, reader.readBytes("empty.bin")!!.size)
        }
    }

    /** 小数据走 store、大数据走 deflate；两种方式都必须能读回来。 */
    @Test
    fun `stored and deflated entries are both readable and standard`() {
        val small = "small".toByteArray()
        val large = ByteArray(100_000) { 'a'.code.toByte() } // 高度可压缩
        val zipFile = File(temp.root, "mixed.jdiary")

        ZipArchive.create(
            zipFile,
            listOf(
                ZipArchive.Source.Bytes("small.bin", small),
                ZipArchive.Source.Bytes("large.bin", large),
            ),
        )

        // 用 JDK 原生 ZipFile 复核：标准 zip 的头部信息必须自洽
        ZipFile(zipFile).use { zip ->
            val smallEntry = zip.getEntry("small.bin")
            assertEquals(java.util.zip.ZipEntry.STORED, smallEntry.method)
            assertEquals(small.size.toLong(), smallEntry.size)
            val largeEntry = zip.getEntry("large.bin")
            assertEquals(java.util.zip.ZipEntry.DEFLATED, largeEntry.method)
            assertEquals(large.size.toLong(), largeEntry.size)
            assertTrue("deflate 应显著压缩", largeEntry.compressedSize < largeEntry.size / 2)
        }

        ZipArchive.open(zipFile).use { reader ->
            assertArrayEquals(small, reader.readBytes("small.bin"))
            assertArrayEquals(large, reader.readBytes("large.bin"))
        }
    }

    /**
     * 改掉 store entry 的数据而不改头部 CRC —— 读取必须报错，
     * 而不是静默给出损坏的内容（iOS 侧同样两者都校验）。
     */
    @Test
    fun `corrupted payload fails the crc check`() {
        val zipFile = File(temp.root, "corrupt.jdiary")
        ZipArchive.create(zipFile, listOf(ZipArchive.Source.Bytes("data.bin", ByteArray(64) { 7 })))

        val bytes = zipFile.readBytes()
        // local header: 30 字节 + 文件名长度(偏移 26) + extra 长度(偏移 28)
        val nameLength = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)
        val extraLength = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8)
        val dataOffset = 30 + nameLength + extraLength
        assertTrue("样本必须足够长", bytes.size > dataOffset + 8)
        bytes[dataOffset + 3] = (bytes[dataOffset + 3] + 1).toByte()
        zipFile.writeBytes(bytes)

        ZipArchive.open(zipFile).use { reader ->
            val error = assertThrows(ZipException::class.java) { reader.readBytes("data.bin") }
            assertTrue(
                "应报 CRC 或长度不符：${error.message}",
                error.message!!.contains("CRC32") || error.message!!.contains("长度不符"),
            )
        }
    }

    @Test
    fun `large payload streams in and out`() {
        val big = temp.newFile("big.bin").apply {
            outputStream().use { out ->
                val chunk = ByteArray(64 * 1024) { (it % 97).toByte() }
                repeat(24) { out.write(chunk) } // ~1.5 MB
            }
        }
        val zipFile = File(temp.root, "big.jdiary")
        ZipArchive.create(zipFile, listOf(ZipArchive.Source.FileSource("images/big.jpg", big)))

        val out = File(temp.root, "big-out.jpg")
        ZipArchive.open(zipFile).use { reader ->
            assertTrue(reader.extractTo("images/big.jpg", out))
        }
        assertArrayEquals(big.readBytes(), out.readBytes())
    }

    /**
     * 生成一个固定的样本包，供 `tools/verify_backup_interop.sh` 用 **iOS 真实的
     * `ZipArchive.swift`** 去解 —— 这是备份格式跨端互通的直接证据。
     */
    @Test
    fun `write cross end sample package`() {
        val outDir = File("build/tmp/cross-end").apply { mkdirs() }
        val sample = File(outDir, "export.jdiary")

        val db = File(outDir, "sample.db").apply { writeBytes("SQLite format 3\u0000fake-db".toByteArray()) }
        val image = File(outDir, "img_1.jpg").apply { writeBytes(ByteArray(2048) { (it % 200).toByte() }) }

        val manifest = com.cov.justdiary.data.BackupManifest(
            formatVersion = 1,
            appVersion = "1.0",
            exportedAt = 1_760_000_000_000,
            includeSettings = true,
            diaryCount = 2,
            blockCount = 3,
            imageCount = 1,
            images = listOf(com.cov.justdiary.data.BackupImageRef("images/img_1.jpg", "images/img_1.jpg")),
            settingsJson = """{"day_start_hour":4,"auto_time":true}""",
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = false }

        ZipArchive.create(
            sample,
            listOf(
                ZipArchive.Source.Bytes("manifest.json", json.encodeToString(
                    com.cov.justdiary.data.BackupManifest.serializer(), manifest,
                ).toByteArray()),
                ZipArchive.Source.FileSource("db/just_diary.db", db),
                ZipArchive.Source.FileSource("images/img_1.jpg", image),
            ),
        )

        assertTrue(sample.exists())
        assertTrue("样本包不应为空", sample.length() > 0)
    }
}
