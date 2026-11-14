package com.cov.justdiary.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchHistoryStoreRobolectricTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `add dedupes and keeps most recent first`() {
        val store = SearchHistoryStore(context)
        store.clear()
        store.add("a")
        store.add("b")
        store.add("a")
        assertEquals(listOf("a", "b"), store.load())
    }

    @Test
    fun `store keeps at most ten entries`() {
        val store = SearchHistoryStore(context)
        store.clear()
        for (i in 1..12) store.add("k$i")
        val loaded = store.load()
        assertEquals(SearchHistoryStore.MAX, loaded.size)
        assertEquals("k12", loaded.first())
    }

    @Test
    fun `clear empties the list`() {
        val store = SearchHistoryStore(context)
        store.add("x")
        store.clear()
        assertEquals(emptyList<String>(), store.load())
    }
}
