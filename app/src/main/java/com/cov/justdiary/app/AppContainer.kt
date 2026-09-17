package com.cov.justdiary.app

import android.content.Context
import com.cov.justdiary.data.DiaryRepository
import com.cov.justdiary.service.BackupService
import com.cov.justdiary.service.MediaStore
import com.cov.justdiary.service.SettingsStore

/**
 * 手工依赖容器（不引入 Hilt，理由见 docs/android-migration-plan.md §3）。
 *
 * iOS 侧的数据层/服务层都是进程级单例（`DiaryRepository.shared`、`SettingsStore`（enum static）、
 * `BackupService`（enum static）…）。Android 侧在这里对应装配，**全部 lazy**：
 * 只有真正用到时才打开数据库、创建目录。
 *
 * | iOS | 这里 |
 * | --- | --- |
 * | `DiaryRepository.shared` | [repository] |
 * | `SettingsStore`（UserDefaults） | [settings] |
 * | `BackupService`（enum static） | [backupService] |
 * | `DiaryImageStore` / `MediaStore` | [mediaStore] |
 *
 * 定位 / 提醒 / 分享长图在后续里程碑接入（M7）。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val repository: DiaryRepository by lazy { DiaryRepository(appContext, settings) }

    val mediaStore: MediaStore by lazy { MediaStore(appContext) }

    val backupService: BackupService by lazy { BackupService(appContext, repository, settings) }
}
