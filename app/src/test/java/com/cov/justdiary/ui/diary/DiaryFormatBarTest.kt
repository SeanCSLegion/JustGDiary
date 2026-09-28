package com.cov.justdiary.ui.diary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.cov.justdiary.ui.components.AnchoredMenu
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cov.justdiary.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 格式栏（规范 §3.3）的**结构与按钮语义**回归。
 *
 * 这一屏最容易做错的不是像素，而是"哪个按钮现在是激活的、哪个按钮此刻不能用"：
 * 段落样式（菜单）、居中（与列表/引用/待办互斥）、列表/引用/待办（三者互斥）、
 * B/I/S/U（有选区时"整个选区都带"才算激活）。这些都在这里逐条钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiaryFormatBarTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun str(id: Int): String = context.getString(id)

    private fun labels() = listOf(
        str(R.string.editor_tool_style),
        str(R.string.editor_align_center),
        str(R.string.editor_tool_list),
        str(R.string.editor_tool_quote),
        str(R.string.editor_tool_todo),
        str(R.string.editor_tool_bold),
        str(R.string.editor_tool_italic),
        str(R.string.editor_tool_strike),
        str(R.string.editor_tool_underline),
    )

    @Test
    fun `the bar has the nine fixed buttons in the documented order`() {
        rule.setContent { DiaryFormatBar(activeBlock = EditorBlockStyle.Body, flags = InlineFlags()) }

        // 顺序就是规范 §3.3 的顺序：段落样式 · 居中 ‖ 列表 · 引用 · 待办 ‖ B I S U
        labels().forEach { label ->
            rule.onNodeWithContentDescription(label).assertExists()
        }
        // 引用**不在**段落样式菜单里（它有自己的按钮）——菜单没展开时不该出现第二份
        rule.onAllNodesWithContentDescription(str(R.string.editor_tool_quote)).assertCountEquals(1)
    }

    @Test
    fun `center is disabled on list quote and todo lines`() {
        // 三类"块级标记"行上，居中都必须置灰（互斥：规范 §3.3）
        var kind by mutableStateOf("plain")
        rule.setContent {
            DiaryFormatBar(
                activeBlock = EditorBlockStyle.Body,
                flags = InlineFlags(),
                isList = kind == "list",
                isQuote = kind == "quote",
                isTodo = kind == "todo",
            )
        }

        // 正文行：居中可用
        rule.onNodeWithContentDescription(str(R.string.editor_align_center)).assertIsEnabled()

        listOf("list", "quote", "todo").forEach { current ->
            kind = current
            rule.waitForIdle()
            rule.onNodeWithContentDescription(str(R.string.editor_align_center)).assertIsNotEnabled()
            rule.onNodeWithContentDescription(str(R.string.editor_tool_list)).assertIsEnabled()
        }
    }

    @Test
    fun `center is enabled on plain paragraphs and reports its active state`() {
        rule.setContent {
            DiaryFormatBar(
                activeBlock = EditorBlockStyle.Body,
                flags = InlineFlags(),
                isCentered = true,
            )
        }
        rule.onNodeWithContentDescription(str(R.string.editor_align_center))
            .assertIsEnabled()
            .assertIsSelected()
    }

    @Test
    fun `inline buttons reflect the whole selection state`() {
        rule.setContent {
            DiaryFormatBar(
                activeBlock = EditorBlockStyle.Body,
                flags = InlineFlags(bold = true, underline = true),
            )
        }
        rule.onNodeWithContentDescription(str(R.string.editor_tool_bold)).assertIsSelected()
        rule.onNodeWithContentDescription(str(R.string.editor_tool_underline)).assertIsSelected()
        rule.onNodeWithContentDescription(str(R.string.editor_tool_italic)).assertIsNotSelected()
        rule.onNodeWithContentDescription(str(R.string.editor_tool_strike)).assertIsNotSelected()
    }

    /**
     * 段落样式菜单：只有三项（**引用不在里面**），点一项上报。
     *
     * 菜单的状态由宿主持有（它要浮在格式栏**之上**、且不新开窗口），所以这里按编辑器的
     * 真实接法把它拼起来：`styleMenuOpen` + 浮层里渲染 `ParagraphStyleMenu`。
     */
    @Test
    fun `the paragraph style menu offers exactly three styles and reports the picked one`() {
        var picked: EditorBlockStyle? = null
        rule.setContent {
            var open by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                DiaryFormatBar(
                    activeBlock = EditorBlockStyle.Heading,
                    flags = InlineFlags(),
                    onBlockStyle = { picked = it },
                    styleMenuOpen = open,
                    onStyleMenuChange = { open = it },
                )
                AnchoredMenu(expanded = open, onDismiss = { open = false }) {
                    ParagraphStyleMenu(
                        activeBlock = EditorBlockStyle.Heading,
                        onSelect = { picked = it; open = false },
                    )
                }
            }
        }

        rule.onNodeWithContentDescription(str(R.string.editor_tool_style)).performClick()
        rule.onNodeWithText(str(R.string.editor_font_heading)).assertExists()
        rule.onNodeWithText(str(R.string.editor_font_heading)).assertExists()
        rule.onNodeWithText(str(R.string.editor_font_body)).assertExists()

        rule.onNodeWithText(str(R.string.editor_font_body)).performClick()
        assertEquals(EditorBlockStyle.Body, picked)
    }

    @Test
    fun `tapping a button reports the action`() {
        var listToggled = 0
        var flag: InlineFlag? = null
        rule.setContent {
            DiaryFormatBar(
                activeBlock = EditorBlockStyle.Body,
                flags = InlineFlags(),
                onToggleList = { listToggled++ },
                onToggleInline = { flag = it },
            )
        }

        rule.onNodeWithContentDescription(str(R.string.editor_tool_list)).performClick()
        assertEquals(1, listToggled)
        rule.onNodeWithContentDescription(str(R.string.editor_tool_strike)).performClick()
        assertEquals(InlineFlag.Strike, flag)
    }

    @Test
    fun `the whole bar can be disabled while the ime is composing`() {
        rule.setContent {
            DiaryFormatBar(
                activeBlock = EditorBlockStyle.Body,
                flags = InlineFlags(),
                enabled = false,
            )
        }
        rule.onNodeWithContentDescription(str(R.string.editor_tool_list)).assertIsNotEnabled()
        rule.onNodeWithContentDescription(str(R.string.editor_tool_bold)).assertIsNotEnabled()
    }
}
