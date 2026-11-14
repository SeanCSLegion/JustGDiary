package com.cov.justdiary.ui.home

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.cov.justdiary.R
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.Lunar
import com.cov.justdiary.ui.components.AppIcons
import com.cov.justdiary.ui.components.AssistPill
import com.cov.justdiary.ui.components.PillVariant
import com.cov.justdiary.ui.diary.BlockGeometry
import com.cov.justdiary.ui.diary.MarkerRow
import com.cov.justdiary.ui.theme.EditorTypography
import com.cov.justdiary.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate

/**
 * 首页当日面板：空态、片段卡片、待办/引用预览与农历标签。
 *
 * 从 [HomeScreen] 拆出；数据来自 [HomeScreen] 的选中日状态，渲染与阅读页同源。
 */

// ---------------------------------------------------------------------- 日历

// ---------------------------------------------------------------------- 当日面板

/**
 * 竖屏下方的「选中日」面板 / 横屏右栏。对应 iOS `Views/Home/DayContentView.swift`
 * 与 `CalendarPaneViews.swift` 的 `DayPane`。
 *
 * 与 iOS 相同的三条业务规则：
 *  1. 只有**今天**才有写入口；未来的日期显示"还没有到来"；
 *  2. 有内容时底部给「继续写日记」→ **阅读态**（[onOpenDay]），空态给「写日记」→ 编辑态（[onWrite]）；
 *  3. 标题行显示完整日期 + 农历。
 *
 * 可见性放宽到 `internal` 是为了让 `HomeDayPanelTest` 能直接钉住第 2 条 ——
 * 从整个首页走一遍要先摆出周态才有当日面板，那样的用例测的是日历而不是这两个入口。
 */
