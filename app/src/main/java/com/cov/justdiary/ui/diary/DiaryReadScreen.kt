package com.cov.justdiary.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.service.SearchUtil
import com.cov.justdiary.ui.theme.EditorTypography
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing

/**
 * 日记阅读页。对应 iOS `Views/Diary/DiaryPageView.swift` + `ReadTextView.swift`。
 *
 * 与 iOS 的差别在于**渲染方式**：iOS 用一个 `UITextView` + `NSAttributedString` 承载整篇
 * （附件区用于待办与图片），Android 侧改为**逐段落渲染 Compose 组件** —— 这样待办勾选、
 * 图片点击、命中高亮都是普通组件行为，不需要文本附件与命中测试那套机制。
 * 段落的样式与行距仍走 [EditorTypography]（与编辑器共用同一套语义样式与比例）。
 *
 * @param blocks 这一天的片段（时间 + 地点 + 内容），按时间升序。
 * @param keyword 条目内搜索的关键词（空则不搜索）。
 * @param currentMatch 当前匹配序号（用于"上一个/下一个"定位）。
 * @param onToggleTodo 勾选/取消待办（`blockIndex, itemIndex`）。
 * @param onSearchChange 搜索关键词变化。
 * @param onSearchStep 上一个 / 下一个匹配（`-1` / `+1`）。
 * @param onShare 生成并分享长图。
 * @param imageLoader 内联图片的加载器（返回已降采样的位图）。
 */
@Composable
fun DiaryReadScreen(
    dayTitle: String,
    blocks: List<ReadBlock>,
    modifier: Modifier = Modifier,
    keyword: String = "",
    currentMatch: Int = -1,
    onSearchChange: (String) -> Unit = {},
    onSearchStep: (Int) -> Unit = {},
    onToggleTodo: (Int, Int) -> Unit = { _, _ -> },
    onShare: () -> Unit = {},
    imageLoader: (ContentPart) -> ImageBitmap? = { null },
) {
    val parts = remember(blocks) { blocks.flatMap { it.parts } }
    val matches = remember(parts, keyword) { DiarySearch.findMatches(parts, keyword) }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // 页头：日期 + 分享
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = ReadLayout.READING_MAX_WIDTH.dp),
            )
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = SHARE_LABEL)
            }
        }

        // 条目内搜索栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(SEARCH_PLACEHOLDER) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = CLEAR_LABEL)
                        }
                    }
                },
            )
            if (keyword.isNotEmpty()) {
                Text(
                    text = if (matches.isEmpty()) NO_MATCH_LABEL else "${currentMatch + 1}/${matches.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { onSearchStep(-1) }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = PREV_MATCH_LABEL)
                }
                IconButton(onClick = { onSearchStep(1) }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = NEXT_MATCH_LABEL)
                }
            }
        }

        // 片段列表
        blocks.forEachIndexed { blockIndex, block ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.page, vertical = Spacing.card),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = block.startLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                block.parts.forEachIndexed { partIndex, part ->
                    ReadPart(
                        part = part,
                        keyword = keyword,
                        onToggleTodo = { itemIndex -> onToggleTodo(blockIndex, itemIndex) },
                        imageLoader = imageLoader,
                    )
                }
            }
        }

        Box(modifier = Modifier.padding(bottom = 96.dp))
    }
}

/** 一天里的一个片段（与 iOS `ShareBlock` 同构，但多了渲染好的起始行文案）。 */
data class ReadBlock(
    val startTimeUtc: Long,
    val startLine: String,
    val parts: List<ContentPart>,
)

@Composable
private fun ReadPart(
    part: ContentPart,
    keyword: String,
    onToggleTodo: (Int) -> Unit,
    imageLoader: (ContentPart) -> ImageBitmap?,
) {
    when (val render = ReadLayout.renderModel(listOf(part)).firstOrNull()) {
        is ReadLayout.PartRender.Paragraph -> ParagraphText(render, keyword)

        is ReadLayout.PartRender.Items -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            render.items.forEachIndexed { index, item ->
                val done = render.done.getOrNull(index) == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (render.isTodo) it.clickable { onToggleTodo(index) } else it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (render.isTodo) {
                        TodoBox(done)
                    } else {
                        Text("•", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyLarge,
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

        is ReadLayout.PartRender.Image -> {
            val bitmap = imageLoader(part)
            if (bitmap != null) {
                val widthDp = ReadLayout.imageDisplayWidth(320.0).dp
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .width(widthDp)
                        .clip(RoundedCornerShape(Radius.image)),
                    contentScale = ContentScale.FillWidth,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(120.dp)
                        .clip(RoundedCornerShape(Radius.image))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = IMAGE_MISSING_LABEL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        null -> Unit
    }
}

@Composable
private fun ParagraphText(render: ReadLayout.PartRender.Paragraph, keyword: String) {
    val style = EditorTypography.designSizeForStyle(render.style)
    val textStyle = when (render.style) {
        "title" -> MaterialTheme.typography.displayMedium
        "heading" -> MaterialTheme.typography.headlineMedium
        "quote" -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodyLarge
    }
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
    val highlighted = highlightAnnotated(annotated, keyword)

    if (render.style == "quote") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.concentric(Radius.card, 4.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Text(
                text = highlighted,
                modifier = Modifier.padding(Spacing.card),
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = (style.value * (1f + EditorTypography.lineSpacingRatio("quote"))).sp,
            )
        }
    } else {
        Text(
            text = highlighted,
            modifier = Modifier.fillMaxWidth(),
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (render.alignCenter) TextAlign.Center else TextAlign.Start,
            lineHeight = (style.value * (1f + EditorTypography.lineSpacingRatio(render.style))).sp,
        )
    }
}

/** 命中高亮：把关键字段落背景设为 `primaryContainer`、前景设为 `primary`。 */
@Composable
private fun highlightAnnotated(annotated: AnnotatedString, keyword: String): AnnotatedString {
    if (keyword.isBlank()) return annotated
    val segments = SearchUtil.highlightSegments(annotated.text, keyword)
    if (segments.none { it.hit }) return annotated

    val background = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        segments.forEach { segment ->
            val start = cursor
            val end = (cursor + segment.text.length).coerceAtMost(annotated.length)
            cursor = end
            if (start < end) {
                if (segment.hit) {
                    withStyle(SpanStyle(background = background, color = foreground)) {
                        append(annotated.subSequence(start, end))
                    }
                } else {
                    append(annotated.subSequence(start, end))
                }
            }
        }
        if (cursor < annotated.length) append(annotated.subSequence(cursor, annotated.length))
    }
}

/**
 * 待办的勾选框。
 *
 * 未勾选 = 1dp 描边 + 透明底；勾选 = 品牌色实底 + 对勾。
 * （iOS 侧是渐变填充的圆角方框，这里按 Material 3 的语义重做，形状与状态含义保持一致。）
 */
@Composable
private fun TodoBox(done: Boolean) {
    val shape = RoundedCornerShape(Radius.badge)
    val color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(shape)
            .background(if (done) color else Color.Transparent)
            .border(1.dp, color, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

private const val SHARE_LABEL = "分享"
private const val SEARCH_PLACEHOLDER = "在当前日记中查找…"
private const val CLEAR_LABEL = "清除"
private const val PREV_MATCH_LABEL = "上一个匹配"
private const val NEXT_MATCH_LABEL = "下一个匹配"
private const val NO_MATCH_LABEL = "无匹配"
private const val IMAGE_MISSING_LABEL = "图片"
