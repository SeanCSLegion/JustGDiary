package com.cov.justdiary.ui.settings

import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.data.db.JustDiarySchema
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.content.Context
import java.io.File

/**
 * P1-16：开库失败必须进入显式错误态，而不是静默显示"空日记本"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupErrorRobolectricTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dbFile = context.getDatabasePath(JustDiarySchema.DB_NAME)
        dbFile.parentFile?.mkdirs()
        // 用一个同名目录占住数据库路径，SQLite 打开必然失败
        dbFile.deleteRecursively()
        dbFile.mkdirs()
    }

    @After
    fun tearDown() {
        dbFile.deleteRecursively()
        context.deleteDatabase(JustDiarySchema.DB_NAME)
    }

    @Test
    fun `a failing prepare surfaces a startup error instead of an empty diary`() {
        val viewModel = SettingsViewModel(AppContainer(context))
        runBlocking { viewModel.bootstrap() }

        assertNotNull(
            "开库失败必须让界面进入错误态",
            viewModel.state.value.startupError,
        )
    }

    @Test
    fun `removing the bad database and retrying recovers`() {
        val broken = SettingsViewModel(AppContainer(context))
        runBlocking { broken.bootstrap() }
        assertNotNull(broken.state.value.startupError)

        dbFile.deleteRecursively()

        val retried = SettingsViewModel(AppContainer(context))
        runBlocking { retried.bootstrap() }
        assertNull("库恢复后重试应当进入正常界面", retried.state.value.startupError)
    }
}
