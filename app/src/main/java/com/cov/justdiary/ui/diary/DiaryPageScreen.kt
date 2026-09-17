package com.cov.justdiary.ui.diary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cov.justdiary.JustDiaryApplication
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.LocationRules
import com.cov.justdiary.service.ShareIntents
import androidx.compose.ui.graphics.asImageBitmap
import com.cov.justdiary.ui.theme.isDarkTheme

/**
 * 日记页宿主：**一个页面、两种形态**。对应 iOS `Views/Diary/DiaryPageView.swift`。
 *
 * 阅读态渲染 [DiaryReadScreen]，编辑态渲染 [EditorScreen]；两者的状态都来自同一个
 * [DiaryPageViewModel]，因此"阅读 → 点片段 → 编辑 → 保存 → 回到阅读"是一条链，
 * 不存在两个页面互相推送的栈（与 iOS 的 `isRead` 切换一致）。
 *
 * 与 iOS 的差异（平台事实）：
 * - 分享长图用 `Intent.ACTION_SEND`（`ShareIntents`），iOS 用 `ShareLink`；
 * - 返回键走 `BackHandler`（系统返回手势），iOS 用悬浮返回按钮 + `dismiss`。
 */
@Composable
fun DiaryPageScreen(
    dayKey: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDataChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as JustDiaryApplication).container
    }
    // 每天一个实例：换一天不会复用上一天的编辑状态
    val viewModel: DiaryPageViewModel = viewModel(
        key = "diary:${dayKey.orEmpty()}",
        factory = DiaryPageViewModel.factory(container),
    )
    val state by viewModel.state.collectAsState()
    // 编辑器有没有未保存内容 —— 由编辑器上报，返回键据此决定是否二次确认
    var editorDirty by remember { mutableStateOf(false) }

    LaunchedEffect(dayKey) { viewModel.open(dayKey) }
    LaunchedEffect(state.requestDismiss) {
        if (state.requestDismiss) {
            viewModel.consumeDismiss()
            onDataChanged()
            onClose()
        }
    }
    // 分享图渲染完成后交给系统分享面板
    LaunchedEffect(state.shareImageFile) {
        val file = state.shareImageFile ?: return@LaunchedEffect
        runCatching {
            context.startActivity(
                ShareIntents.shareImage(context, file, SHARE_CHOOSER_TITLE),
            )
        }
        viewModel.consumeShareImage()
    }

    androidx.activity.compose.BackHandler {
        viewModel.handleBack(editorHasContent = editorDirty)
    }

    val formats = remember(state.settings.appLanguage, state.dayKey) {
        DateFormats.forContext(context, state.settings.appLanguage)
    }
    // 分享图的深浅色跟随"当下界面"的取色规则（与 iOS 的 `UITraitCollection` 一致）
    val darkTheme = isDarkTheme(state.settings.themeMode)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // 全屏路由没有外层 Scaffold，系统栏避让要自己做（对应 iOS 的 safeArea）
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (state.isRead) {
                DiaryReadScreen(
                    dayTitle = formats.dayTitle(com.cov.justdiary.model.DateUtil.parseDayKey(state.actualDayKey) ?: java.time.LocalDate.now()),
                    blocks = state.blocks.map { block ->
                        ReadBlock(
                            startTimeUtc = block.startTimeUtc,
                            startLine = formats.startLine(
                                formats.timeOf(java.time.Instant.ofEpochMilli(block.startTimeUtc)),
                                block.locText,
                            ),
                            parts = ContentFlatten.parseContent(block.contentJson),
                        )
                    },
                    keyword = if (state.showSearch) state.searchText else "",
                    currentMatch = state.hitIndex,
                    onSearchChange = viewModel::onSearchTextChange,
                    onSearchStep = viewModel::stepHit,
                    onToggleTodo = { blockIndex, itemIndex ->
                        state.blocks.getOrNull(blockIndex)?.let { block ->
                            // `DiaryReadScreen` 给的是"这一天里的第几个片段 + 待办项下标"
                            viewModel.toggleTodo(block.id, partIndexOfFirstItems(block.contentJson), itemIndex)
                        }
                    },
                    onShare = {
                        viewModel.shareDiary(
                            isDark = darkTheme,
                            dateText = formats.dayTitle(
                                com.cov.justdiary.model.DateUtil.parseDayKey(state.actualDayKey) ?: java.time.LocalDate.now(),
                            ),
                            startLine = formats.startLine(
                                formats.timeOf(java.time.Instant.ofEpochMilli(state.startTimeUtc)),
                                state.locationText,
                            ),
                        )
                    },
                    imageLoader = { part ->
                        part.src?.let { src -> container.mediaStore.image(src)?.asImageBitmap() }
                    },
                )
            } else {
                EditorScreen(
                    dayTitle = formats.dayTitle(
                        com.cov.justdiary.model.DateUtil.parseDayKey(state.actualDayKey) ?: java.time.LocalDate.now(),
                    ),
                    initialParts = state.loadParts,
                    isNewBlock = !state.isEditingExistingBlock,
                    locText = state.locationText,
                    locPrecision = state.location?.locPrecision.orEmpty(),
                    locationState = editorLocationState(state),
                    autoTime = state.settings.autoTime,
                    startTimeUtc = state.startTimeUtc,
                    onRequestLocation = viewModel::beginLocate,
                    onPrecisionChange = viewModel::applyPrecision,
                    onSave = viewModel::save,
                    onDiscard = viewModel::close,
                    onImportImage = { uri ->
                        viewModel.importImage(uri)?.let { src ->
                            val bitmap = container.mediaStore.image(src)
                            ImportedImage(
                                src = src,
                                width = bitmap?.width?.toDouble(),
                                height = bitmap?.height?.toDouble(),
                            )
                        }
                    },
                    loadImage = { src -> container.mediaStore.image(src) },
                    onDirtyChange = { editorDirty = it },
                )
            }
        }
    }

    state.alert?.let { alert ->
        DiaryAlertDialog(
            alert = alert,
            state = state,
            viewModel = viewModel,
            onClose = {
                viewModel.consumeAlert()
                onDataChanged()
                onClose()
            },
        )
    }
}

