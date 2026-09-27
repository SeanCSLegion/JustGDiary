package com.cov.justdiary.ui.home

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/**
 * morph 期间复用的文本测量表。
 *
 * ## 为什么不能直接用 `TextMeasurer` 的缓存
 *
 * 真机实测（MEIZU 21 / 120Hz，`-morph-log`）：年↔月的 600ms 里 UI 线程只画出 13~25 帧
 * （30~40fps），其中**每帧 12~18ms 花在 `TextMeasurer.measure` 上**：一次动画 390~450 次
 * 调用、每次 0.4~0.8ms，即使命中缓存也要 0.42ms（缓存键是 `TextLayoutInput` +
 * `CacheTextLayoutInput` 两层含十余个字段的 `equals`/`hashCode`，比一次查表贵得多）。
 *
 * 而真正需要重算的量很小：日号只有 1~31 这 31 个字符串，一次动画里字号只跳几档，
 * 颜色/字重只有 2~4 种组合 —— 也就是每个字号档只需要 31 次布局，之后全部复用。
 *
 * 于是这里按「文本 + 字号档 + 字重 + 颜色」自己存一张表。⚠️ **alpha 绝不能进键**：
 * 它是逐帧连续值（`metrics.lunarAlpha`），带进去等于每帧都是新键、缓存完全失效；
 * 透明度要用 `drawText(alpha = …)` 传。
 *
 * 全页只有一份（[shared]），跨帧、跨动画复用 —— 见下面的说明。
 */
class MorphTextLayouts {

    companion object {
        /**
         * 首页**整页共用**的一份布局表。
         *
         * ⚠️ 不要改成 `remember { MorphTextLayouts() }` 挂在 morph 层 / 年历层里：
         * 那两个 composable 在动画期间会被重新挂载，`remember` 随之重建、缓存清零 ——
         * 实测每帧 30 次测量全部落到"未命中"（0.4ms/次 ≈ 12ms/帧）。
         * 挂在页面级（这个单例）才能跨帧命中；表会随字号档增多缓慢增长，无需手动清理。
         */
        val shared = MorphTextLayouts()
    }

    private val cache = HashMap<String, TextLayoutResult>()

    /** 临时探针：真实测量（未命中）次数 —— 用来判断"新月份第一次画"贵在哪。 */
    var misses: Int = 0
        private set

    /**
     * 取一条布局；没有就量一次并记住。
     *
     * @param style 只取其中的**排版相关**字段（字号、字重）；`color` 会被带上（`drawText`
     *   不能单独覆盖颜色，`TextLayoutResult` 自带样式），但 alpha **不要**带进来 ——
     *   动画里的 alpha 是连续值，带上就退化成"每帧都是新键"。
     */
    fun layout(
        measurer: TextMeasurer,
        text: String,
        fontSize: TextUnit,
        fontWeight: FontWeight = FontWeight.Normal,
        color: Color = Color.Unspecified,
        style: TextStyle = TextStyle.Default,
    ): TextLayoutResult {
        // ⚠️ 量化必须在**实际用于测量的那个数**上做：字号先按 dp 量化到 0.5dp，
        // 再经 `calendarSp`（× fontScale、sp→em）转成 sp，浮点乘除会带来 ±1e-4 的抖动
        // （12.5dp → 12.499999sp），于是"同一档字号"每帧算出的键都不一样 —— 缓存全 miss。
        val quantized = kotlin.math.round(fontSize.value * 8f) / 8f
        val key = text + "|" + quantized + "|" + fontWeight.weight + "|" + color.value.toString(16)
        cache[key]?.let { return it }
        misses++
        val result = measurer.measure(
            text = text,
            style = style.copy(fontSize = fontSize, fontWeight = fontWeight, color = color),
        )
        cache[key] = result
        return result
    }

    fun clear() = cache.clear()

    val size: Int get() = cache.size

}
