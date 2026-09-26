package com.cov.justdiary.data

import java.time.DateTimeException
import java.time.LocalDate

/**
 * 自定义时间范围两端的输入解析。
 *
 * 为什么需要它：起止两端原来是**纯文本框**，输入什么就原样存进 `DayKeyRange`，
 * 而范围过滤是**字符串比较**。于是：
 *
 * - 输入 `2026-9-1`：与 `2026-09-01` 的字典序不同（`'9' > '0'`），
 *   区间算出来是空的 → 界面上表现为"没有找到匹配的日记"，用户完全不知道是自己写错了；
 * - 输入 `2026-02-30`：不存在的日期也照收，同样静默变空；
 * - 两端写反：靠调用方交换来兜，但交换后的字符串仍是未归一化的。
 *
 * 这里把"用户可能怎么写"都收下来（`/`、`.`、无分隔的 8 位、不补零的月日），
 * 统一成 `YYYY-MM-DD`，并在无法构成真实日期时返回 `null` 让界面报错。
 */
object DayKeyInput {

    /** 输入框的占位符（也是规范里的示例写法）。 */
    const val PLACEHOLDER = "2026-09-01"

    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2200

    /**
     * 归一化成 `YYYY-MM-DD`；无法构成合法日期时返回 `null`。
     *
     * 接受：`2026-09-01` · `2026-9-1` · `2026/9/1` · `2026.9.1` · `20260901`。
     */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val parts = when {
            text.any { it == '-' || it == '/' || it == '.' } ->
                text.split('-', '/', '.').filter { it.isNotEmpty() }

            text.length == 8 && text.all(Char::isDigit) -> listOf(
                text.substring(0, 4),
                text.substring(4, 6),
                text.substring(6, 8),
            )

            else -> return null
        }
        if (parts.size != 3) return null

        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year !in MIN_YEAR..MAX_YEAR) return null

        // `LocalDate.of` 会挡掉 2026-02-30、2026-13-01 这类"格式对但日期不存在"的输入
        return try {
            LocalDate.of(year, month, day).toString()
        } catch (e: DateTimeException) {
            null
        }
    }

    /** 归一化后转成 `LocalDate`（给日期选择器当初始值用）；非法返回 `null`。 */
    fun parse(raw: String): LocalDate? = normalize(raw)?.let { LocalDate.parse(it) }

    /**
     * 两端都归一化，并在写反时**交换**（不报错：用户把先后写反是很自然的手误）。
     *
     * 任一端非法返回 `null`，由界面在对应输入框上报错。
     */
    fun normalizeRange(from: String, to: String): Pair<String, String>? {
        val start = normalize(from) ?: return null
        val end = normalize(to) ?: return null
        return if (start <= end) start to end else end to start
    }
}
