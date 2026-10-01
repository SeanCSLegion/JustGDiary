package com.cov.justdiary.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.ui.theme.EditorTypography

/**
 * 列表 / 待办的一行：**标记槽 + 间距 + 正文**。
 *
 * 阅读态（`DiaryReadScreen`）与首页日面板预览（`HomeScreen`）共用这一个 composable，
 * 于是两处不可能各画各的；编辑态那份在 `SpannableBridge.MarkerSpan`（Canvas 手绘），
 * 三处共用 [BlockGeometry] 的几何与 M3 语义色 —— "同一行在哪儿都长一样"。
 *
 * 换行后的第二行同样从 [BlockGeometry.MarkerIndent] 起（Row 布局天然如此），
 * 与编辑态的悬挂缩进（`LeadingMarginSpan`）对齐到同一个起点。
 */
@Composable
fun MarkerRow(
    /** `true` = 待办（勾选框），`false` = 列表（圆点）。 */
    isTodo: Boolean,
    done: Boolean,
    modifier: Modifier = Modifier,
    /** 正文列（行内的粗体 / 下划线等由调用方自己拼）。 */
    content: @Composable RowScope.() -> Unit,
) {
    val glyphSize = if (isTodo) BlockGeometry.CheckboxSize else BlockGeometry.BulletSize
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(BlockGeometry.MarkerGap),
    ) {
        Box(
            modifier = Modifier
                .width(BlockGeometry.MarkerSlot)
                .padding(top = markerTopPadding(glyphSize)),
            contentAlignment = Alignment.Center,
        ) {
            if (isTodo) {
                MarkerCheckbox(done)
            } else {
                Box(
                    modifier = Modifier
                        .size(BlockGeometry.BulletSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        content()
    }
}

/**
 * 待办勾选框。尺寸全部对 **M3 `CheckboxTokens`**：容器 18dp、圆角 2dp、
 * 未选描边 2dp / `onSurfaceVariant`、选中 `primary` 实底 + `onPrimary` 对勾、图标 18dp。
 *
 * 编辑态那边是同一套数（`SpannableBridge.MarkerSpan.drawCheckbox` 手绘）。
 */
@Composable
private fun MarkerCheckbox(done: Boolean) {
    val shape = RoundedCornerShape(BlockGeometry.CheckboxRadius)
    val color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(BlockGeometry.CheckboxSize)
            .clip(shape)
            .background(if (done) color else Color.Transparent)
            .border(if (done) 0.dp else BlockGeometry.CheckboxBorder, color, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(BlockGeometry.CheckboxIcon),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * 标记图形相对首行顶部的下沉量：**在首行行盒里垂直居中**。
 *
 * 与编辑态同一条规则（`SpannableBridge.MarkerSpan` 用 `(top + bottom) / 2` 落笔），
 * 三种界面上的圆点 / 勾选框因此都压在首行文字的视觉中线上；行高取正文的 M3 角色。
 */
@Composable
private fun markerTopPadding(glyphSize: Dp): Dp {
    val lineHeight = with(LocalDensity.current) {
        EditorTypography.textStyleFor(ContentPartStyle.body).lineHeight.toDp()
    }
    return ((lineHeight - glyphSize) / 2).coerceAtLeast(0.dp)
}
