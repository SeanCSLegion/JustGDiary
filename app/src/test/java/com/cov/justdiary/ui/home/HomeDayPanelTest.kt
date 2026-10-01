package com.cov.justdiary.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cov.justdiary.R
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.EditBlock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 首页当日面板的**两个写入口**（规范 §3.4 / iOS `DayContentView.swift:29-61`）。
 *
 * 2026-09-28 用户返工：有内容时的「继续写日记」不该直接进编辑态，而应该进阅读态 ——
 * 这一天已经写过东西，先让用户看到它。空态的「写日记」保持直接进编辑态（没有内容可读）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeDayPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun str(id: Int): String = context.getString(id)

    private val today: LocalDate = LocalDate.of(2026, 9, 28)

    private fun block() = EditBlock(
        id = 1L,
        diaryId = 1L,
        startTimeUtc = 1_790_000_000_000L,
        locText = "上海市 · 徐汇区",
        latitude = 31.1,
        longitude = 121.4,
        contentJson = """{"parts":[{"style":"body","text":"已经写过的一条"}],"v":2}""",
        searchText = "已经写过的一条",
        locPrecision = "district",
        locQuality = "precise",
        country = "中国",
        countryCode = "CN",
        region1 = "上海市",
        region2 = "上海市",
        region3 = "徐汇区",
        createdUtc = 1_790_000_000_000L,
        updatedUtc = 1_790_000_000_000L,
    )

    private fun panel(
        blocks: List<EditBlock>?,
        onOpenDay: () -> Unit,
        onWrite: () -> Unit,
    ) {
        rule.setContent {
            DayPanel(
                blocks = blocks,
                selectedDate = today,
                todayKey = DateUtil.dayKeyOf(today),
                dateFormats = null,
                showTime = true,
                imageLoader = { null },
                onOpenDay = onOpenDay,
                onWrite = onWrite,
                onToday = null,
                showHeader = false,
            )
        }
    }

    @Test
    fun `continue writing opens the read mode instead of the editor`() {
        var opened = 0
        var written = 0
        panel(blocks = listOf(block()), onOpenDay = { opened++ }, onWrite = { written++ })

        rule.onNodeWithText(str(R.string.index_continue_write)).performClick()

        assertEquals("「继续写日记」进阅读态", 1, opened)
        assertEquals("「继续写日记」不进编辑态", 0, written)
    }

    @Test
    fun `the empty day keeps the direct write entry`() {
        var opened = 0
        var written = 0
        panel(blocks = emptyList(), onOpenDay = { opened++ }, onWrite = { written++ })

        rule.onNodeWithText(str(R.string.index_write)).performClick()

        assertEquals("空态「写日记」直接进编辑态", 1, written)
        assertEquals(0, opened)
    }

    @Test
    fun `a past day shows no write entry at all`() {
        var opened = 0
        var written = 0
        rule.setContent {
            DayPanel(
                blocks = emptyList(),
                selectedDate = today.minusDays(1),
                todayKey = DateUtil.dayKeyOf(today),
                dateFormats = null,
                showTime = true,
                imageLoader = { null },
                onOpenDay = { opened++ },
                onWrite = { written++ },
                onToday = null,
                showHeader = false,
            )
        }

        rule.onNodeWithText(str(R.string.index_write)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.index_continue_write)).assertDoesNotExist()
        assertEquals(0, opened + written)
    }
}
