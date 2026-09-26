package com.cov.justdiary.ui.root

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.cov.justdiary.R
import com.cov.justdiary.ui.components.AppIcons

/**
 * 主导航的四个目的地，对应 iOS `ViewModels/AppState.swift` 的 `AppTab`
 * 与设计稿底部的「日记 / 足迹 / 搜索 / 设置」。
 *
 * 图标取 Material Symbols（设计规范 `01-design-system.md` §5）：
 *  - **日记用 `calendar_month` 而不是 iOS 的 `house.fill`** —— 这一屏的主体就是日历，
 *    而"房子"在四个标签的语境里会被读成"首页"，与「日记」这个标签名不符；
 *  - 足迹用 `directions_walk`（与 iOS 的 `figure.walk` 同义）、搜索 / 设置同义。
 *    前两个不在 `material-icons-core` 里，见 [AppIcons]。
 */
enum class AppTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home(R.string.tab_home, AppIcons.CalendarMonth),
    Footprint(R.string.tab_footprint, AppIcons.DirectionsWalk),
    Search(R.string.tab_search, Icons.Filled.Search),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}
