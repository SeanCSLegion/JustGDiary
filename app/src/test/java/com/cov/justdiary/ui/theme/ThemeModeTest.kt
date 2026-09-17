package com.cov.justdiary.ui.theme

import com.cov.justdiary.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题模式映射测试。三个取值的行为必须与 iOS 的 `preferredColorScheme` 一致，
 * 且非法值要按"跟随系统"兜住（与 `AppSettings` 的取值校验同一套规则）。
 */
class ThemeModeTest {

    @Test
    fun `explicit modes ignore the system setting`() {
        assertFalse(darkThemeFor(AppSettings.THEME_LIGHT, systemInDark = true))
        assertFalse(darkThemeFor(AppSettings.THEME_LIGHT, systemInDark = false))
        assertTrue(darkThemeFor(AppSettings.THEME_DARK, systemInDark = true))
        assertTrue(darkThemeFor(AppSettings.THEME_DARK, systemInDark = false))
    }

    @Test
    fun `system mode follows the system setting`() {
        assertTrue(darkThemeFor(AppSettings.THEME_SYSTEM, systemInDark = true))
        assertFalse(darkThemeFor(AppSettings.THEME_SYSTEM, systemInDark = false))
    }

    @Test
    fun `unknown mode falls back to system`() {
        assertTrue(darkThemeFor("neon", systemInDark = true))
        assertFalse(darkThemeFor("", systemInDark = false))
    }
}
