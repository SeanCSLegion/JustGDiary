package com.cov.justdiary.app

import android.content.Intent
import android.content.pm.ActivityInfo
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
import com.cov.justdiary.R
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
        applyOrientationPolicy()
        super.onCreate(savedInstanceState)
        LaunchIntent.consume(intent)
        DebugFlags.consume(intent)

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
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    RootScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LaunchIntent.consume(intent)
        DebugFlags.consume(intent)
    }

    override fun onStart() {
        super.onStart()
        // "回到前台"信号：日记页据此重算 todayKey / 重读设置（iOS 的 didBecomeActive 分支）。
        // 这一条也是**跨日保存**能问对问题的前提：在编辑页切出去待一会儿再回来时，
        // "今天"可能已经是另一天了（用户在系统里改时区、或正好跨过换日时刻）。
        AppForeground.onStart()
        lifecycleScope.launch {
            runCatching { settingsViewModel.onForeground() }
                .onFailure { Log.app.w("回前台重排提醒失败：${it.message}") }
        }
    }

    /**
     * 方向策略：**手机锁竖屏，平板自由旋转**（设计规范 §1）。
     *
     * 判据用 `sw600dp`（最小宽度）而不是当前宽度：后者会随旋转变化，
     * 也会在折叠屏展开时跳变 —— 那样会出现"展开后突然允许横屏"的怪异行为。
     *
     * 在 `onCreate` 最前面调用，避免先按系统方向渲染一帧再转回来。
     * 注意 targetSdk 36 起，Android 16 对**大屏**（`sw ≥ 600dp`）会忽略方向声明 ——
     * 这与本策略一致（大屏本来就要自由旋转），手机上的锁定仍然生效。
     */
    private fun applyOrientationPolicy() {
        requestedOrientation = if (resources.getBoolean(R.bool.is_tablet)) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
