package com.cov.justdiary.ui.diary

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import org.junit.Assert.assertEquals
import com.cov.justdiary.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 阅读态的**语义与交互**回归（Robolectric + Compose 测试）。
 *
 * 真机上"点了没反应"那类问题的根因是回调没接线，纯函数测不到；这里直接驱动
 * 生产代码里的 [DiaryReadScreen]，把四条最容易断的链路钉住：
 *
 * 1. 点整卡 → 编辑；长按 → 多选（规范 §2.4）；
 * 2. **整卡是一条语义**（TalkBack 读一句话，而不是逐段念），并带「编辑 / 删除」自定义动作（规范 §5）；
 * 3. 待办行是 `Checkbox`（有 state），点它只切待办、**不会**把整卡点进编辑；
 * 4. 空的一天：Hero 卡仍在，并显示「这一天没有留下日记。」（规范 §2.3，缺陷 D8 的修法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiaryReadScreenTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * 文案从资源里取，不写死中英文：这套用例跑在默认 locale 下，
     * 写死 "今日" 会在英文环境下假红（反之亦然）。
     */
    private val context = RuntimeEnvironment.getApplication()
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun block(
        id: Long = 1L,
        time: String = "9:41",
        loc: String = "浦东新区 · 上海市 · 中国",
        parts: List<ContentPart> = listOf(
            ContentPart(style = ContentPartStyle.body, text = "这一段是正文"),
        ),
    ) = ReadBlock(id = id, startTimeUtc = 0L, timeLabel = time, locText = loc, parts = parts)

    @Test
    fun `tapping a block card enters editing and long press enters select mode`() {
        var edited: Int? = null
        var longPressed: Int? = null
        rule.setContent {
            DiaryReadScreen(
                dayTitle = "9月15日 周二",
                blocks = listOf(block(id = 7L)),
                onEditBlock = { edited = it },
                onLongPressBlock = { longPressed = it },
            )
        }

        rule.onNode(hasText("这一段是正文") and hasClickAction()).performClick()
        assertEquals(0, edited)

        rule.onNode(hasText("这一段是正文") and hasClickAction()).performTouchInput { longClick() }
        assertEquals(0, longPressed)
    }

    @Test
    fun `a block card reads as one element with edit and delete custom actions`() {
        var edited: Int? = null
        var toggled: Int? = null
        rule.setContent {
            DiaryReadScreen(
                dayTitle = "9月15日 周二",
                blocks = listOf(block(id = 7L)),
                onEditBlock = { edited = it },
                onToggleSelect = { toggled = it },
            )
        }

        // 合并之后：卡片节点自己带上了抬头与正文的全部文字
        val card = rule.onNode(hasText("9:41") and hasText("这一段是正文") and hasClickAction())
        card.assertIsDisplayed()
        // 自定义动作「编辑」「删除」挂在同一节点上（TalkBack 的转子菜单）
        val actions = card.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(str(R.string.read_action_edit), str(R.string.delete)), actions.map { it.label })
        actions.first { it.label == str(R.string.read_action_edit) }.action()
        assertEquals(0, edited)
        actions.first { it.label == str(R.string.delete) }.action()
        assertEquals(0, toggled)
    }

    @Test
    fun `todo rows toggle without entering the editor`() {
        var edited: Int? = null
        var toggled: Pair<Int, Int>? = null
        rule.setContent {
            DiaryReadScreen(
                dayTitle = "9月15日 周二",
                blocks = listOf(
                    block(
                        id = 9L,
                        parts = listOf(
                            ContentPart(
                                style = ContentPartStyle.todo,
                                items = listOf("买牛奶", "取快递"),
                                done = listOf(true, false),
                            ),
                        ),
                    ),
                ),
                onEditBlock = { edited = it },
                onToggleTodo = { blockIndex, itemIndex -> toggled = blockIndex to itemIndex },
            )
        }

        rule.onNodeWithText("买牛奶").performClick()
        assertEquals(0 to 0, toggled)
        assertEquals("勾待办不该进编辑", null, edited)

        rule.onNodeWithText("取快递").performClick()
        assertEquals(0 to 1, toggled)
    }

    @Test
    fun `empty day keeps the hero card and says so`() {
        rule.setContent {
            DiaryReadScreen(dayTitle = "9月27日 周日", blocks = emptyList(), isToday = true)
        }

        rule.onNodeWithText("9月27日 周日").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.index_today)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.index_day_empty)).assertIsDisplayed()
    }

    @Test
    fun `hero card summarises the first block`() {
        rule.setContent {
            DiaryReadScreen(dayTitle = "9月15日 周二", blocks = listOf(block(), block(id = 2L, time = "13:05")))
        }

        // Hero 卡本身是一条合并语义：首条时间 + 「N 个片段」+ 地点都在同一节点上
        rule.onNode(
            hasText("9:41") and
                hasText(str(R.string.read_hero_segments, 2)) and
                hasText("浦东新区 · 上海市 · 中国"),
        ).assertIsDisplayed()
        // 条目卡各自也是合并节点，所以 "9:41" 一共出现在两处（Hero + 那张卡）
        rule.onAllNodesWithText("9:41").assertCountEquals(2)
    }

    @Test
    fun `in-page search bar shows the hit ordinal and steps through hits`() {
        // 搜索栏是**悬浮在内容之上**的独立组件（宿主画它，不进列表），因此单独组合它。
        var step = 0
        rule.setContent {
            DiarySearchBarOverlay(
                keyword = "深圳",
                hitOrdinal = 1,
                hitCount = 3,
                onSearchChange = {},
                onSearchStep = { step = it },
            )
        }

        rule.onNodeWithText("1/3").assertIsDisplayed()
        rule.onNodeWithContentDescription(str(R.string.read_search_next)).performClick()
        assertEquals(1, step)
        rule.onNodeWithContentDescription(str(R.string.read_search_prev)).performClick()
        assertEquals(-1, step)
    }

    @Test
    fun `the current hit is highlighted with a different style than the other hits`() {
        var currentBg: Color? = null
        var otherBg: Color? = null
        rule.setContent {
            currentBg = MaterialTheme.colorScheme.primary
            otherBg = MaterialTheme.colorScheme.primaryContainer
            DiaryReadScreen(
                dayTitle = "9月15日 周二",
                blocks = listOf(
                    block(
                        id = 7L,
                        parts = listOf(ContentPart(style = ContentPartStyle.body, text = "深圳 深圳 深圳")),
                    ),
                ),
                showSearch = true,
                keyword = "深圳",
                // 三处命中里把第二处标成"当前命中"
                currentHitBlockId = 7L,
                currentHit = DiarySearch.Match(partIndex = 0, itemIndex = -1, start = 3, end = 5),
            )
        }

        // 卡片是一条合并语义，文本列表里还有时间与地点 —— 取出正文那一条
        val text = rule.onNodeWithText("深圳 深圳 深圳").fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first { it.text.contains("深圳") }
        val backgrounds = text.spanStyles.map { it.item.background }
        assertEquals("当前命中只有一处", 1, backgrounds.count { it == currentBg })
        assertEquals("其余两处用浅底", 2, backgrounds.count { it == otherBg })
    }

    @Test
    fun `select mode marks the card as selected and taps toggle instead of editing`() {
        var edited: Int? = null
        var toggled: Int? = null
        rule.setContent {
            DiaryReadScreen(
                dayTitle = "9月15日 周二",
                blocks = listOf(block(id = 7L)),
                selectMode = true,
                selectedIds = setOf(7L),
                onEditBlock = { edited = it },
                onToggleSelect = { toggled = it },
            )
        }

        rule.onNode(hasText("这一段是正文") and hasClickAction()).assertIsSelected().performClick()
        assertEquals(0, toggled)
        assertTrue("多选态点卡片只切换选中，不进编辑", edited == null)
    }
}
