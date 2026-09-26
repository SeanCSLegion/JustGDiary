package com.cov.justdiary.data

import com.cov.justdiary.model.AppSettings

/**
 * 农历（与干支）是否显示。
 *
 * **只由语言决定**：农历是中文语境的日期表达，英文界面下显示「廿六 / 丙午年」既读不懂
 * 也没有信息量，还会把日格挤成两行（规范 §4.4 / 01 §2.2）。
 *
 * 抽成纯函数是因为它有两条容易混的输入：应用内语言可以是"跟随系统"，
 * 此时要看**系统解析出来的**语言，而不是那个 `"system"` 字面量。
 */
object LunarVisibility {

    /** @param appLanguage [AppSettings.appLanguage]；@param systemLanguage 系统当前语言（如 `Locale.getDefault().language`）。 */
    fun showsLunar(appLanguage: String, systemLanguage: String): Boolean {
        val effective = if (appLanguage == AppSettings.LANGUAGE_SYSTEM) systemLanguage else appLanguage
        return effective.startsWith("zh", ignoreCase = true)
    }
}
