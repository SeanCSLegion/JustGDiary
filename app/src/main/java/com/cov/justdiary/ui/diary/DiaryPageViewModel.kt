package com.cov.justdiary.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.service.EditorDraft
import com.cov.justdiary.service.LocationFix
import com.cov.justdiary.service.LocationRules
import com.cov.justdiary.service.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日记页状态。对应 iOS `ViewModels/DiaryViewModel.swift`（534 行，本页是全 app 最重的一块）。
 *
 * ## 一个页面、两种形态
 *
 * iOS 把"读"和"写"放在同一个 `DiaryPageView` 里，用 `isRead` 切换。Android 侧保持同样的模型：
 * [DiaryPageState.isRead] 为真时渲染 [DiaryReadScreen]，为假时渲染 [EditorScreen]，
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
 * | `onDismiss` 闭包 | [DiaryPageState.requestDismiss] 一次性标志，由宿主导航消费 |
 */
class DiaryPageViewModel(private val container: AppContainer) : ViewModel() {


    private val _state = MutableStateFlow(DiaryPageState())
    val state: StateFlow<DiaryPageState> = _state.asStateFlow()

    private val drafts = container.editorDrafts
    private var draftSaveJob: Job? = null
    private var pendingDraft: EditorDraft? = null
    private var pendingDraftKey: String? = null

    private var searchJob: Job? = null
    private var locateJob: Job? = null

    private val shareController by lazy {
        DiaryPageShareController(
            container = container,
            scope = viewModelScope,
            state = { _state.value },
            update = { _state.value = it },
        )
    }


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

