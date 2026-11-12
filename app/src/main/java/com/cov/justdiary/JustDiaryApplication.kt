package com.cov.justdiary

import android.app.Application
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
        // P0-1：顺手清掉超过 14 天的旧草稿；失败不影响启动。
        runCatching { container.editorDrafts.clearStale() }
        Log.app.i("JustDiaryApplication created")
    }
}
