package com.cov.justdiary.ui.home

import androidx.compose.foundation.background
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cov.justdiary.R
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.Lunar
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.Radius
import java.time.LocalDate
import java.util.Locale

/**
 * 日历的**月格 / 周条**：每个日期都是一个真实的 Compose 组件。
 *
 * ## 为什么不再用 Canvas（首轮迁移最大的缺陷）
 *
 * 首轮把 iOS 的 `Canvas` 绘制逐字搬了过来。iOS 那边用
 * `.accessibilityRepresentation` / `accessibilityAdjustableAction` 单独补语义；
 * Android 的 `Canvas` 是**一个**节点，所有日期在无障碍树里根本不存在 ——
 * TalkBack 只能读到月份标题，日历完全不可用（实测 `uiautomator dump` 里
 * 一个日期都没有）。
 *
 * 改成组件之后一次解决三件事：
 *  1. **无障碍**：每格一个可聚焦节点，带完整日期、今天/选中/有日记状态与点击动作；
 *  2. **版面**：格子高度就是布局值，居中交给 Compose，不再靠"把圆画在哪"；
 *  3. **点击**：不再需要把 px 反算成 dp 的命中测试（[CalendarLayout.dayAt] 仍保留，
 *     给纯数学单测用）。
 *
 * 几何参数（格宽、格高、字号）依然来自 [CalendarLayout] 的纯函数，所以
 * `CalendarLayoutTest` / `CalendarMorphTest` 仍守着这套版面的数值。
 */
