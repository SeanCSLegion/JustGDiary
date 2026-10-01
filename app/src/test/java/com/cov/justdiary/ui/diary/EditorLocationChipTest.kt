package com.cov.justdiary.ui.diary

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import com.cov.justdiary.R
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.LocationRules
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 编辑页地点行的**形态**回归（2026-09-28 用户返工）。
 *
 * 原先这里是"左边一枚状态文字 + 右边一枚「重新获取位置」文字按钮"，iOS 那边从头到尾
 * 只有**一枚胶囊**（`DiaryPageView.swift:610-682`）：状态与动作是同一个元素，
 * 处在"新建 + 还没拿到位置"时这颗胶囊本身就是那个按钮。
 *
 * 因此这一组用例钉住三件事：
 *  1. 每一档状态**只有一枚**胶囊，不会同时出现"未记录地点 + 重新获取位置"；
 *  2. 只有"能点的"那两档带点击动作（`Locating` / `NotRecorded` 点了不该有反应）；
 *  3. 有位置时点胶囊打开精度菜单（走宿主回调）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorLocationChipTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun str(id: Int): String = context.getString(id)

    private val snapshot = LocationRules.LocationSnapshot(
        latitude = 31.19,
        longitude = 121.44,
        locText = "徐汇区 · 上海市 · 中国",
        locPrecision = LocPrecision.district,
        region = LocRegion("中国", "CN", "上海市", "上海市", "徐汇区", LocQuality.precise),
    )

    private fun editor(
        locationState: LocationRules.EditorLocationState,
        canRelocate: Boolean = true,
        onRequestLocation: () -> Unit = {},
        onRefreshLocation: () -> Unit = {},
        onPrecisionChange: (String) -> Unit = {},
    ) {
        rule.setContent {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                EditorScreen(
                    initialParts = emptyList(),
                    loadToken = 0,
                    isNewBlock = true,
                    locationState = locationState,
                    canRelocate = canRelocate,
                    startTimeUtc = 1_790_000_000_000L,
                    actions = androidx.compose.runtime.remember { EditorActions() },
                    autoTime = false,
                    onRequestLocation = onRequestLocation,
                    onRefreshLocation = onRefreshLocation,
                    onPrecisionChange = onPrecisionChange,
                )
            }
        }
    }

    /**
     * 胶囊本身（不是里面的文字）。
     *
     * 编辑卡是可点击的（点空白收键盘），`clickable` 会把整张卡的语义**合并**成一个节点，
     * 所以在合并树里找到的"重新获取位置"其实是整张卡。这里走未合并树，再取文字节点的父节点
     * —— 那颗父节点就是带（或不带）点击动作的胶囊。
     */
    private fun chip(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).onParent()

    @Test
    fun `the failed state is one tappable chip labelled retry`() {
        var requested = 0
        editor(LocationRules.EditorLocationState.LocateFailed, onRequestLocation = { requested++ })

        // iOS：这一档就是一颗 `editor_location_retry_action` 按钮胶囊
        chip(str(R.string.editor_location_retry_action)).assertHasClickAction().performClick()
        assertEquals(1, requested)
        // 不能再有一枚"未记录地点"当状态（那是两块拼起来的老排版）
        rule.onNodeWithText(str(R.string.search_loc_no_loc), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the locating state is a static chip`() {
        var requested = 0
        editor(LocationRules.EditorLocationState.Locating, onRequestLocation = { requested++ })

        chip(str(R.string.editor_location_fetching)).assertExists().assertHasNoClickAction()
        assertEquals("定位中不给重复请求的入口", 0, requested)
        rule.onNodeWithText(str(R.string.editor_location_retry_action), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a recorded location is a tappable chip holding the address`() {
        editor(LocationRules.EditorLocationState.Recorded(snapshot))

        chip(snapshot.locText).assertExists().assertHasClickAction()
        // 点胶囊打开的是精度菜单（宿主负责画菜单），不再有单独的「精度」文字按钮
        rule.onNodeWithText(str(R.string.editor_location_precision), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `an existing block without a location is a read-only chip`() {
        editor(LocationRules.EditorLocationState.NotRecorded, canRelocate = false)

        chip(str(R.string.search_loc_no_loc)).assertExists().assertHasNoClickAction()
        // 已保存的片段没有任何获取入口
        rule.onNodeWithText(str(R.string.editor_location_retry_action), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `the location row is hidden when auto location is off`() {
        editor(LocationRules.EditorLocationState.Hidden, canRelocate = true)

        rule.onNodeWithText(str(R.string.search_loc_no_loc), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.editor_location_retry_action), useUnmergedTree = true)
            .assertDoesNotExist()
        rule.onNodeWithText(str(R.string.editor_location_fetching), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `the hint is no longer a separate block in the input area`() {
        editor(LocationRules.EditorLocationState.Hidden)

        // 提示语现在是 `EditText` 自己的 hint：它画在光标所在的位置上（占位符不再是一块
        // 单独的 `Text`，因此也不在 Compose 语义树里）。真机截图见
        // `docs/ui-audit/r30/`，这里钉住的是"那一块单独的文本没有了"。
        rule.onNodeWithText(str(R.string.editor_placeholder_new), useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
