package com.cov.justdiary.ui.diary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cov.justdiary.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.service.LocationRules
import com.cov.justdiary.ui.theme.Spacing

/**
 * 编辑页。
 *
 * 对应 iOS `Views/Diary/DiaryPageView.swift` 的编辑态。已实现：
 *
 * - **放弃修改**：进入编辑时的快照 + 二次确认（与 iOS 同样"回到进入编辑时的内容"，
 *   而不是撤销栈 —— 撤销栈是额外的能力，见 [EditorHistory]）；
 * - **保存**：`linesToParts` 落库；新建片段还没有位置时**二次确认**，
 *   确认后按无位置保存，此后不能再补（[LocationRules]）；
 * - **位置规则**：只在新建时获取一次；编辑已有片段只显示已记录位置并允许调精度；
 *   无位置的历史片段显示只读的"未记录地点"，不给获取入口；
 * - **自动时间**：新建片段时写入开始时间（让 `auto_time` 设置真正生效 —— iOS 侧是死设置）；
 * - **插入图片**：Photo Picker → 由调用方导入到应用目录后回传 `src`，
 *   作为独立一行插入（落库为 `image` 段落）。
 *
 * @param onSave 交给调用方落库（`parts` 已归一为 `content_json` 的段落）。
 * @param onImportImage 用系统的 Photo Picker 选一张图；返回 `images/xxx.jpg` 键，取消或失败返回 null。
 */
