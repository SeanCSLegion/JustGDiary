package com.cov.justdiary.ui.home

import android.content.Context
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.EditBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P0-4 / P0-5：首页拆出的两个纯状态问题。
 *
 * - 今天的 day_key 必须走 `day_start_hour`（凌晨 0–4 点不能与日记页差一天）；
 * - 快速连点两天时，旧查询晚回来不能覆盖新选择。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeViewModelTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = AppContainer(context)
        container.settings.save(AppSettings(dayStartHour = 4))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.deleteDatabase(JustDiarySchema.DB_NAME)
    }

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, 13, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `today follows the configured day start hour`() = runBlocking {
        var now = at(hour = 1)
        val viewModel = HomeViewModel(container, now = { now })

        assertEquals("01:00 + 4 点换日 → 仍是前一天", "2026-11-12", viewModel.state.value.selectedKey)

        now = at(hour = 12)
        viewModel.refreshToday()

        assertEquals("中午已经进入 11-13", "2026-11-13", viewModel.state.value.selectedKey)
    }

    @Test
    fun `a late load cannot overwrite a newer selection`() = runBlocking {
        val loadA = CompletableDeferred<List<EditBlock>?>()
        val loadB = CompletableDeferred<List<EditBlock>?>()
        val viewModel = HomeViewModel(
            container,
            now = { at(hour = 12) },
            injectedDayLoader = { key ->
                when (key) {
                    "A" -> loadA.await()
                    "B" -> loadB.await()
                    else -> null
                }
            },
        )

        viewModel.select("A")
        viewModel.select("B")
        loadB.complete(emptyList())
        withTimeout(5_000) {
            while (viewModel.state.value.dayBlocks == null) yield()
        }
        // A 晚回来：它属于上一代请求，不能把 B 的片段写回去。
        loadA.complete(listOf(block(id = 99)))
        yield()

        assertEquals("B", viewModel.state.value.selectedKey)
        assertTrue("B 是空的一天，不能被 A 的内容覆盖", viewModel.state.value.dayBlocks!!.isEmpty())
    }

    private fun block(id: Long): EditBlock = EditBlock(
        id = id,
        diaryId = 1,
        startTimeUtc = 0,
        locText = "",
        latitude = 0.0,
        longitude = 0.0,
        contentJson = "[]",
        searchText = "",
        locPrecision = "",
        locQuality = "",
        country = "",
        countryCode = "",
        region1 = "",
        region2 = "",
        region3 = "",
        createdUtc = 0,
        updatedUtc = 0,
    )
}
