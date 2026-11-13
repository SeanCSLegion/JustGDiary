package com.cov.justdiary.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P2-8：草稿在真实 filesDir 上的保存/恢复/清理。 */
@RunWith(AndroidJUnit4::class)
class EditorDraftStoreInstrumentedTest {

    private lateinit var root: File
    private lateinit var store: EditorDraftStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "instrumented-drafts-${System.nanoTime()}")
        store = EditorDraftStore(context, root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `draft survives process-like save and load`() {
        val draft = EditorDraft(
            contentJson = """{"parts":[{"style":"body","text":"instrumented draft"}],"v":2}""",
            startTimeUtc = 1_790_000_000_000L,
            lineIndex = 1,
            selectionStart = 4,
            selectionEnd = 4,
            endLineIndex = 1,
        )
        store.save("2026-11-13#new", draft)
        val loaded = store.load("2026-11-13#new")
        assertEquals(draft.contentJson, loaded?.contentJson)
        assertEquals(draft.selectionStart, loaded?.selectionStart)
        store.clear("2026-11-13#new")
        assertNull(store.load("2026-11-13#new"))
    }
}
