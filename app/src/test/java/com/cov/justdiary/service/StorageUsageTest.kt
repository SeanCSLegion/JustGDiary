package com.cov.justdiary.service

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** P2-5：存储占用的纯计算。 */
class StorageUsageTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `database images and backups are summed separately`() {
        val db = temp.newFile("just_diary.db").apply { writeBytes(ByteArray(100)) }
        val wal = temp.newFile("just_diary.db-wal").apply { writeBytes(ByteArray(40)) }
        val shm = temp.newFile("just_diary.db-shm").apply { writeBytes(ByteArray(10)) }
        val images = temp.newFolder("images").apply {
            File(this, "a.jpg").writeBytes(ByteArray(200))
            File(this, "b.jpg").writeBytes(ByteArray(300))
        }
        val cache = temp.newFolder("cache").apply {
            File(this, "JustDiary_1.jdiary").writeBytes(ByteArray(70))
            File(this, "other.tmp").writeBytes(ByteArray(20))
        }

        val usage = StorageUsage.measureFiles(listOf(db, wal, shm), images, cache)

        assertEquals(150, usage.databaseBytes)
        assertEquals(500, usage.imagesBytes)
        assertEquals(70, usage.backupBytes)
        assertEquals(720, usage.totalBytes)
    }

    @Test
    fun `missing directories measure as zero`() {
        val usage = StorageUsage.measureFiles(emptyList(), null, null)
        assertEquals(0, usage.totalBytes)
    }
}
