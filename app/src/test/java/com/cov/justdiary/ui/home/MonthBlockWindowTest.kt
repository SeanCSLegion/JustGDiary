package com.cov.justdiary.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * **回归**：连续月历流"挪挂载窗口"时，已挂载的月份块**不能被重建**。
 *
 * 为什么值得一条用例：真机上"跨月顿一下"实测就是这件事 ——
 * 一次挪窗口把**整窗 9 块**全部重组 + 重录，一帧 91~157ms（`-morph-log` 逐帧日志 逐帧日志）。
 * 根因是循环形状（见 [MonthBlockWindow] 的注释）：`for (i in range) { blocks.getOrNull(i) ?: continue }`
 * 会让 Compose 的 movable group 匹配不回已有组；换成直接下标后只剩新挂进来的那一块。
 *
 * 这里直接驱动**生产代码里的** [MonthBlockWindow]，所以循环形状一旦被改回去，用例立刻红。
 */
@RunWith(RobolectricTestRunner::class)
// 与其它 Robolectric 用例同一 SDK 档：默认档（36+）在本机 JDK 25 上会踩
// `ApplicationSharedMemory` 的 FileDescriptor 拦截问题（与 Compose 无关）。
@Config(sdk = [35])
class MonthBlockWindowTest {

    @get:Rule
    val rule = createComposeRule()

    private class Log {
        val composes = HashMap<Int, Int>()
        val ids = HashMap<Int, Int>()
        var nextId = 1
    }

    private fun block(index: Int) = MonthFlowLayout.Block(
        index = index,
        key = 202600 + index,
        monthFirst = LocalDate.of(2026, 1, 1).plusMonths(index.toLong()),
        top = index * 400f,
        rowCount = 5,
        bottom = index * 400f + 5 * 72f,
    )

    @Composable
    private fun Item(block: MonthFlowLayout.Block, log: Log) {
        val id = remember { log.nextId++ }
        log.composes[block.key] = (log.composes[block.key] ?: 0) + 1
        log.ids[block.key] = id
        Box(Modifier.size(10.dp))
    }

    @Test
    fun `挪窗口时只有新挂进来的那块被组合，其余块的槽位保住`() {
        val log = Log()
        val blocks = (0..40).map { block(it) }
        var setRange: (IntRange) -> Unit = {}
        rule.setContent {
            var range by remember { mutableStateOf(10..18) }
            setRange = { range = it }
            Box {
                MonthBlockWindow(blocks = blocks, range = range) { b -> Item(b, log) }
            }
        }
        rule.waitForIdle()
        val idOf12 = log.ids[blocks[12].key]

        // 尾部追加一块：只该组合新块
        var before = HashMap(log.composes)
        setRange(10..19)
        rule.waitForIdle()
        assertEquals(
            "尾部追加只该组合新块",
            setOf(blocks[19].key),
            log.composes.filter { (k, v) -> (before[k] ?: 0) != v }.keys,
        )

        // 头部丢一块 + 尾部加一块（真机滚动时每次跨月就是这样）：也只该组合新块
        before = HashMap(log.composes)
        setRange(11..20)
        rule.waitForIdle()
        assertEquals(
            "挪窗口只该组合新块，已挂载的块不许被重组",
            setOf(blocks[20].key),
            log.composes.filter { (k, v) -> (before[k] ?: 0) != v }.keys,
        )
        assertEquals("已挂载块的 remembered 实例必须保住", idOf12, log.ids[blocks[12].key])
    }
}
