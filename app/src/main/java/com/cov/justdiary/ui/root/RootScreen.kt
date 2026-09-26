package com.cov.justdiary.ui.root

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cov.justdiary.ui.adaptive.AdaptiveLayout
import com.cov.justdiary.ui.adaptive.AdaptiveLayoutHost
import com.cov.justdiary.ui.adaptive.rememberAdaptiveLayout
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.JustDiaryApplication
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.app.LaunchIntent
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.ui.diary.DiaryPageScreen
import com.cov.justdiary.ui.footprint.FootprintScreen
import com.cov.justdiary.ui.footprint.FootprintViewModel
import com.cov.justdiary.ui.home.HomeViewModel
import com.cov.justdiary.ui.home.HomeScreen
import com.cov.justdiary.ui.search.SearchScreen
import com.cov.justdiary.ui.search.SearchViewModel
import com.cov.justdiary.ui.settings.SettingsScreen

/**
 * 根界面：主导航宿主。
 *
 * 对应 iOS `Views/RootView.swift`（`TabView(.sidebarAdaptable)` + 设置/语言变更时整树重建）。
 *
 * **导航形态按 Google 自适应导航规范**（Q10 已确认，不照抄 iOS 的底部悬浮条）：
 *  - 紧凑宽度（<600dp）→ 底部 [NavigationBar]
 *  - 中等/扩展宽度（≥600dp，含手机横屏）→ 侧边 [NavigationRail]
 *
 * 几何只在这里读一次，解析成 [AdaptiveLayout] 后经 [LocalAdaptiveLayout] 下发；
 * 页面内部一律**不再自己读屏幕尺寸**（与 iOS 的 `adaptiveLayoutReader` 同一原则）。
 */
