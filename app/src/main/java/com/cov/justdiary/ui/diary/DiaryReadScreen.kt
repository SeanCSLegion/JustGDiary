package com.cov.justdiary.ui.diary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.components.AppIcons
import com.cov.justdiary.ui.components.DiaryCard
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.EditorTypography
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing
import com.cov.justdiary.ui.theme.TypeSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 日记阅读页（只有内容区 —— 顶栏在 [DiaryTopBar]，由 `DiaryPageScreen` 的 `Scaffold` 提供）。
 * 对应 iOS `Views/Diary/DiaryPageView.swift` 的阅读态 + `ReadTextView` / `MediaViews`。
 *
 * ## 结构（规范 `docs/android-design/04-diary.md` §2）
 *
 * ```
 * LazyColumn                        ← 命中定位需要 item 概念（iOS 是 ScrollViewReader）
 *  ├─ [showSearch] 页内搜索栏 52dp   ← 在 Hero **上方**
 *  ├─ Hero 卡（常驻，空的一天也在）
 *  └─ 条目卡 × N（点→编辑；长按 400ms→多选）
 * ```
 *
 * 正文列宽 `min(660, 宽 − 32)` 居中，**阅读 = 编辑**（同一公式，进出编辑卡文字不左右跳）。
 *
 * ## 与 iOS 的差别在于渲染方式
 *
 * iOS 用一个大 `UITextView` + `NSAttributedString` 承载整篇（待办与图片靠附件 + 命中测试），
 * Android 侧改为**逐段落渲染 Compose 组件**（[ReadLayout.PartRender] 的三种形态）：
 * 待办勾选、图片点击、命中高亮都成了普通组件行为，不需要附件与命中测试那套机制。
 * 段落的语义样式与字号仍走 [EditorTypography]（与编辑器共用同一套映射）。
 *
 * @param blocks 这一天的片段（时间 + 地点 + 内容），按时间升序。
 * @param keyword 页内搜索关键词（空则不搜索）。计数与步进键在悬浮的 [DiarySearchBarOverlay] 上。
 * @param scrollTargetBlockId 需要滚动到的片段 id（由"上一个/下一个"设置）。
 * @param contentPadding `Scaffold` 给的顶栏 / 系统栏让位。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DiaryReadScreen(
    dayTitle: String,
    blocks: List<ReadBlock>,
    modifier: Modifier = Modifier,
    isToday: Boolean = false,
    showSearch: Boolean = false,
    keyword: String = "",
    /** 当前命中所在的片段 id（与 [currentHit] 一起决定"哪一处是当前命中"）。 */
    currentHitBlockId: Long? = null,
    /** 当前命中（`当前序号` 指向的那一处）：它用更强的高亮，和其余命中区分开。 */
    currentHit: DiarySearch.Match? = null,
    onToggleTodo: (Int, Int) -> Unit = { _, _ -> },
    onEditBlock: (Int) -> Unit = {},
    onLongPressBlock: (Int) -> Unit = {},
    selectMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (Int) -> Unit = {},
    imageLoader: (ContentPart) -> ImageBitmap? = { null },
    scrollTargetBlockId: Long? = null,
    onScrollTargetConsumed: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
) {
    // 正文列宽：一条公式、一处读取（`AdaptiveLayout.contentColumn`）。
    val columnWidth: Dp = LocalAdaptiveLayout.current
        .contentColumn(ReadLayout.CONTENT_MAX_WIDTH.toFloat()).dp
    val listState = rememberLazyListState()

    // 命中定位：滚到命中的那张卡（iOS 是 `scrollTo(anchor: .center)`；
    // LazyColumn 没有 anchor 参数，用"视口 1/3 处"近似居中）
    LaunchedEffect(scrollTargetBlockId) {
        val target = scrollTargetBlockId ?: return@LaunchedEffect
        val index = blocks.indexOfFirst { it.id == target }
        if (index >= 0) {
            val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            listState.animateScrollToItem(
                index = DiaryScroll.itemIndexForBlock(index),
                // 负偏移 = 让目标停在视口偏上 1/3 处（近似 iOS 的 `anchor: .center`）
                scrollOffset = -viewport / 3,
            )
        }
        onScrollTargetConsumed()
    }

    // 搜索栏**悬浮在内容之上**（宿主绘制，不进列表）。
    //
    // 规范 §2.7 说"插在 Hero 卡上方"，那是按 iOS 的 ScrollView 直译的：iOS 里它跟着内容滚，
    // 于是点「下一个匹配」跳到命中处以后，搜索栏连同计数与步进键一起被顶出屏幕 ——
    // 用户看不到"第几个/共几个"，也没法再点下一个，搜索就失去了意义（用户实测反馈）。
    // 改为钉在顶栏下方：列表在它下面滚动，计数与步进键始终在手边。

    // 点正文 / 卡片之间的空白收键盘（iOS R14）。子组件（卡片、待办）自己消费点击，
    // 这里只接管"没落到任何可点区域"的那些点击。
    val focusManager = LocalFocusManager.current
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            // 页内搜索会拉起键盘：内容区要让开 IME，否则最后一张卡被键盘压住滚不上来
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures { focusManager.clearFocus() }
            },
        contentPadding = PaddingValues(
            // 顶部要让开悬浮搜索栏，否则第一张卡被压在它下面
            top = 10.dp + if (showSearch) SearchBarMetrics.TotalHeight else 0.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.card),
    ) {
        item(key = HERO_KEY) {
            DiaryColumn(columnWidth) { DiaryHeroCard(dayTitle, isToday, blocks) }
        }
        itemsIndexed(blocks, key = { _, block -> block.id }) { index, block ->
            DiaryColumn(columnWidth) {
                DiaryBlockCard(
                    block = block,
                    keyword = keyword,
                    selectMode = selectMode,
                    selected = block.id in selectedIds,
                    onEdit = { onEditBlock(index) },
                    onLongPress = { onLongPressBlock(index) },
                    onToggleSelect = { onToggleSelect(index) },
                    onToggleTodo = { itemIndex -> onToggleTodo(index, itemIndex) },
                    imageLoader = imageLoader,
                    // 只有"当前命中所在的那张卡"才把某一处画成当前命中
                    currentHit = currentHit.takeIf { block.id == currentHitBlockId },
                )
            }
        }
    }
}