/**
 * 编辑器里"位置行"的状态。
 *
 * 规则与 iOS 一致（`LocationRules`）：编辑已有片段只读、**不提供重新获取入口**；
 * 新建片段才可能处于"获取中 / 获取失败"。
 */
private fun editorLocationState(state: DiaryPageViewModel.State): LocationRules.EditorLocationState {
    val snapshot = state.location
    return when {
        snapshot != null && snapshot.hasLocation -> LocationRules.EditorLocationState.Recorded(snapshot)
        state.isEditingExistingBlock -> LocationRules.EditorLocationState.NotRecorded
        !state.settings.autoLoc -> LocationRules.EditorLocationState.Hidden
        state.locating -> LocationRules.EditorLocationState.Locating
        else -> LocationRules.EditorLocationState.LocateFailed
    }
}

/**
 * 找出 `content_json` 里第一个"列表/待办"段的下标。
 *
 * `DiaryReadScreen` 的 `onToggleTodo(blockIndex, itemIndex)` 只回了待办项下标，
 * 而写库需要段落下标，因此在这里定位。
 */
private fun partIndexOfFirstItems(contentJson: String): Int =
    ContentFlatten.parseContent(contentJson).indexOfFirst { !it.items.isNullOrEmpty() }.coerceAtLeast(0)

