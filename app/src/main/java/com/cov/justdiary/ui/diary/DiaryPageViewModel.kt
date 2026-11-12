package com.cov.justdiary.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.EditorDraft
import com.cov.justdiary.service.LocationFix
import com.cov.justdiary.service.LocationResolver
import com.cov.justdiary.service.LocationRules
import com.cov.justdiary.service.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日记页状态。对应 iOS `ViewModels/DiaryViewModel.swift`（534 行，本页是全 app 最重的一块）。
 *
 * ## 一个页面、两种形态
 *
 * iOS 把"读"和"写"放在同一个 `DiaryPageView` 里，用 `isRead` 切换。Android 侧保持同样的模型：
 * [State.isRead] 为真时渲染 [DiaryReadScreen]，为假时渲染 [EditorScreen]，
 * 因此"从阅读态点某个片段进编辑"仍然只有一条路径，不会出现两个栈。
 *
 * ## 位置的规则（数据正确性的核心，见 `LocationRules`）
 *
 * - **只有新建片段**才获取位置；编辑已有片段永不重新获取，连"重新获取"入口都不给；
 * - 编辑时只允许改**显示精度**，坐标与地区列保持写入那一刻的值；
 * - 坐标拿到了但地址为空 → 按没有位置处理。
 *
 * ## 与 iOS 的差异
 *
 * | iOS | Android |
 * | --- | --- |
 * | `RichEditorController` 直接持有 `UITextView` | 编辑内容是 `List<EditorLine>`（纯模型），由 `EditorScreen` 持有；保存时通过 `onSave(EditorSavePayload)` 回投 |
 * | `NotificationCenter.diaryVersionChanged` | 显式的 `refresh()` 调用 |
 * | `onDismiss` 闭包 | [State.requestDismiss] 一次性标志，由宿主导航消费 |
 */
class DiaryPageViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val dayKey: String = "",
        /** 实际写入的日期（跨日保存后可能与 [dayKey] 不同）。 */
        val actualDayKey: String = "",
        val blocks: List<EditBlock> = emptyList(),
        val isRead: Boolean = true,
        /** 正在编辑的片段下标；`null` 表示"新建片段"。 */
        val editingIndex: Int? = null,
        val selectMode: Boolean = false,
        val selectedIds: Set<Long> = emptySet(),
        val location: LocationRules.LocationSnapshot? = null,
        val locating: Boolean = false,
        val startTimeUtc: Long = System.currentTimeMillis(),
        val settings: AppSettings = AppSettings(),
        /** 载入到编辑器里的段落（`loadToken` 变化时重新载入）。 */
        val loadParts: List<ContentPart> = emptyList(),
        val loadToken: Int = 0,
        /** 未保存草稿（P0-1）；非空时编辑器优先恢复它，而不是 [loadParts]。 */
        val editorDraft: EditorDraft? = null,
        val alert: DiaryAlert? = null,
        /** 宿主应当关闭本页。 */
        val requestDismiss: Boolean = false,
        /** 页内搜索（150ms 防抖后算命中）。 */
        val searchText: String = "",
        val showSearch: Boolean = false,
        val hits: List<SearchHit> = emptyList(),
        val hitIndex: Int = 0,
        /** 命中所在片段 id，供宿主滚动定位。 */
        val scrollTargetBlockId: Long? = null,
        /** 分享图生成完成后交给宿主的文件。 */
        val shareImageFile: File? = null,
        /** 分享面板是否开着（规范 §4：点分享**一步**弹面板，渲染在面板里进行）。 */
        val sharePanelVisible: Boolean = false,
        /** 分享图正在渲染（面板里显示进度圈 + 「正在生成分享图…」）。 */
        val sharing: Boolean = false,
        /** 一次性提示（成功类；失败类走 [alert]）。由界面消费成 Snackbar。 */
        val message: DiaryMessage? = null,
        /** "今天"的 day_key（受 `dayStartHour` 影响）—— 决定能否新建片段。 */
        val todayKey: String = "",
        /**
         * 已经点过保存、正在等确认弹窗（未获取到位置 / 跨日）。**没有**它就等于没点过保存。
         *
         * 两个确认弹窗之间要交代的东西（内容 / 起始时刻 / 位置快照）全在这里，
         * 因此「仍然保存」之后不需要编辑器再回投一次内容 —— 它背后还活着，
         * 这期间用户改的任何一个字都不该悄悄进入这一次写入。
         */
        val pendingSave: PendingNewBlock? = null,
    ) {
        val canEditToday: Boolean get() = actualDayKey.isNotEmpty() && actualDayKey == todayKey

        val isEditingExistingBlock: Boolean get() = editingIndex != null

        /** 新建片段可以重新取位置，已有片段不行。 */
        val canRelocate: Boolean get() = editingIndex == null

        val locationText: String get() = location?.locText.orEmpty()

        val precisionOptions: List<String>
            get() {
                val snapshot = location ?: return emptyList()
                return LocationRules.precisionOptions(snapshot)
            }
    }

    /**
     * 一次待确认的新建片段（点过保存、还没落库）。
     *
     * 内容与时间在**点保存的那一刻**冻结：两个确认弹窗期间编辑器仍然在背后活着，
     * 用户还能改精度、甚至重新定位 —— 那些都不该悄悄改动这一次的写入内容（iOS 同此）。
     */
    data class PendingNewBlock(
        val contentJson: String,
        val startTimeUtc: Long,
        /** 保存那一刻的位置文案（未取到位置时为空串）。 */
        val locText: String,
        val locPrecision: String,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val region: LocRegion = EMPTY_REGION,
    )

    /** 一次性提示（成功类；文案由界面层解析）。 */
    sealed interface DiaryMessage {
        /** 「已保存到相册」（`share_saved`）。 */
        data object SavedToGallery : DiaryMessage

        /** 「保存失败」（`share_save_failed`）。 */
        data object SaveToGalleryFailed : DiaryMessage
    }

    /** 一次性的结果提示（文案由界面层解析，保持 ViewModel 与资源解耦）。 */
    sealed interface DiaryAlert {
        /** 历史日期不能新增片段（`editor_history_no_add_*`）。 */
        data object HistoryNoAdd : DiaryAlert

        /** 不允许修改历史日记（`editor_readonly_*`）。 */
        data object ReadOnly : DiaryAlert

        /** 退出且有未保存内容（`editor_exit_*`）→ 确认后丢弃并关闭。 */
        data object ExitConfirm : DiaryAlert

        /** 主动"放弃修改"（`editor_discard_*`）。 */
        data object DiscardConfirm : DiaryAlert

        /** 跨日保存（`editor_cross_day_*`）。 */
        data class CrossDay(val dayKey: String) : DiaryAlert

        /**
         * 新建片段、`auto_loc` 开着却还没拿到位置（`editor_location_retry`：
         * 标题「未获取到位置」· 确认「仍然保存」）。
         *
         * 保存之后这个片段**再也补不上位置**（`LocationRules.canAddLocationAfterSave`），
         * 所以要先问一句 —— 与 iOS D3 同一处、同一组文案。
         */
        data object LocationMissing : DiaryAlert

        data object SaveFailed : DiaryAlert

        data class DeleteConfirm(val count: Int) : DiaryAlert

        data object ImageInsertFailed : DiaryAlert

        data object ShareFailed : DiaryAlert
    }

    /**
     * 页内搜索的一次命中。
     *
     * `itemIndex` 为 -1 表示命中在整段文本上，否则是列表 / 待办的第几项。
     * 命中由 [DiarySearch.findMatches] 算出 —— **与阅读页高亮是同一个函数**（规范 §2.7）。
     */
    data class SearchHit(
        val blockId: Long,
        val partIndex: Int,
        val itemIndex: Int,
        val start: Int,
        val end: Int,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val drafts = container.editorDrafts
    private var draftSaveJob: Job? = null
    private var pendingDraft: EditorDraft? = null
    private var pendingDraftKey: String? = null

    private var searchJob: Job? = null

    /** 关闭分享面板后清缓存的作业（下一次分享前不必等它）。 */
    private var shareCleanupJob: Job? = null

    /** 打开某一天。`dayKey` 为空表示"今天"。 */
    /**
     * 打开某一天。
     *
     * [startInWrite] 为 true 时，**在 `reload()` 之后**直接进入编辑态 ——
     * 顺序很重要：`reload()` 是异步的，但它会把 `isRead` 复位成阅读态；
     * 先 `enterWrite()` 会被它覆盖回去（实测：面板点「写日记」仍然停在阅读页）。
     */
    fun open(dayKey: String?, startInWrite: Boolean = false) {
        val settings = container.settings.load()
        val today = DateUtil.dayKeyForUtc(System.currentTimeMillis(), settings.dayStartHour)
        val key = dayKey?.takeIf { it.isNotBlank() } ?: today
        _state.value = _state.value.copy(
            dayKey = key,
            todayKey = today,
            actualDayKey = key,
            startTimeUtc = System.currentTimeMillis(),
            settings = settings,
            hits = emptyList(),
            hitIndex = 0,
            searchText = "",
            showSearch = false,
            editorDraft = null,
            requestDismiss = false,
            alert = null,
            pendingSave = null,
            isRead = true,
            editingIndex = null,
            selectMode = false,
            selectedIds = emptySet(),
        )
        viewModelScope.launch {
            reload()
            if (startInWrite) enterWrite()
        }
    }

    fun refreshSettings() {
        val settings = container.settings.load()
        _state.value = _state.value.copy(
            settings = settings,
            todayKey = DateUtil.dayKeyForUtc(System.currentTimeMillis(), settings.dayStartHour),
        )
    }

    // ------------------------------------------------------------------ 草稿（P0-1）

    /** `dayKey#blockId` / `dayKey#new`；只用于落盘键，不进 UI。 */
    private fun draftKey(dayKey: String, blockId: Long?): String =
        "${dayKey.ifEmpty { "-" }}#${blockId ?: "new"}"

    private fun draftKeyFor(state: State = _state.value): String {
        val blockId = state.editingIndex?.let { state.blocks.getOrNull(it)?.id }
        return draftKey(state.actualDayKey.ifEmpty { state.dayKey }, blockId)
    }

    /**
     * 进入编辑时读草稿；已有片段且草稿与库内容完全一致时，视为“保存过的旧草稿”清掉。
     */
    private fun loadDraftFor(dayKey: String, blockId: Long?): EditorDraft? {
        val key = draftKey(dayKey, blockId)
        val draft = drafts.load(key) ?: return null
        if (draft.contentJson.isBlank()) {
            drafts.clear(key)
            return null
        }
        if (blockId != null) {
            val block = _state.value.blocks.firstOrNull { it.id == blockId }
            val same = block != null && runCatching {
                ContentFlatten.parseContent(block.contentJson) == ContentFlatten.parseContent(draft.contentJson)
            }.getOrDefault(false)
            if (same) {
                drafts.clear(key)
                return null
            }
        }
        return draft
    }

    /**
     * 编辑器每次（防抖后）上报最新内容。先更新内存里的待写草稿，再在 IO 上落盘；
     * 配置变更时 ViewModel 不销毁，进程被杀时由 [flushEditorDraft] 兜底。
     */
    fun onEditorDraftChanged(draft: EditorDraft) {
        if (_state.value.isRead || _state.value.selectMode) return
        val key = draftKeyFor()
        pendingDraft = draft
        pendingDraftKey = key
        _state.value = _state.value.copy(editorDraft = draft)
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(DRAFT_SAVE_DEBOUNCE_MS)
            drafts.save(key, draft)
        }
    }

    /** Activity 进入后台前把内存里的最新草稿同步落盘（进程随时可能被回收）。 */
    fun flushEditorDraft() {
        val draft = pendingDraft ?: return
        val key = pendingDraftKey ?: return
        draftSaveJob?.cancel()
        drafts.save(key, draft)
    }

    /** 保存 / 放弃 / 空内容退出时清掉当前草稿。 */
    private fun clearEditorDraft(key: String = draftKeyFor()) {
        draftSaveJob?.cancel()
        draftSaveJob = null
        pendingDraft = null
        pendingDraftKey = null
        drafts.clear(key)
        if (_state.value.editorDraft != null) {
            _state.value = _state.value.copy(editorDraft = null)
        }
    }

    override fun onCleared() {
        draftSaveJob?.cancel()
        // 兜底：极端情况下 ON_STOP 没跑到；写文件很小，阻塞一下换不丢稿。
        runCatching {
            val draft = pendingDraft
            val key = pendingDraftKey
            if (draft != null && key != null) drafts.save(key, draft)
        }
        super.onCleared()
    }
    /**
     * 重新从库里读这一天（从别处返回、或收到"数据变过"的通知时）。
     *
     * **编辑 / 多选态只换数据，不动形态**：`reload(showRead = true)` 会把 `isRead` 复位成阅读态、
     * 清空 `editingIndex` 与选中集合 —— iOS 的 `.diaryVersionChanged → load()` 就是这么干的，
     * 结果是"正在写的内容被写库通知踢掉"（规范 §1 与本页不变量 7：**这个坑不迁移**）。
     * 保存 / 删除走的仍是 `reload()`（那两处本来就要回到阅读态）。
     */
    fun refresh() {
        val keepMode = !_state.value.isRead || _state.value.selectMode
        viewModelScope.launch { reload(showRead = !keepMode) }
    }

    private suspend fun reload(showRead: Boolean = true) {
        val key = _state.value.actualDayKey.ifEmpty { _state.value.dayKey }
        val diary = runCatching { container.repository.getDiaryByDay(key) }
            .onFailure { Log.db.w("读取 $key 失败：${it.message}") }
            .getOrNull()
        val blocks = if (diary == null) {
            emptyList()
        } else {
            runCatching { container.repository.getBlocks(diary.id) }
                .onFailure { Log.db.w("读取片段失败：${it.message}") }
                .getOrDefault(emptyList())
        }
        _state.value = _state.value.copy(
            blocks = blocks,
            isRead = if (showRead) true else _state.value.isRead,
            editingIndex = if (showRead) null else _state.value.editingIndex,
            selectMode = if (showRead) false else _state.value.selectMode,
            selectedIds = if (showRead) emptySet() else _state.value.selectedIds,
        )
        if (showRead) recomputeHits()
    }

    // ------------------------------------------------------------------ 模式切换

    /** 点"写点什么"：新建片段。历史日期不允许（与 iOS 一致）。 */
    fun enterWrite() {
        if (!_state.value.canEditToday) {
            _state.value = _state.value.copy(alert = DiaryAlert.HistoryNoAdd)
            return
        }
        val draft = loadDraftFor(_state.value.actualDayKey, null)
        _state.value = _state.value.copy(
            loadParts = emptyList(),
            editorDraft = draft,
            // 新建片段没有"原文"可回退；不清空的话，放弃修改会恢复上一次编辑的片段
            loadToken = _state.value.loadToken + 1,
            isRead = false,
            editingIndex = null,
            location = null,
            locating = false,
            startTimeUtc = System.currentTimeMillis(),
            selectedIds = emptySet(),
            selectMode = false,
        )
        beginLocateIfNeeded()
    }

    /** 点某个片段进编辑。历史日期要 `allow_history_edit`。 */
    fun enterEditBlock(index: Int) {
        val current = _state.value
        if (index !in current.blocks.indices) return
        if (!current.canEditToday && !current.settings.allowHistoryEdit) {
            _state.value = current.copy(alert = DiaryAlert.ReadOnly)
            return
        }
        val block = current.blocks[index]
        val parts = ContentFlatten.parseContent(block.contentJson)
        val draft = loadDraftFor(current.actualDayKey, block.id)
        _state.value = current.copy(
            editingIndex = index,
            loadParts = parts,
            editorDraft = draft,
            loadToken = current.loadToken + 1,
            isRead = false,
            startTimeUtc = block.startTimeUtc,
            // 位置按这个片段**记录时**的样子重建，绝不重新获取
            location = locationSnapshotOf(block),
            locating = false,
            selectMode = false,
            selectedIds = emptySet(),
        )
    }

    /** 长按某个片段进入多选。 */
    fun enterSelect(blockId: Long) {
        val current = _state.value
        if (!current.canEditToday && !current.settings.allowHistoryEdit) {
            _state.value = current.copy(alert = DiaryAlert.ReadOnly)
            return
        }
        _state.value = current.copy(selectMode = true, selectedIds = current.selectedIds + blockId)
    }

    fun toggleSelection(blockId: Long) {
        val selected = _state.value.selectedIds
        _state.value = _state.value.copy(
            selectedIds = if (blockId in selected) selected - blockId else selected + blockId,
        )
    }

    /** 多选态下点"删除"：先确认。 */
    fun confirmDeleteSelected() {
        val count = _state.value.selectedIds.size
        if (count == 0) return
        _state.value = _state.value.copy(alert = DiaryAlert.DeleteConfirm(count))
    }

    fun performDeleteSelected() {
        val ids = _state.value.selectedIds.toList()
        _state.value = _state.value.copy(alert = null)
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { container.repository.deleteBlocks(ids) }
                .onFailure { Log.editor.e("删除片段失败：${it.message}") }
            reload()
        }
    }

    /**
     * 返回键 / 左上角返回。三态的优先级与 iOS `handleBack` 一致：
     *
     * 1. **多选** → 退出多选，页面不关；
     * 2. **编辑且编辑器非空** → 先问「退出编辑」；
     * 3. **编辑且编辑器为空** → 直接回本页的阅读态（**不关页**，iOS E1）；
     * 4. 阅读 → 关闭整页。
     *
     * 第 3 条原先写成了 `close()`：在编辑页按一次返回会直接回首页，
     * 用户看到的是"这一天整个被关掉了"，而不是"回到刚才读的那一页"（真机实测）。
     */
    fun handleBack(editorHasContent: Boolean) {
        val current = _state.value
        if (current.selectMode) {
            _state.value = current.copy(selectMode = false, selectedIds = emptySet())
            return
        }
        if (!current.isRead) {
            if (editorHasContent) {
                _state.value = current.copy(alert = DiaryAlert.ExitConfirm)
            } else {
                showRead()
            }
            return
        }
        close()
    }

    /**
     * 回到本页的阅读态（**不关页**）。对应 iOS 的 `showRead()`
     * （`DiaryViewModel.swift:216-227`）：退出编辑 / 放弃修改后先看到刚写的内容。
     */
    fun showRead() {
        clearEditorDraft()
        _state.value = _state.value.copy(
            isRead = true,
            editingIndex = null,
            selectMode = false,
            selectedIds = emptySet(),
            alert = null,
        )
    }

    /** 顶栏"放弃修改"。 */
    fun confirmDiscard(editorHasContent: Boolean) {
        if (!editorHasContent) return
        _state.value = _state.value.copy(alert = DiaryAlert.DiscardConfirm)
    }

    fun performDiscardEditing() {
        // 回到"进入编辑时"的内容：把原文重新推给编辑器
        clearEditorDraft()
        _state.value = _state.value.copy(
            alert = null,
            loadToken = _state.value.loadToken + 1,
        )
    }

    fun close() {
        _state.value = _state.value.copy(requestDismiss = true)
    }

    fun consumeDismiss() {
        _state.value = _state.value.copy(requestDismiss = false)
    }

    fun consumeAlert() {
        _state.value = _state.value.copy(alert = null)
    }

    fun consumeShareImage() {
        _state.value = _state.value.copy(shareImageFile = null)
    }

    // ------------------------------------------------------------------ 保存

    /** 编辑器点了保存。`payload.parts` 已经是编辑器的最终内容。 */
    fun save(payload: EditorSavePayload) {
        val current = _state.value
        if (payload.parts.isEmpty()) {
            // 空内容 = 什么都没写：新建时直接退出，编辑已有片段时回到阅读态
            clearEditorDraft()
            if (current.isEditingExistingBlock || current.blocks.isNotEmpty()) {
                _state.value = current.copy(isRead = true, editingIndex = null)
            } else {
                close()
            }
            return
        }
        val contentJson = ContentFlatten.serializeContent(payload.parts)
        val index = current.editingIndex
        if (index != null && index in current.blocks.indices) {
            val block = current.blocks[index]
            viewModelScope.launch {
                runCatching {
                    container.repository.updateBlockContent(
                        blockId = block.id,
                        contentJson = contentJson,
                        // 位置只可能改精度与由此派生的文案；坐标列不动
                        locText = current.location?.locText ?: block.locText,
                        locPrecision = current.location?.locPrecision ?: block.locPrecision,
                    )
                }.onFailure {
                    Log.editor.e("保存片段失败：${it.message}")
                    _state.value = _state.value.copy(alert = DiaryAlert.SaveFailed)
                    return@launch
                }
                clearEditorDraft()
                reload()
            }
            return
        }

        // ---- 新建片段：D3「未获取到位置」→ D4「跨日」→ 落库（iOS `saveEditor` 同一顺序）----
        // 位置整组在**这一刻**冻结（文案 / 档位 / 坐标 / 地区）：两个弹窗期间用户还能
        // 回去点「显示精度」，那不该改动这一次的写入内容。
        val snapshot = current.location
        val pending = PendingNewBlock(
            contentJson = contentJson,
            startTimeUtc = payload.startTimeUtc,
            locText = snapshot?.locText.orEmpty(),
            locPrecision = snapshot?.locPrecision ?: LocPrecision.none,
            latitude = snapshot?.latitude ?: 0.0,
            longitude = snapshot?.longitude ?: 0.0,
            region = snapshot?.region ?: EMPTY_REGION,
        )
        // 已经点过保存、弹窗还开着时再点一次：用**新内容**刷新待写入项，不叠加弹窗。
        _state.value = current.copy(pendingSave = pending)
        if (current.location == null && current.settings.autoLoc) {
            _state.value = _state.value.copy(alert = DiaryAlert.LocationMissing)
            return
        }
        promptCrossDayOrInsert()
    }

    /**
     * 「未获取到位置」的确认（D3）：**仍然保存**。
     *
     * 之后**延迟 0.3 秒**再走跨日判定 —— iOS `DiaryViewModel.swift:251-264` 就是
     * `Task.sleep(0.3)`：两个弹窗会前后脚弹出，不留这点时间前一个还没收干净。
     */
    fun confirmSaveAnyway() {
        if (_state.value.alert !is DiaryAlert.LocationMissing) return
        _state.value = _state.value.copy(alert = null)
        viewModelScope.launch {
            delay(CROSS_DAY_PROMPT_DELAY_MS)
            promptCrossDayOrInsert()
        }
    }

    /** 取消这次保存（两个确认弹窗共用的"取消"）。 */
    fun cancelPendingSave() {
        _state.value = _state.value.copy(alert = null, pendingSave = null)
    }

    /** 跨日确认后继续（内容来自 [State.pendingSave]）。 */
    fun confirmCrossDay() {
        val alert = _state.value.alert as? DiaryAlert.CrossDay ?: return
        val pending = _state.value.pendingSave ?: return
        insertBlock(alert.dayKey, pending)
    }

    /**
     * 跨日弹窗的判定（D4）：内容会落到**哪一天**由 `start_time_utc` + `dayStartHour` 算，
     * 与"今天"不是同一天就先问一句（iOS `finishNewBlockSave`）。
     */
    private fun promptCrossDayOrInsert() {
        val current = _state.value
        val pending = current.pendingSave ?: return
        val dayKey = DateUtil.dayKeyForUtc(pending.startTimeUtc, current.settings.dayStartHour)
        if (dayKey != current.todayKey) {
            _state.value = current.copy(alert = DiaryAlert.CrossDay(dayKey))
            return
        }
        insertBlock(dayKey, pending)
    }

    /**
     * 真正写库。
     *
     * 已有日记 → `addBlockToDiary`；没有则 `addBlock`（它会顺带建"这一天"、写
     * `diary_flag` 并重算检索文本）。位置取的是**点保存那一刻**冻结的快照，
     * 与 iOS 的 `saveByDayKey` 一致。
     */
    private fun insertBlock(dayKey: String, pending: PendingNewBlock) {
        val current = _state.value
        val dayStartHour = current.settings.dayStartHour
        // 弹窗一确认，待写入项就作数了：留着它的话，保存失败再点一次会拿旧内容重来
        _state.value = _state.value.copy(alert = null, pendingSave = null)
        viewModelScope.launch {
            runCatching {
                val existing = container.repository.getDiaryByDay(dayKey)
                if (existing != null) {
                    container.repository.addBlockToDiary(
                        diaryId = existing.id,
                        startTimeUtc = pending.startTimeUtc,
                        locText = pending.locText,
                        latitude = pending.latitude,
                        longitude = pending.longitude,
                        locPrecision = pending.locPrecision,
                        region = pending.region,
                        contentJson = pending.contentJson,
                    )
                } else {
                    container.repository.addBlock(
                        startTimeUtc = pending.startTimeUtc,
                        locText = pending.locText,
                        latitude = pending.latitude,
                        longitude = pending.longitude,
                        locPrecision = pending.locPrecision,
                        region = pending.region,
                        contentJson = pending.contentJson,
                        dayStartHour = dayStartHour,
                    )
                }
            }.onFailure {
                Log.editor.e("保存失败：${it.message}")
                _state.value = _state.value.copy(alert = DiaryAlert.SaveFailed)
                return@launch
            }
            // 落到了别的 day_key（跨日）：页面要跟着去看那一天，否则用户眼前还是原来那一天，
            // 而刚写的内容在别处。`todayKey` 也跟着刷新，"能不能再写一条"以当下为准。
            _state.value = _state.value.copy(
                actualDayKey = dayKey,
                todayKey = DateUtil.dayKeyForUtc(System.currentTimeMillis(), dayStartHour),
            )
            clearEditorDraft()
            reload()
        }
    }

    // ------------------------------------------------------------------ 待办

    /** 勾选/取消一个待办项（直接改库，不经过编辑器）。 */
    fun toggleTodo(blockId: Long, partIndex: Int, itemIndex: Int) {
        val block = _state.value.blocks.firstOrNull { it.id == blockId } ?: return
        val parts = ContentFlatten.parseContent(block.contentJson).toMutableList()
        if (partIndex !in parts.indices) return
        val part = parts[partIndex]
        val done = (part.done ?: List(part.items?.size ?: 0) { false }).toMutableList()
        if (itemIndex !in done.indices) return
        done[itemIndex] = !done[itemIndex]
        parts[partIndex] = part.copy(done = done)
        val json = ContentFlatten.serializeContent(parts)
        viewModelScope.launch {
            runCatching { container.repository.updateBlockContent(blockId = blockId, contentJson = json) }
                .onFailure { Log.editor.e("切换待办失败：${it.message}") }
            reload()
        }
    }

    // ------------------------------------------------------------------ 页内搜索

    fun setSearchVisible(visible: Boolean) {
        _state.value = _state.value.copy(
            showSearch = visible,
            searchText = if (visible) _state.value.searchText else "",
            hits = if (visible) _state.value.hits else emptyList(),
            hitIndex = 0,
        )
    }

    /** 输入变化 → 150ms 防抖后算命中（与 iOS 一致）。 */
    fun onSearchTextChange(value: String) {
        _state.value = _state.value.copy(searchText = value)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            recomputeHits()
        }
    }

    fun stepHit(delta: Int) {
        val hits = _state.value.hits
        if (hits.isEmpty()) return
        val index = ((_state.value.hitIndex + delta) % hits.size + hits.size) % hits.size
        _state.value = _state.value.copy(hitIndex = index, scrollTargetBlockId = hits[index].blockId)
    }

    fun consumeScrollTarget() {
        _state.value = _state.value.copy(scrollTargetBlockId = null)
    }

    /**
     * 重算命中。**与阅读页的高亮共用 [DiarySearch.findMatches]**（规范 §2.7：
     * iOS 那种"计数一套、高亮另一套"会让跨 chunk 的关键词只计数不高亮）。
     *
     * 命中数变化后下标要收回界内：删掉片段 / 改关键词后，`hitIndex` 可能指着旧位置。
     */
    private fun recomputeHits() {
        val keyword = _state.value.searchText
        if (keyword.isBlank()) {
            _state.value = _state.value.copy(hits = emptyList(), hitIndex = 0, scrollTargetBlockId = null)
            return
        }
        val found = mutableListOf<SearchHit>()
        for (block in _state.value.blocks) {
            val parts = ContentFlatten.parseContent(block.contentJson)
            DiarySearch.findMatches(parts, keyword).forEach { match ->
                found.add(
                    SearchHit(
                        blockId = block.id,
                        partIndex = match.partIndex,
                        itemIndex = match.itemIndex,
                        start = match.start,
                        end = match.end,
                    ),
                )
            }
        }
        val index = _state.value.hitIndex.coerceIn(0, (found.size - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(hits = found, hitIndex = index)
    }

    // ------------------------------------------------------------------ 位置

    private fun beginLocateIfNeeded() {
        val current = _state.value
        if (!current.settings.autoLoc || current.location != null) return
        beginLocate()
    }

    /**
     * 取一次当前位置。**只有新建片段**能走到这里。
     *
     * 未授权时**不在这里申请权限**（Android 的申请必须从一个 Activity 结果通道发起，
     * 见 `DiaryPageScreen` 的 `requestLocation`）：只把 `locating` 落回 false，
     * 于是地点胶囊停在「重新获取位置」，用户点它就走申请 + 复取的链路。
     * 这与 iOS `beginLocate()` 的"申请 → 600ms 后复查"是同一件事的两种平台写法。
     */
    fun beginLocate() {
        val current = _state.value
        if (current.isEditingExistingBlock) return
        if (!container.locationService.isAuthorized()) {
            _state.value = current.copy(locating = false)
            return
        }
        _state.value = current.copy(locating = true)
        viewModelScope.launch {
            val settings = _state.value.settings
            val fix = container.locationService.currentLocation()
            if (fix == null) {
                _state.value = _state.value.copy(locating = false)
                return@launch
            }
            val placemark = container.locationService.reverseGeocode(
                LocationFix(fix.latitude, fix.longitude),
                settings.appLanguage,
            )
            val snapshot = container.locationService.snapshotFor(
                fix = fix,
                placemark = placemark,
                appLanguage = settings.appLanguage,
                // 上一次手动选的精度在这里生效：只会让这条记录更粗，不会顶穿权限上限
                preferred = settings.defaultLocPrecision,
            )
            _state.value = _state.value.copy(location = snapshot, locating = false)
        }
    }

    /**
     * 「重新获取位置」（地点菜单里的那一项，iOS `refreshLocation()`）：
     * 丢掉当前这份快照再取一次 —— 例如刚才是靠缓存点落下的，用户想拿一个更准的。
     */
    fun refreshLocation() {
        if (_state.value.isEditingExistingBlock) return
        _state.value = _state.value.copy(location = null)
        beginLocate()
    }

    /**
     * 回到前台时补一次定位。
     *
     * 只在**新建片段 + `auto_loc` 开着 + 还没有位置 + 当前没有正在取的请求**时才补
     * （判据是纯函数 [LocationRules.shouldRetryOnForeground]）：用户可能在第一次取点失败
     * （刚下地铁 / 刚开定位开关）之后把应用切出去又切回来，这时界面上的「重新获取位置」
     * 还在，但没有人会替他再点一次 —— 这正是"明明开了权限却经常拿不到位置"的来源之一。
     */
    fun retryLocateOnForeground() {
        val current = _state.value
        val shouldRetry = LocationRules.shouldRetryOnForeground(
            isRead = current.isRead,
            isEditingExistingBlock = current.isEditingExistingBlock,
            autoLoc = current.settings.autoLoc,
            hasLocation = current.location != null,
            locating = current.locating,
        )
        if (!shouldRetry) return
        beginLocate()
    }

    /**
     * 调整显示精度：只改文案与档位，坐标与地区列不动。
     *
     * 顺手把这次的选择记进 `default_loc_precision`（iOS §七）：下一条新日记按
     * `min(记住的, 权限上限)` 记录，用户不必每次重选。与 iOS 一样**只改这一个字段**
     * 再落盘 —— 整份写回会把别处刚改过的设置项一起顶掉。
     */
    fun applyPrecision(precision: String) {
        val snapshot = _state.value.location ?: return
        val remembered = container.settings.load().copy(defaultLocPrecision = precision)
        container.settings.save(remembered)
        _state.value = _state.value.copy(
            location = snapshot.copy(
                locPrecision = precision,
                locText = LocationRules.textForPrecision(snapshot, precision),
            ),
            settings = remembered,
        )
    }

    // ------------------------------------------------------------------ 分享

    /**
     * 点顶栏「分享」：**一步**弹出分享面板，渲染在面板里进行（规范 §4.1）。
     *
     * iOS 的做法是"先拉起面板（立刻有反馈）→ 后台渲染 → 预览替换进度圈"
     * （`DiaryViewModel.swift:584-593`）。因此这里只把面板打开并置 `rendering`，
     * 渲染在 [shareDiary] 里继续。面板已开时重复点击直接返回（不重渲染，预览不会闪回进度圈）。
     */
    fun openSharePanel() {
        if (_state.value.sharePanelVisible) return
        _state.value = _state.value.copy(
            sharePanelVisible = true,
            shareImageFile = null,
            sharing = true,
        )
    }

    /** 关面板：顺手清掉上一次的分享图（规范 §4.2）。 */
    fun dismissSharePanel() {
        _state.value = _state.value.copy(sharePanelVisible = false, sharing = false, shareImageFile = null)
        shareCleanupJob?.cancel()
        shareCleanupJob = viewModelScope.launch {
            withContext(Dispatchers.IO) { container.imageShareRenderer.clearShareDir() }
        }
    }

    /**
     * 生成分享长图（IO 上渲染）。
     *
     * - 渲染中：面板里是进度圈（`sharing = true`）；
     * - 渲染就位：`shareImageFile` 有值，预览出现、两个动作可用；
     * - 渲染失败 / 这一天没有片段：**关面板** + 一条「分享失败」（规范 §4.1 最后一步）。
     *
     * 文案（日期 / 星期 / 品牌 / 起始行）由宿主用 `DateFormats` 备好 —— 这一层不碰资源，
     * 与其它"文案由界面层解析"的约定一致。`showTime` 就是 `auto_time`：
     * 关掉时头部起始行与每条记录的时间都不画（iOS `render(showTime:)` 同一语义）。
     */
    fun shareDiary(
        isDark: Boolean,
        showTime: Boolean,
        textsFor: (dayKey: String) -> com.cov.justdiary.data.ShareLayout.ShareTexts,
        timeLabel: (Long) -> String,
    ) {
        val dayKey = _state.value.actualDayKey
        // 分享图上的地点**只从记录的地区列重建**，并按设置里的上限（`share_loc_precision`）
        // 降级：精确地点与街道永远进不了分享图，与记录时的精度无关。
        // 上限直接读当前设置，而不是这份 VM 手里的快照 —— 分享精度不影响任何常驻界面，
        // 改它时没必要广播刷新，那一份快照可能是旧的（iOS 同此）。
        val cap = container.settings.load().shareLocPrecision
        val blocks = _state.value.blocks.map { block ->
            com.cov.justdiary.data.ShareLayout.ShareBlock(
                time = block.startTimeUtc,
                location = LocationResolver.shareText(block.locPrecision, regionOf(block), cap),
                parts = ContentFlatten.parseContent(block.contentJson),
            )
        }
        if (blocks.isEmpty()) {
            // 空的一天：iOS 只画品牌页眉 + 页脚；这边的渲染器要求至少一个片段，
            // 因此按"分享失败"处理并在界面上说清楚（不是静默什么都不发生）
            _state.value = _state.value.copy(
                sharePanelVisible = false,
                sharing = false,
                alert = DiaryAlert.ShareFailed,
            )
            return
        }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    container.imageShareRenderer.renderToFile(
                        dayKey = dayKey,
                        texts = textsFor(dayKey),
                        blocks = blocks,
                        isDark = isDark,
                        showTime = showTime,
                        timeLabel = timeLabel,
                    )
                }.onFailure { Log.backup.e("渲染分享图失败：${it.message}") }.getOrNull()
            }
            _state.value = _state.value.copy(
                sharing = false,
                shareImageFile = file,
                // 渲染失败：关面板 + 一条提示（iOS D8 同此）
                sharePanelVisible = if (file == null) false else _state.value.sharePanelVisible,
                alert = if (file == null) DiaryAlert.ShareFailed else _state.value.alert,
            )
        }
    }

    /**
     * 把当前分享图写进相册（`Pictures/JustDiary`）。
     *
     * 成功后给一条「已保存到相册」的一次性消息（[State.message]）。
     */
    fun saveShareToGallery() {
        val file = _state.value.shareImageFile ?: return
        val name = file.name
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { com.cov.justdiary.service.GallerySaver.saveToGallery(context(), file, name) }
                    .onFailure { Log.backup.e("保存到相册失败：${it.message}") }
                    .getOrNull()
            }
            _state.value = _state.value.copy(
                message = if (uri != null) DiaryMessage.SavedToGallery else DiaryMessage.SaveToGalleryFailed,
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun context(): android.content.Context = container.appContext

    // ------------------------------------------------------------------ 图片

    /** 插入图片：落盘到 `images/`，返回可写进段落的 src。 */
    suspend fun importImage(uri: android.net.Uri): String? =
        container.mediaStore.importImage(uri)

    /** 片段自己的地区列（编辑态重建快照、分享重建地址都用它）。 */
    private fun regionOf(block: EditBlock): LocRegion = LocRegion(
        country = block.country,
        countryCode = block.countryCode,
        region1 = block.region1,
        region2 = block.region2,
        region3 = block.region3,
        locQuality = block.locQuality,
    )

    private fun locationSnapshotOf(block: EditBlock): LocationRules.LocationSnapshot? {
        val precision = block.locPrecision.ifEmpty { LocPrecision.none }
        if (block.locText.isEmpty() && precision == LocPrecision.none) return null
        return LocationRules.LocationSnapshot(
            latitude = block.latitude,
            longitude = block.longitude,
            locText = block.locText,
            locPrecision = precision,
            region = regionOf(block),
            // 从库里重建的快照：没有地理编码结果（`placemark == null`），因此菜单由
            // 地区列决定、也不受当前权限约束；上限这一格用不到，给最保守的省份。
            maxPrecision = LocPrecision.province,
            // 记录时的档位与原文都取库里的值：回到记录档位时原样恢复
            recordedPrecision = precision,
            recordedText = block.locText,
        )
    }

    companion object {
        /** 页内搜索的防抖（iOS 为 150ms）。 */
        const val SEARCH_DEBOUNCE_MS = 150L

        /** 「仍然保存」→ 跨日弹窗之间的停顿（iOS 是 `Task.sleep(0.3)`）。 */
        const val CROSS_DAY_PROMPT_DELAY_MS = 300L

        /** 草稿落盘防抖；编辑页每 250ms 最多写一次文件。 */
        const val DRAFT_SAVE_DEBOUNCE_MS = 250L

        /** 没有位置时写库的空地区（与 `LocQuality.none` 一起表达"无位置"）。 */
        private val EMPTY_REGION = LocRegion(
            country = "",
            countryCode = "",
            region1 = "",
            region2 = "",
            region3 = "",
            locQuality = LocQuality.none,
        )

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DiaryPageViewModel(container) }
        }
    }
}
