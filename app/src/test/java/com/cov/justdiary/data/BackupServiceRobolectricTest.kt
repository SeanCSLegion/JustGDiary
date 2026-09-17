package com.cov.justdiary.data

import android.content.Context
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.BackupService
import com.cov.justdiary.service.SettingsStore
import com.cov.justdiary.service.ZipArchive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 备份包的**运行时**端到端验证（Robolectric）。
 *
 * `tools/verify_backup_interop.sh` 已经用 iOS 真实的 `ZipArchive.swift` 读 Android 写出的包，
 * 但它读的是**单测构造的样例包**；这里补的是"真的走一遍导出 → 真的走一遍导入"：
 *
 * 1. 导出的包结构（`manifest.json` / `db/just_diary.db` / `images/<name>`）与 manifest 字段；
 * 2. `manifest.settingsJson` 只含 10 个业务设置项（**不含** `search_index_version` /
 *    `content_format_version`，它们是数据版本而不是偏好，恢复备份不能重置）；
 * 3. 导入的 `SKIP` 模式：已存在的日期整天跳过，统计里 `skippedDays` 计上；
 * 4. 导入的 `OVERWRITE` 模式：已存在的日期被整天替换（内容与片段数都变）；
 * 5. 图片随包往返，且被引用的图片会出现在包里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupServiceRobolectricTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var repository: DiaryRepository
    private lateinit var backupService: BackupService

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        settings = SettingsStore(context)
        repository = DiaryRepository(context, settings)
        backupService = BackupService(context, repository, settings)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(JustDiarySchema.DB_NAME)
        repository.imagesDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ 导出

    @Test
    fun `export writes the cross-end layout and a settings payload with ten keys only`(): Unit = runBlocking {
        repository.prepare()
        writeDay("2026-09-14", "今天去了海边")
        settings.save(
            settings.load().copy(
                dayStartHour = 5,
                themeMode = AppSettings.THEME_DARK,
                appLanguage = AppSettings.LANGUAGE_EN,
            ),
        )
        // 数据版本 key 不应出现在 settingsJson 里
        settings.searchIndexVersion = 7
        settings.contentFormatVersion = 99

        val file = backupService.exportBackup(includeSettings = true)
        assertTrue("导出的包应当存在", file.exists() && file.length() > 0)

        ZipArchive.open(file).use { zip ->
            assertTrue("manifest.json", zip.contains("manifest.json"))
            assertTrue("db/just_diary.db", zip.contains("db/just_diary.db"))

            val manifest = json.decodeFromString(
                BackupManifest.serializer(),
                String(zip.readBytes("manifest.json")!!),
            )
            assertEquals(BackupManifest.FORMAT_VERSION, manifest.formatVersion)
            assertEquals(1, manifest.diaryCount)
            assertEquals(1, manifest.blockCount)
            assertTrue(manifest.includeSettings)

            val settingsJson = json.parseToJsonElement(manifest.settingsJson)
            val keys = (settingsJson as kotlinx.serialization.json.JsonObject).keys
            assertEquals("settingsJson 只含 10 个业务设置项", 10, keys.size)
            assertFalse("search_index_version 不是偏好", keys.contains("search_index_version"))
            assertFalse("content_format_version 不是偏好", keys.contains("content_format_version"))
            assertTrue(keys.contains("day_start_hour"))
        }
        file.delete()
    }

    @Test
    fun `images referenced by a block are packed and restored`(): Unit = runBlocking {
        repository.prepare()
        repository.imagesDir.mkdirs()
        val imageFile = File(repository.imagesDir, "img_1700000000000.jpg")
        imageFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03))

        val startUtc = 1_700_000_000_000L
        repository.addBlock(
            startTimeUtc = startUtc,
            locText = "",
            latitude = 0.0,
            longitude = 0.0,
            locPrecision = LocPrecision.none,
            region = LocRegion("", "", "", "", "", LocQuality.none),
            contentJson = ContentFlatten.serializeContent(
                listOf(
                    ContentPart(text = "配图"),
                    ContentPart(
                        style = com.cov.justdiary.model.ContentPartStyle.image,
                        src = "images/img_1700000000000.jpg",
                        w = 100.0,
                        h = 80.0,
                    ),
                ),
            ),
            dayStartHour = 4,
        )

        val file = backupService.exportBackup(includeSettings = false)
        ZipArchive.open(file).use { zip ->
            assertTrue(
                "被引用的图片必须在包里",
                zip.contains("images/img_1700000000000.jpg"),
            )
        }

        // 换一台"设备"：清空库与图片目录后导入
        val dayKey = DateUtil.dayKeyForUtc(startUtc, 4)
        repository.deleteDiaryByDay(dayKey)
        repository.imagesDir.deleteRecursively()

        val stats = backupService.importBackup(file, ImportMode.SKIP)
        assertEquals(1, stats.importedDays)
        assertEquals(1, stats.importedImages)

        // 导入侧会**改名落盘**（`imp_<stamp>_<n>.<ext>`）并重写 content_json 里的 src：
        // 直接覆盖同名文件会让"两个片段共用一张图"的备份互相踩到（iOS 侧踩过这个坑）。
        val imported = repository.imagesDir.listFiles().orEmpty()
        assertEquals("导入后应当恰好落盘一张图", 1, imported.size)
        val name = imported.first().name
        assertTrue("导入的图片按 imp_ 前缀改名：$name", name.startsWith("imp_"))
        assertTrue("扩展名保留", name.endsWith(".jpg"))
        assertArrayEquals(
            "图片内容必须逐字节一致",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03),
            imported.first().readBytes(),
        )
        val restoredBlock = repository.getBlocks(repository.getDiaryByDay(dayKey)!!.id).first()
        assertTrue(
            "content_json 里的 src 应当指向改名后的文件",
            restoredBlock.contentJson.contains("images/$name"),
        )
        file.delete()
    }

    // ------------------------------------------------------------------ 导入

    @Test
    fun `skip mode keeps the existing day and overwrite replaces it`(): Unit = runBlocking {
        // 先造一份"备份"：这一天只有一条片段
        repository.prepare()
        writeDay("2026-09-14", "备份里的内容")
        val packageFile = backupService.exportBackup(includeSettings = false)

        // 本机这一天已有别的内容
        repository.deleteDiaryByDay("2026-09-14")
        writeDay("2026-09-14", "本机的内容")
        val localBlockId = repository.getBlocks(repository.getDiaryByDay("2026-09-14")!!.id).first().id

        // SKIP：整天跳过
        val skipped = backupService.importBackup(packageFile, ImportMode.SKIP)
        assertEquals(0, skipped.importedDays)
        assertEquals(1, skipped.skippedDays)
        assertEquals(0, skipped.overwrittenDays)
        assertEquals("本机的内容", firstSearchText("2026-09-14"))
        assertNotNull("本机的片段没有被替换", repository.getBlocks(repository.getDiaryByDay("2026-09-14")!!.id).firstOrNull { it.id == localBlockId })

        // OVERWRITE：整天替换
        val overwritten = backupService.importBackup(packageFile, ImportMode.OVERWRITE)
        assertEquals(0, overwritten.importedDays)
        assertEquals(0, overwritten.skippedDays)
        assertEquals(1, overwritten.overwrittenDays)
        assertEquals("备份里的内容", firstSearchText("2026-09-14"))
        packageFile.delete()
    }

    @Test
    fun `import restores settings when the package carries them`(): Unit = runBlocking {
        repository.prepare()
        writeDay("2026-09-14", "带设置导出")
        settings.save(
            settings.load().copy(dayStartHour = 6, themeMode = AppSettings.THEME_DARK),
        )
        val packageFile = backupService.exportBackup(includeSettings = true)

        // 恢复到默认设置，再导入
        settings.save(AppSettings())
        assertEquals(4, settings.load().dayStartHour)

        val stats = backupService.importBackup(packageFile, ImportMode.OVERWRITE)
        assertTrue("备份里带了设置，应当一并恢复", stats.settingsRestored)
        assertEquals(6, settings.load().dayStartHour)
        assertEquals(AppSettings.THEME_DARK, settings.load().themeMode)
        packageFile.delete()
    }

    @Test
    fun `a backup without settings does not touch the local settings`(): Unit = runBlocking {
        repository.prepare()
        writeDay("2026-09-14", "仅数据导出")
        settings.save(settings.load().copy(dayStartHour = 6))
        val packageFile = backupService.exportBackup(includeSettings = false)

        settings.save(AppSettings())

        val stats = backupService.importBackup(packageFile, ImportMode.OVERWRITE)
        assertFalse(stats.settingsRestored)
        assertEquals("没有带设置就不该改本地设置", 4, settings.load().dayStartHour)
        packageFile.delete()
    }

    // ------------------------------------------------------------------ 辅助

    private suspend fun writeDay(dayKey: String, text: String) {
        // 用 dayStartHour=4 反推一个落在这天的时刻（中午 12:00，避免跨日边界）
        val startUtc = DateUtil.parseDayKey(dayKey)!!.atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.addBlock(
            startTimeUtc = startUtc,
            locText = "",
            latitude = 0.0,
            longitude = 0.0,
            locPrecision = LocPrecision.none,
            region = LocRegion("", "", "", "", "", LocQuality.none),
            contentJson = ContentFlatten.serializeContent(listOf(ContentPart(text = text))),
            dayStartHour = 4,
        )
    }

    private suspend fun firstSearchText(dayKey: String): String {
        val diary = repository.getDiaryByDay(dayKey) ?: return ""
        val block = repository.getBlocks(diary.id).firstOrNull() ?: return ""
        return block.searchText
    }
}
