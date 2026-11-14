package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPartStyle

/**
 * 从 [EditorModel] 拆出的纯函数分组；对外仍从 [EditorModel] 调用。
 */
internal object EditorParagraphOps {

    // ------------------------------------------------------------------ 段落级操作

    /**
     * 把 `[indices]` 上的段落格式**先取消、再应用**成 [format]。
     *
     * ## 用户 2026-09-28 规则 2 / 3 / 5 的落点
     *
     * - **六选一互斥**（规则 2）：大标题 / 小标题 / 正文 / 引用 / 列表 / 待办 是同一格属性，
     *   应用一个就自动取消另外的 —— 实现上就是"先把本行的段落格式清成正文，再写上目标"。
     *   这与 iOS 的 `restyle(..., .body, cancelCenter: true)` 顺序一致（那边也是先重置再套）。
     * - **本行已有内容也一样**（规则 3）：作用域是"本行"（有选区时是选中的每一行），
     *   已有内容不会被跳过 —— 它会被重新排版成新格式。
     * - **取消格式只取消当前行**（规则 3 后半）：本函数只动 [indices] 覆盖到的行，
     *   绝不回头改"以前已经应用过的"行。
     * - **居中的互斥方向**（规则 5）：引用 / 列表 / 待办会**顺手取消居中**
     *   （[BlockFormat.cancelsCenter]）；反过来的"取消这三类"**不恢复**居中。
     *
     * ## 两条必须守住的实现细节
     *
     * 1. ⚠️ 一律用 `copy`：段落格式只决定"这一段是什么"，**行内样式（B/I/S/U）与它无关**，
     *    必须原样跟着走（iOS `restyle`：*"preserving bold/italic traits"*）。真机缺陷
     *    （2026-09-28）："待办 + 四种行内样式"的行切一次小标题，四种样式全丢。
     * 2. ⚠️ 按区间整体重建，而不是逐下标就地修改：逐下标遍历时下标会整体错位
     *    （iOS 为此在 `toggleQuote` 里写 `ranges.reversed()`）。
     * 3. 图片行**不参与**段落格式：它没有文字，落库时是独立的 image 段
     *    （`linesToParts` 提前 return），改写它的 blockStyle 只会在往返时凭空造出一个样式。
     */
    fun applyBlockFormat(
        lines: List<EditorLine>,
        indices: IntRange,
        format: BlockFormat,
    ): List<EditorLine> {
        val range = normalize(indices, lines.size) ?: return lines
        val cancelCenter = format.cancelsCenter
        val middle = lines.subList(range.first, range.last + 1).map { line ->
            if (line.isImage) return@map line
            // ① 先取消：把本行的六选一格式清干净（正文 + 无标记 + 不勾选；引用/列表/待办还要清居中）
            // ② 再应用：写上目标格式
            line.copy(
                blockStyle = format.partStyle,
                marker = when (format) {
                    BlockFormat.List -> EditorLine.MarkerKind.Bullet
                    BlockFormat.Todo -> EditorLine.MarkerKind.Todo
                    else -> null
                },
                done = false,
                align = if (cancelCenter) null else line.align,
            )
        }
        return lines.take(range.first) + middle + lines.drop(range.last + 1)
    }

    /**
     * 切换 [format]：已经是它了 → 变回正文；否则 → 应用它（[applyBlockFormat]）。
     *
     * 方向（"已经是它了吗"）按 iOS 分两种取法：
     * - **引用**：区间内**全部**已是引用才算"已经是"（iOS `toggleQuote`）；
     * - **列表 / 待办**：只看**光标所在行**（iOS `toggleMarker`；选区只决定范围，不决定方向）。
     *
     * 大标题 / 小标题 / 正文**不做切换**：它们是段落样式菜单的项，选中即应用
     * （菜单里当前项带勾，再点一次不该把它变成正文）。
     */
    fun toggleBlockFormat(
        lines: List<EditorLine>,
        indices: IntRange,
        format: BlockFormat,
        cursorIndex: Int = indices.first,
    ): List<EditorLine> {
        val range = normalize(indices, lines.size) ?: return lines
        val already = when (format) {
            BlockFormat.Quote -> range.all { lines.getOrNull(it)?.blockStyle == ContentPartStyle.quote }
            BlockFormat.List -> lines.getOrNull(cursorIndex)?.marker == EditorLine.MarkerKind.Bullet
            BlockFormat.Todo -> lines.getOrNull(cursorIndex)?.marker == EditorLine.MarkerKind.Todo
            BlockFormat.Title, BlockFormat.Heading, BlockFormat.Body -> false
        }
        return applyBlockFormat(lines, range, if (already) BlockFormat.Body else format)
    }

