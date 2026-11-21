package com.cov.justdiary.ui.home

import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import com.cov.justdiary.model.DateUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * P1-3：月态（连续月历流）的逐日无障碍节点。
 *
 * 生产路径是 `MonthFlow` 里的 `BlockCanvas`；这里直接组合"一个月块"，
 * 避免把 ±3 个月的预挂载窗口也算进节点数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonthA11yTest {

    @get:Rule
    val rule: ComposeContentTestRule = createComposeRule()

    private val manager: AccessibilityManager
        get() = RuntimeEnvironment.getApplication()
            .getSystemService(AccessibilityManager::class.java)

    @Before
    fun enableAccessibility() {
        Shadows.shadowOf(manager).setEnabled(true)
        Shadows.shadowOf(manager).setTouchExplorationEnabled(true)
    }

    @After
    fun disableAccessibility() {
        Shadows.shadowOf(manager).setEnabled(false)
        Shadows.shadowOf(manager).setTouchExplorationEnabled(false)
    }

    private fun composeBlock(
        visibleRows: IntRange = 0 until Int.MAX_VALUE,
        onDayTap: (LocalDate, Int) -> Unit = { _, _ -> },
    ) {
        val weekStart = DateUtil.WEEK_START_MONDAY
        val layout = MonthFlowLayout.of(weekStart, rowH = 96f)
        val block = layout.blocks.first { it.key == 202611 }
        val weeks = CalendarGrid.displayedWeeks(block.monthFirst, weekStart)
        val metrics = CalendarLayout.flowMetrics(402f, rowH = 96f, showLunar = true)
        rule.setContent {
            MaterialTheme {
                BlockCanvas(
                    block = block,
                    weeks = weeks,
                    visibleRows = visibleRows,
                    metrics = metrics,
                    rowH = 96f,
                    showLunar = true,
                    selectedKey = "2026-11-13",
                    todayKey = "2026-11-13",
                    flags = setOf("2026-11-02"),
                    onDayTap = onDayTap,
                )
            }
        }
    }

    @Test
    fun `accessibility on exposes one clickable node per day of the month`() {
        var tapped: Pair<String, Int>? = null
        composeBlock { date, row -> tapped = DateUtil.dayKeyOf(date) to row }

        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals("11 月应当正好有 30 个逐日节点", 30, nodes.size)

        // 每个节点都有日期 + 状态文案，且今天 / 有日记的状态真的写进去了
        val descriptions = nodes.mapNotNull { node ->
            node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].firstOrNull()
        }
        assertTrue(
            "今天节点要朗读「今天」：$descriptions",
            descriptions.any { it.contains(RuntimeEnvironment.getApplication().getString(com.cov.justdiary.R.string.index_today)) },
        )
        assertTrue(
            "有日记节点要朗读「有日记」：$descriptions",
            descriptions.any { it.contains(RuntimeEnvironment.getApplication().getString(com.cov.justdiary.R.string.index_has_diary)) },
        )

        rule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals("2026-11-01" to 0, tapped)
    }

    @Test
    fun `accessibility off keeps the canvas path and adds no extra nodes`() {
        Shadows.shadowOf(manager).setEnabled(false)
        Shadows.shadowOf(manager).setTouchExplorationEnabled(false)
        composeBlock()

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `accessibility on exposes only dates in visible rows`() {
        composeBlock(visibleRows = 1..1)

        // 2026-11 的第 2 行（周一至周日）全部属于当月；预挂载/视口外日期不进入焦点顺序。
        rule.onAllNodes(hasClickAction()).assertCountEquals(7)
    }
}
