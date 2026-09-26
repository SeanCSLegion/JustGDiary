package com.cov.justdiary.data

/**
 * 日记页顶栏的三态与各动作的可用性。
 *
 * 这是一个**纯函数**（`State` → 顶栏状态），刻意不放进 Composable：
 * 顶栏的形态判断是日记页最容易做错的一处（阅读 / 编辑 / 多选是**三个**维度，
 * 不是"读了还是没读"两态），下沉之后可以在 JVM 上逐条钉死。
 *
 * 对应 [docs/android-design/04-diary.md] §2.2 与
 * [docs/android-design/08-diary-implementation-plan.md] §2。
 *
 * ## 三个维度的关系（这里是最容易写错的地方）
 *
 * - `isRead` 与 `editingIndex` 是同一件事的两面：`isRead = (editingIndex == null)`
 *   **只在"阅读 / 编辑"之间成立**；
 * - `selectMode` 是**第三个**维度 —— 多选态下 `isRead` 仍然可以是 `true`，
 *   所以不能写成 `if (isRead) 阅读 else 编辑`，那样多选态会被当成编辑态。
 * - 三者的优先级：**多选 > 编辑 > 阅读**。
 */
data class DiaryTopBarState(
    val mode: Mode,
    /** 只有"当天"才给「写日记」入口（历史日期只能改、不能新增）。 */
    val canWrite: Boolean,
    val doneCount: Int,
    val totalCount: Int,
    /** 搜索态：顶栏的放大镜是 active。 */
    val searchActive: Boolean,
    /** 多选态下选中了几项（标题里显示）。 */
    val selectedCount: Int,
) {
    enum class Mode { Read, Edit, Select }

    /** 多选态的删除键：一项都没选时禁用。 */
    val canDeleteSelected: Boolean get() = mode == Mode.Select && selectedCount > 0

    /** 编辑态：保存键恒可用（内容为空时保存 = 删除该片段，是 `ViewModel` 的既有语义）。 */
    val canSave: Boolean get() = mode == Mode.Edit

    /** 阅读态且当天才显示「写日记」。 */
    val showWrite: Boolean get() = mode == Mode.Read && canWrite

    /** 阅读态才显示分享与搜索。 */
    val showSearch: Boolean get() = mode == Mode.Read
    val showShare: Boolean get() = mode == Mode.Read

    /** 多选态的标题文案（「已选择 N 项」）；非多选态为 `null`。 */
    val selectTitleCount: Int? get() = if (mode == Mode.Select) selectedCount else null

    companion object {

        /**
         * 由页面状态算出顶栏形态。
         *
         * @param isRead   `true` = 没有正在编辑的片段
         * @param editingIndex 正在编辑的片段下标（`null` = 新建片段）
         * @param canEditToday  当天才为真（受"新一天的开始时间"影响）
         */
        fun of(
            isRead: Boolean,
            editingIndex: Int?,
            selectMode: Boolean,
            selectedIds: Set<Long>,
            canEditToday: Boolean,
            showSearch: Boolean,
            doneCount: Int = 0,
            totalCount: Int = 0,
        ): DiaryTopBarState {
            // 优先级：多选 > 编辑 > 阅读。
            // `selectMode` 与 `isRead` **不互斥** —— 多选是在阅读态里长出来的，
            // 所以必须先判多选。
            val mode = when {
                selectMode -> Mode.Select
                !isRead || editingIndex != null -> Mode.Edit
                else -> Mode.Read
            }
            return DiaryTopBarState(
                mode = mode,
                canWrite = canEditToday,
                doneCount = doneCount,
                totalCount = totalCount,
                // 只有阅读态的搜索才算"搜索中"：编辑态即便 showSearch 残留也不显示搜索栏
                searchActive = mode == Mode.Read && showSearch,
                selectedCount = selectedIds.size,
            )
        }
    }
}

/**
 * 阅读页「第 N / 共 M 个片段」的完成度（命中计数用）。
 *
 * 抽出来是因为它有两处容易反的边界：**没有任何片段**（分母 0）与
 * **命中下标越界**（删掉片段后 `hitIndex` 可能还指着旧位置）。
 */
object DiaryProgress {

    /** `hitIndex` 从 0 开始；返回从 1 开始的序号。越界或空集合返回 0。 */
    fun hitOrdinal(hitIndex: Int, hitCount: Int): Int =
        if (hitCount <= 0 || hitIndex !in 0 until hitCount) 0 else hitIndex + 1

    /** 待办完成度：`3/5`。空列表返回 `0/0`（界面据此不显示进度）。 */
    fun todoProgress(done: Int, total: Int): Pair<Int, Int> =
        done.coerceIn(0, total.coerceAtLeast(0)) to total.coerceAtLeast(0)

    /** 是否值得显示进度（没有任何待办时不显示，免得出现 `0/0`）。 */
    fun showTodoProgress(done: Int, total: Int): Boolean = total > 0 && done <= total
}