@Composable
fun CalendarDayGrid(
    rows: List<List<LocalDate>>,
    anchorMonth: LocalDate,
    metrics: CalendarLayout.DayMetrics,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否画出相邻月的日期。
     *
     * **默认 false**（规范 §5.2）：iOS 的月历只画当月日期，相邻月的位置留空。
     * 首轮默认 true，于是 8 月 31 日、10 月 1 日… 全都画了出来（实测缺陷 D4）。
     */
    showAdjacent: Boolean = false,
    showLunar: Boolean = true,
    /**
     * 相邻月日期的透明度（**读取器**，不传则恒为 1）。
     *
     * 月↔周 morph 里只有被点中的那一行会带相邻月日期，而且要按进度淡入
     * （对应 iOS 的 `adjacentAlpha`）—— `p = 0` 时它们完全透明，morph 的第一帧
     * 才能与月视图逐像素一致。传 lambda 是为了在 `graphicsLayer` 的延迟阶段读它，
     * 不让那 3~4 个格子每帧重组。
     */
    adjacentAlpha: () -> Float = { 1f },
    /** 行之间是否画分隔线（周条不画）。 */
    showDividers: Boolean = true,
    /**
     * 第一行**上方**也画分割线（月份开始前的那一条）。
     * 月列表里除第一个月之外的每个月都传 true（用户："一个月的开始前和结束后都是有分割线的"）。
     */
    dividerAboveFirst: Boolean = false,
    /**
     * 日期格改由**整块 Canvas** 绘制（连续月历流用；周条 / morph 层仍走真组件）。
     *
     * 真机实测：正常帧 8ms，但"新月份首次挂载"那一帧 120~365ms，且**布局/放置段
     * 95~684ms、组合段 0ms** —— 成本在 ~90 个 `Text` 组件的测量/放置。
     * 打开后这些文本改由 [drawDayCell] 一次性画完（复用 morph 层那套几何），
     * 每个格子只留一个透明的语义/命中节点。
     */
    canvasCells: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    val dayFontSize: TextUnit = DynamicTypeMetrics.calendarSp(metrics.dayFont.dp, fontScale)
    val lunarFontSize: TextUnit = DynamicTypeMetrics.calendarSp(metrics.lunarFont.dp, fontScale)
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    // ⚠️ 这 4 条资源**必须提在这一层**：原来写在 `CalendarDayCell` 里，而
    // `stringResource` 是**每个组合实例各查一次**（不是全局 memo）—— 一屏 180 个格子
    // 就是 720 次 Resources 查询，正好落在"挂载新月份"那一帧（真机实测该帧 257ms）。
    // 提到行/网格这一层之后，一次组合只查 4 次。
    val scheme = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer(cacheSize = GRID_TEXT_CACHE)
    val textLayouts = MorphTextLayouts.shared
    val todayLabel = stringResource(R.string.index_today)
    val selectedLabel = stringResource(R.string.index_selected)
    val hasDiaryLabel = stringResource(R.string.index_has_diary)
    val noDiaryLabel = stringResource(R.string.index_no_diary)

    // ⚠️ **画在 Column 之前**：这一层是"格子的视觉"，而 Column 里那些节点只负责语义与
    // 命中（`drawVisuals = false` 时它们不画任何东西）。顺序反了的话，某些设备/合成路径下
    // 会出现"形状画了但被后画的内容盖掉"。
    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
    if (canvasCells) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rowPx = metrics.cellH * density
            val cellPx = metrics.cellW * density
            rows.forEachIndexed { row, week ->
                week.forEachIndexed { col, date ->
                    val inMonth =
                        date.year == anchorMonth.year && date.monthValue == anchorMonth.monthValue
                    if (!inMonth && !showAdjacent) return@forEachIndexed
                    drawDayCell(
                        day = date,
                        col = col,
                        rowY = row * metrics.cellH * density,
                        cellW = cellPx,
                        cellH = rowPx,
                        m = metrics,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        measurer = textMeasurer,
                        textLayouts = textLayouts,
                        fontScale = fontScale,
                        primary = scheme.primary,
                        onPrimary = scheme.onPrimary,
                        onSurface = scheme.onSurface,
                        onSurfaceVariant = scheme.onSurfaceVariant,
                        // 排版完全由字号驱动，这里只需要告诉它"要不要画农历"
                        hasLunar = showLunar && metrics.lunarAlpha > 0.01f,
                        t = 1f,
                    )
                }
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEachIndexed { row, week ->
            val m = metrics
            // 每一行都是**独立的 `CalendarDayGrid` 调用**（连续月历流里一行一块、
            // morph 层里一行一层），所以行与行之间的位移由调用方的 `graphicsLayer` 负责，
            // 这里只按自然排布画一行。
            //
            // 分隔线：只画在"这一行至少有一个当月日期"的位置。
            // 不能用 `!showAdjacent || ...` 短路 —— showAdjacent=false 时那会变成恒真，
            // 于是整行都是相邻月的空行也会画一条线（实测缺陷 D12）。
            //
            // 这一行里**真正属于当月**的列区间 —— 分割线只跨这段，不是整行。
            // （用户："分割线范围到日期为限，而不是一整行"。
            //   例：11 月 1 号落在周日，首行只有最右一格有日期，线就只画在那一格上。）
            // 只用首尾两列表达区间（原来是 `indices.filter`，每行多分配一个 List）。
            var firstCol = -1
            var lastCol = -1
            for (i in week.indices) {
                val d = week[i]
                if (d.year == anchorMonth.year && d.monthValue == anchorMonth.monthValue) {
                    if (firstCol < 0) firstCol = i
                    lastCol = i
                }
            }
            val monthCols = firstCol..lastCol
            val drawDivider = showDividers && m.dividerAlpha > 0.01f && firstCol >= 0 &&
                (row > 0 || dividerAboveFirst)
            val rowH = m.cellH.dp

            // ⚠️ 分割线画在**行自己的顶部**（叠层），**不占布局高度**。
            //
            // 原来它是 Column 里的一个兄弟节点并 `flowY += 1f`，于是网格实际高度 =
            // 行数 × 行高 + (行数 − 1)，比容器多出 4~5dp —— **最后一行被挤出容器、
            // 农历被切掉**（用户实测："最后一行日期的农历显示还是有被遮挡"）。
            // 改成叠层后高度恒等于 `行数 × 行高`，与连续月历流的块高、morph 的行距
            // 完全一致（动了会让 morph 的度量推导失配）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowH),
            ) {
            if (drawDivider) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        // 列宽直接用本行的度量（`DayMetrics.cellW`），不必再去量布局
                        .padding(start = (monthCols.first() * m.cellW).dp)
                        .padding(end = ((7 - monthCols.last() - 1) * m.cellW).dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(outlineVariant.copy(alpha = 0.35f * m.dividerAlpha)),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowH),
            ) {
                week.forEach { date ->
                    val inMonth =
                        date.year == anchorMonth.year && date.monthValue == anchorMonth.monthValue
                    if (!inMonth && !showAdjacent) {
                        // 相邻月：留空 —— 不绘制、不可点、**不进无障碍树**（规范 §5.10）
                        Box(modifier = Modifier.weight(1f).height(rowH))
                        return@forEach
                    }
                    // ⚠️ `dayKeyOf` **只算一次**再传下去。原来在这一段里被调用 3 次
                    //（isSelected / isToday / hasDiary），`CalendarDayCell` 内部又算第 4 次 ——
                    // 而它每次都要 `StringBuilder` 拼一个 10 字符的串。一屏 180 格 × 4 = 720 次。
                    val dayKey = DateUtil.dayKeyOf(date)
                    CalendarDayCell(
                        date = date,
                        dayKey = dayKey,
                        drawVisuals = !canvasCells,
                        showLunar = showLunar,
                        isSelected = dayKey == selectedKey,
                        isToday = dayKey == todayKey,
                        hasDiary = flags.contains(dayKey),
                        todayKey = todayKey,
                        dayFontSize = dayFontSize,
                        lunarFontSize = lunarFontSize,
                        circleDiameter = m.circleDiameter.dp,
                        diaryMarkWidth = minOf(20f, m.cellW * 0.5f).dp,
                        enabled = true,
                        todayLabel = todayLabel,
                        selectedLabel = selectedLabel,
                        hasDiaryLabel = hasDiaryLabel,
                        noDiaryLabel = noDiaryLabel,
                        onClick = { onDayClick(date) },
                        modifier = Modifier
                            .weight(1f)
                            // 相邻月：按 `adjacentAlpha` 淡入（morph 的选中行用）。
                            // 延迟读取 —— 进度变化只让这一层失效，格子不重组。
                            .then(
                                if (inMonth) Modifier
                                else Modifier.graphicsLayer { alpha = adjacentAlpha() },
                            ),
                    )
                }
            }
            }
        }
    }
    }
}

