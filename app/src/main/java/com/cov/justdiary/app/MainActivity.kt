package com.cov.justdiary.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.cov.justdiary.JustDiaryApplication
import com.cov.justdiary.R
import com.cov.justdiary.service.Log
import com.cov.justdiary.ui.root.RootScreen
import com.cov.justdiary.ui.root.StartupErrorScreen
import com.cov.justdiary.ui.settings.SettingsViewModel
import com.cov.justdiary.ui.theme.JustDiaryTheme
import com.cov.justdiary.ui.theme.isDarkTheme
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity。
 *
 * 用 [AppCompatActivity] 作为宿主，是为了让应用内语言切换
 * （`AppCompatDelegate.setApplicationLocales`）走官方机制；
 * Material 3 的配色由 Compose 的 [JustDiaryTheme] 负责，XML 主题只提供合法的窗口底。
 *
 * 启动序列对齐 iOS 的 `JustDiaryApp`：
 * 1. [SettingsViewModel.bootstrap] 应用外观、开库、跑一次性迁移、建索引、排提醒；
 * 2. 主题（含动态取色）直接由设置驱动 —— 设置页改完立刻生效，不需要重建 Activity（P1-12）；
 * 3. 每次回到前台重排一次提醒 + 刷新权限状态（iOS 的 `scenePhase == .active`）。
 *
 * 深链 `justdiary://editor?dayKey=…` / `justdiary://editor` / `justdiary://search`
 * 在同一层接入（对应 iOS 的 `LaunchIntent`）。
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

    /** 通知权限只主动申请一次；拒绝后设置页仍保留入口（P1-7）。 */
    private val notificationPromptPrefs by lazy {
        getSharedPreferences(NOTIFICATION_PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        LaunchIntent.consume(intent)
        DebugFlags.consume(intent)

        // 启动序列是挂起的（开库/迁移/索引/排期），不阻塞首帧：界面先按当前设置渲染。
        // P1-16：失败由 ViewModel 收进 startupError，界面显示错误态而不是空日记本。
        lifecycleScope.launch { settingsViewModel.bootstrap() }

        setContent {
            val state by settingsViewModel.state.collectAsState()
            var showNotificationRationale by rememberSaveable { mutableStateOf(false) }
            val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                notificationPromptPrefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
                if (granted) {
                    lifecycleScope.launch { settingsViewModel.onNotificationPermissionResult(true) }
                }
            }

            // P1-7：首次启动（或升级到 Android 13 后第一次打开）先解释用途，再弹系统授权。
            LaunchedEffect(state.startupError, state.settings.remindEnabled) {
                if (state.startupError == null && shouldOfferNotificationPermission(state.settings.remindEnabled)) {
                    showNotificationRationale = true
                }
            }

            val darkTheme = isDarkTheme(state.settings.themeMode)
            JustDiaryTheme(
                themeMode = state.settings.themeMode,
                dynamicColor = state.settings.dynamicColor,
            ) {
                val surface = MaterialTheme.colorScheme.surface
                // P1-12：运行期切主题不重建 Activity；窗口底与系统栏图标在这里跟 Compose 同步。
                SideEffect { applyWindowAppearance(surface, darkTheme) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    val startupError = state.startupError
                    if (startupError != null) {
                        StartupErrorScreen(
                            detail = startupError,
                            onRetry = {
                                lifecycleScope.launch { settingsViewModel.bootstrap() }
                            },
                        )
                    } else {
                        RootScreen()
                    }
                }

                if (showNotificationRationale) {
                    AlertDialog(
                        onDismissRequest = {
                            showNotificationRationale = false
                            notificationPromptPrefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
                        },
                        title = { Text(stringResource(R.string.notification_rationale_title)) },
                        text = { Text(stringResource(R.string.notification_rationale_msg)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showNotificationRationale = false
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                            ) { Text(stringResource(R.string.notification_rationale_allow)) }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showNotificationRationale = false
                                    notificationPromptPrefs.edit()
                                        .putBoolean(KEY_NOTIFICATION_ASKED, true)
                                        .apply()
                                },
                            ) { Text(stringResource(R.string.notification_rationale_not_now)) }
                        },
                    )
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

    /** 首次启动是否该弹"为什么需要通知"的说明。 */
    private fun shouldOfferNotificationPermission(remindEnabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!remindEnabled) return false
        if (notificationPromptPrefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    /** 主题切换后同步窗口底与系统栏图标颜色（不重建 Activity）。 */
    private fun applyWindowAppearance(surface: Color, dark: Boolean) {
        window.setBackgroundDrawable(ColorDrawable(surface.toArgb()))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private companion object {
        const val NOTIFICATION_PREFS = "startup"
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}
