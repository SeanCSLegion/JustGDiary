package com.cov.justdiary.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cov.justdiary.R
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.ui.components.AppIcons

/**
 * 设置页的文案与格式小工具：主题/语言/权限标签、图标表、字节与备份文件名。
 *
 * 从 [SettingsScreen] 拆出；纯函数优先，便于 JVM 断言。
 */

// ---------------------------------------------------------------------- 文案与图标

@Composable
internal fun themeLabel(themeMode: String): String = when (themeMode) {
    AppSettings.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
    AppSettings.THEME_DARK -> stringResource(R.string.settings_theme_dark)
    else -> stringResource(R.string.settings_theme_system)
}

@Composable
internal fun languageLabel(appLanguage: String): String = when (appLanguage) {
    // 语言名按 iOS 的做法显示**母语原文**，不随界面语言翻译
    AppSettings.LANGUAGE_ZH -> "中文"
    AppSettings.LANGUAGE_EN -> "English"
    else -> stringResource(R.string.settings_lang_system)
}

@Composable
internal fun locationPermissionLabel(permission: LocationPermissionState): String = when (permission) {
    LocationPermissionState.NONE -> stringResource(R.string.settings_loc_none)
    LocationPermissionState.FUZZY -> stringResource(R.string.settings_loc_fuzzy)
    LocationPermissionState.EXACT -> stringResource(R.string.settings_loc_exact)
}

/**
 * 设置行的图标（设计规范 `05-search-footprint-settings.md` §3.3–§3.6 的逐行表）。
 *
 * 全部走 [AppIcons] 的 Material Symbols 内联 path —— M0 那批临时图标（语言用定位针、
 * 主题用星星、动态取色用爱心）与语义完全对不上，看起来像占位符。
 */
internal object SettingsIcons {
    val language: ImageVector = AppIcons.Language
    val theme: ImageVector = AppIcons.DarkMode
    val dynamicColor: ImageVector = AppIcons.Palette
    val dayStart: ImageVector = AppIcons.Schedule
    val weekStart: ImageVector = AppIcons.CalendarMonth
    val autoTime: ImageVector = AppIcons.Schedule
    val autoLoc: ImageVector = AppIcons.Place
    val locationPermission: ImageVector = AppIcons.MyLocation
    val shareLoc: ImageVector = AppIcons.ShareLocation
    val historyEdit: ImageVector = AppIcons.Edit
    val reminder: ImageVector = AppIcons.Notifications
    val remindTime: ImageVector = AppIcons.Schedule
    val notificationPermission: ImageVector = AppIcons.Notifications
    val storage: ImageVector = AppIcons.FormatSize
    val cleanup: ImageVector = Icons.Filled.Delete
    val export: ImageVector = AppIcons.Upload
    val import: ImageVector = AppIcons.Download
    val about: ImageVector = AppIcons.Info
}

/** P2-5：把字节数格式化成系统短单位（KB / MB / GB）。 */
@Composable
internal fun formatBytes(bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)

/** SAF 用的 MIME：`.jdiary` 没有注册类型，用 octet-stream 才能保住扩展名。 */
internal const val MIME_BACKUP = "application/octet-stream"

/** 默认文件名，与 iOS 的 `JustDiary_yyyyMMdd_HHmmss.jdiary` 同名规则。 */
internal fun defaultBackupFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    return "JustDiary_$stamp.jdiary"
}
