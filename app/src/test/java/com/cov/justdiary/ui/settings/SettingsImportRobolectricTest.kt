package com.cov.justdiary.ui.settings

import android.content.Context
import android.net.Uri
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.data.ImportMode
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.BackupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * P0-3：导入设置后要立刻生效；备份的换日时刻与当前不同时先问再重算。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsImportRobolectricTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var backupService: BackupService
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = AppContainer(context)
        backupService = BackupService(context, container.repository, container.settings)
        viewModel = SettingsViewModel(container)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.deleteDatabase(JustDiarySchema.DB_NAME)
        container.repository.imagesDir.deleteRecursively()
    }

    /**
     * P2-6b：快速连点两个开关时，后一个必须看到前一个已经提交的值。
     */
    @Test
    fun `two rapid settings updates both persist`() = runBlocking {
        val old = container.settings.load()
        val targetDynamic = !old.dynamicColor
        val targetWeek = if (old.weekStart == AppSettings.WEEK_START_SUNDAY) {
            AppSettings.WEEK_START_MONDAY
        } else {
            AppSettings.WEEK_START_SUNDAY
        }

        viewModel.setDynamicColor(targetDynamic)
        viewModel.setWeekStart(targetWeek)

        withTimeout(5_000) {
            while (true) {
                val now = container.settings.load()
                if (now.dynamicColor == targetDynamic && now.weekStart == targetWeek) break
                kotlinx.coroutines.delay(5)
            }
        }
        val now = container.settings.load()
        assertEquals(targetDynamic, now.dynamicColor)
        assertEquals(targetWeek, now.weekStart)
    }

    @Test
    fun `import applies settings but asks before recomputing a different day start`() = runBlocking {
        // 1. 备份：换日 6 点 + 深色 + 周日为一周开始。
        val backupTime = at(hour = 12)
        container.settings.save(
            AppSettings(
                dayStartHour = 6,
                themeMode = AppSettings.THEME_DARK,
                weekStart = AppSettings.WEEK_START_SUNDAY,
            ),
        )
        addBlock(backupTime, dayStartHour = 6, text = "备份里的一天")
        val backupFile = backupService.exportBackup(includeSettings = true)

        // 2. 本机恢复成默认，并造一条“05:00、当前 4 点换日”的本地日记。
        container.settings.save(AppSettings())
        addBlock(at(hour = 5), dayStartHour = 4, text = "本机 05:00 的日记")
        viewModel.refreshSettings()

        // 3. stage → runImport。
        viewModel.stageImport(context, Uri.fromFile(backupFile))
        val staged = await { it.pendingImport != null || it.alert != null }
        assertNotNull("文件应当被复制进缓存", staged.pendingImport)
        viewModel.runImport(ImportMode.SKIP)

        val pending = await {
            it.pendingImportDayStartHour != null || it.alert is SettingsAlert.ImportSucceeded
        }
        assertEquals("备份的换日时刻应当先问用户", 6, pending.pendingImportDayStartHour)
        assertEquals("当前换日时刻尚未改变", 4, container.settings.load().dayStartHour)
        assertEquals("其它设置已经生效", AppSettings.THEME_DARK, container.settings.load().themeMode)
        assertEquals(AppSettings.WEEK_START_SUNDAY, container.settings.load().weekStart)

        // 4. 重算 → 换日时刻才真正落库。
        viewModel.confirmImportDayStart()
        val done = await { it.pendingImportDayStartHour == null && it.alert is SettingsAlert.ImportSucceeded }
        assertEquals(6, container.settings.load().dayStartHour)
        assertNull(done.pendingImportDayStartHour)
    }

    private suspend fun await(predicate: (SettingsUiState) -> Boolean): SettingsUiState =
        withTimeout(10_000) { viewModel.state.first(predicate) }

    private fun at(hour: Int): Long = LocalDate.of(2026, 11, 12)
        .atTime(LocalTime.of(hour, 0))
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private suspend fun addBlock(startUtc: Long, dayStartHour: Int, text: String) {
        container.repository.addBlock(
            startTimeUtc = startUtc,
            locText = "",
            latitude = 0.0,
            longitude = 0.0,
            locPrecision = LocPrecision.none,
            region = LocRegion("", "", "", "", "", LocQuality.none),
            contentJson = ContentFlatten.serializeContent(listOf(ContentPart(text = text))),
            dayStartHour = dayStartHour,
        )
    }
}
