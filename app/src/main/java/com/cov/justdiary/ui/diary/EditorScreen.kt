package com.cov.justdiary.ui.diary

import android.graphics.Bitmap
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.service.EditorDraft
import com.cov.justdiary.service.LocationRules
import com.cov.justdiary.service.MediaStore
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.components.AppIcons
import com.cov.justdiary.ui.components.AnchoredMenu
import com.cov.justdiary.ui.components.AnchoredMenuItem
import com.cov.justdiary.ui.components.DiaryCard
import com.cov.justdiary.ui.components.locPrecisionLabel
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.EditorTypography
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing
import com.cov.justdiary.ui.theme.TypeSize
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 编辑页（规范 `docs/android-design/04-diary.md` §3）。
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │ ←(拦截)        [插图] [放弃] [保存]    │  ← 顶栏在 DiaryPageScreen 的 Scaffold 里
 * ├──────────────────────────────────────┤
 * │ ┌────────────────────────────────┐   │
 * │ │ ( 🕐 21:40 )                    │   │  ← 时间胶囊（auto_time 开才显示）
 * │ │ 📍 浦东新区 · 上海市 · 中国  显示精度│   │  ← 地点行：左状态 / 右动作
 * │ │                                 │   │
 * │ │ 字体样式自检|                    │   │  ← 输入区（最小高 160dp × 正文系数）
 * │ └────────────────────────────────┘   │
 * │        [格式栏 9 键：悬浮面板]          │  ← 贴键盘上方 8dp
 * └──────────────────────────────────────┘
 * ```
 *
 * ## 与阅读态共用同一把尺子
 *
 * 段落样式 → M3 角色（[EditorTypography]）→ 字号/行高/段距都以 px 传给 `Spannable`
 * （[BlockMetrics]），因此**编辑时看到的排版就是阅读时看到的排版**。
 *
 * ## 键盘与光标
 *
 * 页面的系统栏避让（含 IME）在 `DiaryPageScreen` 统一做，这里只负责把内容排成
 * 「卡片（占满剩余高度）+ 格式栏」，因此格式栏**不可能盖住光标**；
 * `EditText` 自己会在视口变化时把光标滚进可见区。
 *
 * ## 宿主怎么驱动它
 *
 * 顶栏的「插入图片 / 放弃修改 / 保存」属于宿主（`Scaffold` 的 `topBar`），
 * 由 [EditorActions] 这个把手转发进来 —— 编辑器不需要把自己的状态提升出去。
 */
@OptIn(FlowPreview::class)
@Composable
fun EditorScreen(
    initialParts: List<ContentPart>,
    /**
     * 载入令牌：**变化就重建编辑器状态**（换片段、以及「放弃修改」回滚到原文都会 +1）。
     *
     * 不能只用 `initialParts` 当 key：放弃修改时 `loadParts` 是同一个实例，
     * `remember(initialParts)` 不会重建 —— 实测表现为"点了放弃，内容一个字都没回去"。
     */
    loadToken: Int,
    isNewBlock: Boolean,
    locationState: LocationRules.EditorLocationState,
    /** 新片段（还没落库）才允许"重新获取位置"（iOS 的 `vm.canRelocate`）。 */
    canRelocate: Boolean,
    startTimeUtc: Long,
    actions: EditorActions,
    /** 上次未保存的草稿；非空时优先恢复它，而不是 [initialParts]。 */
    initialDraft: EditorDraft? = null,
    /** 编辑器内容变化后上报草稿（由 ViewModel 落盘）。 */
    onDraftChanged: (EditorDraft) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    autoTime: Boolean = true,
    /** 时间胶囊的文案（由宿主用 `DateFormats` 备好，跟随语言与 12/24 小时制）。 */
    startTimeLabel: String = "",
    onRequestLocation: () -> Unit = {},
    /** 菜单里的「重新获取位置」（丢掉当前快照再取一次，iOS `refreshLocation()`）。 */
    onRefreshLocation: () -> Unit = {},
    onPrecisionChange: (String) -> Unit = {},
    onSave: (EditorSavePayload) -> Unit = {},
    onImportImage: suspend (Uri) -> ImportedImage? = { null },
    /** 图片行的异步位图加载器（由调用方接 `MediaStore.imageAsync`）。 */
    loadImage: suspend (String, Int) -> Bitmap? = { _, _ -> null },
    /** 是否有未保存内容（宿主用它决定返回键是否要二次确认）。 */
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val originalLines = remember(loadToken) { EditorModel.partsToLines(initialParts) }
    val restoredLines = remember(loadToken) {
        initialDraft?.let { draft ->
            runCatching { EditorModel.partsToLines(ContentFlatten.parseContent(draft.contentJson)) }
                .getOrElse { originalLines }
        } ?: originalLines
    }
    val restoredSelection = remember(loadToken) {
        initialDraft?.let {
            EditorSelection(
                lineIndex = it.lineIndex,
                start = it.selectionStart,
                end = it.selectionEnd,
                endLineIndex = it.endLineIndex,
            )
        } ?: EditorSelection.NONE
    }
    val state = remember(loadToken) { EditorTextState(restoredLines, restoredSelection) }
    // P2-1：图片先异步解码进内存表，再让 `EditorTextArea` 用同步 imageProvider 组 Spannable。
    // `imageRevision` 变化时强制回写一次文本（否则图加载完成后 ImageSpan 仍是占位）。
    val images = remember(loadToken) { mutableStateMapOf<String, Bitmap>() }
    var imageRevision by remember(loadToken) { mutableIntStateOf(0) }
    LaunchedEffect(loadToken) {
        val srcs = state.lines.mapNotNull { it.imageSrc }.distinct()
        for (src in srcs) {
            if (src !in images) {
                loadImage(src, MediaStore.PREVIEW_MAX_PIXEL)?.let { bitmap ->
                    images[src] = bitmap
                }
            }
        }
        if (srcs.isNotEmpty()) imageRevision += 1
    }
    val effectiveStartTimeUtc = initialDraft?.startTimeUtc ?: startTimeUtc
    val dirty = hasChanges(state, originalLines)

    // 草稿快照：内容 + 选区 + 新片段的时间。只在上报时序列化，不在每次重组做。
    fun currentDraft(): EditorDraft = EditorDraft(
        contentJson = ContentFlatten.serializeContent(EditorModel.linesToParts(state.lines)),
        startTimeUtc = effectiveStartTimeUtc,
        lineIndex = state.selection.lineIndex,
        selectionStart = state.selection.start,
        selectionEnd = state.selection.end,
        endLineIndex = state.selection.endLineIndex,
    )

    val latestOnDraftChanged by rememberUpdatedState(onDraftChanged)
    LaunchedEffect(loadToken) {
        snapshotFlow { state.lines to state.selection }
            .drop(1)
            .debounce(250)
            .collect { latestOnDraftChanged(currentDraft()) }
    }
    DisposableEffect(Unit) {
        onDispose { actions.onFlushDraft = {} }
    }
    // 用 SideEffect（每次成功组合后都跑）而不是 LaunchedEffect(脏标记)：
    // 放弃修改会换一个 [EditorActions] 实例，而脏标记本身没变，LaunchedEffect 不会重跑，
    // 新实例就会停在 dirty = false（实测：返回键因此不再拦截）。
    SideEffect {
        actions.dirty = dirty
        onDirtyChange(dirty)
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var editText by remember { mutableStateOf<AppCompatEditText?>(null) }

    // P1-11：插图不是瞬时动作（大图解码 + 重新编码可能几百毫秒）。失败要有提示，
    // 导入中要有一个**不挡键盘、不抢焦点**的轻量进度。
    var importingImage by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val imageFailedText = stringResource(R.string.editor_image_failed_msg)
    val importingText = stringResource(R.string.editor_image_importing)
    // 多张图片共用同一条导入链：逐张导入 → 预载 → 插入图片行。
    suspend fun importAndInsert(uris: List<Uri>): Boolean {
        var failed = false
        uris.forEach { uri ->
            val imported = onImportImage(uri)
            if (imported == null) {
                failed = true
                return@forEach
            }
            loadImage(imported.src, MediaStore.PREVIEW_MAX_PIXEL)?.let { bitmap ->
                images[imported.src] = bitmap
            }
            imageRevision += 1
            state.insertImage(
                src = imported.src,
                width = imported.width,
                height = imported.height,
            )
        }
        return failed
    }

    // 图片选择：Photo Picker（无需存储权限）。P3：支持一次选多张，按选择顺序逐张
    // 插成独立的图片行（设计：一行一张图）；任何一张失败只提示一次，剩下的继续。
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_PICK),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importingImage = true
                try {
                    if (importAndInsert(uris)) {
                        snackbarHostState.showSnackbar(imageFailedText)
                    }
                } finally {
                    importingImage = false
                }
            }
        }
    }

    // 顶栏（宿主）驱动的三个动作：保存 / 放弃 / 插图
    SideEffect {
        actions.onInsertImage = {
            pickImages.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        // 「保存时还没有位置」的二次确认（规范 §3.6 D3）在 **ViewModel** 里做，
        // 不在这一层：它后面还紧跟着跨日确认（D4），两个弹窗共用同一个待写入项，
        // 分在两处会出现"弹窗点确认时编辑器内容已经变了"这种不一致。
        actions.onSave = { onSave(buildPayload(state.lines, effectiveStartTimeUtc)) }
        actions.onFlushDraft = { latestOnDraftChanged(currentDraft()) }
        actions.onDismissKeyboard = { dismissKeyboard(context, editText, focusManager) }
    }

    val currentLine = state.currentLine
    val flags = state.activeFlags
    val fontScale = LocalDensity.current.fontScale
    val metricsFor = rememberBlockMetrics()
    val quoteStyle = rememberQuoteStyle()
    val markerStyle = rememberMarkerStyle()
    val imageStyle = rememberImageStyle()
    // 正文列宽与阅读态同一条公式（规范 §2.6：阅读 = 编辑）
    val columnWidth: Dp = LocalAdaptiveLayout.current
        .contentColumn(EditorLayout.CONTENT_MAX_WIDTH).dp

    // 段落样式菜单与地点精度菜单都画在**这个 Box 的最上层**（同一个窗口 → 不收键盘）。
    // 两者共用一条"格式栏上沿"的对齐：菜单从格式栏上边长出来。
    var styleMenuOpen by remember { mutableStateOf(false) }
    var precisionMenuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().padding(contentPadding)) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 编辑卡占满剩余高度（weight 挂在有界高度的外层 Column 上），格式栏钉在底部：
        // 两者不重叠，所以格式栏**不可能**盖住光标（规范 §3.5 的 caret-not-hidden 结果）
        Column(modifier = Modifier.weight(1f).width(columnWidth)) {
            DiaryCard(
                modifier = Modifier
                    .fillMaxSize()
                    // 点卡片内的空白收键盘（规范 §3.8 路径 1）
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { dismissKeyboard(context, editText, focusManager) },
                contentPadding = PaddingValues(Spacing.card),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 时间胶囊：auto_time 关闭时不显示（`start_time_utc` 照常入库）
                    if (autoTime && startTimeLabel.isNotEmpty()) {
                        TimeCapsule(startTimeLabel)
                    }
                    LocationRow(
                        state = locationState,
                        onRequestLocation = onRequestLocation,
                        onPrecisionClick = { precisionMenuOpen = true },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    EditorTextArea(
                        state = state,
                        placeholder = if (isNewBlock) PLACEHOLDER_NEW else PLACEHOLDER_EDIT,
                        onViewReady = { editText = it },
                        imageProvider = { src -> images[src] },
                        imageRevision = imageRevision,
                        metricsFor = metricsFor,
                        quoteStyle = quoteStyle,
                        markerStyle = markerStyle,
                        imageStyle = imageStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        minHeight = EditorLayout.minInputHeight(fontScale),
                    )
                }
            }
        }

        // 格式栏：悬浮面板（28dp 圆角 + shadow-2），贴键盘上方 8dp / 手势条上方 8dp。
        // 宽度按规范 §3.3 的预算走**页面宽 − 2×8**（不是正文列宽）：那一行 384dp 要在
        // 412dp 手机上放得下，靠的就是这 8dp 的外边距；宽屏再被 660 卡住。
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.widthIn(max = 660.dp).fillMaxWidth()) {
            DiaryFormatBar(
                activeBlock = EditorBlockStyle.fromPartStyle(currentLine.blockStyle),
                flags = flags,
                isQuote = currentLine.blockStyle == ContentPartStyle.quote,
                isList = currentLine.isListItem,
                isTodo = currentLine.isTodoItem,
                isImage = currentLine.isImage,
                isCentered = currentLine.align == EditorModel.ALIGN_CENTER,
                // IME 组合期间禁用整栏（[FormatGate]：组合区里改 span 会被输入法覆盖）；
                // 居中还要额外过一道"与列表/引用/待办互斥"（规则 5）——判据是**整个选区**，
                // 只要选中的行里有一行是那三类，就置灰（不再是只看光标那一行）。
                enabled = !state.composing,
                centerEnabled = state.centerEnabled,
                styleMenuOpen = styleMenuOpen,
                onStyleMenuChange = { styleMenuOpen = it },
                // 段落样式菜单：**只应用、不切换**（大标题/小标题/正文是六选一里的三项）。
                // 六种段落格式与四种行内样式的接线统一在 `EditorFormatActions` 里
                // （那一层有单测逐个按钮驱动；这里只负责菜单开关与界面状态）
                onBlockStyle = { style ->
                    styleMenuOpen = false
                    EditorFormatActions.applyBlockStyle(state, style)
                },
                onToggleCenter = { EditorFormatActions.toggleCenter(state) },
                onToggleList = { EditorFormatActions.toggleList(state) },
                onToggleQuote = { EditorFormatActions.toggleQuote(state) },
                onToggleTodo = { EditorFormatActions.toggleTodo(state) },
                onToggleInline = { flag -> EditorFormatActions.toggleInline(state, flag) },
                modifier = Modifier.fillMaxWidth(),
            )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

        // ---- 浮层：两个菜单都画在这里（同一个窗口 → 打开菜单不收键盘）----------------
        //
        // 对齐到「格式栏上沿」：格式栏高 56 + 与它的间距 8 + 内容底边距 8 = 72dp。
        // 两者共用同一条对齐，因此段落样式菜单与精度菜单出现在**同一个位置**，
        // 与 iOS 的 `Menu` 一样是"从格式栏长出来的"。
        AnchoredMenu(
            expanded = styleMenuOpen,
            onDismiss = { styleMenuOpen = false },
            alignment = Alignment.BottomStart,
            modifier = Modifier.padding(start = 8.dp, bottom = MenuBarOffset),
        ) {
            ParagraphStyleMenu(
                activeBlock = EditorBlockStyle.fromPartStyle(currentLine.blockStyle),
                onSelect = { style ->
                    styleMenuOpen = false
                    state.apply { EditorModel.setBlockStyle(it, state.lineRange, style.partStyle) }
                },
            )
        }
        AnchoredMenu(
            expanded = precisionMenuOpen,
            onDismiss = { precisionMenuOpen = false },
            alignment = Alignment.BottomStart,
            modifier = Modifier.padding(start = 8.dp, bottom = MenuBarOffset),
        ) {
            val recorded = locationState as? LocationRules.EditorLocationState.Recorded
            if (recorded != null) {
                LocationRules.precisionOptions(recorded.snapshot).forEach { option ->
                    AnchoredMenuItem(
                        label = locPrecisionLabel(option),
                        selected = option == recorded.snapshot.locPrecision,
                        trailingIcon = Icons.Filled.LocationOn,
                        onClick = {
                            precisionMenuOpen = false
                            onPrecisionChange(option)
                        },
                    )
                }
            }
            // iOS `locationMenu` 的最后一项：还没落库的片段（`canRelocate`）可以从菜单里
            // 「重新获取位置」（`DiaryPageView.swift:472-480`）。已保存的片段没有这一项。
            if (canRelocate) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AnchoredMenuItem(
                    label = stringResource(R.string.editor_location_retry_action),
                    selected = false,
                    onClick = {
                        precisionMenuOpen = false
                        onRefreshLocation()
                    },
                )
            }
        }

        // 插图失败 / 提示（不阻塞键盘）
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = MenuBarOffset),
        )
        // 导入中的轻量进度：贴在格式栏上方，不抢焦点、不挡 IME
        if (importingImage) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = MenuBarOffset)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = importingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 菜单底边距：格式栏（56）+ 与它的间距（8）+ 页面底边距（8）。 */
private val MenuBarOffset = 72.dp

/**
 * 顶栏驱动编辑器的把手（宿主 `remember` 一个，编辑器在组合时把动作挂上来）。
 *
 * 这样顶栏的「插图 / 放弃 / 保存」不必把编辑器的状态提升到宿主：
 * 编辑器仍是一个自洽的组件，`DiaryPageScreen` 只拿到 [dirty] 与三个动作。
 */
class EditorActions {
    internal var onInsertImage: () -> Unit = {}
    internal var onSave: () -> Unit = {}
    internal var onDiscard: () -> Unit = {}
    internal var onDismissKeyboard: () -> Unit = {}
    internal var onFlushDraft: () -> Unit = {}

    /** 有未保存内容（顶栏据此决定返回是否拦截）。 */
    var dirty: Boolean by mutableStateOf(false)

    fun insertImage() = onInsertImage()
    fun save() = onSave()
    fun discard() = onDiscard()

    /** 点顶栏空白 / 内容空白时收键盘（规范 §3.8）。 */
    fun dismissKeyboard() = onDismissKeyboard()

    /** Activity 进后台前强制把最新草稿落盘（P0-1）。 */
    fun flushDraft() = onFlushDraft()
}

/** 一次最多选几张图（Photo Picker 的 `maxItems`；与 iOS 多图上限同量级）。 */
private const val MAX_IMAGES_PER_PICK = 9

/** 编辑页的版面常量。 */
object EditorLayout {
    /** 正文列宽上限（与阅读态同一条公式：`min(660, 宽 − 32)`）。 */
    const val CONTENT_MAX_WIDTH = 660f

    /** 输入区最小高度：160dp × 正文字号系数（规范 §3.1，AX5 约 240dp）。 */
    @Composable
    fun minInputHeight(fontScale: Float) =
        EditorTypography.minimumInputHeight * DynamicTypeMetrics.effectiveMultiplier(16f, fontScale)
}

/** 时间胶囊：`primaryContainer` 底 + `primary` 字（规范 §3.1）。 */
@Composable
private fun TimeCapsule(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * 地点行（iOS `DiaryPageView.swift:610-682` 的 `locationRow` / `locationChipLabel`）。
 *
 * **一个状态一枚胶囊**，没有"左边状态 + 右边按钮"那种两块拼起来的排版：iOS 侧从头到尾
 * 只有 `locationChipLabel(text:tint:)` 一个视图，处在"新建 + 还没拿到位置"时它本身就是
 * 那个「重新获取位置」按钮（`Button { vm.beginLocate() } label: { locationChipLabel(…) }`）。
 *
 * | 情形 | 胶囊文字 | 颜色 | 点击 |
 * | --- | --- | --- | --- |
 * | 新建 + 定位中 | `位置获取中…` | `primary` | — |
 * | 新建 + 还没拿到位置 | `重新获取位置` | `primary` | [onRequestLocation] |
 * | 已有位置 | 该位置文本 | `primary` | [onPrecisionClick]（精度菜单） |
 * | 编辑已有片段且无位置 | `未记录地点` | `onSurfaceVariant` | — |
 * | `auto_loc` 关且无位置 | 整行不显示 | — | — |
 *
 * 胶囊的样子照 iOS 的 `locationChipLabel`：`location.fill` 12pt + 文本 13pt、间距 4、
 * 内边距 10×5、整个胶囊刷 `primaryContainer`。
 */
@Composable
private fun LocationRow(
    state: LocationRules.EditorLocationState,
    onRequestLocation: () -> Unit,
    /** 「显示精度」被点击 —— 菜单本身画在 [EditorScreen] 的浮层里（见那里的注释）。 */
    onPrecisionClick: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    when (state) {
        is LocationRules.EditorLocationState.Hidden -> Unit

        is LocationRules.EditorLocationState.Locating -> LocationChip(
            text = stringResource(R.string.editor_location_fetching),
            tint = MaterialTheme.colorScheme.primary,
            fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.meta, fontScale),
        )

        is LocationRules.EditorLocationState.LocateFailed -> LocationChip(
            text = stringResource(R.string.editor_location_retry_action),
            tint = MaterialTheme.colorScheme.primary,
            fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.meta, fontScale),
            onClickLabel = stringResource(R.string.editor_location_retry_action),
            onClick = onRequestLocation,
        )

        is LocationRules.EditorLocationState.Recorded -> LocationChip(
            text = state.snapshot.locText,
            tint = MaterialTheme.colorScheme.primary,
            fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.meta, fontScale),
            // iOS：`accessibilityLabel(search_loc_title)` + `value = 地址`
            onClickLabel = stringResource(R.string.search_loc_title),
            onClick = onPrecisionClick,
        )

        is LocationRules.EditorLocationState.NotRecorded -> LocationChip(
            text = stringResource(R.string.search_loc_no_loc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.meta, fontScale),
        )
    }
}

/**
 * 地点胶囊（iOS `locationChipLabel`）。[onClick] 为 null 时是**静态**胶囊（不给点击语义，
 * 免得读屏报一个点不动的按钮）。
 */
@Composable
private fun LocationChip(
    text: String,
    tint: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = AppIcons.Place,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = tint,
        )
        Text(
            text = text,
            fontSize = fontSize,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 段落样式 → 编辑器排版（px）。字号/行高来自 M3 角色，段后距 8dp（规范 §2.5）。
 *
 * 这四个值随主题与系统字号变化，所以在这里解析一次、以纯数据传进桥接层。
 */
@Composable
private fun rememberBlockMetrics(): (String) -> BlockMetrics {
    val density = LocalDensity.current
    val styles: Map<String, TextStyle> = mapOf(
        ContentPartStyle.title to EditorTypography.textStyleFor(ContentPartStyle.title),
        ContentPartStyle.heading to EditorTypography.textStyleFor(ContentPartStyle.heading),
        ContentPartStyle.quote to EditorTypography.textStyleFor(ContentPartStyle.quote),
        ContentPartStyle.body to EditorTypography.textStyleFor(ContentPartStyle.body),
    )
    val paragraphSpacingPx = with(density) { BlockGeometry.ParagraphGap.toPx() }.toInt()
    return remember(density, styles) {
        { style ->
            // 字号 / 行高 / **字距 / 字重**统一走 [blockMetricsOf]：阅读态的 M3 角色带
            // letterSpacing（正文 0.5sp、引用 0.25sp），早先编辑态只同步字号与行高，
            // 两边同一段文字的换行位置就会不同（用户反馈的"排版不一致"）。
            blockMetricsOf(
                style = styles[style] ?: styles.getValue(ContentPartStyle.body),
                density = density,
                paragraphSpacingPx = paragraphSpacingPx,
            )
        }
    }
}

/**
 * 引用段的外观（编辑态）。**参数与阅读态、首页预览逐项相同**（[BlockGeometry]）：
 * `surfaceContainerHigh` 底 + 4dp 圆角 + 上下 6dp + 左侧 4dp 竖条 + 文字内缩 16dp。
 *
 * 编辑态以前只挂了块样式 span，点「引用」时正文外观**逐像素不变**（R30 记的 F1）——
 * 而阅读态与 iOS 编辑态都是有底的。这里把这套几何交给 `DiaryEditText.onDraw` 画
 * （**不能**用 `LineBackgroundSpan`：平台先画光标、再画行背景 span，底会把光标盖住，
 * 见 [QuoteStyle] 与 R33 的记录）。
 */
@Composable
private fun rememberQuoteStyle(): QuoteStyle {
    val density = LocalDensity.current
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val bar = MaterialTheme.colorScheme.outlineVariant
    return remember(density, background, bar) {
        QuoteStyle(
            backgroundColor = background.toArgb(),
            cornerRadiusPx = with(density) { BlockGeometry.QuoteRadius.toPx() },
            textInsetPx = with(density) { BlockGeometry.QuoteTextInset.toPx() }.toInt(),
            paddingVerticalPx = with(density) { BlockGeometry.QuotePadding.toPx() }.toInt(),
            barLeadingPx = with(density) { BlockGeometry.QuoteBarLeading.toPx() },
            barWidthPx = with(density) { BlockGeometry.QuoteBarWidth.toPx() },
            barInsetPx = with(density) { BlockGeometry.QuoteBarInset.toPx() },
            barRadiusPx = with(density) { BlockGeometry.QuoteBarRadius.toPx() },
            barColor = bar.toArgb(),
            // ⚠️ 必须与段落排版里的段距是同一个值（引用底的末行要把它减掉）
            paragraphSpacingPx = with(density) { BlockGeometry.ParagraphGap.toPx() }.toInt(),
        )
    }
}

/**
 * 列表 / 待办的标记（编辑态）：几何全部来自 [BlockGeometry]，颜色用 M3 语义色 ——
 * 与阅读态的 `BulletRow` / `TodoBox` 是同一套数（那边按 dp 直接用同一批常量）。
 */
@Composable
private fun rememberMarkerStyle(): MarkerStyle {
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val onFill = MaterialTheme.colorScheme.onPrimary
    return remember(density, outline, fill, onFill) {
        MarkerStyle(
            indentPx = with(density) { BlockGeometry.MarkerIndent.toPx() }.toInt(),
            centerXPx = with(density) { BlockGeometry.MarkerSlot.toPx() } / 2f,
            bulletDiameterPx = with(density) { BlockGeometry.BulletSize.toPx() },
            checkboxSizePx = with(density) { BlockGeometry.CheckboxSize.toPx() },
            checkboxRadiusPx = with(density) { BlockGeometry.CheckboxRadius.toPx() },
            checkboxBorderPx = with(density) { BlockGeometry.CheckboxBorder.toPx() },
            outlineColor = outline.toArgb(),
            fillColor = fill.toArgb(),
            checkColor = onFill.toArgb(),
            doneTextColor = outline.toArgb(),
        )
    }
}

/**
 * 图片行的外观（编辑态）：圆角与上下留白**与阅读态的 `InlineImage` 同源**
 * （`Radius.image` / `ReadLayout.IMAGE_SPACING`），占位色用阅读态的 `surfaceContainerHighest`。
 *
 * 阅读态是 Compose `Image`，圆角与留白由布局直接给；编辑态是 `ImageSpan`，
 * 平台只把 drawable 塞进行里 —— 所以这两项必须由 [RoundedImageDrawable] 自己带。
 */
@Composable
private fun rememberImageStyle(): ImageStyle {
    val density = LocalDensity.current
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHighest
    return remember(density, placeholder) {
        ImageStyle(
            verticalPaddingPx = with(density) { ReadLayout.IMAGE_SPACING.dp.toPx() }.toInt(),
            cornerRadiusPx = with(density) { Radius.image.toPx() },
            placeholderColor = placeholder.toArgb(),
        )
    }
}

/** 收键盘：清焦点 + 主动收起 IME（Android 的 `clearFocus` 不保证收起）。 */
private fun dismissKeyboard(
    context: android.content.Context,
    editText: AppCompatEditText?,
    focusManager: FocusManager,
) {
    focusManager.clearFocus()
    val view = editText ?: return
    view.clearFocus()
    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
}

/** 与进入编辑时的快照比较，判断是否有改动（决定"放弃修改"是否需要确认）。 */
private fun hasChanges(
    state: EditorTextState,
    initialLines: List<EditorLine>,
): Boolean = state.lines != initialLines

/**
 * 保存载荷：内容 + **这一刻的起始时刻**。
 *
 * 位置不在载荷里 —— 它由 ViewModel 在收到保存时从自己那份 `location` 快照冻结
 * （新建只写一次、编辑只改精度两条规则都在 `LocationRules` / ViewModel 里，
 * 不重复放到界面层判断）。
 */
data class EditorSavePayload(
    val parts: List<ContentPart>,
    val startTimeUtc: Long,
)

/** 一张已导入应用目录的图片（`MediaStore.importImage` 的结果）。 */
data class ImportedImage(val src: String, val width: Double?, val height: Double?)

private fun buildPayload(
    lines: List<EditorLine>,
    startTimeUtc: Long,
): EditorSavePayload = EditorSavePayload(
    parts = EditorModel.linesToParts(lines),
    // 开始时间**永远**记录，与 `auto_time` 无关：
    // `day_key` / `created_utc` / 排序都依赖它，写 0 或改写都会破坏跨端契约。
    startTimeUtc = startTimeUtc,
)

private val PLACEHOLDER_NEW: String
    @Composable get() = stringResource(R.string.editor_placeholder_new)
private val PLACEHOLDER_EDIT: String
    @Composable get() = stringResource(R.string.editor_placeholder_existing)
