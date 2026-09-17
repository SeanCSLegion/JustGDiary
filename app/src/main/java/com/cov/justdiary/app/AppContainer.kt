package com.cov.justdiary.app

import android.content.Context

/**
 * 手工依赖容器（不引入 Hilt，理由见 docs/android-migration-plan.md §3）。
 *
 * iOS 侧的数据层/服务层都是进程级单例（`DiaryRepository.shared`、`SettingsStore`、
 * `ReminderService`（enum static）、`Lunar`（enum static）…）。Android 侧在这里对应装配：
 *
 * | iOS                              | 这里（M1/M2 起接入）              |
 * |----------------------------------|-----------------------------------|
 * | `DiaryRepository.shared`         | `diaryRepository`                 |
 * | `SettingsStore`（UserDefaults）   | `settingsStore`（DataStore）       |
 * | `BackupService` / `ZipArchive`   | `backupService`                   |
 * | `LocationService`                | `locationService`（GMS 探测）      |
 * | `ReminderService`                | `reminderScheduler`（WorkManager） |
 *
 * M0 只提供 context；各单例在对应里程碑以 `by lazy` 加入，避免提前引入未实现的类型。
 */
class AppContainer(val appContext: Context)
