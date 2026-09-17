package com.cov.justdiary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调试开关的判定测试。
 *
 * 这两个开关只在"动画卡顿排查"时用得到，一旦判定写错就会**静默失效**
 * （开关打了、动画没放慢，然后你会以为是动画本身的问题），所以按纯函数逐条钉住。
 */
class DebugFlagsTest {

    @Test
    fun `extra turns the slow morph on`() {
        assertTrue(DebugFlags.resolve(slowMorphExtra = true, morphLogExtra = false).slowMorph)
    }

    @Test
    fun `environment variable works too`() {
        assertTrue(
            DebugFlags.resolve(
                slowMorphExtra = false,
                morphLogExtra = false,
                env = mapOf(DebugFlags.ENV_SLOW_MORPH to "1"),
            ).slowMorph,
        )
    }

    @Test
    fun `any other env value does not turn it on`() {
        assertFalse(
            DebugFlags.resolve(
                slowMorphExtra = false,
                morphLogExtra = false,
                env = mapOf(DebugFlags.ENV_SLOW_MORPH to "true"),
            ).slowMorph,
        )
    }

    @Test
    fun `morph log is extra only`() {
        assertTrue(DebugFlags.resolve(false, true).morphLog)
        assertFalse(
            DebugFlags.resolve(
                slowMorphExtra = false,
                morphLogExtra = false,
                env = mapOf(DebugFlags.ENV_SLOW_MORPH to "1"),
            ).morphLog,
        )
    }

    @Test
    fun `defaults are both off`() {
        val value = DebugFlags.resolve(slowMorphExtra = false, morphLogExtra = false)
        assertEquals(DebugFlags.Value(slowMorph = false, morphLog = false), value)
    }

    @Test
    fun `slow morph duration matches iOS`() {
        assertEquals(3_000, DebugFlags.SLOW_MORPH_DURATION_MS)
    }
}
