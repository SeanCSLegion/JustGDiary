package com.cov.justdiary.ui.diary

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.cov.justdiary.R
import com.cov.justdiary.data.DiaryTopBarState
import com.cov.justdiary.ui.components.AppIcons

/**
 * 日记页顶栏（M3 `TopAppBar`，64dp）。对应 iOS `DiaryPageView.swift:350-397`
 * 的三态悬浮顶栏，规范见 `docs/android-design/04-diary.md` §2.2。
 *
 * | 形态 | 左侧 | 右侧 |
 * | --- | --- | --- |
 * | 阅读 | `←` 返回 | `search`（active 高亮）· `edit` 写日记（**仅当天**）· `share` |
 * | 多选 | `close` 退出多选 | 标题「已选择 N 项」+ `delete`（`error` 色，空选禁用） |
 *
 * 形态判断**不在这里**：由纯函数 [DiaryTopBarState.of] 给出（阅读 / 编辑 / 多选是三个维度，
 * 见该文件的类注释），这里只按结果渲染。编辑态的顶栏仍在 [EditorScreen] 内部
 * —— 它要用编辑器自己的内容状态（脏标记、保存载荷），并入本组件是编辑态那一轮的事。
 *
 * 顶栏**钉在页面顶部、不进滚动区**（iOS 早期把它挂在 `safeAreaInset` 上，
 * 键盘一弹就被滚出屏幕，返回按钮跑到 y = −51；规范 §2.2 要求钉住）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryTopBar(
    state: DiaryTopBarState,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onWrite: () -> Unit,
    onShare: () -> Unit,
    onExitSelect: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        // 外层已经做过一次系统栏避让（`DiaryPageScreen` 的 `safeDrawing`），
        // 这里再避一次会多出一条状态栏高度的空白。
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        navigationIcon = {
            when (state.mode) {
                DiaryTopBarState.Mode.Select -> IconButton(onClick = onExitSelect) {
                    Icon(Icons.Filled.Close, contentDescription = EXIT_SELECT_LABEL)
                }

                else -> IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = BACK_LABEL)
                }
            }
        },
        title = {
            if (state.mode == DiaryTopBarState.Mode.Select) {
                Text(
                    text = stringResource(R.string.read_select_count, state.selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        actions = {
            when (state.mode) {
                DiaryTopBarState.Mode.Read -> ReadActions(
                    searchActive = state.searchActive,
                    showWrite = state.showWrite,
                    onToggleSearch = onToggleSearch,
                    onWrite = onWrite,
                    onShare = onShare,
                )

                DiaryTopBarState.Mode.Select -> IconButton(
                    onClick = onDeleteSelected,
                    enabled = state.canDeleteSelected,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = DELETE_SELECTED_LABEL,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }

                // 编辑态的顶栏由 EditorScreen 自己画（见类注释），宿主不会在这里渲染它。
                DiaryTopBarState.Mode.Edit -> Unit
            }
        },
    )
}

@Composable
private fun RowScope.ReadActions(
    searchActive: Boolean,
    showWrite: Boolean,
    onToggleSearch: () -> Unit,
    onWrite: () -> Unit,
    onShare: () -> Unit,
) {
    IconButton(
        onClick = onToggleSearch,
        modifier = Modifier.semantics { selected = searchActive },
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = SEARCH_LABEL,
            tint = if (searchActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
    // 「写日记」只在当天出现：历史日期只能改、不能新增（规范 §2.2）。
    if (showWrite) {
        IconButton(onClick = onWrite) {
            Icon(
                AppIcons.Edit,
                contentDescription = WRITE_LABEL,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    IconButton(onClick = onShare) {
        Icon(
            Icons.Filled.Share,
            contentDescription = SHARE_LABEL,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val BACK_LABEL: String
    @Composable get() = stringResource(R.string.a11y_back)
private val SEARCH_LABEL: String
    @Composable get() = stringResource(R.string.search_title)
private val WRITE_LABEL: String
    @Composable get() = stringResource(R.string.index_write)
private val SHARE_LABEL: String
    @Composable get() = stringResource(R.string.a11y_share)
private val EXIT_SELECT_LABEL: String
    @Composable get() = stringResource(R.string.read_select_exit)
private val DELETE_SELECTED_LABEL: String
    @Composable get() = stringResource(R.string.delete)
