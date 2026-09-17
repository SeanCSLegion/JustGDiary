package com.cov.justdiary.data

import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.service.FtsSegment
import com.cov.justdiary.service.SearchUtil

/**
 * 搜索语句的构造，**逐字对齐** iOS `DiaryRepository.runSearch`。
 *
 * 单独抽成纯函数是为了能对最容易出错的部分（SQL 文本 + **绑定参数顺序**）做 JVM 单测 ——
 * 绑定顺序错了不会有编译错误，只会静默返回错误结果，是这类代码最典型的 bug。
 *
 * 语义要点：
 *  - 关键词按空白切成若干词项，**词项之间是"与"**；
 *  - FTS 侧每个词项被切分成 CJK 单字后包成短语（`"深 圳"`），多个短语用空格连接（FTS5 隐含 AND）；
 *  - 同时提供 `LIKE ... ESCAPE '\'` 兜底，两者取 OR；
 *  - 没有关键词时不加正文条件（只按时间/地点筛选）；
 *  - 排序只有 `day_key DESC`，**没有 rank/BM25**（与 iOS 一致）。
 *
 * 与 iOS 的唯一实现差异：`LIMIT` / `OFFSET` 以**字面量内联**而不是占位符 ——
 * Android 的 `SQLiteDatabase.rawQuery` 只接受 `Array<String>` 选择参数，
 * 而这两个值是内部整数（非用户输入），内联可以保证它们以整数类型参与比较。
 */
data class SearchSql(
    val whereSql: String,
    /** `LIMIT`/`OFFSET` 之外的全部绑定参数，顺序必须与 [whereSql] 里的 `?` 一一对应。 */
    val countArgs: List<String>,
    val snippetSql: String,
    val listSql: String,
    /** snippet 子查询的 fts 参数（若有）在前，其余与 [countArgs] 相同。 */
    val listArgs: List<String>,
) {
    companion object {

        fun build(
            keyword: String,
            fromKey: String,
            toKey: String,
            filter: LocFilter,
            offset: Int,
            ftsEnabled: Boolean,
            limit: Int = JustDiarySchema.SEARCH_LIMIT,
        ): SearchSql {
            val terms = SearchUtil.terms(keyword)

            val whereParts = mutableListOf("d.day_key >= ?", "d.day_key <= ?")
            val args = mutableListOf(fromKey, toKey)

            var ftsQuery = ""
            if (terms.isNotEmpty()) {
                ftsQuery = terms.joinToString(separator = " ") { term ->
                    "\"" + FtsSegment.segment(term.replace("\"", "")) + "\""
                }
                val likeParts = terms.map { "d.search_text LIKE ? ESCAPE '\\'" }
                // 与 iOS 的差异（有意修正）：LIKE 兜底也用去掉引号后的词，
                // 否则带引号的输入在 LIKE 分支永远匹配不到任何东西。
                val likeArgs = terms.map { "%" + SearchUtil.escapeLikeTerm(it.replace("\"", "")) + "%" }
                val matchSql = if (ftsEnabled) {
                    "EXISTS (SELECT 1 FROM diary_fts WHERE diary_fts.diary_id = d.id AND diary_fts MATCH ?)"
                } else {
                    "1=0"
                }
                whereParts.add("($matchSql OR (${likeParts.joinToString(" AND ")}))")
                // 与 iOS 的差异（有意修正）：iOS 无条件 append ftsQuery，但 ftsEnabled=false 时
                // matchSql 是 `1=0`、**没有那个占位符** —— 参数会比占位符多一个，
                // 在 Android 上会直接抛 SQLiteException（iOS 的绑定失败被静默忽略，属于潜在缺陷）。
                if (ftsEnabled) args.add(ftsQuery)
                args.addAll(likeArgs)
            }

            if (!filter.noLoc && filter.country.isNotEmpty()) {
                whereParts.add(
                    "EXISTS (SELECT 1 FROM edit_block b WHERE b.diary_id = d.id AND b.country = ? AND b.region1 = ?)",
                )
                args.add(filter.country)
                args.add(filter.region1)
            }

            if (filter.noLoc) {
                whereParts.add(JustDiarySchema.NO_LOC_SQL.trim())
            }

            val whereSql = whereParts.joinToString(" AND ")

            val snippetSql = if (ftsEnabled && terms.isNotEmpty()) {
                "(SELECT snippet(diary_fts, 1, '<hl>', '</hl>', '…', ${SearchUtil.SNIPPET_TOKENS}) " +
                    "FROM diary_fts WHERE diary_fts.diary_id = d.id AND diary_fts MATCH ? LIMIT 1)"
            } else {
                "''"
            }

            val listArgs = mutableListOf<String>()
            if (ftsEnabled && terms.isNotEmpty()) listArgs.add(ftsQuery)
            listArgs.addAll(args)

            val safeLimit = (limit + 1).coerceAtLeast(1)
            val safeOffset = offset.coerceAtLeast(0)
            val listSql = """
                SELECT d.id AS id, d.day_key AS day_key, d.summary AS summary, d.updated_utc AS updated_utc,
                       $snippetSql AS snip
                FROM diary d
                WHERE $whereSql
                ORDER BY d.day_key DESC LIMIT $safeLimit OFFSET $safeOffset;
            """.trimIndent()

            return SearchSql(
                whereSql = whereSql,
                countArgs = args.toList(),
                snippetSql = snippetSql,
                listSql = listSql,
                listArgs = listArgs.toList(),
            )
        }

        fun countSql(whereSql: String): String = "SELECT COUNT(*) AS cnt FROM diary d WHERE $whereSql;"
    }
}