    /**
     * 段落样式菜单（大标题 / 小标题 / 正文 / 引用）的入口 —— 只应用、不切换。
     *
     * 保留这个"按 partStyle 字符串"的签名，是因为落库契约里的样式名就是它
     * （`ContentPartStyle`）；互斥规则的唯一实现是 [applyBlockFormat]。
     */
    fun setBlockStyle(lines: List<EditorLine>, indices: IntRange, style: String): List<EditorLine> =
        applyBlockFormat(lines, indices, BlockFormat.ofPartStyle(style))

    private fun normalize(indices: IntRange, size: Int): IntRange? {
        if (size == 0) return null
        val first = indices.first.coerceIn(0, size - 1)
        val last = indices.last.coerceIn(0, size - 1)
        return if (first > last) last..first else first..last
    }

    /**
     * 设置/取消居中（段落级）。
     *
     * 规则 4：本行**有内容** → 整行居中/取消居中；本行**没有内容** → 改的是"光标的位置"
     * （空段落也带 `AlignmentSpan`，光标的 x 由 `Layout.getPrimaryHorizontal` 给出，
     * 居中段落里就落在行中央）。
     *
     * 规则 5：列表 / 引用 / 待办行上**不允许**居中 —— 与格式栏"任一行是那三类就把按钮置灰"
     * 完全对齐：**整段只要有**一行是那三类，这次居中就整体不生效（而不是"能居中的那几行先居中"，
     * 那种半吊子结果既不好解释、也会让"按钮灰着却改了东西"）。取消居中（`center = false`）
     * 永远允许。
     */
    fun setAlignment(lines: List<EditorLine>, indices: IntRange, center: Boolean): List<EditorLine> {
        val range = normalize(indices, lines.size) ?: return lines
        if (center && !centerAllowed(lines, range)) return lines
        return EditorModel.applyToLines(lines, range) { line ->
            line.copy(align = if (center) EditorModel.ALIGN_CENTER else null)
        }
    }

    /**
     * 居中与引用 / 列表 / 待办 / **图片**互斥（规则 5）。
     *
     * 图片行也要排除：图片段的对齐由编解码固定（`linesToParts` 对图片段**不写** `align`），
     * 于是它上面点「居中」只会让按钮亮一下、画面逐像素不变 —— 一个会说谎的死按钮。
     * iOS `centerIsAllowed` 同样把 `image` 附件与 bullet / todo 一起排除（B24）。
     */
    fun centerAllowed(line: EditorLine): Boolean =
        line.marker == null && !line.isImage && line.blockStyle != ContentPartStyle.quote

    /** 整段区间是否**每一行**都允许居中（格式栏据此置灰居中按钮）。 */
    fun centerAllowed(lines: List<EditorLine>, indices: IntRange): Boolean =
        normalize(indices, lines.size)?.all { centerAllowed(lines[it]) } ?: false

    /** 切换 [indices] 区间上的引用样式（规则 2：先取消本行其它格式，再套引用）。 */
    fun toggleQuote(lines: List<EditorLine>, indices: IntRange): List<EditorLine> =
        toggleBlockFormat(lines, indices, BlockFormat.Quote)

    /**
     * 切换列表 / 待办标记（多行选区版）。对应 iOS `toggleMarker`：
     * 开启前先把该行重置为正文（取消标题/引用/居中/另一种标记），再次点击则取消。
     */
    fun setMarkerOnRange(
        lines: List<EditorLine>,
        indices: IntRange,
        kind: EditorLine.MarkerKind,
        /** 光标所在行；**toggle 方向只看这一行**（选区只决定范围）。 */
        cursorIndex: Int = indices.first,
    ): List<EditorLine> = toggleBlockFormat(lines, indices, BlockFormat.ofMarker(kind), cursorIndex)

    /** 切换光标所在那一行的列表 / 待办标记。 */
    fun toggleMarker(lines: List<EditorLine>, index: Int, kind: EditorLine.MarkerKind): List<EditorLine> =
        toggleBlockFormat(lines, index..index, BlockFormat.ofMarker(kind), index)

