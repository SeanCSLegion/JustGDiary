package com.cov.justdiary.ui.diary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
    autoTime: Boolean = true,
    startTimeUtc: Long = System.currentTimeMillis(),
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
                Icon(Icons.Filled.ArrowBack, contentDescription = BACK_LABEL)
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
                        onSave(buildPayload(state.lines, locationState, autoTime, startTimeUtc))
                    }
                },
            ) {
                Text(SAVE_LABEL)
            }
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
            enabled = !state.composing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page, vertical = 8.dp),
            onBlockStyle = { style -> state.apply { EditorModel.setBlockStyle(it, state.selection.lineIndex..state.selection.lineIndex, style.partStyle) } },
            onToggleQuote = { state.apply { EditorModel.toggleQuote(it, state.selection.lineIndex..state.selection.lineIndex) } },
            onToggleList = { state.apply { EditorModel.toggleMarker(it, state.selection.lineIndex, EditorLine.MarkerKind.Bullet) } },
            onToggleTodo = { state.apply { EditorModel.toggleMarker(it, state.selection.lineIndex, EditorLine.MarkerKind.Todo) } },
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
                    onSave(buildPayload(state.lines, locationState, autoTime, startTimeUtc))
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

    val label = when (state) {
        is LocationRules.EditorLocationState.Locating -> LOCATING_LABEL
        is LocationRules.EditorLocationState.LocateFailed -> REFETCH_LABEL
        is LocationRules.EditorLocationState.NotRecorded -> NOT_RECORDED_LABEL
        is LocationRules.EditorLocationState.Recorded -> state.snapshot.locText
        LocationRules.EditorLocationState.Hidden -> ""
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
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    autoTime: Boolean,
    startTimeUtc: Long,
): EditorSavePayload {
    val recorded = locationState as? LocationRules.EditorLocationState.Recorded
    return EditorSavePayload(
        parts = EditorModel.linesToParts(lines),
        // 编辑态不写位置（精度调整由 onPrecisionChange 单独走 updateBlockContent）
        locText = recorded?.snapshot?.locText,
        locPrecision = recorded?.snapshot?.locPrecision,
        // 自动插入时间：让 auto_time 设置真正生效（iOS 侧是死设置，见方案 Q5）
        startTimeUtc = if (autoTime) startTimeUtc else System.currentTimeMillis(),
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
