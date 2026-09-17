package com.cov.justdiary.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.cov.justdiary.JustDiaryApplication
import com.cov.justdiary.service.Log
import com.cov.justdiary.ui.root.RootScreen
import com.cov.justdiary.ui.settings.SettingsViewModel
import com.cov.justdiary.ui.theme.JustDiaryTheme
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity。
 *
 * 用 [AppCompatActivity] 作为宿主，是为了让应用内的深色模式与应用内语言切换
 * （`AppCompatDelegate.setDefaultNightMode` / `setApplicationLocales`）走官方机制；
 * Material 3 的配色由 Compose 的 [JustDiaryTheme] 负责，XML 主题只提供合法的窗口底。
 *
 * 启动序列对齐 iOS 的 `JustDiaryApp`：
 * 1. [SettingsViewModel.bootstrap] 应用外观、开库、跑一次性迁移、建索引、排提醒；
 * 2. 主题（含动态取色）直接由设置驱动 —— 设置页改完立刻生效，不需要重建 Activity；
 * 3. 每次回到前台重排一次提醒 + 刷新权限状态（iOS 的 `scenePhase == .active`）。
 *
 * 深链 `justdiary://editor?dayKey=…` 在同一层接入（对应 iOS 的 `LaunchIntent`）。
 */
class MainActivity : AppCompatActivity() {

    private val container: AppContainer
        get() = (application as JustDiaryApplication).container

    /**
     * 设置页与应用外观共用同一个 ViewModel 实例：设置页在 Composable 里用
     * 同名 `viewModel()` 取到的是这里这一个（同一个 `ViewModelStore`，同一个默认 key）。
     */
    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory(container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 启动序列是挂起的（开库/迁移/索引/排期），不阻塞首帧：界面先按当前设置渲染。
        lifecycleScope.launch {
            runCatching { settingsViewModel.bootstrap() }
                .onFailure { Log.app.e("启动序列失败：${it.message}") }
        }

        setContent {
            val state by settingsViewModel.state.collectAsState()
            JustDiaryTheme(
                themeMode = state.settings.themeMode,
                dynamicColor = state.settings.dynamicColor,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            runCatching { settingsViewModel.onForeground() }
                .onFailure { Log.app.w("回前台重排提醒失败：${it.message}") }
        }
    }
}
