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

/** `language`：语言 / 地区。 */
    val Language: ImageVector by lazy {
        materialIcon(
            "Language",
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2s.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2s.07-1.35.16-2h4.68c.09.65.16 1.32.16 2s-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2s-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z",
        )
    }

    /** `palette`：动态取色 / 主题色。 */
    val Palette: ImageVector by lazy {
        materialIcon(
            "Palette",
            "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z",
        )
    }

    /** `dark_mode`：深色模式（月牙）。 */
    val DarkMode: ImageVector by lazy {
        materialIcon(
            "DarkMode",
            "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z",
        )
    }

    /** `schedule`：时刻。 */
    val Schedule: ImageVector by lazy {
        materialIcon(
            "Schedule",
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
        )
    }

    /** `image`：图片（内联图占位与「缺图」提示）。 */
    val Image: ImageVector by lazy {
        materialIcon(
            "Image",
            "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
        )
    }

    /** `globe`：国家一级。 */
    val Globe: ImageVector by lazy {
        materialIcon(
            "Globe",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v2c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z",
        )
    }

    /** `place`：地点。 */
    val Place: ImageVector by lazy {
        materialIcon(
            "Place",
            "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
        )
    }

    /** `my_location`：定位权限 / 当前位置。 */
    val MyLocation: ImageVector by lazy {
        materialIcon(
            "MyLocation",
            "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06z",
        )
    }

    /** `edit`：编辑 / 历史日记可改。 */
    val Edit: ImageVector by lazy {
        materialIcon(
            "Edit",
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
        )
    }

    /** `notifications`：提醒。 */
    val Notifications: ImageVector by lazy {
        materialIcon(
            "Notifications",
            "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z",
        )
    }

    /** `upload` / `download`：导出 / 导入备份。 */
    val Upload: ImageVector by lazy {
        materialIcon("Upload", "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z")
    }

    val Download: ImageVector by lazy {
        materialIcon("Download", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
    }

    /** `info`：版本 / 关于。 */
    val Info: ImageVector by lazy {
        materialIcon("Info", "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z")
    }

    /** 把 Material Symbols 的 24dp path 数据变成 [ImageVector]（填充色留黑，由 `Icon` 着色）。 */

    // ---------------------------------------------------------------- 编辑器格式栏（9 键）
    //
    // 路径与 `docs/android-design/mockups/assets/ui.js` 的 ICONS 表同源
    // （Material Symbols 的 24dp 网格），因此格式栏的图标与设计稿逐像素一致。

    /** `format_size`：段落样式（大标题 / 小标题 / 正文）。 */
    val FormatSize: ImageVector by lazy {
        materialIcon("FormatSize", "M9 4v3h5v12h3V7h5V4H9zm-6 8h3v7h3v-7h3V9H3v3z")
    }

    /** `expand_more`：下拉指示（段落样式是**菜单**，不是开关）。 */
    val ExpandMore: ImageVector by lazy {
        materialIcon("ExpandMore", "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z")
    }

    /** `format_align_center`：居中。 */
    val AlignCenter: ImageVector by lazy {
        materialIcon("AlignCenter", "M7 15v2h10v-2H7zm-4 6h18v-2H3v2zm0-8h18v-2H3v2zm4-6v2h10V7H7zM3 3v2h18V3H3z")
    }

    /** `format_list_bulleted`：列表。 */
    val BulletList: ImageVector by lazy {
        materialIcon(
            "BulletList",
            "M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-6c-.83 0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-1.5.68-1.5 1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-6h14v-2H7v2zm0-8v2h14V5H7z",
        )
    }

    /** `checklist`：待办。 */
    val Checklist: ImageVector by lazy {
        materialIcon(
            "Checklist",
            "M22 7h-9v2h9V7zm0 8h-9v2h9v-2zM5.54 11L2 7.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 11zm0 8L2 15.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 19z",
        )
    }

    /** `format_quote`：引用（**不在**段落样式菜单里，它有自己的按钮）。 */
    val Quote: ImageVector by lazy {
        materialIcon("Quote", "M6 17h3l2-4V7H5v6h3zm8 0h3l2-4V7h-6v6h3z")
    }

    /** `format_bold`：粗体。 */
    val Bold: ImageVector by lazy {
        materialIcon(
            "Bold",
            "M15.6 10.79c.97-.67 1.65-1.77 1.65-2.79 0-2.26-1.75-4-4-4H7v14h7.04c2.09 0 3.71-1.7 3.71-3.79 0-1.52-.86-2.82-2.15-3.42zM10 6.5h3c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5h-3v-3zm3.5 9H10v-3h3.5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5z",
        )
    }

    /** `format_italic`：斜体。 */
    val Italic: ImageVector by lazy {
        materialIcon("Italic", "M10 4v3h2.21l-3.42 8H6v3h8v-3h-2.21l3.42-8H18V4z")
    }

    /** `strikethrough`：删除线。 */
    val Strikethrough: ImageVector by lazy {
        materialIcon(
            "Strikethrough",
            "M7.24 8.75c-.26-.48-.39-1.03-.39-1.67 0-.61.13-1.16.4-1.67.26-.5.63-.93 1.11-1.29.48-.35 1.05-.63 1.72-.83.66-.19 1.39-.29 2.18-.29.81 0 1.54.11 2.21.34.66.22 1.23.54 1.69.94.47.4.83.88 1.08 1.43.25.55.38 1.15.38 1.81h-3.01c0-.31-.05-.59-.15-.85-.09-.27-.24-.49-.44-.68-.2-.19-.45-.33-.75-.44-.3-.1-.66-.16-1.06-.16-.39 0-.74.04-1.03.13-.29.09-.53.21-.72.36-.19.16-.34.34-.44.55-.1.21-.16.43-.16.66 0 .25.05.48.16.68.11.2.28.39.51.55.23.16.53.31.9.44.36.13.79.26 1.28.38h.03c.34.08.68.17 1.02.28h6.77v3H7.24zM21 14c0 .34-.02.66-.06.97-.04.31-.11.6-.21.87-.1.27-.24.52-.43.75-.19.23-.42.43-.7.6-.28.17-.61.3-.99.4-.38.1-.81.15-1.29.15-.9 0-1.68-.17-2.33-.5-.65-.33-1.16-.78-1.52-1.34-.36-.56-.54-1.2-.54-1.92h3.01c0 .34.07.63.21.86.14.23.33.42.57.55.24.13.52.2.83.2.3 0 .55-.05.76-.14.21-.09.38-.22.51-.38.13-.16.23-.35.29-.56.06-.21.09-.44.09-.68 0-.24-.03-.47-.1-.68-.07-.21-.17-.4-.3-.56-.13-.16-.29-.29-.47-.38H21z",
        )
    }

    /** `history`：放弃修改（回到进入编辑时的内容）。 */
    val History: ImageVector by lazy {
        materialIcon(
            "History",
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
        )
    }

    /** `format_underlined`：下划线。 */
    val Underline: ImageVector by lazy {
        materialIcon(
            "Underline",
            "M12 17c3.31 0 6-2.69 6-6V3h-2.5v8c0 1.93-1.57 3.5-3.5 3.5S8.5 12.93 8.5 11V3H6v8c0 3.31 2.69 6 6 6zm-7 2v2h14v-2H5z",
        )
    }

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
