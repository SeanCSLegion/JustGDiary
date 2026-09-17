package com.cov.justdiary.ui.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.data.FootprintViewStateBuilder
import com.cov.justdiary.data.SearchUiState
import com.cov.justdiary.data.YEAR_FILTER_ALL
import com.cov.justdiary.ui.footprint.FootprintScreen
import com.cov.justdiary.ui.home.HomeScreen
import com.cov.justdiary.ui.search.SearchScreen
import com.cov.justdiary.ui.settings.SettingsScreen

/**
 * 根界面：主导航宿主。
 *
 * 对应 iOS `Views/RootView.swift`（`TabView(.sidebarAdaptable)` + 设置/语言变更时整树重建）。
 *
 * **导航形态按 Google 自适应导航规范**（Q10 已确认，不照抄 iOS 的底部悬浮条）：
 *  - 紧凑宽度（手机竖屏）→ 底部 [NavigationBar]
 *  - 中等/扩展宽度（手机横屏、平板）→ 侧边 [NavigationRail]
 *
 * 断点在 M8 阶段细化为 M3 窗口尺寸类（compact <600dp / medium 600–839dp / expanded ≥840dp）；
 * M0 先用 600dp 这一个阈值。
 */
@Composable
fun RootScreen(modifier: Modifier = Modifier) {
    var selectedName by rememberSaveable { mutableStateOf(AppTab.Home.name) }
    val selected = AppTab.entries.firstOrNull { it.name == selectedName } ?: AppTab.Home

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    AppTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = tab == selected,
                            onClick = { selectedName = tab.name },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
                VerticalDivider()
                SkeletonScreen(tab = selected, modifier = Modifier.weight(1f))
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == selected,
                                onClick = { selectedName = tab.name },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                SkeletonScreen(
                    tab = selected,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}

/**
 * 页面宿主。
 *
 * 四个页面都已接入真实实现；**足迹与搜索拿到的仍是空状态**
 * （`DiaryRepository` → 页面状态的数据接线在 M7 剩余部分完成，
 * 编辑页入口也在同一批里打通）。
 */
@Composable
private fun SkeletonScreen(tab: AppTab, modifier: Modifier = Modifier) {
    when (tab) {
        AppTab.Home -> {
            HomeScreen(modifier = modifier)
            return
        }

        AppTab.Footprint -> {
            FootprintScreen(
                state = FootprintViewStateBuilder.build(emptyList()),
                yearFilter = YEAR_FILTER_ALL,
                modifier = modifier,
            )
            return
        }

        AppTab.Search -> {
            SearchScreen(
                state = SearchUiState(total = 0),
                filterItems = emptyList(),
                modifier = modifier,
            )
            return
        }

        AppTab.Settings -> {
            SettingsScreen(modifier = modifier)
            return
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(tab.labelRes),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.m0_placeholder_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.m0_package_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