/** 一天里的一个片段（阅读渲染的输入）。 */
data class ReadBlock(
    /** 片段 id（多选与待办回写要用）。 */
    val id: Long,
    val startTimeUtc: Long,
    /** 时间文案；`auto_time` 关闭时为空串（**只是不显示**，`start_time_utc` 照常入库）。 */
    val timeLabel: String,
    /** 地点文案（空 = 没记录地点）。 */
    val locText: String,
    val parts: List<ContentPart>,
)

/** 把一项内容放进居中的正文列（`min(660, 宽 − 32)`）。阅读页与编辑页共用同一条公式。 */
@Composable
internal fun DiaryColumn(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.width(width), content = content)
    }
}

// ---------------------------------------------------------------------------- Hero 卡

/**
 * Hero 卡（规范 §2.3）：**常驻**，哪怕是空的一天。
 *
 * 空的一天**没有独立空态视图** —— 元信息行换成「这一天没有留下日记。」，
 * 这样"这一天有没有内容"和"这一天是哪一天"永远在同一张卡里回答（缺陷 D8 的修法）。
 */
@Composable
private fun DiaryHeroCard(dayTitle: String, isToday: Boolean, blocks: List<ReadBlock>) {
    val fontScale = LocalDensity.current.fontScale
    val meta = DynamicTypeMetrics.effectiveSp(TypeSize.meta, fontScale)
    DiaryCard(
        // Hero 卡也是**一条语义**：TalkBack 读作
        // 「9月15日 周二，今日，9:41，2 个片段，东山区 · 京都府 · 日本」（规范 §5）
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = dayTitle,
                    fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.cardTitle, fontScale),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isToday) TodayPill(fontScale)
            }
            val first = blocks.firstOrNull()
            if (first == null) {
                Text(
                    text = stringResource(R.string.index_day_empty),
                    fontSize = meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (first.timeLabel.isNotEmpty()) {
                        Text(
                            text = first.timeLabel,
                            fontSize = meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.read_hero_segments, blocks.size),
                        fontSize = meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (first.locText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Place,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = first.locText,
                            fontSize = meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 「今日」胶囊（12sp medium、`primary` 底、`onPrimary` 字）—— 只在当天出现。 */
@Composable
private fun TodayPill(fontScale: Float) {
    Text(
        text = stringResource(R.string.index_today),
        fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.caption, fontScale),
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ---------------------------------------------------------------------------- 条目卡

/**
 * 条目卡（规范 §2.4）：12dp 内边距 · 16dp 圆角 · `surfaceContainerLow` · 无阴影。
 *
 * 点整卡（含正文）→ 编辑；长按 → 多选（`combinedClickable` 的长按阈值是系统的 400ms，
 * 与 iOS 的 `minimumDuration: 0.4` 同值）。
 */
@Composable
private fun DiaryBlockCard(
    block: ReadBlock,
    keyword: String,
    selectMode: Boolean,
    selected: Boolean,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onToggleTodo: (Int) -> Unit,
    imageLoader: (ContentPart) -> ImageBitmap?,
    currentHit: DiarySearch.Match?,
) {
    val fontScale = LocalDensity.current.fontScale
    // 命中与高亮用的是同一份结果（`DiarySearch.findMatches`）—— 规范 §2.7 的"一套算法"。
    val matches = remember(block.parts, keyword) { DiarySearch.findMatches(block.parts, keyword) }
    val editLabel = stringResource(R.string.read_action_edit)
    val deleteLabel = stringResource(R.string.delete)

    DiaryCard(
        onClick = { if (selectMode) onToggleSelect() else onEdit() },
        onLongClick = onLongPress,
        // 选中的卡加 2dp `primary` 描边（规范 §2.8）
        border = if (selectMode && selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier.semantics(mergeDescendants = true) {
            this.selected = selectMode && selected
            // 整卡一条语义 + 「编辑 / 删除」两个自定义动作（规范 §5）
            customActions = listOf(
                CustomAccessibilityAction(editLabel) { onEdit(); true },
                CustomAccessibilityAction(deleteLabel) { onToggleSelect(); true },
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectMode) SelectCircle(selected)
            Column(verticalArrangement = Arrangement.spacedBy(BlockGeometry.ParagraphGap)) {
                // 抬头：时间（13sp bold primary）+ 地点（12sp onSurfaceVariant，最多 2 行）
                if (block.timeLabel.isNotEmpty()) {
                    Text(
                        text = block.timeLabel,
                        fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.meta, fontScale),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (block.locText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Place,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = block.locText,
                            fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.caption, fontScale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 正文：逐段落渲染
                block.parts.forEachIndexed { partIndex, part ->
                    ReadPart(
                        part = part,
                        partIndex = partIndex,
                        matches = matches,
                        currentHit = currentHit,
                        onToggleTodo = onToggleTodo,
                        imageLoader = imageLoader,
                    )
                }
            }
        }
    }
}

/** 多选态的勾选圈（20dp）：未选中 = 描边圆，选中 = `primary` 实底 + 对勾。 */
@Composable
private fun SelectCircle(selected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .padding(top = 1.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) primary else Color.Transparent)
            .border(1.5.dp, if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// ---------------------------------------------------------------------------- 段落

@Composable
private fun ReadPart(
    part: ContentPart,
    partIndex: Int,
    matches: List<DiarySearch.Match>,
    currentHit: DiarySearch.Match?,
    onToggleTodo: (Int) -> Unit,
    imageLoader: (ContentPart) -> ImageBitmap?,
) {
    when (val render = ReadLayout.renderModel(listOf(part)).firstOrNull()) {
        is ReadLayout.PartRender.Paragraph -> ParagraphText(
            render = render,
            highlights = DiarySearch.highlights(matches, partIndex, ITEM_WHOLE_PART, currentHit),
        )

        is ReadLayout.PartRender.Items -> Column(
            modifier = Modifier.fillMaxWidth(),
            // 列表项之间与段落之间**同一个距离**（iOS 那边组内更紧，但 Android 三个渲染面
            // 共用一份几何才谈得上一致；设计稿 `.li{margin:0 0 8px}` 也是 8dp）
            verticalArrangement = Arrangement.spacedBy(BlockGeometry.ParagraphGap),
        ) {
            render.items.forEachIndexed { index, item ->
                val done = render.done.getOrNull(index) == true
                if (render.isTodo) {
                    TodoRow(
                        text = item,
                        done = done,
                        highlights = DiarySearch.highlights(matches, partIndex, index, currentHit),
                        onToggle = { onToggleTodo(index) },
                    )
                } else {
                    BulletRow(
                        text = item,
                        highlights = DiarySearch.highlights(matches, partIndex, index, currentHit),
                    )
                }
            }
        }

        is ReadLayout.PartRender.Image -> InlineImage(part = part, imageLoader = imageLoader)

        null -> Unit
    }
}

/**
 * 段落文本：语义样式 → M3 角色（[EditorTypography] 是唯一映射点），
 * 行内样式叠加，命中区间着 `primaryContainer` 底 + `primary` 字。
 */
@Composable
private fun ParagraphText(
    render: ReadLayout.PartRender.Paragraph,
    highlights: List<DiarySearch.Highlight>,
) {
    val textStyle = EditorTypography.textStyleFor(render.style)
    val quoteBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val quoteBar = MaterialTheme.colorScheme.outlineVariant
    val annotated = buildAnnotatedString {
        render.spans.forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = when {
                        span.strike && span.underline -> TextDecoration.combine(
                            listOf(TextDecoration.LineThrough, TextDecoration.Underline),
                        )

                        span.strike -> TextDecoration.LineThrough
                        span.underline -> TextDecoration.Underline
                        else -> null
                    },
                ),
            ) { append(span.text) }
        }
    }
    val text = annotated.withHighlights(highlights)

    if (render.style == ContentPartStyle.quote) {
        // 引用：整列宽的圆角底 + 左侧竖条 + 文字内缩 16dp。
        // 几何全部来自 [BlockGeometry]，与编辑态的 `QuoteBackgroundSpan`、首页预览
        // 是同一套数（编辑态那边画不了的只有右边那 10dp，见 `QuoteStyle` 的说明）。
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val radius = BlockGeometry.QuoteRadius.toPx()
                        drawRoundRect(
                            color = quoteBackground,
                            cornerRadius = CornerRadius(radius),
                        )
                        val barWidth = BlockGeometry.QuoteBarWidth.toPx()
                        if (barWidth > 0f) {
                            val inset = BlockGeometry.QuoteBarInset.toPx()
                            drawRoundRect(
                                color = quoteBar,
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
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (render.alignCenter) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** 列表项：圆点在标记槽里居中，正文从 28dp 起（换行后的第二行也一样）。 */
@Composable
private fun BulletRow(text: String, highlights: List<DiarySearch.Highlight>) {
    MarkerRow(isTodo = false, done = false) {
        Text(
            text = AnnotatedString(text).withHighlights(highlights),
            style = EditorTypography.textStyleFor(ContentPartStyle.body),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 待办行：整行可点（iOS 的命中区是"标记到行尾"），
 * 勾选框只用**形状 + 颜色**表达状态（不靠颜色单独承载）。
 */
@Composable
private fun TodoRow(
    text: String,
    done: Boolean,
    highlights: List<DiarySearch.Highlight>,
    onToggle: () -> Unit,
) {
    MarkerRow(
        isTodo = true,
        done = done,
        modifier = Modifier.toggleable(value = done, role = Role.Checkbox) { onToggle() },
    ) {
        Text(
            text = AnnotatedString(text).withHighlights(highlights),
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

// ---------------------------------------------------------------------------- 图片

/**
 * 内联图片（规范 §2.6）：宽 = 正文列宽、圆角 12dp、上下各 10dp、**没有最大高度**。
 *
 * 文件缺失 / 还没解码完时按库里的 `w/h` 比例占位 —— 图到位后版面不跳。
 * 位图在 IO 线程解码（`MediaStore` 内有 LRU 缓存），避免首帧在主线程解码大图。
 */
@Composable
private fun InlineImage(part: ContentPart, imageLoader: (ContentPart) -> ImageBitmap?) {
    val src = part.src.orEmpty()
    val bitmap by produceState<ImageBitmap?>(initialValue = null, src) {
        value = withContext(Dispatchers.IO) { imageLoader(part) }
    }
    // `w`/`h` 只表达宽高比（跨端契约）：宽度由列宽决定。
    val ratio = remember(part.w, part.h) {
        val w = part.w ?: 0.0
        val h = part.h ?: 0.0
        if (w > 0 && h > 0) (w / h).toFloat() else (1.0 / ReadLayout.IMAGE_FALLBACK_RATIO).toFloat()
    }
    val label = stringResource(
        R.string.read_image_label,
        (part.w ?: 0.0).toInt(),
        (part.h ?: 0.0).toInt(),
    )
    val openLabel = stringResource(R.string.read_image_open)
    var preview by remember(src) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ReadLayout.IMAGE_SPACING.dp),
    ) {
        val box = Modifier
            .fillMaxWidth()
            .heightIn(min = ReadLayout.IMAGE_MIN_HEIGHT.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(Radius.image))
        val shown = bitmap
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = label,
                modifier = box.clickable(onClickLabel = openLabel) { preview = true },
                contentScale = ContentScale.Fit,
            )
        } else {
            // 缺失 / 加载中：保留槽位不重排（iOS 用 `Color.clear` 占位）
            Box(
                modifier = box.background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Image,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    val shown = bitmap
    if (preview && shown != null) {
        DiaryImagePreview(
            bitmap = shown,
            contentDescription = label,
            onDismiss = { preview = false },
        )
    }
}

// ---------------------------------------------------------------------------- 页内搜索栏

/**
 * 页内搜索栏（规范 §2.7）：高 52dp，插在 Hero 卡**上方**。
 *
 * `当前序号 / 总数` + 上一个 / 下一个步进；输入为空时只剩输入框。
 * 计数来自 `DiaryPageViewModel.hits`（[DiarySearch.findMatches] 的结果），
 * 与卡片上的高亮是同一份数据 —— 这正是 iOS 那两套算法刻意收敛掉的地方。
 */
@Composable
fun DiarySearchBar(
    keyword: String,
    hitOrdinal: Int,
    hitCount: Int,
    onSearchChange: (String) -> Unit,
    onSearchStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SearchBarMetrics.BarHeight),
        shape = RoundedCornerShape(Radius.field),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = keyword,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                if (keyword.isEmpty()) {
                    Text(
                        text = stringResource(R.string.read_search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (keyword.isNotEmpty()) {
                Text(
                    text = if (hitCount == 0) {
                        stringResource(R.string.read_search_no_match)
                    } else {
                        "$hitOrdinal/$hitCount"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { onSearchStep(-1) }, enabled = hitCount > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = PREV_MATCH_LABEL)
                }
                IconButton(onClick = { onSearchStep(1) }, enabled = hitCount > 0) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = NEXT_MATCH_LABEL)
                }
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = CLEAR_LABEL)
                }
            }
        }
    }
}

/**
 * 悬浮的页内搜索栏：钉在顶栏下方，**不随内容滚动**。
 *
 * 容器用页面底色铺满整宽（不是半透明），这样列表从它下面滚过去时不会在圆角周围露出来；
 * 一点点阴影表明它浮在内容之上（与格式栏同一套语言）。
 */
@Composable
fun DiarySearchBarOverlay(
    keyword: String,
    hitOrdinal: Int,
    hitCount: Int,
    onSearchChange: (String) -> Unit,
    onSearchStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columnWidth: Dp = LocalAdaptiveLayout.current
        .contentColumn(ReadLayout.CONTENT_MAX_WIDTH.toFloat()).dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SearchBarMetrics.PagePadding,
                    vertical = SearchBarMetrics.VerticalPadding,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.width(columnWidth)) {
                DiarySearchBar(
                    keyword = keyword,
                    hitOrdinal = hitOrdinal,
                    hitCount = hitCount,
                    onSearchChange = onSearchChange,
                    onSearchStep = onSearchStep,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- 工具

/**
 * 命中高亮的**两级**配色：
 *
 * | | 底色 | 字色 |
 * | --- | --- | --- |
 * | 当前命中（`当前序号` 指向的那一处） | `primary` | `onPrimary` |
 * | 其余命中 | `primaryContainer` | `primary` |
 *
 * 两级的原因：所有命中一个样式时，点「上一个 / 下一个」只能靠页面滚动猜"现在看的是哪一个"
 * （同一张卡里有多处命中时尤其看不出来）。当前命中用实底反转色，一眼能认出来。
 *
 * 区间来自 [DiarySearch.highlights]（与计数同一份 `Match`），因此**画出来的
 * 命中数就是数出来的命中数** —— 不会出现 iOS 那种"跨 chunk 只计数不高亮"。
 */
@Composable
private fun AnnotatedString.withHighlights(highlights: List<DiarySearch.Highlight>): AnnotatedString {
    if (highlights.isEmpty()) return this
    val currentBg = MaterialTheme.colorScheme.primary
    val currentFg = MaterialTheme.colorScheme.onPrimary
    val matchBg = MaterialTheme.colorScheme.primaryContainer
    val matchFg = MaterialTheme.colorScheme.primary
    return AnnotatedString.Builder(this).apply {
        highlights.forEach { highlight ->
            val start = highlight.range.first.coerceIn(0, length)
            val end = (highlight.range.last + 1).coerceIn(start, length)
            if (start < end) {
                addStyle(
                    SpanStyle(
                        background = if (highlight.current) currentBg else matchBg,
                        color = if (highlight.current) currentFg else matchFg,
                    ),
                    start,
                    end,
                )
            }
        }
    }.toAnnotatedString()
}

/** 段落里"整段"这个文本单元的下标（列表 / 待办用项下标，普通段落用 -1）。 */
private const val ITEM_WHOLE_PART = -1
private const val HERO_KEY = "diary-hero"

/** 页内搜索栏的尺寸（规范 §2.7：栏高 52dp）。 */
object SearchBarMetrics {
    val BarHeight = 52.dp
    val VerticalPadding = 8.dp
    val PagePadding = 16.dp

    /** 悬浮栏连上下留白占掉的高度 —— 列表顶部要按它让位。 */
    val TotalHeight = BarHeight + VerticalPadding * 2
}

private val PREV_MATCH_LABEL: String
    @Composable get() = stringResource(R.string.read_search_prev)
private val NEXT_MATCH_LABEL: String
    @Composable get() = stringResource(R.string.read_search_next)
private val CLEAR_LABEL: String
    @Composable get() = stringResource(R.string.search_time_clear)
