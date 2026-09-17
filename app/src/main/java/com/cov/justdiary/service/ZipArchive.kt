package com.cov.justdiary.service

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 备份包（`.jdiary`）的读写。对应 iOS `Services/ZipArchive.swift`（自实现的 PKZIP）。
 *
 * ## 与 iOS 的格式契约
 *
 * zip 是标准 PKZIP：iOS 的自实现写 local header + data + central directory + EOCD，
 * 压缩方式 **0（store，压缩无收益时）或 8（raw deflate）**，CRC32 校验，
 * **不是 Zip64、不加密**。Android 侧直接用 `java.util.zip` 即可互通 ——
 * 读 iOS 产物、写出去给 iOS 读，两边都只接受 0/8 两种方式。
 *
 * ## 与 iOS 的两处实现差异（都是为了不在 Android 上 OOM）
 *
 * 1. **导出是流式的**：iOS 把整个 zip 拼成 `Data` 再落盘；这里边读边写
 *    （`ZipOutputStream` + 文件流），图片再多也不占内存。
 * 2. **导入用随机访问**：iOS 把 zip 解成 `[String: Data]`（DB + 全部原图都在堆上）；
 *    这里用 [ZipFile] 按需读单个 entry，并流式写盘。
 *
 * 与 iOS 一致的部分：导入时**强校验 CRC32 与解压后长度**（iOS 同样两者都查）。
 * iOS 读取时还会容忍"zlib 包裹的 deflate"，这里不实现 —— iOS 自己写的是 raw deflate，
 * 该容错只对第三方产生的畸形包有意义。
 */
object ZipArchive {

    /** 小于该长度的 entry 直接 store（清单只有几百字节，压缩无收益）。 */
    private const val STORE_THRESHOLD = 512

    /** zip 内一个 entry 的数据来源。 */
    sealed interface Source {
        val path: String

        /** 已在内存里的数据（清单很小）。 */
        data class Bytes(override val path: String, val bytes: ByteArray) : Source

        /** 磁盘上的文件，流式写入。 */
        data class FileSource(override val path: String, val file: File) : Source
    }

    // ------------------------------------------------------------------ 写

    /**
     * 把 [sources] 写成一个 zip 文件。
     *
     * 压缩策略：小于 [STORE_THRESHOLD] 的 entry 用 store，其余用 deflate。
     * iOS 侧是自己实现"压缩后不小于原始大小就退回 store"的逐 entry 回退，
     * `java.util.zip` 没有这个能力；对备份包里的三类内容（几百字节的清单、
     * 几十 KB 的 SQLite、上百 KB 的 JPEG）来说两者的实际结果一致。
     */
    fun create(target: File, sources: List<Source>) {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target).buffered(BUFFER_SIZE)).use { zip ->
            for (source in sources) {
                val entry = ZipEntry(source.path)
                when (source) {
                    is Source.Bytes -> {
                        if (source.bytes.size < STORE_THRESHOLD) {
                            // 小数据不值得压，也更接近 iOS 的 store 选择
                            entry.method = ZipEntry.STORED
                            entry.size = source.bytes.size.toLong()
                            entry.compressedSize = source.bytes.size.toLong()
                            entry.crc = crc32(source.bytes)
                        }
                        zip.putNextEntry(entry)
                        zip.write(source.bytes)
                    }

                    is Source.FileSource -> {
                        zip.putNextEntry(entry)
                        FileInputStream(source.file).buffered(BUFFER_SIZE).use { input ->
                            input.copyTo(zip, BUFFER_SIZE)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    // ------------------------------------------------------------------ 读

    /** 打开一个 zip 做随机访问。调用方负责 [Reader.close]。 */
    fun open(file: File): Reader = Reader(ZipFile(file))

    class Reader internal constructor(private val zip: ZipFile) : Closeable {

        val entryNames: Set<String> get() = zip.entries().asSequence().map { it.name }.toSet()

        fun contains(name: String): Boolean = zip.getEntry(name) != null

        /** 读一个 entry 的全部字节；不存在返回 null。长度与 CRC 都会被校验。 */
        fun readBytes(name: String): ByteArray? {
            val entry = zip.getEntry(name) ?: return null
            val out = java.io.ByteArrayOutputStream(entry.size.coerceAtLeast(0L).toInt().coerceAtLeast(64))
            copyVerified(entry, out)
            return out.toByteArray()
        }

        /** 把一个 entry 流式落盘；不存在返回 false。 */
        fun extractTo(name: String, target: File): Boolean {
            val entry = zip.getEntry(name) ?: return false
            target.parentFile?.mkdirs()
            FileOutputStream(target).buffered(BUFFER_SIZE).use { out ->
                copyVerified(entry, out)
            }
            return true
        }

        private fun copyVerified(entry: ZipEntry, out: java.io.OutputStream) {
            val crc = CRC32()
            var total = 0L
            zip.getInputStream(entry).use { input -> total += input.copyTo(out, BUFFER_SIZE, crc) }
            if (entry.size >= 0 && total != entry.size) {
                throw ZipException("entry ${entry.name} 长度不符：期望 ${entry.size}，实际 $total")
            }
            if (entry.crc >= 0 && crc.value != entry.crc) {
                throw ZipException("entry ${entry.name} CRC32 校验失败")
            }
        }

        override fun close() = zip.close()
    }

    /** `InputStream.copyTo` 的带 CRC 版本。 */
    private fun InputStream.copyTo(out: java.io.OutputStream, bufferSize: Int, crc: CRC32): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            crc.update(buffer, 0, read)
            out.write(buffer, 0, read)
            total += read
        }
        return total
    }

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    const val BUFFER_SIZE: Int = 64 * 1024
}
