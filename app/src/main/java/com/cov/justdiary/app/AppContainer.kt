package com.cov.justdiary.app

import android.content.Context
import com.cov.justdiary.data.DiaryRepository
import com.cov.justdiary.service.BackupService
import com.cov.justdiary.service.ImageShareRenderer
import com.cov.justdiary.service.LocationService
import com.cov.justdiary.service.MediaStore
import com.cov.justdiary.service.ReminderScheduler
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

    /**
     * 定位（对应 iOS `LocationService`）。
     *
     * 与 iOS 不同：Android 需要**运行时权限**，所以这里只是个薄封装，
     * 真正的授权流程在 UI 层（`rememberLauncherForActivityResult`）触发，见方案 §8.1。
     */
    val locationService: LocationService by lazy { LocationService(appContext) }

    /** 每日提醒（对应 iOS `ReminderService`），内部用 WorkManager 排期。 */
    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(appContext, settings, repository)
    }

    /** 分享长图渲染（自绘，版式来自纯函数 `ShareLayout`）。 */
    val imageShareRenderer: ImageShareRenderer by lazy { ImageShareRenderer(appContext, mediaStore) }

    /** 设置变更的平台侧执行器（外观 / 语言 / day_key 重算 / 回填 / 提醒重排）。 */
    val appSettingsApplier: AppSettingsApplier by lazy {
        AppSettingsApplier(appContext, settings, repository, locationService, reminderScheduler)
    }
}