@Composable
internal fun DayPanel(
    blocks: List<EditBlock>?,
    selectedDate: LocalDate,
    todayKey: String,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: suspend (String) -> ImageBitmap?,
    /** 点日记卡片 / 「继续写日记」→ 进**阅读态**（iOS 的 `openDiary`）。 */
    onOpenDay: () -> Unit,
    /**
     * 「写日记」（空态那一颗）→ 进**编辑态**（iOS 的 `openEditor`）。
     *
     * ⚠️「继续写日记」走的是 [onOpenDay]，不是这里（2026-09-28 用户返工：
     * 这一天已经有内容了，点「继续写日记」应该先让用户看到写过的东西，不该直接进编辑器）。
     */
    onWrite: () -> Unit,
    onToday: (() -> Unit)?,
    showHeader: Boolean,
    showLunar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dayKey = DateUtil.dayKeyOf(selectedDate)
    val isToday = dayKey == todayKey
    val isFuture = dayKey > todayKey
    val hasBlocks = !blocks.isNullOrEmpty()

    Column(modifier = modifier) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 设计值 40dp（分栏 44dp），但分栏右栏标题行要放一枚「今天」胶囊，
                    // M3 chip 的布局高是 48dp —— 取 min 48 让行高由它决定（规范 §2.3 的 44dp
                    // 是 iOS 的 44pt 触控目标，Android 的平台要求是 48dp）。
                    .heightIn(min = Spacing.hitTarget)
                    .padding(horizontal = Spacing.page),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = dateFormats?.dayTitle(selectedDate)
                        ?: DateFormats.dayTitleFor(selectedDate),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                // 这里**不画**相对日文案（今天 / 昨天 / 明天）：
                // iOS 的当日标题行只有「日期 + 农历」，"今天"是横屏右栏标题行里那枚**按钮**。
                // 首轮两处都画，于是横屏标题行出现两个"今天"（实测缺陷 D9）。
                // ⚠️ 农历**整串靠右**（用户 2026-09-27："周视图下面不是有一条日期的单独的栏吗？
                // 里面的农历放在右边。现在农历是在日期和星期后面的"；口径确认：放
                // **整个农历全称**，如「丙午年七月廿七」）。
                //
                // ⚠️ 权重必须加在**它前面那个 Spacer** 上，不能加在 `Text` 上：
                // `weight(1f, fill = false)` 的 Text 只按内容取宽、不占住分配到的空间，
                // 于是文字还贴在左边（实测：改动"生效"了但位置没动）。用弹性间隔把它顶到右边。
                Spacer(modifier = Modifier.weight(1f))
                if (showLunar) {
                    Text(
                        text = lunarLabelOf(selectedDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // 分栏时「今天」只在这一处（右栏标题行），竖屏周态不重复画（规范 §3.4）
                if (onToday != null) {
                    AssistPill(
                        text = stringResource(R.string.index_today),
                        onClick = onToday,
                        variant = PillVariant.Outlined,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 正文列限宽（规范 §4.2）：宽窗口里不限宽会把一行拉成 100+ 字
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasBlocks) {
                    blocks.orEmpty().forEach { block ->
                        DayBlockCard(
                            block = block,
                            dateFormats = dateFormats,
                            showTime = showTime,
                            imageLoader = imageLoader,
                            onOpenDay = onOpenDay,
                        )
                    }
                    if (isToday) {
                        // 「继续写日记」= **进阅读态**（2026-09-28 用户返工）。
                        // 这一天已经有内容，先让用户看到写过的内容；要接着写就在阅读页点
                        // 顶栏的「写点什么」，那才是「新建片段」的入口（与 iOS 的
                        // `openDiary` / `openEditor` 两个入口的分工一致）。
                        Button(onClick = onOpenDay, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.index_continue_write))
                        }
                    }
                } else {
                    DayEmptyState(isFuture = isFuture, isToday = isToday, onWrite = onWrite)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** 空态：图标 + 一行说明 +（仅今天）写入口。垂直居中，不靠"顶到 56dp"的硬编码。 */
@Composable
internal fun DayEmptyState(isFuture: Boolean, isToday: Boolean, onWrite: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Material 图标而不是 emoji：emoji 的字形随厂商变化，也不是设计语言的一部分
            Icon(
                imageVector = if (isFuture) AppIcons.DarkMode else AppIcons.Edit,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                if (isFuture) R.string.index_future_empty else R.string.index_day_empty,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (isToday) {
            Button(onClick = onWrite) { Text(stringResource(R.string.index_write)) }
        }
    }
}

/** 一天的日记片段卡片（只读渲染；编辑与勾选都在日记页里）。 */
@Composable
internal fun DayBlockCard(
    block: EditBlock,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: suspend (String) -> ImageBitmap?,
    onOpenDay: () -> Unit,
) {
    // `auto_time` 关闭时不显示开始时间，只保留地点（与 iOS 的 `showsTime` 一致）
    val startLine = buildString {
        if (showTime && block.startTimeUtc > 0) {
            append(dateFormats?.timeOf(Instant.ofEpochMilli(block.startTimeUtc)).orEmpty())
        }
        if (block.locText.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(block.locText)
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDay),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (startLine.isNotEmpty()) {
                Text(
                    text = startLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⚠️ 必须 remember：解析是 JSON 解码 +（v1 回退时）抛异常 + 逐 part `copy`，
            // 长日记 5~30ms，而它在**组合期**同步跑 —— 不 remember 的话每次重组
            //（选中日变化、切周态、数据刷新）都要重来一遍。
            val parts = remember(block.contentJson) { ContentFlatten.parseContent(block.contentJson) }
            parts.forEach { part ->
                DayPanelPart(part = part, imageLoader = imageLoader)
            }
        }
    }
}

/** 面板里的一个段落（只读）。 */
@Composable
internal fun DayPanelPart(part: ContentPart, imageLoader: suspend (String) -> ImageBitmap?) {
    when {
        part.src != null -> {
            // P2-1：组合期不解码；图到位前这条图片段不占位（与旧行为一致）。
            val bitmap by produceState<ImageBitmap?>(initialValue = null, part.src) {
                value = imageLoader(part.src)
            }
            val shown = bitmap
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        // 列表 / 待办：与阅读态共用 `MarkerRow`（同一套缩进、同一个 M3 勾选框），
        // 所以"预览里看到的"就是点进去以后看到的。
        !part.items.isNullOrEmpty() -> Column(
            verticalArrangement = Arrangement.spacedBy(BlockGeometry.ParagraphGap),
        ) {
            part.items.orEmpty().forEachIndexed { index, item ->
                val done = part.done?.getOrNull(index) == true
                MarkerRow(isTodo = part.style == ContentPartStyle.todo, done = done) {
                    Text(
                        text = item,
                        style = EditorTypography.textStyleFor(ContentPartStyle.body),
                        color = if (done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                    )
                }
            }
        }

        // 引用：整列宽的圆角底 + 左侧竖条 + 文字内缩，与阅读态、编辑态同一套几何
        part.style == ContentPartStyle.quote -> QuotePreview(ContentFlatten.flattenPart(part))

        else -> Text(
            text = ContentFlatten.flattenPart(part),
            style = EditorTypography.textStyleFor(part.style),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (part.align == "center") TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 日面板预览里的引用：几何与阅读态、编辑态完全同源（[BlockGeometry]）。 */
@Composable
internal fun QuotePreview(text: String) {
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val bar = MaterialTheme.colorScheme.outlineVariant
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val radius = BlockGeometry.QuoteRadius.toPx()
                    drawRoundRect(color = background, cornerRadius = CornerRadius(radius))
                    val barWidth = BlockGeometry.QuoteBarWidth.toPx()
                    if (barWidth > 0f) {
                        val inset = BlockGeometry.QuoteBarInset.toPx()
                        drawRoundRect(
                            color = bar,
                            topLeft = Offset(BlockGeometry.QuoteBarLeading.toPx(), inset),
                            size = Size(barWidth, size.height - inset * 2),
                            cornerRadius = CornerRadius(BlockGeometry.QuoteBarRadius.toPx()),
                        )
                    }
                },
        )
        Text(
            text = text,
            modifier = Modifier.padding(
                start = BlockGeometry.QuoteTextInset,
                end = BlockGeometry.QuoteTrailingPadding,
                top = BlockGeometry.QuotePadding,
                bottom = BlockGeometry.QuotePadding,
            ),
            style = EditorTypography.textStyleFor(ContentPartStyle.quote),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 该日期的农历全称（干支年 + 闰月 + 日），如「丙午年八月初四」。 */
internal fun lunarLabelOf(date: LocalDate): String =
    Lunar.fullLabel(Lunar.ChineseCalendarProvider.lunarDateOf(date, java.time.ZoneId.systemDefault()))
