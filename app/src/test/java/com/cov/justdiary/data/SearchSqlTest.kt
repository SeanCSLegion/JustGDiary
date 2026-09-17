package com.cov.justdiary.data

import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.LocFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索语句构造测试。
 *
 * 这里守的是**最不容易发现、后果又最严重**的一类错误：SQL 文本与绑定参数顺序不一致 ——
 * 参数错位不会编译报错，只会静默返回错误结果，甚至把地名绑到日期区间上。
 */
class SearchSqlTest {

    private val from = "0000-01-01"
    private val to = "9999-12-31"

    @Test
    fun `no keyword filters only by date range`() {
        val sql = SearchSql.build("", from, to, LocFilter(), offset = 0, ftsEnabled = true)

        assertEquals("d.day_key >= ? AND d.day_key <= ?", sql.whereSql)
        assertEquals(listOf(from, to), sql.countArgs)
        assertEquals("''", sql.snippetSql)
        assertEquals(listOf(from, to), sql.listArgs)
        assertTrue(sql.listSql.contains("ORDER BY d.day_key DESC LIMIT 201 OFFSET 0"))
    }

    @Test
    fun `keyword terms are segmented into cjk phrases and bound in order`() {
        val sql = SearchSql.build("深圳 天气", from, to, LocFilter(), offset = 40, ftsEnabled = true)

        val ftsQuery = "\"深 圳\" \"天 气\""
        assertTrue("FTS 短语应写在 WHERE 里：${sql.whereSql}", sql.whereSql.contains("diary_fts MATCH ?"))
        assertTrue("LIKE 兜底应对每个词各一组", sql.whereSql.contains("d.search_text LIKE ? ESCAPE '\\'"))
        // 参数顺序：时间段 → fts 查询 → 各词的 LIKE 参数
        assertEquals(listOf(from, to, ftsQuery, "%深圳%", "%天气%"), sql.countArgs)
        // 列表参数：snippet 的 fts 查询在最前，然后是 WHERE 的同一组参数
        assertEquals(listOf(ftsQuery, from, to, ftsQuery, "%深圳%", "%天气%"), sql.listArgs)
        assertTrue(sql.listSql.contains("LIMIT 201 OFFSET 40"))
    }

    @Test
    fun `snippet uses the fts5 signature with highlight markers and token budget`() {
        val sql = SearchSql.build("深圳", from, to, LocFilter(), offset = 0, ftsEnabled = true)
        assertTrue(sql.snippetSql.contains("snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24)"))
    }

    @Test
    fun `double quotes in a term are stripped before quoting`() {
        val sql = SearchSql.build("\"深圳\"", from, to, LocFilter(), offset = 0, ftsEnabled = true)
        assertEquals(listOf(from, to, "\"深 圳\"", "%深圳%"), sql.countArgs)
    }

    @Test
    fun `like wildcards in terms are escaped`() {
        val sql = SearchSql.build("50%_x", from, to, LocFilter(), offset = 0, ftsEnabled = true)
        assertEquals("%50\\%\\_x%", sql.countArgs[3])
    }

    @Test
    fun `without fts the match clause is a contradiction and snippet is empty`() {
        val sql = SearchSql.build("深圳", from, to, LocFilter(), offset = 0, ftsEnabled = false)

        assertTrue("没有 FTS 时 MATCH 分支必须恒假：${sql.whereSql}", sql.whereSql.contains("1=0"))
        assertEquals("''", sql.snippetSql)
        // 没有 snippet 子查询 → 不需要额外的 ftsQuery 参数
        assertEquals(listOf(from, to, "%深圳%"), sql.listArgs)
    }

    @Test
    fun `country filter binds country then region`() {
        val sql = SearchSql.build(
            keyword = "",
            fromKey = from,
            toKey = to,
            filter = LocFilter(country = "中国", region1 = "广东省"),
            offset = 0,
            ftsEnabled = true,
        )
        assertTrue(sql.whereSql.contains("EXISTS (SELECT 1 FROM edit_block b"))
        assertEquals(listOf(from, to, "中国", "广东省"), sql.countArgs)
    }

    @Test
    fun `country filter matches only the country when region is empty`() {
        val sql = SearchSql.build(
            keyword = "",
            fromKey = from,
            toKey = to,
            filter = LocFilter(country = "日本", region1 = ""),
            offset = 0,
            ftsEnabled = true,
        )
        assertEquals(listOf(from, to, "日本", ""), sql.countArgs)
    }

    @Test
    fun `no location filter uses the shared sql and binds nothing extra`() {
        val sql = SearchSql.build(
            keyword = "",
            fromKey = from,
            toKey = to,
            filter = LocFilter(noLoc = true),
            offset = 0,
            ftsEnabled = true,
        )
        assertTrue(sql.whereSql.contains("NOT EXISTS (SELECT 1 FROM edit_block b"))
        assertTrue(sql.whereSql.contains("b.loc_quality <> 'none'"))
        assertEquals(listOf(from, to), sql.countArgs)
    }

    @Test
    fun `no location filter ignores the country filter`() {
        val sql = SearchSql.build(
            keyword = "",
            fromKey = from,
            toKey = to,
            filter = LocFilter(country = "中国", region1 = "广东省", noLoc = true),
            offset = 0,
            ftsEnabled = true,
        )
        assertFalse("互斥：noLoc 时不应再加国家条件", sql.whereSql.contains("b.country = ?"))
        assertEquals(listOf(from, to), sql.countArgs)
    }

    @Test
    fun `negative offset is clamped and page size is limit plus one`() {
        val sql = SearchSql.build("", from, to, LocFilter(), offset = -5, ftsEnabled = true, limit = 10)
        assertTrue(sql.listSql.contains("LIMIT 11 OFFSET 0"))
    }

    @Test
    fun `count sql wraps the same where clause`() {
        val sql = SearchSql.build("深圳", from, to, LocFilter(), offset = 0, ftsEnabled = true)
        assertEquals(
            "SELECT COUNT(*) AS cnt FROM diary d WHERE ${sql.whereSql};",
            SearchSql.countSql(sql.whereSql),
        )
    }

    @Test
    fun `page size follows the schema constant`() {
        assertEquals(200, JustDiarySchema.SEARCH_LIMIT)
        assertEquals(3, JustDiarySchema.SEARCH_INDEX_VERSION)
    }
}