/** canvas 模式的文本测量缓存条数（日号 1..31 + 农历，512 足够覆盖相邻月）。 */
private const val GRID_TEXT_CACHE = 512

/** 量农历盒子高度的探针文本（与 `YearMonthMorph` 同一个）。 */
private const val LUNAR_PROBE_TEXT = "初一"

/**
 * 单个日期格。
 *
 * 视觉规格（`docs/design-spec.md` §5.2）：
 *  - 选中 → `primary` 实心圆 + `onPrimary` 日号；
 *  - 今天 → `primary` 描边圆环；
 *  - 有日记 → 日号下方 2dp 圆角短横线；
 *  - 相邻月 / 未来 → 降透明度（**仍可点**）。
 *
 * 圆的直径由 `metrics.circleDiameter` 派生（`min(cellW−3, 内容高+14, cellH−3)`），
 * 与纯数学层同源；原来写死 40dp，行高一变就会和相邻格相碰。
 */
@Composable
internal fun CalendarDayCell(
    date: LocalDate,
    /** `YYYY-MM-DD`，由调用方算好（一屏 180 格，重复计算 4 次很贵）。 */
    dayKey: String,
    /** `false` = 本格只做**语义 + 命中**，视觉由外层 Canvas 画（见 [CalendarDayGrid.canvasCells]）。 */
    drawVisuals: Boolean = true,
    showLunar: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    hasDiary: Boolean,
    todayKey: String,
    dayFontSize: TextUnit,
    lunarFontSize: TextUnit,
    /** 选中/今天的圆底直径（dp）。由 `metrics.circleDiameter` 派生，与纯数学层同源。 */
    circleDiameter: Dp,
    /** "有日记"下划线的宽度（dp）：`min(20, cellW × 0.5)`（与 iOS 一致）。 */
    diaryMarkWidth: Dp,
    enabled: Boolean,
    /** 无障碍状态词（由 `CalendarDayGrid` 统一取，见那里的说明）。 */
    todayLabel: String,
    selectedLabel: String,
    hasDiaryLabel: String,
    noDiaryLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = dayKey
    val isFuture = key > todayKey

    // `dayTitleFor` 要跑两次 `getDisplayName`（格式化的 ICU），按日期缓存即可。
    val dateLabel = remember(date) { DateFormats.dayTitleFor(date) }

    // 无障碍串只在状态变化时重拼（原来每次重组都 buildList + joinToString）。
    val stateLabel = remember(isToday, isSelected, hasDiary, todayLabel, selectedLabel, hasDiaryLabel, noDiaryLabel) {
        buildList {
            if (isToday) add(todayLabel)
            if (isSelected) add(selectedLabel)
            add(if (hasDiary) hasDiaryLabel else noDiaryLabel)
        }.joinToString(" · ")
    }

    // 文字**不压 alpha**：M3 的强调层级用语义色表达，不用透明度。
    // 实测 0.35 alpha 的日号在浅色下只有 2.19:1，连"大字 3:1"都不到（WCAG AA / Material 都不允许）；
    // `onSurfaceVariant` 是 M3 的"中等强调"角色，浅色下约 8.6:1。
    val dayColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.primary
        isFuture -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val markColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            // 撑满整行：格子高度 = 行高，内容在里面垂直居中。
            // 不写 fillMaxHeight 时 Box 只包住内容（日号 + 农历 ≈ 70dp），
            // 于是内容贴在行顶、下面空一截，分隔线看起来离内容很远。
            .fillMaxHeight()
            // ⚠️ **`indication = null`：格子不要涟漪/状态层**。
            //
            // 用户 2026-09-27："月视图和周视图的点击特效有点多余了，去掉吧"
            //（年历那边更早就去过一次：点卡片缩放的动画本身就是反馈）。
            // 日历格是**高频、密排**的点按目标，涟漪会出现两组：
            // ① 与"选中日"的实心圆叠加，看起来像点出了两个圆；
            // ② 在连续流里滚动时，指尖划过的格子会各亮一下。
            // 点击本身仍有反馈 —— 选中圆会立刻移到新日期。
            //
            // `interactionSource` 保留：`clickable` 需要它来派发 press/release，
            // 无障碍（TalkBack 的"已激活"播报）也走它；只是不再画出来。
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            // 日格里只有装饰性内容（日号、农历、标记点），真正的语义全在**这一个**节点上：
            // 用 `clearAndSetSemantics` 把子节点的文本全部清掉，再挂上日期、状态与点击动作
            // （点击动作来自外层 `clickable`，会保留下来）。这样一屏只读到 35 个日期节点，
            // 实测 `uiautomator dump` 里每格是 `Button + clickable="true"`。
            //
            // 状态**同时**写进 contentDescription 与 stateDescription：uiautomator 与部分
            // 朗读器只认 contentDescription，实测 stateDescription 不会被 dump 出来。
            .clearAndSetSemantics {
                contentDescription = "$dateLabel · $stateLabel"
                stateDescription = stateLabel
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!drawVisuals) return@Box
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            // 与下面农历的 `-LUNAR_TIGHTEN` 配对，让"日号 + 农历"这一组在格子里
            // （也就是分隔线之间）**光学居中**。
            // ⚠️ 必须用 `offset`（不参与布局）而不是 `padding` —— 加 padding 会把 Column 的
            // 布局高度撑大 6dp，内容正好顶到格子下沿，**农历又被裁掉**（实测踩到）。
            modifier = Modifier.offset(y = CalendarLayout.LUNAR_TIGHTEN.dp / 2f),
        ) {
            Box(
                modifier = Modifier.size(circleDiameter),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                } else if (isToday) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .border(
                                CalendarLayout.TODAY_RING_WIDTH.dp,
                                MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                    )
                }
                Text(
                    text = date.dayOfMonth.toString(),
                    fontSize = dayFontSize,
                    color = dayColor,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
            if (showLunar) {
                Text(
                    text = Lunar.dayLabelCached(date),
                    fontSize = lunarFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    // 日号底下那个圆比数字本身高一大截，农历因此离得很远（用户反馈"间隔太大"）。
                    // 往上提 LUNAR_TIGHTEN，把这段空档收掉。
                    modifier = Modifier.offset(y = -CalendarLayout.LUNAR_TIGHTEN.dp),
                )
            }
            // ⚠️ 标记**始终占位**：只在 `hasDiary` 时才组合的话，
            // 有日记的格子内容会整体比别的格子高 3dp、看着不齐（用户提醒"注意保留标记位置"）。
            DiaryMark(color = markColor, width = diaryMarkWidth, visible = hasDiary)
        }
    }
}

