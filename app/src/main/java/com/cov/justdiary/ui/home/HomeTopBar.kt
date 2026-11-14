package com.cov.justdiary.ui.home

import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.ui.components.AssistPill
import com.cov.justdiary.ui.components.PillVariant
import com.cov.justdiary.ui.theme.Spacing
import java.time.LocalDate

/**
 * 首页顶栏：月/年标题、今天胶囊与最小化标题文案。
 *
 * 从 [HomeScreen] 拆出；只读状态 + 回调，不持有业务逻辑。
 */

// ---------------------------------------------------------------------- 顶栏

/**
 * 首页顶栏（规范 §3）：两枚 M3 **assist chip**。
 *
 * | 位置 | 变体 | 月态 | 周态 | 年态 |
 * | --- | --- | --- | --- | --- |
 * | 左 | `Outlined` + 前导 `chevron_left` | `2026年`（→ 年历） | `九月`（→ 回月态） | **整枚隐藏** |
 * | 右 | `Elevated` + 前导 `calendar_today` | 相对日 / 具体日期（→ 回到今天） | 同左 | 同左 |
 *
 * **这里没有"月↔周"按钮**：iOS 没有那个入口，而且它的图标在月/周两态下完全一样，
 * 摆在「今天」旁边只会被读成第二个日历入口（缺陷 D7）。
 *
 * 年态隐藏左胶囊时必须另留退路：**系统返回键**（`PredictiveBackHandler`）与**点迷你月**
 * —— iOS 只有后者，Android 用户满屏找不到返回会先按系统返回键。
 */
@Composable
internal fun HomeTopBar(
    leftLabel: String,
    showLeft: Boolean,
    todayLabel: String,
    onLeftClick: () -> Unit,
    onToday: () -> Unit,
    mode: HomeViewMode = HomeViewMode.MONTH,
) {
    // 左胶囊的无障碍文案要**跟着动作走**：月态点它是进年历，周态才是回月历
    val a11yLeftLabel = stringResource(
        if (showLeft && mode == HomeViewMode.MONTH) R.string.a11y_back_to_year else R.string.a11y_back_to_month,
    )
    val a11yTodayLabel = stringResource(R.string.a11y_scroll_to_today)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CalendarLayout.HEADER_H.dp)
            // 8dp + 48dp 胶囊 + 8dp = 64dp = HEADER_H。
            // M3 的 chip 布局高是 48dp（`minimumInteractiveComponentSize`，触控目标硬要求），
            // 所以这里不能再按"视觉 40dp"去配 12dp 上边距，否则整条会超到 72dp。
            .padding(horizontal = Spacing.page, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeft) {
            AssistPill(
                text = leftLabel,
                onClick = onLeftClick,
                leading = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                variant = PillVariant.Outlined,
                modifier = Modifier.semantics { contentDescription = a11yLeftLabel },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // 不带前导图标（用户反馈：不要日历图标）。可见文案会在
        // 今天 / 昨天 / 3 天后 / 9月26日 之间变，因此用 stateDescription 承载它，
        // contentDescription 固定为「回到今天」，TalkBack 的语义才稳定。
        AssistPill(
            text = todayLabel,
            onClick = onToday,
            // **与左胶囊同款**（用户反馈："今天按钮的样式似乎和年份月份的按钮样式
            // 不太一样，统一一下"）。同一根顶栏上两枚胶囊用两种填充是没道理的 ——
            // `Elevated` 的浅底+阴影让它看起来比左胶囊"高一级"，但两者只是
            // "导航"与"回到今天"的区别，没有主次。
            variant = PillVariant.Outlined,
            modifier = Modifier.semantics {
                contentDescription = a11yTodayLabel
                stateDescription = todayLabel
            },
        )
    }
}

/**
 * 右胶囊的文案（规范 §3.3，**Android 的一处改进**）。
 *
 * ```
 * 选中日属于当前显示的月  → 相对日（今天 / 昨天 / 明天 / 3 天前 …）
 * 否则                    → 具体日期（9月26日）
 * ```
 *
 * iOS 竖屏翻月不改选中日，于是可能出现"屏幕上是 12 月、胶囊却写着『今天』"的误导。
 * Android 保留"翻月不改选中日"（翻月是纯浏览，不该改变写入目标），但让胶囊在两者
 * 脱节时说实话。
 */
@Composable
internal fun todayPillLabel(
    selectedDate: LocalDate,
    today: LocalDate,
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String {
    val sameMonth = selectedDate.year == monthFirst.year &&
        selectedDate.monthValue == monthFirst.monthValue
    if (!sameMonth) {
        return formats?.dateOnly(selectedDate)
            ?: context.getString(R.string.date_day_only, selectedDate.monthValue, selectedDate.dayOfMonth)
    }
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, selectedDate).toInt()
    return when {
        days == 0 -> stringResource(R.string.index_today)
        days == -1 -> stringResource(R.string.index_yesterday)
        days == 1 -> stringResource(R.string.index_tomorrow)
        days < -1 -> stringResource(R.string.index_days_ago, -days)
        else -> stringResource(R.string.index_days_later, days)
    }
}

/**
 * 顶栏左侧胶囊的文案。
 *
 * 月态只放**年份**（规范 §3）：月份由下面 72dp 的 32sp 大标题承担 —— 首轮把
 * "2026年 九月"整串塞进顶栏，结果既没有大标题、顶栏又比 iOS 重（缺陷 D5 的一部分）。
 */
internal fun homeTitle(
    mode: HomeViewMode,
    year: Int,
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = when (mode) {
    HomeViewMode.YEAR, HomeViewMode.MONTH ->
        formats?.year(year) ?: context.getString(R.string.date_year, year)
    // 周态左侧是"返回月历"，文案用月份名（与 iOS 的 `titleText` 一致）
    HomeViewMode.WEEK -> formats?.monthFull(monthFirst)
        ?: context.getString(R.string.date_month_short, monthFirst.monthValue)
}

/** 月历大标题的文案（`九月` / `September`）。 */
/**
 * 月历里**内联**的月份标签：中文数字（`11月`）、英文缩写（`Sep`）。
 *
 * 直接用 [`DateFormats.monthName`]（= `TextStyle.SHORT`）—— 它本来就是这个用途
 * （`date_day_only` / `date_day_title` 也用它），`DateFormatsTest` 里已经断言了
 * `9月` / `Sep` 两种输出，不需要另加一个函数。
 * 固定区（钉住的大标题）用 [monthTitleText]，即 `monthFull`（`十一月` / `September`）。
 */
internal fun monthCompactTitleText(
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = formats?.monthName(monthFirst)
    ?: context.getString(R.string.date_month_short, monthFirst.monthValue)

internal fun monthTitleText(
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = formats?.monthFull(monthFirst)
    ?: context.getString(R.string.date_month_short, monthFirst.monthValue)
