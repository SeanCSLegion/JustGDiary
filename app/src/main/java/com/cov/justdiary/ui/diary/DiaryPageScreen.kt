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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cov.justdiary.JustDiaryApplication
import com.cov.justdiary.app.AppForeground
import com.cov.justdiary.R
import com.cov.justdiary.data.DiaryProgress
import com.cov.justdiary.data.ShareLayout
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
    /**
     * 设置版本号（设置页保存一次 +1）。
     *
     * 本页要跟着刷新的是**"今天"**：`dayStartHour` 改了之后，"今天"可能变成另一天，
     * 而本页的 `todayKey` 是在 [DiaryPageViewModel.open] 时算好并一直存着的 ——
     * 不刷新的话，用户改完换日时刻回来，保存时的跨日判定用的是**旧规则**（少一次确认）。
     */
    settingsVersion: Int = 0,
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

    // 定位权限：编辑器里的「重新获取位置」在未授权时**先申请权限再取点**
    // （iOS `beginLocate()` 未授权时会 `requestPermission()` 并在 600ms 后复查；
    //  Android 的申请必须走 Activity 的结果通道，于是落在这一层）。
    // 少了这条链路，真机上"权限还没给"时点那颗胶囊什么都不会发生。
    val locationPermissions = remember(container) { container.locationService.requiredPermissions() }
    // 授权通过之后要接着做的那件事：false = 取一次位置；true = 丢掉旧的再取（菜单项）
    var pendingRefresh by remember { mutableStateOf(false) }
    val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        val refresh = pendingRefresh
        pendingRefresh = false
        if (granted) {
            if (refresh) viewModel.refreshLocation() else viewModel.beginLocate()
        }
    }
    val askForLocation: (Boolean) -> Unit = { refresh ->
        if (container.locationService.isAuthorized()) {
            if (refresh) viewModel.refreshLocation() else viewModel.beginLocate()
        } else {
            pendingRefresh = refresh
            locationPermissionLauncher.launch(locationPermissions)
        }
    }
    val requestLocation: () -> Unit = { askForLocation(false) }
    val refreshLocation: () -> Unit = { askForLocation(true) }

    LaunchedEffect(dayKey) {
        // P0-1：配置变更（深色/字号/分屏）后 Compose 会重跑这个 effect，但同一个
        // ViewModel 里已经是编辑态了 —— 再 open() 会把 isRead 复位成阅读态并清掉草稿恢复。
        // 只有“这个 ViewModel 还没打开过这一天”时才真正 open。
        val current = viewModel.state.value
        val sameDay = if (dayKey.isNullOrBlank()) {
            current.dayKey.isNotEmpty()
        } else {
            current.dayKey == dayKey
        }
        // 只有“同一天且正在编辑/多选”才跳过 open —— 那正是配置变更后要保住的状态。
        // 阅读态重进同一天仍要 open：它会清掉上一次的 requestDismiss 并重新读库。
        val keepEditing = sameDay && (!current.isRead || current.selectMode)
        if (!keepEditing) {
            viewModel.open(dayKey, startInWrite)
        }
    }
    // 设置变过（尤其是"新一天的开始时间"）：重算 todayKey 与位置/时间等显示设置。
    // 编辑态与待确认的保存**一律不动** —— 只换设置，不碰内容。
    // 首帧跳过：那时 `open()` 还没跑，refreshSettings 会先按默认设置算一次 todayKey。
    val lastSettingsVersion = remember { mutableIntStateOf(settingsVersion) }
    LaunchedEffect(settingsVersion) {
        if (settingsVersion == lastSettingsVersion.intValue) return@LaunchedEffect
        lastSettingsVersion.intValue = settingsVersion
        viewModel.refreshSettings()
    }
    // 回到前台同样要重算：在编辑页切出去待一会儿再回来，"今天"可能已经是另一天了
    // （iOS 的 `scenePhase == .active` 分支；这也是保存时跨日确认能问对问题的前提）。
    val foregroundTick by AppForeground.tick.collectAsState()
    val lastForegroundTick = remember { mutableIntStateOf(foregroundTick) }
    LaunchedEffect(foregroundTick) {
        if (foregroundTick == lastForegroundTick.intValue) return@LaunchedEffect
        lastForegroundTick.intValue = foregroundTick
        viewModel.refreshSettings()
        // 顺手补一次定位：上一次可能是在地铁里 / 刚开定位开关时失败的
        viewModel.retryLocateOnForeground()
    }
    // 新建片段 + 自动定位开着，但权限还没给：进编辑器就把权限要出来
    // （iOS `beginLocate()` 未授权时的 `requestPermission()` 同义）。
    LaunchedEffect(state.isRead, state.loadToken, state.settings.autoLoc) {
        if (state.isRead || state.isEditingExistingBlock || !state.settings.autoLoc) return@LaunchedEffect
        if (!container.locationService.isAuthorized()) {
            locationPermissionLauncher.launch(locationPermissions)
        }
    }
    LaunchedEffect(state.requestDismiss) {
        if (state.requestDismiss) {
            viewModel.consumeDismiss()
            onDataChanged()
            onClose()
        }
    }
    // 分享面板里的「分享…」：交给系统 Sharesheet。
    // 这里**不再**用 `LaunchedEffect(state.shareImageFile)` 自动弹 —— 面板要先把预览画出来，
    // 由用户点「分享…」才走系统面板（规范 §4.2；iOS 是内嵌 UIActivityViewController，
    // Android 的系统面板是独立 UI，只能按钮触发）。
    val shareChooserTitle = stringResource(R.string.share_chooser_title)
    val shareNow2: () -> Unit = {
        state.shareImageFile?.let { file ->
            runCatching {
                context.startActivity(ShareIntents.shareImage(context, file, shareChooserTitle))
            }
        }
    }

    // 一次性提示（保存到相册的结果）→ Snackbar。
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.share_saved)
    val saveFailedMessage = stringResource(R.string.share_save_failed)
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            when (message) {
                DiaryPageViewModel.DiaryMessage.SavedToGallery -> savedMessage
                DiaryPageViewModel.DiaryMessage.SaveToGalleryFailed -> saveFailedMessage
            },
        )
        viewModel.consumeMessage()
    }

    androidx.activity.compose.BackHandler {
        viewModel.handleBack(editorHasContent = editorDirty)
    }

    // 离开编辑态（保存 / 放弃 / 返回）要把键盘收掉：编辑器是被整个换掉的，
    // `EditText` 一走，没人负责收 IME —— 真机上保存之后键盘还杵在阅读页上。
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(state.isRead) {
        if (state.isRead) keyboard?.hide()
    }

    val formats = remember(state.settings.appLanguage, state.dayKey) {
        DateFormats.forContext(context, state.settings.appLanguage)
    }
    // 分享图的深浅色跟随"当下界面"的取色规则（与 iOS 的 `UITraitCollection` 一致）
    val darkTheme = isDarkTheme(state.settings.themeMode)
    // 「分享」：**一步**弹面板（规范 §4.1）。面板先画进度圈，渲染完成换成预览
    // （iOS `DiaryViewModel.shareDiary()` 同一条链：先拉起面板 → 后台渲染 → 预览替换进度）。
    //
    // 分享图里的文案全部在这里用 `DateFormats` 备好（跟随语言 / 12-24 小时制 / `auto_time`）：
    // 品牌行与页脚的「一页时光」是应用名，与 iOS 的 `EntryAbility_label` 同一个来源。
    val appLabel = stringResource(R.string.app_name)
    val shareTextsFor: (String) -> ShareLayout.ShareTexts = { dayKey ->
        val date = com.cov.justdiary.model.DateUtil.parseDayKey(dayKey) ?: java.time.LocalDate.now()
        val firstTime = state.blocks.firstOrNull()?.startTimeUtc ?: 0L
        ShareLayout.ShareTexts(
            brand = appLabel,
            year = formats.year(date.year),
            date = formats.dateOnly(date),
            weekday = formats.weekdayShort(date),
            // 头部第二行**只有时间**（`read_start_time`）：地点在第一条记录的时间行里
            startLine = if (firstTime > 0) {
                formats.startTimeOnly(formats.timeOf(java.time.Instant.ofEpochMilli(firstTime)))
            } else {
                null
            },
            footerBrand = "$appLabel · JustADiary",
            dayKey = dayKey,
        )
    }
    val shareNow: (DiaryPageViewModel.State) -> Unit = { current ->
        viewModel.openSharePanel()
        viewModel.shareDiary(
            isDark = darkTheme,
            showTime = current.settings.autoTime,
            textsFor = shareTextsFor,
            timeLabel = { epoch -> formats.timeOf(java.time.Instant.ofEpochMilli(epoch)) },
        )
    }

    // 外层 Box 只为一件事：把一次性提示（Snackbar）与分享面板叠在页面之上。
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // 全屏路由没有外层 Scaffold，系统栏避让要自己做（对应 iOS 的 safeArea）
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // 顶栏由宿主提供，**钉在页面顶部不进滚动区**（规范 §2.2；iOS 早期挂在
            // safeAreaInset 上，键盘一弹就被滚出屏幕）。三种形态共用一个 `DiaryTopBar`，
            // 形态判断全交给纯函数 `DiaryTopBarState.of`。
            val topBarState = DiaryTopBarState.of(
                isRead = state.isRead,
                editingIndex = state.editingIndex,
                selectMode = state.selectMode,
                selectedIds = state.selectedIds,
                canEditToday = state.canEditToday,
                showSearch = state.showSearch,
            )
            // 编辑器的把手：顶栏的插图/放弃/保存通过它转到编辑器内部（不必提升编辑器状态）
            val editorActions = remember(state.loadToken) { EditorActions() }
            // P0-1：进后台前把最新草稿同步落盘；进程此时随时可能被回收。
            // 用 Activity（LocalContext）而不是已废弃的 LocalLifecycleOwner；预览环境下为 null 时跳过。
            val lifecycleOwner = LocalContext.current as? LifecycleOwner
            DisposableEffect(lifecycleOwner, editorActions) {
                if (lifecycleOwner == null) return@DisposableEffect onDispose { }
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) editorActions.flushDraft()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    DiaryTopBar(
                        state = topBarState,
                        onBack = { viewModel.handleBack(editorHasContent = editorActions.dirty) },
                        onToggleSearch = { viewModel.setSearchVisible(!state.showSearch) },
                        onWrite = viewModel::enterWrite,
                        onShare = { shareNow(state) },
                        onExitSelect = { viewModel.handleBack(editorHasContent = false) },
                        onDeleteSelected = viewModel::confirmDeleteSelected,
                        onInsertImage = editorActions::insertImage,
                        onDiscard = { viewModel.confirmDiscard(editorHasContent = editorActions.dirty) },
                        onSave = editorActions::save,
                        onTapBlank = editorActions::dismissKeyboard,
                    )
                },
            ) { innerPadding ->
              if (state.isRead || state.selectMode) {
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
                        onToggleTodo = { blockIndex, partIndex, itemIndex ->
                            state.blocks.getOrNull(blockIndex)?.let { block ->
                                // P1-14：段落下标由阅读层一路带上来，不再猜"第一个 items 段"。
                                viewModel.toggleTodo(block.id, partIndex, itemIndex)
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
                        onDeleteBlock = { index ->
                            state.blocks.getOrNull(index)?.let { viewModel.confirmDeleteBlock(it.id) }
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
              } else {
                EditorScreen(
                    initialParts = state.loadParts,
                    loadToken = state.loadToken,
                    isNewBlock = !state.isEditingExistingBlock,
                    locationState = editorLocationState(state),
                    canRelocate = state.canRelocate,
                    startTimeUtc = state.startTimeUtc,
                    actions = editorActions,
                    initialDraft = state.editorDraft,
                    onDraftChanged = viewModel::onEditorDraftChanged,
                    contentPadding = innerPadding,
                    autoTime = state.settings.autoTime,
                    startTimeLabel = formats.timeOf(java.time.Instant.ofEpochMilli(state.startTimeUtc)),
                    onRequestLocation = requestLocation,
                    onRefreshLocation = refreshLocation,
                    onPrecisionChange = viewModel::applyPrecision,
                    onSave = viewModel::save,
                    onImportImage = { uri ->
                        viewModel.importImage(uri)?.let { src ->
                            // P1-11/P2-1：回读尺寸也要在 IO 上做，别让大图解码卡住主线程。
                            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                container.mediaStore.image(src)
                            }
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
    }

    // 一次性提示（保存到相册成功/失败）。
    //
    // **钉在顶部**，不是底部：分享面板是 M3 `ModalBottomSheet`，它自带一个**独立窗口**
    // 盖住整屏 —— 底部的 Snackbar 会被那个窗口整个遮住（真机实测：提示根本不出现）。
    // 顶部这一段在面板占位之上（面板最高到 `skipPartiallyExpanded` 的顶部留白），
    // 因此"已保存到相册"能真的被看见。
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = DiaryTopBarHeight),
    )

    // 分享面板（规范 §4）：预览 + 「分享…」+「保存到相册」。关闭时清掉临时分享图。
    ShareSheet(
        visible = state.sharePanelVisible,
        rendering = state.sharing,
        imageFile = state.shareImageFile,
        onDismiss = viewModel::dismissSharePanel,
        onShare = shareNow2,
        onSaveToGallery = viewModel::saveShareToGallery,
    )

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

        DiaryPageViewModel.DiaryAlert.LocationMissing -> {
            title = LOCATION_MISSING_TITLE
            message = LOCATION_MISSING_MESSAGE
            confirmLabel = SAVE_ANYWAY_LABEL
            onConfirm = viewModel::confirmSaveAnyway
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

    // 「取消」对两个确认弹窗（未获取到位置 / 跨日）意味着**放弃这次保存**：
    // 只清 alert 不够，待写入项也得一起丢掉，否则下次点保存会拿旧内容重来。
    val alertIsSaveConfirm = alert is DiaryPageViewModel.DiaryAlert.CrossDay ||
        alert is DiaryPageViewModel.DiaryAlert.LocationMissing
    val cancel: () -> Unit = if (alertIsSaveConfirm) viewModel::cancelPendingSave else viewModel::consumeAlert

    AlertDialog(
        onDismissRequest = cancel,
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
            { TextButton(onClick = cancel) { Text(CANCEL_LABEL) } }
        } else {
            null
        },
    )
}


/** 顶栏高度（一次性提示要落在它下面）。与 `DiaryTopBar` 的 64dp 一致。 */
private val DiaryTopBarHeight = 64.dp

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
private val LOCATION_MISSING_TITLE: String
    @Composable get() = stringResource(R.string.editor_location_retry)
private val LOCATION_MISSING_MESSAGE: String
    @Composable get() = stringResource(R.string.editor_location_missing_msg)
private val SAVE_ANYWAY_LABEL: String
    @Composable get() = stringResource(R.string.editor_save_anyway)

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
