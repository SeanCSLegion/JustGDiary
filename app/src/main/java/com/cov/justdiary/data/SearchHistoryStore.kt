package com.cov.justdiary.data

import android.content.Context
import org.json.JSONArray

/**
 * 最近搜索关键词（P3）。
 *
 * 只存**已提交**的关键词（点搜索 / 回车），不存输入框里的半成品；
 * 最多 [MAX] 条，重复词提到最前。用 JSON 存一个字符串数组，顺序稳定。
 */
class SearchHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i, "").trim()
                    if (value.isNotEmpty()) add(value)
                }
            }.take(MAX)
        }.getOrDefault(emptyList())
    }

    /** 记住一个词；返回新的完整列表。 */
    fun add(keyword: String): List<String> {
        val term = keyword.trim()
        if (term.isEmpty()) return load()
        val next = (listOf(term) + load().filter { it != term }).take(MAX)
        prefs.edit().putString(KEY, JSONArray(next).toString()).apply()
        return next
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val MAX = 10
        private const val PREFS = "search_history"
        private const val KEY = "keywords"
    }
}
