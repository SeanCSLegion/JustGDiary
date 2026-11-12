package com.cov.justdiary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.BackupService
import com.cov.justdiary.service.SettingsStore
import com.cov.justdiary.service.ZipArchive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0-0：恶意备份不得写出 `images/`，也不得删除数据库/设置。
 *
 * 覆盖两条独立攻击面：
 * 1. manifest 的 `images[].file` / `images[].src` 自己带 `../`；
 * 2. manifest 干净、但备份库的 `content_json.src` 带 `../`（导入后编辑/删除时才会触发）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupSecurityRobolectricTest {

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
        context.cacheDir.listFiles()?.filter { it.name.endsWith(".jdiary") }?.forEach { it.delete() }
    }

    @Test
    fun `manifest file with dot dot is rejected before import`(): Unit = runBlocking {
        repository.prepare()
        writeDay("2026-11-12", "正常的一天")
        val valid = backupService.exportBackup(includeSettings = false)
        val dbBytes = ZipArchive.open(valid).use { it.readBytes("db/just_diary.db")!! }

        val manifest = baseManifest(
            images = listOf(
                BackupImageRef(
                    src = "images/a.jpg",
                    file = "images/a.jpg/../../databases/just_diary.db",
                ),
            ),
        )
        val evil = packageWith(manifest, dbBytes)
        assertRejected(evil)
    }

    @Test
    fun `manifest src with dot dot is rejected before import`(): Unit = runBlocking {
        repository.prepare()
        writeDay("2026-11-12", "正常的一天")
        val valid = backupService.exportBackup(includeSettings = false)
        val dbBytes = ZipArchive.open(valid).use { it.readBytes("db/just_diary.db")!! }

        val manifest = baseManifest(
            images = listOf(
                BackupImageRef(
                    src = "images/../../databases/just_diary.db",
                    file = "images/a.jpg",
                ),
            ),
        )
        val evil = packageWith(manifest, dbBytes)
        assertRejected(evil)
    }

    @Test
    fun `content json with dot dot is rejected before any row is written`(): Unit = runBlocking {
        repository.prepare()
        writeDay("2026-11-12", "正常的一天")
        val valid = backupService.exportBackup(includeSettings = false)
        val dbBytes = ZipArchive.open(valid).use { it.readBytes("db/just_diary.db")!! }

        // 把备份库里的 content_json 改成“合法 manifest + 非法 src”的组合。
        val tempDb = File(context.cacheDir, "evil-${System.nanoTime()}.db")
        tempDb.writeBytes(dbBytes)
        SQLiteDatabase.openDatabase(tempDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "UPDATE edit_block SET content_json = ?",
                arrayOf(
                    ContentFlatten.serializeContent(
                        listOf(
                            ContentPart(
                                style = ContentPartStyle.image,
                                src = "images/../../databases/just_diary.db",
                            ),
                        ),
                    ),
                ),
            )
        }
        val modified = tempDb.readBytes()
        tempDb.delete()

        val evil = packageWith(baseManifest(images = emptyList()), modified)
        assertRejected(evil)

        // 本机原有的那一天不能因为一次失败导入被改掉。
        val day = repository.getDiaryByDay("2026-11-12")
        assertTrue("失败导入不应影响现有数据", day != null)
    }

    // ------------------------------------------------------------------ 辅助

    private fun baseManifest(images: List<BackupImageRef>) = BackupManifest(
        formatVersion = BackupManifest.FORMAT_VERSION,
        appVersion = "test",
        exportedAt = 0,
        includeSettings = false,
        diaryCount = 0,
        blockCount = 0,
        imageCount = images.size,
        images = images,
        settingsJson = "",
    )

    private fun packageWith(manifest: BackupManifest, dbBytes: ByteArray): File {
        val file = File(context.cacheDir, "evil-${System.nanoTime()}.jdiary")
        ZipArchive.create(
            file,
            listOf(
                ZipArchive.Source.Bytes("manifest.json", json.encodeToString(manifest).toByteArray()),
                ZipArchive.Source.Bytes("db/just_diary.db", dbBytes),
            ),
        )
        return file
    }

    private suspend fun assertRejected(file: File) {
        try {
            backupService.importBackup(file, ImportMode.OVERWRITE)
            fail("恶意备份应当被拒绝：${file.name}")
        } catch (_: BackupException.InvalidManifest) {
            // 期望路径：整包拒绝，不做“清洗后继续导入”。
        } finally {
            file.delete()
        }
    }

    private suspend fun writeDay(dayKey: String, text: String) {
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
}
