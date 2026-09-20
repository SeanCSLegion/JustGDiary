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
import com.cov.justdiary.service.LocationFix
import com.cov.justdiary.service.LocationResolver
import com.cov.justdiary.service.LocationRules
import com.cov.justdiary.service.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
        /** "今天"的 day_key（受 `dayStartHour` 影响）—— 决定能否新建片段。 */
        val todayKey: String = "",
        /** 跨日确认时暂存待写入的内容（`json` + 起始时刻）。 */
        val pendingSave: Pair<String, Long>? = null,
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

        data object SaveFailed : DiaryAlert

        data class DeleteConfirm(val count: Int) : DiaryAlert

        data object ImageInsertFailed : DiaryAlert

        data object ShareFailed : DiaryAlert
    }

    /** 页内搜索的一次命中（与 iOS `SearchHit` 同构）。 */
    data class SearchHit(val blockId: Long, val partIndex: Int, val start: Int, val len: Int)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var searchJob: Job? = null

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

    /** 重新从库里读这一天（保存/删除之后，或从别处返回时）。 */
    fun refresh() {
        viewModelScope.launch { reload() }
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
        _state.value = _state.value.copy(
            loadParts = emptyList(),
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
        _state.value = current.copy(
            editingIndex = index,
            loadParts = parts,
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

    /** 返回键 / 左上角返回。有未保存内容时先确认。 */
    fun handleBack(editorHasContent: Boolean) {
        val current = _state.value
        if (current.selectMode) {
            _state.value = current.copy(selectMode = false, selectedIds = emptySet())
            return
        }
        if (!current.isRead && editorHasContent) {
            _state.value = current.copy(alert = DiaryAlert.ExitConfirm)
            return
        }
        close()
    }

    /** 顶栏"放弃修改"。 */
    fun confirmDiscard(editorHasContent: Boolean) {
        if (!editorHasContent) return
        _state.value = _state.value.copy(alert = DiaryAlert.DiscardConfirm)
    }

    fun performDiscardEditing() {
        // 回到"进入编辑时"的内容：把原文重新推给编辑器
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
                reload()
            }
            return
        }

        val dayKey = DateUtil.dayKeyForUtc(payload.startTimeUtc, current.settings.dayStartHour)
        if (dayKey != current.todayKey) {
            // 跨日：提醒用户这笔内容会落在哪一天（与 iOS 一致）
            _state.value = current.copy(
                alert = DiaryAlert.CrossDay(dayKey),
                pendingSave = contentJson to payload.startTimeUtc,
            )
            return
        }
        insertBlock(dayKey, payload.startTimeUtc, contentJson)
    }

    /** 跨日确认后继续（内容来自 [State.pendingSave]）。 */
    fun confirmCrossDay() {
        val alert = _state.value.alert as? DiaryAlert.CrossDay ?: return
        val pending = _state.value.pendingSave
        _state.value = _state.value.copy(alert = null, pendingSave = null)
        if (pending == null) return
        insertBlock(alert.dayKey, pending.second, pending.first)
    }

    /**
     * 真正写库。
     *
     * 已有日记 → `addBlockToDiary`；没有则 `addBlock`（它会顺带建"这一天"、写
     * `diary_flag` 并重算检索文本）。位置列只在**新建**时写入，与 iOS 的
     * `saveByDayKey` 一致。
     */
    private fun insertBlock(dayKey: String, startTimeUtc: Long, contentJson: String) {
        val current = _state.value
        val snapshot = current.location
        val region = snapshot?.region ?: LocRegion(
            country = "",
            countryCode = "",
            region1 = "",
            region2 = "",
            region3 = "",
            locQuality = LocQuality.none,
        )
        viewModelScope.launch {
            runCatching {
                val existing = container.repository.getDiaryByDay(dayKey)
                if (existing != null) {
                    container.repository.addBlockToDiary(
                        diaryId = existing.id,
                        startTimeUtc = startTimeUtc,
                        locText = snapshot?.locText.orEmpty(),
                        latitude = snapshot?.latitude ?: 0.0,
                        longitude = snapshot?.longitude ?: 0.0,
                        locPrecision = snapshot?.locPrecision ?: LocPrecision.none,
                        region = region,
                        contentJson = contentJson,
                    )
                } else {
                    container.repository.addBlock(
                        startTimeUtc = startTimeUtc,
                        locText = snapshot?.locText.orEmpty(),
                        latitude = snapshot?.latitude ?: 0.0,
                        longitude = snapshot?.longitude ?: 0.0,
                        locPrecision = snapshot?.locPrecision ?: LocPrecision.none,
                        region = region,
                        contentJson = contentJson,
                        dayStartHour = current.settings.dayStartHour,
                    )
                }
            }.onFailure {
                Log.editor.e("保存失败：${it.message}")
                _state.value = _state.value.copy(alert = DiaryAlert.SaveFailed)
                return@launch
            }
            _state.value = _state.value.copy(actualDayKey = dayKey)
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

    private fun recomputeHits() {
        val keyword = _state.value.searchText.trim()
        if (keyword.isEmpty()) {
            _state.value = _state.value.copy(hits = emptyList(), hitIndex = 0)
            return
        }
        val lower = keyword.lowercase()
        val found = mutableListOf<SearchHit>()
        for (block in _state.value.blocks) {
            ContentFlatten.parseContent(block.contentJson).forEachIndexed { partIndex, part ->
                val text = ContentFlatten.flattenPart(part).lowercase()
                var from = 0
                while (true) {
                    val at = text.indexOf(lower, from)
                    if (at < 0) break
                    found.add(SearchHit(block.id, partIndex, at, keyword.length))
                    from = at + lower.length
                    if (from >= text.length) break
                }
            }
        }
        _state.value = _state.value.copy(hits = found, hitIndex = 0)
    }

    // ------------------------------------------------------------------ 位置

    private fun beginLocateIfNeeded() {
        val current = _state.value
        if (!current.settings.autoLoc || current.location != null) return
        beginLocate()
    }

    /** 取一次当前位置。**只有新建片段**能走到这里。 */
    fun beginLocate() {
        val current = _state.value
        if (current.isEditingExistingBlock) return
        if (!container.locationService.isAuthorized()) {
            _state.value = current.copy(locating = false)
            return
        }
        _state.value = current.copy(locating = true)
        viewModelScope.launch {
            val fix = container.locationService.currentLocation()
            if (fix == null) {
                _state.value = _state.value.copy(locating = false)
                return@launch
            }
            val placemark = container.locationService.reverseGeocode(
                LocationFix(fix.latitude, fix.longitude),
                _state.value.settings.appLanguage,
            )
            val snapshot = container.locationService.snapshotFor(fix, placemark, _state.value.settings.appLanguage)
            _state.value = _state.value.copy(location = snapshot, locating = false)
        }
    }

    /** 调整显示精度：只改文案与档位，坐标与地区列不动。 */
    fun applyPrecision(precision: String) {
        val snapshot = _state.value.location ?: return
        _state.value = _state.value.copy(
            location = snapshot.copy(
                locPrecision = precision,
                locText = LocationRules.textForPrecision(snapshot, precision),
            ),
        )
    }

    // ------------------------------------------------------------------ 分享

    /** 生成分享长图（IO 上渲染），完成后由宿主分享。 */
    fun shareDiary(isDark: Boolean, dateText: String, startLine: String) {
        val dayKey = _state.value.actualDayKey
        val blocks = _state.value.blocks.map { block ->
            com.cov.justdiary.data.ShareLayout.ShareBlock(
                time = block.startTimeUtc,
                location = block.locText,
                parts = ContentFlatten.parseContent(block.contentJson),
            )
        }
        if (blocks.isEmpty()) {
            _state.value = _state.value.copy(alert = DiaryAlert.ShareFailed)
            return
        }
        viewModelScope.launch {
            val file = runCatching {
                container.imageShareRenderer.renderToFile(
                    dayKey = dayKey,
                    dateText = dateText,
                    startLine = startLine,
                    blocks = blocks,
                    isDark = isDark,
                )
            }.onFailure { Log.backup.e("渲染分享图失败：${it.message}") }.getOrNull()
            _state.value = _state.value.copy(
                shareImageFile = file,
                alert = if (file == null) DiaryAlert.ShareFailed else _state.value.alert,
            )
        }
    }

    // ------------------------------------------------------------------ 图片

    /** 插入图片：落盘到 `images/`，返回可写进段落的 src。 */
    suspend fun importImage(uri: android.net.Uri): String? =
        container.mediaStore.importImage(uri)

    private fun locationSnapshotOf(block: EditBlock): LocationRules.LocationSnapshot? {
        val precision = block.locPrecision.ifEmpty { LocPrecision.none }
        if (block.locText.isEmpty() && precision == LocPrecision.none) return null
        val region = LocRegion(
            country = block.country,
            countryCode = block.countryCode,
            region1 = block.region1,
            region2 = block.region2,
            region3 = block.region3,
            locQuality = block.locQuality,
        )
        return LocationRules.LocationSnapshot(
            latitude = block.latitude,
            longitude = block.longitude,
            locText = block.locText,
            locPrecision = precision,
            region = region,
            // 记录时的档位与原文都取库里的值：回到记录档位时原样恢复
            recordedPrecision = precision,
            recordedText = block.locText,
        )
    }

    companion object {
        /** 页内搜索的防抖（iOS 为 150ms）。 */
        const val SEARCH_DEBOUNCE_MS = 150L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DiaryPageViewModel(container) }
        }
    }
}