/** "有日记"标记：2dp 高、1dp 圆角的短横线（对应 iOS 的 `Radius.hairline`）。 */
@Composable
private fun DiaryMark(color: Color, width: Dp, visible: Boolean = true) {
    Box(
        modifier = Modifier
            .padding(top = 1.dp)
            .size(width = width, height = 2.dp),
    ) {
        if (visible) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(color, DiaryUnderlineShape),
            )
        }
    }
}

/**
 * 星期栏。M3 规范：`labelMedium` + `onSurfaceVariant` + 居中。
 *
 * 与 iOS 一致地走"半速动态字体"（格子尺寸由屏幕决定，星期名不能全速放大，
 * 否则会撑破固定高度的条带）。
 */
@Composable
fun CalendarWeekdayHeader(
    weekStart: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    val labels = remember(weekStart) {
        DateFormats.weekdayInitials(Locale.getDefault(), weekStart)
    }
    val height =
        if (compact) CalendarLayout.COMPACT_WEEKDAY_HEADER_H else CalendarLayout.WEEKDAY_HEADER_H
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { _ , label ->
            // 规范 §4.4：星期列**不做特殊配色**（曾经按"周末降一档"算过一个 isWeekend，
            // 但从未用在颜色上，注释与代码相反，已删除）
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                // 而星期栏是 11sp 小字，需要 4.5:1）。M3 的 `onSurfaceVariant` 本身
                // 就是"列标题"这一层强调，直接用即可。
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                // 规范 §4.4：星期栏 13sp（原来写 11 且覆盖了 labelMedium 的 12）
                fontSize = DynamicTypeMetrics.calendarSp(13.dp, fontScale),
                maxLines = 1,
            )
        }
    }
}

/** "有日记"下划线的形状（文件级常量：原来是每格新建一个 `RoundedCornerShape`）。 */
private val DiaryUnderlineShape = RoundedCornerShape(Radius.hairline)
