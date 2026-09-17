package com.cov.justdiary.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDate
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 日期语义槽位测试。
 *
 * 模板**不是**在测试里手抄的，而是直接读 `res/values/date_formats.xml` 与
 * `res/values-zh-rCN/date_formats.xml` —— 于是这份测试同时守住两件事：
 * 资源文件里的模板内容，以及把模板与月名/星期名拼起来的逻辑。
 *
 * 期望值取自 iOS `Localizable.xcstrings` 的中英文案与 `docs/design-system.md` §4 的槽位表。
 */
class DateFormatsTest {

    private val zh = Locale.forLanguageTag("zh-CN")
    private val en = Locale.forLanguageTag("en")

    /** 2026-09-15 是周二（与设计文档里的示例同一天）。 */
    private val day = LocalDate.of(2026, 9, 15)

    private fun templates(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("找不到资源文件：${file.absolutePath}", file.exists())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            result[element.getAttribute("name")] = element.textContent
        }
        return result
    }

    private fun dateFormats(locale: Locale, uses24Hour: Boolean, resourcePath: String) =
        DateFormats(locale, uses24Hour) { key ->
            templates(resourcePath)[key] ?: error("资源里缺少模板：$key")
        }

    private fun zhFormats(uses24Hour: Boolean = true) =
        dateFormats(zh, uses24Hour, "src/main/res/values-zh-rCN/date_formats.xml")

    private fun enFormats(uses24Hour: Boolean = true) =
        dateFormats(en, uses24Hour, "src/main/res/values/date_formats.xml")

    // ------------------------------------------------------------------ 中文

    @Test
    fun `chinese date slots match the ios strings`() {
        val f = zhFormats()
        assertEquals("2026年", f.year(2026))
        assertEquals("九月", f.monthFull(day))
        assertEquals("9月", f.monthName(day))
        assertEquals("周二", f.weekdayShort(day))
        assertEquals("2026年 九月", f.monthTitle(2026, day))
        assertEquals("9月15日", f.dateOnly(day))
        assertEquals("9月15日 周二", f.dayTitle(day))
        assertEquals("2026年9月15日 周二", f.weekHeaderTitle(day))
        assertEquals("9月1日 – 9月15日", f.dateRange(LocalDate.of(2026, 9, 1), day))
    }

    @Test
    fun `chinese relative day labels match the ios strings`() {
        val f = zhFormats()
        assertEquals("今天", f.relativeDayLabel(day, day))
        assertEquals("昨天", f.relativeDayLabel(day.minusDays(1), day))
        assertEquals("明天", f.relativeDayLabel(day.plusDays(1), day))
        assertEquals("3天前", f.relativeDayLabel(day.minusDays(3), day))
        assertEquals("3天后", f.relativeDayLabel(day.plusDays(3), day))
    }

    @Test
    fun `chinese start line matches the ios strings`() {
        val f = zhFormats()
        assertEquals("21:00 开始写", f.startLine("21:00", ""))
        assertEquals("21:00 · 于深圳市开始写", f.startLine("21:00", "深圳市"))
    }

    @Test
    fun `chinese hour wheel labels follow the ios ranges`() {
        val f = zhFormats()
        assertEquals("凌晨 0:00", f.dayStartLabel(0))
        assertEquals("凌晨 1:00", f.dayStartLabel(1))
        assertEquals("凌晨 5:00", f.dayStartLabel(5))
        assertEquals("上午 6:00", f.dayStartLabel(6))
        assertEquals("上午 11:00", f.dayStartLabel(11))
        assertEquals("中午 12:00", f.dayStartLabel(12))
        assertEquals("下午 1:00", f.dayStartLabel(13))
        assertEquals("下午 5:00", f.dayStartLabel(17))
        assertEquals("晚上 6:00", f.dayStartLabel(18))
        assertEquals("晚上 11:00", f.dayStartLabel(23))
    }

    // ------------------------------------------------------------------ 英文

    @Test
    fun `english date slots match the ios strings`() {
        val f = enFormats()
        assertEquals("2026", f.year(2026))
        assertEquals("September", f.monthFull(day))
        assertEquals("Sep", f.monthName(day))
        assertEquals("Tue", f.weekdayShort(day))
        assertEquals("September 2026", f.monthTitle(2026, day))
        assertEquals("Sep 15", f.dateOnly(day))
        assertEquals("Tue, Sep 15", f.dayTitle(day))
        assertEquals("Tue, Sep 15, 2026", f.weekHeaderTitle(day))
        assertEquals("Sep 1 – Sep 15", f.dateRange(LocalDate.of(2026, 9, 1), day))
    }

    @Test
    fun `english relative day labels match the ios strings`() {
        val f = enFormats()
        assertEquals("Today", f.relativeDayLabel(day, day))
        assertEquals("Yesterday", f.relativeDayLabel(day.minusDays(1), day))
        assertEquals("Tomorrow", f.relativeDayLabel(day.plusDays(1), day))
        assertEquals("3 days ago", f.relativeDayLabel(day.minusDays(3), day))
        assertEquals("in 3 days", f.relativeDayLabel(day.plusDays(3), day))
    }

    @Test
    fun `english start line matches the ios strings`() {
        val f = enFormats()
        assertEquals("Started at 9:00 PM", f.startLine("9:00 PM", ""))
        assertEquals("Started at 9:00 PM in Shenzhen", f.startLine("9:00 PM", "Shenzhen"))
    }

    @Test
    fun `english hour wheel labels follow the ios ranges`() {
        val f = enFormats()
        assertEquals("12:00 AM", f.dayStartLabel(0))
        assertEquals("1:00 AM", f.dayStartLabel(1))
        assertEquals("6:00 AM", f.dayStartLabel(6))
        assertEquals("12:00 PM", f.dayStartLabel(12))
        assertEquals("1:00 PM", f.dayStartLabel(13))
        assertEquals("6:00 PM", f.dayStartLabel(18))
    }

    // ------------------------------------------------------------------ 小时制

    @Test
    fun `hour format follows the device and am pm follows the app language`() {
        // 设备 24 小时制
        assertEquals("21:00", enFormats(uses24Hour = true).timeOf(21, 0))
        assertEquals("9:05", enFormats(uses24Hour = true).timeOf(9, 5))
        assertEquals("21:00", zhFormats(uses24Hour = true).timeOf(21, 0))

        // 设备 12 小时制：英文用 AM/PM
        assertEquals("9:00 PM", enFormats(uses24Hour = false).timeOf(21, 0))
        assertEquals("9:05 AM", enFormats(uses24Hour = false).timeOf(9, 5))
        assertEquals("12:00 AM", enFormats(uses24Hour = false).timeOf(0, 0))
        assertEquals("12:00 PM", enFormats(uses24Hour = false).timeOf(12, 0))

        // 设备 12 小时制：中文用中文的时段词
        val zhTwelve = zhFormats(uses24Hour = false)
        assertTrue("中文 12 小时制应使用中文时段词：${zhTwelve.timeOf(21, 0)}", zhTwelve.timeOf(21, 0).contains("下午"))
        assertTrue("中文 12 小时制应使用中文时段词：${zhTwelve.timeOf(9, 0)}", zhTwelve.timeOf(9, 0).contains("上午"))
    }

    // ------------------------------------------------------------------ 其他

    @Test
    fun `invalid template does not crash`() {
        // 模板要求两个占位符、调用只给一个参数 → 必须被兜住而不是崩掉
        val f = DateFormats(en, true) { "%1\$d年%2\$s" }
        assertEquals("%1\$d年%2\$s", f.year(2026))
    }

    @Test
    fun `weekday names follow the configured week start`() {
        assertEquals(
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日"),
            DateFormats.weekdayNames(zh, "monday"),
        )
        assertEquals(
            listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六"),
            DateFormats.weekdayNames(zh, "sunday"),
        )
        assertEquals(
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
            DateFormats.weekdayNames(en, "monday"),
        )
    }

    @Test
    fun `language resolves to the expected locale`() {
        assertEquals("zh-CN", DateFormats.localeFor("zh", en).toLanguageTag())
        assertEquals("en", DateFormats.localeFor("en", zh).toLanguageTag())
        assertEquals("zh-CN", DateFormats.localeFor("system", zh).toLanguageTag())
        assertEquals("en", DateFormats.localeFor("system", en).toLanguageTag())
    }

    @Test
    fun `resource keys cover every slot used by the formatter`() {
        val expectedKeys = setOf(
            DateFormats.KEY_YEAR, DateFormats.KEY_MONTH_TITLE, DateFormats.KEY_DAY_ONLY,
            DateFormats.KEY_DAY_TITLE, DateFormats.KEY_RANGE, DateFormats.KEY_WEEK_HEAD,
            DateFormats.KEY_TODAY, DateFormats.KEY_YESTERDAY, DateFormats.KEY_TOMORROW,
            DateFormats.KEY_DAYS_AGO, DateFormats.KEY_DAYS_LATER, DateFormats.KEY_START_TIME,
            DateFormats.KEY_START_TIME_LOC, DateFormats.KEY_MIDNIGHT, DateFormats.KEY_DAWN,
            DateFormats.KEY_MORNING, DateFormats.KEY_NOON, DateFormats.KEY_AFTERNOON,
            DateFormats.KEY_EVENING,
        )
        assertEquals(expectedKeys, DateFormats.RESOURCE_IDS.keys)

        for (path in listOf("src/main/res/values/date_formats.xml", "src/main/res/values-zh-rCN/date_formats.xml")) {
            val available = templates(path).keys
            assertEquals("$path 缺少或多出 key", expectedKeys, available)
        }
    }
}