@Composable
fun EditorScreen(
    dayTitle: String,
    initialParts: List<ContentPart>,
    isNewBlock: Boolean,
    locText: String,
    locPrecision: String,
    locationState: LocationRules.EditorLocationState,
    modifier: Modifier = Modifier,
    /**
     * `auto_time` 设置：控制**是否显示**这条记录的开始时间。
     *
     * 关闭时不显示时间胶囊（阅读页 / 首页面板 / 分享长图也一并不显示），
     * 但 `start_time_utc` **照旧记录** —— `day_key` / `created_utc` / 排序都依赖它，
     * 这是跨端契约，不允许写成 0。与 iOS 的 `vm.showsTime` 同一语义。
     */
    autoTime: Boolean = true,
    startTimeUtc: Long = System.currentTimeMillis(),
    /** 时间胶囊的文案（由宿主用 `DateFormats` 备好，跟随语言与 12/24 小时制）。 */
    startTimeLabel: String = "",
    onRequestLocation: () -> Unit = {},
    onPrecisionChange: (String) -> Unit = {},
    onSave: (EditorSavePayload) -> Unit = {},
    onDiscard: () -> Unit = {},
    onImportImage: suspend (android.net.Uri) -> ImportedImage? = { null },
    /** 图片行的位图加载器（由调用方接 `MediaStore`）。 */
    loadImage: (String) -> android.graphics.Bitmap? = { null },
    /**
     * 是否有未保存内容（宿主用它决定返回键是否要二次确认）。
     *
     * iOS 的 `DiaryViewModel.handleBack` 直接问 `controller.isEmpty()`；Android 侧编辑内容
     * 是纯模型，宿主看不到，因此由编辑器**上报**（内容或历史与进入时不同即为脏）。
     */
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val initialLines = remember(initialParts) { EditorModel.partsToLines(initialParts) }
    val state = rememberEditorTextState(initialLines)
    val history = remember { EditorHistory(initial = initialLines) }
    val dirty = hasChanges(state, initialLines, history)
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showLocationConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 图片选择：Photo Picker（无需存储权限）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // 调用方用 MediaStore 把图片导入应用目录（最长边 2048 / JPEG 85）
                val imported = onImportImage(uri) ?: return@launch
                state.apply { lines ->
                    EditorModel.insertImageLine(
                        lines,
                        index = state.selection.lineIndex,
                        src = imported.src,
                        width = imported.width,
                        height = imported.height,
                    )
                }
            }
        }
    }

    val currentLine = state.currentLine
    val inlineRange = state.inlineRange
    val flags = EditorModel.activeFlags(currentLine, inlineRange.first, inlineRange.second)

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // 顶栏：返回 / 标题 / 放弃 / 保存
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (hasChanges(state, initialLines, history)) showDiscardConfirm else onDiscard() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = BACK_LABEL)
            }
            Text(
                text = dayTitle,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { if (hasChanges(state, initialLines, history)) showDiscardConfirm else onDiscard() }) {
                Text(DISCARD_LABEL)
            }
            TextButton(
                onClick = {
                    if (LocationRules.needsSaveConfirmation(
                            isNewBlock = isNewBlock,
                            autoLoc = locationState !is LocationRules.EditorLocationState.Hidden,
                            hasLocation = locationState is LocationRules.EditorLocationState.Recorded,
                        )
                    ) {
                        showLocationConfirm = true
                    } else {
                        onSave(buildPayload(state.lines, locationState, startTimeUtc))
                    }
                },
            ) {
                Text(SAVE_LABEL)
            }
        }

        // 时间胶囊（规范 §7.3）：`auto_time` 关闭时不显示。
        // iOS 侧的编辑器也是"打开即记录并显示开始时间"，这里与它对齐。
        if (autoTime && startTimeLabel.isNotEmpty()) {
            Text(
                text = startTimeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = Spacing.page, vertical = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        // 位置行
        LocationRow(
            state = locationState,
            isNewBlock = isNewBlock,
            onRequestLocation = onRequestLocation,
            onPrecisionChange = onPrecisionChange,
        )

        // 正文
        EditorTextArea(
            state = state,
            placeholder = PLACEHOLDER,
            imageProvider = loadImage,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
        )

        // 格式栏：IME 组合期间禁用（FormatGate）
        FontToolbar(
            activeBlock = EditorBlockStyle.fromPartStyle(currentLine.blockStyle),
            flags = flags,
            isQuote = currentLine.blockStyle == com.cov.justdiary.model.ContentPartStyle.quote,
            isList = currentLine.isListItem,
            isTodo = currentLine.isTodoItem,
            // 居中与列表/引用/待办互斥（方案 §7.4），它们不会同时为真：
            // 开启标记时 EditorModel 会清掉 align，因此这里的值总是当前段落的真实状态。
            isCentered = currentLine.align == EditorModel.ALIGN_CENTER,
            enabled = !state.composing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = 8.dp),
            onBlockStyle = { style -> state.apply { EditorModel.setBlockStyle(it, state.selection.lineIndex..state.selection.lineIndex, style.partStyle) } },
            onToggleQuote = { state.apply { EditorModel.toggleQuote(it, state.selection.lineIndex..state.selection.lineIndex) } },
            onToggleList = { state.apply { EditorModel.toggleMarker(it, state.selection.lineIndex, EditorLine.MarkerKind.Bullet) } },
            onToggleTodo = { state.apply { EditorModel.toggleMarker(it, state.selection.lineIndex, EditorLine.MarkerKind.Todo) } },
            // 居中作用于当前行：`setAlignment` 落库为 `content_json` 的 `align`（只写 center）。
            onToggleAlign = {
                state.apply { lines ->
                    val index = state.selection.lineIndex
                    EditorModel.setAlignment(
                        lines,
                        index..index,
                        center = lines.getOrNull(index)?.align != EditorModel.ALIGN_CENTER,
                    )
                }
            },
            onToggleInline = { flag ->
                val (from, to) = state.inlineRange
                state.apply { lines ->
                    lines.mapIndexed { index, line ->
                        if (index == state.selection.lineIndex) EditorModel.toggleInline(line, from, to, flag) else line
                    }
                }
            },
            onInsertImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }

    // 放弃修改的二次确认
    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(DISCARD_TITLE) },
            text = { Text(DISCARD_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDiscard()
                }) { Text(DISCARD_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text(CANCEL_LABEL) }
            },
        )
    }

    // 保存时还没有位置的二次确认
    if (showLocationConfirm) {
        AlertDialog(
            onDismissRequest = { showLocationConfirm = false },
            title = { Text(LOCATION_TITLE) },
            text = { Text(LOCATION_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    showLocationConfirm = false
                    onSave(buildPayload(state.lines, locationState, startTimeUtc))
                }) { Text(SAVE_ANYWAY_LABEL) }
            },
            dismissButton = {
                TextButton(onClick = { showLocationConfirm = false }) { Text(CANCEL_LABEL) }
            },
        )
    }
}

/** 一张已导入应用目录的图片（`MediaStore.importImage` 的结果）。 */
data class ImportedImage(val src: String, val width: Double?, val height: Double?)

/** 保存载荷：调用方据此调用 `addBlock` / `updateBlockContent`。 */
data class EditorSavePayload(
    val parts: List<ContentPart>,
    /**
     * 新建片段时写库用（无位置时为空串）；编辑时由调用方改走
     * `updateBlockContent(..., locText, locPrecision)` 只改这两列。
     */
    val locText: String?,
    val locPrecision: String?,
    val startTimeUtc: Long,
)

