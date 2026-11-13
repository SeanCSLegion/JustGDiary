package com.cov.justdiary.app

import android.content.Context
import android.util.Xml
import com.cov.justdiary.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * P1-9：静态快捷方式必须恰好两个，且深链分别是不带 dayKey 的 editor / search。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShortcutsRobolectricTest {

    @Test
    fun `two shortcuts point at the editor and search deep links`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val parser = context.resources.getXml(R.xml.shortcuts)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)

        val ids = mutableListOf<String>()
        val data = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "shortcut" -> ids += parser.getAttributeValue(ANDROID_NS, "shortcutId").orEmpty()
                "intent" -> data += parser.getAttributeValue(ANDROID_NS, "data").orEmpty()
            }
        }
        assertEquals(listOf("write_today", "search_diary"), ids)
        assertEquals(listOf("justdiary://editor", "justdiary://search"), data)
        assertTrue(
            "写今天的快捷方式不能写死 dayKey（打开时才按 dayStartHour 算）",
            data.first().substringAfter("justdiary://editor").isEmpty(),
        )
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
