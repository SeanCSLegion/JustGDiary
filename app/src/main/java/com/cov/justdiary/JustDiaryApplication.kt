package com.cov.justdiary

import android.app.Application
import android.content.ComponentCallbacks2
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.service.Log

/**
 * 进程入口。
 *
 * 对应 iOS 侧的 `App/JustDiaryApp.swift` + `AppDelegate`：iOS 用 `@UIApplicationDelegateAdaptor`
 * 承载极薄的启动逻辑，数据层是 `DiaryRepository.shared` 这类单例；Android 侧改为在 Application
 * 中装配一个手工容器 [AppContainer]（见 docs/android-migration-plan.md §2.1、§3「DI」）。
 */
class JustDiaryApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        // P1-12：只在这里（Activity 创建前）对齐一次 AppCompat 的 DayNight，
        // 让冷启动 / 语言重建的起始窗口背景与用户主题一致；运行期切主题不再重建。
        runCatching { container.appSettingsApplier.applyInitialNightMode() }
            .onFailure { Log.app.w("初始化窗口主题失败：${it.message}") }
        // P0-1：顺手清掉超过 14 天的旧草稿；失败不影响启动。
        runCatching { container.editorDrafts.clearStale() }
        Log.app.i("JustDiaryApplication created")
    }

    /** P2-1：系统内存紧张时清掉图片 LRU（图都能从磁盘重新解码）。 */
    @Suppress("DEPRECATION") // ComponentCallbacks2 的内存档位常量在新 SDK 标记废弃，但值仍有效
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            container.mediaStore.trimMemory()
        }
    }
}
