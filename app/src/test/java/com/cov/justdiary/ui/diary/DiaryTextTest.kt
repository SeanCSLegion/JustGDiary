package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.EditBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class DiaryTextTest {

    private fun block(id: Long, json: String) = EditBlock(
        id = id,
        diaryId = 1L,
        startTimeUtc = 0L,
        locText = "",
        latitude = 0.0,
        longitude = 0.0,
        contentJson = json,
        searchText = "",
        locPrecision = "",
        locQuality = "",
        country = "",
        countryCode = "",
        region1 = "",
        region2 = "",
        region3 = "",
        createdUtc = 0L,
        updatedUtc = 0L,
    )

    @Test
    fun `paragraphs keep a blank line between blocks`() {
        val parts = mapOf(
            1L to listOf(ContentPart(style = ContentPartStyle.body, text = "first")),
            2L to listOf(ContentPart(style = ContentPartStyle.heading, text = "second")),
        )
        val text = DiaryText.format(
            title = "11月14日 周六",
            blocks = listOf(block(1, ""), block(2, "")),
            partsByBlock = parts,
        )
        assertEquals("11月14日 周六\n\nfirst\n\nsecond", text)
    }

    @Test
    fun `list and todo keep markers and done state`() {
        val parts = mapOf(
            1L to listOf(
                ContentPart(style = ContentPartStyle.list, items = listOf("a", "b")),
                ContentPart(
                    style = ContentPartStyle.todo,
                    items = listOf("open", "done"),
                    done = listOf(false, true),
                ),
                ContentPart(style = ContentPartStyle.quote, text = "quoted"),
            ),
        )
        val text = DiaryText.format("T", listOf(block(1, "")), parts)
        assertEquals("T\n\n• a\n\n• b\n\n☐ open\n\n☑ done\n\n> quoted", text)
    }

    @Test
    fun `image becomes a placeholder and empty parts are skipped`() {
        val parts = mapOf(
            1L to listOf(
                ContentPart(style = ContentPartStyle.body, text = "  "),
                ContentPart(style = ContentPartStyle.image, src = "images/a.jpg"),
                ContentPart(style = ContentPartStyle.body, text = "after"),
            ),
        )
        val text = DiaryText.format("T", listOf(block(1, "")), parts)
        assertEquals("T\n\n[图片]\n\nafter", text)
    }
}
