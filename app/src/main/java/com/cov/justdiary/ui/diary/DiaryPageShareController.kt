package com.cov.justdiary.ui.diary

import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.service.GallerySaver
import com.cov.justdiary.service.LocationResolver
import com.cov.justdiary.service.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分享状态机：面板开合、长图渲染、保存到相册。
 *
 * 从 [DiaryPageViewModel] 拆出：它只碰状态与渲染器，不参与编辑/搜索/定位。
 * `state` / `update` 两个函数由 ViewModel 注入，避免把整个 ViewModel 暴露给协程。
 */
internal class DiaryPageShareController(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val state: () -> DiaryPageState,
    private val update: (DiaryPageState) -> Unit,
) {

    /** 关闭分享面板后清缓存的作业（下一次分享前不必等它）。 */
    private var cleanupJob: Job? = null

    /**
     * 点顶栏「分享」：**一步**弹出分享面板，渲染在面板里进行（规范 §4.1）。
     *
     * 面板已开时重复点击直接返回，预览不会闪回进度圈。
     */
    fun openPanel() {
        if (state().sharePanelVisible) return
        cleanupJob?.cancel()
        update(
            state().copy(
                sharePanelVisible = true,
                shareImageFile = null,
                sharing = true,
            ),
        )
    }

    /**
     * 关面板：**延迟**清掉分享图。
     *
     * 接收方可能还在读 `content://`；30 秒足够一次系统分享把文件读完。
     */
    fun dismissPanel() {
        update(state().copy(sharePanelVisible = false, sharing = false, shareImageFile = null))
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            delay(DiaryPageViewModel.SHARE_CLEANUP_DELAY_MS)
            withContext(Dispatchers.IO) { container.imageShareRenderer.clearShareDir() }
        }
    }

    fun notifyNoShareApp() {
        update(state().copy(message = DiaryPageMessage.NoShareApp))
    }

    fun notifyTextCopied() {
        update(state().copy(message = DiaryPageMessage.TextCopied))
    }

    /**
     * 生成分享长图（IO 上渲染）。
     *
     * 文案由宿主用 `DateFormats` 备好；空的一天按分享失败处理并关面板。
     */
    fun shareDiary(
        isDark: Boolean,
        showTime: Boolean,
        textsFor: (dayKey: String) -> com.cov.justdiary.data.ShareLayout.ShareTexts,
        timeLabel: (Long) -> String,
    ) {
        val current = state()
        val dayKey = current.actualDayKey
        val cap = container.settings.load().shareLocPrecision
        val blocks = current.blocks.map { block ->
            com.cov.justdiary.data.ShareLayout.ShareBlock(
                time = block.startTimeUtc,
                location = LocationResolver.shareText(block.locPrecision, regionOf(block), cap),
                parts = current.partsByBlock[block.id]
                    ?: ContentFlatten.parseContent(block.contentJson),
            )
        }
        if (blocks.isEmpty()) {
            update(
                current.copy(
                    sharePanelVisible = false,
                    sharing = false,
                    alert = DiaryPageAlert.ShareFailed,
                ),
            )
            return
        }
        scope.launch {
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
            val latest = state()
            update(
                latest.copy(
                    sharing = false,
                    shareImageFile = file,
                    sharePanelVisible = if (file == null) false else latest.sharePanelVisible,
                    alert = if (file == null) DiaryPageAlert.ShareFailed else latest.alert,
                ),
            )
        }
    }

    /** 把当前分享图写进相册（`Pictures/JustDiary`）。 */
    fun saveToGallery() {
        val file = state().shareImageFile ?: return
        val name = file.name
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { GallerySaver.saveToGallery(container.appContext, file, name) }
                    .onFailure { Log.backup.e("保存到相册失败：${it.message}") }
                    .getOrNull()
            }
            update(
                state().copy(
                    message = if (uri != null) {
                        DiaryPageMessage.SavedToGallery
                    } else {
                        DiaryPageMessage.SaveToGalleryFailed
                    },
                ),
            )
        }
    }

    fun consumeMessage() {
        update(state().copy(message = null))
    }
}
