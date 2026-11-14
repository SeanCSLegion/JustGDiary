package com.cov.justdiary.ui.diary

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import com.cov.justdiary.R
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 分享面板（规范 `docs/android-design/04-diary.md` §4）。
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │              分享长图                 │
 * │  JustDiary-2026-09-08.png · 1440×1680 │
 * │  ┌──────────────────┐                │
 * │  │   长图预览（200dp） │                │
 * │  └──────────────────┘                │
 * │  [  分享…  ] [ 保存到相册 ]            │
 * └──────────────────────────────────────┘
 * ```
 *
 * 与 iOS 的差异（规范 §7 第 4 条）：iOS 的面板下半部是内嵌的
 * `UIActivityViewController`，Android 的系统 Sharesheet 是独立系统 UI、不能内嵌，
 * 因此这里给两个动作按钮。
 *
 * - 渲染中：不确定进度圈 + 「正在生成分享图…」，区块最小高 220dp；
 * - 面板**自己不画关闭按钮**：下滑手势 / 点 scrim（M3 默认）；
 * - 预览最大宽 200dp（竖屏），宽屏下随内容列宽走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    visible: Boolean,
    rendering: Boolean,
    imageFile: File?,
    plainText: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSaveToGallery: () -> Unit,
    onCopyText: () -> Unit,
    onShareText: () -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ShareSheetContent(
            rendering = rendering,
            imageFile = imageFile,
            plainText = plainText,
            onShare = onShare,
            onSaveToGallery = onSaveToGallery,
            onCopyText = onCopyText,
            onShareText = onShareText,
        )
    }
}

/** 面板内容（抽出来是为了能在 Compose 单测里直接渲染，不必拉起 ModalBottomSheet）。 */
@Composable
fun ShareSheetContent(
    rendering: Boolean,
    imageFile: File?,
    plainText: String,
    onShare: () -> Unit,
    onSaveToGallery: () -> Unit,
    onCopyText: () -> Unit = {},
    onShareText: () -> Unit = {},
) {
    val previewSize = SharePanelLayout.previewWidthDp(LocalAdaptiveLayout.current)
    val density = LocalDensity.current
    val previewWidthPx = with(density) { previewSize.dp.toPx().toInt() }
        .coerceAtLeast(SharePanelLayout.PREVIEW_MIN_DECODE_PX)
    // P2-1：分享图可能有 1000+ px 高，预览只需要 200dp 宽；放 IO 解码并按宽度采样，
    // 既不在组合期解码，也不把整张长图塞进内存。
    val image by produceState<ImageBitmap?>(initialValue = null, imageFile?.path, previewWidthPx) {
        value = imageFile
            ?.takeIf { it.exists() }
            ?.let { file -> withContext(Dispatchers.IO) { loadBitmap(file, previewWidthPx) } }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page)
            .padding(bottom = Spacing.page),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.card),
    ) {
        Text(
            text = stringResource(R.string.share_sheet_title),
            style = MaterialTheme.typography.titleMedium,
        )

        // 渲染中（或图还没解出来）：进度圈 + 文案，区块最小高 220dp（规范 §4.2）。
        // 预览就位、两个动作才可用 —— 渲染失败时面板会被关掉，所以这里不会停在"既没图也不在渲染"。
        val preview = imageFile?.let { file -> image?.let { bitmap -> file to bitmap } }
        if (preview == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SharePanelLayout.PROGRESS_MIN_HEIGHT.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text(
                    text = stringResource(R.string.share_rendering),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.rowIcon),
                )
            }
        } else {
            ShareSheetPreview(
                image = preview.second,
                imageFile = preview.first,
                widthDp = previewSize,
            )
        }

        // 两个动作：预览没就位就禁用（点下去只会得到一句失败）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.card, Alignment.CenterHorizontally),
        ) {
            Button(onClick = onShare, enabled = preview != null && !rendering) {
                Text(stringResource(R.string.share_action_share))
            }
            TextButton(onClick = onSaveToGallery, enabled = preview != null && !rendering) {
                Text(stringResource(R.string.share_action_save))
            }
        }

        // 纯文字的两个动作不依赖长图渲染：分享图失败/还没好时也能用。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.card, Alignment.CenterHorizontally),
        ) {
            TextButton(onClick = onCopyText, enabled = plainText.isNotBlank()) {
                Text(stringResource(R.string.share_action_copy_text))
            }
            TextButton(onClick = onShareText, enabled = plainText.isNotBlank()) {
                Text(stringResource(R.string.share_action_share_text))
            }
        }
    }
}

/** 预览：文件名 + 实际像素尺寸 + 描边圆角长图。 */
@Composable
private fun ShareSheetPreview(
    image: ImageBitmap,
    imageFile: File,
    widthDp: Int,
) {
    val previewDesc = stringResource(R.string.share_preview_desc)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.rowIcon),
    ) {
        Text(
            text = stringResource(R.string.share_sheet_meta, imageFile.name, image.width, image.height),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .widthIn(max = widthDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .semantics { contentDescription = previewDesc },
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

/** 面板的版面常量（规范 §4.2 / §6）。 */
object SharePanelLayout {
    /** 竖屏预览最大宽（规范 §6「分享面板预览最大宽 200dp」）。 */
    const val PREVIEW_MAX_WIDTH = 200

    /** 渲染中区块的最小高。 */
    const val PROGRESS_MIN_HEIGHT = 220

    /** 预览解码的最小宽度（低密度屏也别解得太糊）。 */
    const val PREVIEW_MIN_DECODE_PX = 480

    /**
     * 预览宽度：竖屏 200dp；宽屏（内容列 ≥ 548.5dp）跟着内容列走，但不超过长图自身的
     * 720dp 逻辑宽 —— 预览比原图还宽没有意义。
     */
    fun previewWidthDp(layout: com.cov.justdiary.ui.adaptive.AdaptiveLayout): Int {
        val column = layout.contentColumn(720f).toInt()
        return if (layout.splitsMasterDetail) column.coerceIn(PREVIEW_MAX_WIDTH, 480) else PREVIEW_MAX_WIDTH
    }
}

/**
 * 读分享长图并按 [maxWidthPx] 做 inSampleSize 降采样。
 *
 * 长图的高度可能远大于宽度，但 `Image` 只按预览宽缩放；不采样会把整张长图
 * 按原尺寸读进内存（P2-1 的一部分）。
 */
private fun loadBitmap(file: File, maxWidthPx: Int): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= maxWidthPx) {
        sample *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()
