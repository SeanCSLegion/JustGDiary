package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.EditorDraft
import com.cov.justdiary.service.LocationRules
import java.io.File

/**
 * 日记页的 UI 状态与一次性事件。
 *
 * 从 [DiaryPageViewModel] 拆出：ViewModel 只保留状态机/协程，类型本身是纯数据，
 * 便于单独阅读与在 JVM 上构造（尤其是 [DiaryPageState] 的派生属性）。
 */
data class DiaryPageState(
    val dayKey: String = "",
    /** 实际写入的日期（跨日保存后可能与 [dayKey] 不同）。 */
    val actualDayKey: String = "",
    val blocks: List<EditBlock> = emptyList(),
    /** P2-6b：`blocks` 的 `content_json` 只解析一次，阅读页/搜索/编辑共用。 */
    val partsByBlock: Map<Long, List<ContentPart>> = emptyMap(),
    val isRead: Boolean = true,
    /** 正在编辑的片段下标；`null` 表示"新建片段"。 */
    val editingIndex: Int? = null,
    val selectMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    /**
     * 待删除的片段 id（确认框期间有效）。
     *
     * 读页的「删除」无障碍动作只针对一条，不应该先把界面切进"已选择 1 项"；
     * 多选删除也走这里，两种入口共用同一个确认框与执行路径。
     */
    val pendingDeleteIds: List<Long> = emptyList(),
    val location: LocationRules.LocationSnapshot? = null,
    val locating: Boolean = false,
    val startTimeUtc: Long = System.currentTimeMillis(),
    val settings: AppSettings = AppSettings(),
    /** 载入到编辑器里的段落（`loadToken` 变化时重新载入）。 */
    val loadParts: List<ContentPart> = emptyList(),
    val loadToken: Int = 0,
    /** 未保存草稿（P0-1）；非空时编辑器优先恢复它，而不是 [loadParts]。 */
    val editorDraft: EditorDraft? = null,
    val alert: DiaryPageAlert? = null,
    /** 宿主应当关闭本页。 */
    val requestDismiss: Boolean = false,
    /** 页内搜索（150ms 防抖后算命中）。 */
    val searchText: String = "",
    val showSearch: Boolean = false,
    val hits: List<DiaryPageSearchHit> = emptyList(),
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
    val message: DiaryPageMessage? = null,
    /** "今天"的 day_key（受 `dayStartHour` 影响）—— 决定能否新建片段。 */
    val todayKey: String = "",
    /**
     * 已经点过保存、正在等确认弹窗（未获取到位置 / 跨日）。**没有**它就等于没点过保存。
     *
     * 两个确认弹窗之间要交代的东西（内容 / 起始时刻 / 位置快照）全在这里，
     * 因此「仍然保存」之后不需要编辑器再回投一次内容 —— 它背后还活着，
     * 这期间用户改的任何一个字都不该悄悄进入这一次写入。
     */
    val pendingSave: DiaryPagePendingBlock? = null,
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
data class DiaryPagePendingBlock(
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
sealed interface DiaryPageMessage {
    /** 「已保存到相册」（`share_saved`）。 */
    data object SavedToGallery : DiaryPageMessage

    /** 「保存失败」（`share_save_failed`）。 */
    data object SaveToGalleryFailed : DiaryPageMessage

    /** 没有可用的分享应用（P2-6b）。 */
    data object NoShareApp : DiaryPageMessage

    /** 已把正文复制到剪贴板。 */
    data object TextCopied : DiaryPageMessage
}

/** 一次性的结果提示（文案由界面层解析，保持 ViewModel 与资源解耦）。 */
sealed interface DiaryPageAlert {
    /** 历史日期不能新增片段（`editor_history_no_add_*`）。 */
    data object HistoryNoAdd : DiaryPageAlert

    /** 不允许修改历史日记（`editor_readonly_*`）。 */
    data object ReadOnly : DiaryPageAlert

    /** 退出且有未保存内容（`editor_exit_*`）→ 确认后丢弃并关闭。 */
    data object ExitConfirm : DiaryPageAlert

    /** 主动"放弃修改"（`editor_discard_*`）。 */
    data object DiscardConfirm : DiaryPageAlert

    /** 跨日保存（`editor_cross_day_*`）。 */
    data class CrossDay(val dayKey: String) : DiaryPageAlert

    /**
     * 新建片段、`auto_loc` 开着却还没拿到位置（`editor_location_retry`：
     * 标题「未获取到位置」· 确认「仍然保存」）。
     *
     * 保存之后这个片段**再也补不上位置**（`LocationRules.canAddLocationAfterSave`），
     * 所以要先问一句 —— 与 iOS D3 同一处、同一组文案。
     */
    data object LocationMissing : DiaryPageAlert

    data object SaveFailed : DiaryPageAlert

    data class DeleteConfirm(val count: Int) : DiaryPageAlert

    data object ImageInsertFailed : DiaryPageAlert

    data object ShareFailed : DiaryPageAlert
}

/**
 * 页内搜索的一次命中。
 *
 * `itemIndex` 为 -1 表示命中在整段文本上，否则是列表 / 待办的第几项。
 * 命中由 [DiarySearch.findMatches] 算出 —— **与阅读页高亮是同一个函数**（规范 §2.7）。
 */
data class DiaryPageSearchHit(
    val blockId: Long,
    val partIndex: Int,
    val itemIndex: Int,
    val start: Int,
    val end: Int,
)

/** 没有位置时写库的空地区（与 `LocQuality.none` 一起表达"无位置"）。 */
internal val EMPTY_REGION = LocRegion(
    country = "",
    countryCode = "",
    region1 = "",
    region2 = "",
    region3 = "",
    locQuality = LocQuality.none,
)
