package com.cov.justdiary.ui.diary

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * 分享面板（规范 §4）的结构与状态回归。
 *
 * 面板有三个状态最容易做错，且都在这里钉住：
 * 1. **渲染中**：进度圈 + 「正在生成分享图…」，两个动作都**不可用**；
 * 2. **预览就位**：文件名 + 实际像素尺寸 + 两个动作可用；
 * 3. **点「分享…」/「保存到相册」**：分别上报（宿主去开系统 Sharesheet / 写相册）。
 *
 * 文件名格式（`JustDiary-<dayKey>.png`）是验收清单里明写的一条，一并钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun str(id: Int): String = context.getString(id)

    @Test
    fun `rendering shows progress and disables both actions`() {
        rule.setContent {
            ShareSheetContent(
                rendering = true,
                imageFile = null,
                plainText = "",
                onShare = {},
                onSaveToGallery = {},
            )
        }

        rule.onNodeWithText(str(R.string.share_sheet_title)).assertExists()
        rule.onNodeWithText(str(R.string.share_rendering)).assertExists()
        rule.onNodeWithText(str(R.string.share_action_share)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.share_action_save)).assertIsNotEnabled()
        rule.onAllNodesWithContentDescription(str(R.string.share_preview_desc)).assertCountEquals(0)
    }

    @Test
    fun `a rendered image shows the preview and both actions report`() {
        var shareClicks = 0
        var saveClicks = 0
        val file = writeSharePng("2026-09-08")

        rule.setContent {
            ShareSheetContent(
                rendering = false,
                imageFile = file,
                plainText = "body",
                onShare = { shareClicks++ },
                onSaveToGallery = { saveClicks++ },
            )
        }

        // P2-1：预览在 IO 上解码，等它真的出现再断言。
        rule.waitUntil(5_000) {
            rule.onAllNodesWithContentDescription(str(R.string.share_preview_desc))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // 副标题：文件名 + 真实像素尺寸（预览里那张图是 4 × 3）
        rule.onNodeWithText(
            str(R.string.share_sheet_meta).format(file.name, 4, 3),
        ).assertExists()
        rule.onAllNodesWithContentDescription(str(R.string.share_preview_desc)).assertCountEquals(1)

        rule.onNodeWithText(str(R.string.share_action_share)).assertIsEnabled().performClick()
        rule.onNodeWithText(str(R.string.share_action_save)).assertIsEnabled().performClick()
        assertEquals(1, shareClicks)
        assertEquals(1, saveClicks)
    }

    @Test
    fun `the share file name follows the spec`() {
        val file = writeSharePng("2026-09-08")
        assertEquals("JustDiary-2026-09-08.png", file.name)
    }

    /** 在缓存目录里放一张 4 × 3 的真 PNG（面板会去解码它）。 */
    private fun writeSharePng(dayKey: String): java.io.File {
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val file = java.io.File(dir, "JustDiary-$dayKey.png")
        val bitmap = android.graphics.Bitmap.createBitmap(4, 3, android.graphics.Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(file).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        return file
    }

    @Test
    fun `text actions work without image rendering`() {
        var copyClicks = 0
        var shareTextClicks = 0
        rule.setContent {
            ShareSheetContent(
                rendering = true,
                imageFile = null,
                plainText = "body",
                onShare = {},
                onSaveToGallery = {},
                onCopyText = { copyClicks++ },
                onShareText = { shareTextClicks++ },
            )
        }
        rule.onNodeWithText(str(R.string.share_action_copy_text)).performClick()
        rule.onNodeWithText(str(R.string.share_action_share_text)).performClick()
        assertEquals(1, copyClicks)
        assertEquals(1, shareTextClicks)
    }
}
