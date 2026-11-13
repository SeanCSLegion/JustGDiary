package com.cov.justdiary.app

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** P2-8：真设备上的三种深链解析（通知 / 快捷方式 / 指定日）。 */
@RunWith(AndroidJUnit4::class)
class LaunchIntentInstrumentedTest {

    private fun consume(url: String) {
        LaunchIntent.consume(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    @After
    fun tearDown() {
        LaunchIntent.clear()
        LaunchIntent.clearOpenToday()
        LaunchIntent.clearSearch()
    }

    @Test
    fun `editor with day key opens that day`() {
        consume("justdiary://editor?dayKey=2026-11-13")
        assertEquals("2026-11-13", LaunchIntent.dayKey.value)
    }

    @Test
    fun `notification editor link asks for today`() {
        consume("justdiary://editor")
        assertNull(LaunchIntent.dayKey.value)
        assertEquals(1, LaunchIntent.openToday.value)
    }

    @Test
    fun `search shortcut link asks for the search tab`() {
        consume("justdiary://search")
        assertEquals(1, LaunchIntent.search.value)
    }
}