    private fun draftKeyFor(state: DiaryPageState = _state.value): String {
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
            val cached = _state.value.partsByBlock[blockId]
            val same = block != null && runCatching {
                (cached ?: ContentFlatten.parseContent(block.contentJson)) ==
                    ContentFlatten.parseContent(draft.contentJson)
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val protected = drafts.referencedImageSources()
                container.repository.cleanupOrphanImages(protected)
            }.onFailure { Log.db.w("放弃草稿后清理孤儿图片失败：${it.message}") }
        }
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
        // P2-6b：JSON 解析放在 Default，且只解析一次；阅读页/页内搜索/编辑都读这份缓存。
        val partsByBlock = withContext(Dispatchers.Default) {
            blocks.associate { it.id to ContentFlatten.parseContent(it.contentJson) }
        }
        _state.value = _state.value.copy(
            blocks = blocks,
            partsByBlock = partsByBlock,
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
            _state.value = _state.value.copy(alert = DiaryPageAlert.HistoryNoAdd)
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
            _state.value = current.copy(alert = DiaryPageAlert.ReadOnly)
            return
        }
        val block = current.blocks[index]
        val parts = current.partsByBlock[block.id] ?: ContentFlatten.parseContent(block.contentJson)
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
            _state.value = current.copy(alert = DiaryPageAlert.ReadOnly)
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
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(
            alert = DiaryPageAlert.DeleteConfirm(ids.size),
            pendingDeleteIds = ids,
        )
    }

    /**
     * 读页卡片的「删除」无障碍动作：只删这一条（P1-13）。
     *
     * 不进入多选态 —— TalkBack 用户执行动作后应当直接看到确认框，而不是界面上多出
     * 一个看不见的选中项。
     */
    fun confirmDeleteBlock(blockId: Long) {
        val current = _state.value
        if (!current.canEditToday && !current.settings.allowHistoryEdit) {
            _state.value = current.copy(alert = DiaryPageAlert.ReadOnly)
            return
        }
        _state.value = current.copy(
            alert = DiaryPageAlert.DeleteConfirm(1),
            pendingDeleteIds = listOf(blockId),
        )
    }

    fun performDeleteSelected() {
        // 正常路径是 `confirmDeleteSelected` / `confirmDeleteBlock` 先填好 pending；
        // 直接调用（测试 / 旧的内部调用）时退回当前多选集合，保持原有语义。
        val ids = _state.value.pendingDeleteIds.ifEmpty { _state.value.selectedIds.toList() }
        _state.value = _state.value.copy(
            alert = null,
            pendingDeleteIds = emptyList(),
            selectMode = false,
            selectedIds = emptySet(),
        )
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
                _state.value = current.copy(alert = DiaryPageAlert.ExitConfirm)
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
        _state.value = _state.value.copy(alert = DiaryPageAlert.DiscardConfirm)
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
        _state.value = _state.value.copy(alert = null, pendingDeleteIds = emptyList())
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
                    _state.value = _state.value.copy(alert = DiaryPageAlert.SaveFailed)
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
        val pending = DiaryPagePendingBlock(
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
            _state.value = _state.value.copy(alert = DiaryPageAlert.LocationMissing)
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
        if (_state.value.alert !is DiaryPageAlert.LocationMissing) return
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

    /** 跨日确认后继续（内容来自 [DiaryPageState.pendingSave]）。 */
    fun confirmCrossDay() {
        val alert = _state.value.alert as? DiaryPageAlert.CrossDay ?: return
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
            _state.value = current.copy(alert = DiaryPageAlert.CrossDay(dayKey))
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
    private fun insertBlock(dayKey: String, pending: DiaryPagePendingBlock) {
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
                _state.value = _state.value.copy(alert = DiaryPageAlert.SaveFailed)
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
        val current = _state.value
        val block = current.blocks.firstOrNull { it.id == blockId } ?: return
        val parts = (current.partsByBlock[blockId]
            ?: ContentFlatten.parseContent(block.contentJson)).toMutableList()
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
    private suspend fun recomputeHits() {
        val snapshot = _state.value
        val keyword = snapshot.searchText
        if (keyword.isBlank()) {
            _state.value = _state.value.copy(hits = emptyList(), hitIndex = 0, scrollTargetBlockId = null)
            return
        }
        // P2-6b：JSON 解析 + findMatches 放 Default；长日记不会再在每次防抖后卡主线程 1–2 帧。
        val blocks = snapshot.blocks
        val found = withContext(Dispatchers.Default) {
            buildList {
                for (block in blocks) {
                    val parts = snapshot.partsByBlock[block.id]
                        ?: ContentFlatten.parseContent(block.contentJson)
                    DiarySearch.findMatches(parts, keyword).forEach { match ->
                        add(
                            DiaryPageSearchHit(
                                blockId = block.id,
                                partIndex = match.partIndex,
                                itemIndex = match.itemIndex,
                                start = match.start,
                                end = match.end,
                            ),
                        )
                    }
                }
            }
        }
        // 计算期间用户又改了词：这一轮结果已经过期，直接丢弃（与搜索 Job 取消同义）。
        if (_state.value.searchText != keyword) return
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
        if (locateJob?.isActive == true || current.locating) return
        if (!container.locationService.isAuthorized()) {
            _state.value = current.copy(locating = false)
            return
        }
        _state.value = current.copy(locating = true)
        locateJob = viewModelScope.launch {
            try {
                val settings = _state.value.settings
                var fix = container.locationService.currentLocation(timeoutMillis = 2_500L)
                if (fix == null) {
                    delay(350)
                    fix = container.locationService.currentLocation(timeoutMillis = 2_500L)
                }
                if (fix != null) {
                    val placemark = container.locationService.reverseGeocode(
                        LocationFix(fix.latitude, fix.longitude), settings.appLanguage,
                    )
                    val snapshot = container.locationService.snapshotFor(
                        fix = fix,
                        placemark = placemark,
                        appLanguage = settings.appLanguage,
                        preferred = settings.defaultLocPrecision,
                    )
                    _state.value = _state.value.copy(location = snapshot, locating = false)
                } else {
                    _state.value = _state.value.copy(locating = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.location.w("编辑页定位失败：${error.message}")
                _state.value = _state.value.copy(locating = false)
            } finally {
                locateJob = null
            }
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


    private fun context(): android.content.Context = container.appContext

    // ------------------------------------------------------------------ 图片

    /** 插入图片：落盘到 `images/`，返回可写进段落的 src。 */
    suspend fun importImage(uri: android.net.Uri): String? =
        container.mediaStore.importImage(uri)

    // ------------------------------------------------------------------ 分享

    fun openSharePanel() = shareController.openPanel()

    fun dismissSharePanel() = shareController.dismissPanel()

    fun notifyNoShareApp() = shareController.notifyNoShareApp()

    fun notifyTextCopied() = shareController.notifyTextCopied()

    fun shareDiary(
        isDark: Boolean,
        showTime: Boolean,
        textsFor: (dayKey: String) -> com.cov.justdiary.data.ShareLayout.ShareTexts,
        timeLabel: (Long) -> String,
    ) = shareController.shareDiary(isDark, showTime, textsFor, timeLabel)

    fun saveShareToGallery() = shareController.saveToGallery()

    fun consumeMessage() = shareController.consumeMessage()

    companion object {
        /** 页内搜索的防抖（iOS 为 150ms）。 */
        const val SEARCH_DEBOUNCE_MS = 150L

        /** 分享面板关闭后延迟删除临时文件，避免接收方还在读。 */
        const val SHARE_CLEANUP_DELAY_MS = 30_000L

        /** 「仍然保存」→ 跨日弹窗之间的停顿（iOS 是 `Task.sleep(0.3)`）。 */
        const val CROSS_DAY_PROMPT_DELAY_MS = 300L

        /** 草稿落盘防抖；编辑页每 250ms 最多写一次文件。 */
        const val DRAFT_SAVE_DEBOUNCE_MS = 250L


        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DiaryPageViewModel(container) }
        }
    }
}