@Composable
private fun LocationRow(
    state: LocationRules.EditorLocationState,
    isNewBlock: Boolean,
    onRequestLocation: () -> Unit,
    onPrecisionChange: (String) -> Unit,
) {
    if (state is LocationRules.EditorLocationState.Hidden) return

    // 规范 §7.3 / 缺陷 D10：左侧只显示**状态**，动作只放右侧，两者不许同文案。
    // "获取失败"与"历史片段没有位置"都是同一种状态（未记录地点）——
    // 能不能重试由 isNewBlock 决定，不由文案暗示。
    val status = when (state) {
        is LocationRules.EditorLocationState.Locating -> LOCATING_LABEL
        is LocationRules.EditorLocationState.LocateFailed -> NOT_RECORDED_LABEL
        is LocationRules.EditorLocationState.NotRecorded -> NOT_RECORDED_LABEL
        is LocationRules.EditorLocationState.Recorded -> state.snapshot.locText
        LocationRules.EditorLocationState.Hidden -> ""
    }
    // 定位中是"进行中"的状态，用 primary 强调；其余状态用 onSurfaceVariant（方案 §7.3）。
    val statusColor = if (state is LocationRules.EditorLocationState.Locating) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val canRefetch = LocationRules.canRefetchLocation(isNewBlock, state)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = status,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
        )
        if (canRefetch) {
            AssistChip(onClick = onRequestLocation, label = { Text(REFETCH_LABEL) })
        }
        if (state is LocationRules.EditorLocationState.Recorded) {
            // 已有位置：只允许调整显示精度（精度只改 loc_text / loc_precision）
            val options = LocationRules.precisionOptions(state.snapshot)
            Text(
                text = precisionLabel(state.snapshot.locPrecision),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(end = 4.dp),
            )
            options.firstOrNull { it != state.snapshot.locPrecision }?.let { next ->
                TextButton(onClick = { onPrecisionChange(next) }) { Text(PRECISION_LABEL) }
            }
        }
    }
}

@Composable
private fun precisionLabel(precision: String): String = when (precision) {
    LocPrecision.exact -> stringResource(R.string.loc_precision_exact)
    LocPrecision.street -> stringResource(R.string.loc_precision_street)
    LocPrecision.district -> stringResource(R.string.loc_precision_district)
    LocPrecision.city -> stringResource(R.string.loc_precision_city)
    LocPrecision.province -> stringResource(R.string.loc_precision_province)
    else -> stringResource(R.string.loc_precision_none)
}

/** 与进入编辑时的快照比较，判断是否有改动（决定"放弃修改"是否需要确认）。 */
private fun hasChanges(
    state: EditorTextState,
    initialLines: List<EditorLine>,
    history: EditorHistory,
): Boolean = state.lines != initialLines || history.canUndo

private fun buildPayload(
    lines: List<EditorLine>,
    locationState: LocationRules.EditorLocationState,
    startTimeUtc: Long,
): EditorSavePayload {
    val recorded = locationState as? LocationRules.EditorLocationState.Recorded
    return EditorSavePayload(
        parts = EditorModel.linesToParts(lines),
        // 编辑态不写位置（精度调整由 onPrecisionChange 单独走 updateBlockContent）
        locText = recorded?.snapshot?.locText,
        locPrecision = recorded?.snapshot?.locPrecision,
        // 开始时间**永远**记录编辑器打开的时刻，与 `auto_time` 无关：
        // `day_key` / `created_utc` / 排序都依赖它，写 0 或改写都会破坏跨端契约。
        // `auto_time` 只决定"要不要把它显示出来"（见 EditorScreen 的注释与方案 §7.3）。
        startTimeUtc = startTimeUtc,
    )
}

private val BACK_LABEL: String
    @Composable get() = stringResource(R.string.a11y_back)
private val DISCARD_LABEL: String
    @Composable get() = stringResource(R.string.editor_discard)
private val SAVE_LABEL: String
    @Composable get() = stringResource(R.string.save)
private val CANCEL_LABEL: String
    @Composable get() = stringResource(R.string.cancel)
private val DISCARD_TITLE: String
    @Composable get() = stringResource(R.string.editor_discard_title)
private val DISCARD_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_discard_revert_msg)
private val DISCARD_CONFIRM: String
    @Composable get() = stringResource(R.string.discard)
private val LOCATION_TITLE: String
    @Composable get() = stringResource(R.string.editor_location_retry)
private val LOCATION_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_location_missing_msg)
private val SAVE_ANYWAY_LABEL: String
    @Composable get() = stringResource(R.string.editor_save_anyway)
private val LOCATING_LABEL: String
    @Composable get() = stringResource(R.string.editor_location_fetching)
private val REFETCH_LABEL: String
    @Composable get() = stringResource(R.string.editor_location_retry_action)
private val NOT_RECORDED_LABEL: String
    @Composable get() = stringResource(R.string.search_loc_no_loc)
private val PRECISION_LABEL: String
    @Composable get() = stringResource(R.string.editor_location_precision)
private val PLACEHOLDER: String
    @Composable get() = stringResource(R.string.editor_placeholder_new)
