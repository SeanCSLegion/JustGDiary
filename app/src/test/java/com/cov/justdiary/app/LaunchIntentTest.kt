package com.cov.justdiary.app

import android.content.Intent
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-7 / P1-9 深链解析：通知与快捷方式共用同一套入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchIntentTest {

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
    fun `an explicit day key opens that day`() {
        consume("justdiary://editor?dayKey=2026-11-13")
        assertEquals("2026-11-13", LaunchIntent.dayKey.value)
        assertEquals(0, LaunchIntent.openToday.value)
    }

    @Test
    fun `the path form also works`() {
        consume("justdiary://editor/2026-11-13")
        assertEquals("2026-11-13", LaunchIntent.dayKey.value)
    }

    @Test
    fun `editor without a day key asks for today`() {
        consume("justdiary://editor")
        assertNull("不能把 dayKey 写死", LaunchIntent.dayKey.value)
        assertEquals(1, LaunchIntent.openToday.value)
    }

    @Test
    fun `search deep link switches to the search tab`() {
        consume("justdiary://search")
        assertEquals(1, LaunchIntent.search.value)
        assertEquals(0, LaunchIntent.openToday.value)
    }

    @Test
    fun `an invalid day key falls back to today instead of opening garbage`() {
        consume("justdiary://editor?dayKey=not-a-date")
        assertNull(LaunchIntent.dayKey.value)
        assertEquals(1, LaunchIntent.openToday.value)
    }
}
