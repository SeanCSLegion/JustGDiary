package com.cov.justdiary.ui.root

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.cov.justdiary.R

/**
 * 主导航的四个目的地，对应 iOS `ViewModels/AppState.swift` 的 `AppTab`
 * 与设计稿底部的「日记 / 足迹 / 搜索 / 设置」。
 *
 * 注意：M0 用的是临时图标，M2 阶段替换为正式的 Material Symbols 资源。
 */
enum class AppTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home(R.string.tab_home, Icons.Filled.Edit),
    Footprint(R.string.tab_footprint, Icons.Filled.Place),
    Search(R.string.tab_search, Icons.Filled.Search),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}
