package com.cov.justdiary.ui.diary

import androidx.compose.ui.unit.dp

/**
 * 段落几何的**唯一来源**。
 *
 * 三个渲染面都从这里取值，因此"编辑时看到的"与"阅读时看到的"是同一套数
 * （对应 iOS `BlockDecorations.swift` 的 `BlockMetrics`）：
 *
 * | 渲染面 | 载体 |
 * | --- | --- |
 * | 编辑态 | `EditText` + `Spannable`（[SpannableBridge]） |
 * | 阅读态 | Compose（`DiaryReadScreen`） |
 * | 首页日面板预览 | Compose（`HomeScreen.DayPanelPart`） |
 *
 * ## 取值依据（照 Google 的规范，不照抄 Apple 的比例）
 *
 * - **行高不在这里**：直接取 M3 排版角色的官方 `lineHeight`
 *   （`EditorTypography.textStyleFor` → `headlineMedium 28/36`、`titleLarge 22/28`、
 *   `bodyLarge 16/24`、`bodyMedium 14/20`）。Compose 的默认 `LineHeightStyle` 是
 *   `Proportional + Fixed`，而编辑态把 `ascent / descent` 同比例放大到同一个行高 ——
 *   两边是同一条规则，因此基线位置天然一致，这里不需要任何补偿。
 * - **段距 8dp**：M3 不定义富文本段距（`Type.kt` 与 `01-design-system.md` §2.4 都写了），
 *   所以取 4dp 栅格上的一档，三处共用同一个值，而不是各编一套比例（Apple 那边是
 *   `0.5×/0.55×/0.6×` 字号）。
 * - **标记槽 18dp + 间距 10dp**：正文起点 28dp，与设计稿 `.li .bul{width:18px}` /
 *   `gap:10px` 一致，换行后的第二行也对齐到同一个起点（悬挂缩进）。
 * - **勾选框**：M3 `CheckboxTokens` —— 容器 18dp、圆角 2dp、未选描边 2dp /
 *   `onSurfaceVariant`、选中 `primary` + `onPrimary`、图标 18dp。
 * - **引用**：文字内缩 16dp（M3 关键线）、底色 `surfaceContainerHigh`、圆角 4dp
 *   （M3 `extraSmall`）、竖条 4dp 宽 / 距列左 6dp / 上下各让 5dp、纵向内边距 6dp。
 */
object BlockGeometry {

    /** 段落之间的距离（三处共用）。 */
    val ParagraphGap = 8.dp

    /** 正文区上下留白：编辑区自己的 padding，阅读态与预览的块内上下边距。 */
    val TextInset = 6.dp

    /** 标记占的槽宽。 */
    val MarkerSlot = 18.dp

    /** 槽与文字之间的距离。 */
    val MarkerGap = 10.dp

    /** 标记行的正文起点（= 换行后每一行的左缩进）。 */
    val MarkerIndent = MarkerSlot + MarkerGap

    /**
     * 列表圆点直径。
     *
     * 取平台 `BulletSpan` 的默认值（半径 4dp）—— Google 自己的段落项目符号就是这个尺寸，
     * 比 iOS 的 6.5pt 稍大一点，CJK 正文里更立得住。
     */
    val BulletSize = 8.dp

    // ---- 待办勾选框（M3 Checkbox）----

    val CheckboxSize = 18.dp
    val CheckboxRadius = 2.dp
    val CheckboxBorder = 2.dp
    val CheckboxIcon = 18.dp

    /** 勾选框相对首行文字的下沉量（设计稿 `.todo .cbox{margin-top:3px}`）。 */
    val CheckboxTop = 3.dp

    // ---- 引用 ----

    /** 引用正文距列左边界的缩进（左竖条在里面）。 */
    val QuoteTextInset = 16.dp

    /** 左侧竖条距列左边界的距离。 */
    val QuoteBarLeading = 6.dp

    val QuoteBarWidth = 4.dp

    /** 竖条上下各让出多少（免得它顶到圆角外面）。 */
    val QuoteBarInset = 5.dp

    val QuoteBarRadius = 2.dp

    /** 底色块的圆角（M3 `extraSmall`）。 */
    val QuoteRadius = 4.dp

    /** 底色块在文字行盒之外上下各多出来多少（引用的纵向内边距）。 */
    val QuotePadding = 6.dp

    /** 引用正文距**色块右边**的距离（编辑态挂不上右边距，只有阅读态与预览用得到）。 */
    val QuoteTrailingPadding = 10.dp
}
