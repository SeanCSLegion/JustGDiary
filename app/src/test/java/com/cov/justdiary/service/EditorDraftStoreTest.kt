package com.cov.justdiary.service

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0-1 的磁盘层：草稿能原样读回、保存/清空、坏文件自愈、过期清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorDraftStoreTest {

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: EditorDraftStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "draft-test-${System.nanoTime()}")
        store = EditorDraftStore(context, root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun draft(text: String = "草稿正文") = EditorDraft(
        contentJson = """{"parts":[{"style":"body","text":"$text"}],"v":2}""",
        startTimeUtc = 1_790_000_000_000L,
        lineIndex = 2,
        selectionStart = 3,
        selectionEnd = 3,
        endLineIndex = 2,
    )

    @Test
    fun `draft survives save and load with selection`() {
        val value = draft()
        store.save("2026-11-12#new", value)

        val loaded = store.load("2026-11-12#new")
        assertEquals(value.contentJson, loaded?.contentJson)
        assertEquals(value.startTimeUtc, loaded?.startTimeUtc)
        assertEquals(value.lineIndex, loaded?.lineIndex)
        assertEquals(value.selectionStart, loaded?.selectionStart)
        assertEquals(value.selectionEnd, loaded?.selectionEnd)
        assertEquals(value.endLineIndex, loaded?.endLineIndex)
    }

    @Test
    fun `keys do not collide`() {
        store.save("2026-11-12#new", draft("A"))
        store.save("2026-11-12#42", draft("B"))

        assertTrue(store.load("2026-11-12#new")!!.contentJson.contains("A"))
        assertTrue(store.load("2026-11-12#42")!!.contentJson.contains("B"))
    }

    @Test
    fun `clear removes only the requested draft`() {
        store.save("a", draft("A"))
        store.save("b", draft("B"))

        store.clear("a")

        assertNull(store.load("a"))
        assertTrue(store.load("b") != null)
        assertFalse(listOf(*root.listFiles().orEmpty()).isEmpty())
    }

    @Test
    fun `a corrupt draft is treated as missing and deleted`() {
        store.save("x", draft())
        val file = root.listFiles()!!.first()
        file.writeText("{not json")

        assertNull(store.load("x"))
        assertFalse("坏文件应当被删掉，避免每次打开都解析失败", file.exists())
    }

    @Test
    fun `draft image sources are protected from orphan cleanup`() {
        store.save(
            "with-image",
            draft().copy(
                contentJson = """{"parts":[{"style":"image","src":"images/img_a.jpg"},{"style":"body","text":"A"}],"v":2}""",
            ),
        )
        store.save("plain", draft("B"))

        val refs = store.referencedImageSources()
        assertEquals(setOf("images/img_a.jpg"), refs)
    }

    @Test
    fun `stale drafts are removed`() {
        store.save("old", draft())
        val old = root.listFiles()!!.first()
        old.setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        store.save("new", draft())

        store.clearStale()

        assertNull(store.load("old"))
        assertTrue(store.load("new") != null)
    }
}
