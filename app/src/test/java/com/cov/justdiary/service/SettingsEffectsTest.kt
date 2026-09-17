package com.cov.justdiary.service

import com.cov.justdiary.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置项副作用测试。
 *
 * 这层把"改一个设置会连带发生什么"从散落的 setter 里收成一个纯函数，
 * 于是可以逐条断言 —— 尤其是"开启自动定位才申请权限与回填、关闭不申请"这类不对称行为。
 */
class SettingsEffectsTest {

    private val base = AppSettings()

    @Test
    fun `no change means no effect`() {
        assertTrue(SettingsEffects.effectsOf(base, base).isEmpty())
        assertFalse(SettingsEffects.affectsUi(base, base))
    }

    @Test
    fun `changing the day start hour recomputes day keys and refreshes`() {
        val effects = SettingsEffects.effectsOf(base, base.copy(dayStartHour = 3))
        assertEquals(listOf(SettingsEffect.RecomputeDayKeys, SettingsEffect.RefreshUi), effects)
    }

    @Test
    fun `changing the theme applies night mode and refreshes`() {
        val effects = SettingsEffects.effectsOf(base, base.copy(themeMode = AppSettings.THEME_DARK))
        assertEquals(listOf(SettingsEffect.ApplyNightMode, SettingsEffect.RefreshUi), effects)
    }

    @Test
    fun `changing the language switches language and rearms the reminder`() {
        val effects = SettingsEffects.effectsOf(base, base.copy(appLanguage = AppSettings.LANGUAGE_EN))
        // 语言顺序：先切语言、再重排提醒（通知文案要跟着语言）、最后刷新界面
        assertEquals(
            listOf(SettingsEffect.SwitchLanguage, SettingsEffect.RearmReminder, SettingsEffect.RefreshUi),
            effects,
        )
    }

    @Test
    fun `turning auto location on requests permission and backfills`() {
        val off = base.copy(autoLoc = false)
        val effects = SettingsEffects.effectsOf(off, off.copy(autoLoc = true))
        assertEquals(
            listOf(SettingsEffect.RequestLocationPermission, SettingsEffect.BackfillRegions),
            effects,
        )
    }

    @Test
    fun `turning auto location off does not touch permissions or data`() {
        val effects = SettingsEffects.effectsOf(base, base.copy(autoLoc = false))
        assertTrue("关闭自动定位不需要申请权限", SettingsEffect.RequestLocationPermission !in effects)
        assertTrue("关闭自动定位不需要回填", SettingsEffect.BackfillRegions !in effects)
    }

    @Test
    fun `changing any reminder field rearms`() {
        assertTrue(
            SettingsEffect.RearmReminder in
                SettingsEffects.effectsOf(base, base.copy(remindEnabled = false)),
        )
        assertTrue(
            SettingsEffect.RearmReminder in
                SettingsEffects.effectsOf(base, base.copy(remindHour = 7)),
        )
        assertTrue(
            SettingsEffect.RearmReminder in
                SettingsEffects.effectsOf(base, base.copy(remindMinute = 30)),
        )
    }

    @Test
    fun `changing the week start only refreshes the ui`() {
        val effects = SettingsEffects.effectsOf(base, base.copy(weekStart = AppSettings.WEEK_START_SUNDAY))
        assertEquals(listOf(SettingsEffect.RefreshUi), effects)
    }

    @Test
    fun `toggling dynamic color refreshes the ui`() {
        val effects = SettingsEffects.effectsOf(base, base.copy(dynamicColor = false))
        assertEquals(listOf(SettingsEffect.RefreshUi), effects)
    }

    @Test
    fun `several changes at once produce the union in a stable order`() {
        val changed = base.copy(
            dayStartHour = 6,
            themeMode = AppSettings.THEME_LIGHT,
            appLanguage = AppSettings.LANGUAGE_ZH,
            autoLoc = false,
        )
        val enabled = changed.copy(autoLoc = true)
        val effects = SettingsEffects.effectsOf(changed, enabled)
        assertEquals(listOf(SettingsEffect.RequestLocationPermission, SettingsEffect.BackfillRegions), effects)

        val withTheme = enabled.copy(themeMode = AppSettings.THEME_DARK)
        assertEquals(
            listOf(SettingsEffect.ApplyNightMode, SettingsEffect.RefreshUi),
            SettingsEffects.effectsOf(enabled, withTheme),
        )
    }

    @Test
    fun `night mode mapping covers the three modes including invalid values`() {
        assertEquals(SettingsEffects.NightMode.Light, SettingsEffects.nightModeFor(AppSettings.THEME_LIGHT))
        assertEquals(SettingsEffects.NightMode.Dark, SettingsEffects.nightModeFor(AppSettings.THEME_DARK))
        assertEquals(SettingsEffects.NightMode.FollowSystem, SettingsEffects.nightModeFor(AppSettings.THEME_SYSTEM))
        assertEquals("非法值按跟随系统处理", SettingsEffects.NightMode.FollowSystem, SettingsEffects.nightModeFor("neon"))
    }

    @Test
    fun `locale tag follows the app language setting`() {
        assertEquals("zh-CN", SettingsEffects.localeTagFor(AppSettings.LANGUAGE_ZH))
        assertEquals("en", SettingsEffects.localeTagFor(AppSettings.LANGUAGE_EN))
        assertEquals(null, SettingsEffects.localeTagFor(AppSettings.LANGUAGE_SYSTEM))
    }

    @Test
    fun `allow history edit only triggers a refresh`() {
        val effects = SettingsEffects.effectsOf(base, base.copy(allowHistoryEdit = true))
        assertEquals(listOf(SettingsEffect.RefreshUi), effects)
    }
}
