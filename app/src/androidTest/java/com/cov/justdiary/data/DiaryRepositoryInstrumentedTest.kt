package com.cov.justdiary.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** P2-8：真设备/模拟器上的 SQLite 往返 + 搜索冒烟。 */
@RunWith(AndroidJUnit4::class)
class DiaryRepositoryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var repository: DiaryRepository
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(JustDiarySchema.DB_NAME)
        settings = SettingsStore(context)
        repository = DiaryRepository(context, settings)
        runBlocking { repository.prepare() }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(JustDiarySchema.DB_NAME)
        repository.imagesDir.deleteRecursively()
    }

    @Test
    fun `a block round trips and is searchable`(): Unit = runBlocking {
        val start = System.currentTimeMillis()
        val dayKey = DateUtil.dayKeyForUtc(start, 4)
        repository.addBlock(
            startTimeUtc = start,
            locText = "上海市浦东新区",
            latitude = 31.2,
            longitude = 121.5,
            locPrecision = LocPrecision.district,
            region = LocRegion("中国", "CN", "上海市", "上海市", "浦东新区", LocQuality.precise),
            contentJson = ContentFlatten.serializeContent(
                listOf(ContentPart(text = "instrumented 日记正文")),
            ),
            dayStartHour = 4,
        )

        val diary = repository.getDiaryByDay(dayKey)
        assertTrue("当天应当有日记", diary != null)
        val block = repository.getBlocks(diary!!.id).single()
        assertTrue(block.contentJson.contains("instrumented 日记正文"))

        val hits = repository.search(
            keyword = "instrumented",
            fromKey = "1900-01-01",
            toKey = "2100-12-31",
            filter = LocFilter(),
            offset = 0,
        )
        assertEquals(1, hits.total)
        assertEquals(dayKey, hits.items.single().dayKey)
    }

    @Test
    fun `deleting a day removes its blocks`(): Unit = runBlocking {
        val start = System.currentTimeMillis()
        val dayKey = DateUtil.dayKeyForUtc(start, 4)
        repository.addBlock(
            startTimeUtc = start,
            locText = "",
            latitude = 0.0,
            longitude = 0.0,
            locPrecision = LocPrecision.none,
            region = LocRegion("", "", "", "", "", LocQuality.none),
            contentJson = ContentFlatten.serializeContent(listOf(ContentPart(text = "待删除"))),
            dayStartHour = 4,
        )
        repository.deleteDiaryByDay(dayKey)
        assertEquals(null, repository.getDiaryByDay(dayKey))
    }
}
