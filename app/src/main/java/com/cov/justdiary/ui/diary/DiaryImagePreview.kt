package com.cov.justdiary.ui.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cov.justdiary.R
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 内联图片的全屏预览。对应 iOS `MediaViews.swift` 的 `ImagePreviewView`（规范 §2.6）。
 *
 * | 手势 | 行为 |
 * | --- | --- |
 * | 点背景 / 图 | 关闭（iOS P1） |
 * | 下拉 | 背景按位移淡出、图跟着缩小；松手位移 > 140dp 关闭，否则弹回（iOS P2/P3） |
 * | 双击 | 在「适宽 / 原始」之间切换；原始尺寸下可拖动平移 |
 *
 * 无障碍：整层是模态（`isTraversalGroup` + 自定义「关闭」动作），iOS 侧也是 `.isModal`。
 */
@Composable
fun DiaryImagePreview(
    bitmap: ImageBitmap,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val dismissLabel = stringResource(R.string.a11y_close)
        // 下拉位移（用 Animatable 才能"松手弹回"）
        val dragY = remember { Animatable(0f) }
        var zoomed by remember { mutableStateOf(false) }
        var panX by remember { mutableStateOf(0f) }
        var panY by remember { mutableStateOf(0f) }

        val progress = abs(dragY.value) / DISMISS_DISTANCE
        val backgroundAlpha = (1f - progress).coerceIn(0f, 1f)
        // 原始尺寸 = 位图的像素尺寸换算成 dp（`ContentScale.Fit` 之外的另一档）
        val naturalWidth = with(density) { bitmap.width.toDp() }
        val naturalHeight = with(density) { bitmap.height.toDp() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha))
                .semantics(mergeDescendants = true) {
                    dismiss { onDismiss(); true }
                }
                .pointerInput(zoomed) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            zoomed = !zoomed
                            panX = 0f
                            panY = 0f
                        },
                    )
                }
                .pointerInput(zoomed) {
                    detectDragGestures(
                        onDragEnd = {
                            if (!zoomed && abs(dragY.value) > DISMISS_DISTANCE) {
                                onDismiss()
                            } else if (!zoomed) {
                                scope.launch { dragY.animateTo(0f, spring()) }
                            }
                        },
                        onDragCancel = { scope.launch { dragY.animateTo(0f, spring()) } },
                    ) { change, dragAmount ->
                        change.consume()
                        if (zoomed) {
                            panX += dragAmount.x
                            panY += dragAmount.y
                        } else {
                            scope.launch { dragY.snapTo(dragY.value + dragAmount.y) }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val shrink = if (zoomed) 1f else (1f - progress * 0.4f).coerceAtLeast(0.6f)
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier
                    .then(if (zoomed) Modifier.size(naturalWidth, naturalHeight) else Modifier.fillMaxSize())
                    .graphicsLayer {
                        translationY = dragY.value + panY
                        translationX = panX
                        scaleX = shrink
                        scaleY = shrink
                    },
                contentScale = if (zoomed) ContentScale.None else ContentScale.Fit,
            )
        }
    }
}

/** 下拉关闭的位移阈值（iOS P3：> 140pt 关闭）。 */
private const val DISMISS_DISTANCE = 140f