    /**
     * 回车之后的修补（规范 §3.4 的四种回车行为）。
     *
     * `EditText` 只会插入一个 `\n`，标记与段落样式都不会自己延续；这里按 iOS 的
     * `shouldChangeTextIn` 规则 + 用户 2026-09-28 规则 3 把它补齐：
     *
     * | 场景 | 结果 |
     * | --- | --- |
     * | 列表 / 待办项**有内容**时回车 | 新行继承同种标记（继续加一项），待办默认未勾选 |
     * | 列表 / 待办项**空**时回车 | **结束列表**：上一行去掉标记，新行是普通空行（iOS 同） |
     * | 大 / 小标题回车 | **新行继承同一样式** —— 规则 3 的"回车之后也不取消格式，直到用户取消格式" |
     * | **引用**回车 | **有内容 → 新行继续引用；空引用行 → 结束引用**（与 iOS HEAD 同口径） |
     * | 居中的正文回车 | 新行**不继承居中**（居中只作用于"本行"，见下面的 ⚠️） |
     * | 正文回车 | 新行还是正文（= 没有样式要延续，行为与以前一致） |
     *
     * 三种"新行"都只继承**上一行**的状态，因此只对"新出现、且内容为空"的行生效 ——
     * 粘贴 / 删除 / 普通输入都不受影响。
     *
     * ## 引用：非空续引用，空行结束（2026-11-13 改为跟随 iOS HEAD）
     *
     * 用户 2026-11-12 确认：**引用回车一律跟随 iOS**。iOS 现在的口径是
     * `RichTextEngine.handleReturn`：非空引用行回车 → 新行继续引用；空引用行再回车才结束。
     * 落库形态因此是 `quote,quote,body`（iOS UI 测试断言）。
     *
     * 此前 Android 是"有内容回车 → 新行回正文"（用户 2026-10-01 的旧口径），现已按
     * "跟 iOS" 的统一决策改掉：
     *
     * - **本行有内容**：平台正常换行，新行**继续引用**（就是这里的 `above.blockStyle == quote`
     *   且 `above.text` 非空的分支）。
     * - **本行是空的引用行**：回车被 [blocksNewline] 吃掉，本行变回正文
     *   （[endQuoteOnBlockedNewline]）—— 不新增行，光标回到列的最左边，引用到此结束。
     *
     * 居中空行的回车在**更早一步**就被 [blocksNewline] 吃掉了（规则 4），走不到这里；
     * 万一走到（例如粘贴带进来的换行），也是"新行继承居中"，与 iOS 的
     * "段落属性随换行延续"一致。
     */
    fun repairAfterEnter(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> {
        if (lines.size <= previous.size) return lines
        val result = lines.toMutableList()
        for (index in result.indices) {
            val line = result[index]
            if (line.text.isNotEmpty() || line.marker != null || index == 0) continue
            val above = result[index - 1]
            when {
                // 空条目再回车 → 结束列表：上一行的标记去掉，这一行保持普通空行
                above.marker != null && above.text.isEmpty() ->
                    result[index - 1] = above.copy(marker = null, done = false)

                // 有条目的列表 / 待办 → 继续加一项
                above.marker != null ->
                    result[index] = line.copy(marker = above.marker, done = false)

                // 引用回车 → **新行继续引用**（跟 iOS HEAD）。空引用行会被
                // `blocksNewline` 先吃掉，走不到这里；万一走到也按"结束引用"处理。
                above.blockStyle == ContentPartStyle.quote && above.text.isNotEmpty() ->
                    result[index] = line.copy(blockStyle = ContentPartStyle.quote, align = null)

                above.blockStyle == ContentPartStyle.quote ->
                    result[index] = line.copy(blockStyle = ContentPartStyle.body, align = null)

                // 非标记行 → **块样式跟着换行延续**（规则 3：标题"回车之后也不取消格式"）。
                //
                // iOS 是靠"段落属性挂在终止换行上"天然延续的；Android 这边
                // `Spannable` 的块 span 虽然也覆盖终止换行，但新插入的 `\n` 会让**旧 span**
                // 向后长一格，新行的行首反而没有 span 起点 —— 解析回来就是正文。
                // 所以这里在模型层显式补上（随后 [EditorTextState] 会按模型回写平台文本）。
                // 上一行是正文时不改任何东西，因此不必特判。
                //
                // ⚠️ **居中不在这里延续**（`align` 不带下去）。iOS 的段落属性确实会随换行延续，
                // 但用户 2026-09-28 的规则 4 把居中的作用域写成"本行"，且真机实测反馈
                // （"居中的作用范围不对劲……取消的时候没有真的取消，不知道是不是和换行有关系"）——
                // 就是这一条：居中一旦被换行继承，取消时只取消光标那一行，上面几行还留着居中，
                // 看起来像"没取消掉"。所以居中的延续只在"用户显式点居中"时发生。
                else ->
                    result[index] = line.copy(blockStyle = above.blockStyle)
            }
        }
        return repairSplitAtParagraphStart(result, previous)
    }

    /**
     * **在段落行首按回车**留下的那个空行：段落样式该归谁（用户 2026-10-01 补充的口径）。
     *
     * 用户原话：*"如果在引用的段前输入回车换行，现在是让上一个空行也是引用格式。但是我希望的是
     * 那个空行应该是没有引用格式的。因为如果是引用行回车换行之后会自动取消引用，所以，理论上是
     * 没有引用的空行的。"*
     *
     * ## 平台为什么会那样（Robolectric 实测，见 `docs/migration-progress.md` R33）
     *
     * 段落 span 用"起点包含、终点不包含"（[ParagraphSpanFlags]），于是把光标放在
     * 行首敲回车时，新插入的 `\n` **落在 span 里面** —— 段落 span 连同字号 / 对齐 / 引用内缩 /
     * 列表缩进一起"漏"到了上面那个空行上，而真正有文字的那一行反而因为 span 起点不再等于行首，
     * 解析回来掉成正文：
     *
     * ```
     * "Q"(quote) --段首回车--> 平台文本 "\nQ"、span [0,2)
     *   解析：[""(quote), "Q"(body)]     ← 空行顶了样式、正文丢了样式
     *   目标：[""(body),  "Q"(quote)]    ← 引用不留在空行上，样式跟文字走
     * ```
     *
     * 这里按"上一版这一行是什么"把归属纠正回来（只在**行数正好 +1**、也就是一次拆分时动手）：
     *
     * - **空行**：引用 → 正文（用户口径：引用行回车会自动取消引用，所以不该有空引用行）；
     *   大标题 / 小标题 → **保留**（规则 3 的"回车之后也不取消格式"，与"先在空行上选大标题再回车"
     *   是同一条）；**居中对齐一律不留在空行上**（规则 4：居中的作用域是本行）；
     * - **被顶下去的那一行**：把上一版的段落样式与对齐**接回来** —— 少了这一步，
     *   "在标题行首回车"会把标题悄悄变成正文（落库就错了）。
     */
    private fun repairSplitAtParagraphStart(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> {
        if (lines.size != previous.size + 1) return lines
        val result = lines.toMutableList()
        var changed = false
        for (index in 0 until lines.size - 1) {
            val above = lines[index]
            val pushed = lines[index + 1]
            if (pushed.text.isEmpty() || pushed.marker != null || pushed.isImage) continue
            // 上一版里**同一个下标**是"带着这一段样式的那一行"，且文字正好被顶到了下一行
            val before = previous.getOrNull(index) ?: continue
            if (before.text != pushed.text) continue
            if (before.blockStyle == ContentPartStyle.body && before.align == null) continue
            if (isBlankLine(above)) {
                result[index] = above.copy(
                    blockStyle = if (before.blockStyle == ContentPartStyle.quote) {
                        ContentPartStyle.body
                    } else {
                        before.blockStyle
                    },
                    align = null,
                )
            }
            result[index + 1] = pushed.copy(blockStyle = before.blockStyle, align = before.align)
            changed = true
        }
        return if (changed) result else lines
    }

    /** 兼容旧名字（D9 的继承规则已经并入 [repairAfterEnter]）。 */
    fun inheritMarkersForNewLines(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> = repairAfterEnter(lines, previous)

    /**
     * "在空行上开始输入"时，把**段落样式与对齐**续上（规范 §3.2 的空行规则）。
     *
     * 富文本里空行没有字符可以挂样式：末行的空段连终止换行都没有，于是从 `EditText`
     * 解析回来的新行会退化成正文。这里按上一版的状态把它接过来 ——
     * 用户在空行上点了「大标题」再输入，得到的必须是标题。
     *
     * 只在"上一版是空行、这一版有字、且解析结果没有任何段落样式"时生效，
     * 因此不会覆盖用户粘贴进来的样式。
     */
    fun carryOverStylesWhenTyping(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> = lines.mapIndexed { index, line ->
        val before = previous.getOrNull(index)
        val startedTyping = before != null && before.text.isEmpty() && line.text.isNotEmpty()
        if (startedTyping && line.blockStyle == ContentPartStyle.body && line.align == null) {
            line.copy(blockStyle = before.blockStyle, align = before.align)
        } else {
            line
        }
    }

    /**
     * **空行**（没有文字、没有标记）的段落格式，从上一版接过来 —— [carryOverStylesWhenTyping] 的另一半。
     *
     * ## 为什么必须有这一条
     *
     * 段落 span 只能挂在字符上。文档**末行**如果是空的，它一个字符都没有（终止换行算上一段的边界），
     * 从 `EditText` 解析回来必然退化成正文 —— 于是两件本该成立的事会失效：
     *
     * 1. **把一行的文字删空**（规则 3 的"直到用户取消格式"；iOS 的 `typingAttributes`
     *    在删除之后也不重置）：删光大标题再输入，应该还是大标题；
     * 2. **空行本来就是"待输入样式"的载体**：在空行上选了大标题 / 引用之后再敲回车，
     *    这一行还是大标题 / 引用（规则 3："回车之后也不取消格式"）—— 少了这一条，
     *    回车会把这一行悄悄打回正文。
     *
     * 只在这一版解析出来是**正文且不居中**时生效，所以不会覆盖用户敲进去的或粘贴进来的样式；
     * 非空行、标记行、图片行一律不碰。**每一步文本变更后都跑**（而不是只在行数变化时），
     * 因为"样式丢了"这件事与行数变化无关。
     */
    fun keepStylesForBlankLines(
        lines: List<EditorLine>,
        previous: List<EditorLine>,
    ): List<EditorLine> = lines.mapIndexed { index, line ->
        val before = previous.getOrNull(index) ?: return@mapIndexed line
        if (line.isImage || line.marker != null || line.text.isNotEmpty()) return@mapIndexed line
        if (line.blockStyle == ContentPartStyle.body && line.align == null) {
            line.copy(blockStyle = before.blockStyle, align = before.align)
        } else {
            line
        }
    }

    // ------------------------------------------------------------------ 回车与空行（规则 4）

    /**
     * 是不是一行"什么内容都没有"的空行。
     *
     * **带标记的空条目（`• `）不算**：标记本身就是这一行的内容，用户还要在上面打字，
     * 回车在那里的含义是"结束列表"（[repairAfterEnter]，iOS 同）。
     */
    fun isBlankLine(line: EditorLine?): Boolean =
        line != null && line.text.isEmpty() && line.marker == null && line.imageSrc == null

    /**
     * 这一次回车要不要**吃掉**（用户 2026-09-28 定稿，与 iOS 逐条对齐）。
     *
     * | 条件 | 出处 |
     * | --- | --- |
     * | 本行是空行，且**本行居中** | iOS `shouldBlockNewline`（`RichTextView.swift:191-196`、`RichTextEngine.swift:567-571`）：*"居中的空行回车 → 直接忽略，防止空居中行无限堆叠"* —— 用户原话"**居中的时候不让有空行**" |
     * | 本行是空行，且**本行是引用** | iOS：*"空引用行回车 → 消费按键，结束引用"* —— 用户原话"**按照 iOS 的结束引用**"（见 [endQuoteOnBlockedNewline]） |
     *
     * ⚠️ **不居中的空行不受任何限制**（用户原话："但是如果不是居中的时候，是不做限制的"）：
     * 想按几个空行就按几个空行。早先按"本行空 && 上一行也空"实现过一版，已按这句推翻。
     */
    fun blocksNewline(lines: List<EditorLine>, index: Int): Boolean {
        val line = lines.getOrNull(index) ?: return false
        if (!isBlankLine(line)) return false
        return line.align == EditorModel.ALIGN_CENTER || line.blockStyle == ContentPartStyle.quote
    }

    /**
     * 被吃掉的那次回车**顺手要做的事**：空引用行上的回车 = iOS 的"结束引用"
     * （只重置这一行的段落样式，不动已输入的内容 —— 这一行本来就是空的）。
     *
     * 返回**同一个列表实例**表示不用回写平台文本；返回新列表表示模型变了。
     */
    fun endQuoteOnBlockedNewline(lines: List<EditorLine>, index: Int): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        if (!isBlankLine(line) || line.blockStyle != ContentPartStyle.quote) return lines
        return lines.toMutableList().also { it[index] = line.copy(blockStyle = ContentPartStyle.body) }
    }
}
