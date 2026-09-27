package com.cov.justdiary.ui.diary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cov.justdiary.JustDiaryApplication
import com.cov.justdiary.R
import com.cov.justdiary.data.DiaryProgress
import com.cov.justdiary.data.DiaryTopBarState
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
    /**
     * 进入时直接开写（首页当日面板的「写日记 / 继续写日记」走这条）。
     *
     * iOS 的 `openEditor(dayKey)` 就是这样：面板按钮直接进编辑器，不是先进阅读页再点一次。
     * 只有当天可写时才生效（[DiaryPageViewModel.enterWrite] 自己会再判一次）。
     */
    startInWrite: Boolean = false,
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

    LaunchedEffect(dayKey) { viewModel.open(dayKey, startInWrite) }
    LaunchedEffect(state.requestDismiss) {
        if (state.requestDismiss) {
            viewModel.consumeDismiss()
            onDataChanged()
            onClose()
        }
    }
    // 分享图渲染完成后交给系统分享面板（标题先在这里取好：LaunchedEffect 里不是 composable 作用域）
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    LaunchedEffect(state.shareImageFile) {
        val file = state.shareImageFile ?: return@LaunchedEffect
        runCatching {
            context.startActivity(
                ShareIntents.shareImage(context, file, shareChooserTitle),
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
    // 「分享」：渲染这一天的长图（由 `LaunchedEffect(state.shareImageFile)` 交给系统面板）。
    // 分享面板（预览 + 保存到相册）是规范 §4 的另一块，不在阅读态这一轮。
    val shareNow: (DiaryPageViewModel.State) -> Unit = { current ->
        viewModel.shareDiary(
            isDark = darkTheme,
            dateText = formats.dayTitle(
                com.cov.justdiary.model.DateUtil.parseDayKey(current.actualDayKey)
                    ?: java.time.LocalDate.now(),
            ),
            startLine = formats.blockStartLine(
                showTime = current.settings.autoTime,
                time = formats.timeOf(java.time.Instant.ofEpochMilli(current.startTimeUtc)),
                locText = current.locationText,
            ),
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // 全屏路由没有外层 Scaffold，系统栏避让要自己做（对应 iOS 的 safeArea）
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (state.isRead || state.selectMode) {
                // 阅读 / 多选：顶栏由宿主提供，**钉在页面顶部不进滚动区**
                // （规范 §2.2；iOS 早期挂在 safeAreaInset 上，键盘一弹就被滚出屏幕）。
                // 编辑态仍由 EditorScreen 自己画顶栏 —— 它要用编辑器内部的脏标记与保存载荷。
                val topBarState = DiaryTopBarState.of(
                    isRead = state.isRead,
                    editingIndex = state.editingIndex,
                    selectMode = state.selectMode,
                    selectedIds = state.selectedIds,
                    canEditToday = state.canEditToday,
                    showSearch = state.showSearch,
                )
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        DiaryTopBar(
                            state = topBarState,
                            onBack = { viewModel.handleBack(editorHasContent = false) },
                            onToggleSearch = { viewModel.setSearchVisible(!state.showSearch) },
                            onWrite = viewModel::enterWrite,
                            onShare = { shareNow(state) },
                            onExitSelect = { viewModel.handleBack(editorHasContent = false) },
                            onDeleteSelected = viewModel::confirmDeleteSelected,
                        )
                    },
                ) { innerPadding ->
                  Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    DiaryReadScreen(
                        dayTitle = formats.dayTitle(
                            com.cov.justdiary.model.DateUtil.parseDayKey(state.actualDayKey)
                                ?: java.time.LocalDate.now(),
                        ),
                        isToday = state.canEditToday,
                        blocks = state.blocks.map { block ->
                            ReadBlock(
                                id = block.id,
                                startTimeUtc = block.startTimeUtc,
                                // `auto_time` 关闭时不显示开始时间（只显示地点），与 iOS 的
                                // `vm.showsTime` 同一语义。数据层照旧记录 start_time_utc。
                                timeLabel = if (state.settings.autoTime) {
                                    formats.timeOf(java.time.Instant.ofEpochMilli(block.startTimeUtc))
                                } else {
                                    ""
                                },
                                locText = block.locText,
                                parts = ContentFlatten.parseContent(block.contentJson),
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                        showSearch = state.showSearch,
                        keyword = state.searchText,
                        // 当前命中（计数指向的那一处）用更强的高亮，与其余命中区分
                        currentHitBlockId = currentHit(state)?.blockId,
                        currentHit = currentHit(state)?.let {
                            DiarySearch.Match(it.partIndex, it.itemIndex, it.start, it.end)
                        },
                        onToggleTodo = { blockIndex, itemIndex ->
                            state.blocks.getOrNull(blockIndex)?.let { block ->
                                // `DiaryReadScreen` 给的是"这一天里的第几个片段 + 待办项下标"
                                viewModel.toggleTodo(
                                    block.id,
                                    partIndexOfFirstItems(block.contentJson),
                                    itemIndex,
                                )
                            }
                        },
                        // 点整卡 → 编辑；长按 → 多选（规范 §2.4/§2.8）——
                        // 这三条链路原先一个都没接线，点了没反应。
                        onEditBlock = viewModel::enterEditBlock,
                        onLongPressBlock = { index ->
                            state.blocks.getOrNull(index)?.let { viewModel.enterSelect(it.id) }
                        },
                        onToggleSelect = { index ->
                            state.blocks.getOrNull(index)?.let { viewModel.toggleSelection(it.id) }
                        },
                        selectMode = state.selectMode,
                        selectedIds = state.selectedIds,
                        scrollTargetBlockId = state.scrollTargetBlockId,
                        onScrollTargetConsumed = viewModel::consumeScrollTarget,
                        imageLoader = { part ->
                            part.src?.let { src -> container.mediaStore.image(src)?.asImageBitmap() }
                        },
                    )
                    // 页内搜索栏悬浮在内容之上（钉住不滚动）：跳到命中处时
                    // 「第几个/共几个」与步进键都还在手边
                    if (state.showSearch) {
                        DiarySearchBarOverlay(
                            keyword = state.searchText,
                            hitOrdinal = DiaryProgress.hitOrdinal(state.hitIndex, state.hits.size),
                            hitCount = state.hits.size,
                            onSearchChange = viewModel::onSearchTextChange,
                            onSearchStep = viewModel::stepHit,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                  }
                }
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
                    startTimeLabel = formats.timeOf(java.time.Instant.ofEpochMilli(state.startTimeUtc)),
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
 * 当前命中（`当前序号` 指向的那一处）。搜索栏收起、关键词为空或下标越界时为 null。
 *
 * 下标越界是真实场景：删掉片段后 `hits` 可能已经变短。
 */
private fun currentHit(state: DiaryPageViewModel.State): DiaryPageViewModel.SearchHit? =
    if (state.showSearch) state.hits.getOrNull(state.hitIndex) else null

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
            // 「放弃」= 回到本页的阅读态，**不关页**（iOS E1：discardEditing() + showRead()）
            onConfirm = viewModel::showRead
        }

        DiaryPageViewModel.DiaryAlert.DiscardConfirm -> {
            title = DISCARD_TITLE
            message = DISCARD_MESSAGE
            confirmLabel = DISCARD_LABEL
            onConfirm = viewModel::performDiscardEditing
        }

        is DiaryPageViewModel.DiaryAlert.CrossDay -> {
            title = CROSS_DAY_TITLE
            message = stringResource(R.string.editor_cross_day_msg, alert.dayKey)
            confirmLabel = SAVE_EXIT_LABEL
            onConfirm = viewModel::confirmCrossDay
        }

        DiaryPageViewModel.DiaryAlert.SaveFailed -> {
            title = SAVE_FAILED_TITLE
            message = SAVE_FAILED_MESSAGE
        }

        is DiaryPageViewModel.DiaryAlert.DeleteConfirm -> {
            title = DELETE_TITLE
            message = stringResource(R.string.editor_delete_msg, alert.count)
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


private val OK_LABEL: String
    @Composable get() = stringResource(R.string.ok)
private val CANCEL_LABEL: String
    @Composable get() = stringResource(R.string.cancel)
private val DISCARD_LABEL: String
    @Composable get() = stringResource(R.string.discard)
private val DELETE_LABEL: String
    @Composable get() = stringResource(R.string.delete)
private val SAVE_EXIT_LABEL: String
    @Composable get() = stringResource(R.string.editor_save_exit)

private val HISTORY_NO_ADD_TITLE: String
    @Composable get() = stringResource(R.string.editor_history_no_add_title)
private val HISTORY_NO_ADD_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_history_no_add_msg)
private val READONLY_TITLE: String
    @Composable get() = stringResource(R.string.editor_readonly_title)
private val READONLY_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_readonly_msg)
private val EXIT_TITLE: String
    @Composable get() = stringResource(R.string.editor_exit_title)
private val EXIT_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_exit_content_msg)
private val DISCARD_TITLE: String
    @Composable get() = stringResource(R.string.editor_discard_title)
private val DISCARD_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_discard_msg)
private val CROSS_DAY_TITLE: String
    @Composable get() = stringResource(R.string.editor_cross_day_title)
private val SAVE_FAILED_TITLE: String
    @Composable get() = stringResource(R.string.editor_save_failed_title)
private val SAVE_FAILED_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_save_failed_msg)
private val DELETE_TITLE: String
    @Composable get() = stringResource(R.string.editor_delete_title)
private val IMAGE_FAILED_TITLE: String
    @Composable get() = stringResource(R.string.editor_image_failed_title)
private val IMAGE_FAILED_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_image_failed_msg)
private val SHARE_FAILED_TITLE: String
    @Composable get() = stringResource(R.string.read_share_failed_title)
private val SHARE_FAILED_MESSAGE: String
    @Composable get() = stringResource(R.string.read_share_failed_msg)