@Composable
fun RootScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as JustDiaryApplication).container
    }
    var selectedName by rememberSaveable { mutableStateOf(AppTab.Home.name) }
    val selected = AppTab.entries.firstOrNull { it.name == selectedName } ?: AppTab.Home

    // 全屏的日记页（阅读/编辑）在标签页之上：对应 iOS 的 `.fullScreenCover`
    var openDayKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 「写日记 / 继续写日记」直接进编辑器（对应 iOS 的 `openEditor`），
    // 而点日期 / 点日记卡片是进阅读页（`openDiary`）。
    var openInWrite by rememberSaveable { mutableStateOf(false) }
    // 日记数据变过之后，让各个页面自己刷一次（对应 iOS 的 `.diaryVersionChanged`）
    var dataVersion by rememberSaveable { mutableIntStateOf(0) }

    // 深链：justdiary://editor?dayKey=… → 打开这一天的日记页
    val launchDayKey by LaunchIntent.dayKey.collectAsState()
    LaunchedEffect(launchDayKey) {
        val key = launchDayKey ?: return@LaunchedEffect
        openDayKey = key
        LaunchIntent.clear()
    }

    if (openDayKey != null) {
        AdaptiveLayoutHost(modifier = modifier) {
            DiaryPageScreen(
                dayKey = openDayKey,
                onClose = { openDayKey = null; openInWrite = false },
                onDataChanged = { dataVersion++ },
                startInWrite = openInWrite,
            )
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val adaptive = rememberAdaptiveLayout(maxWidth.value, maxHeight.value)
        val useRail = adaptive.navForm == AdaptiveLayout.NavForm.Rail

        if (useRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
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
                CompositionLocalProvider(LocalAdaptiveLayout provides adaptive) {
                    TabContent(
                        tab = selected,
                        container = container,
                        dataVersion = dataVersion,
                        onOpenDay = { openDayKey = it },
                        onWriteDay = { openInWrite = true; openDayKey = it },
                        modifier = Modifier.weight(1f),
                    )
                }
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
                CompositionLocalProvider(LocalAdaptiveLayout provides adaptive) {
                    TabContent(
                        tab = selected,
                        container = container,
                        dataVersion = dataVersion,
                        onOpenDay = { openDayKey = it },
                        onWriteDay = { openInWrite = true; openDayKey = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        }
    }
}

/**
 * 四个标签页的内容。
 *
 * 每个页面的 ViewModel 在**该标签第一次被打开时**创建（Compose 的 `viewModel()` 语义），
 * 之后一直存活；`dataVersion` 变化时各页面自己重新读库，因此"在日记页里改完数据、
 * 回到列表"看到的一定是新数据（对应 iOS 的 `.diaryVersionChanged` 通知）。
 */
@Composable
private fun TabContent(
    tab: AppTab,
    container: AppContainer,
    dataVersion: Int,
    onOpenDay: (String) -> Unit,
    /** 直接进编辑器（首页当日面板的「写日记」）。 */
    onWriteDay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val formats = remember {
        DateFormats.forContext(context, container.settings.load().appLanguage)
    }

    when (tab) {
        AppTab.Home -> {
            val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
            val state by viewModel.state.collectAsState()
            LaunchedEffect(dataVersion) { viewModel.refresh() }
            // 设置变化（auto_time / 周起始…）后刷新这个**已经活着**的标签页
            val settingsVersion by container.settings.version.collectAsState()
            LaunchedEffect(settingsVersion) { viewModel.refreshSettings() }
            HomeScreen(
                modifier = modifier,
                today = state.today,
                flags = state.flags,
                weekStart = state.weekStart,
                dateFormats = formats,
                dayBlocks = state.dayBlocks,
                showTime = state.autoTime,
                onYearChange = viewModel::setVisibleYear,
                onSelectDay = viewModel::select,
                onOpenDay = { onOpenDay(DateUtil.dayKeyOf(it)) },
                onWrite = { onWriteDay(DateUtil.dayKeyOf(it)) },
                imageLoader = { src -> container.mediaStore.image(src)?.asImageBitmap() },
            )
        }

        AppTab.Footprint -> {
            val viewModel: FootprintViewModel =
                viewModel(factory = FootprintViewModel.factory(container))
            val state by viewModel.state.collectAsState()
            LaunchedEffect(dataVersion) { viewModel.reload() }
            FootprintScreen(
                state = state.viewState,
                yearFilter = state.yearFilter,
                // 并排由 AdaptiveLayout 统一判定（内容宽 ≥548.5dp 且 宽 > 高）
                landscape = LocalAdaptiveLayout.current.splitsMasterDetail,
                expanded = state.expanded,
                onYearFilterChange = viewModel::setYearFilter,
                timeKind = state.timeKind,
                customFrom = state.customFrom,
                customTo = state.customTo,
                onTimeKindChange = viewModel::setTimeKind,
                onApplyCustomRange = viewModel::applyCustomRange,
                onClearTimeFilter = viewModel::clearTimeFilter,
                onToggleExpand = viewModel::toggleExpandNode,
                onDayClick = onOpenDay,
                modifier = modifier,
            )
        }

        AppTab.Search -> {
            val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
            val state by viewModel.state.collectAsState()
            // 日记数据变过之后重查（与其它的标签页一致）。原来是唯一漏掉这条的页面，
            // 于是在日记页改完内容回到搜索页，结果还是旧的。
            LaunchedEffect(dataVersion) { viewModel.refresh() }
            val searchSettingsVersion by container.settings.version.collectAsState()
            LaunchedEffect(searchSettingsVersion) { viewModel.refresh() }
            SearchScreen(
                state = state.ui,
                filterItems = state.filterItems,
                modifier = modifier,
                onKeywordChange = viewModel::onKeywordChange,
                onCommitKeyword = viewModel::commitKeyword,
                onRemoveKeyword = viewModel::removeKeyword,
                onClearAll = viewModel::clearAll,
                onTimeKindChange = viewModel::setTimeKind,
                onNoLocationChange = viewModel::setNoLocation,
                onApplyCustomRange = viewModel::applyCustomRange,
                onLoadMore = viewModel::loadMore,
                onOpenDiary = onOpenDay,
                locOptions = state.locOptions,
                noLocCount = state.noLocCount,
                locLoading = state.locLoading,
                locSearch = state.locSearch,
                onLocSearchChange = viewModel::setLocSearch,
                onOpenLocPicker = { viewModel.loadLocationOptions() },
                onSelectLocation = viewModel::selectLocation,
                onResetLocation = viewModel::resetLocationFilter,
            )
        }

        AppTab.Settings -> SettingsScreen(modifier = modifier)
    }
}
