package com.cov.justdiary.ui.home

import com.cov.justdiary.model.DateUtil

/**
 * 首页三态导航与图层交班的**纯决策**。
 *
 * 从 [HomeScreen] 的组合体里抽出来：这些函数只吃日期/状态、返回下一个状态，
 * 不读 Compose、不写状态，因此可以在 JVM 上逐条断言；HomeScreen 只负责把结果
 * 写回 `monthKey` / `selectedKey` 并触发跳转或 morph。
 */
internal object HomeCalendarNavigation {

    /** 上下滑翻月 / 无障碍动作：按 [CalendarGrid.MONTH_KEYS] 整月移动；越界返回 null。 */
    fun stepMonthKey(current: Int, delta: Int): Int? {
        val keys = CalendarGrid.MONTH_KEYS
        val target = keys.indexOf(current) + delta
        return if (target in keys.indices) keys[target] else null
    }

    /** 年态翻年：只换月份键，不碰连续流的滚动位置。 */
    fun pageMonthKey(current: Int, delta: Int): Int? = stepMonthKey(current, delta)

    /** 无障碍「前一天 / 后一天」：返回新的 day_key；解析失败返回 null。 */
    fun stepDayKey(current: String, delta: Int): String? {
        val day = DateUtil.parseDayKey(current) ?: return null
        return DateUtil.dayKeyOf(day.plusDays(delta.toLong()))
    }

    /** 某 day_key 所属的月份键；解析失败返回 null。 */
    fun monthKeyOfDay(dayKey: String): Int? =
        DateUtil.parseDayKey(dayKey)?.let { CalendarGrid.monthKeyOf(it) }

    /**
     * 分栏落定后把选中日带到目标月份的同一天（日期溢出时夹到月末）。
     * 返回 null 表示选中日无法解析。
     */
    fun carrySelectionKey(selectedKey: String, targetMonthKey: Int): String? {
        val selected = DateUtil.parseDayKey(selectedKey) ?: return null
        val first = CalendarGrid.monthFirstOfMonthKey(targetMonthKey)
        val day = selected.dayOfMonth.coerceAtMost(first.lengthOfMonth())
        return DateUtil.dayKeyOf(first.withDayOfMonth(day))
    }

    /** 周态横向翻周：返回 (新的 day_key, 所属月份键)；解析失败返回 null。 */
    fun pageWeek(selectedKey: String, delta: Int): Pair<String, Int>? {
        val day = DateUtil.parseDayKey(selectedKey) ?: return null
        val next = day.plusDays(7L * delta)
        return DateUtil.dayKeyOf(next) to CalendarGrid.monthKeyOf(next)
    }

    /** 年历点月：目标月的月份键。 */
    fun monthKeyFor(year: Int, month: Int): Int = CalendarGrid.monthKey(year, month)

    /**
     * 顶栏文案跟哪一层走：周 morph 期间按“屏幕上占主导的那一层”显示，
     * 而不是按下那一刻就换成目标形态（否则顶栏会凭空闪现）。
     */
    fun topBarMode(
        mode: HomeViewMode,
        weekMorphing: Boolean,
        weekProgress: Float,
    ): HomeViewMode = when {
        !weekMorphing -> mode
        weekProgress >= 0.5f -> HomeViewMode.WEEK
        else -> HomeViewMode.MONTH
    }

    /** 月流真实图层何时可画：月态、非年 morph、周进度回到 0。 */
    fun showMonthFlow(
        mode: HomeViewMode,
        yearMorphing: Boolean,
        weekProgress: Float,
    ): Boolean = mode == HomeViewMode.MONTH && !yearMorphing && weekProgress <= 0f

    /** 真实周层何时接手：周态且 morph 已到终点。 */
    fun showWeekLayer(mode: HomeViewMode, weekProgress: Float): Boolean =
        mode == HomeViewMode.WEEK && weekProgress >= 1f
}
