package com.cov.justdiary.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 本设计需要、但 `material-icons-core` 里没有的图标。
 *
 * ## 为什么不引 `material-icons-extended`
 *
 * 那是一个 ~10MB 的 artifact，为了几个图标不值得（设计规范 `01-design-system.md` §5：
 * 「零第三方 UI 库」的同一精神）。这里按需内联 path —— 路径数据与
 * `docs/android-design/mockups/assets/ui.js` 的 `ICONS` 表同源，都是 Material Symbols
 * 的标准 24dp 网格，因此视觉上与 M3 自带图标完全一致。
 *
 * 用法与 `Icons.Filled.*` 完全一样：`Icon(AppIcons.CalendarMonth, contentDescription = …)`。
 */
object AppIcons {

    /** `calendar_month`：日历 + 一个代表"某一天"的小方块。主导航「日记」用。 */
    val CalendarMonth: ImageVector by lazy {
        materialIcon(
            "CalendarMonth",
            "M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11zM7 10h5v5H7z",
        )
    }

    /** `directions_walk`：行走的人。主导航「足迹」用。 */
    val DirectionsWalk: ImageVector by lazy {
        materialIcon(
            "DirectionsWalk",
            "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9L7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7",
        )
    }

    /** 把 Material Symbols 的 24dp path 数据变成 [ImageVector]（填充色留黑，由 `Icon` 着色）。 */
    private fun materialIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        ).build()
}