@Composable
private fun DiaryAlertDialog(
    alert: DiaryPageViewModel.DiaryAlert,
    state: DiaryPageViewModel.State,
    viewModel: DiaryPageViewModel,
    onClose: () -> Unit,
) {
    val title: String
    val message: String
    var confirmLabel: String? = null
    var onConfirm: (() -> Unit)? = null

    when (alert) {
        DiaryPageViewModel.DiaryAlert.HistoryNoAdd -> {
            title = HISTORY_NO_ADD_TITLE
            message = HISTORY_NO_ADD_MESSAGE
        }

        DiaryPageViewModel.DiaryAlert.ReadOnly -> {
            title = READONLY_TITLE
            message = READONLY_MESSAGE
        }

        DiaryPageViewModel.DiaryAlert.ExitConfirm -> {
            title = EXIT_TITLE
            message = EXIT_MESSAGE
            confirmLabel = DISCARD_LABEL
            onConfirm = onClose
        }

        DiaryPageViewModel.DiaryAlert.DiscardConfirm -> {
            title = DISCARD_TITLE
            message = DISCARD_MESSAGE
            confirmLabel = DISCARD_LABEL
            onConfirm = viewModel::performDiscardEditing
        }

        is DiaryPageViewModel.DiaryAlert.CrossDay -> {
            title = CROSS_DAY_TITLE
            message = CROSS_DAY_MESSAGE.format(alert.dayKey)
            confirmLabel = SAVE_EXIT_LABEL
            onConfirm = viewModel::confirmCrossDay
        }

        DiaryPageViewModel.DiaryAlert.SaveFailed -> {
            title = SAVE_FAILED_TITLE
            message = SAVE_FAILED_MESSAGE
        }

        is DiaryPageViewModel.DiaryAlert.DeleteConfirm -> {
            title = DELETE_TITLE
            message = DELETE_MESSAGE.format(alert.count)
            confirmLabel = DELETE_LABEL
            onConfirm = viewModel::performDeleteSelected
        }

        DiaryPageViewModel.DiaryAlert.ImageInsertFailed -> {
            title = IMAGE_FAILED_TITLE
            message = IMAGE_FAILED_MESSAGE
        }

        DiaryPageViewModel.DiaryAlert.ShareFailed -> {
            title = SHARE_FAILED_TITLE
            message = SHARE_FAILED_MESSAGE
        }
    }

    AlertDialog(
        onDismissRequest = viewModel::consumeAlert,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (confirmLabel != null && onConfirm != null) {
                TextButton(onClick = onConfirm) { Text(confirmLabel) }
            } else {
                TextButton(onClick = viewModel::consumeAlert) { Text(OK_LABEL) }
            }
        },
        dismissButton = if (confirmLabel != null) {
            { TextButton(onClick = viewModel::consumeAlert) { Text(CANCEL_LABEL) } }
        } else {
            null
        },
    )
}

private const val SHARE_CHOOSER_TITLE = "分享日记"

private const val OK_LABEL = "好"
private const val CANCEL_LABEL = "取消"
private const val DISCARD_LABEL = "放弃"
private const val DELETE_LABEL = "删除"
private const val SAVE_EXIT_LABEL = "保存并退出"

private const val HISTORY_NO_ADD_TITLE = "无法新增编辑块"
private const val HISTORY_NO_ADD_MESSAGE = "历史日记只能修改原有编辑块，不能新增内容，以免时间线错乱。"
private const val READONLY_TITLE = "只读内容"
private const val READONLY_MESSAGE = "开启「允许补写 / 修改历史日记」后，可修改已有内容。"
private const val EXIT_TITLE = "退出编辑"
private const val EXIT_MESSAGE = "内容尚未保存，退出前需要处理。"
private const val DISCARD_TITLE = "放弃修改？"
private const val DISCARD_MESSAGE = "未保存的内容将丢失，且无法恢复。"
private const val CROSS_DAY_TITLE = "跨日编辑提示"
private const val CROSS_DAY_MESSAGE = "本次内容将保存为 %s 的日记。保存并退出后，这篇日记将归档。"
private const val SAVE_FAILED_TITLE = "保存失败"
private const val SAVE_FAILED_MESSAGE = "日记保存失败，请稍后重试。"
private const val DELETE_TITLE = "删除编辑块"
private const val DELETE_MESSAGE = "将删除选中的 %d 个编辑块，删除后不可恢复。"
private const val IMAGE_FAILED_TITLE = "插入图片失败"
private const val IMAGE_FAILED_MESSAGE = "无法读取这张图片，请换一张重试。"
private const val SHARE_FAILED_TITLE = "分享失败"
private const val SHARE_FAILED_MESSAGE = "日记图片生成失败，请稍后重试。"
